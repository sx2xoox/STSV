package dev.stsv.economy.bus;

import dev.stsv.economy.road.RoadNetwork;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "특정 정류장에서 손님이 하차를 안 한다": a stop on a diagonal road on a slope (slabs) had its bay paved flat at its middle's
 * height, a block or more proud of (or sunk below) the lane at its ends, so a bus could not pull in and never stood where
 * the stop is recognised. The bay now follows the lane column by column; a bus is recognised in the bay (from the kerb's
 * line, ± height), by a simple stop from either lane, and after standing still by any stop someone aboard is going to.
 */
class StopArrivalTest {

    static final int LENGTH = 24, WIDTH = 3;
    static final double REACH = 7;

    /** A road running diagonally (north-east) up a slope of one block in three (2 halves every 3 points), starting on a slab. */
    static RoadNetwork.Road diagonalSlope(int n) {
        List<double[]> pts = new ArrayList<>();
        int[] halves = new int[n];
        for (int i = 0; i < n; i++) {
            pts.add(new double[]{1000.5 + i * Math.sqrt(0.5), 2000.5 - i * Math.sqrt(0.5)});
            halves[i] = 129 + (2 * i) / 3;
        }
        return new RoadNetwork.Road("동항", pts, halves);
    }

    static BusStop stopAt(BusRoutes r, int node, int side, boolean bay) {
        double[] p = r.place(node);
        return new BusStop(1, "갈매기 쉼터", "world", p[0], p[1], (int) p[2], p[3], p[4], side, 4, true, bay, "동항");
    }

