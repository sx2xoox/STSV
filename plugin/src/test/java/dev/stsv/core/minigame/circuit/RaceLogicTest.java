package dev.stsv.core.minigame.circuit;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The race's rules: gates in order, laps, the finish and its time, the standings, the boost gauge, the records. */
class RaceLogicTest {

    /** A lap of 1000 samples with 4 gates at 0, 250, 500, 750. */
    private static RaceProgress race(int laps) {
        return new RaceProgress(1000, new int[]{0, 250, 500, 750}, laps, 980);
    }

    /** Drives forward from where it is to `to` (wrapping), 10 samples a tick; returns the last event that was not NONE. */
    private static RaceProgress.Event drive(RaceProgress p, int to, double[] clock) {
        RaceProgress.Event last = RaceProgress.Event.NONE;
        int at = p.at();
        while (at != to) {
            int step = Math.min(10, Math.floorMod(to - at, 1000));
            at = Math.floorMod(at + step, 1000);
            clock[0] += 0.05;
            RaceProgress.Event e = p.move(at, clock[0], 0.05);
            if (e != RaceProgress.Event.NONE) last = e;
        }
        return last;
    }

    @Test
    void lapsCountOnlyWithEveryGateInOrder() {
        RaceProgress p = race(2);
        double[] t = {0};
        assertEquals(0, p.lap());
        drive(p, 10, t);                       // over the line from the grid: lap 1 begins
        assertEquals(1, p.lap());
        assertEquals(1, p.next());
        drive(p, 300, t);
        assertEquals(2, p.next(), "gate 1 passed");
        drive(p, 990, t);
        assertEquals(0, p.next(), "every gate: the finish line is next");
        assertEquals(RaceProgress.Event.LAP, drive(p, 20, t));
        assertEquals(2, p.lap());
        assertEquals(1, p.lapTimes().size());
        drive(p, 990, t);
        assertEquals(RaceProgress.Event.FINISH, drive(p, 5, t));
        assertTrue(p.finished());
        assertEquals(2, p.lapTimes().size());
        double laps = p.lapTimes().get(0) + p.lapTimes().get(1);
        assertTrue(laps > 0 && laps <= p.finishTime(), "the laps add up to the race (less the run up to the line)");
    }

    @Test
    void aShortcutPastAGateGainsNothing() {
        RaceProgress p = race(1);
        double[] t = {0};
        drive(p, 10, t);
        drive(p, 200, t);
        // jumped (a respawn far ahead, or flying over the field) from 200 to 600: gate 250 and 500 never crossed
        p.teleported(600);
        drive(p, 990, t);
        assertEquals(1, p.next(), "still waiting for gate 1");
        assertEquals(RaceProgress.Event.NONE, drive(p, 10, t), "the finish line does not count");
        assertEquals(1, p.lap());
        assertFalse(p.finished());
        // a step longer than MAX_STEP is not driven: no gate
        RaceProgress q = race(1);
        q.move(10, 0.05, 0.05);
        assertEquals(1, q.lap());
        q.move(10 + RaceProgress.MAX_STEP + 300, 0.1, 0.05);
        assertEquals(1, q.next());
    }

    @Test
    void goingBackAndForthOverALineCountsItOnce() {
        RaceProgress p = race(3);
        double[] t = {0};
        drive(p, 10, t);
        assertEquals(1, p.lap());
        p.move(995, t[0] += 0.05, 0.05);       // back over the line
        p.move(8, t[0] += 0.05, 0.05);         // and forward again
        assertEquals(1, p.lap(), "lap 1 still");
        assertEquals(1, p.next());
    }

    @Test
    void theCrossingTimeIsFinerThanATick() {
        RaceProgress p = race(1);
        // at 990 at t = 1.00; at 1000(=0) .. the line at 0: crossing at 10/20 of the tick from 990 to 10
        p.move(990, 1.0, 0.05);
        p.move(10, 1.05, 0.05);
        assertEquals(1.025, p.lapStart(), 1e-9);
    }

