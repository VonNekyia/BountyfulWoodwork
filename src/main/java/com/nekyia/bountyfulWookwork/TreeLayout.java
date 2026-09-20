package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The two ways to put the archive on the ground.
 *
 * <p>{@code categorized} builds a plot per tree, ordered by type, then by creator, then
 * by size, so every tree of a kind stands together and one row never mixes builders.
 * Each plot is floored with a checkerboard of three by three tiles in light gray and
 * cyan terracotta, with one red block in the middle where the trunk goes.
 *
 * <p>{@code terrain} copies a piece of a prepared world - hills, water, paths, whatever
 * was built there - and turns every marker block in it into a tree. The terrain says
 * where the trees stand; the archive says what stands there.
 *
 * <p>Both take a selection like {@code birch,oak} or {@code 70%birch,30%oak}. Work is
 * spread over ticks either way, so neither stalls the server.
 */
final class TreeLayout {

    /** Blocks between two plots. */
    private static final int GAP = 1;
    /** Air above a plot, over the tree standing on it. */
    private static final int HEADROOM = 8;

    /** One tree to put on one plot. */
    private record Job(TreeArchive.Entry entry, int x, int z, int plotSize, int clearTo) {
    }

    /** One square of a prepared world still to copy over, and its chunk. */
    private record Tile(int chunkX, int chunkZ, int minX, int minZ, int maxX, int maxZ) {
    }

    /** Where a marker block stood, so a tree can take its place. */
    private record Marker(int x, int y, int z) {
    }

    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;

    private @Nullable BukkitTask task;
    /** Blueprints already read from disk, so one run reads every tree once. */
    private final Map<String, Clipboard> loaded = new HashMap<>();

    TreeLayout(JavaPlugin plugin, TreeArchive archive, TreePaster paster) {
        this.plugin = plugin;
        this.archive = archive;
        this.paster = paster;
    }

    boolean running() {
        return task != null;
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        loaded.values().forEach(Clipboard::close);
        loaded.clear();
    }

    /** The ways /bw layout can lay the archive out. */
    static List<String> modes() {
        return List.of("categorized", "terrain");
    }

    /** Runs one of the two layouts, or tells the player why it cannot. */
    void start(Player player, String mode, TreeSelection selection) {
        List<TreeArchive.Entry> entries = selection.matching(archive.entries());
        if (entries.isEmpty()) {
            player.sendMessage(Component.text(archive.entries().isEmpty()
                    ? "The archive is empty; archive a tree first."
                    : "No archived tree matches that selection.", NamedTextColor.RED));
            return;
        }
        if (mode.equals("terrain")) {
            terrain(player, entries, selection);
        } else {
            categorized(player, entries);
        }
    }

    // ---- categorized ----------------------------------------------------------------

