package dev.stsv.economy.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The customers' speech bubbles: never on top of each other, over the till's screen and buttons, waiting when there is no room. */
class BubblesTest {

    static final double FEET = 64, WANT = FEET + Bubbles.OVER_FEET, CEIL = FEET + Bubbles.CEILING;
    static final double ONE = Bubbles.tall("이거 계산이요~");

    /** A bubble already up at (x, z) from y. */
    static Bubbles.Band bubble(double x, double z, double y) {
        return new Bubbles.Band(x, z, y, y + ONE);
    }

    @Test
    void aLoneBubbleSitsOverTheHead() {
        assertEquals(WANT, Bubbles.place(0, 0, WANT, ONE, CEIL, List.of()), 1e-9);
        // someone far off is no matter
        assertEquals(WANT, Bubbles.place(0, 0, WANT, ONE, CEIL, List.of(bubble(3, 0, WANT))), 1e-9);
    }

    @Test
    void twoInLineStaggerAndAThirdWaits() {
        // the line: 1.2 apart. The first speaks over their head, the next one a row up, clear of it
        List<Bubbles.Band> up = new ArrayList<>();
        double a = Bubbles.place(-1.5, 9.9, WANT, ONE, CEIL, up);
        assertEquals(WANT, a, 1e-9);
        up.add(bubble(-1.5, 9.9, a));
        double b = Bubbles.place(-1.5, 11.1, WANT, ONE, CEIL, up);
        assertTrue(b >= a + ONE + Bubbles.GAP - 1e-9, "a row up: " + b);
        assertTrue(b - a < 0.45, "about a row (+0.35) up, not more: " + (b - a));
        assertTrue(b + ONE <= CEIL, "under the ceiling");
        assertFalse(up.getFirst().overlaps(b, b + ONE));
        up.add(bubble(-1.5, 11.1, b));
        // between them, no third row fits under the 3-block ceiling: they wait for one to fade
        assertTrue(Double.isNaN(Bubbles.place(-1.5, 10.5, WANT, ONE, CEIL, up)), "no room: wait");
        // the lower one faded: room again, over the head
        double c = Bubbles.place(-1.5, 10.5, WANT, ONE, CEIL, List.of(bubble(-1.5, 11.1, b)));
        assertEquals(WANT, c, 1e-9, "the lower row is free");
    }

    @Test
    void theCounterCustomersBubbleIsOverTheScreenAndTheButtons() {
        // the till's screen (StoreLayout: -2.6, 2.1 up, 7.4; three lines at 0.5) and three answer buttons over the counter's end
        List<Bubbles.Band> fixed = new ArrayList<>();
        fixed.add(new Bubbles.Band(-2.6, 7.4, FEET + 2.1, FEET + 2.1 + 0.5 * (3 * Bubbles.LINE + 0.05)));
        for (int i = 0; i < 3; i++) fixed.add(new Bubbles.Band(-2.2, 8.85, FEET + 1.75 + 0.3 * i, FEET + 1.75 + 0.3 * i + 0.45 * (Bubbles.LINE + 0.05)));
        double y = Bubbles.place(-1.5, 7.5, WANT, ONE, CEIL, fixed);
        assertFalse(Double.isNaN(y));
        for (Bubbles.Band f : fixed) assertFalse(f.overlaps(y, y + ONE), "clear of " + f);
        assertTrue(y > WANT, "raised");
        assertTrue(y + ONE <= CEIL, "under the ceiling");
        // the next in line (1.2 behind, near the screen too): no room beside the counter customer's raised bubble → waits
        List<Bubbles.Band> all = new ArrayList<>(fixed);
        all.add(bubble(-1.5, 7.5, y));
        assertTrue(Double.isNaN(Bubbles.place(-1.5, 8.7, WANT, ONE, CEIL, all)));
        // further back in line: free
        assertEquals(WANT, Bubbles.place(-1.5, 11.1, WANT, ONE, CEIL, all), 1e-9);
    }

