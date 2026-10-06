package com.nekyia.treearchive;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The tree archive, for the creative server: archiving trees as blueprints, and
 * building with them - brushes, layouts, previews of whole forests.
 */
public final class TreeArchivePlugin extends JavaPlugin {

    private TreeArchive archive;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        archive = new TreeArchive(this);
        archive.reload();

        TreePaster paster = new TreePaster(this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, LeafColours.CHANNEL);
        TreePreview preview = new TreePreview(this, archive, paster);
        TreeBrush brush = new TreeBrush(this, archive, paster, preview);
        DebugGeneration debug = new DebugGeneration(this, archive, paster);

        PluginManager plugins = getServer().getPluginManager();
        plugins.registerEvents(new TreeGrowListener(this, archive, paster), this);
        plugins.registerEvents(brush, this);
        plugins.registerEvents(preview, this);
        plugins.registerEvents(debug, this);

        TrunkPosCommand trunkPos = new TrunkPosCommand();
        Objects.requireNonNull(getCommand("/trunkpos")).setExecutor(trunkPos);

        ArchiveCommand command = new ArchiveCommand(new PreviewLibrary(this), brush, debug, archive,
                new TreeLayout(this, archive, paster), new TreeForest(this, archive, paster),
                new TreeDuplicates(this, archive), preview, trunkPos);
        PluginCommand ta = Objects.requireNonNull(getCommand("ta"));
        ta.setExecutor(command);
        ta.setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        if (archive != null) {
            archive.close();
        }
    }
}
