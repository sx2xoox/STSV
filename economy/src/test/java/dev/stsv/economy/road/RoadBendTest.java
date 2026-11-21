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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 굽은 길 여유 (the owner: "국도가 이미 지어져 있는 애들이 급커브 같은 곳에 담장이 있거나, 리무진버스 같은 거나 막 갑자기 벽
 * 맞은 판정"): the long vehicles (the three buses, the box truck and every land vehicle 4.5 long or more, read from the
 * core's content) driven by their own physics ({@link VehiclePhysics}) the way players drive them (A / D towards a point ahead
 * on a line in their lane or on its edge line; a careful driver slowing for a bend, a sloppy one at full speed steering late)
 * along roads built as RoadModule builds them over made-up land: right-angled and 45 degree bends, an S-bend, a road round a
 * hill (cut on its inside, filled on its outside) and round a bowl, bridges straight, on a diagonal and bending, tunnels
 * bending and a portal on a bend, grades with bends (half steps), a diagonal along a hillside, T junctions (90 and 45 degrees)
 * and a road crossing another. Every contact is recorded (what block, its role, how far from the centre line, which side of
 * the bend; how: a bump, a clipped move, a turn not made).
 * <ul>
 *   <li>as roads were built before ({@link RoadSection#narrow}, corners not level) their railings, tunnel walls, guardrails,
 *       reflectors and cut banks stood in the long vehicles' reach and they caught on them;</li>
 *   <li>as built now nothing stands in the reach ({@link RoadSection#reach}), and no long vehicle touches anything on any of
 *       the shapes (the sloppy driver on those no sharper than 45 degrees), nor turning at a junction;</li>
 *   <li>/관리자 국도 커브정리 ({@link RoadSection#tidyCurve}) mends an old road: only the road's own blocks and the ground's,
 *       never a lane, a player's blocks left, twice is once; then the long vehicles drive it clean.</li>
 * </ul>
 */
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
        if (!old) {   // as RoadModule.profileOf: sharp corners level
            boolean[] fixed = new boolean[n];
            for (int i = 0; i < n; i++) fixed[i] = plan.over()[i] || cross[i] != Integer.MIN_VALUE;
            RoadProfile.levelBends(pts, plan.halves(), fixed);
        }
        RoadProfile.Part[] parts = RoadProfile.partsHalves(plan.halves(), ground, water, RoadModule.BRIDGE_GAP, RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN);
        for (int i = 0; i < n; i++) if (plan.over()[i]) parts[i] = RoadProfile.Part.BRIDGE;
        RoadSection sec = new RoadSection(land, STYLE, pts, plan.halves(), parts, dock, 1).network(cross, plan.over());
        if (old) sec.narrow();
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
    /** A round hill (its slopes 0.7: a road round it cuts in on its inside and fills on its outside) and a bowl. */
    static final Ground HILL = (x, z) -> (int) Math.floor(63 + Math.clamp(0.7 * (46 - Math.hypot(x - 60, z - 60)), -8, 20));
    static final Ground BOWL = (x, z) -> (int) Math.floor(63 - Math.clamp(0.7 * (46 - Math.hypot(x - 60, z - 60)), -14, 12));
    static final Wet LAKE = (x, z) -> Math.hypot(x - 60, z - 30) < 40;
    static final Ground LAKE_BED = (x, z) -> Math.hypot(x - 60, z - 30) < 40 ? 56 : 63;
    /** A steep hill (2 in 1) the road tunnels through. */
    static final Ground MOUNT = (x, z) -> (int) Math.floor(63 + Math.clamp(2.0 * (40 - Math.max(Math.abs(x - 70), Math.abs(z - 40))), 0, 30));
    static final Ground SLOPE = (x, z) -> (int) Math.floor(63 + Math.clamp((x - 20) * 0.3, 0, 18));
    static final Ground SIDE = (x, z) -> (int) Math.floor(63 + Math.clamp((z - x) * 0.4, -10, 14));

    /** The shapes (old: as roads were built before: {@link RoadSection#narrow}, no level corners), the sharpest turning this many degrees. */
    static Map<String, Road> scenes(boolean old) {
        Map<String, Road> out = new LinkedHashMap<>();
        out.put("flat 45", build(new Terra(FLAT, DRY), corners(0, 0, 60, 0, 120, 60), "a", old));
        out.put("flat 90", build(new Terra(FLAT, DRY), corners(0, 0, 60, 0, 60, 60), "a", old));
        out.put("S-bend", build(new Terra(FLAT, DRY), corners(0, 0, 40, 0, 56, 16, 56, 40, 72, 56, 110, 56), "a", old));
        out.put("hill octagon", build(new Terra(HILL, DRY), corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 60, 85, 100), "a", old));
        out.put("hill square", build(new Terra(HILL, DRY), corners(0, 22, 98, 22, 98, 110), "a", old));
        out.put("bowl square", build(new Terra(BOWL, DRY), corners(0, 22, 98, 22, 98, 110), "a", old));
        out.put("bridge straight", build(new Terra(LAKE_BED, LAKE), corners(0, 30, 120, 30), "a", old));
        out.put("bridge 45", build(new Terra(LAKE_BED, LAKE), corners(0, 0, 50, 0, 100, 50, 100, 110), "a", old));
        out.put("bridge 90", build(new Terra(LAKE_BED, LAKE), corners(0, 20, 60, 20, 60, 110), "a", old));
        out.put("tunnel 45", build(new Terra(MOUNT, DRY), corners(0, 40, 70, 40, 120, 90, 120, 130), "a", old));
        out.put("tunnel 90", build(new Terra(MOUNT, DRY), corners(0, 40, 70, 40, 70, 110), "a", old));
        out.put("portal 45", build(new Terra(MOUNT, DRY), corners(0, 0, 32, 0, 72, 40, 140, 40), "a", old));
        out.put("grade 45", build(new Terra(SLOPE, DRY), corners(0, 0, 50, 0, 110, 60), "a", old));
        out.put("grade 90", build(new Terra(SLOPE, DRY), corners(0, 0, 70, 0, 70, 80), "a", old));
        out.put("diagonal hillside", build(new Terra(SIDE, DRY), corners(0, 0, 120, 120), "a", old));
        return out;
    }

    /** The shapes no sharper than 45 degrees (a driver keeps his speed and steers late there too). */
    static final List<String> GENTLE = List.of("flat 45", "bridge straight", "bridge 45", "tunnel 45", "portal 45", "grade 45", "diagonal hillside");

    /** A main road and another built after it meeting it level (a junction): a T at a right angle, one at 45 degrees, a crossing. */
    record Junction(Road main, Road branch, int at) {}

    static Map<String, Junction> junctions(boolean old) {
        Map<String, Junction> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> e : Map.of("T 90", corners(100, 0, 100, 90), "T 45", corners(100, 0, 170, 70),
                "crossing", corners(100, -70, 100, 70)).entrySet()) {
            Terra land = new Terra(FLAT, DRY);
            Road main = build(land, corners(0, 0, 160, 0), "a", old);
            land.net = RoadNetwork.of(List.of(new RoadNetwork.Road("a", main.points(), main.halves())));
            Road branch = build(land, e.getValue(), "b", old);
            out.put(e.getKey(), new Junction(main, branch, 100));
        }
        return out;
    }

    /** Along the main road to the junction, then onto the branch (its points from the main road's centre line on). */
    static List<double[]> turn(Junction j, boolean fromStart) {
        List<double[]> out = new ArrayList<>();
        List<double[]> m = j.main().points(), b = j.branch().points();
        int on = 0;
        for (int k = 0; k < b.size(); k++) if (Math.abs(b.get(k)[1] - 0.5) < 0.01 && Math.abs(b.get(k)[0] - (j.at() + 0.5)) < 0.01) on = k;
        if (fromStart) for (int k = 0; k <= j.at(); k++) out.add(m.get(k));
        else for (int k = m.size() - 1; k >= j.at(); k--) out.add(m.get(k));
        for (int k = on + 1; k < b.size(); k++) out.add(b.get(k));
        return out;
    }

    static int[] turnHalves(Junction j, int n) {
        int[] h = new int[n];
        java.util.Arrays.fill(h, j.main().halves()[0]);
        return h;
    }

    static final double[] LATS = {1.25, 2.0, -1.25, -2.0};

    /** Drives a careful driver (slows to 4.5 for a bend) and, on the gentle shapes, a sloppy one at full speed: what was not clean. */
    static List<String> driveAll(String name, Road r, boolean sloppyToo, List<String> vehicles) {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, VehicleSpec> v : longOnes.entrySet()) {
            boolean main = vehicles.contains(v.getKey());
            for (double lat : main ? LATS : new double[]{1.25, -2.0}) {
                for (boolean back : main ? new boolean[]{false, true} : new boolean[]{false}) {
                    Drive d = drive(v.getValue(), r, back, k -> lat, 9, 4.5, false);
                    if (!d.clean()) bad.add(name + " " + v.getKey() + " " + lat + (back ? " back" : "") + ": " + d);
                    if (sloppyToo && main) {
                        d = drive(v.getValue(), r, back, k -> lat, 10, 10, true);
                        if (!d.clean()) bad.add(name + " " + v.getKey() + " " + lat + (back ? " back" : "") + " sloppy: " + d);
                    }
                }
            }
        }
        return bad;
    }

    static final List<String> BUSES = List.of("limousine_bus", "village_bus", "box_truck", "mini_bus");

    // ------------------------------------------------------------------------------------------
    // What a vehicle can hit near a road
    // ------------------------------------------------------------------------------------------

    /**
     * Every block box over a road's surface (from half a block over it to a vehicle's height) nearer its centre line than
     * the vehicles reach there ({@link RoadSection#reach}): "material@x,y,z distance < reach". narrowReach: measured against
     * the reach of a road built now (for an old road: what it left in the way).
     */
    static List<String> inTheWay(Road r, RoadSection rule) {
        List<String> out = new ArrayList<>();
        List<double[]> pts = r.points();
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxZ = Math.max(maxZ, p[1]);
        }
        List<double[]> bx = new ArrayList<>();
        int pad = 10;
        for (int x = (int) Math.floor(minX) - pad; x <= (int) Math.floor(maxX) + pad; x++) {
            for (int z = (int) Math.floor(minZ) - pad; z <= (int) Math.floor(maxZ) + pad; z++) {
                // the nearest point (not the ends: a road begins and ends in the open, where the vehicles start)
                int at = -1;
                double d = Double.MAX_VALUE;
                for (int j = 0; j + 1 < pts.size(); j++) {
                    double e = RoadSection.boxToSegment(x, z, x + 1, z + 1, pts.get(j)[0], pts.get(j)[1], pts.get(j + 1)[0], pts.get(j + 1)[1]);
                    if (e < d) {
                        d = e;
                        at = j;
                    }
                }
                if (at < 12 || at > pts.size() - 14 || d > 9) continue;
                double reach = Math.min(rule.reach(at), Math.min(rule.reach(Math.max(0, at - 1)), rule.reach(at + 1)));   // (its column's own point is one of these)
                if (d >= reach) continue;
                double floor = Double.NEGATIVE_INFINITY;
                for (int j = Math.max(0, at - 2); j <= Math.min(pts.size() - 1, at + 2); j++) floor = Math.max(floor, RoadSection.top(r.halves()[j]));
                for (int y = (int) Math.floor(floor); y <= (int) Math.floor(floor + 3); y++) {
                    bx.clear();
                    RoadLightsTest.boxes(r.land()::get, x, y, z, bx);
                    for (double[] o : bx) {
                        if (o[4] <= floor + 0.55 || o[1] >= floor + 3) continue;
                        double od = Double.MAX_VALUE;
                        for (int j = Math.max(0, at - 14); j < Math.min(pts.size() - 1, at + 14); j++)
                            od = Math.min(od, RoadSection.boxToSegment(o[0], o[2], o[3], o[5], pts.get(j)[0], pts.get(j)[1], pts.get(j + 1)[0], pts.get(j + 1)[1]));
                        if (od < reach - 1e-6) {
                            out.add(String.format(Locale.ROOT, "%s@%d,%d,%d %.2f < %.2f", r.land().get(x, y, z).name().toLowerCase(Locale.ROOT), x, y, z, od, reach));
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** What of the old road the rule finds in the way, by kind (railing, tunnel wall, guardrail, bank ...). */
    static Map<String, Integer> kinds(List<String> in) {
        Map<String, Integer> out = new TreeMap<>();
        for (String s : in) out.merge(s.substring(0, s.indexOf('@')), 1, Integer::sum);
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Why: roads as they were built
    // ------------------------------------------------------------------------------------------

    @Test
    void theOldRoadsLeftWallsInTheLongVehiclesWay() {
        Map<String, Road> old = scenes(true), now = scenes(false);
        // the numbers: a bus 2.4 wide on the edge line (2.0 out) reaches 3.08 on a road along x or z, 3.53 on a diagonal
        // (axis-aligned squares); the luxury SUV 2.7 wide 3.21 and 3.72
        assertEquals(3.315, RoadClearance.reach(1, 0), 1e-9);
        assertEquals(2.0 + 1.215 * Math.sqrt(2) + 0.1, RoadClearance.reach(Math.sqrt(2), 0), 1e-9);
        // a bridge's railing stood 2.5 out (its posts 2.75): in the way of a vehicle on the edge line even along x
        Map<String, Integer> bridge = kinds(inTheWay(old.get("bridge straight"), now.get("bridge straight").section()));
        assertTrue(bridge.getOrDefault("stone_brick_wall", 0) > 50, "the old bridge's railing in the way: " + bridge);
        Drive edge = drive(longOnes.get("village_bus"), old.get("bridge straight"), false, k -> 2.0, 9, 4.5, false);
        assertTrue(edge.bumps() > 0 || edge.stuck() != null, "the village bus on the edge line hit the railing's end head-on: " + edge);
        // a tunnel's lined walls 3.5 out: on a diagonal their blocks' corners (2.8 out) in the way even of a bus in its lane
        Map<String, Integer> tunnel = kinds(inTheWay(old.get("tunnel 45"), now.get("tunnel 45").section()));
        assertTrue(tunnel.keySet().stream().anyMatch(k -> k.equals("white_concrete") || k.equals("polished_andesite") || k.equals("stone_bricks")),
                "the old tunnel's walls in the way: " + tunnel);
        // round the bends: guardrails, reflector posts and the cut bank
        Map<String, Integer> bends = new TreeMap<>();
        for (String s : List.of("hill octagon", "hill square", "bowl square", "flat 90"))
            kinds(inTheWay(old.get(s), now.get(s).section())).forEach((k, v) -> bends.merge(k, v, Integer::sum));
        assertTrue(bends.getOrDefault("andesite_wall", 0) > 0, "guardrails round a bend: " + bends);
        assertTrue(bends.getOrDefault("grass_block", 0) + bends.getOrDefault("dirt", 0) + bends.getOrDefault("stone", 0) > 0, "the bank round a bend: " + bends);
        // and the long vehicles caught on them, as the owner saw: in their lanes, slowing for the bends
        List<String> bad = new ArrayList<>();
        for (String s : List.of("bridge 45", "tunnel 45", "hill octagon", "grade 90")) bad.addAll(driveAll(s, old.get(s), false, BUSES));
        assertTrue(bad.size() > 20, "the old roads: " + bad.size());
        assertTrue(bad.stream().anyMatch(s -> s.startsWith("bridge 45") && s.contains("bumps") && !s.contains("bumps 0")), "bumped on the old bridge");
        assertTrue(bad.stream().anyMatch(s -> s.startsWith("grade 90")), "the crease of steps in the old graded corner");
    }

    // ------------------------------------------------------------------------------------------
    // Roads as built now
    // ------------------------------------------------------------------------------------------

    @Test
    void nothingStandsInTheVehiclesReachOnANewRoad() {
        Map<String, List<String>> bad = new LinkedHashMap<>();
        for (Map.Entry<String, Road> e : scenes(false).entrySet()) {
            List<String> in = inTheWay(e.getValue(), e.getValue().section());
            if (!in.isEmpty()) bad.put(e.getKey(), in.subList(0, Math.min(6, in.size())));
        }
        assertEquals(Map.of(), bad);
    }

    @Test
    void longVehiclesDriveEveryNewShapeWithoutAContact() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Road> e : scenes(false).entrySet()) bad.addAll(driveAll(e.getKey(), e.getValue(), GENTLE.contains(e.getKey()), BUSES));
        assertEquals(List.of(), bad);
    }

    @Test
    void longVehiclesTurnAtJunctionsWithoutAContact() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Junction> e : junctions(false).entrySet()) {
            Junction j = e.getValue();
            bad.addAll(driveAll(e.getKey() + " main", j.main(), true, BUSES));
            bad.addAll(driveAll(e.getKey() + " branch", j.branch(), true, BUSES));
            if (e.getKey().equals("crossing")) continue;   // (it goes over the main road: no turning there)
            for (boolean fromStart : new boolean[]{true, false}) {
                if (!fromStart && e.getKey().equals("T 45")) continue;   // (the acute side: a 135 degree hairpin, as a corner that sharp: not driven)
                List<double[]> path = turn(j, fromStart);
                for (boolean back : new boolean[]{false, true}) {
                    List<double[]> pts = back ? reversed(path) : path;
                    for (String id : BUSES)
                        for (double lat : new double[]{1.25, -1.25}) {
                            Drive d = drive(longOnes.get(id), j.main().land(), pts, turnHalves(j, pts.size()), k -> lat, 9, 4.5, false);
                            if (!d.clean()) bad.add(e.getKey() + " turning " + (fromStart ? "from the start" : "from the end") + (back ? " back" : "") + " " + id + " " + lat + ": " + d);
                        }
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 국도 커브정리
    // ------------------------------------------------------------------------------------------

    /** /관리자 국도 커브정리 over a road as RoadModule does it: a RoadSection of the saved road (and where it meets others), every point. */
    static int tidy(Road r, Terra land, RoadNetwork others) {
        land.net = others;
        RoadSection s = new RoadSection(land, STYLE, r.points(), r.halves(), r.parts(), r.dock(), 1);
        int[] cross = others.crossings(r.points());
        boolean[] over = new boolean[cross.length];
        for (int i = 0; i < cross.length; i++) over[i] = cross[i] != Integer.MIN_VALUE && r.parts()[i] == RoadProfile.Part.BRIDGE;
        s.network(cross, over);
        int n = 0;
        for (int i = 0; i < r.points().size(); i++) {
            n += s.tidyCurve(i, (x, z) -> false);
            if (r.parts()[i] == RoadProfile.Part.ROAD) n += s.tidyLight(i, (x, z) -> false)[2];
        }
        return n;
    }

    /** What the tidy may change a block from or to. */
    static final java.util.Set<Material> MAY = java.util.Set.of(Material.AIR, Material.WATER, Material.GRASS_BLOCK, Material.DIRT, Material.STONE,
            Material.DIRT_PATH, Material.ANDESITE_WALL, Material.END_ROD, Material.STONE_BRICKS, Material.STONE_BRICK_WALL, Material.LANTERN,
            Material.SMOOTH_STONE, Material.SMOOTH_STONE_SLAB, Material.WHITE_CONCRETE, Material.YELLOW_TERRACOTTA, Material.POLISHED_ANDESITE,
            Material.SEA_LANTERN, Material.IRON_TRAPDOOR, Material.GRAVEL, Material.COBBLESTONE);

    /** A point `out` to the right of the road's point k. */
    static int[] beside(Road r, int k, double out) {
        double[] a = r.points().get(k - 2), b = r.points().get(k + 2), p = r.points().get(k);
        double hx = b[0] - a[0], hz = b[1] - a[1], l = Math.hypot(hx, hz);
        return new int[]{(int) Math.floor(p[0] - hz / l * out), (int) Math.floor(p[1] + hx / l * out)};
    }

    @Test
    void theTidyMendsOldRoadsAndTouchesNothingElse() {
        Map<String, Road> old = scenes(true), now = scenes(false);
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Road> e : old.entrySet()) {
            String name = e.getKey();
            Road r = e.getValue();
            Terra land = r.land();
            // a player's things: a chest on the verge and planks out on the shoulder round the sharpest bend, a stone brick
            // wall on a bridge's lane, glass in a tunnel's lane
            int bend = 0;
            for (int i = 20; i < r.points().size() - 20; i++) if (now.get(name).section().turn(i) > now.get(name).section().turn(bend)) bend = i;
            if (bend == 0) bend = r.points().size() / 2;
            int y = RoadSection.surfaceY(r.halves()[bend]), base = RoadProfile.block(r.halves()[bend]);
            int[] chest = beside(r, bend, -4.0), planks = beside(r, bend + 3, 6.2), lane = beside(r, bend - 4, 1.0);
            Map<String, Material> mine = new LinkedHashMap<>();
            if (r.parts()[bend] == RoadProfile.Part.ROAD) {
                mine.put(chest[0] + "," + (base + 1) + "," + chest[1], Material.CHEST);
                mine.put(planks[0] + "," + (base + 1) + "," + planks[1], Material.OAK_PLANKS);
            }
            mine.put(lane[0] + "," + (y + 1) + "," + lane[1], r.parts()[bend - 4] == RoadProfile.Part.TUNNEL ? Material.GLASS : Material.STONE_BRICK_WALL);
            mine.forEach((k, m) -> {
                String[] c = k.split(",");
                land.set(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), m);
            });
            Map<Long, Material> was = new HashMap<>(land.put);
            land.log = new ArrayList<>();
            int n = tidy(r, land, RoadNetwork.NONE);
            if (!name.equals("flat 45") && !name.equals("S-bend") && !name.equals("diagonal hillside") && !name.equals("grade 45") && n == 0)
                bad.add(name + ": nothing mended");
            for (int[] k : land.log) {
                Material from = was.getOrDefault(Terra.key(k[0], k[1], k[2]), land.base(k[0], k[1], k[2])), to = land.get(k[0], k[1], k[2]);
                if (!MAY.contains(from) || !MAY.contains(to)) bad.add(name + ": " + from + " -> " + to + " at " + java.util.Arrays.toString(k));
                RoadNetwork.Spot sp = null;
                double d = Double.MAX_VALUE;
                for (int j = 0; j + 1 < r.points().size(); j++)
                    d = Math.min(d, RoadSection.pointToSegment(k[0] + 0.5, k[2] + 0.5, r.points().get(j)[0], r.points().get(j)[1], r.points().get(j + 1)[0], r.points().get(j + 1)[1]));
                if (d > RoadSection.HALF + RoadSection.MAX_WIDE + 1.5) bad.add(name + ": off the road's cross-section " + d + " at " + java.util.Arrays.toString(k));
                // never the lanes (their surface, kerbs and edge lines)
                Material f = from;
                if ((f == Material.GRAY_CONCRETE || f == Material.YELLOW_CONCRETE || f == Material.SMOOTH_QUARTZ || f.name().endsWith("_SLAB")
                        || f == Material.DEEPSLATE_TILES || f == Material.CRACKED_DEEPSLATE_TILES)) bad.add(name + ": a lane's block " + f);
            }
            // twice is once
            land.log = new ArrayList<>();
            int again = tidy(r, land, RoadNetwork.NONE);
            if (again != 0 || !land.log.isEmpty()) bad.add(name + ": again " + again + " " + land.log.size());
            land.log = null;
            mine.forEach((k, m) -> {
                String[] c = k.split(",");
                if (land.get(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])) != m) bad.add(name + ": a player's " + m + " at " + k);
                land.set(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), Material.AIR);   // (out of the way to drive)
            });
            // what stays in the way: only an old graded corner's crease (the lanes themselves: the tidy never relays them)
            List<String> in = inTheWay(r, now.get(name).section());
            in.removeIf(s -> s.startsWith("gray_concrete") || s.startsWith("smooth_quartz") || s.startsWith("yellow_concrete") || s.startsWith("smooth_stone")
                    || s.startsWith("deepslate_tile") || s.startsWith("cracked_deepslate"));
            if (!in.isEmpty()) bad.add(name + " in the way after: " + in.subList(0, Math.min(5, in.size())));
            if (!name.equals("grade 90")) bad.addAll(driveAll(name + " tidied", r, GENTLE.contains(name), BUSES));
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void theTidyMendsOldJunctions() {
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Junction> e : junctions(true).entrySet()) {
            Junction j = e.getValue();
            Terra land = j.main().land();
            RoadNetwork mainNet = RoadNetwork.of(List.of(new RoadNetwork.Road("a", j.main().points(), j.main().halves())));
            RoadNetwork branchNet = RoadNetwork.of(List.of(new RoadNetwork.Road("b", j.branch().points(), j.branch().halves())));
            Map<Long, Material> lanes = new HashMap<>();
            for (Map.Entry<Long, Material> p : land.put.entrySet()) if (p.getValue() == Material.GRAY_CONCRETE || p.getValue() == Material.SMOOTH_QUARTZ) lanes.put(p.getKey(), p.getValue());
            tidy(j.main(), land, branchNet);
            tidy(j.branch(), land, mainNet);
            for (Map.Entry<Long, Material> p : lanes.entrySet()) if (land.put.get(p.getKey()) != p.getValue()) bad.add(e.getKey() + ": a lane changed");
            land.log = new ArrayList<>();
            int again = tidy(j.main(), land, branchNet) + tidy(j.branch(), land, mainNet);
            if (again != 0) bad.add(e.getKey() + ": again " + again);
            land.log = null;
            land.net = mainNet;
            if (e.getKey().equals("crossing")) continue;
            for (boolean fromStart : new boolean[]{true, false}) {
                if (!fromStart && e.getKey().equals("T 45")) continue;   // (the acute side: a 135 degree hairpin, as a corner that sharp: not driven)
                List<double[]> path = turn(j, fromStart);
                for (boolean back : new boolean[]{false, true}) {
                    List<double[]> pts = back ? reversed(path) : path;
                    for (String id : BUSES)
                        for (double lat : new double[]{1.25, -1.25}) {
                            Drive d = drive(longOnes.get(id), land, pts, turnHalves(j, pts.size()), k -> lat, 9, 4.5, false);
                            if (!d.clean()) bad.add(e.getKey() + " tidied, turning " + (fromStart ? "from the start" : "from the end") + (back ? " back" : "") + " " + id + " " + lat + ": " + d);
                        }
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    // ------------------------------------------------------------------------------------------
    // The rule's numbers
    // ------------------------------------------------------------------------------------------

    @Test
    void theRulesNumbers() throws IOException {
        // the widest vehicle 4.5 long or more (its squares 0.9 of its width) and the core's square share
        double widest = 0;
        for (VehicleSpec s : RoadClearanceTest.landVehicles().values()) if (s.length() >= RoadClearance.LONG) widest = Math.max(widest, s.width());
        assertTrue(RoadClearance.WIDTH >= widest, "RoadClearance.WIDTH < " + widest);
        for (VehicleSpec s : longOnes.values()) assertEquals(s.width() * RoadClearance.SQUARE, s.squareSize(), 1e-9, "the core's square share");
        // straight: as roads were (a bridge's railing a block further out); a right angle: the most room, within the
        // chunks the builder loads round the road
        assertEquals(1, RoadClearance.diagonal(1, 0), 1e-9);
        assertEquals(Math.sqrt(2), RoadClearance.diagonal(1, 1), 1e-9);
        assertEquals(RoadClearance.BEND_MAX, RoadClearance.bend(90), 1e-9);
        assertTrue(RoadModule.REACH >= RoadSection.HALF + RoadSection.WIDEN + RoadSection.MAX_WIDE);
        // the level corners keep the grade
        int[] h = new int[80];
        for (int i = 0; i < h.length; i++) h[i] = 128 + i * 2 / 3;
        List<double[]> pts = RoadPlanner.densify(corners(0, 0, 40, 0, 40, 39), 1);
        RoadProfile.levelBends(pts, h, new boolean[h.length]);
        assertTrue(RoadProfile.graded(h));
        // the bus' bump: only at speed
        assertTrue(dev.stsv.economy.bus.BusModule.BUMP_FROM >= 3);
    }
}
