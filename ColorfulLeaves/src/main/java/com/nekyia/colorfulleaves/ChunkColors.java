package com.nekyia.colorfulleaves;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The leaf colours of one chunk, as the server sends them. It replaces whatever the chunk
 * had before; a chunk without colours is sent to take them away.
 *
 * <p>On the wire, all numbers big-endian ints except the leading version byte:
 * <pre>
 * byte version (1), int chunkX, int chunkZ, int groups,
 *   per group: int rgb, int count, count x int position
 * </pre>
 * where a position packs the block within the chunk as {@code x | z << 4 | (y + 2048) << 8},
 * and bit 24 of a colour asks for the leaves' lighter texture, so that light colours come
 * out light instead of greyish.
 */
record ChunkColors(int chunkX, int chunkZ, Long2IntMap colors) implements CustomPacketPayload {

    static final Type<ChunkColors> TYPE = new Type<>(Identifier.fromNamespaceAndPath("colorfulleaves", "chunk"));
    static final StreamCodec<FriendlyByteBuf, ChunkColors> CODEC = CustomPacketPayload.codec(ChunkColors::write, ChunkColors::read);

    private static final int VERSION = 1;
    private static final int Y_OFFSET = 2048;
    /** Bit 24 of a colour on the wire: the leaves' lighter texture. */
    private static final int WIRE_BRIGHT = 1 << 24;

    @Override
    public Type<ChunkColors> type() {
        return TYPE;
    }

    /**
     * Anything unreadable - a newer version, a broken count - is skipped whole instead of
     * thrown: a throwing decoder would cost the player their connection.
     */
    private static ChunkColors read(FriendlyByteBuf buffer) {
        if (buffer.readableBytes() < 13 || buffer.readByte() != VERSION) {
            buffer.skipBytes(buffer.readableBytes());
            return new ChunkColors(0, 0, null);
        }
        int chunkX = buffer.readInt();
        int chunkZ = buffer.readInt();
        int groups = buffer.readInt();
        Long2IntMap colors = new Long2IntOpenHashMap();
        for (int group = 0; group < groups; group++) {
            if (buffer.readableBytes() < 8) {
                break;
            }
            int wire = buffer.readInt();
            int color = LeafColors.PRESENT | wire & 0xFFFFFF | ((wire & WIRE_BRIGHT) != 0 ? LeafColors.BRIGHT : 0);
            int count = buffer.readInt();
            if (count < 0 || count > buffer.readableBytes() / 4) {
                break;
            }
            for (int i = 0; i < count; i++) {
                int packed = buffer.readInt();
                colors.put(BlockPos.asLong(chunkX << 4 | (packed & 15), (packed >>> 8 & 4095) - Y_OFFSET,
                        chunkZ << 4 | (packed >>> 4 & 15)), color);
            }
        }
        boolean broken = buffer.readableBytes() > 0;
        buffer.skipBytes(buffer.readableBytes());
        return new ChunkColors(chunkX, chunkZ, broken ? null : colors);
    }

    /** The other way round, as a codec has to have it; the client never sends this. */
    private void write(FriendlyByteBuf buffer) {
        Map<Integer, Long2IntOpenHashMap> byColor = new HashMap<>();
        colors.long2IntEntrySet().forEach(entry -> byColor
                .computeIfAbsent(entry.getIntValue() & 0xFFFFFF
                        | ((entry.getIntValue() & LeafColors.BRIGHT) != 0 ? WIRE_BRIGHT : 0), color -> new Long2IntOpenHashMap())
                .put(entry.getLongKey(), 0));
        buffer.writeByte(VERSION);
        buffer.writeInt(chunkX);
        buffer.writeInt(chunkZ);
        buffer.writeInt(byColor.size());
        byColor.forEach((color, positions) -> {
            buffer.writeInt(color);
            buffer.writeInt(positions.size());
            positions.keySet().forEach(pos -> buffer.writeInt(BlockPos.getX(pos) & 15
                    | (BlockPos.getZ(pos) & 15) << 4 | (BlockPos.getY(pos) + Y_OFFSET) << 8));
        });
    }
}
