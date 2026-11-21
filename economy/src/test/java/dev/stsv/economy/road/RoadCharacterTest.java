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
 * 국도 성격 (README "국도", 성격): 쉬운길 / 보통 / 어려운길. The character read and saved (an older road: legacy 보통, kept to
 * the shape it was built with), what each one plans and builds with (legacy exactly as roads always were; 보통 between
 * that and 어려운길), what it pays, and the normal and hard roads driven by the long vehicles' own physics (RoadBendTest's
 * harness): a careful driver (on his lane, at most 4.5 b/s on a hard road, 5 on a normal one) gets through every shape
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
        assertEquals(RoadCharacter.LEGACY, RoadCharacter.ofId(null));
        assertEquals(RoadCharacter.LEGACY, RoadCharacter.ofId("무엇"));
        assertEquals(RoadCharacter.LEGACY, RoadCharacter.ofId("legacy"));
        assertEquals(RoadCharacter.NORMAL, RoadCharacter.ofId("normal"));
        assertNull(RoadCharacter.parse("legacy"), "never chosen");
    }

    @Test
    void savedWithTheRoadAndOldRoadsAreLegacyNormal() {
        RoadJob j = new RoadJob();
        j.dock = "서해";
        j.world = "world";
        j.corners.add(new double[]{0.5, 0.5});
        j.corners.add(new double[]{40.5, 0.5});
        assertEquals(RoadCharacter.NORMAL, j.character, "a new road: today's normal");
        assertTrue(j.write().contains("character=normal\n"), "a new normal road says so");
        assertEquals(RoadCharacter.NORMAL, RoadJob.read(j.write()).character);
        j.character = RoadCharacter.LEGACY;
        assertFalse(j.write().contains("character="), "an old normal road's file is as it always was");
        assertEquals(RoadCharacter.LEGACY, RoadJob.read(j.write()).character);
        j.character = RoadCharacter.HARD;
        String text = j.write();
        assertTrue(text.contains("character=hard\n"));
        assertEquals(RoadCharacter.HARD, RoadJob.read(text).character);
        assertEquals(RoadCharacter.HARD, j.snapshot().character);
        j.character = RoadCharacter.EASY;
        assertEquals(RoadCharacter.EASY, RoadJob.read(j.write()).character);
        // a road saved before characters (or before normal got harder): 보통 as it was built
        String old = "dock=서해\nworld=world\nstart=0,0\nend=40,0\nphase=DONE\nrunning=false\ncursor=0\nstarted=0\nroute=3\nsigns=1\n";
        RoadJob legacy = RoadJob.read(old);
        assertEquals(RoadCharacter.LEGACY, legacy.character);
        assertEquals(RoadCharacter.NORMAL, legacy.character.label());
        assertEquals("보통", legacy.character.word);
        // continuing an old road keeps its old shape
        legacy.corners.add(new double[]{0.5, 0.5});
        legacy.corners.add(new double[]{40.5, 0.5});
        for (int i = 0; i < legacy.points().size(); i++) legacy.ground.add(new RoadJob.Ground());
        assertEquals(RoadCharacter.LEGACY, RoadModule.tailJob(legacy, new int[]{60, 64, 0}).character);
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
        assertEquals(RoadCharacter.LEGACY, n.character(50, 50), "a road without a character: as built before");
        assertNull(n.character(50, 25));
        assertEquals(RoadCharacter.HARD, n.characterNear(50, 8, RoadRewards.STOP_REACH), "a stop's bay beside the road");
        assertNull(n.characterNear(50, 25, RoadRewards.STOP_REACH));
        // the speed bonus: an easy road's more, the others as roads.speed-bonus
        assertEquals(1.2, RoadModule.speedBonus(1.2, RoadCharacter.NORMAL), 1e-9);
        assertEquals(1.2, RoadModule.speedBonus(1.2, RoadCharacter.LEGACY), 1e-9);
        assertEquals(1.2, RoadModule.speedBonus(1.2, null), 1e-9);
        assertEquals(1.2, RoadModule.speedBonus(1.2, RoadCharacter.HARD), 1e-9);
        assertEquals(1.35, RoadModule.speedBonus(1.2, RoadCharacter.EASY), 1e-9);
        assertEquals(2.0, RoadModule.speedBonus(1.95, RoadCharacter.EASY), 1e-9, "never over 2");
    }

    // ------------------------------------------------------------------------------------------
    // What each character plans and builds with
    // ------------------------------------------------------------------------------------------

    @Test
    void legacyPlansAndBuildsAsRoadsAlwaysDid() {
        RoadCharacter.Shape n = RoadCharacter.LEGACY.shape();
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
        assertEquals(1, n.bendScale(), 1e-9);
        // the explicit old numbers (what 보통 was): 1:3, climb 0.02, steep 0.6, straighten 2%, follow 4, bridge 3, tunnel 5,
        // lights ×1, reflectors 20, bend room 1
        assertEquals(new RoadCharacter.Shape(3, 0.02, 0.6, 0, 1.02, 4, 3, 5, 4, 0, 1, 20, 3, 1, false, false, 0, 1, 1, 1, 1), n);
        // the config cannot change how a legacy road is built; it pays (and speeds) as normal is configured
        Map<RoadCharacter, RoadCharacter.Shape> m = new HashMap<>();
        m.put(RoadCharacter.LEGACY, RoadCharacter.HARD.defaults());
        RoadCharacter.Shape d = RoadCharacter.NORMAL.defaults();
        m.put(RoadCharacter.NORMAL, new RoadCharacter.Shape(d.gradeRun(), d.climb(), d.steep(), d.turn(), d.straighten(), d.follow(), d.bridgeGap(),
                d.tunnelCover(), d.minRun(), d.shoulder(), d.lightFactor(), d.reflectEvery(), d.railDrop(), d.bendScale(), d.rough(), d.warnings(),
                0.1, 1.3, 1.5, 1.1, 1.2));
        RoadCharacter.configure(m);
        RoadCharacter.Shape l = RoadCharacter.LEGACY.shape();
        assertEquals(RoadPlanner.Weights.DEFAULT, l.weights());
        assertEquals(0, l.shoulder());
        assertEquals(1, l.bendScale(), 1e-9);
        assertEquals(1.3, l.fare(), 1e-9);
        assertEquals(1.2, l.dock(), 1e-9);
        assertEquals(1.3, RoadRewards.fare(RoadCharacter.LEGACY, null), 1e-9);
        assertEquals(1.3, RoadModule.speedBonus(1.2, RoadCharacter.LEGACY), 1e-9);
        RoadCharacter.configure(null);
        // the same road built through the old calls (before characters) and as legacy: block for block the same
        BuiltRoad a = build(new RoadBendTest.Terra(RoadBendTest.HILL, RoadBendTest.DRY), RoadBendTest.corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 100), null);
        BuiltRoad b = build(new RoadBendTest.Terra(RoadBendTest.HILL, RoadBendTest.DRY), RoadBendTest.corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 100),
                RoadCharacter.LEGACY);
        assertEquals(a.land().put, b.land().put);
        // ... and a new normal road is not
        BuiltRoad c = build(new RoadBendTest.Terra(RoadBendTest.HILL, RoadBendTest.DRY), RoadBendTest.corners(0, 30, 35, 30, 60, 5, 85, 30, 85, 100),
                RoadCharacter.NORMAL);
        assertFalse(a.land().put.equals(c.land().put));
    }

    @Test
    void normalSitsBetweenTheOldRoadsAndHard() {
        RoadCharacter.Shape n = RoadCharacter.NORMAL.shape(), l = RoadCharacter.LEGACY.shape(), h = RoadCharacter.HARD.shape();
        assertEquals(new RoadCharacter.Shape(2.75, 0.015, 0.45, 0, 1.01, 3, 4, 6, 4, 0, 1.25, 24, 3, 0.8, false, false, 0, 1, 1, 1, 1), n);
        assertEquals(new RoadProfile.Grade(11, 8), n.grade(), "4 blocks in 11");
        assertEquals(50, n.lightEvery(40));
        // steeper, closer to the ground, tighter round bends than the old roads; hard more so still
        assertTrue(h.gradeRun() < n.gradeRun() && n.gradeRun() < l.gradeRun());
        assertTrue(h.climb() < n.climb() && n.climb() < l.climb() && h.steep() < n.steep() && n.steep() < l.steep());
        assertTrue(h.straighten() < n.straighten() && n.straighten() < l.straighten());
        assertTrue(h.follow() < n.follow() && n.follow() < l.follow());
        assertTrue(l.tunnelCover() < n.tunnelCover() && n.tunnelCover() < h.tunnelCover() && l.bridgeGap() < n.bridgeGap() && n.bridgeGap() < h.bridgeGap());
        assertTrue(h.bendScale() < n.bendScale() && n.bendScale() < l.bendScale());
        assertTrue(l.lightEvery(40) < n.lightEvery(40) && n.lightEvery(40) < h.lightEvery(40));
        assertTrue(l.reflectEvery() < n.reflectEvery());
        // unchanged: the shoulder, the guardrails, the speed bonus, what it pays
        assertEquals(l.shoulder(), n.shoulder());
        assertEquals(l.railDrop(), n.railDrop());
        assertEquals(0, n.speedBonus(), 1e-9);
        assertEquals(1.2, RoadModule.speedBonus(1.2, RoadCharacter.NORMAL), 1e-9);
        assertEquals(1, RoadRewards.fare(RoadCharacter.NORMAL, RoadCharacter.NORMAL), 1e-9);
        assertEquals(1, RoadRewards.streak(RoadCharacter.NORMAL), 1e-9);
        // configurable like the others (roads.characters.normal.*), kept in bounds
        Map<RoadCharacter, RoadCharacter.Shape> m = new HashMap<>();
        m.put(RoadCharacter.NORMAL, l);
        RoadCharacter.configure(m);
        assertEquals(RoadPlanner.Weights.DEFAULT, RoadCharacter.NORMAL.shape().weights(), "the owner can set normal back to the old numbers");
        assertEquals(40, RoadCharacter.NORMAL.shape().lightEvery(40));
        m.put(RoadCharacter.NORMAL, new RoadCharacter.Shape(1, -1, -1, -1, 9, -3, 99, -5, 0, 7, 0, 0, -1, 0, true, true, 5, -1, -1, -1, -1));
        RoadCharacter.configure(m);
        assertEquals(2, RoadCharacter.NORMAL.shape().gradeRun(), 1e-9);
        assertTrue(RoadCharacter.NORMAL.shape().bendScale() >= RoadCharacter.MIN_BEND);
        assertEquals(RoadCharacter.LEGACY_SHAPE.gradeRun(), RoadCharacter.LEGACY.shape().gradeRun(), 1e-9, "legacy never follows");
    }

    @Test
    void eachCharactersPlannerAndGrade() {
        assertEquals(new RoadProfile.Grade(3, 2), RoadProfile.Grade.of(3));
        assertEquals(new RoadProfile.Grade(5, 2), RoadProfile.Grade.of(5));
        assertEquals(new RoadProfile.Grade(5, 4), RoadProfile.Grade.of(2.5));
        RoadCharacter.Shape e = RoadCharacter.EASY.shape(), h = RoadCharacter.HARD.shape(), n = RoadCharacter.NORMAL.shape();
        assertEquals(new RoadProfile.Grade(5, 2), e.grade());
        assertEquals(new RoadProfile.Grade(9, 8), h.grade());
        assertTrue(e.weights().turn() > 0 && n.weights().turn() == 0 && h.weights().turn() == 0);
        assertTrue(e.weights().steep() > n.weights().steep() && h.weights().steep() < n.weights().steep());
        assertTrue(e.weights().climb() > n.weights().climb() && h.weights().climb() < n.weights().climb());
        assertTrue(h.tunnelCover() > n.tunnelCover() && h.bridgeGap() > n.bridgeGap(), "a hard road keeps off bridges and tunnels");
        assertTrue(e.tunnelCover() < n.tunnelCover(), "an easy road bores rather than climbs");
        assertEquals(30, e.lightEvery(40));
        assertEquals(120, h.lightEvery(40));

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
        // legacy: the same as roads always were without a turn cost
        List<int[]> pl = RoadPlanner.path(f, 0, 0, 30, 12, RoadCharacter.LEGACY.shape().weights(), 100_000);
        assertEquals(cells(RoadPlanner.path(f, 0, 0, 30, 12, RoadPlanner.Weights.DEFAULT, 100_000)), cells(pl));
        assertTrue(climb(f, pn) >= climb(f, pl), "normal " + climb(f, pn) + " vs legacy " + climb(f, pl));
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

    @Test
    void detailsAlongTheRoad() {
        // a long straight road (km posts) and a short one with a sharp bend (chevrons, rumble strips)
        for (RoadCharacter c : RoadCharacter.values()) {
            BuiltRoad longRoad = build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 2100, 0), c);
            RoadBendTest.Road lr = longRoad.road();
            List<String> km = RoadDetail.of(new RoadGuide.Road("서해", lr.points(), lr.halves(), lr.parts(), 7, "본사", c, null), lr.parts(),
                    lr.section().signs().keySet(), 40).stream().filter(l -> l.style() == RoadDetail.Style.KM_POST).map(RoadDetail.Label::text).toList();
            String line = (c == RoadCharacter.EASY ? "고속국도" : "국도") + " 7호선 · ";
            assertEquals(List.of(line + "1km", line + "2km"), km, c.word);
            BuiltRoad b = build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 150, 0, 150, 150), c);
            RoadBendTest.Road r = b.road();
            List<RoadDetail.Label> ls = RoadDetail.of(new RoadGuide.Road("서해", r.points(), r.halves(), r.parts(), 7, "본사", c, null), r.parts(),
                    r.section().signs().keySet(), 40);
            long chevrons = ls.stream().filter(l -> l.style() == RoadDetail.Style.CHEVRON).count();
            assertTrue(chevrons >= 3, c.word + " chevrons: " + chevrons);
            for (RoadDetail.Label l : ls) {
                if (l.post() == null) continue;
                // its post clear of what the vehicles reach there
                double clear = r.section().clearance(l.at(), l.post()[0], l.post()[1]);
                assertTrue(clear >= r.section().reach(l.at()) - 1e-9, c.word + " " + l.text() + " at " + l.at() + ": " + clear + " < " + r.section().reach(l.at()));
                if (l.style() == RoadDetail.Style.CHEVRON) assertEquals(">>>", l.text(), "the road turns right (south)");
            }
            // rumble strips before the bend (and after it, for the way back), none on the straight
            int strips = 0;
            for (int i = 0; i < r.points().size(); i++) if (r.section().rumble(i)) {
                strips++;
                assertTrue(Math.abs(i - 150) < 60, "a strip near the bend only: " + i);
            }
            assertTrue(strips >= 10, "strips: " + strips);
            assertTrue(b.land().put.containsValue(Material.DEEPSLATE_TILES));
            Map<Long, Material> all = new HashMap<>(b.land().put);
            all.putAll(longRoad.land().put);
            boolean poles = all.containsValue(Material.RED_NETHER_BRICK_WALL), flowers = all.containsValue(Material.POPPY)
                    || all.containsValue(Material.DANDELION) || all.containsValue(Material.CORNFLOWER) || all.containsValue(Material.AZURE_BLUET);
            assertEquals(c == RoadCharacter.HARD, poles, c.word + " snow poles");
            assertEquals(c == RoadCharacter.EASY, flowers, c.word + " flowers");
            assertEquals(List.of(), RoadBendTest.inTheWay(r, r.section()), c.word);
        }
        BuiltRoad lake = build(new RoadBendTest.Terra(RoadBendTest.LAKE_BED, RoadBendTest.LAKE), RoadBendTest.corners(0, 30, 120, 30), RoadCharacter.NORMAL);
        List<String> names = RoadDetail.of(new RoadGuide.Road("서해", lake.road().points(), lake.road().halves(), lake.road().parts(), 1, "본사"),
                lake.road().parts(), java.util.Set.of(), 40).stream().filter(l -> l.style() == RoadDetail.Style.NAME).map(RoadDetail.Label::text).toList();
        assertEquals(List.of("서해1교", "서해1교"), names, "a plate at either end of the bridge");
        BuiltRoad hill = build(new RoadBendTest.Terra(RoadBendTest.MOUNT, RoadBendTest.DRY), RoadBendTest.corners(0, 40, 140, 40), RoadCharacter.NORMAL);
        List<String> bores = RoadDetail.of(new RoadGuide.Road("동해", hill.road().points(), hill.road().halves(), hill.road().parts(), 1, "본사"),
                hill.road().parts(), java.util.Set.of(), 40).stream().filter(l -> l.style() == RoadDetail.Style.NAME).map(RoadDetail.Label::text).toList();
        assertEquals(List.of("동해1터널", "동해1터널"), bores, "over either portal");
    }

    /** A causeway: the road on a bank 8 over the ground from 4 out (every shoulder a drop: guardrails all along). */
    static final RoadBendTest.Ground CAUSEWAY = (x, z) -> Math.abs(z) >= 4 && Math.abs(x - 75) >= 4 ? 55 : 63;

    static Map<Material, Integer> count(Map<Long, Material> m) {
        Map<Material, Integer> out = new HashMap<>();
        for (Material x : m.values()) out.merge(x, 1, Integer::sum);
        return out;
    }

    @Test
    void guardrailsOfEachCharacterAndTheirRestyle() {
        Map<RoadCharacter, Map<Material, Integer>> rails = new HashMap<>();
        for (RoadCharacter c : RoadCharacter.values()) {
            BuiltRoad b = build(new RoadBendTest.Terra(CAUSEWAY, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 150, 0), c);
            rails.put(c, count(b.land().put));
            assertEquals(List.of(), RoadBendTest.inTheWay(b.road(), b.road().section()), c.word);
        }
        Map<Material, Integer> n = rails.get(RoadCharacter.NORMAL), e = rails.get(RoadCharacter.EASY), h = rails.get(RoadCharacter.HARD);
        assertTrue(n.getOrDefault(Material.ANDESITE_WALL, 0) > 100 && n.getOrDefault(Material.WHITE_CARPET, 0) > 100, "normal: andesite, white band " + n);
        assertTrue(e.getOrDefault(Material.POLISHED_BLACKSTONE_WALL, 0) > 100 && e.getOrDefault(Material.IRON_TRAPDOOR, 0) > 80
                && e.getOrDefault(Material.YELLOW_CARPET, 0) >= 10 && !e.containsKey(Material.ANDESITE_WALL), "easy: steel " + e);
        int fence = h.getOrDefault(Material.DARK_OAK_FENCE, 0);
        assertTrue(fence > 60 && fence < n.get(Material.ANDESITE_WALL) * 0.8 && !h.containsKey(Material.ANDESITE_WALL), "hard: fence with gaps " + h);

        // a bend's outside on a hard road: yellow and black stripes (drop or not)
        BuiltRoad bend = build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 100, 0, 100, 100), RoadCharacter.HARD);
        Map<Material, Integer> bc = count(bend.land().put);
        assertTrue(bc.getOrDefault(Material.YELLOW_TERRACOTTA, 0) > 0 && bc.getOrDefault(Material.BLACK_TERRACOTTA, 0) > 0, "stripes " + bc);
        assertEquals(List.of(), RoadBendTest.inTheWay(bend.road(), bend.road().section()));

        // 가드레일교체: an old (normal) road's guardrails restyled to hard and to easy in place, nothing else; twice is once
        for (RoadCharacter to : List.of(RoadCharacter.HARD, RoadCharacter.EASY)) {
            BuiltRoad old = build(new RoadBendTest.Terra(CAUSEWAY, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 150, 0), RoadCharacter.LEGACY);
            RoadBendTest.Terra land = old.land();
            Map<Long, Material> before = new HashMap<>(land.put);
            RoadSection sec = new RoadSection(land, RoadBendTest.STYLE, old.road().points(), old.road().halves(), old.road().parts(), "a", 1)
                    .character(to, null);
            land.log = new ArrayList<>();
            int changed = 0;
            for (int i = 0; i < old.road().points().size(); i++) changed += sec.restyleRails(i);
            assertTrue(changed > 100, to.word + ": " + changed);
            for (int[] p : land.log) {
                Material was = before.getOrDefault(RoadBendTest.Terra.key(p[0], p[1], p[2]), land.base(p[0], p[1], p[2]));
                assertTrue(RoadSection.RAIL_BLOCKS.contains(was) || RoadSection.RAIL_CAPS.contains(was) || was == Material.AIR,
                        to.word + ": only guardrails and their caps, not " + was + " at " + java.util.Arrays.toString(p));
            }
            Map<Material, Integer> after = count(land.put);
            assertFalse(after.containsKey(Material.ANDESITE_WALL) || after.containsKey(Material.WHITE_CARPET), to.word + " " + after);
            int again = 0;
            for (int i = 0; i < old.road().points().size(); i++) again += sec.restyleRails(i);
            assertEquals(0, again, "twice is once");
            assertEquals(List.of(), RoadBendTest.inTheWay(new RoadBendTest.Road(land, old.road().points(), old.road().halves(), old.road().parts(), sec, "a"), sec));
        }
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
        assertEquals(1, RoadRewards.fare(RoadCharacter.LEGACY, RoadCharacter.NORMAL), 1e-9);
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

    /**
     * The hard shapes: hairpins, S-bends, steep grades with bends, crests and dips; and on a hard road, the tighter ones
     * it is for: hairpins up a steep mountainside, a hairpin 20 across, a zigzag of right angles.
     */
    static Map<String, BuiltRoad> hardShapes() {
        Map<String, BuiltRoad> out = shapes(RoadCharacter.HARD);
        RoadCharacter h = RoadCharacter.HARD;
        out.put("steep hairpins", build(new RoadBendTest.Terra(SIDE, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 60, 0, 60, 18, 0, 18, 0, 36, 60, 36), h));
        out.put("tight hairpin", build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 60, 0, 60, 20, 0, 20), h));
        out.put("zigzag", build(new RoadBendTest.Terra(RoadBendTest.FLAT, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 30, 0, 42, 12, 30, 24, 42, 36, 30, 48, 60, 48), h));
        return out;
    }

    /** The hard shapes built as roads of this character. */
    static Map<String, BuiltRoad> shapes(RoadCharacter h) {
        Map<String, BuiltRoad> out = new LinkedHashMap<>();
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
        // and normal is steeper and follows the ground more than the old roads did
        BuiltRoad legacy = build(new RoadBendTest.Terra(STEEP, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 140, 0), RoadCharacter.LEGACY);
        assertTrue(steepest(normal.road().halves(), 15) > steepest(legacy.road().halves(), 15), "steeper: " + steepest(normal.road().halves(), 15)
                + " vs " + steepest(legacy.road().halves(), 15));
        // over rolling ground it keeps closer to the ground (less cut and fill) than the old roads, hard closer still
        Map<RoadCharacter, Double> off = new HashMap<>();
        for (RoadCharacter c : List.of(RoadCharacter.LEGACY, RoadCharacter.NORMAL, RoadCharacter.HARD))
            off.put(c, off(build(new RoadBendTest.Terra(ROLL, RoadBendTest.DRY), RoadBendTest.corners(0, 0, 200, 0), c), ROLL));
        assertTrue(off.get(RoadCharacter.HARD) < off.get(RoadCharacter.NORMAL) && off.get(RoadCharacter.NORMAL) < off.get(RoadCharacter.LEGACY) * 0.9,
                "cut and fill: " + off);
    }

    /** How far the road's surface stands off the ground under its middle, all told (blocks), but for its ends. */
    static double off(BuiltRoad b, RoadBendTest.Ground g) {
        double s = 0;
        int[] h = b.road().halves();
        for (int i = 20; i < h.length - 20; i++) {
            double[] p = b.road().points().get(i);
            s += Math.abs(RoadProfile.block(h[i]) - g.top((int) Math.floor(p[0]), (int) Math.floor(p[1])));
        }
        return s;
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

    /**
     * 어려운길 at the edge of passable: every long vehicle driven carefully (on its lane, 4 b/s, 3.5 round a bend, both ways)
     * gets through every hard shape without getting stuck or touching anything; one at a normal speed (7 b/s) steering
     * late gets stuck or scrapes on several (the hairpins, the steep 90, the rolling bends, round the hill); a fast sloppy
     * one on the edge line on more still.
     */
    @Test
    void aCarefulDriverGetsThroughEveryHardShapeAndAFastSloppyOneScrapes() {
        Map<String, BuiltRoad> shapes = hardShapes();
        List<String> bad = new ArrayList<>();
        java.util.Set<String> normal = new java.util.TreeSet<>(), rough = new java.util.TreeSet<>();
        for (var e : shapes.entrySet()) {
            RoadBendTest.Road r = e.getValue().road();
            assertEquals(List.of(), RoadBendTest.inTheWay(r, r.section()), e.getKey() + ": nothing in the hard road's reach");
            for (String id : LONG) {
                VehicleSpec s = all.get(id);
                assertNotNull(s, id);
                for (double lat : new double[]{1.25, -1.25}) {
                    for (boolean back : new boolean[]{false, true}) {
                        // careful: on his lane, 4 b/s, 3.5 round a bend
                        RoadBendTest.Drive d = RoadBendTest.drive(s, r, back, k -> lat, 4.0, 3.5, false);
                        if (!d.clean()) bad.add(e.getKey() + " " + id + " " + lat + (back ? " back" : "") + ": " + d);
                        // a normal speed, not slowing for the bends, steering late
                        if (!RoadBendTest.drive(s, r, back, k -> lat, 7, 7, true).clean()) normal.add(e.getKey());
                    }
                }
            }
            // fast and sloppy: full speed, on the edge line, steering late
            for (String id : List.of("limousine_bus", "village_bus")) {
                for (double lat : new double[]{2.0, -2.0}) {
                    RoadBendTest.Drive d = RoadBendTest.drive(all.get(id), r, false, k -> lat, 10, 10, true);
                    if (!d.clean()) rough.add(e.getKey());
                }
            }
        }
        assertEquals(List.of(), bad, "a careful driver gets through every hard shape");
        assertTrue(normal.size() >= 5, "at 7 b/s a driver gets stuck or scrapes on " + normal);
        assertTrue(rough.size() >= 5, "a fast sloppy driver scrapes or bumps on " + rough);
    }

    /**
     * 보통's tighter bends (bend room 0.8) and steeper grade (1:2.75) on the hard shapes: every long vehicle driven carefully
     * (on its lane, 5 b/s, both ways) gets through without getting stuck or touching anything; a fast sloppy one may not.
     */
    @Test
    void aCarefulDriverGetsThroughEveryNormalShape() {
        Map<String, BuiltRoad> shapes = shapes(RoadCharacter.NORMAL);
        List<String> bad = new ArrayList<>();
        List<String> rough = new ArrayList<>();
        for (var e : shapes.entrySet()) {
            RoadBendTest.Road r = e.getValue().road();
            assertEquals(List.of(), RoadBendTest.inTheWay(r, r.section()), e.getKey() + ": nothing in the normal road's reach");
            for (String id : LONG) {
                VehicleSpec s = all.get(id);
                assertNotNull(s, id);
                for (double lat : new double[]{1.25, -1.25}) {
                    for (boolean back : new boolean[]{false, true}) {
                        // careful: on his lane, 5 b/s, round a bend too
                        RoadBendTest.Drive d = RoadBendTest.drive(s, r, back, k -> lat, 5.0, 5.0, false);
                        if (!d.clean()) bad.add(e.getKey() + " " + id + " " + lat + (back ? " back" : "") + ": " + d);
                    }
                }
            }
            for (String id : List.of("limousine_bus", "village_bus")) {
                for (double lat : new double[]{2.0, -2.0}) {
                    RoadBendTest.Drive d = RoadBendTest.drive(all.get(id), r, false, k -> lat, 10, 10, true);
                    if (!d.clean()) rough.add(e.getKey() + " " + id + " " + lat);
                }
            }
        }
        assertEquals(List.of(), bad, "a careful driver gets through every normal shape");
        assertFalse(rough.isEmpty(), "a fast sloppy driver scrapes or bumps somewhere (the switchback, the steep 90, the rolling bends, round the hill)");
    }
}
