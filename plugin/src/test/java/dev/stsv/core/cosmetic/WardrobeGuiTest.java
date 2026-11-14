package dev.stsv.core.cosmetic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuSpec;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menu backgrounds: the drawn cells must sit exactly where the menu's layout puts items, and the
 * generated font must place the image over the whole screen and bring the title text back to x 8.
 */
class WardrobeGuiTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");
    private static final Path ASSETS = PACK.resolve("assets/stsv");

    /** A drawn button icon (assets/stsv/items), a content model (models/*.bbmodel) or item texture (textures/*.png): their item models are generated. */
    private static boolean iconExists(org.bukkit.NamespacedKey icon) {
        return Files.exists(ASSETS.resolve("items/" + icon.getKey() + ".json")) || Files.exists(PACK.resolve("models/" + icon.getKey() + ".bbmodel"))
                || Files.exists(PACK.resolve("textures/" + icon.getKey() + ".png"));   // a content item's own icon (e.g. the cheque)
    }

    private static MenuSpec wardrobe(List<String> problems) throws Exception {
        List<MenuSpec> menus = MenuSpec.loadAll(List.of(new ContentPack("stsv", "stsv", PACK)), problems);
        return menus.stream().filter(m -> m.id().equals("stsv:wardrobe")).findFirst().orElseThrow();
    }

    @Test
    void menuLoadsWithItsBackground() throws Exception {
        List<String> problems = new ArrayList<>();
        MenuSpec m = wardrobe(problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertNotNull(m.background(), "background kept (size checked)");
        assertEquals(28, m.slots(m.list()).size(), "7 x 4 cosmetics per page");
        for (MenuSpec.Button b : m.buttons().values()) {
            if (b.on().icon() != null) {
                assertTrue(iconExists(b.on().icon()), b.on().icon().toString());
            }
            if (b.off() != null && b.off().icon() != null) {
                assertTrue(iconExists(b.off().icon()), b.off().icon().toString());
            }
        }
    }

    @Test
    void generatedFontCoversTheScreenAndReturnsToTheTitle() throws Exception {
        MenuSpec m = wardrobe(new ArrayList<>());
        JsonObject font = MenuSpec.font(List.of(m));
        int shiftLeft = 0, shiftBack = 0;
        boolean bitmap = false;
        for (JsonElement e : font.getAsJsonArray("providers")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("type").getAsString().equals("space")) {
                shiftLeft = p.getAsJsonObject("advances").get(String.valueOf(MenuSpec.SHIFT_LEFT)).getAsInt();
                shiftBack = p.getAsJsonObject("advances").get(String.valueOf(MenuSpec.SHIFT_BACK)).getAsInt();
            } else {
                bitmap = true;
                assertEquals(13, p.get("ascent").getAsInt(), "top of the glyph = top of the screen");
                assertEquals(m.height(), p.get("height").getAsInt(), "1:1 pixel scale");
                assertEquals(String.valueOf(m.glyph()), p.getAsJsonArray("chars").get(0).getAsString());
            }
        }
        assertTrue(bitmap);
        assertEquals(-8, shiftLeft, "title x 8 -> screen edge");
        assertEquals(0, shiftLeft + (MenuSpec.WIDTH + 1) + shiftBack, "title text starts back at x 8");
        assertTrue(m.backgroundPrefix().startsWith("<white>"), "glyph must not be tinted by the dark default title colour");
    }

    private static MenuSpec menu(String id) throws Exception {
        List<MenuSpec> menus = MenuSpec.loadAll(List.of(new ContentPack("stsv", "stsv", PACK)), new ArrayList<>());
        return menus.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * 옷장 and 치장 뽑기 browse cosmetics the same way: the top row is the category tabs, 전체 then every slot in
     * order, each named with its "owned/total" count and drawn with its own icon; 7 x 4 cosmetics below.
     */
    @Test
    void bothCosmeticScreensHaveTheCategoryTabs() throws Exception {
        for (String[] menu : List.of(new String[]{"stsv:wardrobe", "wardrobe"}, new String[]{"stsv:gacha", "gacha"})) {
            MenuSpec m = menu(menu[0]);
            assertEquals(28, m.slots(m.list()).size(), menu[0] + ": 7 x 4 per page");
            String top = m.layout().getFirst();
            assertEquals(CosmeticBrowser.TABS.size(), top.length(), menu[0] + ": 전체 + one tab per slot fill the top row");
            for (int c = 0; c < top.length(); c++) {
                MenuSpec.Button b = m.buttons().get(top.charAt(c));
                String tab = CosmeticBrowser.TABS.get(c);
                assertEquals(menu[1] + ":tab:" + tab, b.action(), menu[0] + " column " + c);
                assertTrue(b.on().name().contains("{n_" + tab + "}"), menu[0] + " " + tab + ": shows owned/total");
                assertEquals(org.bukkit.NamespacedKey.fromString("stsv:gui/tab_" + tab), b.on().icon(), menu[0] + " " + tab + " icon");
            }
            assertTrue(m.title().contains("{tab}") && m.title().contains("{owned}/{total}"), menu[0] + " title");
        }
        // the wardrobe keeps its 착용 중 view and gets the 보기 filter
        MenuSpec w = menu("stsv:wardrobe");
        List<String> actions = w.buttons().values().stream().map(MenuSpec.Button::action).toList();
        assertTrue(actions.contains("wardrobe:tab:worn") && actions.contains("wardrobe:view") && actions.contains("wardrobe:gacha"), actions.toString());
    }

    /** The not-owned card: one shared model, its second layer tinted by the item's first custom_model_data colour. */
    @Test
    void theNotOwnedCardIsOneTintedModel() throws Exception {
        JsonObject def = com.google.gson.JsonParser.parseString(Files.readString(ASSETS.resolve("items/gui/locked.json"))).getAsJsonObject();
        JsonObject model = def.getAsJsonObject("model");
        assertEquals("stsv:item/gui/locked", model.get("model").getAsString());
        assertEquals(CosmeticBrowser.LOCKED_MODEL.asString(), "stsv:gui/locked");
        JsonObject tint = model.getAsJsonArray("tints").get(1).getAsJsonObject();
        assertEquals("minecraft:custom_model_data", tint.get("type").getAsString());
        assertEquals(0, tint.get("index").getAsInt());
        JsonObject m = com.google.gson.JsonParser.parseString(Files.readString(ASSETS.resolve("models/item/gui/locked.json"))).getAsJsonObject();
        for (String layer : List.of("layer0", "layer1")) {
            String tex = m.getAsJsonObject("textures").get(layer).getAsString();
            assertTrue(Files.exists(ASSETS.resolve("textures/" + tex.substring("stsv:".length()) + ".png")), tex);
        }
        // dark: the card's face is darker than the slot it sits in
        BufferedImage card = ImageIO.read(ASSETS.resolve("textures/item/gui/locked.png").toFile());
        int rgb = card.getRGB(7, 12);
        assertTrue(((rgb >> 16) & 0xff) < 28 && ((rgb >> 8) & 0xff) < 28 && (rgb & 0xff) < 37, Integer.toHexString(rgb));
    }

    @Test
    void layoutSlotsLandOnDrawnCells() throws Exception {
        layoutSlotsLandOnDrawnCells(wardrobe(new ArrayList<>()));
        layoutSlotsLandOnDrawnCells(menu("stsv:gacha"));
    }

    private static void layoutSlotsLandOnDrawnCells(MenuSpec m) throws Exception {
        BufferedImage image = ImageIO.read(ASSETS.resolve("textures/" + m.background().getKey() + ".png").toFile());
        List<Integer> cosmetic = m.slots(m.list());
        List<Integer> buttons = new ArrayList<>();
        for (char k : m.buttons().keySet()) buttons.addAll(m.slots(k));
        int cellFill = centre(image, cosmetic.getFirst());
        for (int s : cosmetic) assertEquals(cellFill, centre(image, s), "list slot " + s);
        int buttonFill = centre(image, buttons.getFirst());
        for (int s : buttons) assertEquals(buttonFill, centre(image, s), "button slot " + s);
        assertTrue(cellFill != buttonFill);
        for (int s = 0; s < m.rows() * 9; s++) {
            if (!cosmetic.contains(s) && !buttons.contains(s)) {
                assertTrue(centre(image, s) != cellFill && centre(image, s) != buttonFill, "slot " + s + " is drawn as a cell but unused");
            }
        }
    }

    @Test
    void badMenusAreReportedNotCrashing(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("menus"));
        Files.writeString(dir.resolve("menus/x.yml"), """
                broken:
                  rows: 1
                  layout: ["AB......."]
                  buttons: { A: { action: close } }
                wrongsize:
                  rows: 1
                  background: t:gui/none
                  layout: ["........."]
                """);
        List<String> problems = new ArrayList<>();
        List<MenuSpec> menus = MenuSpec.loadAll(List.of(new ContentPack("t", "t", dir)), problems);
        assertEquals(1, menus.size(), "the broken one is skipped");
        assertTrue(problems.stream().anyMatch(p -> p.contains("'B'")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("배경 그림이 없음")), problems.toString());
    }

    private static int centre(BufferedImage image, int slot) {
        int x = 7 + 18 * (slot % 9) + 9, y = 17 + 18 * (slot / 9) + 9;
        int argb = image.getRGB(x, y);
        assertEquals(0xFF, argb >>> 24, "opaque at slot " + slot);
        return argb;
    }

    private static String secretButtons(String menuId) throws java.io.IOException {
        String key = menuId.substring(menuId.indexOf(':') + 1);
        try (var files = java.nio.file.Files.list(PACK.resolve("menus"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".yml")).toList()) {
                var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f.toFile());
                if (y.contains(key)) return y.getString(key + ".secret", "");
            }
        }
        return "";
    }

    @Test
    void everyCoreMenuHasTheSharedLookAndTheInventoryLabelIsBlank() throws Exception {
        List<String> problems = new ArrayList<>();
        List<MenuSpec> menus = MenuSpec.loadAll(List.of(new ContentPack("stsv", "stsv", PACK)), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(java.util.Set.of("stsv:wardrobe", "stsv:profile", "stsv:titles", "stsv:achievements", "stsv:achievement_list", "stsv:hub", "stsv:gacha", "stsv:nick_colors", "stsv:tuning",
                        "stsv:donation", "stsv:donation_buy", "stsv:emote_wheel", "stsv:emote_pick", "stsv:tool_skins"),
                menus.stream().map(MenuSpec::id).collect(java.util.stream.Collectors.toSet()));
        Integer panel = null;
        for (MenuSpec m : menus) {
            assertNotNull(m.background(), m.id() + " has a background");
            // a "secret" button (only some players get it) has no cell on the background: it counts as empty
            String secret = secretButtons(m.id());
            for (String row : m.layout()) {
                String used = row.replaceAll(secret.isEmpty() ? "[^.]" : "[" + java.util.regex.Pattern.quote(secret) + "]", ".").replaceAll("[^.]", "#");
                assertEquals(new StringBuilder(used).reverse().toString(), used, m.id() + " row " + row + " is symmetric");
            }
            BufferedImage im = ImageIO.read(ASSETS.resolve("textures/" + m.background().getKey() + ".png").toFile());
            // one look: the same panel colour beside the title on every menu
            int here = im.getRGB(100, 8);
            if (panel == null) panel = here;
            assertEquals(panel.intValue(), here, m.id() + " panel colour");
            for (MenuSpec.Button b : m.buttons().values()) {
                if (b.on().icon() != null && b.on().icon().getNamespace().equals("stsv")) {
                    assertTrue(iconExists(b.on().icon()), m.id() + " " + b.on().icon());
                }
            }
        }
        // the dark menus have no light strip behind the client's dark "Inventory" label: the pack blanks it
        for (String lang : List.of("ko_kr", "en_us")) {
            JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(PACK.resolve("assets/minecraft/lang/" + lang + ".json"))).getAsJsonObject();
            assertEquals("", o.get("container.inventory").getAsString(), lang);
        }
    }
}
