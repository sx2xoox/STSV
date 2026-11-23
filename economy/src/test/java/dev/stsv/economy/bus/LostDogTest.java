package dev.stsv.economy.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** 바둑아 어디 가 (README "마을버스" 이벤트, bus.yml events.lost-dog): the pure parts of LostDog. */
class LostDogTest {

    private static YamlConfiguration busYml() {
        return YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml"));
    }

    @Test
    void busYmlHasTheEvent() {
        LostDog.Cfg c = LostDog.Cfg.parse(busYml());
        assertTrue(c.enabled());
        assertEquals("dogwalker", c.type());
        assertEquals(0.10, c.chance(), 1e-9);
        assertEquals(3, c.perDay());
        assertEquals(3000, c.rewardMin());
        assertEquals(8000, c.rewardMax());
        assertEquals(10, c.distMin(), 1e-9);
        assertEquals(25, c.distMax(), 1e-9);
        assertEquals("jesa", c.secret());
        assertTrue(c.laments().size() >= 6, "laments");
        assertTrue(c.hints().size() >= 10, "hints");
        assertTrue(c.memories().size() >= 6, "memories");
        for (String l : c.laments()) assertTrue(l.contains("{stop}"), l);   // he always names the stop
        assertTrue(c.reunion().contains("아이고 바둑아! 할애비가 얼마나 찾았는 줄 아냐"));
        // no line twice, and a plain memory is never one of the hints
        assertEquals(c.hints().size(), new HashSet<>(c.hints()).size());
        assertEquals(c.memories().size(), new HashSet<>(c.memories()).size());
        for (String m : c.memories()) assertFalse(c.hints().contains(m), m);
        // the kind he is exists, and the secret his talk hints at
        List<String> problems = new java.util.ArrayList<>();
        BusSpec spec = BusSpec.parse(busYml(), problems);
        assertNotNull(spec.types().get(c.type()));
        assertEquals("wolf", spec.types().get(c.type()).x().pet());
        assertTrue(new File("content/economy/secrets/" + c.secret() + ".yml").isFile());
    }

    @Test
    void missingSectionFallsBackToSaneDefaults() {
        LostDog.Cfg c = LostDog.Cfg.parse(new YamlConfiguration());
        assertEquals(0.10, c.chance(), 1e-9);
        assertEquals(3, c.perDay());
        assertEquals(3000, c.rewardMin());
        assertEquals(8000, c.rewardMax());
        assertFalse(c.laments().isEmpty());
        assertFalse(c.reunion().isEmpty());
        YamlConfiguration y = new YamlConfiguration();
        y.set("events.lost-dog.reward", List.of(900, 100));   // the wrong way round: never below the least
        y.set("events.lost-dog.chance", 7);
        LostDog.Cfg odd = LostDog.Cfg.parse(y);
        assertEquals(900, odd.rewardMin());
        assertEquals(900, odd.rewardMax());
        assertEquals(1, odd.chance(), 1e-9);
    }

    @Test
    void rewardStaysInItsRange() {
        LostDog.Cfg c = LostDog.Cfg.parse(busYml());
        assertEquals(3000, c.reward(0));
        assertEquals(8000, c.reward(1));
        assertEquals(8000, c.reward(0.9999999));
        for (int i = 0; i <= 100; i++) {
            long g = c.reward(i / 100.0);
            assertTrue(g >= 3000 && g <= 8000, "" + g);
        }
    }

    @Test
    void theStopIsAnotherOneAheadWhenThereIsOne() {
        List<Integer> line = List.of(1, 2, 3, 4, 5, 6);
        // got on at 2, going to 4, the bus heading up the line: 3, 5 or 6, never 2 or 4, never one behind
        Set<Integer> seen = new TreeSet<>();
        for (long seed = 0; seed < 300; seed++) {
            int t = LostDog.pickStop(List.of(3, 4, 5, 6), line, 2, 4, seed);
            assertEquals(t, LostDog.pickStop(List.of(3, 4, 5, 6), line, 2, 4, seed), "the same for the same seed");
            seen.add(t);
        }
        assertEquals(Set.of(3, 5, 6), seen);
    }

