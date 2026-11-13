package dev.stsv.core.banner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerPackTest {

    static final BannerCatalog C = BannerCatalog.get();

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void shaderIsShippedOnlyInShaderMode() {
        Map<String, byte[]> shader = BannerPack.files(C, BannerPack.Animation.SHADER);
        assertTrue(shader.containsKey(BannerPack.VSH) && shader.containsKey(BannerPack.FSH));
        assertFalse(BannerPack.files(C, BannerPack.Animation.STATIC).containsKey(BannerPack.VSH));
        assertFalse(BannerPack.files(C, BannerPack.Animation.SERVER).containsKey(BannerPack.FSH));
        assertTrue(shader.keySet().containsAll(C.files()));
        assertEquals(BannerPack.Animation.SHADER, BannerPack.Animation.parse(null));
        assertEquals(BannerPack.Animation.SERVER, BannerPack.Animation.parse("server"));
        assertEquals(BannerPack.Animation.STATIC, BannerPack.Animation.parse("static"));
    }

    /** The shader is the game's 26.3 core/text with the banner part; its numbers agree with the plugin's. */
    @Test
    void shaderSanity() {
        String vsh = text(BannerCatalog.resource("shader/text.vsh"));
        String fsh = text(BannerCatalog.resource("shader/text.fsh"));
        for (String s : new String[]{vsh, fsh}) {
            assertTrue(s.startsWith("#version 330"));
            assertTrue(s.contains("#include <minecraft:dynamictransforms.glsl>"));
            assertTrue(s.contains("IS_GUI") && s.contains("IS_SEE_THROUGH"));
            assertTrue(s.contains("layout(location = 4) flat"), "the frame varying, same location in both stages");
            assertFalse(s.contains("#moj_import"), "26.3 uses #include");
            int open = s.length() - s.replace("{", "").length(), close = s.length() - s.replace("}", "").length();
            assertEquals(open, close, "braces balance");
            assertEquals(count(s, "#if"), count(s, "#endif"), "every #if ends");
        }
        assertTrue(vsh.contains("c.r == " + BannerText.MARK_RED), "the marker red of BannerText");
        assertTrue(vsh.contains("252"));
        assertTrue(fsh.contains("#include <minecraft:globals.glsl>") && fsh.contains("GameTime"));
        assertTrue(fsh.contains("#include <minecraft:oit.glsl>") && fsh.contains("OIT_ALPHA_ONLY"), "the game's OIT paths kept");
        assertTrue(fsh.contains("IS_GRAYSCALE"));
        assertTrue(fsh.contains("#define STSV_BANNER_FRAMES " + C.frames() + ".0"));
        assertTrue(fsh.contains("#define STSV_BANNER_GHOST_LEVELS " + C.ghostLevels() + ".0"));
        assertTrue(fsh.contains("#define STSV_BANNER_FRAME_MS 70.0"));
        assertTrue(fsh.contains("color.a < 0.1"), "the game's cut-off, which hides the ghost frames without the shader");
        assertTrue(C.ghostLevels() * 1.0 / 255 < 0.1);
    }

    static int count(String s, String what) {
        int n = 0;
        for (String line : s.split("\n")) if (line.strip().startsWith(what) && (what.equals("#endif") || !line.strip().startsWith("#endif"))) n++;
        return n;
    }

    /** Fonts the client will accept: files exist, ascent within height, equal cells, no code point twice. */
    @Test
    void fontsAreValid() throws Exception {
        Map<String, byte[]> files = BannerPack.files(C, BannerPack.Animation.SHADER);
        int fonts = 0;
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (!f.getKey().startsWith("assets/stsv/font/") || !f.getKey().endsWith(".json")) continue;
            fonts++;
            Set<Integer> seen = new HashSet<>();
            JsonObject font = JsonParser.parseString(text(f.getValue())).getAsJsonObject();
            for (JsonElement e : font.getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                if (p.get("type").getAsString().equals("ttf")) {
                    // letters and gem: TrueType (the game's grayscale text pipeline draws them over the plate)
                    String ttf = "assets/stsv/font/" + p.get("file").getAsString().split(":", 2)[1];
                    assertTrue(files.containsKey(ttf), ttf);
                    java.awt.Font tf = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new ByteArrayInputStream(files.get(ttf)));
                    assertTrue(tf.canDisplay('A') || tf.canDisplay('가') || tf.canDisplay('a'), ttf);
                    float over = p.get("oversample").getAsFloat(), size = p.get("size").getAsFloat();
                    assertEquals(16, Math.round(size * over), "one font pixel a texel: 16 px a em");
                    continue;
                }
                if (!p.get("type").getAsString().equals("bitmap")) continue;
                int h = p.get("height").getAsInt(), asc = p.get("ascent").getAsInt();
                assertTrue(asc <= h, f.getKey() + ": ascent " + asc + " > height " + h);
                String png = "assets/stsv/textures/" + p.get("file").getAsString().split(":", 2)[1];
                assertTrue(files.containsKey(png), png);
                BufferedImage im = ImageIO.read(new ByteArrayInputStream(files.get(png)));
                var rows = p.getAsJsonArray("chars");
                int cols = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
                assertEquals(0, im.getHeight() % rows.size());
                assertEquals(0, im.getWidth() % cols);
                for (JsonElement r : rows) {
                    assertEquals(cols, r.getAsString().codePointCount(0, r.getAsString().length()), "equal rows");
                    r.getAsString().codePoints().filter(cp -> cp != 0).forEach(cp -> assertTrue(seen.add(cp), f.getKey() + " U+" + Integer.toHexString(cp)));
                }
                // RGBA: the game reads every PNG as RGBA and measures a glyph by its alpha
                assertEquals(4, im.getColorModel().getNumComponents(), png);
            }
        }
        // per theme: its plate at both sizes, and its chat banner's when it has one; the letters: title and nick, three sizes
        long chats = C.themes().values().stream().filter(t -> t.chat() != null).count();
        assertEquals(C.themes().size() * 2 + chats + 6, fonts);
    }

    @Test
    void ghostFramesHideWithoutTheShaderAndComeBackForTheServer() throws Exception {
        String ghost = C.ghostFiles().getFirst();
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(BannerCatalog.resource(ghost)));
        BufferedImage served = ImageIO.read(new ByteArrayInputStream(BannerPack.files(C, BannerPack.Animation.SERVER).get(ghost)));
        int cell = stored.getHeight() / C.frames();
        int maxStored = 0, maxServed = 0, frame0 = 0;
        for (int y = 0; y < stored.getHeight(); y++) {
            for (int x = 0; x < stored.getWidth(); x++) {
                int a = stored.getRGB(x, y) >>> 24, b = served.getRGB(x, y) >>> 24;
                if (y < cell) {
                    frame0 = Math.max(frame0, a);
                    assertEquals(a, b, "frame 0 is untouched");
                } else {
                    maxStored = Math.max(maxStored, a);
                    maxServed = Math.max(maxServed, b);
                }
            }
        }
        assertTrue(frame0 > 200);
        assertTrue(maxStored <= C.ghostLevels(), "frames 1-15 under the game's cut-off");
        assertTrue(maxServed > 200, "given back for the server-side animation");
    }
}
