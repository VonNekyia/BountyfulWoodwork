package com.nekyia.bountyfulWookwork;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Remembers which blocks belong to the trees this plugin placed. Every chunk a tree
 * touches stores that tree's blocks inside it in the chunk's own persistent data, so
 * trees survive restarts without a database. Loaded chunks are indexed in memory,
 * which makes asking whether a block belongs to a tree a single map lookup.
 */
final class TreeRegistry implements Listener {

    /** The blocks one tree has in one chunk, with what all parts of that tree share. */
    record TreePart(UUID id, String type, int size, long[] chunks, Map<Long, Material> blocks) {
    }

    /** Shifts block heights into the unsigned 12 bits they are packed into. */
    private static final int Y_OFFSET = 2048;

    private final NamespacedKey treesKey;
    private final NamespacedKey idKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey sizeKey;
    private final NamespacedKey chunksKey;
    private final NamespacedKey paletteKey;
    private final NamespacedKey blocksKey;

    /** World -> chunk key -> the tree parts stored in that chunk. */
    private final Map<UUID, Map<Long, List<TreePart>>> partsByChunk = new HashMap<>();
    /** World -> block key -> the tree part that block belongs to. */
    private final Map<UUID, Map<Long, TreePart>> partsByBlock = new HashMap<>();

    TreeRegistry(JavaPlugin plugin) {
        treesKey = new NamespacedKey(plugin, "trees");
        idKey = new NamespacedKey(plugin, "id");
        typeKey = new NamespacedKey(plugin, "type");
        sizeKey = new NamespacedKey(plugin, "size");
        chunksKey = new NamespacedKey(plugin, "chunks");
        paletteKey = new NamespacedKey(plugin, "palette");
        blocksKey = new NamespacedKey(plugin, "blocks");
    }

