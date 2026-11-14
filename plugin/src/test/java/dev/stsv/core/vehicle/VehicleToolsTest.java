package dev.stsv.core.vehicle;

import dev.stsv.core.data.Db;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 작업기 (content/stsv/vehicle_tools.yml, {@link VehicleToolSpec}, {@link ToolRules}, {@link VehicleToolStore}): the shipped
 * tools read and fit their vehicles; switching, the strips they work, what they till and sow, the bed's slots and payload,
 * the server's load and the protection order.
 */
class VehicleToolsTest {

    private static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("stsv");

    private static YamlConfiguration items(String file) {
        return YamlConfiguration.loadConfiguration(PACK.resolve("items/" + file).toFile());
    }

    private static VehicleSpec vehicle(String file, String id) {
        ConfigurationSection s = items(file).getConfigurationSection(id + ".vehicle");
        assertNotNull(s, id);
        return VehicleSpec.parse(s, "stsv", m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel")));
    }

    private static Map<String, VehicleToolSpec> shipped() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("vehicle_tools.yml").toFile());
        Map<String, VehicleToolSpec> out = new java.util.LinkedHashMap<>();
        for (String id : y.getKeys(false)) {
            out.put(id, VehicleToolSpec.parse(Objects.requireNonNull(y.getConfigurationSection(id)), "stsv",
                    m -> Files.exists(PACK.resolve("models/" + m + ".bbmodel"))));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // The shipped tools
    // ------------------------------------------------------------------------------------------

    @Test
    void theShippedToolsReadAndFitTheirVehicles() {
        Map<String, VehicleToolSpec> t = shipped();
        assertEquals(List.of("ox_cart", "power_tiller", "tractor_trailer", "fishing_boat", "coastal_boat", "trawler", "light_truck", "box_truck"),
                List.copyOf(t.keySet()));
        Map<String, String> files = Map.of("ox_cart", "vehicles_farm.yml", "power_tiller", "vehicles_farm.yml", "tractor_trailer", "vehicles_farm.yml",
                "fishing_boat", "vehicles_misc.yml", "coastal_boat", "vehicles_misc.yml", "trawler", "vehicles_misc.yml",
                "light_truck", "vehicles.yml", "box_truck", "vehicles_road.yml");
        for (var e : t.entrySet()) {
            VehicleSpec v = vehicle(files.get(e.getKey()), e.getKey());
            assertEquals(List.of(), e.getValue().problems(v), e.getKey());
            // every model it shows was built (render budget: RenderBudgetTest)
            for (var m : e.getValue().models()) assertTrue(Files.exists(PACK.resolve("models/" + m.getKey().replace("model/", "") + ".bbmodel")), m.toString());
        }
        // 소달구지: the plough in the cart's place, on the cart's wheels, one row; slow
        VehicleToolSpec.Tool plough = t.get("ox_cart").tool(1);
        assertEquals(VehicleToolSpec.Kind.TILL, plough.kind());
        assertTrue(plough.replaces() && plough.keepWheels());
        assertEquals(1, plough.unit());
        assertEquals(1, plough.work().width());
        // 경운기: the rotary in the trailer's place (its wheels hidden: the rotor carries it), two rows, its tines turning
        VehicleToolSpec.Tool rotary = t.get("power_tiller").tool(1);
        assertTrue(rotary.replaces() && !rotary.keepWheels());
        assertEquals(2, rotary.work().width());
        assertEquals(VehicleToolSpec.Spin.PTO, rotary.parts().getFirst().spin());
        // 트랙터: rotary, seeder, harvester, all on the tractor itself (the trailers stay: seeds come from them, harvests go there), three rows
        VehicleToolSpec tr = t.get("tractor_trailer");
        assertEquals(List.of(VehicleToolSpec.Kind.TILL, VehicleToolSpec.Kind.SOW, VehicleToolSpec.Kind.HARVEST),
                tr.tools().stream().map(VehicleToolSpec.Tool::kind).toList());
        for (VehicleToolSpec.Tool x : tr.tools()) {
            assertEquals(0, x.unit());
            assertFalse(x.replaces());
            assertEquals(3, x.work().width());
            assertTrue(x.speed() > 0 && x.speed() < 6, "slower than the tractor's own top speed while working");
        }
        assertTrue(tr.tool(3).work().at().forward() > 1.5, "the harvester's header is in front");
        assertTrue(tr.tool(1).work().at().forward() < -1.5, "the rotary behind");
        // 통통배: the net, much slower than a rod (a rod bites every ~12 s)
        VehicleToolSpec.Tool net = t.get("fishing_boat").tool(1);
        assertEquals(VehicleToolSpec.Kind.NET, net.kind());
        assertTrue(net.seconds() >= 30);
        // the ladder: the 연안 어선 and the 트롤 어선 haul more often, still slower than a rod
        double before = net.seconds();
        for (String boat : List.of("coastal_boat", "trawler")) {
            VehicleToolSpec.Tool n = t.get(boat).tool(1);
            assertEquals(VehicleToolSpec.Kind.NET, n.kind(), boat);
            assertTrue(n.seconds() < before && n.seconds() >= 15, boat + ": " + n.seconds());
            assertTrue(n.speed() <= 3, boat + " trawls slowly");
            before = n.seconds();
        }
        // 1톤 트럭: the tarp; 탑차: only the roller door (nothing to switch)
        assertNotNull(t.get("light_truck").cover());
        assertTrue(t.get("box_truck").tools().isEmpty());
        assertNotNull(t.get("box_truck").shutter());
    }

    @Test
    void wrongToolsAreRefused() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("tools", List.of(Map.of("id", "x", "kind", "dig", "model", "stsv:a")));
        assertThrows(IllegalArgumentException.class, () -> VehicleToolSpec.parse(y, "stsv", m -> true), "an unknown kind");
        y.set("tools", List.of(Map.of("id", "x", "kind", "till", "model", "stsv:a")));
        assertThrows(IllegalArgumentException.class, () -> VehicleToolSpec.parse(y, "stsv", m -> true), "a tiller without its strip");
        y.set("tools", List.of(Map.of("id", "x", "kind", "till", "model", "stsv:a", "unit", 0, "replaces", true,
                "work", Map.of("at", List.of(0, 0, 0), "width", 1))));
        assertThrows(IllegalArgumentException.class, () -> VehicleToolSpec.parse(y, "stsv", m -> true), "the driven unit cannot be replaced");
        y.set("tools", List.of(Map.of("id", "x", "kind", "till", "model", "stsv:a", "work", Map.of("at", List.of(0, 0, 0), "width", 9))));
        assertThrows(IllegalArgumentException.class, () -> VehicleToolSpec.parse(y, "stsv", m -> true), "too wide");
        y.set("tools", List.of(Map.of("id", "x", "kind", "net", "model", "stsv:a")));
        VehicleToolSpec net = VehicleToolSpec.parse(y, "stsv", m -> true);
        assertFalse(net.problems(vehicle("vehicles_farm.yml", "tractor_trailer")).isEmpty(), "a net on a tractor");
        y.set("tools", List.of(Map.of("id", "x", "kind", "sow", "model", "stsv:a", "unit", 3, "work", Map.of("at", List.of(0, 0, 0), "width", 1))));
        assertFalse(VehicleToolSpec.parse(y, "stsv", m -> true).problems(vehicle("vehicles_farm.yml", "tractor_trailer")).isEmpty(),
                "a unit it does not have");
    }

