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

/** Runs the real default content (content/stsv) through the converter and pack builder. */
class PackPipelineTest {

    private static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    private static final Set<Float> VANILLA_ANGLES = Set.of(-45f, -22.5f, 0f, 22.5f, 45f);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"smelter", "potted_plant", "chair", "crystal_shrine"})
    void modelConvertsToAValidVanillaModel(String name) throws Exception {
        BBModelConverter.Result r = BBModelConverter.convert(CONTENT.resolve("stsv/models/" + name + ".bbmodel"), "stsv:item/" + name);
        assertTrue(r.warnings().isEmpty(), "warnings: " + r.warnings());

        JsonObject model = r.model();
        JsonObject textures = model.getAsJsonObject("textures");
        assertEquals(r.textures().size() + 1, textures.size(), "every texture + particle referenced");
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
        assertEquals(Set.of("smelter", "crystal_shrine").contains(name), glows, "only light sources glow");
        assertEquals(!name.equals("chair"), rotated, "rotated parts");
        assertTrue(model.has("display"));
    }

    @Test
    void packIsCompleteAndDeterministic() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", CONTENT.resolve("stsv"));
        PackBuilder.Built a = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        PackBuilder.Built b = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        assertEquals(a.sha1(), b.sha1(), "same content must give the same hash (no needless re-downloads)");
        assertTrue(a.warnings().isEmpty(), "warnings: " + a.warnings());

        Map<String, byte[]> files = unzip(a.zip());
        assertTrue(files.containsKey("assets/stsv/models/item/smelter.json"));
        assertTrue(files.containsKey("assets/stsv/textures/item/smelter/brick.png"));
        assertTrue(files.containsKey("assets/stsv/models/item/gold_chunk.json"));
        assertTrue(files.containsKey("assets/stsv/textures/item/gold_chunk.png"));

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
