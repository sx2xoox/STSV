package dev.stsv.economy.secret.garage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 폐차고의 주인: the car's true look is one per server and always one of the listed options, he counts a guess right, the
 * notebook survives being written down, the sharpest bend of a road is found (a hard road first), a car's place along
 * the road is read off the road's line, and his pace bends to the road and to the one beside him without ever stopping.
 */
class GarageTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");
    static final List<String> PAINTS = List.of("white", "pearl", "silver", "black", "red", "wine", "orange", "yellow", "green", "blue", "navy", "pink"),
            WHEELS = List.of("five", "split", "turbine", "black", "gold", "white", "disc"),
            GLOWS = List.of("cyan", "blue", "purple", "pink", "red", "orange", "green", "white");

    static YamlConfiguration yml() {
        return YamlConfiguration.loadConfiguration(PACK.resolve("secrets/garage.yml").toFile());
    }

    /** A road of straight legs: from (0, 0), each leg {dx, dz, blocks}, one point a block. */
    static List<double[]> road(double[]... legs) {
        List<double[]> out = new ArrayList<>();
        double x = 0, z = 0;
        out.add(new double[]{x, z});
        for (double[] leg : legs) {
            double len = Math.hypot(leg[0], leg[1]);
            for (int i = 0; i < (int) leg[2]; i++) {
                x += leg[0] / len;
                z += leg[1] / len;
                out.add(new double[]{x, z});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // the true look
    // ------------------------------------------------------------------------------------------

    @Test
    void answerIsStableAndOneOfTheOptions() {
        UUID world = UUID.fromString("3f2b8c1e-7a4d-4e0b-9c55-1d2e3f4a5b6c");
        Combo a = Combo.answer(world, PAINTS, WHEELS, GLOWS);
        assertNotNull(a);
        assertTrue(a.whole());
        assertTrue(PAINTS.contains(a.paint()) && WHEELS.contains(a.wheel()) && GLOWS.contains(a.glow()));
        for (int i = 0; i < 5; i++) assertEquals(a, Combo.answer(world, PAINTS, WHEELS, GLOWS), "the same server, the same car");
        assertEquals(a, Combo.answer(UUID.fromString(world.toString()), new ArrayList<>(PAINTS), new ArrayList<>(WHEELS), new ArrayList<>(GLOWS)));
    }

    @Test
    void answerDiffersBetweenServersAndUsesEveryOption() {
        Set<Combo> seen = new HashSet<>();
        Set<String> paints = new HashSet<>(), wheels = new HashSet<>(), glows = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            Combo a = Combo.answer(new UUID(0x1234_5678L * i + 17, 0x9e37_79b9L * i - 5), PAINTS, WHEELS, GLOWS);
            seen.add(a);
            paints.add(a.paint());
            wheels.add(a.wheel());
            glows.add(a.glow());
        }
        assertTrue(seen.size() > 300, "servers get different cars: " + seen.size());
        assertEquals(new HashSet<>(PAINTS), paints);
        assertEquals(new HashSet<>(WHEELS), wheels);
        assertEquals(new HashSet<>(GLOWS), glows);
    }

    @Test
    void noAnswerWithoutOptions() {
        assertNull(Combo.answer(UUID.randomUUID(), List.of(), WHEELS, GLOWS));
        assertNull(Combo.answer(null, PAINTS, WHEELS, GLOWS));
    }

    @Test
    void heCountsHowManyAreHis() {
        Combo truth = new Combo("wine", "gold", "cyan");
        assertEquals(3, Combo.right(new Combo("wine", "gold", "cyan"), truth));
        assertEquals(2, Combo.right(new Combo("red", "gold", "cyan"), truth));
        assertEquals(1, Combo.right(new Combo("red", "black", "cyan"), truth));
        assertEquals(0, Combo.right(new Combo("red", "black", "blue"), truth));
        // a colour's name in the wrong place is not his: "white" the paint is not "white" the wheel
        assertEquals(0, Combo.right(new Combo("white", "white", "white"), new Combo("black", "black", "blue")));
        assertEquals(1, Combo.right(new Combo("wine", null, null), truth));
        assertEquals(0, Combo.right(Combo.NONE, truth));
        assertEquals(0, Combo.right(null, truth));
    }

    @Test
    void lookIsWrittenAndReadBack() {
        Combo c = new Combo("wine", "gold", "cyan");
        assertEquals("wine/gold/cyan", c.encode());
        assertEquals(c, Combo.decode(c.encode()));
        assertEquals(new Combo("wine", null, null), Combo.decode(Combo.NONE.with("paint", "wine").encode()));
        assertFalse(Combo.decode("wine/-/-").whole());
        assertTrue(Combo.decode(null).empty());
        assertTrue(Combo.decode("nonsense").empty());
        assertNull(Combo.decode("WINE!/gold/cyan").paint(), "not an option id");
        assertEquals("gold", c.get("wheel"));
        assertEquals(new Combo("wine", "disc", "cyan"), c.with("wheel", "disc"));
    }

    @Test
    void notebookKeepsTheLastGuesses() {
        List<Combo.Try> tries = new ArrayList<>();
        for (int i = 0; i < 30; i++) tries.add(new Combo.Try(new Combo(PAINTS.get(i % 12), WHEELS.get(i % 7), GLOWS.get(i % 8)), i % 4));
        String raw = Combo.notebook(tries, 24);
        List<Combo.Try> back = Combo.notebook(raw);
        assertEquals(24, back.size());
        assertEquals(tries.subList(6, 30), back);
        assertTrue(Combo.notebook("").isEmpty());
        assertTrue(Combo.notebook((String) null).isEmpty());
        assertEquals(1, Combo.notebook("wine/gold/cyan=2;broken;red/-/cyan=1;red/gold/cyan=9").size(), "broken lines are left out");
    }

    // ------------------------------------------------------------------------------------------
    // garage.yml
    // ------------------------------------------------------------------------------------------

    @Test
    void ymlListsOnlyWhatTheCarCanShow() {
        YamlConfiguration y = yml();
        assertEquals(PAINTS, new ArrayList<>(Bench.options(y, "paints").keySet()));
        assertEquals(WHEELS, new ArrayList<>(Bench.options(y, "wheels").keySet()));
        assertEquals(GLOWS, new ArrayList<>(Bench.options(y, "underglows").keySet()));
        for (Bench.Option o : Bench.options(y, "paints").values()) assertFalse(o.clue().isBlank() || o.name().isBlank(), "paint " + o.id());
        for (Bench.Option o : Bench.options(y, "wheels").values()) assertFalse(o.clue().isBlank() || o.name().isBlank(), "wheel " + o.id());
        // a clue describes, it never says the option's own name
        for (String section : List.of("paints", "wheels"))
            for (Bench.Option o : Bench.options(y, section).values()) assertFalse(o.clue().contains(o.name()), section + " " + o.id() + " names itself");
        assertEquals(0x46e6ff, Bench.options(y, "underglows").get("cyan").color());
        assertEquals("화이트 · 터빈 · 시안", Bench.words(y, new Combo("white", "turbine", "cyan")));
        assertEquals("economy:last_drive", y.getString("rewards.title"));
        assertEquals(0, y.getLong("rewards.money"));
        // every model the display car asks for is in the core's pack
        Path models = Path.of(System.getProperty("stsv.content")).toAbsolutePath().getParent().getParent().resolve("content/stsv/models");
        assumeTrue(Files.isDirectory(models), "the core's models are not beside this checkout");
        String body = y.getString("car.body"), wheel = y.getString("car.wheel");
        assertTrue(Files.exists(models.resolve(y.getString("car.lamp") + ".bbmodel")));
        for (String id : PAINTS) assertTrue(Files.exists(models.resolve(body + "_p_" + id + ".bbmodel")), "paint model " + id);
        for (String id : WHEELS) assertTrue(Files.exists(models.resolve(wheel + "_w_" + id + ".bbmodel")), "wheel model " + id);
        for (String id : GLOWS) assertTrue(Files.exists(models.resolve("underglow_" + id + ".bbmodel")), "glow model " + id);
    }

    // ------------------------------------------------------------------------------------------
    // the road
    // ------------------------------------------------------------------------------------------

    @Test
    void sharpestBendOfARoad() {
        // south 100, a gentle lean, then a hairpin-like right-angle to the west at point 180, then on
        List<double[]> pts = road(new double[]{0, 1, 100}, new double[]{0.3, 1, 80}, new double[]{-1, 0.05, 120});
        Roadway.Bend b = Roadway.sharpest(pts, 30, 20, pts.size() - 40);
        assertNotNull(b);
        assertEquals(180, b.index(), 1);
        assertTrue(b.degrees() > 80 && b.degrees() < 115, "about a right angle: " + b.degrees());
        // going south-ish and turning west is a turn to the right hand: the outside is the left
        assertEquals(-1, b.outside());
        assertTrue(Roadway.turn(pts, 180, 15) > 0);
        // the same road mirrored turns left: the outside is the right
        List<double[]> mirrored = new ArrayList<>();
        for (double[] p : pts) mirrored.add(new double[]{-p[0], p[1]});
        assertEquals(1, Roadway.sharpest(mirrored, 30, 20, mirrored.size() - 40).outside());
        // limits: the bend is not looked for where it is not allowed, a short road has none
        assertEquals(100, Roadway.sharpest(pts, 30, 20, 150).index(), 1);
        assertNull(Roadway.sharpest(road(new double[]{0, 1, 20}), 30, 20, 10));
        assertEquals(0, Roadway.turn(pts, 5, 15), 1e-9);
        assertEquals(0, Roadway.turn(road(new double[]{0, 1, 100}), 50, 15), 1e-9);
    }

    @Test
    void hardRoadFirst() {
        List<double[]> gentle = road(new double[]{0, 1, 150}, new double[]{0.5, 1, 150});
        List<double[]> sharp = road(new double[]{0, 1, 150}, new double[]{1, 0, 150});
        List<double[]> tiny = road(new double[]{0, 1, 40});
        // the hard road's bend is taken though another road bends more
        Roadway.Pick p = Roadway.pick(List.of(sharp, gentle, tiny), List.of(false, true, true), 30, 60, 84);
        assertEquals(1, p.road());
        assertEquals(150, p.bend().index(), 1);
        // no hard road (long enough): any road's sharpest
        assertEquals(0, Roadway.pick(List.of(sharp, gentle, tiny), List.of(false, false, true), 30, 60, 84).road());
        assertNull(Roadway.pick(List.of(tiny), List.of(true), 30, 60, 84));
        assertNull(Roadway.pick(List.of(), List.of(), 30, 60, 84));
    }

    @Test
    void besideTheRoad() {
        List<double[]> south = road(new double[]{0, 1, 100});
        // going south, the right hand is west (-x)
        double[] right = Roadway.beside(south, 50, 4.5, 4), left = Roadway.beside(south, 50, -4.5, 4);
        assertEquals(-4.5, right[0], 1e-9);
        assertEquals(50, right[1], 1e-9);
        assertEquals(4.5, left[0], 1e-9);
        assertEquals(4.5, Roadway.lateral(south, 50, -4.5, 50, 4), 1e-9);
        assertEquals(-2, Roadway.lateral(south, 50, 2, 50, 4), 1e-9);
        assertEquals(0f, Roadway.yaw(0, 1), 1e-4);      // south
        assertEquals(90f, Roadway.yaw(-1, 0), 1e-4);    // west
        assertEquals(-90f, Roadway.yaw(1, 0), 1e-4);    // east
        double[] mid = Roadway.at(south, 10.25);
        assertEquals(10.25, mid[1], 1e-9);
        assertEquals(100, Roadway.at(south, 500)[1], 1e-9);
        assertEquals(0, Roadway.at(south, -3)[1], 1e-9);
        // the tangent of a one-point road is still a direction
        assertEquals(1, Math.hypot(Roadway.tangent(List.of(new double[]{3, 3}), 0, 3)[0], Roadway.tangent(List.of(new double[]{3, 3}), 0, 3)[1]), 1e-9);
    }

    @Test
    void nearFaceOfARailBlock() {
        // going south, the right hand is west (-x): a rail block at x -5..-4 has its near face 4 blocks out, wherever along it is
        assertEquals(4, Roadway.nearFace(0, 50, -1, 0, -5, 50), 1e-9);
        assertEquals(4, Roadway.nearFace(0, 50.9, -1, 0, -5, 50), 1e-9);
        // on the left hand (+x) the block at x 4..5 is as far
        assertEquals(4, Roadway.nearFace(0, 50, 1, 0, 4, 50), 1e-9);
        // seen along a diagonal it is the block's nearest corner that counts (never further than the middle of its face)
        double d = Math.sqrt(0.5), diagonal = Roadway.nearFace(0, 0, d, d, 3, 3);
        assertEquals(Math.hypot(3, 3), diagonal, 1e-9);
        assertTrue(diagonal < Roadway.nearFace(0, 0, 1, 0, 3, 3) + 3 * d + 1e-9);
        // the memorial's back, 0.8 before that face, never reaches into the rail
        double origin = Roadway.nearFace(0.3, 12.7, -1, 0, -5, 12) - 0.8;
        assertTrue(origin + 0.8 <= 4.7 + 1e-9 && origin > 3, String.valueOf(origin));
    }

    @Test
    void placeAlongTheRoad() {
        List<double[]> pts = road(new double[]{0, 1, 100}, new double[]{1, 0, 100});
        // beside the first leg, two blocks off: as far along as its z
        assertEquals(40.3, Roadway.project(pts, -2, 40.3, 0, pts.size() - 1), 1e-6);
        // on the second leg (east from (0, 100)): 100 + x
        assertEquals(130.5, Roadway.project(pts, 30.5, 97, 0, pts.size() - 1), 1e-6);
        // before the start and past the end: the ends
        assertEquals(0, Roadway.project(pts, 0, -10, 0, pts.size() - 1), 1e-6);
        assertEquals(200, Roadway.project(pts, 150, 100, 0, pts.size() - 1), 1e-6);
        // ahead and behind: the difference of two places along the road, also round the corner
        double ghost = Roadway.project(pts, 0, 90, 0, pts.size() - 1), player = Roadway.project(pts, 12, 100, 0, pts.size() - 1);
        assertEquals(22, player - ghost, 1e-6);
        // a road that comes back beside itself: only the window is looked at
        List<double[]> hairpin = road(new double[]{0, 1, 100}, new double[]{1, 0, 6}, new double[]{0, -1, 100});
        double near = Roadway.project(hairpin, 3, 50, 30, 80), far = Roadway.project(hairpin, 3, 50, 130, 180);
        assertEquals(50, near, 1e-6);
        assertEquals(156, far, 1e-6);
        assertEquals(40, Roadway.project(hairpin, 3, 50, 40, 40), 1e-9);
    }

    @Test
    void roadHeights() {
        int[] halves = {128, 128, 129, 130};
        assertEquals(65.0, Roadway.height(halves, 0), 1e-9);
        assertEquals(65.25, Roadway.height(halves, 1.5), 1e-9);
        assertEquals(66.0, Roadway.height(halves, 9), 1e-9);
        assertEquals(64, Roadway.surfaceY(halves, 0));
        assertEquals(65, Roadway.surfaceY(halves, 2));   // a slab: its own block
        assertEquals(65, Roadway.surfaceY(halves, 99));
    }

    // ------------------------------------------------------------------------------------------
    // his pace
    // ------------------------------------------------------------------------------------------

    @Test
    void heSlowsIntoBendsAndNeverStops() {
        assertEquals(11, Roadway.cruise(0, 11, 6.5, 40), 1e-9);
        assertEquals(6.5, Roadway.cruise(40, 11, 6.5, 40), 1e-9);
        assertEquals(6.5, Roadway.cruise(-90, 11, 6.5, 40), 1e-9);
        assertEquals(8.75, Roadway.cruise(20, 11, 6.5, 40), 1e-9);
        // beside him: his own pace
        assertEquals(11, Roadway.adapt(11, 0, 3), 1e-9);
        assertEquals(11, Roadway.adapt(11, -4, 3), 1e-9);
        // lagging: he eases off, more the further back, but he does not stop
        double a = Roadway.adapt(11, -10, 3), b = Roadway.adapt(11, -20, 3), c = Roadway.adapt(11, -500, 3);
        assertTrue(a < 11 && b < a && c <= b && c >= 3, a + " " + b + " " + c);
        assertTrue(Roadway.adapt(4, -500, 3) >= 3);
        assertEquals(3, Roadway.adapt(1, 0, 3), 1e-9);
        // ahead of him: a little quicker, never racing
        double d = Roadway.adapt(11, 15, 3);
        assertTrue(d > 11 && Roadway.adapt(11, 500, 3) <= 11 * 1.2 + 1e-9, String.valueOf(d));
        // speeds change at a person's rate
        assertEquals(0.15, Roadway.step(0, 11, 3, 5, 0.05), 1e-9);
        assertEquals(10.75, Roadway.step(11, 6.5, 3, 5, 0.05), 1e-9);
        assertEquals(6.5, Roadway.step(6.6, 6.5, 3, 5, 0.05), 1e-9);
    }

    @Test
    void besideHimOrNot() {
        assertEquals(Roadway.Place.BESIDE, Roadway.place(0, 2.6, 14, 18, 22));
        assertEquals(Roadway.Place.BESIDE, Roadway.place(-13, 13.5, 14, 18, 22));
        assertEquals(Roadway.Place.FAR, Roadway.place(16, 16.2, 14, 18, 22));
        assertEquals(Roadway.Place.FAR, Roadway.place(-20, 20.5, 14, 18, 22));
        assertEquals(Roadway.Place.OUT, Roadway.place(18.5, 19, 14, 18, 22));
        assertEquals(Roadway.Place.OUT, Roadway.place(-22.5, 23, 14, 18, 22));
        assertEquals(Roadway.Place.OUT, Roadway.place(2, 40, 14, 18, 22), "far off the road sideways");
    }
}
