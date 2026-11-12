package dev.stsv.economy.road;

/**
 * 국도: how much room a road leaves over itself (README "국도", 높이 여유). Pure (unit-tested: RoadClearanceTest reads the
 * vehicles from the core's content and drives them through what the road builds).
 *
 * <p>A vehicle's collision box is flat: its bottom is where the highest ground under any of its squares holds it up
 * (VehiclePhysics), its top that plus its height. On a grade the box of a long vehicle stands on the high end and reaches
 * over the low end at its full height: the box truck (6.5 long, 3 high) going down a tunnel at 1 in 3 has its rear on
 * ground two blocks higher than the road under its front. A ceiling that only follows the road under it (4 clear) then
 * stops it. So whatever the road builds over itself (a tunnel's ceiling, a street light's lantern and arm, cut banks;
 * a deck over another road: {@link RoadNetwork#CLEARANCE}) clears the <b>highest</b> surface within {@link #REACH} points
 * either way ({@link #ceilings}), by the tallest vehicle and its step ({@link #HEADROOM}): on the flat it is as before,
 * on a grade it steps up early and comes down late.
 */
public final class RoadClearance {

    /** The tallest vehicle's box (the box truck, the tractor: content/stsv/items/vehicles*.yml size[2]). */
    public static final double HEIGHT = 3.0;
    /** Over it: a step's lift (VehiclePhysics lifts by step-height, at most 1, to climb a half step). */
    public static final double MARGIN = 1.0;
    /** The clear height a vehicle needs over the highest ground under it. */
    public static final double HEADROOM = HEIGHT + MARGIN;
    /** The longest vehicle (the box truck: size[0]). */
    public static final double LENGTH = 6.5;
    /**
     * Points either way whose surface a ceiling over a point clears: the whole length (the vehicle's high end may be
     * anywhere along it) and two more (its squares' corners on a diagonal, a column's nearest point).
     */
    public static final int REACH = (int) Math.ceil(LENGTH) + 2;

    private RoadClearance() {}

    /** The highest surface top (blocks: {@link RoadSection#top}) within reach points either way of each point. */
    public static double[] highest(int[] halves, int reach) {
        int n = halves.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int m = Integer.MIN_VALUE;
            for (int j = Math.max(0, i - reach); j <= Math.min(n - 1, i + reach); j++) m = Math.max(m, halves[j]);
            out[i] = RoadSection.top(m);
        }
        return out;
    }

    /**
     * The lowest block y anything over the road may be in at each point (a tunnel's ceiling, a hanging lantern): its
     * highest surface within {@link #REACH} plus {@link #HEADROOM}, rounded up to a block. On the flat: 5 over a full
     * surface block, as the roads always were.
     */
    public static int[] ceilings(int[] halves) {
        double[] h = highest(halves, REACH);
        int[] out = new int[h.length];
        for (int i = 0; i < h.length; i++) out[i] = (int) Math.ceil(h[i] + HEADROOM - 1e-9);
        return out;
    }

    /** The ceiling roads were built with before (5 over the surface's block): what {@link RoadSection#rebore} mends. */
    public static int[] legacy(int[] halves) {
        int[] out = new int[halves.length];
        for (int i = 0; i < halves.length; i++) out[i] = RoadSection.surfaceY(halves[i]) + 5;
        return out;
    }
}