    @Test
    void goingTheWrongWayIsNoticed() {
        RaceProgress p = race(3);
        double[] t = {0};
        drive(p, 100, t);
        assertFalse(p.wrongWay());
        int at = 100;
        for (int k = 0; k < RaceProgress.WRONG_TICKS + 5; k++) {
            at -= 2;
            p.move(at, t[0] += 0.05, 0.05);
        }
        assertTrue(p.wrongWay());
        for (int k = 0; k < 40; k++) {
            at += 3;
            p.move(at, t[0] += 0.05, 0.05);
        }
        assertFalse(p.wrongWay(), "forward again: no longer");
    }

    @Test
    void theStandingsGoByLapThenGateThenDistance() {
        RaceProgress a = race(3), b = race(3), c = race(3);
        double[] ta = {0}, tb = {0}, tc = {0};
        drive(a, 10, ta);
        drive(b, 10, tb);
        drive(c, 10, tc);
        drive(a, 400, ta);       // lap 1, past gate 1
        drive(b, 240, tb);       // lap 1, before gate 1, but...
        drive(c, 260, tc);       // just past gate 1
        assertTrue(a.distance() > c.distance());
        assertTrue(c.distance() > b.distance());
        drive(b, 990, tb);
        drive(b, 20, tb);        // lap 2
        assertTrue(b.distance() > a.distance());
        assertEquals("1:02.345", RaceProgress.format(62.345));
        assertEquals("0:05.09", RaceProgress.formatShort(5.099));
    }

    @Test
    void driftingFillsTheGaugeAndSpaceFiresIt() {
        BoostGauge g = new BoostGauge();
        int ticks = 0;
        while (!g.tick(1.0, false, 1.0)) ticks++;
        assertTrue(ticks >= 48 && ticks <= 50, "a full slide fills one boost in two and a half seconds: " + ticks);
        assertEquals(1, g.stored());
        assertTrue(g.fire(1.3, 30));
        assertEquals(1.3, g.factor(), 1e-9);
        assertFalse(g.fire(1.3, 30), "nothing kept");
        for (int k = 0; k < 30; k++) g.tick(0, false, 1);
        assertEquals(1, g.factor(), 1e-9, "the boost is over");
        // a quicker kart fills faster, two boosts at most
        BoostGauge q = new BoostGauge();
        for (int k = 0; k < 400; k++) q.tick(1.0, true, 1.9);
        assertEquals(BoostGauge.MAX_STORED, q.stored());
        assertEquals(0, q.gauge(), 1e-9);
        // a pad: a boost of its own (nothing kept is used)
        q.free(1.35, 20);
        assertEquals(1.35, q.factor(), 1e-9);
        assertEquals(2, q.stored());
    }

    @Test
    void recordsKeepTheBestAndTellANewRecord(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        CircuitStore s = new CircuitStore(db);
        s.init();
        CircuitStore.Result r1 = s.record("village", a, "A", 41_000, 130_000, true, "storm", 1);
        assertTrue(r1.lapPb() && r1.racePb() && r1.lapRecord() && r1.raceRecord());
        CircuitStore.Result r2 = s.record("village", b, "B", 40_500, 131_000, false, "tub", 2);
        assertTrue(r2.lapRecord(), "a faster lap is the track's new lap record");
        assertFalse(r2.raceRecord(), "a slower race is not");
        CircuitStore.Result r3 = s.record("village", a, "A", 42_000, 0, false, "storm", 3);
        assertFalse(r3.lapPb(), "slower than their own");
        assertEquals(2, r3.row().races());
        assertEquals(1, r3.row().wins());
        assertEquals(41_000, r3.row().bestLap());
        assertEquals(40_500, s.best("village", true));
        assertEquals(130_000, s.best("village", false));
        assertEquals(List.of(a, b), s.top("village", false, 10).stream().map(CircuitStore.Row::player).toList());
        assertEquals(List.of(b, a), s.top("village", true, 10).stream().map(CircuitStore.Row::player).toList());
        assertEquals(2, s.rank("village", a, true));
        db.close();
        Db again = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        CircuitStore t = new CircuitStore(again);
        t.init();
        assertEquals(40_500, t.best("village", true), "kept");
        assertEquals(1, (int) t.reset("village", b).get());
        assertEquals(41_000, t.best("village", true));
        assertEquals(1, (int) t.reset(null, null).get());
        assertEquals(0, t.best("village", false));
        again.close();
    }
}
