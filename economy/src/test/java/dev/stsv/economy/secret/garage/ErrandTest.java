package dev.stsv.economy.secret.garage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 폐차고 후속 임무 「도윤 모터스」: the week's note is the same after a restart and never one of the recent ones, a stretch
 * is read off the road's named points, each rule ends a driven stretch when it is broken and only then, the lights of
 * his line never run three to a side, the photo's list is capped, and the file's tasks are whole.
 */
class ErrandTest {

    static final UUID U = UUID.fromString("3b0a5a0e-6c1f-4f8e-9a53-0d7b6f4f2a11");

    static Errand.Rules rules(int bumps, int limit, double lo, double hi, int grace, List<double[]> gates, int misses, boolean stop) {
        return new Errand.Rules(bumps, limit, lo, hi, grace, gates, 1.2, misses, stop, 10, 5, 9);
    }

    static Errand.Rules plain() {
        return rules(0, 0, 0, 0, 0, List.of(), 0, false);
    }

    /** Drives a stretch at a steady speed down the road's middle; the verdict that ends it (GO when none did). */
    static Errand.Verdict drive(Errand.Rules r, int length, Errand.Progress st, double from, double to, double speed) {
        for (double prog = from; prog <= to; prog += speed / 20) {
            Errand.Verdict v = Errand.step(r, length, st, new Errand.Sample(prog, 0, speed, false));
            if (v != Errand.Verdict.GO) return v;
        }
        return Errand.Verdict.GO;
    }

    // ------------------------------------------------------------------------------------------
    // this week's note
    // ------------------------------------------------------------------------------------------

    @Test
    void theWeeksTaskIsTheSameAgainAndNeverARecentOne() {
        List<String> ids = List.of("clean-home", "flowers", "curfew", "cruise", "stop", "line", "part", "slow-bend");
        long seed = Errand.seed("2026-W48", U);
        assertEquals(seed, Errand.seed("2026-W48", U));
        assertNotEquals(seed, Errand.seed("2026-W49", U));
        String first = Errand.pick(ids, List.of(), seed);
        List<String> shuffled = new ArrayList<>(ids);
        java.util.Collections.reverse(shuffled);
        assertEquals(first, Errand.pick(shuffled, List.of(), seed), "the file's order does not matter");
        // forty weeks in a row: never one of the last four
        String recent = "";
        Set<String> seen = new HashSet<>();
        for (int w = 1; w <= 40; w++) {
            List<String> last = Errand.list(recent, ",");
            String id = Errand.pick(ids, last, Errand.seed("2026-W" + w, U));
            assertNotNull(id);
            assertFalse(last.contains(id), "week " + w + " repeats " + id);
            seen.add(id);
            recent = Errand.remember(recent, id, 4);
            assertTrue(Errand.list(recent, ",").size() <= 4);
        }
        assertEquals(ids.size(), seen.size(), "every task comes round");
    }

    @Test
    void aSmallPoolLetsTheOldestBackInFirst() {
        assertNull(Errand.pick(List.of(), List.of("a"), 7));
        assertEquals("a", Errand.pick(List.of("a"), List.of("a"), 7));
        // both are recent: the older one (a) comes back, not the one just done (b)
        assertEquals("a", Errand.pick(List.of("a", "b"), List.of("a", "b"), 3));
        assertEquals("b", Errand.pick(List.of("a", "b"), List.of("b", "a"), 3));
        assertEquals("b,c,a", Errand.remember("a,b,c", "a", 4));
        assertEquals("c,d", Errand.remember("a,b,c", "d", 2));
    }

    // ------------------------------------------------------------------------------------------
    // the stretch
    // ------------------------------------------------------------------------------------------

    @Test
    void pointsOfARoadByName() {
        int n = 400, bend = 150, garage = 370;
        assertEquals(0, Errand.anchor("start", n, bend, garage));
        assertEquals(4, Errand.anchor("start+4", n, bend, garage));
        assertEquals(399, Errand.anchor("end", n, bend, garage));
        assertEquals(393, Errand.anchor(" End - 6 ", n, bend, garage));
        assertEquals(75, Errand.anchor("bend-75", n, bend, garage));
        assertEquals(0, Errand.anchor("bend-900", n, bend, garage), "kept inside the road");
        assertEquals(399, Errand.anchor("garage+900", n, bend, garage));
        assertEquals(370, Errand.anchor("garage", n, bend, garage));
        assertEquals(-1, Errand.anchor("bend-10", n, -1, -1), "another road has no bend");
        assertEquals(-1, Errand.anchor("garage", n, -1, -1));
        assertEquals(-1, Errand.anchor("corner", n, bend, garage));
        assertEquals(-1, Errand.anchor("start+x", n, bend, garage));
        assertEquals(-1, Errand.anchor(null, n, bend, garage));
    }