    @Test
    void buttonsOverTheirOwnHeadPushTheBubbleUp() {
        // a lost 관광객 at a shelf: two buttons over their head from 2.05 up
        List<Bubbles.Band> b = List.of(new Bubbles.Band(1.5, 9.5, FEET + 2.05, FEET + 2.05 + 0.15), new Bubbles.Band(1.5, 9.5, FEET + 2.35, FEET + 2.35 + 0.15));
        double y = Bubbles.place(1.5, 9.5, WANT, ONE, CEIL, b);
        assertFalse(Double.isNaN(y));
        assertTrue(y >= FEET + 2.5 + Bubbles.GAP - 1e-9);
        assertTrue(y + ONE <= CEIL);
    }

    @Test
    void whatIsInTheWayOfASceneLine() {
        List<Bubbles.Band> others = List.of(bubble(0, 0, WANT), bubble(0.8, 0, WANT + 0.35), bubble(5, 0, WANT));
        assertEquals(List.of(0), Bubbles.inTheWay(0, 0.5, WANT, WANT + ONE, others), "the one at the same height nearby only");
        assertEquals(List.of(), Bubbles.inTheWay(9, 9, WANT, WANT + ONE, others));
    }

    @Test
    void longLinesStandTallerAndOnlyLinesWait() {
        assertTrue(Bubbles.tall("가".repeat(40)) > ONE, "wrapped onto a second line");
        assertTrue(Bubbles.tall("a\nb") > Bubbles.tall("a"));
        assertTrue(Bubbles.stillWaits(Bubbles.Kind.LINE, 0, Bubbles.WAIT));
        assertFalse(Bubbles.stillWaits(Bubbles.Kind.LINE, 0, Bubbles.WAIT + 1), "let go after a while");
        assertFalse(Bubbles.stillWaits(Bubbles.Kind.CHATTER, 0, 0), "chatter never waits");
        assertFalse(Bubbles.stillWaits(Bubbles.Kind.SCENE, 0, 0), "a scene's line never waits (it takes its place)");
    }

    // ---- the worker's choices: speech bubbles on the worker's side

    @Test
    void theWorkersChoicesStandInFrontOfTheCustomerFromChestToHead() {
        for (int n = 1; n <= 5; n++) {
            double[] ys = Bubbles.choiceColumn(n);
            assertEquals(n, ys.length);
            assertEquals(Bubbles.CHOICE_CHEST, ys[0], 1e-9, "the first is the lowest, at the chest");
            assertTrue(ys[n - 1] + Bubbles.choiceTall() <= Bubbles.CHOICE_HEAD + 1e-9, n + ": not over the head (the name tag stays free)");
            for (int i = 1; i < n; i++) {
                assertTrue(ys[i] > ys[i - 1]);
                if (n <= 4) assertTrue(ys[i] - ys[i - 1] >= Bubbles.choiceTall() + 0.01, n + " choices do not overlap");
            }
        }
        assertEquals(0, Bubbles.choiceColumn(0).length);
        assertTrue(Bubbles.CHOICE_HEAD < Bubbles.OVER_FEET, "under where the customer's own bubble (and their name) is");
        // towards the worker, never past them
        double[] at = Bubbles.choiceSpot(0, 0, 3, 4);
        assertEquals(Bubbles.CHOICE_OUT, Math.hypot(at[0], at[1]), 1e-9);
        assertEquals(0.36, at[0], 1e-9);
        assertEquals(0.48, at[1], 1e-9);
        double[] close = Bubbles.choiceSpot(0, 0, 0.4, 0);
        assertEquals(0.2, close[0], 1e-9, "a worker right at them: halfway");
        assertEquals(0, Bubbles.choiceSpot(1, 1, 1, 1)[0] - 1, 1e-9);
    }