    /** Builds a plot per tree, by type, then creator, then size. */
    private void categorized(Player player, List<TreeArchive.Entry> entries) {
        List<String> order = archive.categories().stream().map(TreeArchive.Category::name).toList();
        List<TreeArchive.Entry> sorted = entries.stream()
                .sorted(Comparator.comparing(TreeArchive.Entry::type, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.creator().name(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(entry -> order.indexOf(entry.category()))
                        .thenComparingInt(entry ->
                                TreeArchive.sizeOf(entry.width(), entry.height(), entry.length())))
                .toList();

        World world = player.getWorld();
        int startX = player.getLocation().getBlockX();
        int startZ = player.getLocation().getBlockZ();
        int floorY = player.getLocation().getBlockY() - 1;
        int rowLength = Math.max(1, plugin.getConfig().getInt("layout.row-length", 8));

        Deque<Job> jobs = new ArrayDeque<>();
        int x = startX;
        int z = startZ;
        int depth = 0;
        int inRow = 0;
        String rowType = null;
        for (TreeArchive.Entry entry : sorted) {
            // A row holds one type only, so the map reads as one kind after another.
            boolean newType = rowType != null && !rowType.equals(entry.type());
            if (newType || inRow >= rowLength) {
                z += depth + GAP + (newType ? GAP * 2 : 0);
                x = startX;
                depth = 0;
                inRow = 0;
            }
            rowType = entry.type();

            int plotSize = archive.categoryOf(entry).plotSize();
            jobs.add(new Job(entry, x, z, plotSize, floorY + entry.height() + HEADROOM));
            x += plotSize + GAP;
            depth = Math.max(depth, plotSize);
            inRow++;
        }

        player.sendMessage(Component.text("Laying out " + jobs.size() + " trees by type, creator"
                + " and size, starting at " + startX + ", " + startZ + ".",
                NamedTextColor.GREEN));
        stop();
        int[] done = {0};
        task = every(budget -> {
            long deadline = System.nanoTime() + budget;
            do {
                Job job = jobs.poll();
                if (job == null) {
                    player.sendMessage(Component.text("Laid out " + done[0] + " trees.",
                            NamedTextColor.GREEN));
                    stop();
                    return;
                }
                if (place(world, job, floorY)) {
                    done[0]++;
                }
            } while (System.nanoTime() < deadline);
        });
    }

    /** Clears the plot, floors it with the checkerboard and puts the tree on the middle. */
    private boolean place(World world, Job job, int floorY) {
        Clipboard clipboard = blueprint(job.entry());
        if (clipboard == null) {
            return false;
        }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        int size = job.plotSize();
        int middle = size / 2;

        try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
            // Typed as Region: a cuboid is also a set of positions, and the call would
            // otherwise be ambiguous.
            Region plot = new CuboidRegion(weWorld,
                    BlockVector3.at(job.x(), floorY, job.z()),
                    BlockVector3.at(job.x() + size - 1, job.clearTo(), job.z() + size - 1));
            session.setBlocks(plot, BlockTypes.AIR.getDefaultState());

            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    boolean centre = dx == middle && dz == middle;
                    // Three by three tiles, so the pattern reads as a board, not as noise.
                    boolean light = ((dx / 3) + (dz / 3)) % 2 == 0;
                    session.setBlock(job.x() + dx, floorY, job.z() + dz,
                            block(centre ? Material.RED_TERRACOTTA
                                    : light ? Material.LIGHT_GRAY_TERRACOTTA : Material.CYAN_TERRACOTTA));
                }
            }

            Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                    .to(BlockVector3.at(job.x() + middle, floorY + 1, job.z() + middle))
                    .ignoreAirBlocks(true)
                    .build());
            return true;
        } catch (WorldEditException e) {
            plugin.getLogger().warning("Could not place " + job.entry().id() + ": " + e.getMessage());
            return false;
        }
    }

    // ---- terrain --------------------------------------------------------------------

    /** Copies the prepared world over and turns its markers into trees. */
    private void terrain(Player player, List<TreeArchive.Entry> entries, TreeSelection selection) {
        String name = plugin.getConfig().getString("layout.terrain.world", "worlds:preset_forest");
        World source = worldNamed(name);
        if (source == null) {
            player.sendMessage(Component.text("No world '" + name
                    + "'; it has to be loaded to copy from it.", NamedTextColor.RED));
            return;
        }
        World target = player.getWorld();
        if (source.equals(target)) {
            player.sendMessage(Component.text("You are standing in " + name
                    + " itself; go to the map you want the forest on.", NamedTextColor.RED));
            return;
        }

        int fromX = plugin.getConfig().getInt("layout.terrain.from-x", -150);
        int fromZ = plugin.getConfig().getInt("layout.terrain.from-z", -150);
        int toX = plugin.getConfig().getInt("layout.terrain.to-x", 150);
        int toZ = plugin.getConfig().getInt("layout.terrain.to-z", 150);
        int minY = Math.max(source.getMinHeight(), plugin.getConfig().getInt("layout.terrain.min-y", -64));
        int maxY = Math.min(source.getMaxHeight() - 1, plugin.getConfig().getInt("layout.terrain.max-y", 160));
        int offsetX = plugin.getConfig().getInt("layout.terrain.offset-x", 0);
        int offsetZ = plugin.getConfig().getInt("layout.terrain.offset-z", 0);
        Material marker = Material.matchMaterial(
                plugin.getConfig().getString("layout.terrain.marker", "gold_block"));
        if (marker == null) {
            player.sendMessage(Component.text("layout.terrain.marker is not a block.",
                    NamedTextColor.RED));
            return;
        }

        // One tile per chunk of the source, clipped to the piece that was asked for.
        Deque<Tile> tiles = new ArrayDeque<>();
        for (int chunkX = Math.min(fromX, toX) >> 4; chunkX <= Math.max(fromX, toX) >> 4; chunkX++) {
            for (int chunkZ = Math.min(fromZ, toZ) >> 4; chunkZ <= Math.max(fromZ, toZ) >> 4; chunkZ++) {
                tiles.add(new Tile(chunkX, chunkZ,
                        Math.max(Math.min(fromX, toX), chunkX << 4),
                        Math.max(Math.min(fromZ, toZ), chunkZ << 4),
                        Math.min(Math.max(fromX, toX), (chunkX << 4) + 15),
                        Math.min(Math.max(fromZ, toZ), (chunkZ << 4) + 15)));
            }
        }

        player.sendMessage(Component.text("Copying " + tiles.size() + " chunks of " + name
                + " to " + (Math.min(fromX, toX) + offsetX) + ", " + (Math.min(fromZ, toZ) + offsetZ)
                + " and planting every " + marker.name().toLowerCase(Locale.ROOT) + ".",
                NamedTextColor.GREEN));

        stop();
        Deque<Marker> markers = new ArrayDeque<>();
        int[] counts = {0, 0};
        task = every(budget -> {
            long deadline = System.nanoTime() + budget;
            do {
                Tile tile = tiles.poll();
                if (tile != null) {
                    copy(source, target, tile, minY, maxY, offsetX, offsetZ);
                    scan(source, tile, minY, maxY, offsetX, offsetZ, marker, markers);
                    continue;
                }
                // The ground is there; now the trees go on it.
                Marker spot = markers.poll();
                if (spot == null) {
                    player.sendMessage(Component.text("Planted " + counts[0] + " trees; "
                            + counts[1] + " markers had no room.", NamedTextColor.GREEN));
                    stop();
                    return;
                }
                if (plant(target, spot, marker, entries, selection)) {
                    counts[0]++;
                } else {
                    counts[1]++;
                }
            } while (System.nanoTime() < deadline);
        });
    }

    /** Copies one chunk-sized piece of the prepared world onto the map. */
    private void copy(World source, World target, Tile tile, int minY, int maxY,
                      int offsetX, int offsetZ) {
        com.sk89q.worldedit.world.World from = BukkitAdapter.adapt(source);
        BlockVector3 min = BlockVector3.at(tile.minX(), minY, tile.minZ());
        Region region = new CuboidRegion(from, min, BlockVector3.at(tile.maxX(), maxY, tile.maxZ()));
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(target))) {
            ForwardExtentCopy copy = new ForwardExtentCopy(from, region, min, session,
                    min.add(offsetX, 0, offsetZ));
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        } catch (WorldEditException e) {
            plugin.getLogger().warning("Could not copy chunk " + tile.chunkX() + ", " + tile.chunkZ()
                    + ": " + e.getMessage());
        }
    }

    /** Remembers where the markers of one piece ended up on the map. */
    private void scan(World source, Tile tile, int minY, int maxY, int offsetX, int offsetZ,
                      Material marker, Deque<Marker> markers) {
        Chunk chunk = source.getChunkAt(tile.chunkX(), tile.chunkZ());
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        for (int x = tile.minX(); x <= tile.maxX(); x++) {
            for (int z = tile.minZ(); z <= tile.maxZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (snapshot.getBlockType(x & 15, y, z & 15) == marker) {
                        markers.add(new Marker(x + offsetX, y, z + offsetZ));
                    }
                }
            }
        }
    }

    /** Puts a tree where a marker block stands, taking the marker away with it. */
    private boolean plant(World world, Marker spot, Material marker,
                          List<TreeArchive.Entry> entries, TreeSelection selection) {
        TreeArchive.Entry entry = selection.pick(entries);
        if (entry == null) {
            return false;
        }
        Clipboard clipboard = blueprint(entry);
        if (clipboard == null) {
            return false;
        }

        Block base = world.getBlockAt(spot.x(), spot.y(), spot.z());
        base.setType(Material.AIR, false);
        if (paster.paste(entry.id(), clipboard, base, null)) {
            return true;
        }
        // Left standing, so it is plain to see where a tree did not fit.
        base.setType(marker, false);
        return false;
    }

    // ---- shared ---------------------------------------------------------------------

    /** The world by its key, like worlds:preset_forest, or by its plain name. */
    private @Nullable World worldNamed(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        World world = key == null ? null : plugin.getServer().getWorld(key);
        return world != null ? world : plugin.getServer().getWorld(name);
    }

    /** An entry's blueprint, read once per run. */
    private @Nullable Clipboard blueprint(TreeArchive.Entry entry) {
        return loaded.computeIfAbsent(entry.id(), ignored -> archive.load(entry));
    }

    /** Runs a step every tick with the configured slice of the tick to spend. */
    private BukkitTask every(LongConsumer step) {
        long budget = Math.max(1, plugin.getConfig().getLong("layout.tick-budget-ms", 10)) * 1_000_000L;
        return plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> step.accept(budget), 1, 1);
    }

    private static BlockState block(Material material) {
        return BukkitAdapter.adapt(material.createBlockData());
    }
}
