package dev.stsv.core.vehicle;

import dev.stsv.core.vehicle.VehicleSounds.Horn;
import dev.stsv.core.vehicle.VehicleSounds.HornRules;
import dev.stsv.core.vehicle.VehicleSounds.HornState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The held horn (tap, hold, release, longest hold, rest) and the engine's pitch, loudness and segment timing. */
class VehicleSoundsTest {

    /** 0.2 s shortest beep, 5 s longest hold, 0.3 s rest: the shipped defaults. */
    private static final HornRules RULES = new HornRules(4, 100, 6);

    /** Runs the horn over a held/not-held pattern from tick 0; returns the tick of every START / STOP as "S@t" / "E@t". */
    private static List<String> run(HornState h, boolean[] held) {
        List<String> out = new ArrayList<>();
        for (int t = 0; t < held.length; t++) {
            Horn a = h.tick(held[t], t, RULES);
            if (a == Horn.START) out.add("S@" + t);
            if (a == Horn.STOP) out.add("E@" + t);
        }
        return out;
    }

    /** held from `from` (inclusive) to `to` (exclusive), within `length` ticks. */
    private static boolean[] held(int length, int... ranges) {
        boolean[] h = new boolean[length];
        for (int i = 0; i < ranges.length; i += 2) for (int t = ranges[i]; t < ranges[i + 1] && t < length; t++) h[t] = true;
        return h;
    }

    @Test
    void aHoldSoundsUntilReleased() {
        assertEquals(List.of("S@2", "E@30"), run(new HornState(), held(60, 2, 30)), "starts on press, stops the tick space is up");
    }

    @Test
    void aTapStillGivesAShortBeep() {
        // space down for one tick: the horn sounds its shortest beep (4 ticks), then stops by itself
        assertEquals(List.of("S@10", "E@14"), run(new HornState(), held(40, 10, 11)));
        assertEquals(List.of("S@10", "E@14"), run(new HornState(), held(40, 10, 13)), "released before the shortest beep ends");
        assertEquals(List.of("S@10", "E@15"), run(new HornState(), held(40, 10, 15)), "held past it: stops on release");
    }

    @Test
    void aLongHoldIsCutAndNeedsAFreshPress() {
        // held for 10 s: cut at 5 s, silent while still held, sounds again only after letting go (and the rest)
        boolean[] h = held(400, 0, 200, 220, 240);
        assertEquals(List.of("S@0", "E@100", "S@220", "E@240"), run(new HornState(), h));
    }

    @Test
    void hammeringSpaceRestsBetweenBeeps() {
        // a new press every 2 ticks for 2 s
        boolean[] h = new boolean[40];
        for (int t = 0; t < 40; t += 2) h[t] = true;
        List<String> out = run(new HornState(), h);
        // each beep lasts until space is up after its shortest length, then rests 6 ticks: a beep per 12 ticks, not 20
        assertEquals(List.of("S@0", "E@5", "S@12", "E@17", "S@24", "E@29", "S@36"), out);
    }

    @Test
    void holdingThroughTheRestStartsWhenItEnds() {
        // tap, then press again 2 ticks after it stopped and keep holding: it starts as soon as the rest is over
        assertEquals(List.of("S@0", "E@4", "S@10", "E@30"), run(new HornState(), held(40, 0, 1, 6, 30)));
    }

    @Test
    void cuttingSilencesOnlyASoundingHorn() {
        HornState h = new HornState();
        assertEquals(Horn.NONE, h.cut(0, RULES), "nothing to stop");
        assertEquals(Horn.START, h.tick(true, 1, RULES));
        assertTrue(h.sounding());
        assertEquals(Horn.STOP, h.cut(2, RULES), "the driver got out: stops at once, even within the shortest beep");
        assertFalse(h.sounding());
        assertEquals(Horn.NONE, h.tick(true, 3, RULES), "then rests");
        assertEquals(Horn.START, h.tick(true, 8, RULES));
    }

    @Test
    void rulesStayConsistent() {
        HornRules r = new HornRules(0, 0, -5);
        assertEquals(1, r.minTicks());
        assertEquals(1, r.maxTicks(), "never shorter than the shortest beep");
        assertEquals(0, r.cooldown());
    }

