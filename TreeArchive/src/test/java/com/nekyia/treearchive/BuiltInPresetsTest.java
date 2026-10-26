package com.nekyia.treearchive;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The presets TreeArchive brings along read as /ta preview reads them, every tree with what it is painted. */
class BuiltInPresetsTest {

    /** The marker blocks the presets use, as their file says - and blocks they are, checked in game. */
    private static final Set<String> MARKERS = Set.of("gold_block", "diamond_block", "emerald_block");
    /** Tree types of the build server's archive, types.yml; a word naming none would quietly grow nothing. */
    private static final Set<String> TYPES = Set.of("beech", "maple", "oak", "birch", "larch", "norway_spruce",
            "silver_fir", "white_fir", "norway_pine", "white_pine");

    @Test
    void everyPresetReads() {
        YamlConfiguration presets = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("builtin-presets.yml")), StandardCharsets.UTF_8));
        assertFalse(presets.getKeys(false).isEmpty());
        for (String namespace : presets.getKeys(false)) {
            for (String name : Objects.requireNonNull(presets.getConfigurationSection(namespace)).getKeys(false)) {
                for (String part : presets.getStringList(namespace + "." + name)) {
                    String where = namespace + "@" + name + ": " + part;
                    assertFalse(part.contains(" "), where);
                    int colon = part.indexOf(':');
                    assertTrue(colon > 0 && MARKERS.contains(part.substring(0, colon)), where);
                    TreeSelection trees = TreeSelection.parse(part.substring(colon + 1));
                    assertFalse(trees.parts().isEmpty(), where);
                    trees.parts().forEach(tree -> assertTrue(TYPES.contains(tree.word()), where + " - " + tree.word()));
                    assertDoesNotThrow(() -> PreviewSpec.check(trees), where);
                }
            }
        }
    }
}
