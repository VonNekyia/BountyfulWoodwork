package com.nekyia.bountyfulWookwork;

import com.sk89q.worldedit.math.BlockVector3;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * //trunkpos, or /bw trunkpos, marks the block a tree stands on, the way //pos1 marks a
 * corner. The mark is what /bw archive puts the blueprint's origin on, so a tree is
 * later placed by its trunk rather than by a corner.
 *
 * <p>It takes the block you are looking at, or the one you stand on when you are not
 * looking at anything within reach.
 */
final class TrunkPosCommand implements CommandExecutor {

    private static final int REACH = 10;

    private final Map<UUID, BlockVector3> marks = new HashMap<>();

    /** The trunk block this player marked, or null. */
    @Nullable BlockVector3 mark(Player player) {
        return marks.get(player.getUniqueId());
    }

    void clear(Player player) {
        marks.remove(player.getUniqueId());
    }

    /** Marks the block being looked at, or the one the player stands on. */
    void set(Player player) {
        Block target = player.getTargetBlockExact(REACH);
        Block trunk = target != null ? target : player.getLocation().getBlock();
        marks.put(player.getUniqueId(), BlockVector3.at(trunk.getX(), trunk.getY(), trunk.getZ()));
        player.sendMessage(Component.text("Trunk set to " + trunk.getX() + ", " + trunk.getY() + ", "
                + trunk.getZ() + ".", NamedTextColor.GREEN));
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command,
                             @NonNull String label, @NonNull String @NonNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can mark a trunk.", NamedTextColor.RED));
            return true;
        }
        set(player);
        return true;
    }
}
