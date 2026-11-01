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

    /** The mix screen: what the client sends is clamped, and a colour left alone stays exactly as it was. */
    @Test
    void mixingKeepsAColourLeftAlone() {
        int rgb = 0x6c8fe8;
        float[] hsb = java.awt.Color.RGBtoHSB(0x6c, 0x8f, 0xe8, null);
        Float hue = (float) Math.round(hsb[0] * 360);
        Float saturation = (float) Math.round(hsb[1] * 100);
        Float brightness = (float) Math.round(hsb[2] * 100);
        assertEquals(rgb, ColourPicker.mixed(hue, saturation, brightness, rgb));
        assertEquals(rgb, ColourPicker.mixed(null, Float.NaN, null, rgb));
        assertEquals(0xff0000, ColourPicker.mixed(0f, 100f, 100f, rgb));
        assertEquals(0xffffff, ColourPicker.mixed(9999f, -5f, 9999f, rgb));
    }

    /** Light colours only show on the lighter texture, so taking one turns bright on. */
    @Test
    void lightColoursAreKnown() {
        assertTrue(ColourPicker.light(new int[] {0xffffff}));
        assertTrue(ColourPicker.light(new int[] {0x315d88, 0xb5c8ff}));
        assertTrue(ColourPicker.light(new int[] {0xe0f7b6 | LeafColours.PRESENT}));
        assertFalse(ColourPicker.light(new int[] {0x315d88, 0x1b5e20}));
        assertFalse(ColourPicker.light(new int[] {0xc0392b}));
    }

    /** The example block shows what the tree has: fade, brightness, secondary colour, and blossoms by how many. */
    @Test
    void theExampleBlockShowsTheTree() {
        int blossom = 0xff00ff | LeafColours.PRESENT;
        int secondary = 0x00ffff | LeafColours.PRESENT;
        int[] red = {0xff0000};
        assertEquals(0, count(ColourPicker.exampleColours(red, false, false, 0, 0, 30), 0xff00ff));
        assertEquals(1, count(ColourPicker.exampleColours(red, false, false, 0, blossom, 20), 0xff00ff));
        assertEquals(5, count(ColourPicker.exampleColours(red, false, false, 0, blossom, 100), 0xff00ff));
        assertEquals(0, count(ColourPicker.exampleColours(red, false, false, 0, 0, 100), 0xff00ff));
        assertEquals(3, count(ColourPicker.exampleColours(red, false, true, secondary, 0, 30), 0x00ebeb));
        // Fading up: the last colour on top. Fading out: the first in the middle.
        int[][] up = ColourPicker.exampleColours(new int[] {0x000000, 0xffffff}, false, true, 0, 0, 30);
        assertEquals(0, up[2][0] >> 16 & 255);
        assertTrue((up[0][0] >> 16 & 255) > 200);
        int[][] out = ColourPicker.exampleColours(new int[] {0x000000, 0xffffff}, true, true, 0, 0, 30);
        assertEquals(0, out[1][2]);
        assertTrue((out[0][0] >> 16 & 255) > 200);
        // Bright is lighter, and without a colour the block still shows its own green.
        assertTrue((ColourPicker.exampleColours(red, false, true, 0, 0, 30)[0][0] >> 16 & 255)
                > (ColourPicker.exampleColours(red, false, false, 0, 0, 30)[0][0] >> 16 & 255));
        assertTrue((ColourPicker.exampleColours(null, false, false, 0, 0, 30)[0][0] >> 8 & 255) > 0);
    }

    private static int count(int[][] colours, int colour) {
        return (int) java.util.Arrays.stream(colours).flatMapToInt(java.util.Arrays::stream).filter(each -> each == colour).count();
    }

    /** A preset may hold a limit too long for a number, as the command takes any: it opens all the same. */
    @Test
    void aLimitTooLongIsNoLimit() {
        assertEquals(List.of("--continuous"), again(List.of("--limit 99999999999", "--continuous")));
    }

    /** All that can hang on a tree comes back as written, each at its strength or at the one meant without a number. */
    @Test
    void keepsWhatHangsOnATree() {
        List<String> parts = List.of("gold_block:maple&mistle=30&drymistle&vines=20&lichen&glowberries=5&roots=7&weeping=9&twisting=40");
        assertEquals(parts, again(parts));
    }

    /** Every block that can hang counts as foliage, or a hit on its ghost would take the real marker away. */
    @Test
    void everythingThatHangsIsKnown() {
        for (TreeExtra extra : TreeExtra.values()) {
            assertTrue(TreeExtra.hanging(extra.body), extra.modifier);
            assertTrue(TreeExtra.hanging(extra.tip), extra.modifier);
            assertTrue(TreeSelection.MODIFIERS.contains(extra.modifier), extra.modifier);
            assertTrue(TreeSelection.WITH_STRENGTH.contains(extra.modifier), extra.modifier);
        }
        assertFalse(TreeExtra.hanging(org.bukkit.Material.STONE));
    }

    /** What has no firm hold is named, at any strength; what holds on leaves, and mistletoe with its moss, is not. */
    @Test
    void anIllegalTreeIsKnownByWhatHangsOnIt() {
        assertEquals(List.of(), TreeExtra.illegal(java.util.Set.of("vines=10", "lichen", "mistle=30", "bright")));
        assertEquals(List.of("drymistle", "glowberries", "roots", "weeping", "twisting"), TreeExtra.illegal(
                java.util.Set.of("twisting", "weeping=5", "roots", "glowberries=50", "drymistle", "vines")));
    }
}
