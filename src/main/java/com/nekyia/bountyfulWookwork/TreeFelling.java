package com.nekyia.bountyfulWookwork;

import com.nekyia.bountyfulWookwork.TreeRegistry.TreePart;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Trees the plugin placed cannot be taken apart. Cutting into one takes as long as
 * breaking the block normally, times the size of the tree; once that block breaks,
 * the whole tree comes down - the trunk at once, the leaves decaying right after.
 *
 * <p>The longer cut is a temporary modifier on the player's block break speed, so
 * the client shows the normal cracking and tools, enchantments and haste still count.
 */
final class TreeFelling implements Listener {

    private record Drop(String item, double perBlock) {
    }

    private final JavaPlugin plugin;
    private final TreeRegistry registry;
    private final NamespacedKey slowdownKey;
    /** Drop items already reported as missing, so the log is not flooded. */
    private final Set<String> warned = new HashSet<>();

    private double timePerBlock;
    private double maxTicks;
    private int leafDecayTicks;
    private Map<String, List<Drop>> drops = Map.of();

    TreeFelling(JavaPlugin plugin, TreeRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.slowdownKey = new NamespacedKey(plugin, "felling");
    }

    /** Rereads the felling section; config.yml itself must already be reloaded. */
    void reload() {
        FileConfiguration config = plugin.getConfig();
        timePerBlock = Math.max(0, config.getDouble("felling.time-per-block", 1.0));
        maxTicks = Math.max(0, config.getDouble("felling.max-seconds", 30)) * 20;
        leafDecayTicks = Math.max(1, config.getInt("felling.leaf-decay-ticks", 40));

        Map<String, List<Drop>> byType = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("felling.drops");
        if (section != null) {
            for (String type : section.getKeys(false)) {
                List<Drop> list = new ArrayList<>();
                for (Map<?, ?> entry : section.getMapList(type)) {
                    if (entry.get("item") instanceof String item && entry.get("per-block") instanceof Number perBlock) {
                        list.add(new Drop(item.toLowerCase(Locale.ROOT), perBlock.doubleValue()));
                    } else {
                        plugin.getLogger().warning("Drop for '" + type + "' in config.yml needs item and per-block: " + entry);
                    }
                }
                byType.put(type.toLowerCase(Locale.ROOT), list);
            }
        }
        drops = byType;
        warned.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        removeSlowdown(player);

        Block block = event.getBlock();
        TreePart tree = registry.lookup(block);
        if (tree == null || Tag.LEAVES.isTagged(block.getType()) || bypasses(player)) {
            return;
        }

        // Break speed is the share of the block broken per tick.
        float speed = block.getBreakSpeed(player);
        if (speed <= 0) {
            return;
        }
        double ticks = 1 / speed;
        // max-seconds caps the time without Efficiency, and Efficiency shortens it from
        // there - otherwise every big tree took exactly max-seconds, whatever the axe.
        double efficiency = efficiencyFactor(block, player.getInventory().getItemInMainHand());
        double fellingTicks = Math.min(ticks * efficiency * Math.max(1, tree.size() * timePerBlock), maxTicks)
                / efficiency;
        AttributeInstance breakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (fellingTicks <= ticks || breakSpeed == null) {
            return;
        }
        event.setInstaBreak(false);
        breakSpeed.addTransientModifier(new AttributeModifier(
                slowdownKey, ticks / fellingTicks - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageAbort(BlockDamageAbortEvent event) {
        removeSlowdown(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        TreePart tree = registry.lookup(block);
        if (tree == null) {
            return;
        }
        Player player = event.getPlayer();
        if (bypasses(player)) {
            registry.forget(block);
            return;
        }
        event.setCancelled(true);
        if (!Tag.LEAVES.isTagged(block.getType())) {
            fell(block, tree, player);
        }
    }

    // Separate from onBreak so the slowdown also goes when another plugin cancelled the break.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreakFinished(BlockBreakEvent event) {
        removeSlowdown(event.getPlayer());
    }

    // The modifier is transient, but a crash mid-cut must not leave anyone slowed.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        removeSlowdown(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeSlowdown(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> registry.lookup(block) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> registry.lookup(block) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> registry.lookup(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> registry.lookup(block) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (registry.lookup(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (registry.lookup(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    void removeAllSlowdowns() {
        plugin.getServer().getOnlinePlayers().forEach(this::removeSlowdown);
    }

    private void removeSlowdown(Player player) {
        AttributeInstance breakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (breakSpeed != null) {
            breakSpeed.removeModifier(slowdownKey);
        }
    }

    /** How many times faster Efficiency makes this tool break this block; 1 without it. */
    private static double efficiencyFactor(Block block, ItemStack tool) {
        int level = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        float base = block.getDestroySpeed(tool, false);
        // Like vanilla: Efficiency adds level^2 + 1, and only to a tool suited to the block.
        return level > 0 && base > 1 ? (base + level * level + 1) / base : 1;
    }

    /** Builders in creative with the admin permission break trees block by block. */
    private static boolean bypasses(Player player) {
        return player.getGameMode() == GameMode.CREATIVE && player.hasPermission(TreeBrush.PERMISSION);
    }

    private void fell(Block cut, TreePart tree, Player player) {
        World world = cut.getWorld();
        Map<Long, Material> blocks = registry.blocksOf(world, tree);
        registry.unregister(world, tree);

        int trunkBlocks = 0;
        Map<Integer, List<Block>> leavesByTick = new HashMap<>();
        for (Map.Entry<Long, Material> entry : blocks.entrySet()) {
            long key = entry.getKey();
            Block block = world.getBlockAt(BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key));
            // Only what is still the block the tree placed; anything built into it stays.
            if (block.getType() != entry.getValue()) {
                continue;
            }
            if (Tag.LEAVES.isTagged(entry.getValue())) {
                leavesByTick.computeIfAbsent(1 + ThreadLocalRandom.current().nextInt(leafDecayTicks),
                        tick -> new ArrayList<>()).add(block);
            } else {
                block.setType(Material.AIR, false);
                trunkBlocks++;
            }
        }
        leavesByTick.forEach((tick, leaves) ->
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> decay(leaves), tick));

        Location center = cut.getLocation().toCenterLocation();
        world.playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.6f);
        dropItems(tree.type(), trunkBlocks, center);

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.isEmpty()) {
            player.getInventory().setItemInMainHand(tool.damage(1, player));
        }
    }

    private static void decay(List<Block> leaves) {
        for (Block block : leaves) {
            if (!Tag.LEAVES.isTagged(block.getType())) {
                continue;
            }
            BlockData data = block.getBlockData();
            block.setType(Material.AIR, false);
            block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().toCenterLocation(),
                    4, 0.3, 0.3, 0.3, 0, data);
        }
    }

    /** Drops each configured item, per-block times the trunk blocks, rounding at random. */
    private void dropItems(String type, int trunkBlocks, Location location) {
        List<Drop> list = drops.getOrDefault(type, drops.getOrDefault("default", List.of()));
        for (Drop drop : list) {
            double exact = trunkBlocks * drop.perBlock();
            int amount = (int) exact + (ThreadLocalRandom.current().nextDouble() < exact % 1 ? 1 : 0);
            ItemStack item = amount > 0 ? createItem(drop.item()) : null;
            if (item == null) {
                continue;
            }
            while (amount > 0) {
                ItemStack stack = item.clone();
                stack.setAmount(Math.min(amount, stack.getMaxStackSize()));
                amount -= stack.getAmount();
                location.getWorld().dropItemNaturally(location, stack);
            }
        }
    }

    private @Nullable ItemStack createItem(String item) {
        if (item.startsWith("nexo:")) {
            String id = item.substring("nexo:".length());
            ItemStack stack = plugin.getServer().getPluginManager().isPluginEnabled("Nexo") ? Nexo.item(id) : null;
            if (stack == null) {
                warnOnce(item, "Nexo item '" + id + "' does not exist (yet), so felled trees do not drop it.");
            }
            return stack;
        }
        Material material = Material.matchMaterial(item);
        if (material == null || !material.isItem()) {
            warnOnce(item, "Unknown drop item in config.yml: " + item);
            return null;
        }
        return ItemStack.of(material);
    }

    private void warnOnce(String item, String message) {
        if (warned.add(item)) {
            plugin.getLogger().warning(message);
        }
    }

    /** Kept apart so Nexo's classes are only loaded when Nexo is installed. */
    private static final class Nexo {

        static @Nullable ItemStack item(String id) {
            ItemBuilder builder = NexoItems.itemFromId(id);
            return builder == null ? null : builder.build();
        }
    }
}
