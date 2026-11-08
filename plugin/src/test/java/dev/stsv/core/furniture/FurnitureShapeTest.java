package dev.stsv.core.furniture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Voxelizing models, covering them with click boxes, choosing barrier cells (FurnitureShape). */
class FurnitureShapeTest {

    static final List<int[]> ONE = List.of(new int[]{0, 0, 0});
    /** 2 wide, 2 tall, 1 deep (gem bench, fish bench, press) */
    static final List<int[]> BENCH = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{0, 1, 0}, new int[]{1, 1, 0});

    static JsonObject cube(double[] from, double[] to) {
        JsonObject el = new JsonObject();
        el.add("from", arr(from));
        el.add("to", arr(to));
        JsonObject faces = new JsonObject();
        JsonObject face = new JsonObject();
        face.add("uv", arr(new double[]{0, 0, 16, 16}));
        face.addProperty("texture", "#0");
        faces.add("north", face);
        el.add("faces", faces);
        return el;
    }

    static JsonObject rotated(JsonObject el, String axis, double angle, double[] origin) {
        JsonObject r = new JsonObject();
        r.addProperty("angle", angle);
        r.addProperty("axis", axis);
        r.add("origin", arr(origin));
        el.add("rotation", r);
        return el;
    }

    static JsonObject model(JsonObject... elements) {
        JsonObject m = new JsonObject();
        JsonArray a = new JsonArray();
        for (JsonObject e : elements) a.add(e);
        m.add("elements", a);
        return m;
    }

    static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }

    static FurnitureShape.Voxels voxelize(List<int[]> cells, JsonObject model) {
        return FurnitureShape.voxelize(cells, model, new Vector3f(), new Vector3f(1), List.of(), 0);
    }

    // ------------------------------------------------------------------------------------------
    // Voxelization
    // ------------------------------------------------------------------------------------------

    @Test
    void cubeOnTheGridFillsExactlyItsVoxels() {
        // 0..8 x 0..4 x 4..12 px: 2 x 1 x 2 voxels, nothing spills over the faces lying on voxel edges
        FurnitureShape.Voxels v = voxelize(ONE, model(cube(new double[]{0, 0, 4}, new double[]{8, 4, 12})));
        assertEquals(4, v.filledCount());
        assertTrue(v.filled(0, 0, 1) && v.filled(1, 0, 2));
        assertFalse(v.filled(2, 0, 1) || v.filled(0, 1, 1) || v.filled(0, 0, 3));
    }

    @Test
    void anyOverlapCountsAndTheRealTopIsKept() {
        // 1..2 px thin slab at height 5..6.5: one voxel layer (4..8), but the box top follows the cube (6.5)
        FurnitureShape.Voxels v = voxelize(ONE, model(cube(new double[]{1, 5, 1}, new double[]{15, 6.5, 15})));
        assertEquals(16, v.filledCount());
        FurnitureShape.Shape s = FurnitureShape.shape(v, ONE, 0.3, true);
        assertEquals(1, s.boxes().size());
        FurnitureShape.Box b = s.boxes().getFirst();
        assertEquals(5, b.y0(), 1e-6);
        assertEquals(6.5, b.y1(), 1e-6);
        assertEquals(16, b.size(), 1e-6);
        assertTrue(s.barriers().isEmpty(), "a quarter of the cell is not enough for collision");
    }

    @Test
    void flatPlaneOnAVoxelEdgeTouchesBothSides() {
        FurnitureShape.Voxels v = voxelize(ONE, model(cube(new double[]{0, 8, 0}, new double[]{4, 8, 4})));
        assertTrue(v.filled(0, 1, 0) && v.filled(0, 2, 0));
        assertEquals(2, v.filledCount());
    }

    @Test
    void rotatedCubeCoversItsTurnedCorners() {
        // a 2 px thick bar along x through the cell centre, turned 45 degrees around y: reaches the diagonal corners
        JsonObject bar = rotated(cube(new double[]{-3, 0, 7}, new double[]{19, 4, 9}), "y", 45, new double[]{8, 0, 8});
        FurnitureShape.Voxels v = voxelize(ONE, model(bar));
        assertTrue(v.filled(0, 0, 3) || v.filled(0, 0, 0), "one diagonal corner");
        assertTrue(v.filled(3, 0, 0) || v.filled(3, 0, 3), "the other");
        assertTrue(v.filled(1, 0, 1) || v.filled(1, 0, 2), "centre");
        assertTrue(v.filledCount() < 12, "only along the diagonal, not the whole layer: " + v.filledCount());
        for (int j = 1; j < 4; j++) for (int i = 0; i < 4; i++) for (int k = 0; k < 4; k++) assertFalse(v.filled(i, j, k));
    }

    @Test
    void geometryOutsideTheCellsIsCutOff() {
        // sticks 4 px out of the cell on every side: only the inside is filled, so no box can reach outside
        FurnitureShape.Voxels v = voxelize(ONE, model(cube(new double[]{-4, -4, -4}, new double[]{20, 20, 20})));
        assertEquals(64, v.filledCount());
        FurnitureShape.Shape s = FurnitureShape.shape(v, ONE, 0.3, true);
        for (FurnitureShape.Box b : s.boxes()) assertBoxInside(b, ONE);
    }

    @Test
    void translationLiftsTheModel() {
        // a forge-style model drawn from y -16 with translation [0, 1, 0]: lands in cells y 0..1
        List<int[]> cells = List.of(new int[]{0, 0, 0}, new int[]{0, 1, 0});
        JsonObject m = model(cube(new double[]{0, -16, 0}, new double[]{16, -12, 16}));
        FurnitureShape.Voxels v = FurnitureShape.voxelize(cells, m, new Vector3f(0, 1, 0), new Vector3f(1), List.of(), 0);
        assertEquals(16, v.filledCount());
        assertTrue(v.filled(0, 0, 0) && !v.filled(0, 4, 0));
    }

    @Test
    void spinningPartIsSweptAllTheWayRound() {
        // a 12 px long, 2 px wide blade at the centre of a cell spinning around y: the whole circle is clickable
        JsonObject blade = model(cube(new double[]{2, 7, 7}, new double[]{14, 9, 9}));
        FurnitureShape.Voxels still = FurnitureShape.voxelize(ONE, model(), new Vector3f(), new Vector3f(1),
                List.of(new FurnitureShape.PartModel(blade, new Vector3f(8, 8, 8), null)), 0);
        FurnitureShape.Voxels spun = FurnitureShape.voxelize(ONE, model(), new Vector3f(), new Vector3f(1),
                List.of(new FurnitureShape.PartModel(blade, new Vector3f(8, 8, 8), "y")), 0);
        assertFalse(still.filled(1, 1, 0) || still.filled(1, 2, 0), "a still blade along x does not reach the z edge");
        assertTrue(spun.filled(1, 1, 0) || spun.filled(1, 2, 0), "spun round it does");
        assertTrue(spun.filledCount() > still.filledCount());
    }

    // ------------------------------------------------------------------------------------------
    // Barriers
    // ------------------------------------------------------------------------------------------

    @Test
    void barrierOnlyWhereTheModelFillsEnough() {
        // lower row: a full table body (all of both cells); upper row: a 4 px rail on the left cell (25%)
        // and a 6 px block on the right one (6/16 rounds up to 2 voxel layers = 50%)
        JsonObject m = model(cube(new double[]{0, 0, 0}, new double[]{32, 16, 16}),
                cube(new double[]{0, 16, 0}, new double[]{16, 20, 16}),
                cube(new double[]{16, 16, 0}, new double[]{32, 22, 16}));
        FurnitureShape.Voxels v = voxelize(BENCH, m);
        double[] fill = FurnitureShape.fill(v, BENCH);
        assertEquals(1.0, fill[0], 1e-9);
        assertEquals(1.0, fill[1], 1e-9);
        assertEquals(0.25, fill[2], 1e-9);
        assertEquals(0.5, fill[3], 1e-9);
        assertEquals(List.of("0,0,0", "1,0,0", "1,1,0"), keys(FurnitureShape.barriers(BENCH, fill, 0.30, true)));
        assertEquals(List.of("0,0,0", "1,0,0"), keys(FurnitureShape.barriers(BENCH, fill, 0.6, true)));
        assertEquals(4, FurnitureShape.barriers(BENCH, fill, 0, true).size(), "0 = every cell, as before");
        assertTrue(FurnitureShape.barriers(BENCH, fill, 0.3, false).isEmpty(), "not solid: never a barrier");
        assertEquals(1, FurnitureShape.barriers(ONE, new double[]{0.30}, 0.30, true).size(), "exactly the threshold keeps the barrier");
        assertEquals(0, FurnitureShape.barriers(ONE, new double[]{19 / 64.0}, 0.30, true).size(), "19 of 64 voxels is just under");
    }

    @Test
    void barrierCellsAreClickableWhole() {
        // a barrier cell's invisible faces would otherwise catch the crosshair in front of the model
        JsonObject m = model(cube(new double[]{2, 0, 2}, new double[]{14, 10, 14}));
        FurnitureShape.Shape s = FurnitureShape.shape(voxelize(ONE, m), ONE, 0.30, true);
        assertEquals(1, s.barriers().size());
        assertEquals(1, s.boxes().size());
        FurnitureShape.Box b = s.boxes().getFirst();
        assertEquals(0, b.y0(), 1e-9);
        assertEquals(16, b.y1(), 1e-9);
        // the same model without collision (not solid) is clicked only where it is
        FurnitureShape.Box n = FurnitureShape.shape(voxelize(ONE, m), ONE, 0.30, false).boxes().getFirst();
        assertEquals(10, n.y1(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------
    // Click boxes
    // ------------------------------------------------------------------------------------------

    @Test
    void lowBenchGetsLowBoxes() {
        // a 2x1x2 bench whose model is 18 px tall: boxes end at 18 px, not at the 32 px of its cells
        JsonObject m = model(cube(new double[]{0, 0, 0}, new double[]{32, 14, 16}), cube(new double[]{4, 14, 4}, new double[]{10, 18, 10}));
        FurnitureShape.Shape s = FurnitureShape.shape(voxelize(BENCH, m), BENCH, 0.30, true);
        assertCovers(s, voxelize(BENCH, m), BENCH);
        assertTrue(s.boxes().size() <= FurnitureShape.MAX_BOXES);
        assertEquals(18, s.boxes().stream().mapToDouble(FurnitureShape.Box::y1).max().orElseThrow(), 1e-9);
        assertEquals(List.of("0,0,0", "1,0,0"), keys(s.barriers()));
    }

    @Test
    void scatteredPartsStayWithinTheLimit() {
        // 20 little posts spread over a 3x1 footprint: merged down to at most MAX_BOXES, all still covered
        List<int[]> cells = List.of(new int[]{-1, 0, 0}, new int[]{0, 0, 0}, new int[]{1, 0, 0});
        List<JsonObject> posts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double x = -16 + (i * 7) % 46, z = (i * 5) % 14, h = 3 + (i * 3) % 12;
            posts.add(cube(new double[]{x, 0, z}, new double[]{x + 1.5, h, z + 1.5}));
        }
        JsonObject m = model(posts.toArray(JsonObject[]::new));
        FurnitureShape.Voxels v = voxelize(cells, m);
        FurnitureShape.Shape s = FurnitureShape.shape(v, cells, 0.30, true);
        assertTrue(s.boxes().size() <= FurnitureShape.MAX_BOXES, "boxes: " + s.boxes().size());
        assertCovers(s, v, cells);
    }

    @Test
    void lShapedFootprintNeverLeavesTheCells() {
        // an L of three cells filled whole: a square over the bounding box would take in the missing corner
        List<int[]> cells = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{0, 0, 1});
        JsonObject m = model(cube(new double[]{0, 0, 0}, new double[]{32, 16, 16}), cube(new double[]{0, 0, 16}, new double[]{16, 16, 32}));
        FurnitureShape.Voxels v = voxelize(cells, m);
        FurnitureShape.Shape s = FurnitureShape.shape(v, cells, 0.30, true);
        assertCovers(s, v, cells);
        assertEquals(3, s.boxes().size(), "no two cells of an L make a square: one box each");
    }

    @Test
    void emptyModelFallsBackToTheFullCells() {
        FurnitureShape.Shape s = FurnitureShape.shape(voxelize(BENCH, model()), BENCH, 0.30, true);
        assertFalse(s.fitted());
        assertEquals(4, s.barriers().size());
        assertEquals(2, s.boxes().size());
        assertEquals(32, s.boxes().getFirst().y1() - s.boxes().getFirst().y0(), 1e-9);
    }

    @Test
    void sameModelSameHashOtherModelOtherHash() {
        JsonObject a = model(cube(new double[]{0, 0, 0}, new double[]{32, 14, 16}));
        JsonObject b = model(cube(new double[]{0, 0, 0}, new double[]{32, 20, 16}));
        String ha = FurnitureShape.shape(voxelize(BENCH, a), BENCH, 0.3, true).hash();
        assertEquals(ha, FurnitureShape.shape(voxelize(BENCH, a), BENCH, 0.3, true).hash());
        assertFalse(ha.equals(FurnitureShape.shape(voxelize(BENCH, b), BENCH, 0.3, true).hash()));
        assertFalse(ha.equals(FurnitureShape.shape(voxelize(BENCH, a), BENCH, 0.0, true).hash()), "other barriers");
    }

    // ------------------------------------------------------------------------------------------

    static List<String> keys(List<int[]> cells) {
        return cells.stream().map(c -> c[0] + "," + c[1] + "," + c[2]).sorted().toList();
    }

    static boolean inCells(List<int[]> cells, int x, int y, int z) {
        for (int[] c : cells) if (c[0] == x && c[1] == y && c[2] == z) return true;
        return false;
    }

    /** Every voxel of the box is inside a cell, and the box keeps to the size limits. */
    static void assertBoxInside(FurnitureShape.Box b, List<int[]> cells) {
        assertTrue(b.size() <= FurnitureShape.MAX_SIZE * FurnitureShape.VOXEL && b.y1() - b.y0() <= FurnitureShape.MAX_HEIGHT * FurnitureShape.VOXEL + 1e-9, "too big: " + b);
        for (double x = b.x0() + 2; x < b.x1(); x += 4)
            for (double z = b.z0() + 2; z < b.z1(); z += 4)
                for (double y = Math.floor(b.y0() / 4) * 4 + 2; y < b.y1(); y += 4)
                    assertTrue(inCells(cells, (int) Math.floor(x / 16), (int) Math.floor(y / 16), (int) Math.floor(z / 16)),
                            "box " + b + " leaves the cells at " + x + "," + y + "," + z);
    }

    /** Every filled voxel and every barrier cell lies in a box; no box leaves the cells. */
    static void assertCovers(FurnitureShape.Shape s, FurnitureShape.Voxels v, List<int[]> cells) {
        for (FurnitureShape.Box b : s.boxes()) assertBoxInside(b, cells);
        for (int i = 0; i < v.nx; i++) for (int j = 0; j < v.ny; j++) for (int k = 0; k < v.nz; k++) {
            if (!v.filled(i, j, k)) continue;
            double x = v.edge(0, i) + 2, z = v.edge(2, k);
            double ylo = v.lo[v.idx(i, j, k)], yhi = v.hi[v.idx(i, j, k)];
            boolean hit = false;
            for (FurnitureShape.Box b : s.boxes()) {
                hit |= x > b.x0() && x < b.x1() && z + 2 > b.z0() && z + 2 < b.z1() && ylo >= b.y0() - 1e-6 && yhi <= b.y1() + 1e-6;
            }
            assertTrue(hit, "voxel " + i + "," + j + "," + k + " (y " + ylo + ".." + yhi + ") is not clickable");
        }
        for (int[] c : s.barriers()) {
            boolean hit = false;
            for (FurnitureShape.Box b : s.boxes()) {
                hit |= c[0] * 16 >= b.x0() && c[0] * 16 + 16 <= b.x1() && c[2] * 16 >= b.z0() && c[2] * 16 + 16 <= b.z1()
                        && c[1] * 16 >= b.y0() - 1e-6 && c[1] * 16 + 16 <= b.y1() + 1e-6;
            }
            // a barrier cell may also be covered by several boxes together; check per voxel then
            if (!hit) {
                for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) for (int k = 0; k < 4; k++) {
                    double x = c[0] * 16 + i * 4 + 2, y = c[1] * 16 + j * 4 + 2, z = c[2] * 16 + k * 4 + 2;
                    boolean in = false;
                    for (FurnitureShape.Box b : s.boxes()) in |= x > b.x0() && x < b.x1() && z > b.z0() && z < b.z1() && y > b.y0() && y < b.y1();
                    assertTrue(in, "barrier cell " + keys(List.of(c)) + " not clickable at " + x + "," + y + "," + z);
                }
            }
        }
    }
}
