package dev.stsv.core.minigame.circuit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Everything a circuit is built of, in the track's own blocks (x, z from its centre, y = 0 the field's top: the world's
 * y is {@link #BASE_Y} higher). Pure (unit-tested; tools/assetgen/circuit_preview.py draws it from the JSON a test writes):
 * {@link TrackBuilder} only puts it into the world.
 *
 * <h2>The road</h2>
 * Swept along the centre line ({@link TrackPath}): across it, the road ({@code width}), then the edge (kerb stones in red and
 * white where it turns, a white line along straights), a shoulder, and a barrier of walls or fences (1.5 blocks: a kart
 * steps up a block, never a barrier). A cell takes the nearest road at its own level, so an overpass and the road under
 * it are two layers. Slopes are built in half blocks (slabs); a raised road stands on a bank, a {@code bridge:} part on
 * pillars (never on the road under it). The finish line is chequered, the grid painted behind it, and over the line
 * stands a chequered gantry with lamps and the track's name ({@link #labels}).
 *
 * <h2>On it</h2> ({@link TrackSpec.Feature}) a jump: a kicker that rises a block and a half, a striped launch pad, a canal
 * (water, or lava in the canyon) the kart flies over, the landing beyond; a boost pad: pink arrows across the road; a
 * tunnel: walls and a roof with lamps. Corners that turn hard get striped boards outside them, lamp posts stand along
 * both sides, a grandstand of spectators by the start.
 *
 * <h2>About it</h2> The field (its top block by a soft noise between the theme's ground blocks: {@link #ground}), props
 * placed by hand ({@code decor:}) and the theme's props strewn about it ({@code scatter:}), never within a few blocks of
 * the road. Fences, walls and bars are joined to their neighbours here (the builder places blocks without updates).
 */
public final class TrackLayout {

    /** The world's y of the field's top (y = 0 here). */
    public static final int BASE_Y = 64;
    /** How far the field reaches beyond the farthest road (blocks). */
    public static final int MARGIN = 36;
    /** Every prop kind a track may place (decor: kind). */
    public static final List<String> PROPS = List.of("house", "tree", "bigtree", "flowers", "hay", "well", "bush", "mesa", "cactus", "bones",
            "deadbush", "rock", "arch", "spike", "pine", "snowman", "igloo", "crystal", "mushroom", "log", "tower", "neon", "bench",
            "grandstand", "balloon", "windmill");

    /** A grid slot: where a kart starts (track coordinates, y = the road's top) and the way it faces. */
    public record Slot(double x, double y, double z, float yaw) {}

    /** A floating name: at (x, y, z), facing yaw (Minecraft), text (MiniMessage), this big. */
    public record Label(double x, double y, double z, float yaw, String text, float scale) {}

    /** Where along the line something is (sample indices from .. to, inclusive, along the lap). */
    public record Zone(TrackSpec.FeatureType type, int from, int to, double power) {}

    private final TrackSpec spec;
    private final TrackPath path;
    private final TrackTheme th;
    /** packed (x, y, z) -> block data */
    private final Map<Long, String> blocks = new LinkedHashMap<>();
    /** packed (x, z): near a road (no props) */
    private final Set<Long> reserved = new HashSet<>();
    private final List<Slot> grid = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Zone> zones = new ArrayList<>();
    private final int[] gates;
    private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, maxY = 0;
    private final double[] heights;
    private final boolean[] gap;
    private final Random random;

    /** One cell's nearest road, at one level. */
    private static final class Cell {
        int i;
        double d, t;
        double h;
    }

    public TrackLayout(TrackSpec spec) {
        this.spec = spec;
        this.path = spec.path();
        this.th = spec.theme();
        this.random = new Random(spec.id().hashCode() * 31L + 7);
        int n = path.size();
        heights = new double[n];
        gap = new boolean[n];
        for (int i = 0; i < n; i++) heights[i] = path.y(i);
        gates = new int[spec.checkpoints()];
        for (int k = 0; k < gates.length; k++) gates[k] = path.index(path.length() * k / gates.length);
        features();
        Map<Long, List<Cell>> cells = sweep();
        road(cells);
        decorations(cells);
        joinFences();
    }

    // ------------------------------------------------------------------------------------------
    // Features: jumps change the road's height and leave a gap
    // ------------------------------------------------------------------------------------------

    private int samples(double blocks) {
        return (int) Math.round(blocks / TrackPath.STEP);
    }

    private void features() {
        for (TrackSpec.Feature f : spec.features()) {
            int at = path.index(path.length() * f.at()), to = path.index(path.length() * f.to());
            switch (f.type()) {
                case JUMP -> {
                    // the kicker: up a block and a half over the 9 blocks before the lip, then the gap, then the landing
                    int ramp = samples(9);
                    for (int k = 0; k <= ramp; k++) heights[path.wrap(at - ramp + k)] += Math.round(1.5 * k / ramp * 2) / 2.0;
                    int g = samples(f.gap());
                    for (int k = 1; k <= g; k++) gap[path.wrap(at + k)] = true;
                    zones.add(new Zone(TrackSpec.FeatureType.JUMP, path.wrap(at - samples(2.5)), at, f.power()));
                }
                case BOOST -> zones.add(new Zone(TrackSpec.FeatureType.BOOST, at, path.wrap(at + samples(4)), f.power()));
                case TUNNEL -> zones.add(new Zone(TrackSpec.FeatureType.TUNNEL, at, to, 0));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The sweep: every cell's nearest road, by level
    // ------------------------------------------------------------------------------------------

    private Map<Long, List<Cell>> sweep() {
        Map<Long, List<Cell>> cells = new HashMap<>();
        for (int i = 0; i < path.size(); i++) {
            double half = path.width(i) / 2, reach = half + 3.6;
            double rx = -path.dirZ(i), rz = path.dirX(i);
            for (double t = -reach; t <= reach; t += 0.3) {
                double px = path.x(i) + rx * t, pz = path.z(i) + rz * t;
                int cx = (int) Math.floor(px), cz = (int) Math.floor(pz);
                long key = key2(cx, cz);
                List<Cell> list = cells.computeIfAbsent(key, k -> new ArrayList<>(1));
                Cell same = null;
                for (Cell c : list) if (Math.abs(c.h - heights[i]) <= 2.5) same = c;
                double d = Math.abs(t);
                if (same == null) {
                    same = new Cell();
                    same.d = Double.MAX_VALUE;
                    list.add(same);
                }
                if (d < same.d) {
                    same.d = d;
                    same.t = t;
                    same.i = i;
                    same.h = heights[i];
                }
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------------------------------
    // The road, its edges, barrier, banks and bridges, the finish line and the grid
    // ------------------------------------------------------------------------------------------

    private boolean in(int i, Zone z) {
        return z.from() <= z.to() ? i >= z.from() && i <= z.to() : i >= z.from() || i <= z.to();
    }

    private Zone zone(int i, TrackSpec.FeatureType type) {
        for (Zone z : zones) if (z.type() == type && in(i, z)) return z;
        return null;
    }

    private void road(Map<Long, List<Cell>> cells) {
        double finishHalf = 1.0;
        for (Map.Entry<Long, List<Cell>> e : cells.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey().intValue();
            for (Cell c : e.getValue()) {
                double half = path.width(c.i) / 2;
                double H = c.h;
                int top = (int) Math.floor(H);
                boolean slab = H - top > 0.25;
                boolean lower = false;
                for (Cell o : e.getValue()) if (o != c && o.h < c.h) lower = true;
                reserve(cx, cz, half + 6 - c.d);
                if (gap[c.i]) {
                    // a jump's canal: no road, no ground, a liquid two blocks down
                    if (c.d <= half + 4) {
                        put(cx, -1, cz, "minecraft:air");
                        put(cx, -2, cz, th.pit());
                        put(cx, -3, cz, th.fill());
                    }
                    continue;
                }
                String surface, surfaceSlab;
                double s = path.s(c.i);
                double fromLine = Math.min(s, path.length() - s);   // blocks from the finish line, either way
                boolean behind = s > path.length() / 2;
                if (c.d <= half) {
                    surface = th.road();
                    surfaceSlab = th.roadSlab();
                    if (fromLine < finishHalf) {
                        // the chequered finish line: two rows of squares
                        int a = (int) Math.floor((c.t + half) / 1.0), b = (int) Math.floor((behind ? -fromLine : fromLine) + finishHalf);
                        surface = (a + b) % 2 == 0 ? "minecraft:white_concrete" : "minecraft:black_concrete";
                    } else if (c.d < 0.55 && ((int) Math.floor(s / 3)) % 2 == 0 && !(behind && fromLine < 40)) {
                        surface = th.line();
                        surfaceSlab = th.lineSlab();
                    }
                    Zone boost = zone(c.i, TrackSpec.FeatureType.BOOST);
                    if (boost != null && c.d <= half - 1.2) {
                        surface = "minecraft:magenta_glazed_terracotta[facing=" + facingAgainst(c.i) + "]";
                        surfaceSlab = null;
                    }
                    Zone jump = zone(c.i, TrackSpec.FeatureType.JUMP);
                    if (jump != null && c.d <= half) {
                        surface = ((int) Math.floor(c.t + half)) % 2 == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete";
                        surfaceSlab = null;
                    }
                } else if (c.d <= half + 1) {
                    boolean corner = Math.abs(path.curvature(c.i)) > 1.6;
                    if (corner) {
                        surface = th.kerb().get(((int) Math.floor(s / 1.5)) % th.kerb().size());
                        surfaceSlab = null;
                    } else {
                        surface = th.edge();
                        surfaceSlab = null;
                    }
                } else {
                    surface = th.shoulder();
                    surfaceSlab = null;
                }
                boolean wallCell = c.d > half + 2;
                // the surface: a full block with its top at H, or a slab on a block (H half way)
                if (slab && !wallCell) {
                    put(cx, top, cz, surfaceSlab != null ? surfaceSlab : th.roadSlab());
                    put(cx, top - 1, cz, solidUnder(surface));
                } else if (slab) {
                    put(cx, top, cz, th.fill());
                    put(cx, top - 1, cz, th.fill());
                } else {
                    put(cx, top - 1, cz, surface);
                }
                int bottom = slab ? top - 1 : top - 1;
                // under it: a bank down to the field, or a bridge's pillars, or nothing (on the field)
                if (path.bridge(c.i)) {
                    boolean pillar = ((int) Math.floor(path.s(c.i) / 8)) % 4 == 0 && Math.abs(c.d - (half - 1)) < 0.6 && !lower;
                    if (pillar) for (int y = -1; y < bottom; y++) put(cx, y, cz, th.pillar());
                    else if (bottom > 0) put(cx, bottom - 1, cz, th.fill());   // the deck's underside
                } else {
                    for (int y = 0; y < bottom; y++) put(cx, y, cz, th.fill());
                }
                // the barrier: walls or fences on top, in turn every two blocks along the lap
                Zone tunnel = zone(c.i, TrackSpec.FeatureType.TUNNEL);
                int up = slab ? top + 1 : top;
                if (wallCell && !path.bridge(c.i)) {
                    // the barrier stands on the highest road near it (a slope beside it: never lower than the road)
                    double hw = c.h;
                    for (int k = -8; k <= 8; k++) hw = Math.max(hw, heights[path.wrap(c.i + k)]);
                    int base = (int) Math.ceil(hw);
                    for (int y = up; y < base; y++) put(cx, y, cz, th.fill());
                    up = Math.max(up, base);
                } else if (wallCell) {
                    double hw = c.h;
                    for (int k = -8; k <= 8; k++) hw = Math.max(hw, heights[path.wrap(c.i + k)]);
                    up = Math.max(up, (int) Math.ceil(hw));
                }
                if (wallCell) {
                    if (tunnel != null) {
                        for (int y = up; y < up + 5; y++) put(cx, y, cz, th.tunnel());
                    } else {
                        put(cx, up, cz, th.walls().get(((int) Math.floor(s / 2)) % th.walls().size()));
                        // striped boards behind the barrier where the road turns hard (outside the corner)
                        double k = path.curvature(c.i);
                        if (Math.abs(k) > 3.2 && Math.signum(c.t) == -Math.signum(k) && c.d > half + 2.6)
                            put(cx, up + 1, cz, th.hazard().get(((int) Math.floor(s / 1.5)) % th.hazard().size()));
                    }
                } else {
                    for (int y = up; y < up + 4; y++) clear(cx, y, cz);
                }
                if (tunnel != null) {
                    put(cx, up + 5, cz, th.tunnel());
                    if (c.d < 0.6 && ((int) Math.floor(s)) % 6 == 0) put(cx, up + 4, cz, th.tunnelLamp());
                    for (int y = up; y < up + 4; y++) if (!wallCell) clear(cx, y, cz);
                }
                maxY = Math.max(maxY, up + 6);
                bound(cx, cz);
            }
        }
        paintGrid();
        gantry();
        lamps(cells);
    }

    /** A road made of glazed or chequered blocks keeps the road's block under it. */
    private String solidUnder(String surface) {
        return surface.contains("slab") ? th.fill() : th.road();
    }

    /** The facing a glazed terracotta arrow needs to point the way the road runs (its arrow points against its facing). */
    private String facingAgainst(int i) {
        double dx = path.dirX(i), dz = path.dirZ(i);
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? "west" : "east";
        return dz > 0 ? "north" : "south";
    }

    private void paintGrid() {
        // two columns, staggered, behind the line: slot k at 6 + 3.5 k blocks back
        for (int k = 0; k < 12; k++) {
            int side = k % 2;
            double back = 6 + 3.5 * k;
            int i = path.index(path.length() - back);
            double half = path.width(i) / 2, lat = (side == 0 ? -1 : 1) * Math.min(half - 1.6, half * 0.45);
            double x = path.x(i) - path.dirZ(i) * lat, z = path.z(i) + path.dirX(i) * lat;
            grid.add(new Slot(x, heights[i], z, path.yaw(i)));
            // the slot's front mark: a short white bar across, a block ahead of the kart's nose
            int front = path.index(path.length() - back + 1.8);
            for (double t = -1.2; t <= 1.2; t += 0.4) {
                double fx = path.x(front) - path.dirZ(front) * (lat + t), fz = path.z(front) + path.dirX(front) * (lat + t);
                if (heights[front] == Math.floor(heights[front])) put((int) Math.floor(fx), (int) heights[front] - 1, (int) Math.floor(fz), "minecraft:white_concrete");
            }
        }
    }

    private void gantry() {
        int i = 0;
        double half = path.width(i) / 2 + 3.5;
        int top = (int) Math.floor(heights[i]);
        double rx = -path.dirZ(i), rz = path.dirX(i);
        int height = 8;
        for (int side : new int[]{-1, 1}) {
            for (double a = -0.5; a <= 0.5; a += 0.5) {
                double px = path.x(i) + rx * side * half + path.dirX(i) * a, pz = path.z(i) + rz * side * half + path.dirZ(i) * a;
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                for (int y = top; y < top + height; y++) put(bx, y, bz, "minecraft:polished_blackstone_bricks");
                put(bx, top + height, bz, "minecraft:chiseled_polished_blackstone");
                put(bx, top - 1, bz, "minecraft:polished_blackstone_bricks");
            }
        }
        // the beam: two rows of chequered squares, lamps under it
        for (double t = -half; t <= half; t += 0.3) {
            for (double a = -0.5; a <= 0.5; a += 0.5) {
                double px = path.x(i) + rx * t + path.dirX(i) * a, pz = path.z(i) + rz * t + path.dirZ(i) * a;
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                int col = (int) Math.floor(t + half);
                put(bx, top + height - 1, bz, col % 2 == 0 ? "minecraft:white_concrete" : "minecraft:black_concrete");
                put(bx, top + height, bz, col % 2 == 0 ? "minecraft:black_concrete" : "minecraft:white_concrete");
                if (col % 3 == 1 && Math.abs(a) < 0.1) put(bx, top + height - 2, bz, "minecraft:redstone_lamp[lit=true]");
            }
        }
        labels.add(new Label(path.x(i), top + height + 2.2, path.z(i), path.yaw(i) + 180, "<b><#ffd36b>" + spec.name(), 3.2f));
        labels.add(new Label(path.x(i), top + height + 2.2, path.z(i), path.yaw(i), "<b><#ffd36b>" + spec.name(), 3.2f));
        labels.add(new Label(path.x(i), top + height + 1.4, path.z(i), path.yaw(i) + 180, "<white>START · FINISH", 1.6f));
    }

    private void lamps(Map<Long, List<Cell>> cells) {
        for (int i = 0; i < path.size(); i += samples(14)) {
            if (gap[i] || zone(i, TrackSpec.FeatureType.TUNNEL) != null) continue;
            double half = path.width(i) / 2 + 4;
            for (int side : new int[]{-1, 1}) {
                double px = path.x(i) - path.dirZ(i) * side * half, pz = path.z(i) + path.dirX(i) * side * half;
                int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
                if (roadNear(cells, bx, bz, heights[i])) continue;
                int top = (int) Math.ceil(heights[i]);
                if (top > 0 && !path.bridge(i)) for (int y = 0; y < top; y++) put(bx, y, bz, th.fill());
                if (path.bridge(i) && top > 0) continue;
                taken.add(key2(bx, bz));
                for (int y = top; y < top + 4; y++) put(bx, y, bz, th.lampPost());
                put(bx, top + 4, bz, th.lampHead());
                put(bx, top + 5, bz, "minecraft:light[level=15]");
            }
        }
        // the grandstand by the start, on the road's right
        int i = path.index(36);
        double half = path.width(i) / 2 + 6;
        double px = path.x(i) - path.dirZ(i) * half, pz = path.z(i) + path.dirX(i) * half;
        grandstand((int) Math.round(px), (int) Math.round(pz), yawOut(i, 1), 22);
    }

    /** The yaw (0, 90, 180, 270) facing from the road's side back towards it (side 1 = right). */
    private int yawOut(int i, int side) {
        double fx = path.dirZ(i) * side, fz = -path.dirX(i) * side;   // towards the road from its right side
        if (Math.abs(fx) > Math.abs(fz)) return fx > 0 ? 90 : 270;
        return fz < 0 ? 0 : 180;
    }

    private boolean roadNear(Map<Long, List<Cell>> cells, int x, int z, double h) {
        List<Cell> list = cells.get(key2(x, z));
        if (list == null) return false;
        for (Cell c : list) if (c.d <= path.width(c.i) / 2 + 3.6) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // The field's props
    // ------------------------------------------------------------------------------------------

    private void decorations(Map<Long, List<Cell>> cells) {
        minX -= MARGIN;
        maxX += MARGIN;
        minZ -= MARGIN;
        maxZ += MARGIN;
        for (TrackSpec.Decor d : spec.decor()) prop(d.kind(), d.x(), d.z(), d.yaw(), d.size(), true);
        List<String> kinds = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(th.props()).entrySet()) for (int k = 0; k < e.getValue(); k++) kinds.add(e.getKey());
        int placed = 0, tries = 0;
        while (placed < spec.scatter() && tries++ < spec.scatter() * 30) {
            int x = minX + 3 + random.nextInt(maxX - minX - 6), z = minZ + 3 + random.nextInt(maxZ - minZ - 6);
            String kind = kinds.get(random.nextInt(kinds.size()));
            if (prop(kind, x, z, 90 * random.nextInt(4), 1 + random.nextInt(3), false)) placed++;
        }
    }

    /** Footprint radius of a prop kind (blocks). */
    private static int radius(String kind, int size) {
        return switch (kind) {
            case "house" -> 5 + size;
            case "grandstand" -> 12;
            case "tower" -> 5 + size;
            case "igloo", "mesa", "arch", "windmill" -> 5 + size;
            case "bigtree", "well", "mushroom" -> 4 + size;
            case "flowers", "deadbush", "bones", "bush" -> 2;
            default -> 2 + size;
        };
    }

    private final Set<Long> taken = new HashSet<>();
    /** hand-placed props that did not fit (too near a road): the test lists them */
    private final List<String> skipped = new ArrayList<>();

    /** The hand-placed props ({@code decor:}) left out because they would stand on or by the road. */
    public List<String> skippedDecor() {
        return List.copyOf(skipped);
    }

    private boolean prop(String kind, int x, int z, int yaw, int size, boolean forced) {
        int r = radius(kind, size);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                long k = key2(x + dx, z + dz);
                if (reserved.contains(k) || (!forced && taken.contains(k))) {
                    if (forced) skipped.add(kind + " (" + x + ", " + z + ")");
                    return false;
                }
            }
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) taken.add(key2(x + dx, z + dz));
        Props p = new Props(x, z, yaw);
        switch (kind) {
            case "house" -> p.house(size);
            case "tree" -> p.tree(size, false);
            case "bigtree" -> p.tree(size + 2, true);
            case "flowers" -> p.flowers();
            case "hay" -> p.hay();
            case "well" -> p.well();
            case "bush" -> p.bush(size);
            case "mesa" -> p.mesa(size);
            case "cactus" -> p.cactus(size);
            case "bones" -> p.bones();
            case "deadbush" -> p.scatterPlant(List.of("minecraft:dead_bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass"));
            case "rock" -> p.rock(size);
            case "arch" -> p.arch(size);
            case "spike" -> p.spike(size);
            case "pine" -> p.pine(size);
            case "snowman" -> p.snowman();
            case "igloo" -> p.igloo();
            case "crystal" -> p.crystal(size);
            case "mushroom" -> p.mushroom(size);
            case "log" -> p.log(size);
            case "tower" -> p.tower(size);
            case "neon" -> p.neon(size);
            case "bench" -> p.bench();
            case "grandstand" -> grandstand(x, z, yaw, 14 + 4 * size);
            case "balloon" -> p.balloon(size);
            case "windmill" -> p.windmill(size);
            default -> throw new IllegalArgumentException(kind);
        }
        return true;
    }

    /** Stepped seats with a crowd and a striped roof, facing yaw (0 = towards -z ... as props), length blocks long. */
    private void grandstand(int x, int z, int yaw, int length) {
        for (int dx = -length / 2 - 6; dx <= length / 2 + 6; dx++) for (int dz = -length / 2 - 6; dz <= length / 2 + 6; dz++) taken.add(key2(x + dx, z + dz));
        Props p = new Props(x, z, yaw);
        String[] crowd = {"minecraft:red_wool", "minecraft:yellow_wool", "minecraft:blue_wool", "minecraft:lime_wool", "minecraft:orange_wool",
                "minecraft:white_wool", "minecraft:pink_wool", "minecraft:cyan_wool", "minecraft:purple_wool"};
        int h = length / 2;
        for (int a = -h; a <= h; a++) {
            for (int row = 0; row < 5; row++) {
                // row 0 nearest the road (local -z is towards it)
                for (int y = 0; y <= row; y++) p.set(a, y, row, "minecraft:smooth_stone");
                p.set(a, row + 1, row, "minecraft:spruce_stairs[facing=south]");
                if (Math.abs(a) < h && random.nextInt(10) < 6) p.set(a, row + 2, row, crowd[random.nextInt(crowd.length)]);
            }
            for (int y = 0; y <= 9; y++) if (Math.abs(a) == h || a % 6 == 0) p.set(a, y, 5, "minecraft:spruce_log");
            for (int row = -1; row <= 5; row++) p.set(a, 9, row, (a + 100) % 4 < 2 ? "minecraft:red_concrete" : "minecraft:white_concrete");
            p.set(a, 0, -1, "minecraft:spruce_fence");
        }
    }

    /** Props are drawn in their own frame: (a, y, b) with b towards the back, turned by yaw about (x, z). */
    private final class Props {
        final int x, z, yaw;

        Props(int x, int z, int yaw) {
            this.x = x;
            this.z = z;
            this.yaw = yaw;
        }

        void set(int a, int y, int b, String data) {
            int dx, dz;
            switch (yaw) {
                case 90 -> { dx = -b; dz = a; }
                case 180 -> { dx = -a; dz = -b; }
                case 270 -> { dx = b; dz = -a; }
                default -> { dx = a; dz = b; }
            }
            put(x + dx, y, z + dz, turn(data));
            maxY = Math.max(maxY, y + 1);
        }

        /** Turns a block's facing with the prop (stairs and the like are written facing their prop's frame). */
        String turn(String data) {
            if (yaw == 0 || !data.contains("facing=")) return data;
            String[] dirs = {"north", "east", "south", "west"};
            int steps = yaw / 90;
            for (int k = 0; k < 4; k++) {
                String f = "facing=" + dirs[k];
                if (data.contains(f)) return data.replace(f, "facing=" + dirs[(k + steps) % 4]);
            }
            return data;
        }

        void house(int size) {
            String[] walls = {"minecraft:white_terracotta", "minecraft:smooth_sandstone", "minecraft:birch_planks", "minecraft:light_gray_terracotta"};
            String[] roofs = {"minecraft:red_nether_bricks", "minecraft:dark_oak_planks", "minecraft:deepslate_tiles", "minecraft:bricks"};
            String[] roofStairs = {"minecraft:red_nether_brick_stairs", "minecraft:dark_oak_stairs", "minecraft:deepslate_tile_stairs", "minecraft:brick_stairs"};
            int style = random.nextInt(walls.length);
            int w = 3 + size, d = 3 + size, hgt = 4;
            for (int a = -w; a <= w; a++) for (int b = -d; b <= d; b++) {
                boolean edge = Math.abs(a) == w || Math.abs(b) == d;
                set(a, -1, b, "minecraft:stone_bricks");
                for (int y = 0; y < hgt; y++) {
                    boolean corner = Math.abs(a) == w && Math.abs(b) == d;
                    if (corner) set(a, y, b, "minecraft:stripped_spruce_log");
                    else if (edge) {
                        boolean window = y == 1 || y == 2;
                        boolean door = b == -d && a == 0 && y < 2;
                        if (door) set(a, y, b, y == 0 ? "minecraft:spruce_door[half=lower,facing=north]" : "minecraft:spruce_door[half=upper,facing=north]");
                        else if (window && (Math.abs(a) % 3 == 1) && Math.abs(b) == d) set(a, y, b, "minecraft:glass_pane");
                        else if (window && (Math.abs(b) % 3 == 1) && Math.abs(a) == w) set(a, y, b, "minecraft:glass_pane");
                        else set(a, y, b, walls[style]);
                    }
                }
                if (Math.abs(b) == d && Math.abs(a) % 3 == 1 && Math.abs(a) < w) set(a, 0, b + (b < 0 ? -1 : 1), "minecraft:flower_pot");
            }
            set(0, 2, -d - 1, "minecraft:lantern");
            // a pitched roof along a
            for (int k = 0; k <= d + 1; k++) {
                int y = hgt + k;
                for (int a = -w - 1; a <= w + 1; a++) {
                    if (k < d + 1) {
                        set(a, y, -d - 1 + k, roofStairs[style] + "[facing=south]");
                        set(a, y, d + 1 - k, roofStairs[style] + "[facing=north]");
                    } else set(a, y, 0, roofs[style]);
                }
                if (k < d + 1) for (int b = -d + k; b <= d - k; b++) for (int a : new int[]{-w, w}) set(a, y, b, walls[style]);
            }
            set(w - 1, hgt + d + 1, 1, "minecraft:bricks");
            set(w - 1, hgt + d + 2, 1, "minecraft:bricks");
            set(w - 1, hgt + d + 3, 1, "minecraft:campfire[lit=false]");
        }

        void tree(int size, boolean big) {
            String log, leaves;
            switch (th.id()) {
                case "forest" -> {
                    int k = random.nextInt(3);
                    log = k == 0 ? "minecraft:dark_oak_log" : k == 1 ? "minecraft:oak_log" : "minecraft:birch_log";
                    leaves = k == 0 ? "minecraft:dark_oak_leaves[persistent=true]" : k == 1 ? "minecraft:oak_leaves[persistent=true]" : "minecraft:birch_leaves[persistent=true]";
                }
                case "city" -> {
                    log = "minecraft:cherry_log";
                    leaves = "minecraft:cherry_leaves[persistent=true]";
                }
                default -> {
                    boolean cherry = random.nextInt(4) == 0;
                    log = cherry ? "minecraft:cherry_log" : "minecraft:oak_log";
                    leaves = cherry ? "minecraft:cherry_leaves[persistent=true]" : "minecraft:oak_leaves[persistent=true]";
                }
            }
            int h = 4 + size + (big ? 3 : 0), r = big ? 2 + size / 2 + 1 : 2;
            for (int y = 0; y < h; y++) {
                set(0, y, 0, log);
                if (big && y < 2) for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) set(o[0], y, o[1], log);
            }
            for (int y = h - r - 1; y <= h + 1; y++) {
                int rr = y > h ? r - 1 : r;
                for (int a = -rr; a <= rr; a++) for (int b = -rr; b <= rr; b++) {
                    double dist = Math.hypot(a, b) + Math.abs(y - (h - 0.5)) * 0.6;
                    if (dist <= rr + 0.3 && !(a == 0 && b == 0 && y < h) && random.nextInt(9) > 0) set(a, y, b, leaves);
                }
            }
        }

        void flowers() {
            String[] f = {"minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower", "minecraft:oxeye_daisy", "minecraft:allium",
                    "minecraft:azure_bluet", "minecraft:pink_tulip", "minecraft:orange_tulip", "minecraft:lily_of_the_valley"};
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) if (random.nextInt(3) > 0) set(a, 0, b, f[random.nextInt(f.length)]);
        }

        void scatterPlant(List<String> plants) {
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if (random.nextInt(3) == 0) set(a, 0, b, plants.get(random.nextInt(plants.size())));
        }

        void hay() {
            set(0, 0, 0, "minecraft:hay_block");
            set(1, 0, 0, "minecraft:hay_block");
            set(0, 1, 0, "minecraft:hay_block");
            set(0, 0, 1, "minecraft:hay_block");
        }

        void well() {
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
                boolean rim = Math.abs(a) == 2 || Math.abs(b) == 2;
                set(a, 0, b, rim ? "minecraft:cobblestone" : "minecraft:water");
                set(a, -1, b, "minecraft:cobblestone");
            }
            for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) for (int y = 1; y < 4; y++) set(c[0], y, c[1], "minecraft:oak_fence");
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) set(a, 4, b, "minecraft:spruce_slab[type=bottom]");
            set(0, 3, 0, "minecraft:lantern[hanging=true]");
        }

        void bush(int size) {
            String leaves = th.id().equals("forest") ? "minecraft:azalea_leaves[persistent=true]" : "minecraft:oak_leaves[persistent=true]";
            for (int a = -size; a <= size; a++) for (int b = -size; b <= size; b++) for (int y = 0; y <= size / 2; y++)
                if (Math.hypot(a, b) + y <= size + 0.4 && random.nextInt(6) > 0) set(a, y, b, random.nextInt(5) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : leaves);
        }

        void mesa(int size) {
            String[] layers = {"minecraft:terracotta", "minecraft:orange_terracotta", "minecraft:yellow_terracotta", "minecraft:brown_terracotta",
                    "minecraft:red_terracotta", "minecraft:white_terracotta", "minecraft:light_gray_terracotta"};
            int r = 3 + size, h = 7 + size * 4 + random.nextInt(5);
            for (int y = 0; y < h; y++) {
                double rr = r - y * 0.12 + Math.sin(y * 0.9) * 0.4;
                for (int a = -r - 1; a <= r + 1; a++) for (int b = -r - 1; b <= r + 1; b++)
                    if (Math.hypot(a * 1.1, b) <= rr) set(a, y, b, layers[(y / 2) % layers.length]);
            }
        }

        void cactus(int size) {
            int h = 2 + size;
            for (int y = 0; y < h; y++) set(0, y, 0, "minecraft:cactus");
            if (size >= 2) {
                set(0, h, 0, "minecraft:cactus_flower");
            }
            set(1, 0, 1, "minecraft:dead_bush");
        }

        void bones() {
            set(0, 0, 0, "minecraft:bone_block[axis=x]");
            set(1, 0, 0, "minecraft:bone_block[axis=x]");
            set(2, 0, 0, "minecraft:bone_block[axis=x]");
            set(1, 1, 0, "minecraft:bone_block[axis=y]");
            set(0, 0, 1, "minecraft:bone_block[axis=z]");
        }

        void rock(int size) {
            String[] r = switch (th.id()) {
                case "canyon" -> new String[]{"minecraft:red_sandstone", "minecraft:terracotta", "minecraft:smooth_red_sandstone"};
                case "glacier" -> new String[]{"minecraft:packed_ice", "minecraft:blue_ice", "minecraft:snow_block"};
                default -> new String[]{"minecraft:mossy_cobblestone", "minecraft:stone", "minecraft:andesite", "minecraft:moss_block"};
            };
            for (int a = -size; a <= size; a++) for (int b = -size; b <= size; b++) for (int y = 0; y <= size; y++)
                if (Math.hypot(a, b) + y * 0.9 <= size + 0.5 && random.nextInt(7) > 0) set(a, y, b, r[random.nextInt(r.length)]);
        }

        void arch(int size) {
            int span = 5 + size, h = 8 + size * 2;
            for (int a = -span; a <= span; a++) {
                double top = h - (1 - Math.pow(a / (double) span, 2)) * (h - 3);
                for (int y = 0; y < h + 2; y++) {
                    boolean leg = Math.abs(a) >= span - 1;
                    if (leg || y >= top) for (int b = -1; b <= 1; b++) set(a, y, b, ((y / 2) % 2 == 0) ? "minecraft:orange_terracotta" : "minecraft:red_sandstone");
                }
            }
        }

        void spike(int size) {
            int h = 6 + size * 4 + random.nextInt(4);
            for (int y = 0; y < h; y++) {
                int r = y < h / 3 ? 1 : 0;
                for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) if (Math.abs(a) + Math.abs(b) <= r) set(a, y, b, "minecraft:packed_ice");
            }
            set(0, h, 0, "minecraft:blue_ice");
        }

        void pine(int size) {
            int h = 6 + size * 2;
            for (int y = 0; y < h; y++) set(0, y, 0, "minecraft:spruce_log");
            for (int y = 2; y <= h; y++) {
                int r = Math.max(0, (h - y) / 2 + ((y % 2 == 0) ? 1 : 0));
                r = Math.min(r, 3);
                for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
                    if (Math.abs(a) + Math.abs(b) > r + 1 || (a == 0 && b == 0 && y < h)) continue;
                    set(a, y, b, "minecraft:spruce_leaves[persistent=true]");
                    if (th.id().equals("glacier") && y < h) set(a, y + 1, b, "minecraft:snow[layers=1]");
                }
            }
            set(0, h + 1, 0, "minecraft:spruce_leaves[persistent=true]");
        }

        void snowman() {
            set(0, 0, 0, "minecraft:snow_block");
            set(0, 1, 0, "minecraft:snow_block");
            set(0, 2, 0, "minecraft:carved_pumpkin[facing=north]");
            set(-1, 1, 0, "minecraft:spruce_fence");
            set(1, 1, 0, "minecraft:spruce_fence");
        }

        void igloo() {
            int r = 4;
            for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) for (int y = 0; y <= r; y++) {
                double d = Math.sqrt(a * a + b * b + y * y * 1.4);
                if (d <= r + 0.4 && d > r - 0.7) set(a, y, b, y % 2 == 0 ? "minecraft:snow_block" : "minecraft:packed_ice");
            }
            for (int y = 0; y < 2; y++) set(0, y, -r, "minecraft:air");
            set(0, 0, 0, "minecraft:light[level=12]");
            set(0, 2, -r, "minecraft:lantern");
        }

        void crystal(int size) {
            int h = 3 + size * 2;
            for (int y = 0; y < h; y++) set(0, y, 0, y < h - 1 ? "minecraft:light_blue_stained_glass" : "minecraft:sea_lantern");
            set(1, 0, 0, "minecraft:blue_stained_glass");
            set(0, 0, 1, "minecraft:cyan_stained_glass");
            set(1, 1, 1, "minecraft:light_blue_stained_glass");
            set(0, 1, -1, "minecraft:light[level=10]");
        }

        void mushroom(int size) {
            boolean red = random.nextBoolean();
            int h = 4 + size * 2, r = 2 + size;
            for (int y = 0; y < h; y++) set(0, y, 0, "minecraft:mushroom_stem");
            String cap = red ? "minecraft:red_mushroom_block" : "minecraft:brown_mushroom_block";
            for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
                if (Math.abs(a) == r && Math.abs(b) == r) continue;
                set(a, h, b, cap);
                if ((Math.abs(a) == r || Math.abs(b) == r) && red) set(a, h - 1, b, cap);
            }
            set(0, h - 1, 0, "minecraft:shroomlight");
        }

        void log(int size) {
            String l = random.nextBoolean() ? "minecraft:oak_log[axis=x]" : "minecraft:birch_log[axis=x]";
            for (int a = -size - 1; a <= size + 1; a++) set(a, 0, 0, l);
            set(-size - 1, 1, 0, "minecraft:moss_carpet");
            set(0, 1, 0, "minecraft:brown_mushroom");
            set(size, 1, 0, "minecraft:moss_carpet");
        }

        void tower(int size) {
            String[] walls = {"minecraft:gray_concrete", "minecraft:black_concrete", "minecraft:light_gray_concrete", "minecraft:cyan_terracotta", "minecraft:blue_terracotta"};
            String[] lit = {"minecraft:yellow_stained_glass", "minecraft:orange_stained_glass", "minecraft:light_blue_stained_glass", "minecraft:white_stained_glass"};
            String wall = walls[random.nextInt(walls.length)];
            int w = 3 + size, h = 18 + size * 10 + random.nextInt(14);
            for (int y = 0; y < h; y++) {
                for (int a = -w; a <= w; a++) for (int b = -w; b <= w; b++) {
                    boolean edge = Math.abs(a) == w || Math.abs(b) == w;
                    if (!edge) {
                        if (y % 4 == 0) set(a, y, b, wall);
                        continue;
                    }
                    boolean corner = Math.abs(a) == w && Math.abs(b) == w;
                    boolean window = !corner && y % 4 != 0 && y % 4 != 3 && (a + b + 100) % 2 == 0;
                    set(a, y, b, window ? (random.nextInt(5) < 3 ? lit[random.nextInt(lit.length)] : "minecraft:black_stained_glass") : wall);
                }
                if (y % 4 == 1) set(0, y, 0, "minecraft:light[level=15]");
            }
            for (int a = -w; a <= w; a++) for (int b = -w; b <= w; b++) set(a, h, b, "minecraft:polished_blackstone_slab[type=bottom]");
            set(0, h + 1, 0, "minecraft:iron_bars");
            set(0, h + 2, 0, "minecraft:iron_bars");
            set(0, h + 3, 0, "minecraft:redstone_lamp[lit=true]");
        }

        void neon(int size) {
            String[] neon = {"minecraft:magenta_concrete", "minecraft:cyan_concrete", "minecraft:lime_concrete", "minecraft:pink_concrete", "minecraft:yellow_concrete"};
            String c = neon[random.nextInt(neon.length)];
            int w = 2 + size, h = 6 + size * 2;
            for (int y = 0; y < h - 3; y++) set(0, y, 0, "minecraft:iron_bars");
            for (int a = -w; a <= w; a++) for (int y = h - 3; y <= h; y++) {
                boolean frame = Math.abs(a) == w || y == h - 3 || y == h;
                set(a, y, 0, frame ? "minecraft:sea_lantern" : (a + y) % 3 == 0 ? "minecraft:shroomlight" : c);
            }
            set(0, h + 1, 0, "minecraft:light[level=15]");
        }

        void bench() {
            set(-1, 0, 0, "minecraft:oak_stairs[facing=south]");
            set(0, 0, 0, "minecraft:oak_stairs[facing=south]");
            set(1, 0, 0, "minecraft:oak_stairs[facing=south]");
            set(2, 0, 0, "minecraft:iron_bars");
            set(2, 1, 0, "minecraft:iron_bars");
            set(2, 2, 0, "minecraft:lantern");
        }

        void balloon(int size) {
            String[] cols = {"minecraft:red_wool", "minecraft:yellow_wool", "minecraft:light_blue_wool", "minecraft:lime_wool", "minecraft:white_wool"};
            int base = 14 + size * 4, r = 3 + size;
            for (int y = -r; y <= r + 1; y++) for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) {
                double d = Math.sqrt(a * a + b * b + (y > 0 ? y * y * 0.8 : y * y * 1.3));
                if (d <= r + 0.3) set(a, base + y, b, cols[Math.floorMod(a + b, cols.length)]);
            }
            for (int y = base - r - 4; y < base - r; y++) for (int[] c : new int[][]{{-1, -1}, {1, 1}}) set(c[0], y, c[1], "minecraft:iron_chain");
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) set(a, base - r - 5, b, "minecraft:barrel");
        }

        void windmill(int size) {
            int h = 10 + size * 2;
            for (int y = 0; y < h; y++) {
                int r = y < h / 2 ? 2 : 1;
                for (int a = -r; a <= r; a++) for (int b = -r; b <= r; b++) if (Math.abs(a) == r || Math.abs(b) == r) set(a, y, b, "minecraft:white_terracotta");
            }
            for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) set(a, h, b, "minecraft:red_nether_bricks");
            for (int k = -6; k <= 6; k++) {
                set(k, h - 1, -3, "minecraft:white_wool");
                set(0, h - 1 + k, -3, "minecraft:white_wool");
            }
            set(0, h - 1, -3, "minecraft:spruce_planks");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fences, walls and bars joined to their neighbours
    // ------------------------------------------------------------------------------------------

    private static String family(String data) {
        if (data == null) return null;
        String n = data.contains("[") ? data.substring(0, data.indexOf('[')) : data;
        if (n.endsWith("_fence")) return "fence";
        if (n.endsWith("_wall")) return "wall";
        if (n.endsWith("iron_bars") || n.endsWith("glass_pane")) return "pane";
        return null;
    }

    private void joinFences() {
        Map<Long, String> updates = new HashMap<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            String fam = family(e.getValue());
            if (fam == null) continue;
            long k = e.getKey();
            int x = ux(k), y = uy(k), z = uz(k);
            String base = e.getValue().contains("[") ? e.getValue().substring(0, e.getValue().indexOf('[')) : e.getValue();
            boolean n = fam.equals(family(blocks.get(key(x, y, z - 1)))), s = fam.equals(family(blocks.get(key(x, y, z + 1))));
            boolean w = fam.equals(family(blocks.get(key(x - 1, y, z)))), ea = fam.equals(family(blocks.get(key(x + 1, y, z))));
            String data;
            if (fam.equals("wall")) {
                boolean straight = (n && s && !w && !ea) || (w && ea && !n && !s);
                data = base + "[north=" + (n ? "low" : "none") + ",south=" + (s ? "low" : "none") + ",west=" + (w ? "low" : "none") + ",east="
                        + (ea ? "low" : "none") + ",up=" + !straight + "]";
            } else {
                data = base + "[north=" + n + ",south=" + s + ",west=" + w + ",east=" + ea + "]";
            }
            updates.put(k, data);
        }
        blocks.putAll(updates);
    }

    // ------------------------------------------------------------------------------------------

    private void put(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), data);
    }

    private void clear(int x, int y, int z) {
        long k = key(x, y, z);
        if (y >= 0) blocks.remove(k);
        else blocks.put(k, "minecraft:air");
    }

    private void reserve(int x, int z, double extra) {
        reserved.add(key2(x, z));
    }

    private void bound(int x, int z) {
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
    }

    static long key(int x, int y, int z) {
        return ((long) (x + 2048) << 36) | ((long) (z + 2048) << 12) | (y + 256);
    }

    static int ux(long k) {
        return (int) (k >>> 36) - 2048;
    }

    static int uz(long k) {
        return (int) ((k >>> 12) & 0xFFFFFF) - 2048;
    }

    static int uy(long k) {
        return (int) (k & 0xFFF) - 256;
    }

    private static long key2(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------------------------------
    // What the builder and the race read
    // ------------------------------------------------------------------------------------------

    public TrackSpec spec() {
        return spec;
    }

    public TrackPath path() {
        return path;
    }

    /** Every block (track coordinates): x, y, z, data. The field's ground is not in it ({@link #ground}). */
    public void forEach(BlockSink sink) {
        for (Map.Entry<Long, String> e : blocks.entrySet()) sink.accept(ux(e.getKey()), uy(e.getKey()), uz(e.getKey()), e.getValue());
    }

    public interface BlockSink {
        void accept(int x, int y, int z, String data);
    }

    public int blockCount() {
        return blocks.size();
    }

    /** The block at a place (track coordinates), or null when the layout leaves it (the field, or air). */
    public String at(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    /** The field: x from minX to maxX, z from minZ to maxZ (inclusive). */
    public int minX() {
        return minX;
    }

    public int maxX() {
        return maxX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxZ() {
        return maxZ;
    }

    /** The highest y anything reaches. */
    public int maxY() {
        return maxY;
    }

    /** The field's top block at (x, z): the theme's ground blocks in soft patches. */
    public String ground(int x, int z) {
        double n = noise(x / 11.0, z / 11.0) * 0.8 + noise(x / 4.0 + 50, z / 4.0) * 0.2;
        List<String> g = th.ground();
        int k = n < 0.6 ? 0 : Math.min(g.size() - 1, 1 + (int) ((n - 0.6) / 0.4 * (g.size() - 1)));
        return g.get(k);
    }

    public String under() {
        return th.under();
    }

    private double noise(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        double a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
        double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        return (a + (b - a) * sx) * (1 - sz) + (c + (d - c) * sx) * sz;
    }

    private double hash(int x, int z) {
        long h = x * 374761393L + z * 668265263L + spec.id().hashCode() * 1442695040888963407L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }

    public List<Slot> grid() {
        return grid;
    }

    public List<Label> labels() {
        return labels;
    }

    public List<Zone> zones() {
        return zones;
    }

    /** The checkpoint gates (sample indices), the first is the finish line. */
    public int[] gates() {
        return gates.clone();
    }

    /** The road's top at sample i (blocks above the field: jumps' kickers included). */
    public double height(int i) {
        return heights[path.wrap(i)];
    }

    /** Is sample i over a jump's gap (falling is expected there)? */
    public boolean gap(int i) {
        return gap[path.wrap(i)];
    }

    /** Every chunk (x, z in chunks, world coordinates) the track touches. */
    public List<long[]> chunks() {
        List<long[]> out = new ArrayList<>();
        int ox = spec.originX();
        for (int cx = Math.floorDiv(minX + ox, 16); cx <= Math.floorDiv(maxX + ox, 16); cx++)
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) out.add(new long[]{cx, cz});
        return out;
    }
}
