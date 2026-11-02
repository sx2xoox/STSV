package dev.stsv.economy.land;

import org.bukkit.configuration.ConfigurationSection;

/** contents/economy/land.yml: free area, minimum side, property tax. Pure (unit-tested). */
public record LandRules(long freeArea, int minSide, double taxRate, long progressiveOver, double progressiveFactor, int warnDays, int maxClaims) {

    public static LandRules parse(ConfigurationSection c) {
        return new LandRules(c.getLong("free-area", 400), c.getInt("min-side", 3), c.getDouble("tax.rate", 0.05),
                c.getLong("tax.progressive-over", 2500), c.getDouble("tax.progressive-factor", 2), c.getInt("tax.warn-days", 3),
                c.getInt("max-claims", 8));
    }

    /** Daily property tax for an owned area (whole gold, rounded up); multiplier = 세무 조사. */
    public long tax(long area, double multiplier) {
        if (area <= 0) return 0;
        long normal = Math.min(area, progressiveOver);
        long extra = Math.max(0, area - progressiveOver);
        return (long) Math.ceil((normal * taxRate + extra * taxRate * progressiveFactor) * multiplier - 1e-9);
    }
}
