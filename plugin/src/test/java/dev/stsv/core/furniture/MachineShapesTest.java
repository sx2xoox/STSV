package dev.stsv.core.furniture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.FurnitureSpec;
import dev.stsv.core.pack.PackBuilder;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Matrix4d;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The economy machines (next to the core in this repository), every tier, through the real resource pack
 * builder and the same model ids the machines show (economy:model/&lt;machine&gt;_&lt;tier&gt;): click boxes cover
 * the whole model, stay in the cells and keep to the limit; low machines are not clicked in the air above them.
 *
 * With the environment variable STSV_HITBOX_DUMP set to a folder, it also writes a preview JSON per machine and
 * tier (model, click boxes, barrier cells) for a picture of them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MachineShapesTest {

    private static final Path ECONOMY = Path.of(System.getProperty("stsv.content")).getParent().resolve("economy/content/economy");
    private static final double FILL = 0.30; // furniture.barrier-fill in config.yml

    private PackBuilder.Built built;
    private final Map<String, FurnitureSpec> specs = new LinkedHashMap<>();
    private final Map<String, FurnitureShape.Shape> shapes = new LinkedHashMap<>();
    private final Map<String, FurnitureShape.Voxels> voxels = new HashMap<>();
    private final Map<String, Double> millis = new HashMap<>();

    @BeforeAll
    void build() throws Exception {
        assumeTrue(Files.isDirectory(ECONOMY.resolve("models")), "economy pack not next to the core");
        built = new PackBuilder().build(List.of(new ContentPack("economy", "economy", ECONOMY)), List.of(), 97, "test");
        YamlConfiguration jobs = YamlConfiguration.loadConfiguration(ECONOMY.resolve("items/jobs.yml").toFile());
        for (String id : jobs.getKeys(false)) {
            ConfigurationSection f = jobs.getConfigurationSection(id + ".furniture");
            // machines: the furniture with tier looks (model <id>_0); the 통발 and 가두리 have one look
            if (f == null || !jobs.getString(id + ".model", "").endsWith("_0")) continue;
            specs.put(id, spec(f));
        }
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            for (int tier = 0; tier <= 3; tier++) {
                FurnitureSpec spec = e.getValue();
                PackBuilder.Model main = built.itemModel("economy:model/" + e.getKey() + "_" + tier);
                assertNotNull(main, e.getKey() + "_" + tier);
                List<FurnitureShape.PartModel> parts = new ArrayList<>();
                for (FurnitureSpec.Part p : spec.parts()) {
                    String base = p.model().getKey().replaceFirst("_0$", "_" + tier);
                    PackBuilder.Model pm = built.itemModel("economy:" + base);
                    assertNotNull(pm, base);
                    parts.add(new FurnitureShape.PartModel(pm.json(), p.position(), p.spinAxis()));
                }
                long t0 = System.nanoTime();
                FurnitureShape.Voxels v = FurnitureShape.voxelize(spec.cells(), main.json(), spec.translation(), spec.scale(), parts, 0);
                String name = e.getKey() + "_" + tier;
                voxels.put(name, v);
                shapes.put(name, FurnitureShape.shape(v, spec.cells(), FILL, spec.solid()));
                millis.put(name, (System.nanoTime() - t0) / 1e6);
            }
        }
        String dump = System.getenv("STSV_HITBOX_DUMP");
        if (dump != null) dump(Path.of(dump));
    }

    /** The parts of ContentModule.parseFurniture these machines use. */
    private static FurnitureSpec spec(ConfigurationSection f) {
        List<int[]> cells = new ArrayList<>();
        for (Object o : f.getList("cells")) {
            List<?> l = (List<?>) o;
            cells.add(new int[]{((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue()});
        }
        List<FurnitureSpec.Part> parts = new ArrayList<>();
        for (Object o : f.getList("parts", List.of())) {
            ConfigurationSection p = new YamlConfiguration().createSection("p", (Map<?, ?>) o);
            List<Double> pos = p.getDoubleList("position");
            parts.add(new FurnitureSpec.Part(new NamespacedKey("economy", "model/" + p.getString("model")),
                    new Vector3f(pos.get(0).floatValue(), pos.get(1).floatValue(), pos.get(2).floatValue()),
                    p.getString("spin.axis"), (float) p.getDouble("spin.rpm")));
        }
        List<Double> t = f.getDoubleList("translation");
        Vector3f translation = t.size() == 3 ? new Vector3f(t.get(0).floatValue(), t.get(1).floatValue(), t.get(2).floatValue()) : new Vector3f();
        return new FurnitureSpec(cells, f.getBoolean("solid", true), FurnitureSpec.Rotation.CARDINAL, f.getString("behavior"),
                translation, new Vector3f(1), -1, List.of(), parts);
    }

    @Test
    void everyMachineAndTierIsFitted() {
        assertEquals(7, specs.size(), "gem bench, mill, fish bench, forge, kitchen, still, press");
        assertEquals(28, shapes.size());
        for (Map.Entry<String, FurnitureShape.Shape> e : shapes.entrySet()) {
            FurnitureShape.Shape s = e.getValue();
            String machine = e.getKey().replaceFirst("_\\d$", "");
            List<int[]> cells = specs.get(machine).cells();
            assertTrue(s.fitted(), e.getKey());
            assertTrue(s.boxes().size() <= FurnitureShape.MAX_BOXES, e.getKey() + ": " + s.boxes().size() + " boxes");
            FurnitureShapeTest.assertCovers(s, voxels.get(e.getKey()), cells);
            for (int[] b : s.barriers()) assertTrue(FurnitureShapeTest.inCells(cells, b[0], b[1], b[2]));
        }
    }

    @Test
    void boxesHugTheModel() {
        for (Map.Entry<String, FurnitureShape.Shape> e : shapes.entrySet()) {
            FurnitureShape.Shape s = e.getValue();
            FurnitureShape.Voxels v = voxels.get(e.getKey());
            double top = -1e9;
            for (int i = 0; i < v.nx; i++) for (int j = 0; j < v.ny; j++) for (int k = 0; k < v.nz; k++) {
                if (v.filled(i, j, k)) top = Math.max(top, v.hi[v.idx(i, j, k)]);
            }
            double boxTop = s.boxes().stream().mapToDouble(FurnitureShape.Box::y1).max().orElseThrow();
            double cellTop = 16.0 * (1 + specs.get(e.getKey().replaceFirst("_\\d$", "")).cells().stream().mapToInt(c -> c[1]).max().orElseThrow());
            // the top follows the model (or a barrier cell under it), never the empty rest of the cells
            assertTrue(boxTop <= Math.max(top, highestBarrierTop(s)) + 1e-6, e.getKey() + ": box top " + boxTop + " above the model " + top);
            assertTrue(boxTop <= cellTop);
            // what the boxes take in is mostly model (or a barrier cell, which must be clickable whole)
            double volume = 0;
            for (FurnitureShape.Box b : s.boxes()) volume += b.size() * b.size() * (b.y1() - b.y0());
            double solid = 0;
            for (int i = 0; i < v.nx; i++) for (int j = 0; j < v.ny; j++) for (int k = 0; k < v.nz; k++) {
                int cx = (int) Math.floor(v.edge(0, i) / 16), cy = (int) Math.floor(v.edge(1, j) / 16), cz = (int) Math.floor(v.edge(2, k) / 16);
                if (v.filled(i, j, k) || s.barrier(new int[]{cx, cy, cz})) solid += 64;
            }
            assertTrue(solid / volume > 0.7, e.getKey() + ": boxes are " + Math.round(100 * solid / volume) + "% model");
        }
    }

    private static double highestBarrierTop(FurnitureShape.Shape s) {
        return s.barriers().stream().mapToDouble(c -> 16.0 * (c[1] + 1)).max().orElse(-1e9);
    }

    @Test
    void lowFishBenchIsLowerThanItsTwoBlocks() {
        FurnitureShape.Shape s = shapes.get("fish_bench_0");
        double top = s.boxes().stream().mapToDouble(FurnitureShape.Box::y1).max().orElseThrow();
        assertTrue(top < 32, "tier-0 fish bench boxes end at " + top + " px");
        assertEquals(18.5, top, 1e-6, "the model's own top");
        // the counter is solid, the space above it is not
        assertEquals(List.of("0,0,0", "1,0,0"), FurnitureShapeTest.keys(s.barriers()));
    }

    @Test
    void tallMachinesKeepTheirTopsClickable() {
        // the forge and still are drawn from -16 and lifted a block: their boxes reach into the third row
        for (String m : List.of("forge", "still")) {
            for (int tier = 0; tier <= 3; tier++) {
                double top = shapes.get(m + "_" + tier).boxes().stream().mapToDouble(FurnitureShape.Box::y1).max().orElseThrow();
                assertTrue(top > 32, m + "_" + tier + ": " + top);
            }
        }
    }

    @Test
    void spinningPartsAreInsideTheBoxes() {
        // the gem bench wheel turns around z at (8, 24, 8): its rim (radius 6) at the very top must be clickable
        FurnitureShape.Shape s = shapes.get("gem_bench_0");
        double x = 8, y = 24 + 5.5, z = 8;
        boolean hit = false;
        for (FurnitureShape.Box b : s.boxes()) hit |= x > b.x0() && x < b.x1() && z > b.z0() && z < b.z1() && y >= b.y0() && y <= b.y1();
        assertTrue(hit, "wheel top");
    }

    @Test
    void upgradesChangeTheShape() {
        // a tier changes the model, so the stored hash changes and placed machines are redone
        assertFalse(shapes.get("fish_bench_0").hash().equals(shapes.get("fish_bench_1").hash()));
    }

    // ------------------------------------------------------------------------------------------
    // Preview dump (STSV_HITBOX_DUMP)
    // ------------------------------------------------------------------------------------------

    private void dump(Path dir) throws Exception {
        Files.createDirectories(dir);
        Map<String, byte[]> zip = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(built.zip()))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) zip.put(e.getName(), in.readAllBytes());
        }
        JsonObject summary = new JsonObject();
        for (Map.Entry<String, FurnitureShape.Shape> e : shapes.entrySet()) {
            String name = e.getKey(), machine = name.replaceFirst("_\\d$", "");
            int tier = Integer.parseInt(name.substring(name.length() - 1));
            FurnitureSpec spec = specs.get(machine);
            FurnitureShape.Shape s = e.getValue();
            JsonObject out = new JsonObject();
            JsonObject textures = new JsonObject();
            JsonArray elements = new JsonArray();
            PackBuilder.Model main = built.itemModel("economy:model/" + name);
            addElements(elements, textures, zip, main.json(), FurnitureShape.mainPlacement(main.json(), spec.translation(), spec.scale(), 0));
            for (FurnitureSpec.Part p : spec.parts()) {
                PackBuilder.Model pm = built.itemModel("economy:" + p.model().getKey().replaceFirst("_0$", "_" + tier));
                addElements(elements, textures, zip, pm.json(), FurnitureShape.partPlacement(pm.json(), p.position(), p.spinAxis(), 0, 0));
            }
            out.add("textures", textures);
            out.add("elements", elements);
            JsonArray cells = new JsonArray(), barriers = new JsonArray(), boxes = new JsonArray(), fill = new JsonArray();
            for (int[] c : spec.cells()) cells.add(ints(c));
            for (int[] c : s.barriers()) barriers.add(ints(c));
            for (double f : s.fill()) fill.add(Math.round(f * 1000) / 1000.0);
            for (FurnitureShape.Box b : s.boxes()) {
                JsonArray a = new JsonArray();
                for (double d : new double[]{b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1()}) a.add(d);
                boxes.add(a);
            }
            out.add("cells", cells);
            out.add("barriers", barriers);
            out.add("boxes", boxes);
            out.add("fill", fill);
            out.addProperty("name", name);
            Files.writeString(dir.resolve(name + ".json"), new GsonBuilder().create().toJson(out), StandardCharsets.UTF_8);
            JsonObject sum = new JsonObject();
            sum.add("cells", cells);
            sum.add("barriers", barriers);
            sum.add("boxes", boxes);
            sum.add("fill", fill);
            sum.addProperty("ms", millis.get(name));
            summary.add(name, sum);
        }
        Files.writeString(dir.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary), StandardCharsets.UTF_8);
    }

    private static JsonArray ints(int[] c) {
        JsonArray a = new JsonArray();
        for (int i : c) a.add(i);
        return a;
    }

    /** Elements in the preview renderer's form (tools/assetgen/render), placed by m (as "matrix"). */
    private static void addElements(JsonArray out, JsonObject textures, Map<String, byte[]> zip, JsonObject model, Matrix4d m) {
        JsonObject refs = model.getAsJsonObject("textures");
        double[] mat = new double[16];
        m.get(mat);
        JsonArray matrix = new JsonArray();
        for (double d : mat) matrix.add(d);
        for (JsonElement je : model.getAsJsonArray("elements")) {
            JsonObject el = je.getAsJsonObject();
            JsonObject o = new JsonObject();
            o.add("from", el.get("from"));
            o.add("to", el.get("to"));
            if (el.has("rotation")) {
                JsonObject r = el.getAsJsonObject("rotation");
                JsonArray rot = new JsonArray();
                rot.add(r.get("axis"));
                rot.add(r.get("angle"));
                rot.add(r.has("origin") ? r.get("origin") : new JsonArray());
                o.add("rotation", rot);
            }
            o.add("matrix", matrix);
            if (el.has("light_emission")) o.addProperty("glow", true);
            if (el.has("shade")) o.add("shade", el.get("shade"));
            JsonObject faces = new JsonObject();
            for (Map.Entry<String, JsonElement> f : el.getAsJsonObject("faces").entrySet()) {
                JsonObject face = f.getValue().getAsJsonObject();
                String ref = refs.get(face.get("texture").getAsString().substring(1)).getAsString();
                String path = "assets/" + ref.substring(0, ref.indexOf(':')) + "/textures/" + ref.substring(ref.indexOf(':') + 1) + ".png";
                if (!textures.has(ref) && zip.containsKey(path)) {
                    textures.addProperty(ref, "data:image/png;base64," + Base64.getEncoder().encodeToString(zip.get(path)));
                }
                JsonObject of = new JsonObject();
                of.add("uv", face.get("uv"));
                of.addProperty("texture", ref);
                faces.add(f.getKey(), of);
            }
            o.add("faces", faces);
            out.add(o);
        }
    }
}
