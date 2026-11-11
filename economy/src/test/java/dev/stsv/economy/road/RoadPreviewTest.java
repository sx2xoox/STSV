package dev.stsv.economy.road;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 built for real ({@link RoadProfile} + {@link RoadSection}, as RoadModule does it) over a made-up strip of land with
 * five scenes, checked, and written to build/roads/strip.json for the preview (tools/assetgen/road_preview.py →
 * docs/previews/road.png):
 * <ol>
 *   <li>(a) flat grassland: street lights, the 1 km sign</li>
 *   <li>(b) a hill climbed at 1 in 3: a dip filled, the hill cut, the slope across it cut on one side and filled on the other</li>
 *   <li>(c) a river: a bridge with railings and piers</li>
 *   <li>(d) a steep hill: a tunnel with its lights</li>
 *   <li>(e) a forest the road bends through: trees cleared</li>
 * </ol>
 */
class RoadPreviewTest {

    static final int RIVER0 = 1190, RIVER1 = 1210, WATER_TOP = 62;

    /** A world of its own: land from {@link #ground}, trees, a river, and whatever the road put over it. */
    static final class Land implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();
        final Map<Long, Object[]> signs = new LinkedHashMap<>();   // facing, lines

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        /** The ground's top block before the road. */
        static int ground(int x, int z) {
            double g = 64;
            // (b) a dip to fill, then a hill up at 1 in 2 with its ground falling across the road
            if (x >= 1056 && x <= 1068) g -= 2.6 * Math.sin(Math.PI * (x - 1056) / 12.0);
            if (x > 1074 && x < 1138) {
                double h = Math.min(Math.min((x - 1074) / 2.0, 12), (1138 - x) / 2.0);
                g += h + Math.clamp(h, 0, 4) * z * 0.16;
            }
            // (c) the river
            if (x >= RIVER0 - 4 && x <= RIVER1 + 4) {
                double d = Math.min(x - (RIVER0 - 4), RIVER1 + 4 - x);
                g = Math.max(57, 64 - d * 1.6);
            }
            // (d) a steep hill (2 in 1) with a bumpy top
            if (x > 1234 && x < 1310) {
                double h = Math.min(Math.min((x - 1234) * 2.0, 26), (1310 - x) * 2.0);
                g += h + (h >= 26 ? 1.2 * Math.sin(x * 0.17) + Math.sin(z * 0.21) : 0);
            }
            // (e) the forest: gently uneven
            if (x >= 1320) g += Math.round(Math.sin(x * 0.13) + Math.cos(z * 0.17));
            return (int) Math.floor(g);
        }

        static boolean river(int x) {
            return x >= RIVER0 - 4 && x <= RIVER1 + 4;
        }

        static int hash(int a, int b) {
            int h = a * 73856093 ^ b * 19349663;
            h ^= h >>> 13;
            h *= 0x5bd1e995;
            return h ^ (h >>> 15);
        }

        /** A tree's trunk in this 5 x 5 cell: {x, z, height} or null (the forest from x 1272, a few in the grassland). */
        static int[] tree(int cx, int cz) {
            int h = hash(cx, cz);
            int x = cx * 5 + Math.floorMod(h, 3), z = cz * 5 + Math.floorMod(h >> 4, 3);
            boolean forest = x >= 1312;
            boolean meadow = x >= 940 && x < 1050 && Math.abs(z) > 9 && Math.floorMod(h >> 8, 9) == 0;
            if (!forest && !meadow) return null;
            if (forest && Math.floorMod(h >> 8, 10) >= 7) return null;
            return new int[]{x, z, 4 + Math.floorMod(h >> 12, 3)};
        }

