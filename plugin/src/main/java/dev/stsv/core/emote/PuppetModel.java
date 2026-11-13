package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Joint;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The puppet: the player model's boxes cut at the joints, and every face of every box as a text display (the same
 * numbers as tools/assetgen/emotes.py; its header tells what the client does with a text display, read from the
 * 26.3 client's DisplayRenderer).
 *
 * <p>A face is a grid of skin pixels read from outside. Its text display draws one glyph per pixel, {@link #CELL}
 * text pixels wide and high (the pixel font's advance and the game's line pitch), a text pixel being
 * {@link #TEXT_PX} block: the display is scaled so a glyph is a skin pixel, turned so the text's right, up and
 * front are the face's, and moved so the block of text lies on the face. The game culls the back of text and writes
 * its depth, so the six faces of a box look solid from every side.
 */
public final class PuppetModel {

    /** Text pixels per skin pixel: the pixel glyph's advance and the game's line pitch. */
    public static final int CELL = 10;
    /** A text pixel in blocks at scale 1. */
    public static final double TEXT_PX = 0.025;
    /**
     * Every face is drawn this much larger about its centre. The faces of a box go through different matrices, so
     * their shared edges only agree to rounding: without the overlap single pixels of the background show there.
     */
    public static final double SEAL = 1.004;
    /** Rows a box goes past a joint, into its neighbour (see {@link #BOXES}). */
    public static final int OVERLAP = 2;
    /** How far the second layer stands off its box: the hat, everything else. */
    public static final double HAT = 0.5, COAT = 0.25;

    /** A part of the skin: where its first and second layer are in the picture, and its size. */
    public enum SkinPart {
        HEAD(0, 0, 32, 0, 8, 8, 8),
        BODY(16, 16, 16, 32, 8, 12, 4),
        ARM_R(40, 16, 40, 32, 4, 12, 4),
        ARM_L(32, 48, 48, 48, 4, 12, 4),
        LEG_R(0, 16, 0, 32, 4, 12, 4),
        LEG_L(16, 48, 0, 48, 4, 12, 4);

        final int u, v, u2, v2, w, h, d;

        SkinPart(int u, int v, int u2, int v2, int w, int h, int d) {
            this.u = u;
            this.v = v;
            this.u2 = u2;
            this.v2 = v2;
            this.w = w;
            this.h = h;
            this.d = d;
        }

        /** Width: slim arms are 3 wide. */
        public int width(boolean slim) {
            return slim && (this == ARM_R || this == ARM_L) ? 3 : w;
        }

        public int height() {
            return h;
        }

        public int depth() {
            return d;
        }
    }

    /**
     * A side of a box: the text's right (u) and up (v) on it, seen from outside (its outward normal is u x v), and
     * its shade. Text displays get no directional light, so the shade is fixed, by where the face looks at rest.
     */
    public enum Side {
        FRONT(1, 0, 0, 0, 1, 0, 0.86),
        BACK(-1, 0, 0, 0, 1, 0, 0.80),
        LEFT(0, 0, -1, 0, 1, 0, 0.70),
        RIGHT(0, 0, 1, 0, 1, 0, 0.70),
        TOP(1, 0, 0, 0, 0, -1, 1.00),
        BOTTOM(1, 0, 0, 0, 0, 1, 0.55);

        private final double ux, uy, uz, vx, vy, vz;
        public final double shade;

        Side(double ux, double uy, double uz, double vx, double vy, double vz, double shade) {
            this.ux = ux;
            this.uy = uy;
            this.uz = uz;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.shade = shade;
        }

        public Vector3d u() {
            return new Vector3d(ux, uy, uz);
        }

        public Vector3d v() {
            return new Vector3d(vx, vy, vz);
        }

        public Vector3d normal() {
            return u().cross(v());
        }

        boolean lid() {
            return this == TOP || this == BOTTOM;
        }
    }

    /**
     * A box: the joint it moves with, the skin part it shows, that part's rows [r0, r1) from the top, its right edge
     * (x0), the top of the skin part (y) and its back (z0).
     */
    public record Box(Joint joint, SkinPart part, int r0, int r1, int x0, int top, int z0) {}

    /**
     * The boxes, in the preview tool's order. Where the body bends (waist, knee, hip joint) the boxes go
     * {@link #OVERLAP} rows past the joint, into each other: straight they show the same pixels in the same place,
     * bent their far ends fill the corner a plain cut would open (a box turning about the middle of its end leaves a
     * wedge on the outside of the bend). The hips go on under the body's last row with the first rows of the two legs
     * side by side (rows 12 and 13 of the body): the seat.
     */
    public static final List<Box> BOXES = List.of(
            new Box(Joint.HEAD, SkinPart.HEAD, 0, 8, -4, 32, -4),
            new Box(Joint.CHEST, SkinPart.BODY, 0, 8 + OVERLAP, -4, 24, -2),
            new Box(Joint.HIPS, SkinPart.BODY, 8 - OVERLAP, 12 + OVERLAP, -4, 24, -2),
            new Box(Joint.ARM_R, SkinPart.ARM_R, 0, 12, -8, 24, -2),
            new Box(Joint.ARM_L, SkinPart.ARM_L, 0, 12, 4, 24, -2),
            new Box(Joint.THIGH_R, SkinPart.LEG_R, 0, 6 + OVERLAP, -4, 12, -2),
            new Box(Joint.SHIN_R, SkinPart.LEG_R, 6 - OVERLAP, 12, -4, 12, -2),
            new Box(Joint.THIGH_L, SkinPart.LEG_L, 0, 6 + OVERLAP, 0, 12, -2),
            new Box(Joint.SHIN_L, SkinPart.LEG_L, 6 - OVERLAP, 12, 0, 12, -2));

    /**
     * A face of the puppet: the middle of its rectangle at rest, the text's right and up on it (unit vectors), its
     * grid of colours ({@code grid[row][col]}, row 0 on top as the text reads, 0xRRGGBB, {@link #NONE} where the
     * second layer is transparent), the size of one grid cell in skin pixels, and its shade.
     *
     * @param flipped the text is written upside down, turned half around in the face's plane (see {@link #faces})
     */
    public record Face(Joint joint, int layer, Side side, boolean flipped, Vector3d centre, Vector3d u, Vector3d v,
                       int[][] grid, double cellWidth, double cellHeight, double shade) {

        public int rows() {
            return grid.length;
        }

        public int cols() {
            return grid[0].length;
        }

        public Vector3d normal() {
            return new Vector3d(u).cross(v);
        }
    }

    /** A transparent pixel of the second layer. */
    public static final int NONE = -1;

    /**
     * A prop as a face: a flat picture that moves with its joint and looks the way the puppet looks (it is not
     * shaded: a sign is meant to be seen). The puppet's skin faces come first, a prop's face after them.
     */
    public static Face prop(EmoteSpec.Prop p) {
        return new Face(p.joint(), 0, Side.FRONT, false, new Vector3d(p.x(), p.y(), p.z()), new Vector3d(1, 0, 0), new Vector3d(0, 1, 0),
                p.grid(), p.size(), p.size(), 1.0);
    }

    /** A text display's transformation: translation in blocks from the entity, left rotation, scale. */
    public record Placement(Vector3d translation, Quaterniond rotation, Vector3d scale) {}

    private PuppetModel() {}

    // ------------------------------------------------------------------------------------------
    // The skin on the boxes
    // ------------------------------------------------------------------------------------------

    private static int pixel(PuppetSkin skin, int x, int y, int layer) {
        int argb = skin.pixel(x, y);
        return layer == 0 || (argb >>> 24) >= 128 ? argb & 0xFFFFFF : NONE;
    }

    /**
     * One row of a skin part's side (front, back, left, right), as read from outside. Under the body's last row
     * come the first rows of the legs, side by side.
     */
    static int[] sideRow(PuppetSkin skin, SkinPart part, int layer, Side side, int row) {
        if (part == SkinPart.BODY && row >= part.h) {
            int r = row - part.h;
            return switch (side) {
                case FRONT -> join(sideRow(skin, SkinPart.LEG_R, layer, side, r), sideRow(skin, SkinPart.LEG_L, layer, side, r));
                case BACK -> join(sideRow(skin, SkinPart.LEG_L, layer, side, r), sideRow(skin, SkinPart.LEG_R, layer, side, r));
                case RIGHT -> sideRow(skin, SkinPart.LEG_R, layer, side, r);
                default -> sideRow(skin, SkinPart.LEG_L, layer, side, r);
            };
        }
        int w = part.width(skin.slim()), d = part.d;
        int u0 = layer == 0 ? part.u : part.u2, v0 = layer == 0 ? part.v : part.v2;
        int x = switch (side) {
            case RIGHT -> u0;
            case FRONT -> u0 + d;
            case LEFT -> u0 + d + w;
            default -> u0 + 2 * d + w;
        };
        int[] out = new int[side == Side.FRONT || side == Side.BACK ? w : d];
        for (int i = 0; i < out.length; i++) out[i] = pixel(skin, x + i, v0 + d + row, layer);
        return out;
    }

    private static int[] join(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** The skin part's own top (x = its first column in the picture) seen from above: row 0 at the back. */
    private static int[][] lid(PuppetSkin skin, SkinPart part, int layer, int x) {
        int w = part.width(skin.slim()), v0 = layer == 0 ? part.v : part.v2;
        int[][] out = new int[part.d][w];
        for (int j = 0; j < part.d; j++) for (int i = 0; i < w; i++) out[j][i] = pixel(skin, x + i, v0 + j, layer);
        return out;
    }

    /**
     * A lid for an end that is not the skin part's own, seen from above (row 0 at the back, column 0 at the puppet's
     * right): every pixel takes the colour of the nearest side's pixel in the row next to the end (front or back
     * first, then the sides).
     */
    private static int[][] cap(int[] front, int[] back, int[] right, int[] left) {
        int w = front.length, d = right.length;
        int[][] out = new int[d][w];
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < w; i++) {
                int best = d - 1 - j, colour = front[i];
                if (j < best) {
                    best = j;
                    colour = back[w - 1 - i];
                }
                if (i < best) {
                    best = i;
                    colour = right[j];
                }
                if (w - 1 - i < best) colour = left[d - 1 - j];
                out[j][i] = colour;
            }
        }
        return out;
    }

    private static int[][] upsideDown(int[][] g) {
        int[][] out = new int[g.length][];
        for (int j = 0; j < g.length; j++) out[j] = g[g.length - 1 - j];
        return out;
    }

    private static int[][] halfTurned(int[][] g) {
        int[][] out = new int[g.length][g[0].length];
        for (int j = 0; j < g.length; j++) for (int i = 0; i < g[0].length; i++) out[j][i] = g[g.length - 1 - j][g[0].length - 1 - i];
        return out;
    }

    private static boolean empty(int[][] g) {
        for (int[] row : g) for (int c : row) if (c != NONE) return false;
        return true;
    }

    /**
     * Every face of the puppet in this skin: the first layer of every box (always six faces), then the second
     * layer's faces that have anything on them.
     *
     * <p>An end of a box that is not its skin part's own (waist, knee) is closed with a cap. The second layer stands
     * off its box ({@link #HAT}, {@link #COAT}); past such an end only by half of that, which keeps its cap just
     * outside the first layer's.
     *
     * <p>A text display's text stands on its origin, and between two poses the client moves that origin in a
     * straight line while it turns the text: the further the origin is from the joint, the further the face strays
     * from its box on the way. So a face whose top edge is nearer the joint than its bottom edge is written upside
     * down (turned half around in its own plane): its origin is then at that edge.
     */
    public static List<Face> faces(PuppetSkin skin) {
        List<Face> out = new ArrayList<>();
        for (int layer = 0; layer < 2; layer++) for (Box box : BOXES) faces(skin, box, layer, out);
        return out;
    }

    private static void faces(PuppetSkin skin, Box box, int layer, List<Face> out) {
        SkinPart part = box.part();
        boolean slim = skin.slim();
        int w = part.width(slim), h = part.h, d = part.d, rows = box.r1() - box.r0();
        int x0 = box.x0() + (slim && part == SkinPart.ARM_R ? 1 : 0);
        int u0 = layer == 0 ? part.u : part.u2;
        double g = layer == 0 ? 0 : box.joint() == Joint.HEAD ? HAT : COAT;
        boolean ownTop = box.r0() == 0, ownBottom = box.r1() == h;

        int[][][] grids = new int[Side.values().length][][];
        for (Side side : Side.values()) {
            if (side.lid()) continue;
            int[][] grid = new int[rows][];
            for (int r = 0; r < rows; r++) grid[r] = sideRow(skin, part, layer, side, box.r0() + r);
            grids[side.ordinal()] = grid;
        }
        int[][] f = grids[Side.FRONT.ordinal()], b = grids[Side.BACK.ordinal()], r = grids[Side.RIGHT.ordinal()], l = grids[Side.LEFT.ordinal()];
        grids[Side.TOP.ordinal()] = ownTop ? lid(skin, part, layer, u0 + d) : cap(f[0], b[0], r[0], l[0]);
        grids[Side.BOTTOM.ordinal()] = upsideDown(ownBottom ? lid(skin, part, layer, u0 + d + w)
                : cap(f[rows - 1], b[rows - 1], r[rows - 1], l[rows - 1]));

        Vector3d lo = new Vector3d(x0 - g, box.top() - box.r1() - (ownBottom ? g : g / 2), box.z0() - g);
        Vector3d hi = new Vector3d(x0 + w + g, box.top() - box.r0() + (ownTop ? g : g / 2), box.z0() + d + g);
        Vector3d mid = new Vector3d(lo).add(hi).mul(0.5), size = new Vector3d(hi).sub(lo);
        Vector3d pivot = box.joint().pivot();
        for (Side side : Side.values()) {
            int[][] grid = grids[side.ordinal()];
            if (empty(grid)) continue;
            Vector3d u = side.u(), v = side.v(), n = side.normal();
            Vector3d centre = new Vector3d(mid).fma(Math.abs(n.dot(size)) / 2, n);
            double cw = Math.abs(u.dot(size)) / grid[0].length, ch = Math.abs(v.dot(size)) / grid.length;
            boolean flip = new Vector3d(pivot).sub(centre).dot(v) > 1e-6;
            if (flip) {
                u.negate();
                v.negate();
                grid = halfTurned(grid);
            }
            out.add(new Face(box.joint(), layer, side, flip, centre, u, v, grid, cw, ch, side.shade));
        }
    }

    // ------------------------------------------------------------------------------------------
    // A face as a text display
    // ------------------------------------------------------------------------------------------

    /**
     * The transformation of a face's text display.
     *
     * @param joint  the face's joint matrix ({@link EmoteRig#matrices})
     * @param facing the puppet's turn about +y, radians (a body yaw of a degrees is -toRadians(a))
     * @param lift   the entity stands this many blocks over the puppet's ground
     */
    public static Placement place(Face face, Matrix4d joint, double facing, double lift) {
        Matrix3d turn = new Matrix3d().rotateY(facing);
        Matrix3d r = new Matrix3d(turn).mul(joint.get3x3(new Matrix3d())).mul(new Matrix3d(face.u(), face.v(), face.normal()));
        Vector3d centre = turn.transform(joint.transformPosition(new Vector3d(face.centre()))).mul(EmoteRig.PX).sub(0, lift, 0);
        Vector3d scale = new Vector3d(face.cellWidth() * EmoteRig.PX / (CELL * TEXT_PX) * SEAL,
                face.cellHeight() * EmoteRig.PX / (CELL * TEXT_PX) * SEAL, 1);
        // the middle of the block of text in the display's own frame at scale 1: one pixel right of the origin,
        // from one pixel under it up to the top of the first line
        Vector3d middle = new Vector3d(TEXT_PX, TEXT_PX * (CELL * face.rows() / 2.0 - 1), 0).mul(scale);
        Quaterniond q = new Quaterniond().setFromNormalized(r).normalize();
        if (q.w < 0) q.set(-q.x, -q.y, -q.z, -q.w);
        return new Placement(centre.sub(r.transform(middle)), q, scale);
    }

    /**
     * What the client draws for a text display of cols x rows glyphs with this transformation: the corner the text
     * starts at (top-left as read), then the vectors of one glyph cell to the right and down, in blocks from the
     * entity (the client's rules, DisplayRenderer.TextDisplayRenderer: see the preview tool's header).
     */
    public static Vector3d[] textRect(Placement p, int cols, int rows) {
        Matrix3d r = new Matrix3d().rotation(p.rotation());
        double w = CELL * cols;
        Vector3d origin = new Vector3d((1 - w / 2) * TEXT_PX, (CELL * rows - 1) * TEXT_PX, 0).mul(p.scale());
        return new Vector3d[]{
                r.transform(origin).add(p.translation()),
                r.transform(new Vector3d(CELL * TEXT_PX, 0, 0).mul(p.scale())),
                r.transform(new Vector3d(0, -CELL * TEXT_PX, 0).mul(p.scale()))};
    }
}
