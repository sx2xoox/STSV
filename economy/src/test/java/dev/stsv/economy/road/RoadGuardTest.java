package dev.stsv.economy.road;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 국도 보호 ({@link RoadGuard}, {@link RoadAvoid}): the server's land is kept out of like a player's, but for the chunks
 * open to the road (its dock's, its terminal's); roads.avoid boxes too; the planner goes round, says how far, or that
 * protected land closed the way; the builder never puts a block in a protected column.
 */
class RoadGuardTest {

    static final String W = "world";

    /** Land by chunk: 1 a player's, 2 the server's. */
    static RoadGuard guard(Map<Long, Integer> land, Set<Long> open, List<RoadGuard.Box> boxes) {
        return new RoadGuard((w, cx, cz) -> w.equals(W) ? land.getOrDefault(RoadJob.key(cx, cz), 0) : 0,
                (w, cx, cz) -> w.equals(W) && open.contains(RoadJob.key(cx, cz)), boxes);
    }

    @Test
    void theServersLandIsOutButForTheChunksOpenToTheRoad() {
        Map<Long, Integer> land = Map.of(RoadJob.key(5, 0), 2, RoadJob.key(6, 0), 2, RoadJob.key(7, 0), 1);
        RoadGuard g = guard(land, Set.of(RoadJob.key(6, 0), RoadJob.key(7, 0)), List.of());
        assertEquals(RoadGuard.Why.SERVER, g.chunk(W, 5, 0), "the spawn town");
        assertNull(g.chunk(W, 6, 0), "the dock's own chunk: open");
        assertEquals(RoadGuard.Why.PLAYER, g.chunk(W, 7, 0), "a player's land is never open");
        assertNull(g.chunk(W, 4, 0));
        assertNull(g.chunk("other", 5, 0), "another world");
        assertEquals(RoadGuard.Why.SERVER, g.column(W, 5 * 16 + 3, 9));
        assertNull(g.column(W, 6 * 16 + 3, 9));
    }

    @Test
    void avoidBoxesAreReadFromTheConfig() {
        List<String> warned = new ArrayList<>();
        List<Map<?, ?>> raw = new ArrayList<>();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("world", W);
        ok.put("x1", 130);
        ok.put("z1", 20);
        ok.put("x2", "100");
        ok.put("z2", -4.5);
        raw.add(ok);
        raw.add(Map.of("world", W, "x1", 1));   // broken: left out, warned
        List<RoadGuard.Box> boxes = RoadGuard.boxes(raw, warned::add);
        assertEquals(List.of(new RoadGuard.Box(W, 100, -5, 130, 20)), boxes, "corners in any order");
        assertEquals(1, warned.size());
        RoadGuard g = guard(Map.of(), Set.of(), boxes);
        assertEquals(RoadGuard.Why.AVOID, g.column(W, 100, -5));
        assertEquals(RoadGuard.Why.AVOID, g.column(W, 130, 20));
        assertNull(g.column(W, 131, 0));
        assertNull(g.column("other", 110, 0));
        assertEquals(RoadGuard.Why.AVOID, g.chunk(W, 8, 1), "the planner keeps out of every chunk it reaches into");
        assertEquals(RoadGuard.Why.AVOID, g.chunk(W, 6, -1));
        assertNull(g.chunk(W, 9, 0));
        assertNull(g.chunk(W, 6, -2));
    }

    // ------------------------------------------------------------------------------------------
    // Planning round it
    // ------------------------------------------------------------------------------------------

    /** A flat surveyed corridor, cells x 0..12, z -4..4, the cells' reasons from the guard; from cell (0, 0) to (12, 0). */
    static RoadJob job(RoadGuard g) {
        RoadJob j = new RoadJob();
        j.dock = "서해";
        j.world = W;
        j.startX = 8.5;
        j.startZ = 8.5;
        j.endX = 12 * 16 + 8.5;
        j.endZ = 8.5;
        for (int x = 0; x <= 12; x++) {
            for (int z = -4; z <= 4; z++) {
                RoadGuard.Why why = g.chunk(W, x, z);
                j.cells.put(RoadJob.key(x, z), new RoadJob.Cell(64, 0, 0, why == null ? 0 : why.code));
            }
        }
        return j;
    }

    static Map<Long, Integer> wall(int cx, int value) {
        Map<Long, Integer> land = new HashMap<>();
        for (int z = -4; z <= 4; z++) land.put(RoadJob.key(cx, z), value);
        return land;
    }

    static boolean through(RoadJob j, int cx, int cz) {
        for (double[] p : j.points()) if (((int) Math.floor(p[0]) >> 4) == cx && ((int) Math.floor(p[1]) >> 4) == cz) return true;
        return false;
    }

