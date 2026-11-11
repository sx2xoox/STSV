package dev.stsv.economy.road;

import org.bukkit.Material;
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
 *   <li>(a) flat grassland: street lights, the 1 km direction sign</li>
 *   <li>(b) a hill climbed at 1 in 3: a dip filled, the hill cut, the slope across it cut on one side and filled on the other</li>
 *   <li>(c) a river: a bridge with railings and piers</li>
 *   <li>(d) a steep hill: a tunnel with its lights</li>
 *   <li>(e) a forest the road bends through: trees cleared</li>
 *   <li>(f) the dock's gate: the road runs straight in, widens, a crosswalk and "하역장 입구" on it, and joins the dock's
 *       floor at its level</li>
 *   <li>(g) a junction: another dock's road (built after) branches off it, level, with a crosswalk and wider corners;
 *       the main road's lanes left as they were; the signs RoadGuide plans once both are built (advance signs before the
 *       fork, the gore sign in the V, confirmation, the way back)</li>
 *   <li>(h) an overpass: an older road in a cutting crosses under it; the main road goes over it, its piers clear of it</li>
 *   <li>(i) a plateau and a ravine: the road comes down across it on a bridge in half steps, its railings and lights on
 *       full blocks (never a wall on a slab or over air)</li>
 * </ol>
 */
class RoadPreviewTest {

    static final int RIVER0 = 1190, RIVER1 = 1210, WATER_TOP = 62;
    /** (i) the ravine: from its west rim (the plateau's edge) to its east rim. */
    static final int RAVINE0 = 884, RAVINE1 = 920;
    /** (f) the dock: its floor's top block y, its front edge (the gate's row, z) and middle (x); the pad behind it. */
    static final int PAD_Y = 64, GATE_Z = 170, GATE_X = 1436, PAD_HALF = 22, PAD_DEPTH = 24;

    /** A world of its own: land from {@link #ground}, trees, a river, and whatever the road put over it. */
    static final class Land implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();
        final List<Sign> signs = new ArrayList<>();
        /** the signs RoadGuide planned, put up once the roads were built */
        final List<RoadGuide.Sign> guided = new ArrayList<>();
        /** words painted on the road: x, y, z, yaw and the words */
        final List<Object[]> paints = new ArrayList<>();

        public void roadText(double x, double y, double z, float yaw, String text) {
            paints.add(new Object[]{x, y, z, yaw, text});
        }

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        /** The ground's top block before the road. */
        static int ground(int x, int z) {
            double g = 64;
            // (i) a plateau 6 up, then a ravine the road crosses going down to the land on its other side (in half steps)
            // and low land beyond it (the road comes down onto it on a fill: guardrails), rising to the land at 64 again
            if (x >= RAVINE0 - 90 && x <= RAVINE1 + 32) {
                double top = 64 + Math.clamp((x - (RAVINE0 - 90)) / 3.0, 0, 6), low = 58 + Math.clamp((x - RAVINE1 - 14) / 3.0, 0, 6);
                double west = top - Math.max(0, x - RAVINE0) * 1.5, east = low - Math.max(0, RAVINE1 - x) * 1.5;
                g = x < RAVINE0 ? top : x > RAVINE1 ? low : Math.max(48, Math.min(west, east));
            }
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
            boolean forest = x >= 1312 && z < GATE_Z - 30;
            boolean meadow = x >= 978 && x < 1050 && Math.abs(z) > 9 && Math.floorMod(h >> 8, 9) == 0;
            if (!forest && !meadow) return null;
            if (forest && Math.floorMod(h >> 8, 10) >= 7) return null;
            return new int[]{x, z, 4 + Math.floorMod(h >> 12, 3)};
        }

        static boolean pad(int x, int z) {
            return Math.abs(x - GATE_X) <= PAD_HALF && z >= GATE_Z && z <= GATE_Z + PAD_DEPTH;
        }

