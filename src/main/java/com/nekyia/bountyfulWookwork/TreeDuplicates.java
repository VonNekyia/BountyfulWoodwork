package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Finds archived trees that are the same tree twice, however they were turned when they
 * were built. A square has eight symmetries - four quarter turns, each of them also
 * mirrored - so every tree is fingerprinted under all eight and keeps the smallest of
 * the eight as its own. Two trees that are the same up to turning or mirroring then
 * carry the same fingerprint, whichever way round they were archived.
 *
 * <p>The fingerprint is taken from the blocks around the trunk, counting only which
 * block sits where and not which way it faces - turning a tree turns its logs and
 * stairs with it, and those would otherwise hide the very match being looked for.
 */
final class TreeDuplicates {

    private final JavaPlugin plugin;
    private final TreeArchive archive;

    private @Nullable BukkitTask task;

    TreeDuplicates(JavaPlugin plugin, TreeArchive archive) {
        this.plugin = plugin;
        this.archive = archive;
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

    /** Reads every blueprint, a few per tick, and reports the trees that repeat. */
    void start(CommandSender sender) {
        List<TreeArchive.Entry> entries = archive.entries();
        if (entries.size() < 2) {
            sender.sendMessage(Component.text("There are not two trees to compare yet.",
                    NamedTextColor.YELLOW));
            return;
        }

        stop();
        Deque<TreeArchive.Entry> pending = new ArrayDeque<>(entries);
        // Kept in the order they were first seen, so the report reads alike every run.
        Map<Long, List<TreeArchive.Entry>> byFingerprint = new LinkedHashMap<>();
        int[] unreadable = {0};
        long budget = Math.max(1, plugin.getConfig().getLong("duplicates.tick-budget-ms", 10))
                * 1_000_000L;

        sender.sendMessage(Component.text("Comparing " + entries.size()
                + " trees, turned and mirrored every way.", NamedTextColor.GREEN));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long deadline = System.nanoTime() + budget;
            do {
                TreeArchive.Entry entry = pending.poll();
                if (entry == null) {
                    report(sender, byFingerprint, unreadable[0]);
                    stop();
                    return;
                }
                Long fingerprint = fingerprint(entry);
                if (fingerprint == null) {
                    unreadable[0]++;
                    continue;
                }
                byFingerprint.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(entry);
            } while (System.nanoTime() < deadline);
        }, 1, 1);
    }

    private void report(CommandSender sender, Map<Long, List<TreeArchive.Entry>> byFingerprint,
                        int unreadable) {
        List<List<TreeArchive.Entry>> groups = byFingerprint.values().stream()
                .filter(group -> group.size() > 1)
                .toList();
        if (groups.isEmpty()) {
            sender.sendMessage(Component.text("No tree is in the archive twice.",
                    NamedTextColor.GREEN));
        } else {
            int copies = groups.stream().mapToInt(group -> group.size() - 1).sum();
            sender.sendMessage(Component.text(copies + " tree" + (copies == 1 ? "" : "s")
                    + " are already in the archive, in " + groups.size() + " group"
                    + (groups.size() == 1 ? "" : "s") + ":", NamedTextColor.YELLOW));
            for (List<TreeArchive.Entry> group : groups) {
                sender.sendMessage(Component.text("  " + group.stream()
                        .map(TreeArchive.Entry::id).reduce((a, b) -> a + " = " + b).orElse(""),
                        NamedTextColor.GRAY));
            }
        }
        if (unreadable > 0) {
            sender.sendMessage(Component.text(unreadable + " blueprints could not be read.",
                    NamedTextColor.RED));
        }
    }

    /**
     * The smallest of the eight fingerprints a tree has, one per way of turning and
     * mirroring it. Null when its blueprint cannot be read.
     */
    private @Nullable Long fingerprint(TreeArchive.Entry entry) {
        Clipboard clipboard = archive.load(entry);
        if (clipboard == null) {
            return null;
        }
        try {
            // Measured from the blocks themselves, not from the selection or the trunk
            // mark: the same tree saved with more air around it, or with its trunk
            // marked a block over, is still the same tree.
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockVector3 position : clipboard.getRegion()) {
                if (clipboard.getBlock(position).getBlockType().getMaterial().isAir()) {
                    continue;
                }
                minX = Math.min(minX, position.x());
                minY = Math.min(minY, position.y());
                minZ = Math.min(minZ, position.z());
                maxX = Math.max(maxX, position.x());
                maxZ = Math.max(maxZ, position.z());
            }
            if (maxX == Integer.MIN_VALUE) {
                return null;
            }
            // Doubled, so a middle between two blocks stays a whole number.
            int middleX = minX + maxX;
            int middleZ = minZ + maxZ;

            long[] hashes = new long[8];
            for (BlockVector3 position : clipboard.getRegion()) {
                BlockType type = clipboard.getBlock(position).getBlockType();
                if (type.getMaterial().isAir()) {
                    continue;
                }
                int id = type.id().hashCode();
                int x = 2 * position.x() - middleX;
                int y = position.y() - minY;
                int z = 2 * position.z() - middleZ;
                for (int turn = 0; turn < 4; turn++) {
                    // A quarter turn about the trunk: (x, z) -> (-z, x).
                    int spun = x;
                    x = -z;
                    z = spun;
                    // The same turn, and the same turn mirrored across x.
                    hashes[turn] += mix(x, y, z, id);
                    hashes[4 + turn] += mix(-x, y, z, id);
                }
            }

            long smallest = Long.MAX_VALUE;
            for (long hash : hashes) {
                smallest = Math.min(smallest, hash);
            }
            return smallest;
        } finally {
            TreeArchive.release(clipboard);
        }
    }

    /**
     * One block's share of a fingerprint. The shares are added up, so the blocks may
     * come in any order; the mixing is what keeps different trees apart.
     */
    private static long mix(int x, int y, int z, int block) {
        long value = (long) x * 0x9E3779B97F4A7C15L
                ^ (long) y * 0xC2B2AE3D27D4EB4FL
                ^ (long) z * 0x165667B19E3779F9L
                ^ (long) block * 0x27D4EB2F165667C5L;
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        return value;
    }
}