    @Test
    void atTheTillTheChoicesAreOverTheWorkersEdge() {
        // over the counter's worker half: beside the POS (its screen ends at z 7.41), off the customer's face (they stand at x -1.5)
        assertTrue(Bubbles.CHOICE_TILL_X <= -2.7 && Bubbles.CHOICE_TILL_X > -3.0, "the worker's edge of the counter (x -3 .. -2)");
        assertTrue(Bubbles.CHOICE_TILL_Z - 0.5 > StoreLayout.POS_Z + StoreLayout.POS_WIDE / 2 - 0.3, "not on the POS");
        assertTrue(Bubbles.CHOICE_TILL_Y > StoreLayout.TOP + 0.3, "over the change keys and the mat");
        assertTrue(Math.abs(Bubbles.CHOICE_TILL_X - (-1.5)) > 1.0, "not in the customer's face");
        // four choices there are bands like any other: the counter customer's bubble goes over them, under the ceiling
        List<Bubbles.Band> fixed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double lo = FEET + Bubbles.CHOICE_TILL_Y + Bubbles.CHOICE_STEP * i;
            fixed.add(new Bubbles.Band(Bubbles.CHOICE_TILL_X, Bubbles.CHOICE_TILL_Z, lo, lo + Bubbles.choiceTall()));
        }
        for (int i = 1; i < 4; i++) assertFalse(fixed.get(i).overlaps(fixed.get(i - 1).lo(), fixed.get(i - 1).hi() - Bubbles.GAP - 1e-6) && Bubbles.CHOICE_STEP < Bubbles.choiceTall());
        double y = Bubbles.place(-1.5, 7.5, WANT, ONE, CEIL, fixed);
        assertFalse(Double.isNaN(y), "the customer still has room to speak");
        for (Bubbles.Band f : fixed) assertFalse(f.overlaps(y, y + ONE), "clear of " + f);
        assertTrue(y + ONE <= CEIL);
        // in the aisle the column is under the customer's own bubble: it need not move at all
        List<Bubbles.Band> aisle = new ArrayList<>();
        for (double lo : Bubbles.choiceColumn(3)) aisle.add(new Bubbles.Band(1.5 - Bubbles.CHOICE_OUT, 9.5, FEET + lo, FEET + lo + Bubbles.choiceTall()));
        assertEquals(WANT, Bubbles.place(1.5, 9.5, WANT, ONE, CEIL, aisle), 1e-9);
    }

    @Test
    void wordsAreSpokenThingsDoneAreNot() {
        assertTrue(Bubbles.action("(이번만 달아 준다)"));
        assertTrue(Bubbles.action(" (그냥 둔다) "));
        assertFalse(Bubbles.action("죄송합니다, 안 됩니다"));
        assertFalse(Bubbles.action("(웃으며) 안 됩니다"));
        assertFalse(Bubbles.action(null));
        // a choice is clicked where its text is: wider for longer words, never tiny
        assertTrue(Bubbles.choiceHalfWidth("판매") >= 0.22);
        assertTrue(Bubbles.choiceHalfWidth("죄송하지만 그건 어렵습니다") > Bubbles.choiceHalfWidth("판매"));
        assertEquals(9 * 6 / 40.0 * Bubbles.CHOICE_SCALE / 2 + 0.05, Bubbles.choiceHalfWidth("여섯글자말풍"), 1e-9);
        // aimed at: whole and a little larger; said: over the head in a few ticks, for about 2.5 s
        assertTrue(Bubbles.CHOICE_ALPHA < Bubbles.CHOICE_ALPHA_AIMED && Bubbles.CHOICE_AIMED > 1 && Bubbles.CHOICE_AIMED <= 1.2);
        assertEquals(6, Bubbles.CHOICE_FLY);
        assertEquals(50, Bubbles.CHOICE_SAID);
        assertTrue(Bubbles.CHOICE_SCALE <= Bubbles.SCALE);
    }
}
