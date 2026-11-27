package dev.stsv.core.painting;

/**
 * Where a look hits a canvas: the canvas is a 1 x 1 square standing in a plane (a map in an item frame), seen from the
 * side its normal points to. Cell (0, 0) of the drawing grid ({@link PaintCanvas#GRID}) is its top left as seen from the
 * front, u to the viewer's right, v down (how a map shows in a frame that is not turned). Pure maths on double[3] vectors (x, y, z), tested without a server.
 */
public final class CanvasRay {

    private CanvasRay() {}

    /** A canvas face: its centre, the unit normal towards the viewer, and the unit right and up of the picture. */
    public record Face(double[] center, double[] normal, double[] right, double[] up) {

        /**
         * A canvas standing upright (a map in a frame on a wall) with this horizontal normal (towards the viewer):
         * up = +y, right = up x normal (the viewer's right while looking at it).
         */
        public static Face upright(double[] center, double[] normal) {
            double[] up = {0, 1, 0};
            double[] right = cross(up, normal);
            return new Face(center, normal, right, up);
        }
    }

    /** Distance along the ray to the hit (blocks), the cell hit, and whether the look was on the canvas or only near its edge. */
    public record Hit(double distance, int u, int v, boolean exact) {}

    /** The cell a ray hits, only when it is on the canvas ({@link #hit(double[], double[], Face, double, double)} with no slack). */
    public static Hit hit(double[] eye, double[] dir, Face face, double reach) {
        return hit(eye, dir, face, reach, 0);
    }

    /**
     * The cell a ray from eye along dir (need not be unit) hits on the canvas face, within reach blocks, or null: it
     * looks at the back, away, too far, or past the edge by more than slack (blocks). A look just past the edge (within
     * slack) takes the nearest edge cell, so a stroke along the border does not drop out.
     */
    public static Hit hit(double[] eye, double[] dir, Face face, double reach, double slack) {
        double len = Math.sqrt(dot(dir, dir));
        if (len < 1e-9) return null;
        double[] d = {dir[0] / len, dir[1] / len, dir[2] / len};
        double denom = dot(d, face.normal());
        if (denom > -1e-6) return null;                        // looking at it edge-on, from behind or away
        double[] toPlane = sub(face.center(), eye);
        double t = dot(toPlane, face.normal()) / denom;
        if (t < 0 || t > reach) return null;
        double[] p = {eye[0] + d[0] * t, eye[1] + d[1] * t, eye[2] + d[2] * t};
        double[] rel = sub(p, face.center());
        double x = dot(rel, face.right()) + 0.5;               // 0 at the left edge, 1 at the right
        double y = 0.5 - dot(rel, face.up());                  // 0 at the top, 1 at the bottom
        if (x < -slack || x >= 1 + slack || y < -slack || y >= 1 + slack) return null;
        boolean exact = x >= 0 && x < 1 && y >= 0 && y < 1;
        return new Hit(t, cell(x), cell(y), exact);
    }

    /** The grid cell at a position across the canvas (0 = left/top edge, 1 = right/bottom), clamped to the grid. */
    static int cell(double x) {
        return Math.max(0, Math.min(PaintCanvas.GRID - 1, (int) Math.floor(x * PaintCanvas.GRID)));
    }

    /**
     * The world box over cells u0..u1, v0..v1 (both ends included) lying from front to front + thick blocks in front of
     * the face: {min corner, max corner}, axis aligned for a face that is (an upright map in a frame).
     */
    public static double[][] box(Face face, int u0, int v0, int u1, int v1, double front, double thick) {
        double g = PaintCanvas.GRID;
        double[] a = at(face, u0 / g, v0 / g, front), b = at(face, (u1 + 1) / g, (v1 + 1) / g, front + thick);
        return new double[][]{
                {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])},
                {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])}};
    }

    /** The point at (x across, y down) on the face, off blocks in front of it. */
    private static double[] at(Face f, double x, double y, double off) {
        double[] c = f.center(), r = f.right(), up = f.up(), n = f.normal();
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = c[i] + r[i] * (x - 0.5) + up[i] * (0.5 - y) + n[i] * off;
        return out;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
