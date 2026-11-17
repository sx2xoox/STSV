package dev.stsv.core.minigame.circuit;

/**
 * A kart's boost (부스터), as in kart racing games: drifting fills the gauge (more the further the kart slides, times its
 * {@code charge}), a full gauge becomes a boost kept for later (two at most), and space fires one: the top speed goes up
 * by the kart's {@code boost} factor for its {@code boost-ticks}. Pure (unit-tested).
 */
public final class BoostGauge {

    /** Gauge filled a tick of a full slide at charge 1 (a full gauge: two seconds and a half of hard drifting). */
    public static final double RATE = 0.02;
    /** Gauge filled a tick of driving flat out (a little, so a clean line also earns a boost, slowly). */
    public static final double CRUISE = 0.0012;
    public static final int MAX_STORED = 2;
    /** Ticks between two boosts fired (one press fires one). */
    public static final int COOLDOWN = 8;

    private double gauge;
    private int stored;
    private int left;
    private int cooldown;
    private double power = 1;

    /**
     * One tick: slide = how far the kart slides (0 .. 1: its slip over its drift angle), fast = it goes near its top speed.
     * Returns true when the gauge just became a boost.
     */
    public boolean tick(double slide, boolean fast, double charge) {
        if (left > 0) left--;
        if (cooldown > 0) cooldown--;
        if (stored >= MAX_STORED) {
            gauge = 0;
            return false;
        }
        gauge += (Math.clamp(slide, 0, 1) * RATE + (fast ? CRUISE : 0)) * charge;
        if (gauge >= 1) {
            gauge -= 1;
            stored++;
            if (stored >= MAX_STORED) gauge = 0;
            return true;
        }
        return false;
    }

    /** Space: fires a boost if one is kept (and the last is a moment ago). */
    public boolean fire(double power, int ticks) {
        if (stored == 0 || cooldown > 0) return false;
        stored--;
        left = Math.max(left, 0) + ticks;
        this.power = power;
        cooldown = COOLDOWN;
        return true;
    }

    /** A boost pad or the start: a boost of its own, not from the gauge. */
    public void free(double power, int ticks) {
        this.power = left > 0 ? Math.max(this.power, power) : power;
        left = Math.max(left, ticks);
    }

    /** The top speed factor now (1 = no boost). */
    public double factor() {
        return left > 0 ? power : 1;
    }

    public boolean boosting() {
        return left > 0;
    }

    public double gauge() {
        return gauge;
    }

    public int stored() {
        return stored;
    }

    public void reset() {
        gauge = 0;
        stored = 0;
        left = 0;
        cooldown = 0;
        power = 1;
    }

    /** A respawn ends a boost (what is kept stays). */
    public void stop() {
        left = 0;
    }
}
