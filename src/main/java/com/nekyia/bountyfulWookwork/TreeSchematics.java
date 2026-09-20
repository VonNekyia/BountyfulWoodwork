package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.TreeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The tree types - one per folder under schematics/ - with their schematics, and
 * which of them grow in place of each vanilla tree, as mapped in config.yml.
 */
final class TreeSchematics {

    private final JavaPlugin plugin;

    /** Keyed by lowercase folder name. */
    private Map<String, List<Clipboard>> byType = Map.of();
    private Map<TreeType, String> vanillaTypes = Map.of();
    private List<Clipboard> loaded = List.of();
    private boolean randomRotation;
    private boolean overwriteBlocks;
    private int brushRange;

    TreeSchematics(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Rereads config.yml and every schematic folder. Returns how many schematics loaded. */
    int reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        randomRotation = config.getBoolean("random-rotation", true);
        overwriteBlocks = config.getBoolean("overwrite-blocks", false);
        brushRange = Math.max(1, config.getInt("brush-range", 256));

        clear();

        File root = new File(plugin.getDataFolder(), "schematics");
        root.mkdirs();

        Map<String, List<Clipboard>> types = new TreeMap<>();
        File[] folders = root.listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                types.put(folder.getName().toLowerCase(Locale.ROOT), load(folder));
            }
        }

        Map<TreeType, String> mapping = new EnumMap<>(TreeType.class);
        ConfigurationSection trees = config.getConfigurationSection("trees");
        if (trees != null) {
            for (String key : trees.getKeys(false)) {
                TreeType species;
                try {
                    species = TreeType.valueOf(key.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown vanilla tree type in config.yml: " + key);
                    continue;
                }
                String type = trees.getString(key);
                if (type == null || type.isBlank()) {
                    continue;
                }
                type = type.toLowerCase(Locale.ROOT);
                if (!types.containsKey(type)) {
                    plugin.getLogger().warning("config.yml maps " + key + " to tree type '" + type
                            + "', but there is no schematics/" + type + " folder; it grows vanilla trees.");
                }
                mapping.put(species, type);
            }
        }

        byType = types;
        vanillaTypes = mapping;
        loaded = types.values().stream().flatMap(List::stream).toList();
        plugin.getLogger().info("Loaded " + loaded.size() + " tree schematics across " + types.size() + " tree types.");
        return loaded.size();
    }

    private List<Clipboard> load(File folder) {
        File[] files = folder.listFiles(File::isFile);
        if (files == null) {
            return List.of();
        }

        List<Clipboard> clipboards = new ArrayList<>();
        for (File file : files) {
            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                plugin.getLogger().warning("Not a schematic, skipping: " + file);
                continue;
            }
            try (InputStream in = new FileInputStream(file); ClipboardReader reader = format.getReader(in)) {
                Clipboard clipboard = reader.read();
                originOnTrunk(clipboard);
                clipboards.add(clipboard);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read schematic " + file + ": " + e.getMessage());
            }
        }
        return clipboards;
    }

    /**
     * Schematics saved without an origin (bloxelizer exports, for one) would paste
     * from their corner. Those get the bottom-layer block nearest the middle as their
     * origin instead, which is where the trunk stands.
     */
    private static void originOnTrunk(Clipboard clipboard) {
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        if (!clipboard.getOrigin().equals(min)) {
            return;
        }
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();
        double middleX = (min.x() + max.x()) / 2.0;
        double middleZ = (min.z() + max.z()) / 2.0;

        BlockVector3 trunk = BlockVector3.at((int) Math.floor(middleX), min.y(), (int) Math.floor(middleZ));
        double closest = Double.MAX_VALUE;
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                BlockVector3 position = BlockVector3.at(x, min.y(), z);
                if (clipboard.getBlock(position).getBlockType().getMaterial().isAir()) {
                    continue;
                }
                double distance = (x - middleX) * (x - middleX) + (z - middleZ) * (z - middleZ);
                if (distance < closest) {
                    closest = distance;
                    trunk = position;
                }
            }
        }
        clipboard.setOrigin(trunk);
    }

    /** Every tree type, sorted by name. */
    Set<String> types() {
        return byType.keySet();
    }

    /** The folder a tree type lives in; its name is the type. */
    File folderOf(String type) {
        return new File(new File(plugin.getDataFolder(), "schematics"), type.toLowerCase(Locale.ROOT));
    }

    /**
     * Adds a tree type by making its folder, which is all a type is.
     *
     * @return false when that type already exists or the folder could not be made
     */
    boolean createType(String type) {
        File folder = folderOf(type);
        if (folder.isDirectory() || !folder.mkdirs()) {
            return false;
        }
        reload();
        return true;
    }

    /**
     * Drops an empty tree type again, for when a name was mistyped.
     *
     * @return false when it still holds schematics, or is not a type at all
     */
    boolean removeType(String type) {
        File folder = folderOf(type);
        String[] left = folder.list();
        if (left == null || left.length > 0 || !folder.delete()) {
            return false;
        }
        reload();
        return true;
    }

    boolean hasType(String type) {
        return byType.containsKey(type.toLowerCase(Locale.ROOT));
    }

    /** A random schematic of this tree type, or null if it has none. */
    @Nullable Clipboard pick(String type) {
        List<Clipboard> clipboards = byType.get(type.toLowerCase(Locale.ROOT));
        if (clipboards == null || clipboards.isEmpty()) {
            return null;
        }
        return clipboards.get(ThreadLocalRandom.current().nextInt(clipboards.size()));
    }

    /** The tree type that grows in place of this vanilla tree, or null to let it grow vanilla. */
    @Nullable String typeFor(TreeType species) {
        return vanillaTypes.get(species);
    }

    boolean randomRotation() {
        return randomRotation;
    }

    boolean overwriteBlocks() {
        return overwriteBlocks;
    }

    int brushRange() {
        return brushRange;
    }

    /** Releases every loaded schematic; FAWE may back clipboards with files on disk. */
    void clear() {
        for (Clipboard clipboard : loaded) {
            clipboard.close();
        }
        loaded = List.of();
        byType = Map.of();
        vanillaTypes = Map.of();
    }
}
