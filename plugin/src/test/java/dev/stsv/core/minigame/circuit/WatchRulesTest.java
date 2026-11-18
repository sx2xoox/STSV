package dev.stsv.core.minigame.circuit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 관전: who may watch, the camera's order through the racers, the views, the chase camera, the free camera's box, leaving. */
class WatchRulesTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), D = UUID.randomUUID();

    @Test
    void theCameraCyclesThroughTheRaceInOrder() {
        List<UUID> order = List.of(A, B, C);
        assertEquals(B, WatchRules.next(order, A, 1));
        assertEquals(C, WatchRules.next(order, B, 1));
        assertEquals(A, WatchRules.next(order, C, 1), "round the end");
        assertEquals(C, WatchRules.next(order, A, -1), "backwards round the start");
        assertEquals(A, WatchRules.next(order, null, 1), "nobody followed yet: the leader");
        assertEquals(C, WatchRules.next(order, D, -1), "someone gone: from the end going back");
        assertEquals(A, WatchRules.next(List.of(A), A, 1), "alone: stays");
        assertNull(WatchRules.next(List.of(), A, 1));
    }

    @Test
    void whenTheFollowedRacerLeavesTheCameraTakesWhoeverIsInTheirPlace() {
        assertEquals(B, WatchRules.keep(List.of(A, B, C), B, 1), "still racing: kept");
        assertEquals(C, WatchRules.keep(List.of(A, C), B, 1), "B (2nd) left: the new 2nd");
        assertEquals(A, WatchRules.keep(List.of(A), C, 2), "past the end: the last there is");
        assertNull(WatchRules.keep(List.of(), A, 0));
    }

    @Test
    void whoMayStartWatching() {
        assertNull(WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 16, false, false, false, false));
        assertEquals("circuit.watch-off", WatchRules.check(false, WatchRules.Stage.RUNNING, 0, 16, false, false, false, false));
        assertEquals("circuit.already", WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 16, true, false, false, false), "racers do not watch");
        assertEquals("circuit.watch-already", WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 16, false, true, false, false));
        assertEquals("circuit.watch-lobby", WatchRules.check(true, WatchRules.Stage.LOBBY, 0, 16, false, false, false, false));
        assertEquals("circuit.watch-none", WatchRules.check(true, WatchRules.Stage.ENDING, 0, 16, false, false, false, false), "late joining while it runs only");
        assertEquals("circuit.watch-none", WatchRules.check(true, WatchRules.Stage.CLOSED, 0, 16, false, false, false, false));
        assertEquals("circuit.watch-full", WatchRules.check(true, WatchRules.Stage.RUNNING, 16, 16, false, false, false, false));
        assertNull(WatchRules.check(true, WatchRules.Stage.RUNNING, 15, 16, false, false, false, false));
        assertEquals("circuit.watch-full", WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 0, false, false, false, false), "max 0: nobody");
    }

    @Test
    void aWayBackIsNeverWrittenOver() {
        // the state saved when they came (ReturnPoint) must be the one they get back: whoever still carries one, or is
        // busy (dead, a spectator already, in an emote or the wardrobe) cannot start watching
        assertEquals("circuit.not-now", WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 16, false, false, true, false));
        assertEquals("circuit.not-now", WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 16, false, false, false, true));
        // being in a race beats everything (a racer's way back belongs to the race)
        assertEquals("circuit.already", WatchRules.check(true, WatchRules.Stage.RUNNING, 0, 16, true, false, true, true));
    }

    @Test
    void viewsAndLeaving() {
        assertEquals(WatchRules.View.ONBOARD, WatchRules.View.CHASE.next());
        assertEquals(WatchRules.View.FREE, WatchRules.View.ONBOARD.next());
        assertEquals(WatchRules.View.CHASE, WatchRules.View.FREE.next());
        assertTrue(WatchRules.View.CHASE.follows() && WatchRules.View.ONBOARD.follows());
        assertFalse(WatchRules.View.FREE.follows(), "free flight: sneaking is flying down, not leaving");
        assertFalse(WatchRules.leaves(-1, 100), "a first sneak only asks");
        assertTrue(WatchRules.leaves(100, 100 + WatchRules.LEAVE_TICKS));
        assertFalse(WatchRules.leaves(100, 101 + WatchRules.LEAVE_TICKS), "too late: asks again");
    }

    @Test
    void theChaseCameraHangsBehindAndSwingsRound() {
        // yaw 0 faces +z: behind is -z
        assertArrayEquals(new double[]{0, 12.3, -5}, WatchRules.chase(0, 10, 0, 0, 5, 2.3), 1e-9);
        // yaw 90 faces -x: behind is +x
        assertArrayEquals(new double[]{5, 10, 0}, WatchRules.chase(0, 10, 0, 90, 5, 0), 1e-9);
        assertEquals(10f, WatchRules.approach(0, 20, 0.5), 1e-4);
        assertEquals(0f, WatchRules.approach(350, 10, 0.5), 1e-4, "the short way round");
        assertEquals(350f, WatchRules.approach(10, 330, 0.5), 1e-4);
        assertEquals(20f, WatchRules.approach(0, 20, 2), 1e-4, "never past the target");
    }

    @Test
    void theFreeCameraStaysOverTheTrack() {
        assertNull(WatchRules.inside(5, 70, 5, 0, 10, 60, 90, 0, 10, 4));
        assertNull(WatchRules.inside(13, 70, -3, 0, 10, 60, 90, 0, 10, 4), "within the margin");
        assertArrayEquals(new double[]{14, 70, 0}, WatchRules.inside(40, 70, 0, 0, 10, 60, 90, 0, 10, 4), 1e-9);
        assertArrayEquals(new double[]{5, 56, 5}, WatchRules.inside(5, -64, 5, 0, 10, 60, 90, 0, 10, 4), 1e-9);
    }
}
