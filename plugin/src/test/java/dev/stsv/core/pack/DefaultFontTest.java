package dev.stsv.core.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pack's minecraft:default (Neo둥근모's Korean behind the game's own bitmaps), checked the way the client reads it. */
class DefaultFontTest {

    /**
     * A .hex glyph as the client's unihex provider sees it: drawn 16 rows tall in the 8 px line from the line top,
     * trimmed to its inked columns, advance = trimmed width / 2 + 1 (GUI px). Rows are 0..15 (half GUI pixels).
     */
    record Glyph(int advance, int top, int bottom) {}

    static Glyph glyph(String hex) {
        int width = hex.length() / 16 * 4;
        int left = width, right = -1, top = 16, bottom = -1;
        for (int r = 0; r < 16; r++) {
            String row = hex.substring(r * width / 4, (r + 1) * width / 4);
            long bits = Long.parseLong(row, 16);
            for (int x = 0; x < width; x++) {
                if ((bits >> (width - 1 - x) & 1) == 0) continue;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, r);
                bottom = Math.max(bottom, r);
            }
        }
        assertTrue(right >= 0, "a glyph without ink");
        return new Glyph((right - left + 1) / 2 + 1, top, bottom);
    }

    static List<JsonObject> providers() {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : DefaultFont.json().getAsJsonArray("providers")) out.add(e.getAsJsonObject());
        return out;
    }

    @Test
    void gameBitmapsFirstThenNeodgmThenUnifont() {
        List<JsonObject> p = providers();
        List<String> kinds = p.stream().map(o -> o.get("type").getAsString() + ":" + (o.has("id") ? o.get("id").getAsString()
                : o.has("hex_file") ? o.get("hex_file").getAsString() : "")).toList();
        assertEquals(List.of("reference:minecraft:include/space", "reference:minecraft:include/default", "space:",
                "unihex:stsv:font/default/korean.zip", "reference:minecraft:include/unifont"), kinds);
        assertTrue(DefaultFont.files().containsKey("assets/stsv/font/default/korean.zip"), "hex_file resolves in the pack");
        // "Force Unicode Font" keeps giving the game's Unifont everywhere, as without the pack
        for (JsonObject o : p.subList(1, 4)) assertFalse(o.getAsJsonObject("filter").get("uniform").getAsBoolean(), o.toString());
        assertEquals(8, p.get(2).getAsJsonObject("advances").get("　").getAsInt(), "ideographic space");
    }

    @Test
    void glyphsFitTheLineAndTheTitleWidths() {
        Map<Integer, String> glyphs = DefaultFont.glyphs();
        for (Map.Entry<Integer, String> e : glyphs.entrySet()) {
            int cp = e.getKey();
            String id = "U+" + Integer.toHexString(cp);
            assertTrue(e.getValue().length() == 64 || e.getValue().length() == 32, id + " is 16 rows of 8 or 16 px");
            // the game's own Latin and signs stay: only Korean and CJK punctuation are ours
            assertTrue(cp >= 0x3000 && cp <= 0x303F || cp >= 0x3131 && cp <= 0x318E || cp >= 0xAC00 && cp <= 0xD7A3, id);
            Glyph g = glyph(e.getValue());
            // never wider than the menu titles' estimate (MenuSpec.titleWidth: 9, the game's Unifont Hangul)
            assertTrue(g.advance() <= MenuSpec.titleWidth(Character.toString(cp)), id + " " + g);
            if (cp >= 0xAC00) {
                // syllables from the line top down to the game's baseline (row 14 = 7 GUI px): with the half-pixel
                // shadow they stay within the 9 px of a chat or scoreboard line, like the game's own Korean
                assertTrue(g.top() >= 1 && g.bottom() <= 13, id + " " + g);
                assertTrue(g.advance() >= 7, id + " " + g);
            }
        }
        for (int cp = 0xAC00; cp <= 0xD7A3; cp++) assertTrue(glyphs.containsKey(cp), Character.toString(cp));
        for (char c : "ㅋㅠㅎㅏ「」『』【】、。".toCharArray()) assertTrue(glyphs.containsKey((int) c), String.valueOf(c));
    }

    @Test
    void clientMemoryAndLicense() {
        long bytes = DefaultFont.clientMemory();
        // about 11,240 glyphs of 32 bytes + object overhead; no picture sheets
        assertTrue(bytes > 500_000 && bytes < 1_500_000, "client memory " + bytes);
        String license = new String(DefaultFont.files().get(DefaultFont.DIR + "LICENSE-neodgm.txt"), StandardCharsets.UTF_8);
        assertTrue(license.contains("Neo둥근모") && license.contains("SIL Open Font License") && license.contains("Reserved Font Name"));
    }

    @Test
    void packCarriesTheFontAndItsLicense() throws Exception {
        ContentPack pack = new ContentPack("stsv", "stsv", Path.of(System.getProperty("stsv.content")).resolve("stsv"));
        PackBuilder.Built built = new PackBuilder().extra(DefaultFont.files()).build(List.of(pack), List.of(), 97, "test");
        assertTrue(built.warnings().isEmpty(), "warnings: " + built.warnings());
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(built.zip()))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) files.put(e.getName(), in.readAllBytes());
        }
        for (Map.Entry<String, byte[]> f : DefaultFont.files().entrySet()) {
            assertTrue(java.util.Arrays.equals(f.getValue(), files.get(f.getKey())), f.getKey());
        }
        // the HUD keeps its own fonts and sheets (stsv:hud/..., textures/font/hud/), the menus theirs (stsv:menus)
        assertTrue(DefaultFont.files().keySet().stream().noneMatch(f -> f.contains("/hud/") || f.endsWith("menus.json")));
    }
}
