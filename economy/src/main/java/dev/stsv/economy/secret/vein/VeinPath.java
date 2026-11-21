package dev.stsv.economy.secret.vein;

import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Where the next "impossible ore" of the trail lies (pure, unit-tested). The trail wanders through solid rock from the
 * start point: each step goes stepMin..stepMax blocks along a heading that slowly turns sideways and tends downwards
 * (never below minY). The same seed, attempt, index and starting block always give the same candidates in the same order;
 * the world decides which candidate is good (rock, no liquid next to it, chunk loaded).
 */
final class VeinPath {

    /** A block position. */
    record Pos(int x, int y, int z) {
        double dist(Pos o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        String encode() {
            return x + " " + y + " " + z;
        }

        static Optional<Pos> decode(String s) {
            if (s == null) return Optional.empty();
            String[] w = s.trim().split("\\s+");
            if (w.length < 3) return Optional.empty();
            try {
                return Optional.of(new Pos(Integer.parseInt(w[w.length - 3]), Integer.parseInt(w[w.length - 2]), Integer.parseInt(w[w.length - 1])));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }

    /** The next ore and the heading that led there (dx, dz in -1..1, dy in -1..0). */
    record Step(Pos at, int hx, int hy, int hz) {}

    /** The 8 horizontal headings, clockwise from north. */
    private static final int[][] DIRS = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};

    private VeinPath() {}

    static long mix(long seed, int attempt, int index) {
        long h = seed * 0x9E3779B97F4A7C15L + attempt * 0xC2B2AE3D27D4EB4FL + index * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }

    private static int dirIndex(int hx, int hz) {
        for (int i = 0; i < DIRS.length; i++) if (DIRS[i][0] == hx && DIRS[i][1] == hz) return i;
        return 0;
    }

    /**
     * The next ore after {@code from}. heading = the last step's heading, or null for the first ore (then the seed picks
     * one). {@code ok} says whether a block may hold an ore. Empty when nothing around fits (the caller ends the trail early
     * or tries again later).
     */
    static Optional<Step> next(long seed, int attempt, int index, Pos from, int[] heading, int minY, int stepMin, int stepMax, Predicate<Pos> ok) {
        Random r = new Random(mix(seed, attempt, index));
        int lo = Math.max(2, Math.min(stepMin, stepMax)), hi = Math.max(lo, stepMax);
        int di, hy;
        if (heading == null) {
            di = r.nextInt(DIRS.length);
            hy = r.nextDouble() < 0.6 ? -1 : 0;
        } else {
            di = dirIndex(heading[0], heading[2]);
            hy = heading[1];
        }
        for (int tries = 0; tries < 48; tries++) {
            // later tries turn further away from the heading (a wall of lava or air ahead: go around it)
            int spread = tries < 16 ? 1 : tries < 32 ? 2 : 4;
            int turn = r.nextDouble() < 0.35 || tries >= 16 ? r.nextInt(spread * 2 + 1) - spread : 0;
            int d = Math.floorMod(di + turn, DIRS.length);
            int ny;
            if (from.y() - hi <= minY) ny = 0;                          // the floor is near: only sideways
            else ny = r.nextDouble() < (hy < 0 ? 0.6 : 0.35) ? -1 : 0;  // keep going down, now and then
            int len = lo + r.nextInt(hi - lo + 1);
            double hx = DIRS[d][0], hz = DIRS[d][1];
            double norm = Math.sqrt(hx * hx + hz * hz + ny * ny * 0.5);
            double fx = hx / norm * len, fz = hz / norm * len, fy = ny * 0.7 / norm * len;
            int jx = r.nextInt(3) - 1, jy = r.nextInt(3) - 1, jz = r.nextInt(3) - 1;
            Pos c = new Pos(from.x() + (int) Math.round(fx) + (hx == 0 ? jx : 0), from.y() + (int) Math.round(fy) + jy,
                    from.z() + (int) Math.round(fz) + (hz == 0 ? jz : 0));
            if (c.y() <= minY || c.dist(from) < 2) continue;
            if (ok.test(c)) return Optional.of(new Step(c, DIRS[d][0], ny, DIRS[d][1]));
        }
        return Optional.empty();
    }
}
