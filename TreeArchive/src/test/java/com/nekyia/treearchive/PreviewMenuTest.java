package com.nekyia.treearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the menu reads from a preset, it writes back the same - nothing a preset holds is lost. */
class PreviewMenuTest {

    private static List<String> again(List<String> parts) {
        return PreviewMenu.parts(PreviewMenu.draft(PreviewLibrary.Shelf.of(UUID.randomUUID()), "autumn", parts));
    }

    @Test
    void keepsEverythingATreeCanHave() {
        List<String> parts = List.of("gold_block:70%maple*#c0392b>#f5d130&bright&out&second=#f5d130&blossom=#ffb7c5"
                        + "&bloom=40&mistle=30,30%beech&drymistle",
                "--limit 300", "--continuous", "--bypassterrain");
        assertEquals(parts, again(parts));
    }

    @Test
    void keepsBlocksWithoutTreesAndEqualShares() {
        List<String> parts = List.of("70%gold_block,30%diamond_block", "emerald_block:maple,beech*#ffffff");
        assertEquals(parts, again(parts));
    }

    /**
     * A changed client sends what it likes: no space gets through, so no --seed or --at, and
     * no hash. Whether a word is a block needs a running server, so that is left out here.
     */
    @Test
    void letsNothingIntoThePreviewsCommandLine() {
        assertTrue(PreviewMenu.fits("--limit 300"));
        assertTrue(PreviewMenu.fits("--continuous"));
        assertFalse(PreviewMenu.fits("gold_block:maple&mistle --at minecraft:the_nether,5000,5000"));
        assertFalse(PreviewMenu.fits("--limit 300 --seed 1"));
        assertFalse(PreviewMenu.fits("--seed 1"));
        assertFalse(PreviewMenu.fits("--at minecraft:the_nether,5000,5000"));
        assertFalse(PreviewMenu.fits("#abcdefgh"));
    }
}
