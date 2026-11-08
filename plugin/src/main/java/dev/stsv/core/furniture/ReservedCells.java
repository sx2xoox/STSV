package dev.stsv.core.furniture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Every cell of the loaded furniture, by world, so the guards (ReservedCellGuard) can tell in constant time
 * whether a block change would land in a piece's footprint. Cells without a barrier are air but still belong
 * to the furniture: nothing may be placed, poured, pushed or grown into them.
 *
 * Kept up to date from entity add/remove (chunk load/unload), placement and breaking. Main thread only.
 */
final class ReservedCells {

    private final Map<UUID, Map<Long, UUID>> byWorld = new HashMap<>();
    private final Map<UUID, long[]> cellsOf = new HashMap<>();
    private final Map<UUID, UUID> worldOf = new HashMap<>();

    void add(UUID world, UUID owner, List<int[]> cells) {
        remove(owner);
        Map<Long, UUID> map = byWorld.computeIfAbsent(world, w -> new HashMap<>());
        long[] packed = new long[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get(i);
            packed[i] = pack(c[0], c[1], c[2]);
            map.put(packed[i], owner);
        }
        cellsOf.put(owner, packed);
        worldOf.put(owner, world);
    }

    void remove(UUID owner) {
        long[] packed = cellsOf.remove(owner);
        UUID world = worldOf.remove(owner);
        if (packed == null || world == null) return;
        Map<Long, UUID> map = byWorld.get(world);
        if (map == null) return;
        for (long p : packed) map.remove(p, owner);
        if (map.isEmpty()) byWorld.remove(world);
    }

    void clear() {
        byWorld.clear();
        cellsOf.clear();
        worldOf.clear();
    }

    /** The furniture (its display entity) owning this cell, or null. */
    UUID owner(UUID world, int x, int y, int z) {
        Map<Long, UUID> map = byWorld.get(world);
        return map == null ? null : map.get(pack(x, y, z));
    }

    boolean reserved(UUID world, int x, int y, int z) {
        return owner(world, x, y, z) != null;
    }

    int size() {
        return cellsOf.size();
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    // ------------------------------------------------------------------------------------------
    // Decisions (pure, so they are tested without a server)
    // ------------------------------------------------------------------------------------------

    /**
     * A piston move is refused when a moved block would end up in a reserved cell, or (extending) when the
     * piston head itself would go into one.
     *
     * @param piston the piston block, moved: the blocks it moves, dir: the way they move (BlockPistonEvent#getDirection)
     */
    static boolean pistonBlocked(int[] piston, List<int[]> moved, int[] dir, boolean extending, Predicate<int[]> reserved) {
        if (extending && reserved.test(add(piston, dir))) return true;
        for (int[] b : moved) if (reserved.test(add(b, dir))) return true;
        return false;
    }

    /**
     * A dispenser may not pour or place into a reserved cell: filled buckets (water, lava, powder snow, fish)
     * and blocks it would set down (shulker boxes, pumpkins, skulls...). Anything else (arrows, items) just flies.
     *
     * @param item    the dispensed material's name (Material#name)
     * @param isBlock whether that material is a block
     */
    static boolean dispenseBlocked(String item, boolean isBlock, boolean targetReserved) {
        if (!targetReserved) return false;
        boolean filledBucket = item.endsWith("_BUCKET") && !item.equals("MILK_BUCKET");
        return filledBucket || isBlock;
    }

    /** Something turning a reserved cell into a block (enderman, falling block landing, snow, fire...): refused. Clearing it is fine. */
    static boolean changeBlocked(boolean targetReserved, boolean newIsAir) {
        return targetReserved && !newIsAir;
    }

    private static int[] add(int[] a, int[] b) {
        return new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }
}
