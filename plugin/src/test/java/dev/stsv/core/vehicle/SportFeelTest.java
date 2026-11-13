package dev.stsv.core.vehicle;

import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sports car (컨버터블 스포츠카, an event vehicle): faster than the server's top speed, no bed, and its feel of
 * speed (SportFeel): the driver's view widening, the exhaust popping on lift-off, smoke on a launch.
 */
class SportFeelTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    static VehicleSpec car() {
        ConfigurationSection s = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile()).getConfigurationSection("sports_car.vehicle");
        assertNotNull(s);
        return VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
    }

    @Test
    void theCarIsFastOpenAndCarriesNothing() {
        VehicleSpec car = car();
        assertEquals(14, car.speed(), 1e-9);
        assertTrue(car.extras().overCap(), "it goes over the server's top speed");
        assertEquals(14, car.cap(10), 1e-9, "its own speed where the server's is 10");
        assertEquals(16, car.cap(16), 1e-9, "the server's when that is higher");
        assertEquals(2, car.seats().size(), "two seats");
        assertNull(car.cargo(), "no bed, no boot");
        assertNull(car.train());
        // every other driven vehicle stays under the server's cap
        for (String file : List.of("vehicles.yml", "vehicles_road.yml", "vehicles_farm.yml", "vehicles_misc.yml")) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/" + file).toFile());
            for (String id : y.getKeys(false)) {
                ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
                if (s == null || id.equals("sports_car") || id.equals("tuner_car") || id.equals("hyper_car")) continue;
                VehicleSpec v = VehicleSpec.parse(s, "stsv", m -> true);
                assertFalse(v.extras().overCap(), id);
                assertTrue(v.acceleration() < car.acceleration(), id + " gets going slower than the sports car");
                assertTrue(v.speed() < car.speed(), id + " is slower");
            }
        }
        // it reaches twelve blocks a second within three seconds on the flat
        var t = VehiclePhysicsTest.flat();
        VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        for (int i = 0; i < 60; i++) VehiclePhysics.step(st, VehiclePhysicsTest.W, car, t, car.cap(10), 1f, 1, car.cap(10));
        assertTrue(st.speed > 12, "after 3 s: " + st.speed);
        for (int i = 0; i < 100; i++) VehiclePhysics.step(st, VehiclePhysicsTest.W, car, t, car.cap(10), 1f, 1, car.cap(10));
        assertEquals(14, st.speed, 1e-6, "flat out");
    }

    @Test
    void aSpeedOverTheHardLimitIsRefused() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile());
        ConfigurationSection s = y.getConfigurationSection("sports_car.vehicle");
        s.set("speed", 25);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(s, "stsv", m -> true)).getMessage().contains("over-cap"));
    }

    @Test
    void theViewWidensWithTheSpeed() {
        assertEquals(0, SportFeel.amount(0, 14, 0.3), 1e-9, "standing: as usual");
        assertEquals(0.6, SportFeel.amount(14, 14, 0.3), 1e-9, "flat out: the game halves it to +30 %");
        assertEquals(1.3, SportFeel.view(SportFeel.amount(14, 14, 0.3)), 1e-9);
        assertEquals(0.6, SportFeel.amount(20, 14, 0.3), 1e-9, "never more");
        assertEquals(SportFeel.amount(7, 14, 0.3), SportFeel.amount(-7, 14, 0.3), 1e-9, "backwards the same");
        assertEquals(0, SportFeel.amount(14, 14, 0), 1e-9, "a car without it");
        double last = -1;
        java.util.Set<Double> steps = new java.util.TreeSet<>();
        for (double v = 0; v <= 14; v += 0.05) {
            double a = SportFeel.amount(v, 14, 0.3);
            assertTrue(a >= last - 1e-12, "never narrower as it speeds up");
            last = a;
            steps.add(a);
        }
        assertEquals(SportFeel.STEPS + 1, steps.size(), "a few steps, not an update every tick");
        assertTrue(SportFeel.amount(7, 14, 0.3) < 0.3, "most of it comes near the top");

        PullSpeedTest.Speed speed = new PullSpeedTest.Speed();
        assertFalse(SportFeel.apply(speed, 0), "nothing to put on at a stand");
        assertTrue(SportFeel.apply(speed, 0.3));
        assertFalse(SportFeel.apply(speed, 0.3), "the same again: no update");
        AttributeModifier m = speed.getModifier(SportFeel.KEY);
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, m.getOperation());
        assertEquals(0.3, m.getAmount(), 1e-9);
        assertFalse(speed.saved.contains(SportFeel.KEY), "transient: never saved with the player");
        assertTrue(SportFeel.apply(speed, 0.6));
        assertEquals(0.6, speed.getModifier(SportFeel.KEY).getAmount(), 1e-9);
        assertTrue(SportFeel.apply(speed, 0), "stopped: taken off");
        assertNull(speed.getModifier(SportFeel.KEY));
        assertFalse(SportFeel.remove(speed));
    }

    @Test
    void liftingOffAtSpeedPopsTheExhaust() {
        SportFeel.Throttle th = new SportFeel.Throttle();
        // a short stab of throttle: nothing
        for (int i = 0; i < SportFeel.HELD - 1; i++) assertFalse(th.step(true, 10, 7, true, true));
        assertFalse(th.step(false, 10, 7, true, true), "not held long enough");
        // held, then let go at speed: one pop, flames for a few ticks
        for (int i = 0; i < SportFeel.HELD; i++) assertFalse(th.step(true, 10, 7, true, true));
        assertTrue(th.step(false, 10, 7, true, true));
        assertEquals(SportFeel.FLAME_TICKS, th.flames);
        assertFalse(th.step(false, 10, 7, true, true), "once");
        // straight back on and off: not again so soon
        for (int i = 0; i < SportFeel.HELD; i++) th.step(true, 10, 7, true, true);
        assertFalse(th.step(false, 10, 7, true, true), "cooling down");
        for (int i = 0; i < SportFeel.COOLDOWN + SportFeel.HELD; i++) th.step(true, 10, 7, true, true);
        assertEquals(0, th.flames);
        // let go slowly: nothing; a car without it: nothing
        assertFalse(th.step(false, 5, 7, true, true), "too slow");
        for (int i = 0; i < SportFeel.HELD; i++) th.step(true, 10, 7, true, true);
        assertFalse(th.step(false, 10, 7, false, true));
    }

    @Test
    void flooringItFromAStopSmokesTheTyres() {
        SportFeel.Throttle th = new SportFeel.Throttle();
        th.step(true, 0, 7, true, true);
        assertEquals(SportFeel.LAUNCH_TICKS, th.smoke);
        for (int i = 0; i < SportFeel.LAUNCH_TICKS; i++) th.step(true, 3, 7, true, true);
        assertEquals(0, th.smoke, "it clears as the car gets going");
        // on the throttle again at speed: none
        th.step(false, 8, 7, true, true);
        th.step(true, 8, 7, true, true);
        assertEquals(0, th.smoke);
        // a car without it
        SportFeel.Throttle plain = new SportFeel.Throttle();
        plain.step(true, 0, 7, true, false);
        assertEquals(0, plain.smoke);
    }

    static DriveInput keys(boolean w, int steer, boolean ctrl) {
        return new DriveInput(w, false, steer > 0, steer < 0, false, false, ctrl);
    }

    @Test
    void ctrlAndSteeringAtSpeedDrifts() {
        VehicleSpec car = car();
        VehicleSpec.Drift d = car.extras().drift();
        assertTrue(d != null && d.angle() > 20 && d.minSpeed() >= 5, "vehicles.yml sports_car.vehicle.drift");
        var t = VehiclePhysicsTest.flat();
        double cap = car.cap(10);
        VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        for (int i = 0; i < 80; i++) VehiclePhysics.step(st, keys(true, 0, false), car, t, cap, 1f, 1, cap);
        assertEquals(0, st.slip, 1e-9, "driving straight: it goes where it points");
        // steering without Ctrl: an ordinary turn, no slide
        VehiclePhysics.State plain = TrainPhysics.copy(st);
        for (int i = 0; i < 20; i++) VehiclePhysics.step(plain, keys(true, 1, false), car, t, cap, 1f, 1, cap);
        assertEquals(0, plain.slip, 1e-9);
        // Ctrl + A: the nose swings left (the yaw falls) faster than in the plain turn, and the car slides: it moves
        // to the right of where its nose points
        VehiclePhysics.State drift = TrainPhysics.copy(st);
        double fast = st.speed;
        for (int i = 0; i < 20; i++) {
            double x = drift.x, z = drift.z;
            VehiclePhysics.step(drift, keys(true, 1, true), car, t, cap, 1f, 1, cap);
            assertTrue(Math.abs(drift.slip) <= d.angle() + 1e-9, "never past its angle");
            if (i > 3) {
                float went = CartPull.yawOf(drift.x - x, drift.z - z);
                assertEquals(drift.slip, VehicleSeats.signed(drift.yaw - went), 0.5, "the nose is off its way by the slip");
            }
        }
        assertEquals(-d.angle(), drift.slip, 1e-6, "full slide after a second, the nose turned in (left: below 0)");
        assertTrue(VehicleSeats.signed(drift.yaw - st.yaw) < VehicleSeats.signed(plain.yaw - st.yaw) - 5, "it turns in harder than a plain turn");
        assertTrue(drift.speed < fast && drift.speed > fast - 3, "sliding costs a little speed: " + drift.speed + " from " + fast);
        // the other way
        VehiclePhysics.State right = TrainPhysics.copy(st);
        for (int i = 0; i < 20; i++) VehiclePhysics.step(right, keys(true, -1, true), car, t, cap, 1f, 1, cap);
        assertEquals(d.angle(), right.slip, 1e-6);
        // let go: it grips again within half a second and goes where it points
        for (int i = 0; i < 10; i++) VehiclePhysics.step(drift, keys(true, 0, false), car, t, cap, 1f, 1, cap);
        assertEquals(0, drift.slip, 1e-9);
        double x = drift.x, z = drift.z;
        VehiclePhysics.step(drift, keys(true, 0, false), car, t, cap, 1f, 1, cap);
        assertEquals(0, VehicleSeats.signed(drift.yaw - CartPull.yawOf(drift.x - x, drift.z - z)), 0.01);
        // too slow: no drift; Ctrl alone (no steering): none
        VehiclePhysics.State slow = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        for (int i = 0; i < 8; i++) VehiclePhysics.step(slow, keys(true, 1, true), car, t, cap, 1f, 1, cap);
        assertTrue(slow.speed < d.minSpeed());
        assertEquals(0, slow.slip, 1e-9, "under its drift speed");
        VehiclePhysics.State straight = TrainPhysics.copy(st);
        for (int i = 0; i < 10; i++) VehiclePhysics.step(straight, keys(true, 0, true), car, t, cap, 1f, 1, cap);
        assertEquals(0, straight.slip, 1e-9);
        // a car that does not drift (the patrol car): Ctrl changes nothing
        ConfigurationSection ps = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile()).getConfigurationSection("police_car.vehicle");
        VehicleSpec police = VehicleSpec.parse(ps, "stsv", m -> true);
        assertNull(police.extras().drift());
        VehiclePhysics.State a = VehiclePhysicsTest.onGround(0.5, 0.5, 0), b = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        for (int i = 0; i < 100; i++) {
            VehiclePhysics.step(a, keys(true, i > 60 ? 1 : 0, true), police, t, 10);
            VehiclePhysics.step(b, keys(true, i > 60 ? 1 : 0, false), police, t, 10);
        }
        assertEquals(b.x, a.x, 1e-9);
        assertEquals(b.yaw, a.yaw, 1e-6);
        assertEquals(0, a.slip, 1e-9);
    }

    @Test
    void aDriftNeverPutsTheCarInABlock() {
        // sliding sideways into a wall: it stops against it like any move
        VehicleSpec car = car();
        var t = VehiclePhysicsTest.flat();
        for (int z = -5; z <= 60; z++) for (int y = 64; y <= 66; y++) {
            t.block(6, y, z);
            t.block(-6, y, z);
        }
        double cap = car.cap(10);
        VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        for (int i = 0; i < 300; i++) {
            int steer = (i / 25) % 2 == 0 ? 1 : -1;
            VehiclePhysics.step(st, keys(true, i < 50 ? 0 : steer, i >= 50), car, t, cap, 1f, 1, cap);
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, car, t), "tick " + i);
        }
    }

    @Test
    void theShippedFeelHasIt() {
        List<String> problems = new ArrayList<>();
        VehicleFeel.Spec s = VehicleFeelTest.shipped(problems).spec("stsv:sports_car", VehicleFeel.Power.ENGINE);
        assertTrue(problems.isEmpty(), problems.toString());
        SportFeel.Spec sp = s.sport();
        assertNotNull(sp, "vehicle_feel.yml sports_car.sport");
        assertEquals(0.3, sp.fov(), 1e-9);
        assertEquals(4, sp.exhaust().size(), "two tips each side");
        assertTrue(sp.exhaust().getFirst().forward() < -2, "at the tail");
        assertNotNull(sp.backfire());
        assertTrue(sp.launchSmoke());
        assertNotNull(sp.skid(), "the tyres squeal in a drift");
        assertEquals(1, sp.pops().count(), "one backfire a lift");
        assertNotNull(sp.mark(), "its rear tyres leave skid marks");
        assertEquals("rubber", sp.mark().name());
        assertTrue(s.lamps().front() && s.lamps().rear(), "head and tail lamps");
        assertTrue(s.ignition());
        // no ordinary vehicle has it
        for (String id : List.of("police_car", "light_truck", "golf_cart", "cargo_van"))
            assertNull(VehicleFeelTest.shipped(problems).spec("stsv:" + id, VehicleFeel.Power.ENGINE).sport(), id);
        // its sounds are in the pack
        for (String kind : List.of("horn", "horn_end", "engine", "engine_start", "engine_stop", "backfire", "skid"))
            assertTrue(Files.exists(PACK.resolve("assets/stsv/sounds/vehicle/sports_car_" + kind + ".ogg")), kind);
    }

    /** The tuner car (튜닝카): a closed coupe, a little slower than the roadster, slides further, and its exhaust crackles. */
    @Test
    void theTunerCarDriftsAndCrackles() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile());
        VehicleSpec tuner = VehicleSpec.parse(y.getConfigurationSection("tuner_car.vehicle"), "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
        VehicleSpec car = car();
        assertTrue(tuner.extras().overCap() && tuner.speed() > 10 && tuner.speed() < car.speed(), "over the cap, under the roadster");
        assertEquals(2, tuner.seats().size());
        assertNull(tuner.cargo());
        VehicleSpec.Drift d = tuner.extras().drift(), r = car.extras().drift();
        assertNotNull(d, "vehicles.yml tuner_car.vehicle.drift");
        assertTrue(d.angle() > r.angle() && d.minSpeed() <= r.minSpeed() && d.drag() < r.drag(), "it slides further, sooner, and keeps more speed");
        // a roof over the riders' heads: the size says so
        assertTrue(tuner.height() >= 0.14 + 1.275, "the body is taller than a sitting rider");

        List<String> problems = new ArrayList<>();
        VehicleFeel.Spec s = VehicleFeelTest.shipped(problems).spec("stsv:tuner_car", VehicleFeel.Power.ENGINE);
        assertTrue(problems.isEmpty(), problems.toString());
        SportFeel.Spec sp = s.sport();
        assertNotNull(sp, "vehicle_feel.yml tuner_car.sport");
        assertEquals(1, sp.exhaust().size(), "one fat pipe");
        assertTrue(sp.exhaust().getFirst().right() > 0 && sp.exhaust().getFirst().forward() < -2, "on the right of the tail");
        assertTrue(sp.pops().count() >= 5, "a crackle, not one bang");
        assertNotNull(sp.skid());
        assertNotNull(sp.mark());
        for (String kind : List.of("horn", "horn_end", "engine", "engine_start", "engine_stop", "popcorn"))
            assertTrue(Files.exists(PACK.resolve("assets/stsv/sounds/vehicle/tuner_car_" + kind + ".ogg")), kind);
    }

    /**
     * The premium cars (후원 차량): what is sold is the look. The hyper car goes exactly as the event roadster does (no
     * faster), the grand sedan keeps to the server's limit, the SUV is slower than the fastest vehicle the game gives
     * and shares the ATV's ground.
     */
    @Test
    void thePremiumCarsSellTheLookNotTheSpeed() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles.yml").toFile());
        java.util.function.Function<String, VehicleSpec> spec = id -> VehicleSpec.parse(y.getConfigurationSection(id + ".vehicle"), "stsv",
                m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
        VehicleSpec hyper = spec.apply("hyper_car"), sedan = spec.apply("grand_sedan"), suv = spec.apply("luxury_suv"), car = car();
        assertEquals(car.speed(), hyper.speed(), 1e-9, "the hyper car is as fast as the roadster, not faster");
        assertEquals(car.acceleration(), hyper.acceleration(), 1e-9);
        assertTrue(hyper.extras().overCap() && hyper.extras().drift() != null);
        assertEquals(2, hyper.seats().size());
        assertTrue(hyper.height() >= 0.05 + 1.275, "a roof over its riders");

        assertFalse(sedan.extras().overCap(), "the sedan keeps to the server's limit");
        assertTrue(sedan.speed() <= 10 && sedan.extras().drift() == null);
        assertEquals(4, sedan.seats().size());
        assertTrue(sedan.length() > 5, "the longest car");

        assertFalse(suv.extras().overCap());
        assertTrue(suv.speed() < 9.5, "slower than the delivery scooter, the fastest vehicle earned in the game");
        assertTrue(suv.extras().allTerrain(), "the ATV's ground");
        assertEquals(4, suv.seats().size());
        assertTrue(suv.height() >= 0.62 + 1.275);
        for (VehicleSpec v : List.of(hyper, sedan, suv)) assertNull(v.cargo(), "none carries goods");

        List<String> problems = new ArrayList<>();
        VehicleFeel.File feel = VehicleFeelTest.shipped(problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertNotNull(feel.spec("stsv:hyper_car", VehicleFeel.Power.ENGINE).sport(), "the hyper car has the feel of speed");
        assertEquals(3, feel.spec("stsv:hyper_car", VehicleFeel.Power.ENGINE).sport().exhaust().size(), "three tips in the middle of its tail");
        assertNull(feel.spec("stsv:grand_sedan", VehicleFeel.Power.ENGINE).sport());
        assertNull(feel.spec("stsv:luxury_suv", VehicleFeel.Power.ENGINE).sport());
        for (String id : List.of("hyper_car", "grand_sedan", "luxury_suv")) {
            assertTrue(feel.spec("stsv:" + id, VehicleFeel.Power.ENGINE).lamps().front(), id + ": head lamps");
            for (String kind : List.of("horn", "horn_end", "engine", "engine_start", "engine_stop"))
                assertTrue(Files.exists(PACK.resolve("assets/stsv/sounds/vehicle/" + id + "_" + kind + ".ogg")), id + " " + kind);
        }
    }

    @Test
    void aCrackleIsSeveralPopsAndStopsOnTheThrottle() {
        SportFeel.Pops how = new SportFeel.Pops(7, 2, 5);
        SportFeel.Throttle t = new SportFeel.Throttle();
        for (int i = 0; i < SportFeel.HELD; i++) assertFalse(t.step(true, 10, 5, true, false, how));
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < 80; i++) if (t.step(false, 10, 5, true, false, how)) at.add(i);
        assertEquals(7, at.size(), "seven pops on one lift: " + at);
        assertEquals(0, at.getFirst(), "the first at once");
        for (int i = 1; i < at.size(); i++) {
            int gap = at.get(i) - at.get(i - 1);
            assertTrue(gap >= 2 && gap <= 5, "each two to five ticks after the last: " + at);
        }
        assertTrue(at.getLast() - at.getFirst() > 12, "spread out, not a drum roll: " + at);
        // no second crackle from the same lift
        for (int i = 0; i < 60; i++) assertFalse(t.step(false, 10, 5, true, false, how));

        // back on the throttle in the middle of it: the rest never comes
        SportFeel.Throttle u = new SportFeel.Throttle();
        for (int i = 0; i < SportFeel.HELD; i++) u.step(true, 10, 5, true, false, how);
        assertTrue(u.step(false, 10, 5, true, false, how));
        int pops = 1;
        for (int i = 0; i < 4; i++) if (u.step(false, 10, 5, true, false, how)) pops++;
        for (int i = 0; i < 5; i++) assertFalse(u.step(true, 10, 5, true, false, how));
        for (int i = 0; i < 40; i++) assertFalse(u.step(false, 10, 5, true, false, how), "held too briefly to pop again, and the old crackle is over");
        assertTrue(pops < 7);

        // one pop (the roadster): exactly as before
        SportFeel.Throttle one = new SportFeel.Throttle();
        for (int i = 0; i < SportFeel.HELD; i++) one.step(true, 10, 5, true, false);
        int n = 0;
        for (int i = 0; i < 60; i++) if (one.step(false, 10, 5, true, false)) n++;
        assertEquals(1, n);
    }

    @Test
    void skidMarksAreNamedStylesLaidAlongTheWheelsPath() {
        // the shipped looks
        List<String> problems = new ArrayList<>();
        VehicleFeel.File file = VehicleFeelTest.shipped(problems);
        assertTrue(problems.isEmpty(), problems.toString());
        SkidMarks.Style rubber = file.marks().get("rubber");
        assertNotNull(rubber, "vehicle_feel.yml skid-marks.rubber");
        assertEquals(0x17181c, rubber.argb() & 0xFFFFFF);
        assertEquals(Math.round(0.72 * 255), rubber.argb() >>> 24);
        assertEquals(120, rubber.ticks(), "six seconds");
        assertFalse(rubber.glow());
        assertTrue(file.marks().size() >= 4, "more looks to choose from: " + file.marks().keySet());
        assertTrue(file.marks().get("neon_pink").glow());
        assertEquals((rubber.argb() >>> 24) / 2, SkidMarks.half(rubber.argb()) >>> 24, "half as strong when it fades");
        assertEquals(rubber.argb() & 0xFFFFFF, SkidMarks.half(rubber.argb()) & 0xFFFFFF, "the same colour");

        // a piece from one point to the next: as wide as the style, as long as the step (and a little over, to close a bend)
        SkidMarks.Shape north = SkidMarks.shape(0, 0, 0, -0.5, 0.3);
        assertEquals(0.3, north.across() * SkidMarks.QUAD_W, 1e-6);
        assertEquals(0.5 + SkidMarks.OVERLAP, north.along() * SkidMarks.QUAD_H, 1e-6);
        assertEquals(0, north.yaw(), 1e-6, "laid flat, the piece runs north as it is");
        // turned to run where the wheel went: the piece's far end is the second point
        double[][] ways = {{1, 0}, {0, 1}, {-1, 0}, {0.6, -0.8}, {-0.3, 0.2}};
        for (double[] w : ways) {
            SkidMarks.Shape sh = SkidMarks.shape(3, 7, 3 + w[0], 7 + w[1], 0.3);
            double len = Math.hypot(w[0], w[1]);
            org.joml.Vector3f end = new org.joml.Quaternionf().rotateY(sh.yaw()).rotateX((float) (-Math.PI / 2))
                    .transform(new org.joml.Vector3f(0, (float) len, 0));
            assertEquals(w[0], end.x, 1e-5, "x towards " + w[0] + ", " + w[1]);
            assertEquals(w[1], end.z, 1e-5, "z towards " + w[0] + ", " + w[1]);
            assertEquals(0, end.y, 1e-5, "flat on the ground");
        }

        // a vehicle names its look; an unknown name, a bad colour and a bad width say so
        YamlConfiguration y = new YamlConfiguration();
        y.set("s.mark", "rubber");
        assertEquals(rubber, SportFeel.parse(y.getConfigurationSection("s"), file.marks()).mark());
        y.set("s.mark", "nope");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SportFeel.parse(y.getConfigurationSection("s"), file.marks())).getMessage().contains("nope"));
        y.set("m.color", "red");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SkidMarks.style("m", y.getConfigurationSection("m"))).getMessage().contains("color"));
        y.set("m.color", "#ff0000");
        y.set("m.width", 3);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SkidMarks.style("m", y.getConfigurationSection("m"))).getMessage().contains("width"));
        y.set("m.width", 0.2);
        y.set("m.glow", true);
        SkidMarks.Style made = SkidMarks.style("m", y.getConfigurationSection("m"));
        assertEquals(0xff0000, made.argb() & 0xFFFFFF);
        assertTrue(made.glow());
        y.set("s.backfire.sound", "stsv:vehicle.tuner_car.popcorn");
        y.set("s.exhaust", List.of(List.of(0.5, 0.3, -2.0)));
        y.set("s.backfire.pops", 40);
        y.set("s.mark", null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SportFeel.parse(y.getConfigurationSection("s"))).getMessage().contains("pops"));
    }

    @Test
    void aBrokenSportSectionSaysWhat() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("s.fov", 0.9);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SportFeel.parse(y.getConfigurationSection("s"))).getMessage().contains("fov"));
        y.set("s.fov", 0.2);
        y.set("s.backfire.sound", "stsv:vehicle.sports_car.backfire");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SportFeel.parse(y.getConfigurationSection("s"))).getMessage().contains("exhaust"));
        y.set("s.exhaust", List.of(List.of(0.0, 0.3)));
        assertThrows(IllegalArgumentException.class, () -> SportFeel.parse(y.getConfigurationSection("s")));
    }
}
