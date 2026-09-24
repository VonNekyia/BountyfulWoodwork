package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
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
import java.util.List;
import java.util.function.LongConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Puts the archive on the ground: {@code categorized} builds a plot per tree, ordered
 * by type, then by creator, then
 * by size, so every tree of a kind stands together and one row never mixes builders.
 * Each plot is floored with a checkerboard of three by three tiles in light gray and
 * cyan terracotta, with one red block in the middle where the trunk goes.
 *
 * <p>It takes a selection like {@code birch,oak} or {@code 70%birch,30%oak}. The work is
 * spread over ticks, so it does not stall the server.
 */
final class TreeLayout {

    /** Blocks between two plots. */
    private static final int GAP = 1;
    /** Air above a plot, over the tree standing on it. */
    private static final int HEADROOM = 8;

    /** One tree to put on one plot. */
    private record Job(TreeArchive.Entry entry, int x, int z, int plotSize, int clearTo) {
    }



    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;

    private @Nullable BukkitTask task;

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
    }

    /** The ways /bw layout can lay the archive out. */
    static List<String> modes() {
        return List.of("categorized");
    }

    /** Lays the chosen trees out, or tells the player why it cannot. */
    void start(Player player, String mode, TreeSelection selection) {
        List<TreeArchive.Entry> entries = selection.matching(archive.entries());
        if (entries.isEmpty()) {
            player.sendMessage(Component.text(archive.entries().isEmpty()
                    ? "The archive is empty; archive a tree first."
                    : "No archived tree matches that selection.", NamedTextColor.RED));
            return;
        }
        categorized(player, entries);
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
        Clipboard clipboard = archive.clipboard(job.entry());
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
                    session.setBlock(BlockVector3.at(job.x() + dx, floorY, job.z() + dz),
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

    // ---- shared ---------------------------------------------------------------------

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
