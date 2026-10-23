package com.nekyia.treearchive;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * /ta preview as a menu, in the game's own dialogs, so nobody needs a mod for it. A
 * preview is put together marker block by marker block, each with its trees - a type, a
 * size, a creator or a name - their shares, their leaf colours from the player's own
 * colours, bright or fading outwards, and mistletoe of a strength. It is shown right
 * away, or kept as a preset: on the player's own shelf, or in a namespace everyone can
 * use and only those with treearchive.namespaces change. What the menu makes is written
 * the way /ta preview reads it, so presets, hashes and shared links work as before.
 */
final class PreviewMenu {

    /** Who may change the presets of namespaces, and start new ones. */
    static final String NAMESPACES = "treearchive.namespaces";
    /** The most trees an admin can set in the menu; more is still a command away. */
    private static final int ADMIN_LIMIT = 5000;
    private static final int WIDE = 300;
    private static final ClickCallback.Options CLICKS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build();
    private static final String NONE = "none";
    private static final String WRITTEN = "written";
    private static final String AS_IT_WAS = "as_it_was";

    /** One tree of a marker block: what it is, its share among the block's trees, its paint and mistletoe. */
    private static final class Tree {
        String word = "";
        int share = 1;
        int @Nullable [] colours;
        boolean bright;
        boolean out;
        /** none, mistle or drymistle. */
        String mistle = NONE;
        int strength = TreePaster.MISTLE_STRENGTH;
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

    PreviewMenu(JavaPlugin plugin, PreviewLibrary library, TreeArchive archive, TreePreview preview,
                BiConsumer<Player, List<String>> show) {
        this.plugin = plugin;
        this.library = library;
        this.archive = archive;
        this.preview = preview;
        this.show = show;
    }

    void open(Player player) {
        main(player, null);
    }

    // ---- screens --------------------------------------------------------------------

