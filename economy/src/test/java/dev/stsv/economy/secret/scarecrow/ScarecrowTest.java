package dev.stsv.economy.secret.scarecrow;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scarecrow's seven riddles against the real crops, its turning, its walk to the door and the night clock. */
class ScarecrowTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    private static YamlConfiguration yml(String rel) {
        return YamlConfiguration.loadConfiguration(PACK.resolve(rel).toFile());
    }

    private static Riddles riddles() {
        return Riddles.parse(yml("secrets/scarecrow.yml").getMapList("asks"));
    }

    // ------------------------------------------------------------------------------------------
    // riddles
    // ------------------------------------------------------------------------------------------

    @Test
    void sevenAsksAndEveryAnswerIsARealCropItem() {
        Riddles r = riddles();
        assertEquals(7, r.size());
        YamlConfiguration items = yml("items/crops.yml");
        int most = 0;
        for (Riddles.Ask a : r.all()) {
            assertFalse(a.say().isEmpty(), a.id());
            if (a.most()) {
                most++;
                assertFalse(a.fallback().isEmpty(), "most needs a fallback");
                continue;
            }
            assertFalse(a.items().isEmpty(), a.id() + " has answers");
            for (String id : a.items()) {
                if (id.startsWith("economy:")) assertTrue(items.contains(id.substring(8)), a.id() + ": no such item " + id);
                else assertTrue(id.startsWith("minecraft:"), id);
            }
        }
        assertEquals(1, most, "one 'what you planted most' ask");
        assertTrue(r.ask(7).most(), "it is the last");
        // the last riddle carries the rule, faintly
        assertTrue(String.join(" ", r.ask(7).say()).contains("문을 열면 안 돼"));
    }

    @Test
    void theSlowestCropIsTheOneThatRipensLast() {
        YamlConfiguration crops = yml("crops/crops.yml");
        String slowest = null;
        int best = -1;
        for (String k : crops.getKeys(false)) {
            int age = crops.getInt(k + ".max-age", 7);
            if (age > best) {
                best = age;
                slowest = crops.getMapList(k + ".harvest").getFirst().get("item").toString();
            }
        }
        Riddles.Ask slow = riddles().all().stream().filter(a -> a.id().equals("slow")).findFirst().orElseThrow();
        assertEquals(Set.of("economy:" + slowest), slow.items());
    }

    @Test
    void anOfferingIsRightOnceAndNeverTwice() {
        Riddles.Ask red = riddles().ask(1);
        assertEquals(Riddles.Verdict.RIGHT, Riddles.judge(red, "economy:tomato", Set.of(), Set.of()));
        assertEquals(Riddles.Verdict.RIGHT, Riddles.judge(red, "BEETROOT", Set.of(), Set.of()));
        assertEquals(Riddles.Verdict.WRONG, Riddles.judge(red, "minecraft:carrot", Set.of(), Set.of()));
        assertEquals(Riddles.Verdict.AGAIN, Riddles.judge(red, "minecraft:beetroot", Set.of(), Set.of("minecraft:beetroot")));
        // beetroot is red and grew under the ground, but only answers one of them
        Riddles.Ask under = riddles().ask(2);
        assertEquals(Riddles.Verdict.AGAIN, Riddles.judge(under, "minecraft:beetroot", Set.of(), Set.of("minecraft:beetroot")));
        // two different answers for every pair of neighbouring asks exist (it can always be solved)
        Riddles r = riddles();
        for (int i = 1; i < 7; i++) {
            Set<String> both = new LinkedHashSet<>(r.ask(i).items());
            both.addAll(r.ask(i + 1).items());
            assertTrue(both.size() >= 2);
        }
    }

    @Test
    void whatYouPlantedMostWithItsSeedsAndTies() {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, List<String>> seeds = Map.of("economy:tomato", List.of("economy:tomato_seeds"), "minecraft:wheat", List.of("minecraft:wheat_seeds"));
        assertTrue(Riddles.most(counts, seeds).isEmpty());
        counts.put("economy:tomato", 12);
        counts.put("minecraft:wheat", 30);
        assertEquals(Set.of("minecraft:wheat", "minecraft:wheat_seeds"), Riddles.most(counts, seeds));
        counts.put("economy:tomato", 30);
        assertEquals(Set.of("economy:tomato", "economy:tomato_seeds", "minecraft:wheat", "minecraft:wheat_seeds"), Riddles.most(counts, seeds));
        Riddles.Ask last = riddles().ask(7);
        assertEquals(Riddles.Verdict.RIGHT, Riddles.judge(last, "economy:tomato_seeds", Riddles.most(counts, seeds), Set.of()));
        // nothing planted: the fallback answers
        assertEquals(Riddles.Verdict.RIGHT, Riddles.judge(last, "minecraft:wheat", Set.of(), Set.of()));
        assertEquals(Set.of("a:b", "minecraft:c"), Riddles.split(Riddles.join(Set.of("a:b", "minecraft:c"))));
    }

    @Test
    void everyVanillaCropBlockGivesSomething() {
        for (var e : Riddles.VANILLA.entrySet()) {
            assertFalse(e.getValue().isEmpty(), e.getKey());
            for (String id : e.getValue()) assertTrue(id.startsWith("minecraft:"), id);
        }
    }

    // ------------------------------------------------------------------------------------------
    // turning, walking, the clock
    // ------------------------------------------------------------------------------------------

    @Test
    void itTurnsTheShortWayRoundAndSlowly() {
        assertEquals(0, Geometry.yawTo(0, 1), 1e-4);      // south
        assertEquals(90, Geometry.yawTo(-1, 0), 1e-4);    // west
        assertEquals(-90, Geometry.yawTo(1, 0), 1e-4);    // east
        assertEquals(-180, Geometry.yawTo(0, -1), 1e-4);  // north
        assertEquals(175, Geometry.approach(170, -170, 5), 1e-4);
        assertEquals(-170, Geometry.approach(175, -170, 20), 1e-4);
        assertEquals(10, Geometry.between(175, -175), 1e-4);
        float y = 0;
        for (int i = 0; i < 10; i++) y = Geometry.approach(y, 90, 0.9f);
        assertEquals(9, y, 1e-3);
    }

    @Test
    void itIsFoundCloserToTheDoorWithEveryOffering() {
        // 20 blocks to the door: one block an offering, the field edge (60%) from the fifth, never within 3 of the door
        assertEquals(0, Geometry.along(0, 20, 5, 0.6, 3), 1e-9);
        assertEquals(1, Geometry.along(1, 20, 5, 0.6, 3), 1e-9);
        assertEquals(4, Geometry.along(4, 20, 5, 0.6, 3), 1e-9);
        assertEquals(12, Geometry.along(5, 20, 5, 0.6, 3), 1e-9);
        assertEquals(12, Geometry.along(6, 20, 5, 0.6, 3), 1e-9);
        // a door right by the field: it never stands in the doorway
        assertEquals(2, Geometry.along(6, 5, 5, 0.6, 3), 1e-9);
        assertEquals(0, Geometry.along(3, 2, 5, 0.6, 3), 1e-9);
        double last = -1;
        for (int n = 0; n <= 7; n++) {
            double a = Geometry.along(n, 30, 5, 0.6, 3);
            assertTrue(a >= last, "never steps back");
            last = a;
        }
    }

    @Test
    void theNightClock() {
        assertTrue(Geometry.night(13000, 13000, 23000));
        assertTrue(Geometry.night(22999, 13000, 23000));
        assertFalse(Geometry.night(23000, 13000, 23000));
        assertFalse(Geometry.night(6000, 13000, 23000));
        assertTrue(Geometry.night(24000 * 5 + 18000, 13000, 23000));
        assertTrue(Geometry.night(500, 22000, 1000));      // a night past midnight
        assertEquals(5000, Geometry.nightLeft(18000, 13000, 23000));
        assertEquals(0, Geometry.nightLeft(6000, 13000, 23000));
        // the last night: the real night left, but never shorter than min nor longer than max
        assertEquals(250, Geometry.finalSeconds(5000, 150, 360));
        assertEquals(150, Geometry.finalSeconds(200, 150, 360));
        assertEquals(360, Geometry.finalSeconds(10000, 150, 360));
        assertEquals(18000, Geometry.sky(18000, 23000, 0));
        assertEquals(20500, Geometry.sky(18000, 23000, 0.5));
        assertEquals(23000, Geometry.sky(18000, 23000, 1.5));
        // one night keeps one number through midnight; the next night has the next
        long day = 24000L * 40;
        assertEquals(Geometry.nightNo(day + 13000, 13000), Geometry.nightNo(day + 24000 + 12999, 13000));
        assertEquals(Geometry.nightNo(day + 13000, 13000) + 1, Geometry.nightNo(day + 24000 + 13000, 13000));
    }
}
