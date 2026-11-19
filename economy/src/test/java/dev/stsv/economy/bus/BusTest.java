package dev.stsv.economy.bus;

import dev.stsv.economy.road.RoadNetwork;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusTest {

    static BusSpec spec() {
        List<String> problems = new ArrayList<>();
        BusSpec s = BusSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml")), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    @Test
    void fareFollowsThePlan() {
        BusSpec s = spec();
        BusSpec.Type resident = s.types().get("resident");
        assertEquals(220, s.fare(300, resident), 1e-9);
        assertEquals(420, s.fare(800, resident), 1e-9);
        assertEquals(2900, s.fare(7000, resident), 1e-9);
        assertEquals(420 * 3, s.fare(800, s.types().get("vip")), 1e-9);
        assertEquals(1.2, s.moodFactor(95));
        assertEquals(1.0, s.moodFactor(70));
        assertEquals(0.8, s.moodFactor(50));
        assertEquals(0.5, s.moodFactor(10));
        assertEquals(800 / 6.0 + 30, s.onTime(800), 1e-9);
    }

    @Test
    void dailyCurveBands() {
        BusSpec s = spec();
        assertEquals(1000, s.daily(0, 1000), 1e-9);
        assertEquals(500, s.daily(100_000, 1000), 1e-9);
        assertEquals(200, s.daily(250_000, 1000), 1e-9);
        // across the 10만 line: 500 at full, the rest at half
        assertEquals(500 + 250, s.daily(99_500, 1000), 1e-9);
    }

    @Test
    void hoursAndTypes() {
        BusSpec s = spec();
        assertEquals(2.0, s.hourFactor(8));
        assertEquals(2.0, s.hourFactor(18));
        assertEquals(0.5, s.hourFactor(3));
        assertEquals(1.0, s.hourFactor(13));
        assertTrue(s.rush(7));
        assertEquals(6, s.types().size());
        double w = s.types().values().stream().mapToDouble(BusSpec.Type::weight).sum();
        assertEquals(100, w, 1e-9);
        assertTrue(s.vehicles().contains("stsv:village_bus"));
    }

    static RoadNetwork.Road straight(String dock, double x0, double z0, double dx, double dz, int n) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) pts.add(new double[]{x0 + dx * i, z0 + dz * i});
        return new RoadNetwork.Road(dock, pts, new int[n]);
    }

    @Test
    void roadDistanceGoesAlongTheRoadsAndThroughABranch() {
        // a trunk east 3000 blocks, a branch north from its middle 1000 blocks
        RoadNetwork.Road trunk = straight("동항", 0.5, 0.5, 1, 0, 3000);
        RoadNetwork.Road branch = straight("북항", 1500.5, -2.5, 0, -1, 1000);
        BusRoutes r = new BusRoutes(List.of(trunk, branch));
        int a = r.attach(100, 8), b = r.attach(2900, -5), c = r.attach(1504, -900);
        assertTrue(a >= 0 && b >= 0 && c >= 0);
        double[][] m = r.matrix(new int[]{a, b, c});
        assertEquals(2800, m[0][1], 3);
        assertEquals(1400 + 897, m[0][2], 6);   // to the branch point, then up it
        assertEquals(m[0][2], m[2][0], 1e-6);
        // no road near: not attached, no way
        assertEquals(-1, r.attach(5000, 5000));
        double[][] m2 = r.matrix(new int[]{a, -1});
        assertTrue(Double.isInfinite(m2[0][1]));
    }

    @Test
    void separateRoadsAreNotConnected() {
        BusRoutes r = new BusRoutes(List.of(straight("a", 0.5, 0.5, 1, 0, 500), straight("b", 0.5, 400.5, 1, 0, 500)));
        double[][] m = r.matrix(new int[]{r.attach(10, 0), r.attach(10, 400)});
        assertTrue(Double.isInfinite(m[0][1]));
    }

    @Test
    void autoSpotsAlongARoad() {
        BusSpec s = spec();
        BusRoutes r = new BusRoutes(List.of(straight("동항", 0.5, 0.5, 1, 0, 7000)));
        List<BusRoutes.Spot> spots = r.autoSpots(s.stops().auto(), s.stops().bayLength(), List.of());
        assertEquals(BusRoutes.Kind.START, spots.getFirst().kind());
        assertEquals(BusRoutes.Kind.END, spots.getLast().kind());
        assertTrue(spots.size() >= 4 && spots.size() <= 6, "" + spots.size());
        double end = r.place(spots.getLast().node())[0];
        assertTrue(end >= 6999 - 60 && end <= 6999 - 40, "" + end);
        // an existing stop nearby keeps the start spot away
        List<BusRoutes.Spot> again = r.autoSpots(s.stops().auto(), s.stops().bayLength(), List.of(new double[]{32, 0}));
        assertTrue(Math.abs(r.place(again.getFirst().node())[0] - 32) >= 150 || again.getFirst().kind() != BusRoutes.Kind.START);
    }

    @Test
    void autoSpotsSkipABranchAndAHill() {
        BusSpec s = spec();
        RoadNetwork.Road trunk = straight("동항", 0.5, 0.5, 1, 0, 5000);
        RoadNetwork.Road branch = straight("북항", 2032.5, -2.5, 0, -1, 3000);
        BusRoutes r = new BusRoutes(List.of(trunk, branch));
        for (BusRoutes.Spot sp : r.autoSpots(s.stops().auto(), s.stops().bayLength(), List.of())) {
            double[] p = r.place(sp.node());
            assertTrue(Math.hypot(p[0] - 2032, p[1]) >= 150, "too near the junction: " + p[0] + "," + p[1]);
        }
        // a road climbing a block every 4: never level along a bay
        int[] halves = new int[3000];
        for (int i = 0; i < halves.length; i++) halves[i] = i / 2;
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 3000; i++) pts.add(new double[]{i + 0.5, 0.5});
        BusRoutes hill = new BusRoutes(List.of(new RoadNetwork.Road("x", pts, halves)));
        assertTrue(hill.autoSpots(s.stops().auto(), s.stops().bayLength(), List.of()).isEmpty());
    }

    @Test
    void bayShape() {
        BusSpec s = spec();
        // a road running east, the stop on its right (south, +z)
        BusStop stop = new BusStop(1, "시장 앞", "world", 100.5, 50.5, 128, 1, 0, 1, 4, false, true, "동항");
        double[] side = stop.at(0, 6);
        assertEquals(100.5, side[0], 1e-9);
        assertEquals(56.5, side[1], 1e-9);
        double[] l = stop.local(103, 58);
        assertEquals(2.5, l[0], 1e-9);
        assertEquals(7.5, l[1], 1e-9);
        List<BusStop.Column> cols = stop.bayColumns(s.stops().bayLength(), s.stops().bayWidth());
        for (BusStop.Column c : cols) {
            assertTrue(c.z() >= 56 && c.z() <= 59, "out of the bay: " + c);
            assertTrue(c.x() >= 88 && c.x() <= 113, "beyond its length: " + c);
        }
        long middle = cols.stream().filter(c -> c.x() == 100).count();
        assertEquals(3, middle);
        assertTrue(cols.stream().anyMatch(BusStop.Column::edge));
        // where a bus stands to stop: on the bay, not in the lanes
        assertTrue(stop.standing(102, 57.5, s.stops().stopReach(), s.stops().bayWidth()));
        assertFalse(stop.standing(102, 51, s.stops().stopReach(), s.stops().bayWidth()));
        assertFalse(stop.standing(120, 57.5, s.stops().stopReach(), s.stops().bayWidth()));
        assertTrue(stop.byTheStop(102, 51, s.stops().stopReach()));
        // the left side mirrors it
        BusStop left = new BusStop(2, "x", "world", 100.5, 50.5, 128, 1, 0, -1, 4, false, true, "동항");
        assertEquals(44.5, left.at(0, 6)[1], 1e-9);
        // waiting passengers face the road (north for a stop south of an eastward road)
        assertEquals(180, Math.abs(stop.facingRoad()), 1e-3);
        // a simple stop has no bay
        assertTrue(new BusStop(3, "x", "world", 0, 0, 0, 1, 0, 1, 4, false, false, "").bayColumns(24, 3).isEmpty());
    }

    @Test
    void surfaceLevels() {
        BusStop even = new BusStop(1, "a", "w", 0, 0, 128, 1, 0, 1, 4, false, true, "");
        assertEquals(64, even.y());
        assertFalse(even.slab());
        assertEquals(65.0, even.top(), 1e-9);
        BusStop odd = new BusStop(1, "a", "w", 0, 0, 129, 1, 0, 1, 4, false, true, "");
        assertEquals(65, odd.y());
        assertTrue(odd.slab());
        assertEquals(65.5, odd.top(), 1e-9);
    }
}