    @Test
    void withNothingAheadTheStopIsBehindOrHisOwn() {
        // going to the last stop: only a stop behind is left
        for (long seed = 0; seed < 50; seed++) assertEquals(1, LostDog.pickStop(List.of(3), List.of(1, 2, 3), 2, 3, seed));
        // a line of two stops: where he goes (the dog is found there, he waits there)
        for (long seed = 0; seed < 50; seed++) assertEquals(2, LostDog.pickStop(List.of(2), List.of(1, 2), 1, 2, seed));
        // nothing but the stop he got on at (the others were removed): no event
        assertEquals(-1, LostDog.pickStop(List.of(), List.of(1), 1, 2, 7));
        assertEquals(-1, LostDog.pickStop(List.of(), List.of(), 1, 2, 7));
        // a stop listed twice (a loop line) is one stop
        for (long seed = 0; seed < 50; seed++) assertEquals(3, LostDog.pickStop(List.of(3, 3, 2), List.of(1, 2, 3, 1), 1, 2, seed));
    }

    @Test
    void unheardLinesComeFirst() {
        int size = 12;
        Set<Integer> heard = new TreeSet<>();
        // a whole round: every line once, none twice
        for (int n = 0; n < size; n++) {
            int i = LostDog.pickLine(size, heard, 1000 + n);
            assertFalse(heard.contains(i), "line " + i + " again before the others were heard");
            heard = LostDog.heardAfter(size, heard, i);
            assertEquals(n + 1, heard.size());
        }
        // all heard: any line, and the round starts again with it
        int again = LostDog.pickLine(size, heard, 5);
        assertTrue(again >= 0 && again < size);
        assertEquals(Set.of(again), LostDog.heardAfter(size, heard, again));
        // one left: that one, whatever the seed
        Set<Integer> most = new TreeSet<>(List.of(0, 1, 2, 4));
        for (long seed = 0; seed < 50; seed++) assertEquals(3, LostDog.pickLine(5, most, seed));
        assertEquals(-1, LostDog.pickLine(0, Set.of(), 1));
    }

    @Test
    void heardLinesAreSavedAndReadBack() {
        assertEquals("0,3,11", LostDog.formatHeard(new TreeSet<>(List.of(11, 0, 3))));
        assertEquals(Set.of(0, 3, 11), LostDog.parseHeard("0,3,11"));
        assertEquals(Set.of(), LostDog.parseHeard(""));
        assertEquals(Set.of(), LostDog.parseHeard(null));
        assertEquals(Set.of(2), LostDog.parseHeard("x,2,,"));   // a damaged entry is as if not heard
        // the pool got shorter in bus.yml: what no longer exists is forgotten
        assertEquals(Set.of(1, 2), LostDog.heardAfter(3, new TreeSet<>(List.of(1, 7, 9)), 2));
    }

    @Test
    void threeRewardsADay() {
        String day = "2026-11-23", next = "2026-11-24", saved = "";
        for (int n = 0; n < 3; n++) {
            assertTrue(LostDog.capLeft(saved, day, 3), "reward " + (n + 1));
            saved = LostDog.counted(saved, day);
            assertEquals(n + 1, LostDog.rewardsOn(saved, day));
        }
        assertEquals("2026-11-23:3", saved);
        assertFalse(LostDog.capLeft(saved, day, 3));
        // the next (Korean) day starts again
        assertEquals(0, LostDog.rewardsOn(saved, next));
        assertTrue(LostDog.capLeft(saved, next, 3));
        assertEquals("2026-11-24:1", LostDog.counted(saved, next));
        // nothing saved, something damaged: none yet; per-day 0 switches the rewards off
        assertEquals(0, LostDog.rewardsOn(null, day));
        assertEquals(0, LostDog.rewardsOn("2026-11-23:x", day));
        assertEquals(0, LostDog.rewardsOn("junk", day));
        assertFalse(LostDog.capLeft("", day, 0));
    }

