package dev.stsv.core.vehicle;

/**
 * A motor vehicle's lamps (전조등 · 미등 · 브레이크등 · 후진등): which are lit. Pure (unit-tested); {@link VehicleFeelView}
 * swaps the lamp parts' models when this changes (never their brightness: lamp parts are always drawn full-bright, so
 * nothing here can make a vehicle flicker).
 *
 * <ul>
 *   <li><b>Headlamps</b> ({@link #headOn}): on while someone drives and it is dark around the vehicle: the light it is
 *       drawn with ({@link VehicleLight}, already felt now and then and faded) as the client sees it, the sky's light
 *       less the time of day's and the weather's darkening ({@link #skyDarken}), or a lamp's light if brighter. On at
 *       {@code onBelow} or darker, off at {@code offAbove} or brighter, unchanged in between (no flicker at dusk).</li>
 *   <li><b>Brake lamps</b> ({@link #braking}): S while going forward, W while reversing, both keys; held at least a few
 *       ticks ({@link State#tick}).</li>
 *   <li><b>Reverse lamps</b> ({@link #reversing}): going backwards (or S held at a stop).</li>
 *   <li>The rear shows one look ({@link #rear}): brake, else reverse, else the tail lamps (with the headlamps), else none.</li>
 * </ul>
 */
public final class VehicleLamps {

    /** Slower than this (blocks/s) counts as standing. */
    public static final double STILL = 0.05;

    private VehicleLamps() {}

    /** What the rear lamps show. */
    public enum Rear { OFF, TAIL, BRAKE, REVERSE }

    /**
     * How much the client darkens sky light at this time of day (world ticks, 0 = sunrise) and weather (0..1 each):
     * vanilla's sky darkening, 0 at noon to 11 at midnight (a block lit only by the sky shows 15 - this).
     */
    public static int skyDarken(long dayTime, float rain, float thunder) {
        double d = frac(dayTime / 24000.0 - 0.25);
        double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
        double angle = (d * 2.0 + e) / 3.0;
        double f = 0.5 + 2.0 * Math.clamp(Math.cos(angle * Math.PI * 2), -0.25, 0.25);
        double r = 1.0 - Math.clamp(rain, 0, 1) * 5 / 16.0, t = 1.0 - Math.clamp(thunder, 0, 1) * 5 / 16.0;
        return (int) ((1.0 - f * r * t) * 11.0);
    }

    private static double frac(double d) {
        return d - Math.floor(d);
    }

    /** The light the client shows at a block lit by block light and sky light (0..15 each) under this darkening. */
    public static int seen(int block, int sky, int darken) {
        return Math.clamp(Math.max(block, sky - darken), 0, 15);
    }

    /** Headlamps: on at onBelow or darker, off at offAbove or brighter, as they were in between. */
    public static boolean headOn(boolean was, int light, int onBelow, int offAbove) {
        if (light <= onBelow) return true;
        if (light >= offAbove) return false;
        return was;
    }

    /** Braking: S going forward, W going backward, or both keys while moving. */
    public static boolean braking(DriveInput in, double speed) {
        if (Math.abs(speed) < STILL) return false;
        if (in.forward() && in.backward()) return true;
        return speed > 0 ? in.backward() : in.forward();
    }

    /** Reversing: going backward, or S held at a standstill (about to). */
    public static boolean reversing(DriveInput in, double speed) {
        if (speed <= -STILL) return !in.forward();
        return Math.abs(speed) < STILL && in.backward() && !in.forward();
    }

    /** The rear's look: the brake lamps first, then the reverse lamps, then the tail lamps when the headlamps are on. */
    public static Rear rear(boolean head, boolean braking, boolean reversing) {
        if (braking) return Rear.BRAKE;
        if (reversing) return Rear.REVERSE;
        return head ? Rear.TAIL : Rear.OFF;
    }

    /** One vehicle's lamps over time. */
    public static final class State {
        boolean head;
        long brakeUntil = Long.MIN_VALUE;
        long beepAt = Long.MIN_VALUE;

        /**
         * One tick. driving: someone drives (lamps are off without a driver); light: what the client sees around the
         * vehicle (-1 = not known: the headlamps stay as they are). Returns the rear's look.
         */
        public Rear tick(long now, boolean driving, int light, DriveInput in, double speed, int onBelow, int offAbove, int brakeHold) {
            if (!driving) {
                head = false;
                brakeUntil = Long.MIN_VALUE;
                return Rear.OFF;
            }
            if (light >= 0) head = headOn(head, light, onBelow, offAbove);
            if (braking(in, speed)) brakeUntil = now + brakeHold;
            return rear(head, now <= brakeUntil, reversing(in, speed));
        }

        public boolean head() {
            return head;
        }

        /** Whether the reverse beep sounds this tick: while reversing, once every `every` ticks (the first at once). */
        public boolean beep(long now, boolean reversing, int every) {
            if (!reversing) {
                beepAt = Long.MIN_VALUE;
                return false;
            }
            if (now < beepAt) return false;
            beepAt = now + every;
            return true;
        }
    }
}
