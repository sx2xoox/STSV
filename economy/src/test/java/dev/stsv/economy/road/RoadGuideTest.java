package dev.stsv.economy.road;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 wayfinding ({@link RoadGuide}) from saved roads: forks found where centre lines part (also for roads saved before
 * branches were known), advance signs on the right shoulder before a fork facing the traffic, the arrow of the side a
 * branch leaves on, distances, crossings, the way back, never a post in the lanes, and putting them up twice leaves one set.
 */
class RoadGuideTest {

    static final String HOME = "본사 출발 터미널";

    /** A finished road saved as RoadModule saves it (flat ground), read back. */
    static RoadJob saved(String dock, int route, String branchOf, double[]... corners) {
        RoadJob j = new RoadJob();
        j.dock = dock;
        j.world = "world";
        j.route = route;
        j.phase = RoadJob.Phase.DONE;
        j.running = false;
        j.startX = corners[0][0];
        j.startZ = corners[0][1];
        j.endX = corners[corners.length - 1][0];
        j.endZ = corners[corners.length - 1][1];
        if (branchOf != null) {
            j.branchOf = branchOf;
            j.branchKm = 1;
        }
        j.corners.addAll(List.of(corners));
        for (int i = 0; i < j.points().size(); i++) {
            RoadJob.Ground g = new RoadJob.Ground();
            g.ground = 63;
            g.surface = 63;
            j.ground.add(g);
        }
        j.cursor = j.ground.size();
        return RoadJob.read(j.write());
    }

    static RoadGuide.Road road(RoadJob j, String home) {
        return RoadModule.guideRoad(j, null, home);
    }

    static double[] p(double x, double z) {
        return new double[]{x + 0.5, z + 0.5};
    }

    /** The trunk: east along z 0 from the origin, 3000 long (its km signs at 1000 and 2000). */
    static RoadJob trunk() {
        return saved("서해", 1, null, p(0, 0), p(3000, 0));
    }

    static void postsClear(RoadGuide.Plan plan, List<RoadGuide.Road> roads) {
        for (RoadGuide.Sign s : plan.signs()) {
            for (int[] c : s.posts()) {
                for (RoadGuide.Road r : roads) {
                    for (double[] q : r.points()) {
                        assertTrue(Math.hypot(c[0] + 0.5 - q[0], c[1] + 0.5 - q[1]) > RoadGuide.LANES,
                                "a post in " + r.dock() + "'s lanes at " + c[0] + ", " + c[1] + ": " + s);
                    }
                }
            }
        }
    }