    // ------------------------------------------------------------------------------------------
    // Switching
    // ------------------------------------------------------------------------------------------

    @Test
    void switchingGoesRoundOnlyStandingStill() {
        assertEquals(1, ToolRules.next(0, 4));
        assertEquals(3, ToolRules.next(2, 4));
        assertEquals(0, ToolRules.next(3, 4), "after the last tool: the vehicle as it comes");
        assertEquals(0, ToolRules.next(0, 1), "nothing to switch");
        assertEquals(ToolRules.Switch.MOVING, ToolRules.maySwitch(1.5, false));
        assertEquals(ToolRules.Switch.MOVING, ToolRules.maySwitch(-0.5, false), "backing up too");
        assertEquals(ToolRules.Switch.BED_OPEN, ToolRules.maySwitch(0, true));
        assertEquals(ToolRules.Switch.OK, ToolRules.maySwitch(0, false));
        // the mode kept by tool id; a tool that is no more: the vehicle as it comes
        VehicleToolSpec tr = shipped().get("tractor_trailer");
        assertEquals(3, tr.mode("harvester"));
        assertEquals(0, tr.mode("gone"));
        assertEquals(0, tr.mode(null));
        assertNull(tr.tool(0));
    }

    @Test
    void aToolInATrailersPlaceShutsOnlyThatBed() {
        Map<String, VehicleToolSpec> t = shipped();
        VehicleToolSpec.Tool rotary = t.get("power_tiller").tool(1);
        assertFalse(ToolRules.bedOpen(rotary, 1), "the trailer's bed is shut (its load stays in it)");
        assertTrue(ToolRules.bedOpen(null, 1), "no tool: open");
        VehicleToolSpec.Tool seeder = t.get("tractor_trailer").tool(2);
        assertTrue(ToolRules.bedOpen(seeder, 1) && ToolRules.bedOpen(seeder, 2), "the tractor's tools leave the trailers alone");
    }

