package dev.stsv.core.minigame.circuit;

import java.util.ArrayList;
import java.util.List;

/**
 * A circuit's centre line: a closed Catmull-Rom curve through the track's control points, resampled every
 * {@link #STEP} blocks of its length. Every sample knows where it is (x, z in the track's own blocks), the road's height
 * there (y, in half blocks: slabs make the slopes) and width, and which way the road runs. Pure (unit-tested).
 *
 * <p>Where is a kart on it ({@link #locate}): the nearest sample, looked for only near where it was last (a window
 * along the line), so a road that passes over itself (an overpass) never confuses the two levels, and a jump over a
 * corner of the field does not count as having driven round it.
 */
public final class TrackPath {

    /** Blocks between two samples. */
    public static final double STEP = 0.5;

    /** A control point: where (x, z), the road's height above the track's ground (blocks) and its width there. */
    public record Point(double x, double z, double y, double width, boolean bridge) {}

    private final double[] x, z, y, w, dx, dz;
    private final boolean[] bridge;
    private final double length;

    public TrackPath(List<Point> points) {
        if (points.size() < 4) throw new IllegalArgumentException("서킷 points 는 4개 이상 (닫힌 곡선)");
        // the curve drawn finely first, then walked at equal steps along it
        int n = points.size();
        int fine = 64;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point p0 = points.get(Math.floorMod(i - 1, n)), p1 = points.get(i), p2 = points.get((i + 1) % n), p3 = points.get((i + 2) % n);
            for (int k = 0; k < fine; k++) {
                double t = k / (double) fine;
                pts.add(new double[]{cr(p0.x(), p1.x(), p2.x(), p3.x(), t), cr(p0.z(), p1.z(), p2.z(), p3.z(), t),
                        smooth(p1.y(), p2.y(), t), lerp(p1.width(), p2.width(), t), (p1.bridge() && (p2.bridge() || t < 0.5)) || (p2.bridge() && t >= 0.5) ? 1 : 0});
            }
        }
        double total = 0;
        double[] at = new double[pts.size() + 1];
        for (int i = 0; i < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
            at[i + 1] = total += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        int count = Math.max(8, (int) Math.round(total / STEP));
        length = total;
        x = new double[count];
        z = new double[count];
        y = new double[count];
        w = new double[count];
        dx = new double[count];
        dz = new double[count];
        bridge = new boolean[count];
        int seg = 0;
        for (int i = 0; i < count; i++) {
            double s = total * i / count;
            while (seg < pts.size() - 1 && at[seg + 1] < s) seg++;
            double[] a = pts.get(seg), b = pts.get((seg + 1) % pts.size());
            double span = at[seg + 1] - at[seg], f = span <= 0 ? 0 : (s - at[seg]) / span;
            x[i] = lerp(a[0], b[0], f);
            z[i] = lerp(a[1], b[1], f);
            y[i] = lerp(a[2], b[2], f);
            w[i] = lerp(a[3], b[3], f);
            bridge[i] = (f < 0.5 ? a[4] : b[4]) > 0;
        }
        for (int i = 0; i < count; i++) {
            int a = Math.floorMod(i - 1, count), b = (i + 1) % count;
            double ddx = x[b] - x[a], ddz = z[b] - z[a], d = Math.hypot(ddx, ddz);
            dx[i] = d < 1e-9 ? 0 : ddx / d;
            dz[i] = d < 1e-9 ? 1 : ddz / d;
        }
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** Heights ease in and out between control points (a slope starts and ends gently). */
    private static double smooth(double a, double b, double t) {
        return lerp(a, b, t * t * (3 - 2 * t));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public int size() {
        return x.length;
    }

    /** The length of one lap (blocks). */
    public double length() {
        return length;
    }

    public double x(int i) {
        return x[wrap(i)];
    }

    public double z(int i) {
        return z[wrap(i)];
    }

    /** The road's surface height above the track's ground, in half blocks (rounded the way the road is built). */
    public double y(int i) {
        return Math.round(y[wrap(i)] * 2) / 2.0;
    }

    public double width(int i) {
        return w[wrap(i)];
    }

    public boolean bridge(int i) {
        return bridge[wrap(i)];
    }

    /** The way the road runs (unit x, z). */
    public double dirX(int i) {
        return dx[wrap(i)];
    }

    public double dirZ(int i) {
        return dz[wrap(i)];
    }

    /** Minecraft yaw of the way the road runs (0 = +z, 90 = -x). */
    public float yaw(int i) {
        return (float) Math.toDegrees(Math.atan2(-dirX(i), dirZ(i)));
    }

    /** Distance along the line from the start to sample i. */
    public double s(int i) {
        return length * wrap(i) / x.length;
    }

    /** The sample at distance s along the line (wrapping round). */
    public int index(double s) {
        return wrap((int) Math.round(s / length * x.length));
    }

    public int wrap(int i) {
        return Math.floorMod(i, x.length);
    }

    /** How much the road turns here: degrees per block (positive = to the right, as seen driving), over a few blocks either side. */
    public double curvature(int i) {
        int k = (int) Math.round(3 / STEP);
        double a = Math.atan2(dirZ(i - k), dirX(i - k)), b = Math.atan2(dirZ(i + k), dirX(i + k));
        double d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return Math.toDegrees(d) / (2 * k * STEP);
    }

    /** Signed distance of (px, pz) from sample i's centre, across the road (positive = to the road's right). */
    public double lateral(int i, double px, double pz) {
        double rx = px - x(i), rz = pz - z(i);
        // right of the way (dx, dz) in Minecraft's frame (x east, z south): (-dz, dx)
        return rx * -dirZ(i) + rz * dirX(i);
    }

    /** Where a kart is on the line. */
    public record Spot(int index, double lateral, double height) {}

    /**
     * The nearest sample to (px, py, pz) within window samples either side of hint (or anywhere: hint below 0). Height
     * counts too (an overpass): a sample 4 blocks above or below costs as much as one 4 blocks aside.
     */
    public Spot locate(double px, double py, double pz, int hint, int window) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        int from = hint < 0 ? 0 : hint - window, to = hint < 0 ? x.length - 1 : hint + window;
        for (int k = from; k <= to; k++) {
            int i = wrap(k);
            double ex = px - x[i], ez = pz - z[i], ey = py - y(i);
            double d = ex * ex + ez * ez + ey * ey;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return new Spot(best, lateral(best, px, pz), py - y(best));
    }

    /** Forward distance from a to b along the loop, in samples (0 .. size-1). */
    public int ahead(int a, int b) {
        return Math.floorMod(b - a, x.length);
    }

    /** Signed shortest step from a to b along the loop (samples): positive = forward. */
    public int delta(int a, int b) {
        int d = ahead(a, b);
        return d > x.length / 2 ? d - x.length : d;
    }
}
