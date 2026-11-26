package dev.stsv.core.painting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentModule;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.content.FurnitureSpec;
import dev.stsv.core.furniture.FurnitureShape;
import dev.stsv.core.pack.PackBuilder;
import dev.stsv.core.pack.RenderBudget;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 그림 그리기 (이젤)의 콘텐츠: the easel loads as furniture with the painting behaviour, its two looks are built and within the
 * render budget, nothing of it stands in front of the canvas (the map against its upper front face) and the canvas's cell
 * stays free (no barrier, no click box: the frame is what the crosshair finds there); the canvas, brush and painting items
 * exist with their icons, and every text the module asks for is there.
 */
class EaselContentTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final double FILL = 0.30;   // furniture.barrier-fill in config.yml

    private static YamlConfiguration yaml(String file) {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/" + file).toFile());
    }

    @Test
    void theEaselIsFurnitureWithTheCanvasCellInFront() throws Exception {
        ConfigurationSection easel = yaml("furniture_easel.yml").getConfigurationSection("easel");
        assertNotNull(easel);
        ContentPack pack = new ContentPack("stsv", "stsv", PACK);
        FurnitureSpec spec = ContentModule.furnitureSpec(easel.getConfigurationSection("furniture"), pack);
        assertEquals(PaintingModule.BEHAVIOR, spec.behavior());
        assertEquals(FurnitureSpec.Rotation.CARDINAL, spec.rotation(), "the canvas hangs square to the world");
        assertEquals(List.of("0,0,0", "0,1,0", "0,1,-1"), spec.cells().stream().map(c -> c[0] + "," + c[1] + "," + c[2]).toList());
        assertEquals("easel_icon", easel.getString("icon"));

        PackBuilder builder = new PackBuilder();
        PackBuilder.Built built = builder.build(List.of(pack), List.of(), 97, "test");
        for (String look : List.of("easel", "easel_canvas")) {
            PackBuilder.Model model = built.itemModel("stsv:model/" + look);
            assertNotNull(model, look);
            JsonObject json = model.json();
            // nothing in front of the canvas: above the tray (y 16) every element stays behind z 0
            for (JsonElement e : json.getAsJsonArray("elements")) {
                JsonObject el = e.getAsJsonObject();
                double y1 = el.getAsJsonArray("to").get(1).getAsDouble(), z0 = el.getAsJsonArray("from").get(2).getAsDouble();
                assertFalse(y1 > 16.01 && z0 < -0.01, look + ": an element in front of the canvas " + el);
            }
            FurnitureShape.Voxels v = FurnitureShape.voxelize(spec.cells(), json, spec.translation(), spec.scale(), List.of(), 0);
            FurnitureShape.Shape s = FurnitureShape.shape(v, spec.cells(), FILL, spec.solid());
            assertTrue(s.fitted(), look);
            assertFalse(s.barrier(new int[]{0, 1, -1}), look + ": no barrier where the canvas hangs");
            for (FurnitureShape.Box b : s.boxes()) {
                boolean inCanvasCell = b.z0() < -0.5 && b.y1() > 16.5;
                assertFalse(inCanvasCell, look + ": a click box in the canvas's cell " + b);
            }
            assertTrue(s.boxes().stream().anyMatch(b -> b.y1() > 20), look + ": the upper half can be clicked (the mast, the board)");
        }
        // render budget: the easel is multi-cell furniture; its loaded look is shown by the module (any other model)
        int easelFaces = builder.faceCounts().get("stsv:easel")[1];
        int loadedFaces = builder.faceCounts().get("stsv:easel_canvas")[1];
        System.out.println("easel faces " + easelFaces + ", easel_canvas faces " + loadedFaces);
        assertTrue(easelFaces <= RenderBudget.Category.FURNITURE_LARGE.faces, "easel: " + easelFaces);
        assertTrue(loadedFaces <= RenderBudget.Category.OTHER.faces, "easel_canvas: " + loadedFaces);
        assertEquals("easel_canvas", PaintingModule.LOADED_MODEL.getKey().replace("model/", ""));
    }

    @Test
    void theItemsHaveTheirIcons() throws Exception {
        YamlConfiguration items = yaml("painting.yml");
        for (String id : PaintingModule.itemIds()) {
            String local = id.substring("stsv:".length());
            ConfigurationSection sec = local.equals("easel") ? yaml("furniture_easel.yml").getConfigurationSection(local)
                    : items.getConfigurationSection(local);
            assertNotNull(sec, id);
            String tex = sec.contains("texture") ? sec.getString("texture") : sec.getString("icon");
            BufferedImage im = ImageIO.read(PACK.resolve("textures/" + tex + ".png").toFile());
            assertEquals(16, im.getWidth(), id);
            assertEquals(16, im.getHeight(), id);
        }
        assertEquals("filled_map", items.getString("painting.material"), "a painting is a map: it shows in hand and in frames");
        assertEquals(1, items.getInt("painting.max-stack"));
        assertEquals(1, items.getInt("unfinished_canvas.max-stack"), "each canvas is its own picture");
        assertTrue(items.getBoolean("paint_brush.handheld"));
        // no other items file defines these ids
        try (var files = Files.list(PACK.resolve("items"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String n = f.getFileName().toString();
                if (n.equals("painting.yml") || n.equals("furniture_easel.yml")) continue;
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                for (String id : PaintingModule.itemIds()) assertFalse(y.contains(id.substring(5)), id + " is also in " + n);
            }
        }
    }

    @Test
    void everyToolHasItsName() throws Exception {
        YamlConfiguration messages;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            messages = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (PaintingModule.Tool t : PaintingModule.Tool.values()) assertTrue(messages.contains("painting.tool." + t.key()), t.key());
        for (String k : List.of("pen", "eraser", "fill", "pick", "size", "undo", "take-off", "finish", "prev", "next", "page", "current",
                "colour", "close")) assertTrue(messages.contains("painting.menu." + k), k);
        assertTrue(messages.getStringList("painting.item.lore").size() >= 2);
    }
}