    @Test
    void aStretchRunsEitherWay() {
        List<double[]> pts = GarageTest.road(new double[]{1, 0, 300});
        Errand.Course out = new Errand.Course("w", "d", pts, new int[301], 20, 220), back = new Errand.Course("w", "d", pts, new int[301], 220, 20);
        assertEquals(1, out.dir());
        assertEquals(-1, back.dir());
        assertEquals(200, out.length());
        assertEquals(200, back.length());
        assertEquals(30, out.prog(50), 1e-9);
        assertEquals(170, back.prog(50), 1e-9);
        assertEquals(-10, back.prog(230), 1e-9, "before its start");
        assertEquals(50, out.at(30), 1e-9);
        assertEquals(50, back.at(170), 1e-9);
        assertEquals(26, Errand.limit(200, 9.5, 4.5));
    }

    // ------------------------------------------------------------------------------------------
    // the rules
    // ------------------------------------------------------------------------------------------

    @Test
    void aCleanStretchEndsAtItsEndOrAtTheFirstBump() {
        Errand.Progress st = new Errand.Progress();
        assertEquals(Errand.Verdict.PASS, drive(plain(), 100, st, 12, 101, 12));
        st = new Errand.Progress();
        assertEquals(Errand.Verdict.GO, Errand.step(plain(), 100, st, new Errand.Sample(20, 0, 12, false)));
        assertEquals(Errand.Verdict.BUMP, Errand.step(plain(), 100, st, new Errand.Sample(21, 0, 3, true)));
        // one bump allowed: a bump held over several ticks is one bump
        Errand.Rules one = rules(1, 0, 0, 0, 0, List.of(), 0, false);
        st = new Errand.Progress();
        for (int i = 0; i < 5; i++) assertEquals(Errand.Verdict.GO, Errand.step(one, 100, st, new Errand.Sample(20, 0, 3, true)));
        assertEquals(Errand.Verdict.GO, Errand.step(one, 100, st, new Errand.Sample(22, 0, 8, false)));
        assertEquals(Errand.Verdict.BUMP, Errand.step(one, 100, st, new Errand.Sample(23, 0, 3, true)));
        // bumps that do not matter
        Errand.Rules any = rules(-1, 0, 0, 0, 0, List.of(), 0, false);
        st = new Errand.Progress();
        for (int i = 0; i < 9; i++) assertEquals(Errand.Verdict.GO, Errand.step(any, 100, st, new Errand.Sample(20 + i, 0, 3, i % 2 == 0)));
    }

    @Test
    void aTimeLimitAndTheRoadsEdge() {
        Errand.Rules timed = rules(-1, Errand.limit(200, 10, 4) * 20, 0, 0, 0, List.of(), 0, false);
        assertEquals(Errand.Verdict.PASS, drive(timed, 200, new Errand.Progress(), 12, 201, 11));
        assertEquals(Errand.Verdict.LATE, drive(timed, 200, new Errand.Progress(), 12, 201, 7));
        // off the road for three seconds, not for two
        Errand.Progress st = new Errand.Progress();
        for (int i = 0; i < 40; i++) assertEquals(Errand.Verdict.GO, Errand.step(plain(), 100, st, new Errand.Sample(30, 12, 5, false)));
        assertEquals(Errand.Verdict.GO, Errand.step(plain(), 100, st, new Errand.Sample(30, 2, 5, false)));
        Errand.Verdict v = Errand.Verdict.GO;
        for (int i = 0; i < 61 && v == Errand.Verdict.GO; i++) v = Errand.step(plain(), 100, st, new Errand.Sample(30, -12, 5, false));
        assertEquals(Errand.Verdict.OFF, v);
    }

