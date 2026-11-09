package dev.stsv.core.hud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bars' motion: a trail draining after a drop, a glow after a heal, in whole pixels per 2-tick step. */
class HudTrailTest {

    /** The trail's length after each step of these fills. */
    static List<Integer> trails(HudTrail t, int... fills) {
        List<Integer> out = new ArrayList<>();
        for (int f : fills) {
            t.step(f, false);
            out.add(t.trail(f));
        }
        return out;
    }

    @Test
    void aDropTrailsThenDrainsEasingOut() {
        HudTrail t = new HudTrail();
        t.step(62, false);
        assertTrue(t.settled());
        assertEquals(0, t.trail(62));
        // the fill drops at once (the bar draws 27); the 35 px lost stay for two steps, then halve each step
        assertEquals(List.of(35, 35, 17, 8, 4, 2, 1, 0, 0), trails(t, 27, 27, 27, 27, 27, 27, 27, 27, 27));
        assertTrue(t.settled());
        // the whole bar: gone in 0.8 s (8 steps of 2 ticks); a heart (7 px): 0.4 s
        HudTrail full = new HudTrail();
        full.step(69, false);
        assertEquals(List.of(69, 69, 34, 17, 8, 4, 2, 1, 0), trails(full, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        HudTrail heart = new HudTrail();
        heart.step(69, false);
        assertEquals(List.of(7, 7, 3, 1, 0), trails(heart, 62, 62, 62, 62, 62));
    }

    @Test
    void hitsInARowDrainFromWhereTheTrailIs() {
        HudTrail t = new HudTrail();
        t.step(69, false);
        trails(t, 48, 48, 48);   // trail end 69, 69, then 58
        assertEquals(10, t.trail(48));
        // another hit: the trail does not jump back up or start over; it keeps draining towards the new fill
        List<Integer> ends = new ArrayList<>();
        for (int f : new int[]{31, 31, 31, 31, 31, 31}) {
            t.step(f, false);
            ends.add(f + t.trail(f));
        }
        assertEquals(List.of(44, 37, 34, 32, 31, 31), ends);
        for (int i = 1; i < ends.size(); i++) assertTrue(ends.get(i) <= ends.get(i - 1), "never back up: " + ends);
        assertTrue(t.settled());
    }

    @Test
    void aRiseEndsTheTrailAndSmallChangesStayStill() {
        HudTrail t = new HudTrail();
        t.step(40, false);
        t.step(20, false);
        assertEquals(20, t.trail(20));
        t.step(45, false);    // healed past the trail
        assertEquals(0, t.trail(45));
        assertEquals(0, t.glow(45), "no glow without a heal big enough");
        assertTrue(t.settled());
        t.step(46, false);
        assertTrue(t.settled(), "a pixel of regeneration: nothing moves");
    }

    @Test
    void aHealGlowsAndFadesTheSameWay() {
        HudTrail t = new HudTrail();
        t.step(20, false);
        List<Integer> glows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            t.step(48, i == 0);
            glows.add(t.glow(48));
            assertEquals(0, t.trail(48));
        }
        assertEquals(List.of(28, 28, 14, 7, 3, 1, 0), glows);
        assertTrue(t.settled());
        // hurt while glowing: the glow goes, the trail takes over
        t.step(20, false);
        t.step(60, true);
        t.step(50, false);
        assertEquals(0, t.glow(50));
        assertEquals(10, t.trail(50));
    }

    @Test
    void motionStepsOncePerLoopAndSendsOnlyWhatChanges() {
        HudModule.Motion m = new HudModule.Motion();
        HudLayout.Health full = new HudLayout.Health(20, 20, 0, 20, 5f, 10, HudLayout.Tint.NORMAL, false);
        HudLayout.Health hurt = new HudLayout.Health(10, 20, 0, 19, 5f, 10, HudLayout.Tint.NORMAL, false);
        assertEquals(full, m.apply(full, 0));
        assertEquals(full, m.apply(full, 2), "settled: the same bars, so no packet (HudModule sends on a change)");
        List<HudLayout.Health> frames = new ArrayList<>(List.of(m.apply(hurt, 4)));
        HudLayout.Health first = frames.getFirst();
        assertEquals(first, m.apply(hurt, 4), "a second send in the same tick does not step it");
        for (long tick = 6; tick <= 30; tick += 2) frames.add(m.apply(hurt, tick));
        assertEquals(HudLayout.healthPx(full) - HudLayout.healthPx(hurt), first.anim().health(), "the part just lost");
        assertEquals(HudLayout.rightPx(full) - HudLayout.rightPx(hurt), first.anim().right(), "hunger trails too");
        // the held step repeats the first frame: nothing new to send; then each frame differs until it settles
        assertEquals(frames.get(0), frames.get(1));
        int sends = 1;
        for (int i = 1; i < frames.size(); i++) if (!frames.get(i).equals(frames.get(i - 1))) sends++;
        assertEquals(hurt, frames.getLast(), "settled: the plain bars");
        assertTrue(m.settled());
        assertTrue(sends <= 7, "a packet only for each visible step: " + sends);
        assertNotEquals(frames.get(1), frames.get(2));
        // riding: the right bar starts afresh with the mount's health (no trail from the hunger)
        HudLayout.Health riding = hurt.withMount(new HudLayout.Mount(5, 30));
        assertEquals(0, m.apply(riding, 32).anim().right());
        assertFalse(m.apply(riding, 34).anim().health() > 0);
    }
}
