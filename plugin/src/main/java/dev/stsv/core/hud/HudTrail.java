package dev.stsv.core.hud;

/**
 * One bar's motion, in whole pixels of its inside, stepped with the bar loop (every 2 ticks). When the fill drops
 * it drops at once and the part just lost stays behind it as a light trail: held for HOLD steps, then halved each
 * step (an ease-out: big steps first, single pixels last) until it is gone, 0.4 s for a heart and 0.8 s for the
 * whole bar. Another drop while it drains goes on from where the trail is; a rise past it ends it. A heal big
 * enough (jump) glows the same way from the old fill up to the new one.
 */
final class HudTrail {

    static final int HOLD = 2;

    private int last = -1;   // the fill at the last step (-1: none yet)
    private int end;         // the trail's end (> last while there is one)
    private int start;       // the glow's start (< last while there is one)
    private int holdEnd, holdStart;

    /** One step of the bar loop: the fill now, and whether it just rose enough to glow. */
    void step(int fill, boolean jump) {
        if (last < 0) {
            last = end = start = fill;
            return;
        }
        if (fill >= end) {
            end = fill;
            holdEnd = 0;
        } else {
            if (end == last) holdEnd = HOLD;   // settled until now: a new trail, held a moment
            if (holdEnd > 0) holdEnd--;
            else end = fill + (end - fill) / 2;
        }
        if (fill <= start || end > fill) {
            start = fill;                      // dropped below the glow, or a trail is draining: no glow
            holdStart = 0;
        } else if (start == last) {
            if (jump) holdStart = HOLD;        // a heal: the gained part glows
            else start = fill;
        }
        if (start < fill) {
            if (holdStart > 0) holdStart--;
            else start = fill - (fill - start) / 2;
        }
        last = fill;
    }

    /** The trail's length past this fill (0: none). */
    int trail(int fill) {
        return last < 0 ? 0 : Math.max(0, end - fill);
    }

    /** The glow's length below this fill (0: none). */
    int glow(int fill) {
        return last < 0 || end > fill ? 0 : Math.max(0, fill - start);
    }

    /** Nothing moving: no trail, no glow. */
    boolean settled() {
        return end == last && start == last;
    }
}
