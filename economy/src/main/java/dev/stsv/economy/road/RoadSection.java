package dev.stsv.economy.road;

import org.bukkit.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 국도: which block goes where, one cross-section of the road at a time (README "국도"). Pure: it works on a {@link Site}
 * ({@link RoadModule} gives it the world, RoadPreviewTest a grid of its own), so the road can be built and looked at
 * without a server.
 *
 * <p>The road's height comes in half blocks ({@link RoadProfile#halves}): a point half a block up is built in slabs (the
 * surface's, the kerb's, the edge line's: {@link #slab}), so a vehicle glides up and down instead of bumping a block.
 *
 * <p>A cross-section is every column whose nearest point of the road is this one (so on diagonals and round bends they
 * neither leave gaps nor build over each other), out to 5.5 either side of the middle (more at the dock's gate:
 * {@link #widen}). Across it: the dashed yellow centre line, the lanes (a few patched or cracked blocks:
 * {@link #patch}), a white edge line each side, a kerb, then the shoulder: a clean verge (a path strip with a drain
 * grate every {@link #DRAIN_EVERY}) and grass beyond it, a reflector post every {@link #REFLECT_EVERY}, a guardrail where
 * the road stands {@link #RAIL_DROP} or more over the ground. What it is there depends on the point's
 * {@link RoadProfile.Part}:
 * <ul>
 *   <li>ROAD: the surface at the road's level, cut 5 above it, filled below (stone, then cobblestone); the shoulders cut
 *       down and filled up to the road (grass on dirt); trees cleared.</li>
 *   <li>BRIDGE: the deck (stone bricks at the edges), a railing each side, a pier down to the ground every 10, an
 *       expansion joint across it every {@link #JOINT_EVERY}.</li>
 *   <li>TUNNEL: the surface, air up to a stone brick ceiling (a light flush in it every 8) and lined walls (a white band
 *       with a yellow stripe, lights in it between the ceiling's); at each end a portal (the walls out to the shoulders
 *       and a block higher, a lintel over the opening). The ceiling is {@link RoadClearance#ceilings}: 4 clear over the
 *       flat, higher on a grade (it clears the highest surface a long vehicle can stand on under it), a step in it closed
 *       over with stone bricks.</li>
 * </ul>
 * Street lights every {@code lightEvery} (sides taking turns), a post on the shoulder with an arm over the edge lane and
 * a lantern hanging from it, at the ceiling's height (4 clear blocks over a flat road); direction signs ({@link RoadSign}: the site puts the model and
 * its words) a little way from the start, every {@code markerEvery} and just before the gate, all where the road is plain
 * road ({@link #signs}). Approaching the dock's gate (the end) the road widens ({@link #widen}), a crosswalk is painted
 * across it and "하역장 입구" on it. A column of protected land ({@link Site#protectedLand}) is left alone; a surface column there stops the road
 * (nothing of that cross-section is built).
 */
public final class RoadSection {

    /** The blocks the road is built in. */
    public interface Site {
        /** False below the world's floor or above its roof. */
        boolean inside(int y);

        boolean air(int x, int y, int z);

        boolean liquid(int x, int y, int z);

        /** Trees and the like: logs, leaves, vines, mushroom blocks, snow. */
        boolean natural(int x, int y, int z);

        /** What the road may fill: air, liquid, plants, anything not solid, trees. False outside the world. */
        boolean fillable(int x, int y, int z);

        /** Puts a block (nothing outside the world, nothing when it is that already). */
        void set(int x, int y, int z, Material m);

        /** Puts a block with its state ("lantern[hanging=true]"); by default only its kind. */
        default void set(int x, int y, int z, String data) {
            int b = data.indexOf('[');
            set(x, y, z, Material.matchMaterial(b < 0 ? data : data.substring(0, b)));
        }

        /**
         * A column the road never touches ({@link RoadGuard}): a player's land, the server's land (but the chunks open to
         * this road: its dock's, its terminal's), a roads.avoid box.
         */
        boolean protectedLand(int x, int z);

        /**
         * A direction sign ({@link RoadSign}): the point between its posts (x, z; y the road's surface), its face towards
         * yaw (Minecraft: 0 south, 90 west), the dock, the km left to it, the route's number.
         */
        void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind);

        /** Another road already built at a column ({@link RoadNetwork}), or null: what this road keeps clear of. */
        default RoadNetwork.Spot road(int x, int z) {
            return null;
        }

        /** What is at a block, or null when the site cannot tell (then nothing is spared for being a light). */
        default Material get(int x, int y, int z) {
            return null;
        }

        /** Words painted on the road (a text display lying on it, read by the traffic coming from the start: yaw as a sign's). */
        default void roadText(double x, double y, double z, float yaw, String text) {}
    }

    /** The blocks and spacings of roads.* in the config. */
    public record Style(Material surface, Material line, Material kerb, Material edge, int lightEvery, int markerEvery) {

        public Style(Material surface, Material line, Material kerb, int lightEvery, int markerEvery) {
            this(surface, line, kerb, Material.SMOOTH_QUARTZ, lightEvery, markerEvery);
        }

        /** Everything a vehicle drives on (the road's speed bonus). */
        public java.util.Set<Material> paved() {
            java.util.Set<Material> out = new java.util.HashSet<>();
            for (Material m : new Material[]{surface, line, edge}) {
                out.add(m);
                Material s = slab(m);
                if (s != null) out.add(s);
            }
            Material[] p = patches(surface);
            if (p != null) out.addAll(List.of(p));
            out.add(POLISHED_JOINT);
            return out;
        }

        /**
         * Every block a road's surface is made of: what a vehicle drives on ({@link #paved}), the kerbs and the slabs of
         * the surface and the kerb (a half step). What {@link RoadFinder} knows a road by.
         */
        public java.util.Set<Material> roadBlocks() {
            java.util.Set<Material> out = paved();
            out.add(kerb);
            for (Material m : new Material[]{surface, kerb}) {
                Material s = slab(m);
                if (s != null) out.add(s);
            }
            return out;
        }
    }

    /** Half the width it touches: the surface ±2.5, the kerbs to ±3.5, the shoulders to ±5.5 (plus {@link #WIDEN} at the gate). */
    public static final int HALF = 5;
    /** How much wider each side the road gets at the dock's gate, and over how many points before it (from {@link #WIDE_AT} on: all of it). */
    public static final int WIDEN = 2, WIDEN_FROM = 16, WIDE_AT = 4;
    /** Points before the end: the crosswalk (from, to), the words. */
    static final int CROSS_FROM = 6, CROSS_TO = 8, WORDS_AT = 11;
    static final int DRAIN_EVERY = 12, REFLECT_EVERY = 20, JOINT_EVERY = 16, RAIL_DROP = 3;
    static final Material POLISHED_JOINT = Material.POLISHED_BASALT, GUARDRAIL = Material.ANDESITE_WALL, REFLECTOR = Material.END_ROD,
            VERGE = Material.DIRT_PATH, DRAIN = Material.IRON_TRAPDOOR;
    /** The last points (from the gate) whose shoulders are paved like the dock's floor, so the road joins its apron. */
    static final int APRON = 2;
    static final Material APRON_BLOCK = Material.POLISHED_ANDESITE;
    /** The words painted before the gate. */
    public static final String GATE_WORDS = "하역장 입구";

    private Site site;
    private final Site world;
    /** the old road's surface where this one's middle runs over it (half blocks) or MIN_VALUE; where it goes over it */
    private int[] cross;
    private boolean[] over;
    /** how many points to the nearest level crossing (a junction), MAX_VALUE: none */
    private int[] toJunction;
    /** set while a junction paves an old road's kerb or shoulder (the only time it is touched) */
    private boolean paving;
    private final Style style;
    private final List<double[]> points;
    private final int[] halves;
    /** the lowest block anything over the road may be in, at each point ({@link RoadClearance#ceilings}) */
    private int[] ceilings;
    private final RoadProfile.Part[] parts;
    private final String dock;
    private final int route;
    private final Map<Integer, RoadSign.Kind> signs;

    /** halves: the road's height at each point in half blocks ({@link RoadProfile#halves}). */
    public RoadSection(Site site, Style style, List<double[]> points, int[] halves, RoadProfile.Part[] parts, String dock, int route) {
        this.world = site;
        this.site = guard(site);
        this.style = style;
        this.points = points;
        this.halves = halves;
        this.ceilings = RoadClearance.ceilings(halves);
        this.parts = parts;
        this.dock = dock;
        this.route = route;
        this.signs = signs(parts, style.markerEvery());
    }

    public Style style() {
        return style;
    }

    /** The ceilings it builds to (tests: {@link RoadClearance#legacy} builds a tunnel as roads were built before). */
    RoadSection ceilings(int[] c) {
        this.ceilings = c;
        return this;
    }

    /** The ceiling's block y at point i. */
    public int ceiling(int i) {
        return ceilings[i];
    }

    /**
     * The other roads this one meets ({@link RoadNetwork#crossings}, {@link RoadProfile#planNetwork}): cross[i] the old
     * road's surface where this one's middle is on it, over[i] where it goes over it. Level crossings become junctions
     * (no centre line or kerbs across the old lanes, wider corners, a crosswalk on this road either side).
     */
    public RoadSection network(int[] cross, boolean[] over) {
        this.cross = cross;
        this.over = over;
        int n = points.size();
        toJunction = new int[n];
        java.util.Arrays.fill(toJunction, Integer.MAX_VALUE);
        for (int i = 0; i < n; i++) {
            if (cross[i] == Integer.MIN_VALUE || over[i]) continue;
            for (int k = Math.max(0, i - 12); k <= Math.min(n - 1, i + 12); k++) toJunction[k] = Math.min(toJunction[k], Math.abs(k - i));
        }
        reach = null;
        return this;
    }

    // ------------------------------------------------------------------------------------------
    // Room beside the road (RoadClearance#reach): bridges' railings, tunnels' walls, guardrails, banks
    // ------------------------------------------------------------------------------------------

    /** How far out (blocks) a bend widens the cross-section at most: the road's chunks round it are loaded ({@link RoadModule#REACH}). */
    public static final double MAX_WIDE = 3;
    /** Points within this many of a level crossing count as a right-angled bend (a bus turns there from one road onto the other). */
    static final int JUNCTION_BEND = 12;
    /** Degrees a junction counts as. */
    static final double JUNCTION_TURN = 90;

    /** how far the vehicles reach from the centre line at each point (RoadClearance#reach), worked out on first use */
    private double[] reach, slants;
    /** roads as built before: no room made for the vehicles' reach (bridges 2.5 clear, tunnels 3.5, guardrails at 4.5) */
    private boolean narrow;

    /** Builds as roads were built before this room was made (tests: what /관리자 국도 커브정리 mends). */
    RoadSection narrow() {
        this.narrow = true;
        this.reach = null;
        return this;
    }

    /** How many degrees the road turns at point i (its way over {@link #BEND_REACH} points before against the same after). */
    double turn(int i) {
        double[] a = points.get(Math.max(0, i - BEND_REACH)), p = points.get(i), b = points.get(Math.min(points.size() - 1, i + BEND_REACH));
        double ax = p[0] - a[0], az = p[1] - a[1], bx = b[0] - p[0], bz = b[1] - p[1];
        if (Math.hypot(ax, az) < 1e-6 || Math.hypot(bx, bz) < 1e-6) return 0;
        return Math.toDegrees(Math.atan2(Math.abs(ax * bz - az * bx), ax * bx + az * bz));
    }

    /**
     * How far from the centre line the vehicles reach at point i ({@link RoadClearance#reach}): the slant the most of the
     * road's within {@link RoadClearance#REACH} points (a bus is still slanted a length after the road straightens), the
     * bend the sharpest within {@link #BEND_REACH} points (a bus runs wide out of a bend well after its corner); a level
     * crossing near counts as a right angle. Nothing a vehicle can hit stands nearer.
     */
    public double reach(int i) {
        if (reach == null) {
            int n = points.size();
            double[] bend = new double[n], diag = new double[n];
            for (int k = 0; k < n; k++) {
                bend[k] = turn(k);
                double[] h = heading(k);
                diag[k] = RoadClearance.diagonal(h[0], h[1]);
            }
            double[] out = new double[n];
            slants = new double[n];
            for (int k = 0; k < n; k++) {
                double b = 0, d = 1;
                for (int j = Math.max(0, k - BEND_REACH); j <= Math.min(n - 1, k + BEND_REACH); j++) b = Math.max(b, bend[j]);
                if (junction(k) <= JUNCTION_BEND) b = Math.max(b, JUNCTION_TURN);
                for (int j = Math.max(0, k - RoadClearance.REACH); j <= Math.min(n - 1, k + RoadClearance.REACH); j++) d = Math.max(d, diag[j]);
                if (RoadClearance.bend(b) > 0) d = Math.sqrt(2);   // round a bend it is slanted every way
                out[k] = RoadClearance.reach(d, b);
                slants[k] = d;
            }
            reach = out;
        }
        return reach[i];
    }

    /** How much a column's box (a post's, a block's) reaches out of its middle across the road at point i (half × the slant). */
    private double slant(int i, double half) {
        reach(i);
        return half * slants[i];
    }

    /** How much further out a bridge's railing stands at point i than roads built it before (2.5 out): its post clear of the reach. */
    public double bridgeWide(int i) {
        return narrow ? 0 : Math.max(0, reach(i) + slant(i, POST_HALF) - 2.5);
    }

    /** How much further out a tunnel's lined walls stand at point i than before (3.5 out): their blocks clear of the reach. */
    public double tunnelWide(int i) {
        return narrow ? 0 : Math.max(0, reach(i) + slant(i, 0.5) - 3.5);
    }

    /** How much further out the shoulder's guardrails (and reflectors) stand at point i than before (4.5 out), the banks with them. */
    public double shoulderWide(int i) {
        return narrow ? 0 : Math.max(0, reach(i) + slant(i, POST_HALF) - 4.5);
    }

    /** How much further out than {@link #HALF} the cross-section at point i reaches (the widest of the above, at most {@link #MAX_WIDE}). */
    public double wide(int i) {
        if (narrow) return 0;
        double w = switch (parts[i]) {
            case ROAD -> shoulderWide(i);
            case BRIDGE -> bridgeWide(i) - 2;
            case TUNNEL -> tunnelWide(i) - 1;
        };
        return Math.clamp(w, 0, MAX_WIDE);
    }

    /** Whether a post in column (x, z) (a guardrail's, a railing's, a reflector, a light) stands clear of the vehicles' reach at point i. */
    boolean postClear(int i, int x, int z) {
        return narrow || clearance(i, x, z) >= reach(i) - 1e-9;
    }

    private RoadNetwork.Spot old(int x, int z) {
        return world.road(x, z);
    }

    /** Whether a level crossing is this close (points). */
    private int junction(int i) {
        return toJunction == null ? Integer.MAX_VALUE : toJunction[i];
    }

    /**
     * The site as this road may touch it: never an old road's lanes or kerbs from under its fill to over its lights (but
     * for a junction's paving, level with them), never an old road's light on its shoulder.
     */
    private Site guard(Site s) {
        return new Site() {
            boolean allowed(int x, int y, int z) {
                RoadNetwork.Spot o = s.road(x, z);
                if (o == null) return true;
                int oy = o.y();
                if (o.paved() && y >= oy - 8 && y <= oy + 6) return paving && !o.lane() || (paving && y > oy);
                if (!o.paved() && y > oy && y <= oy + 9) {   // (on a grade a light stands higher: RoadClearance)
                    Material m = s.get(x, y, z);
                    return m != Material.STONE_BRICK_WALL && m != Material.LANTERN;   // its street lights stay
                }
                return true;
            }

            public boolean inside(int y) {
                return s.inside(y);
            }

            public boolean air(int x, int y, int z) {
                return s.air(x, y, z);
            }

            public boolean liquid(int x, int y, int z) {
                return s.liquid(x, y, z);
            }

            public boolean natural(int x, int y, int z) {
                return s.natural(x, y, z);
            }

            public boolean fillable(int x, int y, int z) {
                return s.fillable(x, y, z) && allowed(x, y, z);
            }

            public void set(int x, int y, int z, Material m) {
                if (allowed(x, y, z)) s.set(x, y, z, m);
            }

            public void set(int x, int y, int z, String data) {
                if (allowed(x, y, z)) s.set(x, y, z, data);
            }

            public boolean protectedLand(int x, int z) {
                return s.protectedLand(x, z);
            }

            public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {
                s.roadSign(x, y, z, yaw, dock, km, route, kind);
            }

            public void roadText(double x, double y, double z, float yaw, String text) {
                s.roadText(x, y, z, yaw, text);
            }

            public RoadNetwork.Spot road(int x, int z) {
                return s.road(x, z);
            }

            public Material get(int x, int y, int z) {
                return s.get(x, y, z);
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------------------------------

    /**
     * The slab that goes with a block (half a step of it), or null: X_SLAB, BRICKS → BRICK_SLAB, PLANKS → SLAB; concrete
     * has none, so the nearest colour (grey: deepslate tiles, white: smooth quartz, black: polished blackstone, light grey:
     * polished andesite); yellow and the rest: null (a line then takes the surface's slab).
     */
    public static Material slab(Material m) {
        if (m == null) return null;
        String n = m.name();
        if (n.endsWith("_SLAB")) return m;
        switch (m) {
            case GRAY_CONCRETE, DEEPSLATE_TILES, CRACKED_DEEPSLATE_TILES -> {
                return Material.DEEPSLATE_TILE_SLAB;
            }
            case WHITE_CONCRETE, QUARTZ_BLOCK -> {
                return Material.SMOOTH_QUARTZ_SLAB;
            }
            case BLACK_CONCRETE -> {
                return Material.POLISHED_BLACKSTONE_SLAB;
            }
            case LIGHT_GRAY_CONCRETE -> {
                return Material.POLISHED_ANDESITE_SLAB;
            }
            default -> { }
        }
        for (String s : new String[]{n + "_SLAB", n.endsWith("S") ? n.substring(0, n.length() - 1) + "_SLAB" : null,
                n.endsWith("_PLANKS") ? n.substring(0, n.length() - 7) + "_SLAB" : null, n.endsWith("_BLOCK") ? n.substring(0, n.length() - 6) + "_SLAB" : null}) {
            if (s == null) continue;
            Material x = Material.getMaterial(s);
            if (x != null) return x;
        }
        return null;
    }

    /** Blocks that patch a surface (a mended block, a cracked one), or null (none for it): only the default grey concrete. */
    static Material[] patches(Material surface) {
        return surface == Material.GRAY_CONCRETE ? new Material[]{Material.DEEPSLATE_TILES, Material.CRACKED_DEEPSLATE_TILES} : null;
    }

    /** A column's hash (deterministic: the same road always gets the same patches). */
    static int hash(int a, int b) {
        int h = a * 73856093 ^ b * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ (h >>> 15);
    }

    /** The surface block of a lane column: a few in 2 x 2 patches (one in 25 cells, a quarter of those cracked). */
    Material patch(int x, int z) {
        Material[] p = patches(style.surface());
        if (p == null) return style.surface();
        int h = hash(Math.floorDiv(x, 2), Math.floorDiv(z, 2));
        if (Math.floorMod(h, 25) != 0) return style.surface();
        return Math.floorMod(hash(x, z) >> 6, 4) == 0 ? p[1] : p[0];
    }

    /** The block of the surface's y at a half level: the slab's own block or the full block. */
    public static int surfaceY(int half) {
        return Math.floorDiv(half + 1, 2);
    }

    /** Where the surface's top is (blocks) at a half level. */
    public static double top(int half) {
        return half / 2.0 + 1;
    }

    /** At a half step: the slab of m (a line without one: the surface's), else m. */
    private Material at(Material m, boolean half) {
        if (!half) return m;
        Material s = slab(m);
        if (s == null) s = slab(style.surface());
        return s == null ? Material.SMOOTH_STONE_SLAB : s;
    }

    // ------------------------------------------------------------------------------------------
    // Signs
    // ------------------------------------------------------------------------------------------

    /** The start sign this far along (blocks), the arrival sign this far before the end; no km sign closer than KM_GAP to either. */
    static final int SIGN_START = 24, SIGN_END = 20, KM_GAP = 150;

    /**
     * Where the signs go: point -> kind. Each on plain road (3 points of it either way), moved on (or back, at the end) to
     * the next such point when it would fall on a bridge or in a tunnel. A road shorter than the gaps has none.
     */
    static Map<Integer, RoadSign.Kind> signs(RoadProfile.Part[] parts, int every) {
        Map<Integer, RoadSign.Kind> out = new TreeMap<>();
        int n = parts.length;
        if (n < SIGN_START + SIGN_END + KM_GAP) return out;
        int start = plain(parts, SIGN_START, 1, 60), end = plain(parts, n - 1 - SIGN_END, -1, 60);
        if (start >= 0) out.put(start, RoadSign.Kind.START);
        for (int k = every; k < n; k += every) {
            int i = plain(parts, k, 1, 60);
            if (i < 0 || n - 1 - i < KM_GAP || (start >= 0 && i - start < KM_GAP)) continue;
            out.put(i, RoadSign.Kind.KM);
        }
        if (end >= 0 && (start < 0 || end - start >= KM_GAP)) out.put(end, RoadSign.Kind.ARRIVAL);
        return out;
    }

    /** The first point from `from` (stepping `dir`, at most `look`) with plain road 3 points either way, or -1. */
    static int plain(RoadProfile.Part[] parts, int from, int dir, int look) {
        for (int k = 0; k < look; k++) {
            int i = from + k * dir;
            if (i - 3 < 0 || i + 3 >= parts.length) return -1;
            boolean ok = true;
            for (int j = i - 3; j <= i + 3 && ok; j++) ok = parts[j] == RoadProfile.Part.ROAD;
            if (ok) return i;
        }
        return -1;
    }

    /** The signs of this road (point -> kind). */
    public Map<Integer, RoadSign.Kind> signs() {
        return signs;
    }

    /** How much wider each side the road is at point i: 0, growing to {@link #WIDEN} towards the gate (the end). */
    public int widen(int i) {
        int d = points.size() - 1 - i;
        if (points.size() < WIDEN_FROM * 2 || d >= WIDEN_FROM) return 0;
        if (d <= WIDE_AT) return WIDEN;
        return (int) Math.round(WIDEN * (WIDEN_FROM - d) / (double) (WIDEN_FROM - WIDE_AT));
    }

    // ------------------------------------------------------------------------------------------
    // A cross-section
    // ------------------------------------------------------------------------------------------

    /**
     * Builds the cross-section at point i. Null: built; else {x, z} of the surface column that is protected land (then
     * nothing of it is built and the road stops there).
     */
    public int[] build(int i) {
        double[] p = points.get(i);
        double[] a = points.get(Math.max(0, i - 2)), b = points.get(Math.min(points.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        hx /= len;
        hz /= len;
        double rx = -hz, rz = hx;   // the right hand
        int h = halves[i];
        RoadProfile.Part part = parts[i];
        int dj = junction(i);
        int e = extra(i);
        List<Col> cols = new ArrayList<>();
        int[] stop = columns(i, e, cols);
        if (stop != null) return stop;
        boolean dash = (i / 4) % 2 == 0;
        int d = points.size() - 1 - i;
        boolean onOld = cross != null && cross[i] != Integer.MIN_VALUE && !over[i];
        // crosswalks: before the gate, and on this road either side of a junction
        boolean cross = (widen(i) > 0 && d >= CROSS_FROM && d <= CROSS_TO) || (!onOld && dj >= 3 && dj <= 5);
        if (dj <= 3) dash = false;   // the centre line stops short of a junction
        // a tunnel's ends: a portal (a stone brick face round the opening)
        boolean portal = part == RoadProfile.Part.TUNNEL && (i == 0 || i == points.size() - 1 || parts[i - 1] != part || parts[i + 1] != part);
        boolean half = (h & 1) == 1;
        for (Col c : cols) {
            double ao = Math.abs(c.lat()) - e;   // as if the road were its usual width: the extra goes to the lanes
            double lane = Math.abs(c.lat());
            RoadNetwork.Spot o = old(c.x(), c.z());
            boolean overIt = over != null && over[i];
            if (o != null && o.paved() && !overIt) {
                // another road here, level with this one (a junction) or this road's shoulder over it: its lanes stay as
                // they are; its kerbs and shoulders under this road's lanes and kerbs are paved over, level, as one surface
                if (o.lane() || ao > 3.5 || Math.abs(o.half() - h) > 1 || part != RoadProfile.Part.ROAD) continue;   // its kerb
                paving = true;
                road(c.x(), c.z(), o.half(), 0, at(style.surface(), (o.half() & 1) == 1), i, c.lat());
                paving = false;
                continue;
            }
            Material top;
            if (ao <= 2.5) {
                if (cross) top = Math.floorMod(Math.round(c.lat()), 2) == 0 ? style.edge() : style.surface();
                else if (lane <= 0.5) top = dash ? style.line() : style.surface();
                else if (ao > 1.5) top = style.edge();
                else if (part == RoadProfile.Part.BRIDGE && i % JOINT_EVERY == JOINT_EVERY / 2 && !half) top = POLISHED_JOINT;
                else if (part != RoadProfile.Part.BRIDGE && !half) top = patch(c.x(), c.z());
                else top = style.surface();
            } else {
                top = style.kerb();
            }
            switch (part) {
                case ROAD -> road(c.x(), c.z(), h, ao, at(top, half), i, c.lat());
                case BRIDGE -> bridge(c.x(), c.z(), h, ao, at(top, half), i % 10 == 0, ceilings[i], i);
                case TUNNEL -> tunnel(c.x(), c.z(), h, ao, at(top, half), i, c.lat() > 0, portal);
            }
        }
        if (mending) {   // (/관리자 국도 커브정리: a bridge's light put up again on its railing; signs and words stay as they are)
            if (part == RoadProfile.Part.BRIDGE) light(i, cols, e, h);
            return null;
        }
        light(i, cols, e, h);
        RoadSign.Kind sign = signs.get(i);
        if (sign != null) sign(p, rx, rz, h, RoadSign.yaw(-hx, -hz), i, sign);
        if (d == WORDS_AT && e > 0 && part == RoadProfile.Part.ROAD) site.roadText(p[0], top(h) + 0.01, p[1], RoadSign.yaw(-hx, -hz), GATE_WORDS);
        return null;
    }

    /** How much wider each side the road is at point i: at the dock's gate ({@link #widen}), and round a junction's corners. */
    private int extra(int i) {
        int dj = junction(i);
        return Math.max(widen(i), dj <= 2 ? WIDEN : dj <= 5 ? 1 : 0);
    }

    // ------------------------------------------------------------------------------------------
    // Street lights
    // ------------------------------------------------------------------------------------------

    /**
     * How far from the road's centre line (the nearest point of it, round a bend too) a street light's post stays at the
     * least: a block beyond the kerb (3.5; on a straight road the shoulder's outer column, its post 4.75 out). Lights used
     * to stand on the verge right by the kerb (the column whose centre is nearest 4 out): on a straight road the post 3.75
     * out, but on a diagonal or a bend that column can stand half in the kerb's line (3.18 out at 45 degrees), and a
     * vehicle's collision squares do not turn with it (VehiclePhysics: a bus 2.4 wide reaches 1.53 to each side at 45
     * degrees): a bus a little over the edge line, or cutting a bend, caught on them (RoadLightsTest).
     */
    public static final double LIGHT_CLEAR = 4.5;
    /** How far from the centre line a light's post stands at point i at the least: {@link #LIGHT_CLEAR}, more round a bend (the vehicles' reach). */
    double lightClear(int i) {
        return narrow ? LIGHT_CLEAR : Math.max(LIGHT_CLEAR, reach(i));
    }

    /** A light's point with no column far enough out on its side (a diagonal's columns): the next points (up to this many) take it. */
    public static final int LIGHT_SHIFT = 2;
    /** A light's post: a wall's post, 0.5 across in the middle of its column. */
    static final double POST_HALF = 0.25;

    /** Lights as roads were built before (tests: what /관리자 국도 가로등정리 mends). */
    private boolean oldLights;

    /** Builds the street lights as roads were built before (on the verge by the kerb). */
    RoadSection oldLights() {
        this.oldLights = true;
        return this;
    }

    /**
     * The street light at point i, if one goes here: every {@code lightEvery} (sides taking turns; never on a sign's side).
     * On a bridge on its railing; else on the shoulder, its post's column {@link #LIGHT_CLEAR} or more from the centre line
     * (the nearest such column to the road), at this point or, when it has none, one of the next {@link #LIGHT_SHIFT}.
     */
    private void light(int i, List<Col> cols, int e, int h) {
        int every = style.lightEvery(), k = Math.floorMod(i, every), base = i - k;
        if (base <= 0 || i >= points.size() - 1 || parts[i] == RoadProfile.Part.TUNNEL) return;
        if (oldLights || parts[base] == RoadProfile.Part.BRIDGE) {
            if (k != 0) return;
            int side = (i / every) % 2 == 0 && !signs.containsKey(i) ? 1 : -1;   // never in a sign's posts
            // on the bridge on its railing, else (as before) on the shoulder's verge by the kerb
            boolean deck = parts[i] == RoadProfile.Part.BRIDGE;
            double w = deck ? bridgeWide(i) : 0;
            Col post = nearest(cols, side * ((deck ? 3.0 : 4.0) + e + w), (deck ? 2.5 : 3.5) + e + w);
            if (post != null && (!deck || postClear(i, post.x(), post.z()))) lamp(post, cols, side, e, h, deck, ceilings[i]);
            return;
        }
        if (k > LIGHT_SHIFT || parts[i] != RoadProfile.Part.ROAD || parts[base] != RoadProfile.Part.ROAD) return;
        int side = lightSide(base);
        if (side == 0) return;
        for (int j = base; j < i; j++) if (parts[j] == RoadProfile.Part.ROAD && lightPost(j, side, null) != null) return;   // an earlier point took it
        Col post = lightPost(i, side, cols);
        if (post != null) lamp(post, cols, side, e, h, false, ceilings[i]);
    }

    /**
     * The side the light of the point `base` (a multiple of lightEvery) stands on: sides take turns, never a sign's side,
     * never the inside of a bend ({@link #insideBend}: then the outside; 0, none, when that is a sign's side).
     */
    private int lightSide(int base) {
        int side = (base / style.lightEvery()) % 2 == 0 && !signNear(base) ? 1 : -1;
        if (insideBend(base, side)) side = -side;
        return side == 1 && signNear(base) ? 0 : side;   // (0: none here: the bend's outside is the sign's side)
    }

    /** Whether one of this road's own signs (on the right) stands at the points a light of `base` may take, or just by them. */
    private boolean signNear(int base) {
        for (int j = Math.max(0, base - 1); j <= Math.min(points.size() - 1, base + LIGHT_SHIFT + 1); j++) if (signs.containsKey(j)) return true;
        return false;
    }

    /** A bend this sharp or more (degrees, over {@link #BEND_REACH} points either way) keeps the lights off its inside. */
    public static final double BEND = 10;
    /** Points either way a bend is measured over: twice the longest vehicle's length (a bus cuts a bend from well before it). */
    static final int BEND_REACH = 2 * (int) Math.ceil(RoadClearance.LENGTH);

    /**
     * Whether `side` is the inside of a bend at point i (the road turning that way by {@link #BEND} degrees or more over
     * {@link #BEND_REACH} points either way): a long bus cuts a bend's inside (its squares reach in over the kerb), so a
     * post there caught it even 4 out.
     */
    boolean insideBend(int i, int side) {
        double[] a = points.get(Math.max(0, i - BEND_REACH)), p = points.get(i), b = points.get(Math.min(points.size() - 1, i + BEND_REACH));
        double ax = p[0] - a[0], az = p[1] - a[1], bx = b[0] - p[0], bz = b[1] - p[1];
        if (Math.hypot(ax, az) < 1e-6 || Math.hypot(bx, bz) < 1e-6) return false;
        double cross = ax * bz - az * bx, dot = ax * bx + az * bz;
        double turn = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
        return turn >= BEND && Math.signum(cross) == side;   // turning right (cross > 0): the right is the inside
    }

    /**
     * The column of point i's cross-section (cols, or worked out when null) on this side where a light's post may stand:
     * on the shoulder, its post {@link #LIGHT_CLEAR} (and the widening) or more from the centre line; of those the one
     * nearest the road (then nearest the point). Null: none.
     */
    private Col lightPost(int i, int side, List<Col> cols) {
        int e = extra(i);
        if (cols == null) {
            cols = new ArrayList<>();
            if (columns(i, e, cols) != null) return null;
        }
        Col best = null;
        double bestClear = 0, bestAlong = 0;
        double[] p = points.get(i);
        double[] hd = heading(i);
        for (Col c : cols) {
            if (Math.signum(c.lat()) != side || Math.abs(c.lat()) <= 3.5 + e) continue;
            double clear = clearance(i, c.x(), c.z());
            if (clear < lightClear(i) + e - 1e-9) continue;
            double along = Math.abs((c.x() + 0.5 - p[0]) * hd[0] + (c.z() + 0.5 - p[1]) * hd[1]);
            if (best == null || clear < bestClear - 1e-9 || (clear < bestClear + 1e-9 && along < bestAlong)) {
                best = c;
                bestClear = clear;
                bestAlong = along;
            }
        }
        return best;
    }

    /** The way the road runs at point i (unit x, z: over 2 points either way). */
    private double[] heading(int i) {
        double[] a = points.get(Math.max(0, i - 2)), b = points.get(Math.min(points.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        return new double[]{hx / len, hz / len};
    }

    /**
     * How far a light's post in column (x, z) is from the road's centre line: the nearest point of its post (0.5 across in
     * the column's middle) to the line through the points near i ({@link #NEIGHBOURS} either way), so a bend's inside counts.
     */
    double clearance(int i, int x, int z) {
        double x0 = x + 0.5 - POST_HALF, x1 = x + 0.5 + POST_HALF, z0 = z + 0.5 - POST_HALF, z1 = z + 0.5 + POST_HALF;
        double best = Double.MAX_VALUE;
        int from = Math.max(0, i - NEIGHBOURS), to = Math.min(points.size() - 1, i + NEIGHBOURS);
        for (int j = from; j < Math.max(from + 1, to); j++) {
            double[] a = points.get(j), b = points.get(Math.min(points.size() - 1, j + 1));
            best = Math.min(best, boxToSegment(x0, z0, x1, z1, a[0], a[1], b[0], b[1]));
        }
        return best;
    }

    /** The distance from a box (x0, z0)-(x1, z1) to the segment a-b (0: they meet). */
    static double boxToSegment(double x0, double z0, double x1, double z1, double ax, double az, double bx, double bz) {
        if (segmentMeetsBox(x0, z0, x1, z1, ax, az, bx, bz)) return 0;
        double d = Double.MAX_VALUE;
        for (double[] c : new double[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) d = Math.min(d, pointToSegment(c[0], c[1], ax, az, bx, bz));
        for (double[] q : new double[][]{{ax, az}, {bx, bz}}) {
            double dx = Math.max(0, Math.max(x0 - q[0], q[0] - x1)), dz = Math.max(0, Math.max(z0 - q[1], q[1] - z1));
            d = Math.min(d, Math.hypot(dx, dz));
        }
        return d;
    }

    static double pointToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
        double t = l2 < 1e-12 ? 0 : Math.clamp(((px - ax) * vx + (pz - az) * vz) / l2, 0, 1);
        return Math.hypot(px - ax - t * vx, pz - az - t * vz);
    }

    private static boolean segmentMeetsBox(double x0, double z0, double x1, double z1, double ax, double az, double bx, double bz) {
        // Liang-Barsky clip of a-b against the box
        double t0 = 0, t1 = 1, dx = bx - ax, dz = bz - az;
        double[] p = {-dx, dx, -dz, dz}, q = {ax - x0, x1 - ax, az - z0, z1 - az};
        for (int k = 0; k < 4; k++) {
            if (Math.abs(p[k]) < 1e-12) {
                if (q[k] < 0) return false;
                continue;
            }
            double r = q[k] / p[k];
            if (p[k] < 0) t0 = Math.max(t0, r);
            else t1 = Math.min(t1, r);
            if (t0 > t1) return false;
        }
        return true;
    }

    /**
     * A street light already built at point i mended (/관리자 국도 가로등정리): a light ({@link RoadLights}: its exact shape)
     * whose post stands on this point's shoulder (as roads put them before, on the verge by the kerb) is taken away when its
     * post is nearer the centre line than {@link #LIGHT_CLEAR}, or it stands where `keepClear` says nothing may (a bus
     * stop's bay and the road before and after it, on the bay's side: {x, z} of its post or its lantern); then, but by a bay,
     * put up again where a road puts it now (on the same side; only where its post, arm and lantern find nothing but air,
     * the shoulder's grass and verge, a reflector or a guardrail). Only this road's shoulder and only a light's own blocks
     * (never a bridge's or a tunnel's point, protected land, another road's lanes, anything a player put). How many blocks
     * changed: {lights taken away, put up again, blocks}.
     */
    public int[] tidyLight(int i, java.util.function.BiPredicate<Integer, Integer> keepClear) {
        int[] out = {0, 0, 0};
        if (parts[i] != RoadProfile.Part.ROAD || i == 0 || i >= points.size() - 1) return out;
        int e = extra(i), h = halves[i], base = RoadProfile.block(h);
        List<Col> cols = new ArrayList<>();
        if (columns(i, e, cols) != null) return out;
        RoadLights.Blocks b = world::get;
        int changed = 0;
        for (Col c : cols) {
            double ao = Math.abs(c.lat()) - e;
            if (ao <= 3.5 || ao > 5.5 + wide(i)) continue;
            RoadLights.Light l = RoadLights.post(b, c.x(), c.z(), base + 1);
            if (l == null) continue;
            int side = (int) Math.signum(c.lat());
            boolean byBay = keepClear.test(l.x(), l.z()) || (l.lantern() != null && keepClear.test(l.lantern()[0], l.lantern()[2]));
            if (!byBay && clearance(i, c.x(), c.z()) >= lightClear(i) + e - 1e-9 && !insideBend(i, side)) continue;   // where it may stay
            // its blocks, only if they are still the light's (the arm's walls over the lanes, the post, the lantern)
            for (int[] k : l.blocks()) {
                Material m = world.get(k[0], k[1], k[2]);
                if (m != RoadLights.POST && m != RoadLights.LANTERN) continue;
                site.set(k[0], k[1], k[2], Material.AIR);
                changed++;
            }
            if (ao <= 4.5 && world.get(c.x(), base, c.z()) == RoadLights.FOOTING) {   // the verge's path back over its footing
                site.set(c.x(), base, c.z(), VERGE);
                changed++;
            }
            out[0]++;
            int again = insideBend(i, side) ? -side : side;
            if (!byBay && !(again == 1 && signNear(i))) {   // (never by one of the road's own signs, on the right)
                int put = relight(i, again, keepClear);
                if (put > 0) out[1]++;
                changed += put;
            }
        }
        out[2] = changed;
        return out;
    }

    /** The light of point i put up again on this side where a road puts it now (point i or the next ones), if all its place is free. */
    private int relight(int i, int side, java.util.function.BiPredicate<Integer, Integer> keepClear) {
        for (int j = i; j <= Math.min(points.size() - 2, i + LIGHT_SHIFT); j++) {
            if (parts[j] != RoadProfile.Part.ROAD) return 0;
            int e = extra(j);
            List<Col> cols = new ArrayList<>();
            if (columns(j, e, cols) != null) return 0;
            Col post = lightPost(j, side, cols);
            if (post == null) continue;
            Col end = nearest(cols, side * (2.0 + e), 1.5 + e);
            if (end == null || keepClear.test(post.x(), post.z()) || keepClear.test(end.x(), end.z())) return 0;
            int h = halves[j], y = surfaceY(h), base = RoadProfile.block(h), arm = Math.max(y + 6, ceilings[j] + 1);
            for (int k = base + 1; k <= arm; k++) if (!free(world.get(post.x(), k, post.z()))) return 0;
            for (Col c : cols) {
                double l = Math.abs(c.lat());
                if (Math.signum(c.lat()) != side || l < Math.abs(end.lat()) - 0.01 || l > Math.abs(post.lat()) + 0.01) continue;
                if (!free(world.get(c.x(), arm, c.z())) || !free(world.get(c.x(), arm - 1, c.z()))) return 0;
            }
            Material under = world.get(post.x(), base, post.z());
            if (under == null || !(under == VERGE || under == Material.GRASS_BLOCK || under == Material.DIRT || under == RoadLights.FOOTING
                    || under == DRAIN || under == APRON_BLOCK)) return 0;
            int[] n = {0};
            Site was = site;
            site = counting(was, n);
            try {
                lamp(post, cols, side, e, h, false, ceilings[j]);
            } finally {
                site = was;
            }
            return n[0];
        }
        return 0;
    }

    /** What a mended light may take the place of: air, snow, plants, a reflector or a guardrail. */
    private static boolean free(Material m) {
        if (m == null) return false;
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.SNOW || m == REFLECTOR || m == GUARDRAIL) return true;
        return switch (m) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DANDELION, POPPY, DEAD_BUSH, SWEET_BERRY_BUSH -> true;
            default -> false;
        };
    }

    /** The site, counting the blocks set. */
    private static Site counting(Site s, int[] n) {
        return new Site() {
            public boolean inside(int y) {
                return s.inside(y);
            }

            public boolean air(int x, int y, int z) {
                return s.air(x, y, z);
            }

            public boolean liquid(int x, int y, int z) {
                return s.liquid(x, y, z);
            }

            public boolean natural(int x, int y, int z) {
                return s.natural(x, y, z);
            }

            public boolean fillable(int x, int y, int z) {
                return s.fillable(x, y, z);
            }

            public void set(int x, int y, int z, Material m) {
                n[0]++;
                s.set(x, y, z, m);
            }

            public void set(int x, int y, int z, String data) {
                n[0]++;
                s.set(x, y, z, data);
            }

            public boolean protectedLand(int x, int z) {
                return s.protectedLand(x, z);
            }

            public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {
                s.roadSign(x, y, z, yaw, dock, km, route, kind);
            }

            public RoadNetwork.Spot road(int x, int z) {
                return s.road(x, z);
            }

            public Material get(int x, int y, int z) {
                return s.get(x, y, z);
            }
        };
    }

    /** A column of a cross-section and how far right of the middle its centre is (left: below 0). */
    private record Col(int x, int z, double lat) {}

    /**
     * The columns of the cross-section at point i (e: how much wider each side) into cols, but those in protected land.
     * Null, or {x, z} of the first surface column in protected land (cols is then not whole).
     */
    private int[] columns(int i, int e, List<Col> cols) {
        double[] p = points.get(i);
        double[] a = points.get(Math.max(0, i - 2)), b = points.get(Math.min(points.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        hx /= len;
        hz /= len;
        double rx = -hz, rz = hx;   // the right hand
        double reach = HALF + e + wide(i);
        int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]), r = (int) Math.ceil(reach) + 1;
        for (int bx = px - r; bx <= px + r; bx++) {
            for (int bz = pz - r; bz <= pz + r; bz++) {
                double cx = bx + 0.5 - p[0], cz = bz + 0.5 - p[1];
                double lat = cx * rx + cz * rz, along = cx * hx + cz * hz;
                if (Math.abs(lat) > reach + 0.5 || !owns(i, bx + 0.5, bz + 0.5)) continue;
                if ((i == 0 && along < -0.5) || (i == points.size() - 1 && along > 0.5)) continue;   // not past the ends
                double ao = Math.abs(lat);
                if (site.protectedLand(bx, bz)) {
                    if (ao <= 3.5 + e) return new int[]{bx, bz};
                    continue;   // a shoulder in someone's land: left alone
                }
                cols.add(new Col(bx, bz, lat));
            }
        }
        return null;
    }

    /**
     * A tunnel already built mended (/관리자 국도 터널보수): its cross-section at point i built again to today's ceiling
     * ({@link RoadClearance#ceilings}), but only from where its old ceiling was ({@link RoadClearance#legacy}) up, and only
     * where the block is the tunnel's own, the ground's or air ({@link #carvable}; water only sealed with a solid block):
     * never the surface or the lined walls under the old ceiling, a block a player put, protected land, another road's
     * lanes. On the flat the old and the new ceiling are the same: nothing changes. How many blocks changed (0: not a
     * tunnel).
     */
    public int rebore(int i) {
        if (parts[i] != RoadProfile.Part.TUNNEL) return 0;
        int from = surfaceY(halves[i]) + 5;
        int[] changed = {0};
        Site filtered = new Site() {
            boolean may(int x, int y, int z, Material to) {
                if (y < from || !world.inside(y)) return false;
                Material now = world.get(x, y, z);
                if (now == null || now == to) return false;
                return carvable(now) || (to != Material.AIR && world.liquid(x, y, z));
            }

            public boolean inside(int y) {
                return world.inside(y);
            }

            public boolean air(int x, int y, int z) {
                return world.air(x, y, z);
            }

            public boolean liquid(int x, int y, int z) {
                return world.liquid(x, y, z);
            }

            public boolean natural(int x, int y, int z) {
                return world.natural(x, y, z);
            }

            public boolean fillable(int x, int y, int z) {
                return world.fillable(x, y, z);
            }

            public void set(int x, int y, int z, Material m) {
                if (!may(x, y, z, m)) return;
                world.set(x, y, z, m);
                changed[0]++;
            }

            public void set(int x, int y, int z, String data) {
                int b = data.indexOf('[');
                Material m = Material.matchMaterial(b < 0 ? data : data.substring(0, b));
                if (m == null || !may(x, y, z, m)) return;
                world.set(x, y, z, data);
                changed[0]++;
            }

            public boolean protectedLand(int x, int z) {
                return world.protectedLand(x, z);
            }

            public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}

            public RoadNetwork.Spot road(int x, int z) {
                return world.road(x, z);
            }

            public Material get(int x, int y, int z) {
                return world.get(x, y, z);
            }
        };
        Site was = site;
        site = guard(filtered);
        boolean wasNarrow = narrow;
        narrow = true;   // the bore as wide as it was built: only its ceiling raised (/관리자 국도 커브정리 widens it)
        try {
            int dj = junction(i);
            int e = Math.max(widen(i), dj <= 2 ? WIDEN : dj <= 5 ? 1 : 0);
            List<Col> cols = new ArrayList<>();
            columns(i, e, cols);   // protected land: those columns are not in it
            boolean portal = i == 0 || i == points.size() - 1 || parts[i - 1] != parts[i] || parts[i + 1] != parts[i];
            for (Col c : cols) {
                RoadNetwork.Spot o = old(c.x(), c.z());
                if (o != null && o.paved()) continue;   // another road's lanes or kerbs: never
                tunnel(c.x(), c.z(), halves[i], Math.abs(c.lat()) - e, style.surface(), i, c.lat() > 0, portal);
            }
        } finally {
            site = was;
            narrow = wasNarrow;
        }
        return changed[0];
    }

    /** set while /관리자 국도 커브정리 builds a cross-section again into its overlay ({@link #tidyCurve}) */
    private boolean mending;

    /** What a guardrail or a reflector post is (the shoulder's clutter /관리자 국도 커브정리 may take away or move). */
    static boolean clutter(Material m) {
        return m == GUARDRAIL || m == REFLECTOR;
    }

    /**
     * A built road's cross-section at point i mended to the room roads leave the vehicles now (/관리자 국도 커브정리,
     * {@link #reach}): the cross-section built again as a road builds it now into an overlay of the world (nothing set yet),
     * then only what differs and may change is set:
     * <ul>
     *   <li>a bridge (every point): its railing (and the light on it) moved out of the vehicles' reach, the deck laid out to
     *       it, flush with the lanes; only the bridge's own blocks (its plinths, railing walls, lights) and air or water;</li>
     *   <li>a tunnel on a diagonal or round a bend: its bore widened and lined again further out (as {@link #rebore}: only the
     *       tunnel's own blocks, the ground's and air; never the lanes and kerbs);</li>
     *   <li>plain road round a bend (where the shoulder is wider now): guardrails and reflector posts in the vehicles' reach
     *       taken away (put again further out where the fill is high), the bank cut back and the shoulder laid out to it
     *       (only the ground's own blocks, air and the shoulder's clutter; never the lanes, kerbs or the verge's path).</li>
     * </ul>
     * Never protected land, another road's lanes, a bus stop's bay (keepClear: {x, z}), anything a player put (a block that
     * is none of those). Twice is once. How many blocks changed.
     */
    public int tidyCurve(int i, java.util.function.BiPredicate<Integer, Integer> keepClear) {
        if (narrow || i == 0 || i >= points.size() - 1) return 0;
        RoadProfile.Part part = parts[i];
        boolean need = switch (part) {
            case ROAD -> shoulderWide(i) > 1e-9;
            case BRIDGE -> true;
            case TUNNEL -> tunnelWide(i) > 1e-9;
        };
        if (!need) return 0;
        int e = extra(i), h = halves[i], y = surfaceY(h), base = RoadProfile.block(h);
        List<Col> cols = new ArrayList<>();
        if (columns(i, e, cols) != null) return 0;
        Map<Long, Double> ao = new java.util.HashMap<>();
        for (Col c : cols) ao.put(RoadNetwork.key(c.x(), c.z()), Math.abs(c.lat()) - e);
        Overlay o = new Overlay(world);
        // a bridge's old lights (on its railing, now in the vehicles' reach): taken away, put up again on the new railing
        java.util.Set<Long> lightBlocks = new java.util.HashSet<>();
        if (part == RoadProfile.Part.BRIDGE) {
            for (Col c : cols) {
                if (Math.abs(c.lat()) - e <= 2.5) continue;
                RoadLights.Light l = RoadLights.post(world::get, c.x(), c.z(), y + 1);
                if (l == null) continue;
                for (int[] k : l.blocks()) {
                    o.set(k[0], k[1], k[2], Material.AIR);
                    lightBlocks.add(Overlay.key(k[0], k[1], k[2]));
                }
            }
        }
        Site was = site;
        site = guard(o);
        mending = true;
        try {
            if (build(i) != null) return 0;
        } finally {
            mending = false;
            site = was;
        }
        int changed = 0;
        for (Map.Entry<Long, String> w : o.put.entrySet()) {
            long k = w.getKey();
            int x = Overlay.x(k), by = Overlay.y(k), z = Overlay.z(k);
            Double a = ao.get(RoadNetwork.key(x, z));
            if (a == null || keepClear.test(x, z) || world.protectedLand(x, z)) continue;
            String data = w.getValue();
            int b = data.indexOf('[');
            Material to = Material.matchMaterial(b < 0 ? data : data.substring(0, b)), now = world.get(x, by, z);
            if (to == null || now == null || now == to) continue;
            boolean where = switch (part) {
                case ROAD -> a > 3.5 && (by > base || a > HALF + 0.5);
                case BRIDGE -> a > 2.5 || by > y + 1;
                case TUNNEL -> a > 3.5 || by > y;
            };
            if (!where || !mayMend(part, now, to, world.liquid(x, by, z), by <= y, by == y + 1 && world.get(x, y, z) == Material.STONE_BRICKS,
                    lightBlocks.contains(k))) continue;
            RoadNetwork.Spot other = world.road(x, z);
            if (other != null && other.paved()) continue;   // another road's lanes and kerbs: never
            if (b < 0) world.set(x, by, z, to);
            else world.set(x, by, z, data);
            changed++;
        }
        return changed;
    }

    /**
     * What /관리자 국도 커브정리 may change a block from: air and the ground's own blocks (as {@link #carvable}, but on plain
     * road not the tunnels' and lights' stone bricks), a guardrail or reflector, water to something solid; on a bridge also
     * its deck's stone bricks (its plinths), its railing (a wall on a plinth) and its lights' blocks; in a tunnel its own
     * blocks.
     */
    static boolean mayMend(RoadProfile.Part part, Material now, Material to, boolean liquid, boolean deck, boolean railing, boolean light) {
        if (clutter(now) || light) return true;
        if (liquid) return to != Material.AIR && to.isSolid();
        boolean own = now == Material.STONE_BRICKS || now == Material.SEA_LANTERN || now == Material.WHITE_CONCRETE
                || now == Material.YELLOW_TERRACOTTA || now == Material.POLISHED_ANDESITE;
        return switch (part) {
            case ROAD -> carvable(now) && !own;
            case BRIDGE -> (carvable(now) && !own) || (now == Material.STONE_BRICKS && deck) || (now == Material.STONE_BRICK_WALL && railing);
            case TUNNEL -> carvable(now);
        };
    }

    /** The world with blocks set over it, not yet put (what {@link #tidyCurve} builds into first). */
    static final class Overlay implements Site {
        final Site world;
        final Map<Long, String> put = new java.util.LinkedHashMap<>();

        Overlay(Site world) {
            this.world = world;
        }

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        static int x(long k) {
            return (int) (k >>> 42) - (1 << 20);
        }

        static int y(long k) {
            return (int) (k & ((1 << 21) - 1)) - 1024;
        }

        static int z(long k) {
            return (int) ((k >> 21) & ((1 << 21) - 1)) - (1 << 20);
        }

        private Material over(int x, int y, int z) {
            String d = put.get(key(x, y, z));
            if (d == null) return null;
            int b = d.indexOf('[');
            return Material.matchMaterial(b < 0 ? d : d.substring(0, b));
        }

        public boolean inside(int y) {
            return world.inside(y);
        }

        public boolean air(int x, int y, int z) {
            Material m = over(x, y, z);
            return m == null ? world.air(x, y, z) : m == Material.AIR || m == Material.CAVE_AIR;
        }

        public boolean liquid(int x, int y, int z) {
            Material m = over(x, y, z);
            return m == null ? world.liquid(x, y, z) : m == Material.WATER || m == Material.LAVA;
        }

        public boolean natural(int x, int y, int z) {
            Material m = over(x, y, z);
            if (m == null) return world.natural(x, y, z);
            String n = m.name();
            return n.endsWith("_LOG") || n.endsWith("_LEAVES") || m == Material.VINE || m == Material.SNOW;
        }

        public boolean fillable(int x, int y, int z) {
            Material m = over(x, y, z);
            if (m == null) return world.fillable(x, y, z);
            return world.inside(y) && (m == Material.AIR || m == Material.CAVE_AIR || m == Material.WATER || !m.isSolid());
        }

        public void set(int x, int y, int z, Material m) {
            if (m != null && world.inside(y)) put.put(key(x, y, z), m.getKey().toString());
        }

        public void set(int x, int y, int z, String data) {
            if (data != null && world.inside(y)) put.put(key(x, y, z), data);
        }

        public boolean protectedLand(int x, int z) {
            return world.protectedLand(x, z);
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}

        public RoadNetwork.Spot road(int x, int z) {
            return world.road(x, z);
        }

        public Material get(int x, int y, int z) {
            Material m = over(x, y, z);
            return m != null ? m : world.get(x, y, z);
        }
    }

    /**
     * What {@link #rebore} may take out of (or put over) a tunnel: air, the blocks a tunnel is built of, the ground's own
     * blocks (stone and its kinds, deepslate, dirt, sand, gravel, clay, terracotta, ice, snow, ores, roots and plants).
     * Not water or lava, nor anything made (planks, glass, a chest, bricks but the tunnel's own stone bricks, ...).
     */
    static boolean carvable(Material m) {
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return true;
        switch (m) {
            case STONE_BRICKS, SEA_LANTERN, WHITE_CONCRETE, YELLOW_TERRACOTTA, POLISHED_ANDESITE,
                 STONE, DEEPSLATE, TUFF, CALCITE, GRANITE, DIORITE, ANDESITE, SMOOTH_BASALT, DRIPSTONE_BLOCK, POINTED_DRIPSTONE,
                 DIRT, COARSE_DIRT, ROOTED_DIRT, GRASS_BLOCK, PODZOL, MYCELIUM, MUD, CLAY, GRAVEL, SAND, RED_SAND, SANDSTONE,
                 RED_SANDSTONE, TERRACOTTA, ICE, PACKED_ICE, BLUE_ICE, SNOW, SNOW_BLOCK, POWDER_SNOW, MOSS_BLOCK, MOSS_CARPET,
                 GLOW_LICHEN, VINE, HANGING_ROOTS, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, AMETHYST_BLOCK, BUDDING_AMETHYST,
                 MAGMA_BLOCK, COBWEB -> {
                return true;
            }
            default -> { }
        }
        String n = m.name();
        return n.endsWith("_ORE") || (n.endsWith("_TERRACOTTA") && !n.endsWith("GLAZED_TERRACOTTA")) || n.endsWith("_LOG") || n.endsWith("_LEAVES");
    }

    /** The column whose lat is nearest `want` (on its side, further out than `beyond`), or null. */
    private static Col nearest(List<Col> cols, double want, double beyond) {
        Col best = null;
        for (Col c : cols) {
            if (Math.signum(c.lat()) != Math.signum(want) || Math.abs(c.lat()) <= beyond) continue;
            if (best == null || Math.abs(c.lat() - want) < Math.abs(best.lat() - want)) best = c;
        }
        return best;
    }

    /**
     * Whether point i is the nearest point of the road to (x, z) (a tie: the earlier point). Every column near the road
     * belongs to exactly one cross-section, so they neither leave gaps (on a diagonal, round a bend) nor build over each
     * other (a light put by one is never cut away by the next).
     */
    private boolean owns(int i, double x, double z) {
        double[] p = points.get(i);
        double d = (x - p[0]) * (x - p[0]) + (z - p[1]) * (z - p[1]);
        for (int j = Math.max(0, i - NEIGHBOURS); j <= Math.min(points.size() - 1, i + NEIGHBOURS); j++) {
            if (j == i) continue;
            double[] q = points.get(j);
            double e = (x - q[0]) * (x - q[0]) + (z - q[1]) * (z - q[1]);
            if (e < d - 1e-9 || (e < d + 1e-9 && j < i)) return false;
        }
        return true;
    }

    /** How many points each way a column's nearest point is looked for (enough for the width round a sharp bend). */
    private static final int NEIGHBOURS = 12;

    /** Trees and the like over a column, up to 30 above the road. */
    private void clearTrees(int x, int z, int from) {
        for (int y = from; y < from + 30 && site.inside(y); y++) if (site.natural(x, y, z)) site.set(x, y, z, Material.AIR);
    }

    /**
     * Cut over the road: air `height` blocks over its level, then on up as long as there is ground there, so no bank is left
     * hanging over the road (at most 30 over it).
     */
    private void cut(int x, int z, int level, int height) {
        for (int y = level + 1; y <= level + height; y++) site.set(x, y, z, Material.AIR);
        for (int y = level + height + 1; y <= level + 30 && site.inside(y) && !site.air(x, y, z); y++) site.set(x, y, z, Material.AIR);
    }

    /** How many blocks under y the column is open (air, water, plants), up to `max`: how far the road stands over the ground. */
    private int open(int x, int y, int z, int max) {
        int k = 0;
        while (k < max && site.fillable(x, y - k - 1, z)) k++;
        return k;
    }

    private void road(int x, int z, int h, double ao, Material top, int i, double lat) {
        int y = surfaceY(h), base = RoadProfile.block(h);
        if (ao <= 3.5) {
            site.set(x, y, z, top);
            cut(x, z, y, Math.max(5, ceilings[i] - y));   // on a grade: as high as a tunnel's ceiling would be
            if (y != base) site.set(x, base, z, Material.STONE);   // under a slab
            for (int k = 1; k <= 8; k++) {                         // fill
                if (!site.fillable(x, base - k, z)) break;
                site.set(x, base - k, z, k == 1 ? Material.STONE : Material.COBBLESTONE);
            }
            clearTrees(x, z, y + 6);
            return;
        }
        // the shoulders: cut the bank down to the road, fill a dip to it, clear trees. A clean verge: a path strip by the
        // kerb (a drain grate in it now and then), grass beyond; a guardrail on a high fill, a reflector post now and then
        boolean verge = ao <= 4.5, apron = points.size() - 1 - i <= APRON;
        boolean high = !verge && !apron && open(x, base, z, RAIL_DROP) >= RAIL_DROP;
        cut(x, z, base, 4);
        if (!site.liquid(x, base, z)) {
            if (apron) site.set(x, base, z, APRON_BLOCK);   // the last blocks before the gate: paved like the dock's floor
            else if (verge && i % DRAIN_EVERY == DRAIN_EVERY / 2) site.set(x, base, z, "minecraft:iron_trapdoor[half=top]");
            else site.set(x, base, z, verge ? VERGE : Material.GRASS_BLOCK);
            for (int k = 1; k <= 8; k++) {   // down to the ground (as deep as the road itself is filled): never left hanging
                if (!site.fillable(x, base - k, z) || site.liquid(x, base - k, z)) break;
                site.set(x, base - k, z, verge && k == 1 && i % DRAIN_EVERY == DRAIN_EVERY / 2 ? Material.GRAVEL : Material.DIRT);
            }
            // (round a bend and on a diagonal further out: clear of what the vehicles reach, RoadClearance#reach)
            double w = shoulderWide(i);
            if (!verge && !apron && ao > 4.5 + w && ao <= 5.5 + w && postClear(i, x, z)) {
                if (high) site.set(x, base + 1, z, GUARDRAIL);
                else if (i % REFLECT_EVERY == REFLECT_EVERY / 2) site.set(x, base + 1, z, REFLECTOR);
            }
        }
        clearTrees(x, z, base + 1);
    }

    private void bridge(int x, int z, int h, double ao, Material top, boolean pillar, int ceiling, int i) {
        double w = bridgeWide(i);
        if (ao > 3.5 + w) return;
        int y = surfaceY(h), base = RoadProfile.block(h);
        boolean half = y != base;
        // the railing: clear of what the vehicles reach (RoadClearance#reach: 2.5 out the squares of a bus on the edge line
        // caught its posts), the deck between it and the lanes flush with them (the kerb's block)
        boolean rail = ao > 2.5 + w && postClear(i, x, z);
        // the edges: always a full block, a plinth the railing (and a light) stands on, also where the deck is a slab
        // (a wall on a slab would float half a block over it)
        site.set(x, y, z, rail ? Material.STONE_BRICKS : top);
        if (half) site.set(x, base, z, Material.STONE_BRICKS);
        for (int k = y + 1; k <= Math.max(y + 4, ceiling - 1); k++) site.set(x, k, z, Material.AIR);
        if (rail) site.set(x, y + 1, z, Material.STONE_BRICK_WALL);   // the railing
        if (pillar && ao <= 2.5 && old(x, z) == null) {   // never a pier on another road
            for (int k = 1; k <= 64; k++) {
                if (!site.fillable(x, base - k, z)) break;
                site.set(x, base - k, z, Material.STONE_BRICKS);
            }
        }
        clearTrees(x, z, Math.max(y + 5, ceiling));
    }

    private void tunnel(int x, int z, int h, double ao, Material top, int i, boolean right, boolean portal) {
        int y = surfaceY(h), base = RoadProfile.block(h), c = ceilings[i], cap = c;
        double w = tunnelWide(i);   // the bore wider on a diagonal and round a bend: its walls clear of what the vehicles reach
        // where the ceiling steps (on a grade) the higher bore is closed over by the lower ceiling's columns
        for (int k = Math.max(0, i - 1); k <= Math.min(points.size() - 1, i + 1); k++) cap = Math.max(cap, ceilings[k]);
        if (ao <= 3.5 + w) {
            site.set(x, y, z, top);
            if (y != base) site.set(x, base, z, Material.STONE);
            for (int k = y + 1; k < c; k++) site.set(x, k, z, Material.AIR);
            site.set(x, c, z, i % 8 == 0 && ao <= 0.5 ? Material.SEA_LANTERN : Material.STONE_BRICKS);   // the light flush in it
            for (int k = c + 1; k <= cap; k++) if (site.fillable(x, k, z)) site.set(x, k, z, Material.STONE_BRICKS);
            if (portal) site.set(x, c + 1, z, Material.STONE_BRICKS);   // the portal's lintel
        } else if (portal) {
            for (int k = y; k <= cap + 1; k++) site.set(x, k, z, Material.STONE_BRICKS);   // the portal: wider, higher
            clearTrees(x, z, cap + 2);
        } else if (ao <= 4.5 + w) {
            // the lined wall: a stone brick footing, a grey dado, a white band with a yellow stripe (a light in it every 8,
            // between the ceiling's, sides taking turns), stone bricks to the ceiling
            boolean light = i % 8 == 4 && ((i / 8) % 2 == 0) == right;
            site.set(x, y, z, Material.STONE_BRICKS);
            site.set(x, y + 1, z, Material.POLISHED_ANDESITE);
            site.set(x, y + 2, z, Material.WHITE_CONCRETE);
            site.set(x, y + 3, z, light ? Material.SEA_LANTERN : Material.YELLOW_TERRACOTTA);
            site.set(x, y + 4, z, Material.WHITE_CONCRETE);
            for (int k = y + 5; k <= cap; k++) site.set(x, k, z, Material.STONE_BRICKS);   // stone bricks up to the ceiling
        }
    }

    /**
     * A street light: a post at `post` (the verge, or a bridge's railing) up to a block over the ceiling (6 over a flat
     * road), an arm along the top to over the edge lane and a lantern hanging under its end in the ceiling's block (5
     * over a flat road, 4 clear blocks under it; higher on a grade: {@link RoadClearance}): no vehicle touches it.
     */
    private void lamp(Col post, List<Col> cols, int side, int e, int h, boolean deck, int ceiling) {
        if (site.protectedLand(post.x(), post.z())) return;
        for (Col c : cols) {   // the post, its arm and lantern: never in or over another road's lanes
            if (Math.signum(c.lat()) != side || Math.abs(c.lat()) < 1.5 + e || Math.abs(c.lat()) > Math.abs(post.lat()) + 0.01) continue;
            RoadNetwork.Spot o = old(c.x(), c.z());
            if (o != null && o.paved()) return;
        }
        int y = surfaceY(h), base = RoadProfile.block(h);
        if (!deck) footing(post.x(), base, post.z());
        int arm = Math.max(y + 6, ceiling + 1);
        for (int k = deck ? y + 1 : base + 1; k <= arm; k++) site.set(post.x(), k, post.z(), Material.STONE_BRICK_WALL);
        Col end = nearest(cols, side * (2.0 + e), 1.5 + e);
        if (end == null || end.equals(post)) {
            site.set(post.x(), arm - 1, post.z(), Material.LANTERN);
            return;
        }
        for (Col c : cols) {
            double l = Math.abs(c.lat());
            if (Math.signum(c.lat()) != side || c.equals(post) || l < Math.abs(end.lat()) - 0.01 || l > Math.abs(post.lat()) + 0.01) continue;
            site.set(c.x(), arm, c.z(), Material.STONE_BRICK_WALL);   // the arm
        }
        site.set(end.x(), arm - 1, end.z(), "minecraft:lantern[hanging=true]");
    }

    /** Whether a block stands on the one under it like a post: a wall or a fence (a railing, a guardrail, a light's post). */
    static boolean standing(Material m) {
        String n = m.name();
        return n.endsWith("_WALL") || n.endsWith("_FENCE");
    }

    /** The full block under a wall that stood on this slab: the slab's own block (stone bricks for one without). */
    static Material plinth(Material slab) {
        return switch (slab) {
            case SMOOTH_STONE_SLAB -> Material.SMOOTH_STONE;
            case DEEPSLATE_TILE_SLAB -> Material.DEEPSLATE_TILES;
            case SMOOTH_QUARTZ_SLAB -> Material.SMOOTH_QUARTZ;
            case POLISHED_ANDESITE_SLAB -> Material.POLISHED_ANDESITE;
            case POLISHED_BLACKSTONE_SLAB -> Material.POLISHED_BLACKSTONE;
            default -> Material.STONE_BRICKS;
        };
    }

    /**
     * A built road's column mended (/관리자 국도 난간고치기): every wall or fence from y `from` to `to` that stands on a slab
     * (half a block under it: it floated) gets the slab's full block under it, on a verge's path block (a little low) stone
     * bricks, over air or water stone bricks down to the ground. How many blocks were put. (A road built now never needs
     * it: {@link #bridge} and the lights stand on full blocks.)
     */
    static int plinths(Site s, int x, int z, int from, int to) {
        int n = 0;
        for (int y = from; y <= to; y++) {
            Material m = s.get(x, y, z), below = s.get(x, y - 1, z);
            if (m == null || below == null || !standing(m) || standing(below)) continue;
            if (below.name().endsWith("_SLAB")) {
                s.set(x, y - 1, z, plinth(below));
                n++;
            } else if (below == VERGE) {
                s.set(x, y - 1, z, Material.STONE_BRICKS);
                n++;
            } else if (s.fillable(x, y - 1, z)) {
                for (int k = 1; k <= 8 && s.fillable(x, y - k, z); k++) {
                    s.set(x, y - k, z, Material.STONE_BRICKS);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * A light's footing: a stone brick block at the shoulder's level (in place of the verge's path block, which is a
     * little lower than a full block) and on down to the ground where that is open: a post never over air or water.
     */
    private void footing(int x, int base, int z) {
        site.set(x, base, z, Material.STONE_BRICKS);
        for (int k = 1; k <= 8 && site.fillable(x, base - k, z); k++) site.set(x, base - k, z, Material.STONE_BRICKS);
    }

    private void sign(double[] p, double rx, double rz, int h, float yaw, int i, RoadSign.Kind kind) {
        place(p, rx, rz, h, yaw, i, kind);
    }

    /**
     * A direction sign on the right at point i, facing the traffic coming from the start: its posts on the kerb and the
     * shoulder (a stone brick footing where the shoulder is not solid), none where a post would stand in protected land
     * or another road's lanes; then the site puts the panel. (The signs where roads meet, for the way back and to
     * confirm the way: {@link RoadGuide}, put up once the road is built.)
     */
    private void place(double[] p, double rx, double rz, int h, float yaw, int i, RoadSign.Kind kind) {
        int[][] posts = new int[2][];
        int level = RoadProfile.block(h);
        for (int k = 0; k < 2; k++) {
            double o = RoadSign.LATERAL + (k == 0 ? -RoadSign.POSTS : RoadSign.POSTS);
            posts[k] = new int[]{(int) Math.floor(p[0] + rx * o), (int) Math.floor(p[1] + rz * o)};
            if (site.protectedLand(posts[k][0], posts[k][1])) return;
            RoadNetwork.Spot s = old(posts[k][0], posts[k][1]);
            if (s != null && s.paved()) return;   // never in another road's lanes
        }
        for (int[] c : posts) if (site.fillable(c[0], level, c[1]) && !site.liquid(c[0], level, c[1])) site.set(c[0], level, c[1], Material.STONE_BRICKS);
        double x = p[0] + rx * RoadSign.LATERAL, z = p[1] + rz * RoadSign.LATERAL;
        site.roadSign(x, level + 1, z, yaw, dock, (points.size() - 1 - i) / 1000.0, route, kind);
    }
}
