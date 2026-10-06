package com.nekyia.bountyfulWoodwork;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Trees on the main server: a tree is not cut block by block but felled whole, taking
 * as long as its size asks for. Only trees this plugin knows of are felled so; the
 * trees themselves are archived and built with TreeArchive on the creative server.
 */
public final class BountyfulWoodwork extends JavaPlugin {

    /** Lets creative players break single blocks of a tree. */
    static final String ADMIN = "bountyfulwoodwork.admin";

    private TreeFelling felling;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        TreeRegistry registry = new TreeRegistry(this);
        registry.indexLoadedChunks();
        felling = new TreeFelling(this, registry);
        felling.reload();
        LeafColors leafColors = new LeafColors(this, registry);
        getServer().getMessenger().registerOutgoingPluginChannel(this, LeafColors.CHANNEL);
        registry.onChange(leafColors::changed);
        leafColors.reload();

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(registry, this);
        plugins.registerEvents(felling, this);
        plugins.registerEvents(leafColors, this);

        // /bw reload - rereads config.yml.
        Objects.requireNonNull(getCommand("bw")).setExecutor((sender, command, label, args) -> {
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                return false;
            }
            reloadConfig();
            felling.reload();
            leafColors.reload();
            sender.sendMessage(Component.text("Reloaded the felling settings and leaf colours.", NamedTextColor.GREEN));
            return true;
        });
    }

    @Override
    public void onDisable() {
        if (felling != null) {
            felling.removeAllSlowdowns();
        }
    }
}
