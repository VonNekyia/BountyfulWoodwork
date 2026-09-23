package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Forest presets. A preset is a schematic of markers, one block per tree - a gold block
 * by default - built flat somewhere and saved with /bw forest save. Loading it puts a
 * random archived tree on every marker, on whatever terrain the markers end up over,
 * with the whole pattern centred on the middle of the map.
 *
 * <p>Markers may name a category or a tree type in the config, so a preset can say where
 * the giants go, where the shrubs fill in and where the spruces stand. Markers with no
 * room - water, a trunk in the way - are counted and skipped, never forced.
 */
final class TreeForest {

    /** One tree to put down: where it goes and which trees may go there. */
    private record Marker(int x, int z, String filter) {
    }

    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;

    private @Nullable BukkitTask task;
    /** Marker columns grouped per chunk, so every chunk is loaded once. */
    private final Deque<List<Marker>> pending = new ArrayDeque<>();
    /** Groups whose chunk has finished loading. */
    private final Deque<List<Marker>> ready = new ArrayDeque<>();
    private int loading;
    private int placed;
    private int skipped;

    TreeForest(JavaPlugin plugin, TreeArchive archive, TreePaster paster) {
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
        pending.clear();
        ready.clear();
        loading = 0;
    }

    File folder() {
        File folder = new File(plugin.getDataFolder(), "presets");
        folder.mkdirs();
        return folder;
    }

    /** The middle of the map, where a preset is centred unless told otherwise. */
    int[] centre() {
        return new int[] {plugin.getConfig().getInt("forest.center-x", 0),
                plugin.getConfig().getInt("forest.center-z", 0)};
    }

