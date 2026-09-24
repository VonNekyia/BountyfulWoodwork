package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Places tree schematics into the world and registers them as trees. Trees never take
 * priority over what is already there, with one exception: what is soft may go. The
 * body of the tree - logs, wood, anything built - needs room, or the tree is not placed
 * at all; room being air, leaves, grass, flowers and the like. The soft parts of the
 * tree itself - its leaves, the grass and vines around its foot - take only such soft
 * spots and are left out wherever something firmer stands.
 */
final class TreePaster {

    private final JavaPlugin plugin;
    private final TreeRegistry registry;

    TreePaster(JavaPlugin plugin, TreeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** One block of the tree, rotated and at its world position. */
    record Placement(int x, int y, int z, BaseBlock block) {
    }

    /**
     * Places a tree of the given type with its schematic origin on {@code base}. When
     * a player placed it, it goes into their WorldEdit history so //undo takes it back.
     *
     * @return whether the tree was placed; false when its trunk has no room
     */
    boolean paste(String type, Clipboard clipboard, Block base, @Nullable Player player) {
        return paste(type, clipboard, base, player, false, true);
    }

    /**
     * As above, but {@code intoTrees} lets the tree grow through leaves and logs, and
     * {@code register} can be turned off. Both are for replacing the trees of a
     * generated forest: the neighbours are still standing, so nothing would ever fit,
     * and remembering thousands of throwaway trees would only bloat the chunk data.
     */
    boolean paste(String type, Clipboard clipboard, Block base, @Nullable Player player,
                  boolean intoTrees, boolean register) {
        List<Placement> placements = plan(clipboard, base, intoTrees,
                turns(ThreadLocalRandom.current()), Set.of());
        if (placements == null) {
            return false;
        }

        World world = base.getWorld();
        Map<Long, Material> blocks = register ? new HashMap<>() : Map.of();
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            for (Placement placement : placements) {
                session.setBlock(BlockVector3.at(placement.x(), placement.y(), placement.z()), placement.block());
                if (register) {
                    blocks.put(BlockKeys.of(placement.x(), placement.y(), placement.z()),
                            BukkitAdapter.adapt(placement.block().getBlockType()));
                }
            }
            if (player != null) {
                WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player)).remember(session);
            }
        } catch (WorldEditException e) {
            plugin.getLogger().warning("Could not place a " + type + " tree: " + e.getMessage());
            return false;
        }
        if (register) {
            registry.register(world, type, blocks);
        }
        return true;
    }

    /** How many quarter turns the next tree gets: a random number, unless turning is off. */
    int turns(RandomGenerator random) {
        return plugin.getConfig().getBoolean("random-rotation", true) ? random.nextInt(4) : 0;
    }

    /**
     * Works out every block to set, reading the world before anything changes.
     * Returns null when the trunk would collide, so nothing of the tree is placed.
     * Blocks in {@code alsoSoft} give way like grass does - the marker blocks of a
     * preview, which the trees stand in.
     */
    @Nullable List<Placement> plan(Clipboard clipboard, Block base, boolean intoTrees, int turns,
                                   Set<Material> alsoSoft) {
        AffineTransform transform = new AffineTransform().rotateY(90 * turns);
        boolean overwrite = plugin.getConfig().getBoolean("overwrite-blocks", false);

        World world = base.getWorld();
        BlockVector3 origin = clipboard.getOrigin();
        int bottom = clipboard.getRegion().getMinimumPoint().y();

        List<Placement> placements = new ArrayList<>();
        for (BlockVector3 position : clipboard.getRegion()) {
            BaseBlock block = clipboard.getFullBlock(position);
            if (block.getBlockType().getMaterial().isAir()) {
                continue;
            }
            // Soft parts yield; the body has to fit.
            boolean leaves = soft(BukkitAdapter.adapt(block.getBlockType()));

            // Rotating by quarter turns leaves tiny floating point errors, hence the rounding.
            Vector3 offset = transform.apply(position.subtract(origin).toVector3());
            int x = base.getX() + (int) Math.round(offset.x());
            int y = base.getY() + (int) Math.round(offset.y());
            int z = base.getZ() + (int) Math.round(offset.z());
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                if (leaves) {
                    continue;
                }
                return null;
            }

            Block target = world.getBlockAt(x, y, z);
            if (!overwrite && !canHoldTrunk(target) && !alsoSoft.contains(target.getType())
                    && !(intoTrees && Tag.LOGS.isTagged(target.getType()))) {
                if (leaves) {
                    continue;
                }
                return null;
            }

            BaseBlock rotated = BlockTransformExtent.transform(block, transform);
            placements.add(new Placement(x, y, z, rotated));

            // Where the bottom of the trunk hangs over air, it reaches one block further down.
            if (!leaves && position.y() == bottom && y - 1 >= world.getMinHeight()
                    && target.getRelative(BlockFace.DOWN).isEmpty()) {
                placements.add(new Placement(x, y - 1, z, rotated));
            }
        }
        return placements;
    }

    /** What a tree may grow over: air, leaves, or a plant. Liquids and anything firm stop it. */
    static boolean canHoldTrunk(Block block) {
        return !block.isLiquid() && soft(block.getType());
    }

    /** Air, leaves, or a plant - the parts of a tree that give way, and what gives way to a tree. */
    static boolean soft(Material material) {
        return material.isAir()
                || Tag.LEAVES.isTagged(material)
                || Tag.SAPLINGS.isTagged(material)
                || Tag.FLOWERS.isTagged(material)
                || Tag.REPLACEABLE.isTagged(material);
    }
}
