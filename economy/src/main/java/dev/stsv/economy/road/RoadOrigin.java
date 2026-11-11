package dev.stsv.economy.road;

import java.util.Locale;

/**
 * The road network's reference point (/관리자 국도 기준점 설정): where new 국도 start and where /관리자 하역장 후보 measures
 * from, instead of the build world's spawn. Saved as one line "world,x,y,z" (plugins/STSV-Economy/roads/origin.txt). Pure
 * (unit-tested).
 */
public record RoadOrigin(String world, double x, double y, double z, double dx, double dz) {

    /** A point with no way out given (the road leaves it whichever way it is planned). */
    public RoadOrigin(String world, double x, double y, double z) {
        this(world, x, y, z, 0, 0);
    }

    /** Whether roads leave it a set way (a terminal's exit: straight out for a stretch before they turn). */
    public boolean directed() {
        return dx != 0 || dz != 0;
    }

    /** Where measuring starts: the origin when one is set (in a world that exists), else the fallback (the spawn). */
    public record Use(String world, double x, double y, double z, boolean origin) {}

    public String write() {
        return directed() ? String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.3f,%.3f%n", world, x, y, z, dx, dz)
                : String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f%n", world, x, y, z);
    }

    /** The saved line read back ("world,x,y,z" or "world,x,y,z,dx,dz"), or null (empty, unreadable). */
    public static RoadOrigin read(String text) {
        if (text == null) return null;
        String line = text.strip();
        for (int n : new int[]{5, 3}) {   // the numbers at its end; the world's name (which may hold commas) before them
            int c = line.length();
            for (int k = 0; k < n && c > 0; k++) c = line.lastIndexOf(',', c - 1);
            if (c <= 0) continue;
            String[] p = line.substring(c + 1).split(",");
            try {
                double[] v = new double[p.length];
                for (int i = 0; i < p.length; i++) v[i] = Double.parseDouble(p[i]);
                if (n == 5) return new RoadOrigin(line.substring(0, c), v[0], v[1], v[2], v[3], v[4]);
                return new RoadOrigin(line.substring(0, c), v[0], v[1], v[2]);
            } catch (RuntimeException e) {
                // not that many numbers
            }
        }
        return null;
    }

    /**
     * Where to start from: this origin if it is set and its world exists (worldExists), else the spawn given (its world
     * and place).
     */
    public static Use pick(RoadOrigin origin, java.util.function.Predicate<String> worldExists, String spawnWorld, double sx, double sy, double sz) {
        if (origin != null && worldExists.test(origin.world())) return new Use(origin.world(), origin.x(), origin.y(), origin.z(), true);
        return new Use(spawnWorld, sx, sy, sz, false);
    }
}
