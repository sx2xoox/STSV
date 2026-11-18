package dev.stsv.core.minigame.circuit;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 고스트: the path's encoding (round trip, size), playback between samples, the time gap, laps cut from a recording, the store. */
class GhostTest {

    /** A kart going round a circle of radius 60 (one lap in `seconds`), climbing a little on one side, samples every step ticks. */
    private static Ghost circle(double seconds, int step) {
        int n = (int) Math.ceil(seconds * 20 / step) + 1;
        double[] x = new double[n], y = new double[n], z = new double[n];
        float[] yaw = new float[n];
        boolean[] boost = new boolean[n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i * step * 0.05 / seconds;
            x[i] = 60 * Math.cos(a);
            z[i] = 60 * Math.sin(a);
            y[i] = 3 * Math.max(0, Math.sin(a));
            // heading along the circle (Minecraft: yaw 0 = +z, 90 = -x)
            yaw[i] = (float) Math.toDegrees(Math.atan2(-(-Math.sin(a)), Math.cos(a)));
            boost[i] = i % 50 < 10;
        }
        return Ghost.of(step, 0, seconds, new double[]{seconds / 4, Double.NaN, seconds * 3 / 4}, x, y, z, yaw, boost);
    }

    @Test
    void encodingRoundTripsWithinItsUnits() {
        Ghost g = circle(42.5, 2);
        byte[] data = g.encode();
        Ghost back = Ghost.decode(data);
        assertEquals(g.samples(), back.samples());
        assertEquals(g.lapMs(), back.lapMs());
        assertEquals(2, back.stepTicks());
        assertEquals(42.5 / 4, back.gate(1), 1e-3);
        assertTrue(Double.isNaN(back.gate(2)), "a gate not timed stays untimed");
        assertEquals(42.5 * 3 / 4, back.gate(3), 1e-3);
        assertTrue(Double.isNaN(back.gate(4)) && Double.isNaN(back.gate(0)));
        for (double t = 0; t < 42.5; t += 0.37) {
            Ghost.Pose a = g.at(t), b = back.at(t);
            assertEquals(a.x(), b.x(), 1e-9);
            assertEquals(a.y(), b.y(), 1e-9);
            assertEquals(a.z(), b.z(), 1e-9);
            assertEquals(a.yaw(), b.yaw(), 1e-4);
            assertEquals(a.boost(), b.boost());
        }
        // close to what was driven: positions to 1/128 block, headings to a few thousandths of a degree
        Ghost.Pose p = back.at(10.0);
        double a = 2 * Math.PI * 10.0 / 42.5;
        assertEquals(60 * Math.cos(a), p.x(), 0.01);
        assertEquals(60 * Math.sin(a), p.z(), 0.01);
        // a minute's lap at a sample every two ticks is a few KB
        assertTrue(data.length < 600 * 9, "compact: " + data.length + " bytes for " + g.samples() + " samples");
    }

    @Test
    void sizeIsBoundedEvenAtWorst() {
        // the most samples, every step as far as a coordinate may jump, the heading flipping: still within MAX_BYTES
        int n = Ghost.MAX_SAMPLES;
        double[] x = new double[n], y = new double[n], z = new double[n];
        float[] yaw = new float[n];
        boolean[] boost = new boolean[n];
        for (int i = 0; i < n; i++) {
            double far = i % 2 == 0 ? -999_999 : 999_999;
            x[i] = far;
            y[i] = -far;
            z[i] = far;
            yaw[i] = i % 2 == 0 ? 0 : 180;
            boost[i] = i % 2 == 0;
        }
        Ghost g = Ghost.of(1, -1, 600, new double[Ghost.MAX_GATES], x, y, z, yaw, boost);
        byte[] data = g.encode();
        assertTrue(data.length <= Ghost.MAX_BYTES, data.length + " bytes");
        Ghost back = Ghost.decode(data);
        assertEquals(-999_999, back.at(-1).x(), 0.01);
        assertEquals(999_999, back.at(-1).y(), 0.01);
        // too many samples, too long a lap, coordinates past the bound: refused
        assertThrows(IllegalArgumentException.class, () -> Ghost.of(1, 0, 10, new double[0], new double[n + 1], new double[n + 1],
                new double[n + 1], new float[n + 1], new boolean[n + 1]));
        assertThrows(IllegalArgumentException.class, () -> Ghost.of(1, 0, 10, new double[0], new double[]{0, 2_000_000}, new double[2],
                new double[2], new float[2], new boolean[2]));
    }

