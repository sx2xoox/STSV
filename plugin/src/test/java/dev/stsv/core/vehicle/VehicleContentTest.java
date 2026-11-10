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

/** The shipped vehicles (content/stsv/items/vehicles.yml): they read, drive at sensible speeds, and are admin-only. */
class VehicleContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final List<String> IDS = List.of("police_car", "light_truck", "golf_cart");

    private static Map<String, VehicleSpec> shipped() {
        Path pack = CONTENT.resolve("stsv");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pack.resolve("items/vehicles.yml").toFile());
        java.util.Map<String, VehicleSpec> out = new java.util.LinkedHashMap<>();
        for (String id : IDS) {
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
        assertEquals(5, cart.speed(), 0.5);
        assertEquals(7, truck.speed(), 0.5);
        assertEquals(9, police.speed(), 0.5);
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
        // every vehicle honks (space), each its own way: the cart high and short, the truck low and twice, the car between
        for (VehicleSpec s : v.values()) {
            assertNotNull(s.horn());
            assertTrue(s.horn().volume() >= 1.4, "heard about 24 blocks away");
        }
        assertEquals(1, cart.horn().times());
        assertEquals(2, truck.horn().times(), "빵빵");
        assertEquals(1, police.horn().times());
        assertTrue(cart.horn().pitch() > police.horn().pitch() && police.horn().pitch() > truck.horn().pitch(), "high, mid, low");
        assertNotNull(truck.cargo());
        assertTrue(truck.cargo().slots() > 54, "bigger than a large chest");
        assertEquals(4, truck.cargo().stages().size());
        assertTrue(truck.cargo().behind() < truck.length() / 2 && truck.cargo().behind() > -truck.length() / 2);
        assertNull(police.cargo());
    }

    @Test
    void aBrokenSectionSaysWhat() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("v.speed", 5);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true));
        assertTrue(e.getMessage().startsWith("vehicle."), e.getMessage());
    }

    /** Vehicles are admin items for now: no shop, no gacha, no recipe gives one. */
    @Test
    void onlyAdminsHandThemOut() throws Exception {
        List<Path> roots = List.of(CONTENT.resolve("stsv"), CONTENT.getParent().resolve("economy/content/economy"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    if (f.getFileName().toString().equals("vehicles.yml")) continue;
                    String text = Files.readString(f);
                    for (String id : IDS) assertFalse(text.contains(id), f + " mentions " + id);
                }
            }
        }
    }
}
