package dev.stsv.core.vehicle;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The boat's rocking (drawn only): small, never still for long afloat, out of step between boats, still aground. */
class BoatMotionTest {

    @Test
    void itRocksGentlyOnTheWater() {
        double maxPitch = 0, maxRoll = 0, maxBob = 0;
        boolean changes = false;
        BoatMotion.Rock last = null;
        for (int t = 0; t < 2000; t += 3) {
            BoatMotion.Rock r = BoatMotion.rock(t, 7, 0, 7, 0, 1, true);
            maxPitch = Math.max(maxPitch, Math.abs(r.pitch()));
            maxRoll = Math.max(maxRoll, Math.abs(r.roll()));
            maxBob = Math.max(maxBob, Math.abs(r.bob()));
            if (last != null && !last.equals(r)) changes = true;
            last = r;
            // between two updates (3 ticks) it turns a little at a time: the client's glide looks smooth
            BoatMotion.Rock next = BoatMotion.rock(t + BoatMotion.TICKS, 7, 0, 7, 0, 1, true);
            assertTrue(Math.abs(next.roll() - r.roll()) < 0.8 && Math.abs(next.pitch() - r.pitch()) < 0.8, "no jerks");
        }
        assertTrue(changes);
        assertTrue(maxPitch > 0.5 && maxPitch < 2, "a degree or so of pitch: " + maxPitch);
        assertTrue(maxRoll > 1 && maxRoll < 2.5, "a degree or two of roll: " + maxRoll);
        assertTrue(maxBob > 0.02 && maxBob < 0.06, "a few hundredths of a block up and down: " + maxBob);
        assertNotEquals(BoatMotion.rock(100, 1, 0, 7, 0, 1, true), BoatMotion.rock(100, 2, 0, 7, 0, 1, true), "boats rock out of step");
    }

    @Test
    void underWayTheBowRisesAndItLeansOutOfTurns() {
        double still = 0, fast = 0;
        for (int t = 0; t < 600; t++) {
            still += BoatMotion.rock(t, 3, 0, 7, 0, 1, true).pitch();
            fast += BoatMotion.rock(t, 3, 7, 7, 0, 1, true).pitch();
        }
        assertEquals(BoatMotion.BOW_UP, (fast - still) / 600, 1e-4, "bow up by 2.5 degrees at full speed");
        BoatMotion.Rock right = BoatMotion.rock(0, 3, 7, 7, 2.4f, 1, true), straight = BoatMotion.rock(0, 3, 7, 7, 0, 1, true);
        assertTrue(right.roll() < straight.roll(), "turning right it leans left (out of the turn)");
        assertTrue(Math.abs(BoatMotion.rock(0, 3, 7, 7, 90, 1, true).roll() - straight.roll()) <= BoatMotion.MAX_LEAN + 1e-4, "within reason");
    }

    @Test
    void onIceOrAgroundItLiesStill() {
        assertEquals(BoatMotion.Rock.STILL, BoatMotion.rock(123, 3, 5, 7, 1, 1, false));
        assertEquals(BoatMotion.Rock.STILL, BoatMotion.rock(123, 3, 5, 7, 1, 0, true), "water.rock 0: no rocking");
    }

    @Test
    void partsTurnAboutTheWaterline() {
        Vector3f pivot = new Vector3f(0, 0.375f, 0);
        Quaternionf none = BoatMotion.rotation(BoatMotion.Rock.STILL);
        Vector3f at = new Vector3f(0.5f, 0.8f, -1.6f);
        Vector3f same = BoatMotion.rocked(at, pivot, none, 0);
        assertEquals(0, same.distance(at), 1e-6, "still: where it was");
        Vector3f p = BoatMotion.rocked(new Vector3f(pivot), pivot, BoatMotion.rotation(new BoatMotion.Rock(2, 2, 0.05f)), 0.05f);
        assertEquals(0, p.distance(new Vector3f(0, 0.425f, 0)), 1e-6, "the pivot only bobs");
        // bow up: a point ahead of the pivot (display +z forward) goes up
        Vector3f bow = BoatMotion.rocked(new Vector3f(0, 0.375f, 2), pivot, BoatMotion.rotation(new BoatMotion.Rock(3, 0, 0)), 0);
        assertTrue(bow.y > 0.375f + 0.05f, "the bow rises: " + bow.y);
    }
}
