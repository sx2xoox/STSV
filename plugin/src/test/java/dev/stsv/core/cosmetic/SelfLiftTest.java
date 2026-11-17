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
