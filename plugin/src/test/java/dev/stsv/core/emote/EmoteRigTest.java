package dev.stsv.core.emote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stsv.core.emote.EmoteRig.Channel;
import dev.stsv.core.emote.EmoteRig.Joint;
import dev.stsv.core.emote.EmoteRig.Pose;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The puppet's skeleton and its faces as text displays: the numbers of the preview tool (the fixture), boxes that
 * are closed and face outward by the client's rules for a text display, legs that keep their feet.
 */
class EmoteRigTest {

    static final Path CONTENT = Path.of(System.getProperty("stsv.content"));
    static final Path FIXTURE = Path.of(System.getProperty("stsv.fixtures")).resolve("emote/rig.json");

    static Map<String, EmoteSpec> shipped() {
        List<String> problems = new ArrayList<>();
        Map<String, EmoteSpec> emotes = EmoteSpec.parse(YamlConfiguration.loadConfiguration(CONTENT.resolve("stsv/emotes.yml").toFile()), problems::add);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return emotes;
    }

    static PuppetSkin plain(boolean slim) {
        BufferedImage im = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) im.setRGB(x, y, 0xFF78828C);
        return PuppetSkin.of(im, slim);
    }

    /** Poses to try the geometry on: rest, the shipped emotes through their rounds, and one with every joint turned. */
    static List<Object[]> poses() {
        List<Object[]> out = new ArrayList<>();
        out.add(new Object[]{"rest", Pose.REST, false});
        for (EmoteSpec e : shipped().values()) {
            for (double t = 0; t < e.length(); t += 0.75) out.add(new Object[]{e.id() + " at " + t, e.poseAt(t), e.planted()});
        }
        Pose wild = Pose.REST.with(Channel.ROOT, 1, -2, 3).with(Channel.ROOT_ROT, 10, 25, -8).with(Channel.HIPS, 35, 12, -9)
                .with(Channel.CHEST, -20, -30, 14).with(Channel.HEAD, 40, 60, 5).with(Channel.ARM_L, -150, 20, 30)
                .with(Channel.ARM_R, 70, -10, -45).with(Channel.THIGH_L, -80, 10, 12).with(Channel.SHIN_L, 95, 0, 0)
                .with(Channel.THIGH_R, 30, -20, -10).with(Channel.SHIN_R, 20, 5, 0).with(Channel.FOOT_L, 3, 1, 2)
                .with(Channel.FOOT_R, -4, 0, -3).with(Channel.KNEE_L, 0.4, 0, 1).with(Channel.KNEE_R, -1, 0.2, 0.3);
        out.add(new Object[]{"wild", wild, false});
        out.add(new Object[]{"wild, planted", wild, true});
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // The same numbers as the preview tool
    // ------------------------------------------------------------------------------------------

    /**
     * tools/assetgen/emotes.py --fixture wrote, for the shipped emotes at a few ticks, every joint's matrix and
     * every face's display transformation: the plugin's own must be the same (the previews are what was looked at).
     */
    @Test
    void theRigIsThePreviewToolsRig() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(FIXTURE)).getAsJsonObject();
        assertEquals(EmoteRig.PX, fixture.get("px").getAsDouble(), 1e-12);
        assertEquals(PuppetModel.SEAL, fixture.get("seal").getAsDouble(), 1e-12);
        assertEquals(PuppetModel.CELL, fixture.get("cell").getAsInt());
        JsonArray names = fixture.getAsJsonArray("joints");
        assertEquals(Joint.values().length, names.size());
        for (Joint j : Joint.values()) assertEquals(j.key(), names.get(j.ordinal()).getAsString(), "the joints' order");

        Map<String, EmoteSpec> emotes = shipped();
        List<PuppetModel.Face> faces = PuppetModel.faces(plain(false));
        JsonArray cases = fixture.getAsJsonArray("cases");
        assertTrue(cases.size() >= 5 && cases.size() >= 5 * emotes.size());
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (JsonElement ce : cases) {
            JsonObject c = ce.getAsJsonObject();
            EmoteSpec e = emotes.get(c.get("emote").getAsString());
            assertTrue(e != null, "the fixture knows an emote emotes.yml does not: python3 -I tools/assetgen/emotes.py --fixture");
            covered.add(e.id());
            String where = e.id() + " at tick " + c.get("tick").getAsDouble();
            Matrix4d[] joints = EmoteRig.matrices(e.poseAt(c.get("tick").getAsDouble()), e.planted());
            for (Joint j : Joint.values()) {
                JsonArray m = c.getAsJsonObject("joints").getAsJsonArray(j.key());
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 4; col++) {
                        assertEquals(m.get(row * 4 + col).getAsDouble(), joints[j.ordinal()].get(col, row), 2e-4, where + ": " + j + " [" + row + "][" + col + "]");
                    }
                }
            }
            JsonArray shown = c.getAsJsonArray("faces");
            assertEquals(shown.size(), faces.size(), where + ": faces");
            double facing = -Math.toRadians(c.get("yaw").getAsDouble()), lift = c.get("lift").getAsDouble();
            for (int i = 0; i < faces.size(); i++) {
                PuppetModel.Face f = faces.get(i);
                JsonObject want = shown.get(i).getAsJsonObject();
                String face = where + ": " + f.joint() + " layer " + f.layer() + " " + f.side();
                assertEquals(want.get("joint").getAsString(), f.joint().key(), face);
                assertEquals(want.get("layer").getAsInt(), f.layer(), face);
                assertEquals(want.get("face").getAsString(), f.side().name().toLowerCase(Locale.ROOT), face);
                assertEquals(want.get("cols").getAsInt(), f.cols(), face);
                assertEquals(want.get("rows").getAsInt(), f.rows(), face);
                PuppetModel.Placement at = PuppetModel.place(f, joints[f.joint().ordinal()], facing, lift);
                JsonArray t = want.getAsJsonArray("translation"), q = want.getAsJsonArray("rotation"), s = want.getAsJsonArray("scale");
                for (int k = 0; k < 3; k++) {
                    assertEquals(t.get(k).getAsDouble(), at.translation().get(k), 1e-4, face + " translation " + k);
                    assertEquals(s.get(k).getAsDouble(), at.scale().get(k), 1e-5, face + " scale " + k);
                }
                // q and -q are the same rotation
                double dot = q.get(0).getAsDouble() * at.rotation().x + q.get(1).getAsDouble() * at.rotation().y
                        + q.get(2).getAsDouble() * at.rotation().z + q.get(3).getAsDouble() * at.rotation().w;
                assertEquals(1, Math.abs(dot), 1e-6, face + " rotation");
            }
        }
        assertEquals(emotes.keySet(), covered, "every shipped emote is in the fixture: python3 -I tools/assetgen/emotes.py --fixture");
    }

    // ------------------------------------------------------------------------------------------
    // Angles
    // ------------------------------------------------------------------------------------------

    @Test
    void whatTheAnglesMean() {
        // pitch + tips the top forward, yaw + turns to the left, roll + leans the top to the puppet's right
        assertNear(new Vector3d(0, 0, 1), EmoteRig.euler(90, 0, 0).transform(new Vector3d(0, 1, 0)), 1e-12, "pitch");
        assertNear(new Vector3d(1, 0, 0), EmoteRig.euler(0, 90, 0).transform(new Vector3d(0, 0, 1)), 1e-12, "yaw");
        assertNear(new Vector3d(-1, 0, 0), EmoteRig.euler(0, 0, 90).transform(new Vector3d(0, 1, 0)), 1e-12, "roll");
        // roll, then yaw, then pitch applied first: Rz Ry Rx
        Matrix3d all = EmoteRig.euler(20, 30, 40);
        Matrix3d byHand = new Matrix3d().rotateZ(Math.toRadians(40)).mul(new Matrix3d().rotateY(Math.toRadians(30))).mul(new Matrix3d().rotateX(Math.toRadians(20)));
        assertTrue(all.equals(byHand, 1e-12));

        // the chest bends at the waist and carries the head and the arms; the hips do not follow it
        Matrix4d[] m = EmoteRig.matrices(Pose.REST.with(Channel.CHEST, 90, 0, 0), false);
        assertNear(new Vector3d(0, 16, 8), m[Joint.CHEST.ordinal()].transformPosition(new Vector3d(0, 24, 0)), 1e-9, "the neck swings forward");
        assertNear(new Vector3d(0, 16, 16), m[Joint.HEAD.ordinal()].transformPosition(new Vector3d(0, 32, 0)), 1e-9, "the head goes with the chest");
        assertNear(new Vector3d(5, 16, 6), m[Joint.ARM_L.ordinal()].transformPosition(new Vector3d(5, 22, 0)), 1e-9, "the shoulders too");
        assertNear(new Vector3d(2, 0, 0), m[Joint.SHIN_L.ordinal()].transformPosition(new Vector3d(2, 0, 0)), 1e-9, "the legs stay");
        // the root moves everything
        m = EmoteRig.matrices(Pose.REST.with(Channel.ROOT, 1, -3, 2), false);
        for (Joint j : Joint.values()) assertNear(new Vector3d(1, -3, 2), m[j.ordinal()].transformPosition(new Vector3d()), 1e-9, j + " moved with the root");
        // a knee: the shin hangs on the thigh
        m = EmoteRig.matrices(Pose.REST.with(Channel.THIGH_L, -90, 0, 0).with(Channel.SHIN_L, 90, 0, 0), false);
        assertNear(new Vector3d(2, 12, 6), m[Joint.THIGH_L.ordinal()].transformPosition(new Vector3d(2, 6, 0)), 1e-9, "thigh forward: the knee in front of the hip");
        assertNear(new Vector3d(2, 6, 6), m[Joint.SHIN_L.ordinal()].transformPosition(new Vector3d(2, 0, 0)), 1e-9, "shin down again: the foot under the knee");
    }

    // ------------------------------------------------------------------------------------------
    // Planted legs
    // ------------------------------------------------------------------------------------------

    @Test
    void plantedFeetStayOnTheirSpots() {
        for (EmoteSpec e : shipped().values()) {
            if (!e.planted()) continue;
            for (double t = 0; t < e.length(); t += 0.25) {
                Pose pose = e.poseAt(t);
                Matrix4d[] m = EmoteRig.matrices(pose, true);
                for (int side : new int[]{-1, 1}) {
                    Joint thigh = side < 0 ? Joint.THIGH_R : Joint.THIGH_L, shin = side < 0 ? Joint.SHIN_R : Joint.SHIN_L;
                    double[] f = pose.get(side < 0 ? Channel.FOOT_R : Channel.FOOT_L);
                    String where = e.id() + " at " + t + " " + shin;
                    Vector3d hip = m[Joint.HIPS.ordinal()].transformPosition(thigh.pivot());
                    Vector3d foot = new Vector3d(2.0 * side + f[0], f[1], f[2]);
                    assertTrue(hip.distance(foot) < 2 * EmoteRig.LEG - 0.05, where + ": the foot is out of the leg's reach (" + hip.distance(foot) + " px)");
                    assertNear(foot, m[shin.ordinal()].transformPosition(new Vector3d(2.0 * side, 0, 0)), 1e-9, where + ": the sole is on its spot");
                    assertNear(hip, m[thigh.ordinal()].transformPosition(thigh.pivot()), 1e-9, where + ": the thigh hangs in the hip");
                    assertNear(m[thigh.ordinal()].transformPosition(shin.pivot()), m[shin.ordinal()].transformPosition(shin.pivot()), 1e-9, where + ": one knee");
                    for (Joint j : new Joint[]{thigh, shin}) {
                        Matrix3d r = m[j.ordinal()].get3x3(new Matrix3d());
                        assertEquals(1, r.determinant(), 1e-9, where + ": a rotation");
                        assertTrue(new Matrix3d(r).transpose().mul(r).equals(new Matrix3d(), 1e-9), where + ": a rotation");
                    }
                }
            }
        }
    }

    @Test
    void solvedLegs() {
        // straight down under the hip, the knee forward: the rest pose
        EmoteRig.Leg rest = EmoteRig.solveLeg(new Vector3d(2, 12, 0), new Vector3d(2, 0.002, 0), new Vector3d(0, 0, 1));
        assertTrue(rest.thigh().equals(new Matrix3d(), 0.05) && rest.shin().equals(new Matrix3d(), 0.05));
        // a squat: the knee goes where the pole points, both bones keep their length
        Vector3d hip = new Vector3d(2, 8, -1), foot = new Vector3d(5, 0, 1);
        for (Vector3d pole : new Vector3d[]{new Vector3d(0, 0, 1), new Vector3d(1, 0, 1), new Vector3d(-1, 0, 0)}) {
            EmoteRig.Leg leg = EmoteRig.solveLeg(hip, foot, pole);
            assertEquals(EmoteRig.LEG, leg.knee().distance(hip), 1e-9);
            assertEquals(EmoteRig.LEG, leg.knee().distance(foot), 1e-9);
            Vector3d middle = new Vector3d(hip).add(foot).mul(0.5);
            assertTrue(new Vector3d(leg.knee()).sub(middle).dot(pole) > 0, "the knee is on the pole's side");
            assertNear(foot, leg.foot(), 1e-12, "in reach: the foot is where it was asked");
        }
        // out of reach: the leg is straight towards the spot, as long as it gets
        EmoteRig.Leg far = EmoteRig.solveLeg(new Vector3d(0, 12, 0), new Vector3d(0, -30, 0), new Vector3d(0, 0, 1));
        assertEquals(2 * EmoteRig.LEG, far.foot().distance(new Vector3d(0, 12, 0)), 2e-3);
        assertEquals(0, far.foot().x, 1e-9);
        // nothing breaks when the foot is in the hip or the pole along the leg
        EmoteRig.Leg odd = EmoteRig.solveLeg(new Vector3d(0, 5, 0), new Vector3d(0, 5, 0), new Vector3d(0, 1, 0));
        assertTrue(odd.knee().isFinite() && odd.thigh().isFinite() && odd.shin().isFinite());
    }

    // ------------------------------------------------------------------------------------------
    // Boxes of text displays
    // ------------------------------------------------------------------------------------------

    /** The eight corners of a face's box in a pose, in blocks from the entity, as the model says (no text involved). */
    private static List<Vector3d> boxCorners(PuppetModel.Box box, int layer, boolean slim, Matrix4d joint, double facing, double lift) {
        PuppetModel.SkinPart part = box.part();
        int w = part.width(slim);
        double g = layer == 0 ? 0 : box.joint() == Joint.HEAD ? PuppetModel.HAT : PuppetModel.COAT;
        boolean ownTop = box.r0() == 0, ownBottom = box.r1() == part.height();
        double x0 = box.x0() + (slim && part == PuppetModel.SkinPart.ARM_R ? 1 : 0);
        double[] xs = {x0 - g, x0 + w + g}, ys = {box.top() - box.r1() - (ownBottom ? g : g / 2), box.top() - box.r0() + (ownTop ? g : g / 2)},
                zs = {box.z0() - g, box.z0() + part.depth() + g};
        List<Vector3d> out = new ArrayList<>();
        for (double x : xs) for (double y : ys) for (double z : zs) {
            out.add(joint.transformPosition(new Vector3d(x, y, z)).rotateY(facing).mul(EmoteRig.PX).sub(0, lift, 0));
        }
        return out;
    }

    /**
     * For many poses, both layers, both arm widths: what the client draws for every face (the block of text of its
     * display, {@link PuppetModel#textRect}) is a side of its box: its four corners are corners of the box (so
     * neighbouring faces share their edges: the box is closed), its front (the side text is drawn on) looks out of
     * the box, and its glyph cells are square skin pixels.
     */
    @Test
    void everyBoxIsClosedAndFacesOutward() {
        // faces overlap by the seal: a corner is this far outside its box corner at most (half the growth of the longest face)
        double lip = (PuppetModel.SEAL - 1) / 2 * Math.sqrt(14 * 14 + 8 * 8) * EmoteRig.PX + 1e-6;
        for (boolean slim : new boolean[]{false, true}) {
            List<PuppetModel.Face> faces = PuppetModel.faces(plain(slim));
            assertEquals(2 * 6 * PuppetModel.BOXES.size(), faces.size(), "an opaque skin: six faces of every box in both layers");
            for (Object[] pose : poses()) {
                double facing = -Math.toRadians(123), lift = 1.0;
                Matrix4d[] joints = EmoteRig.matrices((Pose) pose[1], (Boolean) pose[2]);
                int i = 0;
                for (int layer = 0; layer < 2; layer++) {
                    for (PuppetModel.Box box : PuppetModel.BOXES) {
                        List<Vector3d> corners = boxCorners(box, layer, slim, joints[box.joint().ordinal()], facing, lift);
                        Vector3d middle = new Vector3d();
                        corners.forEach(middle::add);
                        middle.div(8);
                        int[] used = new int[8];
                        for (int k = 0; k < 6; k++) {
                            PuppetModel.Face f = faces.get(i++);
                            String where = pose[0] + (slim ? " slim " : " ") + box.joint() + " layer " + layer + " " + f.side();
                            assertEquals(box.joint(), f.joint(), where);
                            assertEquals(layer, f.layer(), where);
                            Vector3d[] rect = PuppetModel.textRect(PuppetModel.place(f, joints[f.joint().ordinal()], facing, lift), f.cols(), f.rows());
                            Vector3d right = new Vector3d(rect[1]).mul(f.cols()), down = new Vector3d(rect[2]).mul(f.rows());
                            Vector3d[] quad = {rect[0], new Vector3d(rect[0]).add(right), new Vector3d(rect[0]).add(down), new Vector3d(rect[0]).add(right).add(down)};
                            for (Vector3d q : quad) {
                                int nearest = 0;
                                for (int c = 1; c < 8; c++) if (corners.get(c).distance(q) < corners.get(nearest).distance(q)) nearest = c;
                                assertTrue(corners.get(nearest).distance(q) <= lip, where + ": a corner of the text is " + corners.get(nearest).distance(q) + " blocks off its box");
                                used[nearest]++;
                            }
                            // text is drawn on the side its right x up points to (the client culls the other)
                            Vector3d front = new Vector3d(right).cross(new Vector3d(down).negate()).normalize();
                            Vector3d centre = new Vector3d(rect[0]).fma(0.5, right).fma(0.5, down);
                            assertTrue(front.dot(new Vector3d(centre).sub(middle).normalize()) > 0.999, where + ": faces out of the box");
                            // a glyph cell is a skin pixel: square, and a pixel wide (a little more on the second layer: most on a slim sleeve, 3.5 over 3)
                            double cell = rect[1].length() / EmoteRig.PX, cellDown = rect[2].length() / EmoteRig.PX;
                            assertEquals(0, right.dot(down), 1e-9, where + ": a rectangle");
                            assertTrue(cell >= 1 && cell < 1.18 && cellDown >= 1 && cellDown < 1.18, where + ": cells of " + cell + " x " + cellDown + " px");
                            if (layer == 0) assertEquals(PuppetModel.SEAL, cell, 1e-9, where);
                        }
                        // three faces meet in every corner of a box
                        for (int c = 0; c < 8; c++) assertEquals(3, used[c], pose[0] + " " + box.joint() + " layer " + layer + ": faces at corner " + c);
                    }
                }
            }
        }
    }

    /** Where a face's text starts and which way it runs, at rest: upright faces from their top-left, the others turned half around. */
    @Test
    void whereTheTextStarts() {
        List<PuppetModel.Face> faces = PuppetModel.faces(plain(false));
        Matrix4d[] rest = EmoteRig.matrices(Pose.REST, false);
        double seal = 0.004;
        for (PuppetModel.Face f : faces) {
            if (f.layer() != 0) continue;
            Vector3d[] rect = PuppetModel.textRect(PuppetModel.place(f, rest[f.joint().ordinal()], 0, 0), f.cols(), f.rows());
            Vector3d start = new Vector3d(rect[0]).div(EmoteRig.PX), right = new Vector3d(rect[1]).normalize(), down = new Vector3d(rect[2]).normalize();
            Vector3d u = f.side().u(), v = f.side().v();
            if (f.flipped()) {
                u.negate();
                v.negate();
            }
            assertNear(u, right, 1e-9, f.joint() + " " + f.side() + ": text right");
            assertNear(v.negate(), down, 1e-9, f.joint() + " " + f.side() + ": text down");
            if (f.joint() == Joint.HEAD && f.side() == PuppetModel.Side.FRONT) {
                assertTrue(!f.flipped(), "the head turns about the neck, under it: upright");
                assertNear(new Vector3d(-4, 32, 4), start, 8 * seal, "the face starts at the head's top right corner (the puppet's right)");
            }
            if (f.joint() == Joint.HEAD && f.side() == PuppetModel.Side.TOP) assertNear(new Vector3d(-4, 32, -4), start, 8 * seal, "the top of the head starts at the back right");
            if (f.joint() == Joint.HEAD && f.side() == PuppetModel.Side.BOTTOM) assertNear(new Vector3d(-4, 24, 4), start, 8 * seal, "the bottom of the head starts at the front right");
            if (f.joint() == Joint.HEAD && f.side() == PuppetModel.Side.BACK) assertNear(new Vector3d(4, 32, -4), start, 8 * seal, "the back of the head starts at its left");
            if (f.joint() == Joint.THIGH_R && f.side() == PuppetModel.Side.FRONT) {
                assertTrue(f.flipped(), "the thigh hangs from the hip, over it: upside down, the text's origin at the hip");
                assertNear(new Vector3d(0, 4, 2), start, 8 * seal, "so it starts at the bottom, at the puppet's left edge of the right leg");
            }
            // the display's origin (its translation) is at the edge of the face nearer the joint
            if (f.side() != PuppetModel.Side.TOP && f.side() != PuppetModel.Side.BOTTOM) {
                Vector3d origin = new Vector3d(PuppetModel.place(f, rest[f.joint().ordinal()], 0, 0).translation()).div(EmoteRig.PX);
                double top = f.centre().y + f.rows() * f.cellHeight() / 2, bottom = f.centre().y - f.rows() * f.cellHeight() / 2, joint = f.joint().pivot().y;
                double nearer = Math.abs(top - joint) < Math.abs(bottom - joint) ? top : bottom;
                assertEquals(nearer, origin.y, 0.2, f.joint() + " " + f.side() + ": the origin is at the edge nearer the joint (" + joint + ")");
                assertTrue(Math.abs(nearer - joint) <= 2, f.joint() + ": that edge is within the overlap of the joint");
            }
        }
    }

    /**
     * Between two poses the client moves every display's origin in a straight line and turns it on the shortest way,
     * each face on its own. For the shipped emotes at their step: no joint turns far in one step and the sides of a
     * box stay together to a small part of a skin pixel on the way.
     */
    @Test
    void facesStayTogetherBetweenTwoPoses() {
        List<PuppetModel.Face> faces = PuppetModel.faces(plain(false));
        for (EmoteSpec e : shipped().values()) {
            for (int t = 0; t < e.length(); t += e.step()) {
                Matrix4d[] a = EmoteRig.matrices(e.poseAt(t), e.planted()), b = EmoteRig.matrices(e.poseAt(t + e.step()), e.planted());
                for (Joint j : Joint.values()) {
                    Matrix3d turn = a[j.ordinal()].get3x3(new Matrix3d()).transpose().mul(b[j.ordinal()].get3x3(new Matrix3d()));
                    double angle = Math.toDegrees(Math.acos(Math.clamp((turn.m00 + turn.m11 + turn.m22 - 1) / 2, -1, 1)));
                    assertTrue(angle <= 20, e.id() + " tick " + t + ": " + j + " turns " + angle + " degrees in one step (keep steps small: the faces of a box blend one by one)");
                }
                for (PuppetModel.Box box : PuppetModel.BOXES) {
                    List<Vector3d[]> quads = new ArrayList<>();
                    for (PuppetModel.Face f : faces) {
                        if (f.layer() != 0 || f.joint() != box.joint() || f.side() == PuppetModel.Side.TOP || f.side() == PuppetModel.Side.BOTTOM) continue;
                        PuppetModel.Placement p0 = PuppetModel.place(f, a[f.joint().ordinal()], 0, 0), p1 = PuppetModel.place(f, b[f.joint().ordinal()], 0, 0);
                        PuppetModel.Placement half = new PuppetModel.Placement(new Vector3d(p0.translation()).lerp(p1.translation(), 0.5),
                                new org.joml.Quaterniond(p0.rotation()).slerp(p1.rotation(), 0.5), p0.scale());
                        Vector3d[] rect = PuppetModel.textRect(half, f.cols(), f.rows());
                        Vector3d right = new Vector3d(rect[1]).mul(f.cols()), down = new Vector3d(rect[2]).mul(f.rows());
                        quads.add(new Vector3d[]{rect[0], new Vector3d(rect[0]).add(right), new Vector3d(rect[0]).add(down), new Vector3d(rect[0]).add(right).add(down)});
                    }
                    for (int i = 0; i < quads.size(); i++) {
                        for (Vector3d corner : quads.get(i)) {
                            double nearest = Double.MAX_VALUE;
                            for (int k = 0; k < quads.size(); k++) if (k != i) for (Vector3d other : quads.get(k)) nearest = Math.min(nearest, other.distance(corner));
                            assertTrue(nearest / EmoteRig.PX < 0.08, e.id() + " tick " + t + ".5: the sides of " + box.joint() + " are " + nearest / EmoteRig.PX + " px apart");
                        }
                    }
                }
            }
        }
    }

    static void assertNear(Vector3d want, Vector3d got, double within, String what) {
        assertTrue(want.distance(got) <= within, what + ": wanted " + want + ", got " + got);
    }
}
