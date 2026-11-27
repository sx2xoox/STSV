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
}
