package dev.stsv.core.vehicle;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A boat rocking on the water (drawn only: its physics stay level, see {@link BoatPhysics}). Pure (unit-tested).
 *
 * <p>A few slow waves of unrelated periods (every boat its own phase) pitch it and roll it by a degree or two and lift
 * it a few hundredths of a block; under way the bow rises with the speed and it leans out of a turn. On ice or aground
 * it lies still. The plugin turns every drawn part of the boat (body, load, propeller) about its waterline middle by
 * this rocking ({@link #rocked}) every few ticks, the client gliding between.
 */
public final class BoatMotion {

    /** Ticks between rocking updates (the client interpolates over them). */
    public static final int TICKS = 3;
    /** Degrees the bow rises at full speed; degrees it leans per degree/tick of turning, and at most. */
    static final double BOW_UP = 2.5, LEAN = 1.2, MAX_LEAN = 3.5;

    private BoatMotion() {}

    /** pitch (degrees, + = bow up), roll (degrees, + = leaning to its right) and bob (blocks up). */
    public record Rock(float pitch, float roll, float bob) {
        public static final Rock STILL = new Rock(0, 0, 0);
    }

    private static double wave(double t, double period, long seed, int k) {
        double phase = ((seed * 0x9E3779B97F4A7C15L + k * 0xBF58476D1CE4E5B9L) >>> 11) * 0x1.0p-53 * 2 * Math.PI;
        return Math.sin(t * 2 * Math.PI / period + phase);
    }

    /**
     * The rocking at tick t.
     *
     * @param seed     the boat's own (its id): boats side by side rock out of step
     * @param speed    blocks/s (negative: reversing)
     * @param top      its top speed
     * @param turn     its yaw change this tick (degrees, + = turning right)
     * @param amp      the item's water.rock (1 = gently, 0 = none)
     * @param afloat   false on ice or aground: still
     */
    public static Rock rock(double t, long seed, double speed, double top, float turn, double amp, boolean afloat) {
        if (!afloat || amp <= 0) return Rock.STILL;
        double pitch = amp * (0.9 * wave(t, 57, seed, 1) + 0.4 * wave(t, 23, seed, 2));
        double roll = amp * (1.5 * wave(t, 71, seed, 3) + 0.5 * wave(t, 29, seed, 4));
        double bob = amp * (0.035 * wave(t, 43, seed, 5) + 0.012 * wave(t, 17, seed, 6));
        double frac = top > 0 ? Math.clamp(speed / top, -1, 1) : 0;
        pitch += BOW_UP * frac;
        roll += Math.clamp(-turn * LEAN * Math.abs(frac), -MAX_LEAN, MAX_LEAN);   // leans out of the turn
        return new Rock((float) pitch, (float) roll, (float) bob);
    }

    /** The rocking as a rotation in a display's frame (x = the boat's left, y up, z forward). */
    public static Quaternionf rotation(Rock r) {
        // display +z is forward: a +x rotation lowers the bow, so the bow rises with -pitch; +z rotation lowers the left (+x)
        return new Quaternionf().rotateX((float) Math.toRadians(-r.pitch())).rotateZ((float) Math.toRadians(-r.roll()));
    }

    /**
     * A part's display translation rocked about the pivot (the waterline's middle, display frame) and lifted by the bob:
     * pivot + R (t - pivot) + bob. Its rotation becomes R x its own (see {@link #rotation}).
     */
    public static Vector3f rocked(Vector3f t, Vector3f pivot, Quaternionf r, float bob) {
        return new Vector3f(t).sub(pivot).rotate(r).add(pivot).add(0, bob, 0);
    }
}
