package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.concurrent.ThreadLocalRandom;
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
 * An item bound to what it places, on the block the player is looking at, however far.
 * It is bound to a category and what of it, as in /bw brush:
 * <ul>
 *   <li>{@code type birch,oak} or {@code creator 70%snifferish,30%graysun} - one of
 *       those trees;</li>
 *   <li>{@code block gold_block,diamond_block} - one of those blocks, for painting the
 *       markers a preview turns into trees;</li>
 *   <li>{@code block gold_block:silver_fir/diamond_block:beech} - the marker, with its
 *       tree shown on it right away as in /bw preview.</li>
 * </ul>
 * Items bound before there were categories hold the words alone, and still work.
 */
final class TreeBrush implements Listener {

    static final String PERMISSION = "bountyfulwookwork.admin";

    /** Trees are only placed where this could stand, i.e. on the blocks saplings grow on. */
    private static final BlockData SAPLING = Material.OAK_SAPLING.createBlockData();

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final TreeArchive archive;
    private final TreePaster paster;
    private final TreePreview preview;

    TreeBrush(JavaPlugin plugin, TreeArchive archive, TreePaster paster, TreePreview preview) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "tree_brush");
        this.archive = archive;
        this.paster = paster;
        this.preview = preview;
    }

    /** Binds the item to a selection, or unbinds it when {@code selection} is null. */
    void bind(ItemStack item, @Nullable String selection) {
        item.editMeta(meta -> {
            if (selection == null) {
                meta.getPersistentDataContainer().remove(key);
            } else {
                meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, selection);
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
        String selection = boundType(event.getItem());
        if (selection == null) {
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

        Block target = player.getTargetBlockExact(
                Math.max(1, plugin.getConfig().getInt("brush-range", 256)), FluidCollisionMode.NEVER);
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

        int space = selection.indexOf(' ');
        String category = space < 0 ? "" : selection.substring(0, space);
        String what = selection.substring(space + 1);
        PreviewSpec spec = category.equals("block") || category.isEmpty() ? spec(what) : null;
        if (spec != null) {
            marker(player, base, spec, what);
            return;
        }
        TreeArchive.Entry entry = trees(category, what).pick(archive.entries());
        Clipboard clipboard = entry == null ? null : archive.clipboard(entry);
        if (clipboard == null) {
            player.sendActionBar(Component.text("No archived tree matches '" + selection + "'.",
                    NamedTextColor.RED));
            return;
        }
        if (!paster.paste(entry.type(), clipboard, base, player)) {
            player.sendActionBar(Component.text("The tree would collide with blocks there.", NamedTextColor.RED));
        }
    }

    /** Sets one of the spec's blocks, and lets the previews it lies in grow its tree. */
    private void marker(Player player, Block base, PreviewSpec spec, String text) {
        Material material = spec.pickBlock(ThreadLocalRandom.current());
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(base.getWorld()))) {
            session.setBlock(BlockVector3.at(base.getX(), base.getY(), base.getZ()),
                    BukkitAdapter.adapt(material.createBlockData()));
            WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player)).remember(session);
        } catch (WorldEditException e) {
            player.sendActionBar(Component.text("Could not set the block: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        // Two ticks later: the block goes out to the clients with the next world tick,
        // which may come after a task of the next tick, and would otherwise land on top
        // of the tree's lowest trunk block.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> preview.brushed(player, base, spec, text), 2);
    }

    /** The trees a brush of this category is about. */
    static TreeSelection trees(String category, String what) {
        return TreeSelection.parse(what).by(switch (category) {
            case "type" -> TreeSelection.Field.TYPE;
            case "creator" -> TreeSelection.Field.CREATOR;
            default -> TreeSelection.Field.ANY;
        });
    }

    /** The brush's text read as blocks, or null when it names trees instead. */
    static @Nullable PreviewSpec spec(String text) {
        try {
            return PreviewSpec.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
