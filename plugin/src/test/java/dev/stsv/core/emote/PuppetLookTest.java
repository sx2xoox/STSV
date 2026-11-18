package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Joint;
import dev.stsv.core.emote.EmoteRig.Pose;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes the puppet look like a player and not like a cut-out: the soles on the ground, the light of a player
 * (the entity shader's sides, the eye's block), the body and head turning the game's way, the held item in the hand.
 */
class PuppetLookTest {

    /** The lowest point of a face as the client draws it (DisplayRenderer.TextDisplayRenderer: PuppetModel.textRect), blocks over the entity. */
    private static double lowest(PuppetModel.Face f, PuppetModel.Placement p) {
        Vector3d[] r = PuppetModel.textRect(p, f.cols(), f.rows());
        Vector3d right = new Vector3d(r[1]).mul(f.cols()), down = new Vector3d(r[2]).mul(f.rows());
        double low = Double.MAX_VALUE;
        for (Vector3d c : new Vector3d[]{r[0], new Vector3d(r[0]).add(right), new Vector3d(r[0]).add(down), new Vector3d(r[0]).add(right).add(down)}) {
            low = Math.min(low, c.y);
        }
        return low;
    }

    /**
     * The soles touch the ground: standing still, the lowest drawn point of the skin is exactly where the feet are,
     * for the standing puppet (displays at the eye), the riding one (at the head top, a player's height) and the
     * vehicles' (at the seat, 0.6 over the deck), whichever way it faces. The second layer (trousers) goes a quarter
     * pixel under, as the game's own (CubeDeformation 0.25 on the pants). Planted legs (the vehicles' riders) keep the sole's
     * centre on the ground; a leg cannot quite stretch (2 x 6 px less 0.001), so the shin leans a hair and its edge
     * dips at most 0.05 px (0.003 block).
     */
    @Test
    void theSolesAreOnTheGround() {
        Matrix4d[] rest = EmoteRig.matrices(Pose.REST, false), planted = EmoteRig.matrices(Pose.REST, true);
        for (boolean slim : new boolean[]{false, true}) {
            List<PuppetModel.Face> faces = PuppetModel.faces(EmoteRigTest.plain(slim));
            for (double lift : new double[]{Puppet.LIFT, 1.8, 0.6}) {
                for (double yaw : new double[]{0, 37, 90, -135}) {
                    for (Matrix4d[] joints : List.of(rest, planted)) {
                        double low0 = Double.MAX_VALUE, low1 = Double.MAX_VALUE;
                        for (PuppetModel.Face f : faces) {
                            PuppetModel.Placement p = PuppetModel.place(f, joints[f.joint().ordinal()], -Math.toRadians(yaw), lift);
                            // the display stands lift blocks over the feet: its drawing is lift over that
                            double y = lowest(f, p) + lift;
                            if (f.layer() == 0) low0 = Math.min(low0, y);
                            else low1 = Math.min(low1, y);
                            if (joints == rest && f.layer() == 0 && f.side() == PuppetModel.Side.BOTTOM && (f.joint() == Joint.SHIN_L || f.joint() == Joint.SHIN_R)) {
                                Vector3d[] r = PuppetModel.textRect(p, f.cols(), f.rows());
                                assertEquals(0, r[0].y + lift, 1e-6, "the sole is flat on the ground");
                                assertEquals(0, r[1].y, 1e-9);
                                assertEquals(0, r[2].y, 1e-9);
                            }
                        }
                        // the faces are drawn SEAL larger about their middle: a 8 px high shin side reaches 0.016 px under
                        assertEquals(0, low0, (joints == rest ? 8 * (PuppetModel.SEAL - 1) / 2 : 0.06) * EmoteRig.PX + 1e-9, "soles at lift " + lift + ", yaw " + yaw);
                        assertTrue(low0 <= 1e-9, "not floating: " + low0);
                        assertEquals(-PuppetModel.COAT * EmoteRig.PX, low1, (joints == rest ? 0.02 : 0.08) * EmoteRig.PX, "the trousers' second layer");
                    }
                }
            }
        }
    }

