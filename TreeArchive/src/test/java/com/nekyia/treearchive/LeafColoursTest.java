package com.nekyia.treearchive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nekyia.treearchive.LeafColours.Look;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What chunks keep is what the mod and the map read: the payload without the chunk's place. */
class LeafColoursTest {

    private static final Map<Integer, Look> PLAIN = Map.of(
            LeafColours.packed(1, 70, 2), new Look(0xc0392b, 0, 0),
            LeafColours.packed(15, -64, 0), new Look(0xf5d130 | LeafColours.BRIGHT, 0, 0),
            LeafColours.packed(3, 319, 15), new Look(0xc0392b, 0, 0));

    /** One colour, three looks: with a secondary colour, with that and a blossom, and plain. */
    private static final Map<Integer, Look> FLOWERING = Map.of(
            LeafColours.packed(1, 70, 2), new Look(0xc0392b, 0xf5d130 | LeafColours.PRESENT, 0),
            LeafColours.packed(2, 70, 2), new Look(0xc0392b, 0xf5d130 | LeafColours.PRESENT,
                    0xffb7c5 | LeafColours.PRESENT),
            LeafColours.packed(3, 70, 2), new Look(0xc0392b, 0, 0));

    @Test
    void keepsWhatItSendsWithoutTheChunksPlace() {
        for (boolean format2 : new boolean[] {false, true}) {
            byte[] sent = LeafColours.encode(format2, -3, 7, FLOWERING);
            byte[] kept = LeafColours.toBytes(FLOWERING, format2);
            byte[] withoutPlace = new byte[sent.length - 8];
            withoutPlace[0] = sent[0];
            System.arraycopy(sent, 9, withoutPlace, 1, sent.length - 9);
            assertArrayEquals(withoutPlace, kept);
            Map<Integer, Look> plain = new HashMap<>();
            FLOWERING.forEach((place, look) -> plain.put(place, look.plain()));
            assertEquals(format2 ? FLOWERING : plain, LeafColours.fromBytes(kept));
        }
    }

    @Test
    void formatOneMergesWhatFormatTwoTellsApart() {
        assertEquals(1, ByteBuffer.wrap(LeafColours.encode(false, 0, 0, FLOWERING), 9, 4).getInt());
        assertEquals(3, ByteBuffer.wrap(LeafColours.encode(true, 0, 0, FLOWERING), 9, 4).getInt());
        assertEquals(2, LeafColours.encode(true, 0, 0, FLOWERING)[0]);
    }

    @Test
    void sameColoursSameBytes() {
        Map<Integer, Look> backwards = new LinkedHashMap<>();
        PLAIN.entrySet().stream().sorted(Map.Entry.<Integer, Look>comparingByKey().reversed())
                .forEach(entry -> backwards.put(entry.getKey(), entry.getValue()));
        assertArrayEquals(LeafColours.toBytes(PLAIN, false), LeafColours.toBytes(backwards, false));
    }

    @Test
    void leavesAloneWhatItCannotRead() {
        byte[] kept = LeafColours.toBytes(PLAIN, false);
        byte[] later = kept.clone();
        later[0] = 3;
        assertNull(LeafColours.fromBytes(later));
        assertNull(LeafColours.fromBytes(Arrays.copyOf(kept, kept.length - 1)));
        assertNull(LeafColours.fromBytes(Arrays.copyOf(kept, kept.length + 4)));
        assertNull(LeafColours.fromBytes(new byte[] {1, -1, -1, -1, -1}));
    }
}
