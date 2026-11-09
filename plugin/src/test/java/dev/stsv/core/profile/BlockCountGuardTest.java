package dev.stsv.core.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** blocks_placed / blocks_broken: a torch placed and broken on one spot over and over counts once, not every time. */
class BlockCountGuardTest {

    private static final long MIN = 60_000L;

    @Test
    void placeBreakOnOneSpotCountsOnce() {
        BlockCountGuard g = new BlockCountGuard(1000, 10 * MIN);
        assertTrue(g.place("p", 0), "the first placement counts");
        assertFalse(g.breakBlock("p", 1), "breaking what a player placed does not");
        for (int i = 0; i < 100; i++) {
            assertFalse(g.place("p", 2 + i), "placing again where a block was just broken does not");
            assertFalse(g.breakBlock("p", 2 + i));
        }
        assertTrue(g.place("p", 200 + 10 * MIN), "after a while it counts again (building there for real)");
    }

    @Test
    void miningAndBuildingCountAsBefore() {
        BlockCountGuard g = new BlockCountGuard(1000, 10 * MIN);
        for (int i = 0; i < 50; i++) assertTrue(g.breakBlock("stone" + i, i), "blocks the world made");
        for (int i = 0; i < 50; i++) assertTrue(g.place("wall" + i, 100 + i), "a wall on new spots");
    }

    @Test
    void forgetsTheOldestPastItsCapacity() {
        BlockCountGuard g = new BlockCountGuard(2, 10 * MIN);
        g.place("a", 0);
        g.place("b", 0);
        g.place("c", 0);
        assertTrue(g.breakBlock("a", 1), "forgotten: counted (the memory is bounded)");
        assertFalse(g.breakBlock("c", 1));
    }
}
