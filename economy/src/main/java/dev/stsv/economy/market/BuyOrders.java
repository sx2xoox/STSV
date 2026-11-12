package dev.stsv.economy.market;

import dev.stsv.economy.TradeRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 거래소 구매 주문: the rules and the arithmetic, pure (unit-tested). A buyer asks for amount of an item at a unit price and the
 * whole amount x price is held (escrow) at once; sellers hand items over, each paid amount x price from it minus the 거래소 fee;
 * what is left comes back on cancel or expiry. The money held is always (amount - filled) x price.
 */
public final class BuyOrders {

    private BuyOrders() {}

    /** What an order holds for this many at this unit price (overflow is an error, never a wrap). */
    public static long escrow(int amount, long unit) {
        return Math.multiplyExact((long) amount, unit);
    }

    /**
     * Why this order cannot be placed (a message key), or null. open = the buyer's open orders now, balance = their money.
     * The item itself (what the shop buys, no cheques/vehicles/vouchers) is checked by the module.
     */
    public static String placeProblem(TradeRules r, int open, int amount, long unit, long balance) {
        if (open >= r.orderMax()) return "order.full";
        if (unit < r.orderMinPrice()) return "order.min-price";
        if (unit > PriceInput.MAX) return "order.bad-price";
        if (amount < 1 || amount > r.orderMaxAmount()) return "order.bad-amount";
        if (escrow(amount, unit) > balance) return "order.not-enough";
        return null;
    }

    /** Why this seller cannot fill the order now (a message key), or null. left = what the order still wants. */
    public static String fillProblem(UUID buyer, UUID seller, int left, int have) {
        if (buyer.equals(seller)) return "order.own";
        if (left <= 0) return "order.gone";
        if (have <= 0) return "order.no-items";
        return null;
    }

    /** How many a fill hands over: a click = one stack at most, shift = as many as the seller has; never more than is left. */
    public static int fillCount(int left, int have, boolean all, int stack) {
        int n = Math.min(left, have);
        if (!all) n = Math.min(n, Math.max(1, stack));
        return Math.max(0, n);
    }

    /** What a fill pays: gross from the order's escrow, the 거래소 fee on it, and net to the seller. */
    public record Payout(long gross, long fee, long net) {}

    public static Payout payout(long unit, int n, double feeRate) {
        long gross = escrow(n, unit);
        long fee = TradeRules.fee(gross, feeRate);
        return new Payout(gross, fee, gross - fee);
    }

    /**
     * The order in which a fill takes the seller's matching slots: the smallest size factor first (a rare fish's trophy size
     * goes last, since an order pays the same for any size), then by slot. factor[i] = NaN: slot i does not match.
     */
    public static int[] smallestFirst(double[] factor) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < factor.length; i++) if (!Double.isNaN(factor[i])) slots.add(i);
        slots.sort(Comparator.<Integer>comparingDouble(i -> factor[i]).thenComparingInt(i -> i));
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }
}
