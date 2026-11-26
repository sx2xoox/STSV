package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 도박장's server-side button aim (ButtonPick): ray vs boxes, the margin, the cone, whose buttons, one press a click. */
class ButtonPickTest {

    static final UUID ME = UUID.randomUUID(), OTHER = UUID.randomUUID();

    /** A row of buttons along +x at z = 0, 0.3 apart, 0.15 wide boxes each side of their middle; the sitter at z = +1.5. */
    static List<ButtonPick.Target> row(UUID viewer) {
        return List.of(
                new ButtonPick.Target(viewer, "die", -0.3, 1.1, 0, 1, 0, 0.15, 0.1, 0.08),
                new ButtonPick.Target(viewer, "call", 0, 1.1, 0, 1, 0, 0.15, 0.1, 0.08),
                new ButtonPick.Target(viewer, "half", 0.3, 1.1, 0, 1, 0, 0.15, 0.1, 0.08));
    }

    /** The sitter's eye (0, 1.6, 1.5) looking at (x, y, z). */
    static ButtonPick.Target look(double x, double y, double z, List<ButtonPick.Target> targets) {
        return ButtonPick.pick(0, 1.6, 1.5, x, y - 1.6, z - 1.5, ME, targets);
    }

    @Test
    void theRayInsideABoxPicksIt() {
        assertEquals("call", look(0, 1.1, 0, row(ME)).key());
        assertEquals("die", look(-0.3, 1.1, 0, row(ME)).key());
        assertEquals("half", look(0.38, 1.15, 0, row(ME)).key());
    }

    @Test
    void justOffTheEdgeTheMarginStillTakesIt() {
        // over the top of the middle button by a little (its box ends at 1.2): the grown box catches it
        assertEquals("call", look(0, 1.24, 0, row(ME)).key());
        // past the end of the row
        assertEquals("half", look(0.48, 1.1, 0, row(ME)).key());
    }

    @Test
    void theConeTakesANearMissAndNothingFurther() {
        List<ButtonPick.Target> one = List.of(new ButtonPick.Target(ME, "call", 0, 1.1, 0, 1, 0, 0.02, 0.02, 0.02));
        // about 3 degrees off (out of the grown box): still the button
        double r = Math.hypot(0.5, 1.5);
        double off = Math.tan(Math.toRadians(3)) * r;
        assertEquals("call", look(off, 1.1, 0, one).key());
        // 15 degrees off: nothing
        assertNull(look(Math.tan(Math.toRadians(15)) * r, 1.1, 0, one));
        // looking away entirely
        assertNull(ButtonPick.pick(0, 1.6, 1.5, 0, 0, 1, ME, row(ME)));
    }

    @Test
    void theNearestAlongTheRayWinsInsideBoxes() {
        // a chip row before the buttons, overlapping in line of sight: the nearer box (the chip) is entered first
        List<ButtonPick.Target> t = List.of(
                new ButtonPick.Target(ME, "call", 0, 1.1, 0, 1, 0, 0.15, 0.1, 0.08),
                new ButtonPick.Target(ME, "chip1", 0, 1.3, 0.5, 1, 0, 0.15, 0.1, 0.12));
        assertEquals("chip1", look(0, 1.1, 0, t).key());
    }

    @Test
    void theClosestToTheLookWinsAmongGrownBoxes() {
        // between two buttons, a hair nearer the right one
        assertEquals("half", look(0.16, 1.1, 0, row(ME)).key());
        assertEquals("call", look(0.14, 1.1, 0, row(ME)).key());
    }

    @Test
    void anotherViewersButtonsAreNeverPressed() {
        assertNull(look(0, 1.1, 0, row(OTHER)));
        List<ButtonPick.Target> both = new java.util.ArrayList<>(row(OTHER));
        both.add(new ButtonPick.Target(ME, "leave", 1.0, 1.1, 0, 1, 0, 0.15, 0.1, 0.08));
        assertNull(look(0, 1.1, 0, both));
        assertEquals("leave", look(1.0, 1.1, 0, both).key());
    }

    @Test
    void outOfReachIsNotPressed() {
        List<ButtonPick.Target> far = List.of(new ButtonPick.Target(ME, "call", 0, 1.6, -5, 1, 0, 0.3, 0.3, 0.3));
        assertNull(look(0, 1.6, -5, far));
    }

    @Test
    void aTurnedRowIsAimedInItsOwnFrame() {
        // a row along the diagonal: right (0.707, 0.707); a thin box (0.02 deep) aimed exactly
        double c = Math.sqrt(0.5);
        ButtonPick.Target t = new ButtonPick.Target(ME, "call", 0, 1.1, 0, c, c, 0.15, 0.1, 0.02);
        assertTrue(ButtonPick.hit(t, 0, 1.6, 1.5, 0.1 * c, -0.5, -1.5 + 0.1 * c, 0) >= 0);
        // 0.14 along its right is inside, 0.14 across it is not (only the margin/cone would take it)
        assertTrue(ButtonPick.hit(t, 0.14 * c, 3, 0.14 * c, 0, -1, 0, 0) >= 0);
        assertTrue(ButtonPick.hit(t, 0.14 * c, 3, -0.14 * c, 0, -1, 0, 0) < 0);
    }

    @Test
    void oneClickIsOnePress() {
        ButtonPick.Debounce d = new ButtonPick.Debounce();
        assertTrue(d.accept(ME, 100));
        assertFalse(d.accept(ME, 100));   // the same click's other event (right + swing, main + off hand)
        assertFalse(d.accept(ME, 101));
        assertTrue(d.accept(OTHER, 101));   // someone else is not held up
        assertTrue(d.accept(ME, 105));   // the next click
        // a held attack swings every tick: one press until it lets go
        assertFalse(d.accept(ME, 106));
        assertFalse(d.accept(ME, 107));
        assertFalse(d.accept(ME, 108));
        assertTrue(d.accept(ME, 112));
    }

    @Test
    void theHintIsQuiet() {
        ButtonPick.Hint h = new ButtonPick.Hint();
        assertTrue(h.due(ME, 0));
        assertFalse(h.due(ME, 2999));
        assertTrue(h.due(ME, 3000));
    }

    @Test
    void clickBoxesNeverOverlap() {
        // three boxes 0.25 apart on a diagonal row, each wanting 0.34: square boxes must shrink
        double c = Math.sqrt(0.5);
        double[] x = {-0.25 * c, 0, 0.25 * c}, z = {-0.25 * c, 0, 0.25 * c};
        double[] w = ButtonPick.fitBoxes(x, z, new double[]{0, 0, 0}, new double[]{0.3, 0.3, 0.3}, new double[]{0.34, 0.34, 0.34});
        for (int i = 0; i < 3; i++)
            for (int j = i + 1; j < 3; j++)
                assertTrue((w[i] + w[j]) / 2 <= Math.max(Math.abs(x[i] - x[j]), Math.abs(z[i] - z[j])) + 1e-9);
        // apart already, or one over the other: untouched
        double[] apart = ButtonPick.fitBoxes(new double[]{0, 1}, new double[]{0, 0}, new double[]{0, 0}, new double[]{1, 1}, new double[]{0.3, 0.3});
        assertEquals(0.3, apart[0], 1e-9);
        double[] stacked = ButtonPick.fitBoxes(new double[]{0, 0.1}, new double[]{0, 0}, new double[]{0, 1}, new double[]{0.5, 0.5}, new double[]{0.3, 0.3});
        assertEquals(0.3, stacked[1], 1e-9);
    }
}