        /** The land as it was. */
        static Material base(int x, int y, int z) {
            int g = ground(x, z);
            boolean river = river(x);
            if (y <= g) return y == g ? (river && g < WATER_TOP + 1 ? Material.SAND : Material.GRASS_BLOCK) : y >= g - 3 ? Material.DIRT : Material.STONE;
            if (river && y <= WATER_TOP) return Material.WATER;
            for (int cx = Math.floorDiv(x - 4, 5); cx <= Math.floorDiv(x + 2, 5); cx++) {
                for (int cz = Math.floorDiv(z - 4, 5); cz <= Math.floorDiv(z + 2, 5); cz++) {
                    int[] t = tree(cx, cz);
                    if (t == null) continue;
                    int tg = ground(t[0], t[1]), top = tg + t[2];
                    int dx = Math.abs(x - t[0]), dz = Math.abs(z - t[1]);
                    if (dx == 0 && dz == 0 && y > tg && y <= top) return Material.OAK_LOG;
                    int r = y >= top - 2 && y <= top - 1 ? 2 : y >= top && y <= top + 1 ? 1 : -1;
                    if (r < 0 || dx > r || dz > r) continue;
                    if (dx == r && dz == r && (r == 2 || y == top + 1)) continue;   // round off the corners
                    return Material.OAK_LEAVES;
                }
            }
            return Material.AIR;
        }

        Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            return m != null ? m : base(x, y, z);
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return !inside(y) || get(x, y, z) == Material.AIR;
        }

        public boolean liquid(int x, int y, int z) {
            return inside(y) && get(x, y, z) == Material.WATER;
        }

        static boolean naturalBlock(Material m) {
            String n = m.name();
            return n.endsWith("_LOG") || n.endsWith("_LEAVES") || m == Material.VINE || m == Material.SNOW;
        }

        public boolean natural(int x, int y, int z) {
            return inside(y) && naturalBlock(get(x, y, z));
        }

        public boolean fillable(int x, int y, int z) {
            if (!inside(y)) return false;
            Material m = get(x, y, z);
            return m == Material.AIR || m == Material.WATER || m == Material.SHORT_GRASS || m == Material.SPRUCE_SIGN || naturalBlock(m);
        }

        public void set(int x, int y, int z, Material m) {
            if (inside(y)) put.put(key(x, y, z), m);
        }

        public boolean playerLand(int x, int z) {
            return false;
        }

        public void sign(int x, int y, int z, BlockFace facing, String[] lines) {
            set(x, y, z, Material.SPRUCE_SIGN);
            signs.put(key(x, y, z), new Object[]{x, y, z, facing, lines});
        }

        /** The ground under a point as RoadModule reads it (DockModule.sampleColumn): ground, water (1/0), the surface. */
        int[] sample(int x, int z) {
            int top = 200;
            while (top > -64 && get(x, top, z) == Material.AIR) top--;
            Material m = get(x, top, z);
            if (m == Material.WATER) {
                int floor = top;
                while (get(x, floor, z) == Material.WATER) floor--;
                return new int[]{floor, 1, top};
            }
            int g = top;
            while (get(x, g, z) == Material.AIR || get(x, g, z).name().endsWith("_LEAVES")) g--;   // MOTION_BLOCKING_NO_LEAVES
            return new int[]{g, 0, top};
        }
    }

    /** The route: straight along x through scenes (a) to (d), then bending through the forest. */
    static final List<double[]> CORNERS = List.of(new double[]{0.5, 0.5}, new double[]{1336.5, 0.5}, new double[]{1376.5, 12.5},
            new double[]{1408.5, 40.5}, new double[]{1428.5, 80.5}, new double[]{1436.5, 130.5});

    record Built(Land land, List<double[]> points, int[] levels, RoadProfile.Part[] parts, int[] ground) {}

    static Built build() {
        Land land = new Land();
        List<double[]> points = RoadPlanner.densify(CORNERS, 1);
        int n = points.size();
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {   // PREPARE: the ground under every point
            int[] s = land.sample((int) Math.floor(points.get(i)[0]), (int) Math.floor(points.get(i)[1]));
            ground[i] = s[0];
            water[i] = s[1] == 1;
            surface[i] = s[2];
        }
        int[] levels = RoadProfile.plan(ground, water, surface);   // BUILD, as RoadModule.profile()
        RoadProfile.Part[] parts = RoadProfile.parts(levels, ground, water, RoadModule.BRIDGE_GAP, RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN);
        RoadSection section = new RoadSection(land, new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE,
                40, 1000), points, levels, parts, "서해");
        for (int i = 0; i < n; i++) assertEquals(null, section.build(i));
        return new Built(land, points, levels, parts, ground);
    }

    static int index(List<double[]> points, double x) {
        for (int i = 0; i < points.size(); i++) if (points.get(i)[0] >= x) return i;
        return points.size() - 1;
    }

    @Test
    void theStripIsBuiltAndEverySceneHasWhatItShows() throws Exception {
        Built b = build();
        export(b);
        Land land = b.land();
        lanesAreWholeAndClear(b);
        // nothing the road filled hangs in the air (the shoulders on a steep side slope reach down to the ground)
        for (Map.Entry<Long, Material> e : land.put.entrySet()) {
            Material m = e.getValue();
            if (m != Material.GRASS_BLOCK && m != Material.DIRT && m != Material.STONE && m != Material.COBBLESTONE) continue;
            long k = e.getKey();
            int x = (int) (k >>> 42) - (1 << 20), z = (int) ((k >>> 21) & ((1 << 21) - 1)) - (1 << 20), y = (int) (k & ((1 << 21) - 1)) - 1024;
            assertTrue(land.get(x, y - 1, z) != Material.AIR, "floating " + m + " at " + x + ", " + y + ", " + z);
        }
        List<double[]> pts = b.points();
        // (a) the 1 km sign beside the road, street lights
        assertEquals(1, land.signs.size());
        Object[] sign = land.signs.values().iterator().next();
        assertEquals(1000, (int) sign[0]);
        assertEquals("1 km 지점", ((String[]) sign[4])[2]);
        assertEquals("남은 길 0.5 km", ((String[]) sign[4])[3], "what is left, not rounded down to nothing");
        assertEquals(Material.LANTERN, land.get(1000, b.levels()[1000] + 4, -4));
        // (b) the hill: a dip filled, the hill cut, never steeper than 1 in 3
        int filled = 0, cut = 0;
        for (int x = 1046; x < 1150; x++) {
            int l = b.levels()[index(pts, x)];
            for (int z = -3; z <= 3; z++) {
                if (Land.base(x, l - 1, z) == Material.AIR && land.get(x, l - 1, z) == Material.STONE) filled++;
                if (Land.base(x, l + 1, z) != Material.AIR && land.get(x, l + 1, z) == Material.AIR) cut++;
            }
        }
        assertTrue(filled > 20 && cut > 20, "filled " + filled + ", cut " + cut);
        assertTrue(RoadProfile.closestSteps(b.levels()) >= 3);
        // (c) the river: a bridge with railings over the water
        int mid = index(pts, (RIVER0 + RIVER1) / 2.0);
        assertEquals(RoadProfile.Part.BRIDGE, b.parts()[mid]);
        assertEquals(Material.STONE_BRICK_WALL, land.get(1196, b.levels()[mid] + 1, 3));
        assertEquals(Material.STONE_BRICK_WALL, land.get(1196, b.levels()[mid] + 1, -3));
        assertEquals(Material.LANTERN, land.get(1200, b.levels()[mid] + 4, 3), "the bridge's light stands on its railing");
        // (d) the steep hill: a tunnel, lit
        int in = index(pts, 1272);
        assertEquals(RoadProfile.Part.TUNNEL, b.parts()[in]);
        boolean lit = false;
        for (int x = 1240; x < 1310; x++) lit |= land.get(x, b.levels()[index(pts, x)] + 5, 0) == Material.SEA_LANTERN;
        assertTrue(lit, "lights in the tunnel's ceiling");
        // (e) the forest: no tree left over the road
        for (int i = index(pts, 1320); i < pts.size(); i++) {
            double[] p = pts.get(i);
            for (int y = b.levels()[i] + 1; y <= b.levels()[i] + 30; y++)
                assertTrue(!Land.naturalBlock(land.get((int) Math.floor(p[0]), y, (int) Math.floor(p[1]))), "a tree over the road at " + i);
        }
    }

    /**
     * Across the lanes, everywhere (straight, diagonal, round the bends, on bridges and in tunnels): the surface is there
     * (no gaps), nothing stands on it for two blocks (no light or railing in the lanes), and side by side it never steps
     * more than one block (a vehicle drives up one).
     */
    static void lanesAreWholeAndClear(Built b) {
        Land land = b.land();
        List<double[]> pts = b.points();
        Map<Long, Integer> top = new HashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i), a = pts.get(Math.max(0, i - 2)), c = pts.get(Math.min(pts.size() - 1, i + 2));
            double hx = c[0] - a[0], hz = c[1] - a[1], len = Math.hypot(hx, hz);
            double rx = -hz / len, rz = hx / len;
            for (double o = -1.7; o <= 1.7 + 1e-9; o += 0.1) {   // a column within 2.41 of the middle: always a lane
                int x = (int) Math.floor(p[0] + rx * o), z = (int) Math.floor(p[1] + rz * o);
                int l = b.levels()[i], y = l + 1;
                while (y >= l - 1 && land.get(x, y, z) == Material.AIR) y--;
                Material m = land.get(x, y, z);
                assertTrue(m == Material.GRAY_CONCRETE || m == Material.YELLOW_CONCRETE, "the lane at point " + i + " (" + x + ", " + z + "): " + m + " at " + y);
                assertEquals(Material.AIR, land.get(x, y + 1, z), "over the lane at point " + i + " (" + x + ", " + z + ")");
                assertEquals(Material.AIR, land.get(x, y + 2, z), "over the lane at point " + i + " (" + x + ", " + z + ")");
                top.put(RoadJob.key(x, z), y);
            }
        }
        for (Map.Entry<Long, Integer> e : top.entrySet()) {
            int x = RoadJob.cx(e.getKey()), z = RoadJob.cz(e.getKey());
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                Integer n = top.get(RoadJob.key(x + d[0], z + d[1]));
                if (n != null) assertTrue(Math.abs(n - e.getValue()) <= 1, "a step of " + Math.abs(n - e.getValue()) + " at " + x + ", " + z);
            }
        }
    }

    /** The land round the road for the preview: columns of runs [y, length, block] from Y0 up. */
    static void export(Built b) throws Exception {
        int x0 = 940, x1 = 1480, z0 = -40, z1 = 150, y0 = 44;
        List<String> palette = new ArrayList<>();
        Map<Material, Integer> index = new HashMap<>();
        StringBuilder cols = new StringBuilder();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                if (cols.length() > 0) cols.append(',');
                cols.append('[');
                Material run = null;
                int from = 0;
                boolean first = true;
                for (int y = y0; y <= 112; y++) {
                    Material m = y == 112 ? null : b.land().get(x, y, z);
                    if (m == run) continue;
                    if (run != null && run != Material.AIR) {
                        if (!first) cols.append(',');
                        first = false;
                        int pi = index.computeIfAbsent(run, k -> {
                            palette.add(k.getKey().getKey());
                            return palette.size() - 1;
                        });
                        cols.append(from).append(',').append(y - from).append(',').append(pi);
                    }
                    run = m;
                    from = y;
                }
                cols.append(']');
            }
        }
        StringBuilder s = new StringBuilder("{\"x0\":").append(x0).append(",\"z0\":").append(z0).append(",\"w\":").append(x1 - x0 + 1)
                .append(",\"d\":").append(z1 - z0 + 1).append(",\"y0\":").append(y0).append(",\"palette\":[");
        for (int i = 0; i < palette.size(); i++) s.append(i > 0 ? "," : "").append('"').append(palette.get(i)).append('"');
        s.append("],\"signs\":[");
        boolean first = true;
        for (Object[] g : b.land().signs.values()) {
            s.append(first ? "" : ",").append('[').append(g[0]).append(',').append(g[1]).append(',').append(g[2]).append(",\"")
                    .append(((BlockFace) g[3]).name().toLowerCase(Locale.ROOT)).append("\",[");
            String[] lines = (String[]) g[4];
            for (int i = 0; i < lines.length; i++) s.append(i > 0 ? "," : "").append('"').append(lines[i]).append('"');
            s.append("]]");
            first = false;
        }
        s.append("],\"points\":[");
        for (int i = 0; i < b.points().size(); i++) {
            double[] p = b.points().get(i);
            s.append(i > 0 ? "," : "").append('[').append(String.format(Locale.ROOT, "%.2f,%.2f", p[0], p[1])).append(',').append(b.levels()[i])
                    .append(",\"").append(b.parts()[i].name().charAt(0)).append("\"]");
        }
        s.append("],\"cols\":[").append(cols).append("]}");
        Path out = Path.of("build/roads/strip.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, s.toString());
    }
}
