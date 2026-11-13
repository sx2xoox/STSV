package dev.stsv.core.banner;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A banner ({@link BannerLayout}) as text: glyphs of the generated fonts put at their places with space glyphs, so
 * the client draws it like any other text (chat, tab list, name tag). No Bukkit types (unit-tested).
 *
 * <ul>
 *   <li>Plate parts are bitmap glyphs (RGBA sheets). The game (26.3) advances one by {@code (int) (0.5 + width *
 *       scale) + 1}, width = its rightmost column with any alpha + 1 (every PNG is read as RGBA).</li>
 *   <li>Letters and the gem are TrueType glyphs (pixel squares). The game draws those with its grayscale text pipeline,
 *       which sorts after the coloured one in a GUI layer, so they are always over the plate (bitmap glyphs of
 *       different atlas pages are drawn in no fixed order). Their advance is exact: ink + 1 px, half at chat size.</li>
 *   <li>A chat banner's letters may have an outline ({@link BannerCatalog.Theme#outline}): the ring of half pixels
 *       around each letter as a glyph of its own, in a dark colour, so the words read over any effect.</li>
 *   <li>Every glyph is put where the layout wants it by space glyphs (±0.5 · 2^k px) from where the last one left
 *       the pen; the empty columns before the left end are left out, and the pen ends where the right end's picture
 *       ends, so whatever follows starts right after the banner.</li>
 *   <li>{@link #ALL_FRAMES}: the 16 frames of every plate part on the same spot, each in its marker colour
 *       ({@link #marker}); the pack's text shader shows one at a time (in a loop, or for a theme that surges:
 *       idling on its first frames, then the rest once), a client without it frame 0 only.
 *       A single frame: that frame's glyphs, plain white (the server-side fallback, or a static banner).</li>
 * </ul>
 */
public final class BannerText {

    /** Every frame stacked, for the shader. */
    public static final int ALL_FRAMES = -1;
    /** Red of a marker colour (text.vsh). */
    public static final int MARK_RED = 253;
    /** Red of a surging banner's marker colour ({@link BannerCatalog.Theme#surge}; text.vsh, text.fsh). */
    public static final int SURGE_RED = 252;

    /**
     * Full size (1 texel = 1 GUI pixel, the name tag), the small size (the plate's 16-row band at half size: 8 GUI
     * px, the top 8 of a 9 px chat line or tab row; its letters are 4.5 px), or a theme's chat banner
     * ({@link BannerCatalog.Theme#chat}: drawn for one chat line at half size, 18 rows of texels that are exactly
     * the line's 9 px, with letters as high as the chat's own; laid out from that theme, in its pixels).
     */
    public enum Size {
        BIG("", 1.0), MINI("_mini", 0.5), CHAT("_chat", 0.5);

        final String suffix;
        final double scale;

        Size(String suffix, double scale) {
            this.suffix = suffix;
            this.scale = scale;
        }

        public double scale() {
            return scale;
        }
    }

    private BannerText() {}

    public static Key font(String name) {
        return Key.key("stsv", "title_banner/" + name);
    }

    /** The colour that marks a banner glyph as frame {@code frame} (0..15) for the shader; all but white. */
    public static TextColor marker(int frame) {
        return marker(frame, false);
    }

    /** As {@link #marker(int)}; surge: of a banner whose frames the shader plays as idling and then one surge. */
    public static TextColor marker(int frame, boolean surge) {
        if (frame < 0 || frame > 15) throw new IllegalArgumentException("frame " + frame);
        return TextColor.color(surge ? SURGE_RED : MARK_RED, 252 + (frame & 3), 252 + (frame >> 2));
    }

    /** The frame a marker colour stands for, -1 when it is no marker (what text.vsh does). */
    public static int frameOf(TextColor c) {
        if (c == null || (c.red() != MARK_RED && c.red() != SURGE_RED) || c.green() < 252 || c.blue() < 252) return -1;
        return (c.green() - 252) + 4 * (c.blue() - 252);
    }

    /** How far the game moves the pen after a bitmap glyph whose rightmost visible column + 1 is {@code width}. */
    public static double advance(int width, double scale) {
        return (int) (0.5 + width * scale) + 1;
    }

    /** One glyph as placed: its font, code point, colour, left edge (GUI px) and the game's advance for it. */
    public record Placed(Key font, int codePoint, TextColor color, boolean shadow, double x, double advance) {}

    /** The empty columns before the left end's picture at this size (texels = full-size pixels). */
    static int lead(BannerCatalog.Theme t, Size size) {
        return size == Size.MINI ? t.leadMini() : t.leadBig();
    }

    /** Where the banner ends (GUI px from where it starts): the right end's picture, widest frame. */
    public static double end(BannerLayout l, Size size) {
        BannerCatalog.Theme t = l.theme();
        BannerLayout.Piece r = l.pieces().stream().filter(p -> p.glyph().equals("R")).findFirst().orElseThrow();
        int[] w = size == Size.MINI ? t.glyph("R").mini() : t.glyph("R").big();
        int max = 0;
        for (int v : w) max = Math.max(max, v);
        return (r.x() + max - lead(t, size)) * size.scale;
    }

    /** The glyphs of a banner in drawing order (what {@link #component} writes). */
    public static List<Placed> place(BannerCatalog c, BannerLayout l, Size size, int frame) {
        BannerCatalog.Theme t = l.theme();
        double s = size.scale;
        int lead = lead(t, size);
        Key plate = font(t.id() + size.suffix);
        Key title = font("title" + size.suffix);
        Key nick = font("nick" + size.suffix);
        List<Placed> out = new ArrayList<>();
        BannerLayout.Piece gem = null;
        for (BannerLayout.Piece p : l.pieces()) {
            if (p.glyph().equals("G")) {
                gem = p;
                continue;
            }
            BannerCatalog.Glyph g = t.glyph(p.glyph());
            int[] widths = size == Size.MINI ? g.mini() : g.big();
            double x = (p.x() - lead) * s;
            if (frame == ALL_FRAMES) {
                for (int f = 0; f < g.chars().length; f++) {
                    out.add(new Placed(plate, g.chars()[f], marker(f, t.surge()), false, x, advance(widths[f], s)));
                }
            } else {
                int f = Math.floorMod(frame, g.chars().length);
                out.add(new Placed(plate, g.chars()[f], WHITE, false, x, advance(widths[f], s)));
            }
        }
        if (gem != null) {
            for (int k = 0; k < t.gemChars().length; k++) {
                int[] col = t.gemTints()[k];
                out.add(new Placed(title, t.gemChars()[k], TextColor.color(col[0], col[1], col[2]), false, (gem.x() - lead) * s,
                        t.gemAdvances()[k] * s));
            }
        }
        boolean shadow = size == Size.BIG;
        if (size == Size.CHAT && t.outline() != null) {
            // every letter's outline first (a glyph of its own that never covers a letter: the ring around it)
            int[][] dark = {t.outline(), t.outline()};
            letters(out, t.metrics().titleWidths(), l.title(), dark, font("title_chat_o"), s, lead, false);
            letters(out, t.metrics().nickWidths(), l.nick(), dark, font("nick_chat_o"), s, lead, false);
        }
        letters(out, t.metrics().titleWidths(), l.title(), t.titleTint(), title, s, lead, shadow);
        letters(out, t.metrics().nickWidths(), l.nick(), t.nickTint(), nick, s, lead, shadow);
        return out;
    }

    private static final TextColor WHITE = TextColor.color(0xFFFFFF);

    private static void letters(List<Placed> out, java.util.Map<Integer, Integer> widths, List<BannerLayout.Letter> letters,
                                int[][] tint, Key font, double s, int lead, boolean shadow) {
        for (BannerLayout.Letter k : letters) {
            out.add(new Placed(font, k.codePoint(), lerp(tint[0], tint[1], k.along()), shadow, (k.x() - lead) * s,
                    widths.get(k.codePoint()) * s));
        }
    }

    static TextColor lerp(int[] a, int[] b, double t) {
        return TextColor.color((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                (int) Math.round(a[2] + (b[2] - a[2]) * t));
    }

    /**
     * The banner as one component: runs of the same font, colour and shadow, with the space glyphs that move the pen
     * to each glyph's place, and a last space so whatever follows starts right after the banner ({@link #end}).
     * Plate glyphs and the gem have no shadow; full-size letters keep the game's drop shadow.
     */
    public static Component component(BannerCatalog c, BannerLayout l, Size size, int frame) {
        List<Placed> glyphs = place(c, l, size, frame);
        List<Run> runs = new ArrayList<>();
        double pen = 0;
        for (Placed g : glyphs) {
            Run r = runs.isEmpty() ? null : runs.getLast();
            if (r == null || !r.font.equals(g.font) || !r.color.equals(g.color) || r.shadow != g.shadow) {
                r = new Run(g.font, g.color, g.shadow);
                runs.add(r);
            }
            spaces(c, r.text, g.x() - pen);
            r.text.appendCodePoint(g.codePoint());
            pen = g.x() + g.advance();
        }
        if (!runs.isEmpty()) spaces(c, runs.getLast().text, end(l, size) - pen);
        TextComponent.Builder b = Component.text();
        for (Run r : runs) {
            Style.Builder st = Style.style().font(r.font).color(r.color);
            if (!r.shadow) st.shadowColor(ShadowColor.none());
            b.append(Component.text(r.text.toString(), st.build()));
        }
        return b.build();
    }

    private static final class Run {
        final Key font;
        final TextColor color;
        final boolean shadow;
        final StringBuilder text = new StringBuilder();

        Run(Key font, TextColor color, boolean shadow) {
            this.font = Objects.requireNonNull(font);
            this.color = Objects.requireNonNull(color);
            this.shadow = shadow;
        }
    }

    /** Space glyphs moving the pen by {@code px} (rounded to half a pixel): the space steps, largest first. */
    static void spaces(BannerCatalog c, StringBuilder out, double px) {
        long halves = Math.round(px * 2);
        if (halves == 0) return;
        boolean back = halves < 0;
        halves = Math.abs(halves);
        double[] steps = c.spaceSteps();
        for (int k = steps.length - 1; k >= 0; k--) {
            long unit = Math.round(steps[k] * 2);
            while (halves >= unit) {
                out.appendCodePoint(c.spaceBase() + 2 * k + (back ? 1 : 0));
                halves -= unit;
            }
        }
    }

    /** The pen movement of a space glyph of the generated fonts, NaN when it is none. */
    public static double spaceAdvance(BannerCatalog c, int codePoint) {
        int k = codePoint - c.spaceBase();
        if (k < 0 || k >= 2 * c.spaceSteps().length) return Double.NaN;
        double v = c.spaceSteps()[k / 2];
        return k % 2 == 0 ? v : -v;
    }
}
