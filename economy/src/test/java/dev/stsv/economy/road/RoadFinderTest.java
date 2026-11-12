package dev.stsv.economy.road;

import dev.stsv.core.vehicle.VehicleSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A road found by its blocks ({@link RoadFinder}): /관리자 국도 터널보수 여기 mends a tunnel no saved road knows of (the
 * owner's: in front of the tunnel, "no finished road with a tunnel"), and /관리자 국도 눈치우기 clears snow on a road's
 * blocks; neither touches anything but the road's own.
 */
class RoadFinderTest {

    static final Set<Material> ROAD = RoadPreviewTest.STYLE.roadBlocks();

    /** The hill's blocks scanned round the tunnel (x along it, z across), as /관리자 국도 터널보수 여기 does chunk by chunk. */
    static RoadFinder.Found scan(RoadFinder.Blocks b) {
        RoadFinder.Found f = new RoadFinder.Found();
        for (int x = 0; x < RoadClearanceTest.Hill.LEN; x++) for (int z = -12; z <= 12; z++) RoadFinder.scan(b, x, z, 50, 110, ROAD, f);
        return f;
    }

    static RoadFinder.Site site(RoadClearanceTest.Hill hill, Set<Long> protect) {
        return new RoadFinder.Site() {
            public Material get(int x, int y, int z) {
                return hill.get(x, y, z);
            }

            public boolean protectedLand(int x, int z) {
                return protect.contains(RoadFinder.key(x, z));
            }

            public void set(int x, int y, int z, Material m) {
                hill.set(x, y, z, m);
            }
        };
    }

    /** Found, planned and bored: how many blocks changed. */
    static int mend(RoadClearanceTest.Hill hill, Set<Long> protect) {
        RoadFinder.Found f = scan(hill::get);
        RoadFinder.Plan plan = RoadFinder.plan(f, k -> false);
        Map<Long, Integer> targets = RoadFinder.targets(plan.bores());
        int n = 0;
        for (RoadFinder.Bore b : plan.bores()) n += RoadFinder.bore(site(hill, protect), b, targets, f);
        return n;
    }

    @Test
    void anOldTunnelNoSavedRoadKnowsIsFoundAndMended() throws Exception {
        RoadClearanceTest.Hill hill = new RoadClearanceTest.Hill();
        RoadClearanceTest.tunnel(hill, true);   // built as roads were before: 4 clear, stepping down with the road
        int[] h = RoadClearanceTest.upAndDown();
        RoadFinder.Found f = scan(hill::get);
        RoadFinder.Plan plan = RoadFinder.plan(f, k -> false);
        assertTrue(plan.lanes() > 0, "its lanes are found");
        assertFalse(plan.bores().isEmpty(), "and some are too low");
        // a player's chest over the old ceiling, in the bore: it stays (the bore goes round it)
        int top = 36, oldCeiling = RoadSection.surfaceY(h[top]) + 5;
        hill.set(top, oldCeiling + 1, 2, Material.CHEST);
        hill.log = new ArrayList<>();
        int n = mend(hill, Set.of());
        assertTrue(n > 0);
        assertEquals(Material.CHEST, hill.get(top, oldCeiling + 1, 2));
        RoadSection fresh = new RoadSection(new RoadClearanceTest.Hill(), RoadPreviewTest.STYLE, RoadClearanceTest.line(), h, RoadClearanceTest.tunnelParts(), "북해", 3);
        for (int[] c : hill.log) {
            int x = c[0];
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int k = Math.max(0, x - 1); k <= Math.min(RoadClearanceTest.Hill.LEN - 1, x + 1); k++) {
                low = Math.min(low, RoadSection.surfaceY(h[k]) + 5);
                high = Math.max(high, fresh.ceiling(k));
            }
            assertTrue(c[1] >= low, "never under the old ceiling: " + java.util.Arrays.toString(c));
            assertTrue(c[1] <= high + 1, "never far over the new ceiling: " + java.util.Arrays.toString(c));
            assertTrue(Math.abs(c[2]) <= 6, "only the bore and its walls: " + java.util.Arrays.toString(c));
            assertTrue(x >= 15 && x <= RoadClearanceTest.Hill.LEN - 16, "only in the tunnel: " + java.util.Arrays.toString(c));
            assertTrue(x >= 30 - RoadClearance.REACH - 2 && x <= 80 + RoadClearance.REACH + 2, "the flat stretches are left: " + x);
        }
        // its middle is as high as a tunnel is built now (or a little higher), never lower
        for (int i = 15; i <= RoadClearanceTest.Hill.LEN - 16; i++) {
            int y = RoadSection.surfaceY(h[i]), k = y + 1;
            while (RoadClearanceTest.isAirBlock(hill.get(i, k, 0))) k++;
            assertTrue(k >= fresh.ceiling(i), "the ceiling at " + i + ": " + k + " < " + fresh.ceiling(i));
            assertTrue(k <= fresh.ceiling(i) + 1, "the ceiling at " + i + ": " + k + " > " + fresh.ceiling(i));
        }
        // done twice: nothing more
        hill.log = new ArrayList<>();
        assertEquals(0, mend(hill, Set.of()));
        assertTrue(hill.log.isEmpty());
        // every vehicle gets through, both ways
        hill.put.remove(RoadClearanceTest.Hill.key(top, oldCeiling + 1, 2));
        for (Map.Entry<String, VehicleSpec> e : RoadClearanceTest.landVehicles().entrySet())
            assertEquals(List.of(), RoadClearanceTest.through(e.getValue(), hill), e.getKey());
    }

    @Test
    void protectedLandIsLeftAlone() {
        RoadClearanceTest.Hill hill = new RoadClearanceTest.Hill();
        RoadClearanceTest.tunnel(hill, true);
        Set<Long> land = new java.util.HashSet<>();
        for (int x = 0; x < 50; x++) for (int z = -12; z <= 12; z++) land.add(RoadFinder.key(x, z));
        hill.log = new ArrayList<>();
        assertTrue(mend(hill, land) > 0);
        for (int[] c : hill.log) assertTrue(c[0] >= 50, "in someone's land: " + java.util.Arrays.toString(c));
    }

    @Test
    void aFlatTunnelIsLeftAsItIs() {
        RoadClearanceTest.Hill hill = new RoadClearanceTest.Hill();
        int[] h = new int[RoadClearanceTest.Hill.LEN];
        java.util.Arrays.fill(h, 128);
        RoadSection s = new RoadSection(hill, RoadPreviewTest.STYLE, RoadClearanceTest.line(), h, RoadClearanceTest.tunnelParts(), "북해", 3)
                .ceilings(RoadClearance.legacy(h));
        for (int i = 0; i < RoadClearanceTest.Hill.LEN; i++) s.build(i);
        RoadFinder.Plan plan = RoadFinder.plan(scan(hill::get), k -> false);
        assertTrue(plan.lanes() > 0);
        assertEquals(plan.lanes(), plan.high(), "all high enough");
        assertTrue(plan.bores().isEmpty());
    }

    @Test
    void aPlayersRoomIsNoTunnel() {
        // a grey concrete floor stepping up, a stone brick roof 5 over it: no lined wall, so no tunnel
        Map<Long, Material> put = new HashMap<>();
        RoadFinder.Blocks b = (x, y, z) -> {
            Material m = put.get(RoadClearanceTest.Hill.key(x, y, z));
            return m != null ? m : y <= 60 ? Material.STONE : Material.AIR;
        };
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 8; z++) {
                int y = 61 + x / 6;
                for (int k = 61; k <= y; k++) put.put(RoadClearanceTest.Hill.key(x, k, z), Material.GRAY_CONCRETE);
                put.put(RoadClearanceTest.Hill.key(x, y + 5, z), Material.STONE_BRICKS);
            }
        }
        RoadFinder.Found f = new RoadFinder.Found();
        for (int x = 0; x < 20; x++) for (int z = 0; z < 8; z++) RoadFinder.scan(b, x, z, 50, 90, ROAD, f);
        assertTrue(f.tunnelCount() > 0, "under a roof");
        RoadFinder.Plan plan = RoadFinder.plan(f, k -> false);
        assertEquals(0, plan.lanes());
        assertTrue(plan.bores().isEmpty());
    }

    @Test
    void snowOnARoadsBlocksGoesOnlyThere() {
        Map<Long, Material> put = new HashMap<>();
        RoadFinder.Blocks b = (x, y, z) -> {
            Material m = put.get(RoadClearanceTest.Hill.key(x, y, z));
            return m != null ? m : y <= 63 ? Material.STONE : Material.AIR;
        };
        put.put(RoadClearanceTest.Hill.key(0, 64, 0), Material.GRAY_CONCRETE);
        put.put(RoadClearanceTest.Hill.key(0, 65, 0), Material.SNOW);
        put.put(RoadClearanceTest.Hill.key(1, 64, 0), Material.SMOOTH_STONE_SLAB);
        put.put(RoadClearanceTest.Hill.key(1, 65, 0), Material.SNOW);
        put.put(RoadClearanceTest.Hill.key(2, 64, 0), Material.GRASS_BLOCK);
        put.put(RoadClearanceTest.Hill.key(2, 65, 0), Material.SNOW);
        // a snow layer does not stop movement: the highest such block is the one under it (64); it was looked for under that
        assertEquals(List.of(new RoadFinder.Snow(65, 2)), RoadFinder.snow(b, 0, 0, 64, null, false, ROAD));
        assertEquals(List.of(new RoadFinder.Snow(65, 2)), RoadFinder.snow(b, 1, 0, 64, null, false, ROAD), "a kerb's slab");
        assertEquals(List.of(), RoadFinder.snow(b, 2, 0, 64, null, false, ROAD), "grass keeps its snow");
        assertEquals(List.of(new RoadFinder.Snow(65, 1)), RoadFinder.snow(b, 2, 0, 64, null, true, ROAD), "in a dock's chunks");
        RoadNetwork.Spot spot = new RoadNetwork.Spot("서해", 0, 0, 128, 4.5);   // a saved road's shoulder (its surface block 64)
        assertEquals(List.of(new RoadFinder.Snow(65, 0)), RoadFinder.snow(b, 2, 0, 64, spot, false, ROAD), "a saved road's shoulder");
        assertTrue(ROAD.containsAll(List.of(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_QUARTZ, Material.DEEPSLATE_TILE_SLAB,
                Material.SMOOTH_STONE, Material.SMOOTH_STONE_SLAB, Material.DEEPSLATE_TILES)));
        assertFalse(ROAD.contains(Material.GRASS_BLOCK));
        assertFalse(ROAD.contains(Material.DIRT_PATH));
    }
}
