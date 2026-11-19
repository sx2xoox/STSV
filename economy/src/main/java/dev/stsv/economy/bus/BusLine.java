package dev.stsv.economy.bus;

import java.util.ArrayList;
import java.util.List;

/**
 * A 마을버스 노선 (README "마을버스"): one 국도's way from the terminal to its dock (through the road it branches off), with the
 * stops on it in order. A bus runs it one way or the other ({@code dir} +1 = towards the dock, -1 = towards the terminal):
 * its passengers only go to stops ahead, the driver is shown the next stop, which way the road goes and the junctions
 * coming. Pure (unit-tested).
 */
public final class BusLine {

    public final int number;
    public final String dock;
    final double[] xs, zs, along;
    final boolean[] junction;
    /** stop ids in order along the way, and where each is (an index of the way) */
    final List<Integer> stopIds = new ArrayList<>();
    final List<Integer> stopAt = new ArrayList<>();

    public BusLine(int number, String dock, double[] xs, double[] zs, boolean[] junction) {
        this.number = number;
        this.dock = dock;
        this.xs = xs;
        this.zs = zs;
        this.junction = junction;
        along = new double[xs.length];
        for (int i = 1; i < xs.length; i++) along[i] = along[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
    }

    /** Puts a stop on the line at way index i (stops are kept in order). */
    public void addStop(int stopId, int i) {
        int k = 0;
        while (k < stopAt.size() && stopAt.get(k) < i) k++;
        stopIds.add(k, stopId);
        stopAt.add(k, i);
    }

    public String name() {
        return number + "번 " + dock + "선";
    }

    public List<Integer> stops() {
        return List.copyOf(stopIds);
    }

    public boolean has(int stopId) {
        return stopIds.contains(stopId);
    }

    public int size() {
        return xs.length;
    }

    /** The way index of a stop on the line, or -1. */
    public int at(int stopId) {
        int k = stopIds.indexOf(stopId);
        return k < 0 ? -1 : stopAt.get(k);
    }

    /** Road distance between two way indexes (blocks). */
    public double between(int i, int j) {
        return Math.abs(along[clamp(i)] - along[clamp(j)]);
    }

    private int clamp(int i) {
        return Math.max(0, Math.min(xs.length - 1, i));
    }

    /** The way index nearest (x, z), and how far that is (blocks) in out[0]. */
    public int nearest(double x, double z, double[] out) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            double d = (xs[i] - x) * (xs[i] - x) + (zs[i] - z) * (zs[i] - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (out != null && out.length > 0) out[0] = Math.sqrt(bd);
        return best;
    }

    /** Which way a vehicle facing (fx, fz) at way index i runs the line: +1 towards the dock, -1 towards the terminal. */
    public int dir(int i, double fx, double fz) {
        int a = clamp(i - 3), b = clamp(i + 3);
        double tx = xs[b] - xs[a], tz = zs[b] - zs[a];
        return tx * fx + tz * fz >= 0 ? 1 : -1;
    }

    /** The stops ahead of way index i going dir (nearest first). */
    public List<Integer> ahead(int i, int dir) {
        List<Integer> out = new ArrayList<>();
        if (dir > 0) {
            for (int k = 0; k < stopIds.size(); k++) if (stopAt.get(k) > i) out.add(stopIds.get(k));
        } else {
            for (int k = stopIds.size() - 1; k >= 0; k--) if (stopAt.get(k) < i) out.add(stopIds.get(k));
        }
        return out;
    }

    /** The next stop ahead of way index i going dir, or null (the end of the line). */
    public Integer next(int i, int dir) {
        List<Integer> a = ahead(i, dir);
        return a.isEmpty() ? null : a.getFirst();
    }

    /**
     * Which way the road goes from a vehicle at (x, z) facing yaw (Minecraft degrees), at way index i going dir: an arrow
     * of 8 (↑ ahead, → right ...), towards the way a little further on.
     */
    public String arrow(int i, int dir, double x, double z, float yaw) {
        int t = clamp(i + dir * 24);
        double dx = xs[t] - x, dz = zs[t] - z;
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
        double fwd = dx * fx + dz * fz, right = dx * rx + dz * rz;
        double deg = Math.toDegrees(Math.atan2(right, fwd));
        int k = (int) Math.round(deg / 45.0);
        return switch (Math.floorMod(k, 8)) {
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

    /** A junction coming: how far (blocks) and which way the line goes there: -1 left, 0 straight on, +1 right. */
    public record Turn(double distance, int turn) {}

    /** The first junction within `within` blocks ahead of way index i going dir, or null. */
    public Turn nextJunction(int i, int dir, double within) {
        for (int k = i + dir; k >= 0 && k < xs.length; k += dir) {
            double d = between(i, k);
            if (d > within) return null;
            if (!junction[k]) continue;
            int a = clamp(k - dir * 6), b = clamp(k + dir * 6);
            double ax = xs[k] - xs[a], az = zs[k] - zs[a], bx = xs[b] - xs[k], bz = zs[b] - zs[k];
            double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
            if (la < 1e-6 || lb < 1e-6) return new Turn(d, 0);
            double cross = (ax * bz - az * bx) / (la * lb), dot = (ax * bx + az * bz) / (la * lb);
            int turn = Math.abs(Math.toDegrees(Math.atan2(cross, dot))) < 20 ? 0 : cross > 0 ? 1 : -1;
            return new Turn(d, turn);
        }
        return null;
    }
}
