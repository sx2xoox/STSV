package dev.stsv.economy.bus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StopQueueTest {

    @Test
    void theFirstToComeStandsAtTheFront() {
        StopQueue q = new StopQueue();
        q.sync(2, 1000);
        q.sync(3, 5000);
        assertEquals(List.of(1000L, 1000L, 5000L), q.since);
        assertEquals(1000, q.oldest());
        assertEquals(5000, q.at(2, 9000));
        assertEquals(9000, q.at(3, 9000));   // not counted yet: comes now
        q.take(1000);   // the first boards
        assertEquals(List.of(1000L, 5000L), q.since);
        q.sync(1, 9000);   // a count cut drops the newest
        assertEquals(List.of(1000L), q.since);
        q.take(1000);
        assertEquals(-1, q.oldest());
    }

    @Test
    void theLineRunsFromTheDoorBackAlongTheBay() {
        double out0 = 9.5;
        List<StopQueue.Spot> dry = StopQueue.line(5, 1, true, false, out0, 0.8, 24);
        assertEquals(5, dry.size());
        assertEquals(2.5, dry.getFirst().along(), 1e-9);   // the front: downstream (traffic runs +along on the right side)
        for (int i = 1; i < dry.size(); i++) {
            assertEquals(dry.get(i - 1).along() - 0.8, dry.get(i).along(), 1e-9);
            assertEquals(out0 - 0.5, dry.get(i).out(), 1e-9);   // between the bay and the shelter
        }
        // the left side of the road: traffic runs the other way, so does the line
        assertEquals(-2.5, StopQueue.line(1, -1, true, false, out0, 0.8, 24).getFirst().along(), 1e-9);
        // a long line never runs past the bay: the rest packed in the shelter
        List<StopQueue.Spot> long_ = StopQueue.line(20, 1, true, false, out0, 0.8, 24);
        assertEquals(20, long_.size());
        for (StopQueue.Spot s : long_) assertTrue(Math.abs(s.along()) <= 11, "inside the bay: " + s);
    }

    @Test
    void inTheRainTheyStandUnderTheRoof() {
        double out0 = 9.5;
        List<StopQueue.Spot> wet = StopQueue.line(6, 1, true, true, out0, 0.8, 24);
        for (int i = 0; i < 4; i++) {
            assertTrue(wet.get(i).roofed());
            assertEquals(out0 + 0.5, wet.get(i).out(), 1e-9);   // inside the shelter
        }
        assertEquals(1.2, wet.getFirst().along(), 1e-9);   // still the door's end first
        for (StopQueue.Spot s : wet) assertTrue(s.roofed());   // the rest under the eave
        // a simple stop (no bay): always inside first
        assertEquals(out0 + 0.5, StopQueue.line(1, 1, false, false, out0, 0.8, 24).getFirst().out(), 1e-9);
    }

    @Test
    void grandmasSitOnTheBenchTheRestKeepTheirOrder() {
        // resident, grandma, student, grandma, grandma, grandma (three seats)
        int[] where = StopQueue.assign(new boolean[]{false, true, false, true, true, true}, 3);
        assertArrayEquals(new int[]{0, -1, 1, -2, -3, 2}, where);
        assertEquals(3, StopQueue.bench(1, 9.5).size());
    }

    @Test
    void aLongWaitMakesThemCross() {
        BusSpec.Patience p = BusTest.spec().patience();
        assertEquals(80, p.mood(80, 0), 1e-9);
        assertEquals(80, p.mood(80, 3), 1e-9);                // grace
        assertEquals(67.5, p.mood(80, 9), 1e-9);              // halfway to the floor
        assertEquals(55, p.mood(80, 15), 1e-9);
        assertEquals(55, p.mood(80, 60), 1e-9);
        assertTrue(p.mood(80, 5) > p.mood(80, 10));
        assertFalse(p.fedUp(19.9));
        assertTrue(p.fedUp(20));
        assertTrue(p.chance() > 0 && p.chance() <= 1);
    }

    @Test
    void theRouteMapShowsAFewAroundHere() {
        List<String> names = List.of("A", "B", "C", "D", "E", "F", "G", "H");
        String row = StopBoard.row(names, 4, 2);
        assertTrue(row.contains("○ C") && row.contains("○ D") && row.contains("● 여기") && row.contains("○ F") && row.contains("○ G"), row);
        assertFalse(row.contains("○ B") || row.contains("○ H") || row.contains("○ E"), row);
        assertTrue(row.startsWith("<dark_gray>…") && row.endsWith("…</dark_gray>"), row);
        // at the start: the room goes to the far side (five shown)
        String start = StopBoard.row(names, 0, 2);
        assertTrue(start.contains("○ E") && !start.contains("○ F") && !start.startsWith("<dark_gray>…"), start);
        assertEquals("", StopBoard.row(names, -1, 2));
        assertEquals("<#ffd166><b>● 여기</b></#ffd166>", StopBoard.row(List.of("X"), 0, 2));
    }

    @Test
    void idleKindsAreKnownTypes() {
        BusSpec s = BusTest.spec();
        assertEquals("sit", s.queue().idleOf("grandma"));
        assertEquals("phone", s.queue().idleOf("student"));
        assertEquals("fidget", s.queue().idleOf("office"));
        assertEquals("hop", s.queue().idleOf("kinder"));
        assertEquals("", s.queue().idleOf("resident"));
        assertNotNull(s.lines(s.types().get("resident"), "give-up"));
        assertNotNull(s.lines(s.types().get("resident"), "waited"));
        assertTrue(s.board().refresh() >= 1);
    }
}
