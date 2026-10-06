package com.nekyia.treearchive;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Shows a forest without planting it. Every marker block around the place a preview is
 * started becomes a tree from the archive, but only in the eyes of those looking: the
 * blocks are sent to their clients and nowhere else, and the world stays as it was.
 *
 * <p>A preview is a session others can join through a shared link: everyone in it sees
 * the same trees, and what changes, changes for all of them. Which tree a marker gets
 * depends on the seed and that marker's place alone, so markers coming and going never
 * reshuffle the rest. Asking again draws a new seed and shuffles the trees.
 *
 * <p>The markers are real blocks, and the preview follows them: one placed - by hand or
 * by brush - grows its tree, one broken takes it away. Hitting a ghost trunk or branch
 * takes the marker, and so the tree, away. Foliage - leaves, moss, bushes - is left
 * alone: trees overlap there, and a hit in the crown is easily a slip. For those who
 * see them, ghosts fill their spots like any block: a block placed against one goes in
 * front of the side aimed at, and none goes into one. Ghosts never change for real blocks.
 *
 * <p>Whatever sends a viewer the real blocks over the ghosts - WorldEdit refreshing the
 * chunks it changed, Axiom, a block update - is watched for on their connection, and the
 * ghosts are sent again right after it.
 *
 * <p>Trees written with a colour, {@code beech*#d98c2b}, show their leaves in it to the
 * viewers who have the ColorfulLeaves mod.
 */
final class TreePreview implements Listener {

    /** What a preview is asked for besides its spec; part of a shared link. */
    record Options(boolean continuous, int limit, boolean bypassTerrain) {

        static final Options NONE = new Options(false, 0, false);

        /** As they are written after the spec. */
        String written() {
            return (continuous ? " --continuous" : "") + (limit > 0 ? " --limit " + limit : "")
                    + (bypassTerrain ? " --bypassterrain" : "");
        }
    }

    /** One preview, and whoever is looking at it. */
    private static final class Session {
        /** Spec, seed and place, as a shared link carries them; also its name here. */
        final String key;
        final PreviewSpec spec;
        final long seed;
        final UUID world;
        final int fromX;
        final int toX;
        final int fromZ;
        final int toZ;
        final int minY;
        final int maxY;
        /** Keeps growing trees on chunks loaded later, anywhere, until the limit. */
        final boolean continuous;
        /** The most trees this preview grows. */
        final int limit;
        /** Grows every tree, whatever stands in its way. */
        final boolean bypassTerrain;
        /** How each coloured tree is painted, and the crown a fade is spread over, by its marker. */
        final Map<Long, LeafColours.Paint> paints = new HashMap<>();
        final Map<Long, LeafColours.Crown> crowns = new HashMap<>();
        /** Chunks already searched for markers. */
        final Set<Long> scanned = new HashSet<>();
        final Set<UUID> viewers = new LinkedHashSet<>();
        /** Every ghost block, by position, and the marker whose tree it belongs to. */
        // Concurrent, as the connections look into it for what overwrites the ghosts.
        final Map<Long, BlockData> ghosts = new ConcurrentHashMap<>();
        final Map<Long, Long> owners = new HashMap<>();
        /** The positions of each tree, by its marker. */
        final Map<Long, long[]> trees = new HashMap<>();
        /** Ghost positions per chunk, to send them again when the chunk is sent again. */
        final Map<Long, Set<Long>> byChunk = new ConcurrentHashMap<>();
        @Nullable BukkitTask task;

        Session(String key, PreviewSpec spec, long seed, UUID world, int x, int z, int radius,
                int minY, int maxY, boolean continuous, int limit, boolean bypassTerrain) {
            this.key = key;
            this.continuous = continuous;
            this.limit = limit;
            this.bypassTerrain = bypassTerrain;
            this.spec = spec;
            this.seed = seed;
            this.world = world;
            this.fromX = x - radius;
            this.toX = x + radius;
            this.fromZ = z - radius;
            this.toZ = z + radius;
            this.minY = minY;
            this.maxY = maxY;
        }

        boolean covers(Block block) {
            return block.getWorld().getUID().equals(world)
                    && inArea(block.getX(), block.getZ())
                    && block.getY() >= minY && block.getY() <= maxY;
        }

        /** A continuous preview has no edge; only its limit stops it. */
        boolean inArea(int x, int z) {
            return continuous || x >= fromX && x <= toX && z >= fromZ && z <= toZ;
        }
    }

    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<UUID, Session> watching = new ConcurrentHashMap<>();

    TreePreview(JavaPlugin plugin, TreeArchive archive, TreePaster paster) {
        this.plugin = plugin;
        this.archive = archive;
        this.paster = paster;
    }

    /** A fresh seed, short enough to read in a chat link. */
    static long newSeed() {
        return ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    }

    /** What a shared link needs to join this player's preview, or null without one. */
    @Nullable String shared(Player player) {
        Session session = watching.get(player.getUniqueId());
        return session == null ? null : session.key;
    }

    /** Takes a player out of their preview, putting back what the world really holds. */
    void clear(Player player) {
        Session session = watching.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        leave(session, player.getUniqueId());
        World world = player.getServer().getWorld(session.world);
        if (world != null && player.getWorld().equals(world)) {
            Map<Position, BlockData> real = new HashMap<>();
            session.ghosts.keySet().forEach(key -> real.put(position(key), realAt(world, key)));
            player.sendMultiBlockChange(real);
            if (!session.paints.isEmpty() && player.getListeningPluginChannels().contains(LeafColours.CHANNEL)) {
                session.byChunk.keySet().forEach(chunk -> player.sendPluginMessage(plugin, LeafColours.CHANNEL,
                        LeafColours.encode((int) (chunk >> 32), (int) (long) chunk, Map.of())));
            }
        }
    }

    /**
     * Shows a preview around the given place - where the player stands when they start
     * one, where it was started for a shared one, and then they are taken there when
     * they are elsewhere. A preview already running is joined, not grown again.
     */
    void show(Player player, String text, PreviewSpec spec, long seed, World world, int centreX, int centreZ,
              Options options) {
        boolean continuous = options.continuous();
        String key = text + options.written() + " --seed " + seed
                + " --at " + world.getKey().asString() + "," + centreX + "," + centreZ;
        clear(player);
        int radius = Math.max(1, config("radius", 150));
        boolean moved = !player.getWorld().equals(world)
                || Math.abs(player.getLocation().getBlockX() - centreX) > radius
                || Math.abs(player.getLocation().getBlockZ() - centreZ) > radius;
        if (moved) {
            player.teleport(new Location(world, centreX + 0.5,
                    world.getHighestBlockYAt(centreX, centreZ) + 1, centreZ + 0.5));
        }

        Session running = sessions.get(key);
        if (running != null) {
            running.viewers.add(player.getUniqueId());
            watch(player, running);
            Map<Position, BlockData> all = new HashMap<>();
            running.ghosts.forEach((position, ghost) -> all.put(position(position), ghost));
            player.sendMultiBlockChange(all);
            sendColours(running, player, running.byChunk.keySet());
            player.sendMessage(Component.text("Joined the preview " + text + " (" + running.trees.size()
                    + " trees).", NamedTextColor.GREEN));
            return;
        }

        Session session = new Session(key, spec, seed, world.getUID(), centreX, centreZ, radius,
                Math.max(world.getMinHeight(), config("min-y", -64)),
                Math.min(world.getMaxHeight() - 1, config("max-y", 160)), continuous,
                options.limit() > 0 ? options.limit() : Math.max(1, config("max-trees", 500)),
                options.bypassTerrain());
        sessions.put(key, session);
        session.viewers.add(player.getUniqueId());
        watch(player, session);
        player.sendMessage(Component.text("Growing the preview " + text + " around " + centreX + ", "
                + centreZ + " (seed " + seed + "). Hit a trunk to take its marker away.", NamedTextColor.GREEN));

        ArrayDeque<long[]> chunks = new ArrayDeque<>();
        if (!continuous) {
            for (int chunkX = session.fromX >> 4; chunkX <= session.toX >> 4; chunkX++) {
                for (int chunkZ = session.fromZ >> 4; chunkZ <= session.toZ >> 4; chunkZ++) {
                    chunks.add(new long[] {chunkX, chunkZ});
                }
            }
        } else if (!moved) {
            // Everything this player already sees - with a long view distance far more than
            // the radius - nearest first, so the limit fills from here outwards. Chunks sent
            // later, and all of them after a teleport, come through onChunkSent.
            int ownX = centreX >> 4;
            int ownZ = centreZ >> 4;
            player.getSentChunks().stream()
                    .filter(chunk -> chunk.getWorld().equals(world))
                    .map(chunk -> new long[] {chunk.getX(), chunk.getZ()})
                    .sorted(Comparator.comparingLong(chunk -> (chunk[0] - ownX) * (chunk[0] - ownX)
                            + (chunk[1] - ownZ) * (chunk[1] - ownZ)))
                    .forEach(chunks::add);
        }
        ArrayDeque<Block> markers = new ArrayDeque<>();
        // A guard against markers that are not markers - stone, dirt - which would ask
        // for a tree on every block around.
        int max = session.limit;
        int[] counts = {0, 0};
        long budget = Math.max(1, plugin.getConfig().getLong("preview.tick-budget-ms", 5)) * 1_000_000L;

        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + budget;
            do {
                // Continuous grows what it has found before searching on, so trees show up
                // while the search still goes; otherwise every marker is counted first.
                Block marker = session.continuous || chunks.isEmpty() ? markers.poll() : null;
                if (marker != null) {
                    if (session.trees.size() >= max) {
                        markers.clear();
                        chunks.clear();
                    } else if (grow(session, marker)) {
                        counts[0]++;
                    } else {
                        counts[1]++;
                    }
                    continue;
                }
                long[] chunk = chunks.poll();
                if (chunk == null) {
                    player.sendMessage(Component.text("Preview ready: " + counts[0] + " trees, "
                            + counts[1] + " markers without room."
                            + (session.continuous ? " More grow as chunks load, up to " + max + "." : ""),
                            NamedTextColor.GREEN));
                    Objects.requireNonNull(session.task).cancel();
                    session.task = null;
                    return;
                }
                // Already searched when it was sent while this search was going.
                if (!session.scanned.add(chunkKey((int) chunk[0], (int) chunk[1]))) {
                    continue;
                }
                scan(world, world.getChunkAt((int) chunk[0], (int) chunk[1]).getChunkSnapshot(false, false, false),
                        session, markers, max);
                if (!session.continuous && markers.size() > max) {
                    Objects.requireNonNull(session.task).cancel();
                    session.task = null;
                    for (UUID viewer : List.copyOf(session.viewers)) {
                        Player looking = plugin.getServer().getPlayer(viewer);
                        if (looking != null) {
                            looking.sendMessage(Component.text("More than " + max + " markers around here,"
                                    + " so no preview. Pick a block that only marks trees, or give"
                                    + " a higher --limit.", NamedTextColor.RED));
                            clear(looking);
                        }
                    }
                    return;
                }
            } while (System.nanoTime() < deadline);
        }, 1, 1);
    }

    /**
     * After a brush set a marker: every preview it lies in grows its tree. Someone who is
     * not looking at a preview gets one started with the brush's own spec, if it names
     * trees, so the brush shows what it plants.
     */
    void brushed(Player player, Block marker, PreviewSpec spec, String text) {
        markerPlaced(marker);
        if (!watching.containsKey(player.getUniqueId())
                && spec.parts().stream().anyMatch(part -> part.trees() != null)) {
            Location at = player.getLocation();
            show(player, text, spec, newSeed(), marker.getWorld(), at.getBlockX(), at.getBlockZ(), Options.NONE);
        }
    }

    /** A marker was set: every preview it lies in grows its tree, for all who look. */
    private void markerPlaced(Block marker) {
        for (Session session : sessions.values()) {
            if (session.covers(marker) && session.spec.markers().contains(marker.getType())
                    && session.trees.size() < session.limit) {
                grow(session, marker);
            }
        }
    }

    /** A marker is gone: every preview it lay in takes its tree away, for all who look. */
    private void markerRemoved(Block marker) {
        long key = BlockKeys.of(marker);
        for (Session session : sessions.values()) {
            if (session.trees.containsKey(key)) {
                remove(session, marker.getWorld(), key);
            }
        }
    }

    /** Grows the tree a marker gets in this preview and shows it to everyone in it. */
    private boolean grow(Session session, Block marker) {
        long markerKey = BlockKeys.of(marker);
        // Set while the preview was still searching, and found by the search as well.
        if (session.trees.containsKey(markerKey)) {
            return true;
        }
        PreviewSpec.Part part = session.spec.partFor(marker.getType());
        if (part == null) {
            return false;
        }
        // Drawn from the seed and this marker's place alone, so every viewer - and every
        // later look - gets the same tree here, whatever other markers come and go.
        Random random = new Random(mix(session.seed ^ mix(markerKey)));
        TreeSelection trees = part.trees() == null ? TreeSelection.ALL : part.trees();
        TreeSelection.Pick pick = trees.draw(archive.entries(), random);
        TreeArchive.Entry entry = pick == null ? null : pick.entry();
        int turns = paster.turns(random);
        Clipboard clipboard = entry == null ? null : archive.clipboard(entry);
        List<TreePaster.Placement> placements = clipboard == null ? null
                : paster.plan(clipboard, marker, false, turns, session.spec.markers(), session.bypassTerrain);
        if (placements == null) {
            return false;
        }
        placements = paster.mistle(placements, pick.modifiers(), marker.getWorld(), random,
                session.bypassTerrain);

        long[] keys = new long[placements.size()];
        Map<Position, BlockData> sent = new HashMap<>();
        List<int[]> crown = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            TreePaster.Placement placement = placements.get(i);
            long key = BlockKeys.of(placement.x(), placement.y(), placement.z());
            keys[i] = key;
            BlockData data = BukkitAdapter.adapt(placement.block());
            if (Tag.LEAVES.isTagged(data.getMaterial())) {
                crown.add(new int[] {placement.x(), placement.y(), placement.z()});
            }
            // Wood takes any spot; foliage gives way to another tree's wood.
            BlockData there = session.ghosts.get(key);
            if (there != null && TreePaster.foliage(data.getMaterial()) && !TreePaster.foliage(there.getMaterial())) {
                continue;
            }
            session.ghosts.put(key, data);
            session.owners.put(key, markerKey);
            session.byChunk.computeIfAbsent(chunkKey(placement.x() >> 4, placement.z() >> 4),
                    ignored -> new HashSet<>()).add(key);
            sent.put(position(key), data);
        }
        session.trees.put(markerKey, keys);
        LeafColours.Paint paint = pick.paint();
        if (paint != null) {
            session.paints.put(markerKey, paint);
            session.crowns.put(markerKey, LeafColours.Crown.of(crown));
        }
        send(session, sent);
        if (!session.paints.isEmpty()) {
            sendColours(session, chunksOf(keys));
        }
        return true;
    }

    /** Takes one tree out of a preview, showing everyone the real world where it stood. */
    private void remove(Session session, World world, long markerKey) {
        long[] keys = session.trees.remove(markerKey);
        if (keys == null) {
            return;
        }
        Map<Position, BlockData> real = new HashMap<>();
        for (long key : keys) {
            // Where another tree has since taken the spot, that one stays.
            if (!Objects.equals(session.owners.get(key), markerKey)) {
                continue;
            }
            session.owners.remove(key);
            session.ghosts.remove(key);
            Set<Long> inChunk = session.byChunk.get(chunkKey(BlockKeys.x(key) >> 4, BlockKeys.z(key) >> 4));
            if (inChunk != null) {
                inChunk.remove(key);
            }
            real.put(position(key), realAt(world, key));
        }
        send(session, real);
        session.crowns.remove(markerKey);
        if (session.paints.remove(markerKey) != null || !session.paints.isEmpty()) {
            sendColours(session, chunksOf(keys));
        }
    }

    /** The chunks these places lie in. */
    private static Set<Long> chunksOf(long[] keys) {
        Set<Long> chunks = new HashSet<>();
        for (long key : keys) {
            chunks.add(chunkKey(BlockKeys.x(key) >> 4, BlockKeys.z(key) >> 4));
        }
        return chunks;
    }

    /** Sends these chunks' leaf colours to everyone in the preview who has the mod. */
    private void sendColours(Session session, Set<Long> chunks) {
        for (UUID viewer : session.viewers) {
            Player player = plugin.getServer().getPlayer(viewer);
            if (player != null) {
                sendColours(session, player, chunks);
            }
        }
    }

    private void sendColours(Session session, Player player, Set<Long> chunks) {
        if (session.paints.isEmpty() || !player.getListeningPluginChannels().contains(LeafColours.CHANNEL)
                || !player.getWorld().getUID().equals(session.world)) {
            return;
        }
        for (long chunk : chunks) {
            Map<Integer, List<Integer>> byColour = new HashMap<>();
            Set<Long> keys = session.byChunk.get(chunk);
            if (keys != null) {
                for (long key : keys) {
                    BlockData ghost = session.ghosts.get(key);
                    Long owner = session.owners.get(key);
                    LeafColours.Paint paint = owner == null ? null : session.paints.get(owner);
                    LeafColours.Crown crown = owner == null ? null : session.crowns.get(owner);
                    if (paint != null && crown != null && ghost != null
                            && LeafColours.TINTED.contains(ghost.getMaterial())) {
                        int x = BlockKeys.x(key);
                        int y = BlockKeys.y(key);
                        int z = BlockKeys.z(key);
                        byColour.computeIfAbsent(paint.at(crown, x, y, z), ignored -> new ArrayList<>())
                                .add(LeafColours.packed(x, y, z));
                    }
                }
            }
            player.sendPluginMessage(plugin, LeafColours.CHANNEL,
                    LeafColours.encode((int) (chunk >> 32), (int) chunk, byColour));
        }
    }

    private void send(Session session, Map<Position, BlockData> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        for (UUID viewer : session.viewers) {
            Player player = plugin.getServer().getPlayer(viewer);
            if (player != null && player.getWorld().getUID().equals(session.world)) {
                player.sendMultiBlockChange(blocks);
            }
        }
    }

    // Early, so the hit is swallowed before anything acts on the real block underneath.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        Session session = watching.get(player.getUniqueId());
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || block == null || session == null) {
            return;
        }
        long key = BlockKeys.of(block);
        BlockData ghost = session.ghosts.get(key);
        if (ghost == null) {
            return;
        }
        // The ghost is not really there; whatever is must not break in its stead.
        event.setCancelled(true);
        Long owner = session.owners.get(key);
        // Wood takes its marker away for real, for whoever hits it - this is a building server.
        if (owner != null && !TreePaster.foliage(ghost.getMaterial())) {
            Block marker = block.getWorld().getBlockAt(BlockKeys.x(owner), BlockKeys.y(owner), BlockKeys.z(owner));
            if (session.spec.markers().contains(marker.getType())) {
                marker.setType(Material.AIR);
            }
            markerRemoved(marker);
            return;
        }
        // Otherwise the server puts the real block back on this client; the ghost goes up again.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (watching.get(player.getUniqueId()) == session && session.ghosts.get(key) == ghost) {
                player.sendMultiBlockChange(Map.of(position(key), ghost));
            }
        });
    }

    /**
     * A right click on a ghost means the ghost, the way the player sees it: a block goes
     * against the side clicked. The server, seeing only air there, would put it into the
     * ghost instead, where the ghost would hide it. Against a ghost there is no room, as
     * against any block.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();
        Session session = watching.get(player.getUniqueId());
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null || session == null
                || event.getHand() == null || !session.ghosts.containsKey(BlockKeys.of(clicked))) {
            return;
        }
        event.setCancelled(true);
        Block target = clicked.getRelative(event.getBlockFace());
        Direction face = Direction.byName(event.getBlockFace().name().toLowerCase(java.util.Locale.ROOT));
        if (face == null || session.ghosts.containsKey(BlockKeys.of(target))) {
            return;
        }
        // The game's own placing, aimed at the spot in front of the ghost: orientation,
        // place events and protection all as for any block.
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        InteractionHand hand = event.getHand() == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        BlockPos spot = new BlockPos(target.getX(), target.getY(), target.getZ());
        Location point = event.getInteractionPoint();
        Vec3 aim = point == null ? Vec3.atCenterOf(spot) : new Vec3(point.getX(), point.getY(), point.getZ());
        handle.gameMode.useItemOn(handle, handle.level(), handle.getItemInHand(hand), hand,
                new BlockHitResult(aim, face, spot, false));
    }

    /**
     * A ghost fills its spot for whoever sees it: nothing goes in there, as into any block
     * - placing against the ground right beside a ghost trunk, say. The game then sends
     * the spot again, and the ghost with it.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlaceIntoGhost(BlockPlaceEvent event) {
        Session session = watching.get(event.getPlayer().getUniqueId());
        if (session != null && session.ghosts.containsKey(BlockKeys.of(event.getBlockPlaced()))) {
            event.setCancelled(true);
        }
    }

    /**
     * A marker placed by hand grows its tree - two ticks later, since the block itself
     * goes out with the next world tick, after the tasks of that tick have run.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        Block block = event.getBlockPlaced();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> markerPlaced(block), 2);
    }

    /** A marker broken by hand takes its tree away. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        markerRemoved(event.getBlock());
    }

    /** In a continuous preview, a chunk sent for the first time grows the trees of its markers. */
    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        Session session = watching.get(player.getUniqueId());
        if (session == null || !session.continuous || !session.world.equals(event.getWorld().getUID())
                || session.trees.size() >= session.limit
                || !session.scanned.add(chunkKey(event.getChunk().getX(), event.getChunk().getZ()))) {
            return;
        }
        World world = event.getWorld();
        ChunkSnapshot snapshot = event.getChunk().getChunkSnapshot(false, false, false);
        // A tick later, so the ghosts land on the chunk and not before it.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (watching.get(player.getUniqueId()) != session) {
                return;
            }
            ArrayDeque<Block> markers = new ArrayDeque<>();
            scan(world, snapshot, session, markers, session.limit - session.trees.size());
            for (Block marker : markers) {
                if (session.trees.size() >= session.limit) {
                    break;
                }
                grow(session, marker);
            }
        });
    }

    /** Makes a player a viewer, and keeps watch on their connection for what overwrites the ghosts. */
    private void watch(Player player, Session session) {
        watching.put(player.getUniqueId(), session);
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        channel.eventLoop().execute(() -> {
            if (channel.isActive() && channel.pipeline().get(GhostKeeper.NAME) == null
                    && channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", GhostKeeper.NAME,
                        new GhostKeeper(player.getUniqueId()));
            }
        });
    }

    /**
     * Sits on a viewer's connection and notices real blocks going out where they see
     * ghosts: a whole chunk sent again, or single blocks. Runs on the network thread, so
     * it only looks, and leaves the resending to the server thread.
     */
    private final class GhostKeeper extends ChannelDuplexHandler {
        static final String NAME = "treearchive_ghosts";
        private final UUID viewer;

        GhostKeeper(UUID viewer) {
            this.viewer = viewer;
        }

        @Override
        public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            super.write(context, packet, promise);
            try {
                Session session = watching.get(viewer);
                if (session != null) {
                    check(session, packet);
                }
            } catch (RuntimeException e) {
                // A preview must never break a connection.
                plugin.getSLF4JLogger().debug("Could not look at a packet for the preview", e);
            }
        }

        private void check(Session session, Object packet) {
            switch (packet) {
                case ClientboundBundlePacket bundle -> bundle.subPackets().forEach(inner -> check(session, inner));
                case ClientboundLevelChunkWithLightPacket chunk -> {
                    long key = chunkKey(chunk.x(), chunk.z());
                    if (session.byChunk.containsKey(key)) {
                        later(session, () -> {
                            Set<Long> inChunk = session.byChunk.get(key);
                            return inChunk == null ? Set.of() : Set.copyOf(inChunk);
                        }, key);
                    }
                }
                case ClientboundBlockUpdatePacket block -> {
                    long key = BlockKeys.of(block.getPos().getX(), block.getPos().getY(), block.getPos().getZ());
                    if (overwrites(session, key, block.getBlockState())) {
                        later(session, () -> Set.of(key), null);
                    }
                }
                case ClientboundSectionBlocksUpdatePacket section -> {
                    Set<Long> hit = new HashSet<>();
                    section.runUpdates((position, state) -> {
                        long key = BlockKeys.of(position.getX(), position.getY(), position.getZ());
                        if (overwrites(session, key, state)) {
                            hit.add(key);
                        }
                    });
                    if (!hit.isEmpty()) {
                        later(session, () -> hit, null);
                    }
                }
                default -> {
                }
            }
        }

        /** Anything but the ghost itself - so sending the ghosts again goes out unanswered. */
        private static boolean overwrites(Session session, long key, BlockState state) {
            BlockData ghost = session.ghosts.get(key);
            return ghost != null && ((CraftBlockData) ghost).getState() != state;
        }

        /**
         * Sends these ghosts again on the server thread, after what overwrote them - and
         * a whole chunk's leaf colours, which the client forgot along with the chunk.
         */
        private void later(Session session, Supplier<Set<Long>> keys, @Nullable Long chunk) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(viewer);
                if (player == null || watching.get(viewer) != session
                        || !player.getWorld().getUID().equals(session.world)) {
                    return;
                }
                Map<Position, BlockData> sent = new HashMap<>();
                for (long key : keys.get()) {
                    BlockData ghost = session.ghosts.get(key);
                    if (ghost != null) {
                        sent.put(position(key), ghost);
                    }
                }
                if (!sent.isEmpty()) {
                    player.sendMultiBlockChange(sent);
                }
                if (chunk != null) {
                    sendColours(session, player, Set.of(chunk));
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        drop(event.getPlayer());
    }

    /** Forgets a viewer whose client has already let go of the preview. */
    private void drop(Player player) {
        Session session = watching.remove(player.getUniqueId());
        if (session != null) {
            leave(session, player.getUniqueId());
        }
    }

    /** A preview nobody looks at any more is let go. */
    private void leave(Session session, UUID viewer) {
        session.viewers.remove(viewer);
        if (session.viewers.isEmpty()) {
            sessions.remove(session.key, session);
            if (session.task != null) {
                session.task.cancel();
                session.task = null;
            }
        }
    }

    /** Collects the markers of one chunk, stopping one past the limit. */
    private static void scan(World world, ChunkSnapshot snapshot, Session session, ArrayDeque<Block> markers,
                             int limit) {
        int baseX = snapshot.getX() << 4;
        int baseZ = snapshot.getZ() << 4;
        Set<Material> wanted = session.spec.markers();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                if (!session.inArea(x, z)) {
                    continue;
                }
                for (int y = session.minY; y <= session.maxY; y++) {
                    if (wanted.contains(snapshot.getBlockType(dx, y, dz))) {
                        markers.add(world.getBlockAt(x, y, z));
                        if (markers.size() > limit) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private int config(String key, int fallback) {
        return plugin.getConfig().getInt("preview." + key, fallback);
    }

    /** The world by its key, like worlds:preset_forest, or by its plain name. */
    @Nullable World world(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        World world = key == null ? null : plugin.getServer().getWorld(key);
        return world != null ? world : plugin.getServer().getWorld(name);
    }

    private static BlockData realAt(World world, long key) {
        return world.getBlockAt(BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key)).getBlockData();
    }

    private static Position position(long key) {
        return Position.block(BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key));
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    /** SplitMix64: neighbouring places and seeds give unrelated draws. */
    static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
