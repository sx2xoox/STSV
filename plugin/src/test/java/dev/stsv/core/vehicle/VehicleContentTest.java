package dev.stsv.core.vehicle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped vehicles (content/stsv/items/vehicles.yml): they read, drive at sensible speeds, and are admin-only. The hand
 * cart is pulled on foot (generation 1 of the transport ladder: slow, a small bed); the truck is generation 4 (a big bed).
 * The road vehicles of the ladder: {@link VehicleRoadContentTest}. */
class VehicleContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    /** The driven ones (seats, horn, engine). */
    private static final List<String> IDS = List.of("police_car", "light_truck", "golf_cart");
    /** Every vehicle item. */
    private static final List<String> ALL = List.of("police_car", "light_truck", "golf_cart", "hand_cart");

    private static Map<String, VehicleSpec> shipped() {
        return shipped(IDS);
    }

    private static Map<String, VehicleSpec> shipped(List<String> ids) {
        Path pack = CONTENT.resolve("stsv");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pack.resolve("items/vehicles.yml").toFile());
        java.util.Map<String, VehicleSpec> out = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
            assertNotNull(s, id);
            out.put(id, VehicleSpec.parse(s, "stsv", m -> Files.exists(pack.resolve("models/" + m + ".bbmodel"))));
        }
        return out;
    }

    @Test
    void theThreeVehiclesRead() {
        Map<String, VehicleSpec> v = shipped();
        VehicleSpec police = v.get("police_car"), truck = v.get("light_truck"), cart = v.get("golf_cart");
        // speeds: cart < truck < police car, all moderate (chunk loading keeps up) and under the server cap of 10
        assertTrue(cart.speed() < truck.speed() && truck.speed() < police.speed());
        assertEquals(6, cart.speed(), 0.5);
        assertEquals(8, truck.speed(), 0.5);
        assertEquals(10, police.speed(), 0.5);
        for (VehicleSpec s : v.values()) {
            assertTrue(s.speed() <= 10 && s.reverseSpeed() < s.speed());
            assertEquals(4, s.wheels().size());
            assertEquals(2, s.wheels().stream().filter(VehicleSpec.Wheel::steer).count(), "two front wheels steer");
            assertTrue(s.wheels().stream().filter(VehicleSpec.Wheel::steer).allMatch(w -> w.at().forward() > 0), "the front ones");
            assertEquals(1.0, s.stepHeight(), 1e-9, "one-block ledges");
            assertTrue(s.clickHeight() <= s.height());
            // every seat inside the body, the driver on the left (Korea drives on the right)
            for (VehicleSpec.Vec seat : s.seats()) {
                assertTrue(Math.abs(seat.right()) < s.width() / 2 && Math.abs(seat.forward()) < s.length() / 2, "seat inside");
            }
            assertTrue(s.seats().getFirst().right() < 0, "driver on the left");
        }
        assertEquals(4, police.seats().size());
        assertEquals(4, truck.seats().size(), "two in the cab, two on the bed");
        assertEquals(2, cart.seats().size());
        assertNotNull(police.lights(), "light bar + siren");
        assertEquals(2, police.lights().flash().size());
        // every vehicle honks (space held) with its own sounds from the pack, and an engine sound
        for (Map.Entry<String, VehicleSpec> e : v.entrySet()) {
            VehicleSpec.Horn h = e.getValue().horn();
            assertNotNull(h);
            assertTrue(h.volume() >= 1.4 && h.volume() <= 1.6, "heard about 24 blocks away");
            assertEquals("stsv:vehicle." + e.getKey() + ".horn", h.sound());
            assertEquals("stsv:vehicle." + e.getKey() + ".horn_end", h.release());
            VehicleSpec.Engine en = e.getValue().engine();
            assertNotNull(en, e.getKey());
            assertEquals("stsv:vehicle." + e.getKey() + ".engine", en.sound());
            assertTrue(en.volume() <= 0.5, "quiet: a background hum, heard 16 blocks away at most");
            assertTrue(en.idleVolume() < en.volume() && en.idlePitch() < en.topPitch(), "quieter and lower standing");
            assertEquals(ENGINE_SEGMENT_TICKS, en.segmentTicks(), "the segment length of the sound files");
        }
        assertEquals(0, cart.engine().idleVolume(), "the electric cart is silent standing");
        // the vanilla fallback (vehicles.horn.custom-sound: false): the cart high, the truck low and twice, the car between
        assertEquals(1, cart.horn().times());
        assertEquals(2, truck.horn().times(), "빵빵");
        assertEquals(1, police.horn().times());
        assertTrue(cart.horn().pitch() > police.horn().pitch() && police.horn().pitch() > truck.horn().pitch(), "high, mid, low");
        assertNotNull(truck.cargo());
        assertTrue(truck.cargo().slots() > 54, "bigger than a large chest");
        assertEquals(450, truck.cargo().slots(), "the truck: 450 (10 pages), generation 4 of the ladder");
        assertEquals(10, truck.cargo().pages());
        for (VehicleSpec s : v.values()) assertFalse(s.pulled(), "driven, not pulled");
        assertEquals(4, truck.cargo().stages().size());
        assertTrue(truck.cargo().behind() < truck.length() / 2 && truck.cargo().behind() > -truck.length() / 2);
        // 적재 무게: the police car's trunk and the golf cart's bag rack are small beds (27 slots, payload 100)
        assertEquals(27, police.cargo().slots());
        assertEquals(27, cart.cargo().slots());
        assertTrue(police.cargo().stages().isEmpty() && cart.cargo().stages().isEmpty(), "closed: no load shown");
        assertEquals(100, police.cargo().payload());
        assertEquals(100, cart.cargo().payload());
        assertEquals(1000, truck.cargo().payload());
    }

    /** tools/assetgen/sounds.py ENGINE_PERIOD (0.5 s) in ticks. */
    private static final int ENGINE_SEGMENT_TICKS = 10;
    /** sounds.py ENGINE_FADE: each engine clip is one period plus its fade-out. */
    private static final double ENGINE_FADE = 0.15;
    /** sounds.py HORN_HOLD: the hold clip covers the longest hold the server allows. */
    private static final double HORN_HOLD = VehicleModule.HORN_CLIP_SECONDS;

    /**
     * Every vehicle sound is in the pack: an event in assets/stsv/sounds.json pointing at an .ogg that exists, the hold
     * clip as long as the longest hold, the engine clip one segment plus its fade (the server times segments by it).
     */
    @Test
    void theirSoundsAreInThePack() throws Exception {
        Path assets = CONTENT.resolve("stsv/assets/stsv");
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
        for (Map.Entry<String, VehicleSpec> e : shipped().entrySet()) {
            VehicleSpec s = e.getValue();
            for (String key : List.of(s.horn().sound(), s.horn().release(), s.engine().sound())) {
                assertTrue(key.startsWith("stsv:"), key);
                String event = key.substring("stsv:".length());
                assertTrue(sounds.has(event), "sounds.json has " + event);
                com.google.gson.JsonArray list = sounds.getAsJsonObject(event).getAsJsonArray("sounds");
                assertFalse(list.isEmpty(), event);
                for (com.google.gson.JsonElement el : list) {
                    String name = el.isJsonObject() ? el.getAsJsonObject().get("name").getAsString() : el.getAsString();
                    assertTrue(name.startsWith("stsv:"), name);
                    Path ogg = assets.resolve("sounds/" + name.substring("stsv:".length()) + ".ogg");
                    assertTrue(Files.isRegularFile(ogg), ogg + " exists");
                    assertTrue(Files.size(ogg) < 64 * 1024, ogg + " stays small");
                    double seconds = OggLength.seconds(ogg);
                    if (key.equals(s.horn().sound())) assertTrue(seconds >= HORN_HOLD, ogg + ": " + seconds + " s covers the longest hold");
                    if (key.equals(s.horn().release())) assertTrue(seconds > 0.1 && seconds < 0.5, ogg + ": a short tail");
                    if (key.equals(s.engine().sound())) {
                        assertEquals(s.engine().segmentTicks() / 20.0 + ENGINE_FADE, seconds, 0.02, ogg + ": one segment + its fade");
                    }
                    assertEquals(1, OggLength.channels(ogg), ogg + ": mono (positional)");
                }
            }
        }
    }

    /** 리어카: pulled on foot (no seats), two wheels on a middle axle, a 135-slot bed in three pages, 40 % slower walking. */
    @Test
    void theHandCartIsPulledAndSlow() {
        Map<String, VehicleSpec> all = shipped(ALL);
        VehicleSpec cart = all.get("hand_cart");
        assertTrue(cart.pulled());
        assertTrue(cart.seats().isEmpty(), "nobody rides it");
        assertNull(cart.horn());
        assertNull(cart.engine(), "no engine");
        assertNull(cart.lights());
        assertEquals(2, cart.wheels().size(), "two wheels");
        assertTrue(cart.wheels().stream().noneMatch(VehicleSpec.Wheel::steer), "nothing steers: it turns about its axle");
        assertEquals(0, cart.axle().forward(), 1e-9, "the axle under the middle of the bed");
        assertEquals(cart.wheelRadius(), cart.axle().up(), 1e-9);
        assertEquals(1.0, cart.stepHeight(), 1e-9, "one-block ledges, like the others");

        assertEquals(135, cart.cargo().slots(), "generation 1: three pages (was 270)");
        assertEquals(3, cart.cargo().pages(), "three pages of 45");
        assertEquals(4, cart.cargo().stages().size(), "a load shown by how full it is");
        assertTrue(cart.cargo().slots() < all.get("light_truck").cargo().slots(), "less than the truck (generation 4)");

        VehicleSpec.Pull pull = cart.pull();
        assertEquals(0.4, pull.slowdown(), 1e-9, "40 % slower while pulling (was 30 %)");
        assertEquals(4, pull.release(), 1e-9, "lets go about 4 blocks from the handles");
        assertTrue(pull.hitch() - 0.4 > cart.length() / 2, "the puller stands clear of the bed (in front of it, between the handles)");
        assertTrue(pull.hitch() < 2.5, "and holds the handles, not a rope");
        assertTrue(pull.restTilt() > 5 && pull.restTilt() < 30, "left alone it rests on its handles");
        assertNotNull(pull.roll(), "a quiet rolling sound");
        assertTrue(pull.roll().volume() <= 0.2, "quiet");
        assertTrue(cart.width() < 2 && cart.length() < 3, "fits a 2-wide path");
    }

    @Test
    void aBrokenSectionSaysWhat() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("v.speed", 5);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true));
        assertTrue(e.getMessage().startsWith("vehicle."), e.getMessage());

        // a pulled cart: no driving numbers needed, but no seats either; its bed in whole pages
        YamlConfiguration c = new YamlConfiguration();
        c.set("v.size", List.of(2.0, 1.5, 1.0));
        c.set("v.wheel", "wheel");
        c.set("v.wheel-radius", 0.375);
        c.set("v.pull.hitch", 1.9);
        c.set("v.cargo.slots", 270);
        c.set("v.cargo.at", List.of(0.0, 0.8, 0.0));
        VehicleSpec ok = VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true);
        assertTrue(ok.pulled());
        assertEquals(6, ok.cargo().pages());
        c.set("v.cargo.slots", 100);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true))
                .getMessage().startsWith("vehicle.cargo.slots"), "slots in whole pages of 45");
        c.set("v.cargo.slots", 45 * 19);
        assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true), "at most 810");
        c.set("v.cargo.slots", 0);
        assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true));
        c.set("v.cargo.slots", 810);
        VehicleSpec.Cargo max = VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true).cargo();
        assertEquals(18, max.pages(), "up to 810 (was 540)");
        assertEquals(810, max.slots());
        assertEquals(810, VehicleSpec.Cargo.PAGE * VehicleSpec.Cargo.MAX_PAGES);
        c.set("v.cargo.slots", 27);
        VehicleSpec.Cargo small = VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true).cargo();
        assertEquals(1, small.pages(), "whole rows of 9: one part-full page");
        assertEquals(27, small.slots());
        c.set("v.cargo.slots", 54);
        assertEquals(2, VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true).cargo().pages(), "45 + 9");
        c.set("v.cargo.slots", 50);
        assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true), "rows of 9");
        c.set("v.cargo.slots", 540);
        assertEquals(12, VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true).cargo().pages());
        c.set("v.seats", List.of(Map.of("at", List.of(0.0, 0.5, 0.0))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(c.getConfigurationSection("v"), "t", m -> true))
                .getMessage().startsWith("vehicle.seats"), "nobody rides a pulled cart");
    }

    /** Vehicles come from the admin browser; the economy shop's 탈것 category sells the hand cart only. No gacha, no recipe gives one. */
    @Test
    void onlyAdminsHandThemOut() throws Exception {
        YamlConfiguration items = YamlConfiguration.loadConfiguration(CONTENT.resolve("stsv/items/vehicles.yml").toFile());
        for (String id : ALL) {
            assertNotNull(items.getConfigurationSection(id + ".vehicle"), id + " is a vehicle item (the admin browser's 탈것 tab)");
            for (String key : List.of("price", "shop", "buy", "sell", "recipe", "gacha")) assertFalse(items.contains(id + "." + key), id + "." + key);
        }
        List<Path> roots = List.of(CONTENT.resolve("stsv"), CONTENT.getParent().resolve("economy/content/economy"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    if (f.getFileName().toString().equals("vehicles.yml")) continue;
                    String text0 = Files.readString(f);
                    if (f.getFileName().toString().equals("shop.yml")) {
                        for (String id : ALL) if (!id.equals("hand_cart")) assertFalse(text0.contains(id), "only the hand cart is sold: " + id);
                        continue;
                    }
                    String text = Files.readString(f);
                    for (String id : ALL) assertFalse(text.contains(id), f + " mentions " + id);
                }
            }
        }
    }
}
