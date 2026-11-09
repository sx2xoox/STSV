package dev.stsv.core.pack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The game's own text font (minecraft:default: chat, item names and lore, menu titles, signs, the scoreboard) with
 * its Korean drawn from Neo둥근모, a 16 px Korean pixel font (SIL OFL 1.1), made by tools/assetgen/default_font.py.
 *
 * The game draws Hangul from Unifont with a "unihex" provider: 16 px glyphs in its 8 px line, shadow and bold half
 * a GUI pixel to the side. The pack's font adds one more such provider with Neo둥근모's glyphs (every Hangul
 * syllable, the compatibility jamo, CJK symbols and punctuation), so Korean is drawn exactly the way the game draws
 * its own, in a clearer design. Everything the game has its own 8 px picture for (Latin, digits, punctuation,
 * · × → ★ ✔ ❤ ...) keeps the Minecraft look and width: the game's bitmaps come first, then Neo둥근모, then
 * Unifont. The game's "Force Unicode Font" option still gives Unifont everywhere. Fonts of the pack that reference
 * minecraft:default (the HUD's action-bar line) see the same font behind their own letters.
 */
public final class DefaultFont {

    static final String ROOT = "/pack/default_font/";
    static final String DIR = "assets/stsv/font/default/";
    public static final String FONT = "assets/minecraft/font/default.json";

    private DefaultFont() {}

    /** Every file of the font (pack path -> bytes): the font, its glyphs (a zip of .hex), the Neo둥근모 license. */
    public static Map<String, byte[]> files() {
        Map<String, byte[]> out = new TreeMap<>();
        out.put(FONT, resource("default.json"));
        for (String f : new String[]{"korean.zip", "LICENSE-neodgm.txt"}) out.put(DIR + f, resource(f));
        return out;
    }

    static JsonObject json() {
        return JsonParser.parseString(new String(resource("default.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** The glyphs of the .hex files: code point -> its rows as in the file (16 rows, 2 hex digits per 8 px). */
    static Map<Integer, String> glyphs() {
        Map<Integer, String> out = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(resource("korean.zip")))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.getName().endsWith(".hex")) continue;
                for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                    if (line.isBlank()) continue;
                    int colon = line.indexOf(':');
                    out.put(Integer.parseInt(line.substring(0, colon), 16), line.substring(colon + 1).trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * About what the glyphs hold in the client's memory, in bytes: the game keeps each .hex glyph as its bit rows
     * (16 rows of 1 to 4 bytes) in a small object, about 64 bytes on top. Nothing is kept as a picture (glyphs drawn
     * are copied into the shared font atlas, like the game's own Korean).
     */
    public static long clientMemory() {
        long bytes = 0;
        for (String rows : glyphs().values()) bytes += rows.length() / 2 + 64;
        return bytes;
    }

    static byte[] resource(String name) {
        try (InputStream in = DefaultFont.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IOException(ROOT + name + " 없음");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
