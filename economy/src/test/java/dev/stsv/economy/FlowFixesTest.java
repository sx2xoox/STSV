package dev.stsv.economy;

import dev.stsv.economy.fishing.FishSpec;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fish's swim is part of the advertised bite time; lava and fire are not put right next to another player. */
class FlowFixesTest {

    @Test
    void theWholeBiteFitsTheAdvertisedTime() {
        FishSpec.Bite bite = new FishSpec.Bite(2, 12, 0.15);
        for (int lure = 0; lure <= 3; lure++) {
            for (double cut : new double[]{0, 0.2, 0.4}) {
                int[] t = bite.ticks(lure, cut);
                int[] p = FishSpec.Bite.split(t[0], t[1]);
                assertTrue(p[0] >= 1 && p[2] >= 1 && p[0] <= p[1] && p[2] <= p[3], "lure " + lure + " cut " + cut);
                assertTrue(p[0] + p[2] >= t[0] - 1 && p[0] + p[2] <= t[0] + 1, "shortest bite ~ " + t[0] + " ticks");
                assertTrue(p[1] + p[3] <= t[1] + 1, "longest bite within " + t[1] + " ticks");
            }
        }
        int[] fresh = FishSpec.Bite.split(40, 240);   // 2~12 s
        assertEquals(40, fresh[0] + fresh[2]);
        assertEquals(240, fresh[1] + fresh[3]);
        int[] tiny = FishSpec.Bite.split(1, 1);
        assertEquals(1, tiny[0]);
        assertEquals(1, tiny[2]);
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