    @Test
    void theNeedleMustStayInItsBandOnceThere() {
        Errand.Rules band = rules(-1, 0, 8, 10.5, 50, List.of(), 0, false);
        assertEquals(Errand.Verdict.PASS, drive(band, 150, new Errand.Progress(), 12, 151, 9.2));
        // pulling away from standing: not counted until the band is reached
        Errand.Progress st = new Errand.Progress();
        for (int i = 0; i < 80; i++) assertEquals(Errand.Verdict.GO, Errand.step(band, 150, st, new Errand.Sample(12 + i * 0.1, 0, i * 0.1, false)));
        assertFalse(st.settled);
        assertEquals(Errand.Verdict.GO, Errand.step(band, 150, st, new Errand.Sample(21, 0, 9, false)));
        assertTrue(st.settled);
        // a slip shorter than the grace is forgiven
        for (int i = 0; i < 50; i++) assertEquals(Errand.Verdict.GO, Errand.step(band, 150, st, new Errand.Sample(22, 0, 12, false)));
        assertEquals(Errand.Verdict.GO, Errand.step(band, 150, st, new Errand.Sample(22, 0, 9, false)));
        Errand.Verdict v = Errand.Verdict.GO;
        for (int i = 0; i < 52 && v == Errand.Verdict.GO; i++) v = Errand.step(band, 150, st, new Errand.Sample(23, 0, 12, false));
        assertEquals(Errand.Verdict.FAST, v);
        // too slow for too long
        st = new Errand.Progress();
        Errand.step(band, 150, st, new Errand.Sample(20, 0, 9, false));
        v = Errand.Verdict.GO;
        for (int i = 0; i < 52 && v == Errand.Verdict.GO; i++) v = Errand.step(band, 150, st, new Errand.Sample(23, 0, 5, false));
        assertEquals(Errand.Verdict.SLOW, v);
        // someone who never gets there is held to it five seconds in
        st = new Errand.Progress();
        v = Errand.Verdict.GO;
        int ticks = 0;
        while (v == Errand.Verdict.GO && ticks++ < 400) v = Errand.step(band, 150, st, new Errand.Sample(15, 0, 14, false));
        assertEquals(Errand.Verdict.FAST, v);
        assertTrue(ticks > 100 && ticks < 160, "ticks " + ticks);
    }

    @Test
    void theLightsOfHisLine() {
        List<double[]> gates = Errand.gates(95, 26, 12, 1.6, 42);
        assertEquals(gates.size(), Errand.gates(95, 26, 12, 1.6, 42).size());
        assertEquals(6, gates.size());   // 26, 38, 50, 62, 74, 86 (none in the last 8)
        assertEquals(26, gates.getFirst()[0], 1e-9);
        assertTrue(gates.getLast()[0] <= 95 - 8);
        for (long seed = 0; seed < 200; seed++) {
            List<double[]> l = Errand.gates(600, 26, 12, 1.6, seed);
            for (int i = 0; i < l.size(); i++) {
                assertEquals(1.6, Math.abs(l.get(i)[1]), 1e-9);
                if (i >= 2) assertFalse(l.get(i)[1] == l.get(i - 1)[1] && l.get(i)[1] == l.get(i - 2)[1], "three to a side (seed " + seed + ")");
            }
        }
        assertTrue(Errand.gates(600, 26, 0, 1.6, 1).isEmpty());
        // driven through every light
        Errand.Rules r = rules(-1, 0, 0, 0, 0, gates, 1, false);
        Errand.Progress st = new Errand.Progress();
        Errand.Verdict v = Errand.Verdict.GO;
        int next = 0;
        for (double prog = 12; prog <= 96 && v == Errand.Verdict.GO; prog += 0.5) {
            double lateral = next < gates.size() ? gates.get(next)[1] + 0.4 : 0;
            v = Errand.step(r, 95, st, new Errand.Sample(prog, lateral, 10, false));
            if (st.hit) next++;
        }
        assertEquals(Errand.Verdict.PASS, v);
        assertEquals(6, st.passed);
        assertEquals(0, st.missed);
        // down the middle: every light is missed, the second miss ends it
        st = new Errand.Progress();
        assertEquals(Errand.Verdict.GATE, drive(r, 95, st, 12, 96, 10));
        assertEquals(2, st.missed);
        // on the wrong side of one light only: forgiven
        st = new Errand.Progress();
        v = Errand.Verdict.GO;
        next = 0;
        for (double prog = 12; prog <= 96 && v == Errand.Verdict.GO; prog += 0.5) {
            double lateral = next < gates.size() ? gates.get(next)[1] * (next == 2 ? -1 : 1) : 0;
            v = Errand.step(r, 95, st, new Errand.Sample(prog, lateral, 10, false));
            if (st.hit || st.miss) next++;
        }
        assertEquals(Errand.Verdict.PASS, v);
        assertEquals(1, st.missed);
    }

    @Test
    void stoppingAtTheLine() {
        Errand.Rules stop = rules(0, 0, 0, 0, 0, List.of(), 0, true);
        // comes fast, stands still inside the box for a second
        Errand.Progress st = new Errand.Progress();
        assertEquals(Errand.Verdict.GO, drive(stop, 60, st, 12, 56, 12));
        assertTrue(st.reached);
        Errand.Verdict v = Errand.Verdict.GO;
        for (int i = 0; i < 20 && v == Errand.Verdict.GO; i++) v = Errand.step(stop, 60, st, new Errand.Sample(57, 0, 0, false));
        assertEquals(Errand.Verdict.PASS, v);
        // rolls over the line
        st = new Errand.Progress();
        assertEquals(Errand.Verdict.OVER, drive(stop, 60, st, 12, 70, 12));
        // crept up to it: standing there does not count, and they are told
        st = new Errand.Progress();
        assertEquals(Errand.Verdict.GO, drive(stop, 60, st, 12, 57, 4));
        boolean told = false;
        for (int i = 0; i < 60; i++) {
            assertEquals(Errand.Verdict.GO, Errand.step(stop, 60, st, new Errand.Sample(57, 0, 0, false)));
            told |= st.tooGentle;
        }
        assertTrue(told);
        // standing still before the box is nothing
        st = new Errand.Progress();
        drive(stop, 60, st, 12, 40, 12);
        for (int i = 0; i < 60; i++) assertEquals(Errand.Verdict.GO, Errand.step(stop, 60, st, new Errand.Sample(40, 0, 0, false)));
    }

