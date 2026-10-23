package com.nekyia.treearchive;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        List<String> parts = List.of("gold_block:70%maple*#c0392b>#f5d130&bright&out&mistle=30,30%beech&drymistle",
                "--limit 300", "--continuous", "--bypassterrain");
        assertEquals(parts, again(parts));
    }

    @Test
    void keepsBlocksWithoutTreesAndEqualShares() {
        List<String> parts = List.of("70%gold_block,30%diamond_block", "emerald_block:maple,beech*#ffffff");
        assertEquals(parts, again(parts));
    }
}
