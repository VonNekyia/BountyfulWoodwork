package com.nekyia.treearchive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * TreeArchive's leaf colour bytes for Woodwork's tests, which sit in another package:
 * leaves as position -> [colour, secondary, blossom].
 */
public final class TreeArchiveBytes {

    private TreeArchiveBytes() {
    }

    public static byte[] toBytes(Map<Integer, List<Integer>> leaves, boolean format2) {
        Map<Integer, LeafColours.Look> looks = new HashMap<>();
        leaves.forEach((position, look) -> looks.put(position, new LeafColours.Look(look.get(0), look.get(1), look.get(2))));
        return LeafColours.toBytes(looks, format2);
    }

    public static @Nullable Map<Integer, List<Integer>> fromBytes(byte[] bytes) {
        Map<Integer, LeafColours.Look> looks = LeafColours.fromBytes(bytes);
        if (looks == null) {
            return null;
        }
        Map<Integer, List<Integer>> leaves = new HashMap<>();
        looks.forEach((position, look) -> leaves.put(position, List.of(look.colour(), look.secondary(), look.blossom())));
        return leaves;
    }
}