    /** The preset names that can be loaded. */
    List<String> presets() {
        File[] files = folder().listFiles(file -> ClipboardFormats.findByFile(file) != null);
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files)
                .map(file -> file.getName().replaceAll("\\.[^.]+$", ""))
                .sorted()
                .toList();
    }

    /** Saves the player's selection as a preset, markers and all. */
    void save(Player player, String name, Region region) throws WorldEditException, IOException {
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
        BlockVector3 min = region.getMinimumPoint();
        CuboidRegion box = new CuboidRegion(world, min, region.getMaximumPoint());
        Clipboard clipboard = new BlockArrayClipboard(box);
        try {
            ForwardExtentCopy copy = new ForwardExtentCopy(world, box, clipboard, min);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
            try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
                    .getWriter(new FileOutputStream(new File(folder(), id(name) + ".schem")))) {
                writer.write(clipboard);
            }
        } finally {
            TreeArchive.release(clipboard);
        }
    }

    /**
     * Reads a preset and starts planting it, centred on the given point.
     *
     * @return false when the preset is missing, unreadable or holds no markers
     */
    boolean start(Player player, String name, int centreX, int centreZ) {
        File file = file(name);
        if (file == null) {
            player.sendMessage(Component.text("No preset '" + name + "' in " + folder() + ".",
                    NamedTextColor.RED));
            return false;
        }
        if (archive.entries().isEmpty()) {
            player.sendMessage(Component.text("The archive is empty; archive a tree first.",
                    NamedTextColor.RED));
            return false;
        }

        Map<Material, String> markers = markers();
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        List<Marker> found = new ArrayList<>();
        try (InputStream in = new FileInputStream(file);
             ClipboardReader reader = format.getReader(in)) {
            Clipboard clipboard = reader.read();
            try {
                Region region = clipboard.getRegion();
                BlockVector3 min = region.getMinimumPoint();
                BlockVector3 max = region.getMaximumPoint();
                // The middle of the pattern lands on the middle of the map.
                int offsetX = centreX - Math.floorDiv(min.x() + max.x(), 2);
                int offsetZ = centreZ - Math.floorDiv(min.z() + max.z(), 2);
                for (BlockVector3 position : region) {
                    Material material = BukkitAdapter.adapt(clipboard.getBlock(position).getBlockType());
                    String filter = markers.get(material);
                    if (filter != null) {
                        found.add(new Marker(position.x() + offsetX, position.z() + offsetZ, filter));
                    }
                }
            } finally {
                TreeArchive.release(clipboard);
            }
        } catch (IOException e) {
            player.sendMessage(Component.text("Could not read the preset: " + e.getMessage(),
                    NamedTextColor.RED));
            return false;
        }

        if (found.isEmpty()) {
            player.sendMessage(Component.text("No markers in '" + name + "'. Marker blocks: "
                    + markers.keySet().stream().map(material -> material.name().toLowerCase(Locale.ROOT))
                            .reduce((a, b) -> a + ", " + b).orElse(""),
                    NamedTextColor.RED));
            return false;
        }

        stop();
        // One group per chunk: the chunk is loaded once and then filled in one go.
        Map<Long, List<Marker>> grouped = new LinkedHashMap<>();
        for (Marker marker : found) {
            long key = (long) (marker.x() >> 4) << 32 | ((marker.z() >> 4) & 0xFFFFFFFFL);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(marker);
        }
        pending.addAll(grouped.values());
        placed = 0;
        skipped = 0;

        player.sendMessage(Component.text("Planting " + found.size() + " trees from '" + name
                + "' around " + centreX + ", " + centreZ + ".", NamedTextColor.GREEN));
        run(player, player.getWorld());
        return true;
    }

    private void run(Player player, World world) {
        long budget = Math.max(1, plugin.getConfig().getLong("forest.tick-budget-ms", 5)) * 1_000_000L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + budget;
            do {
                List<Marker> group = ready.poll();
                if (group != null) {
                    for (Marker marker : group) {
                        plant(world, marker);
                    }
                    continue;
                }
                if (pending.isEmpty() && loading == 0) {
                    player.sendMessage(Component.text("Planted " + placed + " trees; " + skipped
                            + " markers had no room.", NamedTextColor.GREEN));
                    stop();
                    return;
                }
                // Nothing to plant yet: ask for the next chunk and wait for it.
                if (loading >= 4 || pending.isEmpty()) {
                    return;
                }
                request(world, pending.poll());
            } while (System.nanoTime() < deadline);
        }, 1, 1);
    }

    private void request(World world, List<Marker> group) {
        loading++;
        Marker first = group.getFirst();
        world.getChunkAtAsync(first.x() >> 4, first.z() >> 4).thenAccept(chunk -> {
            loading--;
            if (running()) {
                ready.add(group);
            }
        }).exceptionally(error -> {
            loading--;
            plugin.getLogger().warning("Could not load a chunk for the forest: " + error.getMessage());
            return null;
        });
    }

    /** Puts one tree on the ground under a marker, or counts it as skipped. */
    private void plant(World world, Marker marker) {
        TreeArchive.Entry entry = pick(marker.filter());
        if (entry == null) {
            skipped++;
            return;
        }
        Clipboard clipboard = archive.clipboard(entry);
        if (clipboard == null) {
            skipped++;
            return;
        }

        // The ground under the marker, ignoring water and anything growing on it.
        int groundY = world.getHighestBlockYAt(marker.x(), marker.z(), HeightMap.OCEAN_FLOOR);
        Block ground = world.getBlockAt(marker.x(), groundY, marker.z());
        Block base = ground.getRelative(BlockFace.UP);
        if (ground.isEmpty() || Tag.LEAVES.isTagged(ground.getType())
                || !TreePaster.canHoldTrunk(base)) {
            skipped++;
            return;
        }
        if (paster.paste(entry.type(), clipboard, base, null)) {
            placed++;
        } else {
            skipped++;
        }
    }

    /** A random archived tree of that category or tree type, or any tree for "any". */
    private TreeArchive.@Nullable Entry pick(String filter) {
        List<TreeArchive.Entry> choices = filter.equals("any")
                ? archive.entries()
                : archive.entries().stream()
                        .filter(entry -> entry.category().equals(filter) || entry.type().equals(filter))
                        .toList();
        if (choices.isEmpty()) {
            return null;
        }
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    /** Which block means a tree, and which trees it may become: a category or a type. */
    private Map<Material, String> markers() {
        Map<Material, String> markers = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("forest.markers");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning("Unknown forest marker block '" + key + "'.");
                    continue;
                }
                markers.put(material, section.getString(key, "any").toLowerCase(Locale.ROOT));
            }
        }
        if (markers.isEmpty()) {
            markers.put(Material.GOLD_BLOCK, "any");
        }
        return markers;
    }

    private @Nullable File file(String name) {
        String id = id(name);
        File[] files = folder().listFiles(file -> ClipboardFormats.findByFile(file) != null
                && file.getName().replaceAll("\\.[^.]+$", "").equalsIgnoreCase(id));
        return files == null || files.length == 0 ? null : files[0];
    }

    private static String id(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[ \\-]+", "_").replaceAll("[^a-z0-9_]", "");
        return id.isEmpty() ? "forest" : id;
    }
}
