package com.nekyia.treearchive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What chunks keep is what the mod and the map read: the payload without the chunk's place. */
class LeafColoursTest {

    private static final Map<Integer, Integer> COLOURS = Map.of(
            LeafColours.packed(1, 70, 2), 0xc0392b,
            LeafColours.packed(15, -64, 0), 0xf5d130 | LeafColours.BRIGHT,
            LeafColours.packed(3, 319, 15), 0xc0392b);

    @Test
    void keepsWhatItSendsWithoutTheChunksPlace() {
        byte[] sent = LeafColours.encode(-3, 7, LeafColours.grouped(COLOURS));
        byte[] kept = LeafColours.toBytes(COLOURS);
        byte[] withoutPlace = new byte[sent.length - 8];
        withoutPlace[0] = sent[0];
        System.arraycopy(sent, 9, withoutPlace, 1, sent.length - 9);
        assertArrayEquals(withoutPlace, kept);
        assertEquals(COLOURS, LeafColours.fromBytes(kept));
    }

    @Test
    void sameColoursSameBytes() {
        Map<Integer, Integer> backwards = new LinkedHashMap<>();
        COLOURS.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByKey().reversed())
                .forEach(entry -> backwards.put(entry.getKey(), entry.getValue()));
        assertArrayEquals(LeafColours.toBytes(COLOURS), LeafColours.toBytes(backwards));
    }

    @Test
    void leavesAloneWhatItCannotRead() {
        byte[] kept = LeafColours.toBytes(COLOURS);
        byte[] later = kept.clone();
        later[0] = 2;
        assertNull(LeafColours.fromBytes(later));
        assertNull(LeafColours.fromBytes(Arrays.copyOf(kept, kept.length - 1)));
        assertNull(LeafColours.fromBytes(Arrays.copyOf(kept, kept.length + 4)));
    }
}
