package com.nekyia.bountyfulWoodwork;

import com.nekyia.bountyfulWoodwork.TreeRegistry.TreePart;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Tells players who have the ColorfulLeaves mod which colour the leaves of each tree
 * have, chunk by chunk as their client gets the chunks. The colours come from
 * config.yml, by tree type: one colour, one of several, or a fade over the crown.
 * Players without the mod are never sent anything.
 */
final class LeafColors implements Listener {

    /** The mod's channel; the format is described in the mod's ChunkColors. */
    static final String CHANNEL = "colorfulleaves:chunk";
    private static final int VERSION = 1;
    private static final int Y_OFFSET = 2048;
    /** The steps a fade is cut into: smooth to the eye, and few colours to send. */
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
    /** Chunks whose trees changed, sent together once the leaves of a felled tree are gone. */
    private final Set<Chunk> pending = new HashSet<>();
    /** The crown each faded tree was last sent with, to notice when more of it has loaded. */
    private final Map<UUID, Crown> crowns = new HashMap<>();

    LeafColors(JavaPlugin plugin, TreeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** Rereads leaf-colors and shows everyone with the mod the new colours. */
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
        crowns.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getListeningPluginChannels().contains(CHANNEL)) {
                player.getSentChunks().forEach(chunk -> player.sendPluginMessage(plugin, CHANNEL, payload(chunk)));
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
     * The trees of a chunk changed: everyone with the mod who has the chunk hears of it -
     * a little later, so the leaves of a felled tree keep their colour while they fall.
     */
    void changed(Chunk chunk) {
        if (pending.add(chunk) && pending.size() == 1) {
            plugin.getServer().getScheduler().runTaskLater(plugin, this::sendPending,
                    plugin.getConfig().getInt("felling.leaf-decay-ticks", 40) + 1L);
        }
    }

    private void sendPending() {
        // A copy: working out a fade can find more chunks to send.
        List<Chunk> chunks = List.copyOf(pending);
        pending.clear();
        for (Chunk chunk : chunks) {
            if (!chunk.isLoaded()) {
                continue;
            }
            byte[] payload = null;
            for (Player player : chunk.getPlayersSeeingChunk()) {
                if (player.getListeningPluginChannels().contains(CHANNEL)) {
                    payload = payload != null ? payload : payload(chunk);
                    player.sendPluginMessage(plugin, CHANNEL, payload);
                }
            }
        }
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
        if (player.isOnline() && player.getListeningPluginChannels().contains(CHANNEL)) {
            Map<Integer, List<Integer>> leaves = leaves(chunk);
            if (!leaves.isEmpty()) {
                player.sendPluginMessage(plugin, CHANNEL, encode(chunk, leaves));
            }
        }
    }

    private byte[] payload(Chunk chunk) {
        return encode(chunk, leaves(chunk));
    }

    /** Colour -> the leaves in this chunk that have it, packed as the mod reads them. */
    private Map<Integer, List<Integer>> leaves(Chunk chunk) {
        Map<Integer, List<Integer>> byColor = new HashMap<>();
        World world = chunk.getWorld();
        for (TreePart tree : registry.partsIn(world, chunk.getChunkKey())) {
            Paint paint = paintOf(tree);
            if (paint == null) {
                continue;
            }
            Crown crown = paint instanceof Fade ? crown(world, tree, chunk) : null;
            tree.blocks().forEach((key, material) -> {
                int x = BlockKeys.x(key);
                int y = BlockKeys.y(key);
                int z = BlockKeys.z(key);
                // Only leaves still standing where the tree grew them.
                if (!TINTED.contains(material) || chunk.getBlock(x & 15, y, z & 15).getType() != material) {
                    return;
                }
                int color = switch (paint) {
                    // Drawn from the tree's id, so each tree keeps its colour.
                    case Solid solid -> solid.options()[Math.floorMod(tree.id().hashCode(), solid.options().length)]
                            | (solid.bright() ? BRIGHT : 0);
                    case Fade fade -> fade(fade, Objects.requireNonNull(crown), x, y, z) | (fade.bright() ? BRIGHT : 0);
                };
                byColor.computeIfAbsent(color, ignored -> new ArrayList<>()).add((x & 15) | (z & 15) << 4 | (y + Y_OFFSET) << 8);
            });
        }
        return byColor;
    }

    private @Nullable Paint paintOf(TreePart tree) {
        Paint paint = paints.get(tree.type());
        return paint != null ? paint : paints.get(TreeRegistry.kind(tree.type()));
    }

    /**
     * How far the tree's leaves reach, as far as its chunks are loaded. Once more of it
     * has loaded, its other chunks are sent again, so that the fade fits together.
     */
    private Crown crown(World world, TreePart tree, Chunk sending) {
        List<long[]> leaves = new ArrayList<>();
        for (TreePart part : registry.loadedPartsOf(world, tree)) {
            part.blocks().forEach((key, material) -> {
                if (Tag.LEAVES.isTagged(material)) {
                    leaves.add(new long[] {BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key)});
                }
            });
        }
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
        Crown crown = new Crown(bottom, top, middleX, middleZ, radius);

        Crown before = crowns.put(tree.id(), crown);
        if (before != null && !before.equals(crown)) {
            for (long key : tree.chunks()) {
                int chunkX = (int) key;
                int chunkZ = (int) (key >> 32);
                if (world.isChunkLoaded(chunkX, chunkZ) && (chunkX != sending.getX() || chunkZ != sending.getZ())) {
                    changed(world.getChunkAt(chunkX, chunkZ));
                }
            }
        }
        return crown;
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

    private static byte[] encode(Chunk chunk, Map<Integer, List<Integer>> leaves) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeInt(chunk.getX());
            out.writeInt(chunk.getZ());
            out.writeInt(leaves.size());
            for (Map.Entry<Integer, List<Integer>> group : leaves.entrySet()) {
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
}
