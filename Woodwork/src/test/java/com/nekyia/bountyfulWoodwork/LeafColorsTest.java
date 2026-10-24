package com.nekyia.bountyfulWoodwork;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nekyia.bountyfulWoodwork.LeafColors.Look;
import com.nekyia.treearchive.TreeArchiveBytes;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bytes a chunk keeps its leaf colours in, as Woodwork and TreeArchive both read and write them. */
class LeafColorsTest {

    private static final int BRIGHT = 1 << 24;
    private static final int PRESENT = 1 << 24;

    private static int packed(int x, int y, int z) {
        return x | z << 4 | (y + 2048) << 8;
    }

    /** Colours alone: format 1. */
    private static final Map<Integer, Look> PLAIN = Map.of(
            packed(1, 64, 2), new Look(0xD98C2B | BRIGHT, 0, 0),
            packed(15, -64, 15), new Look(0x00FF00, 0, 0),
            packed(0, 319, 0), new Look(0xD98C2B | BRIGHT, 0, 0));

    /** With a secondary colour and blossoms on some: format 2, two leaves of one colour in different groups. */
    private static final Map<Integer, Look> BLOOMING = Map.of(
            packed(1, 64, 2), new Look(0xD98C2B, PRESENT | 0xF5D130, 0),
            packed(2, 64, 2), new Look(0xD98C2B, PRESENT | 0xF5D130, PRESENT | 0xFFB7C5),
            packed(3, 70, 9), new Look(0x00FF00 | BRIGHT, 0, 0));

    private static Map<Integer, List<Integer>> asLists(Map<Integer, Look> looks) {
        Map<Integer, List<Integer>> lists = new HashMap<>();
        looks.forEach((position, look) -> lists.put(position, List.of(look.colour(), look.secondary(), look.blossom())));
        return lists;
    }

    @Test
    void writesFormat1UnlessALeafHasMore() {
        assertEquals(1, LeafColors.encode(PLAIN)[0]);
        assertEquals(2, LeafColors.encode(BLOOMING)[0]);
        assertEquals(PLAIN, LeafColors.decode(LeafColors.encode(PLAIN)));
        assertEquals(BLOOMING, LeafColors.decode(LeafColors.encode(BLOOMING)));
    }

    @Test
    void treeArchiveReadsWhatWoodworkWrites() {
        assertEquals(asLists(PLAIN), TreeArchiveBytes.fromBytes(LeafColors.encode(PLAIN)));
        assertEquals(asLists(BLOOMING), TreeArchiveBytes.fromBytes(LeafColors.encode(BLOOMING)));
    }

    @Test
    void woodworkReadsWhatTreeArchiveWrites() {
        assertEquals(PLAIN, LeafColors.decode(TreeArchiveBytes.toBytes(asLists(PLAIN), false)));
        assertEquals(BLOOMING, LeafColors.decode(TreeArchiveBytes.toBytes(asLists(BLOOMING), true)));
    }

    /** The same leaves give the same bytes on both sides, so neither rewrites what the other kept. */
    @Test
    void bothWriteTheSameBytes() {
        assertArrayEquals(TreeArchiveBytes.toBytes(asLists(PLAIN), false), LeafColors.encode(PLAIN));
        assertArrayEquals(TreeArchiveBytes.toBytes(asLists(BLOOMING), true), LeafColors.encode(BLOOMING));
    }

    @Test
    void olderModsGetColoursAloneInOneGroupEach() {
        byte[] format1 = LeafColors.format1(LeafColors.encode(BLOOMING));
        assertEquals(1, format1[0]);
        Map<Integer, Look> plain = new HashMap<>(BLOOMING);
        plain.replaceAll((position, look) -> look.plain());
        assertEquals(plain, LeafColors.decode(format1));
        // Two colours: the two leaves of 0xd98c2b share their group again.
        assertEquals(2, ((format1[1] & 255) << 24 | (format1[2] & 255) << 16 | (format1[3] & 255) << 8 | format1[4] & 255));
        byte[] stored = LeafColors.encode(PLAIN);
        assertArrayEquals(stored, LeafColors.format1(stored));
    }

    @Test
    void leavesBytesAloneThatFollowNoFormat() {
        assertNull(LeafColors.decode(new byte[] {3, 0, 0, 0, 0}));
        assertNull(LeafColors.decode(new byte[] {1, -1, -1, -1, -1}));
        // A negative count, padded so the bytes still add up: unreadable, not empty.
        assertNull(LeafColors.decode(new byte[] {1, 0, 0, 0, 2, 0, 0, 0, 1, -1, -1, -1, -1, 0, 0, 0, 2, 0, 0, 0, 0}));
        byte[] stored = LeafColors.encode(BLOOMING);
        assertNull(LeafColors.decode(Arrays.copyOf(stored, stored.length - 1)));
    }

    @Test
    void payloadPutsTheChunkAfterTheVersion() throws IOException {
        for (Map<Integer, Look> looks : List.of(PLAIN, BLOOMING)) {
            byte[] stored = LeafColors.encode(looks);
            byte[] payload = LeafColors.payload(-3, 5, stored);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            assertEquals(stored[0], in.readByte());
            assertEquals(-3, in.readInt());
            assertEquals(5, in.readInt());
            assertArrayEquals(Arrays.copyOfRange(stored, 1, stored.length), Arrays.copyOfRange(payload, 9, payload.length));
        }
        // Nothing stored: no groups, which takes the chunk's colours away.
        assertArrayEquals(new byte[] {1, 0, 0, 0, 7, 0, 0, 0, 8, 0, 0, 0, 0}, LeafColors.payload(7, 8, null));
    }
}
