package dev.stsv.core.vehicle;

import dev.stsv.core.emote.EmoteRig.Channel;
import dev.stsv.core.emote.EmoteRig.Pose;

/**
 * How a standing rider (a puppet in the player's skin: {@link StandingRiders}) stands on a 특별 탈것, posed to the
 * motion. Pure (unit-tested). Units are the puppet's (skin pixels, +x its left, +z forward, feet planted on the deck:
 * {@code foot_*} are offsets from the rest stance, the knees bend by themselves when the body goes down).
 *
 * <ul>
 *   <li><b>Kick</b> (킥보드): left foot on the deck, hands on the bar; speeding up, the right foot kicks: forward, down
 *       to the road behind the deck and back up; leans into turns.</li>
 *   <li><b>Segway</b> (세그웨이): feet side by side, hands on the bar, the whole body tipped with the platform; dizzy
 *       after spinning on the spot: head lolling, arms flapping.</li>
 *   <li><b>Pogo</b> (스카이콩콩): feet on the pegs, hands on the T bar; squats as it lands, stretches up as it springs,
 *       knees tucked and head back at the top of a high bounce.</li>
 *   <li><b>Skate</b> (보드): side on, feet along the board, knees soft, arms out for balance; an ollie crouches and pops,
 *       knees up in the air; wobbling arms when about to bail.</li>
 * </ul>
 */
public final class RiderPose {

    private RiderPose() {}

    /** Which way a vehicle's rider stands. */
    public enum Kind { KICK, SEGWAY, POGO, SKATE }

    /** Puppet pixels per block (a skin pixel is 0.9375 / 16 block). */
    public static final double PX_PER_BLOCK = 16 / 0.9375;

    /**
     * What the rider's pose follows this tick.
     *
     * @param speed   the share of the top speed (+ forward, - back)
     * @param lean    degrees the vehicle leans to the left (into a left turn)
     * @param tilt    degrees the platform tips forward (the segway)
     * @param kick    the kick's phase, 0..1 (0 = not kicking)
     * @param crouch  how far down the rider squats, 0..1
     * @param air     in the air (a bounce, an ollie)
     * @param dizzy   0..1, the segway's spin
     * @param wobble  0..1, about to bail (the skateboard)
     * @param time    ticks, for idle sway
     * @param deck    blocks from the deck down to the road (where a kicking foot reaches)
     */
    public record Motion(double speed, double lean, double tilt, double kick, double crouch, boolean air, double dizzy,
                         double wobble, long time, double deck) {}

    /** The pose for this motion. */
    public static Pose pose(Kind kind, Motion m) {
        return switch (kind) {
            case KICK -> kick(m);
            case SEGWAY -> segway(m);
            case POGO -> pogo(m);
            case SKATE -> skate(m);
        };
    }

    private static Pose kick(Motion m) {
        double lean = clamp(m.lean(), -25, 25);
        Pose p = Pose.REST
                .with(Channel.ROOT_ROT, 6 + 6 * Math.abs(m.speed()), 0, -lean * 0.8)
                .with(Channel.ROOT, 0, -0.6, 0)
                .with(Channel.CHEST, 8, 0, 0)
                .with(Channel.HEAD, -10, 0, lean * 0.3)
                .with(Channel.ARM_R, -62, 0, -4)
                .with(Channel.ARM_L, -62, 0, 4)
                // the standing foot on the deck, just ahead; the free one behind it, on the deck
                .with(Channel.FOOT_L, -1.5, 0, 2)
                .with(Channel.FOOT_R, 1.0, 0, -3.5)
                .with(Channel.KNEE_L, 0, 0, 1)
                .with(Channel.KNEE_R, 0, 0, 1);
        if (m.kick() > 0) {
            // the kick: forward over the deck, down to the road behind, a push back, and up again (a loop)
            double a = m.kick() * Math.PI * 2;
            double down = m.deck() * PX_PER_BLOCK;
            double fz = 2.5 * Math.cos(a) - 2.5;                      // forward at the start, far back mid-push
            double fy = -down * Math.max(0, Math.sin(a)) * 1.05 + 1.5 * Math.max(0, -Math.sin(a));
            p = p.with(Channel.FOOT_R, 2.2, fy, -3 + fz * 1.8)
                    .with(Channel.ROOT, 0, -1.4 - 0.8 * Math.max(0, Math.sin(a)), 0)   // the standing knee bends as the other foot reaches down
                    .with(Channel.ROOT_ROT, 12, 0, -lean * 0.8);
        }
        return p;
    }

