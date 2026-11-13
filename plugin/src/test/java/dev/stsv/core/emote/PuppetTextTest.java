package dev.stsv.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.banner.BannerCatalog;
import dev.stsv.core.banner.BannerText;
import dev.stsv.core.emote.EmoteRig.Joint;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A face as text, the colours it may be sent in, and the pack's pixel font. */
class PuppetTextTest {

    static PuppetModel.Face face(int[][] grid, double shade) {
        return new PuppetModel.Face(Joint.HEAD, 0, PuppetModel.Side.FRONT, false, new Vector3d(), new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), grid, 1, 1, shade);
    }

    // ------------------------------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------------------------------

    /**
     * The pack's text shader (title banners) hides most glyphs whose text colour is a banner marker: red 253, green
     * and blue 252 or more. No skin pixel is ever sent in such a colour, whatever its shade.
     */
    @Test
    void noPixelIsABannerMarker() {
        String vsh = new String(BannerCatalog.resource("shader/text.vsh"), StandardCharsets.UTF_8);
        assertTrue(vsh.contains("(c.r == 253 || c.r == 252) && c.g >= 252 && c.b >= 252"), "the shader's test for a banner glyph changed: look at PuppetText.colour again");
        for (int frame = 0; frame < 16; frame++) {
            int surge = PuppetText.colour(BannerText.marker(frame, true).value(), 1.0);
            assertEquals(-1, BannerText.frameOf(TextColor.color(surge)), "frame " + frame + "'s surge marker as a skin colour");
            assertEquals(254, surge >> 16);
            int marker = BannerText.marker(frame).value();
            int sent = PuppetText.colour(marker, 1.0);
            assertEquals(-1, BannerText.frameOf(TextColor.color(sent)), "frame " + frame + "'s marker as a skin colour");
            assertEquals(254, sent >> 16, "one step redder: nobody sees the difference");
            assertEquals(marker & 0xFFFF, sent & 0xFFFF);
        }
        // every colour near white, at every shade a face has, and whatever a shade turns into a marker
        for (PuppetModel.Side side : PuppetModel.Side.values()) {
            for (int r = 240; r < 256; r++) for (int g = 240; g < 256; g++) for (int b = 240; b < 256; b++) {
                assertEquals(-1, BannerText.frameOf(TextColor.color(PuppetText.colour(r << 16 | g << 8 | b, side.shade))));
            }
        }
        for (double shade : new double[]{0.99, 0.995, 253 / 255.0, 0.9922}) {
            for (int r = 250; r < 256; r++) for (int g = 250; g < 256; g++) for (int b = 250; b < 256; b++) {
                assertEquals(-1, BannerText.frameOf(TextColor.color(PuppetText.colour(r << 16 | g << 8 | b, shade))), "shade " + shade);
            }
        }
        // everything else is sent as it is
        assertEquals(0xFFFFFF, PuppetText.colour(0xFFFFFF, 1.0));
        assertEquals(0xFDFBFF, PuppetText.colour(0xFDFBFF, 1.0), "green under 252: no marker");
        assertEquals(0xFBFFFF, PuppetText.colour(0xFBFFFF, 1.0));
        assertEquals(0x123456, PuppetText.colour(0x123456, 1.0));
        assertEquals(0, PuppetText.colour(0, 0.55));
    }

    @Test
    void shading() {
        assertEquals(0x804020, PuppetText.colour(0xFF8040, 0.5), "half as bright (rounded)");
        assertEquals(100 << 16 | 50 << 8 | 25, PuppetText.colour(200 << 16 | 100 << 8 | 50, 0.5));
        assertEquals(0xFFFFFF, PuppetText.colour(0xFFFFFF, 1.5), "never over white");
        // top brightest, bottom darkest, like the game shades its own models
        assertEquals(1.0, PuppetModel.Side.TOP.shade);
        for (PuppetModel.Side s : PuppetModel.Side.values()) {
            assertTrue(s.shade <= PuppetModel.Side.TOP.shade && s.shade >= PuppetModel.Side.BOTTOM.shade, s.name());
        }
        assertTrue(PuppetModel.Side.FRONT.shade > PuppetModel.Side.LEFT.shade && PuppetModel.Side.LEFT.shade == PuppetModel.Side.RIGHT.shade);
        assertTrue(PuppetModel.Side.BOTTOM.shade >= 0.5, "dark, not black");
    }

    // ------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------

    @Test
    void aFaceAsText() {
        int a = 0x112233, b = 0x445566, n = PuppetModel.NONE;
        List<PuppetText.Run> runs = PuppetText.runs(face(new int[][]{{a, a, b}, {b, b, n}, {n, a, a}, {a, a, a}}, 1.0));
        String p = String.valueOf(PuppetText.PIXEL), g = String.valueOf(PuppetText.GAP);
        // neighbours of one colour are one piece, also across the end of a line
        assertEquals(List.of(new PuppetText.Run(a, p + p), new PuppetText.Run(b, p + "\n" + p + p), new PuppetText.Run(n, g + "\n" + g),
                new PuppetText.Run(a, p + p + "\n" + p + p + p)), runs);

        // any face: every line has its columns, there is a line per row, and no real space or other character
        for (PuppetModel.Face f : PuppetModel.faces(PuppetSkin.of(PuppetSkinTest.coordinates(64), true))) {
            String text = String.join("", PuppetText.runs(f).stream().map(PuppetText.Run::text).toList());
            String[] lines = text.split("\n", -1);
            assertEquals(f.rows(), lines.length, f.joint() + " " + f.side());
            for (String line : lines) {
                assertEquals(f.cols(), line.length());
                assertTrue(line.chars().allMatch(c -> c == PuppetText.PIXEL || c == PuppetText.GAP));
            }
            assertTrue(f.cols() * PuppetModel.CELL < PuppetText.LINE_WIDTH, "never wraps");
            // glyph by glyph the colours are the grid's (shaded), and two pieces in a row never have one colour
            List<Integer> colours = new java.util.ArrayList<>();
            int last = Integer.MIN_VALUE;
            for (PuppetText.Run run : PuppetText.runs(f)) {
                assertTrue(run.colour() != last, "two pieces of one colour in a row");
                last = run.colour();
                for (char ch : run.text().toCharArray()) if (ch != '\n') colours.add(run.colour());
            }
            int k = 0;
            for (int[] row : f.grid()) for (int c : row) assertEquals(PuppetText.colour(c, f.shade()), colours.get(k++));
        }
        // a face of one colour is one piece
        List<PuppetModel.Face> plain = PuppetModel.faces(EmoteRigTest.plain(false));
        assertEquals(1, PuppetText.runs(plain.get(0)).size());
    }

    @Test
    void aFaceAsAComponent() {
        int a = 0xFDFCFF, n = PuppetModel.NONE;
        Component c = PuppetText.component(face(new int[][]{{a, n}, {n, a}}, 1.0));
        assertEquals(PuppetText.FONT, c.font());
        assertEquals("stsv:emote", PuppetText.FONT.asString());
        assertEquals("", ((TextComponent) c).content());
        assertEquals(3, c.children().size());
        TextComponent first = (TextComponent) c.children().get(0), gap = (TextComponent) c.children().get(1);
        assertEquals(String.valueOf(PuppetText.PIXEL), first.content());
        assertEquals(TextColor.color(0xFEFCFF), first.color(), "a marker colour, nudged");
        assertNull(first.font(), "the font is the parent's: said once");
        assertEquals(PuppetText.GAP + "\n" + PuppetText.GAP, gap.content());
        assertNull(gap.color(), "a gap needs no colour");
        // a glyph is a private-use character: nothing a client draws without the pack's font
        assertEquals(Character.PRIVATE_USE, Character.getType(PuppetText.PIXEL));
        assertEquals(Character.PRIVATE_USE, Character.getType(PuppetText.GAP));
        assertFalse(PuppetText.PIXEL == PuppetText.GAP);
    }

    /** What a puppet costs to send: the text of all its faces (rough: as JSON; the game sends NBT of about the same size, compressed). */
    @Test
    void whatAPuppetWeighs() {
        Map<String, PuppetSkin> skins = Map.of("placeholder", PuppetSkin.placeholder(), "every pixel another colour, both layers", PuppetSkin.of(PuppetSkinTest.coordinates(64), false));
        for (Map.Entry<String, PuppetSkin> en : skins.entrySet()) {
            List<PuppetModel.Face> faces = PuppetModel.faces(en.getValue());
            int bytes = 0, glyphs = 0, pieces = 0;
            for (PuppetModel.Face f : faces) {
                bytes += GsonComponentSerializer.gson().serialize(PuppetText.component(f)).getBytes(StandardCharsets.UTF_8).length;
                glyphs += f.rows() * f.cols();
                pieces += PuppetText.runs(f).size();
            }
            System.out.println("[emote] puppet (" + en.getKey() + "): " + faces.size() + " faces, " + glyphs + " glyphs in " + pieces + " pieces, " + bytes + " bytes of text");
            assertTrue(faces.size() <= 108);
            assertTrue(bytes < (en.getKey().equals("placeholder") ? 24_000 : 260_000), en.getKey() + ": " + bytes + " bytes");
        }
    }

    // ------------------------------------------------------------------------------------------
    // The pack's font
    // ------------------------------------------------------------------------------------------

    @Test
    void thePackFiles() {
        Map<String, byte[]> files = EmotePack.files();
        assertEquals(java.util.Set.of(EmotePack.FONT_JSON, EmotePack.FONT_TTF), files.keySet());
        JsonObject font = JsonParser.parseString(new String(files.get(EmotePack.FONT_JSON), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray providers = font.getAsJsonArray("providers");
        assertEquals(2, providers.size());
        JsonObject space = null, ttf = null;
        for (JsonElement e : providers) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("type").getAsString().equals("space")) space = p;
            if (p.get("type").getAsString().equals("ttf")) ttf = p;
        }
        assertTrue(space != null && ttf != null);
        // the gap: a space of exactly a pixel glyph's advance (and no other space)
        assertEquals(1, space.getAsJsonObject("advances").size());
        assertEquals(PuppetModel.CELL, space.getAsJsonObject("advances").get(String.valueOf(PuppetText.GAP)).getAsDouble());
        // the pixel: a TrueType font read at 16 px, one font pixel a text pixel, not moved
        assertEquals("stsv:emote/pixel.ttf", ttf.get("file").getAsString());
        assertTrue(files.containsKey("assets/stsv/font/" + ttf.get("file").getAsString().split(":")[1]));
        assertEquals(16.0, ttf.get("size").getAsDouble());
        assertEquals(1.0, ttf.get("oversample").getAsDouble());
        assertEquals(0.0, ttf.getAsJsonArray("shift").get(0).getAsDouble());
        assertEquals(0.0, ttf.getAsJsonArray("shift").get(1).getAsDouble());
        assertEquals(PuppetText.FONT.asString(), "stsv:" + EmotePack.FONT_JSON.substring("assets/stsv/font/".length(), EmotePack.FONT_JSON.length() - ".json".length()),
                "the font PuppetText names");
    }

    /**
     * The pixel glyph fills its cell exactly: at the size the game reads it (16 px to the em) its outline is the
     * square from the pen to 10 px right of it and from 7 px over the baseline to 3 px under it. The game draws a
     * TrueType glyph's top at text y 7 - (its top over the baseline) = 0 and lines 10 px apart: rows 0..9 of the
     * line, all of it, and the next glyph starts where this one ends (advance 10).
     */
    @Test
    void thePixelGlyphFillsItsCell() throws Exception {
        byte[] ttf = EmotePack.files().get(EmotePack.FONT_TTF);
        Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttf)).deriveFont(16f);
        assertTrue(font.canDisplay(PuppetText.PIXEL));
        assertFalse(font.canDisplay(PuppetText.GAP), "the gap is the space provider's");
        assertFalse(font.canDisplay(' '));
        GlyphVector one = font.createGlyphVector(new FontRenderContext(null, false, true), String.valueOf(PuppetText.PIXEL).repeat(3));
        assertEquals(PuppetModel.CELL, one.getGlyphMetrics(0).getAdvanceX(), 1e-4, "the advance: a cell, no gap");
        Rectangle2D box = one.getGlyphOutline(0).getBounds2D();
        assertEquals(0, box.getMinX(), 1e-4);
        assertEquals(PuppetModel.CELL, box.getMaxX(), 1e-4);
        assertEquals(-7, box.getMinY(), 1e-4, "7 px over the baseline");
        assertEquals(PuppetModel.CELL - 7, box.getMaxY(), 1e-4);
        // the outline is that rectangle and nothing else, and the third glyph starts two cells on
        assertEquals(PuppetModel.CELL * PuppetModel.CELL, area(one.getGlyphOutline(0)), 1e-3);
        assertEquals(2 * PuppetModel.CELL, one.getGlyphOutline(2).getBounds2D().getMinX(), 1e-4);

        // its own hinting program is long enough that FreeType does not auto-hint it (2.14 does for a prep of 7 bytes or
        // less in a font without fpgm, and then draws an eleventh, half-transparent row): tools/assetgen/emotes.py
        int tables = (ttf[4] & 0xFF) << 8 | ttf[5] & 0xFF, prep = -1;
        for (int i = 0; i < tables; i++) {
            int at = 12 + i * 16;
            if (new String(ttf, at, 4, StandardCharsets.US_ASCII).equals("prep")) {
                prep = (ttf[at + 12] & 0xFF) << 24 | (ttf[at + 13] & 0xFF) << 16 | (ttf[at + 14] & 0xFF) << 8 | ttf[at + 15] & 0xFF;
            }
        }
        assertTrue(prep > 7, "prep table of " + prep + " bytes");
    }

    private static double area(java.awt.Shape shape) {
        // the outline is straight lines: the shoelace formula over its points
        java.awt.geom.PathIterator it = shape.getPathIterator(null);
        double[] c = new double[6];
        double area = 0, x0 = 0, y0 = 0, px = 0, py = 0;
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            if (type == java.awt.geom.PathIterator.SEG_MOVETO) {
                x0 = px = c[0];
                y0 = py = c[1];
            } else if (type == java.awt.geom.PathIterator.SEG_LINETO) {
                area += px * c[1] - c[0] * py;
                px = c[0];
                py = c[1];
            } else if (type == java.awt.geom.PathIterator.SEG_CLOSE) {
                area += px * y0 - x0 * py;
                px = x0;
                py = y0;
            } else {
                throw new AssertionError("a curve in the pixel glyph");
            }
            it.next();
        }
        return Math.abs(area) / 2;
    }
}
