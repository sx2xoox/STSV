package dev.stsv.economy.land;

import org.bukkit.configuration.ConfigurationSection;

/**
 * contents/economy/land.yml. Land is counted in chunks (16x16, full height). Everyone who lives on a
 * land brings freePerPerson chunks; chunks beyond the free ones are bought (each dearer than the
 * last) and taxed every day, double past the progressive limit (base + perMember for every member).
 * Pure (unit-tested).
 */
public record LandRules(int freePerPerson, long priceBase, long priceStep, double taxPerChunk, int progressiveBase,
                        int progressivePerMember, double progressiveFactor, int warnDays, int lowVaultDays, int inviteMinutes) {

    public static LandRules parse(ConfigurationSection c) {
        return new LandRules(Math.max(0, c.getInt("free-per-person", 2)), Math.max(0, c.getLong("price.base", 500)),
                Math.max(0, c.getLong("price.step", 50)), Math.max(0, c.getDouble("tax.per-chunk", 13)),
                Math.max(0, c.getInt("tax.progressive-base", 10)), Math.max(0, c.getInt("tax.progressive-per-member", 5)),
                Math.max(1, c.getDouble("tax.progressive-factor", 2)), Math.max(1, c.getInt("tax.warn-days", 3)),
                Math.max(0, c.getInt("tax.low-vault-days", 3)), Math.max(1, c.getInt("invite-minutes", 5)));
    }

    public int free(int residents) {
        return freePerPerson * residents;
    }

    /** Price of the next chunk of a land that has size chunks and free free ones: 0 while free chunks remain. */
    public long price(int size, int free) {
        if (size < free) return 0;
        return priceBase + priceStep * (size - free);
    }

    /** The chunk count from which tax doubles: base + perMember x members (the owner is not a member). */
    public int progressiveLimit(int members) {
        return progressiveBase + progressivePerMember * members;
    }

    /**
     * Daily tax: the first free chunks cost nothing, the rest taxPerChunk each, chunks past the
     * progressive limit progressiveFactor times that. multiplier = 세무 조사. Whole gold, rounded up.
     */
    public long tax(int size, int free, int members, double multiplier) {
        int limit = progressiveLimit(members);
        double t = 0;
        for (int i = free + 1; i <= size; i++) t += i > limit ? taxPerChunk * progressiveFactor : taxPerChunk;
        return (long) Math.ceil(t * multiplier - 1e-9);
    }
}
