package dev.stsv.economy.secret.scarecrow;

/**
 * The plain numbers behind the scarecrow: which way it faces, how fast its head may turn, how far along the way to the
 * door it is found after each offering, and the night clock (the world's, or a player's own sky during the last night).
 * Pure: no server needed (tests).
 */
final class Geometry {

    private Geometry() {}

    /** Minecraft yaw (0 = +z south, 90 = -x west) of the direction dx, dz. */
    static float yawTo(double dx, double dz) {
        return (float) wrap(Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** An angle into [-180, 180). */
    static double wrap(double a) {
        double r = ((a + 180) % 360 + 360) % 360 - 180;
        return r == 180 ? -180 : r;
    }

    /** From a toward b by at most step degrees (the short way round). */
    static float approach(float a, float b, float step) {
        double d = wrap(b - a);
        if (Math.abs(d) <= step) return (float) wrap(b);
        return (float) wrap(a + Math.signum(d) * step);
    }

    /** The angle between two yaws (0..180). */
    static double between(float a, float b) {
        return Math.abs(wrap(b - a));
    }

    /**
     * How far from its own spot (blocks, along the way to the door) it is found after {@code done} offerings: one block
     * an offering, and from {@code edgeAt} offerings on at the field edge ({@code edge} of the way, never less than
     * done blocks). Never closer to the door than {@code keep} blocks; 0 when the door is that close already.
     */
    static double along(int done, double distance, int edgeAt, double edge, double keep) {
        double max = Math.max(0, distance - keep);
        double d = done >= edgeAt ? Math.max(done, edge * distance) : done;
        return Math.max(0, Math.min(max, d));
    }

    /** Whether a world time (0..23999) is within the night [from, to) (wrapping past 24000). */
    static boolean night(long time, long from, long to) {
        long t = Math.floorMod(time, 24000L);
        return from <= to ? t >= from && t < to : t >= from || t < to;
    }

    /** Ticks of night left at this world time (0 when it is not night). */
    static long nightLeft(long time, long from, long to) {
        if (!night(time, from, to)) return 0;
        long t = Math.floorMod(time, 24000L);
        return Math.floorMod(to - t, 24000L);
    }

    /** The last night's length (seconds): the night left in real time, between min and max. */
    static int finalSeconds(long nightLeftTicks, int min, int max) {
        long s = nightLeftTicks / 20;
        return (int) Math.max(min, Math.min(max, s));
    }

    /** The player's own sky time at a share of the last night: from start to dawn. */
    static long sky(long start, long dawn, double share) {
        double f = Math.max(0, Math.min(1, share));
        long s = Math.floorMod(start, 24000L), d = Math.floorMod(dawn, 24000L);
        if (d < s) d += 24000;
        return Math.round(s + (d - s) * f);
    }

    /** The number of the night a world's full time is in (a night that runs past midnight stays one night). */
    static long nightNo(long fullTime, long from) {
        return Math.floorDiv(fullTime - from, 24000L);
    }
}
