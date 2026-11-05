package dev.stsv.core.crop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom crops grow by the same numbers as vanilla CropBlock. */
class CropGrowthTest {

    private static int[][] field(int centre, int around) {
        int[][] s = new int[3][3];
        for (int[] row : s) java.util.Arrays.fill(row, around);
        s[1][1] = centre;
        return s;
    }

    @Test
    void wetFarmlandGrowsFasterLikeVanilla() {
        // a lone crop on wet farmland in a wet field: 1 + 3 + 8 x 3/4 = 10 -> 1 / (floor(2.5) + 1) = 1/3
        double wetField = CropGrowth.speed(field(2, 2), false, false, false);
        assertEquals(10, wetField, 1e-9);
        assertEquals(1.0 / 3, CropGrowth.chance(wetField), 1e-9);
        // dry farmland everywhere: 1 + 1 + 8 x 1/4 = 4 -> 1/7
        double dry = CropGrowth.speed(field(1, 1), false, false, false);
        assertEquals(4, dry, 1e-9);
        assertEquals(1.0 / 7, CropGrowth.chance(dry), 1e-9);
        // a wet patch with nothing around: 1 + 3 = 4
        assertEquals(4, CropGrowth.speed(field(2, 0), false, false, false), 1e-9);
        // the same crop on two sides (or a diagonal) halves it: rows beat a solid block
        assertEquals(5, CropGrowth.speed(field(2, 2), true, true, false), 1e-9);
        assertEquals(5, CropGrowth.speed(field(2, 2), false, false, true), 1e-9);
        assertEquals(10, CropGrowth.speed(field(2, 2), true, false, false), 1e-9, "one row: no penalty");
        assertEquals(1.0 / 6, CropGrowth.chance(5), 1e-9);
    }

    @Test
    void randomTicksAverageThreePerSectionPerTick() {
        Random r = new Random(7);
        long total = 0;
        int runs = 20_000;
        for (int i = 0; i < runs; i++) total += CropGrowth.randomTicks(100, 3, r);
        // 100 ticks x 3 / 4096 = 0.0732 random ticks for one block
        assertEquals(100 * 3 / 4096.0, total / (double) runs, 0.005);
        assertEquals(0, CropGrowth.randomTicks(100, 0, r), "randomTickSpeed 0: nothing grows");
        int big = CropGrowth.randomTicks(4096 * 100, 3, r);
        assertTrue(big > 250 && big < 350, "large counts stay near the mean: " + big);
    }

    @Test
    void aWheatLikeCropRipensInAboutHalfAnHourOnWetFarmland() {
        // vanilla wheat: 7 ages, one random tick every 68 s on average, 1/3 chance: about 24 minutes
        Random r = new Random(11);
        double chance = CropGrowth.chance(10);
        long sumTicks = 0;
        int runs = 2000;
        for (int i = 0; i < runs; i++) {
            int age = 0;
            long t = 0;
            while (age < 7) {
                t += 100;
                age = CropGrowth.grow(age, 7, CropGrowth.randomTicks(100, 3, r), chance, r);
            }
            sumTicks += t;
        }
        double minutes = sumTicks / (double) runs / 20 / 60;
        assertEquals(23.9, minutes, 1.5);
    }

    @Test
    void boneMealAddsTwoToFive() {
        Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            int a = CropGrowth.boneMeal(0, 7, r);
            assertTrue(a >= 2 && a <= 5, "" + a);
        }
        assertEquals(7, CropGrowth.boneMeal(6, 7, r), "never past ripe");
    }

    @Test
    void stagesFollowTheAge() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("seed", "tomato_seeds");
        y.set("stages", 5);
        y.set("regrow", 4);
        y.set("height", 1.2);
        y.set("job", "farmer");
        y.set("harvest", List.of(Map.of("item", "tomato", "min", 2, "max", 4), Map.of("item", "tomato_seeds", "min", 0, "max", 1)));
        CropSpec s = CropSpec.parse("economy", "tomato", y);
        assertEquals(7, s.maxAge(), "like wheat by default");
        assertEquals("economy:tomato_seeds", s.seed());
        assertEquals("economy:tomato", s.produce());
        assertEquals(List.of(0, 0, 1, 1, 2, 2, 3, 4), java.util.stream.IntStream.rangeClosed(0, 7).map(s::stageOf).boxed().toList(),
                "the ripe model only when ripe");
        assertTrue(s.ripe(7) && !s.ripe(6));
        assertEquals("economy:model/tomato_4", s.models().get(4));
        assertEquals("farmer", s.extra().get("job"));
        y.set("regrow", 7);
        assertThrows(IllegalArgumentException.class, () -> CropSpec.parse("economy", "tomato", y));
        y.set("regrow", null);
        y.set("max-age", 3);
        assertThrows(IllegalArgumentException.class, () -> CropSpec.parse("economy", "tomato", y), "fewer ages than stages");
    }
}
