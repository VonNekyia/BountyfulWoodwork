package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Debug helper for looking at whole forests: while a selection is set - written like
 * /bw layout terrain takes it - the vanilla trees of newly generated chunks are torn
 * out and replaced by archived trees from it. Chunks that already exist are left alone, so fly into fresh land to see it.
 *
 * <p>The work is split in two. Clearing a chunk is cheap and happens early, so no
 * vanilla tree is left standing; the trunk positions then wait in a queue and the trees
 * are pasted a few at a time. Both share one time budget per tick, so the forest fills
 * in slowly instead of freezing the server.
 *
 * <p>The replacements may grow through leaves and logs, since neighbouring trees are
 * still standing, and they are not registered as fellable: this is for looking at, and
 * a whole forest of them would fill the chunk data for nothing.
 */
final class DebugGeneration implements Listener {

    /** How far below the top of a column a trunk is looked for. */
    private static final int TRUNK_SEARCH_DEPTH = 48;
    /** Queue limits; beyond them the oldest work is dropped rather than piling up. */
    private static final int MAX_PENDING_CHUNKS = 512;
    private static final int MAX_PENDING_TRUNKS = 4096;

    private final JavaPlugin plugin;
    private final TreeArchive archive;
    private final TreePaster paster;

    private final Deque<Chunk> pendingChunks = new ArrayDeque<>();
    private final Deque<Block> pendingTrunks = new ArrayDeque<>();
    private @Nullable BukkitTask task;

    private @Nullable String type;
    /** Who turned it on, to tell them how it is going. */
    private @Nullable UUID watcher;

    private long budget = 2_000_000L;
    private int treesPerChunk = 16;

    private int replaced;
    private int blocked;
    private int dropped;
    private int reported;

    DebugGeneration(JavaPlugin plugin, TreeArchive archive, TreePaster paster) {
        this.plugin = plugin;
        this.archive = archive;
        this.paster = paster;
    }

    /** The tree type new chunks are filled with, or null while this is off. */
    @Nullable String type() {
        return type;
    }

    /** Sets the type, or null to turn it off; the counts start over either way. */
    void type(@Nullable String type, @Nullable UUID watcher) {
        this.type = type;
        this.watcher = watcher;
        replaced = 0;
        blocked = 0;
        dropped = 0;
        reported = 0;
        pendingChunks.clear();
        pendingTrunks.clear();

        budget = Math.max(1, plugin.getConfig().getLong("debug-generation.tick-budget-ms", 2)) * 1_000_000L;
        treesPerChunk = Math.max(1, plugin.getConfig().getInt("debug-generation.trees-per-chunk", 16));

        if (task != null) {
            task.cancel();
            task = null;
        }
        if (type != null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    /** What happened so far: trees replaced, trees without room, work dropped. */
    int[] counts() {
        return new int[] {replaced, blocked, dropped};
    }

    void stop() {
        type(null, null);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (type == null || !event.isNewChunk()) {
            return;
        }
        // Queued rather than done here: flying fast hands over many chunks per tick, and
        // pasting into a chunk that is still generating pulls its neighbours in as well.
        if (pendingChunks.size() >= MAX_PENDING_CHUNKS) {
            pendingChunks.poll();
            dropped++;
        }
        pendingChunks.add(event.getChunk());
    }

    /** Spends the tick's budget, taking turns between clearing chunks and placing trees. */
    private void tick() {
        String treeType = type;
        if (treeType == null) {
            return;
        }
        long deadline = System.nanoTime() + budget;
        boolean worked = false;
        boolean placeTurn = false;
        while (true) {
            if (placeTurn && !pendingTrunks.isEmpty()) {
                place(treeType, pendingTrunks.poll());
            } else if (!pendingChunks.isEmpty()) {
                prepare(pendingChunks.poll());
            } else if (!pendingTrunks.isEmpty()) {
                place(treeType, pendingTrunks.poll());
            } else {
                break;
            }
            worked = true;
            placeTurn = !placeTurn;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        if (worked) {
            report();
        }
    }

    /** Reads a chunk once, remembers where its trunks stand and takes the old trees out. */
    private void prepare(Chunk chunk) {
        if (!chunk.isLoaded()) {
            return;
        }
        World world = chunk.getWorld();
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int floor = world.getMinHeight() + 1;
        int trunks = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = snapshot.getHighestBlockYAt(x, z);
                int bottom = Math.max(floor, top - TRUNK_SEARCH_DEPTH);
                for (int y = top; y >= bottom; y--) {
                    Material material = snapshot.getBlockType(x, y, z);
                    Material below = snapshot.getBlockType(x, Math.max(floor, y - 1), z);
                    if (partOfTree(material) || restsOnTree(material, below)) {
                        world.getBlockAt(baseX + x, y, baseZ + z).setType(Material.AIR, false);
                        if (Tag.LOGS.isTagged(material) && growsOn(below)) {
                            if (trunks < treesPerChunk) {
                                remember(world.getBlockAt(baseX + x, y, baseZ + z));
                                trunks++;
                            }
                            break;
                        }
                    } else if (material.isSolid()) {
                        break; // the ground under the trees
                    }
                }
            }
        }
    }

    private void remember(Block trunk) {
        if (pendingTrunks.size() >= MAX_PENDING_TRUNKS) {
            pendingTrunks.poll();
            dropped++;
        }
        pendingTrunks.add(trunk);
    }

    private void place(String selection, Block trunk) {
        TreeArchive.Entry entry = TreeSelection.parse(selection).pick(archive.entries());
        Clipboard clipboard = entry == null ? null : archive.clipboard(entry);
        // Grows through leaves and logs, and stays unregistered: neighbouring trees are
        // still standing, and this is only meant to be looked at.
        if (clipboard != null && paster.paste(entry.type(), clipboard, trunk, null, true, false)) {
            replaced++;
        } else {
            blocked++;
        }
    }

    private static boolean partOfTree(Material material) {
        return Tag.LOGS.isTagged(material) || Tag.LEAVES.isTagged(material);
    }

    /**
     * Whether this block was only held up by the tree below it: the snow on its leaves,
     * vines, cocoa, a bee nest. Left behind, they hang in the air.
     */
    private static boolean restsOnTree(Material material, Material below) {
        if (!partOfTree(below)) {
            return false;
        }
        return !material.isSolid() || material == Material.BEE_NEST || material == Material.BEEHIVE;
    }

    private static boolean growsOn(Material material) {
        return Tag.DIRT.isTagged(material) || Tag.SAND.isTagged(material);
    }

    /** Tells whoever turned this on how it is going. */
    private void report() {
        Player player = watcher == null ? null : plugin.getServer().getPlayer(watcher);
        if (player == null || replaced + blocked == reported) {
            return;
        }
        reported = replaced + blocked;
        player.sendActionBar(Component.text(replaced + " trees placed, " + blocked + " had no room, "
                + pendingTrunks.size() + " waiting, " + pendingChunks.size() + " chunks to clear",
                NamedTextColor.GRAY));
    }
}
