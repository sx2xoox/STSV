package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.stsv.core.vehicle.VehiclePhysicsTest.flat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hand cart following its puller (CartPull): the hitch, turning about its wheels, the world, letting go. */
class CartPullTest {

    static final double HITCH = 1.9;

    /** Like the shipped 리어카: 2.3 x 1.65 x 1.25, two wheels on the middle axle, the puller 1.9 ahead, lets go at 4. */
    static VehicleSpec cart() {
        return new VehicleSpec(2f, 4.3, 2, 3, 8, 8, 90, 1, 1.0, 2.3, 1.65, 1.25, 1.2, new NamespacedKey("t", "model/wheel"), 0.375,
                List.of(new VehicleSpec.Wheel(new VehicleSpec.Vec(-0.72, 0.375, 0), false), new VehicleSpec.Wheel(new VehicleSpec.Vec(0.72, 0.375, 0), false)),
                List.of(), null, null, new VehicleSpec.Cargo(6, 1.2, new VehicleSpec.Vec(0, 0.875, 0), List.of()), null,
                new VehicleSpec.Pull(HITCH, 0.3, 4, 16, null));
    }

    static VehiclePhysics.State at(double x, double z, float yaw) {
        return VehiclePhysicsTest.onGround(x, z, yaw);
    }

    /** Horizontal distance from the cart's centre to a point. */
    static double reach(VehiclePhysics.State st, double x, double z) {
        return Math.hypot(x - st.x, z - st.z);
    }

    @Test
    void theHitchIsOnTheLineFromTheWheelsToThePuller() {
        double[] t = CartPull.target(0, 0, 0, 3, 4, HITCH);
        assertEquals(3 - 0.6 * HITCH, t[0], 1e-5, "1.9 behind the puller, towards where the cart is (float yaw)");
        assertEquals(4 - 0.8 * HITCH, t[1], 1e-5);
        double[] f = VehiclePhysics.forward((float) t[2]);
        assertEquals(0.6, f[0], 1e-6, "facing the puller");
        assertEquals(0.8, f[1], 1e-6);
        // yawOf is forward's inverse, all the way round
        for (float yaw = 0; yaw < 360; yaw += 15) {
            double[] d = VehiclePhysics.forward(yaw);
            assertEquals(0, VehicleSeats.signed(CartPull.yawOf(d[0], d[1]) - yaw), 1e-3, "yaw " + yaw);
        }
        double[] still = CartPull.target(2, 2, 123, 2, 2, HITCH);
        assertEquals(123, still[2], 1e-9, "a puller right on its centre leaves its facing alone");
        // the handles' end is where the puller should stand
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double[] h = CartPull.hitch(st, cart());
        assertEquals(0.5, h[0], 1e-9);
        assertEquals(0.5 + HITCH, h[2], 1e-9, "yaw 0 faces +z");
        assertEquals(64, h[1], 1e-9);
    }

    @Test
    void itFollowsBehindAtTheHitchLikeATrailer() {
        VehicleSpec s = cart();
        var t = flat();
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double px = 0.5, pz = 0.5 + HITCH;
        double roll = st.roll;
        for (int i = 0; i < 60; i++) {
            pz += 0.15;   // walking at 3 blocks a second (walking, 30 % slower)
            CartPull.step(st, px, pz, s, t);
            assertEquals(HITCH, reach(st, px, pz), 1e-6, "the handles stay where the puller is");
            assertEquals(0, VehicleSeats.signed(st.yaw), 1e-4, "straight behind");
            assertEquals(64, st.y, 1e-9, "on the ground");
            assertTrue(st.onGround);
        }
        assertEquals(3.0, st.speed, 1e-6, "its speed is the puller's");
        assertTrue(st.roll != roll, "the wheels rolled");
        assertFalse(CartPull.tooFar(st, s, px, 64, pz));
        assertEquals(0, CartPull.gap(st, s, px, 64, pz), 1e-6);
        // the puller stops: so does the cart (no creeping, no jitter)
        double x = st.x, z = st.z;
        for (int i = 0; i < 5; i++) CartPull.step(st, px, pz, s, t);
        assertEquals(x, st.x, 1e-9);
        assertEquals(z, st.z, 1e-9);
        assertEquals(0, st.speed, "standing: speed 0 (the bed may open once let go)");
    }

