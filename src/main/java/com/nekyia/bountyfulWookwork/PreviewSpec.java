package com.nekyia.bountyfulWookwork;

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
            parts.add(new Part(materials, weights,
                    colon < 0 ? null : TreeSelection.parse(piece.substring(colon + 1))));
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("name at least one block");
        }
        return new PreviewSpec(List.copyOf(parts));
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
