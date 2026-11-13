package com.nekyia.treearchive;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;

/**
 * What can hang on a tree's leaves besides mistletoe, each asked for by a modifier with a
 * strength: {@code &vines=10} on a tenth of the leaves. Vines and glow lichen hold on
 * leaves for good. Cave, weeping and twisting vines and hanging roots need a sturdy face,
 * which leaves do not have: edits beside them leave them be, but when the leaf they hang
 * on changes - a log next to it is enough - the chain falls.
 */
enum TreeExtra {
    VINES("vines", Grows.SIDE, 6, Material.VINE, Material.VINE),
    LICHEN("lichen", Grows.SIDE, 1, Material.GLOW_LICHEN, Material.GLOW_LICHEN),
    GLOWBERRIES("glowberries", Grows.DOWN, 5, Material.CAVE_VINES_PLANT, Material.CAVE_VINES),
    ROOTS("roots", Grows.DOWN, 1, Material.HANGING_ROOTS, Material.HANGING_ROOTS),
    WEEPING("weeping", Grows.DOWN, 6, Material.WEEPING_VINES_PLANT, Material.WEEPING_VINES),
    TWISTING("twisting", Grows.UP, 4, Material.TWISTING_VINES_PLANT, Material.TWISTING_VINES);

    /** From which side of a leaf it starts; vines then hang down, twisting vines rise. */
    enum Grows { SIDE, DOWN, UP }

    /** The strength meant by a modifier without a number, in percent of the leaves. */
    static final int PLAIN = 10;
    /** The highest strength that counts: more would be a curtain, and many ghosts. */
    static final int STRONGEST = 50;
    /**
     * The most blocks one kind adds to one tree. The largest crown in the archive asks for
     * about 2100 at {@link #STRONGEST}, so the sliders count all the way; this only stops
     * a crown far larger than any there is now.
     */
    static final int MOST_BLOCKS = 2500;

    private static final Set<Material> BLOCKS = EnumSet.noneOf(Material.class);

    static {
        for (TreeExtra extra : values()) {
            BLOCKS.add(extra.body);
            BLOCKS.add(extra.tip);
        }
    }

    final String modifier;
    final Grows grows;
    /** The longest chain, in blocks. */
    final int length;
    /** The block of a chain, and the one at its end. */
    final Material body;
    final Material tip;

    TreeExtra(String modifier, Grows grows, int length, Material body, Material tip) {
        this.modifier = modifier;
        this.grows = grows;
        this.length = length;
        this.body = body;
        this.tip = tip;
    }

    /** Whether this block is one of these: foliage to a preview, so a hit on its ghost takes no marker away. */
    static boolean hanging(Material material) {
        return BLOCKS.contains(material);
    }
}