    @Test
    void decodingRefusesAnythingElse() {
        byte[] good = circle(20, 2).encode();
        assertThrows(IllegalArgumentException.class, () -> Ghost.decode(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> Ghost.decode(null));
        byte[] wrongMagic = good.clone();
        wrongMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Ghost.decode(wrongMagic));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 3);
        assertThrows(IllegalArgumentException.class, () -> Ghost.decode(truncated));
        byte[] longer = java.util.Arrays.copyOf(good, good.length + 1);
        assertThrows(IllegalArgumentException.class, () -> Ghost.decode(longer));
        byte[] version = good.clone();
        version[1] = 99;
        assertThrows(IllegalArgumentException.class, () -> Ghost.decode(version));
    }

    @Test
    void playbackGlidesBetweenSamplesAndHoldsAtTheEnds() {
        double[] x = {0, 2, 4}, y = {0, 0, 1}, z = {0, 0, 0};
        float[] yaw = {350, 10, 30};
        Ghost g = Ghost.of(2, -0.05, 0.2, new double[0], x, y, z, yaw, new boolean[]{false, true, true});
        // sample k at -0.05 + k * 0.1 s
        assertEquals(0, g.at(-1).x(), 1e-9, "held at the start");
        assertEquals(0, g.at(-0.05).x(), 1e-9);
        assertEquals(1, g.at(0).x(), 1e-6, "halfway between the first two");
        float through = g.at(0).yaw();
        assertTrue(through < 0.02 || through > 359.98, "350 -> 10 goes the short way round, through 0: " + through);
        assertEquals(3, g.at(0.1).x(), 1e-6);
        assertEquals(20f, g.at(0.1).yaw(), 0.01);
        assertEquals(4, g.at(9).x(), 1e-9, "held at the end");
        assertEquals(0.5, g.at(0.1).y(), 1e-6);
        assertTrue(g.at(0.07).boost());
        assertEquals(0.15, g.end(), 1e-9);
        // nose up on the way up (Minecraft pitch: negative = up)
        Ghost hill = Ghost.of(1, 0, 1, new double[0], new double[]{0, 1, 2, 3, 4, 5}, new double[]{0, 1, 2, 3, 4, 5}, new double[6],
                new float[6], new boolean[6]);
        assertTrue(hill.at(0.1).pitch() < -20, "pitched up a 45 degree slope: " + hill.at(0.1).pitch());
        assertTrue(hill.at(0.1).pitch() >= -Ghost.MAX_PITCH);
        assertEquals(0, circle(30, 2).at(22).pitch(), 1e-9, "flat ground (the circle's low half), flat nose");
    }

    @Test
    void theGapReadsAheadAndBehind() {
        assertEquals(-1.1, Ghost.gap(40.0, 41.1), 1e-9);
        assertEquals("-1.10", Ghost.gapText(Ghost.gap(40.0, 41.1)));
        assertEquals("+0.42", Ghost.gapText(0.42));
        assertEquals("+0.01", Ghost.gapText(0.005));
        assertEquals("±0.00", Ghost.gapText(0.004));
        assertEquals("+12.34", Ghost.gapText(12.3449));
        assertEquals("-61.50", Ghost.gapText(-61.5));
        assertTrue(Double.isNaN(Ghost.gap(Double.NaN, 3)));
        assertEquals("", Ghost.gapText(Double.NaN));
    }

    @Test
    void choicesCycleAndRead() {
        assertEquals(Ghost.Choice.MINE, Ghost.Choice.NONE.next());
        assertEquals(Ghost.Choice.RECORD, Ghost.Choice.MINE.next());
        assertEquals(Ghost.Choice.NONE, Ghost.Choice.RECORD.next());
        assertEquals(Ghost.Choice.RECORD, Ghost.Choice.NONE.previous());
        assertEquals(Ghost.Choice.RECORD, Ghost.Choice.of("record"));
        assertEquals(Ghost.Choice.NONE, Ghost.Choice.of("??"));
    }

    @Test
    void lapsAreCutFromTheRecording() {
        GhostRecorder rec = new GhostRecorder(2);
        // a kart along x at 10 blocks a second from tick 0; lap 1 begins at 1.03 s, ends at 6.03 s, the second at 11.03 s
        for (int tick = 0; tick <= 300; tick++) {
            if (tick == 77) continue;                 // a tick without a kart (a respawn): its sample is filled in
            rec.sample(tick, tick * 0.5, 0, 0, 0, false);
            if (tick == 21) rec.lapStarted(1.03);
            if (tick == 121) {
                Ghost lap1 = rec.lap(1.03, 6.03, new double[]{2.0});
                assertNotNull(lap1);
                assertEquals(5000, lap1.lapMs());
                assertEquals(2.0, lap1.gate(1), 1e-9);
                assertTrue(lap1.start() <= 0 && lap1.start() > -0.1, "the last sample before the line: " + lap1.start());
                // where it was 2.5 s into the lap: at 3.53 s of the race = 35.3 blocks
                assertEquals(35.3, lap1.at(2.5).x(), 1e-6);
                // its last sample: the last one taken (at most a step before the line: the crossing tick may fall between)
                assertTrue(lap1.end() >= 5.0 - 0.1 - 1e-9, "reaches the line again: " + lap1.end());
            }
            if (tick == 221) {
                Ghost lap2 = rec.lap(6.03, 11.03, new double[]{Double.NaN});
                assertNotNull(lap2);
                assertEquals(85.3, lap2.at(2.5).x(), 1e-6, "the second lap is timed from its own start");
            }
        }
        assertTrue(rec.size() < 60, "the samples of finished laps are let go: " + rec.size());
        assertNull(new GhostRecorder(2).lap(0, 1, new double[0]), "nothing recorded, no ghost");
    }

    @Test
    void aLapTooLongGivesNoGhostButTheNextOneDoes() {
        GhostRecorder rec = new GhostRecorder(1);
        int tick = 0;
        rec.sample(tick, 0, 0, 0, 0, false);
        rec.lapStarted(0);
        for (tick = 1; tick <= Ghost.MAX_SAMPLES + 50; tick++) rec.sample(tick, tick * 0.01, 0, 0, 0, false);
        double end = tick * 0.05;
        rec.sample(tick, 0, 0, 0, 0, false);
        assertNull(rec.lap(0, end, new double[0]), "too long a lap");
        assertTrue(rec.size() <= Ghost.MAX_SAMPLES + 1);
        for (int t = tick + 1; t <= tick + 200; t++) rec.sample(t, t * 0.01, 0, 0, 0, false);
        assertNotNull(rec.lap(end, (tick + 200) * 0.05, new double[0]), "the next lap is a ghost again");
    }

    @Test
    void theStoreKeepsEachPlayersFastestAndTheTrackRecord(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        GhostStore s = new GhostStore(db);
        s.init();
        assertNull(s.mine("village", a));
        assertNull(s.fastest("village"));
        assertTrue(s.offer("village", a, "A", "storm", "red", circle(41, 2), 1, 2));
        assertFalse(s.offer("village", a, "A", "storm", "red", circle(42, 2), 2, 2), "slower than their own: not kept");
        assertTrue(s.offer("village", a, "A", "tub", "white", circle(40, 2), 3, 2), "faster: kept");
        assertTrue(s.offer("village", b, "B", "bubble", "blue", circle(39, 2), 4, 2));
        assertEquals(b, s.fastest("village").player(), "the track record's ghost");
        assertEquals(40_000, s.mine("village", a).lapMs());
        assertEquals("tub", s.mine("village", a).kart());
        // a third player past max 2: the slowest goes (never the record)
        assertTrue(s.offer("village", c, "C", "storm", "red", circle(38, 2), 5, 2));
        assertEquals(2, s.count("village"));
        assertNull(s.mine("village", a), "the slowest was let go");
        assertEquals(c, s.fastest("village").player());
        Ghost loaded = s.load("village", b).get();
        assertEquals(39_000, loaded.lapMs());
        db.close();
        // read again from the database
        Db again = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        GhostStore t = new GhostStore(again);
        t.init();
        assertEquals(2, t.count("village"));
        assertEquals(c, t.fastest("village").player());
        Ghost fromDb = t.load("village", c).get();
        assertEquals(38_000, fromDb.lapMs());
        assertEquals(circle(38, 2).at(12.3).x(), fromDb.at(12.3).x(), 1e-9);
        assertTrue(t.mine("village", c).bytes() > 0 && t.mine("village", c).bytes() <= Ghost.MAX_BYTES);
        assertNull(t.load("village", a).get(), "a ghost let go cannot be loaded");
        assertEquals(1, (int) t.reset("village", c).get());
        assertEquals(b, t.fastest("village").player());
        assertEquals(1, (int) t.reset(null, null).get());
        assertNull(t.fastest("village"));
        again.close();
    }
}