    @Test
    void aWallOfServerLandIsGoneRoundThroughTheDocksChunk() {
        // the spawn town across the corridor; a dock's chunk in it (3 cells north of the straight line) is open
        RoadGuard g = guard(wall(6, 2), Set.of(RoadJob.key(6, 3)), List.of());
        RoadJob j = job(g);
        RoadAvoid.Result r = RoadAvoid.plan(j, RoadNetwork.NONE, g, 40, 100_000);
        assertEquals(RoadAvoid.Kind.OK, r.kind());
        assertTrue(through(j, 6, 3), "through the open chunk");
        for (int z = -4; z <= 4; z++) if (z != 3) assertFalse(through(j, 6, z), "never through the town: " + z);
        assertTrue(r.detoured());
        assertEquals(1, r.avoided()[RoadGuard.Why.SERVER.code], "the straight way crosses one town chunk");
        assertTrue(r.length() > r.freeLength() + 20, r.length() + " vs " + r.freeLength());
        assertNull(RoadAvoid.firstProtected(j.points(), g, W), "its surface touches no protected block");
        // the same town with no chunk open: no way at all, and the admin told it is the land
        RoadGuard shut = guard(wall(6, 2), Set.of(), List.of());
        RoadAvoid.Result none = RoadAvoid.plan(job(shut), RoadNetwork.NONE, shut, 40, 100_000);
        assertEquals(RoadAvoid.Kind.BLOCKED, none.kind());
        assertEquals(RoadGuard.Why.SERVER, none.why());
        assertNotNull(none.at());
        // a player's land the same way, even where a dock's chunks are listed open
        RoadGuard players = guard(wall(6, 1), Set.of(RoadJob.key(6, 3)), List.of());
        assertEquals(RoadAvoid.Kind.BLOCKED, RoadAvoid.plan(job(players), RoadNetwork.NONE, players, 40, 100_000).kind());
    }

    @Test
    void aStartInTheTownCannotBeMovedAndSaysSo() {
        RoadGuard g = guard(Map.of(RoadJob.key(0, 0), 2), Set.of(), List.of());
        RoadAvoid.Result r = RoadAvoid.plan(job(g), RoadNetwork.NONE, g, 40, 100_000);
        assertEquals(RoadAvoid.Kind.ENDS, r.kind());
        assertEquals(RoadGuard.Why.SERVER, r.why());
        // the terminal it starts from is open: fine
        RoadGuard t = guard(Map.of(RoadJob.key(0, 0), 2), Set.of(RoadJob.key(0, 0)), List.of());
        assertEquals(RoadAvoid.Kind.OK, RoadAvoid.plan(job(t), RoadNetwork.NONE, t, 40, 100_000).kind());
    }

    @Test
    void anAvoidBoxIsGoneRound() {
        // a protected build without land right on the straight line: its chunk is kept out of
        RoadGuard g = guard(Map.of(), Set.of(), List.of(new RoadGuard.Box(W, 100, 4, 104, 12)));
        RoadJob j = job(g);
        RoadAvoid.Result r = RoadAvoid.plan(j, RoadNetwork.NONE, g, 40, 100_000);
        assertEquals(RoadAvoid.Kind.OK, r.kind());
        assertFalse(through(j, 6, 0));
        assertEquals(1, r.avoided()[RoadGuard.Why.AVOID.code]);
        for (double[] p : j.points()) assertTrue(Math.hypot(Math.clamp(p[0], 100, 105) - p[0], Math.clamp(p[1], 4, 13) - p[1]) > 4, "clear of the box");
    }

    @Test
    void theBuilderPutsNothingInAProtectedColumn() {
        // a straight road along z 0.5; a box over its north shoulder and a server chunk across it further on
        RoadGuard g = guard(Map.of(RoadJob.key(4, 0), 2, RoadJob.key(4, -1), 2), Set.of(), List.of(new RoadGuard.Box(W, 20, -6, 40, -5)));
        RoadSignFacingTest.Flat land = new RoadSignFacingTest.Flat() {
            @Override
            public boolean protectedLand(int x, int z) {
                return g.column(W, x, z) != null;
            }
        };
        List<double[]> pts = RoadPlanner.densify(List.of(new double[]{0.5, 0.5}, new double[]{100.5, 0.5}), 1);
        int n = pts.size();
        int[] ground = new int[n];
        java.util.Arrays.fill(ground, 64);
        int[] halves = RoadProfile.planHalves(ground, new boolean[n], ground);
        RoadSection s = new RoadSection(land, new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000),
                pts, halves, RoadProfile.partsHalves(halves, ground, new boolean[n], 3, 5, 4), "서해", 1);
        int stopped = -1;
        for (int i = 0; i < n; i++) {
            int[] at = s.build(i);
            if (at != null) {
                assertEquals(RoadGuard.Why.SERVER, g.column(W, at[0], at[1]));
                stopped = i;
                break;
            }
        }
        assertTrue(stopped >= 60 && stopped <= 64, "stops where its surface would enter the town (x 64): " + stopped);
        int shoulders = 0;
        for (long k : land.put.keySet()) {
            int x = (int) ((k >> 42) - (1 << 20)), z = (int) (((k >> 21) & ((1 << 21) - 1)) - (1 << 20));
            assertNull(g.column(W, x, z), "a block put in a protected column at " + x + ", " + z);
            if (z >= -5 && z <= -4) shoulders++;
        }
        assertTrue(shoulders > 0, "the shoulder beside the box is still built where it is open");
        // with the town's chunks open (its dock's): built through
        RoadSignFacingTest.Flat open = new RoadSignFacingTest.Flat();
        RoadSection all = new RoadSection(open, new RoadSection.Style(Material.GRAY_CONCRETE, Material.YELLOW_CONCRETE, Material.SMOOTH_STONE, 40, 1000),
                pts, halves, RoadProfile.partsHalves(halves, ground, new boolean[n], 3, 5, 4), "서해", 1);
        for (int i = 0; i < n; i++) assertNull(all.build(i));
    }
}
