package dev.stsv.economy.secret.fish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 「물이 부르는 날」 ({@link FishSequel}): the rules that need no server (unit-tested). When the water may call, which
 * nameless thing it is this week, where to look for the water, the three clean catches in a row, the time left, which
 * way the float is pulled.
 */
final class Calling {

    private Calling() {}

    /** The same number for a week and a player, every time. */
    static int hash(String week, UUID u) {
        return (week + "/" + u).hashCode();
    }

    /**
     * Whether the water may call now: in the rain, or from dryFrom on (ISO day of the week, 1 = Monday .. 7 = Sunday) even
     * without it, so that a dry week is not lost; always in test mode. dryFrom above 7: only ever in the rain.
     */
    static boolean mayCall(boolean storm, int dayOfWeek, int dryFrom, boolean test) {
        return test || storm || dayOfWeek >= dryFrom;
    }

    /** "3,0,5" → the variants of the last weeks, newest last. */
    static List<Integer> recent(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null) return out;
        for (String w : raw.split(",")) {
            try {
                if (!w.isBlank()) out.add(Integer.parseInt(w.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited note
            }
        }
        return out;
    }

    /** This week's variant (0..count-1): by the week and the player, stepping past the ones of the last weeks. */
    static int variant(String week, UUID u, int count, Collection<Integer> recent) {
        if (count <= 0) return 0;
        int i = Math.floorMod(hash(week, u), count);
        for (int tries = 0; tries < count && recent.contains(i); tries++) i = (i + 1) % count;
        return i;
    }

    /** The recent list with this week's variant added, at most keep long (and never all of them: one must stay free). */
    static String remember(String raw, int picked, int keep, int count) {
        List<Integer> r = recent(raw);
        r.remove(Integer.valueOf(picked));
        r.add(picked);
        int max = Math.max(0, Math.min(keep, count - 1));
        while (r.size() > max) r.removeFirst();
        List<String> out = new ArrayList<>();
        for (Integer i : r) out.add(String.valueOf(i));
        return String.join(",", out);
    }

    /**
     * The chunks to look for the water in ({x, z} chunk coordinates, no chunk twice): along dirs directions from the block
     * (x, z), the first by the seed, each from min to max blocks out in steps; one direction after the other, so the water
     * found first lies in a different direction each week.
     */
    static List<int[]> ring(int x, int z, int min, int max, int step, int dirs, int seed) {
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        dirs = Math.max(1, dirs);
        step = Math.max(8, step);
        min = Math.max(0, min);
        int first = Math.floorMod(seed, dirs);
        for (int i = 0; i < dirs; i++) {
            // 0, +1, -1, +2, -2 ... around the first direction
            int k = first + ((i & 1) == 1 ? (i + 1) / 2 : -(i / 2));
            double a = 2 * Math.PI * Math.floorMod(k, dirs) / dirs;
            for (int d = min; d <= Math.max(min, max); d += step) {
                int cx = (int) Math.floor(x + Math.cos(a) * d) >> 4, cz = (int) Math.floor(z + Math.sin(a) * d) >> 4;
                if (seen.add(((long) cx << 32) ^ (cz & 0xffffffffL))) out.add(new int[]{cx, cz});
            }
        }
        return out;
    }

    /** The streak after a catch at the water: one more for a clean one, back to nothing for a late one. */
    static int streak(int streak, boolean clean) {
        return clean ? Math.max(0, streak) + 1 : 0;
    }

    /** Seconds left of the window (began and now in ms); 0 when it is over. */
    static long left(long began, long now, long windowSeconds) {
        return Math.max(0, windowSeconds - Math.max(0, now - began) / 1000L);
    }

    /** The way the float is pulled: a unit step from it towards the water, times strength ({x, z}); nothing when it is there. */
    static double[] pull(double fromX, double fromZ, double toX, double toZ, double strength) {
        double dx = toX - fromX, dz = toZ - fromZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return new double[]{0, 0};
        return new double[]{dx / len * strength, dz / len * strength};
    }
}
