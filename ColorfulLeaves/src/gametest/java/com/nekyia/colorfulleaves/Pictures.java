package com.nekyia.colorfulleaves;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pictures of the mod at work, for looking at rather than for asserting: plants a few
 * trees from the archive in the sky, colours them the way the server would - one colour,
 * a fade upwards, a fade outwards - and takes a screenshot of each. Run with
 * ./gradlew runClientGameTest; the pictures land in build/run/clientGameTest/screenshots.
 *
 * <p>Other trees and colours: -Ppictures="maple_medium_fwhip_1=#2a46c8,#b46ae8;oak_..." -
 * archived trees with the colours of their fade; one colour alone means just that, and
 * &mistle behind the colours hangs mistletoe in the crown as TreeArchive does.
 */
public final class Pictures implements FabricClientGameTest {

    /** The archive the creative server keeps; the build passes its place. */
    private static final Path ARCHIVE = Path.of(System.getProperty("colorfulleaves.archive", "archive"));
    private static final int GROUND = 200;
    /** Space between two crowns. */
    private static final int GAP = 8;
    private static final int FADE_STEPS = 32;

    /** A tree to plant, with its colours: -1 and null leave it as it is. */
    private record Tree(String id, int colour, int[] fade, String mistle) {
    }

    private record Planted(Tree tree, int x, List<BlockPos> leaves, List<BlockPos> tinted, int width, int height) {
    }

    private static final List<Tree> TREES = trees(System.getProperty("colorfulleaves.pictures", ""));

    /** The trees asked for, or a few to show the idea. */
    private static List<Tree> trees(String asked) {
        if (asked.isBlank()) {
            return List.of(
                    new Tree("maple_medium_fwhip_1", 0xB5361D, new int[] {0x7A1F12, 0xC8461D, 0xF0A33A}, ""),
                    new Tree("larch_medium_snifferish_1", 0xD9A42B, new int[] {0x9A6A14, 0xE8C24A}, ""),
                    new Tree("beech_medium_snifferish_1", 0xC8742A, new int[] {0x6B3A14, 0xC8742A, 0xF0B347}, ""),
                    new Tree("silver_fir_medium_snifferish_1", -1, null, ""));
        }
        List<Tree> trees = new ArrayList<>();
        for (String tree : asked.split(";")) {
            String[] idAndColours = tree.split("=", 2);
            String[] colourAndModifier = idAndColours[1].split("&", 2);
            String mistle = colourAndModifier.length > 1 ? colourAndModifier[1] : "";
            int[] stops = java.util.Arrays.stream(colourAndModifier[0].split(","))
                    .mapToInt(colour -> Integer.parseInt(colour.trim().replace("#", ""), 16))
                    .toArray();
            int[] fade = stops.length > 1 ? stops : new int[] {stops[0], stops[0]};
            trees.add(new Tree(idAndColours[0].trim(), mix(fade, 0.5), fade, mistle));
        }
        return trees;
    }

    private static final Set<Block> TINTED = Set.of(Blocks.OAK_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES,
            Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES);

