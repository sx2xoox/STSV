package dev.stsv.core.furniture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.content.ContentModule;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.FurnitureSpec;
import dev.stsv.core.content.ModelRef;
import dev.stsv.core.pack.PackBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retro Korean furniture (content/stsv/items/furniture_retro_room.yml, _kitchen.yml, _yard.yml) loads with the
 * real parser, every piece has its model and a 16 px icon, and its shape worked out from the shipped model makes
 * sense: the fitted boxes cover the model, nobody sits inside a barrier, a lamp's light is in a cell without a
 * barrier, and every piece sits in the middle of its cells facing any of the four ways (the wall clock hangs flat
 * against the wall behind its cell). Thirty pieces, ten a group; ids are not used by any other item.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetroFurnitureTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final double FILL = 0.30; // furniture.barrier-fill in config.yml
    static final List<String> GROUPS = List.of("room", "kitchen", "yard");
    /** pieces hung flat against the wall behind their cell (not centred front to back) */
    static final Set<String> WALL_HUNG = Set.of("wall_clock");

    private final Map<String, FurnitureSpec> specs = new LinkedHashMap<>();
    private final Map<String, ConfigurationSection> items = new LinkedHashMap<>();
    private final Map<String, Integer> perGroup = new LinkedHashMap<>();
    private final Map<String, FurnitureShape.Shape> shapes = new LinkedHashMap<>();
    private final Map<String, FurnitureShape.Voxels> voxels = new LinkedHashMap<>();
    private final Map<String, JsonObject> models = new LinkedHashMap<>();
    private ContentPack pack;

    @BeforeAll
    void load() throws Exception {
        pack = new ContentPack("stsv", "stsv", PACK);
        for (String g : GROUPS) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(PACK.resolve("items/furniture_retro_" + g + ".yml").toFile());
            perGroup.put(g, yaml.getKeys(false).size());
            for (String id : yaml.getKeys(false)) {
                ConfigurationSection sec = yaml.getConfigurationSection(id);
                assertNull(items.put(id, sec), id + ": twice");
                specs.put(id, ContentModule.furnitureSpec(sec.getConfigurationSection("furniture"), pack));
            }
        }
        if (specs.isEmpty()) return;
        PackBuilder.Built built = new PackBuilder().build(List.of(pack), List.of(), 97, "test");
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            FurnitureSpec spec = e.getValue();
            PackBuilder.Model model = built.itemModel("stsv:model/" + items.get(e.getKey()).getString("model"));
            assertNotNull(model, e.getKey());
            models.put(e.getKey(), model.json());
            FurnitureShape.Voxels v = FurnitureShape.voxelize(spec.cells(), model.json(), spec.translation(), spec.scale(), List.of(), 0);
            voxels.put(e.getKey(), v);
            shapes.put(e.getKey(), FurnitureShape.shape(v, spec.cells(), FILL, spec.solid()));
        }
    }

    @Test
    void eachGroupIsTenPiecesWithIdsOfTheirOwn() throws Exception {
        for (Map.Entry<String, Integer> g : perGroup.entrySet()) assertEquals(10, g.getValue(), g.getKey() + ": pieces");
        assertEquals(30, specs.size());
        // no other items file of the pack defines one of these ids
        try (var files = Files.list(PACK.resolve("items"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml") && !p.getFileName().toString().startsWith("furniture_retro_")).toList()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String id : items.keySet()) assertFalse(y.contains(id), id + " is also in " + f.getFileName());
            }
        }
    }

    @Test
    void everyPieceHasItsModelIconAndName() throws Exception {
        for (Map.Entry<String, ConfigurationSection> e : items.entrySet()) {
            ConfigurationSection sec = e.getValue();
            String id = e.getKey();
            assertTrue(sec.getString("name", "").length() > 3, id);
            assertTrue(sec.getStringList("lore").size() >= 2, id);
            assertTrue(sec.getStringList("lore").getFirst().contains("레트로 가구"), id + ": the lore says which set");
            String model = sec.getString("model"), icon = sec.getString("icon");
            assertTrue(Files.exists(PACK.resolve("models/" + model + ".bbmodel")), id + ": model");
            assertNotNull(icon, id + ": icon");
            assertFalse(Files.exists(PACK.resolve("models/" + icon + ".bbmodel")), id + ": icon name clashes with a model");
            BufferedImage im = ImageIO.read(PACK.resolve("textures/" + icon + ".png").toFile());
            assertEquals(16, im.getWidth(), id);
            assertEquals(16, im.getHeight(), id);
        }
    }

    @Test
    void cellsAreValid() {
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            List<int[]> cells = e.getValue().cells();
            Set<String> seen = new HashSet<>();
            for (int[] c : cells) assertTrue(seen.add(c[0] + "," + c[1] + "," + c[2]), e.getKey() + ": duplicate cell");
            assertTrue(seen.contains("0,0,0"), e.getKey() + ": the anchor cell");
            for (int[] c : cells) assertTrue(c[1] >= 0, e.getKey() + ": nothing below the floor");
        }
    }

    @Test
    void shapesAreFittedAndCoverTheModels() {
        for (Map.Entry<String, FurnitureShape.Shape> e : shapes.entrySet()) {
            FurnitureShape.Shape s = e.getValue();
            List<int[]> cells = specs.get(e.getKey()).cells();
            assertTrue(s.fitted(), e.getKey());
            assertTrue(s.boxes().size() <= FurnitureShape.MAX_BOXES, e.getKey() + ": " + s.boxes().size() + " boxes");
            FurnitureShapeTest.assertCovers(s, voxels.get(e.getKey()), cells);
            for (int[] b : s.barriers()) assertTrue(FurnitureShapeTest.inCells(cells, b[0], b[1], b[2]));
        }
    }

    @Test
    void seatsAreNeverInsideABarrier() {
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            for (FurnitureSpec.Seat seat : e.getValue().seats()) {
                assertFalse(shapes.get(e.getKey()).barrier(seat.cell()), e.getKey() + " " + java.util.Arrays.toString(seat.cell()));
            }
        }
    }

    @Test
    void lampsGiveLightFromACellWithoutABarrier() {
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            FurnitureSpec.Light l = e.getValue().light();
            if (l == null) continue;
            assertTrue(l.level() >= 1 && l.level() <= 15, e.getKey());
            assertFalse(shapes.get(e.getKey()).barrier(l.cell()), e.getKey() + ": its light cell");
        }
    }

    /** Every piece sits in the middle of its cells, placed facing any of the four ways (JapaneseFurnitureTest). */
    @Test
    void modelsAreCentredInTheirCellsFacingEveryWay() {
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            String id = e.getKey();
            FurnitureSpec spec = e.getValue();
            JsonObject model = models.get(id);
            double[] b = FurnitureShape.bounds(model, FurnitureShape.mainPlacement(model, spec.translation(), spec.scale(), 0));
            for (float yaw : new float[]{0, 90, 180, 270}) {
                double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE}, hi = {-Double.MAX_VALUE, -Double.MAX_VALUE};
                for (int c = 0; c < 4; c++) {
                    double[] w = FurnitureModule.modelToWorld(new org.joml.Vector3f((float) ((c & 1) == 0 ? b[0] : b[3]), 0f,
                            (float) ((c & 2) == 0 ? b[2] : b[5])), yaw);
                    lo[0] = Math.min(lo[0], w[0] + 0.5);
                    hi[0] = Math.max(hi[0], w[0] + 0.5);
                    lo[1] = Math.min(lo[1], w[2] + 0.5);
                    hi[1] = Math.max(hi[1], w[2] + 0.5);
                }
                int[] cmin = {Integer.MAX_VALUE, Integer.MAX_VALUE}, cmax = {Integer.MIN_VALUE, Integer.MIN_VALUE};
                for (int[] cell : spec.cells()) {
                    int[] o = FurnitureModule.rotate(cell, yaw);
                    cmin[0] = Math.min(cmin[0], o[0]);
                    cmax[0] = Math.max(cmax[0], o[0] + 1);
                    cmin[1] = Math.min(cmin[1], o[2]);
                    cmax[1] = Math.max(cmax[1], o[2] + 1);
                }
                boolean depthIsX = Math.abs(Math.sin(Math.toRadians(yaw))) > 0.5;
                for (int a = 0; a < 2; a++) {
                    if (WALL_HUNG.contains(id) && (a == 0) == depthIsX) continue;
                    double near = lo[a] - cmin[a], far = cmax[a] - hi[a];
                    assertTrue(Math.abs(near - far) <= 1 / 16.0 + 1e-6, String.format("%s facing %s: %s margins %.3f / %.3f blocks",
                            id, yaw, a == 0 ? "x" : "z", near, far));
                }
            }
        }
    }
}
