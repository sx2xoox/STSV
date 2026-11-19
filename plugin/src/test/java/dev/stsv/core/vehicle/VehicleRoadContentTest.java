package dev.stsv.core.vehicle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The road vehicles of the transport ladder (content/stsv/items/vehicles_road.yml): 짐자전거 (2세대), 배달 오토바이 (3세대),
 * 사륜 오토바이 (특수), 소형 화물 밴 (4세대), 탑차 (5세대). Their specs as briefed, their sounds in the pack, admin-only, and
 * every ladder vehicle's generation in its lore.
 */
class VehicleRoadContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final Path PACK = CONTENT.resolve("stsv");
    private static final List<String> ROAD = List.of("cargo_bicycle", "delivery_scooter", "atv", "cargo_van", "box_truck");

    private static YamlConfiguration items(String file) {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/" + file).toFile());
    }

    private static VehicleSpec spec(YamlConfiguration y, String id) {
        ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
        assertNotNull(s, id);
        return VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
    }

    private static Map<String, VehicleSpec> road() {
        YamlConfiguration y = items("vehicles_road.yml");
        Map<String, VehicleSpec> out = new LinkedHashMap<>();
        for (String id : ROAD) out.put(id, spec(y, id));
        return out;
    }

    @Test
    void theirSpecsAreAsBriefed() {
        Map<String, VehicleSpec> v = road();
        // id -> {seats, top speed, cargo slots}
        Map<String, double[]> want = Map.of(
                "cargo_bicycle", new double[]{1, 6.5, 27},
                "delivery_scooter", new double[]{2, 9.5, 54},
                "atv", new double[]{1, 8, 54},
                "cargo_van", new double[]{2, 8.5, 360},
                "box_truck", new double[]{2, 7.5, 720});
        for (Map.Entry<String, VehicleSpec> e : v.entrySet()) {
            VehicleSpec s = e.getValue();
            double[] w = want.get(e.getKey());
            assertEquals((int) w[0], s.seats().size(), e.getKey() + " seats");
            assertEquals(w[1], s.speed(), 1e-9, e.getKey() + " top speed");
            assertEquals((int) w[2], s.cargo().slots(), e.getKey() + " cargo");
            assertTrue(s.speed() <= 10 && s.reverseSpeed() < s.speed(), e.getKey());
            assertFalse(s.pulled(), "driven");
            assertEquals(1.0, s.stepHeight(), 1e-9, e.getKey() + ": one-block ledges");
            assertTrue(s.clickHeight() <= s.height(), e.getKey());
            assertTrue(s.wheels().stream().filter(VehicleSpec.Wheel::steer).allMatch(x -> x.at().forward() > 0), "the front wheels steer");
            assertTrue(s.wheels().stream().anyMatch(VehicleSpec.Wheel::steer), e.getKey() + " steers");
            for (VehicleSpec.Vec seat : s.seats()) {
                assertTrue(Math.abs(seat.right()) < s.width() / 2 && Math.abs(seat.forward()) < s.length() / 2, e.getKey() + ": seat over it");
            }
        }
        // the paging: 27 (one short page), 54 (45 + 9), 360 (8), 720 (16)
        assertEquals(1, v.get("cargo_bicycle").cargo().pages());
        assertEquals(2, v.get("delivery_scooter").cargo().pages());
        assertEquals(8, v.get("cargo_van").cargo().pages());
        assertEquals(16, v.get("box_truck").cargo().pages());

        // two-wheelers: one wheel steers, the other follows, both in the middle; they lean into turns; one rider each in the middle
        for (String id : List.of("cargo_bicycle", "delivery_scooter")) {
            VehicleSpec s = v.get(id);
            assertEquals(2, s.wheels().size(), id);
            assertEquals(1, s.wheels().stream().filter(VehicleSpec.Wheel::steer).count(), id);
            assertTrue(s.wheels().stream().allMatch(w -> w.at().right() == 0), id + ": in line");
            assertTrue(s.extras().lean() > 0 && s.extras().lean() <= 15, id + " leans a little");
            assertTrue(s.seats().stream().allMatch(seat -> seat.right() == 0), id + ": riders in the middle");
        }
        VehicleSpec bike = v.get("cargo_bicycle");
        assertTrue(bike.width() <= 0.7, "a narrow hitbox");
        assertEquals(1, bike.extras().spinners().size(), "the pedal crank turns with the wheels");
        assertEquals(0.5, bike.extras().spinners().getFirst().ratio(), 1e-9);
        assertTrue(bike.partModels().contains(bike.extras().spinners().getFirst().model()), "the crank counts in the render budget");
        // four-wheelers: two front wheels steer, no lean
        for (String id : List.of("atv", "cargo_van", "box_truck")) {
            VehicleSpec s = v.get(id);
            assertEquals(4, s.wheels().size(), id);
            assertEquals(2, s.wheels().stream().filter(VehicleSpec.Wheel::steer).count(), id);
            assertEquals(0, s.extras().lean(), 1e-9, id);
        }
        // the van and the truck: driver on the left (Korea drives on the right)
        assertTrue(v.get("cargo_van").seats().getFirst().right() < 0);
        assertTrue(v.get("box_truck").seats().getFirst().right() < 0);

        // only the ATV is all-terrain
        for (Map.Entry<String, VehicleSpec> e : v.entrySet()) assertEquals(e.getKey().equals("atv"), e.getValue().extras().allTerrain(), e.getKey());

        // what shows of the load: the bicycle's basket and the ATV's racks; the scooter's box, the van and the truck are closed
        assertEquals(4, bike.cargo().stages().size());
        assertEquals(4, v.get("atv").cargo().stages().size());
        for (String id : List.of("delivery_scooter", "cargo_van", "box_truck")) assertTrue(v.get(id).cargo().stages().isEmpty(), id + " is closed");

        // the box truck is big and turns slowly
        VehicleSpec truck = v.get("box_truck");
        assertEquals(6.5, truck.length(), 0.01);
        assertEquals(2.2, truck.width(), 0.01);
        assertEquals(3.0, truck.height(), 0.01);
        for (Map.Entry<String, VehicleSpec> e : v.entrySet()) {
            if (e.getValue() != truck) assertTrue(truck.turnRate() < e.getValue().turnRate(), "turns slower than the " + e.getKey());
        }
        // capacities up the ladder: the two-wheelers carry little (but ride), then the hand cart, the van, the 1-ton truck, the box truck
        VehicleSpec cart = spec(items("vehicles.yml"), "hand_cart"), lightTruck = spec(items("vehicles.yml"), "light_truck");
        assertTrue(v.get("delivery_scooter").cargo().slots() < cart.cargo().slots() && cart.cargo().slots() < v.get("cargo_van").cargo().slots());
        assertTrue(v.get("cargo_van").cargo().slots() < lightTruck.cargo().slots() && lightTruck.cargo().slots() < truck.cargo().slots());
    }

    /** Every road vehicle honks (the bicycle rings its bell) and sounds (the bicycle's chain ticks) with its own clips. */
    @Test
    void theirSoundsAreInThePack() throws Exception {
        Path assets = PACK.resolve("assets/stsv");
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
        for (Map.Entry<String, VehicleSpec> e : road().entrySet()) {
            VehicleSpec s = e.getValue();
            assertNotNull(s.horn(), e.getKey());
            assertNotNull(s.engine(), e.getKey());
            assertEquals("stsv:vehicle." + e.getKey() + ".horn", s.horn().sound());
            assertEquals("stsv:vehicle." + e.getKey() + ".horn_end", s.horn().release());
            assertEquals("stsv:vehicle." + e.getKey() + ".engine", s.engine().sound());
            assertTrue(s.engine().volume() <= 0.5 && s.engine().idleVolume() < s.engine().volume() && s.engine().idlePitch() < s.engine().topPitch());
            assertEquals(10, s.engine().segmentTicks());
            for (String key : List.of(s.horn().sound(), s.horn().release(), s.engine().sound())) {
                String event = key.substring("stsv:".length());
                assertTrue(sounds.has(event), "sounds.json has " + event);
                for (com.google.gson.JsonElement el : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
                    String name = el.isJsonObject() ? el.getAsJsonObject().get("name").getAsString() : el.getAsString();
                    Path ogg = assets.resolve("sounds/" + name.substring("stsv:".length()) + ".ogg");
                    assertTrue(Files.isRegularFile(ogg), ogg + " exists");
                    assertTrue(Files.size(ogg) < 64 * 1024, ogg + " stays small");
                    double seconds = OggLength.seconds(ogg);
                    if (key.equals(s.horn().sound())) assertTrue(seconds >= VehicleModule.HORN_CLIP_SECONDS, ogg + " covers the longest hold");
                    if (key.equals(s.horn().release())) assertTrue(seconds > 0.1 && seconds < 0.5, ogg + ": a short tail");
                    if (key.equals(s.engine().sound())) assertEquals(0.65, seconds, 0.02, ogg + ": one segment + its fade");
                    assertEquals(1, OggLength.channels(ogg), ogg + ": mono");
                }
            }
        }
        VehicleSpec bike = road().get("cargo_bicycle");
        assertEquals(0, bike.engine().idleVolume(), "no engine: silent standing");
        assertTrue(bike.engine().volume() < 0.2, "a soft chain tick");
    }

    /** Admin-only: the admin browser's 탈것 tab and /관리자 지급, never the shop, a gacha or a recipe. */
    @Test
    void onlyAdminsHandThemOut() throws Exception {
        YamlConfiguration y = items("vehicles_road.yml");
        for (String id : ROAD) {
            for (String key : List.of("price", "shop", "buy", "sell", "recipe", "gacha")) assertFalse(y.contains(id + "." + key), id + "." + key);
        }
        List<Path> roots = List.of(PACK, CONTENT.getParent().resolve("economy/content/economy"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    if (f.getFileName().toString().startsWith("vehicle")) continue;   // vehicles*.yml, vehicle_feel.yml
                    String text = Files.readString(f);
                    boolean shop = f.getFileName().toString().equals("shop.yml");   // generations 1-4 are sold there (4세대 패치)
                    for (String id : ROAD) {
                        if (shop && java.util.Set.of("hand_cart", "jige", "cargo_bicycle", "ox_cart", "delivery_scooter", "power_tiller", "cargo_van", "light_truck", "combine").contains(id)) continue;
                        assertFalse(text.contains("stsv:" + id) || text.contains(" " + id + ":"), f + " mentions " + id);
                    }
                }
            }
        }
    }

    /** The ladder in the lore: "N세대" (special: "특수") on every ladder vehicle, none on the event vehicles. */
    @Test
    void theLoreNamesTheirGeneration() {
        YamlConfiguration road = items("vehicles_road.yml"), old = items("vehicles.yml");
        Map<String, String> gen = Map.of("cargo_bicycle", "2세대", "delivery_scooter", "3세대", "atv", "특수", "cargo_van", "4세대",
                "box_truck", "5세대");
        for (Map.Entry<String, String> e : gen.entrySet()) {
            List<String> lore = road.getStringList(e.getKey() + ".lore");
            assertTrue(lore.stream().anyMatch(l -> l.contains(e.getValue())), e.getKey() + ": " + e.getValue());
            assertTrue(lore.stream().anyMatch(l -> l.contains("W·S") && l.contains("A·D")), e.getKey() + ": the driving keys");
            assertTrue(lore.stream().anyMatch(l -> l.contains("스페이스")), e.getKey() + ": the horn key");
        }
        assertTrue(road.getStringList("cargo_bicycle.lore").stream().anyMatch(l -> l.contains("벨")), "the bicycle rings a bell");
        assertTrue(old.getStringList("hand_cart.lore").stream().anyMatch(l -> l.contains("1세대")));
        assertTrue(old.getStringList("light_truck.lore").stream().anyMatch(l -> l.contains("4세대")));
        for (String event : List.of("police_car", "golf_cart")) {
            assertTrue(old.getStringList(event + ".lore").stream().noneMatch(l -> l.contains("세대") || l.contains("특수")), event + " is an event vehicle");
        }
        assertNull(old.getConfigurationSection("cargo_bicycle"), "the road vehicles live in their own file");
    }

    /**
     * 마을버스: a driver and eight passengers, no bed, two doors on the kerb side (the front one for the driver, the
     * middle one for the passengers), the longest and slowest-turning road vehicle, under the server's cap.
     */
    @Test
    void theVillageBusCarriesPeopleNotGoods() throws Exception {
        YamlConfiguration y = items("vehicles_road.yml");
        VehicleSpec bus = spec(y, "village_bus"), truck = spec(y, "box_truck");
        assertEquals(9, bus.seats().size(), "the driver and eight passengers");
        assertNull(bus.cargo(), "no bed: it carries people");
        assertEquals(9, bus.speed(), 1e-9);
        assertFalse(bus.extras().overCap());
        assertNull(bus.extras().drift(), "a bus does not drift");
        assertFalse(bus.pulled());
        assertEquals(1.0, bus.stepHeight(), 1e-9);
        assertEquals(7.0, bus.length(), 0.01);
        assertEquals(2.4, bus.width(), 0.01);
        assertEquals(3.0, bus.height(), 0.01);
        assertTrue(bus.clickHeight() <= bus.height());
        for (VehicleSpec v : road().values()) {
            assertTrue(bus.turnRate() < v.turnRate(), "turns slower than any other road vehicle");
            assertTrue(bus.length() > v.length(), "the longest");
        }
        assertTrue(bus.acceleration() <= truck.acceleration(), "gets going no quicker than the box truck");
        // four wheels, the front two steer
        assertEquals(4, bus.wheels().size());
        assertEquals(2, bus.wheels().stream().filter(VehicleSpec.Wheel::steer).count());
        assertTrue(bus.wheels().stream().filter(VehicleSpec.Wheel::steer).allMatch(w -> w.at().forward() > 0));
        // the driver at the front on the left, every passenger behind; every seat over the bus and under its roof
        VehicleSpec.Vec driver = bus.seats().getFirst();
        assertTrue(driver.right() < 0, "the driver sits on the left");
        for (VehicleSpec.Vec seat : bus.seats()) {
            assertTrue(Math.abs(seat.right()) < bus.width() / 2 && Math.abs(seat.forward()) < bus.length() / 2, "seat over it");
            assertTrue(seat.up() + 1.275 < bus.height() - 0.25, "a seated head clears the roof");
            if (seat != driver) assertTrue(seat.forward() < driver.forward(), "passengers sit behind the driver");
        }
        // the doors: both on the right; the front one opens for the driver, the middle one for every passenger
        List<VehicleSpec.Mover> movers = bus.extras().dress().movers();
        assertEquals(2, movers.size());
        for (VehicleSpec.Mover mv : movers) {
            assertEquals(VehicleSpec.Channel.DOOR, mv.turn() != 0 ? mv.when() : mv.slideWhen(), mv.model().toString());
            assertTrue(Files.exists(PACK.resolve("models/" + mv.model().getKey().substring("model/".length()) + ".bbmodel")));
        }
        assertEquals(List.of(0), movers.get(0).seats(), "the front door: the driver");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), movers.get(1).seats(), "the middle door: the passengers");
        assertTrue(movers.get(0).hinge().right() > 0 && movers.get(0).hinge().forward() > driver.forward(), "beside the driver, on the right");
        assertTrue(movers.get(1).slide().forward() != 0, "the middle door slides along the side");
        assertEquals(2, bus.extras().dress().plates().size(), "plates front and back");
        // its horn and engine are the box truck's (in the pack)
        assertEquals(truck.horn().sound(), bus.horn().sound());
        assertEquals(truck.engine().sound(), bus.engine().sound());
        // admin-only for now: no price, shop, gacha or recipe
        for (String key : List.of("price", "shop", "buy", "sell", "recipe", "gacha")) assertFalse(y.contains("village_bus." + key), key);
    }
}
