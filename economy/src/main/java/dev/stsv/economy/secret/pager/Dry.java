package dev.stsv.economy.secret.pager;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dry ground: where something only one viewer sees may be put down without it standing on water (a "수상가옥"). A column
 * counts only when the first ground found going down is a full, solid, dry block (not ice, not waterlogged) and the three
 * blocks over it are open air (passable, no liquid, nothing waterlogged, no kelp, seagrass or bubble column). Water or
 * lava met on the way down, or ice, means the column is wet: a sea, a lake's surface and the floor under them never count.
 * A footprint counts only when every one of its columns does. The rule itself reads blocks through {@link Probe}, so it
 * can be tried without a world.
 */
final class Dry {

    private Dry() {}

    /** What one block is, as far as the rule cares. */
    enum Cell {
        /** passable, dry: air, grass, flowers, a snow layer */
        AIR,
        /** water, lava, anything waterlogged, kelp, seagrass, a bubble column */
        WET,
        /** ice of any kind (it lies on water) */
        ICE,
        /** a full, solid, dry block one may build on */
        GROUND,
        /** anything else (leaves, logs, a fence, a slab): looked through going down, never stood on */
        OTHER
    }

    interface Probe {
        Cell at(int x, int y, int z);
    }

    /** No ground in the window (a drop, a wall, a block in the way). */
    static final int NONE = Integer.MIN_VALUE + 2;
    /** Water, lava or ice first: the column is wet. */
    static final int WET = Integer.MIN_VALUE + 3;

    static boolean found(int y) {
        return y > WET;
    }

    static Cell of(Block b) {
        Material t = b.getType();
        if (t == Material.WATER || t == Material.LAVA || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT
                || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS || b.isLiquid()) return Cell.WET;
        if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return Cell.WET;
        if (t == Material.ICE || t == Material.PACKED_ICE || t == Material.BLUE_ICE || t == Material.FROSTED_ICE) return Cell.ICE;
        if (b.isPassable()) return Cell.AIR;
        if (Tag.LEAVES.isTagged(t) || Tag.LOGS.isTagged(t)) return Cell.OTHER;
        if (t == Material.DIRT_PATH || t == Material.FARMLAND || t.isSolid() && t.isOccluding()) return Cell.GROUND;
        return Cell.OTHER;
    }

    /** A world's blocks (the caller makes sure the chunks are loaded). */
    static Probe world(World w) {
        return (x, y, z) -> y < w.getMinHeight() || y >= w.getMaxHeight() ? Cell.AIR : of(w.getBlockAt(x, y, z));
    }

    /**
     * The y to stand on in a column, looking from {@code top} down to {@code low}: over the first ground block, with three
     * open blocks over it. {@link #WET} when water, lava or ice comes first (or lies in those three), {@link #NONE} when
     * there is no such ground in the window.
     */
    static int ground(Probe p, int x, int z, int top, int low) {
        for (int y = top; y >= low; y--) {
            switch (p.at(x, y, z)) {
                case WET, ICE -> {
                    return WET;
                }
                case GROUND -> {
                    for (int k = 1; k <= 3; k++) {
                        Cell c = p.at(x, y + k, z);
                        if (c == Cell.WET || c == Cell.ICE) return WET;
                        if (c != Cell.AIR) return NONE;
                    }
                    return y + 1;
                }
                default -> {}
            }
        }
        return NONE;
    }

    /** How a footprint stands: every column's ground (in the order given), or the first column that failed and why. */
    record Check(int[] ys, int min, int max, boolean wet, boolean missing) {
        boolean ok() {
            return !wet && !missing;
        }
    }

    /** Every column ({x, z}) must be dry ground in the window; stops at the first that is not. */
    static Check footprint(Probe p, List<int[]> cols, int top, int low) {
        int[] ys = new int[cols.size()];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i < cols.size(); i++) {
            int y = ground(p, cols.get(i)[0], cols.get(i)[1], top, low);
            if (y == WET) return new Check(ys, min, max, true, false);
            if (y == NONE) return new Check(ys, min, max, false, true);
            ys[i] = y;
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        return new Check(ys, min, max, false, false);
    }

    /**
     * The block columns under the house front with the middle of its gate in column (cx, cz), turned by yaw (a quarter
     * turn): 9 wide (lx -4..4), 6 deep (lz -3 behind the wall .. 2 before the gate). The middle first.
     */
    static List<int[]> house(int cx, int cz, float yaw) {
        double r = Math.toRadians(yaw), cos = Math.cos(r), sin = Math.sin(r);
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        out.add(new int[] {cx, cz});
        seen.add(((long) cx << 32) ^ (cz & 0xffffffffL));
        for (int lz = -3; lz <= 2; lz++)
            for (int lx = -4; lx <= 4; lx++) {
                int x = (int) Math.floor(cx + 0.5 + lx * cos - lz * sin), z = (int) Math.floor(cz + 0.5 + lx * sin + lz * cos);
                if (seen.add(((long) x << 32) ^ (z & 0xffffffffL))) out.add(new int[] {x, z});
            }
        return out;
    }

    /** Why a footprint is refused, in words (null: it is dry). */
    static String why(Check c) {
        if (c.wet()) return "물 위에는 세울 수 없어요 (물 · 얼음 · 용암 · 물풀이 자리에 걸려요)";
        if (c.missing()) return "디딜 마른 땅이 없어요 (낭떠러지 · 벽 · 너무 울퉁불퉁한 곳)";
        return null;
    }
}
