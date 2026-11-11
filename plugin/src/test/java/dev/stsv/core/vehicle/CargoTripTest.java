package dev.stsv.core.vehicle;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하역장 and 적재 무게 on the core's side: a load's trip (where it last changed, the odometer since), the bed's weight
 * against its payload, telling a real change of a bed from moving things around, the trips kept over a restart, every
 * vehicle's payload as the owner set it, and the road's speed bonus.
 */
class CargoTripTest {

    @TempDir
    Path dir;

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    /** The docks' default: a load must come from 6,000 blocks away (economy config docks.min-distance). */
    private static final double MIN = 6000;

    // ------------------------------------------------------------------------------------------
    // Trip: distance and odometer, reset on a change
    // ------------------------------------------------------------------------------------------

    @Test
    void aLoadCountsOnlyFarFromWhereItChangedAndAfterReallyDrivingThere() {
        VehicleTrip t = VehicleTrip.start("world", 0, 0, 1000);
        // loaded at the origin; the dock is 7,000 blocks east
        assertFalse(t.snapshot().eligible("world", 7000, 0, MIN), "not driven yet");
        for (int i = 0; i < 7000 * 2; i++) t.moved(0.5);   // 7,000 blocks of driving at half a block a tick
        assertTrue(t.snapshot().eligible("world", 7000, 0, MIN));
        assertEquals(7000, t.snapshot().straight("world", 7000, 0), 1e-9);
        // the same odometer but a dock near where it was loaded: too close
        assertFalse(t.snapshot().eligible("world", 3000, 0, MIN));
        // another world is never far enough or close enough: not the same trip
        assertFalse(t.snapshot().eligible("world_nether", 7000, 0, MIN));
    }

    @Test
    void drivingInCirclesNearHomeIsNotHaulingFar() {
        VehicleTrip t = VehicleTrip.start("world", 100, 100, 0);
        for (int i = 0; i < 40_000; i++) t.moved(0.5);   // 20,000 blocks of laps
        assertTrue(t.snapshot().odometer() >= 20_000 - 1e-6);
        assertFalse(t.snapshot().eligible("world", 200, 100, MIN), "the straight line from the load still counts");
    }

    @Test
    void aChangeOfTheLoadStartsTheTripAgainAndTeleportsDoNotCount() {
        VehicleTrip t = VehicleTrip.start("world", 0, 0, 0);
        for (int i = 0; i < 14_000; i++) t.moved(0.5);
        assertTrue(t.snapshot().eligible("world", 7000, 0, MIN));
        // loading at the dock: the trip starts at the dock
        t.reset("world", 7000, 0, 5000);
        assertFalse(t.snapshot().eligible("world", 7000, 0, MIN), "loaded at the dock, sold at the dock: no");
        assertEquals(0, t.snapshot().odometer());
        assertEquals(5000, t.snapshot().changed());
        t.moved(VehicleTrip.MAX_STEP + 1);   // a jump (a teleport, an admin) is not driving
        t.moved(-3);
        t.moved(Double.NaN);
        assertEquals(0, t.snapshot().odometer());
    }

    @Test
    void aJigeKeepsItsTripOnlyWhenPutOnAgainWhereItCameOff() {
        VehicleTrip.Seen seen = new VehicleTrip.Seen("world", 100, 200);
        assertTrue(seen.near("world", 110, 205, JigeModule.REWEAR_REACH), "taken off and put on again in place (a relog)");
        assertFalse(seen.near("world", 100, 260, JigeModule.REWEAR_REACH), "carried off in the inventory: the trip starts again");
        assertFalse(seen.near("world_nether", 100, 200, JigeModule.REWEAR_REACH));
        assertEquals(-5, VehicleTripStore.jige(5), "a 지게's trip row: its id negated (vehicles are positive)");
        assertTrue(JigeModule.MAX_WALK < VehicleTrip.MAX_STEP && JigeModule.MAX_WALK >= 20, "a second of riding a horse counts, a teleport not");
    }

