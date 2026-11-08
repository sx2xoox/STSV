package dev.stsv.core.world;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * The column search of the 엘리베이터 (bone block elevator, {@link ElevatorModule}): from the elevator block a player
 * stands on, the next elevator block up or down the same column whose two blocks above are free (the player lands
 * on top of it and must not suffocate). A target that is not safe is skipped and the search goes on. Pure (tested).
 */
public final class ElevatorShaft {

    private ElevatorShaft() {}

    /**
     * @param fromY       the y of the elevator block the player stands on
     * @param up          true = search above, false = below
     * @param maxDistance how many blocks away the search goes (1 = only the next block)
     * @param minY        the world's lowest block y (inclusive)
     * @param maxY        the world's height limit (exclusive: the highest block is maxY - 1)
     * @param elevator    is the block at this y (same column) an elevator block
     * @param free        can a player stand in the block at this y (no collision, nothing that hurts)
     * @return the y of the target elevator block (the player's feet go to y + 1), empty when there is none
     */
    public static OptionalInt next(int fromY, boolean up, int maxDistance, int minY, int maxY, IntPredicate elevator, IntPredicate free) {
        int step = up ? 1 : -1;
        for (int d = 1; d <= maxDistance; d++) {
            int y = fromY + step * d;
            if (y < minY || y + 2 >= maxY) break;
            if (elevator.test(y) && free.test(y + 1) && free.test(y + 2)) return OptionalInt.of(y);
        }
        return OptionalInt.empty();
    }

    /**
     * Where the player's centre goes on the target block: the same offset inside the block as before, kept far enough
     * from the block's sides (the player is 0.6 wide) that the player stays inside the column.
     */
    public static double inside(double coord, int block) {
        return Math.clamp(coord, block + 0.3, block + 0.7);
    }
}
