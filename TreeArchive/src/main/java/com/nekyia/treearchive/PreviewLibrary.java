package com.nekyia.treearchive;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * What previews are kept as: presets, built up one block at a time and so as long as they
 * need to be - on each player's own shelf, theirs alone to change, or in namespaces
 * everyone shares - and hashes: short names for preview commands, for links that would
 * not fit into a chat line, and for keeping.
 */
final class PreviewLibrary {

    /** Where presets are kept: a player's own shelf, or a namespace. */
    record Shelf(@Nullable UUID player, @Nullable String namespace) {

        static Shelf of(UUID player) {
            return new Shelf(player, null);
        }

        static Shelf namespace(String namespace) {
            return new Shelf(null, namespace);
        }
    }

    private final JavaPlugin plugin;
    private final File presetsFile;
    private final File namespacesFile;
    private final File hashesFile;
    /** Player uuid -> name: their last name, presets: name -> parts. */
    private final YamlConfiguration presets;
    /** Namespace -> name -> parts. */
    private final YamlConfiguration namespaces;
    private final YamlConfiguration hashes;

    PreviewLibrary(JavaPlugin plugin) {
        this.plugin = plugin;
        presetsFile = new File(plugin.getDataFolder(), "presets.yml");
        namespacesFile = new File(plugin.getDataFolder(), "namespaces.yml");
        hashesFile = new File(plugin.getDataFolder(), "hashes.yml");
        presets = YamlConfiguration.loadConfiguration(presetsFile);
        namespaces = YamlConfiguration.loadConfiguration(namespacesFile);
        hashes = YamlConfiguration.loadConfiguration(hashesFile);
    }

    /** The player who keeps presets under this name, or null. */
    @Nullable UUID owner(String name) {
        for (String uuid : presets.getKeys(false)) {
            if (name.equalsIgnoreCase(presets.getString(uuid + ".name"))) {
                return UUID.fromString(uuid);
            }
        }
        return null;
    }

    /** The name a player's presets are kept under. */
    String ownerName(UUID owner) {
        return presets.getString(owner + ".name", owner.toString());
    }

    Set<String> namespaces() {
        return namespaces.getKeys(false);
    }

    Set<String> presets(Shelf shelf) {
        ConfigurationSection section = yaml(shelf).getConfigurationSection(path(shelf));
        return section == null ? Set.of() : section.getKeys(false);
    }

    /** A preset's parts - one for each block or group of blocks, and flags - or null when there is none. */
    @Nullable List<String> preset(Shelf shelf, String name) {
        String path = path(shelf) + "." + name;
        return yaml(shelf).contains(path) ? yaml(shelf).getStringList(path) : null;
    }

    /**
     * Adds a part to a preset: one block's part, or a flag like --limit 10000. It takes the
     * place of an earlier part for the same blocks, or of the same flag.
     */
    void add(Shelf shelf, Player editor, String name, String part) {
        String path = path(shelf) + "." + name;
        List<String> parts = new ArrayList<>(yaml(shelf).getStringList(path));
        parts.removeIf(old -> same(old, part));
        parts.add(part);
        if (shelf.player() != null) {
            presets.set(shelf.player() + ".name", editor.getName());
        }
        yaml(shelf).set(path, parts);
        save(shelf);
    }

    /**
     * Takes the parts for this block, or this flag, out of a preset - or the whole preset
     * without either; false if nothing was there.
     */
    boolean remove(Shelf shelf, String name, @Nullable String what) {
        String path = path(shelf) + "." + name;
        YamlConfiguration yaml = yaml(shelf);
        if (!yaml.contains(path)) {
            return false;
        }
        if (what == null) {
            yaml.set(path, null);
        } else {
            List<String> parts = new ArrayList<>(yaml.getStringList(path));
            Material block = isFlag(what) ? null : Material.matchMaterial(what);
            if (!parts.removeIf(part -> isFlag(part) ? part.split(" ")[0].equals(what)
                    : block != null && PreviewSpec.parse(part).markers().contains(block))) {
                return false;
            }
            yaml.set(path, parts.isEmpty() ? null : parts);
        }
        // A namespace without presets is gone.
        if (shelf.namespace() != null && presets(shelf).isEmpty()) {
            namespaces.set(shelf.namespace(), null);
        }
        save(shelf);
        return true;
    }

    /** A flag of a preset, like --limit 10000, rather than a block's part. */
    static boolean isFlag(String part) {
        return part.startsWith("--");
    }

    /** Whether one part stands in the other's place: the same flag, or a part for some of the same blocks. */
    private static boolean same(String old, String part) {
        if (isFlag(old) || isFlag(part)) {
            return isFlag(old) && isFlag(part) && old.split(" ")[0].equals(part.split(" ")[0]);
        }
        Set<Material> blocks = PreviewSpec.parse(part).markers();
        return PreviewSpec.parse(old).markers().stream().anyMatch(blocks::contains);
    }

    private YamlConfiguration yaml(Shelf shelf) {
        return shelf.player() != null ? presets : namespaces;
    }

    private String path(Shelf shelf) {
        return shelf.player() != null ? shelf.player() + ".presets" : String.valueOf(shelf.namespace());
    }

    private void save(Shelf shelf) {
        save(yaml(shelf), shelf.player() != null ? presetsFile : namespacesFile);
    }

    /**
     * A short name for these preview arguments, the same every time for the same ones:
     * the start of their SHA-256 in base 36, made longer only if it would clash.
     */
    String hash(String arguments) {
        String digits;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(arguments.getBytes(StandardCharsets.UTF_8));
            digits = new BigInteger(1, digest).toString(36);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java without SHA-256", e);
        }
        for (int length = 8; ; length++) {
            String hash = digits.substring(0, Math.min(length, digits.length()));
            String known = hashes.getString(hash);
            if (known == null) {
                hashes.set(hash, arguments);
                save(hashes, hashesFile);
                return hash;
            }
            if (known.equals(arguments) || length >= digits.length()) {
                return hash;
            }
        }
    }

    /** The preview arguments behind a hash, or null. */
    @Nullable String unhash(String hash) {
        return hashes.getString(hash);
    }

    private void save(YamlConfiguration file, File where) {
        try {
            file.save(where);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + where.getName() + ": " + e.getMessage());
        }
    }
}
