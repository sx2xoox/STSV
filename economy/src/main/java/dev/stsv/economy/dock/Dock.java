package dev.stsv.economy.dock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A 하역장 in the world: its name, kind, where it was built (the admin's block) and which way it faces. Everything else
 * (its zone, counter, bounds) follows from {@link DockLayout} turned that way ({@link #world}). Pure (unit-tested).
 *
 * @param facing 0 south (+z), 1 west (-x), 2 north (-z), 3 east (+x): the way the admin looked (Minecraft's yaw / 90)
 * @param chunks the chunks it claimed as server land ("x,z;x,z"): released again by 제거
 * @param price  what it pays, in percent of the shop base price ({@link #MIN_PRICE} .. {@link #MAX_PRICE}), set by
 *               /관리자 하역장 가격; {@link #DEFAULT_PRICE} (0) = the global docks.price-ratio (terminals: always 0)
 */
public record Dock(String name, DockLayout.Kind kind, String world, int x, int y, int z, int facing, String chunks, long created, int price) {

    /** A dock's own price: 1 % .. 300 % of the base; 0 = none of its own (the global ratio). */
    public static final int MIN_PRICE = 1, MAX_PRICE = 300, DEFAULT_PRICE = 0;
    /** {@link #parsePrice}: not a price. */
    public static final int BAD_PRICE = -1;
    /** The word that puts a dock back on the global ratio. */
    public static final String DEFAULT_WORD = "기본";

    /** A dock (or terminal) on the global ratio. */
    public Dock(String name, DockLayout.Kind kind, String world, int x, int y, int z, int facing, String chunks, long created) {
        this(name, kind, world, x, y, z, facing, chunks, created, DEFAULT_PRICE);
    }

    /** The same dock with its own price (percent), or {@link #DEFAULT_PRICE} for the global ratio. */
    public Dock withPrice(int pct) {
        return new Dock(name, kind, world, x, y, z, facing, chunks, created, pct);
    }

    /** Has the admin set its own price? */
    public boolean ownPrice() {
        return price != DEFAULT_PRICE;
    }

    /** What it pays as a share of the base: its own price, else the global ratio. */
    public double ratio(double global) {
        return ownPrice() ? price / 100.0 : global;
    }

    /**
     * The price an admin typed: "95", "110", "110%" -> 95, 110; "기본" -> {@link #DEFAULT_PRICE}; anything else, or outside
     * {@link #MIN_PRICE} .. {@link #MAX_PRICE} -> {@link #BAD_PRICE}.
     */
    public static int parsePrice(String text) {
        if (text == null) return BAD_PRICE;
        String t = text.trim();
        if (t.equals(DEFAULT_WORD)) return DEFAULT_PRICE;
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        if (t.isEmpty() || t.length() > 4 || !t.chars().allMatch(c -> c >= '0' && c <= '9')) return BAD_PRICE;
        int v = Integer.parseInt(t);
        return v >= MIN_PRICE && v <= MAX_PRICE ? v : BAD_PRICE;
    }

    /** A ratio as a percent for people: 90, 110, 92.5 (one decimal at most). */
    public static String percent(double ratio) {
        double p = Math.round(ratio * 1000) / 10.0;
        return p == Math.rint(p) ? Long.toString((long) p) : String.format(java.util.Locale.ROOT, "%.1f", p);
    }

    /** The facing of a yaw (degrees, Minecraft: 0 = south, 90 = west). */
    public static int facing(float yaw) {
        return Math.floorMod(Math.round(yaw / 90f), 4);
    }

    /** The yaw of a facing. */
    public static float yaw(int facing) {
        return facing * 90f;
    }

    /** Forward (x, z) of a facing. */
    static int[] forward(int facing) {
        return switch (Math.floorMod(facing, 4)) {
            case 0 -> new int[]{0, 1};
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    /** A dock point (right, up, forward) in the world. */
    public double[] world(double right, double up, double forward) {
        int[] f = forward(facing);
        // the right hand of someone facing (fx, fz) is (-fz, fx)
        return new double[]{x + 0.5 + (-f[1]) * right + f[0] * forward, y + up, z + 0.5 + f[0] * right + f[1] * forward};
    }

    /**
     * A point given as the layout's props and label give it (block b spans b .. b + 1, so 0.5 is the middle of block 0) in
     * the world.
     */
    public double[] at(double right, double up, double forward) {
        return world(right - 0.5, up, forward - 0.5);
    }

    /** A dock block (right, up, forward) as a world block. */
    public int[] block(int right, int up, int forward) {
        int[] f = forward(facing);
        return new int[]{x + (-f[1]) * right + f[0] * forward, y + up, z + f[0] * right + f[1] * forward};
    }

    /** A world box of a dock box (inclusive blocks). */
    public int[] box(DockLayout.Box b) {
        int[] a = block(b.minX(), b.minY(), b.minZ()), c = block(b.maxX(), b.maxY(), b.maxZ());
        return new int[]{Math.min(a[0], c[0]), Math.min(a[1], c[1]), Math.min(a[2], c[2]), Math.max(a[0], c[0]), Math.max(a[1], c[1]), Math.max(a[2], c[2])};
    }

    /** Is a world point inside one of the dock's unloading zones? */
    public boolean inZone(String inWorld, double px, double py, double pz) {
        if (!world.equals(inWorld)) return false;
        for (int[] b : shape().zones()) {
            if (px >= b[0] && px < b[3] + 1 && py >= b[1] && py < b[4] + 1 && pz >= b[2] && pz < b[5] + 1) return true;
        }
        return false;
    }

    /** Is any of these points (a vehicle and its towed units) inside its zone? */
    public boolean anyInZone(String inWorld, List<double[]> points) {
        for (double[] p : points) if (inZone(inWorld, p[0], p[1], p[2])) return true;
        return false;
    }

    /** The middle of the unloading zone (where distances are measured from). */
    public double[] centre() {
        DockLayout.Box z = DockLayout.ZONE;
        return world((z.minX() + z.maxX() + 1) / 2.0 - 0.5, 0, (z.minZ() + z.maxZ() + 1) / 2.0 - 0.5);
    }

    /** How far in front of the gate the road's route is planned to: the last stretch runs straight in, in line with the gate. */
    public static final int APPROACH = 20;

    /** Where a road's route is planned to: {@link #APPROACH} blocks straight out in front of the gate. */
    public int[] entrance() {
        return block(0, 0, -APPROACH);
    }

    /**
     * Where the road ends: the gate, the middle of the pad's front edge (dock z -1), y the pad's floor (one under where the
     * admin stood): the road's last cross-section is built on it, at the floor's level, so it runs on into the dock.
     */
    public int[] gate() {
        return block(0, -1, -1);
    }

    /** The 하역 창구 (or a terminal's 화물 검사소 counter) as a world block. */
    public int[] counter() {
        return shape().counter().clone();
    }

    /** Is this world block the counter? (on every block right-click: nothing made) */
    public boolean isCounter(String inWorld, int bx, int by, int bz) {
        int[] c = shape().counter();
        return bx == c[0] && by == c[1] && bz == c[2] && world.equals(inWorld);
    }

    /** What it is built of ({@link DockLayout#of}): worked out once per kind and name, the same object every time. */
    public DockLayout.Layout layout() {
        return shape().layout();
    }

    /** The layout of a kind and name, made once ({@link DockLayout#of} is pure: a few thousand blocks laid out). */
    private record Plan(DockLayout.Kind kind, String name) {}

    /** Where a dock stands (what its world boxes follow from). */
    private record Place(DockLayout.Kind kind, String name, int x, int y, int z, int facing) {}

    /** What follows from where a dock stands: its layout, its counter and unloading zones as world blocks and boxes. */
    private record Shape(DockLayout.Layout layout, int[] counter, int[][] zones) {}

    private static final Map<Plan, DockLayout.Layout> LAYOUTS = new ConcurrentHashMap<>();
    private static final Map<Place, Shape> SHAPES = new ConcurrentHashMap<>();

    /**
     * This dock's shape, worked out once for its kind, name, place and facing (a record has no fields of its own to keep
     * it in; the same dock with another price shares it). Docks are few: the caches stay small.
     */
    private Shape shape() {
        return SHAPES.computeIfAbsent(new Place(kind, name, x, y, z, facing), k -> {
            DockLayout.Layout l = LAYOUTS.computeIfAbsent(new Plan(kind, name), p -> DockLayout.of(p.kind(), p.name()));
            DockLayout.Block c = l.counter();
            int[][] zones = new int[l.zones().size()][];
            for (int i = 0; i < zones.length; i++) zones[i] = box(l.zones().get(i));
            return new Shape(l, block(c.x(), c.y(), c.z()), zones);
        });
    }

    /** The chunks the dock's bounds touch. */
    public List<long[]> chunksTouched() {
        int[] b = box(layout().bounds());
        List<long[]> out = new ArrayList<>();
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) out.add(new long[]{cx, cz});
        return out;
    }

    /** The claimed chunks as written in {@link #chunks}. */
    public static String chunkText(List<long[]> chunks) {
        StringBuilder sb = new StringBuilder();
        for (long[] c : chunks) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(c[0]).append(',').append(c[1]);
        }
        return sb.toString();
    }

    public List<long[]> claimed() {
        List<long[]> out = new ArrayList<>();
        if (chunks == null || chunks.isBlank()) return out;
        for (String p : chunks.split(";")) {
            String[] xz = p.split(",");
            out.add(new long[]{Long.parseLong(xz[0].trim()), Long.parseLong(xz[1].trim())});
        }
        return out;
    }

    /** A name a dock may have: 1 to 16 letters, digits, Korean or '_' (it is typed in commands). */
    public static boolean validName(String n) {
        return n != null && n.length() >= 1 && n.length() <= 16 && n.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }
}
