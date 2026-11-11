package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * How a vehicle moves, one server tick at a time. Pure (unit-tested against a small block world): no Bukkit types, the
 * world comes in through {@link Terrain}.
 *
 * <ul>
 *   <li><b>Speed</b> ({@link #nextSpeed}): W speeds up (gently towards the top speed), S brakes and, once stopped,
 *       reverses; W while reversing brakes; both keys brake; no key coasts down. In water, on soul sand or on rough
 *       ground (sand, gravel, snow, mud) the top speed drops ({@link #slowdown}: an all-terrain vehicle only in water and
 *       on sticky blocks); over it the vehicle brakes down to it.</li>
 *   <li><b>Turning</b> ({@link #yawRate}): A / D turn by the turn rate times how fast it goes (up to turn-speed), so it
 *       never turns in place; reversing turns the other way, like a car.</li>
 *   <li><b>Collision</b>: the footprint is a row of axis-aligned squares along the length ({@link #footprint}) that turn
 *       with the vehicle. Moves are clipped against the blocks' collision boxes axis by axis (as vanilla entities move),
 *       so it never goes into a wall, leaves or a fence; a turn that would push a square into a block is not made.</li>
 *   <li><b>Steps</b>: blocked sideways while on the ground, it tries the same move lifted by its step height and set down
 *       again (a block, a slab, stairs). Going down, it follows the ground for up to half a block instead of falling
 *       (stairs and slabs stay smooth); further down it falls with gravity (vanilla's 0.08 a tick, 2 % drag).</li>
 *   <li><b>Hazards</b>: it does not drive onto or into lava, into water deeper than a block, or into unloaded
 *       chunks (the terrain says unloaded blocks are solid): it stops there.</li>
 * </ul>
 */
public final class VehiclePhysics {

    public static final double DT = 0.05;              // seconds a tick
    public static final double GRAVITY = 0.08;          // blocks/tick², as vanilla entities
    public static final double DRAG = 0.98;
    public static final double MAX_FALL = 2.0;           // blocks/tick
    public static final double SNAP = 0.55;              // follows the ground down this far without falling
    public static final double MAX_STEER = 30;           // degrees the front wheels turn
    public static final double STEER_SPEED = 6;          // degrees a tick they turn towards it
    static final double EPS = 1e-7;

    private VehiclePhysics() {}

    /**
     * What a block is, besides its collision boxes. SLOW: sticky (honey, cobweb, powder snow, berry bushes), slows
     * everything; SINK: soul sand; ROUGH: loose or soft ground (sand, gravel, snow, mud). An all-terrain vehicle
     * ({@link VehicleSpec.Extras#allTerrain}) is not slowed by the last two.
     */
    public enum Medium { AIR, WATER, LAVA, SLOW, SINK, ROUGH }

    /** The top-speed factor on rough ground (sand, gravel, snow, mud) for a vehicle that is not all-terrain. */
    public static final double ROUGH_FACTOR = 0.75;

    /** The world as the physics sees it. */
    public interface Terrain {
        /**
         * Adds the collision boxes {minX, minY, minZ, maxX, maxY, maxZ} of the blocks overlapping the region to out.
         * A block in an unloaded chunk counts as a full block.
         */
        void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out);

        Medium medium(int x, int y, int z);
    }

    /** A vehicle's motion. speed in blocks per second (negative = reversing), yaw in degrees (Minecraft: 0 = +z). */
    public static final class State {
        public double x, y, z;
        public float yaw;
        public double speed;
        public double vy;
        public boolean onGround;
        /** front wheels' angle, degrees (+ = left) and the wheels' roll, radians */
        public double steer, roll;
        /** hit something hard this tick (speed lost) */
        public boolean bumped;

        public State(double x, double y, double z, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Speed and turning
    // ------------------------------------------------------------------------------------------

    /**
     * The speed after one tick (blocks per second).
     *
     * @param slow 1, or less in water / on soul sand ({@link #slowdown})
     * @param cap  the server's top speed (vehicles.max-speed)
     */
    public static double nextSpeed(double v, boolean forward, boolean backward, VehicleSpec s, double slow, double cap) {
        double max = Math.min(s.speed(), cap) * slow, rev = Math.min(s.reverseSpeed(), cap) * slow;
        double a = s.acceleration() * DT, b = s.brake() * DT, c = s.coast() * DT;
        if (forward && backward) {
            v = toward(v, 0, b);
        } else if (forward) {
            if (v < 0) v = Math.min(0, v + b);
            else if (v < max) v = Math.min(max, v + a * Math.max(0.35, 1 - 0.6 * v / Math.max(max, 1e-9)));
        } else if (backward) {
            if (v > 0) v = Math.max(0, v - b);
            else if (v > -rev) v = Math.max(-rev, v - a * 0.7);
        } else {
            v = toward(v, 0, c);
        }
        // over the top speed (water, soul sand, a lower cap): brake down to it
        if (v > max) v = Math.max(max, v - b);
        if (v < -rev) v = Math.min(-rev, v + b);
        return v;
    }

    private static double toward(double v, double target, double step) {
        return v > target ? Math.max(target, v - step) : Math.min(target, v + step);
    }

    /**
     * Yaw change for one tick (degrees). steer: +1 = A (left), -1 = D (right). Minecraft's yaw grows clockwise seen from
     * above, so a left turn going forward makes it smaller.
     */
    public static float yawRate(double v, int steer, VehicleSpec s) {
        if (steer == 0 || v == 0) return 0f;
        double f = Math.min(1, Math.abs(v) / s.turnSpeed());
        return (float) (-steer * s.turnRate() * f * Math.signum(v) * DT);
    }

    /** The front wheels' angle after one tick, turning towards the steer input. */
    public static double nextSteer(double now, int steer) {
        return toward(now, steer * MAX_STEER, STEER_SPEED);
    }

    // ------------------------------------------------------------------------------------------
    // Footprint
    // ------------------------------------------------------------------------------------------

    /** Forward direction (x, z) of a yaw: Minecraft's (-sin, cos). */
    public static double[] forward(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** A point on the vehicle (right, up, forward) in the world, for a vehicle at (x, y, z) with this yaw. */
    public static double[] world(double x, double y, double z, float yaw, VehicleSpec.Vec p) {
        double[] f = forward(yaw);
        // right of the forward direction (x, z) is (-z, x)... seen from above with +z down: right = (-f.z, f.x)
        double rx = -f[1], rz = f[0];
        return new double[]{x + f[0] * p.forward() + rx * p.right(), y + p.up(), z + f[1] * p.forward() + rz * p.right()};
    }

    /** The collision squares {minX, minY, minZ, maxX, maxY, maxZ} of a vehicle at (x, y, z) with this yaw. */
    public static List<double[]> footprint(double x, double y, double z, float yaw, VehicleSpec s) {
        double[] f = forward(yaw);
        double h = s.squareSize() / 2;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < s.squares(); i++) {
            double o = s.squareOffset(i);
            double cx = x + f[0] * o, cz = z + f[1] * o;
            out.add(new double[]{cx - h, y, cz - h, cx + h, y + s.height(), cz + h});
        }
        return out;
    }

    /** Does the vehicle at this place overlap a block? */
    public static boolean collides(double x, double y, double z, float yaw, VehicleSpec s, Terrain t) {
        List<double[]> sq = footprint(x, y, z, yaw, s);
        List<double[]> obs = obstacles(sq, 0, 0, 0, t);
        for (double[] a : sq) for (double[] b : obs) if (overlaps(a, b)) return true;
        return false;
    }

    static boolean overlaps(double[] a, double[] b) {
        double e = 1e-4;
        return a[0] < b[3] - e && a[3] > b[0] + e && a[1] < b[4] - e && a[4] > b[1] + e && a[2] < b[5] - e && a[5] > b[2] + e;
    }

    private static List<double[]> obstacles(List<double[]> squares, double dx, double dy, double dz, Terrain t) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] b : squares) {
            minX = Math.min(minX, b[0] + Math.min(0, dx));
            minY = Math.min(minY, b[1] + Math.min(0, dy));
            minZ = Math.min(minZ, b[2] + Math.min(0, dz));
            maxX = Math.max(maxX, b[3] + Math.max(0, dx));
            maxY = Math.max(maxY, b[4] + Math.max(0, dy));
            maxZ = Math.max(maxZ, b[5] + Math.max(0, dz));
        }
        List<double[]> out = new ArrayList<>();
        t.boxes(minX - EPS, minY - EPS, minZ - EPS, maxX + EPS, maxY + EPS, maxZ + EPS, out);
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Moving with collision (vanilla style: Y, then the larger horizontal axis first)
    // ------------------------------------------------------------------------------------------

    /** The part of (dx, dy, dz) the squares can move before touching an obstacle. */
    static double[] collide(List<double[]> squares, double dx, double dy, double dz, Terrain t) {
        List<double[]> obs = obstacles(squares, dx, dy, dz, t);
        List<double[]> sq = new ArrayList<>();
        for (double[] b : squares) sq.add(b.clone());
        if (dy != 0) {
            for (double[] b : sq) for (double[] o : obs) dy = clip(b, o, dy, 1);
            shift(sq, 0, dy, 0);
        }
        boolean xFirst = Math.abs(dx) >= Math.abs(dz);
        for (int pass = 0; pass < 2; pass++) {
            boolean doX = (pass == 0) == xFirst;
            if (doX && dx != 0) {
                for (double[] b : sq) for (double[] o : obs) dx = clip(b, o, dx, 0);
                shift(sq, dx, 0, 0);
            } else if (!doX && dz != 0) {
                for (double[] b : sq) for (double[] o : obs) dz = clip(b, o, dz, 2);
                shift(sq, 0, 0, dz);
            }
        }
        return new double[]{dx, dy, dz};
    }

    /** How far box b may move along axis a (0 x, 1 y, 2 z) by d before hitting o. */
    static double clip(double[] b, double[] o, double d, int a) {
        int p = (a + 1) % 3, q = (a + 2) % 3;
        // must overlap on the other two axes (touching does not count)
        if (!(b[p] < o[p + 3] - EPS && b[p + 3] > o[p] + EPS && b[q] < o[q + 3] - EPS && b[q + 3] > o[q] + EPS)) return d;
        if (d > 0 && o[a] >= b[a + 3] - EPS) return Math.min(d, o[a] - b[a + 3]);
        if (d < 0 && o[a + 3] <= b[a] + EPS) return Math.max(d, o[a + 3] - b[a]);
        return d;
    }

    private static void shift(List<double[]> sq, double dx, double dy, double dz) {
        for (double[] b : sq) {
            b[0] += dx;
            b[3] += dx;
            b[1] += dy;
            b[4] += dy;
            b[2] += dz;
            b[5] += dz;
        }
    }

    private static List<double[]> moved(List<double[]> sq, double dx, double dy, double dz) {
        List<double[]> out = new ArrayList<>();
        for (double[] b : sq) out.add(b.clone());
        shift(out, dx, dy, dz);
        return out;
    }

    /**
     * Moves (dx, dy, dz) as far as the blocks allow, stepping up ledges up to stepHeight when blocked sideways on the
     * ground. Returns the movement made {dx, dy, dz}.
     */
    static double[] move(List<double[]> sq, double dx, double dy, double dz, boolean onGround, double stepHeight, Terrain t) {
        double[] m = collide(sq, dx, dy, dz, t);
        boolean blockedSideways = Math.abs(m[0] - dx) > EPS || Math.abs(m[2] - dz) > EPS;
        boolean landing = dy < 0 && m[1] > dy + EPS;
        if (blockedSideways && (onGround || landing) && stepHeight > 0) {
            // lift, move sideways at that height, set down again: keep it if that got further
            double lift = collide(sq, 0, stepHeight, 0, t)[1];
            List<double[]> up = moved(sq, 0, lift, 0);
            double[] h = collide(up, dx, 0, dz, t);
            List<double[]> across = moved(up, h[0], 0, h[2]);
            double down = collide(across, 0, -lift - Math.max(0, -dy), 0, t)[1];
            if (h[0] * h[0] + h[2] * h[2] > m[0] * m[0] + m[2] * m[2] + EPS) return new double[]{h[0], lift + down, h[2]};
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Hazards and slowdowns
    // ------------------------------------------------------------------------------------------

    /** Lava under or in the footprint, or water deeper than a block there. */
    public static boolean hazard(double x, double y, double z, float yaw, VehicleSpec s, Terrain t) {
        for (double[] b : footprint(x, y, z, yaw, s)) {
            for (int bx = floor(b[0] + 1e-6); bx <= floor(b[3] - 1e-6); bx++) {
                for (int bz = floor(b[2] + 1e-6); bz <= floor(b[5] - 1e-6); bz++) {
                    int feet = floor(y + 0.1);
                    for (int by = floor(y - 0.6); by <= feet; by++) if (t.medium(bx, by, bz) == Medium.LAVA) return true;
                    if (t.medium(bx, feet, bz) == Medium.WATER && t.medium(bx, feet + 1, bz) == Medium.WATER) return true;
                }
            }
        }
        return false;
    }

    /**
     * The top-speed factor where the vehicle is: 0.4 in water, 0.5 on honey / cobwebs / powder snow and on soul sand,
     * 0.75 on sand, gravel, snow or mud, else 1. An all-terrain vehicle ignores soul sand and rough ground.
     */
    public static double slowdown(double x, double y, double z, float yaw, VehicleSpec s, Terrain t) {
        double f = 1;
        boolean allTerrain = s.extras().allTerrain();
        double[] fw = forward(yaw);
        for (int i = 0; i < s.squares(); i++) {
            double o = s.squareOffset(i);
            int bx = floor(x + fw[0] * o), bz = floor(z + fw[1] * o);
            Medium in = t.medium(bx, floor(y + 0.1), bz), under = t.medium(bx, floor(y - 0.1), bz);
            if (in == Medium.WATER) f = Math.min(f, 0.4);
            if (under == Medium.SLOW || in == Medium.SLOW) f = Math.min(f, 0.5);
            if (allTerrain) continue;
            if (under == Medium.SINK || in == Medium.SINK) f = Math.min(f, 0.5);
            if (under == Medium.ROUGH || in == Medium.ROUGH) f = Math.min(f, ROUGH_FACTOR);
        }
        return f;
    }

    static int floor(double d) {
        return (int) Math.floor(d);
    }

    // ------------------------------------------------------------------------------------------
    // One tick
    // ------------------------------------------------------------------------------------------

    /**
     * One tick: speed, turning, gravity, the move with collisions and steps, the wheels.
     *
     * @param cap the server's top speed (blocks/s)
     */
    public static void step(State st, DriveInput in, VehicleSpec s, Terrain t, double cap) {
        step(st, in, s, t, cap, 1f);
    }

    /** One tick, turning only turnScale (0 .. 1) as much as usual (a train near its jack-knife limit: {@link TrainPhysics}). */
    public static void step(State st, DriveInput in, VehicleSpec s, Terrain t, double cap, float turnScale) {
        st.bumped = false;
        double slow = slowdown(st.x, st.y, st.z, st.yaw, s, t);
        st.speed = nextSpeed(st.speed, in.forward(), in.backward(), s, slow, cap);

        float turn = yawRate(st.speed, in.steer(), s) * turnScale;
        if (turn != 0) {
            float yaw = wrap(st.yaw + turn);
            if (!collides(st.x, st.y, st.z, yaw, s, t)) st.yaw = yaw;
        }
        st.steer = nextSteer(st.steer, in.steer());

        // gravity: on the ground a small push down keeps it there (and finds out when the ground is gone)
        st.vy = st.onGround ? -GRAVITY : Math.max(-MAX_FALL, (st.vy - GRAVITY) * DRAG);
        double[] f = forward(st.yaw);
        double dist = st.speed * DT;
        double dx = f[0] * dist, dz = f[1] * dist;

        List<double[]> sq = footprint(st.x, st.y, st.z, st.yaw, s);
        double[] m = move(sq, dx, st.vy, dz, st.onGround, s.stepHeight(), t);
        // not onto lava or into deep water (unless already in it: then it may drive out)
        if ((m[0] != 0 || m[2] != 0) && hazard(st.x + m[0], st.y + m[1], st.z + m[2], st.yaw, s, t)
                && !hazard(st.x, st.y, st.z, st.yaw, s, t)) {
            m = collide(sq, 0, st.vy, 0, t);
            st.bumped = Math.abs(st.speed) > 2;
            st.speed = 0;
        }
        boolean wasOnGround = st.onGround;
        st.onGround = st.vy < 0 && m[1] > st.vy + EPS;
        if (!st.onGround && wasOnGround && m[1] <= 0) {
            // ground a little lower (going down a slab or stairs): follow it instead of falling
            double[] snap = collide(moved(sq, m[0], m[1], m[2]), 0, -SNAP, 0, t);
            if (snap[1] > -SNAP + EPS) {
                m[1] += snap[1];
                st.onGround = true;
            }
        }
        if (st.onGround || (st.vy > 0 && m[1] < st.vy - EPS)) st.vy = 0;

        // speed lost against a wall
        double want = Math.hypot(dx, dz), got = Math.hypot(m[0], m[2]);
        if (want > 1e-4 && got < want * 0.5 && !st.bumped) {
            st.bumped = Math.abs(st.speed) > 2;
            st.speed = st.speed * got / want;
            if (Math.abs(st.speed) < 0.05) st.speed = 0;
        }
        st.x += m[0];
        st.y += m[1];
        st.z += m[2];
        st.roll = (st.roll + Math.signum(st.speed) * got / Math.max(0.05, s.wheelRadius())) % (Math.PI * 4);
    }

    /** Whether something holds it up (a parked vehicle checks now and then: the block under it may be gone). */
    public static boolean supported(State st, VehicleSpec s, Terrain t) {
        double[] m = collide(footprint(st.x, st.y, st.z, st.yaw, s), 0, -0.05, 0, t);
        return m[1] > -0.05 + EPS;
    }

    public static float wrap(float yaw) {
        float y = yaw % 360f;
        return y < 0 ? y + 360f : y;
    }

    /**
     * Where a ray (eye, direction) first meets the vehicle's box (length x width x height, turned with it), as the
     * forward offset (blocks) of that point on the vehicle; NaN if it misses. Tells a click on the truck's bed from one
     * on its cab.
     */
    public static double hitForward(double x, double y, double z, float yaw, VehicleSpec s,
                                    double ex, double ey, double ez, double vx, double vy, double vz) {
        double[] f = forward(yaw);
        double rx = -f[1], rz = f[0];
        // into the vehicle's frame: (right, up, forward)
        double px = (ex - x) * rx + (ez - z) * rz, py = ey - y, pz = (ex - x) * f[0] + (ez - z) * f[1];
        double dxr = vx * rx + vz * rz, dyr = vy, dzr = vx * f[0] + vz * f[1];
        double[] lo = {-s.width() / 2, 0, -s.length() / 2}, hi = {s.width() / 2, s.height(), s.length() / 2};
        double[] p = {px, py, pz}, d = {dxr, dyr, dzr};
        double tmin = 0, tmax = 64;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (p[a] < lo[a] || p[a] > hi[a]) return Double.NaN;
                continue;
            }
            double t1 = (lo[a] - p[a]) / d[a], t2 = (hi[a] - p[a]) / d[a];
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
            if (tmin > tmax) return Double.NaN;
        }
        return pz + dzr * tmin;
    }
}
