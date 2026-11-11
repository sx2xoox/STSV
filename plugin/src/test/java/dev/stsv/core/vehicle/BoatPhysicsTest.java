package dev.stsv.core.vehicle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 통통배 on a small block world (BoatPhysics): it floats at its draft, drives on water only, stops at the shore without
 * climbing it, cannot move on land (only be nudged off a beach towards the water), slides on ice, and stops before lava,
 * waterfalls, bubble columns and drops.
 */
class BoatPhysicsTest {

    static final DriveInput W = new DriveInput(true, false, false, false, false, false);
    static final DriveInput S = new DriveInput(false, true, false, false, false, false);
    static final DriveInput NONE = DriveInput.NONE;
    /** water source surface inside its block */
    static final double SOURCE = 8 / 9.0;

    /** The shipped boat (content/stsv/items/vehicles_misc.yml). */
    static VehicleSpec boat() {
        Path pack = Path.of(System.getProperty("stsv.content")).resolve("stsv");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pack.resolve("items/vehicles_misc.yml").toFile());
        return VehicleSpec.parse(y.getConfigurationSection("fishing_boat.vehicle"), "stsv",
                m -> Files.exists(pack.resolve("models/" + m + ".bbmodel")));
    }

    /** Blocks by position: solid cubes, and what each block is to a boat (water levels, ice, lava, falls...). */
    static final class Sea implements BoatPhysics.Water {
        final Map<Long, BoatPhysics.Cell> cells = new HashMap<>();
        final Map<Long, Double> levels = new HashMap<>();

        static long k(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }

        Sea fill(BoatPhysics.Cell c, int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) cells.put(k(x, y, z), c);
            return this;
        }

        Sea level(double level, int x0, int x1, int y, int z0, int z1) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) levels.put(k(x, y, z), level);
            return this;
        }

        @Override
        public BoatPhysics.Cell cell(int x, int y, int z) {
            return cells.getOrDefault(k(x, y, z), BoatPhysics.Cell.AIR);
        }

        @Override
        public double level(int x, int y, int z) {
            return levels.getOrDefault(k(x, y, z), SOURCE);
        }

        @Override
        public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
                for (int y = (int) Math.floor(minY); y <= (int) Math.floor(maxY); y++)
                    for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
                        BoatPhysics.Cell c = cell(x, y, z);
                        if (c == BoatPhysics.Cell.SOLID || c == BoatPhysics.Cell.ICE) out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                    }
        }

        @Override
        public VehiclePhysics.Medium medium(int x, int y, int z) {
            return switch (cell(x, y, z)) {
                case WATER, FALLING, BUBBLE -> VehiclePhysics.Medium.WATER;
                case LAVA -> VehiclePhysics.Medium.LAVA;
                default -> VehiclePhysics.Medium.AIR;
            };
        }
    }

    /** A lake: a stone bed at y 60, water y 61..63 (its surface at 63 + 8/9), x -20..20, z -20..60. */
    static Sea lake() {
        return new Sea().fill(BoatPhysics.Cell.SOLID, -30, 30, 60, 60, -30, 80).fill(BoatPhysics.Cell.WATER, -20, 20, 61, 63, -20, 60);
    }

    static double floatY(VehicleSpec s) {
        return 63 + SOURCE - s.water().draft();
    }

    static VehiclePhysics.State afloat(VehicleSpec s, double x, double z, float yaw) {
        return new VehiclePhysics.State(x, floatY(s), z, yaw);
    }

    static BoatPhysics.Mode run(VehiclePhysics.State st, DriveInput in, VehicleSpec s, BoatPhysics.Water w, int ticks) {
        BoatPhysics.Mode m = null;
        for (int i = 0; i < ticks; i++) m = BoatPhysics.step(st, in, s, w, 10);
        return m;
    }

    @Test
    void itFloatsAtItsDraftAndStaysThere() {
        VehicleSpec s = boat();
        Sea sea = lake();
        assertEquals(floatY(s), BoatPhysics.launchHeight(0.5, 63, 0.5, s, sea), 1e-9, "launched with its keel draft under the surface");
        assertTrue(BoatPhysics.canLaunch(0.5, floatY(s), 0.5, 0, s, sea));
        VehiclePhysics.State st = afloat(s, 0.5, 0.5, 0);
        assertEquals(BoatPhysics.Mode.FLOAT, run(st, NONE, s, sea, 100));
        assertEquals(floatY(s), st.y, 1e-9, "left alone it stays at the water's level");
        assertEquals(0.5, st.x, 1e-9);
        assertEquals(0.5, st.z, 1e-9, "and does not drift");

        // put a little low or high (the water rose, a reload): it eases back to the surface, a little a tick
        VehiclePhysics.State low = new VehiclePhysics.State(0.5, floatY(s) - 0.4, 0.5, 0);
        BoatPhysics.step(low, NONE, s, sea, 10);
        assertTrue(low.y > floatY(s) - 0.4 && low.y < floatY(s), "rising, not jumping: " + low.y);
        run(low, NONE, s, sea, 60);
        assertEquals(floatY(s), low.y, 1e-6);
        VehiclePhysics.State high = new VehiclePhysics.State(0.5, floatY(s) + 0.3, 0.5, 0);
        run(high, NONE, s, sea, 60);
        assertEquals(floatY(s), high.y, 1e-6, "settles back down");
        assertEquals(0.375, s.water().draft(), 1e-9, "the model's waterline (6 px)");
    }

    @Test
    void itDrivesOnWaterAtSevenBlocksASecond() {
        VehicleSpec s = boat();
        assertEquals(7, s.speed(), 1e-9);
        VehiclePhysics.State st = afloat(s, 0.5, -15.5, 0);
        run(st, W, s, lake(), 200);
        assertEquals(7, st.speed, 1e-9, "top speed on water");
        assertTrue(st.z > 0, "went along +z: " + st.z);
        assertEquals(floatY(s), st.y, 1e-6, "still afloat");
        assertTrue(st.roll != 0, "the propeller turns");
    }

    @Test
    void theShoreStopsItAndItDoesNotClimb() {
        VehicleSpec s = boat();
        // a bank of solid ground from z 40 on, standing a block out of the water (y 61..64)
        Sea sea = lake().fill(BoatPhysics.Cell.SOLID, -30, 30, 61, 64, 40, 80);
        VehiclePhysics.State st = afloat(s, 0.5, 10.5, 0);
        boolean bumped = false;
        for (int i = 0; i < 300; i++) {
            BoatPhysics.step(st, W, s, sea, 10);
            bumped |= st.bumped;
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, sea), "never inside the bank (tick " + i + ")");
        }
        assertTrue(st.z <= 40 - s.length() / 2 + 1e-6, "stopped at the bank: " + st.z);
        assertTrue(st.z > 40 - s.length() / 2 - 0.5, "right at it: " + st.z);
        assertEquals(floatY(s), st.y, 1e-6, "did not climb it");
        assertEquals(0, st.speed, 1e-9);
        assertTrue(bumped, "hit it");

        // a low beach: land at the water's level with no water over it (the keel would clear it): it still stops
        Sea beach = new Sea().fill(BoatPhysics.Cell.SOLID, -30, 30, 60, 60, -30, 80).fill(BoatPhysics.Cell.WATER, -20, 20, 61, 63, -20, 30)
                .fill(BoatPhysics.Cell.SOLID, -30, 30, 61, 62, 31, 80);
        VehiclePhysics.State b = afloat(s, 0.5, 10.5, 0);
        run(b, W, s, beach, 300);
        assertTrue(b.z + s.length() / 2 <= 31 + 0.5, "its bow no further than over the beach's edge: " + b.z);
        assertEquals(floatY(s), b.y, 1e-6, "afloat");
    }

    @Test
    void onLandItCannotMoveButCanBeNudgedOffABeachTowardsTheWater() {
        VehicleSpec s = boat();
        // no water at all: dry ground at y 63 (top 64)
        Sea dry = new Sea().fill(BoatPhysics.Cell.SOLID, -30, 30, 60, 63, -30, 80);
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, 0);
        assertEquals(BoatPhysics.Mode.AGROUND, run(st, W, s, dry, 100));
        assertEquals(0.5, st.z, 1e-9, "on land it does not move");
        assertEquals(64, st.y, 1e-9, "resting on the ground");

        // a beach: the boat's middle on sand (top 64) behind it, water in front (z < 0): nudged forward slowly only
        Sea beach = new Sea().fill(BoatPhysics.Cell.SOLID, -30, 30, 60, 60, -30, 80)
                .fill(BoatPhysics.Cell.WATER, -20, 20, 61, 63, -20, -1).fill(BoatPhysics.Cell.SOLID, -30, 30, 61, 63, 0, 80);
        VehiclePhysics.State b = new VehiclePhysics.State(0.5, 64, 1.0, 180);   // facing -z, towards the water
        BoatPhysics.Hull h = BoatPhysics.hull(b.x, b.y, b.z, b.yaw, s, beach);
        assertEquals(BoatPhysics.Mode.AGROUND, h.mode());
        assertTrue(h.water > 0 && h.land > 0, "half on the beach");
        VehiclePhysics.State back = new VehiclePhysics.State(0.5, 64, 1.0, 180);
        run(back, S, s, beach, 60);
        assertEquals(1.0, back.z, 1e-9, "not further up the beach");
        double max = 0;
        BoatPhysics.Mode m = null;
        for (int i = 0; i < 200 && m != BoatPhysics.Mode.FLOAT; i++) {
            m = BoatPhysics.step(b, W, s, beach, 10);
            max = Math.max(max, Math.abs(b.speed));
        }
        assertEquals(BoatPhysics.Mode.FLOAT, m, "pushed off into the water: " + b.z);
        assertTrue(max <= s.water().nudge() + 1e-9, "slowly (nudge-speed): " + max);
        run(b, W, s, beach, 60);
        run(b, NONE, s, beach, 120);
        assertEquals(floatY(s), b.y, 1e-6, "and once clear of the beach it floats");
    }

    @Test
    void onIceItSlidesALittle() {
        VehicleSpec s = boat();
        // the lake frozen over from z 20 on (ice at the surface block, y 63)
        Sea sea = lake().fill(BoatPhysics.Cell.ICE, -20, 20, 63, 63, 20, 60);
        VehiclePhysics.State st = afloat(s, 0.5, 0.5, 0);
        BoatPhysics.Mode m = null;
        for (int i = 0; i < 200 && m != BoatPhysics.Mode.ICE; i++) m = BoatPhysics.step(st, W, s, sea, 10);
        assertEquals(BoatPhysics.Mode.ICE, m, "driven onto the ice: " + st.z);
        run(st, NONE, s, sea, 20);
        assertEquals(64, st.y, 1e-6, "lying on the ice");
        assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, sea));
        // gliding: with no key it slows down slowly (far slower than on water)
        st.speed = 3;
        run(st, NONE, s, sea, 20);
        assertTrue(st.speed > 2.5, "slides on: " + st.speed);
        VehiclePhysics.State water = afloat(s, 0.5, -10.5, 0);
        water.speed = 3;
        run(water, NONE, s, lake(), 20);
        assertTrue(water.speed < st.speed, "on water it slows down faster");
        // the propeller barely pushes on ice
        VehiclePhysics.State push = new VehiclePhysics.State(0.5, 64, 40.5, 0);
        run(push, W, s, sea, 20);
        assertTrue(push.speed <= s.speed() * BoatPhysics.ICE_TOP + 1e-9 && push.speed < 0.6, "weak push: " + push.speed);
        assertTrue(BoatPhysics.iceSpeed(0, false, false, s, 10) == 0);
    }

    @Test
    void itNeverGoesOntoLavaDownAWaterfallIntoABubbleColumnOrOffADrop() {
        VehicleSpec s = boat();
        for (BoatPhysics.Cell bad : List.of(BoatPhysics.Cell.LAVA, BoatPhysics.Cell.FALLING, BoatPhysics.Cell.BUBBLE)) {
            Sea sea = lake().fill(bad, -20, 20, 61, 63, 30, 60);
            VehiclePhysics.State st = afloat(s, 0.5, 10.5, 0);
            run(st, W, s, sea, 300);
            assertTrue(st.z + s.length() / 2 <= 30 + 0.5, bad + ": stopped before it, " + st.z);
            assertEquals(floatY(s), st.y, 1e-6, bad + ": still afloat");
            assertEquals(0, st.speed, 1e-9, bad.name());
        }
        // the water ends at a cliff (nothing under the next blocks)
        Sea cliff = new Sea().fill(BoatPhysics.Cell.SOLID, -30, 30, 60, 60, -30, 30).fill(BoatPhysics.Cell.WATER, -20, 20, 61, 63, -20, 30);
        VehiclePhysics.State st = afloat(s, 0.5, 10.5, 0);
        run(st, W, s, cliff, 300);
        assertTrue(st.z + s.length() / 2 <= 31 + 0.5, "stopped at the edge: " + st.z);
        assertEquals(floatY(s), st.y, 1e-6, "did not fall");
    }

    @Test
    void thinFlowingWaterIsTooShallowAndLaunchingNeedsOpenWater() {
        VehicleSpec s = boat();
        // a film of flowing water (level 6: 2/9 of a block) on dry ground ahead
        Sea sea = lake().fill(BoatPhysics.Cell.SOLID, -30, 30, 61, 62, 30, 60).fill(BoatPhysics.Cell.WATER, -20, 20, 63, 63, 30, 60)
                .level(2 / 9.0, -20, 20, 63, 30, 60);
        assertEquals(BoatPhysics.Under.LAND, BoatPhysics.column(sea, 0, 40, floatY(s) + s.water().draft()).under());
        VehiclePhysics.State st = afloat(s, 0.5, 10.5, 0);
        run(st, W, s, sea, 300);
        assertTrue(st.z + s.length() / 2 <= 30 + 0.5, "stopped before the shallows: " + st.z);
        // a 1-block-wide channel is too narrow to put it in; open water is fine; dry land is not water at all
        Sea channel = new Sea().fill(BoatPhysics.Cell.SOLID, -30, 30, 60, 63, -30, 30).fill(BoatPhysics.Cell.WATER, 1, 1, 61, 63, -30, 30);
        assertFalse(BoatPhysics.canLaunch(1, floatY(s), 0.5, 0, s, channel), "its hull reaches over the banks");
        assertTrue(Double.isNaN(BoatPhysics.launchHeight(0.5, 63, 0.5, s, new Sea().fill(BoatPhysics.Cell.SOLID, -5, 5, 60, 63, -5, 5))));
    }
}