    @Test
    void aStandingPuppetIsLitAtTheEye() {
        // its displays stand where a player's light probe (the eye) is: the clients light it from the same block
        assertEquals(1.62, Puppet.LIFT, 1e-12);
        assertEquals(PuppetLight.EYE, Puppet.LIFT);
    }

    @Test
    void theEntityShadersLight() {
        assertEquals(1, PuppetLight.diffuse(new Vector3d(0, 1, 0), false), 1e-12, "up: full");
        assertEquals(0.4, PuppetLight.diffuse(new Vector3d(0, -1, 0), false), 1e-12, "down: the ambient light");
        assertEquals(0.7396, PuppetLight.diffuse(new Vector3d(0, 0, 1), false), 1e-4, "south");
        assertEquals(0.7396, PuppetLight.diffuse(new Vector3d(0, 0, -1), false), 1e-4, "north");
        assertEquals(0.4970, PuppetLight.diffuse(new Vector3d(1, 0, 0), false), 1e-4, "east");
        assertEquals(0.4970, PuppetLight.diffuse(new Vector3d(-1, 0, 0), false), 1e-4, "west");
        // the nether: the second light from below, so up and down are alike
        assertEquals(PuppetLight.diffuse(new Vector3d(0, 1, 0), true), PuppetLight.diffuse(new Vector3d(0, -1, 0), true), 1e-12);
        // every side between the bottom's and the top's
        for (int a = 0; a < 360; a += 15) {
            for (int e = -90; e <= 90; e += 15) {
                double ca = Math.cos(Math.toRadians(a)), sa = Math.sin(Math.toRadians(a)), ce = Math.cos(Math.toRadians(e));
                double d = PuppetLight.diffuse(new Vector3d(ca * ce, Math.sin(Math.toRadians(e)), sa * ce), false);
                assertTrue(d >= 0.4 - 1e-12 && d <= 1, a + " " + e);
            }
        }
        // turning the puppet a quarter turns which sides are lit
        PuppetModel.Face front = PuppetModel.faces(EmoteRigTest.plain(false)).stream()
                .filter(f -> f.layer() == 0 && f.joint() == Joint.CHEST && f.side() == PuppetModel.Side.FRONT).findFirst().orElseThrow();
        Matrix4d[] rest = EmoteRig.matrices(Pose.REST, false);
        assertEquals(0.7396, PuppetLight.diffuse(PuppetModel.worldNormal(front, rest[Joint.CHEST.ordinal()], 0), false), 1e-4, "facing south");
        assertEquals(0.4970, PuppetLight.diffuse(PuppetModel.worldNormal(front, rest[Joint.CHEST.ordinal()], -Math.toRadians(90)), false), 1e-4, "facing west");
        // bowing tips the chest's front towards the ground: darker
        Matrix4d[] bow = EmoteRig.matrices(Pose.REST.with(EmoteRig.Channel.CHEST, 80, 0, 0), false);
        assertTrue(PuppetLight.diffuse(PuppetModel.worldNormal(front, bow[Joint.CHEST.ordinal()], 0), false) < 0.5);
    }

    @Test
    void theLightOfTheEyeOnlyWhereItDiffers() {
        int dark = PuppetLight.pack(0, 4), lamp = PuppetLight.pack(14, 4);
        assertEquals(PuppetLight.NATURAL, PuppetLight.override(dark, dark), "the same block light: the clients' own");
        assertEquals(lamp, PuppetLight.override(dark, lamp));
        assertEquals(dark, PuppetLight.override(PuppetLight.pack(0, 0), dark), "the origin inside a solid block: the eye's");
        assertEquals(14, PuppetLight.block(lamp));
        assertEquals(4, PuppetLight.sky(lamp));
        assertEquals(PuppetLight.pack(15, 0), PuppetLight.pack(20, -3), "clamped");
    }

