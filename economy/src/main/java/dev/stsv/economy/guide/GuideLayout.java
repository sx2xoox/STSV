package dev.stsv.economy.guide;

import java.util.List;
import java.util.Map;

/**
 * The 길라잡이 world's plan (pure: the builder lays it, the module reads where things are). A plaza at 0,0 (spawn), an avenue
 * along x, small stations (21x21) on both sides of it and two big ones (41x41: 하역장·탈것 west, 버스 기사 east) at its ends.
 * <p>
 * Every station has its own coordinates: from the middle of its front edge (on the avenue side), lx to the right of someone
 * walking in, lz inward, ly up from the floor (players stand at {@link #FLOOR_Y}). Regions and zones are rectangles
 * {lx1, lz1, lx2, lz2} (inclusive), features are blocks {lx, ly, lz}.
 */
public final class GuideLayout {

    private GuideLayout() {}

    /** Players stand at this y (the floor's blocks are one below). */
    public static final int FLOOR_Y = 64;
    /** The plaza: |x|, |z| up to this. */
    public static final int PLAZA = 10;
    /** The avenue: |z| up to this, |x| up to AVENUE_X. */
    public static final int AVENUE_Z = 4, AVENUE_X = 78;

    public record Station(String id, String title, int ox, int oz, int fx, int fz, int half, int depth,
                          Map<String, int[]> regions, Map<String, int[]> features) {

        /** The right of someone walking in. */
        public int rx() {
            return -fz;
        }

        public int rz() {
            return fx;
        }

        /** The block at station coordinates (x, z). */
        public int[] block(int lx, int lz) {
            return new int[]{ox + lx * rx() + lz * fx, oz + lx * rz() + lz * fz};
        }

        /** A point (block centres at whole numbers). */
        public double[] point(double lx, double lz) {
            return new double[]{ox + 0.5 + lx * rx() + lz * fx, oz + 0.5 + lx * rz() + lz * fz};
        }

        /** World x, z -> station {lx, lz}. */
        public double[] local(double x, double z) {
            double dx = x - (ox + 0.5), dz = z - (oz + 0.5);
            return new double[]{dx * rx() + dz * rz(), dx * fx + dz * fz};
        }

        /** Is the world point in this rectangle {lx1, lz1, lx2, lz2} (whole blocks)? */
        public boolean inside(int[] rect, double x, double z) {
            if (rect == null) return false;
            double[] l = local(x, z);
            return l[0] >= Math.min(rect[0], rect[2]) - 0.5 && l[0] <= Math.max(rect[0], rect[2]) + 0.5
                    && l[1] >= Math.min(rect[1], rect[3]) - 0.5 && l[1] <= Math.max(rect[1], rect[3]) + 0.5;
        }

        /** On this station's floor (the whole pad)? */
        public boolean onPad(double x, double z) {
            return inside(new int[]{-half, 0, half, depth - 1}, x, z);
        }

        /** Looking inward. */
        public float yaw() {
            return (float) Math.toDegrees(Math.atan2(-fx, fz));
        }

        /** Looking along lx (to the right): a vehicle driving along the front straight. */
        public float yawRight() {
            return (float) Math.toDegrees(Math.atan2(-rx(), rz()));
        }

        /** Where a player arrives. */
        public double[] start() {
            return point(0, 2);
        }

        /** Where the helper stands. */
        public double[] helper() {
            return point(3, 3);
        }
    }

    private static final int SMALL_HALF = 10, SMALL_DEPTH = 21, BIG_HALF = 20, BIG_DEPTH = 41;

    private static Station small(String id, String title, int x, boolean north, Map<String, int[]> regions, Map<String, int[]> features) {
        return new Station(id, title, x, north ? -(AVENUE_Z + 1) : AVENUE_Z + 1, 0, north ? -1 : 1, SMALL_HALF, SMALL_DEPTH, regions, features);
    }

    private static final List<Station> STATIONS = List.of(
            small("jobs", "직업 고르기", -22, true, Map.of(), Map.of()),
            small("machines", "기계 쓰기", -44, true, Map.of("machine", new int[]{-7, 6, 7, 16}), Map.of()),
            small("farming", "농사", -66, true, Map.of("field", new int[]{-5, 6, 5, 12}), Map.of()),
            small("mining", "광산·원석", 22, true, Map.of("bench", new int[]{-7, 4, 7, 9}, "ores", new int[]{-3, 12, 3, 12}), Map.of()),
            small("fishing", "낚시", 44, true, Map.of("pond", new int[]{-5, 7, 5, 16}), Map.of()),
            small("shop", "상점·시세", 66, true, Map.of(), Map.of()),
            small("land", "땅·집", -22, false, Map.of("plot", new int[]{-6, 4, 7, 17}, "outline", new int[]{-7, 3, 8, 18}), Map.of()),
            small("accessories", "장신구", -44, false, Map.of(), Map.of()),
            small("trade", "거래", -66, false, Map.of(), Map.of()),
            small("stocks", "주식", 22, false, Map.of(), Map.of()),
            small("missions", "미션·업적·치장", 44, false, Map.of(), Map.of()),
            new Station("dock", "하역장·탈것", -(AVENUE_X + 1), 0, -1, 0, BIG_HALF, BIG_DEPTH,
                    Map.of("lane", new int[]{-3, 4, 3, 38}, "inspect", new int[]{-3, 14, 3, 20}, "unload", new int[]{-3, 30, 3, 36}),
                    Map.of("garage", new int[]{5, 0, 3}, "unload_desk", new int[]{5, 0, 33}, "truck", new int[]{0, 0, 7})),
            new Station("bus", "버스 기사", AVENUE_X + 1, 0, 1, 0, BIG_HALF, BIG_DEPTH,
                    Map.of("road", new int[]{-16, 4, 16, 36}, "inner", new int[]{-11, 9, 11, 31},
                            "stop_a", new int[]{-4, 4, 4, 8}, "stop_b", new int[]{-4, 32, 4, 36}),
                    Map.of("bus", new int[]{-6, 0, 6}, "sign_a", new int[]{0, 0, 3}, "sign_b", new int[]{0, 0, 37})));

    public static List<Station> stations() {
        return STATIONS;
    }

    public static Station station(String id) {
        for (Station s : STATIONS) if (s.id().equals(id)) return s;
        return null;
    }

    /** The station whose pad holds this point, or null. */
    public static Station at(double x, double z) {
        for (Station s : STATIONS) if (s.onPad(x, z)) return s;
        return null;
    }

    /** The ores of the 광산 wall, by place in the wall (lx -3..3, ly 0..2). */
    public static String ore(int lx, int ly) {
        String[] ores = {"COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE", "LAPIS_ORE", "DIAMOND_ORE", "EMERALD_ORE"};
        return ores[Math.floorMod(lx * 3 + ly * 5, ores.length)];
    }
}
