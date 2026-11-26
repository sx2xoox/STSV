package dev.stsv.core.painting;

/**
 * Where a look hits a canvas: the canvas is a 1 x 1 square standing in a plane (a map in an item frame), seen from the
 * side its normal points to. Pixel (0, 0) is its top left as seen from the front, u to the viewer's right, v down
 * (how a map shows in a frame that is not turned). Pure maths on double[3] vectors (x, y, z), tested without a server.
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

    /** Distance along the ray to the hit (blocks) and the pixel hit. */
    public record Hit(double distance, int u, int v) {}

    /**
     * The pixel a ray from eye along dir (need not be unit) hits on the canvas face, within reach blocks, or null: it
     * looks at the back, away, past the edge, or too far.
     */
    public static Hit hit(double[] eye, double[] dir, Face face, double reach) {
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
        if (x < 0 || x >= 1 || y < 0 || y >= 1) return null;
        int u = Math.min(PaintCanvas.SIZE - 1, (int) Math.floor(x * PaintCanvas.SIZE));
        int v = Math.min(PaintCanvas.SIZE - 1, (int) Math.floor(y * PaintCanvas.SIZE));
        return new Hit(t, u, v);
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
