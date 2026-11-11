package dev.stsv.core.vehicle;

import dev.stsv.core.gui.ItemCatalog;
import org.bukkit.Material;
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
 * The articulated farm vehicles (content/stsv/items/vehicles_farm.yml): 소달구지 (2세대), 경운기 + 짐칸 (3세대), 트랙터 +
 * 트레일러 (5세대). They read, their numbers are the ones asked for, their sounds and icons are in the pack, and they are
 * admin-only.
 */
class VehicleFarmContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final Path PACK = CONTENT.resolve("stsv");
    static final List<String> IDS = List.of("ox_cart", "power_tiller", "tractor_trailer");

    private static YamlConfiguration file() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles_farm.yml").toFile());
    }

    static Map<String, VehicleSpec> shipped() {
        YamlConfiguration y = file();
        Map<String, VehicleSpec> out = new LinkedHashMap<>();
        for (String id : IDS) {
            ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
            assertNotNull(s, id);
            out.put(id, VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel"))));
        }
        return out;
    }

    @Test
    void theThreeTrainsRead() {
        Map<String, VehicleSpec> v = shipped();
        VehicleSpec ox = v.get("ox_cart"), tiller = v.get("power_tiller"), tractor = v.get("tractor_trailer");
        // speeds: the generations get faster, all well under the server cap
        assertEquals(4, ox.speed(), 1e-9);
        assertEquals(5, tiller.speed(), 1e-9);
        assertEquals(6, tractor.speed(), 1e-9);
        // seats: two on the ox cart's bench, two on the tiller trailer's seat (the driver there, as on real tillers),
        // the tractor's own seat plus one passenger
        assertEquals(List.of(1, 1), ox.train().seatUnits(), "both on the cart's front bench");
        assertEquals(List.of(1, 1), tiller.train().seatUnits(), "the driver on the trailer's front seat");
        assertEquals(2, tractor.seats().size());
        assertEquals(0, tractor.train().seatUnit(0), "the driver on the tractor");
        // the beds: 270, 360, 2 x 360; no trailer's bed over one bed's 540, whatever the global limit
        assertEquals(270, ox.train().slots());
        assertEquals(360, tiller.train().slots());
        assertEquals(720, tractor.train().slots());
        assertEquals(2, tractor.train().trailers().size(), "two trailers in a chain");
        assertEquals(16, tractor.cargo().pages(), "one storage of 16 pages, 8 a trailer");
        for (VehicleSpec s : v.values()) {
            assertNotNull(s.train());
            for (TrainSpec.Trailer t : s.train().trailers()) {
                assertNotNull(t.cargo());
                assertTrue(t.cargo().slots() <= VehicleSpec.Cargo.PAGE * VehicleSpec.Cargo.MAX_PAGES, "each trailer within one bed's limit");
                assertEquals(4, t.cargo().stages().size(), "a load shown in four stages");
                assertEquals(2, t.unit().wheels().size(), "two wheels on one axle: it turns about it");
                assertTrue(t.tongue() > t.unit().length() / 2, "the tongue reaches out past its front");
            }
            assertTrue(s.train().jackknife() >= 35 && s.train().jackknife() <= 55, "a jack-knife limit");
            assertTrue(s.pulled() == false && s.cargo() != null);
            assertEquals(1.0, s.stepHeight(), 1e-9, "one-block ledges, like the others");
            // every seat inside its unit
            for (int i = 0; i < s.seats().size(); i++) {
                int u = s.train().seatUnit(i);
                VehicleSpec unit = u == 0 ? s : s.train().trailers().get(u - 1).unit();
                VehicleSpec.Vec seat = s.seats().get(i);
                assertTrue(Math.abs(seat.right()) < unit.width() / 2 && Math.abs(seat.forward()) < unit.length() / 2, "seat " + i + " inside its unit");
            }
        }
        // the ox: four legs a quarter stride apart, a bobbing head, hoof steps; no engine; it moos
        TrainSpec.Animal a = ox.train().animal();
        assertNotNull(a);
        assertEquals(4, a.legs().size());
        assertEquals(List.of(0.0, 0.25, 0.5, 0.75), a.legs().stream().map(TrainSpec.Leg::phase).sorted().toList());
        assertNotNull(a.head());
        assertTrue(a.bob() > 0 && a.bob() < 0.1);
        assertNotNull(a.steps(), "soft hoof steps");
        assertNull(ox.engine(), "no engine");
        assertEquals("entity.cow.ambient", ox.horn().vanilla(), "without the pack's sounds: the vanilla cow");
        assertTrue(ox.horn().pitch() < 1, "pitched down");
        assertNull(tiller.train().animal());
        // the tiller's flywheel spins with the engine; the tractor's big rear wheels roll
        assertEquals(TrainSpec.Spin.ENGINE, tiller.train().parts().getFirst().spin());
        assertEquals(2, tractor.train().parts().size());
        for (TrainSpec.Part p : tractor.train().parts()) assertEquals(TrainSpec.Spin.ROLL, p.spin());
        assertTrue(tractor.wheels().stream().allMatch(VehicleSpec.Wheel::steer), "the small front wheels steer");
        // the ground: the tiller barely slows on farmland and dirt paths, the tractor not at all on rough ground
        for (TrainSpec.Surface g : List.of(TrainSpec.Surface.FARMLAND, TrainSpec.Surface.PATH)) {
            assertTrue(tiller.train().ground(g) >= 0.95, "the tiller on " + g);
            assertTrue(tiller.train().ground(g) >= ox.train().ground(g), "the tiller beats the ox cart on " + g);
            assertTrue(TrainSpec.DEFAULT_GROUND.get(g) < tiller.train().ground(g), "and a plain cart");
        }
        for (TrainSpec.Surface g : List.of(TrainSpec.Surface.DIRT, TrainSpec.Surface.FARMLAND, TrainSpec.Surface.GRASS)) {
            assertEquals(1.0, tractor.train().ground(g), 1e-9, "the tractor on " + g);
        }
        // mud, sand, gravel, snow: the shared rough ground (VehiclePhysics ROUGH); the tractor is all-terrain, the others slow
        assertTrue(tractor.extras().allTerrain(), "the tractor: no slowdown in mud");
        assertFalse(ox.extras().allTerrain());
        assertFalse(tiller.extras().allTerrain());
    }

    @Test
    void theLoreSaysTheGenerationAndTheControls() {
        YamlConfiguration y = file();
        Map<String, String> gen = Map.of("ox_cart", "2세대", "power_tiller", "3세대", "tractor_trailer", "5세대");
        for (String id : IDS) {
            String lore = String.join("\n", y.getStringList(id + ".lore"));
            assertTrue(lore.contains(gen.get(id)), id + ": " + gen.get(id));
            for (String k : List.of("W·S", "A·D", "웅크리고", "회수", "짐칸")) assertTrue(lore.contains(k), id + " lore mentions " + k);
            assertEquals(id + "_icon", y.getString(id + ".icon"));
            assertTrue(Files.isRegularFile(PACK.resolve("textures/" + id + "_icon.png")), id + "'s icon");
            assertEquals(1, y.getInt(id + ".max-stack"));
        }
    }

    /** The horn (a moo for the ox), its release and the engine of each are in the pack, as long as the server expects. */
    @Test
    void theirSoundsAreInThePack() throws Exception {
        Path assets = PACK.resolve("assets/stsv");
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
        for (Map.Entry<String, VehicleSpec> e : shipped().entrySet()) {
            VehicleSpec s = e.getValue();
            assertEquals("stsv:vehicle." + e.getKey() + ".horn", s.horn().sound());
            assertEquals("stsv:vehicle." + e.getKey() + ".horn_end", s.horn().release());
            List<String> keys = new java.util.ArrayList<>(List.of(s.horn().sound(), s.horn().release()));
            if (s.engine() != null) {
                assertEquals("stsv:vehicle." + e.getKey() + ".engine", s.engine().sound());
                keys.add(s.engine().sound());
            }
            for (String key : keys) {
                String event = key.substring("stsv:".length());
                assertTrue(sounds.has(event), "sounds.json has " + event);
                String name = sounds.getAsJsonObject(event).getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString();
                Path ogg = assets.resolve("sounds/" + name.substring("stsv:".length()) + ".ogg");
                assertTrue(Files.isRegularFile(ogg), ogg + " exists");
                assertTrue(Files.size(ogg) < 64 * 1024, ogg + " stays small");
                double seconds = OggLength.seconds(ogg);
                if (key.equals(s.horn().sound())) assertTrue(seconds >= VehicleModule.HORN_CLIP_SECONDS, ogg + " covers the longest hold");
                if (key.equals(s.horn().release())) assertTrue(seconds > 0.1 && seconds < 0.5, ogg + ": a short tail");
                if (s.engine() != null && key.equals(s.engine().sound())) assertEquals(s.engine().segmentTicks() / 20.0 + 0.15, seconds, 0.02, ogg.toString());
                assertEquals(1, OggLength.channels(ogg), ogg + ": mono");
            }
        }
        assertNotNull(shipped().get("power_tiller").engine(), "the tiller's 통통통");
        assertNotNull(shipped().get("tractor_trailer").engine(), "the tractor's diesel");
    }

    /** Admin-only: the admin browser's 탈것 tab (any items file), /관리자 지급; no shop, gacha or recipe has them. */
    @Test
    void onlyAdminsHandThemOut() throws Exception {
        YamlConfiguration y = file();
        for (String id : IDS) {
            ConfigurationSection s = y.getConfigurationSection(id);
            assertNotNull(s.getConfigurationSection("vehicle"));
            for (String key : List.of("price", "shop", "buy", "sell", "recipe", "gacha")) assertFalse(s.contains(key), id + "." + key);
            ItemCatalog.Facts f = new ItemCatalog.Facts("stsv:" + id, s.getString("name"), "vehicles_farm", Material.PAPER, false, false, false,
                    false, false, true);
            assertEquals(ItemCatalog.Category.VEHICLE, ItemCatalog.of(f), id + " in the admin browser's 탈것 tab");
        }
        List<Path> roots = List.of(PACK, CONTENT.getParent().resolve("economy/content/economy"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    if (f.getFileName().toString().startsWith("vehicles")) continue;
                    String text = Files.readString(f);
                    boolean shop = f.getFileName().toString().equals("shop.yml");   // generations 1-3 are sold there
                    for (String id : IDS) {
                        if (shop && java.util.Set.of("hand_cart", "jige", "cargo_bicycle", "ox_cart", "delivery_scooter", "power_tiller").contains(id)) continue;
                        assertFalse(text.contains(id), f + " mentions " + id);
                    }
                }
            }
        }
    }
}
