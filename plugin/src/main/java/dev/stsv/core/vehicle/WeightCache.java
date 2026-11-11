package dev.stsv.core.vehicle;

import java.util.function.ToDoubleFunction;

/**
 * 적재 무게 of a bed, slot by slot, remembered ({@link CargoWeights}): a big bed (a 720-slot train) is weighed on every
 * click in its view, and weighing a stack reads its item data. A slot is weighed again only when it holds another stack
 * object than when it was last weighed (a bed's stacks are never changed in place: a change puts a new one, or null) or
 * when the weights themselves changed (the epoch moved on: weights.yml read again). The total is summed slot by slot in
 * order, so it is exactly what {@link CargoWeights#total} gives. Generic over the item type (null = an empty slot), so it
 * is unit-tested without a server. Main thread only.
 */
final class WeightCache<T> {

    private Object[] seen = new Object[0];
    private double[] weights = new double[0];
    private long epoch = Long.MIN_VALUE;

    /**
     * What slot i weighs (0 for an empty one or past the bed's end).
     *
     * @param weigh what a stack weighs (never negative)
     * @param epoch moves on whenever weights change
     */
    double weight(T[] bed, int i, ToDoubleFunction<T> weigh, long epoch) {
        if (bed == null || i < 0 || i >= bed.length) return 0;
        T t = bed[i];
        if (t == null) return 0;
        fit(bed.length, epoch);
        if (seen[i] != t) {
            weights[i] = Math.max(0, weigh.applyAsDouble(t));
            seen[i] = t;
        }
        return weights[i];
    }

    /** The whole bed's weight ({@link CargoWeights#total}). */
    double total(T[] bed, ToDoubleFunction<T> weigh, long epoch) {
        if (bed == null) return 0;
        double sum = 0;
        for (int i = 0; i < bed.length; i++) if (bed[i] != null) sum += weight(bed, i, weigh, epoch);
        return sum;
    }

    /** Sized for the bed; everything forgotten when the weights changed. */
    private void fit(int n, long now) {
        if (now != epoch) {
            epoch = now;
            seen = new Object[n];
            weights = new double[n];
            return;
        }
        if (seen.length != n) {
            seen = java.util.Arrays.copyOf(seen, n);
            weights = java.util.Arrays.copyOf(weights, n);
        }
    }
}
