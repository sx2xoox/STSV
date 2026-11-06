package dev.stsv.economy.stock;

import java.util.List;
import java.util.Locale;

/**
 * The candle chart in the /주식 company window, drawn into the window title with the resource pack font
 * "economy:stockgraph" (tools/assetgen/economy_stockgraph.py makes it): bars 1..H pixels tall for every
 * top row, as a 5 px body and a 1 px wick, red (up) or blue (down), the Korean way; a dashed line at the
 * last price with a tag showing it at the right end; spaces that move the pen by powers of two. No client
 * mod: it is text. Pure (unit-tested).
 */
public final class StockGraph {

    /** Chart height in pixels, its top row in the window, the title's starting x. */
    public static final int H = 32, TOP = 20, ORIGIN = 8;
    /** Candles: set s (0 up body, 1 up wick, 2 down body, 3 down wick), top t, height h -> BASE + (s*H + t)*H + h-1 */
    public static final int BASE = 0xE000, SPACE = 0xF000;
    /** The last price: dash EXTRA + (up ? 0 : 32) + row, tag column EXTRA + 64 + (up ? 0 : 32) + top, digit EXTRA + 0x100 + top*16 + d */
    public static final int EXTRA = 0xF100;
    public static final String FONT = "economy:stockgraph";

    /** Candles from LEFT to RIGHT (exclusive); the price tag ends at TAG_RIGHT (exclusive), inside the screen. */
    public static final int LEFT = 12, RIGHT = 140, TAG_RIGHT = 167;
    /** Body width, tag height. */
    public static final int BODY = 5, TAG_H = 7;

    private StockGraph() {}

    public record Candle(double open, double high, double low, double close) {}

    public static char glyph(boolean up, boolean wick, int top, int height) {
        int s = (up ? 0 : 2) + (wick ? 1 : 0);
        int t = Math.clamp(top, 0, H - 1);
        int h = Math.clamp(height, 1, H - t);
        return (char) (BASE + (s * H + t) * H + h - 1);
    }

    /** Glyph advance: width + 1 (a body is 5 px, a wick 1 px). */
    static int advance(boolean wick) {
        return wick ? 2 : BODY + 1;
    }

    static char dash(boolean up, int row) {
        return (char) (EXTRA + (up ? 0 : 32) + Math.clamp(row, 0, H - 1));
    }

    static char tagColumn(boolean up, int top) {
        return (char) (EXTRA + 64 + (up ? 0 : 32) + Math.clamp(top, 0, H - TAG_H));
    }

    static char digit(char ch, int top) {
        return (char) (EXTRA + 0x100 + Math.clamp(top, 0, H - 5) * 16 + (ch == ',' ? 10 : ch - '0'));
    }

    /** Advance of a tag character: a digit is 3 px, the comma 1 px, plus 1. */
    static int digitAdvance(char ch) {
        return ch == ',' ? 2 : 4;
    }

    /** Chars that move the pen by px (positive right, negative left): +1 .. +128 = SPACE+1..+8, -1 .. -128 = SPACE+17..+24. */
    public static String space(int px) {
        StringBuilder sb = new StringBuilder();
        int left = Math.abs(px);
        for (int bit = 7; bit >= 0; bit--) {
            int v = 1 << bit;
            while (left >= v) {
                sb.append((char) (SPACE + (px > 0 ? 1 : 17) + bit));
                left -= v;
            }
        }
        return sb.toString();
    }

    /** The advance of a space char (for the font generator): SPACE+1+bit = +2^bit, SPACE+17+bit = -2^bit. */
    public static int spaceAdvance(char c) {
        int i = c - SPACE;
        return i >= 17 ? -(1 << (i - 17)) : 1 << (i - 1);
    }

    /** The smallest price range the chart shows, as a share of the price. */
    static final double MIN_BAND = 0.06;

    /** Pixel row (0 = top) of a price in a [lo, hi] chart. */
    static int row(double price, double lo, double hi) {
        if (hi - lo < 1e-9) return H / 2;
        return (int) Math.round((hi - price) / (hi - lo) * (H - 1));
    }

    /** The tag's text: the last price, whole, with thousands commas. */
    public static String label(double price) {
        return String.format(Locale.ROOT, "%,d", Math.round(price));
    }

    /** Tag width: 2 px either side of the text. */
    public static int tagWidth(String text) {
        int w = -1;
        for (char ch : text.toCharArray()) w += digitAdvance(ch);
        return w + 4;
    }

    /**
     * The chart as MiniMessage text for the start of a window title: candles right-aligned in `slots`
     * positions across the chart area, the last price as a dashed line and a tag; the pen ends back at
     * the title's start.
     */
    public static String title(List<Candle> candles, int slots) {
        if (candles.isEmpty()) return "";
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Candle c : candles) {
            lo = Math.min(lo, c.low());
            hi = Math.max(hi, c.high());
        }
        if (hi - lo < hi * MIN_BAND) {      // a quiet chart: at least a 6% band, so a 1% wobble looks like one
            double mid = (hi + lo) / 2;
            lo = mid * (1 - MIN_BAND / 2);
            hi = mid * (1 + MIN_BAND / 2);
        }
        Candle last = candles.getLast();
        boolean lastUp = last.close() >= last.open();
        int lastRow = row(last.close(), lo, hi);
        String text = label(last.close());
        int tagLeft = TAG_RIGHT - tagWidth(text);
        int tagTop = Math.clamp(lastRow - TAG_H / 2, 0, H - TAG_H);

        StringBuilder sb = new StringBuilder("<white><font:" + FONT + ">");   // white: glyphs keep their own colours
        int pen = ORIGIN;
        // the last price's dashed line, under the candles: 3 px on, 2 off, up to the tag
        for (int x = LEFT; x + 3 <= tagLeft - 2; x += 5) {
            sb.append(space(x - pen)).append(dash(lastUp, lastRow));
            pen = x + 4;
        }
        int n = Math.max(slots, candles.size());
        int step = Math.max(BODY + 1, (RIGHT - LEFT) / n);
        int start = RIGHT - step * candles.size() + (step - BODY) / 2;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            boolean up = c.close() >= c.open();
            int x = start + i * step;
            int wTop = row(c.high(), lo, hi), wBottom = row(c.low(), lo, hi);
            sb.append(space(x + BODY / 2 - pen)).append(glyph(up, true, wTop, wBottom - wTop + 1));
            pen = x + BODY / 2 + advance(true);
            int bTop = row(Math.max(c.open(), c.close()), lo, hi), bBottom = row(Math.min(c.open(), c.close()), lo, hi);
            sb.append(space(x - pen)).append(glyph(up, false, bTop, bBottom - bTop + 1));
            pen = x + advance(false);
        }
        // the tag: a block of 1 px columns, then the digits on it
        for (int x = tagLeft; x < TAG_RIGHT; x++) {
            sb.append(space(x - pen)).append(tagColumn(lastUp, tagTop));
            pen = x + 2;
        }
        int x = tagLeft + 2;
        for (char ch : text.toCharArray()) {
            sb.append(space(x - pen)).append(digit(ch, tagTop + 1));
            pen = x + digitAdvance(ch);
            x = pen;
        }
        sb.append(space(ORIGIN - pen)).append("</font></white>");
        return sb.toString();
    }
}