    private enum Look { NONE, COLOUR, FADE_UP, FADE_OUT }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext game = context.worldBuilder().create()) {
            TestServerContext server = game.getServer();
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamemode spectator @a");
            List<Planted> planted = server.computeOnServer(minecraft -> plant(minecraft.overworld()));
            context.getInput().pressKey(options -> options.keyToggleGui);

            Set<Long> coloured = new HashSet<>();
            String[] names = {"1_ohne_mod", "2_einfarbig", "3_fade_hoch", "4_fade_aussen"};
            for (Look look : Look.values()) {
              // Each coloured look twice: on the usual texture, and on the lighter one.
              for (boolean bright : look == Look.NONE ? new boolean[] {false} : new boolean[] {false, true}) {
                colour(server, planted, look, bright, coloured);
                String name = names[look.ordinal()] + (bright ? "_hell" : "");
                if (planted.size() > 1) {
                    Planted first = planted.getFirst();
                    Planted last = planted.getLast();
                    int height = planted.stream().mapToInt(Planted::height).max().orElse(0);
                    int width = last.x() + last.width() / 2 - (first.x() - first.width() / 2);
                    view(context, game, server, (first.x() + last.x()) / 2.0 + 0.5, GROUND + height / 2,
                            -distance(width, height), 5);
                    shoot(context, "reihe_" + name);
                }
                for (Planted tree : planted) {
                    if (look == Look.NONE && planted.size() > 1 || tree.tree().fade() == null) {
                        continue;
                    }
                    view(context, game, server, tree.x() + 0.5, GROUND + tree.height() / 2,
                            -distance(tree.width(), tree.height()), 5);
                    shoot(context, tree.tree().id() + "_" + name);
                }
              }
            }
        }
    }

    /**
     * How far back to stand to have this much in the picture: the game shows 70 degrees
     * upright, and on a 16:9 picture about 102 across.
     */
    private static int distance(int width, int height) {
        return (int) Math.ceil(Math.max((width / 2.0 + 4) / Math.tan(Math.toRadians(51)),
                (height / 2.0 + 4) / Math.tan(Math.toRadians(35))));
    }

    private static void view(ClientGameTestContext context, TestSingleplayerContext game, TestServerContext server,
                             double x, int y, int z, int pitch) {
        server.runCommand("tp @a " + x + " " + y + " " + z + " 0 " + pitch);
        game.getConnection().waitForChunksRender();
        context.waitTicks(10);
    }

    private static void shoot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1600, 900).disableCounterPrefix());
    }

    /** Sends the colours of one look, taking away the last look's from chunks it no longer needs. */
    private static void colour(TestServerContext server, List<Planted> planted, Look look, boolean bright,
                               Set<Long> coloured) {
        Map<Long, Long2IntOpenHashMap> byChunk = new HashMap<>();
        for (Planted tree : planted) {
            for (BlockPos leaf : tree.tinted()) {
                int colour = colourOf(tree, leaf, look);
                if (colour >= 0) {
                    byChunk.computeIfAbsent(ChunkPos.pack(leaf.getX() >> 4, leaf.getZ() >> 4),
                            chunk -> new Long2IntOpenHashMap()).put(leaf.asLong(),
                            colour | LeafColors.PRESENT | (bright ? LeafColors.BRIGHT : 0));
                }
            }
        }
        Set<Long> chunks = new HashSet<>(coloured);
        chunks.addAll(byChunk.keySet());
        coloured.clear();
        coloured.addAll(byChunk.keySet());
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            for (long chunk : chunks) {
                ChunkPos pos = ChunkPos.unpack(chunk);
                ServerPlayNetworking.send(player, new ChunkColors(pos.x(), pos.z(),
                        byChunk.getOrDefault(chunk, new Long2IntOpenHashMap())));
            }
        });
    }

    /** As the server works it out: one colour, or a fade spread over the crown. */
    private static int colourOf(Planted planted, BlockPos leaf, Look look) {
        Tree tree = planted.tree();
        if (look == Look.NONE || tree.colour() < 0) {
            return -1;
        }
        if (look == Look.COLOUR) {
            return tree.colour();
        }
        int bottom = Integer.MAX_VALUE;
        int top = Integer.MIN_VALUE;
        double sumX = 0;
        double sumZ = 0;
        for (BlockPos pos : planted.leaves()) {
            bottom = Math.min(bottom, pos.getY());
            top = Math.max(top, pos.getY());
            sumX += pos.getX() + 0.5;
            sumZ += pos.getZ() + 0.5;
        }
        double middleX = sumX / planted.leaves().size();
        double middleZ = sumZ / planted.leaves().size();
        double radius = 0;
        for (BlockPos pos : planted.leaves()) {
            radius = Math.max(radius, Math.hypot(pos.getX() + 0.5 - middleX, pos.getZ() + 0.5 - middleZ));
        }
        double share = look == Look.FADE_OUT
                ? Math.hypot(leaf.getX() + 0.5 - middleX, leaf.getZ() + 0.5 - middleZ) / Math.max(radius, 1)
                : (leaf.getY() - bottom) / (double) Math.max(top - bottom, 1);
        share = Math.round(Math.clamp(share, 0, 1) * FADE_STEPS) / (double) FADE_STEPS;
        return mix(tree.fade(), share);
    }

    /** The colour of a fade at {@code share}, from 0 for the first colour to 1 for the last. */
    private static int mix(int[] stops, double share) {
        double at = share * (stops.length - 1);
        int from = Math.min((int) at, stops.length - 2);
        int colour = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = stops[from] >> shift & 255;
            int b = stops[from + 1] >> shift & 255;
            colour |= (int) Math.round(a + (b - a) * (at - from)) << shift;
        }
        return colour;
    }

    /** The trees side by side in the sky, on a grass floor under them. */
    private static List<Planted> plant(ServerLevel level) {
        List<Planted> planted = new ArrayList<>();
        int edge = 0;
        for (Tree tree : TREES) {
            Planted done = paste(level, tree, edge);
            planted.add(done);
            edge = done.x() + done.width() / 2 + GAP;
        }
        int reach = planted.stream().mapToInt(Planted::width).max().orElse(0) / 2 + GAP;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = -reach; x <= edge + reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                BlockPos pos = new BlockPos(x, GROUND - 1, z);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, grass, Block.UPDATE_CLIENTS);
                }
            }
        }
        return planted;
    }

    /** Pastes a Sponge schematic from the archive, its crown starting at {@code edge}. */
    private static Planted paste(ServerLevel level, Tree tree, int edge) {
        CompoundTag schematic;
        try {
            CompoundTag root = NbtIo.readCompressed(ARCHIVE.resolve(tree.id() + ".schem"), NbtAccounter.unlimitedHeap());
            schematic = root.getCompound("Schematic").orElse(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int width = schematic.getShortOr("Width", (short) 0);
        int height = schematic.getShortOr("Height", (short) 0);
        int length = schematic.getShortOr("Length", (short) 0);
        int[] offset = schematic.getIntArray("Offset").orElse(new int[3]);
        CompoundTag blocks = schematic.getCompoundOrEmpty("Blocks");
        CompoundTag palette = blocks.getCompoundOrEmpty("Palette");
        byte[] data = blocks.getByteArray("Data").orElseThrow();
        // The origin is on the trunk; the crown reaches from offset to offset + width.
        BlockPos base = new BlockPos(edge - offset[0], GROUND, 0);

        Map<Integer, BlockState> states = new HashMap<>();
        for (String key : palette.keySet()) {
            try {
                states.put(palette.getIntOr(key, 0),
                        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, key, false).blockState());
            } catch (CommandSyntaxException e) {
                states.put(palette.getIntOr(key, 0), Blocks.AIR.defaultBlockState());
            }
        }

        Map<BlockPos, BlockState> placed = new java.util.LinkedHashMap<>();
        int index = 0;
        int read = 0;
        while (read < data.length) {
            int value = 0;
            int shift = 0;
            byte next;
            do {
                next = data[read++];
                value |= (next & 0x7F) << shift;
                shift += 7;
            } while ((next & 0x80) != 0);

            int x = index % width;
            int z = index / width % length;
            int y = index / (width * length);
            index++;
            BlockState state = states.getOrDefault(value, Blocks.AIR.defaultBlockState());
            if (state.isAir() || y >= height) {
                continue;
            }
            if (state.hasProperty(LeavesBlock.PERSISTENT)) {
                state = state.setValue(LeavesBlock.PERSISTENT, true);
            }
            placed.put(base.offset(offset[0] + x, offset[1] + y, offset[2] + z), state);
        }
        if (!tree.mistle().isEmpty()) {
            boolean dry = tree.mistle().equals("drymistle");
            mistle(placed, new java.util.Random(tree.id().hashCode()), dry ? Blocks.MANGROVE_ROOTS : Blocks.MOSS_BLOCK,
                    dry ? List.of(Blocks.SHORT_DRY_GRASS, Blocks.TALL_DRY_GRASS) : List.of(Blocks.BUSH));
        }
        List<BlockPos> leaves = new ArrayList<>();
        List<BlockPos> tinted = new ArrayList<>();
        placed.forEach((pos, state) -> {
            level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (state.is(BlockTags.LEAVES)) {
                leaves.add(pos);
            }
            if (TINTED.contains(state.getBlock())) {
                tinted.add(pos);
            }
        });
        return new Planted(tree, base.getX(), leaves, tinted, Math.max(width, length), height);
    }

    /** TreeArchive's &mistle: a clump in about one leaf in six that is open above, most with a bush on top. */
    private static void mistle(Map<BlockPos, BlockState> placed, java.util.Random random, Block clump,
                               List<Block> tops) {
        List<BlockPos> open = new ArrayList<>();
        int leaves = 0;
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            if (entry.getValue().is(BlockTags.LEAVES)) {
                leaves++;
                if (!placed.containsKey(entry.getKey().above())) {
                    open.add(entry.getKey());
                }
            }
        }
        java.util.Collections.shuffle(open, random);
        int wanted = (int) Math.min(open.size() / 2, Math.round(leaves * 0.18));
        for (BlockPos leaf : open.subList(0, wanted)) {
            placed.put(leaf, clump.defaultBlockState());
            if (random.nextDouble() < 0.62) {
                placed.put(leaf.above(), tops.get(random.nextInt(tops.size())).defaultBlockState());
            }
        }
    }
}
