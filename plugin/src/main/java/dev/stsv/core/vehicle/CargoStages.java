package dev.stsv.core.vehicle;

/**
 * Which load model the truck's bed shows: by how full it is, the larger of the share of its storage slots that hold
 * something and the share of its payload it carries (적재 무게, {@link CargoWeights}): a bed full by weight (a few slots of
 * heavy finished goods) looks as full as one full of light, bulky things. Pure (unit-tested).
 * Nothing stored shows nothing; otherwise the stages split the bed evenly: with four, up to a quarter = stage 1
 * (a couple of crates and a sack), up to half = 2, up to three quarters = 3, more = 4 (a full, tarped load).
 * Without weights (no content plugin weighs, weight 0) or without a payload (0, unlimited) only the slots count.
 */
public final class CargoStages {

    private CargoStages() {}

    /** 0 = no load shown, 1..stages: by the slots used alone. */
    public static int stage(int used, int capacity, int stages) {
        if (used <= 0 || capacity <= 0 || stages <= 0) return 0;
        return Math.clamp((int) Math.ceil(used * (double) stages / capacity - 1e-9), 1, stages);
    }

    /**
     * 0 = no load shown, 1..stages: by the fuller of the slots and the weight ({@link #fraction}).
     *
     * @param weight  what the bed weighs (0 = nothing weighs: slots only)
     * @param payload the most it carries (0, negative, infinite or NaN = no limit: slots only)
     */
    public static int stage(int used, int capacity, double weight, double payload, int stages) {
        int bySlots = stage(used, capacity, stages);
        if (bySlots <= 0) return 0;   // an empty bed (or one with no stage models) shows nothing, whatever is weighed
        double w = weightShare(weight, payload);
        if (!(w > 0)) return bySlots;
        return Math.max(bySlots, Math.clamp((int) Math.ceil(w * stages - 1e-9), 1, stages));
    }

    /** How full a bed looks, 0..1: the larger of slots used / slots and weight / payload. */
    public static double fraction(int used, int capacity, double weight, double payload) {
        if (used <= 0 || capacity <= 0) return 0;
        return Math.max(Math.min(1, used / (double) capacity), weightShare(weight, payload));
    }

    /** weight / payload, 0..1 (0 when there is nothing to weigh or no limit to weigh it against). */
    private static double weightShare(double weight, double payload) {
        if (!(weight > 0) || !(payload > 0) || Double.isInfinite(payload) || Double.isInfinite(weight)) return 0;
        return Math.min(1, weight / payload);
    }
}
