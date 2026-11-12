package dev.stsv.core.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real default content (content/stsv) and the TEST fixture pack (src/test/fixtures/stsv: every
 * kind of item once, never shipped) through the converter and pack builder.
 */
class PackPipelineTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final Path FIXTURES = Path.of(System.getProperty("stsv.fixtures"));
    private static final Set<Float> VANILLA_ANGLES = Set.of(-45f, -22.5f, 0f, 22.5f, 45f);

    /** Model files of both packs (shipped and fixture). */
    static java.util.stream.Stream<Path> models() throws Exception {
        return java.util.stream.Stream.concat(java.nio.file.Files.list(CONTENT.resolve("stsv/models")), java.nio.file.Files.list(FIXTURES.resolve("stsv/models")))
                .filter(p -> p.getFileName().toString().endsWith(".bbmodel"))
                .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()));
    }

    private static final Set<String> GLOWING = Set.of("test_machine", "test_greatsword", "test_slash",
            "halo", "crown", "wizard_hat", "butterfly_wings",   // cosmetics: the halo, gems, stars, wing spots
            "mole", "gumiho", "phoenix",                        // pets: the helmet lamp, fox-fire tails, flame tips
            "andon", "stone_lantern", "tea_kettle",             // furniture: lamp paper, the lantern's fire, embers
            "police_car", "light_truck", "golf_cart",           // vehicles: head and tail lights
            "police_lightbar_a", "police_lightbar_b",           // the light bar's lit halves
            "cargo_bicycle", "cargo_van");                      // road vehicles: the bicycle's lamps, the van's head and tail lights
    private static final Set<String> ROTATED = Set.of("test_sword", "test_sword_skin", "test_greatsword", "test_slash", "test_rod", "test_rod_cast",
            "angel_wings", "demon_wings", "butterfly_wings",   // wings swept back 22.5 degrees
            "gumiho",                                           // nine tails fanned out
            "wagasa",                                           // the parasol's struts, tilted up to its canopy
            "police_car", "light_truck",                        // raked windscreens (45 / 22.5 degrees)
            "cargo_bicycle", "delivery_scooter", "atv",         // road vehicles: frame tubes, forks, stays, steering posts
            "cargo_van", "box_truck",                          // a raked windscreen, a roof deflector and a tilted steering wheel
            "ox_cart", "power_tiller", "tractor",              // the ox's traces, the tiller's handlebars, the tractor's steering wheel
            "jige", "jige_load_1", "jige_load_2", "jige_load_3"); // the 지게 prongs, jutting back and up 22.5 degrees

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("models")
    void modelConvertsToAValidVanillaModel(Path bbmodel) throws Exception {
        String name = bbmodel.getFileName().toString().replace(".bbmodel", "");
        BBModelConverter.Result r = BBModelConverter.convert(bbmodel, "stsv:item/" + name);
        assertTrue(r.warnings().isEmpty(), "warnings: " + r.warnings());

        JsonObject model = r.model();
        JsonObject textures = model.getAsJsonObject("textures");
        long pngs = r.textures().keySet().stream().filter(k -> k.endsWith(".png")).count();
        assertEquals(pngs + 1, textures.size(), "every texture + particle referenced");
        for (Map.Entry<String, JsonElement> t : textures.entrySet()) {
            String file = t.getValue().getAsString().replace("stsv:item/" + name + "/", "") + ".png";
            assertTrue(r.textures().containsKey(file), "missing texture file " + file);
        }

        boolean glows = false, rotated = false;
        for (JsonElement je : model.getAsJsonArray("elements")) {
            JsonObject el = je.getAsJsonObject();
            for (String k : List.of("from", "to")) {
                for (JsonElement c : el.getAsJsonArray(k)) {
                    float v = c.getAsFloat();
                    assertTrue(v >= -16 && v <= 32, k + " out of vanilla range: " + v);
                }
            }
            if (el.has("rotation")) {
                assertTrue(VANILLA_ANGLES.contains(el.getAsJsonObject("rotation").get("angle").getAsFloat()));
                rotated = true;
            }
            glows |= el.has("light_emission");
            for (Map.Entry<String, JsonElement> f : el.getAsJsonObject("faces").entrySet()) {
                JsonObject face = f.getValue().getAsJsonObject();
                for (JsonElement uv : face.getAsJsonArray("uv")) {
                    float v = uv.getAsFloat();
                    assertTrue(v >= 0 && v <= 16, "uv out of range: " + v);
                }
                assertTrue(textures.has(face.get("texture").getAsString().substring(1)), "unknown texture ref");
            }
        }
        assertEquals(GLOWING.contains(name) || name.matches(".+_lamp_(head|tail|brake|reverse)"), glows, "only light sources glow");   // + the vehicles' lamp parts (vehicle_lights.py)
        assertEquals(ROTATED.contains(name), rotated, "rotated parts");
        assertTrue(model.has("display"));
    }

    @Test
    void shippedPackBuildsCleanAndHasNoTestSet() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        PackBuilder.Built a = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        PackBuilder.Built b = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        assertEquals(a.sha1(), b.sha1(), "same content must give the same hash (no needless re-downloads)");
        assertTrue(a.warnings().isEmpty(), "warnings: " + a.warnings());
        assertTrue(unzip(a.zip()).keySet().stream().noneMatch(f -> f.contains("/test_") || f.endsWith("/test.json") || f.endsWith("/test.png")),
                "the TEST set is a test fixture, never shipped");
        try (var files = java.nio.file.Files.walk(CONTENT.resolve("stsv"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith("test_") || f.getFileName().toString().equals("test.yml")),
                    "no TEST files in content/stsv");
        }
    }

    @Test
    void packIsCompleteAndDeterministic() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", FIXTURES.resolve("stsv"));
        PackBuilder.Built a = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        PackBuilder.Built b = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        assertEquals(a.sha1(), b.sha1(), "same content must give the same hash (no needless re-downloads)");
        assertTrue(a.warnings().isEmpty(), "warnings: " + a.warnings());

        Map<String, byte[]> files = unzip(a.zip());
        assertTrue(files.containsKey("assets/stsv/models/item/test_machine.json"));
        assertTrue(files.containsKey("assets/stsv/items/model/test_fan_blade.json"), "every 3D model gets an item definition");
        // model textures are stored once by content under textures/item/shared, the model points there
        JsonObject machine = JsonParser.parseString(new String(files.get("assets/stsv/models/item/test_machine.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        java.util.Set<String> refs = new java.util.HashSet<>();
        machine.getAsJsonObject("textures").entrySet().forEach(e -> refs.add(e.getValue().getAsString()));
        for (String ref : refs) {
            assertTrue(ref.startsWith("stsv:item/shared/"), "shared texture: " + ref);
            assertTrue(files.containsKey("assets/stsv/textures/item/" + ref.substring("stsv:item/".length()) + ".png"), "texture file for " + ref);
        }
        assertTrue(files.keySet().stream().anyMatch(f -> f.startsWith("assets/stsv/textures/item/shared/") && f.endsWith(".png.mcmeta")),
                "animated texture metadata");
        assertTrue(files.keySet().stream().noneMatch(f -> f.startsWith("assets/stsv/textures/item/test_machine/")), "no per-model copies");
        assertTrue(files.containsKey("assets/stsv/models/item/test_material.json"));
        assertTrue(files.containsKey("assets/stsv/textures/item/test_material.png"));

        JsonObject mcmeta = JsonParser.parseString(new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(97, mcmeta.getAsJsonObject("pack").get("min_format").getAsInt());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) out.put(e.getName(), in.readAllBytes());
        }
        return out;
    }
}
