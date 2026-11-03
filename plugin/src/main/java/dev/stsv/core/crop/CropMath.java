package dev.stsv.core.crop;

import java.util.random.RandomGenerator;

/**
 * How a crop grows, without Bukkit (unit-tested). Growth is computed from time, not ticks: a crop
 * remembers when it was last looked at, and catching up later gives exactly the same result as if the
 * chunk had been loaded all along. It grows only while wet and pest-free; dry too long, or pests left
 * too long, and it withers.
 */
public final class CropMath {

    /** How long a crop may stay dry and still count as "never dried" for quality. */
    public static final long GRACE = 30 * 60_000L;

    private CropMath() {}

    /**
     * Brings a crop up to 'now'. sprinkled = a sprinkler covers it (always wet).
     * Returns true if anything a player could see changed (stage, withered, pests, wet/dry).
     */
    public static boolean update(CropState s, CropSpec spec, CropTools.Fertilizer fert, boolean sprinkled, long now, RandomGenerator rng) {
        if (now <= s.last) return false;
        int stageBefore = spec.stageOf(s.progress);
        boolean witheredBefore = s.withered, pestsBefore = s.pests(), wetBefore = s.drySince == 0;
        if (s.withered) {
            s.last = now;
            return false;
        }
        long from = s.last;
        long wetUntil = sprinkled ? now : Math.min(now, Math.max(from, s.waterUntil));
        // dryness
        if (wetUntil < now) {
            if (s.drySince == 0) s.drySince = Math.max(from, wetUntil);
            if (now - s.drySince > GRACE) s.neverDry = false;
            if (now - s.drySince >= (long) (spec.witherHours() * 3_600_000)) s.withered = true;
        } else {
            s.drySince = 0;
        }
        // growth while wet and healthy
        double speed = fert == null ? 1 : fert.speed();
        long grownUntil = wetUntil;
        if (s.pests()) grownUntil = Math.min(grownUntil, s.pestSince);
        if (grownUntil > from && !spec.ripe(s.progress)) {
            long add = (long) ((grownUntil - from) * speed);
            long before = s.progress;
            s.progress = Math.min(spec.growMillis(), s.progress + add);
            // pests may arrive at each new stage; growth stops from that moment
            int st0 = spec.stageOf(before), st1 = spec.stageOf(s.progress);
            for (int st = Math.max(st0 + 1, s.rolledStage + 1); st <= st1 && st < spec.stages() - 1; st++) {
                s.rolledStage = st;
                if (!s.pests() && rng.nextDouble() < spec.pestChance()) {
                    long reachedAt = from + (long) ((st * spec.stageMillis() - before) / speed);
                    s.pestSince = Math.max(from, Math.min(now, reachedAt));
                    s.progress = Math.max(before, (long) st * spec.stageMillis());
                    break;
                }
            }
        }
        if (s.pests() && now - s.pestSince >= (long) (spec.pestHours() * 3_600_000)) s.withered = true;
        s.last = now;
        return spec.stageOf(s.progress) != stageBefore || s.withered != witheredBefore || s.pests() != pestsBefore
                || (s.drySince == 0) != wetBefore;
    }

    public static void water(CropState s, CropSpec spec, long now) {
        s.waterUntil = now + (long) (spec.waterHours() * 3_600_000);
        s.drySince = 0;
    }

    /** After a harvest: back to the regrow stage (the plant stays) or gone (returns false). */
    public static boolean harvested(CropState s, CropSpec spec) {
        if (spec.regrow() < 0) return false;
        s.progress = (long) spec.regrow() * spec.stageMillis();
        s.rolledStage = spec.regrow();
        s.fertilizer = null;
        s.neverDry = true;
        return true;
    }

    /**
     * Quality stars of a harvest: 0.25 base, +0.3 if it never dried out, + fertilizer and other bonuses
     * (a skilled farmer), give or take 0.1 of luck. ★★★ from 0.8, ★★ from 0.55.
     */
    public static int stars(CropState s, CropTools.Fertilizer fert, double bonus, RandomGenerator rng) {
        double q = 0.25 + (s.neverDry ? 0.3 : 0) + (fert == null ? 0 : fert.quality()) + bonus + (rng.nextDouble() * 0.2 - 0.1);
        return q >= 0.8 ? 3 : q >= 0.55 ? 2 : 1;
    }
}
