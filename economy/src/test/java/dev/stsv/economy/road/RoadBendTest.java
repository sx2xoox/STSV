package dev.stsv.economy.road;

import dev.stsv.core.vehicle.DriveInput;
import dev.stsv.core.vehicle.VehiclePhysics;
import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoadBendTest {

    static Map<String, VehicleSpec> longOnes;

    @BeforeAll
    static void read() throws IOException {
        Map<String, VehicleSpec> all = RoadClearanceTest.landVehicles();
        longOnes = new LinkedHashMap<>();
        for (String id : List.of("limousine_bus", "village_bus", "box_truck", "mini_bus")) {
            assertNotNull(all.get(id), id);
            longOnes.put(id, all.get(id));
        }
        for (Map.Entry<String, VehicleSpec> e : all.entrySet()) if (e.getValue().length() >= 4.5) longOnes.putIfAbsent(e.getKey(), e.getValue());
    }

    // ------------------------------------------------------------------------------------------
    // Land
    // ------------------------------------------------------------------------------------------

    interface Ground {
        int top(int x, int z);
    }

    interface Wet {
        boolean water(int x, int z);
    }

    static final int WATER_TOP = 62;

    /** Land from a height function (grass over dirt over stone), water where wet (up to {@link #WATER_TOP}). */
    static final class Terra implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();
        final Ground ground;
        final Wet wet;
        RoadNetwork net = RoadNetwork.NONE;
        List<int[]> log;

        Terra(Ground g, Wet w) {
            this.ground = g;
            this.wet = w;
        }

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        Material base(int x, int y, int z) {
            int g = ground.top(x, z);
            if (y <= g) return y == g ? Material.GRASS_BLOCK : y >= g - 3 ? Material.DIRT : Material.STONE;
            if (wet.water(x, z) && y <= WATER_TOP) return Material.WATER;
            return Material.AIR;
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            return m != null ? m : base(x, y, z);
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public boolean liquid(int x, int y, int z) {
            return get(x, y, z) == Material.WATER;
        }

        public boolean natural(int x, int y, int z) {
            return false;
        }

        public boolean fillable(int x, int y, int z) {
            Material m = get(x, y, z);
            return m == Material.AIR || m == Material.WATER;
        }

        public void set(int x, int y, int z, Material m) {
            if (m == null || !inside(y) || get(x, y, z) == m) return;
            put.put(key(x, y, z), m);
            if (log != null) log.add(new int[]{x, y, z});
        }

        public boolean protectedLand(int x, int z) {
            return false;
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}

        public RoadNetwork.Spot road(int x, int z) {
            return net.at(x, z);
        }

        int[] sample(int x, int z) {
            int top = 200;
            while (top > -64 && get(x, top, z) == Material.AIR) top--;
            if (get(x, top, z) == Material.WATER) {
                int floor = top;
                while (get(x, floor, z) == Material.WATER) floor--;
                return new int[]{floor, 1, top};
            }
            return new int[]{top, 0, top};
        }

        Terra copy() {
            Terra t = new Terra(ground, wet);
            t.put.putAll(put);
            t.net = net;
            return t;
        }
    }

    static final RoadSection.Style STYLE = new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000);

    record Road(Terra land, List<double[]> points, int[] halves, RoadProfile.Part[] parts, RoadSection section, String dock) {}

    /** A road built over the land as RoadModule does (ground read, crossings, profile, parts), among the roads there. */
    static Road build(Terra land, List<double[]> corners, String dock, boolean old) {
        List<double[]> pts = RoadPlanner.densify(corners, 1);
        int n = pts.size();
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            int[] s = land.sample((int) Math.floor(pts.get(i)[0]), (int) Math.floor(pts.get(i)[1]));
            ground[i] = s[0];
            water[i] = s[1] == 1;
            surface[i] = s[2];
        }
        int[] cross = land.net.crossings(pts);
        RoadProfile.Plan plan = RoadProfile.planNetwork(ground, water, surface, cross, land.net.headroom(pts));
        RoadProfile.Part[] parts = RoadProfile.partsHalves(plan.halves(), ground, water, RoadModule.BRIDGE_GAP, RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN);
        for (int i = 0; i < n; i++) if (plan.over()[i]) parts[i] = RoadProfile.Part.BRIDGE;
        RoadSection sec = new RoadSection(land, STYLE, pts, plan.halves(), parts, dock, 1).network(cross, plan.over());
        for (int i = 0; i < n; i++) assertNull(sec.build(i));
        return new Road(land, pts, plan.halves(), parts, sec, dock);
    }

    // ------------------------------------------------------------------------------------------
    // Driving
    // ------------------------------------------------------------------------------------------

    static VehiclePhysics.Terrain terrain(Terra w) {
        VehiclePhysics.Terrain t = RoadLightsTest.terrain(w::get);
        return new VehiclePhysics.Terrain() {
            public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                t.boxes(minX, minY, minZ, maxX, maxY, maxZ, out);
            }

            public VehiclePhysics.Medium medium(int x, int y, int z) {
                return w.get(x, y, z) == Material.WATER ? VehiclePhysics.Medium.WATER : VehiclePhysics.Medium.AIR;
            }
        };
    }

    /** A contact: what, where (lat from the road's centre line, + right of the way driven), on a bend's inside or outside. */
    record Hit(String block, int x, int y, int z, double lat, String side, double rise, String how) {}

    /** When set: every tick, the footprint's corners' signed distance from the polyline and how far along (from the start) that is. */
    static java.util.function.Consumer<double[]> reach;

    record Drive(String stuck, int bumps, int refused, List<Hit> hits) {
        boolean clean() {
            return stuck == null && bumps == 0 && hits.isEmpty();
        }

        @Override
        public String toString() {
            return (stuck == null ? "through" : stuck) + " bumps " + bumps + " refused " + refused
                    + (hits.isEmpty() ? "" : " hits " + hits.subList(0, Math.min(5, hits.size())));
        }
    }

    /** The path a driver follows: the points, lat out to the right. */
    static List<double[]> path(List<double[]> pts, IntToDoubleFunction lat) {
        int n = pts.size();
        List<double[]> path = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(n - 1, i + 2));
            double hx = b[0] - a[0], hz = b[1] - a[1], l = Math.hypot(hx, hz);
            hx /= l;
            hz /= l;
            double o = lat.applyAsDouble(i);
            path.add(new double[]{pts.get(i)[0] - hz * o, pts.get(i)[1] + hx * o});
        }
        return path;
    }

    static List<double[]> reversed(List<double[]> pts) {
        List<double[]> out = new ArrayList<>(pts);
        java.util.Collections.reverse(out);
        return out;
    }

    /** How much the line turns over the next `ahead` points from k (degrees). */
    static double turnAhead(List<double[]> p, int k, int ahead) {
        int a = Math.max(0, k - 1), b = Math.min(p.size() - 1, k + ahead), c = Math.min(p.size() - 1, b + 4);
        double[] u = {p.get(Math.min(p.size() - 1, a + 2))[0] - p.get(a)[0], p.get(Math.min(p.size() - 1, a + 2))[1] - p.get(a)[1]};
        double max = 0;
        for (int j = a + 2; j < c; j++) {
            double[] v = {p.get(j + 1)[0] - p.get(j)[0], p.get(j + 1)[1] - p.get(j)[1]};
            double d = Math.toDegrees(Math.abs(Math.atan2(u[0] * v[1] - u[1] * v[0], u[0] * v[0] + u[1] * v[1])));
            max = Math.max(max, d);
        }
        return max;
    }

    /**
     * Drives along the road points `pts` (as given), its line lat out, at `cruise`, slowing to `bend` where the way ahead
     * turns 30 degrees or more, steering A / D towards a point ahead. jitter: a sloppy driver (steers late, overshoots).
     */
    static Drive drive(VehicleSpec s, Road r, boolean back, IntToDoubleFunction lat, double cruise, double bend, boolean sloppy) {
        return drive(s, r.land(), back ? reversed(r.points()) : r.points(), back ? reversed(r.halves()) : r.halves(), lat, cruise, bend, sloppy);
    }

    static int[] reversed(int[] h) {
        int[] out = new int[h.length];
        for (int i = 0; i < h.length; i++) out[i] = h[h.length - 1 - i];
        return out;
    }

    static Drive drive(VehicleSpec s, Terra w, List<double[]> pts, int[] halves, IntToDoubleFunction lat, double cruise, double bend, boolean sloppy) {
        VehiclePhysics.Terrain t = terrain(w);
        List<double[]> path = path(pts, lat);
        double[] p0 = path.get(3), p1 = path.get(6);
        double y = Double.NEGATIVE_INFINITY;
        for (int k = 0; k <= 3 + (int) Math.ceil(s.length()); k++) y = Math.max(y, RoadSection.top(halves[k]));
        VehiclePhysics.State st = new VehiclePhysics.State(p0[0], y + 0.01, p0[1], (float) Math.toDegrees(Math.atan2(-(p1[0] - p0[0]), p1[1] - p0[1])));
        List<Hit> hits = new ArrayList<>();
        int seg = 3, since = 0, best = 0, bumps = 0, refused = 0, hold = 0, lastSteer = 0;
        for (int tick = 0; tick < 40_000; tick++) {
            int bi = seg;
            double bd = Double.MAX_VALUE;
            for (int k = Math.max(0, seg - 3); k < Math.min(path.size(), seg + 14); k++) {
                double d = Math.hypot(path.get(k)[0] - st.x, path.get(k)[1] - st.z);
                if (d < bd) {
                    bd = d;
                    bi = k;
                }
            }
            seg = bi;
            if (seg >= path.size() - 8) return new Drive(null, bumps, refused, hits);
            int look = (int) Math.max(4, s.length() * 0.7 + Math.abs(st.speed) * 0.4);
            double want = turnAhead(pts, seg, look + 10) >= 30 ? bend : cruise;
            double[] g = path.get(Math.min(path.size() - 1, seg + look));
            float err = VehiclePhysics.wrap((float) Math.toDegrees(Math.atan2(-(g[0] - st.x), g[1] - st.z)) - st.yaw);
            if (err > 180) err -= 360;
            int steer = Math.abs(err) < 1.0 ? 0 : err > 0 ? -1 : 1;
            if (sloppy) {   // reacts 4 ticks late, and keeps a turn on 3 ticks more
                if (steer != lastSteer && hold < 4) {
                    hold++;
                    steer = lastSteer;
                } else hold = 0;
            }
            lastSteer = steer;
            float yawBefore = st.yaw;
            double x0 = st.x, z0 = st.z;
            VehiclePhysics.step(st, new DriveInput(st.speed < want, st.speed > want + 1.5, steer == 1, steer == -1, false, false), s, t, 20);
            boolean clipped = Math.hypot(st.x - x0, st.z - z0) < Math.abs(st.speed) * VehiclePhysics.DT * 0.98 - 1e-3;
            boolean turnRefused = steer != 0 && st.speed > 0.5 && st.yaw == yawBefore;
            if (st.bumped) bumps++;
            if (turnRefused) refused++;
            if (reach != null) {
                for (double[] q : VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s))
                    for (double[] c : new double[][]{{q[0], q[2]}, {q[3], q[2]}, {q[0], q[5]}, {q[3], q[5]}}) {
                        double[] l = latOf(pts, c[0], c[1]);
                        reach.accept(new double[]{l[0], l[1], along(pts, c[0], c[1])});
                    }
            }
            if (st.bumped || clipped || turnRefused) contacts(s, st, w, pts, hits, (st.bumped ? "B" : "") + (clipped ? "C" : "") + (turnRefused ? "T" : ""));
            if (seg > best) {
                best = seg;
                since = 0;
            } else if (++since > 160) {
                return new Drive(String.format(Locale.ROOT, "stuck at (%.2f, %.2f, %.2f) seg %d", st.x, st.y, st.z, seg), bumps, refused, hits);
            }
        }
        return new Drive("too slow", bumps, refused, hits);
    }

    /** Signed distance (+ right of the way pts run) from (x, z) to the polyline, and whether that side is a bend's inside. */
    static double[] latOf(List<double[]> pts, double x, double z) {
        double best = Double.MAX_VALUE, sign = 1;
        int at = 0;
        for (int j = 0; j + 1 < pts.size(); j++) {
            double[] a = pts.get(j), b = pts.get(j + 1);
            double d = RoadSection.pointToSegment(x, z, a[0], a[1], b[0], b[1]);
            if (d < best) {
                best = d;
                double vx = b[0] - a[0], vz = b[1] - a[1];
                // right of (vx, vz) is (-vz, vx)
                sign = Math.signum((x - a[0]) * -vz + (z - a[1]) * vx);
                at = j;
            }
        }
        // the turn there: over 10 points either way (+: turning right)
        double[] a = pts.get(Math.max(0, at - 10)), p = pts.get(at), b = pts.get(Math.min(pts.size() - 1, at + 10));
        double cross = (p[0] - a[0]) * (b[1] - p[1]) - (p[1] - a[1]) * (b[0] - p[0]);
        // Minecraft: x east, z south; right of heading (hx, hz) is (-hz, hx): cross > 0 = turning right
        return new double[]{sign * best, Math.abs(cross) < 1 ? 0 : Math.signum(cross)};
    }

    /** How far along the polyline (blocks from its start) its nearest point to (x, z) is. */
    static double along(List<double[]> pts, double x, double z) {
        double best = Double.MAX_VALUE, at = 0, run = 0;
        for (int j = 0; j + 1 < pts.size(); j++) {
            double[] a = pts.get(j), b = pts.get(j + 1);
            double d = RoadSection.pointToSegment(x, z, a[0], a[1], b[0], b[1]);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (d < best) {
                best = d;
                double t = len < 1e-9 ? 0 : Math.clamp(((x - a[0]) * (b[0] - a[0]) + (z - a[1]) * (b[1] - a[1])) / (len * len), 0, 1);
                at = run + t * len;
            }
            run += len;
        }
        return at;
    }

    @Test
    void measureReach() {
        for (int ang : new int[]{20, 30, 45, 60, 90, 120, 135}) {
            double r = Math.toRadians(ang);
            List<double[]> c = corners(0, 0, 60, 0, 60 + (int) Math.round(60 * Math.cos(r)), (int) Math.round(60 * Math.sin(r)));
            Road road = build(new Terra((x, z) -> 63, DRY), c, "a", false);
            // strip everything over the road's level: an open plain
            road.land().put.entrySet().removeIf(e -> {
                long k = e.getKey();
                int y = (int) (k & ((1 << 21) - 1)) - 1024;
                return y >= 65;
            });
            for (String mode : List.of("careful", "normal", "sloppy")) {
                double[] in = new double[60], out = new double[60];
                java.util.Arrays.fill(in, 0);
                java.util.Arrays.fill(out, 0);
                double vertex = 60;
                reach = v -> {
                    int b = (int) Math.round(v[2] - vertex) + 30;
                    if (b < 0 || b >= 60) return;
                    double d = Math.abs(v[0]);
                    boolean inside = v[1] != 0 && Math.signum(v[0]) == v[1];
                    if (inside) in[b] = Math.max(in[b], d);
                    else out[b] = Math.max(out[b], d);
                };
                for (VehicleSpec s : longOnes.values())
                    for (double lat : LATS)
                        for (boolean back : new boolean[]{false, true}) {
                            if (mode.equals("careful")) drive(s, road, back, k -> lat, 9, 4.5, false);
                            else if (mode.equals("normal")) drive(s, road, back, k -> lat, 9, 6.5, false);
                            else if (Math.abs(lat) < 1.5) drive(s, road, back, k -> lat, 10, 10, true);
                        }
                reach = null;
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%3d %-8s", ang, mode));
                for (int b = 0; b < 60; b += 3) sb.append(String.format(Locale.ROOT, " %+d:%.1f/%.1f", b - 30, in[b], out[b]));
                System.out.println(sb);
            }
        }
    }

    static String role(Material m, Terra w, int x, int y, int z) {
        String n = m.name();
        if (m == Material.ANDESITE_WALL) return "guardrail";
        if (m == Material.STONE_BRICK_WALL) return w.get(x, y - 1, z) == Material.STONE_BRICKS && w.get(x, y + 1, z) == Material.STONE_BRICK_WALL ? "light-post" : "railing";
        if (m == Material.END_ROD) return "reflector";
        if (m == Material.LANTERN) return "lantern";
        if (m == Material.SMOOTH_STONE || m == Material.SMOOTH_STONE_SLAB) return "kerb";
        if (m == Material.WHITE_CONCRETE || m == Material.YELLOW_TERRACOTTA || m == Material.POLISHED_ANDESITE || m == Material.SEA_LANTERN) return "tunnel-wall";
        if (m == Material.STONE_BRICKS) return "stone-bricks";
        if (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.STONE || m == Material.DIRT_PATH) return "bank";
        return n.toLowerCase(Locale.ROOT);
    }

    /** What the vehicle's squares touch now (within 0.03) above its floor: rise is how far over its bottom the box's top is. */
    static void contacts(VehicleSpec s, VehiclePhysics.State st, Terra w, List<double[]> pts, List<Hit> out, String how) {
        List<double[]> bx = new ArrayList<>();
        for (double[] q : VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s)) {
            for (int x = (int) Math.floor(q[0] - 0.05); x <= (int) Math.floor(q[3] + 0.05); x++)
                for (int y = (int) Math.floor(q[1]) - 1; y <= (int) Math.floor(q[4] + 0.05); y++)
                    for (int z = (int) Math.floor(q[2] - 0.05); z <= (int) Math.floor(q[5] + 0.05); z++) {
                        Material m = w.get(x, y, z);
                        bx.clear();
                        RoadLightsTest.boxes(w::get, x, y, z, bx);
                        for (double[] o : bx) {
                            if (o[4] <= st.y + 0.02 || o[1] >= q[4]) continue;   // under it (the floor) or over it
                            if (q[0] < o[3] + 0.03 && q[3] > o[0] - 0.03 && q[2] < o[5] + 0.03 && q[5] > o[2] - 0.03) {
                                double rise = o[4] - st.y;
                                if (rise <= 0.55) continue;   // a half step: stepped up smoothly
                                double[] l = latOf(pts, x + 0.5, z + 0.5);
                                String side = l[1] == 0 ? "straight" : Math.signum(l[0]) == l[1] ? "inside" : "outside";
                                Hit h = new Hit(role(m, w, x, y, z), x, y, z, Math.round(l[0] * 100) / 100.0, side, Math.round(rise * 100) / 100.0, how);
                                boolean seen = false;
                                for (Hit e : out) seen |= e.x() == x && e.y() == y && e.z() == z;
                                if (!seen) out.add(h);
                            }
                        }
                    }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------------------------------

    static List<double[]> corners(double... xz) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) out.add(new double[]{xz[i] + 0.5, xz[i + 1] + 0.5});
        return out;
    }

    static final Ground FLAT = (x, z) -> 63;
    static final Wet DRY = (x, z) -> false;

    static Map<String, Road> scenes() {
        Map<String, Road> out = new LinkedHashMap<>();
        // flat corners: 45, 90, 135 degrees
        out.put("flat 45", build(new Terra(FLAT, DRY), corners(0, 0, 60, 0, 120, 60), "a", false));
        out.put("flat 90", build(new Terra(FLAT, DRY), corners(0, 0, 60, 0, 60, 60), "a", false));
        out.put("flat 135", build(new Terra(FLAT, DRY), corners(0, 0, 60, 0, 10, 50), "a", false));
        out.put("flat S", build(new Terra(FLAT, DRY), corners(0, 0, 40, 0, 56, 16, 56, 40, 72, 56, 110, 56), "a", false));
        // round a hill (an octagon and a square): its inside cut into the hill, its outside on a fill
        Ground hill = (x, z) -> (int) Math.floor(63 + Math.clamp(0.7 * (46 - Math.hypot(x - 60, z - 60)), -8, 20));
        out.put("hill octagon", build(new Terra(hill, DRY), corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 60, 85, 100), "a", false));
        out.put("hill square", build(new Terra(hill, DRY), corners(0, 22, 98, 22, 98, 110), "a", false));
        // round a bowl: the inside on a fill, the outside cut
        Ground bowl = (x, z) -> (int) Math.floor(63 - Math.clamp(0.7 * (46 - Math.hypot(x - 60, z - 60)), -14, 12));
        out.put("bowl square", build(new Terra(bowl, DRY), corners(0, 22, 98, 22, 98, 110), "a", false));
        // a bridge with a bend over a lake
        Wet lake = (x, z) -> Math.hypot(x - 60, z - 30) < 40;
        Ground bed = (x, z) -> Math.hypot(x - 60, z - 30) < 40 ? 56 : 63;
        out.put("bridge 45", build(new Terra(bed, lake), corners(0, 0, 50, 0, 100, 50, 100, 110), "a", false));
        out.put("bridge 90", build(new Terra(bed, lake), corners(0, 20, 60, 20, 60, 110), "a", false));
        // a tunnel bending in a steep hill and at its portal
        Ground mount = (x, z) -> (int) Math.floor(63 + Math.clamp(2.0 * (40 - Math.max(Math.abs(x - 70), Math.abs(z - 40))), 0, 30));
        out.put("tunnel 45", build(new Terra(mount, DRY), corners(0, 40, 70, 40, 120, 90, 120, 130), "a", false));
        out.put("portal 45", build(new Terra(mount, DRY), corners(0, 0, 32, 0, 72, 40, 140, 40), "a", false));
        // a grade with a bend (half steps)
        Ground slope = (x, z) -> (int) Math.floor(63 + Math.clamp((x - 20) * 0.3, 0, 18));
        out.put("grade 90", build(new Terra(slope, DRY), corners(0, 0, 70, 0, 70, 80), "a", false));
        out.put("grade 45", build(new Terra(slope, DRY), corners(0, 0, 50, 0, 110, 60), "a", false));
        // a diagonal on a hillside
        Ground side = (x, z) -> (int) Math.floor(63 + Math.clamp((z - x) * 0.4, -10, 14));
        out.put("diag hillside", build(new Terra(side, DRY), corners(0, 0, 120, 120), "a", false));
        return out;
    }

    static final double[] LATS = {1.25, 2.0, -1.25, -2.0};

    @Test
    void survey() {
        if (true) return;
        Map<String, Road> scenes = scenes();
        Map<String, Integer> roles = new TreeMap<>();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Road> sc : scenes.entrySet()) {
            Road r = sc.getValue();
            for (Map.Entry<String, VehicleSpec> v : longOnes.entrySet()) {
                if (!List.of("limousine_bus", "village_bus", "box_truck", "mini_bus").contains(v.getKey())) continue;
                for (double lat : LATS) {
                    for (boolean back : new boolean[]{false, true}) {
                        for (int mode = 0; mode < 2; mode++) {
                            Drive d = mode == 0 ? drive(v.getValue(), r, back, k -> lat, 9, 4.5, false) : drive(v.getValue(), r, back, k -> lat, 10, 10, true);
                            if (d.clean()) continue;
                            sb.append(sc.getKey()).append(" | ").append(v.getKey()).append(" lat ").append(lat).append(back ? " back" : "").append(mode == 1 ? " sloppy" : "").append(": ").append(d).append('\n');
                            for (Hit h : d.hits()) roles.merge(sc.getKey() + " " + h.block() + " " + h.side() + " lat~" + Math.round(Math.abs(h.lat())) + " " + h.how(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        System.out.println(sb);
        roles.forEach((k, n) -> System.out.println(k + " x" + n));
    }
}
