package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * How a boat (통통배: a vehicle with {@code water:}) moves, one tick at a time. Pure (unit-tested against a small block
 * world): the world comes in through {@link Water}.
 *
 * <ul>
 *   <li><b>Floating</b>: the hull is felt at a few points along and across it ({@link #hull}); under each the column
 *       near the waterline says water (with its surface), ice, land, or something it must never enter. Afloat, the keel
 *       sits {@code draft} below the highest water surface under it and eases there (the water rising or falling).
 *       Rocking on the waves is drawn only ({@link BoatMotion}), the boat itself stays level.</li>
 *   <li><b>Where it goes</b>: only over water (source or flowing, anything at least a block deep enough to clear the
 *       keel). It never steps up: the shore (a block standing out of the water) stops it like a wall, and a point of the
 *       hull over land, air (a drop), lava, a waterfall (falling water) or a bubble column refuses the move (it stops
 *       there, it is not pulled in).</li>
 *   <li><b>Ice</b>: driven at ice it slides up onto it (half a block at most, from the water) and on ice it glides: the
 *       propeller barely pushes and it slows down slowly. It slides off into water again; never onto land.</li>
 *   <li><b>Aground</b> (its middle not over water: the water around it was taken away, or it slid onto a beach): it
 *       rests on the ground. It can only be nudged slowly ({@code nudge-speed}) towards the water, a move that keeps at
 *       least as many of its points over water as before; fully on land it cannot move at all. Nothing under it: it falls.</li>
 * </ul>
 */
public final class BoatPhysics {

    /** On ice: share of the top speed, of the push, and how fast it slows down by itself (blocks/s²). */
    public static final double ICE_TOP = 0.5, ICE_THRUST = 0.25, ICE_COAST = 0.35;
    /** How far it slides up from the water onto ice (blocks). */
    public static final double ICE_CLIMB = 0.5;
    /** Share of the way to the water's level made each tick, at most this many blocks a tick. */
    static final double FOLLOW = 0.3, MAX_RISE = 0.12;
    static final double EPS = 1e-7;
    /** Water less deep than this (a block's share) over the ground is too shallow to float in. */
    public static final double SHALLOW = 0.5;

    private BoatPhysics() {}

    /** What a block is to a boat. */
    public enum Cell { AIR, WATER, FALLING, BUBBLE, ICE, LAVA, SOLID }

    /** The world as a boat sees it: blocks' collision boxes (Terrain) and what each block is. */
    public interface Water extends VehiclePhysics.Terrain {
        /** A block in an unloaded chunk or outside the world is SOLID. */
        Cell cell(int x, int y, int z);

        /** How high the water stands in this block (0..1): a source 8/9, flowing water lower, a full column 1. */
        double level(int x, int y, int z);
    }

    /** Where the boat is: afloat, on ice, or aground (its middle over neither). */
    public enum Mode { FLOAT, ICE, AGROUND }

    /** What is under one point of the hull. */
    public enum Under { WATER, BUBBLE, ICE, LAND, BAD }

    /** One point of the hull: what is under it, and the top of it (the water's surface, the ice's top), NaN = nothing near. */
    public record Column(Under under, double top) {}

    /** What the whole hull is over: how many of its points over each, the highest surfaces, and its middle. */
    public static final class Hull {
        public int water, bubble, ice, land, bad;
        public double surface = Double.NEGATIVE_INFINITY, iceTop = Double.NEGATIVE_INFINITY;
        public Under centre = Under.LAND;
        /** the sum of the forward offsets (blocks) of its points over water: > 0 = the water is ahead */
        public double waterAhead;

        public Mode mode() {
            return centre == Under.WATER || centre == Under.BUBBLE ? Mode.FLOAT : centre == Under.ICE ? Mode.ICE : Mode.AGROUND;
        }

        /** Where its keel should be: draft under the water's surface, or on the ice; NaN = nothing holds it up. */
        public double level(double draft) {
            double t = Double.NEGATIVE_INFINITY;
            if (water + bubble > 0) t = surface - draft;
            if (ice > 0) t = Math.max(t, iceTop);
            return Double.isInfinite(t) ? Double.NaN : t;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Feeling the water
    // ------------------------------------------------------------------------------------------

    private static boolean wet(Cell c) {
        return c == Cell.WATER || c == Cell.BUBBLE || c == Cell.FALLING;
    }

    /**
     * The column under a point of the hull whose waterline is at wl: water rising above the waterline, else the first
     * thing at or under it (a block down).
     */
    public static Column column(Water w, int x, int z, double wl) {
        int top = VehiclePhysics.floor(wl) + 1;
        Cell c = w.cell(x, top, z);
        if (c == Cell.WATER || c == Cell.BUBBLE) {
            double s = wet(w.cell(x, top + 1, z)) ? top + 1 : top + w.level(x, top, z);
            return new Column(c == Cell.WATER ? Under.WATER : Under.BUBBLE, s);
        }
        if (c == Cell.FALLING || c == Cell.LAVA) return new Column(Under.BAD, Double.NaN);
        for (int y = top - 1; y >= top - 2; y--) {
            c = w.cell(x, y, z);
            switch (c) {
                case WATER -> {
                    double level = w.level(x, y, z);
                    // a thin film of flowing water on the ground (under half a block, nothing wet below): too shallow
                    if (level < SHALLOW && !wet(w.cell(x, y - 1, z))) return new Column(Under.LAND, y);
                    return new Column(Under.WATER, y + level);
                }
                case BUBBLE -> {
                    return new Column(Under.BUBBLE, y + w.level(x, y, z));
                }
                case FALLING, LAVA -> {
                    return new Column(Under.BAD, Double.NaN);
                }
                case ICE -> {
                    return new Column(Under.ICE, y + 1);
                }
                case SOLID -> {
                    return new Column(Under.LAND, y + 1);
                }
                case AIR -> { }
            }
        }
        return new Column(Under.LAND, Double.NaN);   // nothing near: a drop
    }

    /** The points the hull is felt at, as vehicle positions [right, 0, forward]: across each collision square, and its two ends. */
    public static List<VehicleSpec.Vec> points(VehicleSpec s) {
        List<VehicleSpec.Vec> out = new ArrayList<>();
        out.add(new VehicleSpec.Vec(0, 0, 0));   // the middle first
        double side = s.width() * 0.38;
        for (int i = 0; i < s.squares(); i++) {
            double f = s.squareOffset(i);
            for (double r : new double[]{-side, 0, side}) if (r != 0 || Math.abs(f) > 1e-9) out.add(new VehicleSpec.Vec(r, 0, f));
        }
        out.add(new VehicleSpec.Vec(0, 0, s.length() / 2 - 0.15));
        out.add(new VehicleSpec.Vec(0, 0, -s.length() / 2 + 0.15));
        return out;
    }

    /** What the hull of a boat with its keel at (x, y, z) and this yaw is over. */
    public static Hull hull(double x, double y, double z, float yaw, VehicleSpec s, Water w) {
        Hull h = new Hull();
        double wl = y + s.water().draft();
        boolean first = true;
        for (VehicleSpec.Vec p : points(s)) {
            double[] at = VehiclePhysics.world(x, y, z, yaw, p);
            Column c = column(w, VehiclePhysics.floor(at[0]), VehiclePhysics.floor(at[2]), wl);
            if (first) h.centre = c.under();
            first = false;
            switch (c.under()) {
                case WATER -> {
                    h.water++;
                    h.surface = Math.max(h.surface, c.top());
                    h.waterAhead += p.forward();
                }
                case BUBBLE -> {
                    h.bubble++;
                    h.surface = Math.max(h.surface, c.top());
                }
                case ICE -> {
                    h.ice++;
                    h.iceTop = Math.max(h.iceTop, c.top());
                }
                case LAND -> h.land++;
                case BAD -> h.bad++;
            }
        }
        return h;
    }

    /**
     * May the boat go from over `now` to over `next`? Never into lava, a waterfall or a new bubble column. Afloat or on
     * ice: never further onto land (no more points over it than now). Aground: only towards the water (at least as many points over water, no more over land).
     */
    public static boolean allowed(Hull now, Hull next, Mode mode) {
        return allowed(now, next, mode, 0);
    }

    /**
     * @param along how far the move goes along the boat's length (blocks, + = forward; 0 = turning on the spot). Aground,
     *              a move must go towards the water (where its points over water are) and a turn must bring more of it
     *              over water.
     */
    public static boolean allowed(Hull now, Hull next, Mode mode, double along) {
        if (next.bad > 0 || next.bubble > now.bubble) return false;
        if (mode == Mode.AGROUND) {
            if (next.water == 0 || next.water < now.water || next.land > now.land) return false;
            return along == 0 ? next.water > now.water : along * now.waterAhead > 0;
        }
        return next.land <= now.land;   // never further onto land (a boat partly over a beach may still go off it)
    }

    /** Can a boat be put in the water here: every point over water, nothing in the way. */
    public static boolean canLaunch(double x, double y, double z, float yaw, VehicleSpec s, Water w) {
        Hull h = hull(x, y, z, yaw, s, w);
        return h.water == points(s).size() && !VehiclePhysics.collides(x, y, z, yaw, s, w);
    }

    /**
     * Where a boat launched at (x, z) floats: its keel draft under the water whose surface is in or just under block y
     * (the block clicked), NaN when there is no water there.
     */
    public static double launchHeight(double x, int y, double z, VehicleSpec s, Water w) {
        Column c = column(w, VehiclePhysics.floor(x), VehiclePhysics.floor(z), y + 0.5);
        return c.under() == Under.WATER ? c.top() - s.water().draft() : Double.NaN;
    }

    // ------------------------------------------------------------------------------------------
    // One tick
    // ------------------------------------------------------------------------------------------

    /** Speed on ice: a weak push, a slow glide down to a stop. */
    public static double iceSpeed(double v, boolean forward, boolean backward, VehicleSpec s, double cap) {
        double max = Math.min(s.speed(), cap) * ICE_TOP, a = s.acceleration() * ICE_THRUST * VehiclePhysics.DT;
        if (forward && !backward && v < max) return Math.min(max, v + a);
        if (backward && !forward && v > -max) return Math.max(-max, v - a);
        double c = ICE_COAST * VehiclePhysics.DT;
        return v > 0 ? Math.max(0, v - c) : Math.min(0, v + c);
    }

    /**
     * One tick: speed by where it is, turning, the move (no steps; refused towards land, lava, falls), then the keel
     * eases to its level (or the boat falls when nothing holds it). Returns where it is now.
     *
     * @param cap the server's top speed (blocks/s)
     */
    public static Mode step(VehiclePhysics.State st, DriveInput in, VehicleSpec s, Water w, double cap) {
        st.bumped = false;
        Hull now = hull(st.x, st.y, st.z, st.yaw, s, w);
        Mode mode = now.mode();
        switch (mode) {
            case FLOAT -> st.speed = VehiclePhysics.nextSpeed(st.speed, in.forward(), in.backward(), s, 1, cap);
            case ICE -> st.speed = iceSpeed(st.speed, in.forward(), in.backward(), s, cap);
            case AGROUND -> {
                double top = Math.min(s.speed(), cap);
                st.speed = now.water == 0 ? 0
                        : VehiclePhysics.nextSpeed(st.speed, in.forward(), in.backward(), s, Math.min(1, s.water().nudge() / top), cap);
            }
        }

        float turn = VehiclePhysics.yawRate(st.speed, in.steer(), s) * (mode == Mode.ICE ? 0.5f : 1f);
        if (turn != 0) {
            float yaw = VehiclePhysics.wrap(st.yaw + turn);
            if (!VehiclePhysics.collides(st.x, st.y, st.z, yaw, s, w) && allowed(now, hull(st.x, st.y, st.z, yaw, s, w), mode)) st.yaw = yaw;
        }
        st.steer = VehiclePhysics.nextSteer(st.steer, in.steer());   // the rudder

        double[] f = VehiclePhysics.forward(st.yaw);
        double dist = st.speed * VehiclePhysics.DT;
        double dx = f[0] * dist, dz = f[1] * dist;
        List<double[]> sq = VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s);
        double[] m = {0, 0, 0};
        if (dx != 0 || dz != 0) {
            m = VehiclePhysics.collide(sq, dx, 0, dz, w);
            double want = dx * dx + dz * dz;
            if (m[0] * m[0] + m[2] * m[2] < want - EPS) {
                // blocked: up onto ice ahead (from the water, half a block at most), never up a shore
                Hull ahead = hull(st.x + dx, st.y, st.z + dz, st.yaw, s, w);
                double climb = ahead.ice > 0 ? ahead.iceTop - st.y : 0;
                if (climb > 0 && climb <= ICE_CLIMB + EPS) {
                    double lift = VehiclePhysics.collide(sq, 0, climb, 0, w)[1];
                    double[] h = VehiclePhysics.collide(lifted(sq, lift), dx, 0, dz, w);
                    if (h[0] * h[0] + h[2] * h[2] > m[0] * m[0] + m[2] * m[2] + EPS) m = new double[]{h[0], lift, h[2]};
                }
            }
            if (m[0] != 0 || m[2] != 0) {
                Hull next = hull(st.x + m[0], st.y + m[1], st.z + m[2], st.yaw, s, w);
                if (!allowed(now, next, mode, m[0] * f[0] + m[2] * f[1])) {
                    m = new double[]{0, 0, 0};
                    st.bumped = Math.abs(st.speed) > 2;
                    st.speed = 0;
                }
            }
            double got = Math.hypot(m[0], m[2]), wanted = Math.sqrt(want);
            if (wanted > 1e-4 && got < wanted * 0.5 && st.speed != 0) {
                st.bumped = Math.abs(st.speed) > 2;
                st.speed = st.speed * got / wanted;
                if (Math.abs(st.speed) < 0.05) st.speed = 0;
            }
        }
        st.x += m[0];
        st.y += m[1];
        st.z += m[2];

        // up or down to its level: eased afloat, resting on the ground in the shallows; nothing under it: it falls
        Hull at = hull(st.x, st.y, st.z, st.yaw, s, w);
        double level = at.level(s.water().draft());
        List<double[]> here = VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s);
        double dy;
        if (Double.isNaN(level)) {
            st.vy = st.onGround ? -VehiclePhysics.GRAVITY : Math.max(-VehiclePhysics.MAX_FALL, (st.vy - VehiclePhysics.GRAVITY) * VehiclePhysics.DRAG);
            dy = VehiclePhysics.collide(here, 0, st.vy, 0, w)[1];
            st.onGround = st.vy < 0 && dy > st.vy + EPS;
            if (st.onGround) st.vy = 0;
        } else {
            st.vy = 0;
            double d = level - st.y;
            dy = Math.abs(d) < 0.004 ? d : Math.clamp(d * FOLLOW, -MAX_RISE, MAX_RISE);
            if (Math.abs(d) >= 0.004 && Math.abs(dy) < 0.004) dy = Math.copySign(0.004, d);
            if (dy != 0) dy = VehiclePhysics.collide(here, 0, dy, 0, w)[1];
            st.onGround = true;
        }
        st.y += dy;
        st.roll = (st.roll + st.speed * VehiclePhysics.DT / Math.max(0.05, s.wheelRadius())) % (Math.PI * 4);
        return at.mode();
    }

    private static List<double[]> lifted(List<double[]> sq, double dy) {
        List<double[]> out = new ArrayList<>();
        for (double[] b : sq) out.add(new double[]{b[0], b[1] + dy, b[2], b[3], b[4] + dy, b[5]});
        return out;
    }
}
