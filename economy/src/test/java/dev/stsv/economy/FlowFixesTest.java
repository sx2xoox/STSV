package dev.stsv.economy;

import dev.stsv.economy.fishing.FishSpec;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fisher's wait is vanilla's, shortened; lava and fire are not put right next to another player. */
class FlowFixesTest {

    @Test
    void theFishersWaitIsVanillasShortened() {
        for (int lure = 0; lure <= 3; lure++) {
            int[] v = FishSpec.waitTicks(lure, 0);
            for (double cut : new double[]{0.32, 0.4, 0.5}) {
                int[] t = FishSpec.waitTicks(lure, cut);
                assertTrue(t[0] >= 1 && t[0] <= t[1], "lure " + lure + " cut " + cut);
                assertTrue(t[1] < v[1], "shorter than vanilla's longest wait");
                assertEquals(Math.round(v[1] * (1 - cut)), t[1], "lure " + lure + " cut " + cut);
            }
        }
    }

    @Test
    void nearAPlayer() {
        double r2 = PvpRule.HAZARD_RADIUS * PvpRule.HAZARD_RADIUS;
        Location block = new Location(null, 10, 64, 10);
        assertTrue(PvpRule.near(new Location(null, 11.5, 64, 10.5), block, r2), "right next to them");
        assertTrue(PvpRule.near(new Location(null, 10.5, 62, 10.5), block, r2), "lava poured on their head");
        assertFalse(PvpRule.near(new Location(null, 16.5, 64, 10.5), block, r2), "six blocks away");
        assertFalse(PvpRule.near(new Location(null, 10.5, 74, 10.5), block, r2), "ten blocks above");
    }
}
