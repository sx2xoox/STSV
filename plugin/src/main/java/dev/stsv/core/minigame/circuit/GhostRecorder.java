package dev.stsv.core.minigame.circuit;

import java.util.Arrays;

/**
 * Records a racer's kart for {@link Ghost}s: a sample every {@code step} race ticks (track coordinates), cut into one
 * ghost a lap. Pure (unit-tested).
 *
 * <p>The race calls {@link #sample} every tick (only every step-th is kept; a tick without a kart repeats the last
 * place, so the samples stay on their grid). When lap 1 begins ({@link #lapStarted}) everything before the last sample
 * at or before the line is dropped; when a lap ends ({@link #lap}) the samples from the lap's start to the first at or
 * past its end become a ghost, and the new lap keeps the samples from the last one at or before the line on. A lap
 * longer than {@link Ghost#MAX_SAMPLES} samples gives no ghost (the samples are dropped as they overflow).
 */
public final class GhostRecorder {

    private final int step;
    private int firstTick = -1;
    private int n;
    private double[] x = new double[256], y = new double[256], z = new double[256];
    private float[] yaw = new float[256];
    private boolean[] boost = new boolean[256];
    private boolean overflow;

    public GhostRecorder(int stepTicks) {
        this.step = Math.clamp(stepTicks, 1, 20);
    }

    public int step() {
        return step;
    }

    /** Samples held now. */
    public int size() {
        return n;
    }

    private double time(int i) {
        return (firstTick + (long) i * step) * 0.05;
    }

    /** The kart at race tick (time = tick * 0.05 s), in track coordinates. Only every step-th tick is kept. */
    public void sample(int tick, double px, double py, double pz, float pyaw, boolean boosting) {
        if (tick % step != 0) return;
        if (n == 0 || firstTick < 0) {
            firstTick = tick;
            n = 0;
        } else {
            int expected = firstTick + n * step;
            if (tick < expected) return;                 // the same tick twice: the first counts
            if ((tick - expected) / step > Ghost.MAX_SAMPLES) {
                // nothing for far too long: this lap can be no ghost; start again here
                overflow = true;
                firstTick = tick;
                n = 0;
            } else {
                // a missed sample (no kart that tick): the last place again, so every sample keeps its time
                while (expected < tick) {
                    push(x[n - 1], y[n - 1], z[n - 1], yaw[n - 1], boost[n - 1]);
                    expected += step;
                }
            }
        }
        push(px, py, pz, pyaw, boosting);
    }

    private void push(double px, double py, double pz, float pyaw, boolean boosting) {
        if (n >= Ghost.MAX_SAMPLES) {
            // too long a lap for a ghost: keep only the last sample (where the next lap may start from)
            overflow = true;
            keepFrom(n - 1);
        }
        if (n == x.length) {
            int cap = Math.min(Ghost.MAX_SAMPLES + 1, x.length * 2);
            x = Arrays.copyOf(x, cap);
            y = Arrays.copyOf(y, cap);
            z = Arrays.copyOf(z, cap);
            yaw = Arrays.copyOf(yaw, cap);
            boost = Arrays.copyOf(boost, cap);
        }
        x[n] = px;
        y[n] = py;
        z[n] = pz;
        yaw[n] = pyaw;
        boost[n] = boosting;
        n++;
    }

    /** Drops every sample before index i (i becomes the first). */
    private void keepFrom(int i) {
        if (i <= 0) return;
        int left = n - i;
        System.arraycopy(x, i, x, 0, left);
        System.arraycopy(y, i, y, 0, left);
        System.arraycopy(z, i, z, 0, left);
        System.arraycopy(yaw, i, yaw, 0, left);
        System.arraycopy(boost, i, boost, 0, left);
        firstTick += i * step;
        n = left;
    }

    /** The index of the last sample at or before time t (0 when none is). */
    private int atOrBefore(double t) {
        int i = (int) Math.floor((t - firstTick * 0.05) / (step * 0.05) + 1e-9);
        return Math.clamp(i, 0, Math.max(0, n - 1));
    }

    /** Lap 1 began at this time (seconds since the start): the run-up before it is dropped. */
    public void lapStarted(double start) {
        if (n == 0) return;
        keepFrom(atOrBefore(start));
        overflow = false;
    }

    /**
     * A lap ended (start and end: seconds since the race began; gates: its gates' times since the lap's start). Returns
     * its ghost, or null when it cannot be one (too long, too few samples); the next lap's samples are kept either way.
     */
    public Ghost lap(double start, double end, double[] gates) {
        Ghost out = null;
        if (!overflow && n >= 2) {
            int from = atOrBefore(start);
            int to = Math.min(n - 1, atOrBefore(end) + (time(atOrBefore(end)) < end - 1e-9 ? 1 : 0));
            int count = to - from + 1;
            if (count >= 2 && count <= Ghost.MAX_SAMPLES && end > start) {
                double t0 = time(from) - start;
                out = Ghost.of(step, t0, end - start, gates, Arrays.copyOfRange(x, from, to + 1), Arrays.copyOfRange(y, from, to + 1),
                        Arrays.copyOfRange(z, from, to + 1), Arrays.copyOfRange(yaw, from, to + 1), Arrays.copyOfRange(boost, from, to + 1));
            }
        }
        if (n > 0) keepFrom(atOrBefore(end));
        overflow = false;
        return out;
    }
}