    @Test
    void aRoadSavedBeforeBranchesWereKnownForksWhereItLeavesTheTrunk() {
        // saved before the network: no branch= line, its first 1100 points on the trunk's, then off to the north-east
        RoadJob east = saved("동해", 2, null, p(0, 0), p(1100, 0), p(1400, -300), p(1400, -1500));
        assertEquals(null, east.branchOf);
        assertEquals(null, east.halves, "an old road: its heights worked out again");
        List<RoadGuide.Road> roads = List.of(road(trunk(), HOME), road(east, HOME));
        RoadGuide.Plan plan = RoadGuide.plan(roads, 1000, 40);
        assertEquals(1, plan.junctions().size(), plan.junctions().toString());
        RoadGuide.Junction j = plan.junctions().getFirst();
        assertTrue(j.fork());
        assertEquals(0, j.a(), "the older road is the trunk");
        assertEquals(1100, j.ai());
        assertEquals(1100, j.bi(), "where the centre lines part");
        // traffic from the origin: ↖ 동해 (it leaves on the left), ↑ 서해, 120 and 40 before the fork
        List<RoadGuide.Sign> advance = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.ADVANCE && s.host().equals("서해") && s.at() < 1100).toList();
        assertEquals(List.of(980, 1060), advance.stream().map(RoadGuide.Sign::at).sorted().toList());
        int eastLen = roads.get(1).points().size() - 1;
        for (RoadGuide.Sign s : advance) {
            int d = 1100 - s.at();
            assertEquals(List.of(new RoadSign.Row(RoadSign.Arrow.UP_LEFT, "동해 하역장", RoadSign.km((eastLen - 1100 + d) / 1000.0)),
                    new RoadSign.Row(RoadSign.Arrow.UP, "서해 하역장", RoadSign.km((3000 - 1100 + d) / 1000.0))), s.rows());
            assertEquals(s.at() + 0.5, s.x(), 1e-9);
            assertEquals(0.5 + RoadSign.GUIDE_LATERAL, s.z(), 1e-9, "on the trunk's shoulder, right of the eastbound traffic");
            assertEquals(90f, s.yaw(), 1e-3f, "facing the traffic from the origin (west)");
            assertEquals("동해", s.also());
            for (int[] c : s.posts()) assertTrue(c[1] >= 3, "on the shoulder: " + c[1]);
        }
        // the gore sign: in the V, both ways
        RoadGuide.Sign gore = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.GORE).findFirst().orElseThrow();
        assertTrue(gore.x() > 1100 && gore.z() < -4);
        assertEquals(List.of("동해 하역장", "서해 하역장"), gore.rows().stream().map(RoadSign.Row::name).toList());
        // no sign of the branch on the stretch it shares with the trunk (the trunk's own stand there)
        assertTrue(plan.signs().stream().noneMatch(s -> s.host().equals("동해") && s.also() == null && s.at() < 1100), "nothing doubled on the shared stretch");
        postsClear(plan, roads);
    }

    @Test
    void aBranchToTheRightGetsTheRightArrowAndTheTrunkAConfirmation() {
        RoadJob south = saved("남해", 2, "서해", p(1100, 0), p(1400, 300), p(1400, 1500));
        List<RoadGuide.Road> roads = List.of(road(trunk(), HOME), road(south, HOME));
        RoadGuide.Plan plan = RoadGuide.plan(roads, 1000, 40);
        RoadGuide.Junction j = plan.junctions().getFirst();
        assertEquals(1100, j.ai());
        assertEquals(0, j.bi());
        RoadGuide.Sign near = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.ADVANCE && s.at() == 1060).findFirst().orElseThrow();
        assertEquals(List.of(RoadSign.Arrow.UP, RoadSign.Arrow.UP_RIGHT), near.rows().stream().map(RoadSign.Row::arrow).toList(), "the branch leaves on the right");
        assertEquals("남해 하역장", near.rows().get(1).name());
        assertEquals(RoadSign.km((roads.get(1).points().size() - 1 + 40) / 1000.0), near.rows().get(1).km());
        // coming back along the trunk from its dock (westbound): the branch a sharp turn to the left (south), the way back ahead
        RoadGuide.Sign back = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.ADVANCE && s.host().equals("서해") && s.at() > 1100)
                .min(java.util.Comparator.comparingInt(RoadGuide.Sign::at)).orElseThrow();
        assertEquals(-90f, back.yaw(), 1e-3f, "facing the traffic coming back (east)");
        assertTrue(back.z() < 0, "on the right of the westbound traffic (north)");
        assertEquals(List.of(new RoadSign.Row(RoadSign.Arrow.LEFT, "남해 하역장", RoadSign.km((roads.get(1).points().size() - 1 + back.at() - 1100) / 1000.0)),
                new RoadSign.Row(RoadSign.Arrow.UP, HOME, RoadSign.km(back.at() / 1000.0))), back.rows());
        // the trunk's next own sign is 900 on: a confirmation a little past the fork
        RoadGuide.Sign confirm = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.CONFIRM && s.host().equals("서해")).findFirst().orElseThrow();
        assertEquals(1163, confirm.at(), "60 past the fork, clear of the light at 1160");
        assertEquals(new RoadSign.Words("서해 하역장", RoadSign.km((3000 - 1163) / 1000.0), "국도 1호선", "1"), confirm.words());
        // the branch's own start sign is near: no confirmation on it
        assertTrue(plan.signs().stream().noneMatch(s -> s.type() == RoadGuide.Type.CONFIRM && s.host().equals("남해")));
        // the way back on the branch: through the trunk to the origin
        RoadGuide.Sign ret = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.RETURN && s.host().equals("남해")).findFirst().orElseThrow();
        assertEquals(HOME, ret.words().big());
        assertEquals(RoadSign.km((ret.at() + 1100) / 1000.0), ret.words().km(), "back along the branch, then the trunk");
        postsClear(plan, roads);
    }

    @Test
    void aCrossingGetsSignsOnBothRoadsAndAnOverpassNone() {
        RoadJob north = saved("북해", 2, null, p(1500, -800), p(1500, 900));
        RoadJob high = saved("동해", 3, null, p(2400, -800), p(2400, 900));
        List<RoadGuide.Road> roads = new ArrayList<>(List.of(road(trunk(), HOME), road(north, "북부 출발 터미널"), road(high, "북부 출발 터미널")));
        int[] up = roads.get(2).halves().clone();
        java.util.Arrays.fill(up, up[0] + 2 * RoadNetwork.CLEARANCE);   // goes over the trunk
        roads.set(2, new RoadGuide.Road("동해", roads.get(2).points(), up, roads.get(2).parts(), 3, "북부 출발 터미널"));
        RoadGuide.Plan plan = RoadGuide.plan(roads, 1000, 40);
        assertEquals(1, plan.junctions().size(), "the overpass is no junction: " + plan.junctions());
        RoadGuide.Junction j = plan.junctions().getFirst();
        assertFalse(j.fork());
        // four ways in, each with an advance sign 40 before naming the three other ways: left, ahead, right
        List<RoadGuide.Sign> advance = plan.signs().stream().filter(s -> s.type() == RoadGuide.Type.ADVANCE).toList();
        assertEquals(8, advance.size(), advance.toString());
        for (RoadGuide.Sign s : advance) {
            assertEquals(List.of(RoadSign.Arrow.LEFT, RoadSign.Arrow.UP, RoadSign.Arrow.RIGHT), s.rows().stream().map(RoadSign.Row::arrow).toList(), s.toString());
        }
        // eastbound on the trunk: left is north (북해 back to its start), ahead 서해, right 북해's dock (south)
        RoadGuide.Sign east = advance.stream().filter(s -> s.host().equals("서해") && s.at() == 1460).findFirst().orElseThrow();
        assertEquals(List.of("북부 출발 터미널", "서해 하역장", "북해 하역장"), east.rows().stream().map(RoadSign.Row::name).toList());
        assertEquals(List.of(RoadSign.km(0.84), RoadSign.km(1.54), RoadSign.km(0.94)), east.rows().stream().map(RoadSign.Row::km).toList());
        postsClear(plan, roads);
    }

    @Test
    void aGuideSignsLinesFitItsPanelBesideTheirArrows() {
        assertEquals(RoadSign.Arrow.UP, RoadSign.Arrow.of(-20));
        assertEquals(RoadSign.Arrow.UP_LEFT, RoadSign.Arrow.of(-45));
        assertEquals(RoadSign.Arrow.UP_RIGHT, RoadSign.Arrow.of(60));
        assertEquals(RoadSign.Arrow.RIGHT, RoadSign.Arrow.of(135));
        assertEquals(RoadSign.Arrow.LEFT, RoadSign.Arrow.of(-90));
        assertTrue(RoadSign.GUIDE_LATERAL - RoadSign.GUIDE_WIDTH / 2 >= 2.5, "the panel never over the lanes");
        for (String name : List.of("서해 하역장", "본사 출발 터미널", "아주아주긴이름 하역장")) {
            for (int n = 1; n <= 3; n++) {
                List<RoadSign.Row> rows = new ArrayList<>();
                for (int k = 0; k < n; k++) rows.add(new RoadSign.Row(RoadSign.Arrow.values()[k], name, RoadSign.km(123.4)));
                List<RoadSign.Text> texts = RoadSign.guideTexts(rows);
                List<RoadSign.ArrowAt> arrows = RoadSign.guideArrows(rows);
                assertEquals(2 * n, texts.size());
                double arrowEdge = arrows.getFirst().right() + arrows.getFirst().size() / 2;
                for (RoadSign.Text t : texts) {
                    double half = RoadSign.width(t.text()) * t.scale() / 40 / 2, h = 10 * t.scale() / 40.0, mid = t.right() + t.scale() / 40.0;
                    assertTrue(Math.abs(mid) + half <= RoadSign.GUIDE_WIDTH / 2 - 1 / 16.0, t.text() + " sticks out sideways");
                    assertTrue(mid - half > arrowEdge, t.text() + " over its arrow");
                    assertTrue(t.up() >= RoadSign.PANEL_BOTTOM && t.up() + h <= RoadSign.GUIDE_TOP, t.text() + " sticks out up or down");
                }
                for (RoadSign.ArrowAt a : arrows) {
                    assertTrue(a.up() - a.size() / 2 >= RoadSign.PANEL_BOTTOM && a.up() + a.size() / 2 <= RoadSign.GUIDE_TOP);
                    assertTrue(a.right() - a.size() / 2 >= -RoadSign.GUIDE_WIDTH / 2);
                }
                // the lines one under another, never over each other
                for (int k = 1; k < n; k++) assertTrue(texts.get(2 * k - 2).up() - texts.get(2 * k).up() >= 10 * texts.getFirst().scale() / 40.0);
            }
        }
    }

    /** Signs in a made-up world: entities with their docks and sets, some in chunks not loaded (gone when they load if old). */
    static final class Board implements RoadGuide.Board {
        record Ent(String dock, int set, String also, int alsoSet, double x, double z, String what, boolean loaded) {}

        final Map<String, Integer> sets = new HashMap<>();
        final List<Ent> ents = new ArrayList<>();
        boolean loaded = true;

        public void clear(String dock) {
            sets.merge(dock, 1, Integer::sum);
            ents.removeIf(e -> e.loaded() && (dock.equals(e.dock()) || dock.equals(e.also())));
        }

        public void put(RoadGuide.Sign s) {
            ents.removeIf(e -> e.loaded() && Math.hypot(e.x() - s.x(), e.z() - s.z()) <= 2.5 && (e.dock().equals(s.host()) || e.dock().equals(s.also())));
            ents.add(new Ent(s.host(), sets.getOrDefault(s.host(), 0), s.also(), s.also() == null ? 0 : sets.getOrDefault(s.also(), 0), s.x(), s.z(),
                    s.type().name(), loaded = !loaded));   // every other one in a chunk not loaded
        }

        public void paint(RoadGuide.Paint p) {
            ents.add(new Ent(p.host(), sets.getOrDefault(p.host(), 0), null, 0, p.x(), p.z(), "paint", true));
        }

        /** Every chunk loads: the old ones go (RoadModule.onEntitiesLoad). */
        void loadAll() {
            ents.removeIf(e -> e.set() != sets.getOrDefault(e.dock(), 0) || (e.also() != null && e.alsoSet() != sets.getOrDefault(e.also(), 0)));
            ents.replaceAll(e -> new Ent(e.dock(), e.set(), e.also(), e.alsoSet(), e.x(), e.z(), e.what(), true));
        }
    }

    @Test
    void puttingTheSignsUpTwiceLeavesOneSet() {
        RoadJob south = saved("남해", 2, "서해", p(1100, 0), p(1400, 300), p(1400, 1500));
        List<RoadGuide.Road> roads = List.of(road(trunk(), HOME), road(south, HOME));
        RoadGuide.Plan plan = RoadGuide.plan(roads, 1000, 40);
        Board world = new Board();
        Set<String> all = Set.of("서해", "남해");
        int first = RoadGuide.retrofit(plan, all, world);
        world.loadAll();
        int after = world.ents.size();
        assertEquals(first + plan.paints().size(), after);
        int second = RoadGuide.retrofit(plan, all, world);
        assertEquals(first, second);
        world.loadAll();
        assertEquals(after, world.ents.size(), "no sign twice");
        Set<String> at = new HashSet<>();
        for (Board.Ent e : world.ents) assertTrue(at.add(e.what() + Math.round(e.x()) + "," + Math.round(e.z())), "two at " + e);
        // one dock's signs again: the other's own stay, those at their fork are put up again
        int fork = (int) plan.signs().stream().filter(s -> "남해".equals(s.also()) || s.host().equals("남해")).count();
        assertEquals(fork, RoadGuide.retrofit(plan, Set.of("남해"), world));
        world.loadAll();
        assertEquals(after, world.ents.size());
    }
}
