package dev.stsv.economy.secret.letter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "where I was a moment ago" ring behind the chase and the shadow, and the saved ritual targets. */
class LetterTrailTest {

    private static Trail.Step at(String w, double x, float yaw) {
        return new Trail.Step(w, x, 64, 0, yaw, 0, false, false);
    }

    @Test
    void delayReadsBackAndBlendsBetweenTicks() {
        Trail t = new Trail(40);
        assertNull(t.at(20));
        for (int i = 0; i <= 30; i++) t.add(at("w", i, 0));   // x = tick number, newest x = 30
        assertEquals(30, t.at(0).x(), 1e-9);
        assertEquals(10, t.at(20).x(), 1e-9);
        assertEquals(19.5, t.at(10.5).x(), 1e-9);
        // asking further back than is kept gives the oldest
        assertEquals(0, t.at(500).x(), 1e-9);
    }

    @Test
    void theRingForgetsTheOldest() {
        Trail t = new Trail(5);
        for (int i = 0; i < 12; i++) t.add(at("w", i, 0));
        assertEquals(5, t.size());
        assertEquals(11, t.back(0).x(), 1e-9);
        assertEquals(7, t.back(4).x(), 1e-9);
        assertEquals(7, t.back(99).x(), 1e-9);
    }

    @Test
    void yawBlendsTheShortWayRound() {
        Trail t = new Trail(4);
        t.add(at("w", 0, -170));
        t.add(at("w", 0, 170));
        assertEquals(180, Math.abs(t.at(0.5).yaw()), 1e-3);
    }

    @Test
    void anotherWorldIsNeverBlended() {
        Trail t = new Trail(4);
        t.add(at("nether", 100, 0));
        t.add(at("world", 0, 0));
        assertSame(t.back(0), t.at(0.5));
        assertEquals(0, t.moved(1), 1e-9);
    }

    @Test
    void swingsAndMovementAreSeen() {
        Trail t = new Trail(10);
        t.add(new Trail.Step("w", 0, 64, 0, 0, 0, false, true));
        t.add(at("w", 3, 0));
        t.add(at("w", 4, 0));
        assertTrue(t.swungBetween(0, 2));
        assertFalse(t.swungBetween(0, 1));
        assertEquals(4, t.moved(2), 1e-9);
    }

    @Test
    void targetsSurviveSaving() {
        for (Target x : new Target[]{Target.chunk("world", -3, 7), Target.land(12, "world", 40, -8), Target.near("world", 10, 70, -20, 4),
                Target.item("minecraft:wheat"), Target.anyItem(), Target.none()}) {
            assertEquals(x, Target.decode(x.encode()), x.encode());
        }
        assertEquals(Target.Kind.NONE, Target.decode("near world x").kind());
        assertEquals(Target.Kind.NONE, Target.decode(null).kind());
    }

    @Test
    void targetsKnowWhereTheyAre() {
        Target c = Target.chunk("world", -1, 2);
        assertTrue(c.contains("world", -16, 64, 32));
        assertTrue(c.contains("world", -1, 0, 47));
        assertFalse(c.contains("world", 0, 64, 32));
        assertFalse(c.contains("nether", -5, 64, 40));
        Target n = Target.near("world", 0, 64, 0, 4);
        assertTrue(n.contains("world", 3, 66, 2));
        assertFalse(n.contains("world", 4, 64, 3));
        assertFalse(n.contains("world", 0, 90, 0));
    }
}
