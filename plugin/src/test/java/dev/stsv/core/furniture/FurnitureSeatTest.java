package dev.stsv.core.furniture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Picking a seat: the free one nearest to the click (games such as the 화투방 tables read the index back). */
class FurnitureSeatTest {

    private static final List<double[]> ROUND = List.of(
            new double[]{0, 0, -2}, new double[]{2, 0, -1}, new double[]{2, 0, 1},
            new double[]{0, 0, 2}, new double[]{-2, 0, 1}, new double[]{-2, 0, -1});

    @Test
    void theNearestFreeSeat() {
        assertEquals(0, FurnitureModule.nearestSeat(ROUND, new boolean[6], 0.2, 0, -3));
        assertEquals(3, FurnitureModule.nearestSeat(ROUND, new boolean[6], 0, 0, 2.1));
        boolean[] taken = new boolean[6];
        taken[3] = true;
        int next = FurnitureModule.nearestSeat(ROUND, taken, 0, 0, 2.1);
        assertEquals(true, next == 2 || next == 4, "a neighbour of the taken seat, got " + next);
    }

    @Test
    void noFreeSeat() {
        boolean[] all = {true, true, true, true, true, true};
        assertEquals(-1, FurnitureModule.nearestSeat(ROUND, all, 0, 0, 0));
        assertEquals(-1, FurnitureModule.nearestSeat(List.of(), new boolean[0], 0, 0, 0));
    }

    @Test
    void theSeatEntityFindsItsOwnIndex() {
        // a seat entity stands exactly on its spot: with nothing marked taken, its own index is nearest
        for (int i = 0; i < ROUND.size(); i++) {
            double[] s = ROUND.get(i);
            assertEquals(i, FurnitureModule.nearestSeat(ROUND, new boolean[6], s[0], s[1], s[2]));
        }
    }
}
