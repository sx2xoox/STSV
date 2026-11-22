package dev.stsv.economy.secret.jesa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Where the house may stand (주인 없는 제사): the spots around a bus stop worth trying, in an order that is always the same
 * for one player at one stop, and the small sums of fitting it on the ground. Pure (unit-tested); the blocks themselves are
 * read by {@link Jesa}.
 */
final class Place {

    /** The house reaches this far from its middle block each way (posts at the corners). */
    static final int REACH = 2;

    private Place() {}

    /** The same for one player at one stop, different for another. */
    static long seed(UUID player, int stop) {
        return player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 17) ^ (stop * 0x9E3779B97F4A7C15L);
    }

    /**
     * Spots (dx, dz from the stop) whose distance is within [min, max], two blocks apart, shuffled by the seed. Those
     * nearer than `clear` to the road's line through the stop (its way there: hx, hz, a unit; 0, 0 = unknown) are left out.
     */
    static List<int[]> candidates(long seed, int min, int max, double hx, double hz, double clear) {
        List<int[]> out = new ArrayList<>();
        int lo = Math.max(0, Math.min(min, max)), hi = Math.max(min, max);
        for (int dx = -hi; dx <= hi; dx += 2) {
            for (int dz = -hi; dz <= hi; dz += 2) {
                double d = Math.hypot(dx, dz);
                if (d < lo || d > hi) continue;
                if ((hx != 0 || hz != 0) && Math.abs(dx * hz - dz * hx) < clear) continue;
                out.add(new int[]{dx, dz});
            }
        }
        Collections.shuffle(out, new Random(seed));
        return out;
    }

    /** Whether ground heights (one per column of the footprint) are one level: a floor to stand a house on. */
    static boolean flat(int[] heights) {
        for (int h : heights) if (h != heights[0]) return false;
        return heights.length > 0;
    }

    /** "world x y z" (the table's block: the first air over the ground). */
    static String encode(String world, int x, int y, int z) {
        return world + " " + x + " " + y + " " + z;
    }

    /** {x, y, z} of a saved place, or null when it does not read; the world's name is {@link #world}. */
    static int[] decode(String saved) {
        if (saved == null) return null;
        String[] w = saved.trim().split(" ");
        if (w.length != 4) return null;
        try {
            return new int[]{Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String world(String saved) {
        if (saved == null) return null;
        int sp = saved.indexOf(' ');
        return sp <= 0 ? null : saved.substring(0, sp);
    }

    /** Whether a world's clock (0..23999) is within [from, to), wrapping past the day's end. */
    static boolean within(long time, int from, int to) {
        long t = Math.floorMod(time, 24000L);
        return from <= to ? t >= from && t < to : t >= from || t < to;
    }

    /**
     * Whether someone at (px, pz) stands before a table whose middle is (tx, tz): on its south side (the screen is on the
     * north), within `reach` blocks.
     */
    static boolean before(double px, double pz, double tx, double tz, double reach) {
        double dx = px - tx, dz = pz - tz;
        return dz >= 0.6 && dx * dx + dz * dz <= reach * reach;
    }
}