    // ------------------------------------------------------------------------------------------
    // the photo, the file
    // ------------------------------------------------------------------------------------------

    @Test
    void theBackOfThePhotoKeepsTheLastFew() {
        String s = "";
        for (int i = 1; i <= 8; i++) s = Errand.stick(s, "2026.11." + (10 + i), "task" + i, 6);
        List<Errand.Done> done = Errand.stuck(s);
        assertEquals(6, done.size());
        assertEquals("task3", done.getFirst().id());
        assertEquals("task8", done.getLast().id());
        assertEquals("2026.11.18", done.getLast().date());
        assertTrue(Errand.stuck("").isEmpty());
        assertTrue(Errand.stuck("broken;;|x;y|").isEmpty());
        assertEquals(1, Errand.stuck(Errand.stick("", "d;1|", "a|b;c", 6)).size(), "separators cannot be smuggled in");
    }

    @Test
    void theFilesTasksAreWhole() {
        YamlConfiguration y = GarageTest.yml();
        ConfigurationSection q = y.getConfigurationSection("sequel");
        assertNotNull(q);
        assertTrue(q.getBoolean("enabled"));
        List<?> money = q.getList("reward.money");
        assertEquals(List.of(150000, 300000), money, "HARD: 150,000 ~ 300,000 G");
        assertTrue(q.isString("lines.fail"));
        ConfigurationSection tasks = q.getConfigurationSection("tasks");
        assertNotNull(tasks);
        assertTrue(tasks.getKeys(false).size() >= 8, "a pool of eight or more");
        int n = 600, bend = 200, garage = 570;
        Set<String> stickers = new HashSet<>();
        for (String id : tasks.getKeys(false)) {
            ConfigurationSection t = tasks.getConfigurationSection(id);
            assertNotNull(t, id);
            assertFalse(id.contains(",") || id.contains(";") || id.contains("|"), id);
            assertFalse(t.getStringList("note").isEmpty(), id + " note");
            for (String key : List.of("goal", "after", "sticker")) assertFalse(t.getString(key, "").isBlank(), id + " " + key);
            assertTrue(stickers.add(t.getString("sticker")), id + " sticker is another task's");
            boolean part = "part".equals(t.getString("kind", "drive")), other = "other".equals(t.getString("road", "home"));
            if (other) {
                assertTrue(t.isString("goal-home"), id + " needs its -home wording");
                if (String.join(" ", t.getStringList("note")).contains("{road}")) assertTrue(t.isList("note-home"), id + " needs a note-home");
                assertFalse(t.getString("goal-home", "").contains("{road}"), id);
            }
            if (part) {
                assertTrue(Errand.anchor(t.getString("spot"), n, bend, garage) >= 0, id);
                if (other) assertTrue(Errand.anchor(t.getString("other-spot"), n, -1, -1) >= 0, id);
                continue;
            }
            // the stretch on the garage's own road (every task can be driven there), and on another road
            int a = Errand.anchor(t.getString("from"), n, bend, garage), b = Errand.anchor(t.getString("to"), n, bend, garage);
            assertTrue(a >= 0 && b >= 0 && Math.abs(b - a) >= q.getInt("min-length"), id + " " + a + " → " + b);
            if (other) assertTrue(Errand.anchor(t.getString("other-from"), n, -1, -1) >= 0 && Errand.anchor(t.getString("other-to"), n, -1, -1) >= 0, id);
            List<Double> band = t.getDoubleList("band");
            assertTrue(band.isEmpty() || (band.size() == 2 && band.get(0) < band.get(1) && band.get(1) < 13.5), id + " band");
            if (t.getDouble("speed") > 0) assertTrue(t.getDouble("speed") < 13.5, id + ": slower than the car's own top speed");
            if (t.getBoolean("stop")) assertTrue(t.getDouble("reach") < 13.5 && b != a, id);
            if (t.getString("goal", "").contains("{seconds}")) assertTrue(t.getDouble("speed") > 0, id);
            if (t.getString("goal", "").contains("{lo}")) assertEquals(2, band.size(), id);
        }
    }
}
