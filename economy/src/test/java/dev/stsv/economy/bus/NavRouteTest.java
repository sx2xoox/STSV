package dev.stsv.economy.bus;

import dev.stsv.economy.road.RoadNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.stsv.economy.bus.BusTest.straight;
import static org.junit.jupiter.api.Assertions.*;

/** 버스 내비게이션: the route over the road points, its turns, how far off it a bus is, 손님 순서대로. */
class NavRouteTest {

    /** A trunk east 3000 blocks and a branch north from its middle 1000 blocks. */
    private static BusRoutes tee() {
        RoadNetwork.Road trunk = straight("동항", 0.5, 0.5, 1, 0, 3000);
        RoadNetwork.Road branch = straight("북항", 1500.5, -2.5, 0, -1, 1000);
        return new BusRoutes(List.of(trunk, branch));
    }

    private static NavRoute route(BusRoutes r, double fx, double fz, double tx, double tz) {
        int from = r.attach(fx, fz), to = r.attach(tx, tz);
        assertTrue(from >= 0 && to >= 0);
        return NavRoute.of(r, r.tree(from).path(to));
    }

    @Test
    void theRouteRunsAlongTheRoadPointsThroughTheJunction() {
        BusRoutes r = tee();
        NavRoute nav = route(r, 100.5, 8, 1504, -900);
        // the same length as the fare's road distance
        int a = r.attach(100.5, 8), c = r.attach(1504, -900);
        assertEquals(r.distances(a)[c], nav.length(), 1e-6);
        assertEquals(1400 + 897, nav.length(), 6);
        // from the bus's road point to the stop's, one road point after another (a junction's join is a short hop)
        assertEquals(100.5, nav.point(0)[0], 1e-9);
        assertEquals(-899.5, nav.point(nav.size() - 1)[1], 1e-9);
        for (int i = 1; i < nav.size(); i++) {
            double[] p = nav.point(i - 1), q = nav.point(i);
            assertTrue(Math.hypot(p[0] - q[0], p[1] - q[1]) <= BusRoutes.JOIN + 1e-9, "a gap at " + i);
        }
        // it goes up the branch, not on along the trunk
        for (int i = 0; i < nav.size(); i++) assertTrue(nav.point(i)[0] <= 1501, "past the junction at " + i);
        // no way: empty
        BusRoutes apart = new BusRoutes(List.of(straight("a", 0.5, 0.5, 1, 0, 500), straight("b", 0.5, 400.5, 1, 0, 500)));
        assertEquals(0, apart.tree(apart.attach(10, 0)).path(apart.attach(10, 400)).length);
        // already there: one point
        int k = r.attach(10, 0);
        assertArrayEquals(new int[]{k}, r.tree(k).path(k));
    }

    @Test
    void theTurnAtAJunctionIsFound() {
        BusRoutes r = tee();
        // east along the trunk, then north up the branch: a left turn ~1400 ahead, once
        NavRoute up = route(r, 100.5, 0.5, 1500, -900);
        List<NavRoute.Turn> turns = up.turns();
        assertEquals(1, turns.size(), turns.toString());
        assertEquals(-1, turns.getFirst().turn());
        NavRoute.Ahead m = up.nextManoeuvre(0, 2000);
        assertNotNull(m);
        assertEquals(1400, m.distance(), 5);
        assertNull(up.nextManoeuvre(0, 300), "not within 300 blocks yet");
        int near = up.nearest(1400, 0, -1, null);
        assertEquals(100, up.nextManoeuvre(near, 300).distance(), 5);
        // past it: nothing more
        int past = up.nearest(1500, -100, -1, null);
        assertNull(up.nextTurn(past, 5000));
        // coming down the branch and turning west: right
        NavRoute down = route(r, 1500, -900, 100, 0);
        assertEquals(1, down.nextManoeuvre(0, 5000).turn().turn());
        // on along the trunk past the junction: straight on (shown), not a manoeuvre (no voice line)
        NavRoute on = route(r, 100.5, 0.5, 2900.5, 0.5);
        NavRoute.Ahead s = on.nextTurn(0, 5000);
        assertNotNull(s);
        assertEquals(0, s.turn().turn());
        assertNull(on.nextManoeuvre(0, 5000));
    }

    @Test
    void howFarOffTheRouteABusIs() {
        NavRoute nav = route(tee(), 100.5, 0.5, 2900.5, 0.5);
        double[] off = new double[1];
        int i = nav.nearest(700.5, 30.5, -1, off);
        assertEquals(30, off[0], 0.6);
        assertEquals(700.5, nav.point(i)[0], 1e-9);
        // between two points: the distance to the segment, not to a point
        nav.nearest(700, 12, i, off);
        assertEquals(11.5, off[0], 1e-6);
        // a window around the last place; a jump is still found
        int j = nav.nearest(2500.5, 0.5, i, off);
        assertEquals(2500.5, nav.point(j)[0], 1e-9);
        assertEquals(0, off[0], 1e-6);
        // the road ahead
        assertEquals(24, nav.point(nav.indexAhead(j, 24))[0] - nav.point(j)[0], 1e-9);
        assertEquals(nav.size() - 1, nav.indexAhead(j, 1e9));
        assertEquals(nav.length() - 2400, nav.remaining(j), 1e-6);
    }

    @Test
    void passengerOrderChainsTheNearestDropOffs() {
        // stops on a line at x = 100 (id 1), 400 (id 2), 900 (id 3); the bus at x = 500
        Map<Integer, Double> x = Map.of(1, 100.0, 2, 400.0, 3, 900.0, 4, 10_000.0);
        double bus = 500;
        List<Integer> order = NavRoute.chain(List.of(3, 1, 2, 2), s -> Math.abs(x.get(s) - bus), (a, b) -> Math.abs(x.get(a) - x.get(b)));
        assertEquals(List.of(2, 1, 3), order);
        // the nearest drop-off, not the stop the bus stands at, none with no way
        assertEquals(2, NavRoute.nearestDrop(Set.of(1, 2, 3), s -> Math.abs(x.get(s) - bus), null));
        assertEquals(3, NavRoute.nearestDrop(Set.of(2, 3), s -> Math.abs(x.get(s) - bus), 2));
        assertNull(NavRoute.nearestDrop(Set.of(4), s -> Double.POSITIVE_INFINITY, null));
        assertNull(NavRoute.nearestDrop(List.of(), s -> 0, null));
        // one with no way is left out of the chain
        assertEquals(List.of(2, 3), NavRoute.chain(List.of(2, 3, 4), s -> s == 4 ? Double.POSITIVE_INFINITY : Math.abs(x.get(s) - bus),
                (a, b) -> a == 4 || b == 4 ? Double.POSITIVE_INFINITY : Math.abs(x.get(a) - x.get(b))));
    }

    @Test
    void theArrowPointsWhereTheRouteGoes() {
        // facing south (yaw 0): south ahead, west to the right, east to the left
        assertEquals("↑", NavRoute.arrow(0, 0, 0f, 0, 10));
        assertEquals("→", NavRoute.arrow(0, 0, 0f, -10, 0));
        assertEquals("←", NavRoute.arrow(0, 0, 0f, 10, 0));
        assertEquals("↓", NavRoute.arrow(0, 0, 0f, 0, -10));
    }
}
