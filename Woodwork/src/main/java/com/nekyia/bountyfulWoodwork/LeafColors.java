package com.nekyia.bountyfulWoodwork;

import com.nekyia.bountyfulWoodwork.TreeRegistry.TreePart;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The leaf colours of trees, kept as data in each chunk and shown to players who have the
 * ColorfulLeaves mod. A tree gets its colour once, from config.yml by its type - one
 * colour, one of several, or a fade over the crown - and keeps it from then on, whatever
 * the config says later. Colours already in a chunk, like those trees from the creative
 * server bring along, are never painted over. Players without the mod are never sent
 * anything.
 */
final class LeafColors implements Listener {

    /** The mod's channel; the format is described in the mod's ChunkColors. */
    static final String CHANNEL = "colorfulleaves:chunk";
    /**
     * Where a chunk keeps its colours, which the map renderer reads too: the mod's payload
     * without chunkX and chunkZ. The contract is heroic-map-renderer's
     * docs/benutzung/laubfarben.md - change it only together with the map and the mod.
     */
    static final NamespacedKey STORED = Objects.requireNonNull(NamespacedKey.fromString("heroicmap:leaf_colors"));
    private static final int VERSION = 1;
    private static final int Y_OFFSET = 2048;
    /** The steps a fade is cut into: smooth to the eye, and few colours to store. */
    private static final int FADE_STEPS = 32;
    /** Bit 24 of a colour: the mod draws these leaves on their lighter texture. */
    private static final int BRIGHT = 1 << 24;

    /**
     * The leaves the mod can colour - the others have their colour in the texture. Bushes
     * in a crown keep their own green.
     */
    private static final Set<Material> TINTED = EnumSet.of(Material.OAK_LEAVES, Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES);

    /** How the leaves of a tree type are coloured. */
    private sealed interface Paint permits Solid, Fade {
    }

    /**
     * One of these colours per tree, 0xRRGGBB, the same one every time. Bright ones are
     * drawn on the leaves' lighter texture, so that light colours do not come out greyish.
     */
    private record Solid(int[] options, boolean bright) implements Paint {
    }

    /** From the first colour at the bottom of the crown - or its middle - to the last at the top, or its edge. */
    private record Fade(int[] stops, boolean outwards, boolean bright) implements Paint {
    }

    /** How far a tree's leaves reach, which a fade is spread over. */
    private record Crown(int bottom, int top, double middleX, double middleZ, double radius) {
    }

    private final JavaPlugin plugin;
    private final TreeRegistry registry;
    private Map<String, Paint> paints = Map.of();
    /** Chunks to bring up to date, together once the leaves of a felled tree are gone. */
    private final Set<Chunk> pending = new HashSet<>();
    /**
     * While a tree is coloured, its other chunks are loaded; they are left for when a
     * player loads them, else colouring one tree would load the next, through the forest.
     */
    private boolean colouring;

