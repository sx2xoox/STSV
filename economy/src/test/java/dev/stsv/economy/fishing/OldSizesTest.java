package dev.stsv.economy.fishing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fish caught before 2026-11 with a size: which are converted, what their owner is paid once. */
class OldSizesTest {

    @Test
    void whatIsSized() {
        assertTrue(OldSizes.sized(true, true), "a rare or legend catch: size and factor");
        assertTrue(OldSizes.sized(false, true), "stripped in a machine slot: the factor alone");
        assertTrue(OldSizes.sized(true, false));
        assertFalse(OldSizes.sized(false, false), "a plain fish");
    }

    @Test
    void storedFactor() {
        assertEquals(1, OldSizes.factor(null), 1e-12);
        assertEquals(1, OldSizes.factor(Double.NaN), 1e-12);
        assertEquals(1.3, OldSizes.factor(1.3), 1e-12);
        assertEquals(OldSizes.MAX_FACTOR, OldSizes.factor(9.0), 1e-12, "never more than a size ever gave");
        assertEquals(0, OldSizes.factor(-2.0), 1e-12);
    }

    @Test
    void refundOnlyAboveBase() {
        // (factor - 1) x base x amount
        assertEquals(0.3 * 60 * 2, OldSizes.refund(1.3, 60, 2), 1e-9);
        assertEquals(0.5 * 900, OldSizes.refund(1.5, 900, 1), 1e-9);
        assertEquals(0.5 * 900, OldSizes.refund(2.0, 900, 1), 1e-9, "capped at 1.5");
        assertEquals(0, OldSizes.refund(1.0, 60, 5), 1e-12, "factor 1: nothing");
        assertEquals(0, OldSizes.refund(0.7, 60, 5), 1e-12, "a small or stripped fish sells for more now: nothing");
        assertEquals(0, OldSizes.refund(1.4, 0, 5), 1e-12, "worthless (a trial fish, not in the shop)");
        assertEquals(0, OldSizes.refund(1.4, 60, 0), 1e-12);
    }

    @Test
    void payoutRounds() {
        assertEquals(36, OldSizes.payout(0.3 * 60 * 2));
        assertEquals(3, OldSizes.payout(2.5));
        assertEquals(0, OldSizes.payout(0));
        assertEquals(0, OldSizes.payout(-4));
        assertEquals(0, OldSizes.payout(Double.POSITIVE_INFINITY));
    }
}
