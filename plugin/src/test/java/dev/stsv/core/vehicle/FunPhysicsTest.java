package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 특별 탈것 (FunPhysics): bounces, ollies, hovering, water as a floor, sleds, the banana boat's toss, the rodeo bull. */
class FunPhysicsTest {

    static final DriveInput NONE = DriveInput.NONE;
    static final DriveInput W = VehiclePhysicsTest.W;
    static final DriveInput JUMP = new DriveInput(false, false, false, false, true, false);

    /** A small vehicle (1 x 0.6 x 1.2) with a fun section. */
    static VehicleSpec spec(Map<String, Object> fun) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("v.speed", 6);
        y.set("v.reverse-speed", 2);
        y.set("v.acceleration", 3);
        y.set("v.brake", 8);
        y.set("v.turn-rate", 90);
        y.set("v.turn-speed", 2);
        y.set("v.size", List.of(1.0, 0.6, 1.2));
        y.set("v.wheel-radius", 0.2);
        y.set("v.seats", List.of(Map.of("at", List.of(0.0, 0.5, 0.0))));
        if (fun != null) y.createSection("v.fun", fun);
        return VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true);
    }

    // ------------------------------------------------------------------------------------------
    // The spec
    // ------------------------------------------------------------------------------------------

    @Test
    void withoutAFunSectionNothingChanges() {
        VehicleSpec s = spec(null);
        assertNull(s.fun());
        assertTrue(s.extras() == VehicleSpec.Extras.NONE, "a plain vehicle's extras are none");
        assertNull(s.wheelModel(), "no wheels, no wheel model needed");
    }

    @Test
    void aFunSectionReadsAndSaysWhatIsWrong() {
        FunSpec f = spec(Map.of("hover", Map.of("start", 2, "max", 3), "trail", "glitter", "look-steer", true)).fun();
        assertNotNull(f.hover());
        assertEquals(FunSpec.Trail.GLITTER, f.trail());
        assertTrue(f.lookSteer() && f.usesSpace() && f.airborne());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> spec(Map.of("hover", Map.of("max", 12))));
        assertTrue(e.getMessage().startsWith("vehicle.fun.hover"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> spec(Map.of("hop", Map.of(), "hover", Map.of())), "one way up and down");
        assertThrows(IllegalArgumentException.class, () -> spec(Map.of("buck", Map.of())), "a bull needs its part");
        assertThrows(IllegalArgumentException.class, () -> spec(Map.of("hover", Map.of("pop", true))), "balloons to pop");
        assertThrows(IllegalArgumentException.class, () -> spec(Map.of("trail", "fireworks")));
        assertThrows(IllegalArgumentException.class, () -> spec(Map.of("amphibious", Map.of("land-speed", 9))), "slower on land than on water");
    }

    // ------------------------------------------------------------------------------------------
    // Jumps
    // ------------------------------------------------------------------------------------------

    @Test
    void theLaunchSpeedReachesTheHeight() {
        for (double h : new double[]{0.5, 1, 2.5, 5}) {
            double v = FunPhysics.launchSpeed(h);
            assertEquals(h, FunPhysics.peak(v), 0.01, "a launch for " + h);
        }
        assertTrue(FunPhysics.flightTicks(FunPhysics.launchSpeed(5)) > FunPhysics.flightTicks(FunPhysics.launchSpeed(1)));
    }

    @Test
    void aPumpedBounceGoesHigherUpToItsTop() {
        FunSpec.Hop h = new FunSpec.Hop(0.8, 5, 1, 0.6, 6, "entity.slime.jump");
        assertEquals(1.8, FunPhysics.bounce(h, 0.8, 3), 1e-9, "pressed late in the fall");
        assertEquals(0.8, FunPhysics.bounce(h, 0.8, 50), 1e-9, "not pressed: never under base");
        assertEquals(1.2, FunPhysics.bounce(h, 1.8, 50), 1e-9, "not pressed: it dies down");
        assertEquals(5, FunPhysics.bounce(h, 4.6, 0), 1e-9, "never over max");
    }

    /** Ridden on flat ground: it keeps bouncing; pressing space just before each landing climbs to the top height. */
    @Test
    void thePogoStickBouncesAndPumps() {
        VehicleSpec s = spec(Map.of("hop", Map.of("base", 0.8, "max", 5, "gain", 1, "window", 6)));
        VehiclePhysicsTest.Grid g = VehiclePhysicsTest.flat();
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, 0);
        st.onGround = true;
        FunPhysics.State fs = new FunPhysics.State();
        int landings = 0;
        double top = 0;
        boolean wasOn = true;
        for (int t = 0; t < 900; t++) {
            // press space when falling and about half a block over the ground
            boolean press = !st.onGround && st.vy < 0 && st.y - 64 < 0.6;
            FunPhysics.hop(st, fs, s.fun().hop(), true, press);
            VehiclePhysics.step(st, NONE, s, g, 10);
            FunPhysics.landing(st, fs, wasOn);
            if (fs.landed) landings++;
            wasOn = st.onGround;
            top = Math.max(top, st.y - 64);
            assertTrue(st.y >= 64 - 1e-9, "never into the ground");
        }
        assertTrue(landings > 10, "it keeps bouncing: " + landings);
        assertEquals(5, top, 0.15, "pumped up to its top height");
        assertEquals(5, fs.hopLevel, 1e-9);
        // nobody on it: it stands still
        for (int t = 0; t < 200; t++) {
            FunPhysics.hop(st, fs, s.fun().hop(), false, false);
            VehiclePhysics.step(st, NONE, s, g, 10);
        }
        assertTrue(st.onGround);
        assertEquals(64, st.y, 1e-6);
        assertEquals(0.8, fs.hopLevel, 1e-9, "and starts low again");
    }

    @Test
    void anOllieHopsAndTheBoardSpinsOnceRound() {
        VehicleSpec s = spec(Map.of("ollie", Map.of("height", 1.1, "spin", 360)));
        VehiclePhysicsTest.Grid g = VehiclePhysicsTest.flat();
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, 0);
        st.onGround = true;
        FunPhysics.State fs = new FunPhysics.State();
        double top = 0, most = 0;
        int spinning = 0;
        for (int t = 0; t < 60; t++) {
            FunPhysics.ollie(st, fs, s.fun().ollie(), t == 0 || t == 3);   // a second press mid-air does nothing
            VehiclePhysics.step(st, NONE, s, g, 10);
            FunPhysics.spin(fs);
            top = Math.max(top, st.y - 64);
            most = Math.max(most, fs.spin);
            if (fs.spinLeft > 0) spinning++;
        }
        assertEquals(1.1, top, 0.05, "the hop");
        assertTrue(most > 300 && most < 360, "nearly a whole turn shown: " + most);
        assertEquals(0, fs.spin, "and back where it began");
        assertTrue(spinning > 8, "spun while up: " + spinning);
        assertTrue(st.onGround, "down again");
    }

    // ------------------------------------------------------------------------------------------
    // Hover
    // ------------------------------------------------------------------------------------------

    static VehicleSpec broom() {
        return spec(Map.of("hover", Map.of("start", 1.5, "min", 0.5, "max", 3, "climb", 2, "sink", 2)));
    }

    /** Ridden it rises to its start height and holds it over the ground; space takes it to max, never higher. */
    @Test
    void theBroomHoversOverTheGroundAtMostItsMax() {
        VehicleSpec s = broom();
        FunSpec.Hover h = s.fun().hover();
        VehiclePhysics.Terrain g = FunPhysics.waterFloor(VehiclePhysicsTest.flat(), 0);
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, 0);
        st.onGround = true;
        FunPhysics.State fs = new FunPhysics.State();
        for (int t = 0; t < 80; t++) {
            FunPhysics.aim(fs, h, true, false, false);
            FunPhysics.hover(st, fs, NONE, s, g, 10);
        }
        assertEquals(65.5, st.y, 0.02, "its start height");
        for (int t = 0; t < 200; t++) {
            FunPhysics.aim(fs, h, true, true, false);
            FunPhysics.hover(st, fs, NONE, s, g, 10);
            assertTrue(st.y <= 67 + 1e-6, "never over max");
        }
        assertEquals(67, st.y, 0.02, "held at max with space");
        for (int t = 0; t < 200; t++) {
            FunPhysics.aim(fs, h, true, false, true);
            FunPhysics.hover(st, fs, NONE, s, g, 10);
        }
        assertEquals(64.5, st.y, 0.02, "down to min");
        // nobody on it: down onto the ground, settled
        for (int t = 0; t < 200; t++) {
            FunPhysics.aim(fs, h, false, false, false);
            FunPhysics.hover(st, fs, NONE, s, g, 10);
        }
        assertEquals(64, st.y, 1e-6);
        assertTrue(st.onGround && FunPhysics.hoverSettled(fs, st));
    }

    /** Off a cliff it sinks with the ground (no gliding on at its height), and it crosses water at its height over it. */
    @Test
    void overADropItSinksAndWaterIsGround() {
        VehicleSpec s = broom();
        VehiclePhysicsTest.Grid grid = new VehiclePhysicsTest.Grid().floor(63, -5, 5, -5, 5).floor(53, -5, 5, 6, 60);
        VehiclePhysics.Terrain g = FunPhysics.waterFloor(grid, 0);
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, 0);
        st.onGround = true;
        FunPhysics.State fs = new FunPhysics.State();
        int over = -1;
        for (int t = 0; t < 300 && st.z < 40; t++) {
            FunPhysics.aim(fs, s.fun().hover(), true, true, false);
            FunPhysics.hover(st, fs, W, s, g, 10);
            if (over < 0 && st.z - 0.5 > 6) over = t;   // all of it past the edge
            // it sinks to its height over the low ground within a second and a half (at most a steep glide, never a flight)
            if (over >= 0 && t > over + 30) assertTrue(st.y - 54 <= 3 + 0.05, "not floating over the drop at " + st.z + ": " + st.y);
        }
        assertTrue(st.z > 20, "it flew on");
        assertEquals(57, st.y, 0.05, "max over the low ground");
        // over water: the surface is its ground
        VehiclePhysicsTest.Grid lake = new VehiclePhysicsTest.Grid().floor(58, -5, 5, -5, 60);
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 60; z++) for (int y = 59; y <= 63; y++) lake.medium(x, y, z, VehiclePhysics.Medium.WATER);
        VehiclePhysics.State w = new VehiclePhysics.State(0.5, 64 + FunPhysics.SURFACE - 1, 0.5, 0);
        FunPhysics.State fw = new FunPhysics.State();
        for (int t = 0; t < 100; t++) {
            FunPhysics.aim(fw, s.fun().hover(), true, false, false);
            FunPhysics.hover(w, fw, W, s, FunPhysics.waterFloor(lake, 0), 10);
        }
        assertEquals(63 + FunPhysics.SURFACE + 1.5, w.y, 0.05, "its height over the water's surface");
        assertTrue(w.z > 20, "across the water");
    }

    @Test
    void aPoppedChairFloatsDownSlowlyAndCannotRise() {
        VehicleSpec s = spec(Map.of("hover", Map.of("start", 4, "min", 1, "max", 8, "climb", 1, "pop", true), "part", Map.of("model", "b", "at", List.of(0, 2, 0))));
        FunSpec.Hover h = s.fun().hover();
        FunPhysics.State fs = new FunPhysics.State();
        for (int t = 0; t < 200; t++) FunPhysics.aim(fs, h, true, true, false);
        assertEquals(8, fs.height, 1e-9);
        fs.popped = true;
        FunPhysics.aim(fs, h, true, true, false);
        assertEquals(8 - FunPhysics.POP_SINK * VehiclePhysics.DT, fs.height, 1e-9, "space does nothing: it sinks slowly");
        assertEquals(0, fs.aim, 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // Amphibious, sled, banana boat
    // ------------------------------------------------------------------------------------------

    /** The duck walks down a bank onto the water, floats there with its bottom under the surface, and climbs out again. */
    @Test
    void theDuckWalksIntoTheWaterAndOutAgain() {
        VehicleSpec s = spec(Map.of("amphibious", Map.of("draft", 0.3, "land-speed", 1.2)));
        VehiclePhysicsTest.Grid grid = new VehiclePhysicsTest.Grid().floor(63, -5, 5, -5, 5).floor(60, -5, 5, 6, 20).floor(63, -5, 5, 21, 30);
        for (int x = -5; x <= 5; x++) for (int z = 6; z <= 20; z++) for (int y = 61; y <= 63; y++) grid.medium(x, y, z, VehiclePhysics.Medium.WATER);
        VehiclePhysics.Terrain wf = FunPhysics.waterFloor(grid, 0.3);
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, 0);
        st.onGround = true;
        boolean floated = false;
        for (int t = 0; t < 1200 && st.z < 24; t++) {
            boolean afloat = FunPhysics.overWater(st.x, st.y + 0.05, st.z, grid);
            VehiclePhysics.step(st, W, s, wf, 10, 1f, 1, 10, afloat ? 1 : 1.2 / 6, 1);
            if (st.z > 8 && st.z < 18) {
                assertEquals(63 + FunPhysics.SURFACE - 0.3, st.y, 0.02, "afloat at its draft");
                floated = true;
                assertTrue(st.speed > 1.2 + 0.5, "paddles faster than it walks");
            }
            if (st.z < 4) assertTrue(st.speed <= 1.2 + 1e-6, "slow on land");
        }
        assertTrue(floated);
        assertTrue(st.z >= 24, "out on the far bank: " + st.z);
        assertEquals(64, st.y, 1e-6);
    }

    @Test
    void theSledIsFastOnSnowAndIceSlowElsewhere() {
        FunSpec.Surface surf = new FunSpec.Surface(1, 1, 0.35, 0.4);
        VehicleSpec s = spec(Map.of("surface", Map.of("snow", 1, "ice", 1, "other", 0.35, "downhill", 0.4)));
        VehiclePhysicsTest.Grid grid = new VehiclePhysicsTest.Grid().floor(63, -5, 5, -5, 5);
        VehiclePhysics.Terrain g = new VehiclePhysics.Terrain() {
            @Override
            public void boxes(double a, double b, double c, double d, double e, double f, java.util.List<double[]> out) {
                grid.boxes(a, b, c, d, e, f, out);
            }

            @Override
            public VehiclePhysics.Medium medium(int x, int y, int z) {
                return grid.medium(x, y, z);
            }

            @Override
            public FunPhysics.Ground ground(int x, int y, int z) {
                return y == 63 && z >= 0 ? FunPhysics.Ground.ICE : FunPhysics.Ground.OTHER;
            }
        };
        assertEquals(1, FunPhysics.surfaceFactor(0.5, 64, 2.5, 0, s, surf, g, 0), 1e-9, "on ice");
        assertEquals(0.35, FunPhysics.surfaceFactor(0.5, 64, -3.5, 0, s, surf, g, 0), 1e-9, "on grass");
        assertEquals(1.4, FunPhysics.surfaceFactor(0.5, 64, 2.5, 0, s, surf, g, -1), 1e-9, "faster down a slope");
        assertEquals(1, FunPhysics.surfaceFactor(0.5, 64, 2.5, 0, s, surf, g, 0.5), 1e-9, "uphill: the feel's own uphill loss");
    }

    @Test
    void onlyAHardTurnAtSpeedTossesRiders() {
        FunSpec.Toss t = new FunSpec.Toss(4, 40, 0.03);
        assertEquals(0, FunPhysics.tossChance(t, 3, 90), "too slow");
        assertEquals(0, FunPhysics.tossChance(t, 8, 20), "a gentle turn");
        assertEquals(0.03, FunPhysics.tossChance(t, 4, 40), 1e-9);
        assertTrue(FunPhysics.tossChance(t, 8, 80) > FunPhysics.tossChance(t, 4, 40), "more the faster and harder");
        assertTrue(FunPhysics.tossChance(t, 100, 1000) <= 0.5);
        assertEquals(FunPhysics.tossChance(t, -6, -60), FunPhysics.tossChance(t, 6, 60), 1e-9, "either way round");
    }

    // ------------------------------------------------------------------------------------------
    // Turning, the rodeo bull, the pose
    // ------------------------------------------------------------------------------------------

    @Test
    void turningOnTheSpotAndTowardsTheLook() {
        VehicleSpec s = spec(Map.of("pivot", true));
        assertEquals(-4.5f, FunPhysics.pivotTurn(1, s), 1e-6, "A: left, at the full rate standing still");
        assertEquals(4.5f, FunPhysics.lookTurn(0, 90, s), 1e-6, "towards the look, at most the turn rate");
        assertEquals(-2f, FunPhysics.lookTurn(10, 8, s), 1e-6);
        assertEquals(4.5f, FunPhysics.lookTurn(350, 20, s), 1e-6, "the short way round");
        assertEquals(0f, FunPhysics.lookTurn(10, 10.2f, s), 1e-6);
    }

    /** Ticks a rider lasts on the bull: idle, or leaning back against every lurch (seeded). */
    static int lasts(FunSpec.Buck b, boolean leans, long seed) {
        FunPhysics.State fs = new FunPhysics.State();
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int t = 1; t <= 20 * 600; t++) {
            // spinning right throws them left: D (steer -1) leans them back; nothing to do in a calm
            int steer = !leans ? 0 : fs.balance < -0.05 ? -1 : fs.balance > 0.05 ? 1 : 0;
            FunPhysics.buck(fs, b, steer, rnd);
            if (fs.thrown) return t;
        }
        return Integer.MAX_VALUE;
    }

    @Test
    void theBullThrowsEveryoneSoonerOrLaterAndSkillHelps() {
        FunSpec.Buck b = new FunSpec.Buck(0.35, 0.06, 0.7);
        long idle = 0, skilled = 0;
        for (long seed = 1; seed <= 40; seed++) {
            int i = lasts(b, false, seed), k = lasts(b, true, seed);
            assertTrue(i < 20 * 60, "an idle rider is off within a minute: " + i);
            assertTrue(k < 20 * 180, "everyone is thrown in the end: " + k);
            idle += i;
            skilled += k;
        }
        assertTrue(skilled > idle * 1.5, "keeping one's balance lasts longer: " + skilled / 40 / 20.0 + " s vs " + idle / 40 / 20.0 + " s");
        assertTrue(idle / 40 > 20 * 3, "not thrown at once: " + idle / 40 / 20.0 + " s");
        assertTrue(FunPhysics.power(b, 400) > FunPhysics.power(b, 100), "stronger the longer");
    }

    @Test
    void theBullCalmsWhenNobodyRides() {
        FunPhysics.State fs = new FunPhysics.State();
        FunSpec.Buck b = new FunSpec.Buck(0.35, 0.06, 0.7);
        SplittableRandom rnd = new SplittableRandom(3);
        for (int t = 0; t < 40; t++) FunPhysics.buck(fs, b, 0, rnd);
        for (int t = 0; t < 60; t++) FunPhysics.calm(fs);
        assertEquals(0, fs.buckPitch);
        assertEquals(0, fs.buckLift);
        assertEquals(0, fs.ridden);
    }

    @Test
    void theSegwayTipsForwardSpeedingUpAndBackBraking() {
        VehicleSpec s = spec(Map.of("pivot", true, "tilt", 10));
        assertTrue(FunPhysics.tiltTarget(1, 0.85, s, 10) > 5, "speeding up: forward");
        assertTrue(FunPhysics.tiltTarget(4, 4.4, s, 10) < 0, "braking: back");
        assertTrue(FunPhysics.tiltTarget(6, 6, s, 10) > 0, "cruising: a little forward");
        assertEquals(0, FunPhysics.tiltTarget(0, 0, s, 10), 1e-9, "standing: upright");
        assertTrue(Math.abs(FunPhysics.tiltTarget(10, 0, s, 10)) <= 10 + 1e-9, "never past its tilt");
        // the pose follows a step at a time
        FunPhysics.State fs = new FunPhysics.State();
        VehiclePhysics.State st = new VehiclePhysics.State(0, 64, 0, 0);
        st.speed = 3;
        FunPhysics.Pose p = FunPhysics.pose(fs, s.fun(), st, s, 0);
        assertEquals(FunPhysics.TILT_STEP, p.pitch(), 1e-9);
        assertFalse(p.rest());
        assertTrue(FunPhysics.Pose.REST.rest());
    }

    @Test
    void theWaterFloorLeavesLandAlone() {
        VehiclePhysicsTest.Grid g = VehiclePhysicsTest.flat();
        g.medium(0, 64, 0, VehiclePhysics.Medium.WATER).medium(1, 64, 0, VehiclePhysics.Medium.LAVA);
        VehiclePhysics.Terrain wf = FunPhysics.waterFloor(g, 0.25);
        java.util.List<double[]> out = new java.util.ArrayList<>();
        wf.boxes(0, 64, 0, 0.9, 65, 0.9, out);
        assertEquals(1, out.size(), "one floor box in the water block");
        assertEquals(64 + FunPhysics.SURFACE - 0.25, out.getFirst()[4], 1e-9);
        assertEquals(VehiclePhysics.Medium.AIR, wf.medium(0, 64, 0), "no slowdown, no deep-water hazard");
        assertEquals(VehiclePhysics.Medium.LAVA, wf.medium(1, 64, 0), "lava stays lava");
        assertEquals(FunPhysics.Ground.OTHER, new VehiclePhysicsTest.Grid().ground(0, 0, 0), "only the real world knows snow and ice");
        assertNotNull(NamespacedKey.fromString("stsv:rodeo_best"));
    }
}
