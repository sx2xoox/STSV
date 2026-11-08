package dev.stsv.core.furniture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The footprint index and the guards' decisions for cells without a barrier. */
class ReservedCellsTest {

    private static final UUID WORLD = UUID.randomUUID(), NETHER = UUID.randomUUID();

    @Test
    void indexFollowsLoadAndUnload() {
        ReservedCells r = new ReservedCells();
        UUID bench = UUID.randomUUID(), forge = UUID.randomUUID();
        r.add(WORLD, bench, List.of(new int[]{10, 64, -5}, new int[]{11, 64, -5}, new int[]{10, 65, -5}, new int[]{11, 65, -5}));
        r.add(WORLD, forge, List.of(new int[]{-30_000_000 + 1, -64, 29_999_999}));
        assertEquals(bench, r.owner(WORLD, 11, 65, -5));
        assertTrue(r.reserved(WORLD, -30_000_000 + 1, -64, 29_999_999), "far corners and the lowest y pack too");
        assertFalse(r.reserved(WORLD, 12, 64, -5));
        assertFalse(r.reserved(NETHER, 10, 64, -5), "per world");
        r.remove(bench); // unloaded or broken
        assertFalse(r.reserved(WORLD, 10, 64, -5));
        assertTrue(r.reserved(WORLD, -30_000_000 + 1, -64, 29_999_999));
        r.add(WORLD, forge, List.of(new int[]{0, 70, 0})); // loaded again (cells re-read): the old entry goes
        assertFalse(r.reserved(WORLD, -30_000_000 + 1, -64, 29_999_999));
        assertEquals(1, r.size());
        r.remove(UUID.randomUUID()); // unknown: nothing happens
        assertEquals(forge, r.owner(WORLD, 0, 70, 0));
        r.clear();
        assertNull(r.owner(WORLD, 0, 70, 0));
    }

    @Test
    void anotherPiecesCellIsNotTakenOverOnRemove() {
        ReservedCells r = new ReservedCells();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        r.add(WORLD, a, List.of(new int[]{0, 0, 0}));
        r.add(WORLD, b, List.of(new int[]{0, 0, 0})); // should never happen, but removing a must not free b's cell
        r.remove(a);
        assertEquals(b, r.owner(WORLD, 0, 0, 0));
    }

    private static Predicate<int[]> cellAt(int x, int y, int z) {
        return c -> c[0] == x && c[1] == y && c[2] == z;
    }

    @Test
    void pistonsCannotPushIntoTheFootprint() {
        int[] piston = {0, 64, 0}, east = {1, 0, 0};
        Predicate<int[]> reserved = cellAt(3, 64, 0);
        // a block at x 1 pushed east lands at 2: fine; two blocks (1, 2): the second lands in the reserved cell
        assertFalse(ReservedCells.pistonBlocked(piston, List.of(new int[]{1, 64, 0}), east, true, reserved));
        assertTrue(ReservedCells.pistonBlocked(piston, List.of(new int[]{1, 64, 0}, new int[]{2, 64, 0}), east, true, reserved));
        // nothing to push, but the head itself would go into a reserved cell
        assertTrue(ReservedCells.pistonBlocked(new int[]{2, 64, 0}, List.of(), east, true, reserved));
        // a sticky piston pulling a block towards itself (west) out of x 4 into x 3
        int[] west = {-1, 0, 0};
        assertTrue(ReservedCells.pistonBlocked(new int[]{1, 64, 0}, List.of(new int[]{4, 64, 0}), west, false, reserved));
        // retracting with nothing attached never enters a cell
        assertFalse(ReservedCells.pistonBlocked(new int[]{4, 64, 0}, List.of(), west, false, reserved));
    }

    @Test
    void dispensersCannotPourOrPlaceIntoTheFootprint() {
        assertTrue(ReservedCells.dispenseBlocked("WATER_BUCKET", false, true));
        assertTrue(ReservedCells.dispenseBlocked("LAVA_BUCKET", false, true));
        assertTrue(ReservedCells.dispenseBlocked("POWDER_SNOW_BUCKET", false, true));
        assertTrue(ReservedCells.dispenseBlocked("COD_BUCKET", false, true));
        assertTrue(ReservedCells.dispenseBlocked("SHULKER_BOX", true, true));
        assertFalse(ReservedCells.dispenseBlocked("ARROW", false, true), "projectiles just fly through");
        assertFalse(ReservedCells.dispenseBlocked("MILK_BUCKET", false, true));
        assertFalse(ReservedCells.dispenseBlocked("BUCKET", false, true), "an empty bucket only picks up");
        assertFalse(ReservedCells.dispenseBlocked("WATER_BUCKET", false, false), "elsewhere anything goes");
    }

    @Test
    void blockChangesIntoTheFootprintAreRefusedButClearingIsNot() {
        assertTrue(ReservedCells.changeBlocked(true, false), "sand landing, an enderman placing, snow forming");
        assertFalse(ReservedCells.changeBlocked(true, true), "something leaving the cell");
        assertFalse(ReservedCells.changeBlocked(false, false), "outside the footprint");
    }
}
