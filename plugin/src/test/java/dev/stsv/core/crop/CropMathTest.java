package dev.stsv.core.crop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropMathTest {

    static final long MIN = 60_000, HOUR = 3_600_000;

    static CropSpec spec(double pestChance) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", "토마토");
        y.set("seed", "tomato_seeds");
        y.set("stages", 5);
        y.set("grow-minutes", 120);      // 30 minutes a stage
        y.set("water-hours", 8);
        y.set("wither-hours", 24);
        y.set("pest-chance", pestChance);
        y.set("pest-hours", 12);
        y.set("regrow", 3);
        y.set("harvest", List.of(Map.of("item", "tomato", "min", 2, "max", 4, "quality", true)));
        y.set("job", "farmer");
        return CropSpec.parse("economy", "tomato", y);
    }

    static CropState fresh(long t) {
        return new CropState(1, "economy:tomato", "world", 0, 64, 0, null, UUID.randomUUID(), t);
    }

    @Test
    void parsesWithDefaultsAndPassesUnknownKeysOn() {
        CropSpec s = spec(0.05);
        assertEquals("economy:tomato", s.id());
        assertEquals("economy:tomato_seeds", s.seed());
        assertEquals(List.of("minecraft:farmland"), s.soil());
        assertEquals("economy:model/tomato_0", s.models().getFirst());
        assertEquals("economy:model/tomato_4", s.models().getLast());
        assertEquals("economy:model/withered_crop", s.witheredModel());
        assertEquals("economy:tomato", s.harvest().getFirst().item());
        assertTrue(s.harvest().getFirst().quality());
        assertEquals("farmer", s.extra().get("job"));
        assertEquals(30 * MIN, s.stageMillis());
        assertEquals(0, s.stageOf(29 * MIN));
        assertEquals(1, s.stageOf(30 * MIN));
        assertEquals(4, s.stageOf(500 * MIN));
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("seed", "x");
        bad.set("stages", 4);
        bad.set("regrow", 3);
        bad.set("harvest", List.of(Map.of("item", "x")));
        assertThrows(IllegalArgumentException.class, () -> CropSpec.parse("e", "x", bad), "regrow must be below the last stage");
    }

    @Test
    void growsOnlyWhileWet() {
        CropSpec spec = spec(0);
        long t = 1_000_000;
        CropState s = fresh(t);
        CropMath.update(s, spec, null, false, t + HOUR, new Random(1));
        assertEquals(0, s.progress, "planted dry: no growth");
        assertTrue(s.drySince > 0);
        CropMath.water(s, spec, t + HOUR);
        CropMath.update(s, spec, null, false, t + HOUR + 45 * MIN, new Random(1));
        assertEquals(45 * MIN, s.progress);
        assertEquals(1, spec.stageOf(s.progress));
        // the water lasts 8 hours: growth stops when it runs out, ripe after 120 wet minutes
        CropMath.update(s, spec, null, false, t + 20 * HOUR, new Random(1));
        assertTrue(spec.ripe(s.progress));
        assertFalse(s.withered);
    }

    @Test
    void catchingUpLaterGivesTheSameResult() {
        CropSpec spec = spec(0);
        long t = 5_000_000;
        CropState a = fresh(t), b = fresh(t);
        CropMath.water(a, spec, t);
        CropMath.water(b, spec, t);
        for (long k = 1; k <= 300; k++) CropMath.update(a, spec, null, false, t + k * MIN, new Random(2));   // a loaded chunk
        CropMath.update(b, spec, null, false, t + 300 * MIN, new Random(2));                              // an unloaded one
        assertEquals(a.progress, b.progress);
        assertEquals(a.drySince, b.drySince);
        assertEquals(a.neverDry, b.neverDry);
    }

    @Test
    void witherAfterADayDry() {
        CropSpec spec = spec(0);
        long t = 0;
        CropState s = fresh(t);
        CropMath.water(s, spec, t);
        CropMath.update(s, spec, null, false, t + 8 * HOUR + 23 * HOUR, new Random(3));
        assertFalse(s.withered, "dry for 23 hours");
        assertFalse(s.neverDry);
        CropMath.update(s, spec, null, false, t + 8 * HOUR + 24 * HOUR, new Random(3));
        assertTrue(s.withered);
        long p = s.progress;
        CropMath.water(s, spec, t + 40 * HOUR);
        CropMath.update(s, spec, null, false, t + 45 * HOUR, new Random(3));
        assertEquals(p, s.progress, "withered crops stay dead");
    }

    @Test
    void sprinklersKeepItWetAndFertilizerSpeedsItUp() {
        CropSpec spec = spec(0);
        CropState s = fresh(0);
        CropMath.update(s, spec, null, true, 60 * MIN, new Random(4));
        assertEquals(60 * MIN, s.progress);
        assertTrue(s.neverDry);
        CropState f = fresh(0);
        CropMath.update(f, spec, new CropTools.Fertilizer("x", 1.5, 0.3, 1), true, 60 * MIN, new Random(4));
        assertEquals(90 * MIN, f.progress);
    }

    @Test
    void pestsStopGrowthUntilCuredAndWitherIfIgnored() {
        CropSpec spec = spec(1.0);   // pests at the first new stage
        CropState s = fresh(0);
        CropMath.update(s, spec, null, true, 5 * HOUR, new Random(5));
        assertTrue(s.pests());
        assertEquals(30 * MIN, s.progress, "stopped when it reached stage 1");
        assertEquals(30 * MIN, s.pestSince);
        assertFalse(s.withered);
        s.pestSince = 0;   // pesticide
        CropMath.update(s, spec, null, true, 6 * HOUR, new Random(5));
        assertTrue(s.pests(), "pests again at the next stage (chance 1)");
        CropMath.update(s, spec, null, true, 6 * HOUR + 13 * HOUR, new Random(5));
        assertTrue(s.withered, "12 hours of pests");
    }

    @Test
    void regrowAndQuality() {
        CropSpec spec = spec(0);
        CropState s = fresh(0);
        CropMath.update(s, spec, null, true, 3 * HOUR, new Random(6));
        assertTrue(spec.ripe(s.progress));
        s.fertilizer = "x";
        assertTrue(CropMath.harvested(s, spec));
        assertEquals(3, spec.stageOf(s.progress));
        assertEquals(null, s.fertilizer, "fertilizer lasts one harvest");
        // quality: never dry + good fertilizer = mostly 3 stars; dried out and nothing = mostly 1-2
        Random r = new Random(7);
        int threes = 0, ones = 0;
        CropState careful = fresh(0), careless = fresh(0);
        careless.neverDry = false;
        CropTools.Fertilizer good = new CropTools.Fertilizer("x", 1, 0.3, 0);
        for (int i = 0; i < 1000; i++) {
            if (CropMath.stars(careful, good, 0, r) == 3) threes++;
            if (CropMath.stars(careless, null, 0, r) == 1) ones++;
        }
        assertTrue(threes > 650 && threes < 850, "about 75% (0.85 ± 0.1 against 0.8): " + threes);
        assertTrue(ones > 900, "ones " + ones);
    }
}
