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
    void rentalKeepsTheShare() {
        BusSpec.Rental r = spec().rental();
        assertEquals("stsv:mini_bus", r.vehicle());
        assertEquals(400, r.keep(1000));
        assertEquals(60, r.returnAfter());
        assertEquals(20, r.range());
    }

    @Test
    void noDailyCap() {
        BusSpec s = spec();
        // every fare paid in full however much was earned today
        assertEquals(1000, s.daily(0, 1000), 1e-9);
        assertEquals(1000, s.daily(150_000, 1000), 1e-9);
        assertEquals(1000, s.daily(5_000_000, 1000), 1e-9);
    }

    @Test
    void aDailyCurveStillWorksWhenSet() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml"));
        y.set("fare.daily", List.of(java.util.Map.of("upto", 100000, "rate", 1.0), java.util.Map.of("upto", 200000, "rate", 0.5),
                java.util.Map.of("upto", -1, "rate", 0.2)));
        BusSpec s = BusSpec.parse(y, problems);
        assertEquals(1000, s.daily(0, 1000), 1e-9);
        assertEquals(500, s.daily(100_000, 1000), 1e-9);
        assertEquals(200, s.daily(250_000, 1000), 1e-9);
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
        assertEquals(54, s.types().size());
        // 2026-11: 할머니는 흔한 손님 (18 = 동네 아저씨 · 대학생과 같음, 약 4.6%; 예전 7.4%), 진상 · 착한 손님의 새 버릇
        double total = s.types().values().stream().mapToDouble(BusSpec.Type::weight).sum();
        assertEquals(18, s.types().get("grandma").weight(), 1e-9);
        assertEquals(s.types().get("uncle").weight(), s.types().get("grandma").weight(), 1e-9);
        assertEquals(0.046, s.types().get("grandma").weight() / total, 0.003);
        assertEquals(390.8, total, 0.05);   // + 건달 3.4
        assertEquals(0.35, s.types().get("dodger").x().dodge(), 1e-9);
        assertEquals(50, s.types().get("rude").x().haggleAt(), 1e-9);
        assertEquals(0.5, s.types().get("rude").x().haggle(), 1e-9);
        assertEquals(0.5, s.types().get("prankster").x().prank(), 1e-9);
        assertEquals(-6, s.types().get("crumbs").x().litter(), 1e-9);
        assertEquals(0.5, s.types().get("gentleman").x().treat(), 1e-9);
        assertEquals(0, s.types().get("resident").x().dodge(), 1e-9);
        // 2026-11 분포: 네 단 (흔함 45% · 자주 30% · 가끔 18% · 드묾 7%), 어느 한 종류도 전체의 8% 를 넘지 않음
        for (BusSpec.Type t : s.types().values()) assertTrue(t.weight() / total <= 0.08, t.id());
        assertTrue(s.vehicles().contains("stsv:village_bus"));
    }

    @Test
    void kindsByPlaceTimeAndDay() {
        BusSpec s = spec();
        BusSpec.Type hiker = s.types().get("hiker"), fisher = s.types().get("fisher"), drunk = s.types().get("drunk"),
                soldier = s.types().get("soldier"), farmer = s.types().get("farmer");
        // a mountain stop has far more hikers than a seaside one; a seaside one more fishers
        assertTrue(hiker.weightAt(java.util.Set.of("mountain"), 10, false, false, false) > 10 * hiker.weightAt(java.util.Set.of("sea"), 10, false, false, false));
        assertTrue(fisher.weightAt(java.util.Set.of("sea"), 10, false, false, false) > 10 * fisher.weightAt(java.util.Set.of("mountain"), 10, false, false, false));
        // hours: no hikers at night, the drunk only at night (a window over midnight)
        assertEquals(0, hiker.weightAt(java.util.Set.of(), 23, false, false, false));
        assertEquals(0, drunk.weightAt(java.util.Set.of(), 15, false, false, false));
        assertTrue(drunk.weightAt(java.util.Set.of(), 23, false, false, false) > 0);
        assertTrue(drunk.weightAt(java.util.Set.of(), 2, false, false, false) > 0);
        // soldiers come at weekends, farmers on market day
        assertEquals(8, soldier.weightAt(java.util.Set.of(), 12, true, false, false) / soldier.weightAt(java.util.Set.of(), 12, false, false, false), 1e-9);
        assertEquals(3, farmer.weightAt(java.util.Set.of(), 12, false, true, false) / farmer.weightAt(java.util.Set.of(), 12, false, false, false), 1e-9);
        assertTrue(s.types().get("couple").pair());
        assertTrue(soldier.toEnd());
        assertEquals(100, farmer.luggage(), 1e-9);
        // market day every 5 days; talk falls back to the default lines
        assertTrue(s.marketDay(10));
        assertFalse(s.marketDay(11));
        assertNotNull(s.lines(s.types().get("resident"), "jolt"));
        assertEquals(s.types().get("grandma").talk().get("jolt"), s.lines(s.types().get("grandma"), "jolt"));
        // the buses differ by how many they carry: every bus takes every kind
        assertTrue(s.bus("stsv:village_bus").takes(s.types().get("grandma")));
        assertTrue(s.bus("stsv:limousine_bus").takes(s.types().get("student")));
        assertEquals(0, s.bus("stsv:mini_bus").minRoad(), 1e-9);
        assertEquals(java.util.Set.of("stsv:mini_bus", "stsv:village_bus", "stsv:limousine_bus"), s.vehicles());
        // grandma's gifts are real items of the economy
        for (BusSpec.Gift g : s.types().get("grandma").gifts()) assertTrue(g.item().startsWith("economy:"), g.item());
    }

    /** 배 (economy:pear): the 할머니 is rarer now, so the 시장 아줌마 and the 농부 give one too; about as many pears as before. */
    @Test
    void pearsStillComeAsOften() {
        BusSpec s = spec();
        double total = s.types().values().stream().mapToDouble(BusSpec.Type::weight).sum(), now = 0;
        for (BusSpec.Type t : s.types().values()) {
            long pears = t.gifts().stream().filter(g -> g.item().equals("economy:pear")).count();
            if (pears == 0) continue;
            double chance = t.x().giftChance() >= 0 ? t.x().giftChance() : s.extra().giftChance();
            now += t.weight() / total * chance * pears / t.gifts().size();
        }
        double before = 29.5 / 398.9 * 0.47 / 4;   // only the 할머니 (29.5 of 398.9), one of her four gifts
        assertEquals(1.0, now / before, 0.15, "pears per pleased passenger, against before");
        // the 시장 아줌마's other gifts as often as before (0.2 of three = 0.27 of four)
        BusSpec.Type market = s.types().get("market");
        assertEquals(0.2 / 3, market.x().giftChance() / market.gifts().size(), 0.002);
        assertTrue(s.types().get("farmer").gifts().stream().allMatch(g -> g.item().equals("economy:pear")));
    }

    /** 제사상 hints (types.*.talk.jesa-hint): real lines of several kinds, never a default line, never an admin preview situation. */
    @Test
    void tableHintsAreTheirOwnLines() {
        BusSpec s = spec();
        int n = 0, kinds = 0;
        for (BusSpec.Type t : s.types().values()) {
            List<String> h = s.hints(t);
            assertFalse(s.situations(t).contains(BusSpec.HINT), t.id());
            if (h.isEmpty()) continue;
            kinds++;
            n += h.size();
            assertTrue(h.size() >= 3 && h.size() <= 6, t.id() + " " + h.size());
            for (String line : h) {
                assertFalse(line.isBlank(), t.id());
                for (String bad : List.of("비밀", "감나무", "초가", "{", "<")) assertFalse(line.contains(bad), t.id() + ": " + line);
            }
        }
        assertTrue(kinds >= 8, "kinds " + kinds);
        assertTrue(n >= 20 && n <= 40, "lines " + n);
        assertFalse(s.extra().talk().containsKey(BusSpec.HINT), "no default hint lines (every kind would say them)");
        assertTrue(s.hints(s.types().get("grandma")).isEmpty(), "she is not the one who tells");
        // every rule of the table is in some line
        String all = String.join("\n", s.types().values().stream().flatMap(t -> s.hints(t).stream()).toList());
        for (String rule : List.of("홍동백서", "어동육서", "좌포우혜", "반서갱동", "조율이시", "고춧가루", "마늘", "팥", "탕", "배", "가운데 잔",
                "두 번", "밤", "북어포", "손질대", "명태", "치' 자")) assertTrue(all.contains(rule), rule);
        assertTrue(s.extra().hintChance() > 0 && s.extra().hintChance() <= 0.1);
        assertTrue(s.extra().hintGap() >= 60);
    }

    /** Only a driver who is laying a table hears a hint, whatever the dice; and only from a kind that has hint lines. */
    @Test
    void hintsOnlyForSeekers() {
        BusSpec s = spec();
        BusSpec.Type monk = s.types().get("monk"), office = s.types().get("office"), market = s.types().get("market");
        List<BusSpec.Type> aboard = java.util.Arrays.asList(office, monk, null, market);
        for (int pick = 0; pick < 8; pick++) {
            assertEquals(-1, BusModule.hinter(s, aboard, false, 0.0, 1.0, pick), "never for someone not seeking");
            int i = BusModule.hinter(s, aboard, true, 0.0, 1.0, pick);
            assertTrue(i == 1 || i == 3, "the monk or the market woman, not " + i);
        }
        assertEquals(-1, BusModule.hinter(s, aboard, true, 0.5, 0.03, 0), "the roll missed");
        assertEquals(-1, BusModule.hinter(s, List.of(office, office), true, 0.0, 1.0, 0), "nobody aboard who talks of tables");
        assertEquals(-1, BusModule.hinter(s, java.util.Arrays.asList((BusSpec.Type) null), true, 0.0, 1.0, 0));
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
        assertTrue(spots.size() >= 9 && spots.size() <= 12, "" + spots.size());
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
    void cuteStopNamesByTheirTraits() {
        BusSpec s = spec();
        String sea = s.stopName(java.util.Set.of("sea"), java.util.Set.of(), 0.0);
        assertTrue(s.extra().names().get("sea").contains(sea), sea);
        // never one already used: with every sea name taken, a general one
        java.util.Set<String> used = new java.util.HashSet<>(s.extra().names().get("sea"));
        String next = s.stopName(java.util.Set.of("sea"), used, 0.5);
        assertTrue(s.extra().names().get("any").contains(next), next);
        // no traits: a general one; all taken: a general one with a number
        assertTrue(s.extra().names().get("any").contains(s.stopName(java.util.Set.of(), java.util.Set.of(), 0.99)));
        used.addAll(s.extra().names().get("any"));
        String numbered = s.stopName(java.util.Set.of(), used, 0.0);
        assertTrue(numbered.matches(".* \\d+"), numbered);
        assertFalse(used.contains(numbered));
        // every trait has names
        for (String t : s.extra().tags().keySet()) assertFalse(s.extra().names().getOrDefault(t, List.of()).isEmpty(), t);
    }

    @Test
    void autoSpotsKeepOffBends() {
        BusSpec s = spec();
        // a road east 1500, then a sharp bend north for 1500: no stop on the bend
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 1500; i++) pts.add(new double[]{i + 0.5, 0.5});
        for (int i = 1; i <= 1500; i++) pts.add(new double[]{1499.5, 0.5 - i});
        BusRoutes r = new BusRoutes(List.of(new RoadNetwork.Road("x", pts, new int[pts.size()])));
        List<BusRoutes.Spot> spots = r.autoSpots(s.stops().auto(), s.stops().bayLength(), List.of());
        assertFalse(spots.isEmpty());
        for (BusRoutes.Spot sp : spots) {
            double[] p = r.place(sp.node());
            assertTrue(Math.hypot(p[0] - 1499.5, p[1] - 0.5) > s.stops().bayLength() / 2.0 + 4, "on the bend: " + p[0] + "," + p[1]);
        }
        // a stop's way is the road's, not a step's: straight east on a straight road
        double[] p = r.place(r.node(0, 700));
        assertEquals(1, p[3], 1e-6);
        assertEquals(0, p[4], 1e-6);
    }

    @Test
    void theShoulderAlongTheBayIsCleared() {
        BusSpec s = spec();
        BusStop stop = new BusStop(1, "시장 앞", "world", 100.5, 50.5, 128, 1, 0, 1, 4, false, true, "동항");
        List<BusStop.Column> sh = stop.shoulderColumns(s.stops().bayLength());
        assertFalse(sh.isEmpty());
        // between the kerb (3.5) and the bay (5.5) on the stop's side, along the bay
        for (BusStop.Column c : sh) {
            assertTrue(c.z() >= 54 && c.z() <= 55, "" + c);
            assertTrue(c.x() >= 88 && c.x() <= 113, "" + c);
        }
        assertTrue(new BusStop(2, "x", "world", 0, 0, 0, 1, 0, 1, 4, false, false, "").shoulderColumns(24).isEmpty());
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
        // a simple stop: anywhere on the road by it, either lane (a bus need not squeeze onto the shoulder; one coming the
        // other way stops in its own lane: there is no bay to pull into)
        BusStop simple = new BusStop(4, "x", "world", 100.5, 50.5, 128, 1, 0, 1, 4, false, false, "동항");
        assertTrue(simple.standing(102, 52, s.stops().stopReach(), s.stops().bayWidth()));
        assertTrue(simple.standing(102, 49, s.stops().stopReach(), s.stops().bayWidth()));
        assertFalse(simple.standing(102, 43, s.stops().stopReach(), s.stops().bayWidth()));
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

    static BusLine line(BusRoutes r, BusRoutes.Line l, int number, int[] stopNodes) {
        double[] xs = new double[l.path().length], zs = new double[l.path().length];
        boolean[] jn = new boolean[l.path().length];
        java.util.Map<Integer, Integer> at = new java.util.HashMap<>();
        for (int k = 0; k < l.path().length; k++) {
            double[] xz = r.xz(l.path()[k]);
            xs[k] = xz[0];
            zs[k] = xz[1];
            jn[k] = r.junction(l.path()[k]);
            at.put(l.path()[k], k);
        }
        BusLine bl = new BusLine(number, l.dock(), xs, zs, jn);
        for (int i = 0; i < stopNodes.length; i++) {
            Integer k = at.get(stopNodes[i]);
            if (k != null) bl.addStop(i, k);
        }
        return bl;
    }

    @Test
    void linesRunFromTheTerminalToEachDockThroughTheTrunk() {
        RoadNetwork.Road trunk = straight("동항", 0.5, 0.5, 1, 0, 3000);
        RoadNetwork.Road branch = straight("북항", 1500.5, -2.5, 0, -1, 1000);
        BusRoutes r = new BusRoutes(List.of(trunk, branch));
        List<BusRoutes.Line> ls = r.lines();
        assertEquals(2, ls.size());
        assertEquals(3000, ls.get(0).path().length);
        assertTrue(Math.abs(ls.get(1).path().length - 2501) <= 3, "" + ls.get(1).path().length);
        // stops: 0 A (trunk 100), 1 B (trunk 1000), 2 C (trunk 2900), 3 D (branch, 900 up it)
        int[] nodes = {r.attach(100, 8), r.attach(1000, 8), r.attach(2900, 8), r.attach(1504, -900)};
        BusLine east = line(r, ls.get(0), 1, nodes), north = line(r, ls.get(1), 2, nodes);
        assertEquals(List.of(0, 1, 2), east.stops());
        assertEquals(List.of(0, 1, 3), north.stops());
        assertEquals("1번 동항선", east.name());
        // passengers only go ahead: from B towards the dock C, towards the terminal A; never back the way the bus came
        assertEquals(List.of(2), east.ahead(east.at(1), 1));
        assertEquals(List.of(0), east.ahead(east.at(1), -1));
        assertEquals(List.of(1, 3), north.ahead(north.at(0), 1));
        assertNull(east.next(east.at(2), 1));   // the end of the line
        // which way a bus runs it, by where it faces (east = yaw -90: towards the dock)
        assertEquals(1, east.dir(east.at(1), 1, 0));
        assertEquals(-1, east.dir(east.at(1), -1, 0));
        // the way goes straight on ahead
        int i = east.nearest(500, 0.5, null);
        assertEquals("↑", east.arrow(i, 1, 500, 0.5, -90f));
        assertEquals("↓", east.arrow(i, -1, 500, 0.5, -90f));
        // the junction: 북항선 turns left (north) off the trunk, 동항선 goes straight on
        BusLine.Turn tn = north.nextJunction(north.nearest(1400, 0.5, null), 1, 150);
        assertNotNull(tn);
        assertEquals(-1, tn.turn());
        assertEquals(100, tn.distance(), 3);
        BusLine.Turn te = east.nextJunction(east.nearest(1400, 0.5, null), 1, 150);
        assertNotNull(te);
        assertEquals(0, te.turn());
        assertNull(east.nextJunction(east.nearest(400, 0.5, null), 1, 150));
        // distances along the way
        assertEquals(900, east.between(east.at(0), east.at(1)), 2);
    }

    @Test
    void passengersHoldTheirThings() {
        BusSpec s = spec();
        // one id: every look holds it; the shiny (-1) too
        BusSpec.Hold hen = s.types().get("chicken").hold();
        for (int look = -1; look < 3; look++) assertEquals("economy:bus_hold_chicken", hen.main(look));
        assertEquals("", hen.off(0));
        assertEquals("economy:bus_hold_chicken_flap", hen.fly());
        // a list: per look in the order of skins, "" an empty hand, the shiny holds the first look's
        BusSpec.Hold shaman = s.types().get("shaman").hold();
        assertEquals("economy:bus_hold_bells", shaman.main(0));
        assertEquals("", shaman.main(1));
        assertEquals("economy:bus_hold_fan", shaman.off(1));
        assertEquals("economy:bus_hold_fan", shaman.off(2));
        assertEquals("economy:bus_hold_bells", shaman.main(-1));
        assertEquals("", shaman.off(-1));
        BusSpec.Hold dog = s.types().get("dogwalker").hold();
        assertEquals("economy:bus_hold_cane", dog.main(1));
        assertEquals("economy:bus_hold_leash", dog.off(1));
        // nothing in hand: NONE
        assertSame(BusSpec.Hold.NONE, s.types().get("resident").hold());
        assertFalse(s.types().get("resident").hold().any());
        // only the hen flies
        for (BusSpec.Type t : s.types().values()) if (!t.id().equals("chicken")) assertEquals("", t.hold().fly(), t.id());
        // every held thing is a bus_hold_ model
        for (BusSpec.Type t : s.types().values()) for (String id : t.hold().ids()) assertTrue(id.startsWith("economy:bus_hold_"), t.id() + ": " + id);
    }

    @Test
    void holdProblemsAreReported() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml"));
        y.set("types.resident.hold", "bus_hold_wand");                         // no namespace: economy's
        y.set("types.student.hold-off", List.of("economy:bus_hold_snack", ""));   // 3 looks, 2 given
        y.set("types.grandma.hold", "not an id!");                             // malformed
        y.set("types.office.hold-fly", "economy:bus_hold_chicken_flap");       // nothing in the main hand to fly
        y.set("types.vip.hold", List.of("", "", ""));                          // all empty: nothing
        List<String> problems = new ArrayList<>();
        BusSpec s = BusSpec.parse(y, problems);
        assertEquals("economy:bus_hold_wand", s.types().get("resident").hold().main(0));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("types.student.hold-off") && p.contains("3")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("types.grandma.hold") && p.contains("잘못된")), problems.toString());
        assertEquals("", s.types().get("grandma").hold().main(0));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("types.office.hold-fly")), problems.toString());
        assertFalse(s.types().get("vip").hold().any());
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void theHenFlutterStaysInTheBusAndComesBack() {
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            List<org.joml.Vector3f> path = Flutter.path(1f, rnd);
            assertEquals(Flutter.TICKS / Flutter.HOP + 1, path.size());
            assertEquals(path.getFirst(), path.getLast(), "back in her hands");
            for (org.joml.Vector3f p : path) {
                assertTrue(Math.abs(p.x) <= 0.85f, "beside the seat: " + p);
                assertTrue(p.z >= -0.65f && p.z <= 1.35f, "not far along the bus: " + p);
                assertTrue(p.y <= -0.35f && p.y >= -1.2f, "between the floor and the shoulders: " + p);
            }
        }
        // a hop goes up between its ends
        assertTrue(Flutter.TICKS % Flutter.HOP == 0 && Flutter.HOP % (2 * Flutter.STEP) == 0);
    }
}
