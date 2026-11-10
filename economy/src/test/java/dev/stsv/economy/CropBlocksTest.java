package dev.stsv.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.crop.CropBlocks;
import dev.stsv.core.crop.CropSpec;
import dev.stsv.core.pack.PackBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The economy's 17 crops as tripwire blocks: the committed state file (crop-blocks.yml) and what the real pack builder
 * makes of it. Writes the pack to build/crop-blocks-pack.zip for tools/assetgen/crop_blocks.py --preview.
 */
class CropBlocksTest {

    private static final Path PACK = ContentTest.PACK;
    private static final Path CORE = Path.of("../content/stsv").toAbsolutePath().normalize();

    private static List<CropSpec> specs() {
        List<String> warnings = new ArrayList<>();
        List<CropSpec> s = CropSpec.loadAll(List.of(new ContentPack("economy", "economy", PACK)), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return s;
    }

    @Test
    void everyStageHasItsCommittedState() throws Exception {
        List<CropSpec> specs = specs();
        assertEquals(17, specs.size());
        Map<String, Integer> file = CropBlocks.read(PACK.resolve(CropBlocks.FILE));
        CropBlocks.Allocation a = CropBlocks.load(List.of(new ContentPack("economy", "economy", PACK)), specs);
        assertTrue(a.added().isEmpty(), "stages missing from crop-blocks.yml (run tools/assetgen/crop_blocks.py): " + a.added());
        assertTrue(a.problems().isEmpty(), a.problems().toString());
        int stages = 0;
        for (CropSpec s : specs) {
            for (int st = 0; st < s.stages(); st++) {
                stages++;
                Integer c = a.code(s.id(), st);
                assertNotNull(c, s.id() + "/" + st);
                assertEquals(file.get(CropBlocks.key(s.id(), st)), c);
                assertFalse(CropBlocks.reserved(c), "real string keeps its states");
                // the thin outline only on the first half of the stages (small plants), while thin states last
                assertEquals(CropBlocks.early(st, s.stages()), CropBlocks.thin(c), s.id() + "/" + st);
            }
        }
        assertEquals(77, stages);
        assertEquals(77, new HashSet<>(a.byKey().values()).size(), "one state per stage");
        assertTrue(CropBlocks.STATES - 2 - stages >= 40, "room for more crops: " + (CropBlocks.STATES - 2 - stages) + " states left");
    }

    /** Released states never move: a planted crop must look the same after an update. Append-only. */
    @Test
    void releasedStatesNeverMove() throws Exception {
        Map<String, Integer> released = new LinkedHashMap<>();
        for (String line : new String(getClass().getResourceAsStream("/crop-blocks-released.txt").readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] kv = line.split("=");
            released.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
        }
        assertTrue(released.size() >= 77);
        Map<String, Integer> file = CropBlocks.read(PACK.resolve(CropBlocks.FILE));
        List<String> fileKeys = new ArrayList<>(file.keySet());
        int i = 0;
        for (Map.Entry<String, Integer> e : released.entrySet()) {
            assertEquals(e.getValue(), file.get(e.getKey()), e.getKey() + " changed its state: crop-blocks.yml is append-only");
            assertEquals(e.getKey(), fileKeys.get(i++), "released entries stay first, in their order");
        }
    }

    @Test
    void packDrawsEveryStageAsBlocks() throws Exception {
        List<ContentPack> packs = List.of(new ContentPack("economy", "economy", PACK), new ContentPack("stsv", "stsv", CORE));
        PackBuilder.Built built = new PackBuilder().build(packs, List.of(), 97, "test");
        assertTrue(built.warnings().isEmpty(), "warnings: " + built.warnings());
        Path out = Path.of("build/crop-blocks-pack.zip");
        Files.createDirectories(out.getParent());
        Files.write(out, built.zip());
        Map<String, byte[]> files = unzip(built.zip());

        JsonObject variants = json(files, PackBuilder.TRIPWIRE).getAsJsonObject("variants");
        assertEquals(CropBlocks.STATES, variants.size(), "every tripwire state, all seven properties");
        CropBlocks.Allocation a = CropBlocks.load(packs, specs());
        Set<String> models = new HashSet<>();
        for (int code = 0; code < CropBlocks.STATES; code++) {
            JsonElement v = variants.get(CropBlocks.properties(code));
            assertNotNull(v, CropBlocks.properties(code));
            String key = a.keyOf(code);
            if (key == null) {
                // real string (and the states no crop has yet) looks like vanilla string
                assertTrue(v.isJsonObject(), "vanilla: " + code);
                assertEquals(CropBlocks.vanilla(code).model(), v.getAsJsonObject().get("model").getAsString());
                continue;
            }
            JsonArray turns = v.getAsJsonArray();
            assertEquals(4, turns.size(), "four turns, picked at random");
            String name = key.substring(key.indexOf(':') + 1).replace('/', '_');
            for (int k = 0; k < 4; k++) {
                JsonObject t = turns.get(k).getAsJsonObject();
                assertEquals("economy:block/crop/" + name + "_r" + (90 * k), t.get("model").getAsString());
                assertFalse(t.has("y"), "turned in the model, so each face keeps the shading it was darkened for");
                assertFalse(t.has("weight"), "equal chances");
                models.add(t.get("model").getAsString());
            }
        }
        assertEquals(77 * 4, models.size());
        for (String m : models) {
            String path = "assets/economy/models/" + m.substring("economy:".length()) + ".json";
            JsonObject model = json(files, path);
            assertFalse(model.get("ambientocclusion").getAsBoolean(), m);
            for (String tex : model.getAsJsonObject("textures").keySet()) {
                String ref = model.getAsJsonObject("textures").get(tex).getAsString();
                assertTrue(ref.startsWith("economy:block/crop/"), "block atlas: " + ref);
                assertTrue(files.containsKey("assets/economy/textures/" + ref.substring("economy:".length()) + ".png"), ref);
            }
            for (JsonElement el : model.getAsJsonArray("elements")) {
                JsonObject e = el.getAsJsonObject();
                assertFalse(e.has("shade"), "26.3 does not read it; shading is in the textures");
                for (String ft : List.of("from", "to")) {
                    for (JsonElement c : e.getAsJsonArray(ft)) assertTrue(c.getAsDouble() >= -16 && c.getAsDouble() <= 32, m + " " + ft);
                }
            }
        }
        // corn is two blocks tall as a block too
        JsonObject corn = json(files, "assets/economy/models/block/crop/corn_4_r0.json");
        double top = 0;
        for (JsonElement el : corn.getAsJsonArray("elements")) top = Math.max(top, el.getAsJsonObject().getAsJsonArray("to").get(1).getAsDouble());
        assertTrue(top > 16, "corn reaches into the block above");

        // crops.render: entities -> string stays vanilla string
        Map<String, byte[]> plain = unzip(new PackBuilder().cropBlocks(false).build(packs, List.of(), 97, "test").zip());
        assertFalse(plain.containsKey(PackBuilder.TRIPWIRE));
        assertFalse(plain.keySet().stream().anyMatch(k -> k.contains("/block/crop/")));
    }

    private static JsonObject json(Map<String, byte[]> files, String path) {
        byte[] b = files.get(path);
        assertNotNull(b, path);
        return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) files.put(e.getName(), in.readAllBytes());
        }
        return files;
    }
}
