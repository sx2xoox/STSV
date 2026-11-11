package dev.stsv.economy.road;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /관리자 국도 미리보기 ({@link RoadPreview}): the summary's numbers and the heuristic for builds without land. */
class RoadScanTest {

    @Test
    void theSummaryCountsLengthPartsEarthChunksAndLandNearby() {
        // 10 points 4 apart along z 0.5: x 0.5 .. 36.5 (chunks x 0, 1, 2)
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < 10; i++) pts.add(new double[]{0.5 + 4 * i, 0.5});
        int[] ground = {64, 64, 66, 66, 64, 60, 60, 64, 64, 64};
        int[] halves = new int[10];
        java.util.Arrays.fill(halves, 128);   // the surface in block 64
        RoadProfile.Part R = RoadProfile.Part.ROAD, B = RoadProfile.Part.BRIDGE, T = RoadProfile.Part.TUNNEL;
        RoadProfile.Part[] parts = {R, R, R, R, B, B, R, T, T, R};
        Map<Long, Integer> land = Map.of(
                RoadJob.key(0, 1), 1,     // z 16..31: 15.5 from the road: near
                RoadJob.key(3, 0), 1,     // x 48..63: 11.5 past the last point: near
                RoadJob.key(1, -1), 2,    // z -16..-1: near
                RoadJob.key(5, -1), 2,    // x 80..: far
                RoadJob.key(-2, 0), 1,    // x -32..-17: 17.5 away: far
                RoadJob.key(0, 2), 1);    // z 32..: far
        RoadPreview.Summary s = RoadPreview.summary(pts, ground, halves, parts, 3, (w, cx, cz) -> land.getOrDefault(RoadJob.key(cx, cz), 0), "world");
        assertEquals(10, s.length());
        assertEquals(3, s.corners());
        assertEquals(1, s.bridges());
        assertEquals(2, s.bridgeLength());
        assertEquals(1, s.tunnels());
        assertEquals(2, s.tunnelLength());
        assertEquals(2 * 2 * RoadPreview.WIDTH, s.cut(), "two points 2 under the ground, 11 wide");
        assertEquals(3 * RoadPreview.WIDTH, s.fill(), "a point 3 over the ground (blocks 61..63)");
        assertEquals(2L * RoadPreview.BORE_WIDE * RoadPreview.BORE_HIGH, s.bore());
        assertEquals(3, s.chunks());
        assertEquals(2, s.playerChunks());
        assertEquals(1, s.serverChunks());
    }

    // ------------------------------------------------------------------------------------------
    // Builds without land
    // ------------------------------------------------------------------------------------------

    /** A little house in chunk (3, 3): planks, glass, a door, a bed, a chest, torches. */
    static void house(RoadPreview.Scan s, int x0, int z0, List<RoadPreview.Piece> pieces, int along) {
        for (int x = 0; x < 5; x++) for (int y = 0; y < 3; y++) s.add(x0 + x, 70 + y, z0, Material.OAK_PLANKS, false, pieces, along);
        s.add(x0 + 1, 71, z0 + 1, Material.GLASS_PANE, false, pieces, along);
        s.add(x0 + 3, 71, z0 + 1, Material.GLASS, false, pieces, along);
        s.add(x0 + 2, 70, z0 + 1, Material.OAK_DOOR, false, pieces, along);
        s.add(x0 + 2, 70, z0 + 2, Material.RED_BED, false, pieces, along);
        s.add(x0 + 3, 70, z0 + 2, Material.CHEST, false, pieces, along);
        s.add(x0 + 1, 72, z0 + 2, Material.WALL_TORCH, false, pieces, along);
    }

    @Test
    void aHouseWithoutLandIsAWarning() {
        RoadPreview.Scan s = new RoadPreview.Scan();
        house(s, 50, 50, List.of(), 120);
        List<RoadPreview.Cluster> c = s.clusters(RoadPreview.CLUSTER_MIN);
        assertEquals(1, c.size());
        RoadPreview.Cluster h = c.getFirst();
        assertEquals(21, h.blocks());
        assertTrue(h.x() >= 50 && h.x() <= 54 && h.z() >= 50 && h.z() <= 52 && h.y() >= 70 && h.y() <= 72, h.toString());
        assertEquals(120, h.along());
        assertEquals(15, h.kinds().get(RoadPreview.Kind.PLANKS));
        assertTrue(h.words().startsWith("판자 15"), h.words());
        // a second, further along, in another chunk: listed in the road's order
        house(s, 200, 50, List.of(), 40);
        assertEquals(List.of(40, 120), s.clusters(RoadPreview.CLUSTER_MIN).stream().map(RoadPreview.Cluster::along).toList());
    }

    @Test
    void generatedVillagesRuinsAndNatureAreNot() {
        RoadPreview.Scan s = new RoadPreview.Scan();
        // the same house inside a village house's piece (the server's structure pieces): left out
        house(s, 50, 50, List.of(new RoadPreview.Piece(48, 60, 48, 56, 80, 56)), 10);
        // a few torches and a sign by a path: under the threshold
        for (int k = 0; k < 4; k++) s.add(100 + 8 * k / 2, 65, 100, Material.TORCH, false, List.of(), 20);
        s.add(101, 65, 101, Material.OAK_SIGN, false, List.of(), 20);
        // badlands: terracotta is the ground
        for (int k = 0; k < 40; k++) s.add(300 + k % 10, 60 + k / 10, 300, Material.ORANGE_TERRACOTTA, true, List.of(), 30);
        // moss carpets, logs, leaves, stone, grass: nature
        for (int k = 0; k < 20; k++) s.add(400 + k % 10, 64, 400 + k / 10, Material.MOSS_CARPET, false, List.of(), 40);
        for (Material m : List.of(Material.OAK_LOG, Material.OAK_LEAVES, Material.STONE, Material.GRASS_BLOCK, Material.DIRT, Material.SAND, Material.TORCHFLOWER))
            for (int k = 0; k < 10; k++) s.add(500 + k, 64, 500, m, false, List.of(), 50);
        assertEquals(List.of(), s.clusters(RoadPreview.CLUSTER_MIN));
        // terracotta outside the badlands (a player's wall) does count, glazed even in the badlands
        for (int k = 0; k < 8; k++) s.add(600 + k, 64, 600, Material.WHITE_TERRACOTTA, false, List.of(), 60);
        for (int k = 0; k < 8; k++) s.add(690 + k, 64, 700, Material.BLUE_GLAZED_TERRACOTTA, true, List.of(), 70);
        assertEquals(List.of(60, 70), s.clusters(RoadPreview.CLUSTER_MIN).stream().map(RoadPreview.Cluster::along).toList());
    }

    @Test
    void whatCountsAsPlayerMade() {
        for (Material m : List.of(Material.SPRUCE_PLANKS, Material.GLASS, Material.LIME_STAINED_GLASS_PANE, Material.WHITE_BED, Material.IRON_DOOR,
                Material.CHEST, Material.BARREL, Material.CRAFTING_TABLE, Material.FURNACE, Material.SMOKER, Material.RED_CARPET, Material.TORCH,
                Material.SOUL_WALL_TORCH, Material.LANTERN, Material.WHITE_WOOL, Material.BRICKS, Material.STONE_BRICKS, Material.GRAY_CONCRETE,
                Material.TERRACOTTA, Material.OAK_STAIRS, Material.STONE_BRICK_SLAB, Material.OAK_SIGN, Material.OAK_WALL_HANGING_SIGN)) {
            assertTrue(RoadPreview.kind(m, false) != null, m.name());
            assertEquals(RoadPreview.kind(m, false), RoadPreview.cached(m), m.name());
        }
        for (Material m : List.of(Material.STONE, Material.DIRT, Material.OAK_LOG, Material.BIRCH_LEAVES, Material.MOSS_CARPET, Material.REDSTONE_TORCH,
                Material.TORCHFLOWER, Material.SAND, Material.WATER, Material.COBWEB, Material.JACK_O_LANTERN)) {
            assertNull(RoadPreview.kind(m, false), m.name());
        }
        assertNull(RoadPreview.kind(Material.RED_TERRACOTTA, true), "badlands ground");
    }
}
