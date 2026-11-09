package dev.stsv.economy.land;

import org.bukkit.configuration.ConfigurationSection;

/**
 * contents/economy/land.yml. Land is counted in chunks (16x16, full height). Everyone who lives on a
 * land brings freePerPerson chunks; chunks beyond the free ones are bought (each dearer than the
 * last) and taxed every day: progressiveFactor times past the progressive limit (base + perMember for every member), and
 * progressiveFactor2 times past the second limit (base2 + perMember2 for every member; 0 = no second step).
 * Pure (unit-tested).
 */
public record LandRules(int freePerPerson, long priceBase, long priceStep, double taxPerChunk, int progressiveBase,
                        int progressivePerMember, double progressiveFactor, int warnDays, int lowVaultDays, int inviteMinutes,
                        int progressiveBase2, int progressivePerMember2, double progressiveFactor2) {

    public LandRules(int freePerPerson, long priceBase, long priceStep, double taxPerChunk, int progressiveBase,
                     int progressivePerMember, double progressiveFactor, int warnDays, int lowVaultDays, int inviteMinutes) {
        this(freePerPerson, priceBase, priceStep, taxPerChunk, progressiveBase, progressivePerMember, progressiveFactor, warnDays,
                lowVaultDays, inviteMinutes, 0, 0, progressiveFactor);
    }

    public static LandRules parse(ConfigurationSection c) {
        return new LandRules(Math.max(0, c.getInt("free-per-person", 2)), Math.max(0, c.getLong("price.base", 500)),
                Math.max(0, c.getLong("price.step", 50)), Math.max(0, c.getDouble("tax.per-chunk", 13)),
                Math.max(0, c.getInt("tax.progressive-base", 10)), Math.max(0, c.getInt("tax.progressive-per-member", 5)),
                Math.max(1, c.getDouble("tax.progressive-factor", 2)), Math.max(1, c.getInt("tax.warn-days", 3)),
                Math.max(0, c.getInt("tax.low-vault-days", 3)), Math.max(1, c.getInt("invite-minutes", 5)),
                Math.max(0, c.getInt("tax.progressive-base-2", 0)), Math.max(0, c.getInt("tax.progressive-per-member-2", 0)),
                Math.max(1, c.getDouble("tax.progressive-factor-2", c.getDouble("tax.progressive-factor", 2))));
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

    /** The chunk count from which the second step applies (Integer.MAX_VALUE when there is none). */
    public int progressiveLimit2(int members) {
        return progressiveBase2 <= 0 ? Integer.MAX_VALUE : Math.max(progressiveLimit(members), progressiveBase2 + progressivePerMember2 * members);
    }

    /** What one chunk numbered i (1 = the first) costs a day, before 세무 조사. */
    public double chunkTax(int i, int members) {
        if (i > progressiveLimit2(members)) return taxPerChunk * progressiveFactor2;
        return i > progressiveLimit(members) ? taxPerChunk * progressiveFactor : taxPerChunk;
    }

    /**
     * Daily tax: the first free chunks cost nothing, the rest taxPerChunk each, chunks past the progressive limit
     * progressiveFactor times that, past the second limit progressiveFactor2 times. multiplier = 세무 조사. Whole gold,
     * rounded up.
     */
    public long tax(int size, int free, int members, double multiplier) {
        double t = 0;
        for (int i = free + 1; i <= size; i++) t += chunkTax(i, members);
        return (long) Math.ceil(t * multiplier - 1e-9);
    }
}
