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

/** content/stsv/items/vehicles_misc.yml: the 통통배 reads as a driven boat, its sounds are in the pack, both items are admin-only. */
class VehicleMiscContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final List<String> IDS = List.of("fishing_boat", "jige");

    private static YamlConfiguration items() {
        return YamlConfiguration.loadConfiguration(CONTENT.resolve("stsv/items/vehicles_misc.yml").toFile());
    }

    @Test
    void theFishingBoat() {
        VehicleSpec s = BoatPhysicsTest.boat();
        assertTrue(s.floats(), "a boat");
        assertFalse(s.pulled(), "driven");
        assertEquals(3, s.seats().size(), "3 seats: the helmsman and two on the bow thwart");
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
        assertTrue(lore.contains("특수"), "its generation");
        assertTrue(lore.contains("물") && lore.contains("우클릭") && lore.contains("낚시"), "where it goes, how to use it, fishing from it");
    }

    @Test
    void itsSoundsAreInThePack() throws Exception {
        Path assets = CONTENT.resolve("stsv/assets/stsv");
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
        VehicleSpec s = BoatPhysicsTest.boat();
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
                    if (f.getFileName().toString().equals("vehicles_misc.yml")) continue;
                    String text = Files.readString(f);
                    assertFalse(text.contains("fishing_boat"), f + " mentions fishing_boat");
                    if (!f.getFileName().toString().equals("shop.yml"))   // the 지게 (1세대) is sold there
                        assertFalse(text.matches("(?s).*\\bjige\\b.*"), f + " mentions jige");
                }
            }
        }
    }
}
