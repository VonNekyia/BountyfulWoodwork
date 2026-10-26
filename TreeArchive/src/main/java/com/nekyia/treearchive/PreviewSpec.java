package com.nekyia.treearchive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * Which marker blocks become which trees, written
 * {@code gold_block:silver_fir,white_pine/diamond_block:beech}: parts split by slashes,
 * each a list of blocks and, after a colon, the trees they turn into - written the way
 * {@link TreeSelection} reads them. Several blocks may share their trees
 * ({@code gold_block,diamond_block:beech}), blocks may carry shares like trees do
 * ({@code 70%gold_block,30%diamond_block}), and a part without a colon means any tree.
 * A tree may carry a leaf colour for the ColorfulLeaves mod, {@code gold_block:beech*#d98c2b},
 * or a fade, {@code beech*#f5d130>#c0392b}, and behind it modifiers for that tree:
 * {@code beech*#ffffff&bright}.
 */
record PreviewSpec(List<Part> parts) {

    /** Some marker blocks, with their shares, and the trees they become; null for any. */
    record Part(List<Material> blocks, List<Double> weights, @Nullable TreeSelection trees) {
    }

    /**
     * Reads a spec.
     *
     * @throws IllegalArgumentException naming the first word that is not a block
     */
    static PreviewSpec parse(String text) {
        List<Part> parts = new ArrayList<>();
        for (String piece : text.split("/")) {
            if (piece.isBlank()) {
                continue;
            }
            int colon = piece.indexOf(':');
            String blocks = colon < 0 ? piece : piece.substring(0, colon);
            List<Material> materials = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            // The same shares-and-commas reading trees get.
            for (TreeSelection.Part word : TreeSelection.parse(blocks).parts()) {
                Material material = Material.matchMaterial(word.word());
                if (material == null || !material.isBlock() || material.isAir()) {
                    throw new IllegalArgumentException("'" + word.word() + "' is not a block");
                }
                materials.add(material);
                weights.add(word.weight());
            }
            if (materials.isEmpty()) {
                throw new IllegalArgumentException("'" + piece + "' names no block");
            }
            TreeSelection trees = colon < 0 ? null : TreeSelection.parse(piece.substring(colon + 1));
            if (trees != null) {
                check(trees);
            }
            parts.add(new Part(materials, weights, trees));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("name at least one block");
        }
        return new PreviewSpec(List.copyOf(parts));
    }

    /**
     * Checks the trees of a part: colours that read as colours, and modifiers that are
     * modifiers, with what they take.
     *
     * @throws IllegalArgumentException naming what does not fit
     */
    static void check(TreeSelection trees) {
        for (TreeSelection.Part tree : trees.parts()) {
            int star = tree.word().indexOf('*');
            if (star >= 0) {
                throw new IllegalArgumentException("'" + tree.word().substring(star + 1)
                        + "' is not a colour like #d98c2b");
            }
            for (String modifier : tree.modifiers()) {
                String name = TreeSelection.name(modifier);
                String value = name.equals(modifier) ? null : modifier.substring(name.length() + 1);
                if (!TreeSelection.MODIFIERS.contains(name)) {
                    throw new IllegalArgumentException("'&" + modifier + "' is not a modifier; there is "
                            + String.join(", ", TreeSelection.MODIFIERS.stream().map(m -> "&" + m).toList()));
                }
                if (TreeSelection.WITH_COLOUR.contains(name)) {
                    int[] colours = value == null ? null : TreeSelection.colours(value);
                    if (colours == null || colours.length != 1) {
                        throw new IllegalArgumentException("&" + name + " takes one colour, like &" + name + "=#ffb7c5");
                    }
                } else if (value != null && (!TreeSelection.WITH_STRENGTH.contains(name)
                        || !value.matches("[1-9][0-9]?|100"))) {
                    throw new IllegalArgumentException("'&" + modifier + "' takes no value; &mistle, &drymistle"
                            + " and &bloom take one from 1 to 100, like &mistle=30");
                }
            }
            for (String modifier : List.of("bright", "out", "second", "blossom", "bloom")) {
                if (tree.colours() == null && TreeSelection.find(tree.modifiers(), modifier) != null) {
                    throw new IllegalArgumentException("&" + modifier + " goes behind a colour, like "
                            + tree.word() + "*#f5d130>#c0392b&" + modifier);
                }
            }
            if (TreeSelection.find(tree.modifiers(), "bloom") != null
                    && TreeSelection.find(tree.modifiers(), "blossom") == null) {
                throw new IllegalArgumentException("&bloom says how much blossom; give one with &blossom=#ffb7c5");
            }
        }
    }

    /** Every marker block the spec names. */
    Set<Material> markers() {
        Set<Material> markers = new LinkedHashSet<>();
        parts.forEach(part -> markers.addAll(part.blocks()));
        return markers;
    }

    /** The part a marker belongs to; the first one naming it, if several do. */
    @Nullable Part partFor(Material marker) {
        for (Part part : parts) {
            if (part.blocks().contains(marker)) {
                return part;
            }
        }
        return null;
    }

    /** One of the blocks at random, by their shares. */
    Material pickBlock(RandomGenerator random) {
        double total = 0;
        for (Part part : parts) {
            total += part.weights().stream().mapToDouble(Double::doubleValue).sum();
        }
        double drawn = random.nextDouble(total);
        for (Part part : parts) {
            for (int i = 0; i < part.blocks().size(); i++) {
                drawn -= part.weights().get(i);
                if (drawn < 0) {
                    return part.blocks().get(i);
                }
            }
        }
        return parts.getLast().blocks().getLast();
    }
}
