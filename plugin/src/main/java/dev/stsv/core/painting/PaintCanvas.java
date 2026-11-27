package dev.stsv.core.painting;

import java.util.Arrays;
import java.util.BitSet;

/**
 * A painting's 128 x 128 pixels (map colour bytes, row by row: index = v * 128 + u, u to the right, v down) and the
 * drawing on them. Drawing works on a 32 x 32 grid of 4 x 4-pixel cells (a brush stamp, a line of stamps, a flood
 * fill, all in cells); the pixels stay 128 x 128 so a map shows them as they are and older paintings load unchanged. Every change goes into a {@link Stroke} (the pixel's
 * colour before the stroke first touched it), so a stroke can be undone. No Bukkit: pure, tested logic.
 */
public final class PaintCanvas {

    public static final int SIZE = 128;
    public static final int PIXELS = SIZE * SIZE;
    /** a drawing cell is CELL x CELL pixels; the grid is GRID x GRID cells */
    public static final int CELL = 4, GRID = SIZE / CELL, CELLS = GRID * GRID;
    /** Brush sizes a painter can pick (cells across). */
    public static final int[] BRUSH_SIZES = {1, 2, 3};

    private final byte[] px;

    /** A blank canvas (all {@link MapColors#CANVAS}). */
    public PaintCanvas() {
        px = new byte[PIXELS];
        Arrays.fill(px, MapColors.CANVAS);
    }

    /** A canvas over these pixels (copied; invalid colours become canvas white). */
    public PaintCanvas(byte[] pixels) {
        if (pixels.length != PIXELS) throw new IllegalArgumentException("pixels: " + pixels.length);
        px = pixels.clone();
        for (int i = 0; i < PIXELS; i++) if (!MapColors.valid(px[i])) px[i] = MapColors.CANVAS;
    }

    public byte get(int u, int v) {
        return px[v * SIZE + u];
    }

    public static boolean inside(int u, int v) {
        return u >= 0 && v >= 0 && u < SIZE && v < SIZE;
    }

    /** A copy of the pixels. */
    public byte[] pixels() {
        return px.clone();
    }

    /** The pixels themselves (read only, for the renderer). */
    byte[] raw() {
        return px;
    }

    /** Sets a pixel (outside the canvas: nothing). True when it changed. */
    public boolean set(int u, int v, byte color, Stroke stroke) {
        if (!inside(u, v)) return false;
        int i = v * SIZE + u;
        if (px[i] == color) return false;
        if (stroke != null) stroke.touch(i, px[i]);
        px[i] = color;
        return true;
    }

    /** The colour of a cell: its top-left pixel (a cell of an older, pixel-drawn painting may hold more than one). */
    public byte cell(int cu, int cv) {
        return get(cu * CELL, cv * CELL);
    }

    public static boolean insideGrid(int cu, int cv) {
        return cu >= 0 && cv >= 0 && cu < GRID && cv < GRID;
    }

    /** The cell a canvas pixel (u or v) lies in. */
    public static int cellOf(int pixel) {
        return Math.floorDiv(pixel, CELL);
    }

    /** Paints a whole cell (all its 16 pixels; outside the grid: nothing). True when any pixel changed. */
    public boolean setCell(int cu, int cv, byte color, Stroke stroke) {
        if (!insideGrid(cu, cv)) return false;
        boolean any = false;
        for (int dv = 0; dv < CELL; dv++) {
            for (int du = 0; du < CELL; du++) any |= set(cu * CELL + du, cv * CELL + dv, color, stroke);
        }
        return any;
    }

    /**
     * The cells a brush of this size covers at (cu, cv), clipped to the grid: {u0, v0, u1, v1}, both ends included, or
     * null when none is on the grid. A size x size square from (cu - size/2, cv - size/2).
     */
    public static int[] stampBounds(int cu, int cv, int size) {
        int o = size / 2;
        int u0 = Math.max(0, cu - o), v0 = Math.max(0, cv - o);
        int u1 = Math.min(GRID - 1, cu - o + size - 1), v1 = Math.min(GRID - 1, cv - o + size - 1);
        return u0 > u1 || v0 > v1 ? null : new int[]{u0, v0, u1, v1};
    }

