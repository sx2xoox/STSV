package dev.stsv.economy.secret.vein;

/**
 * What the player hears and how they aim (pure, unit-tested). The heartbeat from the next ore is the only guide: it comes
 * from the ore's position (so the ear gives the direction), faster and louder the closer they are.
 */
final class Sense {

    private Sense() {}

    /** Ticks between two heartbeats from an ore this far away: 8 (0.4 s, right on it) .. 40 (2 s, far). */
    static int interval(double dist) {
        return (int) Math.max(8, Math.min(40, Math.round(8 + dist * 0.8)));
    }

    /**
     * The volume to play it with. Minecraft fades a sound linearly over 16 × max(1, volume) blocks, and a volume above 1 only
     * makes it carry further. (d + 16) / 16 makes the heard loudness about 16 / (d + 16): soft but never silent far away,
     * full when close. Capped so a lost player still hears it from about 150 blocks.
     */
    static float volume(double dist) {
        return (float) Math.max(1.0, Math.min(10.0, (dist + 16) / 16));
    }

    /** Pitch of the beat: lower when far (the stone muffles it), up to normal when close. */
    static float pitch(double dist) {
        return (float) Math.max(0.55, Math.min(1.0, 1.0 - dist / 80));
    }

    /**
     * Whether a ray from the eye (ex,ey,ez) along a direction (dx,dy,dz) passes within {@code radius} of a point in front,
     * no further than {@code reach}.
     */
    static boolean aimed(double ex, double ey, double ez, double dx, double dy, double dz, double px, double py, double pz, double radius, double reach) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return false;
        dx /= len;
        dy /= len;
        dz /= len;
        double vx = px - ex, vy = py - ey, vz = pz - ez;
        double along = vx * dx + vy * dy + vz * dz;
        if (along < 0 || along > reach) return false;
        double cx = vx - dx * along, cy = vy - dy * along, cz = vz - dz * along;
        return cx * cx + cy * cy + cz * cz <= radius * radius;
    }

    /** Whether a placed block counts as a light (any light emission at all: torches, lanterns, glowstone, lava, ...). */
    static boolean light(int emission) {
        return emission > 0;
    }

    /** The trail's length for a player: the test length in test mode, else the configured range picked by the seed. */
    static int length(long seed, int min, int max, boolean test, int testLength) {
        if (test) return Math.max(2, testLength);
        int lo = Math.max(2, Math.min(min, max)), hi = Math.max(lo, max);
        return lo + (int) Math.floorMod(seed, (long) (hi - lo + 1));
    }
}
