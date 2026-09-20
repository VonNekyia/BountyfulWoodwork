package com.nekyia.bountyfulWookwork;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * Which trees a command is about, written the way WorldEdit writes blocks:
 * {@code birch,oak} takes both, {@code 70%birch,30%oak} takes both but reaches for
 * birch seven times out of ten. Every word may be a tree type, a size, a creator or
 * a piece of a tree's name, so it reads the way it is meant.
 *
 * <p>Nothing written at all means every archived tree.
 */
record TreeSelection(List<Part> parts) {

    /** One word of the selection, with the share it was given. */
    record Part(double weight, String word) {
    }

    /** Everything in the archive. */
    static final TreeSelection ALL = new TreeSelection(List.of());

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
        return parts.isEmpty() ? ALL : new TreeSelection(parts);
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
            if (parts.stream().anyMatch(part -> TreeArchive.matches(entry, part.word()))) {
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
        if (all()) {
            return entries.isEmpty() ? null : entries.get(random(entries.size()));
        }
        // Words that name nothing must not eat their share of the draws.
        List<Part> usable = parts.stream()
                .filter(part -> entries.stream().anyMatch(e -> TreeArchive.matches(e, part.word())))
                .toList();
        if (usable.isEmpty()) {
            return null;
        }

        double total = usable.stream().mapToDouble(Part::weight).sum();
        double drawn = ThreadLocalRandom.current().nextDouble(total);
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
                .filter(entry -> TreeArchive.matches(entry, chosen.word()))
                .toList();
        return of.get(random(of.size()));
    }

    private static int random(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }
}
