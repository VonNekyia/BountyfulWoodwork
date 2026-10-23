package com.nekyia.treearchive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Leaf colours for players with the ColorfulLeaves client mod: its channel, and its
 * format as described in the mod's ChunkColors - the same BountyfulWoodwork sends on
 * the main server. Trees placed for real keep their colours in their chunks, in that
 * format without the chunk's place, where they travel with the world: to the main
 * server, and to the map (heroic-map-renderer reads them from there).
 */
final class LeafColours {

    static final String CHANNEL = "colorfulleaves:chunk";
    /** Where mods that read format 2 - secondary colours and blossoms - get it. */
    static final String LEAVES = "colorfulleaves:leaves";
    private static final int Y_OFFSET = 2048;
    /** The steps a fade is cut into: smooth to the eye, and few colours to send. */
    private static final int FADE_STEPS = 32;
    /** Bit 24 of a colour: the mod draws these leaves on their lighter texture. */
    static final int BRIGHT = 1 << 24;
    /** Bit 24 of a secondary or blossom colour: the leaf has one. */
    static final int PRESENT = 1 << 24;
    private static final Comparator<Look> ORDER = Comparator.comparingInt(Look::colour)
            .thenComparingInt(Look::secondary).thenComparingInt(Look::blossom);
    /** Where a chunk keeps its leaves' colours; the name was agreed with the map. */
    static final NamespacedKey KEPT = Objects.requireNonNull(NamespacedKey.fromString("heroicmap:leaf_colors"));

    /** The leaves the mod can colour; bushes in a crown keep their own green. */
    static final Set<Material> TINTED = EnumSet.of(Material.OAK_LEAVES, Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES,
            Material.CHERRY_LEAVES, Material.PALE_OAK_LEAVES, Material.RED_POPLAR_LEAVES,
            Material.ORANGE_POPLAR_LEAVES, Material.YELLOW_POPLAR_LEAVES);

    private LeafColours() {
    }

    /**
     * How one leaf looks: its colour, bright in bit 24, and a secondary colour some of its
     * leaves take and the colour of its blossoms, each with {@link #PRESENT} when it has one.
     */
    record Look(int colour, int secondary, int blossom) {

        /** The leaf as format 1 tells it: its colour alone. */
        Look plain() {
            return secondary == 0 && blossom == 0 ? this : new Look(colour, 0, 0);
        }
    }

    /**
     * How a tree's leaves are coloured: one colour, or a fade from the first colour at the
     * bottom of the crown - or, outwards, its middle - to the last at the top, or its edge;
     * with a secondary colour, and blossoms on {@code bloom} percent of the leaves.
     */
    record Paint(int[] stops, boolean outwards, boolean bright, int secondary, int blossom, int bloom) {

        /** The look of the leaf at this place, as the mod reads it. */
        Look at(Crown crown, int x, int y, int z) {
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
            // Which leaves bloom hangs on their place alone: the same in a preview, the
            // tree placed and the colours its chunk keeps.
            boolean blooms = blossom != 0 && Math.floorMod(TreePreview.mix(BlockKeys.of(x, y, z)), 100) < bloom;
            return new Look(colour | (bright ? BRIGHT : 0), secondary, blooms ? blossom : 0);
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

    /**
     * One chunk's leaves to send: in format 2 for mods that read it, on {@link #LEAVES},
     * else in format 1, where leaves of one colour make one group whatever else they had.
     * Without any, it takes the chunk's colours away.
     */
    static byte[] encode(boolean format2, int chunkX, int chunkZ, Map<Integer, Look> leaves) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(format2 ? 2 : 1);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            write(out, leaves, format2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The groups, in a fixed order - so the same leaves give the same bytes. */
    private static void write(DataOutputStream out, Map<Integer, Look> leaves, boolean format2) throws IOException {
        Map<Look, List<Integer>> groups = new TreeMap<>(ORDER);
        new TreeMap<>(leaves).forEach((place, look) -> groups.computeIfAbsent(format2 ? look : look.plain(),
                ignored -> new ArrayList<>()).add(place));
        out.writeInt(groups.size());
        for (Map.Entry<Look, List<Integer>> group : groups.entrySet()) {
            out.writeInt(group.getKey().colour());
            if (format2) {
                out.writeInt(group.getKey().secondary());
                out.writeInt(group.getKey().blossom());
            }
            out.writeInt(group.getValue().size());
            for (int position : group.getValue()) {
                out.writeInt(position);
            }
        }
    }

    /**
     * The leaves a chunk keeps, packed place -> look: none when it keeps none, null when
     * it keeps them in a way this cannot read - those are left as they are.
     */
    static @Nullable Map<Integer, Look> kept(Chunk chunk) {
        byte[] bytes;
        try {
            bytes = chunk.getPersistentDataContainer().get(KEPT, PersistentDataType.BYTE_ARRAY);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return bytes == null ? new HashMap<>() : fromBytes(bytes);
    }

    /**
     * Has a chunk keep these leaves, packed place -> look, or none - written only when they
     * differ from what it keeps. Format 2 only where {@code format2} allows it and a leaf
     * has a secondary colour or blossom; format 1 keeps the colour alone.
     *
     * @return whether they did
     */
    static boolean keep(Chunk chunk, Map<Integer, Look> leaves, boolean format2) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (leaves.isEmpty()) {
            boolean kept = data.has(KEPT);
            data.remove(KEPT);
            return kept;
        }
        byte[] bytes = toBytes(leaves, format2 && leaves.values().stream().anyMatch(look -> !look.equals(look.plain())));
        if (Arrays.equals(bytes, data.get(KEPT, PersistentDataType.BYTE_ARRAY))) {
            return false;
        }
        data.set(KEPT, PersistentDataType.BYTE_ARRAY, bytes);
        return true;
    }

    /** Leaves as a chunk keeps them: as they are sent, without the chunk's place. */
    static byte[] toBytes(Map<Integer, Look> leaves, boolean format2) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(format2 ? 2 : 1);
            write(out, leaves, format2);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Kept leaves, packed place -> look, in either format; null from bytes this cannot read. */
    static @Nullable Map<Integer, Look> fromBytes(byte[] bytes) {
        Map<Integer, Look> leaves = new HashMap<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readByte();
            if (version != 1 && version != 2) {
                return null;
            }
            int groups = in.readInt();
            for (; groups > 0; groups--) {
                Look look = new Look(in.readInt(), version == 2 ? in.readInt() : 0, version == 2 ? in.readInt() : 0);
                int count = in.readInt();
                if (count < 0) {
                    return null;
                }
                for (; count > 0; count--) {
                    leaves.put(in.readInt(), look);
                }
            }
            return groups == 0 && in.available() == 0 ? leaves : null;
        } catch (IOException e) {
            return null;
        }
    }
}
