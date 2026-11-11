package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * 적재 무게: how heavy a bed is and how much more it takes (README "적재 무게"). A vehicle carries at most its payload
 * ({@link VehicleSpec.Cargo#payload}); what one item weighs comes from a content plugin (the economy server's weights.yml,
 * {@link VehicleModule#cargoWeight}): a weight per item, stacks counted by their size (there only the finished goods a dock
 * buys are heavy, each its value at the default dock price / 500 G, so a payload P carries at most about P x 500 G of them;
 * raw materials weigh 1/64, a stack 1, so slots limit them, not the payload). Also tells a real change of a bed's
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
     * similar = the same kind of item (ItemStack#isSimilar), amount = how many in a slot. Every stack is one kind here
     * (compared with every kind seen so far): use {@link #sameContents(Object[], Object[], Function, BiPredicate, ToIntFunction)}
     * with a cheap key for big beds.
     */
    public static <T> boolean sameContents(T[] a, T[] b, BiPredicate<T, T> similar, ToIntFunction<T> amount) {
        return sameContents(a, b, t -> Boolean.TRUE, similar, amount);
    }

    /**
     * {@link #sameContents(Object[], Object[], BiPredicate, ToIntFunction)}, grouped first by a cheap key (an item's id:
     * similar stacks must have the same key), so similar is only asked within a group: about one call per stack for a
     * bed of many kinds, not one per stack and kind (a 720-slot train of mixed goods synced on every click).
     */
    public static <T> boolean sameContents(T[] a, T[] b, Function<T, Object> key, BiPredicate<T, T> similar, ToIntFunction<T> amount) {
        Map<Object, List<Kind<T>>> groups = new HashMap<>();
        add(a, 1, groups, key, similar, amount);
        add(b, -1, groups, key, similar, amount);
        for (List<Kind<T>> g : groups.values()) for (Kind<T> k : g) if (k.count != 0) return false;
        return true;
    }

    /** One kind of item in a group and how many of it (the first bed adds, the second takes away). */
    private static final class Kind<T> {
        final T first;
        long count;

        Kind(T first) {
            this.first = first;
        }
    }

    private static <T> void add(T[] bed, int sign, Map<Object, List<Kind<T>>> groups, Function<T, Object> key, BiPredicate<T, T> similar,
                                ToIntFunction<T> amount) {
        if (bed == null) return;
        outer:
        for (T t : bed) {
            if (t == null) continue;
            int n = amount.applyAsInt(t);
            if (n <= 0) continue;
            List<Kind<T>> kinds = groups.computeIfAbsent(key.apply(t), k -> new ArrayList<>(1));
            for (Kind<T> k : kinds) {
                if (similar.test(k.first, t)) {
                    k.count += (long) sign * n;
                    continue outer;
                }
            }
            Kind<T> k = new Kind<>(t);
            k.count = (long) sign * n;
            kinds.add(k);
        }
    }

    /**
     * A weight for messages: whole numbers as they are, else one decimal; under 1 two (one finished good weighs its value /
     * 500 G, often a fraction: 0.32), without trailing zeros.
     */
    public static String format(double w) {
        double scale = Math.abs(w) < 1 ? 100 : 10;
        double r = Math.round(w * scale) / scale;
        if (r == Math.rint(r)) return String.valueOf((long) r);
        String s = String.format(java.util.Locale.ROOT, scale == 100 ? "%.2f" : "%.1f", r);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }
}
