package dev.stsv.core.vehicle;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 탈것의 느낌: the load and slope curve, the lamps, the springs, the ground, hunger, and the shipped vehicle_feel.yml. */
class VehicleFeelTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final VehicleFeel.Profile ENGINE = VehicleFeel.defaultProfile(VehicleFeel.Power.ENGINE);
    private static final VehicleFeel.Profile MUSCLE = VehicleFeel.defaultProfile(VehicleFeel.Power.MUSCLE);
    private static final VehicleFeel.Profile ANIMAL = VehicleFeel.defaultProfile(VehicleFeel.Power.ANIMAL);

    // ------------------------------------------------------------------------------------------
    // Load and slope
    // ------------------------------------------------------------------------------------------

    @Test
    void theShareOfThePayload() {
        assertEquals(0, VehicleFeel.share(0, 1000), 1e-9);
        assertEquals(0.25, VehicleFeel.share(250, 1000), 1e-9);
        assertEquals(1, VehicleFeel.share(1500, 1000), 1e-9, "over the payload counts as full");
        assertEquals(0, VehicleFeel.share(10, 0), 1e-9, "no payload: nothing to feel");
    }

    @Test
    void aFullLoadTakesUpTo15PercentOffTheTopSpeedGentlerForEngines() {
        for (VehicleFeel.Profile p : List.of(ENGINE, MUSCLE, ANIMAL)) {
            assertEquals(1, VehicleFeel.topFactor(p, 0, 0, true), 1e-9, "empty on the flat: as it was");
            double half = VehicleFeel.topFactor(p, 0.5, 0, true), full = VehicleFeel.topFactor(p, 1, 0, true);
            assertTrue(full < half && half < 1, "heavier is slower");
            assertTrue(full >= 0.85 - 1e-9, "never more than 15 % off on the flat");
        }
        assertEquals(0.85, VehicleFeel.topFactor(MUSCLE, 1, 0, true), 1e-9, "a full cart: 15 % slower");
        assertTrue(VehicleFeel.topFactor(ENGINE, 1, 0, true) > VehicleFeel.topFactor(ANIMAL, 1, 0, true), "an engine feels it least");
        assertTrue(VehicleFeel.topFactor(ANIMAL, 1, 0, true) > VehicleFeel.topFactor(MUSCLE, 1, 0, true), "a person most");
        // the acceleration suffers more than the top speed, never to nothing
        assertTrue(VehicleFeel.accelFactor(ENGINE, 1, 0) < VehicleFeel.topFactor(ENGINE, 1, 0, true));
        assertEquals(1 - ENGINE.accelLoss(), VehicleFeel.accelFactor(ENGINE, 1, 0), 1e-9);
        assertTrue(VehicleFeel.accelFactor(MUSCLE, 1, 1) >= VehicleFeel.MIN_ACCEL);
    }

    @Test
    void uphillSlowerDownhillALittleFasterWhenWheeled() {
        double flat = VehicleFeel.topFactor(ENGINE, 0, 0, true);
        double up = VehicleFeel.topFactor(ENGINE, 0, 0.5, true), upFull = VehicleFeel.topFactor(ENGINE, 1, 0.5, true);
        double down = VehicleFeel.topFactor(ENGINE, 0, -0.5, true);
        assertTrue(up < flat && upFull < up * VehicleFeel.topFactor(ENGINE, 1, 0, true) + 1e-9, "uphill slower, more so loaded");
        assertTrue(down > flat, "downhill a little faster");
        assertTrue(VehicleFeel.topFactor(ENGINE, 0, -1, true) <= 1 + ENGINE.downhillMax() + 1e-9, "but only so much");
        assertEquals(flat, VehicleFeel.topFactor(ENGINE, 0, -0.5, false), 1e-9, "not on foot (a 지게, a puller)");
        assertTrue(VehicleFeel.topFactor(MUSCLE, 1, 1, true) >= (1 - MUSCLE.topLoss()) * VehicleFeel.MIN_SLOPE - 1e-9, "a steep road still climbs");
        assertTrue(VehicleFeel.accelFactor(ENGINE, 0, 0.5) < VehicleFeel.accelFactor(ENGINE, 0, 0), "pulls away slower uphill");
        assertEquals(VehicleFeel.accelFactor(ENGINE, 0, 0), VehicleFeel.accelFactor(ENGINE, 0, -0.5), 1e-9, "downhill does not push the start");
    }

    @Test
    void theGradeIsTheRiseOverTheLastFewBlocks() {
        double g = 0;
        for (int i = 0; i < 40; i++) g = VehicleFeel.grade(g, 0.2, 0.4, true);   // a stair road: a block up every two
        assertEquals(0.5, g, 0.01);
        assertEquals(g, VehicleFeel.grade(g, 0, 0, true), 1e-12, "standing keeps it");
        assertEquals(g, VehicleFeel.grade(g, -3, 0.4, true), 1e-12, "a fall is not a slope");
        assertEquals(g, VehicleFeel.grade(g, 0.5, 0.4, false), 1e-12, "nor a move in the air");
        double after = g;
        for (int i = 0; i < 40; i++) after = VehicleFeel.grade(after, 0, 0.4, true);
        assertEquals(0, after, 0.01, "back on the flat it fades");
        double one = VehicleFeel.grade(0, 1, 0.4, true);
        assertTrue(one > 0 && one < 0.2, "one step is not a hill: " + one);
    }

    @Test
    void theVehicleFeelsItsLoadInThePhysics() {
        VehicleSpec car = VehiclePhysicsTest.car();
        double empty = 0, full = 0, down = 0;
        int emptyTicks = -1, fullTicks = -1;
        double fullTop = car.speed() * VehicleFeel.topFactor(ENGINE, 1, 0, true);
        for (int t = 0; t < 400; t++) {
            empty = VehiclePhysics.nextSpeed(empty, true, false, car, 1, 10, 1);
            full = VehiclePhysics.nextSpeed(full, true, false, car, VehicleFeel.topFactor(ENGINE, 1, 0, true), 10, VehicleFeel.accelFactor(ENGINE, 1, 0));
            if (emptyTicks < 0 && empty >= 0.9 * fullTop) emptyTicks = t;
            if (fullTicks < 0 && full >= 0.9 * fullTop) fullTicks = t;
        }
        assertEquals(car.speed(), empty, 1e-9);
        assertEquals(fullTop, full, 1e-9, "a full truck tops out lower");
        assertTrue(fullTicks > emptyTicks, "and gets there later: " + emptyTicks + " vs " + fullTicks);
        // downhill on the step: never past the server's top speed
        VehiclePhysicsTest.Grid g = new VehiclePhysicsTest.Grid().floor(63, -200, 200, -10, 10);
        VehiclePhysics.State st = new VehiclePhysics.State(0.5, 64, 0.5, -90f);
        st.onGround = true;
        DriveInput w = new DriveInput(true, false, false, false, false, false);
        for (int t = 0; t < 200; t++) VehiclePhysics.step(st, w, car, g, 9.5, 1f, 1, 9.5, 1.08, 1);
        assertEquals(9.5, st.speed, 1e-9, "a downhill boost stops at the cap");
        for (int t = 0; t < 200; t++) VehiclePhysics.step(st, w, car, g, 20, 1f, 1, 20, 1.08, 1);
        assertEquals(car.speed() * 1.08, st.speed, 1e-9, "under the cap: 8 % faster");
        down = st.speed;
        assertTrue(down > car.speed());
    }

    @Test
    void pullersAndWearersSlowWithTheLoad() {
        assertEquals(0.4, VehicleFeel.slowdown(0.4, 1), 1e-9, "empty on the flat: the cart's own");
        assertEquals(0.49, VehicleFeel.slowdown(0.4, VehicleFeel.topFactor(MUSCLE, 1, 0, false)), 1e-9, "full: 0.6 x 0.85 left");
        assertTrue(VehicleFeel.slowdown(0.1, VehicleFeel.topFactor(MUSCLE, 1, 0.5, false)) > VehicleFeel.slowdown(0.1, VehicleFeel.topFactor(MUSCLE, 1, 0, false)), "uphill more");
        assertTrue(VehicleFeel.slowdown(0.9, 0.1) <= 0.9, "never stuck");
    }

    @Test
    void effortCostsALittleFoodMoreLoadedAndUphill() {
        VehicleFeel.Effort cart = new VehicleFeel.Effort(0.01, 0.03, 1.0);
        assertEquals(10, VehicleFeel.exhaustion(1000, 0, 0, cart), 1e-9, "1000 blocks empty: 10 (1.25 food points)");
        assertEquals(40, VehicleFeel.exhaustion(1000, 1, 0, cart), 1e-9, "full: 40 (5 food points)");
        assertEquals(80, VehicleFeel.exhaustion(1000, 1, 1, cart), 1e-9, "full and steeply up: twice that");
        assertEquals(40, VehicleFeel.exhaustion(1000, 1, -1, cart), 1e-9, "downhill costs no more");
        assertEquals(0, VehicleFeel.exhaustion(1000, 1, 0, VehicleFeel.Effort.NONE), 1e-9, "engines cost nothing");
        assertTrue(VehicleFeel.exhaustion(1, 1, 0, cart) < 0.1, "less than sprinting a block");
    }

    // ------------------------------------------------------------------------------------------
    // Lamps
    // ------------------------------------------------------------------------------------------

    @Test
    void theSkyDarkensAtNight() {
        assertEquals(0, VehicleLamps.skyDarken(6000, 0, 0), "noon");
        assertEquals(11, VehicleLamps.skyDarken(18000, 0, 0), "midnight");
        assertTrue(VehicleLamps.skyDarken(6000, 1, 1) >= 4, "a thunderstorm darkens the day");
        int dusk = VehicleLamps.skyDarken(12500, 0, 0);
        assertTrue(dusk > 0 && dusk < 11, "dusk in between: " + dusk);
        assertEquals(15, VehicleLamps.seen(0, 15, 0));
        assertEquals(4, VehicleLamps.seen(0, 15, 11), "open sky at midnight");
        assertEquals(12, VehicleLamps.seen(12, 15, 11), "under a lamp");
    }

    @Test
    void headlampsComeOnInTheDarkWithoutFlickering() {
        assertTrue(VehicleLamps.headOn(false, 4, 7, 10));
        assertFalse(VehicleLamps.headOn(false, 12, 7, 10));
        assertTrue(VehicleLamps.headOn(true, 8, 7, 10), "in between: as they were");
        assertFalse(VehicleLamps.headOn(false, 8, 7, 10), "in between: as they were");
        assertFalse(VehicleLamps.headOn(true, 10, 7, 10));
        // a light wobbling round the threshold switches once, not on every wobble
        VehicleLamps.State s = new VehicleLamps.State();
        int switches = 0;
        boolean was = false;
        int[] light = {9, 8, 7, 8, 9, 8, 7, 8, 9, 8, 7, 8, 9};
        for (int t = 0; t < light.length; t++) {
            s.tick(t, true, light[t], DriveInput.NONE, 0, 7, 10, 4);
            if (s.head() != was) switches++;
            was = s.head();
        }
        assertEquals(1, switches);
        s.tick(20, false, 0, DriveInput.NONE, 0, 7, 10, 4);
        assertFalse(s.head(), "off without a driver");
        s.tick(21, true, -1, DriveInput.NONE, 0, 7, 10, 4);
        assertFalse(s.head(), "an unknown light leaves them");
    }

    @Test
    void brakeAndReverseLamps() {
        DriveInput w = new DriveInput(true, false, false, false, false, false), sKey = new DriveInput(false, true, false, false, false, false);
        DriveInput both = new DriveInput(true, true, false, false, false, false);
        assertTrue(VehicleLamps.braking(sKey, 5), "S going forward");
        assertTrue(VehicleLamps.braking(w, -2), "W going backward");
        assertTrue(VehicleLamps.braking(both, 3));
        assertFalse(VehicleLamps.braking(w, 5));
        assertFalse(VehicleLamps.braking(sKey, 0), "S standing: about to reverse, not braking");
        assertTrue(VehicleLamps.reversing(sKey, 0));
        assertTrue(VehicleLamps.reversing(DriveInput.NONE, -1), "rolling back");
        assertFalse(VehicleLamps.reversing(w, -1), "W while reversing: braking");
        assertFalse(VehicleLamps.reversing(sKey, 3));
        assertEquals(VehicleLamps.Rear.BRAKE, VehicleLamps.rear(true, true, true));
        assertEquals(VehicleLamps.Rear.REVERSE, VehicleLamps.rear(false, false, true));
        assertEquals(VehicleLamps.Rear.TAIL, VehicleLamps.rear(true, false, false));
        assertEquals(VehicleLamps.Rear.OFF, VehicleLamps.rear(false, false, false));
        // the brake lamps stay on a few ticks after a tap
        VehicleLamps.State s = new VehicleLamps.State();
        assertEquals(VehicleLamps.Rear.BRAKE, s.tick(0, true, 15, sKey, 5, 7, 10, 4));
        assertEquals(VehicleLamps.Rear.BRAKE, s.tick(3, true, 15, DriveInput.NONE, 4, 7, 10, 4));
        assertEquals(VehicleLamps.Rear.OFF, s.tick(5, true, 15, DriveInput.NONE, 4, 7, 10, 4));
        // the reverse beep: "삐-삐-" every 14 ticks while backing up, at once when it starts
        int beeps = 0;
        for (int t = 100; t < 156; t++) if (s.beep(t, true, 14)) beeps++;
        assertEquals(4, beeps);
        assertFalse(s.beep(156, false, 14));
        assertTrue(s.beep(157, true, 14), "again at once");
        // a vehicle without some lamp shows what it has
        VehicleFeel.Lamps tailOnly = new VehicleFeel.Lamps(null, new org.bukkit.NamespacedKey("t", "tail"), null, null);
        assertEquals("tail", VehicleFeelView.rearModel(tailOnly, VehicleLamps.Rear.BRAKE).getKey());
        assertNull(VehicleFeelView.rearModel(tailOnly, VehicleLamps.Rear.REVERSE));
    }

    // ------------------------------------------------------------------------------------------
    // Springs, ground
    // ------------------------------------------------------------------------------------------

    @Test
    void theBodyBouncesOverAStepAndSettles() {
        Suspension s = new Suspension();
        assertTrue(s.settled());
        assertFalse(s.step(), "still: nothing to do");
        s.drive(1.0, 6, 6, 0, 0.05, 2, 2.5);
        assertFalse(s.settled());
        double lowest = 0, pitch = 0;
        int ticks = 0;
        while (s.step() && ticks < 200) {
            lowest = Math.min(lowest, s.bob());
            pitch = Math.min(pitch, s.pitch());
            ticks++;
        }
        assertTrue(lowest < -0.01 && lowest > -0.1, "sinks onto its springs: " + lowest);
        assertTrue(pitch < -0.3, "the nose lifts climbing it going forward: " + pitch);
        assertTrue(ticks < 60, "settles within about 3 s: " + ticks);
        assertTrue(s.settled());
        // a smooth slab ride is no step
        s.drive(0.1, 6, 6, 0, 0.05, 2, 2.5);
        assertTrue(s.settled());
        // a hard brake dips the nose
        s.drive(0, 4, 4.45, 0, 0.05, 2, 2.5);
        s.step();
        assertTrue(s.pitch() > 0, "nose down");
        s.reset();
        // a right turn throws the load to the left
        s.drive(0, 6, 6, 3, 0.05, 2, 2.5);
        s.step();
        assertTrue(s.sway() > 0);
        assertTrue(Math.abs(s.sway()) <= Suspension.MAX_ANGLE);
    }

    @Test
    void theIdleTrembleAlternates() {
        assertEquals(0, Suspension.shake(10, 3, 0), 1e-12);
        double a = Suspension.shake(0, 0, 0.01), b = Suspension.shake(2, 0, 0.01);
        assertTrue(a * b < 0, "up then down");
        for (int t = 0; t < 100; t++) assertTrue(Math.abs(Suspension.shake(t, 7, 0.01)) <= 0.01 + 1e-12);
    }

    @Test
    void wheelsThrowUpTheGround() {
        assertEquals(GroundFx.Kind.DUST, GroundFx.of(Material.DIRT_PATH, Material.AIR));
        assertEquals(GroundFx.Kind.DUST, GroundFx.of(Material.SAND, Material.AIR));
        assertEquals(GroundFx.Kind.DUST, GroundFx.of(Material.FARMLAND, Material.AIR));
        assertEquals(GroundFx.Kind.MUD, GroundFx.of(Material.MUD, Material.AIR));
        assertEquals(GroundFx.Kind.SPLASH, GroundFx.of(Material.STONE, Material.WATER), "a puddle, shallow water");
        assertEquals(GroundFx.Kind.SNOW, GroundFx.of(Material.GRASS_BLOCK, Material.SNOW), "a snow layer");
        assertEquals(GroundFx.Kind.SNOW, GroundFx.of(Material.SNOW_BLOCK, Material.AIR));
        assertEquals(GroundFx.Kind.NONE, GroundFx.of(Material.GRASS_BLOCK, Material.AIR), "grass holds");
        assertEquals(GroundFx.Kind.NONE, GroundFx.of(Material.STONE, Material.AIR), "roads and stone stay clean");
        assertEquals(0, GroundFx.count(GroundFx.Kind.NONE, 5, 8));
        assertTrue(GroundFx.count(GroundFx.Kind.DUST, 8, 8) > GroundFx.count(GroundFx.Kind.DUST, 2, 8), "more the faster");
        assertTrue(GroundFx.count(GroundFx.Kind.DUST, 20, 8) <= 4);
    }

    // ------------------------------------------------------------------------------------------
    // The shipped file
    // ------------------------------------------------------------------------------------------

    static VehicleFeel.File shipped(List<String> problems) {
        Path pack = CONTENT.resolve("stsv");
        return VehicleFeel.parse(YamlConfiguration.loadConfiguration(pack.resolve("vehicle_feel.yml").toFile()), "stsv",
                m -> Files.exists(pack.resolve("models/" + m + ".bbmodel")), problems::add);
    }

    /** Every vehicle item of the pack (items/*.yml with vehicle: or jige:). */
    static List<String> vehicleItems() throws Exception {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(CONTENT.resolve("stsv/items"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String id : y.getKeys(false)) if (y.isConfigurationSection(id + ".vehicle") || y.isConfigurationSection(id + ".jige")) out.add(id);
            }
        }
        return out;
    }

    @Test
    void theShippedFileReadsAndCoversEveryVehicle() throws Exception {
        List<String> problems = new ArrayList<>();
        VehicleFeel.File f = shipped(problems);
        assertEquals(List.of(), problems);
        List<String> items = vehicleItems();
        for (String key : f.vehicles().keySet()) assertTrue(items.contains(key.substring("stsv:".length())), key + " is a vehicle item");
        for (String id : List.of("police_car", "light_truck", "golf_cart", "hand_cart", "cargo_bicycle", "delivery_scooter", "atv",
                "cargo_van", "box_truck", "ox_cart", "power_tiller", "tractor_trailer", "jige"))
            assertNotNull(f.vehicles().get("stsv:" + id), id + " has its feel");
        Map<String, VehicleFeel.Spec> v = f.vehicles();
        // who pushes: people, an ox, engines
        for (String id : List.of("hand_cart", "cargo_bicycle", "jige")) {
            assertEquals(VehicleFeel.Power.MUSCLE, v.get("stsv:" + id).power(), id);
            assertFalse(v.get("stsv:" + id).effort().none(), id + " costs food");
        }
        assertEquals(VehicleFeel.Power.ANIMAL, v.get("stsv:ox_cart").power());
        // the motor vehicles have lamps and an engine start / stop; trucks beep backing up
        for (String id : List.of("delivery_scooter", "atv", "power_tiller", "tractor_trailer", "cargo_van", "light_truck", "box_truck",
                "police_car", "golf_cart")) {
            VehicleFeel.Spec s = v.get("stsv:" + id);
            assertEquals(VehicleFeel.Power.ENGINE, s.power(), id);
            assertTrue(s.lamps().front(), id + " has headlamps");
            assertTrue(s.ignition(), id + " starts and stops");
            assertTrue(s.effort().none(), id + " costs no food");
        }
        for (String id : List.of("light_truck", "cargo_van", "box_truck")) {
            assertTrue(v.get("stsv:" + id).reverseBeep(), id + " beeps");
            assertNotNull(v.get("stsv:" + id).lamps().reverse(), id + " has reverse lamps");
        }
        assertFalse(v.get("stsv:police_car").reverseBeep());
        assertTrue(v.get("stsv:cargo_bicycle").pedalBob() > 0, "the rider pedals");
        assertNotNull(f.settings().beep(), "the reverse beep's sound");
        assertTrue(f.settings().onBelow() < f.settings().offAbove());
        // a vehicle not in the file feels like a plain engine
        assertEquals(VehicleFeel.Power.ENGINE, f.spec("stsv:nothing", VehicleFeel.Power.ENGINE).power());
    }

    @Test
    void lampPartsAreDrawnLikeTheBody() throws Exception {
        org.bukkit.util.Transformation body = new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.1f, 0),
                new org.joml.Quaternionf().rotateZ(0.1f), new org.joml.Vector3f(2, 2, 2), new org.joml.Quaternionf());
        org.bukkit.util.Transformation lamp = VehicleFeelView.lampTransform(body);
        assertEquals(body.getTranslation(), lamp.getTranslation(), "same place and pose");
        assertEquals(body.getLeftRotation(), lamp.getLeftRotation());
        assertEquals(4, lamp.getScale().x, 1e-6, "drawn LAMP_SCALE times smaller, shown that much larger");
        String py = Files.readString(CONTENT.getParent().resolve("tools/assetgen/models/vehicle_lights.py"));
        assertTrue(py.contains("LAMP_SCALE = " + (int) VehicleFeelView.LAMP_SCALE + "\n"), "the generator's LAMP_SCALE matches");
    }

    @Test
    void itsSoundsAreInThePack() throws Exception {
        VehicleFeel.File f = shipped(new ArrayList<>());
        Path assets = CONTENT.resolve("stsv/assets/stsv");
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
        List<VehicleFeel.Sound> all = new ArrayList<>();
        all.add(f.settings().beep());
        for (VehicleFeel.Spec s : f.vehicles().values()) {
            if (s.start() != null) all.add(s.start());
            if (s.stop() != null) all.add(s.stop());
        }
        assertTrue(all.size() >= 19);
        for (VehicleFeel.Sound s : all) {
            String event = s.key().substring("stsv:".length());
            assertTrue(sounds.has(event), "sounds.json has " + event);
            for (com.google.gson.JsonElement el : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
                String name = el.isJsonObject() ? el.getAsJsonObject().get("name").getAsString() : el.getAsString();
                Path ogg = assets.resolve("sounds/" + name.substring("stsv:".length()) + ".ogg");
                assertTrue(Files.isRegularFile(ogg), ogg + " exists");
                assertTrue(Files.size(ogg) < 48 * 1024, ogg + " stays small");
                assertEquals(1, OggLength.channels(ogg), ogg + ": mono");
                double seconds = OggLength.seconds(ogg);
                assertTrue(seconds > 0.1 && seconds < 2.5, ogg + ": short, " + seconds);
                // the engine's hum starts as the start sound ends (a little overlap)
                if (s.ticks() > 0) assertTrue(Math.abs(s.ticks() / 20.0 - seconds) < 0.4, ogg + ": " + s.ticks() + " ticks vs " + seconds + " s");
            }
        }
    }
}
