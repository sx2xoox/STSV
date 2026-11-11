package dev.stsv.economy.dock;

import java.util.ArrayList;
import java.util.List;

/**
 * A 하역장 in the world: its name, kind, where it was built (the admin's block) and which way it faces. Everything else
 * (its zone, counter, bounds) follows from {@link DockLayout} turned that way ({@link #world}). Pure (unit-tested).
 *
 * @param facing 0 south (+z), 1 west (-x), 2 north (-z), 3 east (+x): the way the admin looked (Minecraft's yaw / 90)
 * @param chunks the chunks it claimed as server land ("x,z;x,z"): released again by 제거
 */
public record Dock(String name, DockLayout.Kind kind, String world, int x, int y, int z, int facing, String chunks, long created) {

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
        for (DockLayout.Box zb : layout().zones()) {
            int[] b = box(zb);
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

    /** Where the road ends: just outside the gate, in front of the dock. */
    public int[] entrance() {
        return block(0, 0, -6);
    }

    public int[] counter() {
        DockLayout.Block c = layout().counter();
        return block(c.x(), c.y(), c.z());
    }

    public DockLayout.Layout layout() {
        return DockLayout.of(kind, name);
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
