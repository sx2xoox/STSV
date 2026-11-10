package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Driving physics on a small block world: speed, turning, walls, steps, gravity, hazards. */
class VehiclePhysicsTest {

    /** A car-like spec: 9 b/s, 3 b/s², brake 9, coast 2, 72°/s from 3 b/s, step 1, 4.6 x 1.9 x 1.65. */
    static VehicleSpec car() {
        return new VehicleSpec(2f, 9, 3.5, 3, 9, 2, 72, 3, 1.0, 4.6, 1.9, 1.65, 1.1,
                new NamespacedKey("t", "model/wheel"), 0.344, List.of(), List.of(new VehicleSpec.Vec(-0.45, 0.28, 0.15)), null, null, null, null);
    }

    /** Blocks by position: full blocks, or a box {minY, maxY} inside the cell (a slab), and media. */
    static final class Grid implements VehiclePhysics.Terrain {
        final Map<Long, double[]> solid = new HashMap<>();
        final Map<Long, VehiclePhysics.Medium> media = new HashMap<>();
        int unloadedFromX = Integer.MAX_VALUE;

        static long k(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }

        Grid floor(int y, int x0, int x1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) solid.put(k(x, y, z), new double[]{0, 1});
            return this;
        }

        Grid block(int x, int y, int z) {
            solid.put(k(x, y, z), new double[]{0, 1});
            return this;
        }

        Grid slab(int x, int y, int z) {
            solid.put(k(x, y, z), new double[]{0, 0.5});
            return this;
        }

        Grid medium(int x, int y, int z, VehiclePhysics.Medium m) {
            media.put(k(x, y, z), m);
            return this;
        }

