package dev.stsv.economy.road;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reference point (기준점), the road running all the way to the dock's gate, and the patch for an old road's end. */
class RoadGateTest {

    // ------------------------------------------------------------------------------------------
    // The reference point
    // ------------------------------------------------------------------------------------------

    @Test
    void theOriginIsSavedAndReadBack() {
        RoadOrigin o = new RoadOrigin("world_build", 120.5, 71, -3400.5);
        assertEquals(o, RoadOrigin.read(o.write()));
        RoadOrigin comma = new RoadOrigin("my,world", -1.5, -20, 2.5);
        assertEquals(comma, RoadOrigin.read(comma.write()), "a world name with a comma");
        assertNull(RoadOrigin.read(""));
        assertNull(RoadOrigin.read("nonsense"));
        assertNull(RoadOrigin.read("world,a,b,c"));
    }

    @Test
    void withoutAnOriginItMeasuresFromTheSpawn() {
        Set<String> worlds = Set.of("world", "build");
        RoadOrigin.Use none = RoadOrigin.pick(null, worlds::contains, "build", 10, 64, 20);
        assertFalse(none.origin());
        assertEquals("build", none.world());
        assertEquals(10, none.x());
        assertEquals(20, none.z());
        RoadOrigin.Use set = RoadOrigin.pick(new RoadOrigin("world", 500.5, 70, -40.5), worlds::contains, "build", 10, 64, 20);
        assertTrue(set.origin());
        assertEquals("world", set.world());
        assertEquals(500.5, set.x());
        RoadOrigin.Use gone = RoadOrigin.pick(new RoadOrigin("deleted", 1, 2, 3), worlds::contains, "build", 10, 64, 20);
        assertFalse(gone.origin(), "its world is gone: the spawn again");
    }

    // ------------------------------------------------------------------------------------------
    // Up to the gate
    // ------------------------------------------------------------------------------------------

