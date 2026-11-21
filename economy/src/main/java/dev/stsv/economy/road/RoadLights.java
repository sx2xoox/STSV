package dev.stsv.economy.road;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * 국도 가로등 (README "국도", 가로등): a street light as a road builds it ({@link RoadSection}), found again in the world by
 * its exact shape, so one can be taken away (a bus stop's bay: StopBuilder; /관리자 국도 가로등정리) without touching
 * anything else. Pure (unit-tested).
 *
 * <p>The shape: a post of stone brick walls, 5 or more high, standing on a stone brick footing; at its top an arm of stone
 * brick walls (level with the post's top, reaching at most {@link #ARM_REACH} across) and a lantern hanging under the arm.
 * Anything else (a wall a player built, a railing, a guardrail of andesite walls) is not a street light.
 */
public final class RoadLights {

    /** What is at a block (null: not known; then nothing is found there). */
    public interface Blocks {
        Material get(int x, int y, int z);
    }

    /** The post's material, its footing's, the lantern's. */
    static final Material POST = Material.STONE_BRICK_WALL, FOOTING = Material.STONE_BRICKS, LANTERN = Material.LANTERN;
    /** A post is at least this many walls high (5 over a flat road's verge: 6 from the footing to the arm). */
    static final int MIN_POST = 5, MAX_POST = 16;
    /** How far (blocks, either axis) the arm reaches from the post, and how many walls it has at most. */
    static final int ARM_REACH = 6, ARM_MAX = 10;

    private RoadLights() {}

    /**
     * A street light found: its post's column (x, z), the y of its lowest wall (its footing is under it) and its top (the
     * arm's level), the arm's blocks and the lantern's ({x, y, z}; null: a post left without its lantern, see
     * {@link #post}).
     */
    public record Light(int x, int z, int bottom, int top, List<int[]> arm, int[] lantern) {

        /** Every block of it that is taken away with it: the post's walls, the arm's, the lantern (not the footing). */
        public List<int[]> blocks() {
            List<int[]> out = new ArrayList<>();
            for (int y = bottom; y <= top; y++) out.add(new int[]{x, y, z});
            out.addAll(arm);
            if (lantern != null) out.add(lantern);
            return out;
        }
    }

    /**
     * The street light whose post stands in column (x, z) with its lowest wall at a y from yFrom to yTo (on its footing),
     * or null.
     */
    public static Light at(Blocks b, int x, int z, int yFrom, int yTo) {
        for (int y = yFrom; y <= yTo; y++) {
            if (b.get(x, y, z) != POST || b.get(x, y - 1, z) != FOOTING) continue;
            Light l = from(b, x, y, z, true);
            if (l != null) return l;
        }
        return null;
    }

    /**
     * As {@link #at}, but also what is left of a light a bus stop built before took the arm and lantern of (they were
     * cleared over the bay's edge lane): a post on its footing, with its arm or without, its lantern or without. Only for a
     * column that is the road's own shoulder (/관리자 국도 가로등정리 looks there and nowhere else).
     */
    public static Light post(Blocks b, int x, int z, int y) {
        if (b.get(x, y, z) != POST || b.get(x, y - 1, z) != FOOTING) return null;
        return from(b, x, y, z, false);
    }

    /** The light whose post's lowest wall is at (x, y, z), or null; whole: its arm and lantern too. */
    static Light from(Blocks b, int x, int bottom, int z, boolean whole) {
        int top = bottom;
        while (top - bottom < MAX_POST && b.get(x, top + 1, z) == POST) top++;
        if (top - bottom + 1 < MIN_POST) return null;
        // the arm: the walls level with the post's top joined to it (a diagonal road's arm steps corner to corner)
        List<int[]> arm = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
        todo.add(new int[]{x, z});
        seen.add(key(x, z));
        while (!todo.isEmpty()) {
            int[] c = todo.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int ax = c[0] + dx, az = c[1] + dz;
                    if (Math.abs(ax - x) > ARM_REACH || Math.abs(az - z) > ARM_REACH || !seen.add(key(ax, az))) continue;
                    if (b.get(ax, top, az) != POST) continue;
                    if (b.get(ax, top - 1, az) == POST) return null;   // another post (or a player's wall) joined to it: not a light
                    arm.add(new int[]{ax, top, az});
                    if (arm.size() > ARM_MAX) return null;
                    todo.add(new int[]{ax, az});
                }
            }
        }
        if (arm.isEmpty() && whole) return null;
        // the lantern hangs under the arm's far end (the arm wall furthest from the post with a lantern under it)
        int[] lantern = null;
        int far = -1;
        for (int[] a : arm) {
            if (b.get(a[0], top - 1, a[2]) != LANTERN) continue;
            int d = (a[0] - x) * (a[0] - x) + (a[2] - z) * (a[2] - z);
            if (d > far) {
                far = d;
                lantern = new int[]{a[0], top - 1, a[2]};
            }
        }
        return lantern == null && whole ? null : new Light(x, z, bottom, top, arm, lantern);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