    /** Indexes the chunks that were loaded before the plugin enabled. */
    void indexLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                index(chunk);
            }
        }
    }

    /** Records a tree that was just placed, given as block key -> the block placed there. */
    void register(World world, String type, Map<Long, Material> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        Map<Long, Map<Long, Material>> byChunk = new HashMap<>();
        blocks.forEach((key, material) -> byChunk
                .computeIfAbsent(Chunk.getChunkKey(BlockKeys.x(key) >> 4, BlockKeys.z(key) >> 4),
                        chunk -> new HashMap<>())
                .put(key, material));

        UUID id = UUID.randomUUID();
        long[] chunks = byChunk.keySet().stream().mapToLong(Long::longValue).toArray();
        byChunk.forEach((chunkKey, chunkBlocks) -> {
            // Loaded first: loading indexes the chunk from its stored data, which would
            // drop a part added before it.
            Chunk chunk = chunkAt(world, chunkKey);
            add(world, chunkKey, new TreePart(id, type, blocks.size(), chunks, new HashMap<>(chunkBlocks)));
            save(chunk);
        });
    }

    /**
     * The tree part this block belongs to, or null. A block that is no longer what the
     * tree placed there (//undo, for one) does not belong to it any more.
     */
    @Nullable TreePart lookup(Block block) {
        Map<Long, TreePart> blocks = partsByBlock.get(block.getWorld().getUID());
        if (blocks == null) {
            return null;
        }
        long key = BlockKeys.of(block);
        TreePart part = blocks.get(key);
        return part != null && part.blocks().get(key) == block.getType() ? part : null;
    }

    /** Every block of the tree across all of its chunks, loading them if needed. */
    Map<Long, Material> blocksOf(World world, TreePart tree) {
        Map<Long, Material> blocks = new HashMap<>();
        for (long chunkKey : tree.chunks()) {
            chunkAt(world, chunkKey);
            for (TreePart part : partsIn(world, chunkKey)) {
                if (part.id().equals(tree.id())) {
                    blocks.putAll(part.blocks());
                }
            }
        }
        return blocks;
    }

    /** Forgets the whole tree. */
    void unregister(World world, TreePart tree) {
        for (long chunkKey : tree.chunks()) {
            Chunk chunk = chunkAt(world, chunkKey);
            for (TreePart part : List.copyOf(partsIn(world, chunkKey))) {
                if (part.id().equals(tree.id())) {
                    removeBlocks(world, part);
                    part.blocks().clear();
                }
            }
            save(chunk);
        }
    }

    /** Forgets a single block of a tree, when it was broken on purpose. */
    void forget(Block block) {
        TreePart part = lookup(block);
        if (part == null) {
            return;
        }
        long key = BlockKeys.of(block);
        part.blocks().remove(key);
        partsByBlock.get(block.getWorld().getUID()).remove(key, part);
        save(block.getChunk());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        index(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        unindex(event.getWorld(), event.getChunk().getChunkKey());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        partsByChunk.remove(event.getWorld().getUID());
        partsByBlock.remove(event.getWorld().getUID());
    }

    private void index(Chunk chunk) {
        World world = chunk.getWorld();
        long chunkKey = chunk.getChunkKey();
        unindex(world, chunkKey);

        List<PersistentDataContainer> stored = chunk.getPersistentDataContainer()
                .get(treesKey, PersistentDataType.LIST.dataContainers());
        if (stored == null) {
            return;
        }
        for (PersistentDataContainer container : stored) {
            TreePart part = decode(chunk, container);
            if (part != null) {
                add(world, chunkKey, part);
            }
        }
    }

    private void unindex(World world, long chunkKey) {
        Map<Long, List<TreePart>> chunks = partsByChunk.get(world.getUID());
        List<TreePart> parts = chunks == null ? null : chunks.remove(chunkKey);
        if (parts != null) {
            parts.forEach(part -> removeBlocks(world, part));
        }
    }

    private void add(World world, long chunkKey, TreePart part) {
        partsByChunk.computeIfAbsent(world.getUID(), uid -> new HashMap<>())
                .computeIfAbsent(chunkKey, key -> new ArrayList<>())
                .add(part);
        Map<Long, TreePart> blocks = partsByBlock.computeIfAbsent(world.getUID(), uid -> new HashMap<>());
        for (long key : part.blocks().keySet()) {
            blocks.put(key, part);
        }
    }

    /** Drops the block index entries pointing at this part. */
    private void removeBlocks(World world, TreePart part) {
        Map<Long, TreePart> blocks = partsByBlock.get(world.getUID());
        if (blocks != null) {
            for (long key : part.blocks().keySet()) {
                blocks.remove(key, part);
            }
        }
    }

    private List<TreePart> partsIn(World world, long chunkKey) {
        Map<Long, List<TreePart>> chunks = partsByChunk.get(world.getUID());
        return chunks == null ? List.of() : chunks.getOrDefault(chunkKey, List.of());
    }

    /** Writes the chunk's indexed tree parts back into its persistent data. */
    private void save(Chunk chunk) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        Map<Long, List<TreePart>> chunks = partsByChunk.get(chunk.getWorld().getUID());
        List<TreePart> parts = chunks == null ? null : chunks.get(chunk.getChunkKey());
        if (parts != null) {
            parts.removeIf(part -> part.blocks().isEmpty());
        }
        if (parts == null || parts.isEmpty()) {
            if (chunks != null) {
                chunks.remove(chunk.getChunkKey());
            }
            data.remove(treesKey);
            return;
        }
        List<PersistentDataContainer> encoded = parts.stream()
                .map(part -> encode(data.getAdapterContext(), part))
                .toList();
        data.set(treesKey, PersistentDataType.LIST.dataContainers(), encoded);
    }

    /**
     * Blocks are packed into one int each: x and z within the chunk (4 bits each), the
     * height (12 bits) and an index into the part's list of block types (12 bits).
     */
    private PersistentDataContainer encode(PersistentDataAdapterContext context, TreePart part) {
        List<String> palette = new ArrayList<>();
        Map<Material, Integer> paletteIndex = new HashMap<>();
        int[] packed = new int[part.blocks().size()];
        int i = 0;
        for (Map.Entry<Long, Material> entry : part.blocks().entrySet()) {
            long key = entry.getKey();
            int index = paletteIndex.computeIfAbsent(entry.getValue(), material -> {
                palette.add(material.getKey().toString());
                return palette.size() - 1;
            });
            packed[i++] = (BlockKeys.x(key) & 15)
                    | (BlockKeys.z(key) & 15) << 4
                    | (BlockKeys.y(key) + Y_OFFSET) << 8
                    | index << 20;
        }

        PersistentDataContainer container = context.newPersistentDataContainer();
        container.set(idKey, PersistentDataType.STRING, part.id().toString());
        container.set(typeKey, PersistentDataType.STRING, part.type());
        container.set(sizeKey, PersistentDataType.INTEGER, part.size());
        container.set(chunksKey, PersistentDataType.LONG_ARRAY, part.chunks());
        container.set(paletteKey, PersistentDataType.LIST.strings(), palette);
        container.set(blocksKey, PersistentDataType.INTEGER_ARRAY, packed);
        return container;
    }

    private @Nullable TreePart decode(Chunk chunk, PersistentDataContainer container) {
        String id = container.get(idKey, PersistentDataType.STRING);
        String type = container.get(typeKey, PersistentDataType.STRING);
        Integer size = container.get(sizeKey, PersistentDataType.INTEGER);
        long[] chunks = container.get(chunksKey, PersistentDataType.LONG_ARRAY);
        List<String> paletteNames = container.get(paletteKey, PersistentDataType.LIST.strings());
        int[] packed = container.get(blocksKey, PersistentDataType.INTEGER_ARRAY);
        if (id == null || type == null || size == null || chunks == null || paletteNames == null || packed == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }

        Material[] palette = paletteNames.stream().map(Material::matchMaterial).toArray(Material[]::new);
        Map<Long, Material> blocks = new HashMap<>();
        for (int value : packed) {
            int index = value >>> 20;
            if (index >= palette.length || palette[index] == null) {
                continue;
            }
            int x = chunk.getX() << 4 | (value & 15);
            int z = chunk.getZ() << 4 | (value >>> 4 & 15);
            int y = (value >>> 8 & 4095) - Y_OFFSET;
            blocks.put(BlockKeys.of(x, y, z), palette[index]);
        }
        return new TreePart(uuid, type, size, chunks, blocks);
    }

    /** Paper's chunk keys hold x in the low 32 bits and z in the high 32 bits. */
    private static Chunk chunkAt(World world, long chunkKey) {
        return world.getChunkAt((int) chunkKey, (int) (chunkKey >> 32));
    }
}
