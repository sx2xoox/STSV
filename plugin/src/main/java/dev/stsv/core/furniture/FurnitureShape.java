package dev.stsv.core.furniture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.PriorityQueue;

/**
 * What a furniture's model really fills, worked out once per model (FurnitureModule caches it per model id).
 *
 * The visible geometry (the converted model's cubes, with their rotation and inflate, the item's "fixed"
 * display transform, the furniture's translation/scale, and the extra part models at their positions,
 * spinning parts swept all the way round) is voxelized at 4 px (1/4 block) over the furniture's cells.
 * From that:
 * <ul>
 *   <li><b>barriers</b>: only the cells the model fills at least {@code furniture.barrier-fill} (by voxels);</li>
 *   <li><b>click boxes</b>: at most {@link #MAX_BOXES} square-based boxes (Interaction entities are squares in
 *       X/Z) that cover every filled voxel and every voxel of a barrier cell (an invisible barrier in front of
 *       the model would otherwise catch the crosshair), stay inside the cells, and take in as little empty air
 *       as possible. Their bottom and top follow the real geometry, not the voxel grid.</li>
 * </ul>
 * Space: model space of the furniture, in px (16 = 1 block); cell (0,0,0) spans 0..16 on each axis.
 * Pure maths (no server), so it is tested directly and over the real machine models.
 */
public final class FurnitureShape {

    /** Voxel edge in px (1/4 block). */
    public static final int VOXEL = 4;
    /** Click boxes per furniture at most (the merge stops there; more only if the cells allow no merge). */
    public static final int MAX_BOXES = 6;
    /**
     * Largest box in voxels: 4 blocks wide, 3 tall. The client finds entities to pick through the sections
     * near the look ray (searched 2 blocks around a box grown by 1), by the entity's position, which is the
     * bottom centre of the box; so a box must not reach more than ~3 blocks from it or its far end can be
     * unpickable when it lies in another section.
     */
    static final int MAX_SIZE = 16, MAX_HEIGHT = 12;
    /** Spinning parts are sampled at this many angles per turn (10 degrees: the rim moves ~1 px between samples). */
    static final int SPIN_STEPS = 36;
    private static final double EPS = 1e-3;   // px: a cube face exactly on a voxel edge does not spill into the next
    private static final double STEP = 0.5;   // px: sample spacing of rotated cubes

    /** A click box in model space (px): footprint [x0, x0+size] x [z0, z0+size], height [y0, y1]. */
    public record Box(double x0, double z0, double size, double y0, double y1) {
        public double x1() { return x0 + size; }
        public double z1() { return z0 + size; }
    }

    /**
     * @param cells    the furniture's cells (model space)
     * @param boxes    click boxes (model space, px)
     * @param barriers the cells that keep a barrier block
     * @param fill     share of each cell (same order as cells) the model fills, 0..1
     * @param fitted   false = no model geometry to go by: today's full-cell behaviour (every cell, full boxes)
     * @param hash     short id of boxes + barriers, stored on placed furniture to see when it must be redone
     */
    public record Shape(List<int[]> cells, List<Box> boxes, List<int[]> barriers, double[] fill, boolean fitted, String hash) {
        public boolean barrier(int[] cell) {
            for (int[] b : barriers) if (b[0] == cell[0] && b[1] == cell[1] && b[2] == cell[2]) return true;
            return false;
        }
    }

    private FurnitureShape() {}

    // ------------------------------------------------------------------------------------------
    // Voxels
    // ------------------------------------------------------------------------------------------

    /** Voxel grid over the bounding box of the cells; only voxels inside a cell are ever filled. */
    public static final class Voxels {
        final int cx0, cy0, cz0;   // lowest cell
        final int nx, ny, nz;      // size in voxels
        final boolean[] inside, filled;
        final float[] lo, hi;      // per filled voxel: lowest and highest geometry inside it (px)

        Voxels(List<int[]> cells) {
            if (cells.isEmpty()) throw new IllegalArgumentException("no cells");
            int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int[] c : cells) for (int a = 0; a < 3; a++) {
                min[a] = Math.min(min[a], c[a]);
                max[a] = Math.max(max[a], c[a]);
            }
            int per = 16 / VOXEL;
            cx0 = min[0]; cy0 = min[1]; cz0 = min[2];
            nx = (max[0] - min[0] + 1) * per; ny = (max[1] - min[1] + 1) * per; nz = (max[2] - min[2] + 1) * per;
            inside = new boolean[nx * ny * nz];
            filled = new boolean[inside.length];
            lo = new float[inside.length];
            hi = new float[inside.length];
            for (int[] c : cells) {
                for (int i = 0; i < per; i++) for (int j = 0; j < per; j++) for (int k = 0; k < per; k++) {
                    inside[idx((c[0] - cx0) * per + i, (c[1] - cy0) * per + j, (c[2] - cz0) * per + k)] = true;
                }
            }
        }

