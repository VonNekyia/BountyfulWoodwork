package com.nekyia.colorfulleaves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** Reads payloads written the way the server writes them: with a plain DataOutputStream. */
class ChunkColorsTest {

    private static int packed(int x, int y, int z) {
        return x | z << 4 | (y + 2048) << 8;
    }

    private static FriendlyByteBuf wire(int version, int chunkX, int chunkZ, int[][] groups) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(version);
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeInt(groups.length);
        for (int[] group : groups) {
            for (int value : group) {
                out.writeInt(value);
            }
        }
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.toByteArray()));
    }

    @Test
    void readsWhatTheServerWrites() throws IOException {
        FriendlyByteBuf buffer = wire(1, -3, 5, new int[][] {
                {0xD98C2B, 2, packed(1, 64, 2), packed(15, -64, 15)},
                {0x00FF00, 1, packed(0, 319, 0)},
        });

        ChunkColors colors = ChunkColors.CODEC.decode(buffer);

        assertEquals(0, buffer.readableBytes());
        assertEquals(3, colors.colors().size());
        assertEquals(LeafColors.PRESENT | 0xD98C2B, colors.colors().get(BlockPos.asLong(-47, 64, 82)));
        assertEquals(LeafColors.PRESENT | 0xD98C2B, colors.colors().get(BlockPos.asLong(-33, -64, 95)));
        assertEquals(LeafColors.PRESENT | 0x00FF00, colors.colors().get(BlockPos.asLong(-48, 319, 80)));
    }

    @Test
    void keepsTheAskForTheLighterTexture() throws IOException {
        FriendlyByteBuf buffer = wire(1, 0, 0, new int[][] {{1 << 24 | 0xFFFFFF, 1, packed(3, 70, 4)}});

        ChunkColors colors = ChunkColors.CODEC.decode(buffer);

        assertEquals(LeafColors.PRESENT | LeafColors.BRIGHT | 0xFFFFFF, colors.colors().get(BlockPos.asLong(3, 70, 4)));
    }

    @Test
    void skipsWhatItCannotReadWithoutThrowing() throws IOException {
        FriendlyByteBuf tooMany = wire(1, 0, 0, new int[][] {{0xFF0000, 50, packed(0, 0, 0)}});
        FriendlyByteBuf newer = wire(2, 0, 0, new int[][] {{0xFF0000, 1, packed(0, 0, 0)}});

        assertNull(ChunkColors.CODEC.decode(tooMany).colors());
        assertNull(ChunkColors.CODEC.decode(newer).colors());
        assertEquals(0, tooMany.readableBytes());
        assertEquals(0, newer.readableBytes());
    }
}
