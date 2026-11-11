package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 국도 wayfinding (README "국도", 길 안내 표지판): where roads meet and which way goes where, worked out from the saved
 * roads alone (their points and heights), so it also covers roads built before the network knew of branches (no
 * {@code branch=} line: forks are found where two centre lines part). Pure (unit-tested); RoadModule puts the plan up
 * (/관리자 국도 표지판 다시세우기, and once a road is finished).
 *
 * <ul>
 *   <li><b>Forks</b>: a road that starts on another one (its first points on that road's centre line) leaves it where they
 *       part. <b>Crossings</b>: where a road's middle runs over another's lanes at about its level (not an overpass).</li>
 *   <li>At each, the ways out (legs): along each road towards its dock, and back towards where it started (its terminal
 *       or the reference point, through the road it branched off).</li>
 *   <li><b>Advance signs</b> ({@link Type#ADVANCE}) on every way in, {@link #ADVANCE} points before the junction, on the
 *       right of the traffic coming in, facing it: a line for each other way, its arrow the turn it takes
 *       ({@link RoadSign.Arrow#of}), where it goes and how far from the sign.</li>
 *   <li><b>The gore sign</b> at a fork ({@link Type#GORE}): in the V between the two roads where they are
 *       {@link #GORE_GAP} apart, facing the traffic coming to the fork, both ways (↖ left, ↗ right).</li>
 *   <li><b>Confirmation</b> ({@link Type#CONFIRM}): a little past a junction, on each road towards its dock, its dock and
 *       distance, unless one of its own signs stands near.</li>
 *   <li><b>The way back</b> ({@link Type#RETURN}): on the other side, facing the traffic going back, where the road
 *       started and how far: just after leaving the dock, every {@link #RETURN_EVERY} markers, and past each junction.</li>
 *   <li>The road's own signs (start, every km, arrival, the words at the gate: as {@link RoadSection} puts them), so a
 *       road's signs can all be put up again.</li>
 * </ul>
 * A sign's posts never stand in any road's lanes, nor in another road's kerbs; signs never crowd each other
 * ({@link #GAP}): the more useful one stays.
 */
public final class RoadGuide {

    /** A built road: its dock, points (one a block), heights (half blocks), parts, number, and where it started (a name). */
    public record Road(String dock, List<double[]> points, int[] halves, RoadProfile.Part[] parts, int route, String home) {}

    /** What a sign is for, the more useful first (when two would stand too close, the first stays). */
    public enum Type { GORE, ADVANCE, START, ARRIVAL, CONFIRM, KM, RETURN }

    /**
     * A sign to put up: its point (between its posts, on the road's level: y = level + 1) and yaw; host the dock whose road
     * it stands by, also the other road's dock at a junction (null: none); the small sign's kind and words, or a guide
     * sign's rows; the columns of its posts (footings at level where open); the road and point it belongs to, and its side
     * (1: right of the road's way to its dock, -1: left, 0: in a gore).
     */
    public record Sign(Type type, String host, String also, double x, double y, double z, float yaw, RoadSign.Kind kind, RoadSign.Words words,
                       List<RoadSign.Row> rows, List<int[]> posts, int level, int road, int at, int side) {
        public boolean guide() {
            return rows != null;
        }
    }

    /** Words painted on a road ("하역장 입구"): as {@link RoadSection.Site#roadText}. */
    public record Paint(String host, double x, double y, double z, float yaw, String text) {}

    /** Where two roads meet: road a at its point ai, road b at bi; a fork (b leaves a there) or a level crossing. */
    public record Junction(int a, int ai, int b, int bi, boolean fork) {}

    public record Plan(List<Sign> signs, List<Paint> paints, List<Junction> junctions) {
        /** The signs a dock's road has a part in (its own and those at its junctions). */
        public List<Sign> about(String dock) {
            return signs.stream().filter(s -> dock.equals(s.host()) || dock.equals(s.also())).toList();
        }
    }

    /** Where signs go up and come down (the world, RoadModule; a test's own). */
    public interface Board {
        /** Every sign of a dock's road goes (also those at its junctions by another road): its set of signs moves on. */
        void clear(String dock);

        void put(Sign s);

        void paint(Paint p);
    }

    /**
     * Puts the signs of these docks' roads up again: first all their old ones go (their sets move on), then every planned
     * sign either of them has a part in, and their words on the road. Run twice, the second leaves what the first put.
     * How many signs went up.
     */
    public static int retrofit(Plan plan, java.util.Set<String> docks, Board board) {
        for (String d : new java.util.TreeSet<>(docks)) board.clear(d);
        int n = 0;
        for (Sign s : plan.signs()) {
            if (!docks.contains(s.host()) && (s.also() == null || !docks.contains(s.also()))) continue;
            board.put(s);
            n++;
        }
        for (Paint p : plan.paints()) if (docks.contains(p.host())) board.paint(p);
        return n;
    }

    /** Centre lines this close (blocks): the same road. A road's lanes, its kerbs (from the middle). */
    static final double ON = 2.0, LANES = 2.5, PAVED = 3.5;
    /** Right on another road's centre line (where a fork is). */
    static final double SAME = 0.5;
    /** How far before a junction its advance signs stand (points), the farther first. */
    static final int[] ADVANCE = {120, 40};
    /** How far along a leg its direction is taken (points), the least a leg may be. */
    static final int LEG = 24, LEG_MIN = 8;
    /** Turns sharper than this (degrees) are left off a sign (a U-turn). */
    static final double SHARPEST = 170;
    /** Confirmation: this far past the junction, unless the road's own sign stands within NEAR. */
    static final int CONFIRM_AT = 60, CONFIRM_NEAR = 300;
    /** The way back: this far from the dock, then every RETURN_EVERY markers. */
    static final int RETURN_FIRST = 60, RETURN_EVERY = 2;
    /** Signs on the same side of a road at least this far apart (points), any two at least SPACE (blocks). */
    static final int GAP = 25;
    static final double SPACE = 5;
    /** The gore sign stands where the two centre lines are this far apart (both roads' shoulders and room for it). */
    static final double GORE_GAP = 14;
    /** A crossing's run over the other road's lanes (points): longer is a road along another, not a crossing. */
    static final int CROSS_RUN = 30;
    /** At most this far apart (half blocks) is a level crossing; more is an overpass. */
    static final int LEVEL = 4;

    private final List<Road> roads;
    private final int every, lights;
    /** points by 8 x 8 cell: {road, index} */
    private final Map<Long, List<int[]>> grid = new HashMap<>();
    private final int[] parent, parentAt, own;
    /** each road's parts, where it goes over another road a bridge (no sign stands there) */
    private final RoadProfile.Part[][] parts;

    private RoadGuide(List<Road> roads, int every, int lights) {
        this.roads = roads;
        this.every = every;
        this.lights = Math.max(1, lights);
        int n = roads.size();
        parent = new int[n];
        parentAt = new int[n];
        own = new int[n];
        parts = new RoadProfile.Part[n][];
        for (int r = 0; r < n; r++) {
            List<double[]> pts = roads.get(r).points();
            for (int i = 0; i < pts.size(); i++) grid.computeIfAbsent(cell(pts.get(i)[0], pts.get(i)[1]), k -> new ArrayList<>()).add(new int[]{r, i});
            parts[r] = roads.get(r).parts().clone();
        }
        for (int r = 0; r < n; r++) {
            List<double[]> pts = roads.get(r).points();
            int[] h = roads.get(r).halves();
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    List<int[]> in = grid.get(RoadNetwork.key((int) Math.floor(p[0] / 8) + dx, (int) Math.floor(p[1] / 8) + dz));
                    if (in == null) continue;
                    for (int[] e : in) {
                        if (e[0] == r) continue;
                        double[] q = roads.get(e[0]).points().get(e[1]);
                        if (Math.hypot(q[0] - p[0], q[1] - p[1]) <= PAVED && h[i] - roads.get(e[0]).halves()[e[1]] > LEVEL) parts[r][i] = RoadProfile.Part.BRIDGE;
                    }
                }
            }
        }
    }

    private static long cell(double x, double z) {
        return RoadNetwork.key((int) Math.floor(x / 8), (int) Math.floor(z / 8));
    }

    // ------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------

    /** How far (x, z) is from road r's centre line (blocks), MAX_VALUE when it is more than about 8 away. */
    double dist(int r, double x, double z) {
        double best = Double.MAX_VALUE;
        int cx = (int) Math.floor(x / 8), cz = (int) Math.floor(z / 8);
        List<double[]> pts = roads.get(r).points();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<int[]> in = grid.get(RoadNetwork.key(cx + dx, cz + dz));
                if (in == null) continue;
                for (int[] e : in) {
                    if (e[0] != r) continue;
                    int i = e[1];
                    if (i + 1 < pts.size()) best = Math.min(best, segment(x, z, pts.get(i), pts.get(i + 1)));
                    if (pts.size() == 1) best = Math.min(best, Math.hypot(x - pts.get(i)[0], z - pts.get(i)[1]));
                }
            }
        }
        return best;
    }

    private static double segment(double x, double z, double[] a, double[] b) {
        double vx = b[0] - a[0], vz = b[1] - a[1], l = vx * vx + vz * vz;
        double t = l < 1e-12 ? 0 : Math.clamp(((x - a[0]) * vx + (z - a[1]) * vz) / l, 0, 1);
        return Math.hypot(x - a[0] - vx * t, z - a[1] - vz * t);
    }

    /** Road r's point nearest (x, z) among from..to. */
    int nearest(int r, double x, double z, int from, int to) {
        List<double[]> pts = roads.get(r).points();
        int best = Math.max(0, from);
        double bd = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i <= Math.min(pts.size() - 1, to); i++) {
            double d = Math.hypot(pts.get(i)[0] - x, pts.get(i)[1] - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** The way road r runs at point i (towards its dock), as RoadSection takes it: from 2 points back to 2 on. */
    double[] tangent(int r, int i) {
        List<double[]> pts = roads.get(r).points();
        double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(pts.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        return new double[]{hx / len, hz / len};
    }

    /** Whether a post may stand in this column: not in host's lanes, nor in any other road's lanes or kerbs. */
    boolean postOk(int host, int x, int z) {
        double cx = x + 0.5, cz = z + 0.5, dh = host < 0 ? Double.NaN : dist(host, cx, cz);
        for (int r = 0; r < roads.size(); r++) {
            double d = r == host ? dh : dist(r, cx, cz);
            // the host's kerb may take a post; so may another road's where it runs on the host (an old road built over it)
            boolean along = r == host || Math.abs(d - dh) < 1;
            if (d <= (along ? LANES : PAVED)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // The network: forks, crossings
    // ------------------------------------------------------------------------------------------

    /** How many of b's first points lie on a's centre line. */
    private int run(int b, int a) {
        List<double[]> pts = roads.get(b).points();
        int k = 0;
        while (k < pts.size() && dist(a, pts.get(k)[0], pts.get(k)[1]) <= ON) k++;
        return k;
    }

    /**
     * Each road's parent (the road it starts on and leaves: the one it runs along longest; between two that start
     * together, the older road (its lower number) is the trunk), where it leaves it (its own first point, the parent's
     * nearest).
     */
    private void forks() {
        int n = roads.size();
        int[] f = new int[n];
        java.util.Arrays.fill(parent, -1);
        for (int b = 0; b < n; b++) {
            int nb = roads.get(b).points().size();
            for (int a = 0; a < n; a++) {
                if (a == b) continue;
                int k = run(b, a);
                if (k == 0 || k >= nb - 40) continue;   // not on it, or all along it
                // where they part: the last of those points right on its centre line (a branch: its first point)
                int last = k - 1;
                while (last > 0 && dist(a, roads.get(b).points().get(last)[0], roads.get(b).points().get(last)[1]) > SAME) last--;
                double[] q = roads.get(b).points().get(last);
                int na = roads.get(a).points().size(), ai = nearest(a, q[0], q[1], 0, na - 1);
                if (ai >= na - 1 - LEG) continue;   // leaves at its end: it goes on from it, no fork
                if (parent[b] < 0 || last > f[b] || (last == f[b] && older(a, parent[b]))) {
                    parent[b] = a;
                    parentAt[b] = ai;
                    f[b] = last;
                }
            }
        }
        // two roads that start together: the older one is the trunk
        for (int b = 0; b < n; b++) {
            int a = parent[b];
            if (a >= 0 && parent[a] == b) {
                int trunk = older(a, b) ? a : b;
                parent[trunk] = -1;
            }
        }
        for (int b = 0; b < n; b++) own[b] = parent[b] < 0 ? 0 : f[b];
    }

    private boolean older(int a, int b) {
        Road x = roads.get(a), y = roads.get(b);
        if (x.route() != y.route()) return x.route() > 0 && (y.route() <= 0 || x.route() < y.route());
        return x.dock().compareTo(y.dock()) < 0;
    }

    /** The level crossings: where b's middle runs over a's lanes for a short run, about level (not an overpass). */
    private List<Junction> crossings() {
        List<Junction> out = new ArrayList<>();
        int n = roads.size();
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                if (a >= b) continue;
                Road ra = roads.get(a), rb = roads.get(b);
                List<double[]> pb = rb.points();
                int i = own[b];
                while (i < pb.size()) {
                    if (dist(a, pb.get(i)[0], pb.get(i)[1]) > LANES) {
                        i++;
                        continue;
                    }
                    int s = i;
                    while (i < pb.size() && dist(a, pb.get(i)[0], pb.get(i)[1]) <= LANES) i++;
                    if (i - s > CROSS_RUN) continue;
                    int bi = (s + i - 1) / 2;
                    double[] q = pb.get(bi);
                    int ai = nearest(a, q[0], q[1], 0, ra.points().size() - 1);
                    if (ai < own[a] + 5 || bi < own[b] + 5) continue;                                   // where they run together
                    if (forkNear(a, ai, b, bi)) continue;                                                // the fork itself
                    if (Math.abs(rb.halves()[bi] - ra.halves()[ai]) > LEVEL) continue;                  // an overpass
                    out.add(new Junction(a, ai, b, bi, false));
                }
            }
        }
        return out;
    }

    private boolean forkNear(int a, int ai, int b, int bi) {
        return (parent[b] == a && Math.abs(bi - own[b]) <= 30) || (parent[a] == b && Math.abs(ai - own[a]) <= 30);
    }

    /** How far back to where road r started from its point i (blocks): along it, then along the road it left. */
    double back(int r, int i) {
        double d = 0;
        for (int depth = 0; depth < 16; depth++) {
            if (parent[r] < 0) return d + i;
            d += i - own[r];
            i = parentAt[r];
            r = parent[r];
        }
        return d + i;
    }

    /** The name of where road r started: its root's (a road that branches off another: that one's). */
    String home(int r) {
        for (int depth = 0; depth < 16 && parent[r] >= 0; depth++) r = parent[r];
        return roads.get(r).home();
    }

    // ------------------------------------------------------------------------------------------
    // Ways out of a junction
    // ------------------------------------------------------------------------------------------

    /** A way out of a junction: along road r from point at (dir 1 towards its dock, -1 back), its heading, where it goes, how far (blocks). */
    record Leg(int road, int at, int dir, double dx, double dz, String name, double far) {}

    private Leg leg(int r, int at, int dir) {
        List<double[]> pts = roads.get(r).points();
        int avail = dir > 0 ? pts.size() - 1 - at : at;
        int k = Math.min(avail, LEG);
        if (k < LEG_MIN) return null;
        double[] p = pts.get(at), q = pts.get(at + dir * k);
        double dx = q[0] - p[0], dz = q[1] - p[1], len = Math.hypot(dx, dz);
        if (len < 1e-6) return null;
        Road road = roads.get(r);
        return dir > 0 ? new Leg(r, at, 1, dx / len, dz / len, road.dock() + " 하역장", pts.size() - 1 - at)
                : new Leg(r, at, -1, dx / len, dz / len, home(r), back(r, at));
    }

    /** The turn (degrees, minus to the left) from heading (hx, hz) onto (ex, ez). */
    static double turn(double hx, double hz, double ex, double ez) {
        double right = ex * -hz + ez * hx, ahead = ex * hx + ez * hz;
        return Math.toDegrees(Math.atan2(right, ahead));
    }

    /** The lines for traffic coming in along leg in (heading against it): every other way, left to right, from `extra` before. */
    private List<RoadSign.Row> rows(Leg in, List<Leg> legs, double extra) {
        double hx = -in.dx(), hz = -in.dz();
        List<double[]> order = new ArrayList<>();
        List<Leg> outs = new ArrayList<>();
        for (Leg e : legs) {
            if (e == in) continue;
            double t = turn(hx, hz, e.dx(), e.dz());
            if (Math.abs(t) > SHARPEST) continue;
            order.add(new double[]{t, outs.size()});
            outs.add(e);
        }
        order.sort(Comparator.comparingDouble(o -> o[0]));
        List<RoadSign.Row> out = new ArrayList<>();
        for (double[] o : order) {
            Leg e = outs.get((int) o[1]);
            out.add(new RoadSign.Row(RoadSign.Arrow.of(o[0]), e.name(), RoadSign.km((e.far() + extra) / 1000.0)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // The plan
    // ------------------------------------------------------------------------------------------

    /** markerEvery, lightEvery: roads.marker-every, roads.light-every (the guide's own signs keep clear of the lights). */
    public static Plan plan(List<Road> roads, int markerEvery, int lightEvery) {
        return new RoadGuide(roads, markerEvery, lightEvery).plan();
    }

    private Plan plan() {
        forks();
        List<Junction> junctions = new ArrayList<>();
        for (int b = 0; b < roads.size(); b++) if (parent[b] >= 0) junctions.add(new Junction(parent[b], parentAt[b], b, own[b], true));
        junctions.addAll(crossings());
        List<Sign> signs = new ArrayList<>();
        List<Paint> paints = new ArrayList<>();
        Map<Integer, List<Integer>> at = new HashMap<>();   // road -> its junction points
        for (Junction j : junctions) {
            at.computeIfAbsent(j.a(), k -> new ArrayList<>()).add(j.ai());
            at.computeIfAbsent(j.b(), k -> new ArrayList<>()).add(j.bi());
            junction(j, signs);
        }
        for (int r = 0; r < roads.size(); r++) own(r, at.getOrDefault(r, List.of()), signs, paints);
        return new Plan(spread(signs), paints, junctions);
    }

    private void junction(Junction j, List<Sign> signs) {
        List<Leg> legs = new ArrayList<>();
        legs.add(leg(j.a(), j.ai(), -1));
        legs.add(leg(j.a(), j.ai(), 1));
        if (!j.fork()) legs.add(leg(j.b(), j.bi(), -1));   // a fork's branch has no way back of its own: it is the trunk's
        legs.add(leg(j.b(), j.bi(), 1));
        legs.removeIf(java.util.Objects::isNull);
        // advance signs on every way in
        for (Leg in : legs) {
            int other = in.road() == j.a() ? j.b() : j.a();
            for (int d : ADVANCE) {
                Road road = roads.get(in.road());
                int n = road.points().size();
                int idx = spot(in.road(), in.at() + in.dir() * d, in.dir());
                if (idx < 0 || idx < own[in.road()] + 3 || idx > n - 4) continue;
                List<RoadSign.Row> rows = rows(in, legs, Math.abs(idx - in.at()));
                if (rows.size() < 2) continue;   // nothing to choose
                Sign s = side(Type.ADVANCE, in.road(), idx, -in.dir(), true, roads.get(other).dock(), null, null, rows);
                if (s != null) signs.add(s);
            }
        }
        if (j.fork()) gore(j, legs, signs);
        // confirmation on each road towards its dock
        for (Leg out : legs) {
            if (out.dir() < 0) continue;
            int r = out.road();
            Road road = roads.get(r);
            boolean near = false;
            for (int k : RoadSection.signs(parts[r], every).keySet()) near |= k >= out.at() + 10 && k <= out.at() + CONFIRM_NEAR;
            if (near) continue;
            int idx = spot(r, out.at() + CONFIRM_AT, 1);
            int n = road.points().size();
            if (idx < 0 || idx > n - 1 - RoadSection.SIGN_END - 10) continue;
            RoadSign.Words w = RoadSign.words(RoadSign.Kind.KM, road.dock(), (n - 1 - idx) / 1000.0, road.route());
            Sign s = side(Type.CONFIRM, r, idx, 1, false, roads.get(r == j.a() ? j.b() : j.a()).dock(), RoadSign.Kind.KM, w, null);
            if (s != null) signs.add(s);
        }
    }

    /** The gore sign: in the V between the trunk and the branch, facing the traffic coming to the fork. */
    private void gore(Junction j, List<Leg> legs, List<Sign> signs) {
        Leg in = null, trunk = null, branch = null;
        for (Leg l : legs) {
            if (l.road() == j.a() && l.dir() < 0) in = l;
            else if (l.road() == j.a()) trunk = l;
            else branch = l;
        }
        if (in == null || trunk == null || branch == null) return;
        double hx = -in.dx(), hz = -in.dz();
        Road a = roads.get(j.a()), b = roads.get(j.b());
        List<double[]> pa = a.points(), pb = b.points();
        for (int k = j.bi() + 1; k <= Math.min(pb.size() - 1, j.bi() + 100); k++) {
            double[] q = pb.get(k);
            int ak = nearest(j.a(), q[0], q[1], j.ai() - 20, j.ai() + 160);
            double[] p = pa.get(ak);
            if (Math.hypot(q[0] - p[0], q[1] - p[1]) < GORE_GAP) continue;
            if (parts[j.b()][k] != RoadProfile.Part.ROAD || parts[j.a()][ak] != RoadProfile.Part.ROAD) return;   // on a bridge: none
            double mx = (q[0] + p[0]) / 2, mz = (q[1] + p[1]) / 2;
            double rx = -hz, rz = hx;
            List<int[]> posts = new ArrayList<>();
            boolean ok = true;
            for (int s = -1; s <= 1 && ok; s += 2) {
                int x = (int) Math.floor(mx + rx * s * RoadSign.GUIDE_POSTS), z = (int) Math.floor(mz + rz * s * RoadSign.GUIDE_POSTS);
                ok = postOk(-1, x, z);
                posts.add(new int[]{x, z});
            }
            if (!ok) continue;
            double ta = turn(hx, hz, trunk.dx(), trunk.dz()), tb = turn(hx, hz, branch.dx(), branch.dz());
            Leg left = ta <= tb ? trunk : branch, right = left == trunk ? branch : trunk;
            double fromA = Math.abs(ak - j.ai()), fromB = k - j.bi();
            List<RoadSign.Row> rows = List.of(
                    new RoadSign.Row(RoadSign.Arrow.UP_LEFT, left.name(), RoadSign.km((left.far() - (left == trunk ? fromA : fromB)) / 1000.0)),
                    new RoadSign.Row(RoadSign.Arrow.UP_RIGHT, right.name(), RoadSign.km((right.far() - (right == trunk ? fromA : fromB)) / 1000.0)));
            int level = Math.max(RoadProfile.block(a.halves()[ak]), RoadProfile.block(b.halves()[k]));
            signs.add(new Sign(Type.GORE, a.dock(), b.dock(), mx, level + 1, mz, RoadSign.yaw(-hx, -hz), null, null, rows, posts, level, j.a(), ak, 0));
            return;
        }
    }

    /**
     * Where one of the guide's signs on road r may stand, from point `from` on (stepping dir, at most 40): plain road 3
     * points either way (RoadSection's rule), and 3 or more points from a street light (every roads.light-every points,
     * on either side: the road's own signs make its light change sides, these come later). -1: none.
     */
    int spot(int r, int from, int dir) {
        RoadProfile.Part[] p = parts[r];
        for (int k = 0; k < 40; k++) {
            int i = from + k * dir;
            if (i - 3 < 0 || i + 3 >= p.length) return -1;
            int m = Math.floorMod(i, lights);
            if (Math.min(m, lights - m) < 3) continue;
            if (RoadSection.plain(p, i, dir, 1) == i) return i;
        }
        return -1;
    }

    /**
     * A sign by road r at point i for the traffic heading `way` (1: towards the dock, -1: back), on its right, facing it;
     * null where a post may not stand.
     */
    private Sign side(Type type, int r, int i, int way, boolean guide, String also, RoadSign.Kind kind, RoadSign.Words words, List<RoadSign.Row> rows) {
        Road road = roads.get(r);
        double[] p = road.points().get(i), t = tangent(r, i);
        double hx = t[0] * way, hz = t[1] * way, rx = -hz, rz = hx;
        double lat = guide ? RoadSign.GUIDE_LATERAL : RoadSign.LATERAL, gap = guide ? RoadSign.GUIDE_POSTS : RoadSign.POSTS;
        List<int[]> posts = new ArrayList<>();
        for (int s = -1; s <= 1; s += 2) {
            int x = (int) Math.floor(p[0] + rx * (lat + s * gap)), z = (int) Math.floor(p[1] + rz * (lat + s * gap));
            if (!postOk(r, x, z)) return null;
            posts.add(new int[]{x, z});
        }
        int level = RoadProfile.block(road.halves()[i]);
        return new Sign(type, road.dock(), also, p[0] + rx * lat, level + 1, p[1] + rz * lat, RoadSign.yaw(-hx, -hz), kind, words, rows, posts,
                level, r, i, way);
    }

    /** A road's own signs (as RoadSection puts them), the words at its gate, and its signs for the way back. */
    private void own(int r, List<Integer> junctions, List<Sign> signs, List<Paint> paints) {
        Road road = roads.get(r);
        int n = road.points().size();
        for (Map.Entry<Integer, RoadSign.Kind> e : RoadSection.signs(parts[r], every).entrySet()) {
            int i = e.getKey();
            if (i < own[r] + 10) continue;   // on the stretch it shares with the road it left: that road's signs
            Type type = switch (e.getValue()) {
                case START -> Type.START;
                case ARRIVAL -> Type.ARRIVAL;
                default -> Type.KM;
            };
            Sign s = side(type, r, i, 1, false, null, e.getValue(), RoadSign.words(e.getValue(), road.dock(), (n - 1 - i) / 1000.0, road.route()), null);
            if (s != null) signs.add(s);
        }
        // the words painted before the gate (RoadSection: where it has widened)
        int w = n - 1 - RoadSection.WORDS_AT;
        if (n >= RoadSection.WIDEN_FROM * 2 && w >= 0 && parts[r][w] == RoadProfile.Part.ROAD) {
            double[] p = road.points().get(w), t = tangent(r, w);
            paints.add(new Paint(road.dock(), p[0], RoadSection.top(road.halves()[w]) + 0.01, p[1], RoadSign.yaw(-t[0], -t[1]), RoadSection.GATE_WORDS));
        }
        // the way back: leaving the dock, every few markers, past each junction
        List<Integer> back = new ArrayList<>();
        for (int i = n - 1 - RETURN_FIRST; i >= 0; i -= every * RETURN_EVERY) back.add(i);
        for (int j : junctions) back.add(j - CONFIRM_AT);
        for (int want : back) {
            if (want < own[r] + 30 || want > n - 1 - 40) continue;
            int i = spot(r, want, -1);
            if (i < own[r] + 10) continue;
            Sign s = side(Type.RETURN, r, i, -1, false, null, RoadSign.Kind.RETURN,
                    RoadSign.words(RoadSign.Kind.RETURN, home(r), back(r, i) / 1000.0, road.route()), null);
            if (s != null) signs.add(s);
        }
    }

    /** The signs kept: the more useful first, none within GAP points on the same side of the same road, nor SPACE of another. */
    private static List<Sign> spread(List<Sign> signs) {
        List<Sign> sorted = new ArrayList<>(signs);
        sorted.sort(Comparator.comparingInt(s -> s.type().ordinal()));   // stable: in the order they were found
        List<Sign> kept = new ArrayList<>();
        for (Sign s : sorted) {
            boolean ok = true;
            for (Sign k : kept) {
                if (s.side() != 0 && k.road() == s.road() && k.side() == s.side() && Math.abs(k.at() - s.at()) < GAP) ok = false;
                if (Math.hypot(k.x() - s.x(), k.z() - s.z()) < SPACE) ok = false;
                if (!ok) break;
            }
            if (ok) kept.add(s);
        }
        return kept;
    }
}
