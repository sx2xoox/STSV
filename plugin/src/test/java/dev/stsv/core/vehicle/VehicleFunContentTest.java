package dev.stsv.core.vehicle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * 특별 탈것 (content/stsv/items/vehicles_fun.yml): the ten fun vehicles read, each does what it was made for, their models,
 * icons and sounds are in the pack, the keys are told, and all of them are admin-only.
 */
class VehicleFunContentTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final Path PACK = CONTENT.resolve("stsv");
    static final List<String> IDS = List.of("kickboard", "pogo_stick", "skateboard", "broom", "banana_boat", "segway", "toy_duck",
            "sled", "balloon_chair", "rodeo_bull");
    /** A player's walking speed (blocks/s). */
    private static final double WALK = 4.317;

    private static YamlConfiguration items() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/vehicles_fun.yml").toFile());
    }

    private static Map<String, VehicleSpec> fun() {
        YamlConfiguration y = items();
        Map<String, VehicleSpec> out = new LinkedHashMap<>();
        for (String id : IDS) {
            ConfigurationSection s = y.getConfigurationSection(id + ".vehicle");
            assertNotNull(s, id);
            out.put(id, VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel"))));
        }
        return out;
    }

    @Test
    void eachDoesWhatItWasMadeFor() {
        Map<String, VehicleSpec> v = fun();
        assertEquals(IDS.size(), items().getKeys(false).size(), "ten, each on its own");
        for (Map.Entry<String, VehicleSpec> e : v.entrySet()) {
            VehicleSpec s = e.getValue();
            assertNotNull(s.fun(), e.getKey() + " is a 특별 탈것");
            assertFalse(s.pulled(), e.getKey());
            assertTrue(s.speed() <= 10 && !s.extras().overCap(), e.getKey() + ": under the server's top speed");
            for (VehicleSpec.Vec seat : s.seats())
                assertTrue(Math.abs(seat.right()) < s.width() / 2 + 1e-9 && Math.abs(seat.forward()) < s.length() / 2, e.getKey() + ": seat over it");
            assertTrue(s.horn() == null || !s.fun().usesSpace(), e.getKey() + ": space does one thing");
        }
        VehicleSpec kick = v.get("kickboard"), skate = v.get("skateboard");
        assertTrue(kick.speed() >= 1.8 * WALK, "the kickboard: about twice walking");
        assertTrue(kick.extras().lean() > 0 && kick.stepHeight() >= 1, "it leans into turns and steps up a block");
        assertNotNull(kick.fun().roll(), "its little wheels rattle");
        assertTrue(skate.speed() > kick.speed() && skate.turnRate() < kick.turnRate(), "the board: faster, turns more slowly");
        assertNotNull(skate.fun().ollie());
        assertEquals(360, skate.fun().ollie().spin(), 1e-9, "one whole spin");

        FunSpec.Hop hop = v.get("pogo_stick").fun().hop();
        assertNotNull(hop);
        assertEquals(5, hop.max(), 1e-9, "up to five blocks");
        assertTrue(v.get("pogo_stick").fun().lookSteer(), "it hops the way the rider looks");

        FunSpec.Hover broom = v.get("broom").fun().hover();
        assertNotNull(broom);
        assertEquals(3, broom.max(), 1e-9, "at most three blocks over the ground");
        assertFalse(broom.pop());
        assertEquals(FunSpec.Trail.GLITTER, v.get("broom").fun().trail());

        VehicleSpec banana = v.get("banana_boat");
        assertTrue(banana.floats(), "water only");
        assertEquals(3, banana.seats().size(), "three in a row");
        assertTrue(banana.seats().stream().allMatch(s -> s.right() == 0), "in a row");
        assertNotNull(banana.fun().toss(), "riders behind may be thrown off");

        VehicleSpec seg = v.get("segway");
        assertTrue(seg.fun().pivot() && seg.fun().tilt() > 0, "turns on the spot, tips with the speed");
        assertEquals(2, seg.wheels().size());
        assertTrue(seg.wheels().stream().allMatch(w -> w.at().forward() == 0), "side by side");
        assertNotNull(seg.engine(), "a motor whine");

        VehicleSpec duck = v.get("toy_duck");
        assertNotNull(duck.fun().amphibious(), "water and land");
        assertTrue(duck.fun().amphibious().landSpeed() < duck.speed() / 2, "a slow waddle on land");
        assertTrue(duck.fun().waddle() > 0);
        assertNotNull(duck.fun().toot(), "꽥");

        VehicleSpec sled = v.get("sled");
        assertEquals(2, sled.seats().size());
        assertNotNull(sled.fun().surface());
        assertTrue(sled.fun().surface().other() < 0.5 && sled.fun().surface().snow() == 1 && sled.fun().surface().ice() == 1,
                "fast on snow and ice, slow elsewhere");
        assertTrue(sled.fun().surface().downhill() > 0, "faster downhill");
        assertTrue(sled.extras().allTerrain(), "snow is its road, not rough ground");

        VehicleSpec chair = v.get("balloon_chair");
        assertEquals(8, chair.fun().hover().max(), 1e-9, "at most eight blocks over the ground");
        assertTrue(chair.fun().hover().pop() && chair.fun().lookSteer());
        assertTrue(chair.fun().hover().climb() < broom.climb(), "rises slowly");
        assertNotNull(chair.fun().part(), "its balloons");

        VehicleSpec bull = v.get("rodeo_bull");
        assertNotNull(bull.fun().buck());
        assertTrue(bull.speed() < 1, "it barely moves");
        assertNotNull(bull.fun().part(), "the bull on its stand");
    }

    /** Every model each shows, its icon, and the generator that draws them. */
    @Test
    void theirModelsAndIconsAreInThePack() throws Exception {
        YamlConfiguration y = items();
        String gen = Files.readString(CONTENT.getParent().resolve("tools/assetgen/models/vehicles_fun.py"));
        for (Map.Entry<String, VehicleSpec> e : fun().entrySet()) {
            String id = e.getKey();
            assertTrue(Files.exists(PACK.resolve("models/" + y.getString(id + ".model") + ".bbmodel")), id + " body");
            assertTrue(Files.exists(PACK.resolve("textures/" + y.getString(id + ".icon") + ".png")), id + " icon");
            assertTrue(gen.contains("\"" + id + "\": "), id + " drawn by vehicles_fun.py");
            for (var key : e.getValue().partModels()) {
                String name = key.getKey().substring("model/".length());
                assertTrue(Files.exists(PACK.resolve("models/" + name + ".bbmodel")), id + ": " + name);
            }
            String lore = String.join("\n", y.getStringList(id + ".lore"));
            assertTrue(lore.contains("특별 탈것") && lore.contains("우클릭"), id + ": what it is and how to use it");
            assertTrue(y.getString(id + ".name").matches(".*[가-힣].*"), id + ": a Korean name");
        }
    }

    /** The sounds they borrow (the bicycle's bell, the golf cart's beep and motor) are in the pack. */
    @Test
    void theirSoundsAreInThePack() throws Exception {
        com.google.gson.JsonObject sounds = com.google.gson.JsonParser.parseString(
                Files.readString(PACK.resolve("assets/stsv/sounds.json"))).getAsJsonObject();
        for (VehicleSpec s : fun().values()) {
            for (String key : new String[]{s.horn() == null ? null : s.horn().sound(), s.horn() == null ? null : s.horn().release(),
                    s.engine() == null ? null : s.engine().sound()}) {
                if (key == null) continue;
                assertTrue(key.startsWith("stsv:") && sounds.has(key.substring(5)), "sounds.json has " + key);
            }
        }
    }

    /** What space and the keys do is told when someone gets on (messages.yml vehicle.fun.*). */
    @Test
    void theKeysAreTold() {
        var messages = YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8));
        for (VehicleSpec s : fun().values()) {
            String key = FunView.keys(s);
            if (key != null) assertTrue(messages.isString(key), key);
        }
        assertNull(FunView.keys(VehiclePhysicsTest.car()), "every other vehicle: the usual hint");
        for (String k : List.of("tossed", "popped", "refilled", "rodeo-meter", "rodeo-time", "rodeo-thrown", "rodeo-off", "rodeo-record", "banana-seat"))
            assertTrue(messages.isString("vehicle.fun." + k), k);
        assertTrue(FunView.meter(0).contains("◆") && FunView.meter(-1).startsWith("<white>◆") && FunView.meter(1).endsWith("◆"));
    }

    /** Admin-only: the admin browser's 탈것 tab, /관리자 지급; no shop, gacha or recipe has them. */
    @Test
    void onlyAdminsHandThemOut() throws Exception {
        YamlConfiguration y = items();
        for (String id : IDS) for (String key : List.of("price", "shop", "buy", "sell", "recipe", "gacha")) assertFalse(y.contains(id + "." + key), id + "." + key);
        List<Path> roots = List.of(PACK, CONTENT.getParent().resolve("economy/content/economy"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    String name = f.getFileName().toString();
                    if (name.equals("vehicles_fun.yml") || name.equals("vehicle_feel.yml")) continue;
                    String text = Files.readString(f);
                    for (String id : IDS) assertFalse(text.matches("(?s).*\\b" + id + "\\b.*"), f + " mentions " + id);
                }
            }
        }
    }
}
