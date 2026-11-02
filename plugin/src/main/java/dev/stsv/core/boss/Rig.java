package dev.stsv.core.boss;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Bone maths, kept free of Bukkit so it can be unit tested.
 *
 * Model space: model units (16 = 1 block), origin on the ground between the feet, front = -z.
 * A bone's model is built with its pivot on (8, 8, 8), so a bone-local point q (relative to the pivot)
 * ends up at  W_bone * q  in model space, where
 *     W_bone = W_parent * T(pivot - parent pivot + animated offset) * R(animated rotation) [* R(look)]
 *
 * Display space: what the display entity's transformation works in. The display sits on the boss as a
 * passenger (so the client moves it smoothly with the boss), its yaw/pitch stay 0, and the item renderer
 * shows the model turned 180° around Y and scaled 1/16:  itemLocal = C * q  with C = diag(-1, 1, -1) / 16.
 * To show q at its posed place:  M * C * q = Ry(-bodyYaw) * k * C * W_bone * q - riderOffset
 *     =>  M = T(-riderOffset) * Ry(-bodyYaw) * S(k) * C * W_bone * C^-1
 * (C turns model front -z into display +z, which is the facing of an entity with yaw 0; Minecraft yaw
 * turns clockwise seen from above, hence the minus.)
 */
public final class Rig {

    private Rig() {}

    public record Pose(Vector3f rot, Vector3f pos) {
        public static final Pose REST = new Pose(new Vector3f(), new Vector3f());

        /** Blends towards another pose the short way round (a 348° spin blends into -12°, not back through 360°). */
        public Pose lerp(Pose to, float k) {
            Vector3f d = new Vector3f(wrap(to.rot.x - rot.x), wrap(to.rot.y - rot.y), wrap(to.rot.z - rot.z));
            return new Pose(new Vector3f(rot).add(d.mul(k)), new Vector3f(pos).lerp(to.pos, k));
        }

        private static float wrap(float deg) {
            deg %= 360;
            if (deg > 180) deg -= 360;
            if (deg < -180) deg += 360;
            return deg;
        }
    }

    /** Pose of every animated bone at animation time t (loops wrap, one-shots hold their last frame). */
    public static Map<String, Pose> sample(BossSpec.Animation anim, float t) {
        Map<String, Pose> out = new HashMap<>();
        if (anim == null) return out;
        float at = anim.loop() ? t % anim.length() : Math.min(t, anim.length());
        for (Map.Entry<String, BossSpec.Track> e : anim.bones().entrySet()) {
            Vector3f r = BossSpec.Animation.sample(e.getValue().rot(), at);
            Vector3f o = BossSpec.Animation.sample(e.getValue().pos(), at);
            out.put(e.getKey(), new Pose(r == null ? new Vector3f() : r, o == null ? new Vector3f() : o));
        }
        return out;
    }

    /** Model-space matrix of every bone for the given pose. look = extra (yaw, pitch) degrees for look bones. */
    public static Map<String, Matrix4f> modelSpace(BossSpec spec, Map<String, Pose> pose, float lookYaw, float lookPitch) {
        Map<String, Matrix4f> out = new HashMap<>();
        for (BossSpec.Bone b : spec.bones().values()) {   // parents are always defined first
            Pose p = pose.getOrDefault(b.name(), Pose.REST);
            Matrix4f parent = b.parent() == null ? new Matrix4f() : out.get(b.parent());
            Vector3f parentPivot = b.parent() == null ? new Vector3f() : spec.bones().get(b.parent()).pivot();
            Matrix4f m = new Matrix4f(parent)
                    .translate(new Vector3f(b.pivot()).sub(parentPivot).add(p.pos()))
                    .rotateXYZ(rad(p.rot().x), rad(p.rot().y), rad(p.rot().z));
            if (b.look()) {
                // yaw then pitch in the bone's frame; looking down tips the front (-z) towards -y
                m.rotateY(rad(-lookYaw)).rotateX(rad(-lookPitch));
            }
            out.put(b.name(), m);
        }
        return out;
    }

    /** Display transformation for a bone (see class comment). riderOffset = height of the passenger seat, blocks. */
    public static Matrix4f display(Matrix4f modelBone, float bodyYaw, float scale, float riderOffset) {
        return new Matrix4f()
                .translate(0, -riderOffset, 0)
                .rotateY(rad(-bodyYaw))
                .scale(scale)
                .scale(-1 / 16f, 1 / 16f, -1 / 16f)
                .mul(modelBone)
                .scale(-16f, 16f, -16f);
    }

    /** World offset (blocks, from the boss's feet) of an assembly-space point carried by a bone. */
    public static Vector3f worldOffset(BossSpec spec, Map<String, Matrix4f> model, String bone, Vector3f assemblyPoint,
                                       float bodyYaw, float scale) {
        Vector3f q = new Vector3f(assemblyPoint).sub(spec.bones().get(bone).pivot());
        Vector3f p = model.get(bone).transformPosition(q);
        p.mul(-1 / 16f * scale, 1 / 16f * scale, -1 / 16f * scale);
        return new Matrix4f().rotateY(rad(-bodyYaw)).transformPosition(p);
    }

    private static float rad(float deg) {
        return (float) Math.toRadians(deg);
    }
}
