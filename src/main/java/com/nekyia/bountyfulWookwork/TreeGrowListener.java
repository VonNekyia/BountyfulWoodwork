package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Grows schematic trees in place of the vanilla trees saplings would grow into. */
final class TreeGrowListener implements Listener {

    /** Trees that grow from a 2x2 of saplings rather than a single one. */
    private static final Set<TreeType> TWO_BY_TWO = EnumSet.of(
            TreeType.MEGA_REDWOOD, TreeType.MEGA_PINE, TreeType.JUNGLE,
            TreeType.DARK_OAK, TreeType.PALE_OAK, TreeType.PALE_OAK_CREAKING);

    private final JavaPlugin plugin;
    private final TreeSchematics schematics;
    private final TreePaster paster;

    TreeGrowListener(JavaPlugin plugin, TreeSchematics schematics, TreePaster paster) {
        this.plugin = plugin;
        this.schematics = schematics;
        this.paster = paster;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        TreeType species = event.getSpecies();
        String type = schematics.typeFor(species);
        Clipboard clipboard = type == null ? null : schematics.pick(type);
        if (type == null || clipboard == null) {
            return;
        }
        event.setCancelled(true);

        // The event fires while the server is still capturing the vanilla tree's
        // block changes, so the sapling is swapped for the schematic a tick later.
        Block sapling = event.getLocation().getBlock();
        Material saplingType = sapling.getType();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (sapling.getType() != saplingType) {
                return;
            }
            List<Block> saplings = TWO_BY_TWO.contains(species) ? twoByTwo(sapling) : List.of(sapling);

            // Saplings count as space a tree may grow into, so they are only removed
            // once the tree fits; one that would collide leaves its sapling as it was.
            if (!paster.paste(type, clipboard, saplings.getFirst(), null)) {
                return;
            }
            for (Block block : saplings) {
                // Those the schematic put a block on are already gone.
                if (block.getType() == saplingType) {
                    block.setType(Material.AIR, false);
                }
            }
        });
    }

    /**
     * The 2x2 of saplings this one belongs to, north-west corner first, checking the
     * squares in the same order vanilla does. Just this sapling if there is none.
     */
    private static List<Block> twoByTwo(Block sapling) {
        Material type = sapling.getType();
        for (int dx = 0; dx >= -1; dx--) {
            for (int dz = 0; dz >= -1; dz--) {
                Block corner = sapling.getRelative(dx, 0, dz);
                List<Block> square = List.of(corner, corner.getRelative(1, 0, 0),
                        corner.getRelative(0, 0, 1), corner.getRelative(1, 0, 1));
                if (square.stream().allMatch(block -> block.getType() == type)) {
                    return square;
                }
            }
        }
        return List.of(sapling);
    }
}
