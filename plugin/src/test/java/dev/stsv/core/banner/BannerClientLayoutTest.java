package dev.stsv.core.banner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's text layout (Minecraft 26.3), played on the banner components with the pack's own files: a bitmap
 * glyph is a cell of its PNG read as RGBA, scale = height / cell height, advance (int) (0.5 + width * scale) + 1 with
 * width = its rightmost column with any alpha + 1, drawn from 7 - ascent; a TrueType glyph is rendered at
 * round(size * oversample) px per em, advance and box / oversample, drawn from 7 - top / oversample + shift; a space
 * glyph moves the pen. Checks that every glyph lands exactly where the layout puts it, that the pen ends right after
 * the banner, that the small size stays inside the top 8 GUI px of its 9 px line, and that a chat banner keeps to
 * its two chat lines (nothing under its line, its letters at full size on the plate). Also writes the components for
 * tools/assetgen/banner_render.py (the previews) to build/banner-preview/components.json.
 */
class BannerClientLayoutTest {

    static final BannerCatalog C = BannerCatalog.get();
    static final List<String[]> WORDS = List.of(new String[]{"거상", "예주니0"}, new String[]{"거상", "상원"}, new String[]{"", "xozne"},
            new String[]{"최고 부자", "xozne"}, new String[]{"명장 대장장이", "Steve_2024"}, new String[]{"단골", "홍길동"},
            new String[]{"아주 아주 긴 칭호 이름", "Long_Name_12345"}, new String[]{"", "a"});

    /** One glyph as the client sees it: advance, the box it draws (GUI px, y from the text's top), its drawn rows. */
    record ClientGlyph(double advance, double left, double top, double bottom, boolean gray, double inkTop, double inkBottom) {
        ClientGlyph(double advance, double left, double top, double bottom, boolean gray) {
            this(advance, left, top, bottom, gray, top, bottom);
        }
    }

    /** The pack's fonts as the client builds them (providers in order, the first that has a code point wins). */
    static final class Client {
        final Map<String, List<JsonObject>> fonts = new HashMap<>();
        final Map<String, byte[]> files;
        final Map<String, BufferedImage> images = new HashMap<>();
        final Map<String, Font> ttfs = new HashMap<>();

        Client() {
            files = BannerPack.files(C, BannerPack.Animation.SHADER);
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                if (!f.getKey().startsWith("assets/stsv/font/title_banner/") || !f.getKey().endsWith(".json")) continue;
                String name = f.getKey().substring("assets/stsv/font/".length(), f.getKey().length() - 5);
                List<JsonObject> providers = new ArrayList<>();
                JsonObject font = JsonParser.parseString(new String(f.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (JsonElement e : font.getAsJsonArray("providers")) providers.add(e.getAsJsonObject());
                fonts.put("stsv:" + name, providers);
            }
        }

        ClientGlyph glyph(String font, int cp) throws Exception {
            List<JsonObject> providers = fonts.get(font);
            assertNotNull(providers, font);
            for (JsonObject p : providers) {
                switch (p.get("type").getAsString()) {
                    case "space" -> {
                        JsonElement a = p.getAsJsonObject("advances").get(new String(Character.toChars(cp)));
                        if (a != null) return new ClientGlyph(a.getAsDouble(), 0, 0, 0, false);
                    }
                    case "bitmap" -> {
                        JsonArray rows = p.getAsJsonArray("chars");
                        for (int r = 0; r < rows.size(); r++) {
                            int[] row = rows.get(r).getAsString().codePoints().toArray();
                            for (int k = 0; k < row.length; k++) {
                                if (row[k] != cp) continue;
                                BufferedImage im = image("assets/stsv/textures/" + p.get("file").getAsString().split(":", 2)[1]);
                                int cw = im.getWidth() / row.length, ch = im.getHeight() / rows.size();
                                float scale = p.get("height").getAsInt() / (float) ch;
                                int width = 0;
                                for (int x = cw - 1; x >= 0 && width == 0; x--) {
                                    for (int y = 0; y < ch; y++) {
                                        if ((im.getRGB(k * cw + x, r * ch + y) >>> 24) != 0) {
                                            width = x + 1;
                                            break;
                                        }
                                    }
                                }
                                double top = 7 - p.get("ascent").getAsInt();
                                int r0 = ch, r1 = -1;
                                for (int y = 0; y < ch; y++) {
                                    for (int x = 0; x < cw; x++) {
                                        if ((im.getRGB(k * cw + x, r * ch + y) >>> 24) != 0) {
                                            r0 = Math.min(r0, y);
                                            r1 = y;
                                            break;
                                        }
                                    }
                                }
                                return new ClientGlyph((int) (0.5 + width * scale) + 1, 0, top, top + ch * scale, false,
                                        top + r0 * scale, top + (r1 + 1) * scale);
                            }
                        }
                    }
                    case "ttf" -> {
                        Font f = ttf(p.get("file").getAsString());
                        if (!f.canDisplay(cp)) continue;
                        float size = p.get("size").getAsFloat(), over = p.get("oversample").getAsFloat();
                        int ppem = Math.round(size * over);
                        Font at = f.deriveFont((float) ppem);
                        GlyphVector gv = at.createGlyphVector(new FontRenderContext(null, false, false), new String(Character.toChars(cp)));
                        double adv = gv.getGlyphMetrics(0).getAdvanceX();
                        assertEquals(Math.rint(adv), adv, 1e-9, "a pixel font's advance is whole pixels");
                        Rectangle2D b = gv.getGlyphOutline(0).getBounds2D();
                        double shiftY = p.getAsJsonArray("shift").get(1).getAsDouble();
                        double top = 7 + b.getMinY() / over + shiftY, bottom = 7 + b.getMaxY() / over + shiftY;
                        return new ClientGlyph(adv / over, b.getMinX() / over, top, bottom, true);
                    }
                    default -> throw new AssertionError(p);
                }
            }
            throw new AssertionError("no glyph U+" + Integer.toHexString(cp) + " in " + font);
        }

        BufferedImage image(String path) throws Exception {
            BufferedImage im = images.get(path);
            if (im == null) {
                assertTrue(files.containsKey(path), path);
                BufferedImage raw = ImageIO.read(new ByteArrayInputStream(files.get(path)));
                im = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < raw.getHeight(); y++) {
                    for (int x = 0; x < raw.getWidth(); x++) {
                        int v = raw.getRGB(x, y);
                        // as stb_image with 4 channels: a PNG without alpha is opaque
                        im.setRGB(x, y, raw.getColorModel().hasAlpha() ? v : v | 0xFF000000);
                    }
                }
                images.put(path, im);
            }
            return im;
        }

