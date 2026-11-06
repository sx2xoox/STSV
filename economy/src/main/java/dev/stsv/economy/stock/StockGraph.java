package dev.stsv.economy.stock;

import java.util.List;

/**
 * The candle chart in the /주식 company window, drawn into the window title with the resource pack font
 * "economy:stockgraph" (tools/assetgen/economy_stockgraph.py makes it): bars 1..H pixels tall for every
 * top row, as a 3 px body and a 1 px wick, red (up) or blue (down), the Korean way, and spaces that move the pen by
 * powers of two. No client mod: it is text. Pure (unit-tested).
 */
public final class StockGraph {

    /** Chart height in pixels, its top row in the window, the title's starting x. */
    public static final int H = 32, TOP = 20, ORIGIN = 8;
    /** First glyph: set s (0 up body, 1 up wick, 2 down body, 3 down wick), top t, height h -> BASE + (s*H + t)*H + h-1 */
    public static final int BASE = 0xE000, SPACE = 0xF000;
    public static final String FONT = "economy:stockgraph";

    /** Chart area, x from LEFT to RIGHT (exclusive). */
    public static final int LEFT = 12, RIGHT = 164;

    private StockGraph() {}

    public record Candle(double open, double high, double low, double close) {}

    public static char glyph(boolean up, boolean wick, int top, int height) {
        int s = (up ? 0 : 2) + (wick ? 1 : 0);
        int t = Math.clamp(top, 0, H - 1);
        int h = Math.clamp(height, 1, H - t);
        return (char) (BASE + (s * H + t) * H + h - 1);
    }

    /** Glyph advance: width + 1 (a body is 3 px, a wick 1 px). */
    static int advance(boolean wick) {
        return wick ? 2 : 4;
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

    /** Pixel row (0 = top) of a price in a [lo, hi] chart. */
    static int row(double price, double lo, double hi) {
        if (hi - lo < 1e-9) return H / 2;
        return (int) Math.round((hi - price) / (hi - lo) * (H - 1));
    }

    /**
     * The chart as MiniMessage text for the start of a window title: candles right-aligned in `slots`
     * positions across the chart area; the pen ends back at the title's start.
     */
    public static String title(List<Candle> candles, int slots) {
        if (candles.isEmpty()) return "";
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Candle c : candles) {
            lo = Math.min(lo, c.low());
            hi = Math.max(hi, c.high());
        }
        if (hi - lo < hi * 0.01) {          // a flat chart: show a 1% band so noise does not fill it
            double mid = (hi + lo) / 2;
            lo = mid * 0.995;
            hi = mid * 1.005;
        }
        int n = Math.max(slots, candles.size());
        int step = Math.max(4, (RIGHT - LEFT) / n);
        int start = RIGHT - step * candles.size() + (step - 3) / 2;
        StringBuilder sb = new StringBuilder("<white><font:" + FONT + ">");   // white: glyphs keep their own colours
        int pen = ORIGIN;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            boolean up = c.close() >= c.open();
            int x = start + i * step;
            int wTop = row(c.high(), lo, hi), wBottom = row(c.low(), lo, hi);
            sb.append(space(x + 1 - pen)).append(glyph(up, true, wTop, wBottom - wTop + 1));
            pen = x + 1 + advance(true);
            int bTop = row(Math.max(c.open(), c.close()), lo, hi), bBottom = row(Math.min(c.open(), c.close()), lo, hi);
            sb.append(space(x - pen)).append(glyph(up, false, bTop, bBottom - bTop + 1));
            pen = x + advance(false);
        }
        sb.append(space(ORIGIN - pen)).append("</font></white>");
        return sb.toString();
    }
}
