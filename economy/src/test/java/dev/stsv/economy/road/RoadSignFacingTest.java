package dev.stsv.economy.road;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every 국도 sign faces the traffic it is for, on every heading (every 15 degrees) and round bends: the model's face and
 * its words both look back at that traffic, the sign stands on its right, the words read left to right as the driver
 * sees them and sit just in front of the face (never behind it). The way-back signs (RETURN), and the guide signs for the
 * traffic coming back, stand on the other side of the road facing the other way: drivers going out see their backs, the
 * solid back of the model (RoadSignModelTest) hiding their words.
 *
 * <p>How the game draws them (as {@code FurnitureModule.rotate} for the item display, vanilla for the text display): a
 * text display's words face its yaw (yaw 0: south, +z); an item display turns its model 180 degrees, then by its yaw, so
 * the model's north face (-z, the sign's face: tools/assetgen/models/roads.py) looks the same way, and its -x (the
 * viewer's right on the panel) goes to the viewer's right.
 */
class RoadSignFacingTest {

    /** Where a text display's words look: yaw 0 south (+z), 90 west. */
    static double[] textFacing(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** A model point (x, z) as the item display draws it: turned 180 degrees, then by the yaw (FurnitureModule.rotate). */
    static double[] model(double x, double z, float yaw) {
        double r = Math.toRadians(yaw), mx = -x, mz = -z;
        return new double[]{mx * Math.cos(r) - mz * Math.sin(r), mx * Math.sin(r) + mz * Math.cos(r)};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    static double[] right(double[] h) {
        return new double[]{-h[1], h[0]};
    }

    /**
     * A sign of yaw at (x, z) for traffic heading h (unit) past road point p: faces it, on its right, words in front of
     * the face, reading to the driver's right.
     */
    static void facesTraffic(String what, float yaw, double x, double z, double[] p, double[] h, List<RoadSign.Text> texts) {
        double[] back = {-h[0], -h[1]};
        double[] front = model(0, -1, yaw), text = textFacing(yaw);
        assertTrue(dot(front, back) > 0.999, what + ": the model's face looks back at the traffic (yaw " + yaw + ")");
        assertTrue(dot(text, back) > 0.999, what + ": the words look back at the traffic");
        assertTrue(dot(new double[]{x - p[0], z - p[1]}, right(h)) > 2, what + ": on the traffic's right");
        double[] viewerRight = right(h);
        assertTrue(dot(model(-1, 0, yaw), viewerRight) > 0.999, what + ": the panel's right is the driver's right");
        assertTrue(dot(RoadSign.offset(yaw, 1, 0), viewerRight) > 0.999, what + ": the words' right is the driver's right");
        for (RoadSign.Text t : texts) {
            double[] o = RoadSign.offset(yaw, t.right(), t.out());
            double out = dot(o, front);
            assertTrue(out > RoadSign.FACE && out < RoadSign.FACE + 0.05, what + ": '" + t.text() + "' just in front of the face: " + out);
        }
    }

    /** Flat ground at 64, recording the signs the road puts. */
    static class Flat implements RoadSection.Site {
        record Put(double x, double z, float yaw, RoadSign.Kind kind) {}

        final List<Put> signs = new ArrayList<>();
        final java.util.Map<Long, Material> put = new java.util.HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            return m != null ? m : y <= 64 ? Material.DIRT : Material.AIR;
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public boolean liquid(int x, int y, int z) {
            return false;
        }

        public boolean natural(int x, int y, int z) {
            return false;
        }

        public boolean fillable(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public void set(int x, int y, int z, Material m) {
            put.put(key(x, y, z), m);
        }

        public boolean protectedLand(int x, int z) {
            return false;
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {
            signs.add(new Put(x, z, yaw, kind));
        }
    }

    /** Builds a road through these corners (as RoadModule does) and returns its points and the signs it put. */
    static List<Flat.Put> build(List<double[]> pts, int every) {
        int n = pts.size();
        int[] ground = new int[n];
        java.util.Arrays.fill(ground, 64);
        int[] halves = RoadProfile.planHalves(ground, new boolean[n], ground);
        Flat land = new Flat();
        RoadSection s = new RoadSection(land, new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, every),
                pts, halves, RoadProfile.partsHalves(halves, ground, new boolean[n], 3, 5, 4), "서해", 1);
        for (int i = 0; i < n; i++) s.build(i);
        return land.signs;
    }

    static double[] tangent(List<double[]> pts, int i) {
        double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(pts.size() - 1, i + 2));
        double l = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return new double[]{(b[0] - a[0]) / l, (b[1] - a[1]) / l};
    }

    static int nearest(List<double[]> pts, double x, double z) {
        int best = 0;
        for (int i = 1; i < pts.size(); i++)
            if (Math.hypot(pts.get(i)[0] - x, pts.get(i)[1] - z) < Math.hypot(pts.get(best)[0] - x, pts.get(best)[1] - z)) best = i;
        return best;
    }

    static double[] at(double deg, double len) {
        double r = Math.toRadians(deg);
        return new double[]{0.5 + Math.cos(r) * len, 0.5 + Math.sin(r) * len};
    }

    @Test
    void theRoadsOwnSignsFaceItsTrafficOnEveryHeading() {
        for (int deg = 0; deg < 360; deg += 15) {
            List<double[]> pts = RoadPlanner.densify(List.of(at(deg, 0), at(deg, 520)), 1);
            List<Flat.Put> signs = build(pts, 200);
            Set<RoadSign.Kind> kinds = EnumSet.noneOf(RoadSign.Kind.class);
            for (Flat.Put s : signs) {
                int i = nearest(pts, s.x(), s.z());
                kinds.add(s.kind());
                facesTraffic(deg + "° " + s.kind() + " at " + i, s.yaw(), s.x(), s.z(), pts.get(i), tangent(pts, i),
                        RoadSign.texts(RoadSign.words(s.kind(), "서해", 1.2, 1)));
            }
            assertEquals(EnumSet.of(RoadSign.Kind.START, RoadSign.Kind.KM, RoadSign.Kind.ARRIVAL), kinds, deg + "°");
        }
    }

    @Test
    void roundBendsTooEveryWay() {
        // a road turning through every heading: a square spiral-ish loop of 8 legs, 45 degrees a bend
        for (int start = 0; start < 360; start += 15) {
            List<double[]> corners = new ArrayList<>();
            double x = 0.5, z = 0.5;
            corners.add(new double[]{x, z});
            for (int k = 0; k < 8; k++) {
                double r = Math.toRadians(start + 45 * k);
                x += Math.cos(r) * 260;
                z += Math.sin(r) * 260;
                corners.add(new double[]{x, z});
            }
            List<double[]> pts = RoadPlanner.densify(corners, 1);
            List<Flat.Put> signs = build(pts, 230);
            assertTrue(signs.size() >= 8, start + "°: " + signs.size());
            for (Flat.Put s : signs) {
                int i = nearest(pts, s.x(), s.z());
                facesTraffic(start + "° bend " + s.kind() + " at " + i, s.yaw(), s.x(), s.z(), pts.get(i), tangent(pts, i),
                        RoadSign.texts(RoadSign.words(s.kind(), "서해", 1.2, 1)));
            }
        }
    }

    /** A road as saved, its corners turned by deg round the origin. */
    static RoadJob turned(String dock, int route, String branchOf, double deg, double[]... corners) {
        double r = Math.toRadians(deg);
        double[][] c = new double[corners.length][];
        for (int i = 0; i < corners.length; i++) {
            double cx = corners[i][0], cz = corners[i][1];
            c[i] = new double[]{Math.rint(cx * Math.cos(r) - cz * Math.sin(r)) + 0.5, Math.rint(cx * Math.sin(r) + cz * Math.cos(r)) + 0.5};
        }
        return RoadGuideTest.saved(dock, route, branchOf, c);
    }

    @Test
    void guideAndWayBackSignsFaceTheirTrafficOnEveryHeading() {
        for (int deg = 0; deg < 360; deg += 15) {
            RoadJob trunk = turned("서해", 1, null, deg, new double[]{0, 0}, new double[]{4200, 0});
            RoadJob branch = turned("동해", 2, "서해", deg, new double[]{1500, 0}, new double[]{1900, -400}, new double[]{1900, -2000});
            List<RoadGuide.Road> roads = List.of(RoadGuideTest.road(trunk, RoadGuideTest.HOME), RoadGuideTest.road(branch, RoadGuideTest.HOME));
            RoadGuide.Plan plan = RoadGuide.plan(roads, 1000, 40);
            Set<RoadGuide.Type> types = EnumSet.noneOf(RoadGuide.Type.class);
            for (RoadGuide.Sign s : plan.signs()) {
                List<double[]> pts = roads.get(s.road()).points();
                double[] t = tangent(pts, s.at());
                int way = s.side() == 0 ? 1 : s.side();
                double[] h = {t[0] * way, t[1] * way};
                types.add(s.type());
                List<RoadSign.Text> texts = s.guide() ? RoadSign.guideTexts(s.rows()) : RoadSign.texts(s.words());
                String what = deg + "° " + s.type() + " of " + s.host() + " at " + s.at();
                if (s.side() == 0) {   // the gore sign: in the V, facing the traffic coming to the fork
                    assertTrue(dot(model(0, -1, s.yaw()), new double[]{-h[0], -h[1]}) > 0.999, what);
                    assertTrue(dot(textFacing(s.yaw()), new double[]{-h[0], -h[1]}) > 0.999, what);
                    continue;
                }
                facesTraffic(what, s.yaw(), s.x(), s.z(), pts.get(s.at()), h, texts);
                if (s.guide()) for (RoadSign.ArrowAt a : RoadSign.guideArrows(s.rows())) {
                    assertTrue(a.out() > RoadSign.FACE, what + ": its arrows in front of the face");
                }
            }
            assertTrue(types.containsAll(EnumSet.of(RoadGuide.Type.RETURN, RoadGuide.Type.ADVANCE, RoadGuide.Type.KM, RoadGuide.Type.GORE)),
                    deg + "°: " + types);
            // the way-back signs face the other way (the traffic going back), on the left of the traffic going out
            for (RoadGuide.Sign s : plan.signs()) {
                if (s.type() != RoadGuide.Type.RETURN) continue;
                assertEquals(-1, s.side());
                double[] out = tangent(roads.get(s.road()).points(), s.at());
                assertTrue(dot(textFacing(s.yaw()), out) > 0.999, "faces the traffic coming back: drivers going out see its back");
            }
        }
    }

    @Test
    void theWordsNeverStickOutPastThePanelsBack() {
        // whatever the words, they fit inside the panel (the solid back hides all of them from behind)
        for (String dock : List.of("서", "서해", "아주아주긴이름의하역장", "Port Royal Harbour")) {
            for (RoadSign.Kind k : RoadSign.Kind.values()) {
                for (RoadSign.Text t : RoadSign.texts(RoadSign.words(k, dock, 123.4, 12))) {
                    double half = RoadSign.width(t.text()) * RoadSign.PX * t.scale() / 16 / 2, mid = t.right() + RoadSign.PX * t.scale() / 16;
                    assertTrue(mid - half >= -RoadSign.PANEL_WIDTH / 2 - 1e-9 && mid + half <= RoadSign.PANEL_WIDTH / 2 + 1e-9, k + " '" + t.text() + "'");
                    assertTrue(t.up() >= RoadSign.PANEL_BOTTOM && t.up() < RoadSign.PANEL_TOP, k + " '" + t.text() + "'");
                }
            }
        }
        assertFalse(RoadSign.BACK < 3 / 16.0, "the back is as deep as the model's");
    }
}