        Font ttf(String id) throws Exception {
            Font f = ttfs.get(id);
            if (f == null) {
                String path = "assets/stsv/font/" + id.split(":", 2)[1];
                assertTrue(files.containsKey(path), path);
                f = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(files.get(path)));
                ttfs.put(id, f);
            }
            return f;
        }
    }

    static List<TextComponent> parts(Component c) {
        return BannerTextTest.parts(c);
    }

    @Test
    void theClientDrawsEveryGlyphWhereTheLayoutPutsIt() throws Exception {
        System.setProperty("java.awt.headless", "true");
        Client client = new Client();
        for (BannerCatalog.Theme theme : C.themes().values()) {
            for (List<String[]> words : List.of(WORDS, BannerLayoutTest.WORDS)) {
                for (String[] w : words) {
                    for (BannerText.Size size : BannerText.Size.values()) {
                        BannerCatalog.Theme t = BannerLayoutTest.of(theme, size);
                        if (t == null) continue;
                        BannerLayout l = BannerLayout.of(C, t, w[0], w[1]);
                        for (int frame : new int[]{BannerText.ALL_FRAMES, 0, 9}) {
                            List<BannerText.Placed> placed = BannerText.place(C, l, size, frame);
                            double pen = 0, top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE, left = Double.MAX_VALUE;
                            int k = 0;
                            String what = t.id() + " " + size + " '" + w[0] + "' '" + w[1] + "' frame " + frame;
                            for (TextComponent part : parts(BannerText.component(C, l, size, frame))) {
                                for (int cp : part.content().codePoints().toArray()) {
                                    ClientGlyph g = client.glyph(part.font().asString(), cp);
                                    if (BannerText.spaceAdvance(C, cp) == BannerText.spaceAdvance(C, cp)) {
                                        pen += g.advance();
                                        continue;
                                    }
                                    BannerText.Placed p = placed.get(k++);
                                    assertEquals(p.codePoint(), cp, what);
                                    assertEquals(p.x(), pen, 1e-9, what + ": glyph " + k + " U+" + Integer.toHexString(cp));
                                    assertEquals(p.advance(), g.advance(), 1e-9, what + ": advance of U+" + Integer.toHexString(cp));
                                    // the plate is coloured (bitmap), letters and gem grayscale (TrueType): drawn over it
                                    assertEquals(!part.font().value().startsWith("title_banner/" + t.id()), g.gray(), what);
                                    top = Math.min(top, g.top());
                                    bottom = Math.max(bottom, g.bottom());
                                    if (size == BannerText.Size.TALL && g.inkBottom() > g.inkTop()) {
                                        // the second of two chat lines: its text's top is y 0, the line is [-1, 8), the
                                        // empty line over it [-10, -1); one more row may hold tips of the art
                                        assertTrue(g.inkTop() >= -11 && g.inkBottom() <= 8, what + ": rows " + g.inkTop() + ".." + g.inkBottom());
                                        // letters and gem on the plate's inside (rows -5 .. 6: 15 rows less edge and bevel)
                                        if (g.gray()) assertTrue(g.inkTop() >= -5 && g.inkBottom() <= 6, what + ": letter rows " + g.inkTop() + ".." + g.inkBottom());
                                    }
                                    left = Math.min(left, pen + g.left());
                                    pen += g.advance();
                                }
                            }
                            assertEquals(placed.size(), k, what);
                            assertEquals(BannerText.end(l, size), pen, 1e-9, what + ": the pen ends after the banner");
                            assertTrue(left >= -BannerText.lead(t, size) * size.scale() - 1e-9, what);
                            if (size == BannerText.Size.MINI) {
                                // a chat line is [-1, 8) around its text's top, a tab row [0, 8): keep to [0, 8)
                                assertTrue(top >= 0 && bottom <= 8, what + ": rows " + top + ".." + bottom);
                            }
                        }
                    }
                }
            }
        }
    }

    /** The trimmed banner starts with a drawn column and ends with one (no gap before or after it). */
    @Test
    void noEmptyColumnsAroundTheBanner() throws Exception {
        Client client = new Client();
        for (BannerCatalog.Theme theme : C.themes().values()) {
            for (BannerText.Size size : BannerText.Size.values()) {
                BannerCatalog.Theme t = BannerLayoutTest.of(theme, size);
                if (t == null) continue;
                BannerCatalog.Glyph L = t.glyph("L");
                int lead = BannerText.lead(t, size);
                boolean any = false;
                for (int f = 0; f < L.chars().length; f++) {
                    String font = "stsv:title_banner/" + t.id() + size.suffix;
                    any |= column(client, font, L.chars()[f], lead);
                    for (int x = 0; x < lead; x++) assertTrue(!column(client, font, L.chars()[f], x), t.id() + " " + size + " column " + x);
                }
                assertTrue(any, t.id() + " " + size + ": the first kept column is drawn in some frame");
            }
        }
    }

    /** Whether column x (texels) of a plate glyph has any alpha. */
    static boolean column(Client client, String font, int cp, int x) throws Exception {
        for (JsonObject p : client.fonts.get(font)) {
            if (!p.get("type").getAsString().equals("bitmap")) continue;
            JsonArray rows = p.getAsJsonArray("chars");
            for (int r = 0; r < rows.size(); r++) {
                int[] row = rows.get(r).getAsString().codePoints().toArray();
                for (int k = 0; k < row.length; k++) {
                    if (row[k] != cp) continue;
                    BufferedImage im = client.image("assets/stsv/textures/" + p.get("file").getAsString().split(":", 2)[1]);
                    int cw = im.getWidth() / row.length, ch = im.getHeight() / rows.size();
                    for (int y = 0; y < ch; y++) if ((im.getRGB(k * cw + x, r * ch + y) >>> 24) != 0) return true;
                    return false;
                }
            }
        }
        throw new AssertionError(font + " U+" + Integer.toHexString(cp));
    }

    /** The components of the previews (tools/assetgen/banner_render.py reads them). */
    @Test
    void writeComponentsForThePreviews() throws Exception {
        JsonArray out = new JsonArray();
        for (BannerCatalog.Theme theme : C.themes().values()) {
            for (String[] w : WORDS) {
                for (BannerText.Size size : BannerText.Size.values()) {
                    BannerCatalog.Theme t = BannerLayoutTest.of(theme, size);
                    if (t == null) continue;
                    BannerLayout l = BannerLayout.of(C, t, w[0], w[1]);
                    JsonObject o = new JsonObject();
                    o.addProperty("theme", t.id());
                    o.addProperty("title", w[0]);
                    o.addProperty("nick", w[1]);
                    o.addProperty("size", switch (size) {
                        case BIG -> "big";
                        case MINI -> "chat";
                        case TALL -> "tall";
                    });
                    o.addProperty("width", BannerText.end(l, size));
                    JsonArray runs = new JsonArray();
                    for (TextComponent part : parts(BannerText.component(C, l, size, BannerText.ALL_FRAMES))) {
                        JsonObject r = new JsonObject();
                        r.addProperty("font", part.font().asString());
                        r.addProperty("color", part.color().value());
                        r.addProperty("shadow", !ShadowColor.none().equals(part.shadowColor()));
                        r.addProperty("text", part.content());
                        runs.add(r);
                    }
                    o.add("runs", runs);
                    out.add(o);
                }
            }
        }
        Path dir = Path.of("build", "banner-preview");
        Files.createDirectories(dir);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        Files.writeString(dir.resolve("components.json"), gson.toJson(out), StandardCharsets.UTF_8);
    }
}
