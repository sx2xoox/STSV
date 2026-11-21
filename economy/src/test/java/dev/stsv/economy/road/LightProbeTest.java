package dev.stsv.economy.road;

import dev.stsv.core.vehicle.DriveInput;
import dev.stsv.core.vehicle.VehiclePhysics;
import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** TEMP investigation. */
class LightProbeTest {

    static final class Flat implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            if (m != null) return m;
            return y < 63 ? Material.STONE : y == 63 ? Material.GRASS_BLOCK : Material.AIR;
        }

        public boolean inside(int y) { return y >= -64 && y < 320; }
        public boolean air(int x, int y, int z) { return get(x, y, z) == Material.AIR; }
        public boolean liquid(int x, int y, int z) { return false; }
        public boolean natural(int x, int y, int z) { return false; }
        public boolean fillable(int x, int y, int z) { return get(x, y, z) == Material.AIR; }
        public void set(int x, int y, int z, Material m) { if (m != null) put.put(key(x, y, z), m); }
        public boolean protectedLand(int x, int z) { return false; }
        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}
    }

    static boolean wallish(Material m) {
        return m != null && (m.name().endsWith("_WALL") || m.name().endsWith("_FENCE"));
    }

    static boolean solidFull(Material m) {
        if (m == null || m == Material.AIR || wallish(m) || m.name().endsWith("_SLAB") || m == Material.LANTERN || m == Material.END_ROD
                || m == Material.IRON_TRAPDOOR || m == Material.DIRT_PATH) return false;
        List<double[]> b = new ArrayList<>();
        RoadClearanceTest.boxes(m, 0, 0, 0, b);
        return b.size() == 1 && b.get(0)[4] == 1 && b.get(0)[0] == 0;
    }

    /** Vanilla-ish collision boxes: walls a 0.5 post (1.5 high) with 0.375 bars to wall/solid neighbours; lanterns their box. */
    static void boxes(RoadClearanceTest.Blocks w, int x, int y, int z, List<double[]> out) {
        Material m = w.at(x, y, z);
        if (m == null) return;
        if (wallish(m)) {
            out.add(new double[]{x + 0.25, y, z + 0.25, x + 0.75, y + 1.5, z + 0.75});
            int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] k : d) {
                Material n = w.at(x + k[0], y, z + k[1]);
                if (!wallish(n) && !solidFull(n)) continue;
                double x0 = k[0] > 0 ? 0.5 : k[0] < 0 ? 0 : 0.3125, x1 = k[0] > 0 ? 1 : k[0] < 0 ? 0.5 : 0.6875;
                double z0 = k[1] > 0 ? 0.5 : k[1] < 0 ? 0 : 0.3125, z1 = k[1] > 0 ? 1 : k[1] < 0 ? 0.5 : 0.6875;
                out.add(new double[]{x + x0, y, z + z0, x + x1, y + 1.5, z + z1});
            }
            return;
        }
        if (m == Material.LANTERN) {
            boolean hanging = wallish(w.at(x, y + 1, z));
            if (hanging) out.add(new double[]{x + 0.3125, y + 0.0625, z + 0.3125, x + 0.6875, y + 0.625, z + 0.6875});
            else out.add(new double[]{x + 0.3125, y, z + 0.3125, x + 0.6875, y + 0.5625, z + 0.6875});
            return;
        }
        RoadClearanceTest.boxes(m, x, y, z, out);
    }

    static VehiclePhysics.Terrain terrain(RoadClearanceTest.Blocks w) {
        return new VehiclePhysics.Terrain() {
            public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
                    for (int y = (int) Math.floor(minY) - 1; y <= (int) Math.floor(maxY); y++)
                        for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) LightProbeTest.boxes(w, x, y, z, out);
            }

            public VehiclePhysics.Medium medium(int x, int y, int z) {
                return VehiclePhysics.Medium.AIR;
            }
        };
    }

    /** A path: points and a lateral target per point. Drives it, returns null or what it touched. */
    static String follow(VehicleSpec s, RoadClearanceTest.Blocks w, List<double[]> pts, java.util.function.IntToDoubleFunction lat,
                         double cruise, boolean reverseDir, List<String> touches) {
        VehiclePhysics.Terrain t = terrain(w);
        List<double[]> path = new ArrayList<>();
        int n = pts.size();
        for (int k = 0; k < n; k++) {
            int i = reverseDir ? n - 1 - k : k;
            double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(n - 1, i + 2));
            double hx = b[0] - a[0], hz = b[1] - a[1], l = Math.hypot(hx, hz);
            hx /= l;
            hz /= l;
            if (reverseDir) { hx = -hx; hz = -hz; }
            double rx = -hz, rz = hx, o = lat.applyAsDouble(k);
            path.add(new double[]{pts.get(i)[0] + rx * o, pts.get(i)[1] + rz * o});
        }
        double[] p0 = path.get(3), p1 = path.get(6);
        float yaw = (float) Math.toDegrees(Math.atan2(-(p1[0] - p0[0]), p1[1] - p0[1]));
        int y = 120;
        while (y > 0 && w.at((int) Math.floor(p0[0]), y, (int) Math.floor(p0[1])) == Material.AIR) y--;
        VehiclePhysics.State st = new VehiclePhysics.State(p0[0], y + 1.01, p0[1], yaw);
        int seg = 3;
        int since = 0;
        double best = 0;
        for (int tick = 0; tick < 20000; tick++) {
            // nearest path index
            int bi = seg;
            double bd = Double.MAX_VALUE;
            for (int k = Math.max(0, seg - 3); k < Math.min(path.size(), seg + 12); k++) {
                double d = Math.hypot(path.get(k)[0] - st.x, path.get(k)[1] - st.z);
                if (d < bd) { bd = d; bi = k; }
            }
            seg = bi;
            if (seg >= path.size() - 8) return null;
            int look = Math.min(path.size() - 1, seg + (int) Math.max(4, s.length() * 0.7 + Math.abs(st.speed) * 0.4));
            double[] g = path.get(look);
            float want = (float) Math.toDegrees(Math.atan2(-(g[0] - st.x), g[1] - st.z));
            float err = VehiclePhysics.wrap(want - st.yaw);
            if (err > 180) err -= 360;
            int steer = Math.abs(err) < 1.0 ? 0 : err > 0 ? -1 : 1;
            boolean fwd = st.speed < cruise;
            DriveInput in = new DriveInput(fwd, false, steer == 1, steer == -1, false, false);
            VehiclePhysics.step(st, in, s, t, 20);
            if (touches != null) {
                for (double[] sq : VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s)) {
                    List<double[]> bx = new ArrayList<>();
                    for (int x = (int) Math.floor(sq[0] - 0.05); x <= (int) Math.floor(sq[3] + 0.05); x++)
                        for (int yy = (int) Math.floor(sq[1]) - 1; yy <= (int) Math.floor(sq[4] + 0.05); yy++)
                            for (int z = (int) Math.floor(sq[2] - 0.05); z <= (int) Math.floor(sq[5] + 0.05); z++) {
                                Material m = w.at(x, yy, z);
                                if (m != Material.STONE_BRICK_WALL && m != Material.LANTERN) continue;
                                bx.clear();
                                boxes(w, x, yy, z, bx);
                                for (double[] o : bx)
                                    if (sq[0] < o[3] + 0.02 && sq[3] > o[0] - 0.02 && sq[1] < o[4] + 0.02 && sq[4] > o[1] - 0.02 && sq[2] < o[5] + 0.02 && sq[5] > o[2] - 0.02) {
                                        String what = m == Material.LANTERN ? "lantern" : (yy - st.y < 3.5 ? "post" : "arm");
                                        String tag = what + "@" + x + "," + yy + "," + z;
                                        if (!touches.contains(tag)) touches.add(tag);
                                    }
                            }
                }
            }
            double done = seg;
            if (done > best + 0.05) { best = done; since = 0; }
            else if (++since > 100) return String.format(java.util.Locale.ROOT, "stuck at (%.2f, %.2f, %.2f) seg %d", st.x, st.y, st.z, seg);
        }
        return "too slow";
    }

    static Object[] road(List<double[]> corners, int half, int every) {
        Flat f = new Flat();
        List<double[]> pts = RoadPlanner.densify(corners, 1);
        int[] h = new int[pts.size()];
        java.util.Arrays.fill(h, half);
        RoadProfile.Part[] parts = new RoadProfile.Part[pts.size()];
        java.util.Arrays.fill(parts, RoadProfile.Part.ROAD);
        RoadSection.Style st = new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, every, 1000);
        RoadSection s = new RoadSection(f, st, pts, h, parts, "북해", 3);
        for (int i = 0; i < pts.size(); i++) s.build(i);
        return new Object[]{f, pts};
    }

    @Test
    @SuppressWarnings("unchecked")
    void probe() throws Exception {
        Map<String, VehicleSpec> vs = RoadClearanceTest.landVehicles();
        Map<String, List<double[]>> shapes = new java.util.LinkedHashMap<>();
        shapes.put("straight", List.of(new double[]{0.5, 0.5}, new double[]{160.5, 0.5}));
        shapes.put("diag45", List.of(new double[]{0.5, 0.5}, new double[]{120.5, 120.5}));
        shapes.put("diag30", List.of(new double[]{0.5, 0.5}, new double[]{140.5, 80.5}));
        shapes.put("diag18", List.of(new double[]{0.5, 0.5}, new double[]{150.5, 49.5}));
        shapes.put("bend", List.of(new double[]{0.5, 0.5}, new double[]{50.5, 0.5}, new double[]{80.5, 12.5}, new double[]{100.5, 32.5}, new double[]{112.5, 62.5}, new double[]{112.5, 140.5}));
        shapes.put("bendL", List.of(new double[]{0.5, 0.5}, new double[]{50.5, 0.5}, new double[]{80.5, -12.5}, new double[]{100.5, -32.5}, new double[]{112.5, -62.5}, new double[]{112.5, -140.5}));
        for (String bus : List.of("village_bus", "mini_bus", "limousine_bus", "box_truck")) {
            VehicleSpec s = vs.get(bus);
            for (Map.Entry<String, List<double[]>> e : shapes.entrySet()) {
                for (int half : new int[]{128, 129}) {
                    Object[] r = road(e.getValue(), half, 10);
                    Flat f = (Flat) r[0];
                    List<double[]> pts = (List<double[]>) r[1];
                    StringBuilder sb = new StringBuilder();
                    for (double lat : new double[]{1.25, 1.6, 2.0, 2.3, 2.6}) {
                        for (boolean rev : new boolean[]{false, true}) {
                            List<String> touch = new ArrayList<>();
                            String res = follow(s, f::get, pts, k -> lat, 9, rev, touch);
                            if (res != null || !touch.isEmpty())
                                sb.append(String.format(java.util.Locale.ROOT, "  lat %.2f %s: %s touches %d %s%n", lat, rev ? "back" : "fwd", res, touch.size(),
                                        touch.subList(0, Math.min(3, touch.size()))));
                        }
                    }
                    System.out.println("== " + bus + " " + e.getKey() + " half " + half + "\n" + sb);
                }
            }
        }
    }
}
