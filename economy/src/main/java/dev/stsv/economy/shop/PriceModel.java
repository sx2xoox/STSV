package dev.stsv.economy.shop;

/**
 * The admin shop's buying price: it falls as the whole server sells the same item today.
 *
 * <pre>P = max(floor · P0, P0 · e^(-S / Q)) · multiplier,   Q = q · N</pre>
 * P0 base price, S sold today by everyone, q what one player is expected to sell per day, N players
 * who joined today. At S = Q the price is 37% of the base; the floor (35%) stops it there.
 * Every unit of a sale pushes S by one, so selling 64 at once gets the same total as 64 one by one.
 */
public final class PriceModel {

    private PriceModel() {}

    public static double unit(double base, double sold, double q, int players, double floor, double multiplier) {
        double quota = Math.max(1e-9, q * Math.max(1, players));
        return Math.max(floor * base, base * Math.exp(-sold / quota)) * multiplier;
    }

    /** Total for selling count units now. */
    public static double total(double base, double sold, double q, int players, double floor, double multiplier, int count) {
        double sum = 0;
        for (int k = 0; k < count; k++) sum += unit(base, sold + k, q, players, floor, multiplier);
        return sum;
    }

    /** Is the price at its floor? (counted in the daily report) */
    public static boolean atFloor(double base, double sold, double q, int players, double floor) {
        return base * Math.exp(-sold / Math.max(1e-9, q * Math.max(1, players))) <= floor * base;
    }
}