    private static final VehicleSpec.Engine CAR = new VehicleSpec.Engine("stsv:vehicle.police_car.engine", 0.35f, 0.16f, 0.6f, 1.45f, 10);
    private static final VehicleSpec.Engine CART = new VehicleSpec.Engine("stsv:vehicle.golf_cart.engine", 0.22f, 0f, 0.7f, 1.5f, 10);

    @Test
    void enginePitchRisesWithSpeed() {
        assertEquals(0.6f, VehicleSounds.enginePitch(0, 10, CAR), 1e-6, "idle");
        assertEquals(0.6f, VehicleSounds.enginePitch(0.01, 10, CAR), 1e-6, "creeping counts as standing");
        assertEquals(1.45f, VehicleSounds.enginePitch(10, 10, CAR), 1e-6, "top speed");
        assertEquals(1.45f, VehicleSounds.enginePitch(14, 10, CAR), 1e-6, "past top: top");
        assertEquals(VehicleSounds.enginePitch(3, 10, CAR), VehicleSounds.enginePitch(-3, 10, CAR), 1e-6, "reversing revs the same");
        float last = 0;
        for (double v = 0; v <= 10; v += 0.5) {
            float p = VehicleSounds.enginePitch(v, 10, CAR);
            assertTrue(p >= last, "never falls as it speeds up");
            assertTrue(p >= 0.5f && p <= 2f, "Minecraft's pitch range");
            last = p;
        }
        // revs up faster at first: half speed is past half the pitch range
        assertTrue(VehicleSounds.enginePitch(5, 10, CAR) > (0.6f + 1.45f) / 2);
        VehicleSpec.Engine wild = new VehicleSpec.Engine("k", 0.5f, 0.1f, 0.5f, 2f, 10);
        assertEquals(2f, VehicleSounds.enginePitch(100, 10, wild), 1e-6);
        assertEquals(0.5f, VehicleSounds.enginePitch(0, 0, wild), 1e-6, "no top speed: idle");
    }

    @Test
    void engineIsQuietStandingAndTheCartSilent() {
        assertEquals(0.16f, VehicleSounds.engineVolume(0, 10, CAR, 1), 1e-6, "idle hum");
        assertEquals(0.35f, VehicleSounds.engineVolume(10, 10, CAR, 1), 1e-6);
        assertTrue(VehicleSounds.engineVolume(2, 10, CAR, 1) > 0.16f && VehicleSounds.engineVolume(2, 10, CAR, 1) < 0.35f);
        assertEquals(0f, VehicleSounds.engineVolume(0, 6, CART, 1), 1e-6, "an electric cart is silent standing");
        assertTrue(VehicleSounds.engineVolume(1, 6, CART, 1) > 0, "and whines when it moves");
        assertEquals(0.175f, VehicleSounds.engineVolume(10, 10, CAR, 0.5), 1e-6, "the server's scale");
        assertEquals(0f, VehicleSounds.engineVolume(10, 10, CAR, 0), 1e-6, "scale 0 = silent");
        assertEquals(0.7f, VehicleSounds.engineVolume(10, 10, CAR, 2), 1e-6);
        assertEquals(1f, VehicleSounds.engineVolume(10, 10, new VehicleSpec.Engine("k", 1f, 0f, 1f, 1f, 10), 2), 1e-6,
                "never above 1 (heard no further than 16 blocks)");
    }

    @Test
    void segmentsFollowEachOtherAtThePitchesLength() {
        assertEquals(10, VehicleSounds.engineGap(10, 1f));
        assertEquals(5, VehicleSounds.engineGap(10, 2f), "twice as fast: half as long");
        assertEquals(20, VehicleSounds.engineGap(10, 0.5f));
        assertEquals(6, VehicleSounds.engineGap(10, 1.45f), "rounded down: the next starts a little early (more overlap, no gap)");
        assertEquals(16, VehicleSounds.engineGap(10, 0.6f));
        assertEquals(2, VehicleSounds.engineGap(3, 2f), "at least 2 ticks");
        // a segment lasts (segment + fade) / pitch; the next always starts before it ends
        double fade = 3;   // ticks of fade-out at the end of each clip (sounds.py ENGINE_FADE)
        for (float p = 0.5f; p <= 2f; p += 0.05f) {
            int gap = VehicleSounds.engineGap(10, p);
            assertTrue(gap * p <= 10 + 1e-3, "never later than the fade-out starts, pitch " + p);
            assertTrue(gap * p > 10 - fade, "and not so early that three overlap, pitch " + p);
        }
    }
}