    @Test
    void theBodyTurnsTheGamesWay() {
        // standing still: the body stays while the head looks up to 50 degrees away, then is dragged along
        assertEquals(0, EmoteRules.bodyYaw(0, 40, 0, 0), 1e-4);
        assertEquals(30, EmoteRules.bodyYaw(0, 80, 0, 0), 1e-4);
        assertEquals(-30, EmoteRules.bodyYaw(0, -80, 0, 0), 1e-4);
        assertEquals(-150, EmoteRules.bodyYaw(170, -100, 0, 0), 1e-3, "across the seam");
        // walking south (yaw 0) while facing south-east: the body eases towards the walk
        float b = 0;
        for (int i = 0; i < 40; i++) b = EmoteRules.bodyYaw(b, -45, 0, 0.2);
        assertEquals(0, b, 0.5);
        // walking east (yaw -90)
        b = 0;
        for (int i = 0; i < 40; i++) b = EmoteRules.bodyYaw(b, -90, 0.2, 0);
        assertEquals(-90, b, 0.5);
        // walking backwards: the body keeps facing the look's way
        b = 0;
        for (int i = 0; i < 40; i++) b = EmoteRules.bodyYaw(b, 0, 0, -0.2);
        assertEquals(0, b, 0.5);
        // the head: the rest of the look, at most 50 degrees; the game's yaw + is the rig's yaw -
        assertEquals(-40, EmoteRules.look(0, 40, 10)[1], 1e-9);
        assertEquals(10, EmoteRules.look(0, 40, 10)[0], 1e-9, "pitch + looks down, as the rig's");
        assertEquals(50, EmoteRules.look(0, -120, 0)[1], 1e-9);
        assertEquals(90, EmoteRules.look(0, 0, 120)[0], 1e-9);
        double[] head = Pose.REST.with(EmoteRig.Channel.HEAD, 5, 1, 2).looking(new double[]{10, -20}).get(EmoteRig.Channel.HEAD);
        assertEquals(15, head[0], 1e-12);
        assertEquals(-19, head[1], 1e-12);
        assertEquals(2, head[2], 1e-12);
    }

    /**
     * The held item: where the client puts it on a player (ItemInHandLayer: the arm's pivot, Rx(-90) Ry(180), then
     * (+-1, 2, -10) model pixels) for an item display, which turns by 180 degrees about y itself.
     */
    @Test
    void theItemIsInTheHand() {
        Matrix4d[] rest = EmoteRig.matrices(Pose.REST, false);
        PuppetModel.Placement right = PuppetModel.hand(rest[Joint.ARM_R.ordinal()], true, false, 0, 0);
        assertNear(new Vector3d(-6, 12, 2).mul(EmoteRig.PX), right.translation(), "the right hand's end of the arm, at its front");
        assertNear(new Vector3d(5.5, 12, 2).mul(EmoteRig.PX), PuppetModel.hand(rest[Joint.ARM_L.ordinal()], false, true, 0, 0).translation(), "slim: half a pixel in");
        assertEquals(0.9375, right.scale().x, 1e-12);
        // the display's turn and the renderer's half turn: the game's (model to world: x stays, y and z flip)
        Matrix3d shown = new Matrix3d().rotation(right.rotation()).mul(new Matrix3d().rotationY(Math.PI));
        Matrix3d game = new Matrix3d(1, 0, 0, 0, -1, 0, 0, 0, -1).mul(new Matrix3d().rotationX(-Math.PI / 2)).mul(new Matrix3d().rotationY(Math.PI));
        assertTrue(shown.equals(game, 1e-9), shown + " vs " + game);
        // a raised arm carries it: the hand over the shoulder
        Matrix4d[] up = EmoteRig.matrices(Pose.REST.with(EmoteRig.Channel.ARM_R, 180, 0, 0), false);
        assertTrue(PuppetModel.hand(up[Joint.ARM_R.ordinal()], true, false, 0, 0).translation().y > 30 * EmoteRig.PX);
        // turned with the puppet, lifted with its displays
        PuppetModel.Placement turned = PuppetModel.hand(rest[Joint.ARM_R.ordinal()], true, false, -Math.toRadians(90), 1.62);
        assertNear(new Vector3d(-2, 12 - 1.62 / EmoteRig.PX, -6).mul(EmoteRig.PX), turned.translation(), "facing west");
        Quaterniond q = turned.rotation();
        assertEquals(1, q.lengthSquared(), 1e-9);
    }

    private static void assertNear(Vector3d want, Vector3d got, String what) {
        assertTrue(want.distance(got) < 1e-9, what + ": " + got + " instead of " + want);
    }
}
