package dev.stsv.core.vehicle;

/**
 * A draft animal's walk ({@link TrainSpec.Animal}: the ox of the ox cart): where it is in its stride and how far its legs
 * swing. Pure (unit-tested); {@link TrainView} turns each leg model by {@link #leg} about its hip and lifts the head by
 * {@link #bob} every tick, the client gliding between ticks (display interpolation).
 *
 * <ul>
 *   <li>The stride follows the ground covered, not time: one full step cycle every {@code stride} blocks walked, so the
 *       legs swing twice as often at twice the speed and never slide over the ground. Walking backwards turns the cycle
 *       backwards.</li>
 *   <li>Each leg is at its own phase of the cycle (a walk: left hind, left fore, right hind, right fore a quarter apart).</li>
 *   <li>Standing still the legs come to rest under the body: the swing fades out over a few ticks (and back in when it
 *       sets off), so it never freezes mid-step.</li>
 * </ul>
 */
public final class Gait {

    /** Below this speed (blocks/s) the legs swing less, down to nothing standing. */
    public static final double FULL_SWING_SPEED = 1.0;
    /** How fast the swing fades in or out (share of the full swing a tick). */
    public static final double FADE = 0.15;

    /** Where in the stride it is, 0 .. 1. */
    private double phase;
    /** How much of the full swing it walks with now, 0 .. 1. */
    private double amount;

    public double phase() {
        return phase;
    }

    public double amount() {
        return amount;
    }

    /**
     * One tick: it moved {@code moved} blocks along its facing (negative = backwards) at {@code speed} blocks a second.
     *
     * @param stride blocks walked per full step cycle
     */
    public void step(double moved, double speed, double stride) {
        phase = wrap(phase + moved / stride);
        double want = Math.clamp(Math.abs(speed) / FULL_SWING_SPEED, 0, 1);
        amount = amount < want ? Math.min(want, amount + FADE) : Math.max(want, amount - FADE);
    }

    /** How far a leg at this phase of the stride is swung forward now (degrees; + = forward). */
    public double leg(double legPhase, double swing) {
        return amount * swing * Math.sin(2 * Math.PI * (phase + legPhase));
    }

    /** How far the head is lowered now (blocks): it dips twice a stride, as each fore leg takes the weight. */
    public double bob(double bob) {
        return amount * bob * 0.5 * (1 - Math.cos(4 * Math.PI * phase));
    }

    /** Standing still with the legs at rest (nothing to redraw). */
    public boolean resting() {
        return amount <= 1e-9;
    }

    private static double wrap(double p) {
        double w = p % 1.0;
        return w < 0 ? w + 1 : w;
    }
}