    // ------------------------------------------------------------------------------------------
    // Working
    // ------------------------------------------------------------------------------------------

    @Test
    void itWorksOnlyDrivenForward() {
        VehicleToolSpec.Tool plough = shipped().get("ox_cart").tool(1);
        assertTrue(ToolRules.working(plough, true, 2));
        assertFalse(ToolRules.working(plough, false, 2), "nobody driving");
        assertFalse(ToolRules.working(plough, true, 0), "standing");
        assertFalse(ToolRules.working(plough, true, -1), "backing up: the plough is lifted");
        assertFalse(ToolRules.working(null, true, 2));
        VehicleToolSpec.Tool tarp = shipped().get("light_truck").cover();
        assertFalse(ToolRules.working(tarp, true, 5), "a cover does no work");
    }

    @Test
    void theNetCatchesOnlyTrawlingSlowlyAfloat() {
        VehicleToolSpec.Tool net = shipped().get("fishing_boat").tool(1);
        assertTrue(ToolRules.trawling(net, true, true, 2.5));
        assertFalse(ToolRules.trawling(net, true, true, 0), "standing still");
        assertFalse(ToolRules.trawling(net, true, true, 6), "too fast");
        assertFalse(ToolRules.trawling(net, true, false, 2), "aground or on ice");
        assertFalse(ToolRules.trawling(net, false, true, 2), "nobody driving");
        // a catch every net.seconds on average: one check a second
        double p = ToolRules.catchChance(net.seconds(), 20);
        assertEquals(1.0 / net.seconds(), p, 1e-9);
        assertTrue(3600 * p < 120, "fewer than 120 fish an hour (a rod: about 300)");
    }

    @Test
    void theStripCoversItsWidthAcrossTheUnit() {
        VehicleSpec.Vec behind = new VehicleSpec.Vec(0, 0, -2);
        // facing south (yaw 0: +z): two blocks behind is z - 2, three columns across x
        List<int[]> s = ToolRules.strip(10.5, 10.5, 0f, behind, 3);
        assertEquals(3, s.size());
        List<Integer> xs = new ArrayList<>();
        for (int[] c : s) {
            assertEquals(8, c[1]);
            xs.add(c[0]);
        }
        assertEquals(List.of(9, 10, 11), xs.stream().sorted().toList());
        // facing east (yaw -90: +x): the strip runs along z
        List<int[]> e = ToolRules.strip(10.5, 10.5, -90f, behind, 3);
        for (int[] c : e) assertEquals(8, c[0]);
        assertEquals(List.of(9, 10, 11), e.stream().map(c -> c[1]).sorted().toList());
        // one wide: the column under its point
        assertArrayEquals(new int[]{10, 8}, ToolRules.strip(10.5, 10.5, 0f, behind, 1).getFirst());
        // an even width straddles the middle: two columns
        assertEquals(2, ToolRules.strip(10.5, 10.5, 0f, new VehicleSpec.Vec(0, 0, 0), 2).size());
    }

