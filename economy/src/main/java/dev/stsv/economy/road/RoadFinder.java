package dev.stsv.economy.road;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * A road found by its blocks, where no saved road says one is (README "국도", 터널보수 여기 · 눈치우기): a road file lost or
 * replaced, a world renamed moving the server, parts worked out again that disagree with what the builder did. Only ever
 * what the road builder makes: its surface blocks ({@link RoadSection.Style#roadBlocks}: the lanes, lines, patches,
 * kerbs and their slabs), a tunnel's stone brick ceiling (a sea lantern flush in it) at least 4 clear over them, and its
 * lined wall beside it (polished andesite, white concrete, a yellow stripe or a light, white concrete). Pure (unit-tested:
 * RoadFinderTest).
 *
 * <ul>
 *   <li><b>Tunnels</b> ({@link #scan}, {@link #plan}, {@link #bore}): a lane column with that ceiling and a lined wall
 *       within {@link #WALL_REACH} is a tunnel's. Its ceiling goes as high as a road builds one now
 *       ({@link RoadClearance}: over the highest road surface within {@link RoadClearance#REACH} + 1 blocks, at most
 *       {@link #STEP} blocks off its own: never another road over or under it), carved from its old ceiling up through the
 *       ground's and the tunnel's own blocks only ({@link RoadSection#carvable}); the ceiling block goes back on top (the
 *       light moved up with it), and the rock beside the higher bore is closed with stone bricks where it is open (air or
 *       water), never in front of a portal (over open road or a verge).</li>
 *   <li><b>Snow</b> ({@link #snow}): a snow layer (or powder snow) on a road block.</li>
 * </ul>
 */
final class RoadFinder {

    private RoadFinder() {}

    /** The world's blocks (unit tests: a map). */
    interface Blocks {
        Material get(int x, int y, int z);
    }

    /** Where blocks may change: a column's protection, and setting one. */
    interface Site extends Blocks {
        /** A column the road never touches (a player's or the server's land, roads.avoid). */
        boolean protectedLand(int x, int z);

        void set(int x, int y, int z, Material m);
    }

    /** No ceiling. */
    static final int OPEN = Integer.MIN_VALUE;
    /** How far (blocks across) a lane column's tunnel wall may be. */
    static final int WALL_REACH = 7;
    /** How far a road's own surface rises within reach at most (1 in 3 over {@link RoadClearance#REACH} + 1). */
    static final int STEP = 4;
    /** The surfaces a ceiling clears: within this many blocks across. */
    static final int REACH = RoadClearance.REACH + 1;
    /** The highest a ceiling is looked for over a surface (a tunnel built now on a steep grade). */
    static final int LOOK_UP = 12;

    static long key(int x, int z) {
        return RoadNetwork.key(x, z);
    }

    static boolean air(Material m) {
        return m == null || m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    static boolean snowy(Material m) {
        return m == Material.SNOW || m == Material.POWDER_SNOW;
    }

    static boolean ceilingBlock(Material m) {
        return m == Material.STONE_BRICKS || m == Material.SEA_LANTERN;
    }

    /** A road surface found: its column, the block it is in, its height in half blocks, its ceiling (block y) or {@link #OPEN}. */
    record Surface(int x, int z, int y, int half, int ceiling, Material ceilingBlock) {
        boolean tunnel() {
            return ceiling != OPEN;
        }
    }

    /** What a scan found: the road surfaces and the tunnels' lined walls (their surface block y), by column. */
    static final class Found {
        final Map<Long, List<Surface>> surfaces = new HashMap<>();
        final Map<Long, List<Integer>> walls = new HashMap<>();
        int columns;

        int surfaceCount() {
            int n = 0;
            for (List<Surface> l : surfaces.values()) n += l.size();
            return n;
        }

        int tunnelCount() {
            int n = 0;
            for (List<Surface> l : surfaces.values()) for (Surface s : l) if (s.tunnel()) n++;
            return n;
        }

        /** The surface at a column within dy of y, or null. */
        Surface at(int x, int z, int y, int dy) {
            List<Surface> l = surfaces.get(key(x, z));
            if (l == null) return null;
            for (Surface s : l) if (Math.abs(s.y() - y) <= dy) return s;
            return null;
        }

        boolean wallNear(Surface s) {
            for (int dx = -WALL_REACH; dx <= WALL_REACH; dx++) {
                for (int dz = -WALL_REACH; dz <= WALL_REACH; dz++) {
                    if (dx * dx + dz * dz > WALL_REACH * WALL_REACH) continue;
                    List<Integer> w = walls.get(key(s.x() + dx, s.z() + dz));
                    if (w == null) continue;
                    for (int y : w) if (Math.abs(y - s.y()) <= 2) return true;
                }
            }
            return false;
        }
    }

    /**
     * One column from hi down to lo: every road surface with at least 4 clear over it (snow and air) and what is over
     * that (a tunnel's ceiling within {@link #LOOK_UP}, else open), every lined tunnel wall.
     */
    static void scan(Blocks b, int x, int z, int lo, int hi, Set<Material> road, Found into) {
        into.columns++;
        Material up1 = b.get(x, hi + 1, z), up2 = b.get(x, hi + 2, z), up3 = b.get(x, hi + 3, z);
        for (int y = hi; y >= lo; y--) {
            Material m = b.get(x, y, z);
            if (road.contains(m) && clear(up1)) {
                int k = y + 1;
                while (k <= y + LOOK_UP && clear(b.get(x, k, z))) k++;
                if (k - y >= 5) {
                    Material c = k <= y + LOOK_UP ? b.get(x, k, z) : Material.AIR;
                    int ceiling = k <= y + LOOK_UP && ceilingBlock(c) ? k : OPEN;
                    int half = m.name().endsWith("_SLAB") ? 2 * y - 1 : 2 * y;
                    into.surfaces.computeIfAbsent(key(x, z), q -> new ArrayList<>(1)).add(new Surface(x, z, y, half, ceiling, ceiling == OPEN ? null : c));
                }
            }
            if (m == Material.POLISHED_ANDESITE && up1 == Material.WHITE_CONCRETE && (up2 == Material.YELLOW_TERRACOTTA || up2 == Material.SEA_LANTERN)
                    && up3 == Material.WHITE_CONCRETE) into.walls.computeIfAbsent(key(x, z), q -> new ArrayList<>(1)).add(y - 1);
            up3 = up2;
            up2 = up1;
            up1 = m;
        }
    }

    private static boolean clear(Material m) {
        return air(m) || m == Material.SNOW;
    }

    /** A lane column's bore raised: its surface block, its ceiling now (y), the ceiling it gets, whether a light is in it. */
    record Bore(int x, int z, int y, int from, int to, boolean light) {}

    /** What {@link #plan} found: the bores to raise, the tunnels' lane columns, those already high enough. */
    record Plan(List<Bore> bores, int lanes, int high) {}

    /**
     * Every tunnel lane column found (a lined wall near: {@link Found#wallNear}) whose ceiling is lower than a road builds
     * it now, but those skip says to leave (protected land, a saved road's own tunnel mended by it).
     */
    static Plan plan(Found f, LongPredicate skip) {
        List<Bore> out = new ArrayList<>();
        int lanes = 0, high = 0;
        for (List<Surface> l : f.surfaces.values()) {
            for (Surface s : l) {
                if (!s.tunnel() || !f.wallNear(s)) continue;
                if (skip.test(key(s.x(), s.z()))) continue;
                lanes++;
                int top = s.half();
                for (int dx = -REACH; dx <= REACH; dx++) {
                    for (int dz = -REACH; dz <= REACH; dz++) {
                        if (dx * dx + dz * dz > REACH * REACH) continue;
                        List<Surface> o = f.surfaces.get(key(s.x() + dx, s.z() + dz));
                        if (o == null) continue;
                        for (Surface t : o) if (Math.abs(t.y() - s.y()) <= STEP) top = Math.max(top, t.half());
                    }
                }
                int to = (int) Math.ceil(RoadSection.top(top) + RoadClearance.HEADROOM - 1e-9);
                if (to <= s.ceiling()) {
                    high++;
                    continue;
                }
                out.add(new Bore(s.x(), s.z(), s.y(), s.ceiling(), to, s.ceilingBlock() == Material.SEA_LANTERN));
            }
        }
        out.sort((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
        return new Plan(out, lanes, high);
    }

    /**
     * One bore raised (the chunks round it loaded): from its old ceiling up to the new one only the ground's and the
     * tunnel's own blocks go ({@link RoadSection#carvable}; water stays), the ceiling block goes on top, and round it
     * (the next columns) the rock is closed with stone bricks where open, from the old ceiling's height up: beside a lane
     * with a lower ceiling over that one's, beside the wall or the rock where it stands 4 over the road (never over a road
     * or a verge in front of a portal). targets: the new ceiling of every bore (by column). How many blocks changed.
     */
    static int bore(Site s, Bore b, Map<Long, Integer> targets, Found f) {
        if (s.protectedLand(b.x(), b.z())) return 0;
        int n = 0, from = b.y() + 5;
        for (int y = Math.max(from, b.from()); y < b.to(); y++) {
            Material m = s.get(b.x(), y, b.z());
            if (!air(m) && RoadSection.carvable(m)) {
                s.set(b.x(), y, b.z(), Material.AIR);
                n++;
            }
        }
        Material top = s.get(b.x(), b.to(), b.z());
        Material want = b.light() ? Material.SEA_LANTERN : Material.STONE_BRICKS;
        if (top != want && !ceilingBlock(top) && (RoadSection.carvable(top) || top == Material.WATER || top == Material.LAVA)) {
            s.set(b.x(), b.to(), b.z(), want);
            n++;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int x = b.x() + dx, z = b.z() + dz;
                if (s.protectedLand(x, z)) continue;
                Surface o = f.at(x, z, b.y(), 2);
                int low;
                if (o != null) {
                    if (!o.tunnel()) continue;   // open road: the portal's opening
                    Integer t = targets.get(key(x, z));
                    low = Math.max(o.ceiling(), t == null ? o.ceiling() : t) + 1;
                } else {
                    if (air(s.get(x, b.y() + 4, z))) continue;   // in front of a portal: nothing beside the road to close
                    low = from;
                }
                for (int y = low; y <= b.to(); y++) {
                    Material m = s.get(x, y, z);
                    if (air(m) || m == Material.WATER || m == Material.LAVA) {
                        s.set(x, y, z, Material.STONE_BRICKS);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** The bores' new ceilings by column. */
    static Map<Long, Integer> targets(List<Bore> bores) {
        Map<Long, Integer> out = new HashMap<>();
        for (Bore b : bores) out.merge(key(b.x(), b.z()), b.to(), Math::max);
        return out;
    }

    /** Where snow lies that may go, and why: on a saved road (0), in a dock's or terminal's chunks (1), on a road block (2). */
    record Snow(int y, int why) {}

    /**
     * The snow on a column to clear: in a saved road's window over its surface (spot: the saved road there, or null), in
     * a dock's chunks (dock) near the top, on a road block near the top (top: the highest block that stops movement,
     * snow lies over it).
     */
    static List<Snow> snow(Blocks b, int x, int z, int top, RoadNetwork.Spot spot, boolean dock, Set<Material> road) {
        List<Snow> out = new ArrayList<>(1);
        if (spot != null) for (int y = spot.y(); y <= spot.y() + 3; y++) if (snowy(b.get(x, y, z))) out.add(new Snow(y, 0));
        for (int y = top + 2; y >= top - 2; y--) {
            if (spot != null && y >= spot.y() && y <= spot.y() + 3) continue;
            if (!snowy(b.get(x, y, z))) continue;
            if (dock) out.add(new Snow(y, 1));
            else if (road.contains(b.get(x, y - 1, z))) out.add(new Snow(y, 2));
        }
        return out;
    }
}
