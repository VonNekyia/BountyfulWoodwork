package com.nekyia.colorfulleaves;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

/** The leaf colours the server has sent, for the chunks the client holds. */
final class LeafColors {

    /** Set on every colour kept, so that none is ever 0 - which stands for no colour. */
    static final int PRESENT = 1 << 24;
    /** The leaves' lighter texture, for light colours that would come out greyish. */
    static final int BRIGHT = 1 << 25;

    /**
     * Chunk -> block -> colour. The chunk builders read this from their own threads, so a
     * chunk's map is only ever replaced whole, never changed.
     */
    private static final Map<Long, Long2IntMap> BY_CHUNK = new ConcurrentHashMap<>();

    private LeafColors() {
    }

    /** The colour the server gave this block as a tint, or 0 for none. */
    static int tint(BlockPos pos) {
        int colour = at(pos);
        return colour == 0 ? 0 : 0xFF000000 | colour & 0xFFFFFF;
    }

    /** Whether the server asked for the lighter texture here. */
    static boolean bright(BlockPos pos) {
        return (at(pos) & BRIGHT) != 0;
    }

    /** What is kept for this block: 0xRRGGBB with PRESENT and maybe BRIGHT, or 0 for none. */
    private static int at(BlockPos pos) {
        Long2IntMap colors = BY_CHUNK.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        return colors == null ? 0 : colors.get(pos.asLong());
    }

    /** Takes a chunk's new colours and redraws what changed. */
    static void set(Minecraft client, ChunkColors payload) {
        if (payload.colors() == null) {
            return;
        }
        long chunk = ChunkPos.pack(payload.chunkX(), payload.chunkZ());
        Long2IntMap old = payload.colors().isEmpty() ? BY_CHUNK.remove(chunk) : BY_CHUNK.put(chunk, payload.colors());
        redraw(client, payload.chunkX(), payload.chunkZ(), old, payload.colors());
    }

    static void forget(ChunkPos chunk) {
        BY_CHUNK.remove(chunk.pack());
    }

    /** Another world, or no world: nothing sent so far applies any more. */
    static void clear() {
        BY_CHUNK.clear();
    }

    /** Rebuilds the sections of the chunk that held coloured leaves before or after. */
    private static void redraw(Minecraft client, int chunkX, int chunkZ, @Nullable Long2IntMap before, Long2IntMap after) {
        if (client.level == null) {
            return;
        }
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (Long2IntMap colors : new Long2IntMap[] {before, after}) {
            if (colors == null) {
                continue;
            }
            for (long pos : colors.keySet()) {
                lowest = Math.min(lowest, BlockPos.getY(pos));
                highest = Math.max(highest, BlockPos.getY(pos));
            }
        }
        if (lowest <= highest) {
            client.level.setSectionRangeDirty(chunkX, lowest >> 4, chunkZ, chunkX, highest >> 4, chunkZ);
        }
    }
}
