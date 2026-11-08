package dev.stsv.core.world;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /야생월드's ready spots: the pool, its file, when the background filler may work, and the search without a pool. */
class WildSpotsTest {

    private static WildSpotRules bundled() throws Exception {
        try (var in = WildSpotsTest.class.getResourceAsStream("/config.yml")) {
            return WildSpotRules.parse(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)).getConfigurationSection("worlds"));
        }
    }

    @Test
    void bundledSettings() throws Exception {
        WildSpotRules r = bundled();
        assertEquals(24, r.pool());
        assertEquals(2000, r.intervalMs(), "one chunk every 2 s");
        assertEquals(250, r.fastIntervalMs());
        assertEquals(19.0, r.minTps());
        assertEquals(40.0, r.maxMspt());
        assertEquals(1, r.arrivalRadius(), "3x3 around a spot");
        assertEquals(2, r.fallbackGenerate());
        assertEquals(WildSpotRules.DEFAULTS, r, "the code's fallback (no worlds section) matches config.yml");
        assertEquals(WildSpotRules.DEFAULTS, WildSpotRules.parse(null));
    }

    @Test
    void parsingClamps() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("wild-spots.pool", -3);
        y.set("wild-spots.interval-seconds", 0);
        y.set("wild-spots.fast-interval-seconds", -1);
        y.set("wild-spots.min-tps", 25);
        y.set("wild-spots.max-mspt", 0);
        y.set("wild-spots.arrival-chunk-radius", 9);
        y.set("wild-spots.fallback-generate-tries", -1);
        WildSpotRules r = WildSpotRules.parse(y);
        assertEquals(0, r.pool(), "0 = off");
        assertEquals(50, r.intervalMs(), "never a busy loop");
        assertEquals(50, r.fastIntervalMs());
        assertEquals(20.0, r.minTps());
        assertEquals(1.0, r.maxMspt());
        assertEquals(3, r.arrivalRadius());
        assertEquals(0, r.fallbackGenerate(), "0 = only already generated chunks");
    }

    @Test
    void takeRemovesARandomSpotAndRefillSkipsDuplicates() {
        WildSpotPool p = new WildSpotPool();
        p.reset("wild", 42);
        assertTrue(p.matches("wild", 42));
        assertFalse(p.matches("wild", 43), "a regenerated world (new seed) is another world");
        assertFalse(p.matches("other", 42));
        for (int i = 0; i < 10; i++) assertTrue(p.add(new WildSpotPool.Spot(i * 100, 70, -i)));
        assertFalse(p.add(new WildSpotPool.Spot(0, 70, 0)), "no duplicates");
        assertEquals(10, p.size());

        SplittableRandom random = new SplittableRandom(3);
        Set<WildSpotPool.Spot> taken = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            WildSpotPool.Spot s = p.take(random);
            assertTrue(s != null && taken.add(s), "each spot once");
            assertEquals(9 - i, p.size());
        }
        assertNull(p.take(random), "empty");
        assertTrue(p.add(new WildSpotPool.Spot(0, 70, 0)), "a taken spot may be found again later");

        // taking is random, not first-in-first-out
        Set<WildSpotPool.Spot> firsts = new HashSet<>();
        for (int round = 0; round < 30; round++) {
            WildSpotPool q = new WildSpotPool();
            for (int i = 0; i < 10; i++) q.add(new WildSpotPool.Spot(i, 64, i));
            firsts.add(q.take(random));
        }
        assertTrue(firsts.size() > 3, "picked " + firsts);

        p.reset("wild", 7);
        assertEquals(0, p.size(), "rebinding empties it");
    }

    @Test
    void theFileRoundTrips() {
        WildSpotPool p = new WildSpotPool();
        p.reset("my wild", -1234567890123L);
        p.add(new WildSpotPool.Spot(4012, 71, -2290));
        p.add(new WildSpotPool.Spot(-300, -12, 15));
        String text = p.write();
        WildSpotPool back = WildSpotPool.read(text);
        assertTrue(back.matches("my wild", -1234567890123L), "a name with a space and a negative seed");
        assertEquals(p.spots(), back.spots());
        assertEquals(text, back.write());

        // broken lines are skipped; spots before any world line mean nothing
        WildSpotPool messy = WildSpotPool.read("1 2 3\n# c\nworld wild 5\n\n10 64 10\nx y z\n1 2\n20 65 20\r\n10 64 10\n");
        assertTrue(messy.matches("wild", 5));
        assertEquals(List.of(new WildSpotPool.Spot(10, 64, 10), new WildSpotPool.Spot(20, 65, 20)), messy.spots());
        assertEquals(0, WildSpotPool.read("").size());
        assertFalse(WildSpotPool.read(null).matches("wild", 0));
        assertTrue(WildSpotPool.read(new WildSpotPool().write()).matches("", 0), "an unbound pool stays unbound");
    }

    @Test
    void theFillerWorksOnlyWhileTheServerIsHealthyAndAtItsPace() throws Exception {
        WildSpotRules r = bundled();
        assertTrue(r.healthy(20, 12));
        assertTrue(r.healthy(19, 39.9));
        assertFalse(r.healthy(18.9, 10), "TPS under 19");
        assertFalse(r.healthy(20, 40), "MSPT 40 or more");

        assertEquals(WildSpotRules.Gate.RUN, r.gate(true, 0, 24, false, 20, 10, 2000, false));
        assertEquals(WildSpotRules.Gate.NO_WORLD, r.gate(false, 0, 24, false, 20, 10, 9999, false));
        assertEquals(WildSpotRules.Gate.OFF, r.gate(true, 0, 0, false, 20, 10, 9999, false));
        assertEquals(WildSpotRules.Gate.FULL, r.gate(true, 24, 24, false, 20, 10, 9999, false));
        assertEquals(WildSpotRules.Gate.BUSY, r.gate(true, 3, 24, true, 20, 10, 9999, false), "one chunk at a time");
        assertEquals(WildSpotRules.Gate.LAG, r.gate(true, 3, 24, false, 17.5, 10, 9999, false));
        assertEquals(WildSpotRules.Gate.LAG, r.gate(true, 3, 24, false, 20, 55, 9999, false));
        assertEquals(WildSpotRules.Gate.PACE, r.gate(true, 3, 24, false, 20, 10, 1999, false), "rests 2 s between chunks");
        assertEquals(WildSpotRules.Gate.RUN, r.gate(true, 3, 24, false, 20, 10, 300, true), "채우기: the short rest");
        assertEquals(WildSpotRules.Gate.PACE, r.gate(true, 3, 24, false, 20, 10, 200, true));
        assertEquals(WildSpotRules.Gate.LAG, r.gate(true, 3, 100, false, 15, 10, 9999, true), "채우기 still waits for a healthy server");
    }

    @Test
    void withoutAPoolGeneratedChunksComeFirstAndGenerationIsCapped() {
        // columns 0..9; even ones are in generated chunks
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) candidates.add(i);
        List<Integer> plan = WildSpotRules.plan(candidates, i -> i % 2 == 0, 12, 2);
        assertEquals(List.of(0, 2, 4, 6, 8, 1, 3), plan, "generated first, then at most 2 that generate terrain");

        assertEquals(List.of(1, 3), WildSpotRules.plan(List.of(1, 3, 5, 7), i -> false, 12, 2), "nothing generated: 2 tries");
        assertEquals(List.of(), WildSpotRules.plan(List.of(1, 3), i -> false, 12, 0), "0 = never generate");
        assertEquals(List.of(0, 2, 4), WildSpotRules.plan(candidates, i -> i % 2 == 0, 3, 2), "tries cap the total");
        assertEquals(List.of(0, 2, 1), WildSpotRules.plan(List.of(0, 1, 2, 3), i -> i % 2 == 0, 3, 2));
    }

    @Test
    void arrivalAreaIsTheSquareAroundTheSpot() {
        List<int[]> a = WildSpotRules.arrivalChunks(10, -4, 1);
        assertEquals(9, a.size(), "3x3");
        assertEquals(10, a.getFirst()[0]);
        assertEquals(-4, a.getFirst()[1], "the spot's own chunk first");
        Set<String> seen = new HashSet<>();
        for (int[] c : a) {
            assertTrue(Math.abs(c[0] - 10) <= 1 && Math.abs(c[1] + 4) <= 1);
            assertTrue(seen.add(c[0] + "," + c[1]));
        }
        assertEquals(1, WildSpotRules.arrivalChunks(0, 0, 0).size());
        assertEquals(25, WildSpotRules.arrivalChunks(0, 0, 2).size());
    }
}
