package com.nekyia.treearchive;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;

/**
 * Leaf colours for players with the ColorfulLeaves client mod: its channel, and its
 * format as described in the mod's ChunkColors - the same BountyfulWoodwork sends on
 * the main server.
 */
final class LeafColours {

    static final String CHANNEL = "colorfulleaves:chunk";
    private static final int VERSION = 1;
    private static final int Y_OFFSET = 2048;
    /** The steps a fade is cut into: smooth to the eye, and few colours to send. */
    private static final int FADE_STEPS = 32;
    /** Bit 24 of a colour: the mod draws these leaves on their lighter texture. */
    static final int BRIGHT = 1 << 24;

    /** The leaves the mod can colour; bushes in a crown keep their own green. */
    static final Set<Material> TINTED = EnumSet.of(Material.OAK_LEAVES, Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES);

    private LeafColours() {
    }

    /**
     * How a tree's leaves are coloured: one colour, or a fade from the first colour at the
     * bottom of the crown - or, outwards, its middle - to the last at the top, or its edge.
     */
    record Paint(int[] stops, boolean outwards, boolean bright) {

        /** The colour of the leaf at this place, as the mod reads it. */
        int at(Crown crown, int x, int y, int z) {
            int colour = stops[0];
            if (stops.length > 1) {
                double share = outwards
                        ? Math.hypot(x + 0.5 - crown.middleX(), z + 0.5 - crown.middleZ()) / Math.max(crown.radius(), 1)
                        : (y - crown.bottom()) / (double) Math.max(crown.top() - crown.bottom(), 1);
                share = Math.round(Math.clamp(share, 0, 1) * FADE_STEPS) / (double) FADE_STEPS;
                double at = share * (stops.length - 1);
                int from = Math.min((int) at, stops.length - 2);
                colour = mix(stops[from], stops[from + 1], at - from);
            }
            return colour | (bright ? BRIGHT : 0);
        }

        /** Between two colours, channel by channel. */
        private static int mix(int from, int to, double share) {
            int colour = 0;
            for (int shift = 0; shift <= 16; shift += 8) {
                int a = from >> shift & 255;
                int b = to >> shift & 255;
                colour |= (int) Math.round(a + (b - a) * share) << shift;
            }
            return colour;
        }
    }

    /** How far a tree's leaves reach, which a fade is spread over. */
    record Crown(int bottom, int top, double middleX, double middleZ, double radius) {

        /** The crown of these leaves, each {x, y, z}. */
        static Crown of(List<int[]> leaves) {
            int bottom = Integer.MAX_VALUE;
            int top = Integer.MIN_VALUE;
            double sumX = 0;
            double sumZ = 0;
            for (int[] leaf : leaves) {
                bottom = Math.min(bottom, leaf[1]);
                top = Math.max(top, leaf[1]);
                sumX += leaf[0] + 0.5;
                sumZ += leaf[2] + 0.5;
            }
            double middleX = sumX / Math.max(1, leaves.size());
            double middleZ = sumZ / Math.max(1, leaves.size());
            double radius = 0;
            for (int[] leaf : leaves) {
                radius = Math.max(radius, Math.hypot(leaf[0] + 0.5 - middleX, leaf[2] + 0.5 - middleZ));
            }
            return new Crown(bottom, top, middleX, middleZ, radius);
        }
    }

    /** A block's place within its chunk, the way the mod reads it. */
    static int packed(int x, int y, int z) {
        return (x & 15) | (z & 15) << 4 | (y + Y_OFFSET) << 8;
    }

    /** One chunk's colours, 0xRRGGBB -> packed places. Without any, it takes the chunk's colours away. */
    static byte[] encode(int chunkX, int chunkZ, Map<Integer, List<Integer>> leaves) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeInt(leaves.size());
            for (Map.Entry<Integer, List<Integer>> group : leaves.entrySet()) {
                out.writeInt(group.getKey());
                out.writeInt(group.getValue().size());
                for (int position : group.getValue()) {
                    out.writeInt(position);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
