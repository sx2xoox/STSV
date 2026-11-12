package dev.stsv.economy;

import org.bukkit.configuration.ConfigurationSection;

/** contents/economy/trade.yml: fees and limits of the ways to trade (거래소 and its 구매 주문, 경매, 직거래). Pure data. */
public record TradeRules(
        double marketFee, int marketMax, double marketHours,
        double auctionFee, long auctionDeposit, double auctionMinutes, int extendSeconds, double minRaise,
        int maxRunning, int rareWeeklyWins, long minStart,
        double tradeDistance,
        long creditMinPrice, double creditShopRatio, double creditPairHours,
        int orderMax, double orderHours, long orderMinPrice, int orderMaxAmount) {

    public static TradeRules parse(ConfigurationSection c) {
        return new TradeRules(
                c.getDouble("market.fee", 0.05), c.getInt("market.max-listings", 10), c.getDouble("market.hours", 48),
                c.getDouble("auction.fee", 0.07), c.getLong("auction.deposit", 100), c.getDouble("auction.minutes", 30),
                c.getInt("auction.extend-seconds", 60), c.getDouble("auction.min-raise", 0.05), c.getInt("auction.max-running", 3),
                c.getInt("auction.rare-weekly-wins", 2), c.getLong("auction.min-start", 10),
                c.getDouble("trade.distance", 10),
                c.getLong("credit.min-price", 100), c.getDouble("credit.shop-ratio", 1.0), c.getDouble("credit.pair-hours", 24),
                Math.max(1, c.getInt("orders.max-orders", 10)), Math.max(1, c.getDouble("orders.hours", 168)),
                Math.max(1, c.getLong("orders.min-price", 1)), Math.max(1, c.getInt("orders.max-amount", 64 * 54)));
    }

    /** A fee on an amount, rounded up (at least 1 when the rate is above 0 and the amount is positive). */
    public static long fee(long amount, double rate) {
        if (rate <= 0 || amount <= 0) return 0;
        return Math.max(1, (long) Math.ceil(amount * rate - 1e-9));   // 100 x 0.07 is 7.000000000000001 in floating point
    }

    /** The least price at which a sale counts for achievements and missions (see {@link TradeCredit}). */
    public long creditFloor(double shopValue) {
        return TradeCredit.floor(shopValue, creditMinPrice, creditShopRatio);
    }
}
