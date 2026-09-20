package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
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
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Places tree schematics into the world and registers them as trees. Trees never take
 * priority over what is already there: the trunk needs free space or the tree is not
 * placed at all, and leaves only fill air.
 */
final class TreePaster {

    private final TreeSchematics schematics;
    private final TreeRegistry registry;

    TreePaster(TreeSchematics schematics, TreeRegistry registry) {
        this.schematics = schematics;
        this.registry = registry;
    }

    /** One block of the tree, rotated and at its world position. */
    private record Placement(int x, int y, int z, BaseBlock block) {
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
        List<Placement> placements = plan(clipboard, base, intoTrees);
        if (placements == null) {
            return false;
        }

        World world = base.getWorld();
        Map<Long, Material> blocks = register ? new HashMap<>() : Map.of();
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            for (Placement placement : placements) {
                session.setBlock(placement.x(), placement.y(), placement.z(), placement.block());
                if (register) {
                    blocks.put(BlockKeys.of(placement.x(), placement.y(), placement.z()),
                            BukkitAdapter.adapt(placement.block().getBlockType()));
                }
            }
            if (player != null) {
                WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player)).remember(session);
            }
        }
        if (register) {
            registry.register(world, type, blocks);
        }
        return true;
    }

    /**
     * Works out every block to set, reading the world before anything changes.
     * Returns null when the trunk would collide, so nothing of the tree is placed.
     */
    private @Nullable List<Placement> plan(Clipboard clipboard, Block base, boolean intoTrees) {
        AffineTransform transform = new AffineTransform();
        if (schematics.randomRotation()) {
            transform = transform.rotateY(90 * ThreadLocalRandom.current().nextInt(4));
        }
        boolean overwrite = schematics.overwriteBlocks();

        World world = base.getWorld();
        BlockVector3 origin = clipboard.getOrigin();
        int bottom = clipboard.getRegion().getMinimumPoint().y();

        List<Placement> placements = new ArrayList<>();
        for (BlockVector3 position : clipboard.getRegion()) {
            BaseBlock block = clipboard.getFullBlock(position);
            if (block.getBlockType().getMaterial().isAir()) {
                continue;
            }
            boolean leaves = block.getBlockType().id().endsWith("_leaves");

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
            if (!overwrite) {
                boolean free = intoTrees && isTree(target);
                if (leaves && !free && !target.isEmpty()) {
                    continue;
                }
                if (!leaves && !free && !canHoldTrunk(target)) {
                    return null;
                }
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

    /**
     * Whether a trunk block may go here: air, a sapling, or a plant a sapling could be
     * planted over, like grass or flowers. Liquids and everything solid block it.
     */
    /** Whether this is part of some tree: a log or leaves. */
    private static boolean isTree(Block block) {
        return Tag.LOGS.isTagged(block.getType()) || Tag.LEAVES.isTagged(block.getType());
    }

    static boolean canHoldTrunk(Block block) {
        return block.isEmpty()
                || Tag.SAPLINGS.isTagged(block.getType())
                || (block.isReplaceable() && !block.isLiquid());
    }
}
