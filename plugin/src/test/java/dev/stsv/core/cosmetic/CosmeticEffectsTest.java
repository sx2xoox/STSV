package dev.stsv.core.cosmetic;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trails and kill effects reach everyone near who can see the wearer, and a trail mark is never under the wearer. */
class CosmeticEffectsTest {

    @Test
    void otherPlayersGetTheEffectsNotOnlyTheWearer() {
        assertTrue(CosmeticEffects.shownTo(true, false, 0, 48), "the wearer");
        assertTrue(CosmeticEffects.shownTo(false, true, 10 * 10, 48), "a player near who sees the wearer");
        assertTrue(CosmeticEffects.shownTo(false, true, 40 * 40, 48), "also beyond the client's own 32-block particle limit");
        assertFalse(CosmeticEffects.shownTo(false, false, 4, 48), "someone the wearer is hidden from (wardrobe, vanish)");
        assertFalse(CosmeticEffects.shownTo(false, true, 49 * 49, 48), "too far");
        // forced: the viewer's particle setting (최소) and the 32-block limit of the client do not drop them
        assertTrue(CosmeticEffects.FORCE);
    }

    @Test
    void aTrailMarkIsBehindTheWearer() {
        // walking east (+x): the mark is TRAIL_BEHIND blocks west of the feet, at the feet's height
        Location mark = CosmeticEffects.trailMark(new Location(null, 0, 64, 0), new Location(null, 0.6, 64.4, 0));
        assertEquals(0.6 - CosmeticEffects.TRAIL_BEHIND, mark.getX(), 1e-9);
        assertEquals(64.4, mark.getY(), 1e-9);
        assertEquals(0, mark.getZ(), 1e-9);
        // diagonal: along the way walked
        mark = CosmeticEffects.trailMark(new Location(null, 0, 64, 0), new Location(null, 3, 64, 4));
        assertEquals(3 - 0.6 * CosmeticEffects.TRAIL_BEHIND, mark.getX(), 1e-9);
        assertEquals(4 - 0.8 * CosmeticEffects.TRAIL_BEHIND, mark.getZ(), 1e-9);
        // no step to go by (first mark, straight up): at the feet
        Location here = new Location(null, 5, 70, 5);
        assertEquals(here, CosmeticEffects.trailMark(null, here));
        assertEquals(here, CosmeticEffects.trailMark(new Location(null, 5, 69, 5), here));
        assertTrue(CosmeticEffects.TRAIL_BEHIND >= 0.5, "far enough to be clear of the legs as others see them");
    }
}
