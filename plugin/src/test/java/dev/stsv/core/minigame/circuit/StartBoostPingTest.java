package dev.stsv.core.minigame.circuit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The start boost judged later for a high ping (docs/plans/핑_완화_조사.md A-4). */
class StartBoostPingTest {

    @Test
    void delayFollowsThePing() {
        assertEquals(0, Race.startBoostDelay(10));
        assertEquals(2, Race.startBoostDelay(80));
        assertEquals(4, Race.startBoostDelay(900));
        assertEquals(0, Race.startBoostDelay(-1));
    }

    @Test
    void windowMovesWithTheDelay() {
        int go = Race.COUNTDOWN;
        assertTrue(Race.startBoostTimed(go - 5, 0));
        assertFalse(Race.startBoostTimed(go + 1, 0));
        // 80 ms: a W pressed at their own GO reaches the server 2 ticks after it
        assertTrue(Race.startBoostTimed(go + 2, 2));
        assertFalse(Race.startBoostTimed(go - 11, 2));   // held too long, as they saw it
        assertFalse(Race.startBoostTimed(-1, 2));
    }
}
