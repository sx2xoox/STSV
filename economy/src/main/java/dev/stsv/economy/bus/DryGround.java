package dev.stsv.economy.bus;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

/**
 * Dry ground for something only one driver sees (바둑이, LostDog): the same strict rule as the hidden secrets' houses
 * (secret/pager/Dry). A column counts only when the first ground met going down is a full, solid, dry block and the two
 * blocks over it are open and dry; water, lava or ice met first means the column is wet (a sea, a lake, the floor under
 * them never count). Reads blocks through {@link Probe}, so it can be tried without a world.
 */
final class DryGround {

    private DryGround() {}

    enum Cell { AIR, WET, GROUND, OTHER }

    interface Probe {
        Cell at(int x, int y, int z);
    }

    /** No ground in the window. */
    static final int NONE = Integer.MIN_VALUE + 2;
    /** Water, lava or ice first. */
    static final int WET = Integer.MIN_VALUE + 3;

    static boolean found(int y) {
        return y > WET;
    }

    static Cell of(Block b) {
        Material t = b.getType();
        if (t == Material.WATER || t == Material.LAVA || t == Material.BUBBLE_COLUMN || t == Material.KELP || t == Material.KELP_PLANT
                || t == Material.SEAGRASS || t == Material.TALL_SEAGRASS || b.isLiquid()) return Cell.WET;
        if (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return Cell.WET;
        if (t == Material.ICE || t == Material.PACKED_ICE || t == Material.BLUE_ICE || t == Material.FROSTED_ICE) return Cell.WET;
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
     * The y to stand at in a column, looking from {@code top} down to {@code low}: over the first ground block, with two
     * open blocks over it. {@link #WET} when water, lava or ice comes first (or lies in those two); {@link #NONE} when
     * something else is in the way or there is no ground in the window.
     */
    static int ground(Probe p, int x, int z, int top, int low) {
        for (int y = top; y >= low; y--) {
            switch (p.at(x, y, z)) {
                case WET -> {
                    return WET;
                }
                case GROUND -> {
                    for (int k = 1; k <= 2; k++) {
                        Cell c = p.at(x, y + k, z);
                        if (c == Cell.WET) return WET;
                        if (c != Cell.AIR) return NONE;
                    }
                    return y + 1;
                }
                default -> {}
            }
        }
        return NONE;
    }

    /** Whether (x, z) and the four columns beside it are all dry ground within a block of each other: the y, or NONE / WET. */
    static int spot(Probe p, int x, int z, int top, int low) {
        int y = ground(p, x, z, top, low);
        if (!found(y)) return y;
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] a : around) {
            int n = ground(p, x + a[0], z + a[1], top, low);
            if (n == WET) return WET;
            if (!found(n) || Math.abs(n - y) > 1) return NONE;
        }
        return y;
    }
}
