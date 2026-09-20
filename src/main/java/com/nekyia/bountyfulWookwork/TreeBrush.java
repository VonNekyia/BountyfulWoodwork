package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * An item bound to a tree type. Right-clicking with it in creative places a random
 * tree of that type on the block the player is looking at, however far away.
 */
final class TreeBrush implements Listener {

    static final String PERMISSION = "bountyfulwookwork.admin";

    /** Trees are only placed where this could stand, i.e. on the blocks saplings grow on. */
    private static final BlockData SAPLING = Material.OAK_SAPLING.createBlockData();

    private final NamespacedKey key;
    private final TreeSchematics schematics;
    private final TreePaster paster;

    TreeBrush(JavaPlugin plugin, TreeSchematics schematics, TreePaster paster) {
        this.key = new NamespacedKey(plugin, "tree_brush");
        this.schematics = schematics;
        this.paster = paster;
    }

    /** Binds the item to a tree type, or unbinds it when {@code type} is null. */
    void bind(ItemStack item, @Nullable String type) {
        item.editMeta(meta -> {
            if (type == null) {
                meta.getPersistentDataContainer().remove(key);
            } else {
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type);
            }
        });
    }

    private @Nullable String boundType(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // Not ignoreCancelled: right-clicking air arrives already cancelled.
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        String type = boundType(event.getItem());
        if (type == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Player player = event.getPlayer();
        if (!player.hasPermission(PERMISSION)) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.sendActionBar(Component.text("Tree brushes only work in creative mode.", NamedTextColor.RED));
            return;
        }

        Clipboard clipboard = schematics.pick(type);
        if (clipboard == null) {
            player.sendActionBar(Component.text("Tree type '" + type + "' has no schematics.", NamedTextColor.RED));
            return;
        }
        Block target = player.getTargetBlockExact(schematics.brushRange(), FluidCollisionMode.NEVER);
        if (target == null) {
            player.sendActionBar(Component.text("No block in range.", NamedTextColor.RED));
            return;
        }
        // Always on top of the targeted block, whichever face was aimed at, so that
        // spot must have room for a sapling and ground a sapling grows on.
        Block base = target.getRelative(BlockFace.UP);
        if (!TreePaster.canHoldTrunk(base) || !SAPLING.isSupported(base)) {
            player.sendActionBar(Component.text("A sapling could not grow there.", NamedTextColor.RED));
            return;
        }
        if (!paster.paste(type, clipboard, base, player)) {
            player.sendActionBar(Component.text("The tree would collide with blocks there.", NamedTextColor.RED));
        }
    }
}