    private void main(Player player, @Nullable String note) {
        UUID uuid = player.getUniqueId();
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("New preview", "Put one together from marker blocks and their trees", who -> {
            drafts.put(uuid, new Draft(PreviewLibrary.Shelf.of(uuid), null));
            editor(who, null);
        }));
        if (drafts.containsKey(uuid)) {
            buttons.add(button("Back to the draft", "The preview you were putting together", who -> editor(who, null)));
        }
        buttons.add(button("My presets", "Previews you kept", who -> presets(who, PreviewLibrary.Shelf.of(uuid), null)));
        buttons.add(button("Namespaces", "Presets everyone shares", who -> namespaces(who, null)));
        buttons.add(button("My colours", "Colours and fades to paint leaves with", who -> colours(who, null)));
        if (preview.shared(player) != null) {
            buttons.add(button("Hide the preview", "Takes the trees away again", who -> {
                preview.clear(who);
                main(who, "Preview taken away.");
            }));
        }
        player.showDialog(dialog("Tree previews", lines(note, preview.shared(player) != null
                ? "A preview is showing." : "Marker blocks around you turn into trees from the archive - for your"
                        + " eyes only."), List.of(), buttons, null));
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
        buttons.add(button("Show", "Grows it around you", who -> show.accept(who, parts)));
        if (canChange(player, shelf)) {
            buttons.add(button("Change", null, who -> {
                drafts.put(who.getUniqueId(), draft(shelf, name, parts));
                editor(who, null);
            }));
            buttons.add(button("Delete", null, who -> confirm(who, "Delete the preset " + name + "?", () -> {
                library.set(shelf, who, name, List.of());
                presets(who, shelf, "Deleted " + name + ".");
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
            draft.markers.add(new Marker());
            marker(who, draft.markers.size() - 1, null);
        }));
        buttons.add(button("Show", "Grows the preview around you", (view, who) -> {
            keep(view, draft, who);
            if (draft.markers.isEmpty()) {
                editor(who, "Add a marker block first.");
            } else {
                show.accept(who, parts(draft));
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
        List<DialogInput> inputs = List.of(DialogInput.text("blocks", Component.text(
                        "Marker block - or several by shares, 70%gold_block,30%diamond_block"))
                .initial(marker.blocks).maxLength(200).width(WIDE).build());
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < marker.trees.size(); i++) {
            int tree = i;
            buttons.add(button(marker.trees.get(i).word, null, (view, who) -> {
                marker.blocks = blocks(view);
                tree(who, index, tree, null);
            }));
        }
        buttons.add(button("Add a tree", "Without any, the block grows every archived tree", (view, who) -> {
            marker.blocks = blocks(view);
            marker.trees.add(new Tree());
            tree(who, index, marker.trees.size() - 1, null);
        }));
        buttons.add(button("Remove the block", null, who -> {
            draft.markers.remove(marker);
            editor(who, null);
        }));
        List<DialogBody> body = new ArrayList<>(lines(note, marker.trees.isEmpty()
                ? "No trees chosen: every archived tree grows on it." : null));
        for (Tree tree : marker.trees) {
            body.add(DialogBody.plainMessage(describe(tree, marker.trees), WIDE));
        }
        player.showDialog(dialog("Marker block", body, inputs, buttons, button("Done", null, (view, who) -> {
            marker.blocks = blocks(view);
            if (marker.blocks.isEmpty()) {
                draft.markers.remove(marker);
                editor(who, null);
                return;
            }
            try {
                PreviewSpec.parse(marker.blocks);
                editor(who, null);
            } catch (IllegalArgumentException e) {
                marker(who, index, "Cannot read that: " + e.getMessage() + ".");
            }
        })));
    }

    /** One tree of a marker block. */
    private void tree(Player player, int markerIndex, int index, @Nullable String note) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null || markerIndex >= draft.markers.size()
                || index >= draft.markers.get(markerIndex).trees.size()) {
            editor(player, null);
            return;
        }
        Marker marker = draft.markers.get(markerIndex);
        Tree tree = marker.trees.get(index);
        Map<String, String> colours = library.colours(player.getUniqueId());

        List<SingleOptionDialogInput.OptionEntry> types = new ArrayList<>();
        boolean typed = archive.types().contains(tree.word);
        types.add(SingleOptionDialogInput.OptionEntry.create(WRITTEN, Component.text("As written below"), !typed));
        for (String type : archive.types()) {
            types.add(SingleOptionDialogInput.OptionEntry.create(type, Component.text(type), type.equals(tree.word)));
        }
        String current = tree.colours == null ? null : hex(tree.colours);
        String chosen = current == null ? NONE : colours.entrySet().stream()
                .filter(colour -> colour.getValue().equalsIgnoreCase(current)).map(Map.Entry::getKey)
                .findFirst().orElse(AS_IT_WAS);
        List<SingleOptionDialogInput.OptionEntry> paints = new ArrayList<>();
        paints.add(SingleOptionDialogInput.OptionEntry.create(NONE, Component.text("Its own leaf colours"),
                chosen.equals(NONE)));
        if (chosen.equals(AS_IT_WAS)) {
            paints.add(SingleOptionDialogInput.OptionEntry.create(AS_IT_WAS,
                    swatch(Objects.requireNonNull(tree.colours)).append(Component.text(" as it was")), true));
        }
        colours.forEach((name, written) -> {
            int[] stops = TreeSelection.colours(written);
            if (stops != null) {
                paints.add(SingleOptionDialogInput.OptionEntry.create("c_" + name,
                        swatch(stops).append(Component.text(" " + name)), chosen.equals(name)));
            }
        });
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("type", Component.text("Tree type"), types).width(WIDE).build(),
                DialogInput.text("word", Component.text("... or words: a type, size, creator or name"))
                        .initial(typed ? "" : tree.word).maxLength(100).width(WIDE).build(),
                DialogInput.numberRange("share", Component.text("Share among the block's trees"), 1, 100)
                        .step(1f).initial((float) tree.share).width(WIDE).build(),
                DialogInput.singleOption("paint", Component.text("Leaf colour"), paints).width(WIDE).build(),
                DialogInput.bool("bright", Component.text("Bright - on a lighter leaf texture")).initial(tree.bright).build(),
                DialogInput.bool("out", Component.text("Fade from the middle outwards, not upwards"))
                        .initial(tree.out).build(),
                DialogInput.singleOption("mistle", Component.text("Mistletoe"), List.of(
                        SingleOptionDialogInput.OptionEntry.create(NONE, Component.text("None"), tree.mistle.equals(NONE)),
                        SingleOptionDialogInput.OptionEntry.create("mistle", Component.text("Moss with bushes"),
                                tree.mistle.equals("mistle")),
                        SingleOptionDialogInput.OptionEntry.create("drymistle",
                                Component.text("Dry - mangrove roots with dry grass"), tree.mistle.equals("drymistle"))))
                        .width(WIDE).build(),
                DialogInput.numberRange("strength", Component.text("Mistletoe in place of leaves, %"), 1, 100)
                        .step(1f).initial((float) tree.strength).width(WIDE).build());

        List<ActionButton> buttons = List.of(
                button("Remove the tree", null, who -> {
                    marker.trees.remove(tree);
                    marker(who, markerIndex, null);
                }),
                button("Paint with a new colour", "Make one under My colours first", who -> colours(who, null)));
        player.showDialog(dialog("Tree", lines(note, colours.isEmpty()
                        ? "Colours to paint with come from My colours; you have none yet." : null),
                inputs, buttons, button("Done", null, (view, who) -> {
                    String type = Objects.requireNonNullElse(view.getText("type"), WRITTEN);
                    String word = (type.equals(WRITTEN) ? Objects.requireNonNullElse(view.getText("word"), "") : type)
                            .toLowerCase(Locale.ROOT).replaceAll("[\\s,/:*&%]", "");
                    long matching = archive.entries().stream().filter(entry -> TreeArchive.matches(entry, word)).count();
                    if (word.isEmpty() || matching == 0) {
                        tree(who, markerIndex, index, word.isEmpty() ? "Choose a tree type or write words."
                                : "No archived tree matches '" + word + "'.");
                        return;
                    }
                    tree.word = word;
                    tree.share = Math.round(Objects.requireNonNullElse(view.getFloat("share"), 1f));
                    String paint = Objects.requireNonNullElse(view.getText("paint"), NONE);
                    if (paint.equals(NONE)) {
                        tree.colours = null;
                    } else if (paint.startsWith("c_")) {
                        tree.colours = TreeSelection.colours(colours.getOrDefault(paint.substring(2), ""));
                    }
                    tree.bright = Boolean.TRUE.equals(view.getBoolean("bright"));
                    tree.out = Boolean.TRUE.equals(view.getBoolean("out"));
                    tree.mistle = Objects.requireNonNullElse(view.getText("mistle"), NONE);
                    tree.strength = Math.round(Objects.requireNonNullElse(view.getFloat("strength"),
                            (float) TreePaster.MISTLE_STRENGTH));
                    marker(who, markerIndex, tree.colours == null && (tree.bright || tree.out)
                            ? "Bright and the outward fade only go with a leaf colour." : null);
                })));
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
                    if (!name.matches("[a-z0-9_-]+") || ArchiveCommand.PRESET_WORDS.contains(name)
                            || shelf.namespace() != null && !shelf.namespace().matches("[a-z0-9_-]+")) {
                        saveAs(who, "That name will not do.");
                    } else if (!canChange(who, shelf)) {
                        saveAs(who, "Only mods and admins change namespaces.");
                    } else {
                        save(who, draft, shelf, name);
                    }
                })), button("Back", null, who -> editor(who, null))));
    }

    private void save(Player player, Draft draft, PreviewLibrary.Shelf shelf, String name) {
        library.set(shelf, player, name, parts(draft));
        draft.shelf = shelf;
        draft.name = name;
        editor(player, "Saved as " + name + " in " + where(shelf) + ".");
    }

    private void colours(Player player, @Nullable String note) {
        List<ActionButton> buttons = new ArrayList<>();
        library.colours(player.getUniqueId()).forEach((name, written) -> {
            int[] stops = TreeSelection.colours(written);
            Component label = stops == null ? Component.text(name) : swatch(stops).append(Component.text(" " + name));
            buttons.add(ActionButton.builder(label).tooltip(Component.text(written)).width(150)
                    .action(action(who -> colour(who, name, null))).build());
        });
        buttons.add(button("New colour", "One colour, or a fade over two or three", who -> colour(who, null, null)));
        player.showDialog(dialog("My colours", lines(note, "Colours you paint trees with, in the tree's menu."),
                List.of(), buttons, button("Back", null, who -> {
                    if (drafts.containsKey(who.getUniqueId())) {
                        editor(who, null);
                    } else {
                        main(who, null);
                    }
                })));
    }

    /** A colour, new or kept: a name, and one to three colours - more than one fade into each other. */
    private void colour(Player player, @Nullable String name, @Nullable String note) {
        String kept = name == null ? null : library.colours(player.getUniqueId()).get(name);
        int[] stops = kept == null ? null : TreeSelection.colours(kept);
        int[] shown = stops == null ? new int[] {0x6b8e23} : stops;
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("name", Component.text("Name")).initial(name == null ? "" : name).maxLength(40)
                .width(WIDE).build());
        inputs.add(DialogInput.singleOption("count", Component.text("Colours"), List.of(
                SingleOptionDialogInput.OptionEntry.create("1", Component.text("One colour"), shown.length == 1),
                SingleOptionDialogInput.OptionEntry.create("2", Component.text("A fade over two"), shown.length == 2),
                SingleOptionDialogInput.OptionEntry.create("3", Component.text("A fade over three"), shown.length >= 3)))
                .width(WIDE).build());
        String[] channels = {"Red", "Green", "Blue"};
        for (int stop = 0; stop < 3; stop++) {
            int rgb = shown[Math.min(stop, shown.length - 1)];
            for (int channel = 0; channel < 3; channel++) {
                inputs.add(DialogInput.numberRange("c" + stop + channel, Component.text(
                                (stop == 0 ? "First" : stop == 1 ? "Second" : "Third") + " colour: " + channels[channel]),
                        0, 255).step(1f).initial((float) (rgb >> (16 - 8 * channel) & 255)).width(WIDE).build());
            }
        }
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Save", "Keeps the colour; save again to see it", (view, who) -> {
            String newName = Objects.requireNonNullElse(view.getText("name"), "").trim().toLowerCase(Locale.ROOT);
            if (!newName.matches("[a-z0-9_-]+")) {
                colour(who, name, "Name it with letters, digits, _ and -.");
                return;
            }
            int count = Integer.parseInt(Objects.requireNonNullElse(view.getText("count"), "1"));
            int[] made = new int[count];
            for (int stop = 0; stop < count; stop++) {
                for (int channel = 0; channel < 3; channel++) {
                    int value = Math.round(Objects.requireNonNullElse(view.getFloat("c" + stop + channel), 0f));
                    made[stop] |= Math.clamp(value, 0, 255) << (16 - 8 * channel);
                }
            }
            if (name != null && !name.equals(newName)) {
                library.colour(who.getUniqueId(), name, null);
            }
            library.colour(who.getUniqueId(), newName, hex(made));
            colour(who, newName, "Saved.");
        }));
        if (kept != null) {
            buttons.add(button("Delete", null, who -> {
                library.colour(who.getUniqueId(), Objects.requireNonNull(name), null);
                colours(who, "Deleted " + name + ".");
            }));
        }
        List<DialogBody> body = new ArrayList<>(lines(note, null));
        if (stops != null) {
            body.add(DialogBody.plainMessage(swatch(stops).append(Component.text(" " + kept)), WIDE));
        }
        player.showDialog(dialog(name == null ? "New colour" : "Colour " + name, body, inputs, buttons,
                button("Back", null, who -> colours(who, null))));
    }

    private void confirm(Player player, String question, Runnable yes, Runnable no) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(question)).build())
                .type(DialogType.confirmation(button("Yes", null, who -> yes.run()), button("No", null, who -> no.run())))));
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
        return Objects.requireNonNullElse(view.getText("blocks"), "").toLowerCase(Locale.ROOT).replaceAll("[\\s:/]", "");
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
                draft.limit = Integer.parseInt(part.substring("--limit ".length()));
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
                        for (String kind : TreeSelection.WITH_STRENGTH) {
                            String modifier = TreeSelection.find(written.modifiers(), kind);
                            if (modifier != null) {
                                tree.mistle = kind;
                                tree.strength = TreeSelection.strength(modifier, TreePaster.MISTLE_STRENGTH);
                            }
                        }
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
            boolean shares = marker.trees.stream().mapToInt(tree -> tree.share).distinct().count() > 1;
            parts.add(marker.blocks + (marker.trees.isEmpty() ? "" : ":" + marker.trees.stream()
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
            written.append('*').append(hex(tree.colours)).append(tree.bright ? "&bright" : "").append(tree.out ? "&out" : "");
        }
        if (!tree.mistle.equals(NONE)) {
            written.append('&').append(tree.mistle)
                    .append(tree.strength == TreePaster.MISTLE_STRENGTH ? "" : "=" + tree.strength);
        }
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
        int total = all.stream().mapToInt(each -> each.share).sum();
        TextComponent.Builder line = Component.text();
        if (all.size() > 1) {
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
        if (!tree.mistle.equals(NONE)) {
            also.add((tree.mistle.equals("drymistle") ? "dry mistletoe " : "mistletoe ") + tree.strength + "%");
        }
        if (!also.isEmpty()) {
            line.append(Component.text(" (" + String.join(", ", also) + ")", NamedTextColor.GRAY));
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

    private static String hex(int[] colours) {
        return Arrays.stream(colours).mapToObj(colour -> String.format("#%06x", colour & 0xFFFFFF))
                .collect(Collectors.joining(">"));
    }

    /** A square per colour, in that colour. */
    private static Component swatch(int[] colours) {
        TextComponent.Builder swatch = Component.text();
        for (int colour : colours) {
            swatch.append(Component.text("\u2588", TextColor.color(colour & 0xFFFFFF)));
        }
        return swatch.build();
    }

    /** A note in red over the screen's own line, either of them left out when null. */
    private static List<DialogBody> lines(@Nullable String note, @Nullable String line) {
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
        DialogType type = buttons.isEmpty() && exit != null
                ? DialogType.notice(exit)
                : exit == null ? DialogType.multiAction(buttons).columns(2).build()
                        : DialogType.multiAction(buttons).columns(2).exitAction(exit).build();
        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).inputs(inputs).build())
                .type(type));
    }

    private ActionButton button(String label, @Nullable String tooltip, Consumer<Player> action) {
        return button(label, tooltip, (view, player) -> action.accept(player));
    }

    private ActionButton button(String label, @Nullable String tooltip, BiConsumer<DialogResponseView, Player> action) {
        ActionButton.Builder button = ActionButton.builder(Component.text(label)).width(150).action(action(action));
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
                    if (player.isOnline()) {
                        action.accept(view, player);
                    }
                });
            }
        }, CLICKS);
    }
}
