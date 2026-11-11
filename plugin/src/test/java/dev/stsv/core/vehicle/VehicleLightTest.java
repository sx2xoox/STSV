package dev.stsv.core.vehicle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A vehicle's brightness: felt around its body (not at its ground-level origin), faded to, felt now and then. */
class VehicleLightTest {

    /** A small light world: every block open sky (15) unless set. */
    private static final class World implements VehicleLight.Lookup {
        final Map<String, Integer> set = new HashMap<>();
        final List<int[]> asked = new ArrayList<>();
        int otherwise = VehicleLight.pack(0, 15);

        World put(int x, int y, int z, int block, int sky) {
            set.put(x + "," + y + "," + z, VehicleLight.pack(block, sky));
            return this;
        }

        @Override
        public int light(int x, int y, int z) {
            asked.add(new int[]{x, y, z});
            return set.getOrDefault(x + "," + y + "," + z, otherwise);
        }
    }

    private static final List<VehicleSpec.Vec> CAR = VehicleLight.probes(4, 2);

    @Test
    void packsBlockAndSkyLight() {
        int p = VehicleLight.pack(7, 12);
        assertEquals(7, VehicleLight.block(p));
        assertEquals(12, VehicleLight.sky(p));
        assertEquals(VehicleLight.pack(15, 0), VehicleLight.pack(20, -3), "clamped to 0..15");
    }

    @Test
    void itIsFeltOverTheFloorNotInTheGroundBlock() {
        // standing exactly on the top of the ground block at y 63 (its origin at y 64.0), or a rounding error under it
        for (double y : new double[]{64.0, Math.nextDown(64.0), 63.875 /* in soul sand */}) {
            World w = new World();
            w.otherwise = VehicleLight.pack(0, 0);   // the ground and everything else: solid
            w.put(10, 64, 20, 0, 15).put(10, 65, 20, 0, 15);
            int l = VehicleLight.sample(10.5, y, 20.5, 0, CAR, w);
            assertEquals(15, VehicleLight.sky(l), "y " + y);
            for (int[] a : w.asked) assertTrue(a[1] >= 64, "never the block under the floor: " + a[1]);
        }
    }

    @Test
    void theHighestOfThePointsCountsSoOneSolidBlockDoesNotBlackItOut() {
        World w = new World();
        w.otherwise = VehicleLight.pack(0, 0);
        w.put(10, 65, 21, 0, 13);   // only the point ahead (yaw 0 faces +z) is in the open
        assertEquals(VehicleLight.pack(0, 13), VehicleLight.sample(10.5, 64, 20.0, 0, CAR, w));

        // block light and sky light each their own highest: a lamp beside it, the sky over it
        World lamp = new World();
        lamp.otherwise = VehicleLight.pack(0, 9);
        lamp.put(9, 65, 20, 12, 4);
        int l = VehicleLight.sample(10.0, 64, 20.5, 0, CAR, lamp);
        assertEquals(12, VehicleLight.block(l));
        assertEquals(9, VehicleLight.sky(l));
    }

    @Test
    void unknownPointsAreLeftOut() {
        VehicleLight.Lookup w = (x, y, z) -> z > 20 ? -1 : VehicleLight.pack(3, 8);   // the front in an unloaded chunk
        assertEquals(VehicleLight.pack(3, 8), VehicleLight.sample(10.5, 64, 20.0, 0, CAR, w));
        assertEquals(-1, VehicleLight.sample(10.5, 64, 20.0, 0, CAR, (x, y, z) -> -1), "nothing known");
    }

    @Test
    void thePointsStandInsideTheBodyAndTurnWithIt() {
        List<VehicleSpec.Vec> p = VehicleLight.probes(5, 2);
        assertEquals(6, p.size());
        double far = 0, side = 0;
        for (VehicleSpec.Vec v : p) {
            assertTrue(v.up() > 0 && v.up() <= 1.0, "over the floor, at most a block");
            far = Math.max(far, Math.abs(v.forward()));
            side = Math.max(side, Math.abs(v.right()));
        }
        assertEquals(5 / 2.0 * VehicleLight.INSET, far, 1e-9);
        assertEquals(2 / 2.0 * VehicleLight.INSET, side, 1e-9);
        assertTrue(far < 2.5 && side < 1, "inside the body: not felt in a wall it drives along");

        // turned 90 degrees (facing -x), the point ahead is felt on -x
        World w = new World();
        w.otherwise = VehicleLight.pack(0, 0);
        int ahead = (int) Math.floor(10.5 - far);
        w.put(ahead, 65, 20, 0, 11);
        assertEquals(VehicleLight.pack(0, 11), VehicleLight.sample(10.5, 64, 20.5, 90, p, w));
        assertEquals(VehicleLight.pack(0, 0), VehicleLight.sample(10.5, 64, 20.5, 0, p, w));
    }

