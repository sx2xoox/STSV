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
 * The Japanese-style furniture (content/stsv/items/furniture_japanese.yml) loads with the real parser, every
 * piece has its model and a 16 px icon, and its shape worked out from the shipped model makes sense: cushions and
 * mats can be walked over and sat on (never a barrier where someone sits), screens, fences and lanterns are solid,
 * the torii can be walked through in the middle, lamps give light from a cell of their own, and every piece sits
 * in the middle of its cells (a size that is not a whole number of blocks leaves the same margin on both sides).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JapaneseFurnitureTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final double FILL = 0.30; // furniture.barrier-fill in config.yml

    private final Map<String, FurnitureSpec> specs = new LinkedHashMap<>();
    private final Map<String, ConfigurationSection> items = new LinkedHashMap<>();
    private final Map<String, FurnitureShape.Shape> shapes = new LinkedHashMap<>();
    private final Map<String, FurnitureShape.Voxels> voxels = new LinkedHashMap<>();
    private final Map<String, JsonObject> models = new LinkedHashMap<>();
    private ContentPack pack;

    @BeforeAll
    void load() throws Exception {
        pack = new ContentPack("stsv", "stsv", PACK);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(PACK.resolve("items/furniture_japanese.yml").toFile());
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection sec = yaml.getConfigurationSection(id);
            items.put(id, sec);
            specs.put(id, ContentModule.furnitureSpec(sec.getConfigurationSection("furniture"), pack));
        }
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
    void everyPieceHasItsModelIconAndName() throws Exception {
        assertEquals(30, specs.size(), "Japanese furniture pieces");
        for (Map.Entry<String, ConfigurationSection> e : items.entrySet()) {
            ConfigurationSection sec = e.getValue();
            String id = e.getKey();
            assertTrue(sec.getString("name", "").length() > 3, id);
            assertTrue(sec.getStringList("lore").size() >= 2, id);
            String model = sec.getString("model"), icon = sec.getString("icon");
            assertTrue(Files.exists(PACK.resolve("models/" + model + ".bbmodel")), id + ": model");
            assertNotNull(icon, id + ": icon");
            // a texture and a .bbmodel of the same name would both become models/item/<name>.json
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
        int seats = 0;
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            // with the default fill, and even if the piece were solid
            FurnitureShape.Shape solid = FurnitureShape.shape(voxels.get(e.getKey()), e.getValue().cells(), FILL, true);
            for (FurnitureSpec.Seat seat : e.getValue().seats()) {
                seats++;
                assertFalse(shapes.get(e.getKey()).barrier(seat.cell()), e.getKey());
                assertFalse(solid.barrier(seat.cell()), e.getKey() + ": the seat's cell is filled enough for a barrier");
            }
        }
        assertEquals(1 + 4 + 2, seats, "zabuton, kotatsu, futon");
        // the kotatsu's sitters look at the table
        List<FurnitureSpec.Seat> k = specs.get("kotatsu").seats();
        for (FurnitureSpec.Seat s : k) {
            int[] c = s.cell();
            float want = c[2] == -1 ? 180 : c[2] == 1 ? 0 : c[0] == 1 ? -90 : 90;
            assertEquals(want, s.facing(), 1e-6, java.util.Arrays.toString(c));
        }
        assertTrue(shapes.get("kotatsu").barrier(new int[]{0, 0, 0}), "the kotatsu table itself is solid");
    }

    @Test
    void lowAndHangingThingsCanBeWalkedThrough() {
        for (String id : List.of("tatami", "zabuton", "futon", "noren", "chochin", "wind_chime", "stepping_stones", "koi_pond", "hanging_scroll")) {
            assertFalse(specs.get(id).solid(), id);
            assertTrue(shapes.get(id).barriers().isEmpty(), id);
        }
        // mats, cushions and stones are low enough that even a solid version would get no barrier
        for (String id : List.of("tatami", "zabuton", "stepping_stones")) {
            assertTrue(FurnitureShape.shape(voxels.get(id), specs.get(id).cells(), FILL, true).barriers().isEmpty(), id);
        }
    }

    @Test
    void wallsAndBigPiecesAreSolid() {
        for (String id : List.of("shoji_screen", "fusuma_panel", "bamboo_fence", "stone_lantern", "chabudai", "kimono_rack")) {
            assertTrue(specs.get(id).solid(), id);
            assertFalse(shapes.get(id).barriers().isEmpty(), id);
        }
        assertTrue(shapes.get("shoji_screen").barrier(new int[]{0, 1, 0}), "a screen two blocks tall blocks at head height");
        assertTrue(shapes.get("bamboo_fence").barrier(new int[]{0, 1, 0}), "the kenninji fence screens at eye level (1.75 blocks)");
        assertTrue(shapes.get("fusuma_panel").barrier(new int[]{1, 2, 0}), "the fusuma is three blocks tall");
        FurnitureShape.Shape torii = shapes.get("torii");
        for (int y = 0; y < 2; y++) {   // the beams fill the cells above (2 blocks up)
            assertFalse(torii.barrier(new int[]{0, y, 0}) || torii.barrier(new int[]{1, y, 0}), "walk through the torii's middle, two wide");
        }
        for (int y = 0; y < 2; y++) {
            assertTrue(torii.barrier(new int[]{-1, y, 0}) && torii.barrier(new int[]{2, y, 0}), "its posts are solid");
        }
    }

    /**
     * Every piece sits in the middle of its cells: the empty margin between the model's bounds and its cells'
     * outline is the same on both sides (within 1/16 block) along x and z, placed facing any of the four ways.
     * Worked out from the shipped model where the item display shows it (display transform, translation,
     * scale) and turned with the same maths as placing it. The hanging scroll is the one exception in depth:
     * it hangs flat against the wall behind its cell.
     */
    @Test
    void modelsAreCentredInTheirCellsFacingEveryWay() {
        Set<String> wallHung = Set.of("hanging_scroll");
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            String id = e.getKey();
            FurnitureSpec spec = e.getValue();
            JsonObject model = models.get(id);
            double[] b = FurnitureShape.bounds(model, FurnitureShape.mainPlacement(model, spec.translation(), spec.scale(), 0));
            for (float yaw : new float[]{0, 90, 180, 270}) {
                // the model's bounds and the cells, in blocks from the anchor block's corner, turned as placed
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
                boolean depthIsX = Math.abs(Math.sin(Math.toRadians(yaw))) > 0.5; // the model's z (front to back) along world x
                for (int a = 0; a < 2; a++) {
                    if (wallHung.contains(id) && (a == 0) == depthIsX) continue;
                    double near = lo[a] - cmin[a], far = cmax[a] - hi[a];
                    assertTrue(Math.abs(near - far) <= 1 / 16.0 + 1e-6, String.format("%s facing %s: %s margins %.3f / %.3f blocks",
                            id, yaw, a == 0 ? "x" : "z", near, far));
                }
            }
        }
    }

    @Test
    void lampsGiveLightFromACellOfTheirOwn() {
        Map<String, Integer> want = Map.of("andon", 14, "stone_lantern", 15, "chochin", 13, "tea_kettle", 7);
        for (Map.Entry<String, FurnitureSpec> e : specs.entrySet()) {
            FurnitureSpec.Light l = e.getValue().light();
            if (!want.containsKey(e.getKey())) {
                assertNull(l, e.getKey());
                continue;
            }
            assertNotNull(l, e.getKey());
            assertEquals(want.get(e.getKey()), l.level(), e.getKey());
        }
        // the stone lantern stays solid at the bottom; its light is in the fire box above
        assertTrue(shapes.get("stone_lantern").barrier(new int[]{0, 0, 0}));
        assertEquals(List.of(0, 1, 0), List.of(specs.get("stone_lantern").light().cell()[0], specs.get("stone_lantern").light().cell()[1],
                specs.get("stone_lantern").light().cell()[2]));
    }

    @Test
    void theIconShowsInInventoriesAndTheModelEverywhereElse() throws Exception {
        CustomItem tatami = new CustomItem(new NamespacedKey("stsv", "tatami"), pack, Material.PAPER, "tatami", List.of(),
                new ModelRef(ModelRef.Kind.BBMODEL, "tatami", false, "tatami_icon"), null, null, false,
                specs.get("tatami"), null, null, null, null, null, null, null);
        PackBuilder.Built built = new PackBuilder().build(List.of(pack), List.of(tatami), 97, "test");
        assertTrue(built.warnings().isEmpty(), built.warnings().toString());
        JsonObject def = JsonParser.parseString(new String(built.json().get("assets/stsv/items/tatami.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject select = def.getAsJsonObject("model");
        assertEquals("minecraft:select", select.get("type").getAsString());
        assertEquals("minecraft:display_context", select.get("property").getAsString());
        JsonObject gui = select.getAsJsonArray("cases").get(0).getAsJsonObject();
        assertEquals("gui", gui.get("when").getAsString());
        assertEquals("stsv:item/tatami_icon", gui.getAsJsonObject("model").get("model").getAsString());
        assertEquals("stsv:item/tatami", select.getAsJsonObject("fallback").get("model").getAsString());
        // placed furniture (an item display, context "fixed") is fitted to the 3D model behind the select
        PackBuilder.Model placed = built.itemModel("stsv:tatami");
        assertNotNull(placed);
        assertEquals("stsv:item/tatami", placed.id());
        assertTrue(built.json().containsKey("assets/stsv/models/item/tatami_icon.json"));
    }

    @Test
    void lightParsing() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("a.cells", List.of(List.of(0, 0, 0), List.of(0, 1, 0)));
        y.set("a.light", 9);
        assertEquals(9, ContentModule.furnitureSpec(y.getConfigurationSection("a"), pack).light().level());
        y.set("a.light", null);
        y.set("a.light.level", 12);
        y.set("a.light.cell", List.of(0, 1, 0));
        FurnitureSpec.Light l = ContentModule.furnitureSpec(y.getConfigurationSection("a"), pack).light();
        assertEquals(12, l.level());
        assertEquals(1, l.cell()[1]);
        y.set("a.light.cell", List.of(1, 0, 0));
        assertThrowsIae(() -> ContentModule.furnitureSpec(y.getConfigurationSection("a"), pack));
        y.set("a.light", 0);
        assertThrowsIae(() -> ContentModule.furnitureSpec(y.getConfigurationSection("a"), pack));
        y.set("a.light", null);
        assertNull(ContentModule.furnitureSpec(y.getConfigurationSection("a"), pack).light());
        List<FurnitureSpec.Seat> none = new ArrayList<>(ContentModule.furnitureSpec(y.getConfigurationSection("a"), pack).seats());
        assertTrue(none.isEmpty());
    }

    private static void assertThrowsIae(Runnable r) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, r::run);
    }
}
