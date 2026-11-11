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
 *   <li>TUNNEL: the surface, 4 of air, a stone brick ceiling (a light in it every 8) and lined walls (a white band with a
 *       yellow stripe, lights in it between the ceiling's); at each end a portal (the walls out to the shoulders and a
 *       block higher, a lintel over the opening).</li>
 * </ul>
 * Street lights every {@code lightEvery} (sides taking turns), a post on the shoulder with an arm over the edge lane and
 * a lantern hanging from it, 4 clear blocks over the road; direction signs ({@link RoadSign}: the site puts the model and
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

    private final Site site, world;
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
        this.parts = parts;
        this.dock = dock;
        this.route = route;
        this.signs = signs(parts, style.markerEvery());
    }

    public Style style() {
        return style;
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
        return this;
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
                if (!o.paved() && y > oy && y <= oy + 6) {
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
        // wider at the dock's gate, and round a junction's corners
        int e = Math.max(widen(i), dj <= 2 ? WIDEN : dj <= 5 ? 1 : 0), reach = HALF + e;
        List<Col> cols = new ArrayList<>();
        int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
        for (int bx = px - reach - 1; bx <= px + reach + 1; bx++) {
            for (int bz = pz - reach - 1; bz <= pz + reach + 1; bz++) {
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
                case BRIDGE -> bridge(c.x(), c.z(), h, ao, at(top, half), i % 10 == 0);
                case TUNNEL -> tunnel(c.x(), c.z(), h, ao, at(top, half), i, c.lat() > 0, portal);
            }
        }
        if (i % style.lightEvery() == 0 && part != RoadProfile.Part.TUNNEL && i > 0 && i < points.size() - 1) {
            int side = (i / style.lightEvery()) % 2 == 0 && !signs.containsKey(i) ? 1 : -1;   // never in a sign's posts
            // on the bridge on its railing, else on the shoulder's verge: never in the lanes; its arm over the edge lane
            boolean deck = part == RoadProfile.Part.BRIDGE;
            Col post = nearest(cols, side * ((deck ? 3.0 : 4.0) + e), (deck ? 2.5 : 3.5) + e);
            if (post != null) lamp(post, cols, side, e, h, deck);
        }
        RoadSign.Kind sign = signs.get(i);
        if (sign != null) sign(p, rx, rz, h, RoadSign.yaw(-hx, -hz), i, sign);
        if (d == WORDS_AT && e > 0 && part == RoadProfile.Part.ROAD) site.roadText(p[0], top(h) + 0.01, p[1], RoadSign.yaw(-hx, -hz), GATE_WORDS);
        return null;
    }

    /** A column of a cross-section and how far right of the middle its centre is (left: below 0). */
    private record Col(int x, int z, double lat) {}

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
            cut(x, z, y, 5);
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
            if (!verge && !apron && ao <= 5.5) {
                if (high) site.set(x, base + 1, z, GUARDRAIL);
                else if (i % REFLECT_EVERY == REFLECT_EVERY / 2) site.set(x, base + 1, z, REFLECTOR);
            }
        }
        clearTrees(x, z, base + 1);
    }

    private void bridge(int x, int z, int h, double ao, Material top, boolean pillar) {
        if (ao > 3.5) return;
        int y = surfaceY(h), base = RoadProfile.block(h);
        boolean half = y != base;
        // the edges: always a full block, a plinth the railing (and a light) stands on, also where the deck is a slab
        // (a wall on a slab would float half a block over it)
        site.set(x, y, z, ao > 2.5 ? Material.STONE_BRICKS : top);
        if (half) site.set(x, base, z, Material.STONE_BRICKS);
        for (int k = y + 1; k <= y + 4; k++) site.set(x, k, z, Material.AIR);
        if (ao > 2.5) site.set(x, y + 1, z, Material.STONE_BRICK_WALL);   // the railing
        if (pillar && ao <= 2.5 && old(x, z) == null) {   // never a pier on another road
            for (int k = 1; k <= 64; k++) {
                if (!site.fillable(x, base - k, z)) break;
                site.set(x, base - k, z, Material.STONE_BRICKS);
            }
        }
        clearTrees(x, z, y + 5);
    }

    private void tunnel(int x, int z, int h, double ao, Material top, int i, boolean right, boolean portal) {
        int y = surfaceY(h), base = RoadProfile.block(h);
        if (ao <= 3.5) {
            site.set(x, y, z, top);
            if (y != base) site.set(x, base, z, Material.STONE);
            for (int k = y + 1; k <= y + 4; k++) site.set(x, k, z, Material.AIR);
            site.set(x, y + 5, z, i % 8 == 0 && ao <= 0.5 ? Material.SEA_LANTERN : Material.STONE_BRICKS);
            if (portal) site.set(x, y + 6, z, Material.STONE_BRICKS);   // the portal's lintel
        } else if (portal) {
            for (int k = y; k <= y + 6; k++) site.set(x, k, z, Material.STONE_BRICKS);   // the portal: wider, higher
            clearTrees(x, z, y + 7);
        } else if (ao <= 4.5) {
            // the lined wall: a stone brick footing, a grey dado, a white band with a yellow stripe (a light in it every 8,
            // between the ceiling's, sides taking turns), stone bricks to the ceiling
            boolean light = i % 8 == 4 && ((i / 8) % 2 == 0) == right;
            site.set(x, y, z, Material.STONE_BRICKS);
            site.set(x, y + 1, z, Material.POLISHED_ANDESITE);
            site.set(x, y + 2, z, Material.WHITE_CONCRETE);
            site.set(x, y + 3, z, light ? Material.SEA_LANTERN : Material.YELLOW_TERRACOTTA);
            site.set(x, y + 4, z, Material.WHITE_CONCRETE);
            site.set(x, y + 5, z, Material.STONE_BRICKS);
        }
    }

    /**
     * A street light: a post at `post` (the verge, or a bridge's railing) up to 6 over the road, an arm along the top to
     * over the edge lane and a lantern hanging under its end, 5 over the road (4 clear blocks under it: no vehicle
     * touches it).
     */
    private void lamp(Col post, List<Col> cols, int side, int e, int h, boolean deck) {
        if (site.protectedLand(post.x(), post.z())) return;
        for (Col c : cols) {   // the post, its arm and lantern: never in or over another road's lanes
            if (Math.signum(c.lat()) != side || Math.abs(c.lat()) < 1.5 + e || Math.abs(c.lat()) > Math.abs(post.lat()) + 0.01) continue;
            RoadNetwork.Spot o = old(c.x(), c.z());
            if (o != null && o.paved()) return;
        }
        int y = surfaceY(h), base = RoadProfile.block(h);
        if (!deck) footing(post.x(), base, post.z());
        for (int k = deck ? y + 1 : base + 1; k <= y + 6; k++) site.set(post.x(), k, post.z(), Material.STONE_BRICK_WALL);
        Col end = nearest(cols, side * (2.0 + e), 1.5 + e);
        if (end == null || end.equals(post)) {
            site.set(post.x(), y + 5, post.z(), Material.LANTERN);
            return;
        }
        for (Col c : cols) {
            double l = Math.abs(c.lat());
            if (Math.signum(c.lat()) != side || c.equals(post) || l < Math.abs(end.lat()) - 0.01 || l > Math.abs(post.lat()) + 0.01) continue;
            site.set(c.x(), y + 6, c.z(), Material.STONE_BRICK_WALL);   // the arm
        }
        site.set(end.x(), y + 5, end.z(), "minecraft:lantern[hanging=true]");
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
