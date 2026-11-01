package com.nekyia.treearchive;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Picking a colour in the preview menu, the same for leaves, secondary colours and
 * blossoms: a click on the palette takes one; My colours holds what the player kept;
 * Mix makes any colour, by sliders or hex; and a fade is its colours in a row, each
 * picked the same way. A dialog cannot change while it is open, so a mixed colour shows
 * once it is applied. As everywhere in the menu, what comes back is only the client's
 * word: sliders are clamped, hex and names are read as if they were typed.
 */
final class ColourPicker {

    /**
     * What a colour is picked for: reads what it has, takes what is picked - null for
     * none, where {@code none} allows it - and goes back; with {@code fades} it may be a
     * fade, otherwise a colour alone.
     */
    record Target(String title, Supplier<int @Nullable []> current, Consumer<int @Nullable []> take,
                  Consumer<Player> back, boolean fades, boolean none) {
    }

    /** A kept colour being changed: its name as typed so far, and its colours. */
    private static final class Kept {
        final @Nullable String original;
        String name;
        int[] stops;

        Kept(@Nullable String original, int[] stops) {
            this.original = original;
            this.name = original == null ? "" : original;
            this.stops = stops;
        }
    }

    private static final int COLUMNS = 8;
    private static final int CELL = 40;
    private static final int WIDE = 300;
    private static final int MAX_STOPS = 5;
    private static final int FRESH = 0x6b8e23;
    /** Rows of eight: greens, autumn, blossoms and light, cold and magic, dark and earth. */
    private static final int[] PALETTE = {
        0x9ccc65, 0x7cb342, 0x558b2f, 0x33691e, 0x2e7d32, 0x1b5e20, 0x1f4d2b, 0x26a69a,
        0xffd600, 0xf5d130, 0xffb300, 0xe67e22, 0xd35400, 0xc0392b, 0xd50000, 0x8d3b2a,
        0xffffff, 0xfff1f5, 0xffd1dc, 0xffb7c5, 0xf48fb1, 0xec407a, 0xfff59d, 0xe0f7b6,
        0xe3f2fd, 0xb5c8ff, 0x64c9dc, 0x42a5f5, 0x315d88, 0x5355a6, 0x7e57c2, 0xab47bc,
        0xc2b9b8, 0x77737b, 0x34343b, 0xa1887f, 0x795548, 0x4e342e, 0x738147, 0x373b2a,
    };

    private final PreviewMenu menu;
    private final PreviewLibrary library;

    ColourPicker(PreviewMenu menu, PreviewLibrary library) {
        this.menu = menu;
        this.library = library;
    }

    /** Opens on the palette, or on the colours of a fade there is. */
    void open(Player player, Target target) {
        int[] current = target.current().get();
        if (target.fades() && current != null && current.length > 1) {
            fade(player, target, null);
        } else {
            palette(player, target, null);
        }
    }

    // ---- picking --------------------------------------------------------------------

