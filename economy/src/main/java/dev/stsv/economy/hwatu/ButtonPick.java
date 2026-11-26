package dev.stsv.economy.hwatu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side aim for the 도박장's table buttons (README "도박장" 조작): whatever a seated player clicks (air, the table's
 * barrier or click box, a button's own click box, left or right), the button they look at is worked out from their eye
 * ray here, so a click is never lost to the table behind a small click box. Pure (unit-tested).
 *
 * <p>A button is a box in its own frame: centred on its plate and label, `right` along the row (horizontal), up the world
 * up, `depth` toward its sitter. A ray inside a box (as drawn) wins first, the nearest along the ray; then inside a box
 * grown by {@link #MARGIN}, the one closest to the ray's line of sight; then the one in the smallest angle to the look
 * within {@link #CONE} degrees. Never further than {@link #REACH}, never another viewer's buttons.
 */
public final class ButtonPick {

    private ButtonPick() {}

    /** How much bigger a box is on the second try (blocks, each side). */
    public static final double MARGIN = 0.06;
    /** The widest angle off the look a button is still taken at (degrees). */
    public static final double CONE = 6.0;
    /** The furthest a button is pressed from (blocks from the eye). */
    public static final double REACH = 4.0;

    /**
     * A button shown to `viewer`: centre (x y z), its row's right (rx, rz: a unit vector on the ground) and half sizes along
     * right · up · toward the sitter.
     */
    public record Target(UUID viewer, String key, double x, double y, double z, double rx, double rz,
                         double halfWidth, double halfHeight, double halfDepth) {}

    /** Which target the ray from (ex, ey, ez) along (dx, dy, dz) picks, or null. */
    public static Target pick(double ex, double ey, double ez, double dx, double dy, double dz, UUID viewer, List<Target> targets) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return null;
        dx /= len;
        dy /= len;
        dz /= len;
        Target exact = null, near = null, cone = null;
        double exactT = Double.MAX_VALUE, nearA = Double.MAX_VALUE, coneA = Math.toRadians(CONE);
        for (Target t : targets) {
            if (!t.viewer().equals(viewer)) continue;
            double cx = t.x() - ex, cy = t.y() - ey, cz = t.z() - ez;
            double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (dist > REACH) continue;
            double angle = dist < 1e-9 ? 0 : Math.acos(Math.clamp((cx * dx + cy * dy + cz * dz) / dist, -1.0, 1.0));
            double hit = hit(t, ex, ey, ez, dx, dy, dz, 0);
            if (hit >= 0 && hit <= REACH && hit < exactT) {
                exactT = hit;
                exact = t;
            }
            double grown = hit(t, ex, ey, ez, dx, dy, dz, MARGIN);
            if (grown >= 0 && grown <= REACH && angle < nearA) {
                nearA = angle;
                near = t;
            }
            if (angle <= coneA) {
                coneA = angle;
                cone = t;
            }
        }
        return exact != null ? exact : near != null ? near : cone;
    }

    /** How far along the (unit) ray it enters the box grown by `margin` (0 if it starts inside), or -1: it misses. */
    static double hit(Target t, double ex, double ey, double ez, double dx, double dy, double dz, double margin) {
        // the box's frame: right (rx, 0, rz), up (0, 1, 0), depth (-rz, 0, rx) (sideways of right on the ground)
        double ox = ex - t.x(), oy = ey - t.y(), oz = ez - t.z();
        double[] o = {ox * t.rx() + oz * t.rz(), oy, -ox * t.rz() + oz * t.rx()};
        double[] d = {dx * t.rx() + dz * t.rz(), dy, -dx * t.rz() + dz * t.rx()};
        double[] h = {t.halfWidth() + margin, t.halfHeight() + margin, t.halfDepth() + margin};
        double tmin = 0, tmax = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (Math.abs(o[i]) > h[i]) return -1;
                continue;
            }
            double a = (-h[i] - o[i]) / d[i], b = (h[i] - o[i]) / d[i];
            if (a > b) {
                double s = a;
                a = b;
                b = s;
            }
            tmin = Math.max(tmin, a);
            tmax = Math.min(tmax, b);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    /**
     * Widths of axis-aligned click boxes (Interaction entities: as wide along x as along z) at (x[i], z[i]), spanning y
     * from bottom[i] up height[i], each wanting width[i]: shrunk where two would overlap, the gap between them split in
     * proportion to what each wanted. Returns the widths to use.
     */
    public static double[] fitBoxes(double[] x, double[] z, double[] bottom, double[] height, double[] width) {
        double[] w = width.clone();
        for (int i = 0; i < w.length; i++) {
            for (int j = i + 1; j < w.length; j++) {
                boolean ys = bottom[i] < bottom[j] + height[j] && bottom[j] < bottom[i] + height[i];
                if (!ys) continue;
                double apart = Math.max(Math.abs(x[i] - x[j]), Math.abs(z[i] - z[j]));
                if ((w[i] + w[j]) / 2 <= apart) continue;
                double f = 2 * apart / (w[i] + w[j]) * 0.98;   // a hair apart
                w[i] *= f;
                w[j] *= f;
            }
        }
        return w;
    }

    /**
     * One press a click: a click arrives as several events (right-click on an entity and its arm swing, main and off hand,
     * a held attack swinging every tick). An event counts only after {@link #QUIET} ticks without one from that player.
     */
    public static final class Debounce {
        /** ticks with no event before the next one counts */
        public static final int QUIET = 3;
        private final Map<UUID, Long> last = new HashMap<>();

        /** Whether this event (at server tick `tick`) is a new click. */
        public boolean accept(UUID who, long tick) {
            Long before = last.put(who, tick);
            return before == null || tick - before >= QUIET || tick < before;
        }

        public void forget(UUID who) {
            last.remove(who);
        }
    }

    /** At most one hint per player per {@link #HINT_MS}. */
    public static final class Hint {
        public static final long HINT_MS = 3000;
        private final Map<UUID, Long> last = new HashMap<>();

        public boolean due(UUID who, long nowMs) {
            Long before = last.get(who);
            if (before != null && nowMs - before < HINT_MS) return false;
            last.put(who, nowMs);
            return true;
        }

        public void forget(UUID who) {
            last.remove(who);
        }
    }
}
