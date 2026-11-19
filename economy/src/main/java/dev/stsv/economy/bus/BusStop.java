package dev.stsv.economy.bus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 마을버스 정류장 (README "마을버스"): a bus bay paved beside a 국도 (outside its shoulder, on one side) and a shelter behind
 * it. Where it is: the road point it hangs on (x, z; the road's surface `half` there, in half blocks) and the way the road
 * runs there (hx, hz, unit); `side` +1 = the right of that way, -1 = the left. `bay` false: a simple stop (no bay could be
 * paved: the bus stops on the shoulder). The shapes are pure (unit-tested).
 */
public record BusStop(int id, String name, String world, double x, double z, int half, double hx, double hz, int side, int size,
                      boolean auto, boolean bay, String dock) {

    /** How far from the road's middle the bay starts (the shoulders end there). */
    public static final double ROAD_HALF = 5.5;

    public BusStop named(String n) {
        return new BusStop(id, n, world, x, z, half, hx, hz, side, size, auto, bay, dock);
    }

    /** The block the road's surface is in here (and the bay's). */
    public int y() {
        return Math.floorDiv(half + 1, 2);
    }

    /** Whether the surface here is a half block (a slab). */
    public boolean slab() {
        return Math.floorMod(half, 2) == 1;
    }

    /** Where the surface's top is. */
    public double top() {
        return half / 2.0 + 1;
    }

    /** A point `along` the road from the stop's point and `out` from the road's middle towards the stop's side: x, z. */
    public double[] at(double along, double out) {
        double nx = -hz * side, nz = hx * side;
        return new double[]{x + hx * along + nx * out, z + hz * along + nz * out};
    }

    /** Along and out of a point (the inverse of {@link #at}). */
    public double[] local(double px, double pz) {
        double dx = px - x, dz = pz - z;
        double nx = -hz * side, nz = hx * side;
        return new double[]{dx * hx + dz * hz, dx * nx + dz * nz};
    }

    /** How wide the bay is `along` the stop: full in its middle, narrowing over 6 blocks at each end. */
    static double bayWidth(double along, int length, int width) {
        double h = length / 2.0, a = Math.abs(along);
        if (a >= h) return 0;
        if (a <= h - 6) return width;
        return width * (h - a) / 6.0;
    }

    /** A block column of the bay: x, z and whether it is the bay's yellow edge. */
    public record Column(int x, int z, boolean edge) {}

    /** The bay's block columns (none for a simple stop). */
    public List<Column> bayColumns(int length, int width) {
        Map<Long, Column> out = new LinkedHashMap<>();
        if (!bay) return List.of();
        for (double a = -length / 2.0 + 0.25; a < length / 2.0; a += 0.5) {
            double w = bayWidth(a, length, width);
            if (w < 0.5) continue;
            for (double o = ROAD_HALF + 0.25; o <= ROAD_HALF + w; o += 0.5) {
                double[] p = at(a, o);
                int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[1]);
                boolean edge = o > ROAD_HALF + w - 0.75;
                long k = ((long) bx << 32) ^ (bz & 0xffffffffL);
                Column c = out.get(k);
                if (c == null || (edge && !c.edge())) out.put(k, new Column(bx, bz, edge || (c != null && c.edge())));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** How far out the shelter's road side is (behind the bay, or the shoulder for a simple stop). */
    public double shelterOut(int width) {
        return ROAD_HALF + (bay ? width : 0) + 1;
    }

    /** Whether a point (x, z) is where a bus stands to stop here: within reach along the stop, on the bay (or the shoulder). */
    public boolean standing(double px, double pz, double reach, int width) {
        double[] l = local(px, pz);
        double lo = bay ? ROAD_HALF - 1.5 : 2.5, hi = bay ? ROAD_HALF + width + 0.5 : ROAD_HALF + 1;
        return Math.abs(l[0]) <= reach && l[1] >= lo && l[1] <= hi;
    }

    /** Whether a point is on the road near the stop (a bus stopped in a lane by it: told to pull into the bay). */
    public boolean byTheStop(double px, double pz, double reach) {
        double[] l = local(px, pz);
        return Math.abs(l[0]) <= reach + 6 && Math.abs(l[1]) <= ROAD_HALF + 4;
    }

    /** Where waiting passenger i of n stands in the shelter: x, z. */
    public double[] waitingSpot(int i, int n, int width) {
        double along = n <= 1 ? 0 : -1.5 + 3.0 * i / (n - 1);
        return at(along, shelterOut(width) + 1.0);
    }

    /** The yaw a passenger in the shelter faces (the road). */
    public float facingRoad() {
        double nx = hz * side, nz = -hx * side;   // towards the road's middle
        return (float) Math.toDegrees(Math.atan2(-nx, nz));
    }
}