    private void palette(Player player, Target target, @Nullable String note) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int colour : PALETTE) {
            buttons.add(menu.button(cell(colour), hex(colour), CELL, who -> {
                target.take().accept(new int[] {colour});
                target.back().accept(who);
            }));
        }
        if (target.none()) {
            buttons.add(menu.button(Component.text("None"), "No colour", CELL, who -> {
                target.take().accept(null);
                target.back().accept(who);
            }));
        }
        buttons.add(menu.button(Component.text("Mine"), "My colours", CELL, who -> mine(who, target)));
        buttons.add(menu.button(Component.text("Mix"), "Any colour, by sliders or hex", CELL, who -> {
            int[] current = target.current().get();
            mix(who, target, current == null ? FRESH : current[0], null);
        }));
        if (target.fades()) {
            buttons.add(menu.button(Component.text("Fade"), "Several colours, fading into each other", CELL, who -> {
                int[] current = target.current().get();
                int first = current == null ? FRESH : current[0];
                target.take().accept(current != null && current.length > 1 ? current : new int[] {first, first});
                fade(who, target, null);
            }));
        }
        // One line, to keep the palette and what follows it in sight on a small window.
        int[] current = target.current().get();
        List<DialogBody> body = new ArrayList<>(PreviewMenu.lines(note, current == null ? "Click a colour to take it." : null));
        if (current != null) {
            body.add(DialogBody.plainMessage(Component.text("Now: ").append(gradient(current)), WIDE));
        }
        player.showDialog(PreviewMenu.dialog(target.title(), body, List.of(), buttons,
                menu.button("Back", null, target.back()), COLUMNS));
    }

    /** The player's kept colours to pick from; a fade comes whole where the target can have one. */
    private void mine(Player player, Target target) {
        List<ActionButton> buttons = new ArrayList<>();
        library.colours(player.getUniqueId()).forEach((name, written) -> {
            int[] stops = TreeSelection.colours(written);
            if (stops != null) {
                buttons.add(menu.button(PreviewMenu.swatch(stops).append(Component.text(" " + name)), written, 150, who -> {
                    target.take().accept(target.fades() ? stops : new int[] {stops[0]});
                    target.back().accept(who);
                }));
            }
        });
        player.showDialog(PreviewMenu.dialog("My colours", PreviewMenu.lines(null, buttons.isEmpty()
                        ? "Nothing kept yet: Mix can keep a colour under a name." : null), List.of(), buttons,
                menu.button("Back", null, who -> palette(who, target, null)), 2));
    }

    /** Any colour: hue, saturation and brightness, or hex; applied to see it, used to take it. */
    private void mix(Player player, Target target, int rgb, @Nullable String note) {
        float[] hsb = hsb(rgb);
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("hue", Component.text("Hue"), 0, 360).step(1f)
                        .initial((float) Math.round(hsb[0] * 360)).width(WIDE).build(),
                DialogInput.numberRange("saturation", Component.text("Saturation"), 0, 100).step(1f)
                        .initial((float) Math.round(hsb[1] * 100)).width(WIDE).build(),
                DialogInput.numberRange("brightness", Component.text("Brightness"), 0, 100).step(1f)
                        .initial((float) Math.round(hsb[2] * 100)).width(WIDE).build(),
                DialogInput.text("hex", Component.text("Hex")).initial(hex(rgb)).maxLength(7).width(WIDE).build());
        List<ActionButton> buttons = List.of(
                menu.button(Component.text("Apply"), "Shows the colour here", 100, (view, who) -> mix(who, target, read(view, rgb), null)),
                menu.button(Component.text("Use it"), "Takes the colour", 100, (view, who) -> {
                    target.take().accept(new int[] {read(view, rgb)});
                    target.back().accept(who);
                }),
                menu.button(Component.text("Keep ..."), "Also keeps it in My colours", 100,
                        (view, who) -> keepAs(who, target, read(view, rgb), null)));
        List<DialogBody> body = new ArrayList<>(PreviewMenu.lines(note, null));
        TextComponent.Builder big = Component.text();
        for (int i = 0; i < 10; i++) {
            big.append(cell(rgb));
        }
        body.add(DialogBody.plainMessage(big.build().append(Component.text(" " + hex(rgb))), WIDE));
        player.showDialog(PreviewMenu.dialog(target.title() + ": mix", body, inputs, buttons,
                menu.button("Back", null, who -> palette(who, target, null)), 3));
    }

    /** Keeps a mixed colour under a name, and goes on mixing. */
    private void keepAs(Player player, Target target, int colour, @Nullable String note) {
        player.showDialog(PreviewMenu.dialog("Keep in My colours", PreviewMenu.lines(note, null),
                List.of(DialogInput.text("name", Component.text("Name")).maxLength(32).width(WIDE).build()),
                List.of(menu.button("Keep", null, (view, who) -> {
                    String name = Objects.requireNonNullElse(view.getText("name"), "").trim().toLowerCase(Locale.ROOT);
                    String refused = keep(who, null, name, new int[] {colour});
                    if (refused != null) {
                        keepAs(who, target, colour, refused);
                    } else {
                        mix(who, target, colour, "Kept as " + name + ".");
                    }
                })), menu.button("Back", null, who -> mix(who, target, colour, null)), 1));
    }

    private static int read(DialogResponseView view, int rgb) {
        return mixed(view.getFloat("hue"), view.getFloat("saturation"), view.getFloat("brightness"), view.getText("hex"), rgb);
    }

    /**
     * What the mix screen says, starting from {@code rgb}: hex wins if it was changed and
     * reads, otherwise the sliders - unless they stand where they stood, as the way there
     * and back through hue and saturation would shift the colour a little.
     */
    static int mixed(@Nullable Float hue, @Nullable Float saturation, @Nullable Float brightness, @Nullable String hex, int rgb) {
        int[] typed = hex == null ? null : TreeSelection.colours(hex.trim());
        if (typed != null && typed.length == 1 && typed[0] != rgb) {
            return typed[0];
        }
        float[] was = hsb(rgb);
        int h = whole(hue, Math.round(was[0] * 360), 360);
        int s = whole(saturation, Math.round(was[1] * 100), 100);
        int b = whole(brightness, Math.round(was[2] * 100), 100);
        if (h == Math.round(was[0] * 360) && s == Math.round(was[1] * 100) && b == Math.round(was[2] * 100)) {
            return rgb;
        }
        return Color.HSBtoRGB(h / 360f, s / 100f, b / 100f) & 0xFFFFFF;
    }

    private static int whole(@Nullable Float value, int fallback, int top) {
        return value == null || value.isNaN() ? fallback : Math.clamp(Math.round(value), 0, top);
    }

    private static float[] hsb(int rgb) {
        return Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
    }

    /** A fade: its colours in a row, each picked like any colour; taken as soon as it changes. */
    private void fade(Player player, Target target, @Nullable String note) {
        int[] current = target.current().get();
        int[] stops = current == null ? new int[] {FRESH, FRESH} : current;
        List<ActionButton> buttons = new ArrayList<>(stopButtons(stops, target.title(), target.take(),
                who -> fade(who, target, null)));
        if (stops.length < MAX_STOPS) {
            buttons.add(menu.button(Component.text("Add"), "One more colour at the end", CELL, who -> {
                int[] longer = Arrays.copyOf(stops, stops.length + 1);
                longer[stops.length] = stops[stops.length - 1];
                target.take().accept(longer);
                palette(who, stop(stops.length, longer, target.title(), target.take(), wh -> fade(wh, target, null)), null);
            }));
        }
        buttons.add(menu.button(Component.text("Drop"), "Takes the last colour away", CELL, who -> {
            target.take().accept(Arrays.copyOf(stops, stops.length - 1));
            if (stops.length > 2) {
                fade(who, target, null);
            } else {
                target.back().accept(who);
            }
        }));
        List<DialogBody> body = new ArrayList<>(PreviewMenu.lines(note, "Click a colour to change it."));
        body.add(DialogBody.plainMessage(gradient(stops), WIDE));
        player.showDialog(PreviewMenu.dialog(target.title() + ": fade", body, List.of(), buttons,
                menu.button("Done", null, target.back()), COLUMNS));
    }

    private List<ActionButton> stopButtons(int[] stops, String title, Consumer<int @Nullable []> take, Consumer<Player> back) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < stops.length; i++) {
            int index = i;
            buttons.add(menu.button(cell(stops[i]), hex(stops[i]), CELL,
                    who -> palette(who, stop(index, stops, title, take, back), null)));
        }
        return buttons;
    }

    /** One colour of a fade as something to pick for. */
    private static Target stop(int index, int[] stops, String title, Consumer<int @Nullable []> take, Consumer<Player> back) {
        return new Target(title + ": colour " + (index + 1), () -> new int[] {stops[index]}, picked -> {
            if (picked != null) {
                int[] changed = stops.clone();
                changed[index] = picked[0];
                take.accept(changed);
            }
        }, back, false, false);
    }

    // ---- my colours -----------------------------------------------------------------

    /** The player's kept colours, to change or delete. */
    void manage(Player player, @Nullable String note) {
        List<ActionButton> buttons = new ArrayList<>();
        library.colours(player.getUniqueId()).forEach((name, written) -> {
            int[] stops = TreeSelection.colours(written);
            if (stops != null) {
                buttons.add(menu.button(PreviewMenu.swatch(stops).append(Component.text(" " + name)), written, 150,
                        who -> edit(who, new Kept(name, stops), null)));
            }
        });
        buttons.add(menu.button("New colour", "One colour, or a fade", who -> edit(who, new Kept(null, new int[] {FRESH}), null)));
        player.showDialog(PreviewMenu.dialog("My colours", PreviewMenu.lines(note,
                        "Colours kept here are a click away wherever trees are painted."), List.of(), buttons,
                menu.button("Back", null, menu::home), 2));
    }

    private void edit(Player player, Kept kept, @Nullable String note) {
        Consumer<int @Nullable []> take = stops -> kept.stops = stops == null ? kept.stops : stops;
        Consumer<Player> back = who -> edit(who, kept, null);
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < kept.stops.length; i++) {
            int index = i;
            buttons.add(menu.button(cell(kept.stops[i]), hex(kept.stops[i]), CELL, (view, who) -> {
                name(view, kept);
                palette(who, stop(index, kept.stops, "Colour", take, back), null);
            }));
        }
        if (kept.stops.length < MAX_STOPS) {
            buttons.add(menu.button(Component.text("Add"), "One more colour at the end, for a fade", CELL, (view, who) -> {
                name(view, kept);
                int[] longer = Arrays.copyOf(kept.stops, kept.stops.length + 1);
                longer[kept.stops.length] = kept.stops[kept.stops.length - 1];
                kept.stops = longer;
                palette(who, stop(longer.length - 1, longer, "Colour", take, back), null);
            }));
        }
        if (kept.stops.length > 1) {
            buttons.add(menu.button(Component.text("Drop"), "Takes the last colour away", CELL, (view, who) -> {
                name(view, kept);
                kept.stops = Arrays.copyOf(kept.stops, kept.stops.length - 1);
                edit(who, kept, null);
            }));
        }
        buttons.add(menu.button(Component.text("Save"), "Keeps it under its name", CELL, (view, who) -> {
            name(view, kept);
            String refused = keep(who, kept.original, kept.name, kept.stops);
            if (refused != null) {
                edit(who, kept, refused);
            } else {
                manage(who, "Saved " + kept.name + ".");
            }
        }));
        if (kept.original != null) {
            buttons.add(menu.button(Component.text("Del"), "Deletes this colour", CELL, who -> {
                library.colour(who.getUniqueId(), kept.original, null);
                manage(who, "Deleted " + kept.original + ".");
            }));
        }
        List<DialogBody> body = new ArrayList<>(PreviewMenu.lines(note, "Click a colour to change it."));
        body.add(DialogBody.plainMessage(gradient(kept.stops), WIDE));
        player.showDialog(PreviewMenu.dialog(kept.original == null ? "New colour" : "Colour " + kept.original, body,
                List.of(DialogInput.text("name", Component.text("Name")).initial(kept.name).maxLength(32).width(WIDE).build()),
                buttons, menu.button("Back", null, who -> manage(who, null)), COLUMNS));
    }

    private static void name(DialogResponseView view, Kept kept) {
        kept.name = Objects.requireNonNullElse(view.getText("name"), "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Keeps a colour under a name, in place of {@code original} if that is another.
     *
     * @return why it cannot be kept, or null when it was
     */
    private @Nullable String keep(Player player, @Nullable String original, String name, int[] stops) {
        Map<String, String> own = library.colours(player.getUniqueId());
        if (!name.matches(PreviewMenu.NAME)) {
            return "Name it with up to 32 letters, digits, _ and -.";
        }
        if (!own.containsKey(name) && own.size() >= PreviewMenu.MAX_KEPT) {
            return PreviewMenu.MAX_KEPT + " colours are the most; delete one first.";
        }
        if (original != null && !original.equals(name)) {
            library.colour(player.getUniqueId(), original, null);
        }
        library.colour(player.getUniqueId(), name, PreviewMenu.hex(stops));
        return null;
    }

    // ---- drawing --------------------------------------------------------------------

    private static String hex(int colour) {
        return PreviewMenu.hex(new int[] {colour});
    }

    private static Component cell(int colour) {
        return Component.text("██", TextColor.color(colour & 0xFFFFFF));
    }

    /** The colours as a bar: one for a colour alone, a fade's colours run into each other. */
    static Component gradient(int[] stops) {
        TextComponent.Builder bar = Component.text();
        int cells = stops.length > 1 ? 24 : 8;
        for (int i = 0; i < cells; i++) {
            int colour = stops.length > 1 ? LeafColours.fade(stops, i / (cells - 1.0)) : stops[0];
            bar.append(Component.text("█", TextColor.color(colour & 0xFFFFFF)));
        }
        return bar.build();
    }
}