    @Test
    void itTurnsAboutItsWheelsRoundACorner() {
        VehicleSpec s = cart();
        var t = flat();
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double px = 0.5, pz = 0.5 + HITCH;
        for (int i = 0; i < 20; i++) {
            pz += 0.15;
            CartPull.step(st, px, pz, s, t);
        }
        // turn right onto -x (Minecraft: yaw 90 faces -x)
        double lastX = st.x, lastZ = st.z;
        for (int i = 0; i < 80; i++) {
            px -= 0.15;
            CartPull.step(st, px, pz, s, t);
            assertTrue(Math.hypot(st.x - lastX, st.z - lastZ) <= CartPull.MAX_MOVE + 1e-9, "never jumps");
            lastX = st.x;
            lastZ = st.z;
            assertEquals(HITCH, reach(st, px, pz), 1e-3, "the hitch follows the puller round the corner");
        }
        assertEquals(90, st.yaw, 1.0, "now facing -x, the way the puller walks");
        assertTrue(st.z < pz + 0.05 && st.z > pz - 0.6, "it cut the corner a little, as a trailer does");
    }

    @Test
    void takingTheHandlesFromTheSideSwingsItRoundSmoothly() {
        VehicleSpec s = cart();
        var t = flat();
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double px = 3.5, pz = 0.5;   // standing beside it
        float lastYaw = st.yaw;
        double lastX = st.x, lastZ = st.z;
        for (int i = 0; i < 30; i++) {
            CartPull.step(st, px, pz, s, t);
            assertTrue(Math.abs(VehicleSeats.signed(st.yaw - lastYaw)) <= CartPull.MAX_TURN + 1e-3, "turns at most 24° a tick");
            assertTrue(Math.hypot(st.x - lastX, st.z - lastZ) <= CartPull.MAX_MOVE + 1e-9, "moves at most 0.6 a tick");
            lastYaw = st.yaw;
            lastX = st.x;
            lastZ = st.z;
        }
        assertEquals(HITCH, reach(st, px, pz), 1e-6, "settled with its handles at the puller");
        assertEquals(VehiclePhysics.wrap(-90), st.yaw, 1e-3, "facing them (+x)");
    }

    @Test
    void walkingBackPushesIt() {
        VehicleSpec s = cart();
        var t = flat();
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        CartPull.step(st, 0.5, 0.5 + HITCH - 0.3, s, t);
        assertEquals(0.2, st.z, 1e-6, "the handles are rigid: the cart goes back");
        assertTrue(st.speed < 0, "rolling backwards");
    }

