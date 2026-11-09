package dev.stsv.economy.rank;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The weekly ranking: earnings are worth minus the week's start, and money handed between players is nobody's earnings. */
class WeekBasesTest {

    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

    @Test
    void handedMoneyIsNotEarnings() {
        WeekBases w = new WeekBases();
        long worthA = 10_000, worthB = 1_000;
        boolean[] created = {false};
        assertEquals(10_000, w.startOf(a, worthA, created));
        assertTrue(created[0]);
        w.startOf(b, worthB, null);

        worthA += 2_000;   // sold to the shop: earned
        assertEquals(2_000, w.earned(a, worthA));

        // A pays B 5,000 (/송금): A's worth falls, B's rises, neither earned or lost it
        worthA -= 5_000;
        w.shift(a, -5_000);
        worthB += 5_000;
        w.shift(b, 5_000);
        assertEquals(2_000, w.earned(a, worthA));
        assertEquals(0, w.earned(b, worthB));

        // and back again, any number of times: still nothing
        for (int i = 0; i < 10; i++) {
            worthB -= 5_000;
            w.shift(b, -5_000);
            worthA += 5_000;
            w.shift(a, 5_000);
        }
        assertEquals(2_000, w.earned(a, worthA));
        assertEquals(0, w.earned(b, worthB));
    }

    @Test
    void someoneNewThisWeekStartsFromWhatTheyHaveAfterTheMoney() {
        WeekBases w = new WeekBases();
        assertNull(w.shift(c, 3_000), "no start yet: nothing to move");
        assertEquals(0, w.earned(c, 3_500), "the start is taken from the worth with the money already in it");
        assertEquals(500, w.earned(c, 4_000));
    }
}
