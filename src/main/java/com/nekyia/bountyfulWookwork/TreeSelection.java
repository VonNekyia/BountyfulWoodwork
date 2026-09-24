package com.nekyia.bountyfulWookwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Which trees a command is about, written the way WorldEdit writes blocks:
 * {@code birch,oak} takes both, {@code 70%birch,30%oak} takes both but reaches for
 * birch seven times out of ten. Every word may be a tree type, a size, a creator or
 * a piece of a tree's name, so it reads the way it is meant.
 *
 * <p>Nothing written at all means every archived tree. Held to one {@link Field}, the
 * words have to be exactly a type or exactly a creator instead.
 */
record TreeSelection(List<Part> parts, Field field) {

    /** One word of the selection, with the share it was given. */
    record Part(double weight, String word) {
    }

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
            if (!word.isEmpty() && weight > 0) {
                parts.add(new Part(weight, word));
            }
        }
        return parts.isEmpty() ? ALL : new TreeSelection(parts, Field.ANY);
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
     * One tree at random, by the shares given: a word is drawn by its weight, and a
     * tree drawn from the trees that word names. Null when nothing matches at all.
     */
    TreeArchive.@Nullable Entry pick(List<TreeArchive.Entry> entries) {
        return pick(entries, ThreadLocalRandom.current());
    }

    /** As above, drawing from {@code random}, so a seeded one draws the same trees again. */
    TreeArchive.@Nullable Entry pick(List<TreeArchive.Entry> entries, RandomGenerator random) {
        if (all()) {
            return entries.isEmpty() ? null : entries.get(random.nextInt(entries.size()));
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
        return of.get(random.nextInt(of.size()));
    }
}
