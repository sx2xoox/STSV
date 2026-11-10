package dev.stsv.core.vehicle;

/**
 * When the horn sounds and how the engine sounds. Pure (unit-tested); {@link VehicleModule} plays what it says.
 *
 * <h2>Horn</h2>
 * Held, not pressed: space down starts the vehicle's horn clip (one steady tone, longer than any hold:
 * tools/assetgen/sounds.py), space up stops it and plays its short release tail. A tap still sounds for minTicks; a hold
 * is cut after maxTicks and stays silent until space is let go; after each stop it rests for cooldown ticks. So holding
 * or hammering space gives at most one short beep per (minTicks + cooldown) or one long one per maxTicks.
 *
 * <h2>Engine</h2>
 * Short segments re-triggered one after another, each fading in while the last fades out; every segment takes the
 * pitch (and loudness) of the speed at that moment. A pitch-p segment plays 1/p as long, so the next comes
 * {@link #engineGap} ticks later.
 */
public final class VehicleSounds {

    private VehicleSounds() {}

    /** What the horn does this tick. */
    public enum Horn { NONE, START, STOP }

    /** minTicks: a tap sounds at least this long; maxTicks: a hold is cut here; cooldown: rest after each stop. */
    public record HornRules(int minTicks, int maxTicks, int cooldown) {
        public HornRules {
            minTicks = Math.max(1, minTicks);
            maxTicks = Math.max(minTicks, maxTicks);
            cooldown = Math.max(0, cooldown);
        }
    }

    /** One vehicle's horn: sounding or not, resting, or waiting for space to be let go. */
    public static final class HornState {
        private long started = -1;
        private long readyAt = Long.MIN_VALUE;
        private boolean mustRelease;

        /**
         * One tick. held: the driver holds space now (false without a driver). START = begin the horn sound, STOP = stop
         * it and play the tail.
         */
        public Horn tick(boolean held, long now, HornRules r) {
            if (started >= 0) {
                long on = now - started;
                if (on >= r.maxTicks()) {
                    stop(now, r);
                    mustRelease = held;
                    return Horn.STOP;
                }
                if (!held && on >= r.minTicks()) {
                    stop(now, r);
                    return Horn.STOP;
                }
                return Horn.NONE;   // held, or a tap still within its shortest beep
            }
            if (!held) {
                mustRelease = false;
                return Horn.NONE;
            }
            if (mustRelease || now < readyAt) return Horn.NONE;
            started = now;
            return Horn.START;
        }

        /** Silences it at once (the driver got out, the vehicle went): STOP if it was sounding. */
        public Horn cut(long now, HornRules r) {
            if (started < 0) return Horn.NONE;
            stop(now, r);
            return Horn.STOP;
        }

        public boolean sounding() {
            return started >= 0;
        }

        private void stop(long now, HornRules r) {
            started = -1;
            readyAt = now + r.cooldown();
        }
    }

    /** Below this speed (blocks/s) the engine idles. */
    public static final double IDLE_BELOW = 0.05;

    /** Minecraft's pitch range. */
    public static final float MIN_PITCH = 0.5f, MAX_PITCH = 2f;

    /** How far along its speed range the vehicle is, 0..1 (either direction; past top counts as top). */
    static double share(double speed, double top) {
        if (top <= 0) return 0;
        return Math.min(1, Math.abs(speed) / top);
    }

    /**
     * The engine's pitch at this speed: idle pitch standing, rising to the top pitch at top speed (faster at first, as
     * an engine revs up), kept in Minecraft's 0.5..2.
     */
    public static float enginePitch(double speed, double top, VehicleSpec.Engine e) {
        double f = Math.abs(speed) < IDLE_BELOW ? 0 : share(speed, top);
        double p = e.idlePitch() + (e.topPitch() - e.idlePitch()) * Math.pow(f, 0.75);
        return (float) Math.clamp(p, MIN_PITCH, MAX_PITCH);
    }

    /**
     * The engine's loudness at this speed: idle volume standing (0 = silent, the golf cart), the driving volume soon after
     * it moves, times the server's scale; at most 1 (so it is heard 16 blocks away, no further).
     */
    public static float engineVolume(double speed, double top, VehicleSpec.Engine e, double scale) {
        double v;
        if (Math.abs(speed) < IDLE_BELOW) v = e.idleVolume();
        else v = e.idleVolume() + (e.volume() - e.idleVolume()) * Math.sqrt(share(speed, top));
        return (float) Math.clamp(v * scale, 0, 1);
    }

    /** Ticks until the next segment: a segment of `segmentTicks` at pitch 1 lasts 1/pitch as long (at least 2 ticks). */
    public static int engineGap(int segmentTicks, float pitch) {
        return Math.max(2, (int) Math.floor(segmentTicks / Math.max(MIN_PITCH, pitch) + 1e-6));
    }
}
