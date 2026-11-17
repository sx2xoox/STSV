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
 * frame:             optional {left, right, top, scale}: the picture also covers left / right / top px round the
 *                    panel (props, a roof over the title) and has scale texels per screen pixel (2 = sharper text at
 *                    GUI scale 2+), so it is (176 + left + right) * scale x (114 + 18 x rows + top) * scale. The client
 *                    cannot draw a glyph over 256 x 256 texels, so such a picture is shown as tiles (see tiles())
 * </pre>
 * The background is shown through the title: every menu with a background gets one character of the
 * generated font "stsv:menus" (U+E000 + n, in id order), so authors only draw a PNG.
 */
public record MenuSpec(String id, String title, int rows, NamespacedKey background, List<String> layout, char list,
                       Map<Character, Button> buttons, String filler, char glyph, Frame frame) {

    /** How far a background reaches beyond the 176 px panel, and its texels per screen pixel. */
    public record Frame(int left, int right, int top, int scale) {
        public static final Frame NONE = new Frame(0, 0, 0, 1);

        public boolean none() {
            return equals(NONE);
        }
    }

    /** The background picture's size in texels. */
    public int[] pictureSize() {
        return new int[]{(WIDTH + frame.left() + frame.right()) * frame.scale(), (height() + frame.top()) * frame.scale()};
    }

    /**
     * The client keeps glyphs on 256 x 256 textures: a larger picture is not drawn at all (the missing-glyph box
     * instead). A framed picture is therefore shown as a grid of tiles, each a glyph of at most 256 x 256 texels,
     * put side by side and row under row. {columns, rows}.
     */
    public static final int GLYPH_MAX = 256;

    public int[] tiles() {
        int[] size = pictureSize();
        return new int[]{(size[0] + GLYPH_MAX - 1) / GLYPH_MAX, (size[1] + GLYPH_MAX - 1) / GLYPH_MAX};
    }

    /** The glyph of one tile of a framed picture (16 a menu, from U+E800). */
    public char tile(int row, int column) {
        return (char) (0xE800 + 16 * (glyph - 0xE000) + row * tiles()[0] + column);
    }

    /** A framed menu's own shift spaces: to its picture's left edge, back to the title's x 8 after it, back to the row's start. */
    private char shiftLeft() {
        return (char) (0xF100 + 4 * (glyph - 0xE000));
    }

    private char shiftBack() {
        return (char) (0xF101 + 4 * (glyph - 0xE000));
    }

    private char shiftRow() {
        return (char) (0xF102 + 4 * (glyph - 0xE000));
    }

    /** -1: takes back the pixel the client adds after every glyph (between two tiles of a row). */
    public static final char SHIFT_ONE = '\uF803';

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

    /** Room for a title on the 176 px panel: it starts at x 8 and must end before the right edge's 8 px. */
    public static final int TITLE_ROOM = WIDTH - 16;

    /**
     * About how wide the client draws a title (MiniMessage, placeholders already filled), in pixels with the default font:
     * Hangul and other wide glyphs 9, most ASCII 6, narrow letters and punctuation 2 to 5, a space 4. Tags count 0.
     */
    public static int titleWidth(String mini) {
        String s = mini.replaceAll("<[^<>]*>", "");
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            w += switch (c) {
                case ' ' -> 4;
                case 'i', '!', '.', ',', ':', ';', '|', '\'', '·' -> 2;
                case 'l', '`' -> 3;
                case 'I', 't', '[', ']', '(', ')', '*', '"' -> 4;
                case 'f', 'k', '<', '>', '{', '}' -> 5;
                case '@', '~' -> 7;
                default -> c < 0x80 ? 6 : c < 0x1100 ? 6 : 9;
            };
        }
        return w;
    }

    /** Text placed before the title so the background image covers the whole screen. */
    public String backgroundPrefix() {
        if (background == null) return "";
        if (!frame.none()) {
            int[] t = tiles();
            StringBuilder b = new StringBuilder("<white><font:" + FONT + ">").append(shiftLeft());
            for (int r = 0; r < t[1]; r++) {
                for (int c = 0; c < t[0]; c++) b.append(tile(r, c)).append(SHIFT_ONE);
                b.append(r < t[1] - 1 ? shiftRow() : shiftBack());
            }
            return b.append("</font></white>").toString();
        }
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
                                    int[] want = m.pictureSize();
                                    int[] t = m.tiles();
                                    int wide = WIDTH + m.frame().left() + m.frame().right(), high = m.height() + m.frame().top();
                                    if (size[0] != want[0] || size[1] != want[1]) {
                                        problems.add(id + ": 배경 크기는 " + want[0] + "x" + want[1] + " 이어야 함 (지금 " + size[0] + "x" + size[1] + ")");
                                        m = m.withoutBackground();
                                    } else if (!m.frame().none() && (wide % t[0] != 0 || high % t[1] != 0 || t[0] * t[1] > 16)) {
                                        // tiles of whole pixels: (176 + left + right) by the columns, (height + top) by the rows
                                        problems.add(id + ": frame 의 배경은 가로 " + t[0] + "조각 · 세로 " + t[1] + "조각으로 나뉘어야 함 (화면 "
                                                + wide + "x" + high + " 픽셀이 조각 수로 나누어떨어지지 않거나 조각이 16개를 넘음)");
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
        for (MenuSpec m : menus) {
            if (m.background() == null || m.frame().none()) continue;
            // the picture's left edge is frame.left px left of the panel; after it (its width + 1) back to x 8
            adv.addProperty(String.valueOf(m.shiftLeft()), -(8 + m.frame().left()));
            adv.addProperty(String.valueOf(m.shiftBack()), -(WIDTH + m.frame().right() - 8));
            adv.addProperty(String.valueOf(m.shiftRow()), -(WIDTH + m.frame().left() + m.frame().right()));
            adv.addProperty(String.valueOf(SHIFT_ONE), -1);
        }
        space.add("advances", adv);
        providers.add(space);
        for (MenuSpec m : menus) {
            if (m.background() == null || m.frame().none()) continue;
            // a framed picture: one provider a row of tiles (a provider has one ascent), the other rows' cells skipped
            int[] t = m.tiles();
            int tileHeight = (m.height() + m.frame().top()) / t[1];
            for (int r = 0; r < t[1]; r++) {
                JsonObject bmp = new JsonObject();
                bmp.addProperty("type", "bitmap");
                bmp.addProperty("file", m.background().getNamespace() + ":" + m.background().getKey() + ".png");
                bmp.addProperty("ascent", 13 + m.frame().top() - r * tileHeight);
                bmp.addProperty("height", tileHeight);
                JsonArray chars = new JsonArray();
                for (int row = 0; row < t[1]; row++) {
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < t[0]; c++) line.append(row == r ? m.tile(r, c) : '\u0000');
                    chars.add(line.toString());
                }
                bmp.add("chars", chars);
                providers.add(bmp);
            }
        }
        for (MenuSpec m : menus) {
            if (m.background() == null || !m.frame().none()) continue;
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
        Frame frame = Frame.NONE;
        ConfigurationSection f = c.getConfigurationSection("frame");
        if (f != null) {
            frame = new Frame(f.getInt("left"), f.getInt("right"), f.getInt("top"), f.getInt("scale", 1));
            if (frame.left() < 0 || frame.right() < 0 || frame.top() < 0 || frame.scale() < 1 || frame.scale() > 4) {
                throw new IllegalArgumentException("frame: left/right/top 는 0 이상, scale 은 1~4");
            }
        }
        return new MenuSpec(id, c.getString("title", ""), rows, background, List.copyOf(layout),
                list.isEmpty() ? '\0' : list.charAt(0), buttons, c.getString("filler", "none"), glyph, frame);
    }

    private static Look look(ConfigurationSection b, Look base) {
        String icon = b.getString("icon", base == null || base.icon() == null ? null : base.icon().asString());
        return new Look(b.getString("material", base == null ? "PAPER" : base.material()),
                icon == null ? null : NamespacedKey.fromString(icon),
                b.getString("name", base == null ? " " : base.name()),
                b.contains("lore") ? b.getStringList("lore") : base == null ? List.of() : base.lore());
    }

    private MenuSpec withGlyph(char g) {
        return new MenuSpec(id, title, rows, background, layout, list, buttons, filler, g, frame);
    }

    private MenuSpec withoutBackground() {
        return new MenuSpec(id, title, rows, null, layout, list, buttons, filler, '\0', Frame.NONE);
    }
}
