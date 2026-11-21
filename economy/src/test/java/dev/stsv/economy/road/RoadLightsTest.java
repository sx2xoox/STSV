package dev.stsv.economy.road;

import dev.stsv.core.vehicle.DriveInput;
import dev.stsv.core.vehicle.VehiclePhysics;
import dev.stsv.core.vehicle.VehicleSpec;
import dev.stsv.economy.bus.BusStop;
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
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 가로등 and the buses (the owner: "국도에 가로등이 있는데 버스가 거기에 엄청 많이 걸려"). The long vehicles (the three
 * buses and the box truck, read from the core's content) driven by their own physics ({@link VehiclePhysics}) the way
 * players drive them: a steering driver following a line, holding its lane, its outer edge, a little over the kerb onto the
 * verge, overtaking in the other lane, both ways, on straight, diagonal and bent roads, up and down a grade in half steps;
 * pulling into a bus stop's bay and out again at a slant. The world's blocks have their real collision boxes (a wall a
 * 0.5 post 1.5 high joined to its neighbours, a hanging lantern its box). What touches a light's post, arm or lantern is
 * recorded.
 * <ul>
 *   <li>as roads were built before ({@link RoadSection#oldLights}: the post's column the one nearest 4 out), the posts stood
 *       3.18 from the centre line on a diagonal or a bend (0.32 inside the kerb's edge) and the buses' squares (axis-aligned:
 *       1.53 to each side at 45 degrees) scraped or stuck on them; a bay cleared the lights only along itself, and a bus
 *       swinging in and out met the posts before and after it;</li>
 *   <li>as built now every post is {@link RoadSection#LIGHT_CLEAR} or more from the centre line (none on a bend's inside),
 *       a bay clears its side {@link BusStop#LIGHT_CLEAR} either way ({@link BusStop#lightsInTheWay}), and no bus touches
 *       a light; the arm and lantern never were in the way (also on grades and slabs: {@link RoadClearance});</li>
 *   <li>/관리자 국도 가로등정리 ({@link RoadSection#tidyLight}) mends an old road and an old bay: only the lights' own blocks
 *       on the road's shoulder, a player's blocks left, twice is once.</li>
 * </ul>
 */
class RoadLightsTest {

    static Map<String, VehicleSpec> buses;

    @BeforeAll
    static void read() throws IOException {
        Map<String, VehicleSpec> all = RoadClearanceTest.landVehicles();
        buses = new LinkedHashMap<>();
        for (String id : List.of("village_bus", "mini_bus", "limousine_bus", "box_truck")) {
            assertNotNull(all.get(id), id);
            buses.put(id, all.get(id));
        }
        // every land vehicle 4.5 long or more is driven too
        for (Map.Entry<String, VehicleSpec> e : all.entrySet()) if (e.getValue().length() >= 4.5) buses.putIfAbsent(e.getKey(), e.getValue());
        assertTrue(buses.get("limousine_bus").length() >= 8.5);
    }

    // ------------------------------------------------------------------------------------------
    // A flat world with real collision boxes
    // ------------------------------------------------------------------------------------------

    /** Grass at 63 over stone; what was put over it (a log of the changes when recording). */
    static final class Flat implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();
        List<int[]> log;

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            if (m != null) return m;
            return y < 63 ? Material.STONE : y == 63 ? Material.GRASS_BLOCK : Material.AIR;
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public boolean liquid(int x, int y, int z) {
            return false;
        }

        public boolean natural(int x, int y, int z) {
            return false;
        }

        public boolean fillable(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public void set(int x, int y, int z, Material m) {
            if (m == null || get(x, y, z) == m) return;
            put.put(key(x, y, z), m);
            if (log != null) log.add(new int[]{x, y, z});
        }

        public boolean protectedLand(int x, int z) {
            return false;
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}

        Flat copy() {
            Flat f = new Flat();
            f.put.putAll(put);
            return f;
        }

        /** Every light post (its lowest wall over its footing): {x, y, z}. */
        List<int[]> posts() {
            List<int[]> out = new ArrayList<>();
            for (Map.Entry<Long, Material> e : put.entrySet()) {
                if (e.getValue() != Material.STONE_BRICK_WALL) continue;
                long k = e.getKey();
                int y = (int) (k & ((1 << 21) - 1)) - 1024, z = (int) ((k >> 21) & ((1 << 21) - 1)) - (1 << 20), x = (int) (k >>> 42) - (1 << 20);
                if (get(x, y - 1, z) == Material.STONE_BRICKS && RoadLights.at(this::get, x, z, y, y) != null) out.add(new int[]{x, y, z});
            }
            return out;
        }
    }

    static boolean wall(Material m) {
        return m != null && (m.name().endsWith("_WALL") || m.name().endsWith("_FENCE"));
    }

    /** A full solid cube (what a wall joins to). */
    static boolean cube(Material m) {
        if (m == null || m == Material.AIR || wall(m) || m.name().endsWith("_SLAB") || m == Material.LANTERN || m == Material.END_ROD
                || m == Material.IRON_TRAPDOOR || m == Material.DIRT_PATH) return false;
        List<double[]> b = new ArrayList<>();
        RoadClearanceTest.boxes(m, 0, 0, 0, b);
        return b.size() == 1 && b.getFirst()[4] == 1 && b.getFirst()[0] == 0;
    }

    /** The collision boxes as the server has them: a wall's post 0.5 across (1.5 high) and bars to what it joins, a lantern its box. */
    static void boxes(RoadClearanceTest.Blocks w, int x, int y, int z, List<double[]> out) {
        Material m = w.at(x, y, z);
        if (wall(m)) {
            out.add(new double[]{x + 0.25, y, z + 0.25, x + 0.75, y + 1.5, z + 0.75});
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Material n = w.at(x + d[0], y, z + d[1]);
                if (!wall(n) && !cube(n)) continue;
                double x0 = d[0] > 0 ? 0.5 : d[0] < 0 ? 0 : 0.3125, x1 = d[0] > 0 ? 1 : d[0] < 0 ? 0.5 : 0.6875;
                double z0 = d[1] > 0 ? 0.5 : d[1] < 0 ? 0 : 0.3125, z1 = d[1] > 0 ? 1 : d[1] < 0 ? 0.5 : 0.6875;
                out.add(new double[]{x + x0, y, z + z0, x + x1, y + 1.5, z + z1});
            }
        } else if (m == Material.LANTERN) {
            if (wall(w.at(x, y + 1, z))) out.add(new double[]{x + 0.3125, y + 0.0625, z + 0.3125, x + 0.6875, y + 0.625, z + 0.6875});
            else out.add(new double[]{x + 0.3125, y, z + 0.3125, x + 0.6875, y + 0.5625, z + 0.6875});
        } else {
            RoadClearanceTest.boxes(m, x, y, z, out);
        }
    }

    static VehiclePhysics.Terrain terrain(RoadClearanceTest.Blocks w) {
        return new VehiclePhysics.Terrain() {
            public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++)
                    for (int y = (int) Math.floor(minY) - 1; y <= (int) Math.floor(maxY); y++)
                        for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) RoadLightsTest.boxes(w, x, y, z, out);
            }

            public VehiclePhysics.Medium medium(int x, int y, int z) {
                return VehiclePhysics.Medium.AIR;
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // A driver
    // ------------------------------------------------------------------------------------------

    /** How a drive went: null if it got to the end; else where it stuck. What of a light it touched (post / arm / lantern @ x,y,z). */
    record Drive(String stuck, List<String> touched) {
        boolean clean() {
            return stuck == null && touched.isEmpty();
        }

        @Override
        public String toString() {
            return (stuck == null ? "through" : stuck) + (touched.isEmpty() ? "" : ", touched " + touched.subList(0, Math.min(4, touched.size())));
        }
    }

    /**
     * Drives a vehicle along the road's points (back: from the end), lat(k) out from the middle to the right of the way it
     * goes at its k-th point, at cruise blocks/s, as a player does: W while slower, A or D towards a point a little ahead
     * on that line (a bus's length and a bit: it cuts in a little round a bend).
     */
    static Drive drive(VehicleSpec s, Flat w, List<double[]> pts, IntToDoubleFunction lat, double cruise, boolean back) {
        VehiclePhysics.Terrain t = terrain(w::get);
        int n = pts.size();
        List<double[]> path = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = back ? n - 1 - k : k;
            double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(n - 1, i + 2));
            double hx = b[0] - a[0], hz = b[1] - a[1], l = Math.hypot(hx, hz);
            hx /= l;
            hz /= l;
            if (back) {
                hx = -hx;
                hz = -hz;
            }
            double o = lat.applyAsDouble(k);
            path.add(new double[]{pts.get(i)[0] - hz * o, pts.get(i)[1] + hx * o});
        }
        double[] p0 = path.get(3), p1 = path.get(6);
        int y = 120;
        while (y > 0 && RoadClearanceTest.isOpen(w.get((int) Math.floor(p0[0]), y, (int) Math.floor(p0[1])))) y--;
        VehiclePhysics.State st = new VehiclePhysics.State(p0[0], y + 1.01, p0[1], (float) Math.toDegrees(Math.atan2(-(p1[0] - p0[0]), p1[1] - p0[1])));
        List<String> touched = new ArrayList<>();
        int seg = 3, since = 0, best = 0;
        for (int tick = 0; tick < 30_000; tick++) {
            int bi = seg;
            double bd = Double.MAX_VALUE;
            for (int k = Math.max(0, seg - 3); k < Math.min(path.size(), seg + 12); k++) {
                double d = Math.hypot(path.get(k)[0] - st.x, path.get(k)[1] - st.z);
                if (d < bd) {
                    bd = d;
                    bi = k;
                }
            }
            seg = bi;
            if (seg >= path.size() - 8) return new Drive(null, touched);
            double[] g = path.get(Math.min(path.size() - 1, seg + (int) Math.max(4, s.length() * 0.7 + Math.abs(st.speed) * 0.4)));
            float err = VehiclePhysics.wrap((float) Math.toDegrees(Math.atan2(-(g[0] - st.x), g[1] - st.z)) - st.yaw);
            if (err > 180) err -= 360;
            int steer = Math.abs(err) < 1.0 ? 0 : err > 0 ? -1 : 1;   // A turns the yaw down
            VehiclePhysics.step(st, new DriveInput(st.speed < cruise, false, steer == 1, steer == -1, false, false), s, t, 20);
            touches(s, st, w, touched);
            if (seg > best) {
                best = seg;
                since = 0;
            } else if (++since > 120) {
                return new Drive(String.format(Locale.ROOT, "stuck at (%.2f, %.2f, %.2f)", st.x, st.y, st.z), touched);
            }
        }
        return new Drive("too slow", touched);
    }

    /** What of a light the vehicle's squares touch now (within 0.02). */
    static void touches(VehicleSpec s, VehiclePhysics.State st, Flat w, List<String> out) {
        List<double[]> bx = new ArrayList<>();
        for (double[] q : VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s)) {
            for (int x = (int) Math.floor(q[0] - 0.05); x <= (int) Math.floor(q[3] + 0.05); x++)
                for (int y = (int) Math.floor(q[1]) - 1; y <= (int) Math.floor(q[4] + 0.05); y++)
                    for (int z = (int) Math.floor(q[2] - 0.05); z <= (int) Math.floor(q[5] + 0.05); z++) {
                        Material m = w.get(x, y, z);
                        if (m != Material.STONE_BRICK_WALL && m != Material.LANTERN) continue;
                        bx.clear();
                        boxes(w::get, x, y, z, bx);
                        for (double[] o : bx) {
                            if (q[0] < o[3] + 0.02 && q[3] > o[0] - 0.02 && q[1] < o[4] + 0.02 && q[4] > o[1] - 0.02 && q[2] < o[5] + 0.02 && q[5] > o[2] - 0.02) {
                                String what = (m == Material.LANTERN ? "lantern" : w.get(x, y - 1, z) == Material.AIR ? "arm" : "post") + "@" + x + "," + y + "," + z;
                                if (!out.contains(what)) out.add(what);
                            }
                        }
                    }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Roads
    // ------------------------------------------------------------------------------------------

    static final Map<String, List<double[]>> SHAPES = new LinkedHashMap<>();

    static {
        SHAPES.put("straight", List.of(new double[]{0.5, 0.5}, new double[]{160.5, 0.5}));
        SHAPES.put("diagonal 45", List.of(new double[]{0.5, 0.5}, new double[]{120.5, 120.5}));
        SHAPES.put("diagonal 30", List.of(new double[]{0.5, 0.5}, new double[]{140.5, 80.5}));
        SHAPES.put("diagonal 18", List.of(new double[]{0.5, 0.5}, new double[]{150.5, 49.5}));
        SHAPES.put("bend right", List.of(new double[]{0.5, 0.5}, new double[]{50.5, 0.5}, new double[]{80.5, 12.5}, new double[]{100.5, 32.5},
                new double[]{112.5, 62.5}, new double[]{112.5, 140.5}));
        SHAPES.put("bend left", List.of(new double[]{0.5, 0.5}, new double[]{50.5, 0.5}, new double[]{80.5, -12.5}, new double[]{100.5, -32.5},
                new double[]{112.5, -62.5}, new double[]{112.5, -140.5}));
        SHAPES.put("sharp", List.of(new double[]{0.5, 0.5}, new double[]{60.5, 0.5}, new double[]{90.5, 30.5}, new double[]{90.5, 120.5}));
        SHAPES.put("zigzag", List.of(new double[]{0.5, 0.5}, new double[]{30.5, 0.5}, new double[]{50.5, 20.5}, new double[]{80.5, 20.5},
                new double[]{100.5, 0.5}, new double[]{140.5, 0.5}));
        SHAPES.put("grade 45", List.of(new double[]{0.5, 0.5}, new double[]{120.5, 120.5}));
    }

    /** Lights every 10 (the config's 40, packed: more of them, every way they fall on the columns). */
    static final int EVERY = 10;

    record Road(Flat land, List<double[]> points, int[] halves, RoadProfile.Part[] parts, RoadSection section) {}

    /** A road built over the flat land (old: its lights as roads were built before); "grade": up 8 and down at 1 in 3, in half steps. */
    static Road road(String shape, boolean old) {
        Flat f = new Flat();
        List<double[]> pts = RoadPlanner.densify(SHAPES.get(shape), 1);
        double[] p = new double[pts.size()];
        for (int i = 0; i < p.length; i++) p[i] = shape.startsWith("grade") ? 64 + Math.clamp(Math.min(i - 30, p.length - 30 - i) / 3.0, 0, 8) : 64;
        int[] h = RoadProfile.halves(p);
        RoadProfile.Part[] parts = new RoadProfile.Part[pts.size()];
        java.util.Arrays.fill(parts, RoadProfile.Part.ROAD);
        RoadSection.Style style = new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, EVERY, 1000);
        RoadSection s = new RoadSection(f, style, pts, h, parts, "북해", 3);
        if (old) s.oldLights();
        for (int i = 0; i < pts.size(); i++) assertNull(s.build(i));
        return new Road(f, pts, h, parts, s);
    }

    /** Distance from a post's box (0.5 across in its column) to the road's centre line. */
    static double clearance(List<double[]> pts, int x, int z) {
        double d = Double.MAX_VALUE;
        for (int j = 0; j + 1 < pts.size(); j++)
            d = Math.min(d, RoadSection.boxToSegment(x + 0.25, z + 0.25, x + 0.75, z + 0.75, pts.get(j)[0], pts.get(j)[1], pts.get(j + 1)[0], pts.get(j + 1)[1]));
        return d;
    }

    /** Lanes and their edges, over the kerb onto the verge, overtaking in the other lane. */
    static final double[] LATS = {1.25, 2.0, 2.6, -1.25, -2.6};

    // ------------------------------------------------------------------------------------------
    // Why: the posts as roads were built before
    // ------------------------------------------------------------------------------------------

    @Test
    void theOldPostsStoodInTheKerbsLineAndTheBusesCaughtOnThem() {
        // straight: 3.75 out (a quarter beyond the kerb); diagonal and bend: the column nearest 4 out stood half in the kerb's line
        Road straight = road("straight", true), diag = road("diagonal 45", true), bend = road("bend left", true);
        assertEquals(3.75, straight.land().posts().stream().mapToDouble(c -> clearance(straight.points(), c[0], c[2])).min().orElseThrow(), 1e-9);
        double d = diag.land().posts().stream().mapToDouble(c -> clearance(diag.points(), c[0], c[2])).min().orElseThrow();
        assertTrue(d < 3.5, "a post inside the kerb's edge on the diagonal: " + d);
        // a bus 2.4 wide's squares reach 1.53 to each side at 45 degrees (they do not turn with it): on the kerb a little
        VehicleSpec bus = buses.get("village_bus");
        assertEquals(1.08 * Math.sqrt(2), bus.squareSize() / 2 * Math.sqrt(2), 1e-9);
        assertFalse(drive(bus, diag.land(), diag.points(), k -> 2.0, 9, false).clean(), "the village bus on the diagonal's kerb");
        // round a bend in the middle of its lane the limousine bus cut in to the inside's post
        Drive limo = drive(buses.get("limousine_bus"), bend.land(), bend.points(), k -> 1.25, 9, true);
        assertFalse(limo.clean(), "the limousine bus round the bend in its lane: " + limo);
        assertTrue(limo.touched().stream().allMatch(t -> t.startsWith("post")), "only posts: " + limo);
        // over the verge round a bend: stuck on a post
        Road right = road("bend right", true);
        List<Drive> stuck = new ArrayList<>();
        for (VehicleSpec s : buses.values())
            for (Road r : List.of(bend, right))
                for (boolean back : new boolean[]{false, true}) {
                    Drive one = drive(s, r.land(), r.points(), k -> 2.6, 9, back);
                    if (one.stuck() != null) stuck.add(one);
                }
        assertFalse(stuck.isEmpty(), "a bus stuck on a post round a bend");
    }

    // ------------------------------------------------------------------------------------------
    // The roads as built now
    // ------------------------------------------------------------------------------------------

    @Test
    void everyPostStandsClearOfTheKerbAndOffABendsInside() {
        for (String shape : SHAPES.keySet()) {
            Road r = road(shape, false);
            List<int[]> posts = r.land().posts();
            int expected = (r.points().size() - 2) / EVERY;
            assertTrue(posts.size() >= expected - 1, shape + ": a light at (or just after) every " + EVERY + " points: " + posts.size() + " of " + expected);
            for (int[] c : posts) {
                double d = clearance(r.points(), c[0], c[2]);
                assertTrue(d >= RoadSection.LIGHT_CLEAR - 1e-9, shape + ": a post " + d + " from the centre line at " + c[0] + ", " + c[2]);
                assertTrue(d <= 5.5 + RoadSection.WIDEN, shape + ": on the shoulder (wider at the gate): " + d);
            }
        }
    }

    @Test
    void noBusTouchesALightInItsLaneOverTheKerbOrOvertaking() {
        List<String> bad = new ArrayList<>();
        for (String shape : SHAPES.keySet()) {
            Road r = road(shape, false);
            for (Map.Entry<String, VehicleSpec> e : buses.entrySet()) {
                for (double lat : LATS) {
                    for (boolean back : new boolean[]{false, true}) {
                        Drive d = drive(e.getValue(), r.land(), r.points(), k -> lat, 9, back);
                        if (!d.clean()) bad.add(shape + " " + e.getKey() + " " + lat + (back ? " back" : "") + ": " + d);
                    }
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    // ------------------------------------------------------------------------------------------
    // Bus stops
    // ------------------------------------------------------------------------------------------

    static final int BAY = 24, WIDTH = 3;

    /** The stop at point c, on the right. */
    static BusStop stop(Road r, int c) {
        double[] a = r.points().get(c - 2), b = r.points().get(c + 2);
        double hx = b[0] - a[0], hz = b[1] - a[1], l = Math.hypot(hx, hz);
        return new BusStop(1, "x", "w", r.points().get(c)[0], r.points().get(c)[1], r.halves()[c], hx / l, hz / l, 1, 4, false, true, "");
    }

    /** The bay as StopBuilder paves it; now: the lights in the way cleared ({@link BusStop#lightsInTheWay}), before: only along it. */
    static void pave(Flat f, BusStop st, boolean now) {
        int y = st.y();
        List<int[]> lights = now ? st.lightsInTheWay(BAY, f::get) : List.of();
        for (BusStop.Column c : st.bayColumns(BAY, WIDTH)) {
            f.set(c.x(), y, c.z(), c.edge() ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE);
            for (int up = 1; up <= 9; up++) if (up <= 4 || f.get(c.x(), y + up, c.z()) != Material.AIR) f.set(c.x(), y + up, c.z(), Material.AIR);
        }
        for (BusStop.Column c : st.shoulderColumns(BAY)) {
            f.set(c.x(), y, c.z(), Material.GRAY_CONCRETE);
            for (int up = 1; up <= 9; up++) if (f.get(c.x(), y + up, c.z()) != Material.AIR) f.set(c.x(), y + up, c.z(), Material.AIR);
        }
        for (double a = -BAY / 2.0; a <= BAY / 2.0; a += 0.5)   // over the edge lane: a light's arm and lantern (as before)
            for (double o = 1.5; o <= BusStop.KERB; o += 0.5) {
                double[] p = st.at(a, o);
                for (int up = 3; up <= 7; up++) {
                    Material m = f.get((int) Math.floor(p[0]), y + up, (int) Math.floor(p[1]));
                    if (m == Material.LANTERN || m == Material.STONE_BRICK_WALL) f.set((int) Math.floor(p[0]), y + up, (int) Math.floor(p[1]), Material.AIR);
                }
            }
        for (int[] b : lights) f.set(b[0], b[1], b[2], Material.AIR);
    }

    /** In from the lane to the bay's middle (7 out) over `slant` points, ending 4 before the stop's point; out again the same. */
    static IntToDoubleFunction inAndOut(int c, int slant) {
        return k -> {
            double a = k - c, in0 = -4 - slant, in1 = -4, out0 = 4, out1 = 4 + slant;
            double t = a < in0 ? 0 : a < in1 ? (a - in0) / slant : a <= out0 ? 1 : a < out1 ? 1 - (a - out0) / slant : 0;
            return 1.25 + (7.0 - 1.25) * (1 - Math.cos(Math.PI * t)) / 2;
        };
    }

    /** Pulls in and out of a stop at each of 10 places along the lights (old roads: a light every 20 on its side), at 3 slants. */
    static List<String> bays(String shape, boolean now, int limit) {
        List<String> bad = new ArrayList<>();
        Road base = road(shape, true);   // the roads stops stand by were built before
        for (int c = 70; c < 90; c += 2) {
            Flat f = base.land().copy();
            BusStop st = stop(base, c);
            pave(f, st, now);
            for (Map.Entry<String, VehicleSpec> e : buses.entrySet()) {
                for (int slant : new int[]{12, 18, 26}) {
                    Drive d = drive(e.getValue(), f, base.points(), inAndOut(c, slant), 5, false);
                    if (!d.clean()) bad.add(shape + " stop at " + c + " " + e.getKey() + " slant " + slant + ": " + d);
                    if (bad.size() >= limit) return bad;
                }
            }
        }
        return bad;
    }

    @Test
    void theOldBayLeftThePostsBeforeAndAfterItInTheWay() {
        List<String> bad = bays("straight", false, 5);
        assertFalse(bad.isEmpty());
        assertTrue(bad.stream().anyMatch(s -> s.contains("post@")), bad.toString());
    }

    @Test
    void everyBusPullsInAndOutOfABayClearedNow() {
        List<String> bad = new ArrayList<>();
        for (String shape : List.of("straight", "diagonal 30", "diagonal 45")) bad.addAll(bays(shape, true, 20));
        assertEquals(List.of(), bad);
    }

    @Test
    void aBayClearsOnlyTheLightsOnItsSide() {
        Road r = road("straight", true);
        BusStop st = stop(r, 80);
        List<int[]> lights = st.lightsInTheWay(BAY, r.land()::get);
        assertFalse(lights.isEmpty());
        for (int[] b : lights) {
            double[] l = st.local(b[0] + 0.5, b[2] + 0.5);
            assertTrue(l[1] > 1 && l[1] < BusStop.ROAD_HALF, "its side, over the road and the shoulder: " + java.util.Arrays.toString(b));
            assertTrue(Math.abs(l[0]) <= BAY / 2.0 + BusStop.LIGHT_CLEAR + 1, "along it: " + java.util.Arrays.toString(b));
            Material m = r.land().get(b[0], b[1], b[2]);
            assertTrue(m == Material.STONE_BRICK_WALL || m == Material.LANTERN, m.name());
        }
        // the other side's lights stay; a simple stop (no bay) clears none
        BusStop left = new BusStop(2, "y", "w", st.x(), st.z(), st.half(), st.hx(), st.hz(), -1, 4, false, true, "");
        for (int[] b : left.lightsInTheWay(BAY, r.land()::get)) assertTrue(st.local(b[0] + 0.5, b[2] + 0.5)[1] < 0);
        assertTrue(new BusStop(3, "z", "w", st.x(), st.z(), st.half(), st.hx(), st.hz(), 1, 4, false, false, "").lightsInTheWay(BAY, r.land()::get).isEmpty());
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 국도 가로등정리
    // ------------------------------------------------------------------------------------------

    /** A point `out` to the right of the road's point k (left: below 0). */
    static double[] beside(Road r, int k, double out) {
        double[] a = r.points().get(k - 2), b = r.points().get(k + 2), p = r.points().get(k);
        double hx = b[0] - a[0], hz = b[1] - a[1], l = Math.hypot(hx, hz);
        return new double[]{p[0] - hz / l * out, p[1] + hx / l * out};
    }

    /** /관리자 국도 가로등정리 over every point of the road, as RoadModule does it (a RoadSection of the saved road over the world). */
    static int[] tidy(Road r, Flat f, java.util.function.BiPredicate<Integer, Integer> keepClear) {
        RoadSection.Style style = r.section().style();
        RoadSection s = new RoadSection(f, style, r.points(), r.halves(), r.parts(), "북해", 3);
        int[] n = {0, 0, 0};
        for (int i = 0; i < r.points().size(); i++) {
            int[] t = s.tidyLight(i, keepClear);
            for (int k = 0; k < 3; k++) n[k] += t[k];
        }
        return n;
    }

    @Test
    void theTidyMendsOldRoadsAndOldBaysAndTouchesNothingElse() {
        for (String shape : List.of("diagonal 45", "bend left", "zigzag", "grade 45")) {
            Road r = road(shape, true);
            Flat f = r.land();
            int before = f.posts().size();
            // a stop built before (its bay cleared only along itself), a player's things by the road: a stone brick wall
            // standing on stone bricks out past the shoulder, a chest and a lantern on the verge
            int c = 80;
            BusStop st = stop(r, c);
            pave(f, st, false);
            double[] far = beside(r, 40, -7.5), verge = beside(r, 30, -4.0);
            for (int k = 30; f.get((int) Math.floor(verge[0]), RoadSection.surfaceY(r.halves()[k]) + 1, (int) Math.floor(verge[1])) != Material.AIR; k--)
                verge = beside(r, k, -4.0);   // by no light
            int fx = (int) Math.floor(far[0]), fz = (int) Math.floor(far[1]), vx = (int) Math.floor(verge[0]), vz = (int) Math.floor(verge[1]);
            int fy = RoadSection.surfaceY(r.halves()[40]), vy = RoadSection.surfaceY(r.halves()[30]) + 1;
            f.set(fx, fy, fz, Material.STONE_BRICKS);
            for (int y = fy + 1; y <= fy + 7; y++) f.set(fx, y, fz, Material.STONE_BRICK_WALL);
            f.set(vx, vy, vz, Material.CHEST);
            Map<Long, Material> was = new HashMap<>(f.put);
            f.log = new ArrayList<>();
            java.util.function.BiPredicate<Integer, Integer> keep = (x, z) -> st.keepsClearOfLights(x, z, BAY);
            int[] n = tidy(r, f, keep);
            assertTrue(n[0] > 0 && n[1] > 0, shape + ": lights moved: " + java.util.Arrays.toString(n));
            // what changed: only lights' own blocks (walls, lanterns), their footings (stone bricks, the verge's path) on the shoulder
            for (int[] k : f.log) {
                Material from = was.getOrDefault(Flat.key(k[0], k[1], k[2]), new Flat().get(k[0], k[1], k[2])), to = f.get(k[0], k[1], k[2]);
                for (Material m : new Material[]{from, to})
                    assertTrue(m == Material.AIR || m == Material.STONE_BRICK_WALL || m == Material.LANTERN || m == Material.STONE_BRICKS
                            || m == Material.DIRT_PATH || m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.END_ROD, shape + ": " + m + " at " + java.util.Arrays.toString(k));
                assertTrue(clearance(r.points(), k[0], k[2]) <= 5.75 + RoadSection.WIDEN, shape + ": on the road's cross-section: " + java.util.Arrays.toString(k));
            }
            assertEquals(Material.CHEST, f.get(vx, vy, vz));
            for (int y = fy + 1; y <= fy + 7; y++) assertEquals(Material.STONE_BRICK_WALL, f.get(fx, y, fz), "a player's wall");
            // every light where a road puts it now, none by the bay; about as many as before (but the bay's)
            List<int[]> posts = f.posts();
            for (int[] p : posts) {
                assertTrue(clearance(r.points(), p[0], p[2]) >= RoadSection.LIGHT_CLEAR - 1e-9, shape + ": " + java.util.Arrays.toString(p));
                assertFalse(keep.test(p[0], p[2]), shape + ": by the bay: " + java.util.Arrays.toString(p));
            }
            assertTrue(posts.size() >= before - 4, shape + ": " + posts.size() + " of " + before);
            // twice is once
            f.log = new ArrayList<>();
            assertEquals(0, tidy(r, f, keep)[2]);
            assertEquals(List.of(), f.log);
            // and the buses: in the lanes and over the kerb, both ways, and in and out of the bay
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, VehicleSpec> e : buses.entrySet()) {
                for (double lat : new double[]{1.25, 2.6, -2.6}) {
                    for (boolean back : new boolean[]{false, true}) {
                        Drive d = drive(e.getValue(), f, r.points(), k -> lat, 9, back);
                        if (!d.clean()) bad.add(shape + " " + e.getKey() + " " + lat + (back ? " back" : "") + ": " + d);
                    }
                }
                if (!shape.startsWith("bend") && !shape.startsWith("zig")) {   // (a stop stands on a straight stretch)
                    for (int slant : new int[]{12, 26}) {
                        Drive d = drive(e.getValue(), f, r.points(), inAndOut(c, slant), 5, false);
                        if (!d.clean()) bad.add(shape + " bay " + e.getKey() + " slant " + slant + ": " + d);
                    }
                }
            }
            assertEquals(List.of(), bad);
        }
    }

    @Test
    void aLightIsKnownByItsWholeShapeOnly() {
        Flat f = new Flat();
        // a light: footing, 6 walls, an arm of 2, a lantern under its end
        f.set(0, 64, 0, Material.STONE_BRICKS);
        for (int y = 65; y <= 70; y++) f.set(0, y, 0, Material.STONE_BRICK_WALL);
        f.set(0, 70, 1, Material.STONE_BRICK_WALL);
        f.set(0, 70, 2, Material.STONE_BRICK_WALL);
        f.set(0, 69, 2, Material.LANTERN);
        RoadLights.Light l = RoadLights.at(f::get, 0, 0, 60, 70);
        assertNotNull(l);
        assertEquals(6 + 2 + 1, l.blocks().size());
        assertEquals(List.of(0, 69, 2), List.of(l.lantern()[0], l.lantern()[1], l.lantern()[2]));
        // no lantern: not a light (but what /관리자 국도 가로등정리 knows on a road's shoulder as one left by a stop)
        f.set(0, 69, 2, Material.AIR);
        assertNull(RoadLights.at(f::get, 0, 0, 60, 70));
        assertNotNull(RoadLights.post(f::get, 0, 0, 65));
        f.set(0, 69, 2, Material.LANTERN);
        // too short, no footing, or a wall built on to its arm: not a light
        Flat g = f.copy();
        g.set(0, 64, 0, Material.COBBLESTONE);
        assertNull(RoadLights.at(g::get, 0, 0, 60, 70));
        g = f.copy();
        for (int y = 65; y <= 69; y++) g.set(0, y, 2, Material.STONE_BRICK_WALL);
        assertNull(RoadLights.at(g::get, 0, 0, 60, 70));
        g = f.copy();
        g.set(0, 65, 0, Material.AIR);
        g.set(0, 65, 0, Material.STONE_BRICKS);
        g.set(0, 66, 0, Material.STONE_BRICKS);
        assertNull(RoadLights.at(g::get, 0, 0, 60, 70));
    }

    @Test
    void theRulesNumbers() {
        // a post a block and a quarter beyond the kerb on a straight road, never less than 4.5 from the centre line;
        // a bay's stretch covers the longest bus swinging in and out (14.3 measured) with a margin
        assertTrue(RoadSection.LIGHT_CLEAR >= 3.5 + 1.0);
        assertTrue(BusStop.LIGHT_CLEAR >= Math.ceil(RoadClearance.LENGTH) + 6);
        assertTrue(RoadSection.BEND_REACH >= RoadClearance.LENGTH);
    }
}
