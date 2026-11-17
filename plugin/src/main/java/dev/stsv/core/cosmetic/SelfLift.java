package dev.stsv.core.cosmetic;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The wearer's own view of the models they wear, without a shader (so it works with shader packs too).
 *
 * <p>The game does not draw an entity whose culling box is outside the camera's view (EntityRenderer.shouldRender:
 * the box, grown by half a block, against the view frustum). A display's box is its own to set (width and height, at
 * the display's position), and what it shows can be drawn anywhere relative to it (the translation). So on the
 * wearer's own client their worn displays ride an invisible entity of height {@link #DEFAULT} standing on the
 * wearer instead of riding the wearer directly, with a tiny box, and are drawn lowered by the same height: the
 * models are where they were, the displays are above the head. In first person (the camera in the head) what is
 * above the head is outside the view while looking level or down, so the models are not drawn; looking up brings the
 * box into view, but then the body is out of the view anyway. In third person (the camera 4 blocks away) box and body
 * are both in view. The displays still ride the wearer on the client, so they follow without delay.
 *
 * <p>Looking up brings what is above the head into view, and with it everything worn (wings, rings round the body). So
 * only what is worn above the head stays up there. The other displays change their place with the look ({@link Band}):
 * above the head while looking down, just under the feet while looking level or a little up, at the ankles while
 * looking up more. Each place is outside the first-person view for its range of looks (with room to spare: the server
 * hears of a turn a moment late) and inside the third-person views; where one place ends, the next is already hidden.
 * The game's test is box against the six planes of the view, which lets a box near the camera through sooner than its
 * shape would: the ranges are what that test gives at every turn of the head (SelfLiftTest).
 *
 * <p>This class is the numbers and the geometry check ({@link #drawn}); CosmeticPackets does the packets.
 */
final class SelfLift {

    /**
     * Blocks the wearer's own displays sit above the top of their hitbox (config cosmetics.own-view-lift). Higher: hidden
     * in first person up to a steeper look upwards, but left out in third person sooner when a wall pushes the camera
     * in. 1.4: hidden looking level or down up to FOV 110, drawn in F5 from FOV 40, and from FOV 70 with the camera
     * pushed in to 2 blocks (SelfLiftTest).
     */
    static final double DEFAULT = 1.4;
    /** Width and height of a lifted display's culling box (0 would switch culling off). */
    static final Float CULL_BOX = 0.01f;
    /** The game grows a culling box by this much on every side before testing it. */
    static final double GROWN = 0.5;
    /** Third-person camera distance. */
    static final double THIRD_PERSON = 4.0;

    /** Where the wearer's own displays (all but what is worn above the head) sit on their client, by how they look. */
    enum Band {
        /** above the head: looking down */
        HIGH,
        /** under the feet: looking level or a little up (hidden from the widest range of looks) */
        LOW,
        /** at the ankles: looking up more (a third-person camera pushed to the ground looks over what is under the feet) */
        MID
    }

    /** Blocks under the top of the hitbox: 0.2 under the feet standing, 0.5 sneaking. */
    static final double LOW_DROP = 2.0;
    /** Blocks under the top of the hitbox: 0.2 over the feet standing, 0.1 under them sneaking. */
    static final double MID_DROP = 1.6;
    /**
     * Degrees of looking up where HIGH gives way to LOW, and LOW to MID; SLACK either side before going back. At -2
     * both HIGH and LOW are hidden 13 degrees either way at FOV 90, 8 at FOV 100, 3 at FOV 110; LOW is drawn in third
     * person up to 20 degrees, MID is hidden from 10.
     */
    static final double TO_LOW = -2, TO_MID = 15, SLACK = 1.5;

    /** How far over (under: negative) the top of the wearer's hitbox the displays of this band sit. */
    static double height(Band b, double lift) {
        return switch (b) {
            case HIGH -> lift;
            case LOW -> -LOW_DROP;
            case MID -> -MID_DROP;
        };
    }

    /**
     * The band for a look, given the band so far (it stays until the look is SLACK degrees past an edge, so a look
     * resting on an edge does not flip it back and forth).
     *
     * @param pitch   degrees, positive = looking up
     * @param upright standing or sneaking; lying (swimming, gliding, sleeping) the eye is at the feet: always HIGH
     */
    static Band band(Band was, double pitch, boolean upright) {
        if (!upright) return Band.HIGH;
        switch (was) {
            case HIGH:
                if (pitch <= TO_LOW + SLACK) return Band.HIGH;
                return pitch > TO_MID + SLACK ? Band.MID : Band.LOW;
            case LOW:
                if (pitch < TO_LOW - SLACK) return Band.HIGH;
                return pitch > TO_MID + SLACK ? Band.MID : Band.LOW;
            default:
                if (pitch >= TO_MID - SLACK) return Band.MID;
                return pitch < TO_LOW - SLACK ? Band.HIGH : Band.LOW;
        }
    }

    private SelfLift() {
    }

    /**
     * Whether the game draws a display lifted by lift blocks (negative: lowered), as its culling goes (the same frustum test as the client:
     * JOML's FrustumIntersection of projection x view).
     *
     * @param top      the wearer's hitbox height (where passengers ride): 1.8 standing, 1.5 sneaking
     * @param eye      the eye height: 1.62 standing, 1.27 sneaking
     * @param camera   how far the camera is behind the eye along the view (0 = first person, 4 = F5),
     *                 negative: in front, looking back (the second F5 view)
     * @param yaw      degrees, pitch degrees (positive = looking up)
     * @param fov      the vertical field of view in degrees, aspect = width / height
     */
    static boolean drawn(double lift, double top, double eye, double camera, double yaw, double pitch, double fov, double aspect) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        Vector3f dir = new Vector3f((float) (Math.cos(p) * Math.sin(y)), (float) Math.sin(p), (float) (Math.cos(p) * Math.cos(y)));
        Vector3f head = new Vector3f(0, (float) eye, 0);
        // behind the head looking along the view, or (negative) in front of it looking back
        Vector3f at = new Vector3f(head).sub(new Vector3f(dir).mul((float) camera));
        Vector3f look = camera < 0 ? new Vector3f(dir).negate() : dir;
        // an up vector that is never parallel to the view (looking straight up or down)
        Vector3f up = Math.abs(look.y) > 0.999f ? new Vector3f((float) Math.sin(y), 0, (float) Math.cos(y)).mul(look.y > 0 ? -1 : 1) : new Vector3f(0, 1, 0);
        Matrix4f m = new Matrix4f().perspective((float) Math.toRadians(fov), (float) aspect, 0.05f, 512f)
                .lookAt(at, new Vector3f(at).add(look), up);
        double h = CULL_BOX / 2.0 + GROWN, base = top + lift;
        return new FrustumIntersection(m).testAab((float) -h, (float) (base - GROWN), (float) -h, (float) h, (float) (base + CULL_BOX + GROWN), (float) h);
    }
}
