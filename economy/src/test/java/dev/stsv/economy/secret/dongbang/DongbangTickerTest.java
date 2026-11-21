package dev.stsv.economy.secret.dongbang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 동방해운's share: the price follows its holder (atmosphere only), and its candles and closes save and load. */
class DongbangTickerTest {

    static final long DAY = 60_000L;

    private static Ticker test(long start) {
        Ticker.Clock c = new Ticker.Clock(true, DAY);
        return new Ticker(c, new Ticker.Rules(0.005, 0.003, 0.004, 0.05, 100, 20_000), 1987, start);
    }

    @Test
    void midnightsAreRecorded() {
        Ticker k = test(0);
        List<Ticker.Close> c = k.advance(3 * DAY + 5, Ticker.Mood.WATER, new Random(2));
        assertEquals(3, c.size());
        assertEquals(DAY, c.getFirst().at());
        assertEquals(c.getLast().price(), k.prevClose(0));
        assertFalse(k.m10.isEmpty());
    }

    @Test
    void freePriceFollowsTheHolder() {
        Ticker up = test(0), down = test(0);
        Random r = new Random(1);
        up.advance(DAY / 2, Ticker.Mood.ONLINE, r);
        down.advance(DAY / 2, Ticker.Mood.OFFLINE, r);
        assertTrue(up.price > 1987, "online climbs");
        assertTrue(down.price < 1987, "away sinks");
    }

    @Test
    void realDaysUseKoreanMidnights() {
        Ticker.Clock c = new Ticker.Clock(false, 0);
        long t = java.time.ZonedDateTime.of(2026, 11, 21, 23, 59, 0, 0, Ticker.Clock.KST).toInstant().toEpochMilli();
        long mid = java.time.ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, Ticker.Clock.KST).toInstant().toEpochMilli();
        assertEquals(mid, c.nextMidnight(t));
        assertEquals(144, c.candlesPerDay());
        assertEquals(0, (mid - c.dayStart(t)) % c.candle());
    }

    @Test
    void candidatesAreInRangeAndCentred() {
        for (long seed = 0; seed < 200; seed++) {
            for (int[] c : Ticker.candidates(seed, 300, 1500, 12)) {
                for (int v : c) {
                    assertTrue(Math.abs(v) >= 300 && Math.abs(v) <= 1500, "range " + v);
                    assertEquals(8, Math.floorMod(v, 16), "chunk middle " + v);
                }
            }
        }
        assertEquals(Ticker.candidates(42, 300, 1500, 3).getFirst()[0], Ticker.candidates(42, 300, 1500, 3).getFirst()[0]);
    }

    @Test
    void savesRoundTrip() {
        Ticker k = test(0);
        k.advance(DAY * 2, Ticker.Mood.ONLINE, new Random(3));
        List<Ticker.Candle> back = new ArrayList<>();
        Ticker.decode(Ticker.encode(k.m10), back);
        assertEquals(k.m10.size(), back.size());
        assertEquals(k.m10.getLast().t(), back.getLast().t());
        List<Ticker.Close> closes = new ArrayList<>();
        Ticker.decodeCloses(Ticker.encodeCloses(k.closes), closes);
        assertEquals(k.closes.size(), closes.size());
        assertEquals(k.closes.getLast().up(), closes.getLast().up());
    }
}
