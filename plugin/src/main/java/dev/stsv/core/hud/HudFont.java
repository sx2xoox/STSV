package dev.stsv.core.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;

import java.util.HashMap;
import java.util.Map;

/**
 * One generated font of the HUD (assets/stsv/font/hud/&lt;name&gt;.json) and what the layout needs to use it.
 *
 * Every font starts with a space provider: the space and the pen shifts U+F800 + b (+2^b px) and U+F810 + b
 * (-2^b px), b = 0..10. Picture fonts give each (sheet, height on screen, scale) a bitmap provider whose named
 * cells get private-use characters from U+E000 on, in the order they are added (so the same settings always make
 * the same font). Text fonts use the real characters of one of the HUD's letter sheet pairs (Galmuri9 or
 * Galmuri7, ASCII and signs + Hangul) at a chosen height and scale, and, for the action bar line only, the game's
 * default font as a last resort for anything else.
 *
 * Every bitmap provider keeps its whole sheet in the client's memory (RGBA), once per provider, whatever its
 * scale: a full Hangul sheet costs about 0.8 MB, the panel's smaller ones about a third of that, a picture a few kB.
 */
final class HudFont {

    static final char SHIFT = '\uF800';
    static final int SHIFT_BITS = 11;
    static final String NAMESPACE = "stsv";

    private final String name;
    private final Key key;
    private final JsonArray providers = new JsonArray();
    private final JsonObject spaces = new JsonObject();
    private final Map<String, Character> glyphs = new HashMap<>();
    private final Map<Character, Integer> advances = new HashMap<>();
    private final Map<Integer, Integer> letters = new HashMap<>();
    private char next = '\uE000';
    private boolean fallback;
    private int space = 4;

    HudFont(String name) {
        this.name = name;
        this.key = Key.key(NAMESPACE, "hud/" + name);
        spaces.addProperty(" ", space);
        for (int b = 0; b < SHIFT_BITS; b++) {
            spaces.addProperty(String.valueOf((char) (SHIFT + b)), 1 << b);
            spaces.addProperty(String.valueOf((char) (SHIFT + 16 + b)), -(1 << b));
        }
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "space");
        provider.add("advances", spaces);
        providers.add(provider);
    }

    Key key() {
        return key;
    }

    String name() {
        return name;
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
        add(sheet, ascent, 1);
    }

    /** Adds a sheet drawn `scale` times its own size, its glyph tops at the given ascent. */
    void add(HudSheets.Sheet sheet, int ascent, int scale) {
        String prefix = prefix(sheet.name(), ascent, scale);
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
                advances.put(ch, sheet.advanceAt(k, scale));
                row.append(ch);
            }
            chars.add(row.toString());
        }
        providers.add(bitmap(NAMESPACE + ":font/hud/" + sheet.name() + ".png", ascent, sheet.height() * scale, chars));
    }

    private static String prefix(String sheet, int ascent, int scale) {
        return sheet + "@" + ascent + "x" + scale + ":";
    }

    char glyph(String sheet, String cell, int ascent) {
        return glyph(sheet, cell, ascent, 1);
    }

    char glyph(String sheet, String cell, int ascent, int scale) {
        Character c = glyphs.get(prefix(sheet, ascent, scale) + cell);
        if (c == null) {
            throw new IllegalArgumentException("HUD font " + name + " has no " + sheet + ":" + cell + " at " + ascent + " x" + scale);
        }
        return c;
    }

    /** Pen advance of a character of this font (pictures, shifts, the space; text characters: see {@link #textAdvance}). */
    int advance(char c) {
        Integer a = advances.get(c);
        if (a != null) return a;
        if (c == ' ') return space;
        if (c >= SHIFT && c < SHIFT + SHIFT_BITS) return 1 << (c - SHIFT);
        if (c >= SHIFT + 16 && c < SHIFT + 16 + SHIFT_BITS) return -(1 << (c - SHIFT - 16));
        throw new IllegalArgumentException("HUD font " + name + " has no character U+" + Integer.toHexString(c));
    }

    // ------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------

    /** Galmuri9's letters (sheets text and hangul) at their own size, see {@link #text(HudSheets, String, String, int, int)}. */
    void text(HudSheets sheets, int ascent) {
        text(sheets, "text", "hangul", ascent, 1);
    }

    /**
     * The HUD's letters (a sheet of ASCII, signs and jamo and a sheet of Hangul syllables, one font pixel per GUI
     * pixel times `scale`) with their cell tops at one ascent. Galmuri9's cells start 9 px above the baseline, so
     * ascent 9 puts the baseline where the game's own text has it. The space is the letters' own, times scale.
     */
    void text(HudSheets sheets, String textSheet, String hangulSheet, int ascent, int scale) {
        HudSheets.Sheet first = sheets.sheet(textSheet);
        if (first.space() > 0) {
            space = first.space() * scale;
            spaces.addProperty(" ", space);
        }
        for (String s : new String[]{textSheet, hangulSheet}) {
            HudSheets.Sheet sheet = sheets.sheet(s);
            JsonArray chars = new JsonArray();
            for (int r = 0; r < sheet.rows(); r++) {
                StringBuilder row = new StringBuilder();
                for (int c = 0; c < sheet.cols(); c++) {
                    int k = r * sheet.cols() + c;
                    if (k < sheet.names().size()) {
                        row.append(sheet.names().get(k));
                        letters.putIfAbsent(sheet.names().get(k).codePointAt(0), sheet.advanceAt(k, scale));
                    } else {
                        row.append('\u0000');
                    }
                }
                chars.add(row.toString());
            }
            providers.add(bitmap(NAMESPACE + ":font/hud/" + s + ".png", ascent, sheet.height() * scale, chars));
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
        if (cp == ' ') return space;
        Integer a = letters.get(cp);
        return a == null ? -1 : a;
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
