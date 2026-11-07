package dev.stsv.economy.shop;

/**
 * The admin shop's buying price for one player: it falls as THAT player sells the same item, and nobody else's selling
 * moves it.
 *
 * <pre>P = max(floor · P0, P0 · e^(-S / Q)) · multiplier</pre>
 * P0 base price, S what this player sold of it lately (recovering hourly, shrinking at the daily reset), Q what one player
 * is expected to sell of it a day (the shop passes q x depth). At S = Q the price is 37% of the base; the floor stops it
 * there. With S = 0 (nothing sold lately) it is the market price, P0 x multiplier.
 * Every unit of a sale pushes S by one, so selling 64 at once gets the same total as 64 one by one.
 */
public final class PriceModel {

    private PriceModel() {}

    public static double unit(double base, double sold, double q, double floor, double multiplier) {
        return Math.max(floor * base, base * Math.exp(-sold / Math.max(1e-9, q))) * multiplier;
    }

    /** Total for selling count units now. */
    public static double total(double base, double sold, double q, double floor, double multiplier, int count) {
        double sum = 0;
        for (int k = 0; k < count; k++) sum += unit(base, sold + k, q, floor, multiplier);
        return sum;
    }

    /** Is the price at its floor? (counted in the daily report) */
    public static boolean atFloor(double base, double sold, double q, double floor) {
        return base * Math.exp(-sold / Math.max(1e-9, q)) <= floor * base;
    }
}
