package dev.stsv.economy.store;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a store customer's speech bubble goes so that no two stack on top of each other ("말풍선이 자주 겹치더라"). Pure
 * (unit-tested); {@link StoreBubbles} shows them.
 *
 * <p>Everything is in world blocks. A bubble is a band of height over the place it stands (x, z): from its bottom up by
 * {@link #tall} of its text. Other things already showing near it (closer than {@link #NEAR} sideways) are bands too: the
 * other customers' bubbles, the till's screen, the green answer buttons. A new bubble takes the lowest bottom from where it
 * wants to be (over the head) up to the ceiling that clears every band near it: over the head when nothing is in the way,
 * else just above the highest thing in the way (a stagger: one customer's bubble over the next one's). When there is no
 * room under the ceiling (the store is 3 blocks high: two rows at most) it has to wait until a bubble near it fades.
 */
final class Bubbles {

    private Bubbles() {
    }

    /** A bubble's bottom over the feet as BusModule.bubble puts it (riding the head, 0.55 up). */
    static final double OVER_FEET = 2.35;
    /** The store's ceiling over its floor (StoreLayout.ROOF: the roof blocks start 3 up). */
    static final double CEILING = StoreLayout.ROOF;
    /** Things closer than this sideways (blocks) are in each other's way (the line is 1.2 apart: neighbours are). */
    static final double NEAR = 2.0;
    /** Air kept between two bands (blocks). */
    static final double GAP = 0.08;
    /** How long an ordinary line may wait for room before it is let go (ms). */
    static final long WAIT = 3000;
    /** BusModule.bubble's text: 0.7 scale; a line is 10 px of 40 a block; text wraps at 200 px. */
    static final double SCALE = 0.7, LINE = 0.25, WRAP = 200;

    /** What a line is worth: chatter (browsing, snoring, everyone's "불 나갔어요?") is let go when there is no room now; a line
     *  waits for room up to {@link #WAIT}; a scene's line never waits (the bubbles in its way are taken down). */
    enum Kind { CHATTER, LINE, SCENE }

    /** Something showing near a place: where (x, z) and from `lo` up to `hi`. */
    record Band(double x, double z, double lo, double hi) {
        boolean near(double x2, double z2) {
            return Math.hypot(x - x2, z - z2) < NEAR;
        }

        boolean overlaps(double lo2, double hi2) {
            return lo2 < hi + GAP && hi2 + GAP > lo;
        }
    }

    /** How tall a bubble of this text stands (blocks): its lines (wrapped) and the background's edge. */
    static double tall(String text) {
        int lines = 0;
        for (String part : (text == null ? "" : text).split("\n", -1)) lines += Math.max(1, (int) Math.ceil(width(part) / WRAP));
        return SCALE * (Math.max(1, lines) * LINE + 0.05);
    }

    /** About how wide a line of text is in the font (px): Korean and other wide letters 9, the rest 6. */
    static double width(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); i++) w += s.charAt(i) >= 0x1100 ? 9 : 6;
        return w;
    }

    /**
     * The lowest bottom for a bubble `tall` high at (x, z), from `want` up while its top stays under `ceiling`, clear of every
     * band near; NaN when there is none.
     */
    static double place(double x, double z, double want, double tall, double ceiling, List<Band> others) {
        List<Band> near = new ArrayList<>();
        for (Band b : others) if (b.near(x, z)) near.add(b);
        List<Double> tries = new ArrayList<>();
        tries.add(want);
        for (Band b : near) if (b.hi() + GAP > want) tries.add(b.hi() + GAP);
        tries.sort(Double::compare);
        for (double y : tries) {
            if (y + tall > ceiling + 1e-9) break;
            boolean clear = true;
            for (Band b : near) if (b.overlaps(y, y + tall)) clear = false;
            if (clear) return y;
        }
        return Double.NaN;
    }

    /** Which of these bands are in the way of a bubble from `lo` to `hi` at (x, z) (their indexes). */
    static List<Integer> inTheWay(double x, double z, double lo, double hi, List<Band> others) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < others.size(); i++) if (others.get(i).near(x, z) && others.get(i).overlaps(lo, hi)) out.add(i);
        return out;
    }

    // ------------------------------------------------------------------------------------------ the worker's choices

    /**
     * What the worker could say to a customer: speech bubbles of the same look as the customers', a little smaller and half
     * see-through until aimed at, each with a small tail. They stand on the worker's side: in a short column just in front of
     * the customer (from the chest to the head) in the aisle, and over the counter's worker edge at the till — never on the
     * customer's name tag or face, the POS, the change keys.
     */
    static final double CHOICE_SCALE = 0.6, CHOICE_STEP = 0.27;
    /** The column in front of a customer in the aisle: this far towards the worker, its lowest bubble this high over the feet. */
    static final double CHOICE_OUT = 0.6, CHOICE_CHEST = 1.0, CHOICE_HEAD = 1.95;
    /** Over the counter's worker edge (the store's frame): clear of the POS (z up to 7.41) and of the customer (x -1.5). */
    static final double CHOICE_TILL_X = -2.8, CHOICE_TILL_Y = 1.42, CHOICE_TILL_Z = 7.98;
    /** Aimed at, a choice is whole and a little larger; spoken, it flies over the worker's head in this many ticks and stays so long. */
    static final double CHOICE_AIMED = 1.12;
    static final int CHOICE_FLY = 6, CHOICE_SAID = 50;
    /** How see-through a choice is (the background's alpha of 255): waiting, aimed at. */
    static final int CHOICE_ALPHA = 150, CHOICE_ALPHA_AIMED = 235;

    /** A choice that is something done, not said ("(이번만 달아 준다)"): shown small, grey and slanted, never spoken. */
    static boolean action(String label) {
        String t = label == null ? "" : label.strip();
        return t.startsWith("(") && t.endsWith(")");
    }

    /** How tall one choice stands (blocks). */
    static double choiceTall() {
        return CHOICE_SCALE * (LINE + 0.05);
    }

    /** Half a choice's width (blocks) for clicking: its text in the font at the choice's scale, and the bubble's edge. */
    static double choiceHalfWidth(String label) {
        return Math.clamp(width(label == null ? "" : label) / 40.0 * CHOICE_SCALE / 2 + 0.05, 0.22, 1.3);
    }

    /**
     * The bottoms of n choices over the feet, the first lowest: from the chest up, {@link #CHOICE_STEP} apart, closer when
     * that many would pass the head (never overlapping while they fit at all).
     */
    static double[] choiceColumn(int n) {
        double[] out = new double[Math.max(0, n)];
        if (n <= 0) return out;
        double room = CHOICE_HEAD - choiceTall() - CHOICE_CHEST;
        double step = n == 1 ? 0 : Math.min(CHOICE_STEP, room / (n - 1));
        for (int i = 0; i < n; i++) out[i] = CHOICE_CHEST + step * i;
        return out;
    }

    /** Where the column stands: {@link #CHOICE_OUT} from the customer (cx, cz) towards the worker (wx, wz): {x, z}. */
    static double[] choiceSpot(double cx, double cz, double wx, double wz) {
        double dx = wx - cx, dz = wz - cz, len = Math.hypot(dx, dz);
        if (len < 1e-6) return new double[]{cx, cz};
        double out = Math.min(CHOICE_OUT, len * 0.5);   // (never behind a worker who stands right at them)
        return new double[]{cx + dx / len * out, cz + dz / len * out};
    }

    /** Whether a line said at `said` (ms) that found no room is still worth showing at `now`. */
    static boolean stillWaits(Kind kind, long said, long now) {
        return kind == Kind.LINE && now - said <= WAIT;
    }
}