    /** A flat world: ground (top block) at 64 everywhere, the dock's pad (floor at y 61) from z 100 on. */
    static final class Flat implements RoadSection.Site {
        final Map<Long, Material> put = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x + (1 << 20)) << 42) | ((long) (z + (1 << 20)) << 21) | (y + 1024);
        }

        public Material get(int x, int y, int z) {
            Material m = put.get(key(x, y, z));
            if (m != null) return m;
            if (z >= 100 && Math.abs(x) <= 22) return y <= 61 ? Material.POLISHED_ANDESITE : Material.AIR;
            return y < 64 ? Material.DIRT : y == 64 ? Material.GRASS_BLOCK : Material.AIR;
        }

        public boolean inside(int y) {
            return y >= -64 && y < 320;
        }

        public boolean air(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public boolean liquid(int x, int y, int z) {
            return false;
        }

        public boolean natural(int x, int y, int z) {
            return false;
        }

        public boolean fillable(int x, int y, int z) {
            return get(x, y, z) == Material.AIR;
        }

        public void set(int x, int y, int z, Material m) {
            put.put(key(x, y, z), m);
        }

        public boolean playerLand(int x, int z) {
            return false;
        }

        public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {}
    }

    /** A road job as /관리자 국도 makes it for a dock whose gate is at (0.5, 100.5), floor y 61: planned to 20 in front. */
    static RoadJob job() {
        RoadJob j = new RoadJob();
        j.dock = "서해";
        j.world = "world";
        j.startX = 0.5;
        j.startZ = -60.5;
        j.endX = 0.5;
        j.endZ = 100.5 - dev.stsv.economy.dock.Dock.APPROACH;
        j.gateX = 0.5;
        j.gateZ = 100.5;
        j.gateY = 61;
        j.route = 3;
        j.route(List.of(new int[]{0, -4}, new int[]{0, 5}));
        return j;
    }

    /** Builds a job's road over the flat world as RoadModule does (ground read, the gate's level, the profile, the sections). */
    static RoadSection build(Flat land, RoadJob j) {
        List<double[]> pts = j.points();
        int n = pts.size();
        int[] ground = new int[n], surface = new int[n];
        for (int i = 0; i < n; i++) {
            int x = (int) Math.floor(pts.get(i)[0]), z = (int) Math.floor(pts.get(i)[1]);
            int y = 200;
            while (land.get(x, y, z) == Material.AIR) y--;
            ground[i] = surface[i] = y;
        }
        if (j.gated()) ground[n - 1] = j.gateY;
        int[] halves = RoadProfile.planHalves(ground, new boolean[n], surface);
        RoadSection s = new RoadSection(land, new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000),
                pts, halves, RoadProfile.partsHalves(halves, ground, new boolean[n], 3, 5, 4), j.dock, j.route);
        for (int i = 0; i < n; i++) assertNull(s.build(i));
        return s;
    }

    @Test
    void theRoadRunsOnToTheGateAtTheFloorsLevel() {
        RoadJob j = job();
        List<double[]> pts = j.points();
        double[] last = pts.getLast();
        assertEquals(0.5, last[0], 1e-9);
        assertEquals(100.5, last[1], 1e-9, "the last point is the gate itself");
        assertEquals(j.endZ, j.corners.get(j.corners.size() - 2)[1], 1e-9, "straight in from the planned end");
        Flat land = new Flat();
        RoadSection s = build(land, j);
        Set<Material> paved = s.style().paved();
        // no gaps: every block of the middle from the start to the gate is paved, and never a step over half a block
        int prev = Integer.MIN_VALUE;
        for (int z = -60; z <= 100; z++) {
            int y = 200;
            while (y > 0 && land.get(0, y, z) == Material.AIR) y--;
            Material m = land.get(0, y, z);
            assertTrue(paved.contains(m), "the road at z " + z + ": " + m + " at " + y);
            int top = 2 * y + (m.name().endsWith("_SLAB") ? 1 : 2);
            if (prev != Integer.MIN_VALUE) assertTrue(Math.abs(top - prev) <= 1, "a step at z " + z);
            prev = top;
        }
        // the last section is built at the floor's level: it joins the pad
        assertTrue(paved.contains(land.get(0, 61, 100)), "the gate's block");
        assertEquals(Material.AIR, land.get(0, 62, 100));
        // widened at the gate: lanes out to 4.5 each side, the shoulders paved like the pad
        assertTrue(paved.contains(land.get(4, 61, 100)) || paved.contains(land.get(-4, 61, 100)), "wider at the gate");
        assertEquals(Material.POLISHED_ANDESITE, land.get(7, 61, 100), "the shoulder at the gate: paved like the dock");
        // a crosswalk before the gate
        int white = 0;
        for (int x = -4; x <= 4; x++) {
            int y = 70;
            while (y > 0 && land.get(x, y, 100 - RoadSection.CROSS_FROM) == Material.AIR) y--;
            if (land.get(x, y, 100 - RoadSection.CROSS_FROM).name().startsWith("SMOOTH_QUARTZ")) white++;
        }
        assertTrue(white >= 4, "zebra stripes: " + white);
    }

    @Test
    void anOldRoadThatStoppedShortGetsItsMissingPiece() {
        // a road built before roads went to the gate: it ends 6 in front of it, on the ground
        RoadJob old = new RoadJob();
        old.dock = "서해";
        old.world = "world";
        old.startX = 0.5;
        old.startZ = -60.5;
        old.endX = 0.5;
        old.endZ = 94.5;
        old.route = 3;
        old.signs = 2;
        old.corners.add(new double[]{old.startX, old.startZ});
        old.corners.add(new double[]{old.endX, old.endZ});
        old.phase = RoadJob.Phase.DONE;
        assertFalse(old.gated());
        RoadJob t = RoadModule.tailJob(old, new int[]{0, 61, 100});
        assertTrue(t.tail);
        assertEquals("서해.tail", t.file());
        assertEquals(3, t.route, "the same road number");
        assertEquals(2, t.signs, "and set of signs");
        assertEquals(RoadJob.Phase.PREPARE, t.phase, "no survey or plan: straight on");
        List<double[]> pts = t.points();
        assertEquals(94.5 - RoadModule.TAIL_BACK, pts.getFirst()[1], 1e-9, "from a little way back along the old road");
        assertTrue(pts.stream().anyMatch(p -> Math.abs(p[1] - 94.5) < 1e-9), "through its end");
        assertEquals(100.5, pts.getLast()[1], 1e-9, "to the gate");
        assertEquals(pts.size(), t.ground.size());
        RoadJob back = RoadJob.read(t.write());
        assertTrue(back.tail);
        assertTrue(back.gated());
        assertEquals(61, back.gateY);
        assertEquals(100.5, back.gateZ, 1e-9);
        assertEquals(t.corners.size(), back.corners.size());
        // built: it closes the gap, level with the old road where it starts and the floor where it ends
        Flat land = new Flat();
        RoadJob built = old;
        built.gateY = Integer.MIN_VALUE;
        build(land, built);
        for (int z = 95; z <= 99; z++) assertEquals(Material.GRASS_BLOCK, land.get(0, 64, z), "the gap before the patch at z " + z);
        build(land, t);
        Set<Material> paved = new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000).paved();
        for (int z = 78; z <= 100; z++) {
            int y = 70;
            while (y > 0 && land.get(0, y, z) == Material.AIR) y--;
            assertTrue(paved.contains(land.get(0, y, z)), "patched at z " + z + ": " + land.get(0, y, z));
        }
        assertNotNull(land.get(0, 61, 100));
        assertTrue(paved.contains(land.get(0, 61, 100)), "down to the floor at the gate");
    }
}
