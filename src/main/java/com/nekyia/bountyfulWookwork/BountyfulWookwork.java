package com.nekyia.bountyfulWookwork;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class BountyfulWookwork extends JavaPlugin {

    private TreeSchematics schematics;
    private TreeFelling felling;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        schematics = new TreeSchematics(this);
        schematics.reload();

        TreeRegistry registry = new TreeRegistry(this);
        registry.indexLoadedChunks();

        felling = new TreeFelling(this, registry);
        felling.reload();

        TreePaster paster = new TreePaster(schematics, registry);
        TreeBrush brush = new TreeBrush(this, schematics, paster);

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(registry, this);
        plugins.registerEvents(felling, this);
        plugins.registerEvents(new TreeGrowListener(this, schematics, paster), this);
        plugins.registerEvents(brush, this);

        DebugGeneration debug = new DebugGeneration(this, schematics, paster);
        plugins.registerEvents(debug, this);

        TreeArchive archive = new TreeArchive(this);
        archive.reload();
        TrunkPosCommand trunkPos = new TrunkPosCommand();
        Objects.requireNonNull(getCommand("/trunkpos")).setExecutor(trunkPos);

        WookworkCommand command = new WookworkCommand(schematics, felling, brush, debug,
                archive, new TreeLayout(this, archive, paster), new TreeForest(this, archive, paster),
                new TreeDuplicates(this, archive), trunkPos);
        PluginCommand bw = Objects.requireNonNull(getCommand("bw"));
        bw.setExecutor(command);
        bw.setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        if (felling != null) {
            felling.removeAllSlowdowns();
        }
        if (schematics != null) {
            schematics.clear();
        }
    }
}
