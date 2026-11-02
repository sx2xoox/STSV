package dev.stsv.core.gui.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentPack;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A chest menu defined in contents/&lt;pack&gt;/menus/*.yml (see content/stsv/menus/wardrobe.yml).
 *
 * <pre>
 * layout:            9 characters per row: '.' = nothing, a letter = that button, the "list" letter = paged area
 * buttons:           letter -> action, look (material, icon = item model, name, lore), "off" look when unavailable
 * background:        &lt;ns&gt;:&lt;path&gt; = assets/&lt;ns&gt;/textures/&lt;path&gt;.png, exactly 176 x (114 + 18 x rows) px
 * </pre>
 * The background is shown through the title: every menu with a background gets one character of the
 * generated font "stsv:menus" (U+E000 + n, in id order), so authors only draw a PNG.
 */
public record MenuSpec(String id, String title, int rows, NamespacedKey background, List<String> layout, char list,
                       Map<Character, Button> buttons, String filler, char glyph) {

    public static final String FONT = "stsv:menus";
    public static final char SHIFT_LEFT = '';   // -8: from the title position (x 8) to the screen edge
    public static final char SHIFT_BACK = '';   // -(176 + 1 - 8): back to x 8 after the background
    public static final int WIDTH = 176;

    public record Look(String material, NamespacedKey icon, String name, List<String> lore) {}

    public record Button(char key, String action, Look on, Look off) {}

    public int height() {
        return 114 + 18 * rows;
    }

    /** Slots (0-based, row by row) that hold the given layout letter. */
    public List<Integer> slots(char c) {
        List<Integer> out = new ArrayList<>();
        for (int r = 0; r < layout.size(); r++) {
            String row = layout.get(r);
            for (int col = 0; col < 9 && col < row.length(); col++) if (row.charAt(col) == c) out.add(r * 9 + col);
        }
        return out;
    }

    /** Text placed before the title so the background image covers the whole screen. */
    public String backgroundPrefix() {
        if (background == null) return "";
        return "<white><font:" + FONT + ">" + SHIFT_LEFT + glyph + SHIFT_BACK + "</font></white>";
    }

    // ------------------------------------------------------------------------------------------

    /** Every menu of every pack, sorted by id (so glyphs are stable for the same set of menus). */
    public static List<MenuSpec> loadAll(List<ContentPack> packs, List<String> problems) throws IOException {
        List<MenuSpec> raw = new ArrayList<>();
        for (ContentPack pack : packs) {
            Path dir = pack.dir().resolve("menus");
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                    for (String key : y.getKeys(false)) {
                        String id = pack.namespace() + ":" + key;
                        try {
                            MenuSpec m = parse(id, y.getConfigurationSection(key), '\0');
                            if (m.background() != null) {
                                Path png = packTexture(pack, m.background());
                                if (png == null || !Files.exists(png)) {
                                    problems.add(id + ": 배경 그림이 없음 (" + m.background() + ")");
                                    m = m.withoutBackground();
                                } else {
                                    int[] size = pngSize(png);
                                    if (size[0] != WIDTH || size[1] != m.height()) {
                                        problems.add(id + ": 배경 크기는 " + WIDTH + "x" + m.height() + " 이어야 함 (지금 " + size[0] + "x" + size[1] + ")");
                                        m = m.withoutBackground();
                                    }
                                }
                            }
                            raw.add(m);
                        } catch (RuntimeException e) {
                            problems.add(id + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        raw.sort(Comparator.comparing(MenuSpec::id));
        List<MenuSpec> out = new ArrayList<>();
        char next = '';
        for (MenuSpec m : raw) out.add(m.background() == null ? m : m.withGlyph(next++));
        return out;
    }

    private static Path packTexture(ContentPack pack, NamespacedKey key) {
        if (!key.getNamespace().equals(pack.namespace())) return null;
        return pack.assets().resolve(key.getNamespace()).resolve("textures").resolve(key.getKey() + ".png");
    }

    private static int[] pngSize(Path png) throws IOException {
        byte[] h = new byte[24];
        try (var in = Files.newInputStream(png)) {
            if (in.readNBytes(h, 0, 24) < 24) return new int[]{0, 0};
        }
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h);
        return new int[]{b.getInt(16), b.getInt(20)};
    }

    /** The generated font: shift spaces + one bitmap per background. */
    public static JsonObject font(List<MenuSpec> menus) {
        JsonArray providers = new JsonArray();
        JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        JsonObject adv = new JsonObject();
        adv.addProperty(String.valueOf(SHIFT_LEFT), -8);
        adv.addProperty(String.valueOf(SHIFT_BACK), -(WIDTH + 1 - 8));
        space.add("advances", adv);
        providers.add(space);
        for (MenuSpec m : menus) {
            if (m.background() == null) continue;
            JsonObject bmp = new JsonObject();
            bmp.addProperty("type", "bitmap");
            bmp.addProperty("file", m.background().getNamespace() + ":" + m.background().getKey() + ".png");
            bmp.addProperty("ascent", 13);          // title is drawn at y 6 with a 7 px ascent: 6 + 7 - 13 = top of the screen
            bmp.addProperty("height", m.height());
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(m.glyph()));
            bmp.add("chars", chars);
            providers.add(bmp);
        }
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        return font;
    }

    public static MenuSpec parse(String id, ConfigurationSection c, char glyph) {
        int rows = c.getInt("rows", 6);
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("rows 는 1~6");
        List<String> layout = c.getStringList("layout");
        if (layout.size() != rows) throw new IllegalArgumentException("layout 은 " + rows + "줄이어야 함");
        for (String row : layout) if (row.length() != 9) throw new IllegalArgumentException("layout 한 줄은 9칸: '" + row + "'");
        String list = c.getString("list", "");
        Map<Character, Button> buttons = new LinkedHashMap<>();
        ConfigurationSection bs = c.getConfigurationSection("buttons");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                if (k.length() != 1) throw new IllegalArgumentException("버튼 이름은 한 글자: " + k);
                ConfigurationSection b = bs.getConfigurationSection(k);
                Look on = look(b, null);
                ConfigurationSection off = b.getConfigurationSection("off");
                buttons.put(k.charAt(0), new Button(k.charAt(0), b.getString("action", ""), on, off == null ? null : look(off, on)));
            }
        }
        for (String row : layout) {
            for (char ch : row.toCharArray()) {
                if (ch != '.' && !buttons.containsKey(ch) && (list.isEmpty() || ch != list.charAt(0))) {
                    throw new IllegalArgumentException("layout 의 '" + ch + "' 가 buttons 에 없음");
                }
            }
        }
        String bg = c.getString("background");
        NamespacedKey background = bg == null || bg.isBlank() ? null : NamespacedKey.fromString(bg);
        return new MenuSpec(id, c.getString("title", ""), rows, background, List.copyOf(layout),
                list.isEmpty() ? '\0' : list.charAt(0), buttons, c.getString("filler", "none"), glyph);
    }

    private static Look look(ConfigurationSection b, Look base) {
        String icon = b.getString("icon", base == null || base.icon() == null ? null : base.icon().asString());
        return new Look(b.getString("material", base == null ? "PAPER" : base.material()),
                icon == null ? null : NamespacedKey.fromString(icon),
                b.getString("name", base == null ? " " : base.name()),
                b.contains("lore") ? b.getStringList("lore") : base == null ? List.of() : base.lore());
    }

    private MenuSpec withGlyph(char g) {
        return new MenuSpec(id, title, rows, background, layout, list, buttons, filler, g);
    }

    private MenuSpec withoutBackground() {
        return new MenuSpec(id, title, rows, null, layout, list, buttons, filler, '\0');
    }
}
