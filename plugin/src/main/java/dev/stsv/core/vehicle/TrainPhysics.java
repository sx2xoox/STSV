package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * How an articulated vehicle moves ({@link TrainSpec}: a driven unit and towed units behind it), one tick at a time. Pure
 * (unit-tested against the same small block world as {@link VehiclePhysics}).
 *
 * <ul>
 *   <li><b>The driven unit</b> drives like any vehicle ({@link VehiclePhysics#step}: the driver's keys, gravity, steps,
 *       walls, hazards), its top speed lowered by the ground under the train ({@link #groundFactor}: farmland, mud...).</li>
 *   <li><b>Each towed unit</b> follows the hitch on the unit in front of it like the hand cart follows its puller
 *       ({@link CartPull#target}: it turns about its axle so its tongue points at the hitch, its axle a tongue's length
 *       behind it), with the same moves (gravity, a one-block step up, never into a block, not onto lava or into deep
 *       water). Reversing pushes it back the same way.</li>
 *   <li><b>Jack-knife limit</b>: driving never bends two units to more than {@link TrainSpec#jackknife} degrees: a
 *       towed unit is held at it about its hitch; steering that bends the train further turns slower from
 *       {@link TrainSpec#slowFrom} of the limit on and not at all at it ({@link #turnScale}). Only a block can push a
 *       unit further ({@link #OVER}).</li>
 *   <li><b>Pushed aside</b>: a towed unit that meets a block on its way swings about its hitch to the nearest place it
 *       fits ({@link #followOne}): it slides along a post or a wall's corner instead of holding the train.</li>
 *   <li><b>Stuck</b>: when a towed unit cannot get to its hitch even so (a gap too narrow, a ledge too high, lava
 *       ahead), the whole train stays where it was this tick and stops: no unit ever goes into a block and the
 *       train never comes apart.</li>
 * </ul>
 */
public final class TrainPhysics {

    /** A towed unit further than this from where its hitch wants it (blocks) is held back (the train stops). */
    public static final double TOLERANCE = 0.02;

    private TrainPhysics() {}

    /** The ground under the train, for its speed (the real world: {@link WorldGround}). */
    public interface Ground {
        /** The kind of the block at (x, y, z). */
        TrainSpec.Surface surface(int x, int y, int z);

        Ground NONE = (x, y, z) -> TrainSpec.Surface.OTHER;
    }

    // ------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------

    /** Where a unit's hitch is in the world: the trailer's hitch point on the unit in front of it. */
    public static double[] hitch(VehiclePhysics.State front, TrainSpec.Trailer trailer) {
        return VehiclePhysics.world(front.x, front.y, front.z, front.yaw, trailer.hitch());
    }

    /** The angle between two units (degrees, -180 .. 180): + = the front one turned right of the one behind it. */
    public static float angle(VehiclePhysics.State front, VehiclePhysics.State back) {
        return VehicleSeats.signed(front.yaw - back.yaw);
    }

    /**
     * Where a towed unit wants to be for its hitch at (hx, hz): {x, z, yaw}. Facing the hitch from its axle (the trailer's
     * rule, as {@link CartPull#target}), but never at more than cap degrees from the unit in front (frontYaw): then it is
     * turned to the limit about the hitch.
     */
    public static double[] target(double x, double z, float yaw, double hx, double hz, double tongue, float frontYaw, double cap) {
        double[] want = CartPull.target(x, z, yaw, hx, hz, tongue);
        float rel = VehicleSeats.signed(frontYaw - (float) want[2]);
        if (Math.abs(rel) > cap) {
            float y = VehiclePhysics.wrap(frontYaw - (float) (Math.signum(rel) * cap));
            double[] f = VehiclePhysics.forward(y);
            return new double[]{hx - f[0] * tongue, hz - f[1] * tongue, y};
        }
        return want;
    }

    /**
     * How much of a turn the driven unit makes (0 .. 1): all of it, unless it bends the train further (yawDelta the same
     * way as the angle to the first towed unit) past slowFrom of the limit; from there less and less, nothing at it.
     */
    public static float turnScale(float angle, float yawDelta, TrainSpec t) {
        if (yawDelta == 0 || Math.signum(yawDelta) != Math.signum(angle)) return 1f;
        double share = Math.abs(angle) / t.jackknife();
        if (share <= t.slowFrom()) return 1f;
        return (float) Math.clamp((1 - share) / (1 - t.slowFrom()), 0, 1);
    }

    /** The towed units laid out straight behind the driven one (a new train, or one whose saved layout does not fit). */
    public static List<VehiclePhysics.State> straight(VehiclePhysics.State head, TrainSpec t) {
        List<VehiclePhysics.State> out = new ArrayList<>();
        VehiclePhysics.State front = head;
        for (TrainSpec.Trailer tr : t.trailers()) {
            VehiclePhysics.State st = new VehiclePhysics.State(0, head.y, 0, front.yaw);
            place(st, front, tr);
            st.onGround = head.onGround;
            out.add(st);
            front = st;
        }
        return out;
    }

    /** Puts a towed unit on the line from its hitch along its own yaw (keeps its y), a tongue behind the hitch. */
    public static void place(VehiclePhysics.State st, VehiclePhysics.State front, TrainSpec.Trailer tr) {
        double[] h = hitch(front, tr);
        double[] f = VehiclePhysics.forward(st.yaw);
        st.x = h[0] - f[0] * tr.tongue();
        st.z = h[2] - f[1] * tr.tongue();
    }

    /**
     * The towed units of a saved train (their yaws and heights; their places follow from the hitches, so a train always
     * hangs together), or laid out straight when the saved ones do not fit the train any more (null, another count).
     */
    public static List<VehiclePhysics.State> restore(VehiclePhysics.State head, TrainSpec t, List<VehiclePhysics.State> saved) {
        if (saved == null || saved.size() != t.trailers().size()) return straight(head, t);
        List<VehiclePhysics.State> out = new ArrayList<>();
        VehiclePhysics.State front = head;
        for (int i = 0; i < saved.size(); i++) {
            VehiclePhysics.State s = saved.get(i);
            VehiclePhysics.State st = new VehiclePhysics.State(s.x, s.y, s.z, s.yaw);
            st.onGround = true;
            float rel = angle(front, st);
            double most = t.jackknife() + OVER;       // as far as a block may have pushed it (followOne)
            if (Math.abs(rel) > most) st.yaw = VehiclePhysics.wrap(front.yaw - (float) (Math.signum(rel) * most));
            place(st, front, t.trailers().get(i));
            out.add(st);
            front = st;
        }
        return out;
    }

    /** The top speed's share where the train is: the slowest ground under any of its units. */
    public static double groundFactor(VehiclePhysics.State head, VehicleSpec s, List<VehiclePhysics.State> units, Ground g) {
        TrainSpec t = s.train();
        double f = ground(head, s, t, g);
        for (int i = 0; i < units.size(); i++) f = Math.min(f, ground(units.get(i), t.trailers().get(i).unit(), t, g));
        return f;
    }

    private static double ground(VehiclePhysics.State st, VehicleSpec unit, TrainSpec t, Ground g) {
        double f = 1;
        double[] fw = VehiclePhysics.forward(st.yaw);
        int y = VehiclePhysics.floor(st.y - 0.1);
        for (int i = 0; i < unit.squares(); i++) {
            double o = unit.squareOffset(i);
            f = Math.min(f, t.ground(g.surface(VehiclePhysics.floor(st.x + fw[0] * o), y, VehiclePhysics.floor(st.z + fw[1] * o))));
        }
        return f;
    }

    // ------------------------------------------------------------------------------------------
    // One tick
    // ------------------------------------------------------------------------------------------

    /**
     * One tick of the whole train: the driven unit drives (head, its keys), the towed units follow. Returns false when a
     * towed unit held it back: then every unit is where it was before this tick and the train has stopped.
     *
     * @param cap the server's top speed (blocks/s)
     */
    public static boolean step(VehiclePhysics.State head, List<VehiclePhysics.State> units, DriveInput in, VehicleSpec s,
                               VehiclePhysics.Terrain t, Ground g, double cap) {
        return step(head, units, in, s, t, g, cap, 1);
    }

    /** One tick with a road's speed bonus for the driven unit (국도: {@link VehiclePhysics#step(VehiclePhysics.State, DriveInput, VehicleSpec, VehiclePhysics.Terrain, double, float, double, double)}). */
    public static boolean step(VehiclePhysics.State head, List<VehiclePhysics.State> units, DriveInput in, VehicleSpec s,
                               VehiclePhysics.Terrain t, Ground g, double cap, double bonus) {
        return step(head, units, in, s, t, g, cap, bonus, 1, 1);
    }

    /** One tick with the train's feel ({@link VehicleFeel}: its load and the slope) on the driven unit's top speed and acceleration. */
    public static boolean step(VehiclePhysics.State head, List<VehiclePhysics.State> units, DriveInput in, VehicleSpec s,
                               VehiclePhysics.Terrain t, Ground g, double cap, double bonus, double topScale, double accelScale) {
        TrainSpec train = s.train();
        List<VehiclePhysics.State> before = new ArrayList<>();
        before.add(copy(head));
        for (VehiclePhysics.State u : units) before.add(copy(u));

        double ground = groundFactor(head, s, units, g);
        float scale = units.isEmpty() ? 1f : turnScale(angle(head, units.getFirst()), VehiclePhysics.yawRate(head.speed, in.steer(), s), train);
        VehiclePhysics.step(head, in, s, t, Math.min(s.speed(), cap) * ground, scale, bonus, cap * ground, topScale, accelScale);
        if (follow(before.getFirst(), head, units, train, t)) return true;
        double speed = head.speed;
        set(head, before.getFirst());
        for (int i = 0; i < units.size(); i++) set(units.get(i), before.get(i + 1));
        head.bumped = Math.abs(speed) > 2;
        head.speed = 0;
        return false;
    }

    /**
     * Every towed unit follows its hitch (front to back), was: the driven unit before it moved this tick. false = one
     * could not get there: every towed unit is where it was (the caller undoes the driven one).
     */
    public static boolean follow(VehiclePhysics.State was, VehiclePhysics.State head, List<VehiclePhysics.State> units, TrainSpec t,
                                 VehiclePhysics.Terrain terrain) {
        // how far each unit was folded before this tick (by where it stood: its facing may have been waiting for room)
        double[] folded = new double[units.size()];
        boolean over = false;
        VehiclePhysics.State front = was;
        for (int i = 0; i < units.size(); i++) {
            VehiclePhysics.State u = units.get(i);
            TrainSpec.Trailer tr = t.trailers().get(i);
            double[] h = hitch(front, tr);
            folded[i] = Math.abs(VehicleSeats.signed(front.yaw - (float) CartPull.target(u.x, u.z, u.yaw, h[0], h[2], tr.tongue())[2]));
            over |= folded[i] > t.jackknife() + 1e-3;
            front = copy(u);
        }
        if (follow(head, units, t, terrain, folded, true)) return true;
        // a unit coming back to the limit swings the hitch of the one behind it, which may have no room for that: then
        // no unit comes back this tick
        return over && follow(head, units, t, terrain, folded, false);
    }

    /** Front to back; when a unit fails, all are put back where they were. */
    private static boolean follow(VehiclePhysics.State head, List<VehiclePhysics.State> units, TrainSpec t, VehiclePhysics.Terrain terrain,
                                  double[] folded, boolean ease) {
        List<VehiclePhysics.State> before = new ArrayList<>();
        for (VehiclePhysics.State u : units) before.add(copy(u));
        VehiclePhysics.State front = head;
        for (int i = 0; i < units.size(); i++) {
            if (!followOne(front, units.get(i), t.trailers().get(i), t.jackknife(), terrain, folded[i], ease)) {
                for (int k = 0; k < units.size(); k++) set(units.get(k), before.get(k));
                return false;
            }
            front = units.get(i);
        }
        return true;
    }

    /**
     * One towed unit after the unit in front of it moved: it goes where its hitch wants it ({@link #target}). When a
     * block is in the way there, the block pushes it aside: it swings about its hitch, a little at a time
     * ({@link #DEFLECT_STEP} degrees, to either side, the nearest first, at most {@link #DEFLECT_MAX} a tick and never
     * more than {@link #OVER} past the jack-knife limit), to the nearest place it fits. So a trailer that brushes a post or a wall's corner
     * slides along it instead of holding the train, and a train that was held can always back out. false = no place
     * within that swing (a gap too narrow for it, a wall straight ahead): the caller undoes the tick.
     */
    static boolean followOne(VehiclePhysics.State front, VehiclePhysics.State st, TrainSpec.Trailer tr, double cap, VehiclePhysics.Terrain t,
                             double folded, boolean ease) {
        st.bumped = false;
        double[] h = hitch(front, tr);
        // held at the limit. A unit a block had pushed past it (folded: how far it was folded before this tick) is not
        // thrown back: it comes back EASE degrees a tick (ease), or stays as far out as it was
        double held = Math.max(cap, Math.min(folded, cap + OVER) - (ease ? EASE : 0));
        if (reach(st, tr, target(st.x, st.z, st.yaw, h[0], h[2], tr.tongue(), front.yaw, held), t)) return true;
        // a block keeps it from the limit: just as its hitch pulls it, then swung aside from there
        double[] want = target(st.x, st.z, st.yaw, h[0], h[2], tr.tongue(), front.yaw, cap + OVER);
        if (reach(st, tr, want, t)) return true;
        for (float d = DEFLECT_STEP; d <= DEFLECT_MAX + 1e-3; d += DEFLECT_STEP) {
            for (int side = -1; side <= 1; side += 2) {
                float yaw = VehiclePhysics.wrap((float) want[2] + side * d);
                if (Math.abs(VehicleSeats.signed(front.yaw - yaw)) > cap + OVER) continue;
                double[] f = VehiclePhysics.forward(yaw);
                if (reach(st, tr, new double[]{h[0] - f[0] * tr.tongue(), h[2] - f[1] * tr.tongue(), yaw}, t)) return true;
            }
        }
        // squeezed between blocks, where it cannot swing at all: it goes where its hitch wants it facing as it does
        // (its way out of a gap it got into; it turns to its hitch again as soon as there is room)
        VehiclePhysics.State a = copy(st);
        if (reach(a, tr, want, t, 3)) {
            set(st, a);
            return true;
        }
        return false;
    }

    /** Degrees a towed unit swings about its hitch at a time, and at most in a tick, to get past a block. */
    public static final float DEFLECT_STEP = 0.75f, DEFLECT_MAX = 12f;

    /**
     * Degrees past the jack-knife limit a block may push a towed unit (a train folded to its limit against a post
     * could move neither way: backing up, the post now folds it a little further, as it would a real one), and the
     * degrees a tick it comes back to the limit when it is free again.
     */
    public static final float OVER = 15f, EASE = 1.5f;

    /**
     * Brings a towed unit to a place {x, z, yaw}: turning where it stands and then moving, or (when the turn would push
     * it into a block there) moving first and turning at the end. With gravity, steps and walls as any vehicle; not onto
     * lava or into deep water. false = it cannot get there this tick: then it is left as it was.
     */
    private static boolean reach(VehiclePhysics.State st, TrainSpec.Trailer tr, double[] want, VehiclePhysics.Terrain t) {
        for (int how = 0; how < 3; how++) {
            VehiclePhysics.State a = copy(st);
            if (reach(a, tr, want, t, how)) {
                set(st, a);
                return true;
            }
        }
        return false;
    }

    /**
     * A towed unit standing against a block may leave a turn this small (degrees) for a later tick, and one squeezed
     * between blocks a turn up to TURN_LAG_MAX ({@link #reach}).
     */
    public static final float TURN_LAG = 0.5f, TURN_LAG_MAX = 45f;

    /**
     * how: 0 = turn, then move; 1 = move, then turn; 2 = move and leave the turn for later, when it is at most
     * {@link #TURN_LAG} (a unit touching a block cannot swing the least bit towards it, but once it has moved off it
     * can: its place always follows its hitch, only its facing waits); 3 = the same up to {@link #TURN_LAG_MAX}.
     */
    private static boolean reach(VehiclePhysics.State st, TrainSpec.Trailer tr, double[] want, VehiclePhysics.Terrain t, int how) {
        boolean turnFirst = how == 0;
        VehicleSpec u = tr.unit();
        float yaw = VehiclePhysics.wrap((float) want[2]);
        boolean turns = Math.abs(VehicleSeats.signed(yaw - st.yaw)) > 1e-4;
        if (how >= 2) {
            if (!turns || Math.abs(VehicleSeats.signed(yaw - st.yaw)) > (how == 2 ? TURN_LAG : TURN_LAG_MAX)) return false;
            turns = false;
        }
        // a unit that is in a block already (a block set into it, a train saved that way) may turn and move as its
        // hitch says: that is its way out
        boolean inside = VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, u, t);
        if (turns && turnFirst) {
            if (!inside && VehiclePhysics.collides(st.x, st.y, st.z, yaw, u, t)) return false;   // the turn would push it into a block
            st.yaw = yaw;
        }
        double dx = want[0] - st.x, dz = want[1] - st.z;
        if (Math.hypot(dx, dz) < CartPull.DEAD) dx = dz = 0;

        st.vy = st.onGround ? -VehiclePhysics.GRAVITY : Math.max(-VehiclePhysics.MAX_FALL, (st.vy - VehiclePhysics.GRAVITY) * VehiclePhysics.DRAG);
        List<double[]> sq = VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, u);
        double[] m = VehiclePhysics.move(sq, dx, st.vy, dz, VehiclePhysics.canStep(st), u.stepHeight(), t);
        if ((m[0] != 0 || m[2] != 0) && VehiclePhysics.hazard(st.x + m[0], st.y + m[1], st.z + m[2], st.yaw, u, t)
                && !VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, u, t)) return false;   // not onto lava or into deep water
        VehiclePhysics.land(st, sq, m, t);
        st.x += m[0];
        st.y += m[1];
        st.z += m[2];
        if (turns && !turnFirst) {
            if (!inside && VehiclePhysics.collides(st.x, st.y, st.z, yaw, u, t)) return false;   // no room to turn there either
            st.yaw = yaw;
        }
        double[] f = VehiclePhysics.forward(st.yaw);
        double along = m[0] * f[0] + m[2] * f[1];
        st.speed = Math.abs(along) < 1e-6 && Math.hypot(m[0], m[2]) < 1e-6 ? 0 : along / VehiclePhysics.DT;
        st.roll = (st.roll + along / Math.max(0.05, u.wheelRadius())) % (Math.PI * 4);
        return Math.hypot(want[0] - st.x, want[1] - st.z) <= TOLERANCE;
    }

    /** Whether every towed unit stands on something (a parked train checks now and then: the ground may be gone). */
    public static boolean supported(List<VehiclePhysics.State> units, TrainSpec t, VehiclePhysics.Terrain terrain) {
        for (int i = 0; i < units.size(); i++) if (!VehiclePhysics.supported(units.get(i), t.trailers().get(i).unit(), terrain)) return false;
        return true;
    }

    /**
     * Whether a new train fits here: its towed units laid out straight behind the driven one (each set down on the ground
     * under it), none in a block or over lava / deep water. The units are settled onto the ground on the way.
     */
    public static boolean fits(List<VehiclePhysics.State> units, TrainSpec t, VehiclePhysics.Terrain terrain) {
        for (int i = 0; i < units.size(); i++) {
            VehiclePhysics.State st = units.get(i);
            VehicleSpec u = t.trailers().get(i).unit();
            st.y += VehiclePhysics.collide(VehiclePhysics.footprint(st.x, st.y + 1, st.z, st.yaw, u), 0, -2.5, 0, terrain)[1] + 1;
            if (VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, u, terrain) || VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, u, terrain)) return false;
        }
        return true;
    }

    /** The collision squares of a towed unit (land checks when placing). */
    public static List<double[]> footprint(VehiclePhysics.State st, TrainSpec.Trailer tr) {
        return VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, tr.unit());
    }

    static VehiclePhysics.State copy(VehiclePhysics.State s) {
        VehiclePhysics.State c = new VehiclePhysics.State(s.x, s.y, s.z, s.yaw);
        set(c, s);
        return c;
    }

    static void set(VehiclePhysics.State to, VehiclePhysics.State from) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.yaw = from.yaw;
        to.speed = from.speed;
        to.vy = from.vy;
        to.onGround = from.onGround;
        to.steer = from.steer;
        to.roll = from.roll;
        to.slip = from.slip;
        to.bumped = from.bumped;
        to.air = from.air;
    }
}
