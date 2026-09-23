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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The archive of tree blueprints. Every tree is two files side by side in
 * plugins/BountyfulWookwork/archive: the schematic, with its origin on the trunk, and
 * a yml holding who built it, where it came from and how big it is.
 *
 * <p>The size decides the category. A tree that is taller than a category allows moves
 * up, and so does one too wide for that category's plot - all trees of a category have
 * to fit the same plot on the tree map.
 */
final class TreeArchive {

    /**
     * Who built a tree. A player is kept by their uuid, a patreon by a link to the
     * artist - they have no account here, and the link is to them, not to a pack.
     */
    record Creator(String name, boolean patreon, String reference) {
    }

    /** One archived tree. */
    record Entry(String id, String type, Creator creator, String category,
                 int width, int height, int length, File schematic) {
    }

    /** A size class and the plot it is shown on. */
    record Category(String name, int maxSize, int plotSize) {
    }

    // Fitted against the trees already archived: height plus the widest side agreed with
    // how they had been filed by hand for 87 of every 100, and never by more than a step.
    private static final List<Category> DEFAULTS = List.of(
            new Category("shrub", 12, 9),
            new Category("little", 25, 15),
            new Category("medium", 39, 27),
            new Category("large", 60, 39),
            new Category("giant", 90, 51),
            new Category("fantasy", Integer.MAX_VALUE, 81));

    private final JavaPlugin plugin;

    private List<Category> categories = DEFAULTS;
    private List<Entry> entries = List.of();
    /** Blueprints read so far, so placing a tree does not read its file every time. */
    private final Map<String, Clipboard> cache = new HashMap<>();

