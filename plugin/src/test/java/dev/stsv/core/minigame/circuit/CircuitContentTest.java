package dev.stsv.core.minigame.circuit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 서킷's content: five karts that each read, drive (a vehicle item with a model, wheels, a drift), have every colour
 * they offer drawn (the tuning shop's paint models) and allowed (vehicle_tuning.yml), each with a clear strength and a
 * clear weakness, one of them the toy; the race's pictures in the fonts the code names.
 */
class CircuitContentTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    @Test
    void fiveDistinctKartsThatDrive() {
        List<KartSpec> karts = KartSpec.parseAll(YamlConfiguration.loadConfiguration(PACK.resolve("circuit/karts.yml").toFile()));
        assertEquals(5, karts.size());
        YamlConfiguration items = YamlConfiguration.loadConfiguration(PACK.resolve("items/karts.yml").toFile());
        YamlConfiguration tuning = YamlConfiguration.loadConfiguration(PACK.resolve("vehicle_tuning.yml").toFile());
        Set<String> names = new HashSet<>(), models = new HashSet<>();
        int toys = 0;
        for (KartSpec k : karts) {
            assertTrue(names.add(k.name()), "a name of its own: " + k.name());
            String id = k.item().substring(k.item().indexOf(':') + 1);
            ConfigurationSection item = items.getConfigurationSection(id);
            assertNotNull(item, k.item() + " in items/karts.yml");
            VehicleSpec spec = VehicleSpec.parse(item.getConfigurationSection("vehicle"), "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
            String model = item.getString("model");
            assertTrue(models.add(model), "a model of its own");
            assertTrue(Files.exists(PACK.resolve("models/" + model + ".bbmodel")), model);
            assertTrue(Files.exists(PACK.resolve("textures/" + item.getString("icon") + ".png")), "icon of " + id);
            assertNotNull(spec.extras().drift(), id + " drifts (Ctrl)");
            assertNull(spec.horn(), id + ": space is the boost, not a horn");
            assertTrue(spec.extras().overCap() && spec.speed() >= 15 && spec.speed() <= 20, id + " is quick");
            assertEquals(1, spec.seats().size());
            // every colour drawn (a paint model) and allowed for this kart
            List<String> allowed = tuning.getStringList("vehicles." + id + ".paints");
            for (String c : k.colours()) {
                if (c.equals(k.own())) continue;
                assertTrue(Files.exists(PACK.resolve("models/" + model + "_p_" + c + ".bbmodel")), model + " in " + c);
                assertTrue(allowed.contains(c), id + ": vehicle_tuning.yml allows " + c);
            }
            assertTrue(k.colours().size() >= 6, "a few colours to pick from");
            // a clear strength and a clear weakness
            assertTrue(k.bars().containsValue(5), k.id() + " is the best at something");
            assertTrue(k.bars().values().stream().anyMatch(v -> v <= 2), k.id() + " pays for it somewhere");
            if (k.role().equals("장난감")) toys++;
        }
        assertEquals(1, toys, "one goofy kart");
        // the karts differ where it counts: no two share their top speed and acceleration
        Set<String> feel = new HashSet<>();
        for (KartSpec k : karts) {
            String id = k.item().substring(k.item().indexOf(':') + 1);
            ConfigurationSection v = items.getConfigurationSection(id + ".vehicle");
            assertTrue(feel.add(v.getDouble("speed") + "/" + v.getDouble("acceleration") + "/" + v.getDouble("turn-rate")), id + " feels different");
        }
    }

    @Test
    void theRacePicturesAreInTheFonts() throws Exception {
        Set<Character> title = chars(PACK.resolve("assets/stsv/font/circuit_title.json"));
        for (char c : new char[]{CircuitHud.THREE, CircuitHud.TWO, CircuitHud.ONE, CircuitHud.GO, CircuitHud.FINISH, CircuitHud.WRONG,
                CircuitHud.RECORD, CircuitHud.LAP}) assertTrue(title.contains(c), "title picture " + (int) c);
        for (int k = 1; k <= 12; k++) assertTrue(title.contains(CircuitHud.place(k)), k + "위");
        Set<Character> icons = chars(PACK.resolve("assets/stsv/font/circuit.json"));
        for (char c : new char[]{CircuitHud.FLAG, CircuitHud.CUP, CircuitHud.CLOCK, CircuitHud.CELL, CircuitHud.CELL_EMPTY, CircuitHud.SEG,
                CircuitHud.SEG_EMPTY, CircuitHud.KART, CircuitHud.BOLT}) assertTrue(icons.contains(c), "icon " + (int) c);
    }

    private static Set<Character> chars(Path font) throws Exception {
        JsonObject o = JsonParser.parseString(Files.readString(font)).getAsJsonObject();
        Set<Character> out = new HashSet<>();
        for (JsonElement p : o.getAsJsonArray("providers")) {
            JsonObject pr = p.getAsJsonObject();
            String file = pr.get("file").getAsString();
            Path png = PACK.resolve("assets/" + file.substring(0, file.indexOf(':')) + "/textures/" + file.substring(file.indexOf(':') + 1));
            assertTrue(Files.exists(png), "texture " + png);
            assertTrue(pr.get("ascent").getAsInt() <= pr.get("height").getAsInt(), "ascent within the height: " + file);
            JsonArray cs = pr.getAsJsonArray("chars");
            for (JsonElement c : cs) for (char ch : c.getAsString().toCharArray()) out.add(ch);
        }
        return out;
    }
}
