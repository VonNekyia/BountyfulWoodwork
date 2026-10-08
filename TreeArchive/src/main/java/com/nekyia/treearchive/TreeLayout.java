package com.nekyia.treearchive;

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
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Puts the archive on the ground: {@code categorized} builds a plot per tree, ordered
 * by type, then by creator, then
 * by size, so every tree of a kind stands together and one row never mixes builders.
 * Each plot is floored with a checkerboard of three by three tiles in light gray and
 * cyan terracotta, with one red block in the middle where the trunk goes. Above each
 * tree floats its id, for every direction, so the layout tells which tree is which. The
 * names are never saved with the world - it may go on to the main server - but kept by
 * the plugin, in layout-labels.yml, and put up again whenever their chunk loads.
 *
 * <p>It takes a selection like {@code birch,oak} or {@code 70%birch,30%oak}. The work is
 * spread over ticks, so it does not stall the server.
 */
final class TreeLayout implements Listener {

    /** Blocks between two plots. */
    private static final int GAP = 1;
    /** Air above a plot, over the tree standing on it. */
    private static final int HEADROOM = 8;
    /** Marks the names floating over the trees, so laying out again replaces them. */
    private static final String LABEL = "treearchive_layout_label";

    /** One tree to put on one plot. */
    private record Job(TreeArchive.Entry entry, int x, int z, int plotSize, int clearTo) {
    }

    /** The name floating over a laid out tree. */
    private record Label(double x, double y, double z, String id) {

        long chunk() {
            return Chunk.getChunkKey((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        }
    }



    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;

    private @Nullable BukkitTask task;
    /** World name -> chunk key -> the names in that chunk. */
    private final Map<String, Map<Long, List<Label>>> labels = new HashMap<>();
    private boolean labelsChanged;

    TreeLayout(JavaPlugin plugin, TreeArchive archive, TreePaster paster) {
        this.plugin = plugin;
        this.archive = archive;
        this.paster = paster;
        YamlConfiguration file = YamlConfiguration.loadConfiguration(labelFile());
        for (String world : file.getKeys(false)) {
            for (String line : file.getStringList(world)) {
                String[] parts = line.split(" ");
                if (parts.length == 4) {
                    add(world, new Label(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2]), parts[3]));
                }
            }
        }
        // After a plugin reload the old names may still stand in loaded chunks.
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (entity.getScoreboardTags().contains(LABEL)) {
                        entity.remove();
                    }
                }
                show(chunk);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        show(event.getChunk());
    }

    /** Names a world saved before they were kept out of it go. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().stream().filter(entity -> entity.getScoreboardTags().contains(LABEL))
                .forEach(Entity::remove);
    }

    private void show(Chunk chunk) {
        World world = chunk.getWorld();
        for (Label label : labels.getOrDefault(world.getName(), Map.of()).getOrDefault(chunk.getChunkKey(), List.of())) {
            spawn(world, label);
        }
    }

    private void add(String world, Label label) {
        labels.computeIfAbsent(world, ignored -> new HashMap<>())
                .computeIfAbsent(label.chunk(), ignored -> new ArrayList<>()).add(label);
    }

    private File labelFile() {
        return new File(plugin.getDataFolder(), "layout-labels.yml");
    }

    private void saveLabels() {
        YamlConfiguration file = new YamlConfiguration();
        labels.forEach((world, byChunk) -> file.set(world, byChunk.values().stream().flatMap(List::stream)
                .map(label -> label.x() + " " + label.y() + " " + label.z() + " " + label.id()).toList()));
        try {
            file.save(labelFile());
            labelsChanged = false;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + labelFile().getName() + ": " + e.getMessage());
        }
    }

    boolean running() {
        return task != null;
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (labelsChanged) {
            saveLabels();
        }
    }

    /** The ways /ta layout can lay the archive out. */
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
        } catch (WorldEditException e) {
            plugin.getLogger().warning("Could not place " + job.entry().id() + ": " + e.getMessage());
            return false;
        }
        label(world, job, floorY);
        return true;
    }

    /** Floats the tree's id over it, in place of a name an earlier layout left on the plot. */
    private void label(World world, Job job, int floorY) {
        int size = job.plotSize();
        BoundingBox plot = new BoundingBox(job.x(), floorY, job.z(), job.x() + size, job.clearTo() + 1,
                job.z() + size);
        world.getNearbyEntities(plot, entity -> entity.getScoreboardTags().contains(LABEL)).forEach(Entity::remove);
        Map<Long, List<Label>> inWorld = labels.getOrDefault(world.getName(), Map.of());
        for (int chunkX = job.x() >> 4; chunkX <= (job.x() + size) >> 4; chunkX++) {
            for (int chunkZ = job.z() >> 4; chunkZ <= (job.z() + size) >> 4; chunkZ++) {
                List<Label> inChunk = inWorld.get(Chunk.getChunkKey(chunkX, chunkZ));
                if (inChunk != null) {
                    inChunk.removeIf(label -> plot.contains(label.x(), label.y(), label.z()));
                }
            }
        }
        Label label = new Label(job.x() + size / 2 + 0.5, floorY + job.entry().height() + 2,
                job.z() + size / 2 + 0.5, job.entry().id());
        add(world.getName(), label);
        labelsChanged = true;
        // Shown right away: the chunk is loaded, its load already past.
        spawn(world, label);
    }

    /** Puts a name up, for as long as its chunk stays loaded: the world never saves it. */
    private static void spawn(World world, Label label) {
        world.spawn(new Location(world, label.x(), label.y(), label.z()), TextDisplay.class, display -> {
            display.text(Component.text(label.id()));
            display.setBillboard(Display.Billboard.CENTER);
            display.addScoreboardTag(LABEL);
            display.setPersistent(false);
        });
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
