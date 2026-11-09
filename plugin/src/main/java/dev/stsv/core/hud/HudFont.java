package dev.stsv.core.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;

import java.util.HashMap;
import java.util.Map;

/**
 * One generated font of the HUD (assets/stsv/font/hud/&lt;name&gt;.json) and what the layout needs to use it.
 *
 * Every font starts with a space provider: the space (4 px, as in the game's font) and the pen shifts
 * U+F800 + b (+2^b px) and U+F810 + b (-2^b px), b = 0..10. Picture fonts give each (sheet, height on
 * screen) a bitmap provider whose named cells get private-use characters from U+E000 on, in the order
 * they are added (so the same settings always make the same font). Text fonts use the real characters:
 * the game's own ascii.png referenced at a chosen height, the Hangul and symbol sheets, and, for the
 * action bar line only, the game's default font as a last resort for anything else.
 */
final class HudFont {

    static final char SHIFT = '';
    static final int SHIFT_BITS = 11;
    static final String NAMESPACE = "stsv";

    private final String name;
    private final Key key;
    private final JsonArray providers = new JsonArray();
    private final Map<String, Character> glyphs = new HashMap<>();
    private final Map<Character, Integer> advances = new HashMap<>();
    private char next = '';
    private boolean ascii, fallback;
    private HudSheets sheets;

    HudFont(String name) {
        this.name = name;
        this.key = Key.key(NAMESPACE, "hud/" + name);
        JsonObject adv = new JsonObject();
        adv.addProperty(" ", 4);
        for (int b = 0; b < SHIFT_BITS; b++) {
            adv.addProperty(String.valueOf((char) (SHIFT + b)), 1 << b);
            adv.addProperty(String.valueOf((char) (SHIFT + 16 + b)), -(1 << b));
        }
        JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        space.add("advances", adv);
        providers.add(space);
    }

    Key key() {
        return key;
    }

    /** Pack path of the font file. */
    String path() {
        return "assets/" + NAMESPACE + "/font/hud/" + name + ".json";
    }

    JsonObject json() {
        JsonObject o = new JsonObject();
        o.add("providers", providers.deepCopy());
        return o;
    }

    /** The characters that move the pen by dx (none for 0). */
    static String shift(int dx) {
        StringBuilder sb = new StringBuilder();
        int n = Math.abs(dx);
        char base = (char) (dx < 0 ? SHIFT + 16 : SHIFT);
        for (int b = SHIFT_BITS - 1; b >= 0; b--) {
            while (n >= (1 << b)) {
                sb.append((char) (base + b));
                n -= 1 << b;
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Picture glyphs
    // ------------------------------------------------------------------------------------------

    /** Adds a sheet drawn with its glyph tops at the given ascent (see HudLayout for the screen maths). */
    void add(HudSheets.Sheet sheet, int ascent) {
        String prefix = sheet.name() + "@" + ascent + ":";
        if (glyphs.containsKey(prefix + sheet.names().getFirst())) return;
        JsonArray chars = new JsonArray();
        for (int r = 0; r < sheet.rows(); r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < sheet.cols(); c++) {
                int k = r * sheet.cols() + c;
                if (k >= sheet.names().size() || sheet.names().get(k).isEmpty()) {
                    row.append('\u0000');
                    continue;
                }
                char ch = next++;
                if (ch >= SHIFT) throw new IllegalStateException("HUD font " + name + " has too many glyphs");
                glyphs.put(prefix + sheet.names().get(k), ch);
                advances.put(ch, sheet.advances()[k]);
                row.append(ch);
            }
            chars.add(row.toString());
        }
        providers.add(bitmap(NAMESPACE + ":font/hud/" + sheet.name() + ".png", ascent, sheet.height(), chars));
    }

    char glyph(String sheet, String cell, int ascent) {
        Character c = glyphs.get(sheet + "@" + ascent + ":" + cell);
        if (c == null) throw new IllegalArgumentException("HUD font " + name + " has no " + sheet + ":" + cell + " at " + ascent);
        return c;
    }

    /** Pen advance of a character of this font (pictures, shifts, the space; text characters: see {@link #textAdvance}). */
    int advance(char c) {
        Integer a = advances.get(c);
        if (a != null) return a;
        if (c == ' ') return 4;
        if (c >= SHIFT && c < SHIFT + SHIFT_BITS) return 1 << (c - SHIFT);
        if (c >= SHIFT + 16 && c < SHIFT + 16 + SHIFT_BITS) return -(1 << (c - SHIFT - 16));
        throw new IllegalArgumentException("HUD font " + name + " has no character U+" + Integer.toHexString(c));
    }

    // ------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------

    /** ASCII 0x21..0x7E as the game draws it (its own ascii.png), Hangul and symbols, all at one ascent. */
    void text(HudSheets sheets, int ascent) {
        this.sheets = sheets;
        this.ascii = true;
        JsonArray rows = new JsonArray();
        for (int r = 0; r < 16; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < 16; c++) {
                int cp = r * 16 + c;
                row.append(cp > 0x20 && cp < 0x7F ? (char) cp : '\u0000');
            }
            rows.add(row.toString());
        }
        providers.add(bitmap("minecraft:font/ascii.png", ascent, 8, rows));
        for (String s : new String[]{"hangul", "symbols"}) {
            HudSheets.Sheet sheet = sheets.sheet(s);
            JsonArray chars = new JsonArray();
            for (int r = 0; r < sheet.rows(); r++) {
                StringBuilder row = new StringBuilder();
                for (int c = 0; c < sheet.cols(); c++) {
                    int k = r * sheet.cols() + c;
                    row.append(k < sheet.names().size() ? sheet.names().get(k) : "\u0000");
                }
                chars.add(row.toString());
            }
            providers.add(bitmap(NAMESPACE + ":font/hud/" + s + ".png", ascent, sheet.height(), chars));
        }
    }

    /** Anything else falls through to the game's font (same line, so only for a font at the normal text height). */
    void fallback() {
        fallback = true;
        JsonObject ref = new JsonObject();
        ref.addProperty("type", "reference");
        ref.addProperty("id", "minecraft:default");
        providers.add(ref);
    }

    boolean hasFallback() {
        return fallback;
    }

    /** Advance of a text character in this font, -1 if the font has no glyph for it (fallback aside). */
    int textAdvance(int cp) {
        if (cp == ' ') return 4;
        if (ascii && cp > 0x20 && cp < 0x7F) return HudText.ascii(cp);
        if (sheets != null) return sheets.charAdvance(cp);
        return -1;
    }

    private static JsonObject bitmap(String file, int ascent, int height, JsonArray chars) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "bitmap");
        o.addProperty("file", file);
        o.addProperty("ascent", ascent);
        o.addProperty("height", height);
        o.add("chars", chars);
        return o;
    }
}
