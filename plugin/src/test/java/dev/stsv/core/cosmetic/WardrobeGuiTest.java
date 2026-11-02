package dev.stsv.core.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wardrobe screen texture is drawn for a fixed slot layout. These checks keep the three parts in
 * step: the config (which slot shows what), the font glyph (where the image lands) and the image
 * (where it draws the cells). A mismatch would put items next to their drawn cells in game.
 */
class WardrobeGuiTest {

    private static final Path ASSETS = Path.of(System.getProperty("stsv.content")).resolve("stsv/assets/stsv");
    private static final List<String> BUTTON_ICONS = List.of("prev", "next", "prev_off", "next_off", "take_off", "info", "exit");

    private static YamlConfiguration config() throws Exception {
        try (var in = WardrobeGuiTest.class.getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void backgroundGlyphCoversTheLargeChestScreen() throws Exception {
        JsonObject font = JsonParser.parseString(Files.readString(ASSETS.resolve("font/wardrobe.json"))).getAsJsonObject();
        Map<Integer, Integer> advances = new java.util.HashMap<>();
        Set<Integer> glyphs = new HashSet<>();
        BufferedImage image = null;
        for (JsonElement e : font.getAsJsonArray("providers")) {
            JsonObject p = e.getAsJsonObject();
            switch (p.get("type").getAsString()) {
                case "space" -> p.getAsJsonObject("advances").entrySet()
                        .forEach(a -> advances.put(a.getKey().codePointAt(0), a.getValue().getAsInt()));
                case "bitmap" -> {
                    String[] file = p.get("file").getAsString().split(":");
                    image = ImageIO.read(ASSETS.resolve("textures/" + file[1]).toFile());
                    assertEquals(176, image.getWidth(), "large chest screen width");
                    assertEquals(222, image.getHeight(), "large chest screen height");
                    assertEquals(222, p.get("height").getAsInt(), "glyph drawn at 1:1 pixel scale");
                    assertEquals(13, p.get("ascent").getAsInt(), "top of the glyph = top of the screen (title y 6 + 7 - 13)");
                    JsonArray chars = p.getAsJsonArray("chars");
                    chars.forEach(c -> glyphs.add(c.getAsString().codePointAt(0)));
                }
                default -> { }
            }
        }
        assertTrue(image != null, "bitmap provider");

        // the configured title prefix must start 8 px left (title x = 8), draw the glyph, and return to x = 8
        String bg = config().getString("cosmetics.menu.background");
        int[] cps = bg.replaceAll("<[^>]*>", "").codePoints().toArray();
        assertEquals(3, cps.length, "shift, glyph, shift back: " + bg);
        assertEquals(-8, advances.get(cps[0]));
        assertTrue(glyphs.contains(cps[1]));
        int glyphAdvance = image.getWidth() + 1;
        assertEquals(0, advances.get(cps[0]) + glyphAdvance + advances.get(cps[2]), "title text starts back at x = 8");
        assertTrue(bg.contains("<white>"), "glyph must not be tinted by the default dark title colour");
    }

    @Test
    void configuredSlotsLandOnDrawnCells() throws Exception {
        BufferedImage image = ImageIO.read(ASSETS.resolve("textures/gui/wardrobe.png").toFile());
        YamlConfiguration c = config();
        List<Integer> cosmetic = c.getIntegerList("cosmetics.menu.cosmetic-slots");
        List<Integer> buttons = List.of("prev", "take-off", "info", "exit", "next").stream()
                .map(k -> c.getInt("cosmetics.menu.slots." + k)).toList();

        // every cosmetic cell has the same recessed fill at its centre, every button the same raised fill,
        // and the two differ (so a slot moved in the config without redrawing the image is caught)
        int cellFill = centre(image, cosmetic.getFirst());
        for (int s : cosmetic) assertEquals(cellFill, centre(image, s), "cosmetic slot " + s);
        int buttonFill = centre(image, buttons.getFirst());
        for (int s : buttons) assertEquals(buttonFill, centre(image, s), "button slot " + s);
        assertTrue(cellFill != buttonFill);
        for (int s = 0; s < 54; s++) {
            if (!cosmetic.contains(s) && !buttons.contains(s)) {
                assertTrue(centre(image, s) != cellFill && centre(image, s) != buttonFill, "slot " + s + " is drawn as a cell but unused");
            }
        }
    }

    @Test
    void buttonIconsExist() {
        for (String icon : BUTTON_ICONS) {
            assertTrue(Files.exists(ASSETS.resolve("items/gui/" + icon + ".json")), icon);
            assertTrue(Files.exists(ASSETS.resolve("textures/item/gui/" + icon + ".png")), icon);
        }
    }

    private static int centre(BufferedImage image, int slot) {
        int x = 7 + 18 * (slot % 9) + 9, y = 17 + 18 * (slot / 9) + 9;
        int argb = image.getRGB(x, y);
        assertEquals(0xFF, argb >>> 24, "opaque at slot " + slot);
        return argb;
    }
}
