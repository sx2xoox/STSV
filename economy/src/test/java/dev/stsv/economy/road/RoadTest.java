package dev.stsv.economy.road;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 국도: the A* (water, steepness and players' land avoided), the straightening, the grade, bridges and tunnels, the saved job. */
class RoadTest {

    /** A grid of cells: flat ground at 64 unless set. */
    static final class Grid implements RoadPlanner.Field {
        final int w, h;
        final Map<Long, double[]> cells = new HashMap<>();   // height, water, rough, blocked
        Grid(int w, int h) {
            this.w = w;
            this.h = h;
        }

        double[] at(int x, int z) {
            return cells.computeIfAbsent(RoadJob.key(x, z), k -> new double[]{64, 0, 0, 0});
        }

        public boolean known(int x, int z) {
            return x >= 0 && z >= 0 && x < w && z < h;
        }

        public double height(int x, int z) {
            return at(x, z)[0];
        }

        public double water(int x, int z) {
            return at(x, z)[1];
        }

        public double rough(int x, int z) {
            return at(x, z)[2];
        }

        public boolean blocked(int x, int z) {
            return at(x, z)[3] == 1;
        }
    }

    static boolean visits(List<int[]> path, int x, int z) {
        return path.stream().anyMatch(c -> c[0] == x && c[1] == z);
    }

    @Test
    void onFlatGroundTheRouteIsStraight() {
        Grid g = new Grid(40, 9);
        List<int[]> p = RoadPlanner.path(g, 0, 4, 39, 4, RoadPlanner.Weights.DEFAULT, 100_000);
        assertEquals(40, p.size());
        for (int[] c : p) assertEquals(4, c[1]);
        List<int[]> s = RoadPlanner.smooth(g, p, RoadPlanner.Weights.DEFAULT);
        assertEquals(2, s.size(), "one straight line: only its ends");
    }

    @Test
    void itGoesRoundALakeWhenRoundIsCheaperThanABridge() {
        Grid g = new Grid(40, 21);
        for (int x = 15; x <= 24; x++) for (int z = 6; z <= 14; z++) g.at(x, z)[1] = 5;   // a lake across the straight line
        List<int[]> p = RoadPlanner.path(g, 0, 10, 39, 10, RoadPlanner.Weights.DEFAULT, 100_000);
        assertFalse(p.isEmpty());
        for (int[] c : p) assertEquals(0, g.water(c[0], c[1]), "dry all the way: " + c[0] + "," + c[1]);
        // a sea too wide to go round: it bridges it, by the narrowest way
        Grid sea = new Grid(40, 21);
        for (int x = 15; x <= 17; x++) for (int z = 0; z < 21; z++) sea.at(x, z)[1] = 4;
        List<int[]> q = RoadPlanner.path(sea, 0, 10, 39, 10, RoadPlanner.Weights.DEFAULT, 100_000);
        assertFalse(q.isEmpty());
        long wet = q.stream().filter(c -> sea.water(c[0], c[1]) > 0).count();
        assertEquals(3, wet, "straight across, three cells of bridge");
    }

    @Test
    void itAvoidsSteepGroundAndClimbsGently() {
        Grid g = new Grid(40, 21);
        // a steep ridge across the line (40 blocks up in a cell) with a low pass at z = 2
        for (int z = 0; z < 21; z++) if (z != 2) g.at(20, z)[0] = 104;
        List<int[]> p = RoadPlanner.path(g, 0, 10, 39, 10, RoadPlanner.Weights.DEFAULT, 100_000);
        assertTrue(visits(p, 20, 2), "through the pass");
        // a gentle slope is fine straight up
        Grid hill = new Grid(40, 9);
        for (int x = 0; x < 40; x++) for (int z = 0; z < 9; z++) hill.at(x, z)[0] = 64 + x * 4;   // 1 in 4
        List<int[]> h = RoadPlanner.path(hill, 0, 4, 39, 4, RoadPlanner.Weights.DEFAULT, 100_000);
        assertEquals(40, h.size());
    }

    @Test
    void playersLandIsNeverEnteredNorCutAtACorner() {
        Grid g = new Grid(30, 21);
        for (int z = 0; z < 20; z++) g.at(15, z)[3] = 1;   // a wall of claims with one gap at z = 20
        List<int[]> p = RoadPlanner.path(g, 0, 5, 29, 5, RoadPlanner.Weights.DEFAULT, 100_000);
        assertFalse(p.isEmpty());
        for (int[] c : p) assertFalse(g.blocked(c[0], c[1]));
        assertTrue(visits(p, 15, 20));
        for (int i = 1; i < p.size(); i++) {
            int[] a = p.get(i - 1), b = p.get(i);
            if (a[0] != b[0] && a[1] != b[1]) assertFalse(g.blocked(a[0], b[1]) || g.blocked(b[0], a[1]), "a corner past a claim");
        }
        // the straightened road crosses no claim either (every cell its line touches)
        List<int[]> s = RoadPlanner.smooth(g, p, RoadPlanner.Weights.DEFAULT);
        for (int i = 1; i < s.size(); i++)
            for (int[] c : RoadPlanner.line(s.get(i - 1)[0], s.get(i - 1)[1], s.get(i)[0], s.get(i)[1])) assertFalse(g.blocked(c[0], c[1]));
        // closed off completely: no road
        g.at(15, 20)[3] = 1;
        assertTrue(RoadPlanner.path(g, 0, 5, 29, 5, RoadPlanner.Weights.DEFAULT, 100_000).isEmpty());
    }

    @Test
    void theLineTouchesEveryCellItCrosses() {
        List<int[]> l = RoadPlanner.line(0, 0, 3, 1);
        assertEquals(0, l.getFirst()[0]);
        assertEquals(3, l.getLast()[0]);
        for (int i = 1; i < l.size(); i++) assertTrue(Math.max(Math.abs(l.get(i)[0] - l.get(i - 1)[0]), Math.abs(l.get(i)[1] - l.get(i - 1)[1])) <= 1, "no gaps");
        List<int[]> d = RoadPlanner.line(0, 0, 2, 2);
        assertTrue(d.size() >= 5, "a diagonal takes the cells beside each corner");
        List<double[]> pts = RoadPlanner.densify(List.of(new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 5}), 1);
        assertEquals(16, pts.size());
    }

    // ------------------------------------------------------------------------------------------
    // Grade, bridges, tunnels
    // ------------------------------------------------------------------------------------------

    @Test
    void theGradeIsNeverSteeperThanOneInThree() {
        int n = 400;
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        java.util.Random r = new java.util.Random(7);
        int g = 70;
        for (int i = 0; i < n; i++) {
            g += r.nextInt(5) - 2;   // rough hills, up to 2 a block
            if (i > 150 && i < 170) g -= 3;   // a cliff
            ground[i] = g;
        }
        int[] road = RoadProfile.plan(ground, water, surface);
        assertTrue(RoadProfile.closestSteps(road) >= 3, "one-block steps at least three apart");
        for (int i = 1; i < n; i++) assertTrue(Math.abs(road[i] - road[i - 1]) <= 1);
        double[] smooth = RoadProfile.grade(RoadProfile.smooth(new double[]{0, 9, 0, 9, 0, 30, 30, 0}, 1), RoadProfile.MAX_GRADE);
        assertTrue(RoadProfile.steepest(smooth) <= RoadProfile.MAX_GRADE + 1e-9);
        // flat ground: the road lies on it
        int[] flat = new int[50];
        java.util.Arrays.fill(flat, 64);
        int[] on = RoadProfile.plan(flat, new boolean[50], new int[50]);
        for (int y : on) assertEquals(64, y);
    }

    @Test
    void ravinesAndWaterAreBridgedAndSteepHillsTunnelled() {
        int n = 120;
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        java.util.Arrays.fill(ground, 64);
        for (int i = 30; i < 40; i++) ground[i] = 40;          // a ravine 10 wide, 24 deep
        for (int i = 60; i < 75; i++) ground[i] = 90;          // a steep hill 15 wide
        for (int i = 95; i < 105; i++) {                      // a river
            water[i] = true;
            ground[i] = 58;
            surface[i] = 62;
        }
        ground[50] = 66;                                      // a bump: just cut
        int[] road = RoadProfile.plan(ground, water, surface);
        RoadProfile.Part[] parts = RoadProfile.parts(road, ground, water, 3, 5, 4);
        assertEquals(RoadProfile.Part.BRIDGE, parts[35], "the ravine");
        assertEquals(RoadProfile.Part.TUNNEL, parts[67], "the hill");
        assertEquals(RoadProfile.Part.BRIDGE, parts[100], "the river");
        assertEquals(RoadProfile.Part.ROAD, parts[50], "a bump is cut, not tunnelled");
        assertEquals(RoadProfile.Part.ROAD, parts[10]);
        assertTrue(road[100] >= surface[100] + 1, "the deck over the water");
        // a dip of two blocks for two points is filled, not bridged
        int[] g2 = new int[30];
        java.util.Arrays.fill(g2, 64);
        g2[15] = 58;
        g2[16] = 58;
        RoadProfile.Part[] p2 = RoadProfile.parts(RoadProfile.plan(g2, new boolean[30], new int[30]), g2, new boolean[30], 3, 5, 4);
        assertEquals(RoadProfile.Part.ROAD, p2[15]);
    }

    // ------------------------------------------------------------------------------------------
    // The job survives a restart
    // ------------------------------------------------------------------------------------------

    @Test
    void theJobIsSavedAndReadBack() {
        RoadJob j = new RoadJob();
        j.dock = "서해";
        j.world = "world";
        j.startX = 0.5;
        j.startZ = -12.5;
        j.endX = 7000.5;
        j.endZ = 33.5;
        j.phase = RoadJob.Phase.BUILD;
        j.running = true;
        j.cursor = 1234;
        j.started = 99;
        j.cells.put(RoadJob.key(-3, 7), new RoadJob.Cell(70, 0, 4, false));
        j.cells.put(RoadJob.key(437, -1), new RoadJob.Cell(58, 6, 1, true));
        j.corners.add(new double[]{0.5, -12.5});
        j.corners.add(new double[]{7000.5, 33.5});
        RoadJob.Ground a = new RoadJob.Ground();
        a.ground = 70;
        RoadJob.Ground b = new RoadJob.Ground();
        b.ground = 55;
        b.water = true;
        b.surface = 62;
        j.ground.add(a);
        j.ground.add(b);
        j.ground.add(new RoadJob.Ground());
        RoadJob r = RoadJob.read(j.write());
        assertEquals("서해", r.dock);
        assertEquals(RoadJob.Phase.BUILD, r.phase);
        assertTrue(r.running);
        assertEquals(1234, r.cursor);
        assertEquals(99, r.started);
        assertEquals(7000.5, r.endX, 1e-9);
        assertEquals(-12.5, r.startZ, 1e-9);
        assertEquals(new RoadJob.Cell(58, 6, 1, true), r.cells.get(RoadJob.key(437, -1)));
        assertEquals(2, r.cells.size());
        assertEquals(2, r.corners.size());
        assertEquals(3, r.ground.size());
        assertEquals(55, r.ground.get(1).ground);
        assertTrue(r.ground.get(1).water);
        assertEquals(62, r.ground.get(1).surface);
        assertFalse(r.ground.get(2).read(), "not read yet stays so");
        assertEquals(-3, RoadJob.cx(RoadJob.key(-3, 7)));
        assertEquals(7, RoadJob.cz(RoadJob.key(-3, 7)));
        assertEquals(r.write(), RoadJob.read(r.write()).write(), "stable");
        assertTrue(r.field().blocked(437, -1) && r.field().known(-3, 7) && !r.field().known(0, 0));
    }

    @Test
    void theCorridorFollowsTheStraightLine() {
        Set<Long> c = RoadModule.corridor(0, 0, 7000, 0, 6);
        assertTrue(c.contains(RoadJob.key(0, 6)) && c.contains(RoadJob.key(437, -6)));
        assertFalse(c.contains(RoadJob.key(200, 7)));
        assertEquals(13 * (437 + 1 + 12), c.size(), "13 chunks wide, from 6 before the start to 6 past the end");
    }
}
