package dev.stsv.core.vehicle;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * How the 특별 탈것 ({@link FunSpec}: content/stsv/items/vehicles_fun.yml) move besides driving, one tick at a time. Pure
 * (unit-tested against a small block world): {@link VehicleModule} calls in around {@link VehiclePhysics#step}, and
 * {@link FunView} draws what is worked out here.
 *
 * <ul>
 *   <li><b>Hop</b> (스카이콩콩): ridden, it springs up again two ticks after every landing, to its bounce height; space
 *       pressed late in the fall makes the next bounce higher ({@link #bounce}), none lower again. Launched with the
 *       speed that reaches that height under the vehicles' own gravity ({@link #launchSpeed}).</li>
 *   <li><b>Ollie</b> (보드): space on the ground hops it a little; the board spins once round while it is up (drawn only).</li>
 *   <li><b>Hover</b> (빗자루, 풍선 의자): it floats at a height over whatever is under it (blocks, or water's surface:
 *       {@link #waterFloor}); the rider raises and lowers that height between the spec's min and max. So it can never
 *       be higher than max over the ground under it: over a drop it sinks with the ground ({@link #hover}).</li>
 *   <li><b>Amphibious</b> (장난감 오리): water's surface is a floor for it (its bottom draft under it), so it walks off a
 *       bank onto the water and climbs out again like steps; slower on land.</li>
 *   <li><b>Surface</b> (썰매): its top speed by what it slides on, faster downhill ({@link #surfaceFactor}).</li>
 *   <li><b>Toss</b> (바나나보트): the chance a rider behind the driver falls off in a hard turn ({@link #tossChance}).</li>
 *   <li><b>Buck</b> (로데오 황소): lurches one way and the other, stronger the longer it is ridden; the rider keeps their
 *       balance with A / D until they cannot ({@link #buck}).</li>
 * </ul>
 */
public final class FunPhysics {

    /** The highest a bounce may be set (blocks). */
    public static final double HOP_MAX = 6;
    /** The highest a hover may be set over the ground (blocks). */
    public static final double HOVER_MAX = 8;
    /** Ticks a bouncing vehicle stays down before it springs up (the spring squashed). */
    public static final int GROUND_TICKS = 2;
    /** How high water stands in its top block (a source block: 8/9). */
    public static final double SURFACE = 0.875;
    /** A popped balloon chair floats down this fast (blocks/s). */
    public static final double POP_SINK = 0.8;
    /** Ticks a popped balloon chair stands on the ground before its balloons are blown up again. */
    public static final int REFILL_TICKS = 100;
    /** How far the bucking rider leans per tick holding A or D (balance units: 1 = thrown). */
    public static final double CORRECT = 0.04;
    /** How fast an off-centre rider slides further over by their own weight (a share of their balance a tick). */
    public static final double TIP_OVER = 0.025;
    /** No toss in the first ticks after a rider got on. */
    public static final int TOSS_GRACE = 60;

    private FunPhysics() {}

    /** What a vehicle's behaviour is doing now (one per shown vehicle: {@link LiveVehicle#fun}). */
    public static final class State {
        /** hop: the bounce height now; ticks on the ground since landing; ticks since space was last pressed */
        public double hopLevel;
        public int grounded;
        public int pressAgo = 1000;
        /** space was down last tick (to tell a press) */
        public boolean jumpBefore;
        /** ollie: ticks of spin left, degrees turned each, how far round it is now (degrees) */
        public int spinLeft;
        public double spinStep, spin;
        /** hover: the height it is at over the ground now, the height asked for, whether it was ridden last tick */
        public double height, aim;
        public boolean wasRidden;
        /** a popped balloon chair: popped, ticks it has stood on the ground since */
        public boolean popped;
        public int poppedGround;
        /** the segway's tilt shown (degrees, + = forward), the speed last tick */
        public double tilt, speedBefore;
        /** a phase for rocking (waddle, bob, buck), radians */
        public double phase;
        /** amphibious: afloat now */
        public boolean floating;
        /** buck: ticks ridden, the rider's balance (-1..1, + = slid to the right), the lurch (+1 = spinning right, 0 = calm) */
        public int ridden;
        public double balance;
        public int lurch, lurchLeft, calmLeft;
        public double buckPitch, buckRoll, buckLift;
        /** events of this tick for the view: landed (with the height it fell from), launched, thrown off */
        public boolean landed, launched, thrown;
        public double fell;
        public double topY;
        /** buck: the bull's move now, ticks of it left, ticks to the next, its way (+1 / -1) */
        public Move move = Move.CALM;
        public int moveLeft, gap, way;
        /** tip: ticks steered fully at speed; a tumble's ticks left and how far it rolls over (degrees: 90 its side, 180 upside down) */
        public int hardTurn, tumble;
        public double tumbleRoll;
        /** the segway: ticks spun on the spot (it speeds up), how dizzy the rider is (0..1) */
        public int spinRun;
        public double dizzy;
        /** the banana boat's heel (degrees, + = to the left) and how fast it swings */
        public double roll, rollVel;
        /** the duck: quick squeaks in a row, when the last one was (ticks), ticks of its squash left */
        public int tootCombo, squash;
        public long lastToot = Long.MIN_VALUE / 2;
        /** free flight: its climb now (blocks/s) */
        public double freeVy;
        /** the skateboard: windmilling (0..1), clean landings in a row */
        public double wobble;
        public int tricks;
        /** keys asked of a free flyer this tick (space, Ctrl / looking down) */
        public boolean up, down;
        /** the kick scooter's kick (its phase 0..1, 0 = none), ticks of a squat left (landing), of the bull's victory */
        public double kick;
        public int crouchLeft, victory;
        /** events: tipped over, overloaded, bailed, crashed (this tick) */
        public boolean tipped, overload;
    }

    // ------------------------------------------------------------------------------------------
    // Tumbles: tipping over, crashing, the spring flopping
    // ------------------------------------------------------------------------------------------

    /** A tumble lasts this long: it falls over, lies there, rights itself. */
    public static final int TUMBLE_TICKS = 50;
    /** Ticks it takes to fall over, and to right itself at the end. */
    static final int FALL_TICKS = 6, RIGHT_TICKS = 10;

    /**
     * The kick scooter's tip: steered fully ({@code steer} +-1) at the tip speed or faster for the tip's ticks in a row.
     * Counts the ticks in the state; true when it goes over now.
     */
    public static boolean tipping(State fs, FunSpec.Tip t, int steer, double speed) {
        fs.hardTurn = steer != 0 && Math.abs(speed) >= t.speed() ? fs.hardTurn + 1 : 0;
        return fs.hardTurn >= t.ticks();
    }

    /** Starts a tumble rolling over by roll degrees (+ = onto its left side). */
    public static void tumble(State fs, double roll) {
        fs.tumble = TUMBLE_TICKS;
        fs.tumbleRoll = roll;
        fs.hardTurn = 0;
    }

    /** How far over it is now (degrees): falling with a bounce, lying, getting up again. */
    public static double tumbleAngle(State fs) {
        if (fs.tumble <= 0) return 0;
        int done = TUMBLE_TICKS - fs.tumble;
        double r = fs.tumbleRoll;
        if (done < FALL_TICKS) {
            double k = (done + 1.0) / FALL_TICKS;
            return r * k * k;
        }
        if (fs.tumble <= RIGHT_TICKS) return r * (fs.tumble - 1.0) / RIGHT_TICKS;
        // a little bounce as it lands, settling
        int since = done - FALL_TICKS;
        return r * (1 - 0.12 * Math.exp(-since / 3.0) * Math.cos(since * 1.4));
    }

    /** How far a tumbled vehicle is lifted so it lies on the ground (blocks): half its width on its side, its height upside down. */
    public static double tumbleLift(double angle, double width, double height) {
        // turned about the middle of its bottom: the lowest corner of its cross-section must come back up to the ground
        double a = Math.toRadians(angle);
        return width / 2 * Math.abs(Math.sin(a)) - Math.min(0, height * Math.cos(a));
    }

    // ------------------------------------------------------------------------------------------
    // The skateboard's bail
    // ------------------------------------------------------------------------------------------

    /** An ollie landed now bails: carving (steer +-1) at the bail speed or faster, and the roll r (0..1) under its chance. */
    public static boolean bails(FunSpec.Bail b, int steer, double speed, double r) {
        return steer != 0 && Math.abs(speed) >= b.speed() && r < b.chance();
    }

    /** How hard the rider windmills: in the air carving at the bail speed or faster (they might not make it). */
    public static double wobble(FunSpec.Bail b, boolean air, int steer, double speed, double was) {
        double target = air && steer != 0 && Math.abs(speed) >= b.speed() ? 1 : 0;
        return was + Math.clamp(target - was, -0.25, 0.25);
    }

    // ------------------------------------------------------------------------------------------
    // The segway's spin
    // ------------------------------------------------------------------------------------------

    /** Slower than this (blocks/s) a turn is a spin on the spot. */
    public static final double SPIN_SPEED = 0.8;
    /** Ticks of spinning to reach the full spin-up. */
    public static final int SPIN_UP_TICKS = 40;

    /**
     * The segway's turn this tick (degrees): on the spot it spins up, faster the longer it spins (up to spinUp times),
     * and the rider gets dizzier (1 after the dizzy ticks; it wears off at half that pace).
     */
    public static float spin(State fs, FunSpec.Dizzy d, int steer, double speed, VehicleSpec s) {
        boolean spinning = steer != 0 && Math.abs(speed) < SPIN_SPEED;
        fs.spinRun = spinning ? fs.spinRun + 1 : Math.max(0, fs.spinRun - 4);
        fs.dizzy = Math.clamp(fs.dizzy + (spinning ? 1.0 : -0.5) / d.ticks(), 0, 1);
        double up = 1 + (d.spinUp() - 1) * Math.min(1, (double) fs.spinRun / SPIN_UP_TICKS);
        return (float) (pivotTurn(steer, s) * (spinning ? up : 1));
    }

    // ------------------------------------------------------------------------------------------
    // The banana boat's heel
    // ------------------------------------------------------------------------------------------

    /**
     * The heel this tick (degrees, + = to the left): outwards in a turn (a right turn heels it left), more the harder and
     * faster, on a spring that overshoots and wobbles back; choppy at speed.
     *
     * @param turn the turn this tick (degrees, + = right)
     * @param chop a random number in -0.5 .. 0.5 (the waves)
     */
    public static double heel(State fs, FunSpec.Heel h, float turn, double speed, double top, double chop) {
        double share = Math.min(1, Math.abs(speed) / Math.max(1, top));
        double target = Math.signum(turn) * h.roll() * Math.min(1, Math.abs(turn) / (60 * VehiclePhysics.DT)) * share;
        fs.rollVel += (target - fs.roll) * 0.16 + chop * 0.9 * share;
        fs.rollVel *= 0.84;
        fs.roll = Math.clamp(fs.roll + fs.rollVel, -h.roll() * 1.4, h.roll() * 1.4);
        return fs.roll;
    }

    /** How much the heel adds to the chance of a toss: none upright, twice the chance heeled right over. */
    public static double heelToss(FunSpec.Heel h, double roll) {
        double k = Math.abs(roll) / h.roll();
        return Math.min(2, 2 * k * k);
    }

    /** Where a rider this high over the waterline (blocks) sways to with the heel: {right, up} offsets (blocks). */
    public static double[] sway(double roll, double height) {
        double a = Math.toRadians(roll);
        return new double[]{-Math.sin(a) * height, (Math.cos(a) - 1) * height};
    }

    // ------------------------------------------------------------------------------------------
    // The duck's squeaks
    // ------------------------------------------------------------------------------------------

    /** Squeaks this close together (ticks) are a combo; the sixth makes the duck jump. */
    public static final int COMBO_TICKS = 20, COMBO_JUMP = 6;

    /** A squeak now: how many in a row it is (1 = a first one). */
    public static int squeak(State fs, long now) {
        fs.tootCombo = now - fs.lastToot <= COMBO_TICKS ? fs.tootCombo + 1 : 1;
        fs.lastToot = now;
        fs.squash = 4;
        return fs.tootCombo;
    }

    /** The pitch of a squeak in a combo: two semitones up with each, at most 2. */
    public static float squeakPitch(float base, int combo) {
        return (float) Math.min(2.0, base * Math.pow(2, (combo - 1) * 2 / 12.0));
    }

    // ------------------------------------------------------------------------------------------
    // Jumps: the launch speed for a height
    // ------------------------------------------------------------------------------------------

    /** How high a launch at vy (blocks/tick) goes under the vehicles' gravity ({@link VehiclePhysics#step}: vy = (vy - g) drag, then move). */
    public static double peak(double vy) {
        double y = 0, top = 0;
        for (int i = 0; i < 400 && vy > 0; i++) {
            vy = (vy - VehiclePhysics.GRAVITY) * VehiclePhysics.DRAG;
            y += vy;
            top = Math.max(top, y);
        }
        return top;
    }

    /** Ticks from a launch at vy until it is back down where it started. */
    public static int flightTicks(double vy) {
        double y = 0;
        for (int i = 1; i < 400; i++) {
            vy = (vy - VehiclePhysics.GRAVITY) * VehiclePhysics.DRAG;
            y += vy;
            if (y <= 0) return i;
        }
        return 400;
    }

    /** The launch speed (blocks/tick) that reaches height blocks. */
    public static double launchSpeed(double height) {
        double lo = 0, hi = 3;
        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            if (peak(mid) < height) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** A press of space now (it was up last tick): kept in the state. */
    static boolean press(State fs, boolean jump) {
        boolean pressed = jump && !fs.jumpBefore;
        fs.jumpBefore = jump;
        fs.pressAgo = pressed ? 0 : Math.min(1000, fs.pressAgo + 1);
        return pressed;
    }

    /**
     * The next bounce's height: higher by gain when space was pressed in the last window ticks of the fall or on the
     * ground (pressAgo ticks ago), else lower by decay; within base .. max.
     */
    public static double bounce(FunSpec.Hop h, double level, int pressAgo) {
        boolean pumped = pressAgo <= h.window() + GROUND_TICKS;
        double next = pumped ? level + h.gain() : level - h.decay();
        return Math.clamp(next, h.base(), h.max());
    }

    /**
     * The pogo stick before the physics' tick: ridden, a bounce once it has been down {@link #GROUND_TICKS} (st.vy set to
     * its launch speed, off the ground); not ridden it stands still at its base height.
     */
    public static void hop(VehiclePhysics.State st, State fs, FunSpec.Hop h, boolean ridden, boolean jump) {
        press(fs, jump);
        fs.launched = false;
        if (fs.hopLevel < h.base()) fs.hopLevel = h.base();
        if (!ridden) {
            fs.hopLevel = h.base();
            fs.grounded = 0;
            return;
        }
        if (!st.onGround) {
            fs.grounded = 0;
            return;
        }
        if (++fs.grounded < GROUND_TICKS) return;
        fs.hopLevel = bounce(h, fs.hopLevel, fs.pressAgo);
        fs.pressAgo = 1000;   // one press, one bounce
        st.vy = launchSpeed(fs.hopLevel);
        st.onGround = false;
        st.air = VehiclePhysics.COYOTE + 1;   // no stepping up a ledge while it rises
        fs.grounded = 0;
        fs.launched = true;
    }

    /** The skateboard before the physics' tick: space on the ground hops it and starts the board's spin. */
    public static void ollie(VehiclePhysics.State st, State fs, FunSpec.Ollie o, boolean jump) {
        boolean pressed = press(fs, jump);
        fs.launched = false;
        if (pressed && st.onGround && fs.spinLeft == 0) {
            double vy = launchSpeed(o.height());
            st.vy = vy;
            st.onGround = false;
            st.air = VehiclePhysics.COYOTE + 1;
            int n = Math.max(4, flightTicks(vy));
            fs.spinLeft = n;
            fs.spinStep = o.spin() / n;
            fs.spin = 0;
            fs.launched = true;
        }
    }

    /** The board's spin this tick (degrees round, 0 when done: a whole turn ends where it began). */
    public static void spin(State fs) {
        if (fs.spinLeft <= 0) return;
        fs.spinLeft--;
        fs.spin = fs.spinLeft == 0 ? 0 : fs.spin + fs.spinStep;
    }

    /** After the physics' tick: whether it landed now (and from how high: the highest point since it left the ground). */
    public static void landing(VehiclePhysics.State st, State fs, boolean wasOnGround) {
        if (!st.onGround) {
            if (wasOnGround) fs.topY = st.y;
            fs.topY = Math.max(fs.topY, st.y);
        }
        fs.landed = st.onGround && !wasOnGround;
        fs.fell = fs.landed ? Math.max(0, fs.topY - st.y) : 0;
    }

    // ------------------------------------------------------------------------------------------
    // Water as a floor
    // ------------------------------------------------------------------------------------------

    /**
     * The terrain with the water's surface as a floor (its top draft under the surface, for each column's topmost water
     * block) and water no longer a medium (no slowdown, no "too deep" hazard: the floor holds it). Lava stays lava.
     */
    public static VehiclePhysics.Terrain waterFloor(VehiclePhysics.Terrain t, double draft) {
        return new VehiclePhysics.Terrain() {
            @Override
            public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
                t.boxes(minX, minY, minZ, maxX, maxY, maxZ, out);
                for (int x = VehiclePhysics.floor(minX); x <= VehiclePhysics.floor(maxX); x++) {
                    for (int z = VehiclePhysics.floor(minZ); z <= VehiclePhysics.floor(maxZ); z++) {
                        for (int y = VehiclePhysics.floor(minY); y <= VehiclePhysics.floor(maxY) + 1; y++) {
                            if (t.medium(x, y, z) != VehiclePhysics.Medium.WATER || t.medium(x, y + 1, z) == VehiclePhysics.Medium.WATER) continue;
                            double top = y + SURFACE - draft;
                            if (top > minY && top - 1 < maxY) out.add(new double[]{x, top - 1, z, x + 1, top, z + 1});
                        }
                    }
                }
            }

            @Override
            public VehiclePhysics.Medium medium(int x, int y, int z) {
                VehiclePhysics.Medium m = t.medium(x, y, z);
                return m == VehiclePhysics.Medium.WATER ? VehiclePhysics.Medium.AIR : m;
            }

            @Override
            public Ground ground(int x, int y, int z) {
                return t.ground(x, y, z);
            }
        };
    }

    /** Is the middle of the vehicle over water (at most a block under it)? */
    public static boolean overWater(double x, double y, double z, VehiclePhysics.Terrain t) {
        int bx = VehiclePhysics.floor(x), bz = VehiclePhysics.floor(z), by = VehiclePhysics.floor(y);
        for (int dy = 0; dy >= -1; dy--) {
            VehiclePhysics.Medium m = t.medium(bx, by + dy, bz);
            if (m == VehiclePhysics.Medium.WATER) return true;
            if (m != VehiclePhysics.Medium.AIR) return false;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Hover
    // ------------------------------------------------------------------------------------------

    /** The top of what is under the vehicle (blocks, a water floor), at most depth below its bottom; NaN = nothing there. */
    public static double groundBelow(double x, double y, double z, float yaw, VehicleSpec s, VehiclePhysics.Terrain t, double depth) {
        double dy = VehiclePhysics.collide(VehiclePhysics.footprint(x, y, z, yaw, s), 0, -depth, 0, t)[1];
        return dy > -depth + VehiclePhysics.EPS ? y + dy : Double.NaN;
    }

    /**
     * The height asked for this tick: someone getting on asks for start; space raises it, sink (Ctrl, looking down)
     * lowers it, within min .. max; nobody on it (or popped): the ground. Then the height itself follows at the climb
     * and sink rates (a popped chair sinks at {@link #POP_SINK}).
     */
    public static void aim(State fs, FunSpec.Hover h, boolean ridden, boolean up, boolean down) {
        if (ridden && !fs.wasRidden && !fs.popped) fs.aim = h.start();
        fs.wasRidden = ridden;
        if (!ridden || fs.popped) {
            fs.aim = 0;
        } else {
            if (up && !down) fs.aim += h.climb() * VehiclePhysics.DT;
            if (down && !up) fs.aim -= h.sink() * VehiclePhysics.DT;
            fs.aim = Math.clamp(fs.aim, h.min(), h.max());
        }
        double rate = (fs.aim > fs.height ? h.climb() : fs.popped ? POP_SINK : h.sink()) * VehiclePhysics.DT;
        fs.height = fs.aim > fs.height ? Math.min(fs.aim, fs.height + rate) : Math.max(fs.aim, fs.height - rate);
    }

    /** Easing towards the hover height: a share of the way each tick, at most this far up / down a tick. */
    static final double HOVER_EASE = 0.35, HOVER_RISE = 0.25, HOVER_FALL = 0.45;

    /**
     * One tick of a hovering vehicle: speed and turning as on the ground (no slowdown: it is not on it), up or down
     * towards the height over the ground under it, the move with collisions (stepping over small ledges). The terrain
     * should be a {@link #waterFloor} (water counts as ground). Over nothing within reach it sinks.
     */
    public static void hover(VehiclePhysics.State st, State fs, DriveInput in, VehicleSpec s, VehiclePhysics.Terrain t, double cap) {
        st.bumped = false;
        st.speed = VehiclePhysics.nextSpeed(st.speed, in.forward(), in.backward(), s, 1, cap);
        float turn = VehiclePhysics.yawRate(st.speed, in.steer(), s);
        if (turn != 0) {
            float yaw = VehiclePhysics.wrap(st.yaw + turn);
            if (!VehiclePhysics.collides(st.x, st.y, st.z, yaw, s, t)) st.yaw = yaw;
        }
        st.steer = VehiclePhysics.nextSteer(st.steer, in.steer());
        double reach = HOVER_MAX + 4;
        double ground = groundBelow(st.x, st.y + 0.01, st.z, st.yaw, s, t, reach);
        double target = Double.isNaN(ground) ? st.y - 1 : ground + fs.height;
        double d = target - st.y;
        double dy = Math.abs(d) < 0.01 ? d : Math.clamp(d * HOVER_EASE, -HOVER_FALL, HOVER_RISE);
        double[] f = VehiclePhysics.forward(st.yaw);
        double dist = st.speed * VehiclePhysics.DT;
        double dx = f[0] * dist, dz = f[1] * dist;
        List<double[]> sq = VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s);
        double[] m = VehiclePhysics.move(sq, dx, dy, dz, true, s.stepHeight(), t);
        if ((m[0] != 0 || m[2] != 0) && VehiclePhysics.hazard(st.x + m[0], st.y + m[1], st.z + m[2], st.yaw, s, t)
                && !VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, s, t)) {
            m = VehiclePhysics.collide(sq, 0, dy, 0, t);
            st.bumped = Math.abs(st.speed) > 2;
            st.speed = 0;
        }
        double want = Math.hypot(dx, dz), got = Math.hypot(m[0], m[2]);
        if (want > 1e-4 && got < want * 0.5 && !st.bumped) {
            st.bumped = Math.abs(st.speed) > 2;
            st.speed = st.speed * got / want;
            if (Math.abs(st.speed) < 0.05) st.speed = 0;
        }
        st.x += m[0];
        st.y += m[1];
        st.z += m[2];
        double under = groundBelow(st.x, st.y + 0.01, st.z, st.yaw, s, t, 0.06);
        st.onGround = !Double.isNaN(under) && st.y - under < 0.03;
        st.vy = 0;
        st.air = st.onGround ? 0 : st.air + 1;
        st.roll = (st.roll + Math.signum(st.speed) * got / Math.max(0.05, s.wheelRadius())) % (Math.PI * 4);
    }

    /** Settled: nobody on it, resting on the ground, not moving (it need not be ticked). */
    public static boolean hoverSettled(State fs, VehiclePhysics.State st) {
        return fs.aim == 0 && fs.height == 0 && st.onGround && st.speed == 0 && Math.abs(fs.freeVy) < 1e-3;
    }

    /** Free flight stays this far under the world's build limit. */
    public static final double CEILING = 4;

    /**
     * One tick of free flight (a hover that is {@code free}: the balloon chair): speed and turning as hovering; up and
     * down only by the rider (space up at the climb rate, sink down at the sink rate, nothing: it hangs where it is),
     * eased; just got on, it rises to its start height over the ground first. Nobody on it, or popped, it sinks. Never
     * over ceiling (the world's top less {@link #CEILING}).
     */
    public static void freeFly(VehiclePhysics.State st, State fs, FunSpec.Hover h, DriveInput in, boolean ridden, boolean up, boolean down,
                               VehicleSpec s, VehiclePhysics.Terrain t, double cap, double ceiling) {
        st.bumped = false;
        st.speed = VehiclePhysics.nextSpeed(st.speed, in.forward(), in.backward(), s, 1, cap);
        float turn = VehiclePhysics.yawRate(st.speed, in.steer(), s);
        if (turn != 0) {
            float yaw = VehiclePhysics.wrap(st.yaw + turn);
            if (!VehiclePhysics.collides(st.x, st.y, st.z, yaw, s, t)) st.yaw = yaw;
        }
        if (ridden && !fs.wasRidden && !fs.popped) fs.aim = h.start();   // the lift-off
        fs.wasRidden = ridden;
        double ground = groundBelow(st.x, st.y + 0.01, st.z, st.yaw, s, t, h.start() + 0.5);
        boolean lifting = ridden && !fs.popped && fs.aim > 0 && (Double.isNaN(ground) ? false : st.y - ground < fs.aim - 0.05);
        if (!lifting) fs.aim = 0;
        double target;
        if (!ridden || fs.popped) target = -(fs.popped ? POP_SINK : h.sink());
        else if (up && !down) target = h.climb();
        else if (down && !up) target = -h.sink();
        else target = lifting ? h.climb() : 0;
        fs.freeVy += (target - fs.freeVy) * 0.15;
        if (Math.abs(fs.freeVy) < 1e-3) fs.freeVy = 0;
        double dy = fs.freeVy * VehiclePhysics.DT;
        if (st.y + dy > ceiling) {
            dy = Math.max(0, ceiling - st.y);
            fs.freeVy = 0;
        }
        double[] f = VehiclePhysics.forward(st.yaw);
        double dist = st.speed * VehiclePhysics.DT;
        double dx = f[0] * dist, dz = f[1] * dist;
        List<double[]> sq = VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s);
        double[] m = VehiclePhysics.move(sq, dx, dy, dz, true, s.stepHeight(), t);
        if ((m[0] != 0 || m[2] != 0) && VehiclePhysics.hazard(st.x + m[0], st.y + m[1], st.z + m[2], st.yaw, s, t)
                && !VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, s, t)) {
            m = VehiclePhysics.collide(sq, 0, dy, 0, t);
            st.speed = 0;
        }
        double want = Math.hypot(dx, dz), got = Math.hypot(m[0], m[2]);
        if (want > 1e-4 && got < want * 0.5) {
            st.speed = st.speed * got / want;
            if (Math.abs(st.speed) < 0.05) st.speed = 0;
        }
        if (dy < 0 && m[1] > dy + 1e-7) fs.freeVy = 0;   // landed (or a roof above)
        st.x += m[0];
        st.y += m[1];
        st.z += m[2];
        double under = groundBelow(st.x, st.y + 0.01, st.z, st.yaw, s, t, 0.06);
        st.onGround = !Double.isNaN(under) && st.y - under < 0.03;
        st.vy = 0;
        st.air = st.onGround ? 0 : st.air + 1;
        fs.height = st.onGround ? 0 : 1;   // only "up or not" matters for a free flyer (settled: see hoverSettled)
    }

    // ------------------------------------------------------------------------------------------
    // Turning: on the spot, towards the look
    // ------------------------------------------------------------------------------------------

    /** The segway's turn this tick (degrees): A / D at the full turn rate whatever its speed (+1 = left: the yaw grows smaller). */
    public static float pivotTurn(int steer, VehicleSpec s) {
        return (float) (-steer * s.turnRate() * VehiclePhysics.DT);
    }

    /** A turn towards where the rider looks, at most the turn rate (degrees this tick). */
    public static float lookTurn(float yaw, float look, VehicleSpec s) {
        float d = VehicleSeats.signed(look - yaw);
        float max = (float) (s.turnRate() * VehiclePhysics.DT);
        return Math.abs(d) < 0.5f ? 0f : Math.clamp(d, -max, max);
    }

    // ------------------------------------------------------------------------------------------
    // Surfaces (the sled)
    // ------------------------------------------------------------------------------------------

    /** What a block is to a sled. */
    public enum Ground { SNOW, ICE, OTHER }

    /** The share of the top speed a block gives the sled. */
    public static double factor(FunSpec.Surface s, Ground g) {
        return switch (g) {
            case SNOW -> s.snow();
            case ICE -> s.ice();
            case OTHER -> s.other();
        };
    }

    /**
     * The sled's top speed factor where it is: the mean over its collision squares of what each slides on (a snow
     * layer in the block it is in, else the block under it), faster downhill (grade below 0) by downhill per unit.
     */
    public static double surfaceFactor(double x, double y, double z, float yaw, VehicleSpec s, FunSpec.Surface surf,
                                       VehiclePhysics.Terrain t, double grade) {
        double sum = 0;
        double[] fw = VehiclePhysics.forward(yaw);
        for (int i = 0; i < s.squares(); i++) {
            double o = s.squareOffset(i);
            int bx = VehiclePhysics.floor(x + fw[0] * o), bz = VehiclePhysics.floor(z + fw[1] * o);
            Ground in = t.ground(bx, VehiclePhysics.floor(y + 0.05), bz);
            Ground g = in != Ground.OTHER ? in : t.ground(bx, VehiclePhysics.floor(y - 0.05), bz);
            sum += factor(surf, g);
        }
        double f = sum / s.squares();
        if (grade < 0) f *= 1 + surf.downhill() * Math.min(1, -grade);
        return f;
    }

    // ------------------------------------------------------------------------------------------
    // Toss (the banana boat)
    // ------------------------------------------------------------------------------------------

    /**
     * The chance this tick that a rider behind the driver falls off: none below minSpeed or under minTurn degrees a
     * second, else the spec's chance, more the faster and the harder the turn (at most 0.5).
     */
    public static double tossChance(FunSpec.Toss t, double speed, double turnPerSecond) {
        double v = Math.abs(speed), turn = Math.abs(turnPerSecond);
        if (v < t.minSpeed() || turn < t.minTurn()) return 0;
        return Math.min(0.5, t.chance() * (v / t.minSpeed()) * (turn / t.minTurn()));
    }

    // ------------------------------------------------------------------------------------------
    // Buck (the rodeo bull)
    // ------------------------------------------------------------------------------------------

    /** How hard it bucks after this many ticks ridden (no top: it gets brutal). */
    public static double power(FunSpec.Buck b, int ticks) {
        return b.start() + b.growth() * ticks * VehiclePhysics.DT;
    }

    /** The bull's moves: a rest, a fast spin one way, a buck (front and back up in turn), rearing up, a back kick. */
    public enum Move { CALM, SPIN, BUCK, REAR, KICK }

    /**
     * One tick of bucking with a rider on. The bull goes from move to move with short rests (shorter, its moves longer
     * and wilder, the longer it is ridden); a spin may reverse halfway (a feint). Every move throws the rider's weight:
     * a spin outwards, steadily; a buck, a rear and a kick in sudden jolts to a random side. A / D (steer: +1 = A) lean
     * back, {@link #CORRECT} a tick; the wrong key makes it worse. Thrown at a balance of 1. Sets the pose (pitch: + nose
     * down, roll, the rider's lift) and returns the turn this tick (degrees, + = right).
     */
    public static float buck(State fs, FunSpec.Buck b, int steer, RandomGenerator rnd) {
        fs.ridden++;
        double p = power(b, fs.ridden), wild = Math.min(2.2, p);
        if (fs.moveLeft > 0) {
            fs.moveLeft--;
            // a feint: a spin turning round halfway
            if (fs.move == Move.SPIN && fs.moveLeft == 4 && rnd.nextDouble() < 0.18 * wild) fs.way = -fs.way;
            if (fs.moveLeft == 0) {
                fs.move = Move.CALM;
                fs.gap = Math.max(1, (int) Math.round(12 - 6 * wild)) + rnd.nextInt(Math.max(1, (int) Math.round(6 - 2 * wild)));
            }
        } else if (fs.gap > 0) {
            fs.gap--;
        } else {
            Move[] moves = {Move.SPIN, Move.BUCK, Move.REAR, Move.KICK, Move.SPIN};
            fs.move = moves[rnd.nextInt(moves.length)];
            fs.way = rnd.nextBoolean() ? 1 : -1;
            fs.moveLeft = 8 + rnd.nextInt(6) + (int) Math.round(5 * wild);
        }
        double push = 0, turn = 0, pitch = 0, roll = 0;
        int age = fs.moveLeft;
        switch (fs.move) {
            case SPIN -> {
                turn = fs.way * (200 + 240 * wild) * VehiclePhysics.DT;
                push = -fs.way * 0.07 * wild;   // a right spin flings them out to the left
                roll = fs.way * 10 * wild;
                pitch = 6 * Math.sin(fs.ridden * 0.9);
            }
            case BUCK -> {
                pitch = Math.sin(age * 0.85) * (22 + 14 * wild);
                if (age % 4 == 0) push = (rnd.nextBoolean() ? 1 : -1) * 0.11 * wild;
            }
            case REAR -> {
                pitch = -(30 + 12 * wild) * Math.min(1, (fs.moveLeft + 1) / 4.0);
                if (age % 5 == 2) push = fs.way * 0.12 * wild;
                roll = fs.way * 6;
            }
            case KICK -> {
                pitch = (26 + 14 * wild) * Math.abs(Math.sin(age * 0.6));
                if (age % 3 == 0) push = (rnd.nextBoolean() ? 1 : -1) * 0.1 * wild;
                turn = fs.way * 40 * VehiclePhysics.DT;
            }
            case CALM -> pitch = Math.sin(fs.ridden * 0.3) * 4 * wild;
        }
        fs.lurch = fs.move == Move.SPIN ? fs.way : 0;
        fs.balance += push;
        fs.balance += (rnd.nextDouble() - 0.5) * 0.05 * wild;
        fs.balance -= steer * CORRECT;
        // off centre, the weight pulls further over (a rider does not sit still on a bucking bull: they fall or fight it)
        fs.balance *= 1 + TIP_OVER;
        fs.thrown = Math.abs(fs.balance) >= 1;
        // the pose eases a little towards the move's (the client glides between)
        fs.buckPitch += (pitch - fs.buckPitch) * 0.6;
        fs.buckRoll += (roll + fs.balance * 14 - fs.buckRoll) * 0.5;
        fs.buckLift = Math.max(0, -fs.buckPitch) / 40 * 0.25 + Math.max(0, Math.sin(fs.ridden * 0.85)) * 0.06 * wild;
        return (float) turn;
    }

    /** The bull at rest: its pose eases back to level. */
    public static void calm(State fs) {
        fs.ridden = 0;
        fs.balance = 0;
        fs.lurch = 0;
        fs.move = Move.CALM;
        fs.moveLeft = 0;
        fs.gap = 0;
        fs.buckPitch *= 0.7;
        fs.buckRoll *= 0.7;
        fs.buckLift *= 0.7;
        if (Math.abs(fs.buckPitch) < 0.2) fs.buckPitch = 0;
        if (Math.abs(fs.buckRoll) < 0.2) fs.buckRoll = 0;
        if (fs.buckLift < 0.005) fs.buckLift = 0;
    }

    // ------------------------------------------------------------------------------------------
    // The drawn pose
    // ------------------------------------------------------------------------------------------

    /**
     * How the vehicle is drawn besides where it is: turned round (the board's spin, degrees), tipped forward (pitch,
     * degrees: the segway), rocked to the left (roll, degrees: the duck's waddle and bob), lifted (blocks) and squashed
     * (the share of its height: the pogo stick's spring).
     */
    public record Pose(double spin, double pitch, double roll, double lift, double squash) {
        public static final Pose REST = new Pose(0, 0, 0, 0, 1);

        public boolean rest() {
            return equals(REST);
        }
    }

    /** The segway's tilt this tick: towards forward the harder it speeds up (back braking), a little forward cruising. */
    public static double tiltTarget(double speed, double before, VehicleSpec s, double tilt) {
        double accel = (speed - before) / VehiclePhysics.DT;
        double push = Math.clamp(accel / Math.max(0.1, s.acceleration()), -1, 1);
        double cruise = 0.35 * Math.clamp(speed / Math.max(0.1, s.speed()), -1, 1);
        return tilt * Math.clamp(push * 0.8 + cruise, -1, 1);
    }

    /** How far the shown tilt follows its target each tick (degrees). */
    static final double TILT_STEP = 1.5;

    /** The pose this tick (advances the rocking phase). */
    public static Pose pose(State fs, FunSpec f, VehiclePhysics.State st, VehicleSpec s, long ticks) {
        double spin = fs.spin, pitch = 0, roll = 0, lift = 0, squash = 1;
        if (f.tilt() > 0) {
            double target = tiltTarget(st.speed, fs.speedBefore, s, f.tilt());
            fs.tilt = Math.abs(target - fs.tilt) <= TILT_STEP ? target : fs.tilt + Math.copySign(TILT_STEP, target - fs.tilt);
            pitch = Math.round(fs.tilt * 4) / 4.0;
        }
        fs.speedBefore = st.speed;
        if (f.amphibious() != null) {
            if (fs.floating) {
                // bobbing on the water, gently
                roll = 2.2 * Math.sin(ticks * 0.09);
                pitch += 1.5 * Math.sin(ticks * 0.07 + 1);
                lift = 0.03 * Math.sin(ticks * 0.11);
            } else if (f.waddle() > 0 && st.speed != 0) {
                // a step every 0.55 blocks: a full rock (left and right) every two steps
                fs.phase += Math.abs(st.speed) * VehiclePhysics.DT / 0.55 * Math.PI;
                roll = f.waddle() * Math.sin(fs.phase) * Math.min(1, Math.abs(st.speed) / f.amphibious().landSpeed());
                lift = 0.04 * Math.abs(Math.sin(fs.phase));
            }
        }
        if (f.hop() != null && st.onGround && fs.grounded > 0) squash = 0.86;
        // the duck squeezed by its squeak: squashed, then springing back a little tall
        if (fs.squash > 0) squash = fs.squash > 2 ? 0.82 : 1.08;
        if (f.heel() != null) roll += Math.round(fs.roll * 2) / 2.0;
        if (fs.tumble > 0) {
            double a = tumbleAngle(fs);
            roll += a;
            lift += tumbleLift(a, s.width(), s.height() * 0.6);
        }
        return new Pose(spin, pitch, roll, lift, squash);
    }
}
