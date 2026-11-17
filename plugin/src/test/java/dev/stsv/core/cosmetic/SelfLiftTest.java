package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem.CosmeticSlot;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wearer's own view without a shader (SelfLift): with the displays lifted above the head, the game's own culling
 * leaves them out in first person while looking level or down, and draws them in third person (F5).
 */
class SelfLiftTest {

    private static final double[][] POSES = {{1.8, 1.62}, {1.5, 1.27}};   // hitbox top, eye: standing, sneaking
    private static final double[] ASPECTS = {4.0 / 3, 16.0 / 9, 21.0 / 9};

    @Test
    void firstPersonLookingLevelOrDownDoesNotDrawThem() {
        List<String> seen = new ArrayList<>();
        for (double[] pose : POSES) for (double aspect : ASPECTS) for (int fov = 30; fov <= 110; fov += 10) {
            for (int yaw = 0; yaw < 360; yaw += 15) for (int pitch = -90; pitch <= 0; pitch += 5) {
                if (SelfLift.drawn(SelfLift.DEFAULT, pose[0], pose[1], 0, yaw, pitch, fov, aspect)) {
                    seen.add("top " + pose[0] + " fov " + fov + " aspect " + aspect + " yaw " + yaw + " pitch " + pitch);
                }
            }
        }
        assertTrue(seen.isEmpty(), seen.size() + " views draw the lifted displays in first person, e.g. " + seen.stream().limit(5).toList());
    }