    @Test
    void tripsAreKeptOverARestart() throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("t.db"), Logger.getLogger("test"));
        try {
            VehicleTripStore s = new VehicleTripStore(db);
            s.init();
            s.save(7, new VehicleTrip.Snapshot("world", 12.5, -40.25, 6543.5, 1234)).join();
            s.save(8, new VehicleTrip.Snapshot("other", 1, 2, 3, 4)).join();
            s.save(7, new VehicleTrip.Snapshot("world", 13, -41, 7000, 5678)).join();   // replaced
            s.delete(8).join();
            s.save(-3, new VehicleTrip.Snapshot("world", 5, 6, 70, 9), new VehicleTrip.Seen("world", 50, 60)).join();
            VehicleTripStore again = new VehicleTripStore(db);
            again.init();
            Map<Long, VehicleTrip> all = again.all();
            assertEquals(2, all.size());
            assertEquals(new VehicleTrip.Seen("world", 50, 60), all.get(-3L).seen);
            assertEquals(70, all.get(-3L).snapshot().odometer(), 1e-9);
            assertEquals(null, all.get(7L).seen);
            VehicleTrip.Snapshot t = all.get(7L).snapshot();
            assertEquals("world", t.world());
            assertEquals(13, t.x(), 1e-9);
            assertEquals(-41, t.z(), 1e-9);
            assertEquals(7000, t.odometer(), 1e-9);
            assertEquals(5678, t.changed());
        } finally {
            db.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Weight and payload
    // ------------------------------------------------------------------------------------------

    /** A stack as "id x count", weighing weight x count. */
    record Stack(String id, int n) {}

    private static double weigh(Stack s) {
        return switch (s.id()) {
            case "ring" -> 0.3;
            case "barrel" -> 5;
            default -> 1;
        } * s.n();
    }

    @Test
    void weightsAddUpAndThePayloadRefusesTheRest() {
        Stack[] bed = {new Stack("ring", 64), null, new Stack("barrel", 4), new Stack("kimchi", 10)};
        assertEquals(64 * 0.3 + 20 + 10, CargoWeights.total(bed, CargoTripTest::weigh), 1e-9);
        double payload = 60;   // a delivery scooter
        double used = CargoWeights.total(bed, CargoTripTest::weigh);   // 49.2
        assertTrue(CargoWeights.fits(used, 10, payload));
        assertFalse(CargoWeights.fits(used, 11, payload));
        assertEquals(2, CargoWeights.fitting(used, 5, 64, payload), "two barrels more, not 64");
        assertEquals(36, CargoWeights.fitting(used, 0.3, 64, payload));
        assertEquals(0, CargoWeights.fitting(payload, 0.3, 64, payload), "full");
        assertEquals(64, CargoWeights.fitting(payload, 0, 64, payload), "weightless things always fit");
        // 0.1 x 3 is a hair over 0.3: still fits exactly
        assertTrue(CargoWeights.fits(0.1 + 0.1 + 0.1, 0, 0.3));
        assertTrue(CargoWeights.fits(0.1 + 0.1, 0.1, 0.3));
        assertEquals("49.2", CargoWeights.format(used));
        assertEquals("60", CargoWeights.format(60));
        // raw materials (economy weights.yml raw = 1/64): a hand cart (150) full of 135 stacks still has room
        double crop = 1.0 / 64;
        Stack[] cart = new Stack[135];
        java.util.Arrays.fill(cart, new Stack("wheat", 64));
        double full = CargoWeights.total(cart, s -> s.n() * crop);
        assertEquals(135, full, 1e-9);
        assertEquals(64, CargoWeights.fitting(full - 1, crop, 64, 150), "the last stack goes in whole");
        assertEquals("135", CargoWeights.format(full));
        assertEquals("1.3", CargoWeights.format(83 * crop));
    }

    @Test
    void movingThingsAroundIsNotAChangeButTakingOrAddingIs() {
        Stack[] before = {new Stack("ring", 64), new Stack("kimchi", 10), null};
        Stack[] moved = {null, new Stack("kimchi", 4), new Stack("ring", 64)};
        moved = new Stack[]{new Stack("kimchi", 6), moved[1], moved[2]};   // the kimchi split over two slots
        assertTrue(CargoWeights.sameContents(before, moved, (a, b) -> a.id().equals(b.id()), Stack::n));
        Stack[] less = {new Stack("ring", 63), new Stack("kimchi", 10), null};
        assertFalse(CargoWeights.sameContents(before, less, (a, b) -> a.id().equals(b.id()), Stack::n));
        Stack[] other = {new Stack("ring", 64), new Stack("tofu", 10), null};
        assertFalse(CargoWeights.sameContents(before, other, (a, b) -> a.id().equals(b.id()), Stack::n));
        assertTrue(CargoWeights.sameContents(new Stack[0], new Stack[3], (a, b) -> true, Stack::n), "empty is empty");
    }

    @Test
    void aBigMixedBedIsComparedByGroupsQuicklyAndRightly() {
        // a 720-slot train of 240 kinds (40 materials x 6 variants each, the variants alike by key: custom goods on paper)
        int slots = 720;
        Stack[] before = new Stack[slots];
        for (int i = 0; i < slots; i++) before[i] = i % 7 == 0 ? null : new Stack("m" + i % 40 + "#" + i % 240, 1 + i % 64);
        java.util.function.Function<Stack, Object> key = s -> s.id().substring(0, s.id().indexOf('#'));
        long[] calls = {0};
        java.util.function.BiPredicate<Stack, Stack> similar = (a, b) -> {
            calls[0]++;
            return a.id().equals(b.id());
        };
        // moved around (reversed) and one kind split over two slots: no change
        Stack[] moved = new Stack[slots];
        for (int i = 0; i < slots; i++) moved[i] = before[slots - 1 - i];
        int split = 1;
        while (moved[split] == null || moved[split].n() < 2 || moved[split - 1] != null) split++;
        Stack whole = moved[split];
        moved[split] = new Stack(whole.id(), whole.n() - 1);
        moved[split - 1] = new Stack(whole.id(), 1);
        assertTrue(CargoWeights.sameContents(before, moved, key, similar, Stack::n));
        assertTrue(CargoWeights.sameContents(before, moved, similar, Stack::n), "the same answer as without keys");
        // isSimilar only within a group: at most (stacks) x (kinds in a group = 6), not stacks x 240
        calls[0] = 0;
        CargoWeights.sameContents(before, moved, key, similar, Stack::n);
        assertTrue(calls[0] <= 2L * slots * 6, "isSimilar calls: " + calls[0]);
        // one item fewer, or one swapped for a kind in the same group: a change
        Stack[] less = moved.clone();
        less[split] = new Stack(whole.id(), whole.n() - 2);
        assertFalse(CargoWeights.sameContents(before, less, key, similar, Stack::n));
        Stack[] swapped = moved.clone();
        String id = whole.id();
        String other = id.substring(0, id.indexOf('#')) + "#x";
        swapped[split - 1] = new Stack(other, 1);
        assertFalse(CargoWeights.sameContents(before, swapped, key, similar, Stack::n));
        assertFalse(CargoWeights.sameContents(before, swapped, similar, Stack::n), "the same answer as without keys");
        // and fast: a thousand syncs of the whole train well under a second
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) CargoWeights.sameContents(before, moved, key, similar, Stack::n);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2000, "1000 x 720 slots took " + ms + " ms");
    }

    // ------------------------------------------------------------------------------------------
    // Every vehicle's payload (README "적재 무게")
    // ------------------------------------------------------------------------------------------

    private static Map<String, VehicleSpec> all() {
        Map<String, VehicleSpec> out = new LinkedHashMap<>();
        for (String file : new String[]{"vehicles.yml", "vehicles_road.yml", "vehicles_farm.yml", "vehicles_misc.yml"}) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("items/" + file).toFile());
            for (String id : y.getKeys(false)) {
                ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
                if (s != null) out.put(id, VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel"))));
            }
        }
        return out;
    }

    @Test
    void everyVehicleCarriesWhatTheOwnerSet() {
        Map<String, Double> want = new LinkedHashMap<>();
        want.put("hand_cart", 150.0);
        want.put("cargo_bicycle", 20.0);
        want.put("delivery_scooter", 60.0);
        want.put("atv", 120.0);
        want.put("cargo_van", 600.0);
        want.put("light_truck", 1000.0);
        want.put("box_truck", 2500.0);
        want.put("police_car", 100.0);
        want.put("golf_cart", 100.0);
        want.put("ox_cart", 400.0);
        want.put("power_tiller", 500.0);
        want.put("tractor_trailer", 2000.0);
        want.put("fishing_boat", 800.0);
        Map<String, VehicleSpec> all = all();
        for (Map.Entry<String, Double> e : want.entrySet()) {
            VehicleSpec s = all.get(e.getKey());
            assertNotNull(s, e.getKey());
            assertNotNull(s.cargo(), e.getKey() + " has a bed");
            assertEquals(e.getValue(), s.cargo().payload(), 1e-9, e.getKey());
        }
        // every vehicle with a bed has a payload of its own in its file (the default is only for new ones)
        for (Map.Entry<String, VehicleSpec> e : all.entrySet())
            if (e.getValue().cargo() != null) assertTrue(want.containsKey(e.getKey()), e.getKey() + ": payload 를 정하세요");
        // the 지게 (worn): 30
        YamlConfiguration misc = YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles_misc.yml").toFile());
        assertEquals(30, JigeSpec.parse(misc.getConfigurationSection("jige.jige"), "stsv",
                m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel"))).payload(), 1e-9);
        // the ladder: each generation carries more than the one before
        assertTrue(150 > 20 && 60 > 20 && 600 > 120 && 1000 > 600 && 2500 > 1000);
    }

    @Test
    void aBedWithoutAPayloadTakesTwoPerSlot() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("v.speed", 5);
        y.set("v.reverse-speed", 2);
        y.set("v.acceleration", 2);
        y.set("v.brake", 6);
        y.set("v.turn-rate", 60);
        y.set("v.turn-speed", 2);
        y.set("v.size", java.util.List.of(2.0, 1.0, 1.0));
        y.set("v.wheel", "x:wheel");
        y.set("v.wheel-radius", 0.3);
        y.set("v.seats", java.util.List.of(Map.of("at", java.util.List.of(0.0, 0.5, 0.0))));
        y.set("v.cargo.slots", 90);
        y.set("v.cargo.at", java.util.List.of(0.0, 0.5, 0.0));
        assertEquals(180, VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true).cargo().payload(), 1e-9);
        y.set("v.cargo.payload", 75);
        assertEquals(75, VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true).cargo().payload(), 1e-9);
        y.set("v.cargo.payload", 0);
        assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true));
        y.set("v.cargo", null);
        y.set("v.payload", 50);
        assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true), "no bed");
    }

    // ------------------------------------------------------------------------------------------
    // 국도: the road's speed bonus
    // ------------------------------------------------------------------------------------------

    @Test
    void onARoadTheTopSpeedIsHigherButNeverOverTheServerCap() {
        VehicleSpec car = VehiclePhysicsTest.car();   // 9 b/s
        VehiclePhysicsTest.Grid flat = new VehiclePhysicsTest.Grid().floor(63, -40, 40, -40, 700);
        VehiclePhysics.State plain = VehiclePhysicsTest.onGround(0, 0, 0), road = VehiclePhysicsTest.onGround(10, 0, 0);
        for (int i = 0; i < 400; i++) {
            VehiclePhysics.step(plain, VehiclePhysicsTest.W, car, flat, 20);
            VehiclePhysics.step(road, VehiclePhysicsTest.W, car, flat, 20, 1f, 1.2, 20);
        }
        assertEquals(9, plain.speed, 1e-9);
        assertEquals(9 * 1.2, road.speed, 1e-9);
        VehiclePhysics.State capped = VehiclePhysicsTest.onGround(-10, 0, 0);
        for (int i = 0; i < 400; i++) VehiclePhysics.step(capped, VehiclePhysicsTest.W, car, flat, 10, 1f, 1.2, 10);
        assertEquals(10, capped.speed, 1e-9, "vehicles.max-speed still caps it");
        // in water the bonus does not apply
        VehiclePhysicsTest.Grid wet = new VehiclePhysicsTest.Grid().floor(63, -40, 40, -40, 700);
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 700; z++) wet.medium(x, 64, z, VehiclePhysics.Medium.WATER);
        VehiclePhysics.State swim = VehiclePhysicsTest.onGround(0, 0, 0);
        for (int i = 0; i < 400; i++) VehiclePhysics.step(swim, VehiclePhysicsTest.W, car, wet, 20, 1f, 1.2, 20);
        assertEquals(9 * 0.4, swim.speed, 1e-9);
    }
}
