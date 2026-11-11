package dev.stsv.economy.road;

import java.util.Locale;

/**
 * The road network's reference point (/관리자 국도 기준점 설정): where new 국도 start and where /관리자 하역장 후보 measures
 * from, instead of the build world's spawn. Saved as one line "world,x,y,z" (plugins/STSV-Economy/roads/origin.txt). Pure
 * (unit-tested).
 */
public record RoadOrigin(String world, double x, double y, double z) {

    /** Where measuring starts: the origin when one is set (in a world that exists), else the fallback (the spawn). */
    public record Use(String world, double x, double y, double z, boolean origin) {}

    public String write() {
        return String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f%n", world, x, y, z);
    }

    /** The saved line read back, or null (empty, unreadable). */
    public static RoadOrigin read(String text) {
        if (text == null) return null;
        String line = text.strip();
        int c = line.lastIndexOf(',', line.lastIndexOf(',', line.lastIndexOf(',') - 1) - 1);
        if (c <= 0) return null;
        String[] p = line.substring(c + 1).split(",");
        try {
            return new RoadOrigin(line.substring(0, c), Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
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