    @Test
    void thirdPersonDrawsThemFromBehindAndFromTheFront() {
        List<String> missing = new ArrayList<>();
        // the camera 4 blocks behind or in front: any FOV from 40; pushed in to 2 blocks by a wall: from the default 70
        for (double[] pose : POSES) for (double aspect : ASPECTS) for (double camera : new double[]{SelfLift.THIRD_PERSON, -SelfLift.THIRD_PERSON, 2.0}) {
            for (int fov = camera == 2.0 ? 70 : 40; fov <= 110; fov += 10) {
                for (int yaw = 0; yaw < 360; yaw += 45) for (int pitch = -90; pitch <= 60; pitch += 5) {
                    if (!SelfLift.drawn(SelfLift.DEFAULT, pose[0], pose[1], camera, yaw, pitch, fov, aspect)) {
                        missing.add("top " + pose[0] + " fov " + fov + " camera " + camera + " yaw " + yaw + " pitch " + pitch);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), missing.size() + " third-person views leave the lifted displays out, e.g. " + missing.stream().limit(5).toList());
    }

    /** The bands a look can rest in: a band stays until the look is past its edge by the slack. */
    private static List<SelfLift.Band> resting(double pitch) {
        List<SelfLift.Band> out = new ArrayList<>();
        for (SelfLift.Band b : SelfLift.Band.values()) if (SelfLift.band(b, pitch, true) == b) out.add(b);
        return out;
    }

    /**
     * What is worn on the body changes its place with the look, and is never drawn in first person: not at any look, and
     * (up to FOV 100) not while the look is up to 4 degrees ahead of what the server knows (a quick turn, heard of a
     * moment late). At FOV 110 the places overlap by a degree and a half either side.
     */
    @Test
    void firstPersonNeverDrawsWhatIsWornOnTheBody() {
        List<String> seen = new ArrayList<>();
        for (double[] pose : POSES) for (double aspect : ASPECTS) for (int fov = 30; fov <= 110; fov += 10) {
            int room = fov <= 100 ? 4 : 1;
            for (int pitch = -90; pitch <= 90; pitch++) for (SelfLift.Band b : resting(pitch)) {
                double lift = SelfLift.height(b, SelfLift.DEFAULT);
                for (int ahead = -room; ahead <= room; ahead += room) {
                    int look = Math.clamp(pitch + ahead, -90, 90);
                    for (int yaw = 0; yaw < 360; yaw += 15) {
                        if (SelfLift.drawn(lift, pose[0], pose[1], 0, yaw, look, fov, aspect)) {
                            seen.add("top " + pose[0] + " fov " + fov + " aspect " + aspect + " " + b + " at " + pitch + " looking " + look);
                        }
                    }
                }
            }
        }
        assertTrue(seen.isEmpty(), seen.size() + " first-person views draw them, e.g. " + seen.stream().limit(5).toList());
    }

    /** The camera's distance in third person: the ground stops it under the wearer (behind looking up, in front looking down). */
    private static double camera(double eye, double distance, int pitch) {
        double under = distance > 0 ? Math.sin(Math.toRadians(pitch)) : -Math.sin(Math.toRadians(pitch));
        if (under <= 0) return distance;
        return Math.signum(distance) * Math.min(Math.abs(distance), (eye - 0.1) / under);
    }

    @Test
    void thirdPersonDrawsWhatIsWornOnTheBodyAtEveryLook() {
        List<String> missing = new ArrayList<>();
        for (double[] pose : POSES) for (double aspect : ASPECTS) for (double distance : new double[]{4, -4, 2.5, -2.5}) {
            for (int fov = 60; fov <= 110; fov += 10) {
                for (int pitch = -90; pitch <= 90; pitch += 2) for (SelfLift.Band b : resting(pitch)) {
                    double lift = SelfLift.height(b, SelfLift.DEFAULT);
                    for (int yaw = 0; yaw < 360; yaw += 45) {
                        if (!SelfLift.drawn(lift, pose[0], pose[1], camera(pose[1], distance, pitch), yaw, pitch, fov, aspect)) {
                            missing.add("top " + pose[0] + " fov " + fov + " aspect " + aspect + " camera " + distance + " " + b + " pitch " + pitch);
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), missing.size() + " third-person views leave them out, e.g. " + missing.stream().limit(8).toList());
    }

    @Test
    void theBandFollowsTheLookWithoutFlippingOnAnEdge() {
        SelfLift.Band b = SelfLift.Band.HIGH;
        assertEquals(SelfLift.Band.HIGH, b = SelfLift.band(b, -30, true));
        assertEquals(SelfLift.Band.HIGH, b = SelfLift.band(b, SelfLift.TO_LOW, true), "on the edge: stays");
        assertEquals(SelfLift.Band.LOW, b = SelfLift.band(b, 2, true));
        assertEquals(SelfLift.Band.LOW, b = SelfLift.band(b, SelfLift.TO_LOW, true), "back on the edge: stays");
        assertEquals(SelfLift.Band.MID, b = SelfLift.band(b, 40, true));
        assertEquals(SelfLift.Band.MID, b = SelfLift.band(b, SelfLift.TO_MID, true));
        assertEquals(SelfLift.Band.LOW, b = SelfLift.band(b, 5, true));
        assertEquals(SelfLift.Band.HIGH, b = SelfLift.band(b, -20, true));
        assertEquals(SelfLift.Band.MID, SelfLift.band(SelfLift.Band.HIGH, 80, true), "a jump over the middle band");
        assertEquals(SelfLift.Band.HIGH, SelfLift.band(SelfLift.Band.MID, -80, true));
        assertEquals(SelfLift.Band.HIGH, SelfLift.band(SelfLift.Band.LOW, 30, false), "lying down: above the head");
        assertEquals(SelfLift.DEFAULT, SelfLift.height(SelfLift.Band.HIGH, SelfLift.DEFAULT));
        assertTrue(SelfLift.height(SelfLift.Band.LOW, SelfLift.DEFAULT) < SelfLift.height(SelfLift.Band.MID, SelfLift.DEFAULT));
    }

    /**
     * What is worn above the head stays above it. The game tests the box against the planes of the view one by one,
     * so a box right over the camera is let through sooner than its shape would be: with the head turned to a corner
     * of the box it is hidden up to 11 degrees of looking up at FOV 90, 21 at FOV 70 (and seen above that: it is
     * above the head).
     */
    @Test
    void whatIsAboveTheHeadIsHiddenUntilTheLookGoesUp() {
        for (double[] pose : POSES) for (double aspect : ASPECTS) for (int yaw = 0; yaw < 360; yaw += 15) {
            for (int pitch = -90; pitch <= 11; pitch++) {
                assertTrue(!SelfLift.drawn(SelfLift.DEFAULT, pose[0], pose[1], 0, yaw, pitch, 90, aspect), "FOV 90, yaw " + yaw + ", pitch " + pitch);
            }
            for (int pitch = 12; pitch <= 21; pitch++) {
                assertTrue(!SelfLift.drawn(SelfLift.DEFAULT, pose[0], pose[1], 0, yaw, pitch, 70, aspect), "FOV 70, yaw " + yaw + ", pitch " + pitch);
            }
        }
    }

    /** Without the lift (riding the wearer directly, as everyone else sees them) the box is round the head: always drawn. */
    @Test
    void withoutTheLiftFirstPersonDrawsThem() {
        assertTrue(SelfLift.drawn(0, 1.8, 1.62, 0, 0, 0, 70, 16.0 / 9));
        assertTrue(SelfLift.drawn(0, 1.8, 1.62, 0, 0, -60, 70, 16.0 / 9));
    }

    /**
     * The sneaking lean is in the transformation, not in the display's pitch (a pitched display would swing a lowered
     * model round itself): the picture is the same as pitching the display by the lean.
     */
    @Test
    void theSneakingLeanIsInTheTransformation() {
        // a back piece with an offset, a scale and a swaying motion (so the turn is not the identity)
        dev.stsv.core.content.CustomItem.Cosmetic c = new dev.stsv.core.content.CustomItem.Cosmetic(CosmeticSlot.BACK,
                dev.stsv.core.content.CustomItem.Rarity.values()[0], "test", new org.bukkit.util.Vector(1, 2, 3), 1.25f, false, null,
                false, null, false, new dev.stsv.core.content.CustomItem.Motion(dev.stsv.core.content.CustomItem.MotionKind.SWAY, 12f, 40));
        Vector3f anchor = CosmeticAttachments.DEFAULT_ANCHORS.get(CosmeticSlot.BACK);
        Transformation stand = CosmeticAttachments.transformation(anchor, c, false, 1f, 0, 1);
        Transformation sneak = CosmeticAttachments.transformation(anchor, c, true, 1f, 0, 1);
        // as it was drawn before: the display pitched by the lean, then the sneaking translation, the turn, the scale
        Vector3f t = CosmeticAttachments.translation(anchor, c.offset(), true, 1f, 0);
        Matrix4f want = new Matrix4f().rotateX((float) Math.toRadians(CosmeticAttachments.SNEAK_TILT))
                .translate(t).rotate(stand.getLeftRotation()).scale(stand.getScale());
        Matrix4f got = new Matrix4f().translate(sneak.getTranslation()).rotate(sneak.getLeftRotation()).scale(sneak.getScale()).rotate(sneak.getRightRotation());
        for (Vector3f v : List.of(new Vector3f(0, 0, 0), new Vector3f(0.3f, -0.2f, 0.5f), new Vector3f(-1, 1, 0.25f))) {
            Vector3f a = want.transformPosition(new Vector3f(v)), b = got.transformPosition(new Vector3f(v));
            assertEquals(0, a.distance(b), 1e-4, "point " + v);
        }
    }
}
