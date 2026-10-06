package com.nekyia.treearchive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Which trees a command is about, written the way WorldEdit writes blocks:
 * {@code birch,oak} takes both, {@code 70%birch,30%oak} takes both but reaches for
 * birch seven times out of ten. Every word may be a tree type, a size, a creator or
 * a piece of a tree's name, so it reads the way it is meant.
 *
 * <p>Nothing written at all means every archived tree. Held to one {@link Field}, the
 * words have to be exactly a type or exactly a creator instead.
 *
 * <p>A word may end in a leaf colour, {@code beech*#d98c2b}, for the players who have the
 * ColorfulLeaves mod, or a fade over the crown, {@code beech*#f5d130>#c0392b}; a colour
 * that cannot be read stays part of the word. Behind it come the modifiers of that tree,
 * each after an ampersand: {@code beech*#ffffff&bright}.
 */
record TreeSelection(List<Part> parts, Field field) {

    /** One word of the selection, with the share it was given, its leaf colours, 0xRRGGBB, and modifiers. */
    record Part(double weight, String word, int @Nullable [] colours, Set<String> modifiers) {
    }

    /**
     * What may stand behind an ampersand: bright puts the leaves on their lighter texture,
     * out runs a fade from the middle of the crown to its edge instead of upwards, mistle
     * hangs mistletoe in the crown - moss with a bush on top, as the birches have it - and
     * drymistle dry mistletoe for fall trees, mangrove roots with dry grass.
     */
    static final Set<String> MODIFIERS = Set.of("bright", "out", "mistle", "drymistle");

    /** What the words are compared with. */
    enum Field {
        /** Type, size, creator or a piece of the name - whatever fits. */
        ANY,
        TYPE,
        CREATOR
    }

    /** Everything in the archive. */
    static final TreeSelection ALL = new TreeSelection(List.of(), Field.ANY);

    /** Reads a selection, or ALL when there is nothing to read. */
    static TreeSelection parse(@Nullable String argument) {
        if (argument == null || argument.isBlank()) {
            return ALL;
        }
        List<Part> parts = new ArrayList<>();
        for (String piece : argument.split(",")) {
            String word = piece.trim().toLowerCase(Locale.ROOT);
            if (word.isEmpty()) {
                continue;
            }
            double weight = 1;
            int percent = word.indexOf('%');
            if (percent > 0) {
                try {
                    weight = Double.parseDouble(word.substring(0, percent));
                } catch (NumberFormatException e) {
                    // Not a share after all; take the whole thing as a word.
                    percent = -1;
                }
                if (percent > 0) {
                    word = word.substring(percent + 1).trim();
                }
            }
            Set<String> modifiers = Set.of();
            int ampersand = word.indexOf('&');
            if (ampersand >= 0) {
                modifiers = Set.copyOf(Arrays.stream(word.substring(ampersand + 1).split("&"))
                        .map(String::trim)
                        .filter(modifier -> !modifier.isEmpty())
                        .toList());
                word = word.substring(0, ampersand).trim();
            }
            int[] colours = null;
            int star = word.indexOf('*');
            if (star >= 0) {
                colours = colours(word.substring(star + 1));
                if (colours != null) {
                    word = word.substring(0, star).trim();
                }
            }
            if (!word.isEmpty() && weight > 0) {
                parts.add(new Part(weight, word, colours, modifiers));
            }
        }
        return parts.isEmpty() ? ALL : new TreeSelection(parts, Field.ANY);
    }

    /** Colours like #f5d130>#c0392b, the # optional; null when one cannot be read. */
    private static int @Nullable [] colours(String written) {
        String[] hexes = written.split(">");
        int[] colours = new int[hexes.length];
        for (int i = 0; i < hexes.length; i++) {
            String hex = hexes[i].trim();
            TextColor colour = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
            if (colour == null) {
                return null;
            }
            colours[i] = colour.value();
        }
        return colours;
    }

    /** The same words, compared with one field only. */
    TreeSelection by(Field field) {
        return new TreeSelection(parts, field);
    }

    private boolean matches(TreeArchive.Entry entry, String word) {
        return switch (field) {
            case ANY -> TreeArchive.matches(entry, word);
            case TYPE -> entry.type().equals(word);
            case CREATOR -> entry.creator().name().equalsIgnoreCase(word);
        };
    }

    boolean all() {
        return parts.isEmpty();
    }

    /** Every tree any word of the selection names, in the order they were given. */
    List<TreeArchive.Entry> matching(List<TreeArchive.Entry> entries) {
        if (all()) {
            return entries;
        }
        List<TreeArchive.Entry> found = new ArrayList<>();
        for (TreeArchive.Entry entry : entries) {
            if (parts.stream().anyMatch(part -> matches(entry, part.word()))) {
                found.add(entry);
            }
        }
        return found;
    }

    /**
     * A tree drawn, with the word that drew it - whose colours and modifiers it gets, so
     * that maple written twice with two colours gives half the maples each.
     */
    record Pick(TreeArchive.Entry entry, @Nullable Part part) {

        /** How the word paints the leaves, or null for their own colours. */
        LeafColours.@Nullable Paint paint() {
            return part == null || part.colours() == null ? null : new LeafColours.Paint(part.colours(),
                    part.modifiers().contains("out"), part.modifiers().contains("bright"));
        }

        Set<String> modifiers() {
            return part == null ? Set.of() : part.modifiers();
        }
    }

    /**
     * One tree at random, by the shares given: a word is drawn by its weight, and a
     * tree drawn from the trees that word names. Null when nothing matches at all.
     */
    TreeArchive.@Nullable Entry pick(List<TreeArchive.Entry> entries) {
        return pick(entries, ThreadLocalRandom.current());
    }

    /** As above, drawing from {@code random}, so a seeded one draws the same trees again. */
    TreeArchive.@Nullable Entry pick(List<TreeArchive.Entry> entries, RandomGenerator random) {
        Pick pick = draw(entries, random);
        return pick == null ? null : pick.entry();
    }

    /** One tree at random, as {@link #pick}, with the word that drew it. */
    @Nullable Pick draw(List<TreeArchive.Entry> entries, RandomGenerator random) {
        if (all()) {
            return entries.isEmpty() ? null : new Pick(entries.get(random.nextInt(entries.size())), null);
        }
        // Words that name nothing must not eat their share of the draws.
        List<Part> usable = parts.stream()
                .filter(part -> entries.stream().anyMatch(e -> matches(e, part.word())))
                .toList();
        if (usable.isEmpty()) {
            return null;
        }

        double total = usable.stream().mapToDouble(Part::weight).sum();
        double drawn = random.nextDouble(total);
        Part picked = usable.getLast();
        for (Part part : usable) {
            drawn -= part.weight();
            if (drawn < 0) {
                picked = part;
                break;
            }
        }

        Part chosen = picked;
        List<TreeArchive.Entry> of = entries.stream()
                .filter(entry -> matches(entry, chosen.word()))
                .toList();
        return new Pick(of.get(random.nextInt(of.size())), chosen);
    }
}
