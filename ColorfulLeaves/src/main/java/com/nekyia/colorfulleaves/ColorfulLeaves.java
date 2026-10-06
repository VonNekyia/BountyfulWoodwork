package com.nekyia.colorfulleaves;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Shows the leaf colours Terranova's trees carry. The server tells the client, chunk by
 * chunk, which leaves get which colour; which tree that is and why is the server's
 * business alone. The falling leaves take the colour of the leaf they fall from.
 *
 * <p>Only leaves whose models are tinted can take a colour: azalea, cherry, pale oak and
 * poplar leaves have theirs painted into the texture.
 *
 * <p>A tint only darkens the texture it lies on, and leaf textures are a mid grey - so a
 * white comes out grey. Where the server asks for it, the leaves are drawn with a lighter
 * copy of their texture instead, made from it by the palettes this mod brings along.
 *
 * <p>A bush standing on moss takes the moss's green, so that mistletoe - moss with a bush
 * on top, as builders hang it in crowns - reads as one clump.
 */
public final class ColorfulLeaves implements ClientModInitializer {

    private static final List<Block> TINTED_LEAVES = List.of(Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.MANGROVE_LEAVES);

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(ChunkColors.TYPE, ChunkColors.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ChunkColors.TYPE,
                (payload, context) -> LeafColors.set(context.client(), payload));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> LeafColors.forget(chunk.getPos()));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> LeafColors.clear());
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> LeafColors.clear());
        ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((model, context) ->
                TINTED_LEAVES.contains(context.state().getBlock()) ? new BrightLeaves(model, context.state().getBlock())
                        : model));

        // Once the game has set up its own tints, so that leaves without a colour from the
        // server look exactly as they would without the mod.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            BlockColors colors = client.getBlockColors();
            for (Block leaves : TINTED_LEAVES) {
                List<BlockTintSource> sources = new ArrayList<>(colors.getTintSources(leaves.defaultBlockState()));
                if (!sources.isEmpty()) {
                    sources.set(0, new LeafTint(sources.getFirst()));
                    colors.register(sources, leaves);
                }
            }
            List<BlockTintSource> bush = new ArrayList<>(colors.getTintSources(Blocks.BUSH.defaultBlockState()));
            if (!bush.isEmpty()) {
                bush.set(0, new MossBush(bush.getFirst()));
                colors.register(bush, Blocks.BUSH);
            }
        });
    }

    /** A bush's own tint, unless it stands on moss. */
    private record MossBush(BlockTintSource vanilla) implements BlockTintSource {

        /** The bush texture comes out at the moss blocks' own average colour with these. */
        private static final int ON_MOSS = 0xFFBEE861;
        private static final int ON_PALE_MOSS = 0xFFE3EDDF;

        @Override
        public int color(BlockState state) {
            return vanilla.color(state);
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            int moss = moss(level, pos);
            return moss != 0 ? moss : vanilla.colorInWorld(state, level, pos);
        }

        @Override
        public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            int moss = moss(level, pos);
            return moss != 0 ? moss : vanilla.colorAsTerrainParticle(state, level, pos);
        }

        @Override
        public Set<Property<?>> relevantProperties() {
            return vanilla.relevantProperties();
        }

        private static int moss(BlockAndTintGetter level, BlockPos pos) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(Blocks.MOSS_BLOCK) ? ON_MOSS : below.is(Blocks.PALE_MOSS_BLOCK) ? ON_PALE_MOSS : 0;
        }
    }

    /** A leaf's own tint, unless the server gave the leaf at that spot a colour. */
    private record LeafTint(BlockTintSource vanilla) implements BlockTintSource {

        @Override
        public int color(BlockState state) {
            return vanilla.color(state);
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            int color = LeafColors.tint(pos);
            return color != 0 ? color : vanilla.colorInWorld(state, level, pos);
        }

        @Override
        public int colorAsTerrainParticle(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            int color = LeafColors.tint(pos);
            return color != 0 ? color : vanilla.colorAsTerrainParticle(state, level, pos);
        }

        @Override
        public Set<Property<?>> relevantProperties() {
            return vanilla.relevantProperties();
        }
    }
}
