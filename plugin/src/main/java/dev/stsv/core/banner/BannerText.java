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
 *   <li>Plate parts come first (coloured glyphs), then the gem and the letters (grayscale glyphs, which the game
 *       draws after every coloured glyph of the same layer, so they are always on top).</li>
 *   <li>The game advances a bitmap glyph by {@code (int) (0.5 + width * scale) + 1}; every glyph is put where the
 *       layout wants it by a space glyph (±0.5 · 2^k px) from where the last one left the pen.</li>
 *   <li>{@link #ALL_FRAMES}: the 16 frames of every part on the same spot, each in its marker colour
 *       ({@link #marker}); the pack's text shader shows one at a time, a client without it frame 0 only.
 *       A single frame: that frame's glyphs, plain white (the server-side fallback, or a static banner).</li>
 * </ul>
 */
public final class BannerText {

    /** Every frame stacked, for the shader. */
    public static final int ALL_FRAMES = -1;
    /** Red of a marker colour (text.vsh). */
    public static final int MARK_RED = 253;

    /** Full size (1 texel = 1 GUI pixel, the name tag) or half (the plate's band, for a 9 px chat or tab line). */
    public enum Size {
        BIG("", 1.0), MINI("_mini", 0.5);

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
        if (frame < 0 || frame > 15) throw new IllegalArgumentException("frame " + frame);
        return TextColor.color(MARK_RED, 252 + (frame & 3), 252 + (frame >> 2));
    }

    /** The frame a marker colour stands for, -1 when it is no marker (what text.vsh does). */
    public static int frameOf(TextColor c) {
        if (c == null || c.red() != MARK_RED || c.green() < 252 || c.blue() < 252) return -1;
        return (c.green() - 252) + 4 * (c.blue() - 252);
    }

    /** How far the game moves the pen after a bitmap glyph whose rightmost visible column + 1 is {@code width}. */
    public static double advance(int width, double scale) {
        return (int) (0.5 + width * scale) + 1;
    }

    /** One glyph as placed: its font, code point, colour, left edge (GUI px) and the game's advance for it. */
    public record Placed(Key font, int codePoint, TextColor color, boolean shadow, double x, double advance) {}

    /** The glyphs of a banner in drawing order (what {@link #component} writes). */
    public static List<Placed> place(BannerCatalog c, BannerLayout l, Size size, int frame) {
        BannerCatalog.Theme t = l.theme();
        double s = size.scale;
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
            int[] widths = size == Size.BIG ? g.big() : g.mini();
            if (frame == ALL_FRAMES) {
                for (int f = 0; f < g.chars().length; f++) {
                    out.add(new Placed(plate, g.chars()[f], marker(f), false, p.x() * s, advance(widths[f], s)));
                }
            } else {
                int f = Math.floorMod(frame, g.chars().length);
                out.add(new Placed(plate, g.chars()[f], WHITE, false, p.x() * s, advance(widths[f], s)));
            }
        }
        if (gem != null) {
            out.add(new Placed(title, t.gemChar(), TextColor.color(t.gemTint()[0], t.gemTint()[1], t.gemTint()[2]), false,
                    gem.x() * s, advance(size == Size.BIG ? t.gemBig() : t.gemMini(), s)));
        }
        boolean shadow = size == Size.BIG;
        letters(out, c.titleWidths(), l.title(), t.titleTint(), title, s, shadow);
        letters(out, c.nickWidths(), l.nick(), t.nickTint(), nick, s, shadow);
        return out;
    }

    private static final TextColor WHITE = TextColor.color(0xFFFFFF);

    private static void letters(List<Placed> out, java.util.Map<Integer, Integer> widths, List<BannerLayout.Letter> letters,
                                int[][] tint, Key font, double s, boolean shadow) {
        for (BannerLayout.Letter k : letters) {
            int ink = widths.getOrDefault(k.codePoint(), 1) - 1;
            out.add(new Placed(font, k.codePoint(), lerp(tint[0], tint[1], k.along()), shadow, k.x() * s, advance(ink, s)));
        }
    }

    static TextColor lerp(int[] a, int[] b, double t) {
        return TextColor.color((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                (int) Math.round(a[2] + (b[2] - a[2]) * t));
    }

    /**
     * The banner as one component: runs of the same font, colour and shadow, with the space glyphs that move the pen
     * to each glyph's place, and a last space so whatever follows starts right after the banner ({@link
     * BannerLayout#width()}). Plate glyphs have no shadow; full-size letters keep the game's drop shadow.
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
        double end = l.width() * size.scale;
        if (!runs.isEmpty()) spaces(c, runs.getLast().text, end - pen);
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
