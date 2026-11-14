package dev.stsv.core.vehicle;

import java.util.List;

/**
 * How a pulled cart (리어카) follows the player holding its handles, one tick at a time. Pure (unit-tested against
 * the same small block world as {@link VehiclePhysics}).
 *
 * <ul>
 *   <li><b>Hitch</b> ({@link #target}): like a trailer, the cart turns about its wheels (its centre) towards the
 *       puller and keeps its handles' end, {@link VehicleSpec.Pull#hitch} ahead of the centre, where the puller
 *       stands. The handles are rigid, so walking back into it pushes it back.</li>
 *   <li><b>Catching up</b>: it turns at most {@link #MAX_TURN} degrees and moves at most {@link #MAX_MOVE} blocks a
 *       tick, so taking the handles from its side swings it round smoothly instead of jumping.</li>
 *   <li><b>No faster than its puller walks</b> ({@link #maxMove}): the cart is what makes pulling slow, so it never
 *       goes faster than the puller's own (slowed) walk plus a little slack. Whoever gets ahead of it some other way
 *       (letting go, running on and taking the handles again from as far as they reach; gliding; a knock) gains
 *       nothing: the cart comes after them at a walk, and past the release distance they have let go.</li>
 *   <li><b>The world</b>: the same moves as a driven vehicle ({@link VehiclePhysics#move}): gravity, a one-block step
 *       up, walls and fences clipped (never into a block), a turn that would push it into a block is not made; it
 *       stops before lava, water deeper than a block and unloaded chunks.</li>
 *   <li><b>Letting go</b> ({@link #gap}, {@link #tooFar}): when it is stuck and the puller walks on, the gap between the
 *       puller and its handles grows; past {@link VehicleSpec.Pull#release} they let go. The player is never pulled
 *       back.</li>
 * </ul>
 */
public final class CartPull {

    /** Blocks a tick the cart moves at most (a sprint-jump is about 0.4). */
    public static final double MAX_MOVE = 0.6;
    /** Degrees a tick it turns at most. */
    public static final float MAX_TURN = 24f;
    /** Closer than this to where it should be: it stays put (no jitter from a player standing still). */
    public static final double DEAD = 1e-4;
    /** Blocks a second a player walks for each point of their movement speed (the game: 0.1 walks 4.317). */
    public static final double WALK = 43.17;
    /** The cart may go this much faster than its puller walks: it swings wider than they turn, and catches up after a step. */
    public static final double SLACK = 1.25;
    /** Blocks a tick it may always move (a puller who can barely move still gets it to their hands). */
    public static final double MIN_MOVE = 0.05;

    private CartPull() {}

