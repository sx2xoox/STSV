package dev.stsv.economy.bus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * A 버스 내비게이션 route (README "마을버스", 내비게이션): the road points from where the bus joined the 국도 to the target stop's
 * point, the shortest way over the road network ({@link BusRoutes#tree}, the same graph a fare is counted on), with its
 * manoeuvres worked out once: every junction the way passes (a cluster of joined points counts once) and which way it goes
 * there (-1 left, 0 straight on, +1 right). Pure (unit-tested); {@link BusNavigator} drives it.
 */
public final class NavRoute {

    /** A manoeuvre: where on the way (an index), how far along it (blocks), which way. */
    public record Turn(int index, double along, int turn) {}

    /** A manoeuvre coming: how far (blocks) and which way. */
    public record Ahead(Turn turn, double distance) {}

    /** How many points either side of a junction (cluster) its turn is measured over. */
    static final int REACH = 8;
    /** Joined points this close along the way (blocks) are one junction. */
    static final double CLUSTER = 12;
    /** Less than this many degrees is straight on. */
    static final double STRAIGHT_DEG = 25;

    final double[] xs, zs, along;
    final int[] halves;
    final List<Turn> turns = new ArrayList<>();

    public NavRoute(double[] xs, double[] zs, int[] halves, boolean[] junction) {
        this.xs = xs;
        this.zs = zs;
        this.halves = halves;
        along = new double[xs.length];
        for (int i = 1; i < xs.length; i++) along[i] = along[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        for (int i = 0; i < xs.length; i++) {
            if (!junction[i]) continue;
            int j = i;
            while (j + 1 < xs.length && (junction[j + 1] || (j + 2 < xs.length && junction[j + 2])) && along[j + 1] - along[i] <= CLUSTER) j++;
            int mid = (i + j) / 2;
            if (i > 0 && j < xs.length - 1) turns.add(new Turn(mid, along[mid], turnAt(i, j)));
            i = j;
        }
    }

    /** The route along these nodes of the road graph. */
    public static NavRoute of(BusRoutes r, int[] path) {
        double[] x = new double[path.length], z = new double[path.length];
        int[] h = new int[path.length];
        boolean[] jn = new boolean[path.length];
        for (int i = 0; i < path.length; i++) {
            double[] p = r.xz(path[i]);
            x[i] = p[0];
            z[i] = p[1];
            h[i] = r.half(path[i]);
            jn[i] = r.junction(path[i]);
        }
        return new NavRoute(x, z, h, jn);
    }

    public int size() {
        return xs.length;
    }

    public double length() {
        return xs.length == 0 ? 0 : along[xs.length - 1];
    }

    /** The road distance left from way index i to the end. */
    public double remaining(int i) {
        return xs.length == 0 ? 0 : length() - along[clamp(i)];
    }

    public List<Turn> turns() {
        return List.copyOf(turns);
    }

    int clamp(int i) {
        return Math.max(0, Math.min(xs.length - 1, i));
    }

    /** x, z and the road's surface top (y) at way index i. */
    public double[] point(int i) {
        int k = clamp(i);
        return new double[]{xs[k], zs[k], halves[k] / 2.0 + 1};
    }

    /** Which way the way turns between the points before index a and after index b: -1 left, 0 straight on, +1 right. */
    int turnAt(int a, int b) {
        int p = clamp(a - REACH), q = clamp(b + REACH);
        double ax = xs[a] - xs[p], az = zs[a] - zs[p], bx = xs[q] - xs[b], bz = zs[q] - zs[b];
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        if (la < 1e-6 || lb < 1e-6) return 0;
        double cross = (ax * bz - az * bx) / (la * lb), dot = (ax * bx + az * bz) / (la * lb);
        // Minecraft: x east, z south; looking along (ax, az), a positive cross product is to the right
        return Math.abs(Math.toDegrees(Math.atan2(cross, dot))) < STRAIGHT_DEG ? 0 : cross > 0 ? 1 : -1;
    }

    /**
     * The way index nearest (x, z), looked for around `hint` (the last one: a bus moves a few blocks between looks; -1 = the
     * whole way). out[0] = how far the bus is off the way (to its nearest segment, blocks).
     */
    public int nearest(double x, double z, int hint, double[] out) {
        int lo = 0, hi = xs.length - 1;
        if (hint >= 0) {
            lo = Math.max(0, hint - 60);
            hi = Math.min(xs.length - 1, hint + 240);
        }
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = lo; i <= hi; i++) {
            double d = (xs[i] - x) * (xs[i] - x) + (zs[i] - z) * (zs[i] - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (hint >= 0 && Math.sqrt(bd) > 30) return nearest(x, z, -1, out);   // jumped (a teleport, a short cut): look everywhere
        if (out != null && out.length > 0) out[0] = off(x, z, best);
        return best;
    }

    /** How far (x, z) is from the way's segments next to index i (blocks). */
    public double off(double x, double z, int i) {
        if (xs.length == 0) return Double.POSITIVE_INFINITY;
        if (xs.length == 1) return Math.hypot(xs[0] - x, zs[0] - z);
        double best = Double.MAX_VALUE;
        for (int a = Math.max(0, i - 1); a <= Math.min(xs.length - 2, i); a++) best = Math.min(best, segment(x, z, a));
        return best;
    }

    private double segment(double x, double z, int a) {
        double ax = xs[a], az = zs[a], bx = xs[a + 1], bz = zs[a + 1];
        double dx = bx - ax, dz = bz - az, len = dx * dx + dz * dz;
        double t = len < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len));
        return Math.hypot(ax + dx * t - x, az + dz * t - z);
    }

    /** The way index `ahead` blocks further along than index i (the end at most). */
    public int indexAhead(int i, double ahead) {
        int k = clamp(i);
        double want = along[k] + ahead;
        while (k + 1 < xs.length && along[k] < want) k++;
        return k;
    }

    /** The next junction ahead of way index i within `within` blocks (straight on included), or null. */
    public Ahead nextTurn(int i, double within) {
        return next(i, within, false);
    }

    /** The next real manoeuvre (a left or right turn) ahead of way index i within `within` blocks, or null. */
    public Ahead nextManoeuvre(int i, double within) {
        return next(i, within, true);
    }

    private Ahead next(int i, double within, boolean turning) {
        double at = along[clamp(i)];
        for (Turn t : turns) {
            if (t.index() <= i || (turning && t.turn() == 0)) continue;
            double d = t.along() - at;
            return d > within ? null : new Ahead(t, d);
        }
        return null;
    }

    /** An arrow of 8 (↑ ahead, → right ...) from a vehicle at (x, z) facing yaw (Minecraft degrees) towards (tx, tz). */
    public static String arrow(double x, double z, float yaw, double tx, double tz) {
        double dx = tx - x, dz = tz - z;
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
        double deg = Math.toDegrees(Math.atan2(dx * rx + dz * rz, dx * fx + dz * fz));
        return switch (Math.floorMod((int) Math.round(deg / 45.0), 8)) {
            case 0 -> "↑";
            case 1 -> "↗";
            case 2 -> "→";
            case 3 -> "↘";
            case 4 -> "↓";
            case 5 -> "↙";
            case 6 -> "←";
            default -> "↖";
        };
    }

    /**
     * 손님 순서대로: of the stops the riders get off at, the one nearest by road (`road`: its road distance, +inf = no way), or
     * null (no one to drop / no way to any). `except`: a stop not to pick (where the bus stands while they get off), or null.
     */
    public static Integer nearestDrop(Collection<Integer> dests, ToDoubleFunction<Integer> road, Integer except) {
        Integer best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (Integer s : dests) {
            if (s == null || s < 0 || s.equals(except)) continue;
            double d = road.applyAsDouble(s);
            if (d < bd || (d == bd && best != null && s < best)) {
                bd = d;
                best = s;
            }
        }
        return Double.isFinite(bd) ? best : null;
    }

    /**
     * The whole 손님 순서 from a stop (or the bus's place: `first`, each stop's road distance from it): nearest first, then the
     * nearest to that by road between stops (`between`), till every reachable one is in.
     */
    public static List<Integer> chain(Collection<Integer> dests, ToDoubleFunction<Integer> first, java.util.function.ToDoubleBiFunction<Integer, Integer> between) {
        List<Integer> left = new ArrayList<>(new java.util.LinkedHashSet<>(dests));
        left.removeIf(s -> s == null || s < 0);
        List<Integer> out = new ArrayList<>();
        Integer at = nearestDrop(left, first, null);
        while (at != null) {
            out.add(at);
            left.remove(at);
            Integer from = at;
            at = nearestDrop(left, s -> between.applyAsDouble(from, s), null);
        }
        return out;
    }
}
