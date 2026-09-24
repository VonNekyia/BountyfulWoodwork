package com.nekyia.bountyfulWoodwork;

import org.bukkit.block.Block;

/**
 * Block positions packed into a long the way Minecraft packs them: 26 bits each for x
 * and z, 12 bits for the height. Paper's own block keys are deprecated.
 */
final class BlockKeys {

    private BlockKeys() {
    }

    static long of(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    static long of(Block block) {
        return of(block.getX(), block.getY(), block.getZ());
    }

    static int x(long key) {
        return (int) (key >> 38);
    }

    static int y(long key) {
        return (int) (key << 52 >> 52);
    }

    static int z(long key) {
        return (int) (key << 26 >> 38);
    }
}
