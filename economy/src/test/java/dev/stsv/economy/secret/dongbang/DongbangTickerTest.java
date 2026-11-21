package dev.stsv.economy.secret.dongbang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 동방해운's share: the two key midnights must close exactly on the coordinates, with the sign in the last candle. */
class DongbangTickerTest {

    static final long DAY = 60_000L;

    private static Ticker test(long start) {
        Ticker.Clock c = new Ticker.Clock(true, DAY);
        return new Ticker(c, new Ticker.Rules(0.005, 0.003, 0.004, 0.05, 0.04, 4, 100, 20_000), 1987, start);
    }

    private static Ticker.Close at(List<Ticker.Close> l, long when) {
        for (Ticker.Close c : l) if (c.at() == when) return c;
        throw new AssertionError("no close at " + when + " in " + l);
    }

    private static void check(int x, int z, boolean online) {
        long t0 = 1000 * DAY + 1234;
        Ticker k = test(t0);
        k.record(t0, 1987);
        Random r = new Random(7);
        List<Ticker.Close> all = new ArrayList<>();
        // a day of holding, then the notice on day 2
        long notice = 1001 * DAY + 900;
        all.addAll(k.advance(notice, online ? Ticker.Mood.WATER : Ticker.Mood.OFFLINE, r));
        k.notice(notice, x, z);
        assertEquals(1002 * DAY, k.kx);
        long end = k.kz() + DAY / 2;
        if (online) {
            for (long t = notice; t <= end; t += 1000) {
                all.addAll(k.advance(t, Ticker.Mood.WATER, r));
                if (t > k.kx && t < k.kz() - k.approach()) assertTrue(k.halted(t));
            }
        } else {
            all.addAll(k.advance(end, Ticker.Mood.OFFLINE, r));
        }
        Ticker.Close cx = at(all, k.kx), cz = at(all, k.kz());
        assertEquals(Math.abs(x), Math.round(cx.price()));
        assertEquals(x > 0, cx.up(), "the last candle before the first midnight gives x's sign");
        assertEquals(Math.abs(z), Math.round(cz.price()));
        assertEquals(z > 0, cz.up(), "the last candle before the second midnight gives z's sign");
        assertEquals(Math.abs(z), Math.round(k.price));
        assertTrue(k.halted(end));
        // the chart, frozen after the second midnight, ends on that candle
        Ticker.Candle last = k.m10.getLast();
        assertEquals(Math.abs(z), Math.round(last.close()));
        assertEquals(z > 0, last.up());
    }

    @Test
    void midnightsCloseOnTheCoordinates() {
        check(412, -1207, true);
        check(-1352, 344, true);
        check(1500, 1500, false);
        check(-300, -888, false);
    }

    @Test
    void freePriceFollowsTheHolder() {
        Ticker up = test(0), down = test(0);
        Random r = new Random(1);
        up.advance(DAY / 2, Ticker.Mood.ONLINE, r);
        down.advance(DAY / 2, Ticker.Mood.OFFLINE, r);
        assertTrue(up.price > 1987, "online climbs");
        assertTrue(down.price < 1987, "away sinks");
        assertFalse(up.halted(DAY / 2));
    }

    @Test
    void lateNoticeMovesToTheNextNight() {
        Ticker k = test(0);
        long late = 5 * DAY + DAY - k.approach();
        k.notice(late, 500, 500);
        assertEquals(7 * DAY, k.kx);
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
