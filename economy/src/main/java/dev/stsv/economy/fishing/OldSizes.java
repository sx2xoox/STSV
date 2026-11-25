package dev.stsv.economy.fishing;

import org.bukkit.NamespacedKey;

/**
 * Fish sizes before 2026-11: a rare or legend catch carried its size (economy:fish_size) and the shop's price factor by it
 * (economy:fish_factor, 0.7 - 1.5; a fish whose size a machine slot took off kept only the factor, 0.7). Fish no longer have a
 * size: such a stack is turned into the plain fish of the same id and amount (FishingModule#tidy) and its owner is paid once what
 * the size was worth above the base price. The pure parts, unit-tested.
 */
public final class OldSizes {

    public static final NamespacedKey SIZE = new NamespacedKey("economy", "fish_size");
    public static final NamespacedKey FACTOR = new NamespacedKey("economy", "fish_factor");
    /** The biggest factor a size ever gave (priceFactor at the top of a species' range): a bigger stored one is not believed. */
    public static final double MAX_FACTOR = 1.5;

    private OldSizes() {}

    /** Whether a stack with these keys is an old sized fish (either key alone counts: a stripped one has only the factor). */
    public static boolean sized(boolean hasSize, boolean hasFactor) {
        return hasSize || hasFactor;
    }

    /** The stored factor to pay by: 1 when there is none or it is not a number, at most {@link #MAX_FACTOR}, never below 0. */
    public static double factor(Double stored) {
        if (stored == null || !Double.isFinite(stored)) return 1;
        return Math.clamp(stored, 0, MAX_FACTOR);
    }

    /**
     * What one converted stack is owed (G, not rounded): (factor - 1) x the shop's base price x amount, for a factor above 1 only
     * (a small fish, 0.7, now sells for more as the plain fish: nothing to pay). A worthless stack (a 길라잡이 trial fish, base 0)
     * is owed nothing.
     */
    public static double refund(double factor, double base, int amount) {
        if (!(factor > 1) || !(base > 0) || amount <= 0) return 0;
        return (Math.min(factor, MAX_FACTOR) - 1) * base * amount;
    }

    /** The G paid for one tidy-up (all its stacks together): rounded half up, never negative. */
    public static long payout(double owed) {
        return owed > 0 && Double.isFinite(owed) ? Math.round(owed) : 0;
    }
}
