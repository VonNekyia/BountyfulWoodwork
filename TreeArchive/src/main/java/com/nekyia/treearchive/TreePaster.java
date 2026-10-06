package com.nekyia.treearchive;

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
import java.util.Collections;
import java.util.EnumSet;
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
 * Places tree schematics into the world. Trees never take
 * priority over what is already there, with one exception: what is soft may go. The
 * body of the tree - logs, wood, anything built - needs room, or the tree is not placed
 * at all; room being air, leaves, grass, flowers and the like. The soft parts of the
 * tree itself - its leaves, the grass and vines around its foot - take only such soft
 * spots and are left out wherever something firmer stands.
 */
final class TreePaster {

    /** About one leaf in six becomes mistletoe, as on the birches in the archive. */
    private static final double MISTLE_SHARE = 0.18;
    /** And most of it, about three in five, has a bush on top. */
    private static final double MISTLE_BUSH_SHARE = 0.62;

    private final JavaPlugin plugin;

    TreePaster(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** One block of the tree, rotated and at its world position. */
    record Placement(int x, int y, int z, BaseBlock block) {
    }

    /** Mistletoe, by the modifier asking for it: the block in place of a leaf, and what may grow on it. */
    enum Mistletoe {
        GREEN("mistle", Material.MOSS_BLOCK, List.of(Material.BUSH)),
        DRY("drymistle", Material.MANGROVE_ROOTS, List.of(Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS));

        final String modifier;
        final Material clump;
        final List<Material> tops;

        Mistletoe(String modifier, Material clump, List<Material> tops) {
            this.modifier = modifier;
            this.clump = clump;
            this.tops = tops;
        }
    }

    /**
     * Places a tree of the given type with its schematic origin on {@code base}. When
     * a player placed it, it goes into their WorldEdit history so //undo takes it back.
     *
     * @return whether the tree was placed; false when its trunk has no room
     */
    boolean paste(String type, Clipboard clipboard, Block base, @Nullable Player player) {
        return paste(type, clipboard, base, player, false);
    }

    /**
     * As above, but {@code intoTrees} lets the tree grow through leaves and logs - for
     * replacing the trees of a generated forest, where the neighbours are still
     * standing and nothing would ever fit otherwise.
     */
    boolean paste(String type, Clipboard clipboard, Block base, @Nullable Player player, boolean intoTrees) {
        return paste(type, clipboard, base, player, intoTrees, Set.of());
    }

    /** As above, with the mistletoe the tree's modifiers ask for hung in the crown. */
    boolean paste(String type, Clipboard clipboard, Block base, @Nullable Player player, boolean intoTrees,
                  Set<String> modifiers) {
        List<Placement> placements = plan(clipboard, base, intoTrees,
                turns(ThreadLocalRandom.current()), Set.of(), false);
        if (placements == null) {
            return false;
        }
        placements = mistle(placements, modifiers, base.getWorld(), ThreadLocalRandom.current(), false);

        World world = base.getWorld();
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            for (Placement placement : placements) {
                session.setBlock(BlockVector3.at(placement.x(), placement.y(), placement.z()), placement.block());
            }
            if (player != null) {
                WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player)).remember(session);
            }
        } catch (WorldEditException e) {
            plugin.getLogger().warning("Could not place a " + type + " tree: " + e.getMessage());
            return false;
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
     * preview, which the trees stand in. {@code bypassTerrain} plans the whole tree
     * whatever stands in its way, leaving out only what is beyond the world's height.
     */
    @Nullable List<Placement> plan(Clipboard clipboard, Block base, boolean intoTrees, int turns,
                                   Set<Material> alsoSoft, boolean bypassTerrain) {
        AffineTransform transform = new AffineTransform().rotateY(90 * turns);
        boolean overwrite = bypassTerrain || plugin.getConfig().getBoolean("overwrite-blocks", false);

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
                if (leaves || bypassTerrain) {
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

    /** Hangs the mistletoe these modifiers ask for, if any, in the crown. */
    List<Placement> mistle(List<Placement> placements, Set<String> modifiers, World world, RandomGenerator random,
                           boolean bypassTerrain) {
        for (Mistletoe kind : Mistletoe.values()) {
            if (modifiers.contains(kind.modifier)) {
                placements = mistle(placements, kind, world, random, bypassTerrain);
            }
        }
        return placements;
    }

    /**
     * Mistletoe the way the birches in the archive have it: a clump - moss, or mangrove
     * roots when dry - in place of a leaf that lies open above, about one for every six
     * leaves, most with a bush or dry grass on top: where there is room for one, or
     * anywhere with {@code bypassTerrain}. Dry grass does not hold on bare roots, so in the
     * world a block update next to it can knock it off.
     */
    private List<Placement> mistle(List<Placement> placements, Mistletoe kind, World world, RandomGenerator random,
                                   boolean bypassTerrain) {
        Map<Long, Integer> taken = new HashMap<>();
        for (int i = 0; i < placements.size(); i++) {
            Placement placement = placements.get(i);
            taken.put(BlockKeys.of(placement.x(), placement.y(), placement.z()), i);
        }
        List<Integer> open = new ArrayList<>();
        int leaves = 0;
        for (int i = 0; i < placements.size(); i++) {
            Placement placement = placements.get(i);
            if (Tag.LEAVES.isTagged(BukkitAdapter.adapt(placement.block().getBlockType()))) {
                leaves++;
                if (!taken.containsKey(BlockKeys.of(placement.x(), placement.y() + 1, placement.z()))) {
                    open.add(i);
                }
            }
        }
        Collections.shuffle(open, random);
        BaseBlock clump = BukkitAdapter.adapt(kind.clump.createBlockData()).toBaseBlock();

        List<Placement> result = new ArrayList<>(placements);
        int wanted = (int) Math.min(open.size() / 2, Math.round(leaves * MISTLE_SHARE));
        for (int i : open.subList(0, wanted)) {
            Placement leaf = placements.get(i);
            result.set(i, new Placement(leaf.x(), leaf.y(), leaf.z(), clump));
            int above = leaf.y() + 1;
            if (random.nextDouble() < MISTLE_BUSH_SHARE && above < world.getMaxHeight()
                    && (bypassTerrain || canHoldTrunk(world.getBlockAt(leaf.x(), above, leaf.z())))) {
                Material top = kind.tops.get(random.nextInt(kind.tops.size()));
                result.add(new Placement(leaf.x(), above, leaf.z(),
                        BukkitAdapter.adapt(top.createBlockData()).toBaseBlock()));
            }
        }
        return result;
    }

    /** What a tree may grow over: air, leaves, or a plant. Liquids and anything firm stop it. */
    static boolean canHoldTrunk(Block block) {
        return !block.isLiquid() && soft(block.getType());
    }

    /** Moss and the roots of dry mistletoe, which builders hang in crowns and no plant tag counts. */
    private static final Set<Material> CLUMPS = EnumSet.of(Material.MOSS_BLOCK, Material.MOSS_CARPET,
            Material.PALE_MOSS_BLOCK, Material.PALE_MOSS_CARPET, Material.PALE_HANGING_MOSS, Material.MANGROVE_ROOTS);

    /** The leafy parts of a tree - leaves, moss, bushes and other plants - as against its wood. */
    static boolean foliage(Material material) {
        return soft(material) || Tag.REPLACEABLE_BY_TREES.isTagged(material) || CLUMPS.contains(material);
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