        @Override
        public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
                for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++)
                    for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
                        if (x >= unloadedFromX) {
                            out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                            continue;
                        }
                        double[] b = solid.get(k(x, y, z));
                        if (b != null) out.add(new double[]{x, y + b[0], z, x + 1, y + b[1], z + 1});
                    }
        }

        @Override
        public VehiclePhysics.Medium medium(int x, int y, int z) {
            return media.getOrDefault(k(x, y, z), VehiclePhysics.Medium.AIR);
        }
    }

    static final DriveInput W = new DriveInput(true, false, false, false, false, false);
    static final DriveInput S = new DriveInput(false, true, false, false, false, false);
    static final DriveInput WA = new DriveInput(true, false, true, false, false, false);

    /** A flat world: ground at y = 63 (top 64) over a large area. */
    static Grid flat() {
        return new Grid().floor(63, -40, 40, -40, 80);
    }

    static VehiclePhysics.State onGround(double x, double z, float yaw) {
        VehiclePhysics.State st = new VehiclePhysics.State(x, 64, z, yaw);
        st.onGround = true;
        return st;
    }

    @Test
    void wSpeedsUpToTheTopSpeedAndNoFurther() {
        VehicleSpec s = car();
        double v = 0;
        int ticks = 0;
        while (v < 9 && ticks < 400) {
            v = VehiclePhysics.nextSpeed(v, true, false, s, 1, 10);
            ticks++;
        }
        assertEquals(9, v, 1e-9);
        assertTrue(ticks > 60 && ticks < 160, "0 -> 9 b/s in about 3-8 seconds, not " + ticks + " ticks");
        assertEquals(9, VehiclePhysics.nextSpeed(9, true, false, s, 1, 10), 1e-9, "held at the top speed");
        assertEquals(6, VehiclePhysics.nextSpeed(6, true, false, s, 1, 6), 1e-9, "the server's cap wins");
        assertTrue(VehiclePhysics.nextSpeed(9, true, false, s, 0.4, 10) < 9, "in water it slows to the lower top speed");
    }

    @Test
    void sBrakesThenReverses() {
        VehicleSpec s = car();
        double v = 8;
        v = VehiclePhysics.nextSpeed(v, false, true, s, 1, 10);
        assertEquals(8 - 9 * 0.05, v, 1e-9, "brakes at 9 b/s²");
        int ticks = 0;
        while (v > 0) {
            v = VehiclePhysics.nextSpeed(v, false, true, s, 1, 10);
            ticks++;
        }
        assertEquals(0, v);
        assertTrue(ticks < 20, "stops within a second");
        for (int i = 0; i < 200; i++) v = VehiclePhysics.nextSpeed(v, false, true, s, 1, 10);
        assertEquals(-3.5, v, 1e-9, "then reverses up to the reverse speed");
        assertTrue(VehiclePhysics.nextSpeed(-3, true, false, s, 1, 10) > -3, "W while reversing brakes");
        assertEquals(2 - 2 * 0.05, VehiclePhysics.nextSpeed(2, false, false, s, 1, 10), 1e-9, "no key: coasts down");
        assertEquals(5 - 9 * 0.05, VehiclePhysics.nextSpeed(5, true, true, s, 1, 10), 1e-9, "both keys brake");
    }

    @Test
    void turningNeedsSpeedAndScalesWithIt() {
        VehicleSpec s = car();
        assertEquals(0f, VehiclePhysics.yawRate(0, 1, s), "no turning in place");
        assertEquals(0f, VehiclePhysics.yawRate(5, 0, s));
        float slow = VehiclePhysics.yawRate(1.5, 1, s), full = VehiclePhysics.yawRate(3, 1, s), fast = VehiclePhysics.yawRate(9, 1, s);
        assertEquals(-72 * 0.05 / 2, slow, 1e-6, "half the turn speed: half the rate");
        assertEquals(-72 * 0.05, full, 1e-6);
        assertEquals(full, fast, 1e-6, "capped at the turn rate");
        assertTrue(full < 0, "A going forward turns left: yaw goes down (Minecraft yaw grows clockwise)");
        assertTrue(VehiclePhysics.yawRate(-3, 1, s) > 0, "reversing turns the other way");
        assertEquals(6, VehiclePhysics.nextSteer(0, 1), 1e-9, "front wheels turn towards the input");
        assertEquals(VehiclePhysics.MAX_STEER, VehiclePhysics.nextSteer(29, 1), 1e-9);
        assertEquals(24, VehiclePhysics.nextSteer(30, 0), 1e-9, "and back to straight");
    }

    @Test
    void drivesAlongItsYawOnFlatGround() {
        Grid g = flat();
        VehiclePhysics.State st = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 100; i++) VehiclePhysics.step(st, W, car(), g, 10);
        assertTrue(st.z > 15, "yaw 0 drives towards +z: " + st.z);
        assertEquals(0.5, st.x, 1e-9);
        assertEquals(64, st.y, 1e-9, "stays on the ground");
        assertTrue(st.onGround);
        assertTrue(st.roll != 0, "the wheels roll");
        VehiclePhysics.State turn = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 60; i++) VehiclePhysics.step(turn, WA, car(), g, 10);
        assertTrue(turn.yaw > 180 && turn.yaw < 360, "turned left (yaw went below 0 and wrapped): " + turn.yaw);
        assertTrue(turn.x > 0.5, "left of south is east (+x)");
    }

    @Test
    void aWallStopsItWithoutGoingIn() {
        Grid g = flat();
        for (int x = -5; x <= 5; x++) for (int y = 64; y <= 65; y++) g.block(x, y, 12);
        VehiclePhysics.State st = onGround(0.5, 0.5, 0);
        boolean bumped = false;
        for (int i = 0; i < 200; i++) {
            VehiclePhysics.step(st, W, car(), g, 10);
            bumped |= st.bumped;
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, car(), g), "never inside a block (tick " + i + ")");
        }
        assertEquals(12 - 2.3, st.z, 1e-6, "its front (half of 4.6 ahead) touches the wall");
        assertEquals(0, st.speed, 1e-9, "stopped");
        assertTrue(bumped, "hit it hard");
        assertEquals(64, st.y, 1e-9, "a two-block wall is no step");
    }

    @Test
    void stepsUpBlocksAndSlabs() {
        Grid g = flat();
        for (int x = -5; x <= 5; x++) for (int z = 10; z <= 30; z++) g.block(x, 64, z);
        for (int x = -5; x <= 5; x++) for (int z = 31; z <= 50; z++) g.block(x, 64, z).slab(x, 65, z);
        VehiclePhysics.State st = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 80 && st.z < 25; i++) VehiclePhysics.step(st, W, car(), g, 10);
        assertEquals(65, st.y, 1e-9, "up a one-block ledge");
        for (int i = 0; i < 80 && st.z < 45; i++) VehiclePhysics.step(st, W, car(), g, 10);
        assertEquals(65.5, st.y, 1e-9, "and a slab");
        assertTrue(st.z >= 45, "without stopping: " + st.z);
    }

    @Test
    void fallsOffAnEdgeAndFollowsSmallStepsDown() {
        Grid g = new Grid().floor(63, -10, 10, -10, 10).floor(60, -10, 10, 11, 60);
        VehiclePhysics.State st = onGround(0.5, 0.5, 0);
        boolean fell = false;
        for (int i = 0; i < 120; i++) {
            VehiclePhysics.step(st, W, car(), g, 10);
            fell |= !st.onGround;
        }
        assertTrue(fell, "it left the ground at the edge");
        assertEquals(61, st.y, 1e-9, "landed three blocks down");
        assertTrue(st.onGround);

        Grid stairs = new Grid().floor(63, -10, 10, -10, 10);
        for (int x = -10; x <= 10; x++) for (int z = 11; z <= 40; z++) stairs.slab(x, 63, z);
        VehiclePhysics.State down = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 120; i++) {
            VehiclePhysics.step(down, W, car(), stairs, 10);
            assertTrue(down.onGround, "half a block down is followed, not fallen (tick " + i + ")");
        }
        assertEquals(63.5, down.y, 1e-9);
    }

    @Test
    void lavaDeepWaterAndUnloadedChunksStopIt() {
        Grid g = flat();
        for (int x = -5; x <= 5; x++) for (int z = 14; z <= 16; z++) g.medium(x, 63, z, VehiclePhysics.Medium.LAVA);
        VehiclePhysics.State st = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 200; i++) VehiclePhysics.step(st, W, car(), g, 10);
        assertTrue(st.z + 2.3 <= 14 + 1e-9, "stops before the lava: front at " + (st.z + 2.3));
        assertFalse(VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, car(), g));

        Grid w = flat();
        for (int x = -5; x <= 5; x++) for (int z = 14; z <= 20; z++) {
            w.medium(x, 64, z, VehiclePhysics.Medium.WATER).medium(x, 65, z, VehiclePhysics.Medium.WATER);
        }
        VehiclePhysics.State sw = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 200; i++) VehiclePhysics.step(sw, W, car(), w, 10);
        assertTrue(sw.z + 2.3 <= 14 + 1e-9, "no driving into deep water");

        Grid shallow = flat();
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 60; z++) shallow.medium(x, 64, z, VehiclePhysics.Medium.WATER);
        VehiclePhysics.State ss = onGround(0.5, 0.5, 0);
        for (int i = 0; i < 200; i++) VehiclePhysics.step(ss, W, car(), shallow, 10);
        assertEquals(9 * 0.4, ss.speed, 1e-9, "shallow water: 40 % of the top speed");

        Grid edge = flat();
        edge.unloadedFromX = 6;
        VehiclePhysics.State se = onGround(0.5, 0.5, 270);   // yaw 270 = towards +x
        for (int i = 0; i < 200; i++) VehiclePhysics.step(se, W, car(), edge, 10);
        assertEquals(6 - 2.3, se.x, 1e-6, "an unloaded chunk is a wall");
    }

    @Test
    void aTurnIntoAWallIsNotMade() {
        Grid g = flat();
        // a corridor just wider than the car: turning would put its corners into the walls
        for (int z = -10; z <= 40; z++) for (int y = 64; y <= 65; y++) g.block(-1, y, z).block(2, y, z);
        VehiclePhysics.State st = onGround(1.0, 0.5, 0);
        for (int i = 0; i < 60; i++) {
            VehiclePhysics.step(st, WA, car(), g, 10);
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, car(), g));
        }
        assertTrue(st.yaw == 0f || st.yaw > 355f, "kept straight between the walls: " + st.yaw);
        assertTrue(st.z > 5, "and still drives on: " + st.z);
    }

    @Test
    void footprintCoversTheLengthAndTurnsWithIt() {
        VehicleSpec s = car();
        assertEquals(3, s.squares());
        List<double[]> fp = VehiclePhysics.footprint(0, 64, 0, 0, s);
        assertEquals(-2.3, fp.getFirst()[2], 1e-9, "rear square reaches the back");
        assertEquals(2.3, fp.getLast()[5], 1e-9, "front square reaches the front");
        List<double[]> side = VehiclePhysics.footprint(0, 64, 0, 90, s);   // yaw 90 = towards -x
        assertEquals(2.3, side.getFirst()[3], 1e-9, "turned: the rear is at +x");
        double[] driver = VehiclePhysics.world(0, 64, 0, 0, new VehicleSpec.Vec(-0.45, 0.28, 0.15));
        assertEquals(0.45, driver[0], 1e-9, "facing +z, the left (driver's) side is +x (east)");
        assertEquals(64.28, driver[1], 1e-9);
        assertEquals(0.15, driver[2], 1e-9);
    }

    @Test
    void aClickTellsTheBedFromTheCab() {
        VehicleSpec s = car();
        // standing behind the vehicle (yaw 0 faces +z, so behind = -z), looking at it
        double f = VehiclePhysics.hitForward(0, 64, 0, 0, s, 0, 65.6, -5, 0, -0.2, 1);
        assertEquals(-2.3, f, 1e-6, "hits its back");
        double side = VehiclePhysics.hitForward(0, 64, 0, 0, s, 4, 65, 1.5, -1, -0.1, 0);
        assertEquals(1.5, side, 1e-6, "from the side: where along it");
        assertTrue(Double.isNaN(VehiclePhysics.hitForward(0, 64, 0, 0, s, 4, 65, 1.5, 1, 0, 0)), "looking away misses");
    }

    @Test
    void aParkedVehicleNoticesTheGroundGoing() {
        Grid g = flat();
        VehiclePhysics.State st = onGround(0.5, 0.5, 0);
        assertTrue(VehiclePhysics.supported(st, car(), g));
        assertFalse(VehiclePhysics.supported(onGround(0.5, 200, 0), car(), g), "nothing under it");
    }
}