    @Test
    void itStepsUpABlockAndFallsWithGravity() {
        VehicleSpec s = cart();
        var t = flat();
        for (int x = -4; x <= 4; x++) for (int z = 4; z <= 20; z++) t.block(x, 64, z);   // a one-block ledge from z = 4
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double px = 0.5, pz = 0.5 + HITCH;
        for (int i = 0; i < 60; i++) {
            pz += 0.15;
            CartPull.step(st, px, pz, s, t);
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, t), "never inside a block");
        }
        assertEquals(65, st.y, 1e-6, "up the ledge");
        assertTrue(st.z > 6, "and on along it");

        // off the far edge of a high step: it falls, with gravity, onto the ground below
        var drop = flat();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 2; z++) for (int y = 64; y <= 66; y++) drop.block(x, y, z);
        VehiclePhysics.State high = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        high.y = 67;
        double hz = 0.5 + HITCH;
        boolean fell = false;
        for (int i = 0; i < 80; i++) {
            hz += 0.15;
            CartPull.step(high, 0.5, hz, s, drop);
            if (!high.onGround) fell = true;
            assertFalse(VehiclePhysics.collides(high.x, high.y, high.z, high.yaw, s, drop));
        }
        assertTrue(fell, "it fell off the edge");
        assertEquals(64, high.y, 1e-6, "down on the ground");
    }

    @Test
    void aWallStopsItAndThePullerLetsGoPastTheReleaseDistance() {
        VehicleSpec s = cart();
        var t = flat();
        for (int x = -4; x <= 4; x++) for (int y = 64; y <= 66; y++) t.block(x, y, 5);   // a wall (with a 1-wide door the player used)
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double px = 0.5, pz = 0.5 + HITCH;
        int letGo = -1;
        for (int i = 0; i < 80 && letGo < 0; i++) {
            pz += 0.15;
            CartPull.step(st, px, pz, s, t);
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, t), "never into the wall");
            assertTrue(st.z + s.length() / 2 <= 5 + 1e-6, "stopped at it");
            if (CartPull.tooFar(st, s, px, 64, pz)) letGo = i;
        }
        assertTrue(letGo > 0, "the puller walked on until they let go");
        double gap = CartPull.gap(st, s, px, 64, pz);
        assertTrue(gap > 4 && gap < 4.2, "just past 4 blocks from the handles: " + gap);
    }

    @Test
    void theReleaseDistanceIsMeasuredFromTheHandles() {
        VehicleSpec s = cart();
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double hz = 0.5 + HITCH;
        assertFalse(CartPull.tooFar(st, s, 0.5, 64, hz + 3.99));
        assertTrue(CartPull.tooFar(st, s, 0.5, 64, hz + 4.01), "4 blocks ahead of the handles: let go");
        assertTrue(CartPull.tooFar(st, s, 0.5 + 4.01, 64, hz), "or beside them");
        assertTrue(CartPull.tooFar(st, s, 0.5, 64 + 4.01, hz), "or above (up a ladder)");
        assertFalse(CartPull.tooFar(st, s, 0.5, 64 + 1.25, hz), "a jump is not letting go");
        assertEquals(5, CartPull.gap(st, s, 0.5 + 3, 64, hz + 4), 1e-9);
    }

    @Test
    void itStopsBeforeLavaDeepWaterAndUnloadedChunks() {
        VehicleSpec s = cart();
        // lava ahead
        var lava = flat();
        for (int x = -4; x <= 4; x++) for (int z = 5; z <= 8; z++) lava.medium(x, 64, z, VehiclePhysics.Medium.LAVA);
        assertStopsBefore(s, lava, 5);
        // water two blocks deep ahead (a pond)
        var pond = flat();
        for (int x = -4; x <= 4; x++) {
            for (int z = 5; z <= 8; z++) pond.medium(x, 64, z, VehiclePhysics.Medium.WATER).medium(x, 65, z, VehiclePhysics.Medium.WATER);
        }
        assertStopsBefore(s, pond, 5);
        // an unloaded chunk ahead: a wall to the cart
        var edge = flat();
        edge.unloadedFromX = 3;
        VehiclePhysics.State st = at(0.5, 0.5, VehiclePhysics.wrap(-90));   // facing +x
        double px = 0.5 + HITCH;
        for (int i = 0; i < 60; i++) {
            px += 0.15;
            CartPull.step(st, px, 0.5, s, edge);
        }
        assertEquals(3 - s.length() / 2, st.x, 1e-6, "not into the unloaded chunk: it is a wall");
    }

    private static void assertStopsBefore(VehicleSpec s, VehiclePhysicsTest.Grid t, int z) {
        VehiclePhysics.State st = at(0.5, 0.5, 0);
        double pz = 0.5 + HITCH;
        for (int i = 0; i < 60; i++) {
            pz += 0.15;
            CartPull.step(st, 0.5, pz, s, t);
            assertFalse(VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, s, t), "never onto it");
        }
        assertTrue(st.z + s.length() / 2 <= z + 1e-6, "stopped at its edge, z " + st.z);
        assertTrue(CartPull.tooFar(st, s, 0.5, 64, pz), "and its puller, walking on, let go");
    }
}