    @Test
    void aBayOnASlopeIsPavedLevelWithTheLane() {
        BusRoutes r = new BusRoutes(List.of(diagonalSlope(200)));
        int node = 100;
        for (int side : new int[]{1, -1}) {
            BusStop s = stopAt(r, node, side, true);
            BusStop.Levels lv = r.levels(node, LENGTH / 2 + 24, s.half());
            BusStop.Levels flat = BusStop.Levels.flat(s.half());
            int worstFlat = 0;
            Map<Long, Integer> level = new HashMap<>();
            List<BusStop.Column> cols = new ArrayList<>(s.bayColumns(LENGTH, WIDTH));
            for (BusStop.Column c : s.shoulderColumns(LENGTH)) cols.add(c);
            for (BusStop.Column c : cols) {
                int road = roadHalfBeside(r, s, c);
                int h = lv.half(c.x(), c.z());
                // level with the lane beside it (its nearest road point: what the road itself was paved at, ±a slab)
                assertTrue(Math.abs(h - road) <= 1, "bay column " + c + " at " + h + ", the lane beside at " + road);
                worstFlat = Math.max(worstFlat, Math.abs(flat.half(c.x(), c.z()) - road));
                level.put(key(c.x(), c.z()), h);
            }
            // as it was: paved flat at the middle's height, a block and more (2+ halves) proud or sunk at the ends
            assertTrue(worstFlat >= 4, "the old flat bay was only " + worstFlat + " halves off");
            // no full-block step between neighbouring columns of the bay and shoulder (a bus steps a slab smoothly)
            for (BusStop.Column c : cols) {
                int h = level.get(key(c.x(), c.z()));
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Integer o = level.get(key(c.x() + d[0], c.z() + d[1]));
                    if (o != null) assertTrue(Math.abs(o - h) <= 2, "a step of " + Math.abs(o - h) + " halves at " + c);
                }
            }
        }
    }

    /** The road's surface the lane beside a column has: the half of the road point nearest it (as RoadNetwork assigns columns). */
    static int roadHalfBeside(BusRoutes r, BusStop s, BusStop.Column c) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int k = 0; k < r.nodes(); k++) {
            double[] xz = r.xz(k);
            double d = Math.hypot(xz[0] - (c.x() + 0.5), xz[1] - (c.z() + 0.5));
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        return r.half(best);
    }

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @Test
    void aBusInADiagonalSlopedBayIsAtTheStop() {
        BusRoutes r = new BusRoutes(List.of(diagonalSlope(200)));
        int node = 100;
        BusStop s = stopAt(r, node, 1, true);
        BusStop.Levels lv = r.levels(node, LENGTH / 2 + 24, s.half());
        for (double along = -REACH; along <= REACH; along += 0.5) {
            for (double out : new double[]{3.2, 5.0, 7.0, 8.4}) {   // half in (from the kerb's line) to deep in the bay
                double[] p = s.at(along, out);
                int h = lv.half((int) Math.floor(p[0]), (int) Math.floor(p[1]));
                double y = h / 2.0 + 1;   // the bus rests on the bay's surface there
                assertTrue(s.standing(p[0], y, p[1], REACH, WIDTH, BusModule.STOP_DY), "along " + along + " out " + out + " y " + y);
            }
        }
        // in the lane (not the bay) it is not, nor on a road crossing over it
        double[] lane = s.at(0, 1.5);
        assertFalse(s.standing(lane[0], s.top(), lane[1], REACH, WIDTH, BusModule.STOP_DY));
        double[] bay = s.at(0, 7);
        assertFalse(s.standing(bay[0], s.top() + 8, bay[1], REACH, WIDTH, BusModule.STOP_DY));
    }

    @Test
    void aSimpleStopServesBothLanes() {
        BusStop simple = new BusStop(4, "x", "world", 100.5, 50.5, 128, 1, 0, 1, 4, false, false, "동항");
        assertTrue(simple.standing(102, 52.5, REACH, WIDTH));   // its own side's lane
        assertTrue(simple.standing(102, 48.5, REACH, WIDTH));   // the other lane: a bus coming the other way (it was not)
        assertFalse(simple.standing(102, 40, REACH, WIDTH));
        assertFalse(simple.standing(112, 52.5, REACH, WIDTH));
    }

    static BusModule.Rider rider(int dest) {
        return new BusModule.Rider(null, null, 9, dest, 100, 70);
    }

    @Test
    void ofTwoStopsTogetherTheOneSomeoneIsGoingTo() {
        Map<Integer, BusStop> all = new LinkedHashMap<>();
        // a stop and a simple one close by on the same side (both cover where the bus stands)
        all.put(1, new BusStop(1, "a", "world", 100.5, 50.5, 128, 1, 0, 1, 4, false, true, ""));
        all.put(2, new BusStop(2, "b", "world", 105.5, 50.5, 128, 1, 0, 1, 4, false, false, ""));
        double x = 102.5, y = 65, z = 54.5;
        assertEquals(1, BusModule.stopAt(all.values(), List.of(), 0, "world", x, y, z, REACH, WIDTH).id());   // nearer along
        assertEquals(2, BusModule.stopAt(all.values(), List.of(rider(2)), 0, "world", x, y, z, REACH, WIDTH).id());
        assertNull(BusModule.stopAt(all.values(), List.of(rider(2)), 0, "nether", x, y, z, REACH, WIDTH));
    }

    @Test
    void standingStillBesideAStopSomeoneIsGoingToLetsThemOff() {
        Map<Integer, BusStop> all = new LinkedHashMap<>();
        BusStop s = new BusStop(7, "a", "world", 100.5, 50.5, 128, 1, 0, 1, 4, false, true, "");
        all.put(7, s);
        double[] lane = s.at(9, 1.5);   // in the lane, a little past the bay's middle: not in the bay
        double y = s.top();
        assertNull(BusModule.stopAt(all.values(), List.of(rider(7)), 0, "world", lane[0], y, lane[1], REACH, WIDTH));
        assertNull(BusModule.stopAt(all.values(), List.of(rider(7)), BusModule.STILL_TICKS - 5, "world", lane[0], y, lane[1], REACH, WIDTH));
        assertEquals(7, BusModule.stopAt(all.values(), List.of(rider(7)), BusModule.STILL_TICKS, "world", lane[0], y, lane[1], REACH, WIDTH).id());
        // nobody going there, or the other side of the road a way off: not
        assertNull(BusModule.stopAt(all.values(), List.of(rider(3)), 200, "world", lane[0], y, lane[1], REACH, WIDTH));
        double[] far = s.at(30, 1.5);
        assertNull(BusModule.stopAt(all.values(), List.of(rider(7)), 200, "world", far[0], y, far[1], REACH, WIDTH));
        // the 선글라스 남자 is not counted
        BusModule.Rider agent = rider(7);
        agent.secret = true;
        assertNull(BusModule.stopAt(all.values(), List.of(agent), 200, "world", lane[0], y, lane[1], REACH, WIDTH));
    }

    static BusLine line(int number, String dock, int... stops) {
        double[] xs = new double[100], zs = new double[100];
        for (int i = 0; i < 100; i++) xs[i] = i;
        BusLine l = new BusLine(number, dock, xs, zs, new boolean[100]);
        for (int i = 0; i < stops.length; i++) l.addStop(stops[i], 10 + 20 * i);
        return l;
    }

    @Test
    void aPassengerGoingNowhereGetsOffAtTheNextStop() {
        Map<Integer, BusStop> stops = new HashMap<>();
        for (int id : new int[]{1, 2, 3}) stops.put(id, new BusStop(id, "s" + id, "world", 0, 0, 0, 1, 0, 1, 4, false, true, ""));
        BusLine l = line(1, "동항", 1, 2);
        BusModule.Rider ok = rider(2), deleted = rider(9), offLine = rider(3), agent = rider(9), next = rider(-1);
        agent.secret = true;
        deleted.sleepThrough = true;
        List<BusModule.Rider> riders = List.of(ok, deleted, offLine, agent, next);
        assertEquals(2, BusModule.lost(stops, riders, l));
        assertEquals(2, ok.dest);
        assertEquals(-1, deleted.dest);
        assertFalse(deleted.sleepThrough);
        assertEquals(-1, offLine.dest);
        assertEquals(9, agent.dest);   // his own rules
        assertEquals(-1, next.dest);
        // with no line (between lines): only a stop that is gone counts
        BusModule.Rider r3 = rider(3);
        assertEquals(0, BusModule.lost(stops, List.of(r3), null));
        assertEquals(3, r3.dest);
    }

    @Test
    void theOverlayListsWhoGetsOffSoonestFirst() {
        // a 16-seat bus's riders (two columns of the overlay read down the first, then the second): next stop first,
        // then stop by stop along the way it runs, a stop off the line after them, the 선글라스 남자 last
        BusLine l = line(1, "동항", 1, 2, 3, 4);
        int[][] riders = {{3, 0}, {9, 1}, {-1, 0}, {1, 0}, {4, 0}, {7, 0}, {2, 0}};
        java.util.function.IntFunction<List<Integer>> order = dir -> java.util.Arrays.stream(riders)
                .sorted(java.util.Comparator.comparingInt(x -> BusModule.offOrder(x[0], x[1] == 1, dir, l)))
                .map(x -> x[0]).toList();
        assertEquals(List.of(-1, 1, 2, 3, 4, 7, 9), order.apply(1));
        assertEquals(List.of(-1, 4, 3, 2, 1, 7, 9), order.apply(-1), "the next stop first whichever way it runs");
    }

    @Test
    void aStopByWhereABranchLeavesIsOnBothLines() {
        RoadNetwork.Road trunk = BusTest.straight("동항", 0.5, 0.5, 1, 0, 3000);
        RoadNetwork.Road branch = BusTest.straight("북항", 1500.5, -1.5, 0, -1, 1000);
        BusRoutes r = new BusRoutes(List.of(trunk, branch));
        BusStop s = new BusStop(5, "갈림길", "world", 1500.5, -1.5, 0, 0, -1, 1, 4, false, true, "북항");
        int node = r.attach(s.x(), s.z());
        for (BusRoutes.Line l : r.lines()) {
            Map<Integer, Integer> at = new HashMap<>();
            double[] xs = new double[l.path().length], zs = new double[l.path().length];
            for (int k = 0; k < l.path().length; k++) {
                at.put(l.path()[k], k);
                xs[k] = r.xz(l.path()[k])[0];
                zs[k] = r.xz(l.path()[k])[1];
            }
            BusLine bl = new BusLine(1, l.dock(), xs, zs, new boolean[xs.length]);
            assertNotNull(BusModule.onLine(at, node, bl, s), "not on " + l.dock() + "'s line");
        }
        // far from a line's way: not on it
        double[] xs = {0, 100}, zs = {500, 500};
        assertNull(BusModule.onLine(Map.of(), node, new BusLine(9, "x", xs, zs, new boolean[2]), s));
    }

    @Test
    void linesNumberedAgainKeepTheBusOnItsLine() {
        BusLine a = line(1, "동항", 1, 2), b = line(2, "북항", 3, 4);
        BusModule.Net before = new BusModule.Net(null, Map.of(), new double[0][0], List.of(a, b));
        // a new stop put a road's line in front: the 북항 line is number 3 now
        BusLine c = line(1, "서항", 5, 6), a2 = line(2, "동항", 1, 2, 7), b2 = line(3, "북항", 3, 4);
        BusModule.Net now = new BusModule.Net(null, Map.of(), new double[0][0], List.of(c, a2, b2));
        assertEquals(3, BusModule.renumber(before, now, 2));
        assertEquals(2, BusModule.renumber(before, now, 1));
        assertEquals(0, BusModule.renumber(before, now, 0));
    }

    @Test
    void atAStopOfTwoLinesTheOneThePassengersAreGoingOn() {
        BusLine a = line(1, "동항", 1, 2), b = line(2, "북항", 1, 3);
        assertEquals(2, BusModule.mostGoing(List.of(a, b), List.of(rider(3), rider(3), rider(2))).number);
        assertEquals(1, BusModule.mostGoing(List.of(a, b), List.of()).number);
    }

    @Test
    void whatAPassengerPaysForWhenGettingOffEarly() {
        BusLine l = line(1, "동항", 1, 2, 3);
        assertEquals(40, BusModule.rode(null, l, 1, 3, 999), 1e-9);
        // not both on the line: the roads' shortest way, else what they were to ride
        BusModule.Net n = new BusModule.Net(null, Map.of(1, 0, 9, 1), new double[][]{{0, 77}, {77, 0}}, List.of(l));
        assertEquals(77, BusModule.rode(n, l, 1, 9, 999), 1e-9);
        assertEquals(999, BusModule.rode(n, l, 1, 8, 999), 1e-9);
    }
}
