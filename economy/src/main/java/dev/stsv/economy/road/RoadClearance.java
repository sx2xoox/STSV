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
    /**
     * The longest vehicle (the 리무진 관광버스: size[0]). Raised from the box truck's 6.5: roads built before reach 8
     * points, 0.5 short of its ends, which on the steepest grade (1 in 3) is under 0.2 blocks, inside the step's margin.
     */
    public static final double LENGTH = 8.5;
    /**
     * Points either way whose surface a ceiling over a point clears: the whole length (the vehicle's high end may be
     * anywhere along it) and two more (its squares' corners on a diagonal, a column's nearest point).
     */
    public static final int REACH = (int) Math.ceil(LENGTH) + 2;

    private RoadClearance() {}

    // ------------------------------------------------------------------------------------------
    // Room beside the road (README "국도", 굽은 길 여유): how far from the centre line nothing may stand
    // ------------------------------------------------------------------------------------------

    /**
     * The widest vehicle {@link #LONG} long or more (the luxury SUV, 2.7: content/stsv/items/vehicles*.yml size[1]). Its
     * collision squares are this wide times {@link #SQUARE} (VehiclePhysics: axis-aligned, they do not turn with it, so on a
     * slant they reach out {@link #diagonal} times as far as on a road along x or z: a bus 2.4 wide 1.53 to a side at 45
     * degrees, where its body reaches 1.2).
     */
    public static final double WIDTH = 2.7, SQUARE = 0.9, LONG = 4.5;
    /** The furthest from the centre line the middle of a vehicle runs in its lane: on the edge line. */
    public static final double LANE_EDGE = 2.0;
    /** And a little more (a driver steering A / D swings a few hundredths either side of his line). */
    public static final double SIDE_MARGIN = 0.1;
    /**
     * Round a bend more: a vehicle turns about its middle, so its ends swing out of the way its middle takes, and a driver
     * cuts the bend's inside and runs wide out of it (a bend's polyline corner is sharp: the road turns at a point). This
     * many blocks for every {@link #BEND_PER} degrees the road turns within {@link RoadSection#BEND_REACH} points either way,
     * at most {@link #BEND_MAX} (RoadBendTest measured: the long vehicles slowing for a bend reach 4.5 from the centre line
     * round a 30 to 60 degree corner, 5.5 out of a right angle).
     */
    public static final double BEND_PER = 30, BEND_MAX = 3;

    /** How many times as far as along x or z an axis-aligned square reaches across a road running at this heading (1 .. √2). */
    public static double diagonal(double hx, double hz) {
        double l = Math.hypot(hx, hz);
        return l < 1e-9 ? 1 : (Math.abs(hx) + Math.abs(hz)) / l;
    }

    /** The extra room round a bend that turns this many degrees. */
    public static double bend(double degrees) {
        return Math.clamp(degrees / BEND_PER, 0, BEND_MAX);
    }

    /**
     * How far from the centre line the vehicles reach (nothing may stand nearer): a vehicle on the edge line, its squares
     * at the road's slant (diag: {@link #diagonal}), round a bend turning `degrees`. Straight along x or z 3.32, on a
     * diagonal 3.82, more round a bend.
     */
    public static double reach(double diag, double degrees) {
        return LANE_EDGE + WIDTH * SQUARE / 2 * diag + SIDE_MARGIN + bend(degrees);
    }

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
