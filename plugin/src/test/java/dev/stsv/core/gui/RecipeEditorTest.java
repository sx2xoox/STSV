package dev.stsv.core.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeEditorTest {

    @Test
    void trimsEmptyRowsAndColumnsAndReusesLetters() {
        String i = "minecraft:iron_ingot", s = "minecraft:stick";
        String[][] grid = {
                {null, null, null},
                {null, i, i},
                {null, s, null},
        };
        Map<String, Object> out = RecipeEditor.buildShaped(grid);
        assertEquals(List.of("AA", "B "), out.get("pattern"));
        assertEquals(Map.of("A", i, "B", s), out.get("keys"));
    }

    @Test
    void fullGridKeepsShape() {
        String b = "minecraft:bricks", c = "stsv:steel_ingot";
        String[][] grid = {{b, c, b}, {c, null, c}, {b, c, b}};
        Map<String, Object> out = RecipeEditor.buildShaped(grid);
        assertEquals(List.of("ABA", "B B", "ABA"), out.get("pattern"));
    }

    @Test
    void emptyGridIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecipeEditor.buildShaped(new String[3][3]));
    }
}
