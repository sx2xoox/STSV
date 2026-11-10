package dev.stsv.core.nickname;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

/**
 * A nickname's colour: one RGB colour, or a gradient of two across the letters. Stored apart from the name
 * ("#ffb7c5" or "#ffb7c5>#7fd8ff"), so the name itself never carries codes or tags. No Bukkit types (unit-tested).
 *
 * <p>Readability: chat, the tab list, item tooltips and the name tag all draw on a dark background, so a colour
 * must be bright enough there. Brightness is the HSP perceived brightness (sqrt(.299 R² + .587 G² + .114 B²),
 * 0..1): pure blue (0.34), dark grey (#555555: 0.33) and anything darker are refused at the default 0.35.
 */
public record NickColor(int from, int to, boolean gradient) {

    public NickColor {
        from &= 0xFFFFFF;
        to &= 0xFFFFFF;
        if (!gradient) to = from;
    }

    public static NickColor solid(int rgb) {
        return new NickColor(rgb, rgb, false);
    }

    public static NickColor gradient(int from, int to) {
        return new NickColor(from, to, true);
    }

    /** "#RRGGBB" or "RRGGBB" (any case); -1 for anything else (legacy § / & codes, names, tags). */
    public static int parseHex(String s) {
        if (s == null) return -1;
        String t = s.strip();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return -1;
        for (int i = 0; i < 6; i++) if (Character.digit(t.charAt(i), 16) < 0) return -1;
        return Integer.parseInt(t, 16);
    }

    /** Perceived brightness 0..1 (HSP). */
    public static double brightness(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0, g = ((rgb >> 8) & 0xFF) / 255.0, b = (rgb & 0xFF) / 255.0;
        return Math.sqrt(0.299 * r * r + 0.587 * g * g + 0.114 * b * b);
    }

    /** Bright enough to read on the game's dark backgrounds. */
    public static boolean readable(int rgb, double minBrightness) {
        return brightness(rgb) >= minBrightness;
    }

    /** Both ends (a gradient's middle is between them, so it is readable too). */
    public boolean readable(double minBrightness) {
        return readable(from, minBrightness) && readable(to, minBrightness);
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    /** "#rrggbb" or "#rrggbb>#rrggbb" (database). */
    public String encode() {
        return gradient ? hex(from) + ">" + hex(to) : hex(from);
    }

    /** {@link #encode} back; null for null, blank or anything broken. */
    public static NickColor decode(String s) {
        if (s == null || s.isBlank()) return null;
        int cut = s.indexOf('>');
        if (cut < 0) {
            int c = parseHex(s);
            return c < 0 ? null : solid(c);
        }
        int a = parseHex(s.substring(0, cut)), b = parseHex(s.substring(cut + 1));
        return a < 0 || b < 0 ? null : gradient(a, b);
    }

    /** The colour of letter i of n: a gradient goes from {@code from} (first letter) to {@code to} (last letter). */
    public int at(int i, int n) {
        if (!gradient || n <= 1) return from;
        return lerp(from, to, i / (double) (n - 1));
    }

    static int lerp(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * The name as MiniMessage in this colour, to put into any MiniMessage text (it overrides the colour around it).
     * The name must be a valid nickname (no tags); real account names ([A-Za-z0-9_]) are safe too.
     */
    public String mini(String name) {
        return gradient ? "<gradient:" + hex(from) + ":" + hex(to) + ">" + name + "</gradient>"
                : "<color:" + hex(from) + ">" + name + "</color>";
    }

    /** The name as a component, one colour per letter for a gradient. */
    public Component component(String name) {
        if (!gradient) return Component.text(name, TextColor.color(from));
        int n = NickRules.length(name);
        TextComponent.Builder b = Component.text();
        int i = 0;
        for (int k = 0; k < name.length(); ) {
            int cp = name.codePointAt(k);
            k += Character.charCount(cp);
            b.append(Component.text(new String(Character.toChars(cp)), TextColor.color(at(i++, n))));
        }
        return b.build();
    }

    /** No colour: the name as it is; otherwise {@link #mini}. */
    public static String mini(NickColor c, String name) {
        return c == null ? name : c.mini(name);
    }

    public static Component component(NickColor c, String name) {
        return c == null ? Component.text(name) : c.component(name);
    }
}