        /** The land as it was. */
        static Material base(int x, int y, int z) {
            if (pad(x, z)) {   // (f) the dock's pad, as DockLayout lays it: a rim, a floor, a low wall open at the gate
                if (y == PAD_Y) return z == GATE_Z || Math.abs(x - GATE_X) == PAD_HALF ? Material.STONE_BRICKS
                        : Math.floorMod(x, 4) == 0 || Math.floorMod(z, 4) == 0 ? Material.ANDESITE : Material.POLISHED_ANDESITE;
                if (y < PAD_Y) return y >= PAD_Y - 3 ? Material.STONE_BRICKS : Material.STONE;
                if (y == PAD_Y + 1 && ((z == GATE_Z && Math.abs(x - GATE_X) > 8) || Math.abs(x - GATE_X) == PAD_HALF)) return Material.STONE_BRICK_WALL;
                return Material.AIR;
            }
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

        public Material get(int x, int y, int z) {
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

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {
            signs.add(new Sign(x, y, z, yaw, kind, RoadSign.words(kind, dock, km, route)));
        }

        RoadNetwork net = RoadNetwork.NONE;

        public RoadNetwork.Spot road(int x, int z) {
            return net.at(x, z);
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

    /** A direction sign as the site was asked for it. */
    record Sign(double x, double y, double z, float yaw, RoadSign.Kind kind, RoadSign.Words words) {}

    /** The route: straight along x through scenes (a) to (d), then bending through the forest. */
    static final List<double[]> CORNERS = List.of(new double[]{0.5, 0.5}, new double[]{1336.5, 0.5}, new double[]{1376.5, 12.5},
            new double[]{1408.5, 40.5}, new double[]{1428.5, 80.5}, new double[]{GATE_X + 0.5, GATE_Z - dev.stsv.economy.dock.Dock.APPROACH + 0.5},
            new double[]{GATE_X + 0.5, GATE_Z + 0.5});
    /** As Dock.APPROACH: the route's last stretch runs straight in to the gate. */
    static final int Dock_APPROACH = dev.stsv.economy.dock.Dock.APPROACH;

    /**
     * halves: the road's height (half blocks, {@link RoadProfile#halves}); levels: the full block under it (the surface
     * block itself at a whole level, the slab's support at a half one).
     */
    record Built(Land land, List<double[]> points, int[] halves, int[] levels, RoadProfile.Part[] parts, int[] ground, RoadSection section,
                 RoadGuide.Plan plan) {
        /** The block the surface is in at point i (the slab's own block at a half level). */
        int top(int i) {
            return RoadSection.surfaceY(halves[i]);
        }
    }

    /** (h) the older road in a cutting: across the strip at x 955, down to 6 under the land at 1 in 3. */
    static final double UNDER_X = 955.5;
    /**
     * (g) the road that branches off the strip at x 1160 (another dock's, built after it): to the north-east (the
     * strip's traffic sees it leave on its left), then north.
     */
    static final double BRANCH_X = 1160.5;
    static final List<double[]> BRANCH = List.of(new double[]{BRANCH_X, 0.5}, new double[]{BRANCH_X + 22, -21.5}, new double[]{BRANCH_X + 22, -79.5});
    /** Where the strip's road starts (the way back names it). */
    static final String HOME = "본사 출발 터미널";

    static final RoadSection.Style STYLE = new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000);

    /** A straight road built over the land among the roads there (as RoadModule: its ground, the crossings, the profile). */
    static RoadNetwork.Road side(Land land, String dock, List<double[]> pts, int[] fixed, String branchOf) {
        return side(land, dock, pts, fixed, new ArrayList<>());
    }

    /** As above, its RoadGuide view added to guide. */
    static RoadNetwork.Road side(Land land, String dock, List<double[]> pts, int[] fixed, List<RoadGuide.Road> guide) {
        int n = pts.size();
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            int[] s = land.sample((int) Math.floor(pts.get(i)[0]), (int) Math.floor(pts.get(i)[1]));
            ground[i] = s[0];
            surface[i] = s[2];
        }
        int[] cross = land.net.crossings(pts);
        RoadProfile.Plan plan = fixed != null ? new RoadProfile.Plan(fixed, new boolean[n]) : RoadProfile.planNetwork(ground, water, surface, cross);
        RoadProfile.Part[] parts = RoadProfile.partsHalves(plan.halves(), ground, water, RoadModule.BRIDGE_GAP, RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN);
        if (fixed != null) java.util.Arrays.fill(parts, RoadProfile.Part.ROAD);   // in a cutting, open to the sky
        RoadSection sec = new RoadSection(land, STYLE, pts, plan.halves(), parts, dock, 2).network(cross, plan.over());
        for (int i = 0; i < n; i++) assertEquals(null, sec.build(i));
        guide.add(new RoadGuide.Road(dock, pts, plan.halves(), parts, 2 + guide.size(), HOME));
        return new RoadNetwork.Road(dock, pts, plan.halves());
    }

    /** The signs RoadGuide plans, put up as RoadModule does: footings where open, the sign as the site is asked for it. */
    static void putUp(Land land, RoadGuide.Plan plan) {
        for (RoadGuide.Sign s : plan.signs()) {
            for (int[] c : s.posts()) if (land.fillable(c[0], s.level(), c[1]) && !land.liquid(c[0], s.level(), c[1])) land.set(c[0], s.level(), c[1], Material.STONE_BRICKS);
            land.guided.add(s);
        }
    }

    static Built build() {
        Land land = new Land();
        // (h) first: an older road in a cutting, 6 down at its middle
        List<double[]> under = RoadPlanner.densify(List.of(new double[]{UNDER_X, -40.5}, new double[]{UNDER_X, 40.5}), 1);
        int[] dip = new int[under.size()];
        for (int i = 0; i < dip.length; i++) dip[i] = 2 * Math.max(58, 64 - Math.min(i, dip.length - 1 - i) / 3);
        List<RoadGuide.Road> guide = new ArrayList<>();
        RoadNetwork.Road old = side(land, "남해", under, dip, guide);
        land.net = RoadNetwork.of(List.of(old));
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
        ground[n - 1] = PAD_Y;   // BUILD, as RoadModule.profile(): the last point is the gate, level with the dock's floor
        water[n - 1] = false;
        int[] cross = land.net.crossings(points);
        RoadProfile.Plan plan = RoadProfile.planNetwork(ground, water, surface, cross);
        int[] halves = plan.halves();
        int[] levels = RoadProfile.blocks(halves);
        RoadProfile.Part[] parts = RoadProfile.partsHalves(halves, ground, water, RoadModule.BRIDGE_GAP, RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN);
        for (int i = 0; i < n; i++) if (plan.over()[i]) parts[i] = RoadProfile.Part.BRIDGE;
        RoadSection section = new RoadSection(land, STYLE, points, halves, parts, "서해", 1).network(cross, plan.over());
        for (int i = 0; i < n; i++) assertEquals(null, section.build(i));
        // (g) after: another dock's road branches off it
        land.net = RoadNetwork.of(List.of(old, new RoadNetwork.Road("서해", points, halves)));
        guide.add(new RoadGuide.Road("서해", points, halves, parts, 1, HOME));
        side(land, "동해", RoadPlanner.densify(BRANCH, 1), null, guide);
        // once the roads are built: the signs where they meet, for the way back, confirming the way (as RoadModule.resign)
        RoadGuide.Plan guided = RoadGuide.plan(guide, STYLE.markerEvery(), STYLE.lightEvery());
        putUp(land, guided);
        return new Built(land, points, halves, levels, parts, ground, section, guided);
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
        nothingFloats(b);
        // nothing the road filled hangs in the air (the shoulders on a steep side slope reach down to the ground)
        for (Map.Entry<Long, Material> e : land.put.entrySet()) {
            Material m = e.getValue();
            if (m != Material.GRASS_BLOCK && m != Material.DIRT && m != Material.STONE && m != Material.COBBLESTONE) continue;
            long k = e.getKey();
            int x = (int) (k >>> 42) - (1 << 20), z = (int) ((k >>> 21) & ((1 << 21) - 1)) - (1 << 20), y = (int) (k & ((1 << 21) - 1)) - 1024;
            assertTrue(land.get(x, y - 1, z) != Material.AIR, "floating " + m + " at " + x + ", " + y + ", " + z);
        }
        List<double[]> pts = b.points();
        // (a) the direction signs: at the start, the 1 km one, before the gate; street lights
        assertEquals(List.of(RoadSign.Kind.START, RoadSign.Kind.KM, RoadSign.Kind.ARRIVAL), land.signs.stream().map(Sign::kind).toList());
        Sign start = land.signs.get(0), km = land.signs.get(1), end = land.signs.get(2);
        assertEquals(new RoadSign.Words("서해 하역장 방면", RoadSign.km((pts.size() - 1 - 24) / 1000.0), "국도 1호선", "1"), start.words());
        assertEquals(24.5, start.x(), 1e-9);
        assertEquals(new RoadSign.Words("서해 하역장", RoadSign.km((pts.size() - 1 - 1000) / 1000.0), "국도 1호선", "1"), km.words());
        assertEquals("0.5 km", RoadSign.km(0.474), "what is left, not rounded down to nothing");
        assertEquals(new RoadSign.Words("서해 하역장 도착", "", "국도 1호선", "1"), end.words());
        assertEquals(Map.of(RoadSection.SIGN_START, RoadSign.Kind.START, 1000, RoadSign.Kind.KM, pts.size() - 1 - RoadSection.SIGN_END, RoadSign.Kind.ARRIVAL),
                b.section().signs());
        // the 1 km sign: on the right of the traffic from the start (+z), 4 out, on the road's surface, its face to the west (the traffic)
        assertEquals(1000.5, km.x(), 1e-9);
        assertEquals(0.5 + RoadSign.LATERAL, km.z(), 1e-9);
        assertEquals(b.levels()[1000] + 1, km.y(), 1e-9);
        assertEquals(90f, km.yaw(), 1e-3f);
        // the light at the sign: on the other side, its post on the verge, its arm over the edge lane, 4 clear under the lantern
        assertEquals(Material.STONE_BRICK_WALL, land.get(1000, b.top(1000) + 3, -4), "the post");
        assertEquals(Material.STONE_BRICK_WALL, land.get(1000, b.top(1000) + 6, -3), "the arm");
        assertEquals(Material.LANTERN, land.get(1000, b.top(1000) + 5, -2), "the lantern hangs over the edge lane");
        // the panel hangs over the kerb and the shoulder, never the lanes, and nothing the road built is in it or in the posts' way
        assertTrue(RoadSign.LATERAL - RoadSign.PANEL_WIDTH / 2 >= 2.5);
        int at = b.levels()[1000];
        for (int z = 3; z <= 5; z++) {
            for (int y = at + 1; y <= at + 5; y++) assertEquals(Material.AIR, land.get(1000, y, z), "in the sign at 1000, " + y + ", " + z);
            assertTrue(land.get(1000, at, z) != Material.AIR, "a post on nothing at z " + z);
        }
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
        for (int i = 3; i < b.halves().length; i++) assertTrue(Math.abs(b.halves()[i] - b.halves()[i - 3]) <= 2, "never over a block in three");
        // (c) the river: a bridge with railings over the water
        int mid = index(pts, (RIVER0 + RIVER1) / 2.0);
        assertEquals(RoadProfile.Part.BRIDGE, b.parts()[mid]);
        assertEquals(Material.STONE_BRICK_WALL, land.get(1196, b.levels()[mid] + 1, 3));
        assertEquals(Material.STONE_BRICK_WALL, land.get(1196, b.levels()[mid] + 1, -3));
        assertEquals(Material.STONE_BRICK_WALL, land.get(1200, b.top(mid) + 4, 3), "the bridge's light stands on its railing");
        assertEquals(Material.LANTERN, land.get(1200, b.top(mid) + 5, 2), "its lantern over the edge lane");
        // (d) the steep hill: a tunnel, lit
        int in = index(pts, 1272);
        assertEquals(RoadProfile.Part.TUNNEL, b.parts()[in]);
        boolean lit = false;
        for (int x = 1240; x < 1310; x++) lit |= land.get(x, b.top(index(pts, x)) + 5, 0) == Material.SEA_LANTERN;
        assertTrue(lit, "lights in the tunnel's ceiling");
        // (f) the gate: painted words before it, the road level with the dock's floor at its edge
        assertTrue(land.paints.stream().anyMatch(q -> RoadSection.GATE_WORDS.equals(q[4]) && Math.abs((double) q[2] - (GATE_Z - RoadSection.WORDS_AT + 0.5)) < 1));
        assertEquals(PAD_Y, b.top(pts.size() - 1));
        // (g) the branch: the main road's lanes at the junction untouched by it; the signs of the fork (RoadGuide)
        fork(b);
        int jl = b.top(index(pts, BRANCH_X));
        for (int z = -2; z <= 2; z++) assertTrue(b.section().style().paved().contains(land.get((int) BRANCH_X, jl, z)), "the main road at the junction");
        // (h) over the old road in its cutting: a bridge, 4 and more clear over the old lanes, no pier on them
        int ov = index(pts, UNDER_X);
        assertEquals(RoadProfile.Part.BRIDGE, b.parts()[ov]);
        for (int y = 59; y <= 62; y++) assertEquals(Material.AIR, land.get((int) UNDER_X, y, 0), "under the overpass at " + y);
        assertTrue(b.top(ov) >= 58 + RoadNetwork.CLEARANCE);
        // (e) the forest: no tree left over the road
        for (int i = index(pts, 1320); i < pts.size(); i++) {
            double[] p = pts.get(i);
            for (int y = b.levels()[i] + 1; y <= b.levels()[i] + 30; y++)
                assertTrue(!Land.naturalBlock(land.get((int) Math.floor(p[0]), y, (int) Math.floor(p[1]))), "a tree over the road at " + i);
        }
    }

    /** /관리자 국도 난간고치기 on a road built before: a wall on a slab, on a verge's path, over air gets its full block under it. */
    @Test
    void floatingWallsGetTheirPlinths() {
        Land land = new Land();
        int g = Land.ground(0, 0);   // flat land there
        land.set(0, g + 1, 0, Material.STONE_BRICK_SLAB);          // a half-step deck's edge
        land.set(0, g + 2, 0, Material.STONE_BRICK_WALL);
        land.set(2, g, 0, Material.DIRT_PATH);                     // a light's post on the verge
        land.set(2, g + 1, 0, Material.STONE_BRICK_WALL);
        land.set(4, g + 4, 0, Material.ANDESITE_WALL);             // over air
        land.set(6, g + 1, 0, Material.SMOOTH_STONE_SLAB);
        land.set(6, g + 2, 0, Material.SMOOTH_STONE);              // not a wall: left alone
        int n = 0;
        for (int x = 0; x <= 6; x++) n += RoadSection.plinths(land, x, 0, g, g + 5);
        assertEquals(Material.STONE_BRICKS, land.get(0, g + 1, 0));
        assertEquals(Material.STONE_BRICKS, land.get(2, g, 0));
        for (int y = g + 1; y <= g + 3; y++) assertEquals(Material.STONE_BRICKS, land.get(4, y, 0), "down to the ground at " + y);
        assertEquals(Material.SMOOTH_STONE_SLAB, land.get(6, g + 1, 0));
        assertEquals(5, n);
        assertEquals(Material.SMOOTH_STONE, RoadSection.plinth(Material.SMOOTH_STONE_SLAB));
        int again = 0;
        for (int x = 0; x <= 6; x++) again += RoadSection.plinths(land, x, 0, g, g + 5);
        assertEquals(0, again, "mended once");
    }

    /**
     * (g) the fork as the guide signs it: on the strip's road 120 and 40 before it, on its right (south), facing the traffic
     * from the start (west), "↖ 동해 하역장" over "↑ 서해 하역장" with the distances from the sign; the gore sign in the V
     * between the two roads; the overpass of (h) is not a junction; every sign's posts clear of every road's lanes.
     */
    static void fork(Built b) {
        RoadGuide.Plan plan = b.plan();
        assertEquals(1, plan.junctions().size(), "the fork only (the overpass is no junction): " + plan.junctions());
        assertTrue(plan.junctions().getFirst().fork());
        int j = index(b.points(), BRANCH_X);
        List<RoadGuide.Sign> advance = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.ADVANCE && s.host().equals("서해") && s.at() < j).toList();
        assertEquals(2, advance.size(), "two advance signs before the fork: " + advance);
        int branchLen = RoadPlanner.densify(BRANCH, 1).size() - 1, trunkLen = b.points().size() - 1;
        for (RoadGuide.Sign s : advance) {
            int d = j - s.at();
            assertTrue(d >= 40 && d <= 160, "before the fork: " + d);
            assertTrue(s.z() > 0.5 + 3, "on the right of the eastbound traffic (south): " + s.z());
            assertEquals(90f, s.yaw(), 1e-3f, "facing the traffic from the start (west)");
            assertEquals(List.of(new RoadSign.Row(RoadSign.Arrow.UP_LEFT, "동해 하역장", RoadSign.km((branchLen + d) / 1000.0)),
                    new RoadSign.Row(RoadSign.Arrow.UP, "서해 하역장", RoadSign.km((trunkLen - j + d) / 1000.0))), s.rows());
            assertEquals("동해", s.also(), "cleared with either dock's signs");
        }
        RoadGuide.Sign gore = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.GORE).findFirst().orElseThrow();
        assertTrue(gore.x() > BRANCH_X && gore.z() < -4, "in the V, north of the strip: " + gore);
        assertEquals(List.of(RoadSign.Arrow.UP_LEFT, RoadSign.Arrow.UP_RIGHT), gore.rows().stream().map(RoadSign.Row::arrow).toList());
        assertEquals(List.of("동해 하역장", "서해 하역장"), gore.rows().stream().map(RoadSign.Row::name).toList());
        // the strip's own signs as the guide puts them again: where RoadSection put them
        for (Sign s : b.land().signs) {
            assertTrue(plan.signs().stream().anyMatch(g -> g.kind() == s.kind() && Math.abs(g.x() - s.x()) < 1e-9 && Math.abs(g.z() - s.z()) < 1e-9
                    && g.y() == s.y() && g.yaw() == s.yaw() && g.words().equals(s.words())), "put up again where it stood: " + s);
        }
        // the way back on the strip: facing the traffic going west, on its other side, naming where it started
        assertTrue(plan.signs().stream().anyMatch(s -> s.type() == RoadGuide.Type.RETURN && s.host().equals("서해") && s.z() < 0
                && s.yaw() == -90f && s.words().big().equals(HOME)), "a sign for the way back");
        for (RoadGuide.Sign s : plan.signs()) {
            for (int[] c : s.posts()) {
                notInLanes(b, c[0], c[1], s);
                for (int y = s.level() + 1; y <= s.level() + 6; y++)
                    assertTrue(!RoadSection.standing(b.land().get(c[0], y, c[1])), "a light's post where a sign's post stands: " + s);
            }
        }
    }

    /** A column of a sign's post: never in any road's lanes (2.5 from its middle). */
    static void notInLanes(Built b, int x, int z, Object what) {
        for (List<double[]> road : List.of(b.points(), RoadPlanner.densify(BRANCH, 1),
                RoadPlanner.densify(List.of(new double[]{UNDER_X, -40.5}, new double[]{UNDER_X, 40.5}), 1))) {
            for (double[] p : road) assertTrue(Math.hypot(x + 0.5 - p[0], z + 0.5 - p[1]) > 2.5, "a post in the lanes at " + x + ", " + z + ": " + what);
        }
    }

    /**
     * Nothing that stands like a post (a wall: railings, guardrails, lights' posts) floats: none on a slab (half a block
     * under it), none over air but a light's arm (5 and more over the road).
     */
    static void nothingFloats(Built b) {
        for (Map.Entry<Long, Material> e : b.land().put.entrySet()) {
            if (!RoadSection.standing(e.getValue())) continue;
            long k = e.getKey();
            int x = (int) (k >>> 42) - (1 << 20), z = (int) ((k >>> 21) & ((1 << 21) - 1)) - (1 << 20), y = (int) (k & ((1 << 21) - 1)) - 1024;
            Material below = b.land().get(x, y - 1, z);
            assertTrue(!below.name().endsWith("_SLAB"), e.getValue() + " on a slab at " + x + ", " + y + ", " + z);
            if (below == Material.AIR) {   // only a light's arm: 4 and more clear blocks under it (over the lane)
                int clear = 0;
                while (clear < 4 && b.land().get(x, y - 1 - clear, z) == Material.AIR) clear++;
                assertEquals(4, clear, e.getValue() + " hangs just over something at " + x + ", " + y + ", " + z);
            }
        }
    }

    /**
     * Across the lanes, everywhere (straight, diagonal, round the bends, on bridges and in tunnels): the surface is there
     * (no gaps), nothing stands on it for four blocks (no light, arm or railing in the lanes), and side by side it never
     * steps more than half a block (a slab: a vehicle glides over it).
     */
    static void lanesAreWholeAndClear(Built b) {
        Land land = b.land();
        List<double[]> pts = b.points();
        java.util.Set<Material> paved = b.section().style().paved();
        Map<Long, Integer> top = new HashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i), a = pts.get(Math.max(0, i - 2)), c = pts.get(Math.min(pts.size() - 1, i + 2));
            double hx = c[0] - a[0], hz = c[1] - a[1], len = Math.hypot(hx, hz);
            double rx = -hz / len, rz = hx / len;
            for (double o = -1.7; o <= 1.7 + 1e-9; o += 0.1) {   // a column within 2.41 of the middle: always a lane
                int x = (int) Math.floor(p[0] + rx * o), z = (int) Math.floor(p[1] + rz * o);
                int l = b.levels()[i], y = l + 2;
                while (y >= l - 1 && land.get(x, y, z) == Material.AIR) y--;
                Material m = land.get(x, y, z);
                assertTrue(paved.contains(m), "the lane at point " + i + " (" + x + ", " + z + "): " + m + " at " + y);
                for (int k = 1; k <= 4; k++) assertEquals(Material.AIR, land.get(x, y + k, z), "over the lane at point " + i + " (" + x + ", " + z + ") +" + k);
                top.put(RoadJob.key(x, z), 2 * y + (m.name().endsWith("_SLAB") ? 1 : 2));
            }
        }
        for (Map.Entry<Long, Integer> e : top.entrySet()) {
            int x = RoadJob.cx(e.getKey()), z = RoadJob.cz(e.getKey());
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                Integer n = top.get(RoadJob.key(x + d[0], z + d[1]));
                if (n != null) assertTrue(Math.abs(n - e.getValue()) <= 1, "a step of " + Math.abs(n - e.getValue()) / 2.0 + " at " + x + ", " + z);
            }
        }
    }

    /**
     * A crossing's advance sign (three ways) for the preview's row of signs, as RoadGuide words it (RoadGuideTest plans
     * one for real); it stands far off the strip (only the row of signs shows it).
     */
    static RoadGuide.Sign crossingSample() {
        List<RoadSign.Row> rows = List.of(new RoadSign.Row(RoadSign.Arrow.LEFT, "본사 출발 터미널", RoadSign.km(3.4)),
                new RoadSign.Row(RoadSign.Arrow.UP, "서해 하역장", RoadSign.km(5.2)), new RoadSign.Row(RoadSign.Arrow.RIGHT, "남해 하역장", RoadSign.km(2.7)));
        return new RoadGuide.Sign(RoadGuide.Type.ADVANCE, "서해", "남해", -500, 65, -500, 90f, null, null, rows, List.of(), 64, 0, 0, 1);
    }

    /** The land round the road for the preview: columns of runs [y, length, block] from Y0 up. */
    static void export(Built b) throws Exception {
        int x0 = 860, x1 = 1480, z0 = -84, z1 = 196, y0 = 40;
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
        // the signs as RoadSigns puts them: the model at the point, each text display where RoadSign puts it
        s.append("],\"signs\":[");
        boolean first = true;
        // (the signs as they stand once the roads are built: RoadGuide's, the roads' own among them)
        List<RoadGuide.Sign> shown = new ArrayList<>(b.land().guided);
        shown.add(crossingSample());
        for (RoadGuide.Sign g : shown) {
            s.append(first ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"yaw\":%.3f,\"kind\":\"%s\",\"model\":\"%s\",\"rows\":%d,\"texts\":[",
                    g.x(), g.y(), g.z(), g.yaw(), g.type(), g.guide() ? "guide" : "sign", g.guide() ? g.rows().size() : 0));
            boolean ft = true;
            for (RoadSign.Text t : g.guide() ? RoadSign.guideTexts(g.rows()) : RoadSign.texts(g.words())) {
                double[] o = RoadSign.offset(g.yaw(), t.right(), t.out());
                s.append(ft ? "" : ",").append(String.format(Locale.ROOT, "{\"text\":\"%s\",\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"scale\":%.4f}",
                        t.text(), g.x() + o[0], g.y() + t.up(), g.z() + o[1], t.scale()));
                ft = false;
            }
            s.append("],\"arrows\":[");
            ft = true;
            for (RoadSign.ArrowAt a : g.guide() ? RoadSign.guideArrows(g.rows()) : List.<RoadSign.ArrowAt>of()) {
                double[] o = RoadSign.offset(g.yaw(), a.right(), a.out());
                s.append(ft ? "" : ",").append(String.format(Locale.ROOT, "{\"arrow\":\"%s\",\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"size\":%.4f}",
                        a.arrow().model, g.x() + o[0], g.y() + a.up(), g.z() + o[1], a.size()));
                ft = false;
            }
            s.append("]}");
            first = false;
        }
        s.append("],\"paints\":[");
        for (int i = 0; i < b.land().paints.size(); i++) {
            Object[] p = b.land().paints.get(i);
            s.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"yaw\":%.3f,\"text\":\"%s\",\"scale\":%.2f}",
                    p[0], p[1], p[2], p[3], p[4], RoadSigns.PAINT));
        }
        s.append("],\"points\":[");
        for (int i = 0; i < b.points().size(); i++) {
            double[] p = b.points().get(i);
            s.append(i > 0 ? "," : "").append('[').append(String.format(Locale.ROOT, "%.2f,%.2f", p[0], p[1])).append(',').append(b.top(i))
                    .append(",\"").append(b.parts()[i].name().charAt(0)).append("\"]");
        }
        s.append("],\"cols\":[").append(cols).append("]}");
        Path out = Path.of("build/roads/strip.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, s.toString());
    }
}
