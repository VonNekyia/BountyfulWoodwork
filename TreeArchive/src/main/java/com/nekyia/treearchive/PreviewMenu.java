package com.nekyia.treearchive;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * /ta preview as a menu, in the game's own dialogs, so nobody needs a mod for it. A
 * preview is put together marker block by marker block, each with its trees - a type, a
 * size, a creator or a name - their shares, their leaf colours from the player's own
 * colours, bright or fading outwards, a secondary colour, blossoms and how many, and
 * mistletoe, vines and what else hangs on a tree, each of a strength; colours are picked in the {@link ColourPicker}. It is shown right
 * away, or kept as a preset: on the player's own shelf, or in a namespace everyone can
 * use and only those with treearchive.namespaces change. What the menu makes is written
 * the way /ta preview reads it, so presets, hashes and shared links work as before.
 *
 * <p>Whatever comes back from a dialog is the client's word only: a changed client sends
 * any option and any text, so every answer is checked here as if it were typed.
 */
final class PreviewMenu implements Listener {

    /** Who may change the presets of namespaces, and start new ones. */
    static final String NAMESPACES = "treearchive.namespaces";
    /** The most trees an admin can set in the menu; more is still a command away. */
    private static final int ADMIN_LIMIT = 5000;
    private static final int WIDE = 300;
    private static final int TAB = 100;
    /** How far off a tree can be tried out, in blocks. */
    private static final int TRY_REACH = 64;
    private static final int TYPE = 130;
    private static final int QUICK = 100;
    private static final ClickCallback.Options CLICKS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build();
    /** Clicks closer together than this are dropped, in nanoseconds: nobody clicks that fast by hand. */
    private static final long CLICK_GAP = 100_000_000L;
    /** The marker blocks the built-in presets use, and what grows on them there. */
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("gold_block", "Broadleaf trees in the built-in presets");
        MARKERS.put("diamond_block", "Conifers in the built-in presets");
        MARKERS.put("emerald_block", "Birches in the built-in presets");
        MARKERS.put("redstone_block", "Fruit and accent trees in the built-in presets");
        MARKERS.put("netherite_block", "Dark and old trees in the built-in presets");
    }

    /** Names of presets, namespaces and colours. */
    static final String NAME = "[a-z0-9_-]{1,32}";
    /** How much one player keeps and puts together, so no one fills the server's files. */
    static final int MAX_KEPT = 64;
    private static final int MAX_MARKERS = 32;
    private static final int MAX_TREES = 16;

    /** The tabs of a tree's screen: a few inputs at a time, as a dialog has no headings between them. */
    private enum Tab {
        TREE("Tree", "What grows here, and how often"),
        LEAVES("Leaves", "Colours and blossoms"),
        EXTRAS("Extras", "What hangs in the crown");

        final String label;
        final String tooltip;

        Tab(String label, String tooltip) {
            this.label = label;
            this.tooltip = tooltip;
        }
    }

    /**
     * One thing that can hang on a tree: its modifier, what it is called, the highest
     * strength its slider goes to, and the strength it means written without a number.
     */
    private record Extra(String modifier, String label, int top, int plain) {
    }

    /** What can hang on a tree, as the Extras tab groups it into screens: more would not fit on one. */
    private static final Map<String, List<Extra>> EXTRAS = new LinkedHashMap<>();

    static {
        EXTRAS.put("Crown", List.of(
                new Extra("mistle", "Mistletoe: moss with bushes", 100, TreePaster.MISTLE_STRENGTH),
                new Extra("drymistle", "Dry mistletoe: roots with dry grass", 100, TreePaster.MISTLE_STRENGTH)));
        EXTRAS.put("Lianes", List.of(
                extra(TreeExtra.VINES, "Vines"),
                extra(TreeExtra.LICHEN, "Glow lichen")));
        EXTRAS.put("Hanging", List.of(
                extra(TreeExtra.GLOWBERRIES, "Glow berries"),
                extra(TreeExtra.ROOTS, "Hanging roots"),
                extra(TreeExtra.WEEPING, "Weeping vines"),
                extra(TreeExtra.TWISTING, "Twisting vines, growing up")));
    }

    private static Extra extra(TreeExtra extra, String label) {
        return new Extra(extra.modifier, label, TreeExtra.STRONGEST, TreeExtra.PLAIN);
    }

    /** One tree of a marker block: what it is, its share among the block's trees, its paint and what hangs on it. */
    private static final class Tree {
        /** Which archived tree, and which turn, Try it shows, so tries can be compared; not part of a preset. */
        long seed = ThreadLocalRandom.current().nextLong();
        String word = "";
        int share = 1;
        int @Nullable [] colours;
        boolean bright;
        boolean out;
        /** What hangs on it, by modifier, and how strongly; what is not here is not there. */
        final Map<String, Integer> extras = new HashMap<>();
        /** A secondary colour some of the leaves take, and the blossoms' colour - PRESENT set - or 0. */
        int secondary;
        int blossom;
        /** Leaves in blossom, in percent. */
        int bloom = TreeSelection.BLOOM;
    }

    /** A marker block - or several, by shares - and the trees growing on it; none means any. */
    private static final class Marker {
        String blocks = "";
        final List<Tree> trees = new ArrayList<>();
    }

    /** A preview being put together, and the preset it goes back to. */
    static final class Draft {
        PreviewLibrary.Shelf shelf;
        @Nullable String name;
        final List<Marker> markers = new ArrayList<>();
        /** The most trees, or 0 for the server's own limit. */
        int limit;
        boolean continuous;
        boolean bypassTerrain;

        Draft(PreviewLibrary.Shelf shelf, @Nullable String name) {
            this.shelf = shelf;
            this.name = name;
        }
    }

    private final JavaPlugin plugin;
    private final PreviewLibrary library;
    private final TreeArchive archive;
    private final TreePreview preview;
    /** Shows a preview from its parts, as a preset keeps them. */
    private final BiConsumer<Player, List<String>> show;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    /**
     * When each player last clicked. Every screen registers a callback per button, kept
     * until it runs out, so a changed client clicking as fast as it can would pile up
     * millions of them, each holding its draft.
     */
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final ColourPicker picker;

    PreviewMenu(JavaPlugin plugin, PreviewLibrary library, TreeArchive archive, TreePreview preview,
                BiConsumer<Player, List<String>> show) {
        this.plugin = plugin;
        this.library = library;
        this.archive = archive;
        this.preview = preview;
        this.show = show;
        this.picker = new ColourPicker(this, library);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drafts.remove(event.getPlayer().getUniqueId());
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    void open(Player player) {
        main(player, null);
    }

    /** Back to where the player was: the draft if there is one, else the start. */
    void home(Player player) {
        if (drafts.containsKey(player.getUniqueId())) {
            editor(player, null);
        } else {
            main(player, null);
        }
    }

    // ---- screens --------------------------------------------------------------------

    private void main(Player player, @Nullable String note) {
        UUID uuid = player.getUniqueId();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Quick forest", "The presets TreeArchive brings, a click away", who -> quick(who, null)));
        buttons.add(button("New preview", "Put one together from marker blocks and their trees", who -> {
            drafts.put(uuid, new Draft(PreviewLibrary.Shelf.of(uuid), null));
            editor(who, null);
        }));
        if (drafts.containsKey(uuid)) {
            buttons.add(button("Back to the draft", "The preview you were putting together", who -> editor(who, null)));
        }
        buttons.add(button("My presets", "Previews you kept", who -> presets(who, PreviewLibrary.Shelf.of(uuid), null)));
        buttons.add(button("Namespaces", "Presets everyone shares", who -> namespaces(who, null)));
        buttons.add(button("My colours", "Colours and fades to paint leaves with", who -> picker.manage(who, null)));
        if (preview.shared(player) != null) {
            buttons.add(button("Hide the preview", "Takes the trees away again", who -> {
                preview.clear(who);
                main(who, "Preview taken away.");
            }));
        }
        player.showDialog(dialog("Tree previews", lines(note, preview.shared(player) != null
                ? "A preview is showing." : "Marker blocks around you turn into trees from the archive - for your"
                        + " eyes only."), List.of(), buttons, button("Close", null, who -> who.closeDialog())));
    }

    /** The presets TreeArchive brings, a click from a forest; or, ticked, a copy to change. */
    private void quick(Player player, @Nullable String note) {
        UUID uuid = player.getUniqueId();
        List<ActionButton> buttons = new ArrayList<>();
        for (String namespace : library.namespaces()) {
            PreviewLibrary.Shelf shelf = PreviewLibrary.Shelf.namespace(namespace);
            for (String name : library.presets(shelf)) {
                List<String> parts = library.preset(shelf, name);
                if (parts == null || !library.builtIn(shelf, name)) {
                    continue;
                }
                int[] colours = firstColours(parts);
                Component label = colours == null ? Component.text(name)
                        : swatch(new int[] {colours[colours.length - 1]}).append(Component.text(" " + name));
                buttons.add(button(label, namespace + "@" + name, QUICK, (view, who) -> {
                    if (Boolean.TRUE.equals(view.getBoolean("change"))) {
                        drafts.put(uuid, draft(PreviewLibrary.Shelf.of(uuid), null, parts));
                        editor(who, "A copy of " + name + "; save it to keep it.");
                    } else {
                        show(who, parts);
                    }
                }));
            }
        }
        player.showDialog(dialog("Quick forest", lines(note, "Grows on gold, diamond, emerald, redstone and netherite blocks around you."),
                List.of(DialogInput.bool("change", Component.text("Change it first: open a copy as my own")).build()),
                buttons, button("Back", null, who -> main(who, null)), 4));
    }

    /** The colours of a preset's first painted tree, for showing the preset at a glance. */
    private static int @Nullable [] firstColours(List<String> parts) {
        Draft read = draft(PreviewLibrary.Shelf.namespace("-"), null, parts);
        return read.markers.stream().flatMap(marker -> marker.trees.stream()).map(tree -> tree.colours)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** A shelf's presets: the player's own, or a namespace's. */
    private void presets(Player player, PreviewLibrary.Shelf shelf, @Nullable String note) {
        List<ActionButton> buttons = new ArrayList<>();
        for (String name : library.presets(shelf)) {
            buttons.add(button(name, written(Objects.requireNonNull(library.preset(shelf, name))),
                    who -> preset(who, shelf, name, null)));
        }
        if (canChange(player, shelf)) {
            buttons.add(button("New preset", "Put one together and keep it here", who -> {
                drafts.put(who.getUniqueId(), new Draft(shelf, null));
                editor(who, "Save it to keep it in " + where(shelf) + ".");
            }));
        }
        String title = shelf.namespace() != null ? "Namespace " + shelf.namespace() : "My presets";
        player.showDialog(dialog(title, lines(note, buttons.isEmpty() ? "Nothing here yet." : null), List.of(),
                buttons, button("Back", null, who -> {
                    if (shelf.namespace() != null) {
                        namespaces(who, null);
                    } else {
                        main(who, null);
                    }
                })));
    }

    private void namespaces(Player player, @Nullable String note) {
        List<ActionButton> buttons = new ArrayList<>();
        for (String namespace : library.namespaces()) {
            buttons.add(button(namespace, library.presets(PreviewLibrary.Shelf.namespace(namespace)).size() + " presets",
                    who -> presets(who, PreviewLibrary.Shelf.namespace(namespace), null)));
        }
        if (player.hasPermission(NAMESPACES)) {
            buttons.add(button("New namespace", "Its first preset starts it", who -> {
                drafts.put(who.getUniqueId(), new Draft(PreviewLibrary.Shelf.of(who.getUniqueId()), null));
                editor(who, "Save it as namespace@name to start the namespace with it.");
            }));
        }
        player.showDialog(dialog("Namespaces", lines(note, library.namespaces().isEmpty() ? "No namespaces yet."
                : "Presets everyone may use; " + (player.hasPermission(NAMESPACES) ? "you can change them."
                        : "mods and admins change them.")), List.of(), buttons,
                button("Back", null, who -> main(who, null))));
    }

    /** One preset, to show, change, copy or delete. */
    private void preset(Player player, PreviewLibrary.Shelf shelf, String name, @Nullable String note) {
        List<String> parts = library.preset(shelf, name);
        if (parts == null) {
            presets(player, shelf, "There is no preset " + name + " any more.");
            return;
        }
        Draft read = draft(shelf, name, parts);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Show", "Grows it around you", who -> show(who, parts)));
        if (canChange(player, shelf)) {
            buttons.add(button("Change", null, who -> {
                drafts.put(who.getUniqueId(), draft(shelf, name, parts));
                editor(who, null);
            }));
            boolean brought = library.builtIn(shelf, name);
            buttons.add(button(brought ? "Reset" : "Delete", brought ? "Back to how TreeArchive brings it" : null,
                    who -> confirm(who, (brought ? "Reset the preset " : "Delete the preset ") + name + "?", () -> {
                        if (!canChange(who, shelf)) {
                            presets(who, shelf, "Only mods and admins change namespaces.");
                            return;
                        }
                        library.set(shelf, who, name, List.of());
                        presets(who, shelf, (brought ? "Reset " : "Deleted ") + name + ".");
                    }, () -> preset(who, shelf, name, null))));
        }
        if (!shelf.equals(PreviewLibrary.Shelf.of(player.getUniqueId()))) {
            buttons.add(button("Copy to mine", "A draft of your own to change and keep", who -> {
                Draft copy = draft(PreviewLibrary.Shelf.of(who.getUniqueId()), null, parts);
                drafts.put(who.getUniqueId(), copy);
                editor(who, "A copy of " + name + "; save it to keep it.");
            }));
        }
        List<DialogBody> body = new ArrayList<>(lines(note, null));
        body.addAll(describe(read));
        player.showDialog(dialog(name + " in " + where(shelf), body, List.of(), buttons,
                button("Back", null, who -> presets(who, shelf, null))));
    }

    /** The draft: its marker blocks, and what the whole preview does. */
    private void editor(Player player, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            main(player, null);
            return;
        }
        int cap = cap(player);
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("limit", Component.text("Trees at most"), 1, cap).step(1f)
                        .initial((float) Math.min(cap, draft.limit > 0 ? draft.limit : maxTrees())).width(WIDE).build(),
                DialogInput.bool("continuous", Component.text("Keep growing on chunks loaded later"))
                        .initial(draft.continuous).build(),
                DialogInput.bool("bypass", Component.text("Grow trees even where they break blocks"))
                        .initial(draft.bypassTerrain).build());
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < draft.markers.size(); i++) {
            int index = i;
            Marker marker = draft.markers.get(i);
            buttons.add(button(marker.blocks, "Its trees", (view, who) -> {
                keep(view, draft, who);
                marker(who, index, null);
            }));
        }
        buttons.add(button("Add a marker block", "A block whose every copy around you becomes a tree", (view, who) -> {
            keep(view, draft, who);
            if (draft.markers.size() >= MAX_MARKERS) {
                editor(who, MAX_MARKERS + " marker blocks are the most.");
                return;
            }
            draft.markers.add(new Marker());
            markerBlock(who, draft.markers.size() - 1, null);
        }));
        buttons.add(button("Show", "Grows the preview around you", (view, who) -> {
            keep(view, draft, who);
            if (draft.markers.isEmpty()) {
                editor(who, "Add a marker block first.");
            } else {
                show(who, parts(draft));
            }
        }));
        buttons.add(button("Save", draft.name == null ? "Asks for a name" : "Keeps it as " + draft.name
                + " in " + where(draft.shelf), (view, who) -> {
                    keep(view, draft, who);
                    if (draft.name == null) {
                        saveAs(who, null);
                    } else {
                        save(who, draft, draft.shelf, draft.name);
                    }
                }));
        buttons.add(button("Save as ...", "Under another name, or in a namespace", (view, who) -> {
            keep(view, draft, who);
            saveAs(who, null);
        }));
        buttons.add(button("Throw away", "Forgets this draft", who -> {
            drafts.remove(who.getUniqueId());
            main(who, "Draft thrown away.");
        }));
        List<DialogBody> body = new ArrayList<>(lines(note, draft.name == null ? "A new preview."
                : draft.name + " in " + where(draft.shelf) + "."));
        body.addAll(describe(draft));
        player.showDialog(dialog("Preview", body, inputs, buttons, button("Back", null, (view, who) -> {
            keep(view, draft, who);
            main(who, null);
        })));
    }

    /** One marker block of the draft, and its trees. */
    private void marker(Player player, int index, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || index >= draft.markers.size()) {
            editor(player, null);
            return;
        }
        Marker marker = draft.markers.get(index);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Change the block", "Which block turns into these trees", who -> markerBlock(who, index, null)));
        for (int i = 0; i < marker.trees.size(); i++) {
            int tree = i;
            buttons.add(button(marker.trees.get(i).word.isEmpty() ? "(no tree yet)" : marker.trees.get(i).word, null,
                    who -> tree(who, index, tree, Tab.TREE, null)));
        }
        buttons.add(button("Add a tree", "Without any, the block grows every archived tree", who -> {
            if (marker.trees.size() >= MAX_TREES) {
                marker(who, index, MAX_TREES + " trees are the most for one block.");
                return;
            }
            marker.trees.add(new Tree());
            treeType(who, index, marker.trees.size() - 1);
        }));
        buttons.add(button("Remove the block", null, who -> {
            draft.markers.remove(marker);
            editor(who, null);
        }));
        List<DialogBody> body = new ArrayList<>(lines(note, null));
        body.add(blockIcon(marker.blocks));
        if (marker.trees.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("No trees chosen: every archived tree grows on it."), WIDE));
        }
        for (Tree tree : marker.trees) {
            if (!tree.word.isEmpty()) {
                body.add(DialogBody.plainMessage(describe(tree, marker.trees), WIDE));
            }
        }
        player.showDialog(dialog("Marker block", body, List.of(), buttons, button("Done", null, who -> editor(who, null))));
    }

    /** The block as an icon with its name beside it; several by shares show the first. */
    private static DialogBody blockIcon(String blocks) {
        Component written = Component.text(blocks);
        List<TreeSelection.Part> words = TreeSelection.parse(blocks).parts();
        Material material = words.isEmpty() ? null : Material.matchMaterial(words.getFirst().word());
        return material == null || material.isAir() || !material.isItem() ? DialogBody.plainMessage(written, WIDE)
                : DialogBody.item(ItemStack.of(material)).description(DialogBody.plainMessage(written, 150)).build();
    }

    /** Which block a marker is: the one in hand, one the built-in presets use, or written out. */
    private void markerBlock(Player player, int index, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || index >= draft.markers.size()) {
            editor(player, null);
            return;
        }
        Marker marker = draft.markers.get(index);
        List<ActionButton> buttons = new ArrayList<>();
        Material held = player.getInventory().getItemInMainHand().getType();
        String inHand = held.isBlock() && !held.isAir() ? held.getKey().getKey() : null;
        buttons.add(button(inHand == null ? "Block in my hand" : "In hand: " + inHand, "Every copy of the block you hold around you", who -> {
            Material now = who.getInventory().getItemInMainHand().getType();
            if (!now.isBlock() || now.isAir()) {
                markerBlock(who, index, "Hold a block to use it.");
                return;
            }
            marker.blocks = now.getKey().getKey();
            marker(who, index, null);
        }));
        MARKERS.forEach((block, what) -> buttons.add(button(block, what, who -> {
            marker.blocks = block;
            marker(who, index, null);
        })));
        buttons.add(button("Several, by shares ...", "Written out, like 70%gold_block,30%diamond_block", who -> markerText(who, index, null)));
        player.showDialog(dialog("Which block?", lines(note, null), List.of(), buttons, button("Back", null, who -> {
            // A block never chosen is no block.
            if (marker.blocks.isEmpty()) {
                draft.markers.remove(marker);
                editor(who, null);
            } else {
                marker(who, index, null);
            }
        })));
    }

    private void markerText(Player player, int index, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || index >= draft.markers.size()) {
            editor(player, null);
            return;
        }
        Marker marker = draft.markers.get(index);
        player.showDialog(dialog("Which blocks?", lines(note, null),
                List.of(DialogInput.text("blocks", Component.text("Blocks, like 70%gold_block,30%diamond_block"))
                        .initial(marker.blocks).maxLength(200).width(WIDE).build()),
                List.of(button("Use them", null, (view, who) -> {
                    String blocks = blocks(view);
                    try {
                        PreviewSpec.parse(blocks);
                        marker.blocks = blocks;
                        marker(who, index, null);
                    } catch (IllegalArgumentException e) {
                        markerText(who, index, "Cannot read that: " + e.getMessage() + ".");
                    }
                })), button("Back", null, who -> markerBlock(who, index, null))));
    }

    /** What a tree is: one of the types with trees in the archive, a click each, or words. */
    private void treeType(Player player, int markerIndex, int index) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || markerIndex >= draft.markers.size()
                || index >= draft.markers.get(markerIndex).trees.size()) {
            editor(player, null);
            return;
        }
        Marker marker = draft.markers.get(markerIndex);
        Tree tree = marker.trees.get(index);
        List<ActionButton> buttons = new ArrayList<>();
        for (String type : archive.types()) {
            long trees = archive.entries().stream().filter(entry -> entry.type().equals(type)).count();
            if (trees > 0) {
                buttons.add(button(type.equals(tree.word) ? Component.text(type, NamedTextColor.GOLD) : Component.text(type),
                        trees + " archived trees", TYPE, who -> {
                            tree.word = type;
                            tree(who, markerIndex, index, Tab.TREE, null);
                        }));
            }
        }
        buttons.add(button(Component.text("Words ..."), "A size, a creator or a name", TYPE, who -> treeWords(who, markerIndex, index, null)));
        player.showDialog(dialog("Which tree?", List.of(), List.of(), buttons, button("Back", null, who -> {
            // A tree never chosen is no tree.
            if (tree.word.isEmpty()) {
                marker.trees.remove(tree);
                marker(who, markerIndex, null);
            } else {
                tree(who, markerIndex, index, Tab.TREE, null);
            }
        }), 3));
    }

    private void treeWords(Player player, int markerIndex, int index, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || markerIndex >= draft.markers.size()
                || index >= draft.markers.get(markerIndex).trees.size()) {
            editor(player, null);
            return;
        }
        Tree tree = draft.markers.get(markerIndex).trees.get(index);
        player.showDialog(dialog("Which tree?", lines(note, null),
                List.of(DialogInput.text("word", Component.text("A type, size, creator or a piece of a name"))
                        .initial(tree.word).maxLength(100).width(WIDE).build()),
                List.of(button("Use it", null, (view, who) -> {
                    String written = Objects.requireNonNullElse(view.getText("word"), "").toLowerCase(Locale.ROOT)
                            .replaceAll("[\\s,/:*&%]", "");
                    String word = written.substring(0, Math.min(written.length(), 100));
                    if (archive.entries().stream().noneMatch(entry -> TreeArchive.matches(entry, word)) || word.isEmpty()) {
                        treeWords(who, markerIndex, index, "No archived tree matches '" + word + "'.");
                        return;
                    }
                    tree.word = word;
                    tree(who, markerIndex, index, Tab.TREE, null);
                })), button("Back", null, who -> treeType(who, markerIndex, index))));
    }

    /** One tree of a marker block, a tab at a time; every click keeps what the tab says. */
    private void tree(Player player, int markerIndex, int index, Tab tab, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || markerIndex >= draft.markers.size()
                || index >= draft.markers.get(markerIndex).trees.size()) {
            editor(player, null);
            return;
        }
        Marker marker = draft.markers.get(markerIndex);
        Tree tree = marker.trees.get(index);

        List<ActionButton> buttons = new ArrayList<>();
        for (Tab each : Tab.values()) {
            buttons.add(button(each == tab ? Component.text("> " + each.label + " <", NamedTextColor.GOLD)
                    : Component.text(each.label), each.tooltip, TAB, (view, who) -> {
                        keep(view, tree, tab);
                        tree(who, markerIndex, index, each, null);
                    }));
        }
        buttons.add(button(Component.text("Try it"), "Grows one such tree where you look, in place of any preview", TAB, (view, who) -> {
            keep(view, tree, tab);
            tryOut(who, markerIndex, index, tab);
        }));
        if (tab == Tab.TREE) {
            buttons.add(button(Component.text(tree.word.isEmpty() ? "Which tree?" : tree.word), "Another type, or words", TAB, (view, who) -> {
                keep(view, tree, tab);
                treeType(who, markerIndex, index);
            }));
            buttons.add(button(Component.text("Remove"), "Takes this tree off the block", TAB, who -> {
                marker.trees.remove(tree);
                marker(who, markerIndex, null);
            }));
            buttons.add(button(Component.text("Another one"), "Tries another archived tree of this kind, or another turn", TAB, (view, who) -> {
                keep(view, tree, tab);
                tree.seed = ThreadLocalRandom.current().nextLong();
                tryOut(who, markerIndex, index, tab);
            }));
        }
        if (tab == Tab.EXTRAS) {
            EXTRAS.forEach((group, extras) -> {
                long on = extras.stream().filter(extra -> tree.extras.containsKey(extra.modifier())).count();
                buttons.add(button(Component.text(group + (on > 0 ? " (" + on + ")" : "")), extras.stream().map(Extra::label)
                        .collect(Collectors.joining(", ")), TAB, who -> extras(who, markerIndex, index, group)));
            });
        }
        if (tab == Tab.LEAVES) {
            Consumer<Player> back = who -> tree(who, markerIndex, index, Tab.LEAVES, null);
            buttons.add(paint("Leaf colour", tree.colours, tree, new ColourPicker.Target("Leaf colour", () -> tree.colours,
                    stops -> {
                        tree.colours = stops;
                        // A light colour only shows on the lighter texture; the box stays free to change.
                        tree.bright |= stops != null && ColourPicker.light(stops);
                    }, back, true, true, stops -> ColourPicker.example(stops, tree.out,
                    tree.bright || stops != null && ColourPicker.light(stops), tree.secondary, tree.blossom, tree.bloom))));
        }
        // Without a leaf colour there is nothing for these to go with.
        if (tab == Tab.LEAVES && tree.colours != null) {
            Consumer<Player> back = who -> tree(who, markerIndex, index, Tab.LEAVES, null);
            buttons.add(paint("Secondary", single(tree.secondary), tree, new ColourPicker.Target("Secondary colour",
                    () -> single(tree.secondary), stops -> tree.secondary = stops == null ? 0 : stops[0] | LeafColours.PRESENT,
                    back, false, true, stops -> ColourPicker.example(tree.colours, tree.out, tree.bright,
                    stops == null ? 0 : stops[0] | LeafColours.PRESENT, tree.blossom, tree.bloom))));
            buttons.add(paint("Blossoms", single(tree.blossom), tree, new ColourPicker.Target("Blossom colour",
                    () -> single(tree.blossom), stops -> tree.blossom = stops == null ? 0 : stops[0] | LeafColours.PRESENT,
                    back, false, true, stops -> ColourPicker.example(tree.colours, tree.out, tree.bright, tree.secondary,
                    stops == null ? 0 : stops[0] | LeafColours.PRESENT, tree.bloom))));
        }
        List<DialogBody> body = new ArrayList<>(lines(note, null));
        body.add(DialogBody.plainMessage(ColourPicker.example(tree.colours, tree.out, tree.bright, tree.secondary,
                tree.blossom, tree.bloom), WIDE));
        player.showDialog(dialog((tree.word.isEmpty() ? "New tree" : shortened(tree.word)) + " on " + firstBlock(marker.blocks),
                body, inputs(tree, tab, marker.trees.size()), buttons,
                button("Done", null, (view, who) -> {
                    keep(view, tree, tab);
                    if (tree.word.isEmpty()) {
                        treeType(who, markerIndex, index);
                    } else {
                        marker(who, markerIndex, null);
                    }
                }), 4));
    }

    /** One tree as it stands, on the block the player looks at or else stands over, until they sneak. */
    private void tryOut(Player player, int markerIndex, int index, Tab tab) {
        Tree tree = drafts.get(player.getUniqueId()).markers.get(markerIndex).trees.get(index);
        if (tree.word.isEmpty()) {
            tree(player, markerIndex, index, tab, "Choose a tree first.");
            return;
        }
        Block target = player.getTargetBlockExact(TRY_REACH);
        Block at = target != null ? target : player.getWorld().getHighestBlockAt(player.getLocation());
        String part = at.getType().getKey().getKey() + ":" + written(tree, false);
        if (!fits(part)) {
            tree(player, markerIndex, index, tab, "There is nothing to grow it on here.");
            return;
        }
        player.closeDialog();
        if (preview.tryOut(player, PreviewSpec.parse(part), at, tree.seed, who -> tree(who, markerIndex, index, tab, null))) {
            player.sendMessage(Component.text("Sneak on the ground or press ", NamedTextColor.GREEN)
                    .append(Component.keybind("key.swapOffhand")).append(Component.text(" to go back to the menu.")));
        } else {
            tree(player, markerIndex, index, tab, "No archived tree like that could grow.");
        }
    }

    /** One group of what can hang on a tree: a slider each, 0 for none. */
    private void extras(Player player, int markerIndex, int index, String group) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || markerIndex >= draft.markers.size()
                || index >= draft.markers.get(markerIndex).trees.size()) {
            editor(player, null);
            return;
        }
        Tree tree = draft.markers.get(markerIndex).trees.get(index);
        List<Extra> extras = EXTRAS.get(group);
        List<DialogInput> inputs = new ArrayList<>();
        for (Extra extra : extras) {
            inputs.add(DialogInput.numberRange(extra.modifier(), Component.text(extra.label() + ", %"), 0, extra.top()).step(1f)
                    .initial((float) Math.min(tree.extras.getOrDefault(extra.modifier(), 0), extra.top())).width(WIDE).build());
        }
        player.showDialog(dialog(group, lines(null, group.equals("Hanging")
                ? "Share of the crown's edge, each on a twig; 0 for none." : "Share of the leaves; 0 for none."), inputs, List.of(), button("Done", null, (view, who) -> {
            for (Extra extra : extras) {
                Float value = view.getFloat(extra.modifier());
                if (value != null && !value.isNaN()) {
                    // The client's word only: within the slider, whatever it sent.
                    int strength = Math.clamp(Math.round(value), 0, extra.top());
                    if (strength == 0) {
                        tree.extras.remove(extra.modifier());
                    } else {
                        tree.extras.put(extra.modifier(), strength);
                    }
                }
            }
            tree(who, markerIndex, index, Tab.EXTRAS, null);
        })));
    }

    /** A button naming a colour of the tree and showing it, keeping the tab's inputs on the way to the picker. */
    private ActionButton paint(String label, int @Nullable [] colours, Tree tree, ColourPicker.Target target) {
        Component shown = colours == null ? Component.text(label)
                : Component.text(label + " ").append(swatch(colours));
        return button(shown, "Pick it", TAB, (view, who) -> {
            keep(view, tree, Tab.LEAVES);
            picker.open(who, target);
        });
    }

    /** Words as long as a title has room for. */
    private static String shortened(String words) {
        return words.length() <= 24 ? words : words.substring(0, 24) + "...";
    }

    /** The first of a marker's blocks, for a title of one line: others follow as dots. */
    private static String firstBlock(String blocks) {
        int comma = blocks.indexOf(',');
        String first = (comma < 0 ? blocks : blocks.substring(0, comma)).replaceFirst("^[0-9.]+%", "");
        return shortened(first) + (comma < 0 ? "" : " ...");
    }

    private static int @Nullable [] single(int colour) {
        return colour == 0 ? null : new int[] {colour & 0xFFFFFF};
    }

    /** What a tab asks: only what means something for the tree so far. */
    private List<DialogInput> inputs(Tree tree, Tab tab, int trees) {
        List<DialogInput> inputs = new ArrayList<>();
        switch (tab) {
            case TREE -> {
                if (trees > 1) {
                    inputs.add(DialogInput.numberRange("share", Component.text("Share among the block's trees"), 1, 100)
                            .step(1f).initial((float) tree.share).width(WIDE).build());
                }
            }
            case LEAVES -> {
                if (tree.colours != null) {
                    inputs.add(DialogInput.bool("bright", Component.text("Bright - on a lighter leaf texture")).initial(tree.bright).build());
                }
                if (tree.colours != null && tree.colours.length > 1) {
                    inputs.add(DialogInput.bool("out", Component.text("Fade from the middle outwards, not upwards")).initial(tree.out).build());
                }
                if (tree.colours != null && tree.blossom != 0) {
                    inputs.add(DialogInput.numberRange("bloom", Component.text("Leaves in blossom, %"), 1, 100)
                            .step(1f).initial((float) tree.bloom).width(WIDE).build());
                }
            }
            case EXTRAS -> {
                // Asked on their own screens, by group.
            }
        }
        return inputs;
    }

    /**
     * Reads a tab back, as the client's word only: anything else would go into the preview's
     * command line. What the tab did not ask stays as it is.
     */
    private static void keep(DialogResponseView view, Tree tree, Tab tab) {
        switch (tab) {
            case TREE -> tree.share = percent(view.getFloat("share"), tree.share);
            case LEAVES -> {
                tree.bright = Objects.requireNonNullElse(view.getBoolean("bright"), tree.bright);
                tree.out = Objects.requireNonNullElse(view.getBoolean("out"), tree.out);
                tree.bloom = percent(view.getFloat("bloom"), tree.bloom);
            }
            case EXTRAS -> {
                // Read on their own screens.
            }
        }
    }

    private void saveAs(Player player, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            main(player, null);
            return;
        }
        String initial = draft.name == null ? "" : (draft.shelf.namespace() != null ? draft.shelf.namespace() + "@" : "")
                + draft.name;
        String hint = player.hasPermission(NAMESPACES) ? "Name - namespace@name puts it into a namespace" : "Name";
        player.showDialog(dialog("Save the preview", lines(note, "Letters, digits, _ and -."),
                List.of(DialogInput.text("name", Component.text(hint)).initial(initial).maxLength(60).width(WIDE).build()),
                List.of(button("Save", null, (view, who) -> {
                    String written = Objects.requireNonNullElse(view.getText("name"), "").trim().toLowerCase(Locale.ROOT);
                    int at = written.indexOf('@');
                    String name = written.substring(at + 1);
                    PreviewLibrary.Shelf shelf = at < 0 ? PreviewLibrary.Shelf.of(who.getUniqueId())
                            : PreviewLibrary.Shelf.namespace(written.substring(0, at));
                    if (!name.matches(NAME) || ArchiveCommand.PRESET_WORDS.contains(name)
                            || shelf.namespace() != null && !shelf.namespace().matches(NAME)) {
                        saveAs(who, "That name will not do.");
                    } else if (!canChange(who, shelf)) {
                        saveAs(who, "Only mods and admins change namespaces.");
                    } else if (library.preset(shelf, name) == null && library.presets(shelf).size() >= MAX_KEPT) {
                        saveAs(who, MAX_KEPT + " presets are the most in one place; delete one first.");
                    } else {
                        save(who, draft, shelf, name);
                    }
                })), button("Back", null, who -> editor(who, null))));
    }

    private void save(Player player, Draft draft, PreviewLibrary.Shelf shelf, String name) {
        if (!canChange(player, shelf)) {
            editor(player, "Only mods and admins change namespaces.");
            return;
        }
        library.set(shelf, player, name, parts(draft));
        draft.shelf = shelf;
        draft.name = name;
        editor(player, "Saved as " + name + " in " + where(shelf) + ".");
    }

    void confirm(Player player, String question, Runnable yes, Runnable no) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(question)).afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE).build())
                .type(DialogType.confirmation(button("Yes", null, who -> yes.run()), button("No", null, who -> no.run())))));
    }

    /**
     * Shows a preview, its parts checked once more on their way to /ta preview's command
     * line: a flag as presets have them, or a block's part that reads as one and holds no
     * space - nothing that could slip in a --seed, an --at or a word of /ta preview's own.
     */
    private void show(Player player, List<String> parts) {
        player.closeDialog();
        for (String part : parts) {
            if (!fits(part)) {
                player.sendMessage(Component.text("That preview cannot be shown: " + part, NamedTextColor.RED));
                return;
            }
        }
        show.accept(player, parts);
    }

    /** Whether a part may go to /ta preview: see {@link #show(Player, List)}. */
    static boolean fits(String part) {
        try {
            return PreviewLibrary.isFlag(part) ? part.matches("--continuous|--bypassterrain|--limit [1-9][0-9]{0,5}")
                    : !part.contains(" ") && !part.startsWith("#") && !PreviewSpec.parse(part).parts().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- drafts and presets ---------------------------------------------------------

    /** Reads the draft's flags from the inputs of its screen, so leaving it keeps them. */
    private void keep(DialogResponseView view, Draft draft, Player player) {
        int limit = Math.round(Objects.requireNonNullElse(view.getFloat("limit"), (float) maxTrees()));
        draft.limit = limit == maxTrees() ? 0 : Math.min(limit, cap(player));
        draft.continuous = Boolean.TRUE.equals(view.getBoolean("continuous"));
        draft.bypassTerrain = Boolean.TRUE.equals(view.getBoolean("bypass"));
    }

    private static String blocks(DialogResponseView view) {
        String blocks = Objects.requireNonNullElse(view.getText("blocks"), "").toLowerCase(Locale.ROOT)
                .replaceAll("[\\s:/]", "");
        return blocks.substring(0, Math.min(blocks.length(), 200));
    }

    /** A slider's value as a whole percent, 1 to 100 whatever the client sent. */
    private static int percent(@Nullable Float value, int fallback) {
        return value == null || value.isNaN() ? fallback : Math.clamp(Math.round(value), 1, 100);
    }

    /** A preset's parts as a draft. */
    static Draft draft(PreviewLibrary.Shelf shelf, @Nullable String name, List<String> parts) {
        Draft draft = new Draft(shelf, name);
        for (String part : parts) {
            if (part.equals("--continuous")) {
                draft.continuous = true;
            } else if (part.equals("--bypassterrain")) {
                draft.bypassTerrain = true;
            } else if (part.startsWith("--limit ")) {
                // /ta preview preset add takes a limit of any length; one too long is none.
                try {
                    draft.limit = Integer.parseInt(part.substring("--limit ".length()));
                } catch (NumberFormatException e) {
                    draft.limit = 0;
                }
            } else if (!PreviewLibrary.isFlag(part)) {
                int colon = part.indexOf(':');
                Marker marker = new Marker();
                marker.blocks = colon < 0 ? part : part.substring(0, colon);
                if (colon >= 0) {
                    for (TreeSelection.Part written : TreeSelection.parse(part.substring(colon + 1)).parts()) {
                        Tree tree = new Tree();
                        tree.word = written.word();
                        tree.share = (int) Math.clamp(Math.round(written.weight()), 1, 100);
                        tree.colours = written.colours();
                        tree.bright = written.modifiers().contains("bright");
                        tree.out = written.modifiers().contains("out");
                        tree.secondary = TreeSelection.colour(written.modifiers(), "second");
                        tree.blossom = TreeSelection.colour(written.modifiers(), "blossom");
                        String bloom = TreeSelection.find(written.modifiers(), "bloom");
                        tree.bloom = bloom == null ? TreeSelection.BLOOM : TreeSelection.strength(bloom, TreeSelection.BLOOM);
                        EXTRAS.values().stream().flatMap(List::stream).forEach(extra -> {
                            String modifier = TreeSelection.find(written.modifiers(), extra.modifier());
                            if (modifier != null) {
                                tree.extras.put(extra.modifier(), TreeSelection.strength(modifier, extra.plain()));
                            }
                        });
                        marker.trees.add(tree);
                    }
                }
                draft.markers.add(marker);
            }
        }
        return draft;
    }

    /** A draft as a preset keeps it: one part per marker block, and the flags. */
    static List<String> parts(Draft draft) {
        List<String> parts = new ArrayList<>();
        for (Marker marker : draft.markers) {
            if (marker.blocks.isEmpty()) {
                continue;
            }
            // A tree added but not chosen yet is nothing to keep.
            List<Tree> trees = marker.trees.stream().filter(tree -> !tree.word.isEmpty()).toList();
            boolean shares = trees.stream().mapToInt(tree -> tree.share).distinct().count() > 1;
            parts.add(marker.blocks + (trees.isEmpty() ? "" : ":" + trees.stream()
                    .map(tree -> written(tree, shares)).collect(Collectors.joining(","))));
        }
        if (draft.limit > 0) {
            parts.add("--limit " + draft.limit);
        }
        if (draft.continuous) {
            parts.add("--continuous");
        }
        if (draft.bypassTerrain) {
            parts.add("--bypassterrain");
        }
        return parts;
    }

    /** A tree the way /ta preview reads it: 70%maple*#c0392b>#f5d130&bright&mistle=30. */
    private static String written(Tree tree, boolean shares) {
        StringBuilder written = new StringBuilder(shares ? tree.share + "%" : "").append(tree.word);
        if (tree.colours != null) {
            written.append('*').append(hex(tree.colours)).append(tree.bright ? "&bright" : "").append(tree.out ? "&out" : "")
                    .append(tree.secondary == 0 ? "" : "&second=" + hex(new int[] {tree.secondary}))
                    .append(tree.blossom == 0 ? "" : "&blossom=" + hex(new int[] {tree.blossom}))
                    .append(tree.blossom == 0 || tree.bloom == TreeSelection.BLOOM ? "" : "&bloom=" + tree.bloom);
        }
        EXTRAS.values().stream().flatMap(List::stream).forEach(extra -> {
            Integer strength = tree.extras.get(extra.modifier());
            if (strength != null) {
                written.append('&').append(extra.modifier()).append(strength == extra.plain() ? "" : "=" + strength);
            }
        });
        return written.toString();
    }

    /** A preset's parts in a line, for a tooltip. */
    private static String written(List<String> parts) {
        return String.join(" ", parts);
    }

    /** A line per marker block, with its trees, and one for the flags. */
    private List<DialogBody> describe(Draft draft) {
        List<DialogBody> lines = new ArrayList<>();
        for (Marker marker : draft.markers) {
            TextComponent.Builder line = Component.text().append(Component.text(marker.blocks, NamedTextColor.GOLD));
            if (marker.trees.isEmpty()) {
                line.append(Component.text(": any tree"));
            }
            for (Tree tree : marker.trees) {
                line.append(Component.text(marker.trees.indexOf(tree) == 0 ? ": " : ", ")).append(describe(tree, marker.trees));
            }
            lines.add(DialogBody.plainMessage(line.build(), WIDE));
        }
        if (draft.markers.isEmpty()) {
            lines.add(DialogBody.plainMessage(Component.text("No marker blocks yet."), WIDE));
        }
        lines.add(DialogBody.plainMessage(Component.text((draft.limit > 0 ? draft.limit : maxTrees()) + " trees at most"
                + (draft.continuous ? ", growing on" : "") + (draft.bypassTerrain ? ", through the terrain" : ""),
                NamedTextColor.GRAY), WIDE));
        return lines;
    }

    private static Component describe(Tree tree, List<Tree> all) {
        List<Tree> chosen = all.stream().filter(each -> !each.word.isEmpty()).toList();
        int total = chosen.stream().mapToInt(each -> each.share).sum();
        TextComponent.Builder line = Component.text();
        if (chosen.size() > 1) {
            line.append(Component.text(Math.round(100.0 * tree.share / Math.max(1, total)) + "% "));
        }
        line.append(Component.text(tree.word));
        if (tree.colours != null) {
            line.append(Component.text(" ")).append(swatch(tree.colours));
        }
        List<String> also = new ArrayList<>();
        if (tree.colours != null && tree.bright) {
            also.add("bright");
        }
        if (tree.colours != null && tree.out) {
            also.add("fading out");
        }
        EXTRAS.values().stream().flatMap(List::stream).forEach(extra -> {
            Integer strength = tree.extras.get(extra.modifier());
            if (strength != null) {
                also.add(extra.modifier() + " " + strength + "%");
            }
        });
        if (!also.isEmpty()) {
            line.append(Component.text(" (" + String.join(", ", also) + ")", NamedTextColor.GRAY));
        }
        if (tree.colours != null && tree.secondary != 0) {
            line.append(Component.text(" also ", NamedTextColor.GRAY)).append(swatch(new int[] {tree.secondary}));
        }
        if (tree.colours != null && tree.blossom != 0) {
            line.append(Component.text(" blossoms ", NamedTextColor.GRAY)).append(swatch(new int[] {tree.blossom}))
                    .append(Component.text(" " + tree.bloom + "%", NamedTextColor.GRAY));
        }
        return line.build();
    }

    // ---- helpers ----------------------------------------------------------------------

    private boolean canChange(Player player, PreviewLibrary.Shelf shelf) {
        return shelf.player() != null ? shelf.player().equals(player.getUniqueId()) : player.hasPermission(NAMESPACES);
    }

    private String where(PreviewLibrary.Shelf shelf) {
        return shelf.namespace() != null ? "namespace " + shelf.namespace()
                : "the presets of " + library.ownerName(Objects.requireNonNull(shelf.player()));
    }

    private int maxTrees() {
        return Math.max(1, plugin.getConfig().getInt("preview.max-trees", 500));
    }

    /** The most trees this player may ask for: the server's limit, or more for admins. */
    private int cap(Player player) {
        return player.hasPermission(TreeBrush.PERMISSION) ? Math.max(ADMIN_LIMIT, maxTrees()) : maxTrees();
    }

    static String hex(int[] colours) {
        return Arrays.stream(colours).mapToObj(colour -> String.format("#%06x", colour & 0xFFFFFF))
                .collect(Collectors.joining(">"));
    }

    /** A square per colour, in that colour. */
    static Component swatch(int[] colours) {
        TextComponent.Builder swatch = Component.text();
        for (int colour : colours) {
            swatch.append(Component.text("\u2588", TextColor.color(colour & 0xFFFFFF)));
        }
        return swatch.build();
    }

    /** A note in red over the screen's own line, either of them left out when null. */
    static List<DialogBody> lines(@Nullable String note, @Nullable String line) {
        List<DialogBody> lines = new ArrayList<>();
        if (note != null) {
            lines.add(DialogBody.plainMessage(Component.text(note, NamedTextColor.YELLOW), WIDE));
        }
        if (line != null) {
            lines.add(DialogBody.plainMessage(Component.text(line), WIDE));
        }
        return lines;
    }

    private static Dialog dialog(String title, List<DialogBody> body, List<DialogInput> inputs,
                                 List<ActionButton> buttons, @Nullable ActionButton exit) {
        return dialog(title, body, inputs, buttons, exit, 2);
    }

    static Dialog dialog(String title, List<DialogBody> body, List<DialogInput> inputs,
                         List<ActionButton> buttons, @Nullable ActionButton exit, int columns) {
        DialogType type = buttons.isEmpty() && exit != null
                ? DialogType.notice(exit)
                : exit == null ? DialogType.multiAction(buttons).columns(columns).build()
                        : DialogType.multiAction(buttons).columns(columns).exitAction(exit).build();
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).inputs(inputs)
                        // The next screen comes from here, or closeDialog in show(): the mouse stays put.
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE).build())
                .type(type));
    }

    ActionButton button(String label, @Nullable String tooltip, Consumer<Player> action) {
        return button(label, tooltip, (view, player) -> action.accept(player));
    }

    ActionButton button(String label, @Nullable String tooltip, BiConsumer<DialogResponseView, Player> action) {
        return button(Component.text(label), tooltip, 150, action);
    }

    ActionButton button(Component label, @Nullable String tooltip, int width, Consumer<Player> action) {
        return button(label, tooltip, width, (view, player) -> action.accept(player));
    }

    ActionButton button(Component label, @Nullable String tooltip, int width, BiConsumer<DialogResponseView, Player> action) {
        ActionButton.Builder button = ActionButton.builder(label).width(width).action(action(action));
        if (tooltip != null) {
            button.tooltip(Component.text(tooltip));
        }
        return button.build();
    }

    private DialogAction action(Consumer<Player> action) {
        return action((view, player) -> action.accept(player));
    }

    /** Runs a click on the server thread, for the player who clicked. */
    private DialogAction action(BiConsumer<DialogResponseView, Player> action) {
        return DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player player) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // Online first: a click still queued after quitting must not leave an entry behind.
                    if (!player.isOnline()) {
                        return;
                    }
                    long now = System.nanoTime();
                    Long last = lastClick.put(player.getUniqueId(), now);
                    if (last == null || now - last >= CLICK_GAP) {
                        // The client waits for the next screen: one that fails must not leave it waiting.
                        try {
                            action.accept(view, player);
                        } catch (RuntimeException e) {
                            player.closeDialog();
                            throw e;
                        }
                    }
                });
            }
        }, CLICKS);
    }
}
