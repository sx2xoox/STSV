package dev.stsv.core.painting;

import java.util.Arrays;
import java.util.BitSet;

/**
 * A painting's 128 x 128 pixels (map colour bytes, row by row: index = v * 128 + u, u to the right, v down) and the
 * drawing on them: a brush stamp, a line of stamps, a flood fill. Every change goes into a {@link Stroke} (the pixel's
 * colour before the stroke first touched it), so a stroke can be undone. No Bukkit: pure, tested logic.
 */
public final class PaintCanvas {

    public static final int SIZE = 128;
    public static final int PIXELS = SIZE * SIZE;
    /** Brush sizes a painter can pick (pixels across). */
    public static final int[] BRUSH_SIZES = {1, 2, 4};

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

    /**
     * The brush's footprint at (u, v): a size x size square from (u - size/2, v - size/2), the 4 x 4 brush with its
     * corners cut (round). Returns how many pixels changed.
     */
    public int stamp(int u, int v, int size, byte color, Stroke stroke) {
        int changed = 0;
        int o = size / 2;
        for (int dv = 0; dv < size; dv++) {
            for (int du = 0; du < size; du++) {
                if (size >= 4 && (du == 0 || du == size - 1) && (dv == 0 || dv == size - 1)) continue;
                if (set(u - o + du, v - o + dv, color, stroke)) changed++;
            }
        }
        return changed;
    }

    /** Stamps along the line from (u0, v0) to (u1, v1) (Bresenham, both ends included). */
    public int line(int u0, int v0, int u1, int v1, int size, byte color, Stroke stroke) {
        int changed = 0;
        int du = Math.abs(u1 - u0), dv = -Math.abs(v1 - v0);
        int su = u0 < u1 ? 1 : -1, sv = v0 < v1 ? 1 : -1;
        int err = du + dv;
        int u = u0, v = v0;
        for (int guard = 0; guard < 4 * SIZE; guard++) {
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
     * Flood fill from (u, v): every pixel of the same colour connected to it (up, down, left, right; never outside the
     * canvas) becomes color. Returns how many pixels changed (0 when it already is that colour).
     */
    public int fill(int u, int v, byte color, Stroke stroke) {
        if (!inside(u, v)) return 0;
        byte target = get(u, v);
        if (target == color) return 0;
        int[] stack = new int[PIXELS];
        int top = 0, changed = 0;
        stack[top++] = v * SIZE + u;
        BitSet seen = new BitSet(PIXELS);
        seen.set(v * SIZE + u);
        while (top > 0) {
            int i = stack[--top];
            int x = i % SIZE, y = i / SIZE;
            if (px[i] != target) continue;
            if (set(x, y, color, stroke)) changed++;
            int[][] next = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
            for (int[] n : next) {
                if (!inside(n[0], n[1])) continue;
                int j = n[1] * SIZE + n[0];
                if (seen.get(j) || px[j] != target) continue;
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
