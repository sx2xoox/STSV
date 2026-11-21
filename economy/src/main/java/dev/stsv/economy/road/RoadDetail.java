package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 국도 디테일 (README "국도", 디테일): the small things along a road that are words, put up with its signs ({@link RoadGuide}:
 * when it is finished and by /관리자 국도 표지판 다시세우기, so a road built before gets them too): a kilometre post every km
 * ("국도 7호선 · 3km" on a stone post), chevron boards on the outside of sharp bends (denser on a hard road), a name plate
 * at each end of a bridge ("서해1교") and over each tunnel's portals ("서해1터널"). Text displays; a post (a stone brick wall
 * on the shoulder) only for km posts and chevrons, never nearer the centre line than the vehicles reach there
 * ({@link #reachAt} + {@link #POST_GAP}). Pure (unit-tested: RoadCharacterTest).
 */
public final class RoadDetail {

    private RoadDetail() {}

    public enum Style { KM_POST, CHEVRON, NAME }

    /**
     * A label: whose road (host), where its text's middle is (y: its bottom), its face's yaw (Minecraft: towards the
     * traffic it is for), its words and look, the column its post stands in (null: none) on the level `level` (the
     * shoulder's block), the road's point.
     */
    public record Label(String host, double x, double y, double z, float yaw, String text, Style style, int[] post, int level, int at) {}

    /** A km post this many points past each km (clear of the km sign); a chevron where the road turns this much. */
    static final int KM = 1000, KM_AT = 17;
    static final double CHEVRON_TURN = 45;
    /** Chevron boards along a bend's outside: this many points apart, this many either way of its corner. */
    static final int CHEVRON_EVERY = 6, CHEVRON_EVERY_HARD = 4, CHEVRON_SPAN = 2, CHEVRON_SPAN_HARD = 3;
    /** A post at least this much further out than the vehicles reach (its own half and a margin). */
    static final double POST_GAP = 0.75;
    /** A bridge or tunnel this long or more gets name plates. */
    static final int NAMED_FROM = 8;

    /**
     * How far from the centre line the vehicles reach at point i, as {@link RoadSection#reach} works it out (the sharpest
     * bend within {@link RoadSection#BEND_REACH} points, the most slant within {@link RoadClearance#REACH}), scaled round
     * bends by the road's character.
     */
    static double reachAt(List<double[]> pts, int i, double bendScale) {
        int n = pts.size();
        double b = 0, d = 1;
        for (int j = Math.max(0, i - RoadSection.BEND_REACH); j <= Math.min(n - 1, i + RoadSection.BEND_REACH); j++) b = Math.max(b, RoadSection.turnAt(pts, j));
        for (int j = Math.max(0, i - RoadClearance.REACH); j <= Math.min(n - 1, i + RoadClearance.REACH); j++) {
            double[] h = heading(pts, j);
            d = Math.max(d, RoadClearance.diagonal(h[0], h[1]));
        }
        d = Math.max(d, 1 + (Math.sqrt(2) - 1) * Math.min(1, b / 45));
        return RoadClearance.reach(d, b * bendScale);
    }

    static double[] heading(List<double[]> pts, int i) {
        double[] a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(pts.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        return new double[]{hx / len, hz / len};
    }

    /** The labels of a road (its parts as built; signs: where its own signs stand, kept clear of). */
    public static List<Label> of(RoadGuide.Road road, RoadProfile.Part[] parts, Set<Integer> signs, int lightEvery) {
        List<Label> out = new ArrayList<>();
        List<double[]> pts = road.points();
        int n = Math.min(pts.size(), Math.min(parts.length, road.halves().length));
        if (n < 40) return out;
        RoadCharacter c = road.character();
        double scale = c.shape().bendScale();
        int every = c.shape().lightEvery(lightEvery);
        String line = (c == RoadCharacter.EASY ? RoadSign.EASY_ROUTE : "국도") + " " + road.route() + "호선";
        // km posts, on the right
        for (int k = 1; k * KM + KM_AT < n - 30; k++) {
            for (int i = k * KM + KM_AT; i < k * KM + KM_AT + 20 && i < n - 30; i++) {
                if (!plain(parts, i) || RoadSection.turnAt(pts, i) >= RoadSection.BEND || nearLight(i, every) || near(signs, i, 8)) continue;
                Label l = post(road, pts, i, 1, Math.max(4.0, reachAt(pts, i, scale) + POST_GAP), line + " · " + k + "km", Style.KM_POST, 1.1);
                if (l != null) out.add(l);
                break;
            }
        }
        // chevrons on the outside of sharp bends, for the traffic from the start
        boolean hard = c == RoadCharacter.HARD;
        int step = hard ? CHEVRON_EVERY_HARD : CHEVRON_EVERY, span = hard ? CHEVRON_SPAN_HARD : CHEVRON_SPAN;
        for (int v = 1; v < n - 1; v++) {
            if (RoadSection.turnAt(pts, v) < CHEVRON_TURN) continue;
            int end = v;
            while (end + 1 < n && RoadSection.turnAt(pts, end + 1) >= CHEVRON_TURN) end++;
            int corner = v;
            for (int j = v; j <= end; j++) if (RoadSection.turnAt(pts, j) > RoadSection.turnAt(pts, corner)) corner = j;
            int side = -turnSide(pts, corner);   // the outside
            String arrows = side < 0 ? ">>>" : "<<<";   // the way the road turns, as the driver sees it
            for (int k = -span; k <= span; k++) {
                int i = corner + k * step;
                if (i < 2 || i > n - 3 || parts[i] != RoadProfile.Part.ROAD || near(signs, i, 4) || nearLight(i, every)) continue;
                Label l = post(road, pts, i, side, reachAt(pts, i, scale) + POST_GAP + 0.25, arrows, Style.CHEVRON, 0.9);
                if (l != null) out.add(l);
            }
            v = end;
        }
        // name plates at bridges' ends and over tunnels' portals
        int bridges = 0, tunnels = 0;
        int[] ceil = RoadClearance.ceilings(road.halves());
        for (int i = 0; i < n; i++) {
            RoadProfile.Part p = parts[i];
            if (p == RoadProfile.Part.ROAD) continue;
            int j = i;
            while (j + 1 < n && parts[j + 1] == p) j++;
            if (j - i + 1 >= NAMED_FROM) {
                boolean bridge = p == RoadProfile.Part.BRIDGE;
                String name = road.dock() + (bridge ? ++bridges + "교" : ++tunnels + "터널");
                for (int at : new int[]{i, j}) {
                    int way = at == i ? 1 : -1;   // the traffic going in at this end
                    double[] h = heading(pts, at), q = pts.get(at);
                    float yaw = RoadSign.yaw(-h[0] * way, -h[1] * way);
                    double y = bridge ? RoadSection.top(road.halves()[at]) + 1.3 : ceil[at] + 1.15;
                    double x = q[0], z = q[1];
                    if (bridge) {   // over the right railing of the traffic going in
                        double lat = 3.5;
                        x += -h[1] * way * lat;
                        z += h[0] * way * lat;
                    } else {   // on the portal's face
                        x -= h[0] * way * 0.55;
                        z -= h[1] * way * 0.55;
                    }
                    out.add(new Label(road.dock(), x, y, z, yaw, name, Style.NAME, null, RoadSection.surfaceY(road.halves()[at]), at));
                }
            }
            i = j;
        }
        return out;
    }

    /** A label on a post `lat` out on `side` (1: right of the way to the dock) at point i, facing the traffic from the start. */
    private static Label post(RoadGuide.Road road, List<double[]> pts, int i, int side, double lat, String text, Style style, double up) {
        double[] h = heading(pts, i), q = pts.get(i);
        double rx = -h[1] * side, rz = h[0] * side;
        double x = q[0] + rx * lat, z = q[1] + rz * lat;
        int[] col = {(int) Math.floor(x), (int) Math.floor(z)};
        // a chevron round a sharp bend: its column never drawn in by the rounding more than half a block (a hard road's
        // bend room leaves no margin to spare)
        for (int k = 0; style == Style.CHEVRON && k < 4 && Math.hypot(col[0] + 0.5 - q[0], col[1] + 0.5 - q[1]) < lat - 0.5; k++) {
            lat += 0.25;
            x = q[0] + rx * lat;
            z = q[1] + rz * lat;
            col = new int[]{(int) Math.floor(x), (int) Math.floor(z)};
        }
        int level = RoadProfile.block(road.halves()[i]);
        return new Label(road.dock(), col[0] + 0.5, level + 1 + up, col[1] + 0.5, RoadSign.yaw(-h[0], -h[1]), text, style, col, level, i);
    }

    /** 1: the road turns right at i (its right is the bend's inside), -1: left. */
    static int turnSide(List<double[]> pts, int i) {
        double[] a = pts.get(Math.max(0, i - RoadSection.BEND_REACH)), p = pts.get(i), b = pts.get(Math.min(pts.size() - 1, i + RoadSection.BEND_REACH));
        double cross = (p[0] - a[0]) * (b[1] - p[1]) - (p[1] - a[1]) * (b[0] - p[0]);
        return cross >= 0 ? 1 : -1;
    }

    private static boolean plain(RoadProfile.Part[] parts, int i) {
        return RoadSection.plain(parts, i, 1, 1) == i;
    }

    private static boolean near(Set<Integer> s, int i, int d) {
        for (int k = i - d; k <= i + d; k++) if (s.contains(k)) return true;
        return false;
    }

    private static boolean nearLight(int i, int every) {
        int m = Math.floorMod(i, Math.max(1, every));
        return m <= RoadSection.LIGHT_SHIFT + 2 || every - m <= 2;
    }
}