    LeafColors(JavaPlugin plugin, TreeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /**
     * Rereads leaf-colors. Trees that have their colours keep them; the loaded chunks are
     * looked over for trees that only now have a colour.
     */
    void reload() {
        Map<String, Paint> byType = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("leaf-colors");
        if (section != null) {
            for (String type : section.getKeys(false)) {
                Paint paint = paint(section, type);
                if (paint != null) {
                    byType.put(type.toLowerCase(Locale.ROOT), paint);
                }
            }
        }
        paints = byType;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                look(chunk);
            }
        }
    }

    private @Nullable Paint paint(ConfigurationSection section, String type) {
        ConfigurationSection options = section.getConfigurationSection(type);
        if (options == null) {
            int[] colours = colors(type, listOrOne(section, type));
            return colours.length == 0 ? null : new Solid(colours, false);
        }
        boolean bright = options.getBoolean("bright", false);
        if (!options.contains("fade")) {
            int[] colours = colors(type, listOrOne(options, options.contains("colours") ? "colours" : "colour"));
            if (colours.length == 0) {
                plugin.getLogger().warning("leaf-colors." + type + " needs a colour, colours or a fade.");
                return null;
            }
            return new Solid(colours, bright);
        }
        int[] stops = colors(type, options.getStringList("fade"));
        String direction = options.getString("direction", "up").toLowerCase(Locale.ROOT);
        if (stops.length < 2 || !direction.equals("up") && !direction.equals("out")) {
            plugin.getLogger().warning("leaf-colors." + type + " needs a fade of at least two colours,"
                    + " and a direction of up or out.");
            return null;
        }
        return new Fade(stops, direction.equals("out"), bright);
    }

    /** A list of colours, or a single one. */
    private static List<String> listOrOne(ConfigurationSection section, String key) {
        return section.isList(key) ? section.getStringList(key)
                : List.of(Objects.requireNonNullElse(section.getString(key), ""));
    }

    private int[] colors(String type, List<String> values) {
        return values.stream()
                .map(value -> {
                    TextColor color = TextColor.fromHexString(value);
                    if (color == null) {
                        plugin.getLogger().warning("leaf-colors." + type + ": '" + value
                                + "' is not a colour like #d9a42b.");
                    }
                    return color;
                })
                .filter(Objects::nonNull)
                .mapToInt(TextColor::value)
                .toArray();
    }

    /**
     * The trees or leaves of a chunk may have changed: its colours are brought up to date -
     * a little later, so the leaves of a felled tree keep their colour while they fall.
     */
    void changed(Chunk chunk) {
        if (!colouring && pending.add(chunk) && pending.size() == 1) {
            plugin.getServer().getScheduler().runTaskLater(plugin, this::updatePending,
                    plugin.getConfig().getInt("felling.leaf-decay-ticks", 40) + 1L);
        }
    }

    /** Leaves may have gone, or trees come, while the chunk was loaded before. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        look(event.getChunk());
    }

    /** A chunk with colours or trees is brought up to date. */
    private void look(Chunk chunk) {
        if (chunk.getPersistentDataContainer().has(STORED)
                || !registry.partsIn(chunk.getWorld(), chunk.getChunkKey()).isEmpty()) {
            changed(chunk);
        }
    }

    private void updatePending() {
        // A copy: colouring a tree loads its other chunks, which may come back here.
        List<Chunk> chunks = List.copyOf(pending);
        pending.clear();
        Set<Chunk> updated = new LinkedHashSet<>();
        for (Chunk chunk : chunks) {
            if (chunk.isLoaded()) {
                update(chunk, updated);
            }
        }
        for (Chunk chunk : updated) {
            byte[] payload = null;
            for (Player player : chunk.getPlayersSeeingChunk()) {
                if (player.getListeningPluginChannels().contains(CHANNEL)) {
                    payload = payload != null ? payload : payload(chunk.getX(), chunk.getZ(), stored(chunk));
                    player.sendPluginMessage(plugin, CHANNEL, payload);
                }
            }
        }
    }

    /**
     * Drops the colours of leaves that are gone, and colours the trees here that have none
     * yet. Every chunk whose colours changed goes into updated.
     */
    private void update(Chunk chunk, Set<Chunk> updated) {
        Map<Integer, Integer> colors = read(chunk);
        if (colors == null) {
            return;
        }
        if (colors.keySet().removeIf(position -> !Tag.LEAVES.isTagged(block(chunk, position)))) {
            write(chunk, colors);
            updated.add(chunk);
        }
        World world = chunk.getWorld();
        for (TreePart part : List.copyOf(registry.partsIn(world, chunk.getChunkKey()))) {
            if (uncoloured(chunk, part, colors)) {
                colour(world, part, updated);
            }
        }
    }

    /** A tree with a colour in the config, standing leaves to show it, and none coloured yet. */
    private boolean uncoloured(Chunk chunk, TreePart part, Map<Integer, Integer> colors) {
        if (paintOf(part) == null) {
            return false;
        }
        boolean leaves = false;
        for (Map.Entry<Long, Material> block : part.blocks().entrySet()) {
            int position = position(block.getKey());
            if (colors.containsKey(position)) {
                return false;
            }
            leaves |= TINTED.contains(block.getValue()) && block(chunk, position) == block.getValue();
        }
        return leaves;
    }

    /** Gives the whole tree its colours, in all of its chunks at once, so that a fade fits together. */
    private void colour(World world, TreePart tree, Set<Chunk> updated) {
        Paint paint = Objects.requireNonNull(paintOf(tree));
        Map<Long, Material> blocks;
        colouring = true;
        try {
            blocks = registry.blocksOf(world, tree);
        } finally {
            colouring = false;
        }
        Crown crown = paint instanceof Fade ? crown(blocks) : null;

        Map<Long, Map<Integer, Integer>> byChunk = new HashMap<>();
        blocks.forEach((key, material) -> {
            int x = BlockKeys.x(key);
            int y = BlockKeys.y(key);
            int z = BlockKeys.z(key);
            // Only leaves still standing where the tree grew them.
            if (!TINTED.contains(material) || world.getBlockAt(x, y, z).getType() != material) {
                return;
            }
            int color = switch (paint) {
                // Drawn from the tree's id, so each tree keeps its colour.
                case Solid solid -> solid.options()[Math.floorMod(tree.id().hashCode(), solid.options().length)]
                        | (solid.bright() ? BRIGHT : 0);
                case Fade fade -> fade(fade, Objects.requireNonNull(crown), x, y, z) | (fade.bright() ? BRIGHT : 0);
            };
            byChunk.computeIfAbsent(Chunk.getChunkKey(x >> 4, z >> 4), ignored -> new HashMap<>())
                    .put(position(key), color);
        });
        byChunk.forEach((chunkKey, leaves) -> {
            Chunk chunk = world.getChunkAt((int) (long) chunkKey, (int) (chunkKey >> 32));
            Map<Integer, Integer> colors = read(chunk);
            if (colors == null) {
                return;
            }
            Map<Integer, Integer> before = Map.copyOf(colors);
            // Colours already there stay, wherever they came from.
            leaves.forEach(colors::putIfAbsent);
            if (!colors.equals(before)) {
                write(chunk, colors);
                updated.add(chunk);
            }
        });
    }

    private @Nullable Paint paintOf(TreePart tree) {
        Paint paint = paints.get(tree.type());
        return paint != null ? paint : paints.get(TreeRegistry.kind(tree.type()));
    }

    /** How far the tree's leaves reach. */
    private static Crown crown(Map<Long, Material> blocks) {
        List<long[]> leaves = new ArrayList<>();
        blocks.forEach((key, material) -> {
            if (Tag.LEAVES.isTagged(material)) {
                leaves.add(new long[] {BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key)});
            }
        });
        int bottom = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        double sumX = 0;
        double sumZ = 0;
        for (long[] leaf : leaves) {
            bottom = (int) Math.min(bottom, leaf[1]);
            top = (int) Math.max(top, leaf[1]);
            sumX += leaf[0] + 0.5;
            sumZ += leaf[2] + 0.5;
        }
        double middleX = sumX / Math.max(1, leaves.size());
        double middleZ = sumZ / Math.max(1, leaves.size());
        double radius = 0;
        for (long[] leaf : leaves) {
            radius = Math.max(radius, Math.hypot(leaf[0] + 0.5 - middleX, leaf[2] + 0.5 - middleZ));
        }
        return new Crown(bottom, top, middleX, middleZ, radius);
    }

    /** The colour of the fade at this leaf. */
    private static int fade(Fade fade, Crown crown, int x, int y, int z) {
        double share = fade.outwards()
                ? Math.hypot(x + 0.5 - crown.middleX(), z + 0.5 - crown.middleZ()) / Math.max(crown.radius(), 1)
                : (y - crown.bottom()) / (double) Math.max(crown.top() - crown.bottom(), 1);
        share = Math.round(Math.clamp(share, 0, 1) * FADE_STEPS) / (double) FADE_STEPS;
        int[] stops = fade.stops();
        double at = share * (stops.length - 1);
        int from = Math.min((int) at, stops.length - 2);
        return mix(stops[from], stops[from + 1], at - from);
    }

    /** Between two colours, channel by channel. */
    private static int mix(int from, int to, double share) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = from >> shift & 255;
            int b = to >> shift & 255;
            color |= (int) Math.round(a + (b - a) * share) << shift;
        }
        return color;
    }

    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent event) {
        sendIfColoured(event.getPlayer(), event.getChunk());
    }

    /** The client names its channels only after the first chunks went out. */
    @EventHandler
    public void onModFound(PlayerRegisterChannelEvent event) {
        if (event.getChannel().equals(CHANNEL)) {
            Player player = event.getPlayer();
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> player.getSentChunks().forEach(chunk -> sendIfColoured(player, chunk)));
        }
    }

    /** A chunk the client has just got holds no colours yet, so one without any needs nothing. */
    private void sendIfColoured(Player player, Chunk chunk) {
        byte[] stored = stored(chunk);
        if (stored != null && player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
            player.sendPluginMessage(plugin, CHANNEL, payload(chunk.getX(), chunk.getZ(), stored));
        }
    }

    /** The chunk's stored colours as they are, or null without any - or with some of another version. */
    private static byte @Nullable [] stored(Chunk chunk) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        byte[] bytes = data.has(STORED, PersistentDataType.BYTE_ARRAY) ? data.get(STORED, PersistentDataType.BYTE_ARRAY) : null;
        return bytes != null && bytes.length > 0 && bytes[0] == VERSION ? bytes : null;
    }

    /** Position -> colour; null when what is stored is not ours to read, and so not ours to change. */
    private static @Nullable Map<Integer, Integer> read(Chunk chunk) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (!data.has(STORED)) {
            return new HashMap<>();
        }
        return data.has(STORED, PersistentDataType.BYTE_ARRAY) ? decode(data.get(STORED, PersistentDataType.BYTE_ARRAY)) : null;
    }

    /** Only called when the colours changed: a chunk written to counts as unsaved, for the map too. */
    private static void write(Chunk chunk, Map<Integer, Integer> colors) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (colors.isEmpty()) {
            data.remove(STORED);
        } else {
            data.set(STORED, PersistentDataType.BYTE_ARRAY, encode(colors));
        }
    }

    /** What stands at a stored position; nothing outside the world's heights. */
    private static Material block(Chunk chunk, int position) {
        int y = (position >>> 8 & 4095) - Y_OFFSET;
        World world = chunk.getWorld();
        return y < world.getMinHeight() || y >= world.getMaxHeight() ? Material.AIR
                : chunk.getBlock(position & 15, y, position >>> 4 & 15).getType();
    }

    private static int position(long key) {
        return (BlockKeys.x(key) & 15) | (BlockKeys.z(key) & 15) << 4 | (BlockKeys.y(key) + Y_OFFSET) << 8;
    }

    /** Stored bytes -> position -> colour; null if they do not follow version 1. A position named twice: the last counts. */
    static @Nullable Map<Integer, Integer> decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readByte() != VERSION) {
                return null;
            }
            Map<Integer, Integer> colors = new HashMap<>();
            int groups = in.readInt();
            for (int group = 0; group < groups; group++) {
                int color = in.readInt();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    colors.put(in.readInt(), color);
                }
            }
            return in.available() == 0 && groups >= 0 ? colors : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Position -> colour, as stored: version, groups, per group colour, count and positions. */
    static byte[] encode(Map<Integer, Integer> colors) {
        Map<Integer, List<Integer>> groups = new HashMap<>();
        colors.forEach((position, color) -> groups.computeIfAbsent(color, ignored -> new ArrayList<>()).add(position));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeInt(groups.size());
            for (Map.Entry<Integer, List<Integer>> group : groups.entrySet()) {
                out.writeInt(group.getKey());
                out.writeInt(group.getValue().size());
                for (int position : group.getValue()) {
                    out.writeInt(position);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** What the mod is sent: the stored bytes with the chunk put in after the version. Without any, it takes the chunk's colours away. */
    static byte[] payload(int chunkX, int chunkZ, byte @Nullable [] stored) {
        byte[] colors = stored != null ? stored : encode(Map.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(colors[0]);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.write(colors, 1, colors.length - 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