    /** The yaw (degrees, Minecraft: 0 = +z) of a direction (dx, dz): the inverse of {@link VehiclePhysics#forward}. */
    public static float yawOf(double dx, double dz) {
        return VehiclePhysics.wrap((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /**
     * Where the cart wants to be for a puller at (hx, hz): {x, z, yaw}. It faces from its centre towards the puller and
     * its centre lies `hitch` behind them on that line (the trailer's rule: the tractrix). A puller right on its
     * centre leaves its facing as it is.
     */
    public static double[] target(double x, double z, float yaw, double hx, double hz, double hitch) {
        double dx = hx - x, dz = hz - z;
        float y = Math.hypot(dx, dz) < 1e-3 ? yaw : yawOf(dx, dz);
        double[] f = VehiclePhysics.forward(y);
        return new double[]{hx - f[0] * hitch, hz - f[1] * hitch, y};
    }

    /**
     * The most a cart moves in a tick behind a puller with this movement speed: the attribute's value as it is now,
     * with the pulling slowdown (and a sprint, potions, soul speed) in it, as a walk, plus {@link #SLACK}; between
     * {@link #MIN_MOVE} and {@link #MAX_MOVE}.
     */
    public static double maxMove(double movementSpeed) {
        double walk = Math.max(0, movementSpeed) * WALK / 20;
        return Math.clamp(walk * SLACK, MIN_MOVE, MAX_MOVE);
    }

    /** {@link #step(VehiclePhysics.State, double, double, VehicleSpec, VehiclePhysics.Terrain, double)} at {@link #MAX_MOVE}. */
    public static void step(VehiclePhysics.State st, double hx, double hz, VehicleSpec s, VehiclePhysics.Terrain t) {
        step(st, hx, hz, s, t, MAX_MOVE);
    }

    /**
     * One tick of following the puller standing at (hx, hz): turn towards them, move towards the target with gravity,
     * steps and walls, stop at hazards; the wheels roll by the distance made. st.speed is set to the speed made along
     * its facing (blocks per second, negative = pushed back; 0 = it did not move).
     *
     * @param maxMove blocks it moves at most this tick ({@link #maxMove(double)})
     */
    public static void step(VehiclePhysics.State st, double hx, double hz, VehicleSpec s, VehiclePhysics.Terrain t, double maxMove) {
        maxMove = Math.clamp(maxMove, 0, MAX_MOVE);
        st.bumped = false;
        double hitch = s.pull().hitch();
        double[] want = target(st.x, st.z, st.yaw, hx, hz, hitch);
        float turn = Math.clamp(VehicleSeats.signed((float) want[2] - st.yaw), -MAX_TURN, MAX_TURN);
        if (Math.abs(turn) > 1e-3) {
            float yaw = VehiclePhysics.wrap(st.yaw + turn);
            if (!VehiclePhysics.collides(st.x, st.y, st.z, yaw, s, t)) st.yaw = yaw;
        }
        double dx = want[0] - st.x, dz = want[1] - st.z, d = Math.hypot(dx, dz);
        if (d < DEAD) {
            dx = dz = 0;
        } else if (d > maxMove) {
            dx *= maxMove / d;
            dz *= maxMove / d;
        }

        st.vy = st.onGround ? -VehiclePhysics.GRAVITY : Math.max(-VehiclePhysics.MAX_FALL, (st.vy - VehiclePhysics.GRAVITY) * VehiclePhysics.DRAG);
        List<double[]> sq = VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s);
        double[] m = VehiclePhysics.move(sq, dx, st.vy, dz, VehiclePhysics.canStep(st), s.stepHeight(), t);
        if ((m[0] != 0 || m[2] != 0) && VehiclePhysics.hazard(st.x + m[0], st.y + m[1], st.z + m[2], st.yaw, s, t)
                && !VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, s, t)) {
            m = VehiclePhysics.collide(sq, 0, st.vy, 0, t);   // not onto lava or into deep water: it stays at the edge
        }
        VehiclePhysics.land(st, sq, m, t);
        st.x += m[0];
        st.y += m[1];
        st.z += m[2];
        double[] f = VehiclePhysics.forward(st.yaw);
        double along = m[0] * f[0] + m[2] * f[1];
        st.speed = Math.abs(along) < 1e-6 && Math.hypot(m[0], m[2]) < 1e-6 ? 0 : along / VehiclePhysics.DT;
        st.roll = (st.roll + along / Math.max(0.05, s.wheelRadius())) % (Math.PI * 4);
    }

    /** Where the handles' end is now (where the puller should stand): {x, y, z} on the ground under it. */
    public static double[] hitch(VehiclePhysics.State st, VehicleSpec s) {
        return VehiclePhysics.world(st.x, st.y, st.z, st.yaw, new VehicleSpec.Vec(0, 0, s.pull().hitch()));
    }

    /** How far the puller's feet (px, py, pz) are from the handles' end (blocks). */
    public static double gap(VehiclePhysics.State st, VehicleSpec s, double px, double py, double pz) {
        double[] h = hitch(st, s);
        return Math.sqrt((px - h[0]) * (px - h[0]) + (py - h[1]) * (py - h[1]) + (pz - h[2]) * (pz - h[2]));
    }

    /** Whether the puller lets go: further from the handles than the cart's release distance. */
    public static boolean tooFar(VehiclePhysics.State st, VehicleSpec s, double px, double py, double pz) {
        return gap(st, s, px, py, pz) > s.pull().release();
    }
}
