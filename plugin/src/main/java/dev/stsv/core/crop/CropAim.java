package dev.stsv.core.crop;

import java.util.ArrayList;
import java.util.List;

/**
 * Which crop a player aims at, for crops drawn as blocks. The game's own aiming only knows the tripwire's outline
 * (thin, or half a block), not the plant drawn there, so a click is tested again here against the box the crop's old
 * click entity had (0.9 wide, as tall as the plant): a crop whose box the look ray enters before the block that was
 * clicked (the farmland under it, the ground behind it) or within reach when nothing was clicked, is the one clicked.
 * Pure (unit-tested).
 */
public final class CropAim {

    private CropAim() {}

    /** Half the gap around a crop's box: 0.9 wide in the middle of its block, like its old click entity. */
    public static final double INSET = 0.05;
    /** How many blocks above its own a crop's box can reach (the tallest crops are under two blocks). */
    public static final int REACH_UP = 2;

    /**
     * Distance along the ray (direction need not be unit length: the result is in units of it) to where it enters the box,
     * 0 if it starts inside, NaN if it misses within max.
     */
    public static double hit(double ox, double oy, double oz, double dx, double dy, double dz,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double max) {
        double tMin = 0, tMax = max;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return Double.NaN;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            if (t1 > t2) {
                double t = t1;
                t1 = t2;
                t2 = t;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Double.NaN;
        }
        return tMin;
    }

    /** The box of a crop at block (x, y, z), this tall. {minX, minY, minZ, maxX, maxY, maxZ} */
    public static double[] box(int x, int y, int z, double height) {
        return new double[]{x + INSET, y, z + INSET, x + 1 - INSET, y + height, z + 1 - INSET};
    }

    public static double hit(double[] origin, double[] dir, double[] box, double max) {
        return hit(origin[0], origin[1], origin[2], dir[0], dir[1], dir[2], box[0], box[1], box[2], box[3], box[4], box[5], max);
    }

    /**
     * The blocks a ray passes through, in order, up to max (unit direction), starting with the one it starts in.
     * (Amanatides and Woo.)
     */
    public static List<int[]> cells(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        List<int[]> out = new ArrayList<>();
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tx = next(ox, dx, x), ty = next(oy, dy, y), tz = next(oz, dz, z);
        double ddx = sx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), ddy = sy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy),
                ddz = sz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        out.add(new int[]{x, y, z});
        for (int i = 0; i < 64; i++) {
            double t = Math.min(tx, Math.min(ty, tz));
            if (t > max) break;
            if (t == tx) {
                x += sx;
                tx += ddx;
            } else if (t == ty) {
                y += sy;
                ty += ddy;
            } else {
                z += sz;
                tz += ddz;
            }
            out.add(new int[]{x, y, z});
        }
        return out;
    }

    private static double next(double o, double d, int cell) {
        if (d > 0) return (cell + 1 - o) / d;
        if (d < 0) return (cell - o) / d;
        return Double.MAX_VALUE;
    }
}
