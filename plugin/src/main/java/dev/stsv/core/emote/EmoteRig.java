package dev.stsv.core.emote;

import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The puppet's skeleton and its forward kinematics (the same numbers as tools/assetgen/emotes.py, which draws the
 * previews; the fixture src/test/fixtures/emote/rig.json keeps the two from drifting apart).
 *
 * <p>Units are skin pixels, the origin is on the ground between the feet, +x is the puppet's left, +y up, +z
 * forward. A joint turns about its pivot by Rz(roll) Ry(yaw) Rx(pitch), degrees: pitch + tips the top forward,
 * yaw + turns to the left, roll + leans the top to the puppet's right. Hips and chest both hang on the root at the
 * waist, so the hips can swing without the upper body following.
 *
 * <p>With planted legs the thigh and shin angles are not used: each leg is solved (two bones of {@link #LEG}) so
 * that the sole's centre stays on its spot, the knee towards its pole.
 */
public final class EmoteRig {

    /** A skin pixel in blocks (the player model's scale). */
    public static final double PX = 0.9375 / 16;
    /** Thigh and shin length. */
    public static final double LEG = 6;

    /** The joints, parents first. This order is the preview tool's (JOINTS). */
    public enum Joint {
        ROOT(null, 0, 0, 0),
        HIPS(ROOT, 0, 16, 0),
        CHEST(ROOT, 0, 16, 0),
        HEAD(CHEST, 0, 24, 0),
        ARM_R(CHEST, -5, 22, 0),
        ARM_L(CHEST, 5, 22, 0),
        THIGH_R(HIPS, -2, 12, 0),
        SHIN_R(THIGH_R, -2, 6, 0),
        THIGH_L(HIPS, 2, 12, 0),
        SHIN_L(THIGH_L, 2, 6, 0);

        public final Joint parent;
        private final double px, py, pz;

        Joint(Joint parent, double px, double py, double pz) {
            this.parent = parent;
            this.px = px;
            this.py = py;
            this.pz = pz;
        }

        public Vector3d pivot() {
            return new Vector3d(px, py, pz);
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What a pose holds, three numbers each: the root's offset and rotation, every other joint's rotation, where the
     * soles stand (offsets from the rest stance) and where the knees point (planted legs only).
     */
    public enum Channel {
        ROOT, ROOT_ROT, HIPS, CHEST, HEAD, ARM_R, ARM_L, THIGH_R, SHIN_R, THIGH_L, SHIN_L, FOOT_R, FOOT_L, KNEE_R, KNEE_L;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Channel of(String key) {
            for (Channel c : values()) if (c.key().equals(key)) return c;
            return null;
        }

        static Channel of(Joint j) {
            return j == Joint.ROOT ? ROOT_ROT : valueOf(j.name());
        }
    }

    /** A pose: every channel's three numbers. Immutable. */
    public static final class Pose {

        public static final Pose REST = new Pose(new EnumMap<>(Channel.class));

        private final Map<Channel, double[]> values;

        private Pose(Map<Channel, double[]> values) {
            this.values = values;
        }

        public double[] get(Channel c) {
            double[] v = values.get(c);
            if (v != null) return v.clone();
            return c == Channel.KNEE_R || c == Channel.KNEE_L ? new double[]{0, 0, 1} : new double[3];
        }

        /** This pose with one channel replaced. */
        public Pose with(Channel c, double x, double y, double z) {
            Map<Channel, double[]> next = new EnumMap<>(Channel.class);
            next.putAll(values);
            next.put(c, new double[]{x, y, z});
            return new Pose(next);
        }

        /** Channel by channel from this pose (u = 0) to the other (u = 1). */
        public Pose mix(Pose to, double u) {
            Map<Channel, double[]> next = new EnumMap<>(Channel.class);
            for (Channel c : Channel.values()) {
                double[] a = get(c), b = to.get(c);
                next.put(c, new double[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u});
            }
            return new Pose(next);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pose p)) return false;
            for (Channel c : Channel.values()) if (!Arrays.equals(get(c), p.get(c))) return false;
            return true;
        }

        @Override
        public int hashCode() {
            int h = 0;
            for (Channel c : Channel.values()) h = h * 31 + Arrays.hashCode(get(c));
            return h;
        }
    }

    private EmoteRig() {}

    /** Rz(roll) Ry(yaw) Rx(pitch), degrees. */
    public static Matrix3d euler(double pitch, double yaw, double roll) {
        return new Matrix3d().rotateZ(Math.toRadians(roll)).rotateY(Math.toRadians(yaw)).rotateX(Math.toRadians(pitch));
    }

    /** Turn by r about the pivot, then move by the offset. */
    private static Matrix4d about(Vector3d pivot, Matrix3d r, Vector3d offset) {
        Vector3d t = new Vector3d(pivot).sub(r.transform(new Vector3d(pivot))).add(offset);
        return new Matrix4d().set(r).setTranslation(t);
    }

    /** Every joint's matrix (rest pixels to posed pixels), by {@link Joint#ordinal()}. */
    public static Matrix4d[] matrices(Pose pose, boolean planted) {
        Matrix4d[] out = new Matrix4d[Joint.values().length];
        double[] rr = pose.get(Channel.ROOT_ROT), ro = pose.get(Channel.ROOT);
        out[0] = about(Joint.ROOT.pivot(), euler(rr[0], rr[1], rr[2]), new Vector3d(ro[0], ro[1], ro[2]));
        for (Joint j : Joint.values()) {
            if (j == Joint.ROOT || (planted && leg(j))) continue;
            double[] a = pose.get(Channel.of(j));
            out[j.ordinal()] = new Matrix4d(out[j.parent.ordinal()]).mul(about(j.pivot(), euler(a[0], a[1], a[2]), new Vector3d()));
        }
        if (planted) {
            plant(out, pose, Joint.THIGH_R, Joint.SHIN_R, Channel.FOOT_R, Channel.KNEE_R, -1);
            plant(out, pose, Joint.THIGH_L, Joint.SHIN_L, Channel.FOOT_L, Channel.KNEE_L, 1);
        }
        return out;
    }

    private static boolean leg(Joint j) {
        return j == Joint.THIGH_R || j == Joint.SHIN_R || j == Joint.THIGH_L || j == Joint.SHIN_L;
    }

    private static void plant(Matrix4d[] out, Pose pose, Joint thigh, Joint shin, Channel foot, Channel knee, int side) {
        Vector3d hip = out[Joint.HIPS.ordinal()].transformPosition(thigh.pivot());
        double[] f = pose.get(foot), k = pose.get(knee);
        Leg leg = solveLeg(hip, new Vector3d(2.0 * side + f[0], f[1], f[2]), new Vector3d(k[0], k[1], k[2]));
        out[thigh.ordinal()] = at(leg.thigh, hip, thigh.pivot());
        out[shin.ordinal()] = at(leg.shin, leg.knee, shin.pivot());
    }

    /** The matrix that turns by r and puts the rest pivot on the given point. */
    private static Matrix4d at(Matrix3d r, Vector3d point, Vector3d pivot) {
        return new Matrix4d().set(r).setTranslation(new Vector3d(point).sub(r.transform(new Vector3d(pivot))));
    }

    /** A solved leg: the thigh's and the shin's rotation, the knee, and the foot (pulled in if out of reach). */
    public record Leg(Matrix3d thigh, Matrix3d shin, Vector3d knee, Vector3d foot) {}

    /**
     * Two bones of {@link #LEG} from the hip socket to the sole centre, the knee towards the pole. A foot out of
     * reach is pulled in along the line to the hip (and pushed out when it is closer than half a pixel).
     */
    public static Leg solveLeg(Vector3d hip, Vector3d foot, Vector3d pole) {
        Vector3d d = new Vector3d(foot).sub(hip);
        double dist = d.length();
        if (dist < 1e-6) {
            d.set(0, -1, 0);
            dist = 1;
        }
        double reach = Math.min(Math.max(dist, 0.5), 2 * LEG - 1e-3);
        Vector3d down = d.div(dist);
        Vector3d reached = new Vector3d(hip).fma(reach, down);
        Vector3d up = new Vector3d(down).negate();
        Vector3d side = null;
        for (Vector3d p : new Vector3d[]{pole, new Vector3d(0, 0, 1), new Vector3d(0, 1, 0)}) {
            Vector3d s = new Vector3d(p).fma(-p.dot(up), up);
            if (s.length() > 1e-9) {
                side = s.normalize();
                break;
            }
        }
        Vector3d knee = new Vector3d(hip).fma(reach / 2, down).fma(Math.sqrt(LEG * LEG - reach * reach / 4), side);
        Vector3d hinge = new Vector3d(up).cross(side).normalize();
        return new Leg(frame(hinge, hip, knee), frame(hinge, knee, reached), knee, reached);
    }

    /** The rotation whose x is the hinge and whose y runs from the bone's lower end to its upper end. */
    private static Matrix3d frame(Vector3d hinge, Vector3d top, Vector3d bottom) {
        Vector3d y = new Vector3d(top).sub(bottom).div(LEG);
        return new Matrix3d(hinge, y, new Vector3d(hinge).cross(y));
    }
}