    @Test
    void ploughsTillLikeAHoeAndNeverACrop() {
        assertTrue(ToolRules.tills(ToolRules.Ground.GRASS, ToolRules.Above.AIR));
        assertTrue(ToolRules.tills(ToolRules.Ground.DIRT, ToolRules.Above.PLANT), "short grass is cut away");
        assertTrue(ToolRules.tills(ToolRules.Ground.PATH, ToolRules.Above.AIR));
        assertFalse(ToolRules.tills(ToolRules.Ground.FARMLAND, ToolRules.Above.AIR), "already farmland");
        assertFalse(ToolRules.tills(ToolRules.Ground.DIRT, ToolRules.Above.CROP), "a crop stays");
        assertFalse(ToolRules.tills(ToolRules.Ground.GRASS, ToolRules.Above.OTHER), "a flower, a block stays");
        assertFalse(ToolRules.tills(ToolRules.Ground.OTHER, ToolRules.Above.AIR), "stone, sand, a road");
        assertTrue(ToolRules.sows(ToolRules.Ground.FARMLAND, ToolRules.Above.AIR));
        assertFalse(ToolRules.sows(ToolRules.Ground.FARMLAND, ToolRules.Above.CROP), "something grows there");
        assertFalse(ToolRules.sows(ToolRules.Ground.DIRT, ToolRules.Above.AIR), "not tilled");
    }

    @Test
    void theFirstSeedInTheBedIsSownFirst() {
        String[] bed = {null, "stone", "carrot", "wheat_seeds", null};
        assertEquals(2, ToolRules.seedSlot(bed, s -> s.equals("carrot") || s.equals("wheat_seeds")));
        assertEquals(3, ToolRules.seedSlot(bed, s -> s.equals("wheat_seeds")), "a refused kind is skipped by the caller's test");
        assertEquals(-1, ToolRules.seedSlot(bed, s -> false));
        assertEquals(-1, ToolRules.seedSlot(null, s -> true));
    }

    // ------------------------------------------------------------------------------------------
    // Into the bed
    // ------------------------------------------------------------------------------------------

    /** A stack in tests: a kind and how many. */
    record Stack(String kind, int n) {}

    private static final ToolRules.Stacks<Stack> STACKS = new ToolRules.Stacks<>() {
        public boolean similar(Stack a, Stack b) {
            return a.kind().equals(b.kind());
        }

        public int amount(Stack t) {
            return t.n();
        }

        public int max(Stack t) {
            return 64;
        }

        public Stack withAmount(Stack t, int n) {
            return new Stack(t.kind(), n);
        }
    };

    @Test
    void aHarvestFillsStacksThenEmptySlots() {
        Stack[] bed = {new Stack("wheat", 60), null, new Stack("seeds", 64), null};
        assertEquals(10, ToolRules.insert(bed, new Stack("wheat", 10), 0, 0, 100, STACKS));
        assertEquals(new Stack("wheat", 64), bed[0], "topped up first");
        assertEquals(new Stack("wheat", 6), bed[1], "the rest in the first empty slot");
        assertEquals(new Stack("seeds", 64), bed[2]);
    }

    @Test
    void aFullBedTakesWhatFitsAndNoMore() {
        Stack[] bed = {new Stack("wheat", 64), new Stack("seeds", 64)};
        assertEquals(0, ToolRules.insert(bed, new Stack("carrot", 3), 0, 0, 100, STACKS), "no slot left");
        Stack[] room = {null, null};
        assertEquals(128, ToolRules.insert(room, new Stack("carrot", 200), 0, 0, 1000, STACKS), "two stacks at most");
        assertFalse(ToolRules.roomToHarvest(new Stack[]{new Stack("a", 1), null}, 0, 100), "a harvest needs two free slots");
        assertTrue(ToolRules.roomToHarvest(new Stack[]{null, null, new Stack("a", 1)}, 0, 100));
    }

