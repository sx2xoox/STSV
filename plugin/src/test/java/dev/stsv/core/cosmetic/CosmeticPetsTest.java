package dev.stsv.core.cosmetic;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticPetsTest {

    /** A pet that caught up with its spot is not moved (no packet) every tick; walking, turning and bobbing still move it. */
    @Test
    void standingPetIsNotMovedEveryTick() {
        Location now = new Location(null, 10.5, 64.5, -3.25, 90f, 0f);
        assertFalse(CosmeticPets.moved(now, new Location(null, 10.500001, 64.5, -3.250001, 90.01f, 0f)));
        assertTrue(CosmeticPets.moved(now, new Location(null, 10.52, 64.5, -3.25, 90f, 0f)));      // a step
        assertTrue(CosmeticPets.moved(now, new Location(null, 10.5, 64.505, -3.25, 90f, 0f)));     // a bob
        assertTrue(CosmeticPets.moved(now, new Location(null, 10.5, 64.5, -3.25, 96f, 0f)));       // turning to the owner
        assertFalse(CosmeticPets.moved(new Location(null, 0, 0, 0, 179.99f, 0), new Location(null, 0, 0, 0, -179.99f, 0)));
    }
}
