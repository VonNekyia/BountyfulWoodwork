package com.nekyia.colorfulleaves;

import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A leaf model that draws the leaves the server asked to be bright with the lighter copy
 * of their texture: the same quads, moved over to where that copy sits on the atlas. The
 * copy is made while the atlas is stitched, by the palettes in assets/colorfulleaves.
 */
record BrightLeaves(BlockStateModel model, Identifier texture, Identifier brightTexture) implements BlockStateModel {

    BrightLeaves(BlockStateModel model, Block leaves) {
        this(model, texture(leaves, ""), texture(leaves, "_colorfulleaves_bright"));
    }

    private static Identifier texture(Block leaves, String suffix) {
        Identifier block = BuiltInRegistries.BLOCK.getKey(leaves);
        return block.withPath("block/" + block.getPath() + suffix);
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state,
                          RandomSource random, Predicate<Direction> cullTest) {
        TextureAtlasSprite to = LeafColors.bright(pos) ? sprite(brightTexture) : null;
        // A resource pack that leaves the copy out leaves the leaves as they are.
        if (to == null || to.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            model.emitQuads(emitter, level, pos, state, random, cullTest);
            return;
        }
        TextureAtlasSprite from = sprite(texture);
        float du = to.getU0() - from.getU0();
        float dv = to.getV0() - from.getV0();
        emitter.pushTransform(quad -> {
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.uv(vertex, quad.u(vertex) + du, quad.v(vertex) + dv);
            }
            return true;
        });
        model.emitQuads(emitter, level, pos, state, random, cullTest);
        emitter.popTransform();
    }

    /** Bright leaves look unlike the plain ones, so neither may stand in for the other. */
    @Override
    public @Nullable Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                              RandomSource random) {
        return LeafColors.bright(pos) ? null : model.createGeometryKey(level, pos, state, random);
    }

    private static TextureAtlasSprite sprite(Identifier texture) {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(texture);
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        model.collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return model.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return model.materialFlags();
    }

    @Override
    public boolean hasMaterialFlag(int flag) {
        return model.hasMaterialFlag(flag);
    }
}
