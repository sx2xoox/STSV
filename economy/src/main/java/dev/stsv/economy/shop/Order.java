package dev.stsv.economy.shop;

/**
 * 긴급 납품: a merchant wants target of an item; the first one delivered pays top x the shop's base price,
 * the last one bottom x, falling evenly in between (deliver early, earn more). Pure (unit-tested).
 */
public record Order(double base, int target, double top, double bottom) {

    /** How many to ask for: about value G worth at the base price, between min and max, in eights. */
    public static int target(double base, double value, int min, int max) {
        int n = (int) Math.round(value / Math.max(0.01, base));
        n = Math.max(min, Math.min(max, n));
        return Math.max(min, n / 8 * 8);
    }

    /** What the next one pays after done have been delivered. */
    public double unit(int done) {
        return base * (top - (top - bottom) * Math.min(done, target) / Math.max(1, target));
    }

    /** What n more pay after done. */
    public double pay(int done, int n) {
        double sum = 0;
        for (int k = 0; k < n; k++) sum += unit(done + k);
        return sum;
    }
}