        int idx(int i, int j, int k) {
            return (i * ny + j) * nz + k;
        }

        /** px coordinate of the low edge of voxel index i on an axis (0 x, 1 y, 2 z). */
        double edge(int axis, int i) {
            int c0 = axis == 0 ? cx0 : axis == 1 ? cy0 : cz0;
            return c0 * 16.0 + i * VOXEL;
        }

        int n(int axis) {
            return axis == 0 ? nx : axis == 1 ? ny : nz;
        }

        public boolean filled(int i, int j, int k) {
            return filled[idx(i, j, k)];
        }

        public int filledCount() {
            int n = 0;
            for (boolean b : filled) if (b) n++;
            return n;
        }

        /** Marks the voxels of the px box [x0..x1] x [y0..y1] x [z0..z1] (cell space) as filled. */
        void fill(double x0, double y0, double z0, double x1, double y1, double z1) {
            int[] ia = range(0, x0, x1), ja = range(1, y0, y1), ka = range(2, z0, z1);
            if (ia == null || ja == null || ka == null) return;
            for (int i = ia[0]; i <= ia[1]; i++) for (int j = ja[0]; j <= ja[1]; j++) for (int k = ka[0]; k <= ka[1]; k++) {
                double vy = edge(1, j);
                mark(i, j, k, Math.max(y0, vy), Math.min(y1, vy + VOXEL));
            }
        }

