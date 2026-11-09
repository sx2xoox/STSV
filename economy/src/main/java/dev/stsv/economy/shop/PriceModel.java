package dev.stsv.economy.shop;

/**
 * The admin shop's buying price for one player: it falls as THAT player sells the same item, and nobody else's selling
 * moves it.
 *
 * <pre>P = max(floor, (1 + S / Q)^(-a)) · P0 · multiplier</pre>
 * P0 base price, S what this player sold of it lately (recovering hourly, shrinking at the daily reset), Q what one player
 * is expected to sell of it a day (the shop passes q x depth), a the curve's exponent (shop.yml curve-exponent, 0.5).
 * The price keeps falling the more is sold but never stops at a wall: at S = Q it is 71% of the base, at 3Q 50%, at 8Q
 * 33%; the floor (25%) only after 15Q. Selling a lot is a loss, never a refusal. With S = 0 (nothing sold lately) it is
 * the market price, P0 x multiplier. Every unit of a sale pushes S by one, so selling 64 at once gets the same total as 64
 * one by one.
 */
public final class PriceModel {

    /** The exponent when a caller has none (shop.yml curve-exponent). */
    public static final double EXPONENT = 0.5;

    private PriceModel() {}

    /** The share of the base one unit pays after sold units: max(floor, (1 + sold / q)^(-a)). */
    public static double factor(double sold, double q, double floor, double exponent) {
        return Math.max(floor, Math.pow(1 + Math.max(0, sold) / Math.max(1e-9, q), -exponent));
    }

    public static double unit(double base, double sold, double q, double floor, double multiplier, double exponent) {
        return base * factor(sold, q, floor, exponent) * multiplier;
    }

    public static double unit(double base, double sold, double q, double floor, double multiplier) {
        return unit(base, sold, q, floor, multiplier, EXPONENT);
    }

    /** Total for selling count units now. */
    public static double total(double base, double sold, double q, double floor, double multiplier, int count, double exponent) {
        double sum = 0;
        for (int k = 0; k < count; k++) sum += unit(base, sold + k, q, floor, multiplier, exponent);
        return sum;
    }

    public static double total(double base, double sold, double q, double floor, double multiplier, int count) {
        return total(base, sold, q, floor, multiplier, count, EXPONENT);
    }

    /** Is the price at its floor? (counted in the daily report) */
    public static boolean atFloor(double base, double sold, double q, double floor, double exponent) {
        return Math.pow(1 + Math.max(0, sold) / Math.max(1e-9, q), -exponent) <= floor;
    }

    public static boolean atFloor(double base, double sold, double q, double floor) {
        return atFloor(base, sold, q, floor, EXPONENT);
    }
}
