package dev.stsv.economy.road;

import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 성격 (README "국도", 성격): 쉬운길 / 보통 / 어려운길. The character read and saved (an older road: 보통), what each one
 * plans and builds with (보통 exactly as roads always were), what it pays, and the hard roads driven by the long vehicles'
 * own physics (RoadBendTest's harness): a careful driver (on his lane, at most 4.5 b/s) gets through every hard shape
 * without getting stuck or touching anything, a fast sloppy one scrapes and bumps.
 */
class RoadCharacterTest {

    static Map<String, VehicleSpec> all;

    @BeforeAll
    static void read() throws IOException {
        all = RoadClearanceTest.landVehicles();
    }

    @AfterEach
    void defaults() {
        RoadCharacter.configure(null);
    }

    // ------------------------------------------------------------------------------------------
    // The character: words, the road file
    // ------------------------------------------------------------------------------------------

    @Test
    void parsesItsWordsAndIds() {
        assertEquals(RoadCharacter.EASY, RoadCharacter.parse("쉬운길"));
        assertEquals(RoadCharacter.NORMAL, RoadCharacter.parse("보통"));
        assertEquals(RoadCharacter.HARD, RoadCharacter.parse("어려운길"));
        assertEquals(RoadCharacter.HARD, RoadCharacter.parse("hard"));
        assertEquals(RoadCharacter.EASY, RoadCharacter.parse("EASY"));
        assertNull(RoadCharacter.parse("기준점"));
        assertNull(RoadCharacter.parse("새로"));
        assertEquals(RoadCharacter.NORMAL, RoadCharacter.ofId(null));
        assertEquals(RoadCharacter.NORMAL, RoadCharacter.ofId("무엇"));
    }

    @Test
    void savedWithTheRoadAndOldRoadsAreNormal() {
        RoadJob j = new RoadJob();
        j.dock = "서해";
        j.world = "world";
        j.corners.add(new double[]{0.5, 0.5});
        j.corners.add(new double[]{40.5, 0.5});
        assertFalse(j.write().contains("character="), "a normal road's file is as it always was");
        j.character = RoadCharacter.HARD;
        String text = j.write();
        assertTrue(text.contains("character=hard\n"));
        assertEquals(RoadCharacter.HARD, RoadJob.read(text).character);
        assertEquals(RoadCharacter.HARD, j.snapshot().character);
        j.character = RoadCharacter.EASY;
        assertEquals(RoadCharacter.EASY, RoadJob.read(j.write()).character);
        // a road saved before characters
        String old = "dock=서해\nworld=world\nstart=0,0\nend=40,0\nphase=DONE\nrunning=false\ncursor=0\nstarted=0\nroute=3\nsigns=1\n";
        assertEquals(RoadCharacter.NORMAL, RoadJob.read(old).character);
        // continuing a road keeps it; its patch keeps it
        RoadJob done = RoadJob.read(j.write());
        done.phase = RoadJob.Phase.DONE;
        for (int i = 0; i < done.points().size(); i++) done.ground.add(new RoadJob.Ground());
        assertEquals(RoadCharacter.EASY, RoadModule.tailJob(done, new int[]{60, 64, 0}).character);
        // a preview of another character never stands in for the job
        RoadJob pv = j.snapshot();
        pv.preview = true;
        pv.phase = RoadJob.Phase.DONE;
        pv.started = 1000;
        RoadJob job = j.snapshot();
        assertTrue(pv.samePlan(job, 2000));
        job.character = RoadCharacter.HARD;
        assertFalse(pv.samePlan(job, 2000));
    }

    @Test
    void theNetworkKnowsEachRoadsCharacter() {
        List<double[]> a = RoadPlanner.densify(List.of(new double[]{0.5, 0.5}, new double[]{100.5, 0.5}), 1);
        List<double[]> b = RoadPlanner.densify(List.of(new double[]{0.5, 50.5}, new double[]{100.5, 50.5}), 1);
        RoadNetwork n = RoadNetwork.of(List.of(new RoadNetwork.Road("a", a, new int[a.size()], RoadCharacter.HARD),
                new RoadNetwork.Road("b", b, new int[b.size()])));
        assertEquals(RoadCharacter.HARD, n.character(50, 0));
        assertEquals(RoadCharacter.NORMAL, n.character(50, 50));
        assertNull(n.character(50, 25));
        assertEquals(RoadCharacter.HARD, n.characterNear(50, 8, RoadRewards.STOP_REACH), "a stop's bay beside the road");
        assertNull(n.characterNear(50, 25, RoadRewards.STOP_REACH));
        // the speed bonus: an easy road's more, the others as roads.speed-bonus
        assertEquals(1.2, RoadModule.speedBonus(1.2, RoadCharacter.NORMAL), 1e-9);
        assertEquals(1.2, RoadModule.speedBonus(1.2, null), 1e-9);
        assertEquals(1.2, RoadModule.speedBonus(1.2, RoadCharacter.HARD), 1e-9);
        assertEquals(1.35, RoadModule.speedBonus(1.2, RoadCharacter.EASY), 1e-9);
        assertEquals(2.0, RoadModule.speedBonus(1.95, RoadCharacter.EASY), 1e-9, "never over 2");
    }

    // ------------------------------------------------------------------------------------------
    // What each character plans and builds with
    // ------------------------------------------------------------------------------------------

    @Test
    void normalPlansAndBuildsAsRoadsAlwaysDid() {
        RoadCharacter.Shape n = RoadCharacter.NORMAL.shape();
        assertEquals(RoadPlanner.Weights.DEFAULT, n.weights());
        assertEquals(RoadProfile.Grade.NORMAL, n.grade());
        assertEquals(RoadModule.BRIDGE_GAP, n.bridgeGap());
        assertEquals(RoadModule.TUNNEL_COVER, n.tunnelCover());
        assertEquals(RoadModule.MIN_RUN, n.minRun());
        assertEquals(RoadProfile.FOLLOW, n.follow());
        assertEquals(0, n.shoulder());
        assertEquals(40, n.lightEvery(40));
        assertEquals(RoadSection.REFLECT_EVERY, n.reflectEvery());
        assertEquals(RoadSection.RAIL_DROP, n.railDrop());
        // the config cannot change how a normal road is built, only what it pays
        Map<RoadCharacter, RoadCharacter.Shape> m = new HashMap<>();
        RoadCharacter.Shape d = RoadCharacter.HARD.defaults();
        m.put(RoadCharacter.NORMAL, d);
        RoadCharacter.configure(m);
        assertEquals(RoadPlanner.Weights.DEFAULT, RoadCharacter.NORMAL.shape().weights());
        assertEquals(0, RoadCharacter.NORMAL.shape().shoulder());
        assertEquals(1.4, RoadCharacter.NORMAL.shape().fare(), 1e-9);
        // the same road built as normal and with the normal character set: block for block the same
        BuiltRoad a = build(new RoadBendTest.Terra(RoadBendTest.HILL, RoadBendTest.DRY), RoadBendTest.corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 100), null);
        BuiltRoad b = build(new RoadBendTest.Terra(RoadBendTest.HILL, RoadBendTest.DRY), RoadBendTest.corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 100),
                RoadCharacter.NORMAL);
        assertEquals(a.land().put, b.land().put);
    }

    @Test
    void eachCharactersPlannerAndGrade() {
        assertEquals(new RoadProfile.Grade(3, 2), RoadProfile.Grade.of(3));
        assertEquals(new RoadProfile.Grade(5, 2), RoadProfile.Grade.of(5));
        assertEquals(new RoadProfile.Grade(5, 4), RoadProfile.Grade.of(2.5));
        RoadCharacter.Shape e = RoadCharacter.EASY.shape(), h = RoadCharacter.HARD.shape(), n = RoadCharacter.NORMAL.shape();
        assertEquals(new RoadProfile.Grade(5, 2), e.grade());
        assertEquals(new RoadProfile.Grade(5, 4), h.grade());
        assertTrue(e.weights().turn() > 0 && n.weights().turn() == 0 && h.weights().turn() == 0);
        assertTrue(e.weights().steep() > n.weights().steep() && h.weights().steep() < n.weights().steep());
        assertTrue(e.weights().climb() > n.weights().climb() && h.weights().climb() < n.weights().climb());
        assertTrue(h.tunnelCover() > n.tunnelCover() && h.bridgeGap() > n.bridgeGap(), "a hard road keeps off bridges and tunnels");
        assertTrue(e.tunnelCover() < n.tunnelCover(), "an easy road bores rather than climbs");
        assertEquals(30, e.lightEvery(40));
        assertEquals(80, h.lightEvery(40));

        // the profile: up a 1 in 1 slope from 63 to 93, each road keeps its own grade
        int len = 200;
        int[] ground = new int[len];
        for (int i = 0; i < len; i++) ground[i] = 63 + Math.clamp(i - 60, 0, 30);
        for (RoadCharacter c : RoadCharacter.values()) {
            RoadProfile.Grade g = c.shape().grade();
            RoadProfile.Plan p = RoadProfile.planNetwork(ground, new boolean[len], ground, fill(len), null, g, c.shape().follow());
            assertTrue(RoadProfile.graded(p.halves(), g), c + " keeps its grade");
            int steepest = 0;
            for (int i = 0; i + g.window() < len; i++) steepest = Math.max(steepest, Math.abs(p.halves()[i + g.window()] - p.halves()[i]));
            assertEquals(g.halves(), steepest, c + " climbs at its grade");
        }
        assertFalse(RoadProfile.graded(new int[]{0, 1, 2, 3, 4, 4}, RoadProfile.Grade.NORMAL));
        assertTrue(RoadProfile.graded(new int[]{0, 1, 2, 3, 4, 4}, RoadProfile.Grade.of(2.5)));
        assertFalse(RoadProfile.graded(new int[]{0, 1, 2, 2, 2, 2}, RoadProfile.Grade.of(5)), "an easy road's half steps at least 2 apart");

        // the planner: across a corridor of ridges, the easy road turns less, the hard one climbs more
        RoadPlanner.Field f = ridges();
        List<int[]> pe = RoadPlanner.path(f, 0, 0, 30, 12, e.weights(), 100_000), pn = RoadPlanner.path(f, 0, 0, 30, 12, n.weights(), 100_000),
                ph = RoadPlanner.path(f, 0, 0, 30, 12, h.weights(), 100_000);
        assertFalse(pe.isEmpty() || pn.isEmpty() || ph.isEmpty());
        assertTrue(RoadPlanner.turns(pe) <= RoadPlanner.turns(pn), "easy " + RoadPlanner.turns(pe) + " vs normal " + RoadPlanner.turns(pn));
        assertTrue(climb(f, ph) >= climb(f, pn), "hard " + climb(f, ph) + " vs normal " + climb(f, pn));
        // the same as roads always were without a turn cost
        assertEquals(cells(RoadPlanner.path(f, 0, 0, 30, 12, RoadPlanner.Weights.DEFAULT, 100_000)), cells(pn));
    }

    static int[] fill(int n) {
        int[] out = new int[n];
        java.util.Arrays.fill(out, Integer.MIN_VALUE);
        return out;
    }

    static String cells(List<int[]> c) {
        StringBuilder b = new StringBuilder();
        for (int[] x : c) b.append(x[0]).append(',').append(x[1]).append(' ');
        return b.toString();
    }

    static double climb(RoadPlanner.Field f, List<int[]> c) {
        double s = 0;
        for (int i = 1; i < c.size(); i++) s += Math.abs(f.height(c.get(i)[0], c.get(i)[1]) - f.height(c.get(i - 1)[0], c.get(i - 1)[1]));
        return s;
    }

    /** A corridor 31 x 13 cells with hills in it. */
    static RoadPlanner.Field ridges() {
        return new RoadPlanner.Field() {
            public boolean known(int x, int z) {
                return x >= 0 && x <= 30 && z >= 0 && z <= 12;
            }

            public double height(int x, int z) {
                return 64 + 30 * Math.max(0, Math.sin(x * 0.5)) * Math.max(0, Math.cos(z * 0.4));
            }

            public double water(int x, int z) {
                return 0;
            }

            public double rough(int x, int z) {
                return 0;
            }

            public boolean blocked(int x, int z) {
                return false;
            }
        };
    }

    /** Flat, a block lower from 4 out of the road's line (z = 0): the shoulder is filled up to the road, so it shows. */
    static final RoadBendTest.Ground VERGE = (x, z) -> Math.abs(z) >= 4 ? 62 : 63;

    @Test
    void crossSectionsOfEachCharacter() {
        // a straight road over flat land: the shoulder's width, the lights, the reflectors
        Map<RoadCharacter, BuiltRoad> roads = new LinkedHashMap<>();
        for (RoadCharacter c : RoadCharacter.values())
            roads.put(c, build(new RoadBendTest.Terra(VERGE, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 400, 0), c));
        Map<RoadCharacter, Integer> width = new HashMap<>(), lights = new HashMap<>(), reflectors = new HashMap<>(), worn = new HashMap<>();
        for (var e : roads.entrySet()) {
            int w = 0, l = 0, r = 0, d = 0;
            for (var b : e.getValue().land().put.entrySet()) {
                long k = b.getKey();
                int z = (int) ((k >> 21) & ((1 << 21) - 1)) - (1 << 20), y = (int) (k & ((1 << 21) - 1)) - 1024;
                int x = (int) (k >> 42) - (1 << 20);
                if (x < 30 || x > 350) continue;   // (not the ends: the gate widens)
                if (y == 63 && (b.getValue() == Material.GRASS_BLOCK || b.getValue() == Material.DIRT_PATH || b.getValue() == Material.IRON_TRAPDOOR))
                    w = Math.max(w, Math.abs(z));
                if (b.getValue() == Material.LANTERN) l++;
                if (b.getValue() == Material.END_ROD) r++;
                if (b.getValue() == Material.COARSE_DIRT) d++;
            }
            width.put(e.getKey(), w);
            lights.put(e.getKey(), l);
            reflectors.put(e.getKey(), r);
            worn.put(e.getKey(), d);
        }
        assertEquals(5, width.get(RoadCharacter.NORMAL), "the shoulder out to 5.5: " + width);
        assertEquals(6, width.get(RoadCharacter.EASY), "+1: " + width);
        assertEquals(4, width.get(RoadCharacter.HARD), "-1: " + width);
        assertTrue(lights.get(RoadCharacter.EASY) > lights.get(RoadCharacter.NORMAL) && lights.get(RoadCharacter.NORMAL) > lights.get(RoadCharacter.HARD) * 1.5,
                "lights: " + lights);
        assertTrue(lights.get(RoadCharacter.HARD) > 0, "a hard road still has lights: " + lights);
        assertTrue(reflectors.get(RoadCharacter.EASY) > reflectors.get(RoadCharacter.NORMAL), "reflectors: " + reflectors);
        assertEquals(0, reflectors.get(RoadCharacter.HARD), "a hard road: guardrails at drops only, no reflectors");
        assertTrue(worn.get(RoadCharacter.HARD) > 10 && worn.get(RoadCharacter.NORMAL) == 0, "a hard road's worn edge: " + worn);
        // nothing stands in the reach on any of them
        for (var e : roads.entrySet()) assertEquals(List.of(), RoadBendTest.inTheWay(e.getValue().road(), e.getValue().road().section()), e.getKey().word);
    }

    @Test
    void signsSayTheCharacter() {
        assertEquals("국도 7호선", RoadSign.words(RoadSign.Kind.KM, "서해", 3, 7).route());
        assertEquals("고속국도 7호선", RoadSign.words(RoadSign.Kind.KM, "서해", 3, 7, RoadCharacter.EASY).route());
        RoadSign.Words w = RoadSign.words(RoadSign.Kind.CURVE, "서해", 3, 7, RoadCharacter.HARD);
        assertEquals(RoadSign.CURVE_WORDS, w.big());
        assertEquals("", w.km());
        // a hard road warns of its sharp bends, steep grades and tunnels; the others do not
        BuiltRoad hard = build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 200, 0, 200, 200, 400, 200), RoadCharacter.HARD);
        Map<Integer, RoadSign.Kind> warns = RoadSection.warnings(hard.road().points(), hard.road().halves(), hard.road().parts(), null,
                hard.road().section().signs().keySet(), 80);
        assertTrue(warns.containsValue(RoadSign.Kind.CURVE), "warnings: " + warns);
        for (int i : warns.keySet()) {
            assertTrue(i < 200 - RoadSection.WARN_AHEAD + 1 || (i > 200 + RoadSection.BEND_REACH && i < 400 - RoadSection.WARN_AHEAD + 1), "before its bend: " + i);
            for (int s : hard.road().section().signs().keySet()) assertTrue(Math.abs(s - i) >= RoadSection.WARN_GAP);
        }
        int[] up = new int[300];
        for (int i = 0; i < 300; i++) up[i] = Math.clamp(i - 100, 0, 40);
        List<double[]> pts = RoadPlanner.densify(List.of(new double[]{0.5, 0.5}, new double[]{299.5, 0.5}), 1);
        RoadProfile.Part[] parts = new RoadProfile.Part[300];
        java.util.Arrays.fill(parts, RoadProfile.Part.ROAD);
        for (int i = 220; i < 240; i++) parts[i] = RoadProfile.Part.TUNNEL;
        Map<Integer, RoadSign.Kind> g = RoadSection.warnings(pts, up, parts, null, java.util.Set.of(), 80);
        assertTrue(g.containsValue(RoadSign.Kind.GRADE) && g.containsValue(RoadSign.Kind.ROCKS), "grade and rocks: " + g);
        RoadGuide.Plan plan = RoadGuide.plan(List.of(new RoadGuide.Road("서해", hard.road().points(), hard.road().halves(), hard.road().parts(), 1, "본사",
                RoadCharacter.HARD, null)), 1000, 40);
        assertTrue(plan.signs().stream().anyMatch(s -> s.type() == RoadGuide.Type.WARNING), "the guide puts them up again");
        RoadGuide.Plan normal = RoadGuide.plan(List.of(new RoadGuide.Road("서해", hard.road().points(), hard.road().halves(), hard.road().parts(), 1, "본사")), 1000, 40);
        assertTrue(normal.signs().stream().noneMatch(s -> s.type() == RoadGuide.Type.WARNING));
    }

    // ------------------------------------------------------------------------------------------
    // Rewards
    // ------------------------------------------------------------------------------------------

    @Test
    void whatEachCharacterPays() {
        assertEquals(1.4, RoadRewards.fare(null, RoadCharacter.HARD), 1e-9);
        assertEquals(1.4, RoadRewards.fare(RoadCharacter.HARD, RoadCharacter.NORMAL), 1e-9);
        assertEquals(1, RoadRewards.fare(RoadCharacter.EASY, null), 1e-9);
        assertEquals(1, RoadRewards.fare(null, null), 1e-9);
        assertEquals(2, RoadRewards.streak(RoadCharacter.HARD), 1e-9);
        assertEquals(1, RoadRewards.streak(RoadCharacter.EASY), 1e-9);
        assertEquals(2, RoadRewards.weight("hiker", RoadCharacter.HARD), 1e-9);
        assertEquals(2, RoadRewards.weight("camper", RoadCharacter.HARD), 1e-9);
        assertEquals(1, RoadRewards.weight("student", RoadCharacter.HARD), 1e-9);
        assertEquals(1, RoadRewards.weight("hiker", null), 1e-9);
        assertEquals(1, RoadRewards.dock(RoadCharacter.HARD), 1e-9, "the dock's bonus: off till the owner sets it");
        assertTrue(RoadRewards.mountain(RoadCharacter.HARD) && !RoadRewards.mountain(RoadCharacter.EASY) && !RoadRewards.mountain(null));
        assertTrue(RoadRewards.titleNow(49, 50));
        assertFalse(RoadRewards.titleNow(50, 51));
        // as configured
        Map<RoadCharacter, RoadCharacter.Shape> m = new HashMap<>();
        RoadCharacter.Shape d = RoadCharacter.HARD.defaults();
        m.put(RoadCharacter.HARD, new RoadCharacter.Shape(d.gradeRun(), d.climb(), d.steep(), d.turn(), d.straighten(), d.follow(), d.bridgeGap(),
                d.tunnelCover(), d.minRun(), d.shoulder(), d.lightFactor(), d.reflectEvery(), d.railDrop(), d.bendScale(), d.rough(), d.warnings(),
                d.speedBonus(), 1.25, 3, 1.5, 1.2));
        RoadCharacter.configure(m);
        assertEquals(1.25, RoadRewards.fare(RoadCharacter.HARD, null), 1e-9);
        assertEquals(1.2, RoadRewards.dock(RoadCharacter.HARD), 1e-9);
        assertEquals(1.5, RoadRewards.weight("hiker", RoadCharacter.HARD), 1e-9);
        // out of bounds: clamped to what the builder can take
        m.put(RoadCharacter.HARD, new RoadCharacter.Shape(1, -1, -1, -1, 9, -3, 99, -5, 0, 7, 0, 0, -1, 0, true, true, 5, -1, -1, -1, -1));
        RoadCharacter.configure(m);
        RoadCharacter.Shape c = RoadCharacter.HARD.shape();
        assertEquals(2, c.gradeRun(), 1e-9);
        assertTrue(c.shoulder() >= -1 && c.shoulder() <= 1 && c.follow() >= 0 && c.bridgeGap() <= 12 && c.tunnelCover() >= 3);
        assertTrue(c.bendScale() >= RoadCharacter.MIN_BEND);
        assertEquals(0, c.fare(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // Hard roads, driven
    // ------------------------------------------------------------------------------------------

    record BuiltRoad(RoadBendTest.Terra land, RoadBendTest.Road road) {}

    /** A road built as RoadModule builds it for this character (null: as before characters, through the old calls). */
    static BuiltRoad build(RoadBendTest.Terra land, List<double[]> corners, RoadCharacter c) {
        List<double[]> pts = RoadPlanner.densify(corners, 1);
        int n = pts.size();
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            int[] s = land.sample((int) Math.floor(pts.get(i)[0]), (int) Math.floor(pts.get(i)[1]));
            ground[i] = s[0];
            water[i] = s[1] == 1;
            surface[i] = s[2];
        }
        int[] cross = land.net.crossings(pts);
        RoadProfile.Plan plan;
        RoadProfile.Part[] parts;
        boolean[] fixed = new boolean[n];
        if (c == null) {
            plan = RoadProfile.planNetwork(ground, water, surface, cross, land.net.headroom(pts));
            for (int i = 0; i < n; i++) fixed[i] = plan.over()[i] || cross[i] != Integer.MIN_VALUE;
            RoadProfile.levelBends(pts, plan.halves(), fixed);
            parts = RoadProfile.partsHalves(plan.halves(), ground, water, RoadModule.BRIDGE_GAP, RoadModule.TUNNEL_COVER, RoadModule.MIN_RUN);
        } else {
            RoadCharacter.Shape sh = c.shape();
            plan = RoadProfile.planNetwork(ground, water, surface, cross, land.net.headroom(pts), sh.grade(), sh.follow());
            for (int i = 0; i < n; i++) fixed[i] = plan.over()[i] || cross[i] != Integer.MIN_VALUE;
            RoadProfile.levelBends(pts, plan.halves(), fixed, sh.grade());
            parts = RoadProfile.partsHalves(plan.halves(), ground, water, sh.bridgeGap(), sh.tunnelCover(), sh.minRun());
        }
        for (int i = 0; i < n; i++) if (plan.over()[i]) parts[i] = RoadProfile.Part.BRIDGE;
        RoadSection sec = new RoadSection(land, RoadBendTest.STYLE, pts, plan.halves(), parts, "a", 1).network(cross, plan.over());
        if (c != null) sec.character(c, ground);
        for (int i = 0; i < n; i++) assertNull(sec.build(i));
        return new BuiltRoad(land, new RoadBendTest.Road(land, pts, plan.halves(), parts, sec, "a"));
    }

    /** Steep ground: 1 in 1.6 up along x from x = 20. */
    static final RoadBendTest.Ground STEEP = (x, z) -> (int) Math.floor(63 + Math.clamp((x - 20) * 0.6, 0, 30));
    /** Rolling ground: crests and dips 8 high, 48 apart. */
    static final RoadBendTest.Ground ROLL = (x, z) -> (int) Math.floor(67 + 4 * Math.sin(x * 2 * Math.PI / 48));
    /** Short crests and dips: 6 high, 18 apart. */
    static final RoadBendTest.Ground BUMPY = (x, z) -> (int) Math.floor(66 + 3 * Math.sin(x * 2 * Math.PI / 18));
    /** A mountainside rising in z (1 in 1.4): a switchback climbs it. */
    static final RoadBendTest.Ground SIDE = (x, z) -> (int) Math.floor(63 + Math.clamp(z * 0.7, 0, 40));

    /** The hard shapes: hairpins, S-bends, steep grades with bends, crests and dips. */
    static Map<String, BuiltRoad> hardShapes() {
        Map<String, BuiltRoad> out = new LinkedHashMap<>();
        RoadCharacter h = RoadCharacter.HARD;
        out.put("hairpin", build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 70, 0, 70, 24, 0, 24), h));
        out.put("switchback", build(new RoadBendTest.Terra(SIDE, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 70, 0, 80, 16, 70, 32, 0, 32, -10, 48, 0, 64, 70, 64), h));
        out.put("S-bends", build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 40, 0, 56, 16, 56, 32, 72, 48, 88, 48, 104, 32, 140, 32), h));
        out.put("steep straight", build(new RoadBendTest.Terra(STEEP, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 140, 0), h));
        out.put("steep 90", build(new RoadBendTest.Terra(STEEP, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 60, 0, 60, 80), h));
        out.put("crests and dips", build(new RoadBendTest.Terra(ROLL, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 200, 0), h));
        out.put("rolling bends", build(new RoadBendTest.Terra(ROLL, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 50, 0, 90, 40, 130, 0, 180, 0), h));
        out.put("hill square", build(new RoadBendTest.Terra(RoadBendTest.HILL, RoadBendTest.DRY), RoadBendTest.corners(0, 22, 98, 22, 98, 110), h));
        return out;
    }

    @Test
    void hardRoadsAreSteeperAndFollowTheGround() {
        BuiltRoad normal = build(new RoadBendTest.Terra(STEEP, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 140, 0), RoadCharacter.NORMAL);
        BuiltRoad hard = build(new RoadBendTest.Terra(STEEP, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 140, 0), RoadCharacter.HARD);
        assertTrue(steepest(hard.road().halves(), 15) > steepest(normal.road().halves(), 15), "steeper: " + steepest(hard.road().halves(), 15)
                + " vs " + steepest(normal.road().halves(), 15));
        BuiltRoad rollN = build(new RoadBendTest.Terra(BUMPY, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 200, 0), RoadCharacter.NORMAL);
        BuiltRoad rollH = build(new RoadBendTest.Terra(BUMPY, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 200, 0), RoadCharacter.HARD);
        assertTrue(range(rollH.road().halves()) > range(rollN.road().halves()), "crests and dips: " + range(rollH.road().halves()) + " vs "
                + range(rollN.road().halves()));
    }

    static int steepest(int[] h, int over) {
        int m = 0;
        for (int i = 0; i + over < h.length; i++) m = Math.max(m, Math.abs(h[i + over] - h[i]));
        return m;
    }

    /** How far up and down it goes, all told (half blocks), but for its ends. */
    static int range(int[] h) {
        int s = 0;
        for (int i = 21; i < h.length - 20; i++) s += Math.abs(h[i] - h[i - 1]);
        return s;
    }

    /** The buses and the box truck: every long vehicle the roads are for. */
    static final List<String> LONG = List.of("limousine_bus", "village_bus", "mini_bus", "box_truck");

    @Test
    void aCarefulDriverGetsThroughEveryHardShapeAndAFastSloppyOneScrapes() {
        Map<String, BuiltRoad> shapes = hardShapes();
        List<String> bad = new ArrayList<>();
        int rough = 0;
        for (var e : shapes.entrySet()) {
            RoadBendTest.Road r = e.getValue().road();
            assertEquals(List.of(), RoadBendTest.inTheWay(r, r.section()), e.getKey() + ": nothing in the hard road's reach");
            for (String id : LONG) {
                VehicleSpec s = all.get(id);
                assertNotNull(s, id);
                for (double lat : new double[]{1.25, -1.25}) {
                    for (boolean back : new boolean[]{false, true}) {
                        // careful: on his lane, 4.5 b/s, 4 round a bend
                        RoadBendTest.Drive d = RoadBendTest.drive(s, r, back, k -> lat, 4.5, 4.0, false);
                        if (!d.clean()) bad.add(e.getKey() + " " + id + " " + lat + (back ? " back" : "") + ": " + d);
                    }
                }
            }
            // fast and sloppy: full speed, on the edge line, steering late
            boolean scraped = false;
            for (String id : List.of("limousine_bus", "village_bus")) {
                for (double lat : new double[]{2.0, -2.0}) {
                    RoadBendTest.Drive d = RoadBendTest.drive(all.get(id), r, false, k -> lat, 10, 10, true);
                    scraped |= !d.clean();
                }
            }
            if (scraped) rough++;
        }
        assertEquals(List.of(), bad, "a careful driver gets through every hard shape");
        assertTrue(rough >= 3, "a fast sloppy driver scrapes or bumps on several hard shapes (the switchback, the steep 90, the rolling bends, "
                + "round the hill), shapes: " + rough);
    }
}
