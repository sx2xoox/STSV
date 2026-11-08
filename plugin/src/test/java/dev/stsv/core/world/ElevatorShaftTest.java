package dev.stsv.core.world;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The bone block elevator's column search: the next elevator block up or down with room to stand on it. */
class ElevatorShaftTest {

    /** A column: elevator blocks at these ys, solid (not free) blocks at those, air everywhere else. */
    private static OptionalInt ride(int from, boolean up, int max, Set<Integer> elevators, Set<Integer> solid) {
        IntPredicate elevator = elevators::contains;
        IntPredicate free = y -> !elevators.contains(y) && !solid.contains(y);
        return ElevatorShaft.next(from, up, max, -64, 320, elevator, free);
    }

    @Test
    void upToTheNextElevatorWithRoomAbove() {
        assertEquals(OptionalInt.of(70), ride(64, true, 32, Set.of(64, 70, 80), Set.of()));
        assertEquals(OptionalInt.of(80), ride(70, true, 32, Set.of(64, 70, 80), Set.of()));
        assertEquals(OptionalInt.empty(), ride(80, true, 32, Set.of(64, 70, 80), Set.of()), "the top floor");
        // floors in between (a ceiling) do not matter, only the landing
        assertEquals(OptionalInt.of(70), ride(64, true, 32, Set.of(64, 70), Set.of(66, 67)));
    }

    @Test
    void downToTheNextElevatorBelow() {
        assertEquals(OptionalInt.of(70), ride(80, false, 32, Set.of(64, 70, 80), Set.of()));
        assertEquals(OptionalInt.of(64), ride(70, false, 32, Set.of(64, 70, 80), Set.of()));
        assertEquals(OptionalInt.empty(), ride(64, false, 32, Set.of(64, 70, 80), Set.of()), "the ground floor");
    }

    @Test
    void unsafeTargetsAreSkipped() {
        // a block right above the next elevator (would suffocate): skipped, the one after is used
        assertEquals(OptionalInt.of(80), ride(64, true, 32, Set.of(64, 70, 80), Set.of(71)));
        assertEquals(OptionalInt.of(80), ride(64, true, 32, Set.of(64, 70, 80), Set.of(72)), "the head block counts too");
        // stacked elevator blocks: the lower one has no room (the upper one is right on it)
        assertEquals(OptionalInt.of(71), ride(64, true, 32, Set.of(64, 70, 71), Set.of()));
        assertEquals(OptionalInt.of(64), ride(80, false, 32, Set.of(64, 70, 80), Set.of(71)));
        assertEquals(OptionalInt.empty(), ride(64, true, 32, Set.of(64, 70), Set.of(72)), "nothing safe: no ride");
    }

    @Test
    void searchStopsAtTheDistanceAndTheWorldLimits() {
        assertEquals(OptionalInt.of(96), ride(64, true, 32, Set.of(64, 96), Set.of()), "exactly max-distance");
        assertEquals(OptionalInt.empty(), ride(64, true, 32, Set.of(64, 97), Set.of()), "one block too far");
        assertEquals(OptionalInt.empty(), ride(64, false, 32, Set.of(64, 31), Set.of()));
        // no landing whose head block would be above the build limit (319 is the highest block)
        assertEquals(OptionalInt.of(317), ride(300, true, 32, Set.of(300, 317), Set.of()));
        assertEquals(OptionalInt.empty(), ride(300, true, 32, Set.of(300, 318), Set.of()));
        assertEquals(OptionalInt.of(-64), ride(-50, false, 32, Set.of(-50, -64), Set.of()), "the bottom block is fine");
    }

    @Test
    void thePlayerStaysInsideTheColumn() {
        assertEquals(10.5, ElevatorShaft.inside(10.5, 10), 1e-9, "the offset is kept");
        assertEquals(10.62, ElevatorShaft.inside(10.62, 10), 1e-9);
        assertEquals(10.3, ElevatorShaft.inside(10.05, 10), 1e-9, "at the edge: moved in so the body fits");
        assertEquals(-4.3, ElevatorShaft.inside(-4.01, -5), 1e-9);
        assertEquals(-4.7, ElevatorShaft.inside(-4.9, -5), 1e-9);
    }
}
