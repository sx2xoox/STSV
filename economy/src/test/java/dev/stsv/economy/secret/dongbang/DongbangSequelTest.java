package dev.stsv.economy.secret.dongbang;

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

/** 청산인의 업무 (the 후속 임무 of 동방해운): the eleven, their order, the visits, the stop, the clue words. */
class DongbangSequelTest {

    @Test
    void elevenWereAboard() {
        List<String> chairs = new ArrayList<>();
        for (int i = 0; i < Inquest.SEATS; i++) chairs.add("n" + i);
        List<String> h = Liquidation.holders(chairs, 4);
        assertEquals(11, Liquidation.HOLDERS);
        assertEquals(11, h.size());
        assertFalse(h.contains("n4"), "the one who was not aboard is owed nothing");
        assertEquals("n5", h.get(4));
    }

    @Test
    void theOrderIsFixedAndComplete() {
        List<Integer> a = Liquidation.order(123L), b = Liquidation.order(123L);
        assertEquals(a, b, "the same after a restart");
        assertEquals(11, a.size());
        assertEquals(11, new HashSet<>(a).size());
        for (int x : a) assertTrue(x >= 0 && x < 11);
        assertNotEquals(a, Liquidation.order(124L), "another liquidator, another order");
    }

    @Test
    void elevenWeeksThenAnniversaries() {
        List<Integer> order = Liquidation.order(7L);
        Set<Integer> seen = new HashSet<>();
        for (int n = 0; n < 11; n++) {
            Liquidation.Visit v = Liquidation.visit(order, n);
            assertTrue(seen.add(v.slot()), "everyone once");
            assertFalse(v.again());
            assertEquals(n == 10, v.last(), "the last letter follows the eleventh only");
            assertEquals(0, v.round());
        }
        for (int n = 11; n < 40; n++) {
            Liquidation.Visit v = Liquidation.visit(order, n);
            assertTrue(v.again());
            assertFalse(v.last());
            assertEquals(order.get(n % 11), v.slot(), "the same order again");
            assertEquals(n / 11, v.round());
        }
        assertEquals(order.getFirst(), Liquidation.visit(order, -3).slot());
    }

    @Test
    void familiesLiveByDifferentStops() {
        assertEquals(-1, Liquidation.stopFor(1L, 3, 0));
        Set<Integer> stops = new HashSet<>();
        for (int slot = 0; slot < 11; slot++) {
            int i = Liquidation.stopFor(99L, slot, 20);
            assertTrue(i >= 0 && i < 20);
            assertEquals(i, Liquidation.stopFor(99L, slot, 20), "the same family, the same stop");
            stops.add(i);
        }
        assertEquals(11, stops.size(), "eleven stops for eleven families when there are enough");
        Set<Integer> few = new HashSet<>();
        for (int slot = 0; slot < 11; slot++) few.add(Liquidation.stopFor(99L, slot, 3));
        assertEquals(3, few.size(), "with three stops all three are used");
        assertEquals(0, Liquidation.stopFor(5L, 9, 1));
    }

    @Test
    void aStopIsSpokenOfByItsFirstSounds() {
        assertEquals("ㄷㄷ ㅇ", Liquidation.chosung("등대 앞"));
        assertEquals("ㅅㅊ 2", Liquidation.chosung("산책 2"));
        assertEquals("", Liquidation.chosung(null));
        assertEquals("등대 앞", Liquidation.stopWord("등대 앞 정류장"));
        assertEquals("정류장", Liquidation.stopWord("정류장"), "a stop named only that keeps its name");
        assertEquals("", Liquidation.stopWord(null));
    }

    @Test
    void whichWayAndHowFar() {
        assertEquals("동쪽", Liquidation.direction(10, 0));
        assertEquals("남쪽", Liquidation.direction(0, 10));
        assertEquals("북서쪽", Liquidation.direction(-7, -7));
        assertEquals("북쪽", Liquidation.direction(1, -40));
        assertNull(Liquidation.direction(0, 0));
        assertEquals("약 350보", Liquidation.paces(340));
        assertEquals("약 50보", Liquidation.paces(3));
        assertEquals("약 1200보", Liquidation.paces(1210));
    }

    @Test
    void statementsAreMarkedByWeekAndSlot() {
        assertEquals("2026-W48#3", Liquidation.mark("2026-W48", 3));
        assertNotEquals(Liquidation.mark("2026-W48", 3), Liquidation.mark("2026-W49", 3), "last week's statement opens no door");
        assertEquals("제01번", Liquidation.number(0));
        assertEquals("제11번", Liquidation.number(10));
    }
}