    @Test
    void theDogShowsOnlyAfterReallyDrivingThere() {
        ServiceGate.Rules r = new ServiceGate.Rules(100, 0.7, 30, 30);
        assertFalse(LostDog.honest(0, 400, r));      // the bus was carried there (or never moved)
        assertFalse(LostDog.honest(250, 400, r));    // less than 70% of the road between the two stops
        assertTrue(LostDog.honest(280, 400, r));
        assertFalse(LostDog.honest(60, 50, r));      // two stops close together: still at least min-drive
        assertTrue(LostDog.honest(100, 50, r));
        assertFalse(LostDog.honest(99, Double.NaN, r));   // no road known between them: min-drive
        assertTrue(LostDog.honest(100, Double.POSITIVE_INFINITY, r));
    }

    @Test
    void theDogSitsOnlyOnDryGround() {
        // y 63 = the surface; columns: x 0..2 grass, x 3 water, x 5 ice over water, x 7 a tree, x 9..11 grass with a hole at 10
        DryGround.Probe p = (x, y, z) -> {
            if (x == 3) return y <= 63 ? DryGround.Cell.WET : DryGround.Cell.AIR;
            if (x == 5) return y <= 63 ? DryGround.Cell.WET : DryGround.Cell.AIR;
            if (x == 7) return y <= 63 ? DryGround.Cell.GROUND : y <= 68 ? DryGround.Cell.OTHER : DryGround.Cell.AIR;
            if (x == 10) return y <= 59 ? DryGround.Cell.GROUND : DryGround.Cell.AIR;
            return y <= 63 ? DryGround.Cell.GROUND : DryGround.Cell.AIR;
        };
        assertEquals(64, DryGround.ground(p, 0, 0, 72, 56));
        assertEquals(DryGround.WET, DryGround.ground(p, 3, 0, 72, 56));
        assertEquals(DryGround.WET, DryGround.ground(p, 5, 0, 72, 56));
        assertEquals(DryGround.NONE, DryGround.ground(p, 7, 0, 72, 56));    // under a tree: something in the way
        assertEquals(DryGround.NONE, DryGround.ground(p, 0, 0, 72, 70));    // no ground in the window
        assertFalse(DryGround.found(DryGround.WET));
        assertFalse(DryGround.found(DryGround.NONE));
        // a spot needs the columns beside it too: not at the water's edge, not at the edge of a hole
        assertEquals(64, DryGround.spot(p, 1, 0, 72, 56));
        assertEquals(64, DryGround.spot(p, 20, 0, 72, 56));
        assertEquals(DryGround.WET, DryGround.spot(p, 2, 0, 72, 56));
        assertEquals(DryGround.NONE, DryGround.spot(p, 9, 0, 72, 56));
        assertEquals(DryGround.NONE, DryGround.spot(p, 11, 0, 72, 56));
    }

    @Test
    void theAchievementCountsTenReunions() {
        YamlConfiguration a = YamlConfiguration.loadConfiguration(new File("content/economy/achievements/bus.yml"));
        assertEquals(1, a.getConfigurationSection("achievements").getKeys(false).size(), "exactly one");
        assertEquals("바둑아 어디 가", a.getString("achievements.dog_found_10.name"));
        assertEquals(LostDog.STAT, a.getString("achievements.dog_found_10.stat"));
        assertEquals(10, a.getInt("achievements.dog_found_10.goal"));
        assertEquals("normal", a.getString("achievements.dog_found_10.tier"));
        assertTrue(a.getLong("achievements.dog_found_10.rewards.gold") > 0);
        assertFalse(a.contains("achievements.dog_found_10.rewards.title"));
    }

    @Test
    void everyMessageItSendsExists() throws Exception {
        YamlConfiguration m = YamlConfiguration.loadConfiguration(new File("src/main/resources/messages.yml"));
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/stsv/economy/bus/LostDog.java"));
        java.util.regex.Matcher k = java.util.regex.Pattern.compile("\"(bus\\.[a-z-]+)\"").matcher(src);
        int n = 0;
        while (k.find()) {
            if (k.group(1).startsWith("bus.dog.")) continue;   // eco.kv keys
            assertTrue(m.isString(k.group(1)), k.group(1));
            n++;
        }
        assertTrue(n >= 10, "keys " + n);
    }
}