    @Test
    void thePayloadIsNeverExceeded() {
        Stack[] bed = new Stack[5];
        // one weighs 0.5, the bed carries 9.5 of its 10 already: one more fits
        assertEquals(1, ToolRules.insert(bed, new Stack("fish", 4), 0.5, 9.5, 10, STACKS));
        assertEquals(new Stack("fish", 1), bed[0]);
        assertEquals(0, ToolRules.insert(bed, new Stack("fish", 4), 0.5, 10, 10, STACKS), "full");
        assertFalse(ToolRules.roomToHarvest(new Stack[]{null, null, null}, 9.8, 10), "almost at the payload: the harvester rests");
    }

    @Test
    void sowingTakesOneSeedAtATime() {
        Stack[] bed = {new Stack("wheat_seeds", 2)};
        ToolRules.takeOne(bed, 0, STACKS);
        assertEquals(new Stack("wheat_seeds", 1), bed[0]);
        ToolRules.takeOne(bed, 0, STACKS);
        assertNull(bed[0], "the slot empties");
        ToolRules.takeOne(bed, 0, STACKS);
        assertNull(bed[0]);
    }

    // ------------------------------------------------------------------------------------------
    // The server's load, protection
    // ------------------------------------------------------------------------------------------

    @Test
    void aBusyServerSlowsTheToolsDown() {
        assertEquals(24, ToolRules.budget(20, 40, 24));
        assertEquals(12, ToolRules.budget(33, 40, 24), "from 80 %: half");
        assertEquals(0, ToolRules.budget(40, 40, 24), "at the limit: none");
        assertEquals(0, ToolRules.budget(80, 40, 24));
        assertEquals(24, ToolRules.budget(80, 0, 24), "0 = always");
        assertEquals(0, ToolRules.budget(10, 40, 0), "switched off");
        assertEquals(1, ToolRules.budget(35, 40, 1), "never halved to nothing");
    }

    @Test
    void spawnProtectionIsVanillas() {
        assertTrue(ToolRules.spawnProtected(5, -10, 0, 0, 16, false, true));
        assertFalse(ToolRules.spawnProtected(17, 0, 0, 0, 16, false, true), "outside the square");
        assertFalse(ToolRules.spawnProtected(5, 5, 0, 0, 16, true, true), "an operator");
        assertFalse(ToolRules.spawnProtected(5, 5, 0, 0, 0, false, true), "radius 0: off");
        assertFalse(ToolRules.spawnProtected(5, 5, 0, 0, 16, false, false), "no operators: off (vanilla)");
    }

    @Test
    void protectionIsAskedInOrder() {
        assertEquals(ToolRules.Verdict.OK, ToolRules.verdict(true, false, false, true));
        assertEquals(ToolRules.Verdict.UNLOADED, ToolRules.verdict(false, true, true, false), "an unloaded chunk is never touched");
        assertEquals(ToolRules.Verdict.SPAWN, ToolRules.verdict(true, true, false, true));
        assertEquals(ToolRules.Verdict.GUARDED, ToolRules.verdict(true, false, true, true), "a road or a dock even where one may build");
        assertEquals(ToolRules.Verdict.LAND, ToolRules.verdict(true, false, false, false), "someone else's land, server land");
    }

    // ------------------------------------------------------------------------------------------
    // Kept per vehicle
    // ------------------------------------------------------------------------------------------

    @Test
    void theToolOnIsKept(@TempDir Path dir) throws Exception {
        Db db = dev.stsv.core.data.DatabaseModule.sqlite(dir.resolve("t.db"), java.util.logging.Logger.getLogger("test"));
        try {
            VehicleToolStore s = new VehicleToolStore(db);
            s.init();
            s.save(7, "harvester", 1).join();
            s.save(8, "plough", 1).join();
            s.save(7, "seeder", 2).join();
            s.save(8, null, 3).join();
            assertEquals(Map.of(7L, "seeder"), s.all(), "replaced; none = no row");
            s.delete(7).join();
            assertEquals(Map.of(), s.all());
        } finally {
            db.close();
        }
    }
}
