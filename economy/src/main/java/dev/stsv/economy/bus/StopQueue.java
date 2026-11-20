package dev.stsv.economy.bus;

import java.util.ArrayList;
import java.util.List;

/**
 * 줄 서기 (README "마을버스"): where waiting passengers stand at a stop, in the order they came, and when each came (in
 * memory only, like the waiting numbers' fractions). The first to come stands at the front of the line, at the end of the
 * shelter the bus's door stops by (downstream: the way traffic runs on the stop's side); in the rain the line moves in under
 * the roof. Pure (unit-tested).
 */
final class StopQueue {

    /** A place to stand: along the stop and out from the road's middle (see {@link BusStop#at}); `roofed` under the shelter's roof. */
    record Spot(double along, double out, boolean roofed) {}

    /** Arrival times (millis) of the passengers counted at the stop, the first to come first. */
    final List<Long> since = new ArrayList<>();

    /** Matches the count of waiting passengers: newcomers come now, a count cut (an admin's test) drops the newest. */
    void sync(int count, long now) {
        while (since.size() < count) since.add(now);
        while (since.size() > Math.max(0, count)) since.removeLast();
    }

    /** When the passenger at place i in the line came (now when not counted). */
    long at(int i, long now) {
        return i >= 0 && i < since.size() ? since.get(i) : now;
    }

    /** One passenger who came at this time leaves the line (boarded, gave up). */
    void take(long when) {
        since.remove(Long.valueOf(when));
    }

    /** When the one waiting longest came, or -1. */
    long oldest() {
        return since.isEmpty() ? -1 : since.getFirst();
    }

    /**
     * The line's n places, front first. `side` +1/-1 is the stop's side (traffic runs +side along it, so its front is
     * downstream); `out0` the shelter's road side ({@link BusStop#shelterOut}). Dry, at a bay: one file on the strip between
     * the bay and the shelter (under the eave at first), `gap` apart, from 2.5 blocks downstream of the middle backwards
     * along the bay. In the rain, or at a simple stop (no bay: its strip is the road's verge): inside the shelter's front
     * row first (4 places), then the strip. Past the bay's end a second file stands inside.
     */
    static List<Spot> line(int n, int side, boolean bay, boolean rain, double out0, double gap, int bayLength) {
        List<Spot> out = new ArrayList<>();
        int f = side >= 0 ? 1 : -1;
        if (rain || !bay) for (double a : new double[]{1.2, 0.4, -0.4, -1.2}) {
            if (out.size() >= n) break;
            out.add(new Spot(f * a, out0 + 0.5, true));
        }
        double limit = bayLength / 2.0 - 1;
        for (int i = 0; out.size() < n; i++) {
            double a = 2.5 - gap * i;
            if (Math.abs(a) > limit) break;
            out.add(new Spot(f * a, out0 - 0.5, Math.abs(a) <= 2.5));
        }
        for (int i = 0; out.size() < n; i++) out.add(new Spot(f * (1.2 - 0.8 * (i % 4)), out0 + 0.5 + 0.3 * (i / 4 % 2), true));   // packed in
        return out;
    }

    /** The bench's seats (along the back wall inside the shelter), downstream first. */
    static List<Spot> bench(int side, double out0) {
        int f = side >= 0 ? 1 : -1;
        return List.of(new Spot(f * 1.0, out0 + 1.5, true), new Spot(0, out0 + 1.5, true), new Spot(-f * 1.0, out0 + 1.5, true));
    }

    /**
     * Who stands where: those who sit (in line order) take the bench's seats while there are any, everyone else (and a
     * sitter left without a seat) the line's places in order. Returns, for each passenger, -1 - seat for a bench seat, else
     * the index of their place in the line.
     */
    static int[] assign(boolean[] sits, int seats) {
        int[] out = new int[sits.length];
        int seat = 0, place = 0;
        for (int i = 0; i < sits.length; i++) {
            if (sits[i] && seat < seats) out[i] = -1 - seat++;
            else out[i] = place++;
        }
        return out;
    }
}
