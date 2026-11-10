package dev.stsv.core.furniture;

import dev.stsv.core.content.FurnitureSpec;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Display;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Locale;

/**
 * How far away the plugin's 3D models (display entities) are drawn, and what the client is told to skip.
 *
 * <p>Why it matters: the client draws every display entity on its own, every frame (its model is rebuilt and sent to the
 * GPU each time), unlike blocks, which are baked into chunk meshes once. A farm of a few thousand crops is a few thousand
 * separate draws per frame. Two vanilla knobs cut that down without changing what a player sees up close:
 * <ul>
 *   <li><b>view range</b>: the client draws a display only within {@code view_range × 64} blocks (times the player's own
 *       "Entity Distance" setting). Per kind, from config display.view-distance (blocks), times the old
 *       furniture.view-range multiplier.</li>
 *   <li><b>culling box</b> (display width / height): with the default 0 x 0 the client never frustum-culls a display, so
 *       everything in range is drawn even behind the camera. A box that holds the model lets the client skip the ones off
 *       screen. The box stands on the entity's position (x/z centred, width wide, height up) and the client widens it by
 *       half a block each way; it does not turn with the entity, so it is sized for any yaw.</li>
 * </ul>
 */
public final class DisplayView {

    /** The client's display range unit: view_range 1.0 = 64 blocks (at Entity Distance 100%). */
    static final double BLOCKS_PER_RANGE = 64.0;
    /** Extra room around the cells in culling boxes: models may hang a little over their cells (roofs, handles). */
    static final double MARGIN = 0.5;

    private DisplayView() {}

    /** What a display is, for its view distance. */
    public enum Kind {
        CROP("crops"),
        SMALL("small-furniture"),
        LARGE("large-furniture"),
        MACHINE("machines");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /**
     * @param crops, small, large, machines  view distance in blocks per kind (0 or less: the vanilla 64)
     * @param multiplier   furniture.view-range (older setting, multiplies all)
     * @param culling      tell the client the model's size so it can skip off-screen ones
     * @param animateRange spinning parts turn only while a player is this close (blocks); 0 = always
     */
    public record Settings(double crops, double small, double large, double machines, double multiplier, boolean culling, double animateRange) {

        public static Settings of(Configuration c) {
            return new Settings(c.getDouble("display.view-distance.crops"), c.getDouble("display.view-distance.small-furniture"),
                    c.getDouble("display.view-distance.large-furniture"), c.getDouble("display.view-distance.machines"),
                    c.getDouble("furniture.view-range"), c.getBoolean("display.frustum-culling"), c.getDouble("display.animate-range"));
        }

        public double blocks(Kind k) {
            double b = switch (k) {
                case CROP -> crops;
                case SMALL -> small;
                case LARGE -> large;
                case MACHINE -> machines;
            };
            return (b > 0 ? b : BLOCKS_PER_RANGE) * (multiplier > 0 ? multiplier : 1.0);
        }

        /** The display entity's view_range value for this kind. */
        public float viewRange(Kind k) {
            return (float) (blocks(k) / BLOCKS_PER_RANGE);
        }
    }

    /**
     * Machines and other working furniture (a behavior or moving parts), else small (one cell) or large.
     */
    public static Kind kindOf(FurnitureSpec spec) {
        if (spec == null) return Kind.LARGE;
        if (spec.behavior() != null || !spec.parts().isEmpty()) return Kind.MACHINE;
        return spec.cells().size() <= 1 ? Kind.SMALL : Kind.LARGE;
    }

    /** No culling box: the client draws it whenever it is in range (the vanilla default). */
    static final float[] NO_BOX = {0f, 0f};

    /**
     * {width, height} of a culling box that holds a placed piece's model at any yaw: the display stands at the centre of the
     * anchor cell, its model turns about that point, then is scaled and moved by the item's scale / translation. Cells
     * below the anchor (which the box, starting at the entity's height, cannot reach): no box.
     */
    public static float[] furnitureBox(FurnitureSpec spec) {
        if (spec == null || spec.cells().isEmpty()) return NO_BOX;
        double radius = 0, top = 0;
        for (int[] c : spec.cells()) {
            if (c[1] < 0) return NO_BOX;
            // the cell's far corner from the anchor centre, on x/z, and its top above the anchor centre
            radius = Math.max(radius, Math.hypot(Math.abs(c[0]) + 0.5, Math.abs(c[2]) + 0.5));
            top = Math.max(top, c[1] + 0.5);
        }
        Vector3f s = spec.scale(), t = spec.translation();
        double scale = Math.max(1.0, Math.max(Math.abs(s.x), Math.max(Math.abs(s.y), Math.abs(s.z))));
        double shift = t.length();
        radius = radius * scale + shift + MARGIN;
        top = top * scale + shift + MARGIN;
        return new float[]{(float) (2 * radius), (float) top};
    }

    /**
     * {width, height} for a crop: its display stands at the middle of its block and the model fills the block (taller
     * plants: up to their height). The client's own half-block widening covers the lower half of the block.
     */
    public static float[] cropBox(double height) {
        return new float[]{1f + (float) MARGIN, (float) Math.max(1.0, height) + (float) MARGIN};
    }

    /** Sets view range and culling box (the server only sends what changed). */
    public static void apply(Display d, float viewRange, float[] box, boolean culling) {
        if (d.getViewRange() != viewRange) d.setViewRange(viewRange);
        float w = culling ? box[0] : 0f, h = culling ? box[1] : 0f;
        if (d.getDisplayWidth() != w) d.setDisplayWidth(w);
        if (d.getDisplayHeight() != h) d.setDisplayHeight(h);
    }

    /**
     * Is any of these positions ({x, y, z} in the same world) within range of the point? range 0 or less: always true
     * (no limit).
     */
    public static boolean anyoneWithin(double x, double y, double z, Collection<double[]> players, double range) {
        if (range <= 0) return true;
        double r2 = range * range;
        for (double[] p : players) {
            double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    /** "32" or "32.5": a distance for messages. */
    static String blocksText(double b) {
        return b == Math.rint(b) ? String.valueOf((long) b) : String.format(Locale.ROOT, "%.1f", b);
    }
}
