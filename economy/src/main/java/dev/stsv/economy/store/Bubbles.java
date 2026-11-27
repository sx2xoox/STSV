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

    /** Whether a line said at `said` (ms) that found no room is still worth showing at `now`. */
    static boolean stillWaits(Kind kind, long said, long now) {
        return kind == Kind.LINE && now - said <= WAIT;
    }
}
