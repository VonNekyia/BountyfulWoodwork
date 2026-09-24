package com.nekyia.bountyfulWookwork;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class BountyfulWookwork extends JavaPlugin {

    private TreeArchive archive;
    private TreeFelling felling;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        archive = new TreeArchive(this);
        archive.reload();

        TreeRegistry registry = new TreeRegistry(this);
        registry.indexLoadedChunks();

        felling = new TreeFelling(this, registry);
        felling.reload();

        TreePaster paster = new TreePaster(this, registry);
        TreePreview preview = new TreePreview(this, archive, paster);
        TreeBrush brush = new TreeBrush(this, archive, paster, preview);

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(registry, this);
        plugins.registerEvents(felling, this);
        plugins.registerEvents(new TreeGrowListener(this, archive, paster), this);
        plugins.registerEvents(brush, this);
        plugins.registerEvents(preview, this);

        DebugGeneration debug = new DebugGeneration(this, archive, paster);
        plugins.registerEvents(debug, this);

        TrunkPosCommand trunkPos = new TrunkPosCommand();
        Objects.requireNonNull(getCommand("/trunkpos")).setExecutor(trunkPos);

        WookworkCommand command = new WookworkCommand(felling, brush, debug,
                archive, new TreeLayout(this, archive, paster), new TreeForest(this, archive, paster),
                new TreeDuplicates(this, archive), preview, trunkPos);
        PluginCommand bw = Objects.requireNonNull(getCommand("bw"));
        bw.setExecutor(command);
        bw.setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        if (felling != null) {
            felling.removeAllSlowdowns();
        }
        if (archive != null) {
            archive.close();
        }
    }
}
