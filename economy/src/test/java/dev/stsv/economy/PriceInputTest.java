package dev.stsv.economy;

import dev.stsv.economy.market.PriceInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The price picker of 판매 등록 / 경매 등록 / 입찰: steps, typed amounts, quick bids. */
class PriceInputTest {

    @Test
    void stepsAddAndTakeAwayButNeverBelowTheLeast() {
        assertEquals(110, PriceInput.step(100, 10, true, false, 1));
        assertEquals(90, PriceInput.step(100, 10, false, false, 1));
        assertEquals(200, PriceInput.step(100, 10, true, true, 1));            // shift: ten steps
        assertEquals(1, PriceInput.step(100, 1_000, false, false, 1));          // not to zero or below
        assertEquals(50, PriceInput.step(60, 100, false, false, 50));          // the least bid stays
        assertEquals(PriceInput.MAX, PriceInput.step(PriceInput.MAX, 10_000, true, true, 1));
    }

    @Test
    void typedAmounts() {
        assertEquals(1500, PriceInput.parse("1500"));
        assertEquals(1500, PriceInput.parse("1,500"));
        assertEquals(1500, PriceInput.parse(" 1500G "));
        assertEquals(1500, PriceInput.parse("1500 골드"));
        assertEquals(1500, PriceInput.parse("1.5천"));
        assertEquals(30_000, PriceInput.parse("3만"));
        assertEquals(25_000, PriceInput.parse("2만5천"));
        assertEquals(25_000, PriceInput.parse("2만 5천"));
        assertEquals(12_000, PriceInput.parse("12k"));
        assertEquals(10_000, PriceInput.parse("만"));
        assertEquals(100_000_000, PriceInput.parse("1억"));
        assertEquals(-1, PriceInput.parse("abc"));
        assertEquals(-1, PriceInput.parse("-5"));
        assertEquals(-1, PriceInput.parse("0"));
        assertEquals(-1, PriceInput.parse(""));
        assertEquals(-1, PriceInput.parse("5천2만"));
        assertEquals(-1, PriceInput.parse("99999999999999999999"));
    }

    @Test
    void quickBidsRaiseTheLeastBidRoundedUp() {
        assertEquals(105, PriceInput.raise(100, 5));
        assertEquals(110, PriceInput.raise(100, 10));
        assertEquals(12, PriceInput.raise(11, 5));      // 11.55 -> 12
        assertEquals(1, PriceInput.raise(1, 0));
    }

    @Test
    void stepLabels() {
        assertEquals("1,000", PriceInput.label(1_000));
        assertEquals("1만", PriceInput.label(10_000));
        assertEquals(5, PriceInput.STEPS.length);
    }
}
