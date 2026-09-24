package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
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
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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
 * by brush - grows its tree, one broken takes it away. Hitting a ghost trunk takes the
 * marker, and so the tree, away. Leaves are left alone - trees overlap there, and
 * working out whose leaf it was is not worth it.
 */
final class TreePreview implements Listener {

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
        final Set<UUID> viewers = new LinkedHashSet<>();
        /** Every ghost block, by position, and the marker whose tree it belongs to. */
        final Map<Long, BlockData> ghosts = new HashMap<>();
        final Map<Long, Long> owners = new HashMap<>();
        /** The positions of each tree, by its marker. */
        final Map<Long, long[]> trees = new HashMap<>();
        /** Ghost positions per chunk, to send them again when the chunk is sent again. */
        final Map<Long, Set<Long>> byChunk = new HashMap<>();
        @Nullable BukkitTask task;

        Session(String key, PreviewSpec spec, long seed, UUID world, int x, int z, int radius,
                int minY, int maxY) {
            this.key = key;
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
                    && block.getX() >= fromX && block.getX() <= toX
                    && block.getZ() >= fromZ && block.getZ() <= toZ
                    && block.getY() >= minY && block.getY() <= maxY;
        }
    }

    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<UUID, Session> watching = new HashMap<>();

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
        }
    }

    /**
     * Shows a preview around the given place - where the player stands when they start
     * one, where it was started for a shared one, and then they are taken there when
     * they are elsewhere. A preview already running is joined, not grown again.
     */
    void show(Player player, String text, PreviewSpec spec, long seed, World world, int centreX, int centreZ) {
        String key = text + " --seed " + seed + " --at " + world.getKey().asString() + "," + centreX + "," + centreZ;
        clear(player);
        int radius = Math.max(1, config("radius", 150));
        if (!player.getWorld().equals(world)
                || Math.abs(player.getLocation().getBlockX() - centreX) > radius
                || Math.abs(player.getLocation().getBlockZ() - centreZ) > radius) {
            player.teleport(new Location(world, centreX + 0.5,
                    world.getHighestBlockYAt(centreX, centreZ) + 1, centreZ + 0.5));
        }

        Session running = sessions.get(key);
        if (running != null) {
            running.viewers.add(player.getUniqueId());
            watching.put(player.getUniqueId(), running);
            Map<Position, BlockData> all = new HashMap<>();
            running.ghosts.forEach((position, ghost) -> all.put(position(position), ghost));
            player.sendMultiBlockChange(all);
            player.sendMessage(Component.text("Joined the preview " + text + " (" + running.trees.size()
                    + " trees).", NamedTextColor.GREEN));
            return;
        }

        Session session = new Session(key, spec, seed, world.getUID(), centreX, centreZ, radius,
                Math.max(world.getMinHeight(), config("min-y", -64)),
                Math.min(world.getMaxHeight() - 1, config("max-y", 160)));
        sessions.put(key, session);
        session.viewers.add(player.getUniqueId());
        watching.put(player.getUniqueId(), session);
        player.sendMessage(Component.text("Growing the preview " + text + " around " + centreX + ", "
                + centreZ + " (seed " + seed + "). Hit a trunk to take its marker away.", NamedTextColor.GREEN));

        ArrayDeque<long[]> chunks = new ArrayDeque<>();
        for (int chunkX = session.fromX >> 4; chunkX <= session.toX >> 4; chunkX++) {
            for (int chunkZ = session.fromZ >> 4; chunkZ <= session.toZ >> 4; chunkZ++) {
                chunks.add(new long[] {chunkX, chunkZ});
            }
        }
        ArrayDeque<Block> markers = new ArrayDeque<>();
        // A guard against markers that are not markers - stone, dirt - which would ask
        // for a tree on every block around.
        int limit = Math.max(1, config("max-trees", 100));
        int[] counts = {0, 0};
        long budget = Math.max(1, plugin.getConfig().getLong("preview.tick-budget-ms", 5)) * 1_000_000L;

        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + budget;
            do {
                long[] chunk = chunks.poll();
                if (chunk != null) {
                    scan(world, world.getChunkAt((int) chunk[0], (int) chunk[1]).getChunkSnapshot(false, false, false),
                            session, markers, limit);
                    if (markers.size() > limit) {
                        Objects.requireNonNull(session.task).cancel();
                        session.task = null;
                        for (UUID viewer : List.copyOf(session.viewers)) {
                            Player looking = plugin.getServer().getPlayer(viewer);
                            if (looking != null) {
                                looking.sendMessage(Component.text("More than " + limit + " markers around here,"
                                        + " so no preview. Pick a block that only marks trees, or raise"
                                        + " preview.max-trees.", NamedTextColor.RED));
                                clear(looking);
                            }
                        }
                        return;
                    }
                    continue;
                }
                Block marker = markers.poll();
                if (marker == null) {
                    player.sendMessage(Component.text("Preview ready: " + counts[0] + " trees, "
                            + counts[1] + " markers without room.", NamedTextColor.GREEN));
                    Objects.requireNonNull(session.task).cancel();
                    session.task = null;
                    return;
                }
                if (grow(session, marker)) {
                    counts[0]++;
                } else {
                    counts[1]++;
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
            show(player, text, spec, newSeed(), marker.getWorld(), at.getBlockX(), at.getBlockZ());
        }
    }

    /** A marker was set: every preview it lies in grows its tree, for all who look. */
    private void markerPlaced(Block marker) {
        int limit = Math.max(1, config("max-trees", 100));
        for (Session session : sessions.values()) {
            if (session.covers(marker) && session.spec.markers().contains(marker.getType())
                    && session.trees.size() < limit) {
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
        TreeArchive.Entry entry = trees.pick(archive.entries(), random);
        int turns = paster.turns(random);
        Clipboard clipboard = entry == null ? null : archive.clipboard(entry);
        List<TreePaster.Placement> placements = clipboard == null ? null
                : paster.plan(clipboard, marker, false, turns, session.spec.markers());
        if (placements == null) {
            return false;
        }

        long[] keys = new long[placements.size()];
        Map<Position, BlockData> sent = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            TreePaster.Placement placement = placements.get(i);
            long key = BlockKeys.of(placement.x(), placement.y(), placement.z());
            keys[i] = key;
            BlockData data = BukkitAdapter.adapt(placement.block());
            // A trunk takes any spot; leaves give way to another tree's trunk.
            BlockData there = session.ghosts.get(key);
            if (there != null && TreePaster.soft(data.getMaterial()) && !TreePaster.soft(there.getMaterial())) {
                continue;
            }
            session.ghosts.put(key, data);
            session.owners.put(key, markerKey);
            session.byChunk.computeIfAbsent(chunkKey(placement.x() >> 4, placement.z() >> 4),
                    ignored -> new HashSet<>()).add(key);
            sent.put(position(key), data);
        }
        session.trees.put(markerKey, keys);
        send(session, sent);
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
        // A trunk takes its marker away for real, for whoever hits it - this is a building server.
        if (owner != null && !TreePaster.soft(ghost.getMaterial())) {
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

    /** A chunk sent again - after walking away and back - comes without the ghosts. */
    @EventHandler
    public void onChunkSent(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        Session session = watching.get(player.getUniqueId());
        if (session == null || !session.world.equals(event.getWorld().getUID())) {
            return;
        }
        Set<Long> keys = session.byChunk.get(chunkKey(event.getChunk().getX(), event.getChunk().getZ()));
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<Long> resend = Set.copyOf(keys);
        // A tick later, so the ghosts land on the chunk and not before it.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (watching.get(player.getUniqueId()) != session) {
                return;
            }
            Map<Position, BlockData> sent = new HashMap<>();
            resend.forEach(key -> {
                BlockData ghost = session.ghosts.get(key);
                if (ghost != null) {
                    sent.put(position(key), ghost);
                }
            });
            player.sendMultiBlockChange(sent);
        });
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
                if (x < session.fromX || x > session.toX || z < session.fromZ || z > session.toZ) {
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