        /** Marks the voxel holding a sampled point. */
        void point(double x, double y, double z) {
            int i = (int) Math.floor((x - edge(0, 0)) / VOXEL), j = (int) Math.floor((y - edge(1, 0)) / VOXEL),
                    k = (int) Math.floor((z - edge(2, 0)) / VOXEL);
            if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) return;
            double vy = edge(1, j);
            mark(i, j, k, Math.clamp(y, vy, vy + VOXEL), Math.clamp(y, vy, vy + VOXEL));
        }

        private void mark(int i, int j, int k, double ylo, double yhi) {
            int id = idx(i, j, k);
            if (!inside[id]) return; // outside the furniture's cells: never clickable, never a barrier
            if (!filled[id]) {
                filled[id] = true;
                lo[id] = (float) ylo;
                hi[id] = (float) yhi;
            } else {
                lo[id] = Math.min(lo[id], (float) ylo);
                hi[id] = Math.max(hi[id], (float) yhi);
            }
        }

        /** Voxel indices [first, last] an extent [a, b] (px) touches; a flat extent on a voxel edge touches both sides. */
        private int[] range(int axis, double a, double b) {
            double g = edge(axis, 0);
            int i0, i1;
            if (b - a > 2 * EPS) {
                i0 = (int) Math.floor((a + EPS - g) / VOXEL);
                i1 = (int) Math.floor((b - EPS - g) / VOXEL);
            } else {
                double mid = (a + b) / 2;
                i0 = (int) Math.floor((mid - EPS - g) / VOXEL);
                i1 = (int) Math.floor((mid + EPS - g) / VOXEL);
            }
            i0 = Math.max(i0, 0);
            i1 = Math.min(i1, n(axis) - 1);
            return i0 > i1 ? null : new int[]{i0, i1};
        }
    }

    public static Voxels voxels(List<int[]> cells) {
        return new Voxels(cells);
    }

    /**
     * Where the main model's points (its own px) land in the furniture's model space: the display's "fixed"
     * transform, then the furniture's translation/scale (blocks), as the item display renders it. The 180 degree
     * turn of item displays and the cells' turn cancel out (see FurnitureModule.rotate), so x/z of the
     * translation flip. yawOffset is furniture.display-yaw-offset (normally 0).
     */
    public static Matrix4d mainPlacement(JsonObject model, Vector3f translation, Vector3f scale, double yawOffset) {
        Matrix4d m = new Matrix4d().translate(8, 8, 8).rotateY(-Math.toRadians(yawOffset))
                .translate(-16.0 * translation.x, 16.0 * translation.y, -16.0 * translation.z)
                .scale(16.0 * scale.x, 16.0 * scale.y, 16.0 * scale.z);
        return applyDisplay(m, model);
    }

    /**
     * A part model at its position (px, the part's centre), turned by angle (radians) around its spin axis
     * ("x", "y", "z" or null).
     */
    public static Matrix4d partPlacement(JsonObject model, Vector3f position, String spinAxis, double angle, double yawOffset) {
        Matrix4d m = new Matrix4d().translate(position.x, position.y, position.z).rotateY(-Math.toRadians(yawOffset)).scale(16);
        if (spinAxis != null) {
            switch (spinAxis) {
                case "x" -> m.rotateX(angle);
                case "z" -> m.rotateZ(angle);
                default -> m.rotateY(angle);
            }
        }
        return applyDisplay(m, model);
    }

    /** The item's display.fixed (translate, rotate XYZ, scale; then the model's 0..16 centred), as vanilla applies it. */
    private static Matrix4d applyDisplay(Matrix4d m, JsonObject model) {
        JsonObject fixed = model.has("display") && model.getAsJsonObject("display").has("fixed")
                ? model.getAsJsonObject("display").getAsJsonObject("fixed") : null;
        if (fixed != null) {
            double[] t = vec(fixed, "translation", 0), r = vec(fixed, "rotation", 0), s = vec(fixed, "scale", 1);
            m.translate(Math.clamp(t[0], -80, 80) / 16, Math.clamp(t[1], -80, 80) / 16, Math.clamp(t[2], -80, 80) / 16);
            m.rotate(new Quaterniond().rotationXYZ(Math.toRadians(r[0]), Math.toRadians(r[1]), Math.toRadians(r[2])));
            m.scale(Math.clamp(s[0], -4, 4), Math.clamp(s[1], -4, 4), Math.clamp(s[2], -4, 4));
        }
        return m.translate(-0.5, -0.5, -0.5).scale(1 / 16.0);
    }

    /** Fills the voxels of every visible cube of a converted model (vanilla model JSON), placed by m. */
    public static void addModel(Voxels v, JsonObject model, Matrix4d m) {
        JsonArray elements = model.has("elements") ? model.getAsJsonArray("elements") : new JsonArray();
        for (JsonElement je : elements) {
            JsonObject el = je.getAsJsonObject();
            if (!el.has("faces") || el.getAsJsonObject("faces").isEmpty()) continue; // nothing drawn
            addCube(v, vec(el, "from", 0), vec(el, "to", 0), elementPlacement(el, m));
        }
    }

    /** One element's placement: m, then the element's own rotation (origin, angle, rescale). */
    private static Matrix4d elementPlacement(JsonObject el, Matrix4d m) {
        Matrix4d em = new Matrix4d(m);
        if (el.has("rotation")) {
            JsonObject r = el.getAsJsonObject("rotation");
            double angle = Math.toRadians(r.get("angle").getAsDouble());
            String axis = r.has("axis") ? r.get("axis").getAsString() : "y";
            double[] o = vec(r, "origin", 8);
            boolean rescale = r.has("rescale") && r.get("rescale").getAsBoolean();
            double s = rescale && Math.cos(angle) != 0 ? 1 / Math.abs(Math.cos(angle)) : 1;
            em.translate(o[0], o[1], o[2]);
            switch (axis) {
                case "x" -> em.scale(1, s, s).rotateX(angle);
                case "z" -> em.scale(s, s, 1).rotateZ(angle);
                default -> em.scale(s, 1, s).rotateY(angle);
            }
            em.translate(-o[0], -o[1], -o[2]);
        }
        return em;
    }

    /**
     * Exact bounds of every visible cube of a model placed by m (rotated cubes by their corners), px:
     * {minX, minY, minZ, maxX, maxY, maxZ}. Used to check that a piece sits in the middle of its cells.
     */
    public static double[] bounds(JsonObject model, Matrix4d m) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        JsonArray elements = model.has("elements") ? model.getAsJsonArray("elements") : new JsonArray();
        Vector3d p = new Vector3d();
        for (JsonElement je : elements) {
            JsonObject el = je.getAsJsonObject();
            if (!el.has("faces") || el.getAsJsonObject("faces").isEmpty()) continue;
            double[] from = vec(el, "from", 0), to = vec(el, "to", 0);
            Matrix4d em = elementPlacement(el, m);
            for (int c = 0; c < 8; c++) {
                em.transformPosition(p.set((c & 1) == 0 ? from[0] : to[0], (c & 2) == 0 ? from[1] : to[1], (c & 4) == 0 ? from[2] : to[2]));
                b[0] = Math.min(b[0], p.x); b[1] = Math.min(b[1], p.y); b[2] = Math.min(b[2], p.z);
                b[3] = Math.max(b[3], p.x); b[4] = Math.max(b[4], p.y); b[5] = Math.max(b[5], p.z);
            }
        }
        return b;
    }

    /** One cube (its own px, from..to) placed by m. Axis-aligned placements are filled exactly, others sampled. */
    static void addCube(Voxels v, double[] from, double[] to, Matrix4d m) {
        double[] a = {Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])};
        double[] b = {Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])};
        if (axisAligned(m)) {
            Vector3d p = m.transformPosition(new Vector3d(a[0], a[1], a[2]));
            Vector3d q = m.transformPosition(new Vector3d(b[0], b[1], b[2]));
            v.fill(Math.min(p.x, q.x), Math.min(p.y, q.y), Math.min(p.z, q.z), Math.max(p.x, q.x), Math.max(p.y, q.y), Math.max(p.z, q.z));
            return;
        }
        // sample on a grid fine enough in the furniture's space; each axis slightly inside the cube (a face on a
        // voxel edge stays out of the next voxel), a flat axis on both sides of itself
        double[][] samples = new double[3][];
        for (int ax = 0; ax < 3; ax++) {
            double len = b[ax] - a[ax];
            double unit = new Vector3d(m.m00(), m.m01(), m.m02()).length();
            if (ax == 1) unit = new Vector3d(m.m10(), m.m11(), m.m12()).length();
            if (ax == 2) unit = new Vector3d(m.m20(), m.m21(), m.m22()).length();
            double e = unit > 0 ? EPS / unit : EPS;
            if (len <= 2 * e) {
                double mid = (a[ax] + b[ax]) / 2;
                samples[ax] = new double[]{mid - e, mid + e};
            } else {
                int n = Math.max(1, (int) Math.ceil(len * unit / STEP));
                samples[ax] = new double[n + 1];
                for (int i = 0; i <= n; i++) samples[ax][i] = (a[ax] + e) + (len - 2 * e) * i / n;
            }
        }
        Vector3d p = new Vector3d();
        for (double x : samples[0]) for (double y : samples[1]) for (double z : samples[2]) {
            m.transformPosition(p.set(x, y, z));
            v.point(p.x, p.y, p.z);
        }
    }

    private static boolean axisAligned(Matrix4d m) {
        double[][] r = {{m.m00(), m.m10(), m.m20()}, {m.m01(), m.m11(), m.m21()}, {m.m02(), m.m12(), m.m22()}};
        for (int row = 0; row < 3; row++) {
            int nonZero = 0;
            for (int col = 0; col < 3; col++) if (Math.abs(r[row][col]) > 1e-9) nonZero++;
            if (nonZero != 1) return false;
        }
        for (int col = 0; col < 3; col++) {
            int nonZero = 0;
            for (int row = 0; row < 3; row++) if (Math.abs(r[row][col]) > 1e-9) nonZero++;
            if (nonZero != 1) return false;
        }
        return true;
    }

    /**
     * The whole furniture: main model plus parts (a spinning part counts at every angle it turns through).
     *
     * @param parts part models with their position and spin axis; a null model is skipped
     */
    public static Voxels voxelize(List<int[]> cells, JsonObject model, Vector3f translation, Vector3f scale,
                                  List<PartModel> parts, double yawOffset) {
        Voxels v = new Voxels(cells);
        addModel(v, model, mainPlacement(model, translation, scale, yawOffset));
        for (PartModel p : parts) {
            if (p.model() == null) continue;
            int steps = p.spinAxis() == null ? 1 : SPIN_STEPS;
            for (int s = 0; s < steps; s++) {
                addModel(v, p.model(), partPlacement(p.model(), p.position(), p.spinAxis(), 2 * Math.PI * s / steps, yawOffset));
            }
        }
        return v;
    }

    /** A part's model, where its centre sits (px) and the axis it spins around (null = still). */
    public record PartModel(JsonObject model, Vector3f position, String spinAxis) {}

    // ------------------------------------------------------------------------------------------
    // Barriers and click boxes
    // ------------------------------------------------------------------------------------------

    /** Share of each cell (same order as cells) the voxels fill. */
    public static double[] fill(Voxels v, List<int[]> cells) {
        int per = 16 / VOXEL;
        double[] out = new double[cells.size()];
        for (int n = 0; n < cells.size(); n++) {
            int[] c = cells.get(n);
            int count = 0;
            for (int i = 0; i < per; i++) for (int j = 0; j < per; j++) for (int k = 0; k < per; k++) {
                if (v.filled[v.idx((c[0] - v.cx0) * per + i, (c[1] - v.cy0) * per + j, (c[2] - v.cz0) * per + k)]) count++;
            }
            out[n] = count / (double) (per * per * per);
        }
        return out;
    }

    /** The cells that keep a barrier: filled at least minFill (none when the furniture is not solid). */
    public static List<int[]> barriers(List<int[]> cells, double[] fill, double minFill, boolean solid) {
        List<int[]> out = new ArrayList<>();
        if (!solid) return out;
        for (int n = 0; n < cells.size(); n++) if (fill[n] >= minFill - 1e-9) out.add(cells.get(n));
        return out;
    }

    /** Barriers and click boxes of a voxelized furniture. An empty model (or one wholly outside the cells) gets the full cells. */
    public static Shape shape(Voxels v, List<int[]> cells, double minFill, boolean solid) {
        if (v.filledCount() == 0) return fullCells(cells, solid);
        double[] fill = fill(v, cells);
        List<int[]> barriers = barriers(cells, fill, minFill, solid);
        int per = 16 / VOXEL;
        // must be clickable: what the model fills, and all of a barrier cell (its invisible faces catch the crosshair)
        boolean[] must = v.filled.clone();
        float[] lo = v.lo.clone(), hi = v.hi.clone();
        for (int[] c : barriers) {
            for (int i = 0; i < per; i++) for (int j = 0; j < per; j++) for (int k = 0; k < per; k++) {
                int vj = (c[1] - v.cy0) * per + j;
                int id = v.idx((c[0] - v.cx0) * per + i, vj, (c[2] - v.cz0) * per + k);
                must[id] = true;
                lo[id] = (float) v.edge(1, vj);
                hi[id] = (float) v.edge(1, vj) + VOXEL;
            }
        }
        List<Box> boxes = new ArrayList<>();
        for (int[] b : cover(v, must)) {
            // trim bottom and top to the geometry inside the box
            float y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (int i = b[0]; i < b[0] + b[2]; i++) for (int k = b[1]; k < b[1] + b[2]; k++) for (int j = b[3]; j < b[4]; j++) {
                int id = v.idx(i, j, k);
                if (!must[id]) continue;
                y0 = Math.min(y0, lo[id]);
                y1 = Math.max(y1, hi[id]);
            }
            boxes.add(new Box(v.edge(0, b[0]), v.edge(2, b[1]), b[2] * VOXEL, Math.floor(y0 * 1000) / 1000, Math.ceil(y1 * 1000) / 1000));
        }
        boxes.sort(Comparator.comparingDouble(Box::y0).thenComparingDouble(Box::x0).thenComparingDouble(Box::z0));
        return new Shape(List.copyOf(cells), List.copyOf(boxes), List.copyOf(barriers), fill, true, hash(boxes, barriers, solid));
    }

    /** No geometry to go by: every cell is clicked and (if solid) blocked whole, as before click boxes were fitted. */
    public static Shape fullCells(List<int[]> cells, boolean solid) {
        List<Box> boxes = new ArrayList<>();
        for (int[] p : FurnitureModule.coverWithPrisms(cells)) {
            boxes.add(new Box(p[0] * 16.0, p[2] * 16.0, p[3] * 16.0, p[1] * 16.0, (p[1] + p[4]) * 16.0));
        }
        double[] fill = new double[cells.size()];
        java.util.Arrays.fill(fill, 1);
        List<int[]> barriers = solid ? List.copyOf(cells) : List.of();
        return new Shape(List.copyOf(cells), List.copyOf(boxes), barriers, fill, false, hash(boxes, barriers, solid));
    }

    private static String hash(List<Box> boxes, List<int[]> barriers, boolean solid) {
        StringBuilder sb = new StringBuilder(solid ? "s" : "n");
        for (Box b : boxes) sb.append('|').append(b.x0()).append(',').append(b.z0()).append(',').append(b.size()).append(',').append(b.y0()).append(',').append(b.y1());
        sb.append('#');
        for (int[] c : barriers) sb.append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(';');
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What one more click box is worth in voxels of clickable air (8 = an eighth of a block): a cover with a box
     * fewer may take in that much more air. Keeps thin slivers (a 2 px ledge, a lid) from getting boxes of their own.
     */
    static final int BOX_COST = 8;
    /** Weights of air against newly covered voxels tried by the greedy cover (0 = biggest boxes, more = tighter). */
    private static final double[] AIR_WEIGHTS = {0, 0.02, 0.05, 0.1, 0.15, 0.25, 0.35, 0.5, 0.75, 1, 1.5, 2, 3, 5, 8};

    /**
     * Covers every "must" voxel with at most {@link #MAX_BOXES} square-based boxes inside the cells, taking in as
     * little air (voxels that need no cover) as possible.
     *
     * Greedy set cover over every square box that fits in the cells: each step takes the box with the most newly
     * covered voxels minus w * its air. That is run for a range of weights w (small w: few big boxes, large w: many
     * tight ones), each result tightened (a box shrinks to what only it covers, or goes), and the one within the
     * limit with the least air + {@link #BOX_COST} per box wins. Each result is first merged pairwise (always the
     * merge adding the least air) down to the limit, and further while a merge costs less air than a box is worth.
     *
     * @return voxel boxes [x0, z0, size, y0, y1) (indices into the grid)
     */
    static List<int[]> cover(Voxels v, boolean[] must) {
        Sums sums = new Sums(v, must);
        int total = sums.must(0, 0, 0, v.nx, v.ny, v.nz);
        if (total == 0) return List.of();
        List<int[]> candidates = new ArrayList<>();
        for (int size = 1; size <= Math.min(MAX_SIZE, Math.min(v.nx, v.nz)); size++) {
            for (int x = 0; x + size <= v.nx; x++) for (int z = 0; z + size <= v.nz; z++) {
                for (int y0 = 0; y0 < v.ny; y0++) {
                    for (int y1 = y0 + 1; y1 <= Math.min(v.ny, y0 + MAX_HEIGHT); y1++) {
                        if (sums.outside(x, y0, z, x + size, y1, z + size) > 0) break; // taller only gets worse
                        // tight in height: must voxels in its bottom and top layer (others are never better)
                        if (sums.must(x, y0, z, x + size, y0 + 1, z + size) == 0 || sums.must(x, y1 - 1, z, x + size, y1, z + size) == 0) continue;
                        candidates.add(new int[]{x, z, size, y0, y1});
                    }
                }
            }
        }
        List<int[]> best = null;
        long bestCost = Long.MAX_VALUE;
        for (double w : AIR_WEIGHTS) {
            List<int[]> boxes = greedy(v, must, sums, candidates, w, total);
            tighten(v, must, sums, boxes);
            boxes = mergeDown(v, sums, boxes);
            tighten(v, must, sums, boxes);
            if (boxes.size() > MAX_BOXES && best != null) continue;
            long cost = unionAir(v, must, boxes) + (long) BOX_COST * boxes.size();
            if (best == null || (best.size() > MAX_BOXES && boxes.size() < best.size())
                    || (boxes.size() <= MAX_BOXES && (cost < bestCost || (cost == bestCost && boxes.size() < best.size())))) {
                best = boxes;
                bestCost = cost;
            }
        }
        return best;
    }

    /** One greedy cover (very tight weights may give many boxes: they are merged down afterwards). */
    private static List<int[]> greedy(Voxels v, boolean[] must, Sums sums, List<int[]> candidates, double w, int total) {
        boolean[] left = must.clone();
        int remaining = total;
        List<int[]> out = new ArrayList<>();
        while (remaining > 0) {
            Sums open = new Sums(v, left);
            int[] pick = null;
            double bestScore = 0;
            long pickAir = 0;
            for (int[] c : candidates) {
                int fresh = open.must(c[0], c[3], c[1], c[0] + c[2], c[4], c[1] + c[2]);
                if (fresh == 0) continue;
                long air = (long) c[2] * c[2] * (c[4] - c[3]) - sums.must(c[0], c[3], c[1], c[0] + c[2], c[4], c[1] + c[2]);
                double score = fresh - w * air;
                if (pick == null || score > bestScore || (score == bestScore && air < pickAir)) {
                    pick = c;
                    bestScore = score;
                    pickAir = air;
                }
            }
            out.add(pick);
            for (int i = pick[0]; i < pick[0] + pick[2]; i++) for (int k = pick[1]; k < pick[1] + pick[2]; k++)
                for (int j = pick[3]; j < pick[4]; j++) {
                    int id = v.idx(i, j, k);
                    if (left[id]) {
                        left[id] = false;
                        remaining--;
                    }
                }
        }
        return out;
    }

    /**
     * Each box shrinks to the smallest square (within itself) around the must voxels no other box covers; a box
     * that covers nothing alone goes. Repeated until nothing changes.
     */
    static void tighten(Voxels v, boolean[] must, Sums sums, List<int[]> boxes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int b = 0; b < boxes.size() && !changed; b++) {
                int[] box = boxes.get(b);
                int x0 = Integer.MAX_VALUE, x1 = -1, y0 = Integer.MAX_VALUE, y1 = -1, z0 = Integer.MAX_VALUE, z1 = -1;
                for (int i = box[0]; i < box[0] + box[2]; i++) for (int k = box[1]; k < box[1] + box[2]; k++)
                    for (int j = box[3]; j < box[4]; j++) {
                        if (!must[v.idx(i, j, k)] || coveredByOther(boxes, b, i, j, k)) continue;
                        x0 = Math.min(x0, i); x1 = Math.max(x1, i);
                        y0 = Math.min(y0, j); y1 = Math.max(y1, j);
                        z0 = Math.min(z0, k); z1 = Math.max(z1, k);
                    }
                if (x1 < 0) {
                    boxes.remove(b);
                    changed = true;
                    continue;
                }
                int size = Math.max(x1 - x0 + 1, z1 - z0 + 1);
                int[] fit = null;
                long fitAir = Long.MAX_VALUE;
                for (int sx = Math.max(box[0], x1 + 1 - size); sx <= Math.min(x0, box[0] + box[2] - size); sx++) {
                    for (int sz = Math.max(box[1], z1 + 1 - size); sz <= Math.min(z0, box[1] + box[2] - size); sz++) {
                        long air = (long) size * size * (y1 + 1 - y0) - sums.must(sx, y0, sz, sx + size, y1 + 1, sz + size);
                        if (air < fitAir) {
                            fitAir = air;
                            fit = new int[]{sx, sz, size, y0, y1 + 1};
                        }
                    }
                }
                if (fit != null && !java.util.Arrays.equals(fit, box)) {
                    boxes.set(b, fit);
                    changed = true;
                }
            }
        }
    }

    private static boolean coveredByOther(List<int[]> boxes, int self, int i, int j, int k) {
        for (int o = 0; o < boxes.size(); o++) {
            if (o == self) continue;
            int[] b = boxes.get(o);
            if (i >= b[0] && i < b[0] + b[2] && k >= b[1] && k < b[1] + b[2] && j >= b[3] && j < b[4]) return true;
        }
        return false;
    }

    /** Voxels inside any of the boxes that need no cover. */
    static long unionAir(Voxels v, boolean[] must, List<int[]> boxes) {
        boolean[] in = new boolean[must.length];
        long air = 0;
        for (int[] b : boxes) for (int i = b[0]; i < b[0] + b[2]; i++) for (int k = b[1]; k < b[1] + b[2]; k++)
            for (int j = b[3]; j < b[4]; j++) {
                int id = v.idx(i, j, k);
                if (!in[id]) {
                    in[id] = true;
                    if (!must[id]) air++;
                }
            }
        return air;
    }

    /**
     * Merges boxes pairwise, always the pair whose merged box adds the least air: down to the limit, and further
     * while a merge adds less air than a box is worth ({@link #BOX_COST}).
     */
    private static List<int[]> mergeDown(Voxels v, Sums sums, List<int[]> start) {
        List<int[]> boxes = new ArrayList<>(start);
        boolean[] alive = new boolean[boxes.size() * 2 + 1];
        java.util.Arrays.fill(alive, 0, boxes.size(), true);
        int count = boxes.size();
        PriorityQueue<Merge> queue = new PriorityQueue<>();
        for (int a = 0; a < boxes.size(); a++) for (int b = a + 1; b < boxes.size(); b++) {
            Merge m = merge(v, sums, boxes, a, b);
            if (m != null) queue.add(m);
        }
        while (!queue.isEmpty()) {
            Merge m = queue.poll();
            if (!alive[m.a] || !alive[m.b]) continue;
            if (count <= MAX_BOXES && m.cost >= BOX_COST) break;
            alive[m.a] = alive[m.b] = false;
            count--;
            int id = boxes.size();
            boxes.add(m.box);
            if (alive.length <= id) alive = java.util.Arrays.copyOf(alive, alive.length * 2);
            alive[id] = true;
            // boxes the new one swallowed whole are gone for free
            for (int o = 0; o < id; o++) {
                if (alive[o] && contains(m.box, boxes.get(o))) {
                    alive[o] = false;
                    count--;
                }
            }
            for (int o = 0; o < id; o++) {
                if (!alive[o]) continue;
                Merge n = merge(v, sums, boxes, o, id);
                if (n != null) queue.add(n);
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) if (i < alive.length && alive[i]) out.add(boxes.get(i));
        return out;
    }

    private record Merge(int a, int b, int[] box, long cost, long volume) implements Comparable<Merge> {
        @Override
        public int compareTo(Merge o) {
            int c = Long.compare(cost, o.cost);
            return c != 0 ? c : Long.compare(volume, o.volume);
        }
    }

    private static boolean contains(int[] outer, int[] inner) {
        return inner[0] >= outer[0] && inner[0] + inner[2] <= outer[0] + outer[2]
                && inner[1] >= outer[1] && inner[1] + inner[2] <= outer[1] + outer[2]
                && inner[3] >= outer[3] && inner[4] <= outer[4];
    }

    /** The best square box holding boxes a and b, inside the cells; null if there is none (or it is too big). */
    private static Merge merge(Voxels v, Sums sums, List<int[]> boxes, int a, int b) {
        int[] p = boxes.get(a), q = boxes.get(b);
        int x0 = Math.min(p[0], q[0]), x1 = Math.max(p[0] + p[2], q[0] + q[2]);
        int z0 = Math.min(p[1], q[1]), z1 = Math.max(p[1] + p[2], q[1] + q[2]);
        int y0 = Math.min(p[3], q[3]), y1 = Math.max(p[4], q[4]);
        int size = Math.max(x1 - x0, z1 - z0);
        if (size > MAX_SIZE || y1 - y0 > MAX_HEIGHT) return null;
        int[] best = null;
        long bestAir = Long.MAX_VALUE;
        for (int sx = Math.max(0, x1 - size); sx <= Math.min(x0, v.nx - size); sx++) {
            for (int sz = Math.max(0, z1 - size); sz <= Math.min(z0, v.nz - size); sz++) {
                if (sums.outside(sx, y0, sz, sx + size, y1, sz + size) > 0) continue;
                long air = (long) size * size * (y1 - y0) - sums.must(sx, y0, sz, sx + size, y1, sz + size);
                if (air < bestAir) {
                    bestAir = air;
                    best = new int[]{sx, sz, size, y0, y1};
                }
            }
        }
        if (best == null) return null;
        long cost = bestAir - air(sums, p) - air(sums, q);
        return new Merge(a, b, best, cost, (long) size * size * (y1 - y0));
    }

    private static long air(Sums sums, int[] b) {
        return (long) b[2] * b[2] * (b[4] - b[3]) - sums.must(b[0], b[3], b[1], b[0] + b[2], b[4], b[1] + b[2]);
    }

    /** 3D prefix sums: must voxels and voxels outside the cells in any box, in constant time. */
    static final class Sums {
        final int nx, ny, nz;
        final int[] must, out;

        Sums(Voxels v, boolean[] mustVoxels) {
            nx = v.nx; ny = v.ny; nz = v.nz;
            must = new int[(nx + 1) * (ny + 1) * (nz + 1)];
            out = new int[must.length];
            for (int i = 1; i <= nx; i++) for (int j = 1; j <= ny; j++) for (int k = 1; k <= nz; k++) {
                int id = v.idx(i - 1, j - 1, k - 1);
                must[at(i, j, k)] = (mustVoxels[id] ? 1 : 0) + sum3(must, i, j, k);
                out[at(i, j, k)] = (v.inside[id] ? 0 : 1) + sum3(out, i, j, k);
            }
        }

        private int at(int i, int j, int k) {
            return (i * (ny + 1) + j) * (nz + 1) + k;
        }

        private int sum3(int[] s, int i, int j, int k) {
            return s[at(i - 1, j, k)] + s[at(i, j - 1, k)] + s[at(i, j, k - 1)]
                    - s[at(i - 1, j - 1, k)] - s[at(i - 1, j, k - 1)] - s[at(i, j - 1, k - 1)]
                    + s[at(i - 1, j - 1, k - 1)];
        }

        private int box(int[] s, int x0, int y0, int z0, int x1, int y1, int z1) {
            return s[at(x1, y1, z1)] - s[at(x0, y1, z1)] - s[at(x1, y0, z1)] - s[at(x1, y1, z0)]
                    + s[at(x0, y0, z1)] + s[at(x0, y1, z0)] + s[at(x1, y0, z0)] - s[at(x0, y0, z0)];
        }

        int must(int x0, int y0, int z0, int x1, int y1, int z1) {
            return box(must, x0, y0, z0, x1, y1, z1);
        }

        int outside(int x0, int y0, int z0, int x1, int y1, int z1) {
            return box(out, x0, y0, z0, x1, y1, z1);
        }
    }

    private static double[] vec(JsonObject o, String key, double def) {
        double[] v = {def, def, def};
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsDouble();
        }
        return v;
    }
}
