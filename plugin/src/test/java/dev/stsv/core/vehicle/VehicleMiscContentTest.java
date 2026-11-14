package dev.stsv.core.vehicle;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** content/stsv/items/vehicles_misc.yml: the fishing boats (1세대 to 5세대) read as driven boats and climb as a ladder, their sounds are in the pack, all of it admin-only. */
class VehicleMiscContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    /** The fishing boats, 1세대 to 5세대. */
    static final List<String> BOATS = List.of("raft", "rowboat", "fishing_boat", "coastal_boat", "trawler");
    private static final List<String> IDS = List.of("raft", "rowboat", "fishing_boat", "coastal_boat", "trawler", "jige");

    private static YamlConfiguration items() {
        return YamlConfiguration.loadConfiguration(CONTENT.resolve("stsv/items/vehicles_misc.yml").toFile());
    }

    @Test
    void theFishingBoat() {
        VehicleSpec s = BoatPhysicsTest.boat();
        assertTrue(s.floats(), "a boat");
        assertFalse(s.pulled(), "driven");
        assertEquals(4, s.seats().size(), "4 seats: the helmsman, two on the bow thwart, the angler on the fore peak");
        assertEquals(7, s.speed(), 1e-9, "7 blocks/s on water");
        assertTrue(s.reverseSpeed() < s.speed());
        for (VehicleSpec.Vec seat : s.seats()) {
            assertTrue(Math.abs(seat.right()) < s.width() / 2 && Math.abs(seat.forward()) < s.length() / 2, "seats inside the hull");
            assertTrue(seat.up() > s.water().draft(), "above the water");
        }
        assertEquals(270, s.cargo().slots(), "a 270-slot hold");
        assertEquals(6, s.cargo().pages());
        assertEquals(4, s.cargo().stages().size(), "the catch shown by how full the hold is");
        assertTrue(s.cargo().behind() < 0 && s.cargo().at().forward() < s.cargo().behind(), "the hold is the aft deck");
        assertEquals(1, s.wheels().size(), "its 'wheel' is the propeller");
        assertTrue(s.wheels().getFirst().at().forward() < -s.length() / 2 + 0.6, "at the stern");
        assertTrue(s.wheels().getFirst().at().up() < s.water().draft(), "under the water");
        assertNotNull(s.water().exhaust(), "the 통통 stack puffs");
        assertTrue(s.water().exhaust().up() > 2.5, "above the wheelhouse");
        assertEquals(1.0, s.water().nudge(), 1e-9);
        assertTrue(s.height() >= 2.4, "the wheelhouse counts: it does not slip under a low bridge");
        // its horn and engine are its own
        assertEquals("stsv:vehicle.fishing_boat.horn", s.horn().sound());
        assertEquals("stsv:vehicle.fishing_boat.horn_end", s.horn().release());
        assertEquals("stsv:vehicle.fishing_boat.engine", s.engine().sound());
        assertTrue(s.engine().idleVolume() > 0, "통통통 even idling");
        assertEquals(10, s.engine().segmentTicks());
        String lore = String.join("\n", items().getStringList("fishing_boat.lore"));
        assertTrue(lore.contains("3세대") && !lore.contains("특수"), "its generation");
        assertTrue(lore.contains("물") && lore.contains("우클릭") && lore.contains("낚시"), "where it goes, how to use it, fishing from it");
    }

    private static VehicleSpec boat(String id) {
        Path pack = CONTENT.resolve("stsv");
        return VehicleSpec.parse(items().getConfigurationSection(id + ".vehicle"), "stsv", m -> Files.exists(pack.resolve("models/" + m + ".bbmodel")));
    }

    /**
     * The ladder, 뗏목 → 나룻배 → 통통배 → 연안 어선 → 트롤 어선: boats are for fishing from, together, so every generation
     * seats more people, holds more of the catch and goes faster; the first two are rowed (no engine, no horn), from the
     * 통통배 on they have an engine, a horn, a stack that smokes and a net (vehicle_tools.yml).
     */
    @Test
    void theBoatsClimbAsALadder() {
        YamlConfiguration tools = YamlConfiguration.loadConfiguration(CONTENT.resolve("stsv/vehicle_tools.yml").toFile());
        VehicleSpec before = null;
        for (int g = 0; g < BOATS.size(); g++) {
            String id = BOATS.get(g);
            VehicleSpec s = boat(id);
            assertTrue(s.floats() && !s.pulled(), id + ": a driven boat");
            assertNotNull(s.cargo(), id + ": a hold");
            assertEquals(4, s.cargo().stages().size(), id + ": the catch shown by how full the hold is");
            assertTrue(s.cargo().behind() < 0 && s.cargo().at().forward() < s.cargo().behind(), id + ": the hold is aft");
            assertTrue(s.water().draft() > 0 && s.water().draft() < 1, id + ": its draft");
            for (VehicleSpec.Vec seat : s.seats()) {
                assertTrue(Math.abs(seat.right()) < s.width() / 2 && Math.abs(seat.forward()) < s.length() / 2, id + ": seats inside the hull");
                assertTrue(seat.up() > s.water().draft(), id + ": above the water");
            }
            // somewhere to fish from: at least one seat that is not the helmsman's
            assertTrue(s.seats().size() >= 2, id + ": a seat to fish from beside the one who steers");
            String lore = String.join("\n", items().getStringList(id + ".lore"));
            assertTrue(lore.contains((g + 1) + "세대"), id + ": its generation in its lore");
            assertTrue(lore.contains(s.seats().size() + "인승") && lore.contains("낚시"), id + ": its seats and fishing in its lore");
            boolean engine = g >= 2;
            assertEquals(engine, s.engine() != null, id + ": engine");
            assertEquals(engine, s.horn() != null, id + ": horn");
            assertEquals(engine, s.water().exhaust() != null, id + ": a stack that smokes");
            assertTrue(tools.isConfigurationSection(id), id + ": every fishing boat has a net");
            assertEquals(engine, tools.contains(id + ".tools") && tools.getMapList(id + ".tools").getFirst().containsKey("model"),
                    id + ": a net that shows (a winch and a drum) from the 통통배 on; a hand net and pots show nothing");
            if (engine) {
                assertEquals("stsv:vehicle." + id + ".horn", s.horn().sound());
                assertEquals("stsv:vehicle." + id + ".engine", s.engine().sound());
                assertEquals(1, s.wheels().size(), id + ": a propeller");
                assertTrue(s.wheels().getFirst().at().up() < s.water().draft(), id + ": the propeller under the water");
            }
            if (before != null) {
                assertTrue(s.seats().size() > before.seats().size(), id + ": more seats");
                assertTrue(s.cargo().slots() > before.cargo().slots(), id + ": a bigger hold");
                assertTrue(s.cargo().payload() > before.cargo().payload(), id + ": carries more");
                assertTrue(s.speed() > before.speed(), id + ": faster");
                assertTrue(s.length() > before.length(), id + ": longer");
                assertTrue(s.water().nudge() <= before.water().nudge(), id + ": heavier to push off a beach");
            }
            before = s;
        }
        // the raft's sweep is its "wheel" (turned about the raft's length); the rowboat has no wheel: its oars are spinners
        assertEquals(1, boat("raft").wheels().size());
        assertTrue(boat("rowboat").wheels().isEmpty());
        assertEquals(2, boat("rowboat").extras().spinners().size(), "an oar each side");
        assertEquals(-boat("rowboat").extras().spinners().get(0).at().right(), boat("rowboat").extras().spinners().get(1).at().right(), 1e-9);
    }

    @Test
    void itsSoundsAreInThePack() throws Exception {
        for (String id : List.of("fishing_boat", "coastal_boat", "trawler")) soundsOf(boat(id));
    }

    private static void soundsOf(VehicleSpec s) throws Exception {
        Path assets = CONTENT.resolve("stsv/assets/stsv");
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
        for (String key : List.of(s.horn().sound(), s.horn().release(), s.engine().sound())) {
            String event = key.substring("stsv:".length());
            assertTrue(sounds.has(event), "sounds.json has " + event);
            for (com.google.gson.JsonElement el : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
                String name = el.isJsonObject() ? el.getAsJsonObject().get("name").getAsString() : el.getAsString();
                Path ogg = assets.resolve("sounds/" + name.substring("stsv:".length()) + ".ogg");
                assertTrue(Files.isRegularFile(ogg), ogg + " exists");
                assertTrue(Files.size(ogg) < 64 * 1024, ogg + " stays small");
                double seconds = OggLength.seconds(ogg);
                if (key.equals(s.horn().sound())) assertTrue(seconds >= VehicleModule.HORN_CLIP_SECONDS, ogg + ": covers the longest hold");
                if (key.equals(s.horn().release())) assertTrue(seconds > 0.1 && seconds < 0.5, ogg + ": a short tail");
                if (key.equals(s.engine().sound())) assertEquals(s.engine().segmentTicks() / 20.0 + 0.15, seconds, 0.02, ogg + ": one segment + its fade");
                assertEquals(1, OggLength.channels(ogg), ogg + ": mono");
            }
        }
    }

    /** Admin-only: the admin browser's 탈것 tab (a vehicle file), /관리자 지급; no shop, gacha or recipe has them. */
    @Test
    void onlyAdminsHandThemOut() throws Exception {
        YamlConfiguration y = items();
        for (String id : IDS) {
            assertNotNull(y.getConfigurationSection(id), id);
            for (String key : List.of("price", "shop", "buy", "sell", "recipe", "gacha")) assertFalse(y.contains(id + "." + key), id + "." + key);
        }
        assertNull(y.getConfigurationSection("jige.vehicle"), "the 지게 is worn, not placed");
        List<Path> roots = List.of(CONTENT.resolve("stsv"), CONTENT.getParent().resolve("economy/content/economy"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    if (f.getFileName().toString().equals("vehicles_misc.yml") || f.getFileName().toString().equals("vehicle_feel.yml")
                            || f.getFileName().toString().equals("vehicle_tools.yml")   // the feel, the tools: no shop
                            || f.getFileName().toString().equals("fish.yml")) continue;      // the economy's net: which sea a boat's net is made for
                    String text = Files.readString(f);
                    for (String boat : BOATS) assertFalse(text.matches("(?s).*\\b" + boat + "\\b.*"), f + " mentions " + boat);
                    if (!f.getFileName().toString().equals("shop.yml"))   // the 지게 (1세대) is sold there
                        assertFalse(text.matches("(?s).*\\bjige\\b.*"), f + " mentions jige");
                }
            }
        }
    }
}
