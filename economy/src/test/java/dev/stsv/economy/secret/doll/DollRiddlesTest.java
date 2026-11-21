package dev.stsv.economy.secret.doll;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 낡은 인형's riddles: each names the right people (ties all count), and a told riddle can always be answered. */
class DollRiddlesTest {

    static UUID id(int n) {
        return new UUID(0, n);
    }

    static Riddles.Fact f(int n, String name, int land, long session, long first, double fish, int y, double dist, long money, int friend) {
        return new Riddles.Fact(id(n), name, land, session, first, fish, y, dist, money, friend);
    }

    @Test
    void eachRiddleReadsItsRecord() {
        List<Riddles.Fact> fs = List.of(
                f(1, "민수", 12, 1000, 50, 0, 64, 10, 5_000, 2),
                f(2, "Sam", 0, 3000, 10, 88.5, -40, Double.POSITIVE_INFINITY, 90_000, -1),
                f(3, "하늘바다별", 3, 2000, 90, 41.0, 200, 300, 0, 0),
                f(4, "지아", 12, 500, 70, 88.5, 12, 300, 100, 1));
        assertEquals(Set.of(id(1), id(4)), Riddles.answers(Riddles.Kind.LAND, fs));      // a tie: both
        assertEquals(Set.of(id(2)), Riddles.answers(Riddles.Kind.LATE, fs));
        assertEquals(Set.of(id(4)), Riddles.answers(Riddles.Kind.EARLY, fs));
        assertEquals(Set.of(id(3)), Riddles.answers(Riddles.Kind.FRIEND, fs));           // rank 0 = closest
        assertEquals(Set.of(id(2), id(4)), Riddles.answers(Riddles.Kind.FISH, fs));
        assertEquals(Set.of(id(3)), Riddles.answers(Riddles.Kind.LONG, fs));             // 5 letters
        assertEquals(Set.of(id(2)), Riddles.answers(Riddles.Kind.DEEP, fs));
        assertEquals(Set.of(id(3)), Riddles.answers(Riddles.Kind.HIGH, fs));
        assertEquals(Set.of(id(2)), Riddles.answers(Riddles.Kind.FAR, fs));              // another world is farthest
        assertEquals(Set.of(id(2)), Riddles.answers(Riddles.Kind.RICH, fs));
        assertEquals(Set.of(id(3)), Riddles.answers(Riddles.Kind.POOR, fs));
        assertEquals(Set.of(id(2)), Riddles.answers(Riddles.Kind.OLD, fs));
        assertEquals(Set.of(id(3)), Riddles.answers(Riddles.Kind.NEW, fs));
        assertEquals(Set.of(id(1), id(2), id(3), id(4)), Riddles.answers(Riddles.Kind.STRANGER, fs));
        assertTrue(Riddles.answers(Riddles.Kind.HOME, fs).isEmpty());
    }

    @Test
    void riddlesWithoutDataAreNeverChosen() {
        // nobody has land, nobody fished, nobody is a friend of the first owner, the holder's distance is unknown
        List<Riddles.Fact> fs = List.of(f(1, "a", 0, 1, 1, 0, 1, -1, 1, -1), f(2, "bb", 0, 2, 2, 0, 2, -1, 2, -1));
        for (Riddles.Kind k : List.of(Riddles.Kind.LAND, Riddles.Kind.FISH, Riddles.Kind.FRIEND, Riddles.Kind.FAR))
            assertTrue(Riddles.answers(k, fs).isEmpty(), k.name());
        for (long seed = 0; seed < 500; seed++) {
            Riddles.Kind k = Riddles.choose(seed, EnumSet.noneOf(Riddles.Kind.class), fs);
            assertFalse(Riddles.answers(k, fs).isEmpty(), k.name());
        }
        // nobody left to ask: any stranger (and then whoever comes)
        assertEquals(Riddles.Kind.STRANGER, Riddles.choose(1, EnumSet.noneOf(Riddles.Kind.class), List.of()));
        assertEquals(Riddles.Kind.STRANGER, Riddles.choose(1, EnumSet.copyOf(Riddles.POOL), fs));
    }

    @Test
    void aRunNeverRepeatsARiddleAndUsesThemAll() {
        Random r = new Random(7);
        Map<Riddles.Kind, Integer> seen = new HashMap<>();
        for (int run = 0; run < 2000; run++) {
            List<Riddles.Fact> fs = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                fs.add(f(i, "p" + "x".repeat(r.nextInt(8)), r.nextInt(3) * r.nextInt(20), r.nextInt(5000), 1 + r.nextInt(5000), r.nextInt(3) == 0 ? 0 : r.nextInt(900) / 10.0,
                        r.nextInt(300) - 60, r.nextInt(5) == 0 ? Double.POSITIVE_INFINITY : r.nextInt(3000), r.nextInt(1_000_000), r.nextInt(4) == 0 ? r.nextInt(8) : -1));
            Set<Riddles.Kind> used = EnumSet.noneOf(Riddles.Kind.class);
            for (int step = 0; step < Chain.HANDS - 1; step++) {
                Riddles.Kind k = Riddles.choose(run * 31L + step, used, fs);
                assertFalse(used.contains(k));
                assertFalse(Riddles.answers(k, fs).isEmpty());
                used.add(k);
                seen.merge(k, 1, Integer::sum);
            }
        }
        for (Riddles.Kind k : Riddles.POOL) assertTrue(seen.getOrDefault(k, 0) > 200, k + " " + seen);
        assertFalse(seen.containsKey(Riddles.Kind.STRANGER));   // a dozen players always have something to ask
    }
}
