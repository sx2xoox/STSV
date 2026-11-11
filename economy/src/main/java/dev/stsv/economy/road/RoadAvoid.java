package dev.stsv.economy.road;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국도 PLAN with the protected places kept out ({@link RoadGuard}): A* over the surveyed cells with every protected cell
 * shut (a cell an old road runs through stays open: following it builds nothing, {@link RoadNetwork#branch}), then the
 * road's surface checked block by block ({@link #firstProtected}): where the route, pulled straight, runs within reach of
 * a shut cell, it is pulled straight less (lines a cell away from shut cells), else it runs cell by cell through their
 * middles (8 blocks from any other cell). Where that is not enough the start, the straight way out of a terminal or the
 * run up to the gate is in protected land ({@link Kind#ENDS}). The route is also looked for with nothing shut, to tell the
 * admin how far protected land made it go round, or that it alone closed the way. Pure (unit-tested).
 */
final class RoadAvoid {

    private RoadAvoid() {}

    enum Kind {
        /** a route that touches nothing protected */
        OK,
        /** no way through the corridor at all (water, steepness, its edge), protection or not */
        NO_ROUTE,
        /** protected land closes every way */
        BLOCKED,
        /** the start, the straight way out of a terminal or the last stretch to the gate is in protected land */
        ENDS
    }

    /**
     * @param avoided    the protected cells (by {@link RoadGuard.Why#code}: [1] player, [2] server, [3] boxes) the route
     *                   would have crossed with nothing shut
     * @param freeLength how long (blocks, about) the route with nothing shut is; 0: there is none
     * @param length     how long (blocks, about) the route found is (before a branch is cut off)
     * @param at         where it hit protected land (x, z), or null
     */
    record Result(Kind kind, List<int[]> cells, RoadNetwork.Branch branch, int[] avoided, double freeLength, double length, int[] at,
                  RoadGuard.Why why, int rounds) {
        boolean detoured() {
            return kind == Kind.OK && avoided[1] + avoided[2] + avoided[3] > 0;
        }
    }

    static long key(int x, int z) {
        return RoadJob.key(x, z);
    }

    /**
     * Plans the job's route (job.route: its corners, the start moved onto a road it branches off): the cells with their
     * reasons ({@link RoadJob.Cell#why}) shut, the blocks of its surface checked against the guard in the job's world.
     */
    static Result plan(RoadJob job, RoadNetwork net, RoadGuard guard, int lightEvery, int maxNodes) {
        double[] from = job.from();
        int sx = (int) Math.floor(from[0]) >> 4, sz = (int) Math.floor(from[1]) >> 4;
        int ex = (int) Math.floor(job.endX) >> 4, ez = (int) Math.floor(job.endZ) >> 4;
        long startK = key(sx, sz), endK = key(ex, ez);
        RoadPlanner.Field base = job.field(net);
        RoadPlanner.Weights w = RoadPlanner.Weights.DEFAULT;
        // nothing shut: how it would have gone
        List<int[]> free = RoadPlanner.path(shut(base, k -> false), sx, sz, ex, ez, w, maxNodes);
        int[] avoided = new int[4];
        int[] example = null;
        RoadGuard.Why exampleWhy = null;
        for (int i = 1; i < free.size() - 1; i++) {
            int[] c = free.get(i);
            RoadJob.Cell cell = job.cells.get(key(c[0], c[1]));
            if (cell == null || !cell.blocked() || net.cell(c[0], c[1])) continue;
            if (cell.why() >= 1 && cell.why() <= 3) avoided[cell.why()]++;
            if (example == null) {
                example = new int[]{c[0] * 16 + 8, c[1] * 16 + 8};
                exampleWhy = RoadGuard.Why.of(cell.why());
            }
        }
        double freeLength = free.isEmpty() ? 0 : length(from, RoadPlanner.smooth(shut(base, k -> false), free, w), job.endX, job.endZ);
        RoadPlanner.Field f = shut(base, k -> {
            if (k == startK || k == endK) return false;   // where it must start and end: checked by block below
            RoadJob.Cell c = job.cells.get(k);
            return c != null && c.blocked() && !net.cell(RoadJob.cx(k), RoadJob.cz(k));
        });
        List<int[]> cells = RoadPlanner.path(f, sx, sz, ex, ez, w, maxNodes);
        if (cells.isEmpty()) {
            if (free.isEmpty()) return new Result(Kind.NO_ROUTE, List.of(), null, avoided, 0, 0, null, null, 0);
            return new Result(Kind.BLOCKED, List.of(), null, avoided, freeLength, 0, example, exampleWhy, 0);
        }
        double length = length(from, RoadPlanner.smooth(f, cells, w), job.endX, job.endZ);
        job.branchOf = null;
        RoadNetwork.Branch b = net.branch(cells, job.endX, job.endZ, lightEvery);
        List<int[]> route = cells;
        if (b != null) {
            job.startX = b.x();
            job.startZ = b.z();
            job.branchOf = b.dock();
            job.branchKm = b.left() / 1000.0;
            route = cells.subList(b.cell(), cells.size());
        }
        // pulled straight (round 1); a straight stretch that runs too near a shut cell: lines kept a cell away from shut
        // cells (2); else cell by cell through their middles, 8 blocks from any other cell (3)
        RoadPlanner.Field strict = shut(base, k -> {
            if (k == startK || k == endK) return false;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (f.blocked(RoadJob.cx(k) + dx, RoadJob.cz(k) + dz)) return true;
            return false;
        });
        Hit hit = null;
        for (int round = 1; round <= 3; round++) {
            job.route(round == 1 ? RoadPlanner.smooth(f, route, w) : round == 2 ? RoadPlanner.smooth(strict, route, w) : route);
            List<double[]> pts = job.points();
            hit = firstProtected(pts, guard, job.world);
            if (hit == null) return new Result(Kind.OK, route, b, avoided, freeLength, length, null, null, round);
            long hk = key(hit.x >> 4, hit.z >> 4);
            if (hk == startK || hk == endK || fixedStretch(job, pts, hit.point)) break;   // it cannot go anywhere else
        }
        return new Result(Kind.ENDS, route, b, avoided, freeLength, length, new int[]{hit.x, hit.z}, hit.why, 3);
    }

    /** Whether point i is on a stretch the route cannot move: the straight way out of a terminal, the run up to the gate. */
    static boolean fixedStretch(RoadJob job, List<double[]> pts, int i) {
        double[] p = pts.get(i);
        if (job.led() && Math.hypot(p[0] - job.startX, p[1] - job.startZ) <= RoadModule.LEAD + 1) return true;
        return Math.hypot(p[0] - job.endX, p[1] - job.endZ) <= 1 || (job.gated() && segment(p, job.endX, job.endZ, job.gateX, job.gateZ) <= 1);
    }

    private static double segment(double[] p, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.clamp(((p[0] - ax) * dx + (p[1] - az) * dz) / l2, 0, 1);
        return Math.hypot(p[0] - ax - t * dx, p[1] - az - t * dz);
    }

    /** A field with cells shut by `closed` (a cell's key) instead of their own reasons. */
    static RoadPlanner.Field shut(RoadPlanner.Field f, java.util.function.LongPredicate closed) {
        return new RoadPlanner.Field() {
            public boolean known(int cx, int cz) {
                return f.known(cx, cz);
            }

            public double height(int cx, int cz) {
                return f.height(cx, cz);
            }

            public double water(int cx, int cz) {
                return f.water(cx, cz);
            }

            public double rough(int cx, int cz) {
                return f.rough(cx, cz);
            }

            public boolean blocked(int cx, int cz) {
                return closed.test(key(cx, cz));
            }

            public boolean road(int cx, int cz) {
                return f.road(cx, cz);
            }

            public boolean nearRoad(int cx, int cz) {
                return f.nearRoad(cx, cz);
            }
        };
    }

    /** A route's length (blocks): from the start through the middles of its corner cells (ends left out) to the end. */
    static double length(double[] from, List<int[]> corners, double endX, double endZ) {
        double len = 0, x = from[0], z = from[1];
        for (int i = 1; i < corners.size() - 1; i++) {
            double nx = corners.get(i)[0] * 16 + 8.5, nz = corners.get(i)[1] * 16 + 8.5;
            len += Math.hypot(nx - x, nz - z);
            x = nx;
            z = nz;
        }
        return len + Math.hypot(endX - x, endZ - z);
    }

    /** A block the road's surface would touch in protected land: the point, the column, why. */
    record Hit(int point, int x, int z, RoadGuard.Why why) {}

    /** How far from a point its surface and kerbs reach (blocks), and at the gate (it widens: {@link RoadSection#WIDEN}). */
    static final double REACH = 4, REACH_GATE = 4 + RoadSection.WIDEN;

    /**
     * The first point whose surface (lanes and kerbs, a disc round it) touches a protected column, or null. The builder stops
     * there ({@link RoadSection#build}); a shoulder in protected land is only left alone, so it is not looked at.
     */
    static Hit firstProtected(List<double[]> pts, RoadGuard guard, String world) {
        Map<Long, Integer> land = new HashMap<>();
        Map<Long, Boolean> boxFree = new HashMap<>();
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            double r = i >= n - 1 - RoadSection.WIDEN_FROM ? REACH_GATE : REACH;
            int x0 = (int) Math.floor(p[0] - r), x1 = (int) Math.floor(p[0] + r), z0 = (int) Math.floor(p[1] - r), z1 = (int) Math.floor(p[1] + r);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (Math.hypot(x + 0.5 - p[0], z + 0.5 - p[1]) > r) continue;
                    long ck = key(x >> 4, z >> 4);
                    int why = land.computeIfAbsent(ck, k -> {
                        RoadGuard.Why wv = guard.land(world, RoadJob.cx(k), RoadJob.cz(k));
                        return wv == null ? 0 : wv.code;
                    });
                    if (why != 0) return new Hit(i, x, z, RoadGuard.Why.of(why));
                    if (boxFree.computeIfAbsent(ck, k -> guard.boxFree(world, RoadJob.cx(k), RoadJob.cz(k)))) continue;
                    RoadGuard.Why c = guard.column(world, x, z);
                    if (c != null) return new Hit(i, x, z, c);
                }
            }
        }
        return null;
    }
}
