package dev.stsv.economy.secret.fish;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 「물이 부르는 날」: when the water may call, the week's nameless thing (fixed, not one of the last weeks'),
 * where the water is looked for, the streak and the window, the pull; fish.yml has the pool and a reward in the hard range.
 */
class FishSequelTest {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static YamlConfiguration yml() {
        return YamlConfiguration.loadConfiguration(new File(System.getProperty("stsv.content"), "economy/secrets/fish.yml"));
    }

    @Test
    void callsInTheRainOrLateInADryWeek() {
        assertTrue(Calling.mayCall(true, 1, 5, false));
        assertFalse(Calling.mayCall(false, 1, 5, false));
        assertFalse(Calling.mayCall(false, 4, 5, false));
        assertTrue(Calling.mayCall(false, 5, 5, false));
        assertTrue(Calling.mayCall(false, 7, 5, false));
        assertFalse(Calling.mayCall(false, 7, 8, false));   // only ever in the rain
        assertTrue(Calling.mayCall(true, 7, 8, false));
        assertTrue(Calling.mayCall(false, 1, 8, true));     // test mode
    }

    @Test
    void variantIsFixedForAWeekAndSkipsRecentOnes() {
        int v = Calling.variant("2026-W48", A, 10, List.of());
        assertEquals(v, Calling.variant("2026-W48", A, 10, List.of()));
        assertNotEquals(v, Calling.variant("2026-W48", A, 10, List.of(v)));
        String recent = "";
        List<Integer> seen = new ArrayList<>();
        for (int w = 1; w <= 30; w++) {
            int x = Calling.variant("2026-W" + w, A, 10, Calling.recent(recent));
            assertTrue(x >= 0 && x < 10);
            assertFalse(seen.subList(Math.max(0, seen.size() - 4), seen.size()).contains(x), "week " + w + " repeats " + x);
            seen.add(x);
            recent = Calling.remember(recent, x, 4, 10);
        }
        assertTrue(new HashSet<>(seen).size() >= 6);
        assertEquals(0, Calling.variant("w", A, 1, List.of(0)));
        assertEquals("", Calling.remember("0", 0, 4, 1));
        assertEquals(0, Calling.variant("w", A, 0, List.of()));
    }

    @Test
    void theRingLooksSomeWayOffInEveryDirectionOnce() {
        List<int[]> ring = Calling.ring(1000, -2000, 120, 360, 24, 12, 7);
        assertFalse(ring.isEmpty());
        Set<Long> seen = new HashSet<>();
        int cx = 1000 >> 4, cz = -2000 >> 4;
        Set<Integer> quadrants = new HashSet<>();
        for (int[] c : ring) {
            assertTrue(seen.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL)), "chunk twice");
            double d = Math.hypot((c[0] - cx) * 16, (c[1] - cz) * 16);
            assertTrue(d >= 120 - 24 && d <= 360 + 24, "distance " + d);
            quadrants.add((c[0] >= cx ? 1 : 0) + (c[1] >= cz ? 2 : 0));
        }
        assertEquals(4, quadrants.size());
        assertTrue(ring.size() <= 12 * 11);
        // the same week looks the same way; another week begins in another direction
        assertArrayEquals(ring.getFirst(), Calling.ring(1000, -2000, 120, 360, 24, 12, 7).getFirst());
        assertFalse(java.util.Arrays.equals(ring.getFirst(), Calling.ring(1000, -2000, 120, 360, 24, 12, 10).getFirst()));
        // odd settings do not loop for ever or divide by zero
        assertFalse(Calling.ring(0, 0, 50, 10, 0, 0, -3).isEmpty());
    }

    @Test
    void threeCleanInARow() {
        int s = 0;
        s = Calling.streak(s, true);
        s = Calling.streak(s, true);
        assertEquals(2, s);
        s = Calling.streak(s, false);
        assertEquals(0, s);
        s = Calling.streak(Calling.streak(Calling.streak(s, true), true), true);
        assertEquals(3, s);
        // clean = reeled within the ticks after the bite (the secret's own rule)
        assertTrue(Waters.perfect(100, 110, 10));
        assertFalse(Waters.perfect(100, 111, 10));
        assertFalse(Waters.perfect(0, 5, 10));
    }

    @Test
    void theWindowRunsOut() {
        assertEquals(420, Calling.left(1_000, 1_000, 420));
        assertEquals(360, Calling.left(1_000, 61_000, 420));
        assertEquals(0, Calling.left(1_000, 421_000, 420));
        assertEquals(0, Calling.left(1_000, 9_999_000, 420));
        assertEquals(420, Calling.left(5_000, 1_000, 420));   // a clock set back does not end it
    }

    @Test
    void theFloatIsPulledTowardTheWater() {
        double[] v = Calling.pull(0, 0, 30, 40, 0.1);
        assertEquals(0.06, v[0], 1e-9);
        assertEquals(0.08, v[1], 1e-9);
        assertArrayEquals(new double[]{0, 0}, Calling.pull(5, 5, 5, 5, 0.1));
        double[] w = Calling.pull(10, 0, -90, 0, 0.07);
        assertEquals(-0.07, w[0], 1e-9);
        assertEquals(0, w[1], 1e-9);
    }

    @Test
    void ymlHasThePoolAndAHardReward() {
        YamlConfiguration y = yml();
        List<Map<?, ?>> things = y.getMapList("sequel.things");
        assertTrue(things.size() >= 8, "sequel.things: " + things.size());
        Set<String> ids = new HashSet<>(), memories = new HashSet<>(), looks = new HashSet<>();
        for (Map<?, ?> t : things) {
            assertTrue(ids.add(String.valueOf(t.get("id"))), "id twice: " + t.get("id"));
            assertNotNull(t.get("name"), t.get("id") + ".name");
            assertTrue(String.valueOf(t.get("name")).contains("이름을 잃은 것"));
            assertTrue(String.valueOf(t.get("memory")).startsWith("…"), t.get("id") + ".memory");
            assertTrue(memories.add(String.valueOf(t.get("memory"))));
            assertTrue(t.get("lore") instanceof List<?> l && !l.isEmpty() && looks.add(String.valueOf(l)), t.get("id") + ".lore");
        }
        List<Long> money = y.getLongList("sequel.reward.money");
        assertEquals(2, money.size());
        assertTrue(money.get(0) >= 150_000 && money.get(1) <= 300_000 && money.get(0) <= money.get(1));
        for (String k : List.of("where.far", "where.near", "where.close", "here", "here-how", "slip", "hurry", "rise", "rise-subtitle", "hold", "back",
                "memory-format", "done", "sunk", "perk.line", "perk.bite"))
            assertTrue(y.isString("sequel." + k), k);
        assertFalse(y.getStringList("sequel.call").isEmpty());
        assertEquals(y.getInt("sequel.need") - 1, y.getStringList("sequel.count").size());
        assertTrue(y.getInt("sequel.need") >= 2 && y.getInt("sequel.window") >= 120 && y.getInt("sequel.perfect-ticks") >= 4);
        double perk = y.getDouble("sequel.perk.rare-chance");
        assertTrue(perk > 0 && perk <= 0.1, "perk.rare-chance");
        assertTrue(y.getInt("sequel.search.min") >= 60 && y.getInt("sequel.search.max") > y.getInt("sequel.search.min"));
        assertTrue(y.getDouble("sequel.radius") < y.getDouble("sequel.close") && y.getDouble("sequel.close") < y.getDouble("sequel.near"));
        // the hint never names a place
        for (String l : y.getStringList("sequel.call")) assertFalse(l.matches(".*\\d.*") || l.contains("{"), l);
    }
}