    TreeArchive(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The file the artists and their links live in; editable by hand. */
    private File patreonFile() {
        return new File(plugin.getDataFolder(), "patreons.yml");
    }

    /** Remembers an artist and the link to them, so later trees only need the name. */
    void patreon(String name, String link) throws IOException {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(patreonFile());
        // Replace whatever spelling of the name was there before.
        for (String key : file.getKeys(false)) {
            if (key.equalsIgnoreCase(name)) {
                file.set(key, null);
            }
        }
        file.set(name, link);
        file.save(patreonFile());
    }

    /** The known artists, by the spelling they were registered with. */
    Map<String, String> patreons() {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(patreonFile());
        Map<String, String> known = new LinkedHashMap<>();
        for (String key : file.getKeys(false)) {
            known.put(key, file.getString(key, ""));
        }
        return known;
    }

    /**
     * Works out who a name means: an artist registered with /bw patreon, else a player
     * who has been on the server. Null when it is neither.
     */
    @Nullable Creator creator(String name) {
        for (Map.Entry<String, String> patreon : patreons().entrySet()) {
            if (patreon.getKey().equalsIgnoreCase(name)) {
                return new Creator(patreon.getKey(), true, patreon.getValue());
            }
        }
        OfflinePlayer player = plugin.getServer().getOfflinePlayerIfCached(name);
        if (player != null) {
            String known = player.getName();
            return new Creator(known == null ? name : known, false, player.getUniqueId().toString());
        }
        return null;
    }

    /** The file the tree types live in; editable by hand. */
    private File typeFile() {
        return new File(plugin.getDataFolder(), "types.yml");
    }

    /** Every tree type: the ones made with /bw type, and any an archived tree carries. */
    Set<String> types() {
        Set<String> types = new TreeSet<>(YamlConfiguration.loadConfiguration(typeFile()).getStringList("types"));
        entries.forEach(entry -> types.add(entry.type()));
        return types;
    }

    boolean hasType(String type) {
        return types().contains(type.toLowerCase(Locale.ROOT));
    }

    /** Adds a tree type; false when it is already there. */
    boolean createType(String type) throws IOException {
        return !hasType(type) && editTypes(types -> types.add(type));
    }

    /** Drops a tree type again; false while an archived tree still is one. */
    boolean removeType(String type) throws IOException {
        return entries.stream().noneMatch(entry -> entry.type().equals(type))
                && editTypes(types -> types.remove(type));
    }

    private boolean editTypes(Predicate<List<String>> edit) throws IOException {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(typeFile());
        List<String> types = new ArrayList<>(file.getStringList("types"));
        if (!edit.test(types)) {
            return false;
        }
        file.set("types", types.stream().sorted().toList());
        file.save(typeFile());
        return true;
    }

    /** An entry's blueprint, read once and kept. Null when it cannot be read. */
    @Nullable Clipboard clipboard(Entry entry) {
        return cache.computeIfAbsent(entry.id(), ignored -> load(entry));
    }

    /** Lets go of every kept blueprint; FAWE may back them with files on disk. */
    void close() {
        cache.values().forEach(Clipboard::close);
        cache.clear();
    }

    /** Rereads config.yml, the categories and every yml in the archive. */
    int reload() {
        plugin.reloadConfig();
        close();
        List<Category> read = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("categories")) {
            if (raw.get("name") instanceof String name && raw.get("plot-size") instanceof Number plot) {
                int maxSize = raw.get("max-size") instanceof Number size
                        ? size.intValue() : Integer.MAX_VALUE;
                read.add(new Category(name.toLowerCase(Locale.ROOT), maxSize, plot.intValue()));
            }
        }
        categories = read.isEmpty() ? DEFAULTS : List.copyOf(read);

        List<Entry> found = new ArrayList<>();
        File[] files = folder().listFiles(file -> file.getName().endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                Entry entry = read(file);
                if (entry != null) {
                    found.add(entry);
                }
            }
        }
        entries = List.copyOf(found);
        return entries.size();
    }

    List<Entry> entries() {
        return entries;
    }

    List<Category> categories() {
        return categories;
    }

    File folder() {
        File folder = new File(plugin.getDataFolder(), "archive");
        folder.mkdirs();
        return folder;
    }

    /** One archived tree by its name, or null when nothing is called that. */
    @Nullable Entry entry(String id) {
        for (Entry entry : entries) {
            if (entry.id().equalsIgnoreCase(id)) {
                return entry;
            }
        }
        return null;
    }

    /** The category an archived tree sits in, falling back to what its size asks for. */
    Category categoryOf(Entry entry) {
        Category category = category(entry.category());
        return category != null ? category : categoryFor(entry.width(), entry.height(), entry.length());
    }

    /**
     * Files an archived tree under another type, size or creator. Its name is made of
     * all three, so both files are renamed with it and the tree takes the next free
     * number under its new name.
     *
     * @return the tree under its new name
     */
    Entry refile(Entry entry, String type, Category category, Creator creator) throws IOException {
        String id = freeId(baseId(type, category, creator));
        File schematic = new File(folder(), id + ".schem");
        if (!entry.schematic().renameTo(schematic)) {
            throw new IOException("could not rename " + entry.schematic().getName());
        }

        File meta = new File(folder(), entry.id() + ".yml");
        YamlConfiguration read = YamlConfiguration.loadConfiguration(meta);
        read.set("id", id);
        read.set("type", type);
        read.set("category", category.name());
        read.set("creator.name", creator.name());
        // The other kind of creator's key has to go, or both would be on the tree.
        read.set("creator.uuid", null);
        read.set("creator.link", null);
        read.set(creator.patreon() ? "creator.link" : "creator.uuid", creator.reference());
        read.save(new File(folder(), id + ".yml"));
        if (!meta.delete()) {
            plugin.getLogger().warning("Left " + meta + " behind; delete it by hand.");
        }

        Entry moved = new Entry(id, type, creator, category.name(),
                entry.width(), entry.height(), entry.length(), schematic);
        entries = entries.stream().map(known -> known.equals(entry) ? moved : known).toList();
        return moved;
    }

    /** One category by name, or null when nothing is called that. */
    @Nullable Category category(String name) {
        for (Category category : categories) {
            if (category.name().equalsIgnoreCase(name)) {
                return category;
            }
        }
        return null;
    }

    /**
     * How big a tree reads as: its height plus its widest side. Neither alone tells
     * them apart - a fir is tall and thin where an oak is short and broad - but the two
     * added up sorts them the way a builder would, give or take a step.
     */
    static int sizeOf(int width, int height, int length) {
        return height + Math.max(width, length);
    }

    /** The category a tree of this size belongs in: small enough and narrow enough to fit. */
    Category categoryFor(int width, int height, int length) {
        int side = Math.max(width, length);
        for (Category category : categories) {
            if (sizeOf(width, height, length) <= category.maxSize() && side <= category.plotSize()) {
                return category;
            }
        }
        return categories.getLast();
    }

    /** Whether one word says something true about this tree: its type, size, creator or name. */
    static boolean matches(Entry entry, String word) {
        return entry.type().equals(word)
                || entry.category().equals(word)
                || entry.creator().name().toLowerCase(Locale.ROOT).contains(word)
                || entry.id().contains(word);
    }

    /**
     * Copies the selection into the archive, with the origin on the trunk block.
     *
     * @param size the category to file it under, instead of the one its size asks for
     * @param replacing an archived tree this one takes the place of; it keeps its name
     *                  when type, size and creator stay the same, and is gone either way
     * @return the entry that was written
     */
    Entry archive(Player player, String type, Creator creator, Region region, BlockVector3 trunk,
                  @Nullable Category size, @Nullable Entry replacing) throws WorldEditException, IOException {
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;
        Category category = size != null ? size : categoryFor(width, height, length);

        CuboidRegion box = new CuboidRegion(world, min, max);
        Clipboard clipboard = new BlockArrayClipboard(box);
        String base = baseId(type, category, creator);
        String id = replacing != null && replacing.id().matches(Pattern.quote(base) + "_\\d+")
                ? replacing.id() : freeId(base);
        if (replacing != null) {
            Clipboard kept = cache.remove(replacing.id());
            if (kept != null) {
                kept.close();
            }
        }
        try {
            ForwardExtentCopy copy = new ForwardExtentCopy(world, box, clipboard, min);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
            clipboard.setOrigin(trunk);

            File schematic = new File(folder(), id + ".schem");
            try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
                    .getWriter(new FileOutputStream(schematic))) {
                writer.write(clipboard);
            }

            YamlConfiguration meta = new YamlConfiguration();
            meta.set("id", id);
            meta.set("type", type);
            meta.set("creator.name", creator.name());
            // A player is identified by their uuid, an artist by the link to them.
            meta.set(creator.patreon() ? "creator.link" : "creator.uuid", creator.reference());
            meta.set("category", category.name());
            meta.set("size.width", width);
            meta.set("size.height", height);
            meta.set("size.length", length);
            // The trunk inside the schematic, counted from its lowest corner.
            meta.set("origin.x", trunk.x() - min.x());
            meta.set("origin.y", trunk.y() - min.y());
            meta.set("origin.z", trunk.z() - min.z());
            meta.set("archived.by", player.getName());
            meta.set("archived.at", Instant.now().toString());
            meta.set("archived.world", player.getWorld().getName());
            meta.set("archived.from", min.x() + "," + min.y() + "," + min.z()
                    + " to " + max.x() + "," + max.y() + "," + max.z());
            meta.save(new File(folder(), id + ".yml"));

            Entry entry = new Entry(id, type, creator, category.name(), width, height, length, schematic);
            List<Entry> updated = new ArrayList<>(entries);
            if (replacing != null) {
                updated.remove(replacing);
                // Under a new name the old files would stay behind as a second tree.
                if (!id.equals(replacing.id())) {
                    replacing.schematic().delete();
                    new File(folder(), replacing.id() + ".yml").delete();
                }
            }
            updated.add(entry);
            entries = List.copyOf(updated);
            return entry;
        } finally {
            clipboard.close();
        }
    }

    /** Loads an entry's blueprint, or null when the schematic is gone or unreadable. */
    @Nullable Clipboard load(Entry entry) {
        ClipboardFormat format = ClipboardFormats.findByFile(entry.schematic());
        if (format == null) {
            return null;
        }
        try (InputStream in = new FileInputStream(entry.schematic());
             ClipboardReader reader = format.getReader(in)) {
            return reader.read();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read " + entry.schematic() + ": " + e.getMessage());
            return null;
        }
    }

    private @Nullable Entry read(File file) {
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(file);
        String id = meta.getString("id", file.getName().replaceAll("\\.yml$", ""));
        File schematic = new File(folder(), id + ".schem");
        if (!schematic.isFile()) {
            plugin.getLogger().warning("Archived tree " + id + " has no schematic next to it.");
            return null;
        }
        String link = meta.getString("creator.link", "");
        return new Entry(id,
                meta.getString("type", "unsorted"),
                new Creator(meta.getString("creator.name", "unknown"), !link.isEmpty(),
                        link.isEmpty() ? meta.getString("creator.uuid", "") : link),
                meta.getString("category", "medium"),
                meta.getInt("size.width"),
                meta.getInt("size.height"),
                meta.getInt("size.length"),
                schematic);
    }

    /**
     * The name a tree gets: what it is, how big, who built it, and which one of theirs
     * it is - birch_medium_snifferish_1. Counting up from one leaves whatever is already
     * in the archive alone, so archiving never overwrites.
     */
    private String freeId(String base) {
        for (int number = 1; ; number++) {
            String id = base + "_" + number;
            if (!new File(folder(), id + ".schem").exists()) {
                return id;
            }
        }
    }

    /** A tree's name without its number: type_size_creator. */
    private static String baseId(String type, Category category, Creator creator) {
        return slug(type) + "_" + slug(category.name()) + "_" + slug(creator.name());
    }

    /** A word fit for a file name: lowercase, with underscores for anything else. */
    private static String slug(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "unnamed" : slug;
    }
}
