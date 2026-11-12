package dev.stsv.core.vehicle;

/**
 * A vehicle body on its springs (서스펜션): three damped springs, one tick at a time. Pure (unit-tested); what it moves is
 * only drawn ({@link VehicleFeelView}: the body and its lamps bob and nod, the load sways too; the wheels stay on the
 * ground; nothing it does touches the physics or the seats).
 *
 * <ul>
 *   <li><b>bob</b> (blocks, + = up): a step up drops the body onto its springs, a step down lifts it.</li>
 *   <li><b>pitch</b> (degrees, + = the nose down): a hard brake dips the nose, a start squats the tail, a step nods it.</li>
 *   <li><b>sway</b> (degrees, + = to the vehicle's left): the load leans out of a turn and rocks over steps.</li>
 * </ul>
 * Each settles within about a second; once all three are still it reports {@link #settled} and costs nothing.
 */
public final class Suspension {

    /** Spring stiffness and the share of the speed kept each tick (a light damping: one or two rebounds). */
    static final double K = 0.28, KEEP = 0.72;
    /** Below these it is still (blocks, degrees). */
    static final double REST_BOB = 2e-4, REST_ANGLE = 0.02;
    /** The most each may swing (bounce share of blocks, degrees). */
    static final double MAX_BOB = 0.2, MAX_ANGLE = 12;

    double bob, bobV, pitch, pitchV, sway, swayV;

    /** A kick: added to the springs' speeds (blocks / degrees per tick). */
    public void kick(double dBob, double dPitch, double dSway) {
        bobV += dBob;
        pitchV += dPitch;
        swayV += dSway;
    }

    /** One tick of the springs. Returns whether anything moved. */
    public boolean step() {
        if (settled()) return false;
        bobV = (bobV - K * bob) * KEEP;
        pitchV = (pitchV - K * pitch) * KEEP;
        swayV = (swayV - K * sway) * KEEP;
        bob = Math.clamp(bob + bobV, -MAX_BOB, MAX_BOB);
        pitch = Math.clamp(pitch + pitchV, -MAX_ANGLE, MAX_ANGLE);
        sway = Math.clamp(sway + swayV, -MAX_ANGLE, MAX_ANGLE);
        if (Math.abs(bob) < REST_BOB && Math.abs(bobV) < REST_BOB && Math.abs(pitch) < REST_ANGLE && Math.abs(pitchV) < REST_ANGLE
                && Math.abs(sway) < REST_ANGLE && Math.abs(swayV) < REST_ANGLE) reset();
        return true;
    }

    public boolean settled() {
        return bob == 0 && bobV == 0 && pitch == 0 && pitchV == 0 && sway == 0 && swayV == 0;
    }

    public void reset() {
        bob = bobV = pitch = pitchV = sway = swayV = 0;
    }

    public double bob() {
        return bob;
    }

    public double pitch() {
        return pitch;
    }

    public double sway() {
        return sway;
    }

    /**
     * The kicks for one tick of driving.
     *
     * @param dy       how far it rose this tick on the ground (a step: more than {@link #STEP})
     * @param speed    its speed now (blocks/s, + = forward) and before (the change brakes or starts it)
     * @param turn     degrees it turned this tick (+ = clockwise from above: to the right)
     * @param bounce   blocks a step drops the body; pitch: degrees a step or a hard brake nods it; sway: the most a load sways
     */
    public void drive(double dy, double speed, double speedBefore, double turn, double bounce, double pitch, double sway) {
        if (Math.abs(dy) > STEP) {
            double s = Math.signum(dy) * Math.min(1, Math.abs(dy));
            // up: the body sinks onto the springs and the nose lifts (going forward); down: the other way
            kick(-s * bounce * 0.55, -s * Math.signum(speed) * pitch * 0.5, (Math.floorMod((long) Math.floor(dy * 1000), 2) == 0 ? 1 : -1) * sway * 0.25);
        }
        double accel = (speed - speedBefore) / VehiclePhysics.DT;   // blocks/s²
        if (Math.abs(accel) > 1.5) kick(0, -Math.clamp(accel / 10, -1, 1) * pitch * 0.12, 0);
        // the load leans out of the turn: a right turn (turn > 0) going forward throws it to the left (+)
        double lateral = Math.toRadians(turn) / VehiclePhysics.DT * speed;   // blocks/s²
        if (Math.abs(lateral) > 0.5) kick(0, 0, Math.clamp(lateral / 12, -1, 1) * sway * 0.08);
    }

    /** A rise or fall in one tick on the ground bigger than this is a step (blocks): a slab's smooth ride is not. */
    public static final double STEP = 0.2;

    /** The idle shake's offset this tick (blocks): a small uneven tremble, alternating up and down. */
    public static double shake(long ticks, long id, double amplitude) {
        if (amplitude <= 0) return 0;
        long k = (ticks + id) / 2;
        double jitter = 0.6 + 0.4 * ((k * 2654435761L >>> 7) % 100) / 100.0;
        return (k % 2 == 0 ? 1 : -1) * amplitude * jitter;
    }
}
