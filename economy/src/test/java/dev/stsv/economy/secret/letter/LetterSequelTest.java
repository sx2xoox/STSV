package dev.stsv.economy.secret.letter;

import dev.stsv.economy.secret.Memory;
import dev.stsv.economy.secret.letter.Yesterday.Facts;
import dev.stsv.economy.secret.letter.Yesterday.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 어제의 편지 (the 후속 임무 of 내일의 나): which riddle a week gets, what the sales say, how warm the answer is. */
class LetterSequelTest {

    private static final List<Kind> ALL = List.of(Kind.values());

    @Test
    void atLeastEightKindsAndEachHasItsWord() {
        assertTrue(Kind.values().length >= 8);
        Set<String> keys = new HashSet<>();
        for (Kind k : Kind.values()) {
            assertTrue(keys.add(k.key));
            assertEquals(k, Kind.of(k.key).orElseThrow());
        }
        assertTrue(Kind.of("nothing").isEmpty());
    }

    @Test
    void onlyRiddlesTheRecordsAllow() {
        Facts nothing = new Facts(false, true, false, false, false, null, null, null, null);
        assertEquals(List.of(Kind.BIRTH), Yesterday.possible(nothing, ALL));
        Facts seller = new Facts(true, true, false, false, false, "economy:ruby", null, "economy:ruby", "economy:ruby");
        List<Kind> can = Yesterday.possible(seller, ALL);
        assertTrue(can.containsAll(List.of(Kind.QUIT, Kind.BIRTH, Kind.SELL_MOST, Kind.CARRY_LAST_QUIT)));
        assertFalse(can.contains(Kind.LAND));
        assertFalse(can.contains(Kind.CARRY_MOST_LAND));
        assertFalse(can.contains(Kind.CARRY_WEEK_BIRTH), "nothing sold this week");
        assertFalse(can.contains(Kind.SELL_OLDEST), "the oldest is the most sold: the same riddle");
        assertFalse(can.contains(Kind.WALK), "the two places are the same spot");
        Facts full = new Facts(true, true, true, true, true, "a", "b", "c", "d");
        assertEquals(ALL, Yesterday.possible(full, ALL));
        assertEquals(List.of(Kind.QUIT), Yesterday.possible(full, List.of(Kind.QUIT)), "only the kinds the settings allow");
    }

    @Test
    void aWeekAlwaysGetsTheSameRiddle() {
        for (int w = 1; w <= 52; w++) {
            String week = String.format("2026-W%02d", w);
            assertEquals(Yesterday.pick(42L, week, ALL, List.of()), Yesterday.pick(42L, week, ALL, List.of()));
        }
        assertNull(Yesterday.pick(1L, "2026-W01", List.of(), List.of()));
    }

    @Test
    void weeksAndPlayersDiffer() {
        Set<Kind> seen = new HashSet<>();
        for (int w = 1; w <= 52; w++) seen.add(Yesterday.pick(7L, String.format("2026-W%02d", w), ALL, List.of()));
        assertTrue(seen.size() >= 8, "a year shows most kinds: " + seen);
        int same = 0;
        for (int w = 1; w <= 52; w++) {
            String week = String.format("2026-W%02d", w);
            if (Yesterday.pick(7L, week, ALL, List.of()) == Yesterday.pick(8L, week, ALL, List.of())) same++;
        }
        assertTrue(same < 26, "two players do not get the same riddles: " + same);
    }

    @Test
    void recentRiddlesDoNotComeBack() {
        List<String> recent = new ArrayList<>();
        for (int w = 1; w <= 60; w++) {
            List<Kind> avoid = new ArrayList<>();
            for (String r : recent) avoid.add(Kind.of(r).orElseThrow());
            Kind k = Yesterday.pick(99L, "2027-W" + w, ALL, avoid);
            assertFalse(avoid.contains(k), "week " + w);
            recent = Yesterday.remember(recent, k.key, 4);
            assertTrue(recent.size() <= 4);
        }
    }

    @Test
    void whenEverythingIsRecentTheOldestComesBack() {
        List<Kind> two = List.of(Kind.QUIT, Kind.BIRTH);
        assertEquals(Kind.QUIT, Yesterday.pick(5L, "w", two, List.of(Kind.QUIT, Kind.BIRTH)), "the newest stays away");
        assertEquals(Kind.BIRTH, Yesterday.pick(5L, "w", List.of(Kind.BIRTH), List.of(Kind.BIRTH)), "one option is always that one");
        assertEquals(Kind.BIRTH, Yesterday.pick(5L, "w", two, List.of(Kind.LAND, Kind.QUIT)), "recent ones that are not options do not count");
    }

    @Test
    void rememberKeepsTheNewestLast() {
        assertEquals(List.of("b", "c", "a"), Yesterday.remember(List.of("a", "b", "c"), "a", 4));
        assertEquals(List.of("c", "d"), Yesterday.remember(List.of("a", "b", "c"), "d", 2));
        assertEquals(List.of("a", "b"), Yesterday.words("a, b,,"));
        assertEquals(List.of(), Yesterday.words(null));
    }

    @Test
    void whatTheSalesSay() {
        long now = 1_000_000_000L, week = now - 7L * 86_400_000L;
        List<Memory.Sale> sales = List.of(   // newest first
                new Memory.Sale("minecraft:wheat", 3, now - 10),
                new Memory.Sale("economy:ruby", 5, now - 20),
                new Memory.Sale("economy:rice", 40, week - 5),
                new Memory.Sale("economy:ruby", 2, week - 6),
                new Memory.Sale("minecraft:cod", 1, week - 7));
        Yesterday.Sold s = Yesterday.sold(sales, week);
        assertEquals("economy:rice", s.most());
        assertEquals(40, s.mostCount());
        assertEquals("economy:ruby", s.weekMost());
        assertEquals(5, s.weekCount());
        assertEquals("minecraft:wheat", s.last());
        assertEquals("minecraft:cod", s.oldest());
        Yesterday.Sold none = Yesterday.sold(List.of(), week);
        assertNull(none.most());
        assertNull(none.weekMost());
        assertNull(none.last());
    }

    @Test
    void theAnswerGetsWarmer() {
        List<Integer> after = List.of(4, 10);
        assertEquals(0, Yesterday.warmth(0, after));
        assertEquals(0, Yesterday.warmth(3, after));
        assertEquals(1, Yesterday.warmth(4, after));
        assertEquals(1, Yesterday.warmth(9, after));
        assertEquals(2, Yesterday.warmth(10, after));
        assertEquals(2, Yesterday.warmth(500, List.of(1, 2, 3)), "never past the last pool");
        assertEquals(0, Yesterday.warmth(500, List.of()));
    }

    @Test
    void repliesTakeTurns() {
        assertEquals(0, Yesterday.turn(0, 6));
        assertEquals(5, Yesterday.turn(5, 6));
        assertEquals(0, Yesterday.turn(6, 6));
        assertNotEquals(Yesterday.turn(7, 6), Yesterday.turn(8, 6));
        assertEquals(-1, Yesterday.turn(3, 0));
    }

    @Test
    void aWalkNeedsTwoPlaces() {
        Memory.Place a = new Memory.Place("w", 0, 64, 0);
        assertFalse(Yesterday.apart(a, new Memory.Place("w", 30, 64, 30), 48));
        assertTrue(Yesterday.apart(a, new Memory.Place("w", 48, 64, 0), 48));
        assertTrue(Yesterday.apart(a, new Memory.Place("other", 0, 64, 0), 48));
        assertFalse(Yesterday.apart(a, null, 48));
    }
}
