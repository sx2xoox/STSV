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
                if (s == null || id.equals("sports_car")) continue;
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
        assertTrue(s.lamps().front() && s.lamps().rear(), "head and tail lamps");
        assertTrue(s.ignition());
        // no other vehicle has it
        for (String id : List.of("police_car", "light_truck", "golf_cart", "cargo_van"))
            assertNull(VehicleFeelTest.shipped(problems).spec("stsv:" + id, VehicleFeel.Power.ENGINE).sport(), id);
        // its sounds are in the pack
        for (String kind : List.of("horn", "horn_end", "engine", "engine_start", "engine_stop", "backfire"))
            assertTrue(Files.exists(PACK.resolve("assets/stsv/sounds/vehicle/sports_car_" + kind + ".ogg")), kind);
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
