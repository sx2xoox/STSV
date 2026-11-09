package dev.stsv.economy.market;

import java.util.Locale;

/**
 * The price picker of the 거래소 / 경매 menus (판매 등록, 경매 등록, 입찰): step buttons (left click adds, right click takes
 * away), a typed amount, quick bids. Pure arithmetic, kept within [min, MAX].
 */
public final class PriceInput {

    /** The step buttons, left to right. */
    public static final long[] STEPS = {1, 10, 100, 1_000, 10_000};
    /** Above anything anyone has (and far from overflowing a long). */
    public static final long MAX = 1_000_000_000_000L;

    private PriceInput() {}

    public static long clamp(long price, long min) {
        return Math.clamp(price, Math.max(1, min), MAX);
    }

    /** One step up (left click) or down (right click); shift clicks move ten steps. */
    public static long step(long price, long step, boolean up, boolean ten, long min) {
        long by = step * (ten ? 10 : 1);
        return clamp(up ? Math.min(MAX, price + by) : price - by, min);
    }

    /** base raised by percent, rounded up (quick bids: the least bid + 5 %, + 10 %). */
    public static long raise(long base, int percent) {
        return clamp((long) Math.ceil(base * (100 + percent) / 100.0 - 1e-9), base);
    }

    /**
     * A typed amount: "1500", "1,500", "1500G", "1500 골드", "1.5천", "3만", "2만 5천", "12k"; -1 when it is not a positive
     * amount.
     */
    public static long parse(String typed) {
        if (typed == null) return -1;
        String s = typed.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
        for (String unit : new String[]{"골드", "원", "g"}) if (s.endsWith(unit)) s = s.substring(0, s.length() - unit.length());
        if (s.isEmpty()) return -1;
        double total = 0;
        String rest = s;
        boolean any = false;
        // Korean units from the biggest: 억, 만, 천 (and k)
        for (String[] u : new String[][]{{"억", "100000000"}, {"만", "10000"}, {"천", "1000"}, {"k", "1000"}}) {
            int i = rest.indexOf(u[0]);
            if (i < 0) continue;
            String num = rest.substring(0, i);
            double n = num.isEmpty() ? 1 : number(num);
            if (n < 0) return -1;
            total += n * Long.parseLong(u[1]);
            rest = rest.substring(i + u[0].length());
            any = true;
        }
        if (!rest.isEmpty()) {
            double n = number(rest);
            if (n < 0 || (any && rest.contains("."))) return -1;
            total += n;
        }
        long out = Math.round(total);
        return out > 0 && out <= MAX ? out : -1;
    }

    private static double number(String s) {
        if (!s.matches("\\d+(\\.\\d+)?")) return -1;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "1", "10", ..., "1,000", "1만": the label of a step button. */
    public static String label(long step) {
        return step >= 10_000 && step % 10_000 == 0 ? (step / 10_000) + "만" : String.format(Locale.ROOT, "%,d", step);
    }
}
