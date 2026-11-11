package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * 적재 무게: how heavy a bed is and how much more it takes (README "적재 무게"). A vehicle carries at most its payload
 * ({@link VehicleSpec.Cargo#payload}); what one item weighs comes from a content plugin (the economy server's weights.yml,
 * {@link VehicleModule#cargoWeight}): a weight per item, stacks counted by their size. Also tells a real change of a bed's
 * contents (loading, unloading) from moving things between its slots ({@link #sameContents}). Generic over the item type
 * (null = an empty slot), so it is unit-tested without a server.
 */
public final class CargoWeights {

    /** A little slack for sums of decimals (0.1 x 3 is not quite 0.3). */
    static final double EPS = 1e-6;

    private CargoWeights() {}

    /** The bed's weight: each item's weight (a whole stack's: weight already counts its size). */
    public static <T> double total(T[] bed, ToDoubleFunction<T> weight) {
        if (bed == null) return 0;
        double sum = 0;
        for (T t : bed) if (t != null) sum += Math.max(0, weight.applyAsDouble(t));
        return sum;
    }

    /** Does adding this much keep the bed within its payload? */
    public static boolean fits(double used, double adding, double payload) {
        return adding <= 0 || used + adding <= payload + EPS;
    }

    /** How many of `want` units of this weight each still fit (0 .. want); weightless ones all fit. */
    public static int fitting(double used, double unit, int want, double payload) {
        if (want <= 0) return 0;
        if (unit <= 0) return want;
        double room = payload - used + EPS;
        if (room <= 0) return 0;
        return (int) Math.min(want, Math.floor(room / unit));
    }

    /**
     * The same things in both beds, wherever they lie (a stack split in two or moved to another slot is not a change)?
     * similar = the same kind of item (ItemStack#isSimilar), amount = how many in a slot.
     */
    public static <T> boolean sameContents(T[] a, T[] b, BiPredicate<T, T> similar, ToIntFunction<T> amount) {
        List<T> kinds = new ArrayList<>();
        List<long[]> counts = new ArrayList<>();
        add(a, 1, kinds, counts, similar, amount);
        add(b, -1, kinds, counts, similar, amount);
        for (long[] c : counts) if (c[0] != 0) return false;
        return true;
    }

    private static <T> void add(T[] bed, int sign, List<T> kinds, List<long[]> counts, BiPredicate<T, T> similar, ToIntFunction<T> amount) {
        if (bed == null) return;
        outer:
        for (T t : bed) {
            if (t == null) continue;
            int n = amount.applyAsInt(t);
            if (n <= 0) continue;
            for (int i = 0; i < kinds.size(); i++) {
                if (similar.test(kinds.get(i), t)) {
                    counts.get(i)[0] += (long) sign * n;
                    continue outer;
                }
            }
            kinds.add(t);
            counts.add(new long[]{(long) sign * n});
        }
    }

    /** A weight for messages: whole numbers as they are, else one decimal. */
    public static String format(double w) {
        double r = Math.round(w * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(java.util.Locale.ROOT, "%.1f", r);
    }
}