    /** The brush's footprint at cell (cu, cv) ({@link #stampBounds}). Returns how many cells changed. */
    public int stamp(int cu, int cv, int size, byte color, Stroke stroke) {
        int[] b = stampBounds(cu, cv, size);
        if (b == null) return 0;
        int changed = 0;
        for (int v = b[1]; v <= b[3]; v++) {
            for (int u = b[0]; u <= b[2]; u++) if (setCell(u, v, color, stroke)) changed++;
        }
        return changed;
    }

    /** Stamps cell by cell along the line from cell (u0, v0) to (u1, v1) (Bresenham, both ends included). */
    public int line(int u0, int v0, int u1, int v1, int size, byte color, Stroke stroke) {
        int changed = 0;
        int du = Math.abs(u1 - u0), dv = -Math.abs(v1 - v0);
        int su = u0 < u1 ? 1 : -1, sv = v0 < v1 ? 1 : -1;
        int err = du + dv;
        int u = u0, v = v0;
        for (int guard = 0; guard < 4 * GRID; guard++) {
            changed += stamp(u, v, size, color, stroke);
            if (u == u1 && v == v1) break;
            int e2 = 2 * err;
            if (e2 >= dv) {
                err += dv;
                u += su;
            }
            if (e2 <= du) {
                err += du;
                v += sv;
            }
        }
        return changed;
    }

    /**
     * Flood fill from cell (cu, cv): every cell of the same colour ({@link #cell}) connected to it (up, down, left,
     * right; never outside the grid) becomes color, all 16 pixels of it, so an older pixel-drawn painting is left with
     * no half cells where it was filled. Returns how many cells changed (0 when it already is that colour).
     */
    public int fill(int cu, int cv, byte color, Stroke stroke) {
        if (!insideGrid(cu, cv)) return 0;
        byte target = cell(cu, cv);
        if (target == color) return 0;
        int[] stack = new int[CELLS];
        int top = 0, changed = 0;
        stack[top++] = cv * GRID + cu;
        BitSet seen = new BitSet(CELLS);
        seen.set(cv * GRID + cu);
        while (top > 0) {
            int i = stack[--top];
            int x = i % GRID, y = i / GRID;
            if (setCell(x, y, color, stroke)) changed++;
            int[][] next = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
            for (int[] n : next) {
                if (!insideGrid(n[0], n[1])) continue;
                int j = n[1] * GRID + n[0];
                if (seen.get(j) || cell(n[0], n[1]) != target) continue;
                seen.set(j);
                stack[top++] = j;
            }
        }
        return changed;
    }

    /** Puts back what a stroke changed. True when anything changed. */
    public boolean undo(Stroke stroke) {
        boolean any = false;
        for (int k = stroke.size() - 1; k >= 0; k--) {
            int i = stroke.index(k);
            if (px[i] != stroke.before(k)) {
                px[i] = stroke.before(k);
                any = true;
            }
        }
        return any;
    }

    /** One stroke's changes: each pixel's colour before the stroke first touched it. */
    public static final class Stroke {
        private final BitSet touched = new BitSet(PIXELS);
        private int[] index = new int[64];
        private byte[] before = new byte[64];
        private int n;

        void touch(int i, byte old) {
            if (touched.get(i)) return;
            touched.set(i);
            if (n == index.length) {
                index = Arrays.copyOf(index, n * 2);
                before = Arrays.copyOf(before, n * 2);
            }
            index[n] = i;
            before[n++] = old;
        }

        public int size() {
            return n;
        }

        public boolean isEmpty() {
            return n == 0;
        }

        int index(int k) {
            return index[k];
        }

        byte before(int k) {
            return before[k];
        }
    }
}