    private static Pose segway(Motion m) {
        double tilt = clamp(m.tilt(), -15, 15);
        Pose p = Pose.REST
                .with(Channel.ROOT_ROT, tilt * 1.1, 0, 0)
                .with(Channel.ROOT, 0, -0.3, tilt * 0.06)
                .with(Channel.ARM_R, -48 - tilt, 0, -6)
                .with(Channel.ARM_L, -48 - tilt, 0, 6)
                .with(Channel.FOOT_L, 0.5, 0, 0.5)
                .with(Channel.FOOT_R, -0.5, 0, 0.5)
                .with(Channel.HEAD, -tilt * 0.6, 0, 0);
        if (m.dizzy() > 0.05) {
            // head lolling round, arms flapping, knees going
            double d = m.dizzy(), t = m.time();
            p = p.with(Channel.HEAD, 18 * d * Math.sin(t * 0.45), 25 * d * Math.cos(t * 0.33), 22 * d * Math.cos(t * 0.45))
                    .with(Channel.CHEST, 10 * d * Math.sin(t * 0.3), 0, 14 * d * Math.sin(t * 0.21))
                    .with(Channel.ARM_R, -48 + 70 * d * Math.sin(t * 0.9), 0, -20 - 50 * d * Math.abs(Math.sin(t * 0.7)))
                    .with(Channel.ARM_L, -48 + 70 * d * Math.cos(t * 0.8), 0, 20 + 50 * d * Math.abs(Math.cos(t * 0.6)))
                    .with(Channel.ROOT, 0, -0.3 - 2.2 * d * Math.abs(Math.sin(t * 0.25)), 0);
        }
        return p;
    }

    private static Pose pogo(Motion m) {
        double c = clamp(m.crouch(), 0, 1);
        Pose p = Pose.REST
                .with(Channel.FOOT_L, 2.6, 0, 0)
                .with(Channel.FOOT_R, -2.6, 0, 0)
                .with(Channel.KNEE_L, 0.4, 0, 1)
                .with(Channel.KNEE_R, -0.4, 0, 1)
                .with(Channel.ROOT, 0, -0.4 - 3.6 * c, 0.2)
                .with(Channel.ROOT_ROT, 6 + 14 * c, 0, 0)
                .with(Channel.CHEST, 6 * c, 0, 0)
                .with(Channel.ARM_R, -40 + 25 * c, 0, 16)
                .with(Channel.ARM_L, -40 + 25 * c, 0, -16)
                .with(Channel.HEAD, -8 * c, 0, 0);
        if (m.air() && c == 0) {
            // up high: whee, head back, a little kick of the heels
            double w = Math.sin(m.time() * 0.35);
            p = p.with(Channel.HEAD, -20, 6 * w, 0).with(Channel.ROOT, 0, 0.6, 0).with(Channel.ROOT_ROT, -4, 0, 4 * w);
        }
        return p;
    }

    private static Pose skate(Motion m) {
        double lean = clamp(m.lean(), -25, 25);
        double c = clamp(m.crouch(), 0, 1);
        // side on: facing the board's left, the front foot over the front truck, the back one over the tail
        Pose p = Pose.REST
                .with(Channel.ROOT_ROT, 0, 80, -lean * 0.4)
                .with(Channel.HIPS, 0, 0, 0)
                .with(Channel.FOOT_L, -2, 0, 4.5)
                .with(Channel.FOOT_R, 2, 0, -4.5)
                .with(Channel.KNEE_L, 1, 0, 0.6)
                .with(Channel.KNEE_R, 1, 0, 0.6)
                .with(Channel.ROOT, 0, -1.2 - 3.4 * c, 0)
                .with(Channel.CHEST, 10 + 18 * c, -55, -lean * 0.5)
                .with(Channel.HEAD, -6, -20, 0)
                .with(Channel.ARM_R, -10, 0, -38 - 20 * c)
                .with(Channel.ARM_L, -10, 0, 38 + 20 * c);
        if (m.air()) {
            // popped: knees up, arms wide
            p = p.with(Channel.ROOT, 0, -2.6, 0).with(Channel.ARM_R, -30, 0, -80).with(Channel.ARM_L, -30, 0, 80)
                    .with(Channel.HEAD, -12, -25, 0);
        }
        if (m.wobble() > 0.05) {
            // windmilling arms: about to bail
            double t = m.time(), w = m.wobble();
            p = p.with(Channel.ARM_R, -10 + 160 * w * Math.sin(t * 0.9), 0, -60)
                    .with(Channel.ARM_L, -10 + 160 * w * Math.sin(t * 0.9 + Math.PI), 0, 60)
                    .with(Channel.CHEST, 10 + 25 * w * Math.sin(t * 0.5), -55, 20 * w * Math.sin(t * 0.7))
                    .with(Channel.HEAD, -6, -20, 15 * w * Math.sin(t * 0.6));
        }
        return p;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
