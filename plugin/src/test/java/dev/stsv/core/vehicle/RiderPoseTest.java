package dev.stsv.core.vehicle;

import dev.stsv.core.emote.EmoteRig;
import dev.stsv.core.emote.EmoteRig.Channel;
import dev.stsv.core.emote.EmoteRig.Pose;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standing riders' poses (RiderPose): on the deck, kicking down to the road, squatting, side on, dizzy, windmilling. */
class RiderPoseTest {

    static RiderPose.Motion still(double deck) {
        return new RiderPose.Motion(0, 0, 0, 0, 0, false, 0, 0, 0, deck);
    }

    /** The lowest point of a shin (its sole), in puppet pixels over the deck. */
    static double sole(Pose p, EmoteRig.Joint shin) {
        Matrix4d[] m = EmoteRig.matrices(p, true);
        double x = shin == EmoteRig.Joint.SHIN_R ? -2 : 2;
        return m[shin.ordinal()].transformPosition(new Vector3d(x, 0, 0)).y;
    }

    @Test
    void everyKindStandsOnItsDeck() {
        for (RiderPose.Kind k : RiderPose.Kind.values()) {
            Pose p = RiderPose.pose(k, still(0.3));
            assertEquals(0, sole(p, EmoteRig.Joint.SHIN_L), 0.6, k + ": the left foot on the deck");
            Matrix4d[] m = EmoteRig.matrices(p, true);
            double head = m[EmoteRig.Joint.HEAD.ordinal()].transformPosition(new Vector3d(0, 28, 0)).y;
            assertTrue(head > 22 && head < 31, k + ": standing up, the head at a standing height (" + head + ")");
        }
    }

    @Test
    void theKickReachesTheRoadAndComesBack() {
        double deck = 0.25;
        Pose push = RiderPose.pose(RiderPose.Kind.KICK, new RiderPose.Motion(0.5, 0, 0, 0.25, 0, false, 0, 0, 0, deck));
        assertTrue(sole(push, EmoteRig.Joint.SHIN_R) < -deck * RiderPose.PX_PER_BLOCK * 0.75, "mid-push the foot is down on the road: " + sole(push, EmoteRig.Joint.SHIN_R));
        Pose up = RiderPose.pose(RiderPose.Kind.KICK, new RiderPose.Motion(0.5, 0, 0, 0.75, 0, false, 0, 0, 0, deck));
        assertTrue(sole(up, EmoteRig.Joint.SHIN_R) >= -0.5, "on the way back the foot is up over the deck");
        Pose lean = RiderPose.pose(RiderPose.Kind.KICK, new RiderPose.Motion(0.8, 20, 0, 0, 0, false, 0, 0, 0, deck));
        assertTrue(lean.get(Channel.ROOT_ROT)[2] < -10, "leans into a left turn");
    }

    @Test
    void theyMoveWithTheMotion() {
        Pose pogo = RiderPose.pose(RiderPose.Kind.POGO, still(0.47));
        Pose squat = RiderPose.pose(RiderPose.Kind.POGO, new RiderPose.Motion(0, 0, 0, 0, 1, false, 0, 0, 0, 0.47));
        assertTrue(squat.get(Channel.ROOT)[1] < pogo.get(Channel.ROOT)[1] - 2, "landing: a deep squat");
        Pose seg = RiderPose.pose(RiderPose.Kind.SEGWAY, new RiderPose.Motion(0.5, 0, 8, 0, 0, false, 0, 0, 0, 0.31));
        assertTrue(seg.get(Channel.ROOT_ROT)[0] > 5, "tipped forward with the platform");
        Pose dizzy1 = RiderPose.pose(RiderPose.Kind.SEGWAY, new RiderPose.Motion(0, 0, 0, 0, 0, false, 1, 0, 10, 0.31));
        Pose dizzy2 = RiderPose.pose(RiderPose.Kind.SEGWAY, new RiderPose.Motion(0, 0, 0, 0, 0, false, 1, 0, 14, 0.31));
        assertNotEquals(dizzy1, dizzy2, "dizzy: the head lolls about");
        Pose skate = RiderPose.pose(RiderPose.Kind.SKATE, still(0.23));
        assertEquals(80, skate.get(Channel.ROOT_ROT)[1], 1e-9, "side on");
        Pose air = RiderPose.pose(RiderPose.Kind.SKATE, new RiderPose.Motion(0.5, 0, 0, 0, 0, true, 0, 0, 0, 0.23));
        assertTrue(Math.abs(air.get(Channel.ARM_L)[2]) > Math.abs(skate.get(Channel.ARM_L)[2]), "arms wide in the air");
        Pose bail1 = RiderPose.pose(RiderPose.Kind.SKATE, new RiderPose.Motion(0.8, 0, 0, 0, 0, true, 0, 1, 3, 0.23));
        Pose bail2 = RiderPose.pose(RiderPose.Kind.SKATE, new RiderPose.Motion(0.8, 0, 0, 0, 0, true, 0, 1, 6, 0.23));
        assertNotEquals(bail1.get(Channel.ARM_R)[0], bail2.get(Channel.ARM_R)[0], "windmilling arms");
    }
}
