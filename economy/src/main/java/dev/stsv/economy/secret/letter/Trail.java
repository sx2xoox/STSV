package dev.stsv.economy.secret.letter;

/**
 * Where someone was over the last few seconds, one sample per tick (newest last), so a copy of them can stand where they
 * stood a moment ago. Asking for a delay between two ticks blends the two samples (the yaw the short way round); a
 * sample in another world is never blended with one in this world. No Bukkit here.
 */
final class Trail {

    /** One tick of someone: where, which way they looked, whether they were sneaking or swung an arm. */
    record Step(String world, double x, double y, double z, float yaw, float pitch, boolean sneak, boolean swing) {}

    private final Step[] ring;
    private int next;
    private int size;

    Trail(int capacity) {
        ring = new Step[Math.max(2, capacity)];
    }

    void add(Step s) {
        ring[next] = s;
        next = (next + 1) % ring.length;
        if (size < ring.length) size++;
    }

    int size() {
        return size;
    }

    void clear() {
        next = 0;
        size = 0;
    }

    /** The sample {@code back} ticks ago (0 = newest), clamped to what is kept; null when empty. */
    Step back(int back) {
        if (size == 0) return null;
        int b = Math.max(0, Math.min(size - 1, back));
        return ring[Math.floorMod(next - 1 - b, ring.length)];
    }

    /** Where they were {@code delay} ticks ago, blended between ticks. */
    Step at(double delay) {
        if (size == 0) return null;
        double d = Math.max(0, Math.min(size - 1, delay));
        int lo = (int) Math.floor(d);
        double f = d - lo;
        Step a = back(lo), b = back(lo + 1);
        if (f <= 1e-6 || a == b || !a.world().equals(b.world())) return a;
        // a is newer, b older: f of the way from a back to b
        return new Step(a.world(), lerp(a.x(), b.x(), f), lerp(a.y(), b.y(), f), lerp(a.z(), b.z(), f),
                (float) lerpAngle(a.yaw(), b.yaw(), f), (float) lerp(a.pitch(), b.pitch(), f),
                f < 0.5 ? a.sneak() : b.sneak(), false);
    }

    /** Whether an arm swing happened in the tick window (newer, older] — so a replay swings exactly once. */
    boolean swungBetween(int newer, int older) {
        for (int i = Math.max(0, newer); i <= Math.min(size - 1, older); i++) if (back(i).swing()) return true;
        return false;
    }

    /** How far they moved over the last {@code ticks} ticks (straight line), for "are they still?". */
    double moved(int ticks) {
        Step a = back(0), b = back(ticks);
        if (a == null || b == null || !a.world().equals(b.world())) return 0;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    static double lerpAngle(double a, double b, double f) {
        double d = ((b - a) % 360 + 540) % 360 - 180;
        return a + d * f;
    }
}