    @Test
    void approachStepsAtMostAFewLevels() {
        assertEquals(13, VehicleLight.approach(15, 0, 2));
        assertEquals(6, VehicleLight.approach(4, 15, 2));
        assertEquals(9, VehicleLight.approach(10, 9, 2), "the last bit at once");
        assertEquals(7, VehicleLight.approach(7, 7, 2));
    }

    @Test
    void theFirstLightIsShownAtOnceThenItFades() {
        VehicleLight.Shown s = new VehicleLight.Shown();
        assertTrue(s.unset());
        assertFalse(s.fade(), "nothing to fade from");
        assertFalse(s.felt(-1));
        assertTrue(s.unset());

        assertTrue(s.felt(VehicleLight.pack(2, 15)), "the first one shown at once");
        assertEquals(2, s.block());
        assertEquals(15, s.sky());
        assertTrue(s.settled());

        // into a tunnel: dark, but step by step
        assertFalse(s.felt(VehicleLight.pack(0, 0)), "felt only: shown on the next fade");
        int last = s.sky(), steps = 0;
        while (!s.settled()) {
            assertTrue(s.fade());
            assertTrue(last - s.sky() <= VehicleLight.STEP && last - s.sky() > 0, "never a jump");
            last = s.sky();
            steps++;
        }
        assertEquals(0, s.sky());
        assertEquals(0, s.block());
        assertEquals(8, steps, "15 levels at 2 a step");
        assertFalse(s.fade());

        // a jump (it appeared somewhere else) shows the light at once
        assertTrue(s.jump(VehicleLight.pack(14, 15)));
        assertEquals(14, s.block());
        assertTrue(s.settled());
        assertFalse(s.jump(VehicleLight.pack(14, 15)), "nothing changed");
    }

    @Test
    void aLightFeltBackAndForthDoesNotFlicker() {
        // the old way: the block at the origin flipped between the ground (0) and the air (15) as it drove
        VehicleLight.Shown s = new VehicleLight.Shown();
        s.felt(VehicleLight.pack(0, 15));
        int min = 15;
        for (int i = 0; i < 10; i++) {
            s.felt(VehicleLight.pack(0, i % 2 == 0 ? 13 : 15));
            s.fade();
            min = Math.min(min, s.sky());
        }
        assertTrue(min >= 13, "between the two, never black: " + min);
    }

    @Test
    void feltEveryTenTicksWhileActiveLessOftenParked() {
        int active = 0, parked = 0, fades = 0;
        for (long t = 0; t < 400; t++) {
            if (VehicleLight.sampleDue(t, 7, true)) active++;
            if (VehicleLight.sampleDue(t, 7, false)) parked++;
            if (VehicleLight.fadeDue(t, 7)) fades++;
            // a feel is always a fade tick too: the first step comes with it
            if (VehicleLight.sampleDue(t, 7, true) || VehicleLight.sampleDue(t, 7, false)) assertTrue(VehicleLight.fadeDue(t, 7));
        }
        assertEquals(400 / VehicleLight.MOVING_TICKS, active);
        assertEquals(400 / VehicleLight.PARKED_TICKS, parked);
        assertEquals(400 / VehicleLight.FADE_TICKS, fades);
        assertTrue(VehicleLight.PARKED_TICKS > VehicleLight.MOVING_TICKS);

        // spread over the ticks by the id (not every vehicle on the same tick), negative ids too
        int same = 0;
        for (long t = 0; t < 10; t++) if (VehicleLight.sampleDue(t, 1, true) && VehicleLight.sampleDue(t, 2, true)) same++;
        assertEquals(0, same);
        int n = 0;
        for (long t = 0; t < 40; t++) if (VehicleLight.sampleDue(t, -3, true)) n++;
        assertEquals(4, n);
    }
}
