package dev.stsv.economy.secret;

import dev.stsv.economy.secret.SequelState.State;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 후속 임무 (Sequel): the week (ISO, Korean time, year ends, a tester's short week), the saved state and how it moves,
 * expiry when the week turns, and the reward range. A fake sequel plays the module's per-second loop on a map.
 */
class SequelTest {

    static final ZoneId KST = SecretModule.KST;

    /** One solver's keys in a map, and the per-second loop of {@link Sequel} without a server. */
    static final class Fake implements SequelState.Store {
        final Map<String, String> kv = new HashMap<>();
        final List<String> log = new ArrayList<>();
        boolean wants;      // offer() would begin now
        boolean finishes;   // tick() completes it

        @Override
        public String get(String key) {
            return kv.get(key);
        }

        @Override
        public void set(String key, String value) {
            kv.put(key, value);
        }

        @Override
        public void remove(String key) {
            kv.remove(key);
        }

        @Override
        public void removePrefix(String prefix) {
            kv.keySet().removeIf(k -> k.startsWith(prefix));
        }

        /** The same order as Sequel.tick1: expiry, then the going mission's tick, else the offer. */
        void second(String week, long now) {
            if (SequelState.expiring(this, week)) log.add("expired " + get("x.item"));
            SequelState.turn(this, week);
            if (SequelState.state(this).live()) {
                log.add("tick");
                if (finishes && SequelState.finish(this, true, now)) log.add("done");
            } else if (SequelState.due(this, week) && wants) {
                set("x.item", "note");
                if (SequelState.offer(this, week, now)) log.add("offered " + week);
            }
        }
    }

    static long at(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST).toInstant().toEpochMilli();
    }

    @Test
    void weekIsTheIsoWeek() {
        assertEquals("2026-W48", SequelState.week(LocalDate.of(2026, 11, 23)));   // a Monday
        assertEquals("2026-W48", SequelState.week(LocalDate.of(2026, 11, 29)));   // its Sunday
        assertEquals("2026-W49", SequelState.week(LocalDate.of(2026, 11, 30)));
        assertEquals("2026-W01", SequelState.week(LocalDate.of(2025, 12, 29)));   // the week-based year, not the calendar year
        assertEquals("2026-W53", SequelState.week(LocalDate.of(2027, 1, 1)));
        assertEquals("2026-W53", SequelState.week(LocalDate.of(2027, 1, 3)));
        assertEquals("2027-W01", SequelState.week(LocalDate.of(2027, 1, 4)));
        assertEquals("2024-W52", SequelState.week(LocalDate.of(2024, 12, 29)));
        assertEquals("2025-W01", SequelState.week(LocalDate.of(2024, 12, 30)));
        assertEquals("2026-W05", SequelState.week(LocalDate.of(2026, 1, 26)));    // two digits: the keys sort
    }

    @Test
    void weekTurnsOnMondayMidnightInKorea() {
        assertEquals("2026-W48", SequelState.week(at(2026, 11, 29, 23, 59), KST));
        assertEquals("2026-W49", SequelState.week(at(2026, 11, 30, 0, 0), KST));
        // Sunday 15:00 UTC is already Monday in Korea
        long utc = ZonedDateTime.of(2026, 11, 29, 15, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli();
        assertEquals("2026-W49", SequelState.week(utc, KST));
        assertEquals("2026-W48", SequelState.week(utc - 1, KST));
        assertEquals("2026-W53", SequelState.week(at(2027, 1, 3, 23, 59), KST));
        assertEquals("2027-W01", SequelState.week(at(2027, 1, 4, 0, 0), KST));
    }

    @Test
    void aTestersWeekIsShortAndNeverLooksReal() {
        long week = 120_000L;
        assertEquals("T0", SequelState.testWeek(0, week));
        assertEquals("T0", SequelState.testWeek(119_999, week));
        assertEquals("T1", SequelState.testWeek(120_000, week));
        long now = at(2026, 11, 23, 12, 0);
        assertEquals(SequelState.testWeek(now, week), SequelState.week(now, KST, true, week));
        assertEquals("2026-W48", SequelState.week(now, KST, false, week));
        assertNotEquals(SequelState.week(now, KST, true, week), SequelState.week(now + week, KST, true, week));
        assertTrue(SequelState.week(now, KST, true, week).startsWith("T"));
        assertEquals("T5", SequelState.testWeek(5, 0));   // a broken setting does not divide by zero
    }

    @Test
    void statesMoveOnlyForward() {
        Fake f = new Fake();
        assertEquals(State.NONE, SequelState.state(f));
        assertTrue(SequelState.due(f, "2026-W48"));
        assertFalse(SequelState.accept(f, 1));                // nothing offered
        assertFalse(SequelState.finish(f, true, 1));          // nothing to finish: no reward
        assertEquals(0, SequelState.count(f));

        assertTrue(SequelState.offer(f, "2026-W48", 1000));
        assertEquals(State.OFFERED, SequelState.state(f));
        assertEquals("2026-W48", f.get("week"));
        assertEquals(1000, SequelState.since(f));
        assertFalse(SequelState.due(f, "2026-W48"));
        assertFalse(SequelState.offer(f, "2026-W48", 2000));  // one at a time
        assertTrue(State.OFFERED.live());

        assertTrue(SequelState.accept(f, 3000));
        assertEquals(State.ACTIVE, SequelState.state(f));
        assertFalse(SequelState.accept(f, 3500));
        SequelState.step(f, 2, 4000);
        assertEquals(2, SequelState.step(f));
        assertEquals(4000, SequelState.since(f));

        assertTrue(SequelState.finish(f, true, 5000));
        assertEquals(State.DONE, SequelState.state(f));
        assertEquals(1, SequelState.count(f));
        assertFalse(SequelState.finish(f, true, 6000));       // not paid twice
        assertEquals(1, SequelState.count(f));
        assertFalse(SequelState.due(f, "2026-W48"));          // done for this week
        assertFalse(State.DONE.live());
        assertEquals(State.NONE, State.of("???"));
        assertEquals("진행 중", State.ACTIVE.word());
    }

    @Test
    void failingCostsOnlyThisWeek() {
        Fake f = new Fake();
        SequelState.offer(f, "2026-W48", 1);
        assertTrue(SequelState.finish(f, false, 2));
        assertEquals(State.FAILED, SequelState.state(f));
        assertEquals(0, SequelState.count(f));
        assertFalse(SequelState.due(f, "2026-W48"));
        assertFalse(SequelState.turn(f, "2026-W49"));         // it was over: nothing to tidy
        assertEquals(State.NONE, SequelState.state(f));
        assertTrue(SequelState.due(f, "2026-W49"));
    }

    @Test
    void aMissionLeftUnfinishedExpiresWithTheWeek() {
        Fake f = new Fake();
        f.set("keep.last", "3");
        f.set("x.place", "10,20");
        SequelState.offer(f, "2026-W48", 1);
        SequelState.accept(f, 2);
        SequelState.step(f, 3, 3);
        assertFalse(SequelState.expiring(f, "2026-W48"));
        assertFalse(SequelState.turn(f, "2026-W48"));         // the same week: untouched
        assertEquals(State.ACTIVE, SequelState.state(f));
        assertEquals("10,20", f.get("x.place"));

        assertTrue(SequelState.expiring(f, "2026-W49"));
        assertEquals(State.ACTIVE, SequelState.shown(f, "2026-W49"));   // still shown as going until it is turned
        assertTrue(SequelState.turn(f, "2026-W49"));          // it was going on: expired
        assertEquals(State.NONE, SequelState.state(f));
        assertNull(f.get("x.place"));
        assertNull(f.get("step"));
        assertEquals("3", f.get("keep.last"));                // what the author keeps survives
        assertEquals("2026-W48", f.get("week"));              // the week it was last offered
        assertEquals(0, SequelState.count(f));                // missing a week costs nothing
        assertFalse(SequelState.turn(f, "2026-W49"));         // asked again: nothing to do
        assertTrue(SequelState.due(f, "2026-W49"));
    }

    @Test
    void lastWeeksResultReadsAsNoneThisWeek() {
        Fake f = new Fake();
        SequelState.offer(f, "2026-W48", 1);
        SequelState.finish(f, true, 2);
        assertEquals(State.DONE, SequelState.shown(f, "2026-W48"));
        assertEquals(State.NONE, SequelState.shown(f, "2026-W49"));
        assertEquals(State.DONE, SequelState.state(f));       // shown() changed nothing
    }

    @Test
    void stoppingKeepsTheWeekAndTheCount() {
        Fake f = new Fake();
        SequelState.offer(f, "2026-W47", 1);
        SequelState.finish(f, true, 2);
        SequelState.turn(f, "2026-W48");
        SequelState.offer(f, "2026-W48", 3);
        f.set("x.item", "note");
        assertTrue(SequelState.stop(f));                      // 서버초기화: the old solver's mission ends without an outcome
        assertEquals(State.NONE, SequelState.state(f));
        assertNull(f.get("x.item"));
        assertEquals(1, SequelState.count(f));
        assertFalse(SequelState.due(f, "2026-W48"));          // this week was used
        assertFalse(SequelState.stop(f));
    }

    @Test
    void theLoopOffersOnceAWeekAndExpires() {
        Fake f = new Fake();
        f.second("2026-W48", 1);
        assertTrue(f.log.isEmpty());                          // offer() did not want to yet
        f.wants = true;
        f.second("2026-W48", 2);
        f.second("2026-W48", 3);
        assertEquals(List.of("offered 2026-W48", "tick"), f.log);
        f.log.clear();
        f.second("2026-W49", 4);                              // never finished: expires with its notes readable, then a new one may come
        assertEquals(List.of("expired note", "offered 2026-W49"), f.log);
        f.log.clear();
        f.finishes = true;
        f.second("2026-W49", 5);
        f.second("2026-W49", 6);
        f.second("2026-W49", 7);
        assertEquals(List.of("tick", "done"), f.log);         // done: nothing more this week
        assertEquals(1, SequelState.count(f));
        f.log.clear();
        f.second("2026-W50", 8);
        assertEquals(List.of("offered 2026-W50"), f.log);     // no "expired": it was finished
        // a tester's weeks
        Fake t = new Fake();
        t.wants = true;
        t.finishes = true;
        for (long now = 0; now < 360_000; now += 1000) t.second(SequelState.testWeek(now, 120_000), now);
        assertEquals(3, SequelState.count(t));
    }

    @Test
    void rewardRangeIsParsed() {
        assertArrayEquals(new long[]{100_000, 300_000}, SequelState.range(List.of(100_000, 300_000)));
        assertArrayEquals(new long[]{100_000, 300_000}, SequelState.range(List.of(300_000, 100_000)));   // swapped
        assertArrayEquals(new long[]{150_000, 150_000}, SequelState.range(150_000));
        assertArrayEquals(new long[]{150_000, 150_000}, SequelState.range(List.of(150_000)));
        assertArrayEquals(new long[]{100_000, 300_000}, SequelState.range("100000-300000"));
        assertArrayEquals(new long[]{100_000, 300_000}, SequelState.range("100,000 ~ 300,000"));
        assertArrayEquals(new long[]{0, 0}, SequelState.range(null));
        assertArrayEquals(new long[]{0, 0}, SequelState.range("많이"));
        assertArrayEquals(new long[]{0, 5}, SequelState.range(List.of(-10, 5)));
        YamlConfiguration y = new YamlConfiguration();
        assertTrue(loads(y, "reward:\n  money: [100000, 300000]\n  items: [\"economy:x 2\"]\n"));
        assertArrayEquals(new long[]{100_000, 300_000}, SequelState.range(y.get("reward.money")));
        assertArrayEquals(new long[]{0, 0}, SequelState.range(y.get("reward.nothing")));
    }

    static boolean loads(YamlConfiguration y, String text) {
        try {
            y.loadFromString(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void rewardIsRolledWithinBounds() {
        long[] r = {100_000, 300_000};
        assertEquals(100_000, SequelState.pick(r, 0.0, 1000));
        assertEquals(300_000, SequelState.pick(r, 0.999999999, 1000));
        assertEquals(300_000, SequelState.pick(r, 1.0, 1));
        assertEquals(100_000, SequelState.pick(r, -3, 1));
        for (int i = 0; i <= 1000; i++) {
            long v = SequelState.pick(r, i / 1001.0, 1000);
            assertTrue(v >= r[0] && v <= r[1], "in range: " + v);
            assertEquals(0, v % 1000);
        }
        long[] odd = {100_500, 100_900};                      // rounding never goes under the minimum
        for (int i = 0; i < 100; i++) assertTrue(SequelState.pick(odd, i / 100.0, 1000) >= 100_500);
        assertEquals(7, SequelState.pick(new long[]{7, 7}, 0.5, 1000));
        assertEquals(0, SequelState.pick(new long[]{0, 0}, 0.5, 1000));
        long[] small = {1, 3};                                // every value can come up
        assertEquals(1, SequelState.pick(small, 0.1, 1));
        assertEquals(2, SequelState.pick(small, 0.5, 1));
        assertEquals(3, SequelState.pick(small, 0.9, 1));
    }

    @Test
    void commonSettingsHaveTheSequelBlock() throws Exception {
        Path f = Path.of(System.getProperty("stsv.content")).resolve("economy").resolve("secrets").resolve("common.yml");
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(Files.readString(f));
        assertTrue(y.isBoolean("sequel.enabled"));
        assertTrue(y.getLong("sequel.test-week-seconds") >= 10);
        assertTrue(y.getLong("sequel.round") >= 1);
        assertTrue(y.getString("sequel.done", "").contains("{reward}"));
        assertFalse(y.getString("sequel.failed", "").isEmpty());
    }
}
