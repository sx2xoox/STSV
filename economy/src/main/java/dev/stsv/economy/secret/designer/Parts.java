package dev.stsv.economy.secret.designer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The designer's rules that need no server (unit-tested): which part a stage asks for, whether a lane holds exactly a
 * part's materials, which machine kinds may make it, and where the 13th machine's eye looks.
 */
final class Parts {

    /** The parts in the order they are made; stage n (1..4) asks for ORDER[n - 1]. */
    static final List<String> ORDER = List.of("rib", "vein", "lens", "heart");

    /** "Any kind not used yet" in a part's machine setting. */
    static final String ANY = "*";

    private Parts() {}

    /** The part a stage asks for, or null (not started, or done). */
    static String forStage(int stage) {
        return stage >= 1 && stage <= ORDER.size() ? ORDER.get(stage - 1) : null;
    }

    /**
     * Whether a lane holds the materials and nothing else: every filled slot is one of them (key, amount), every one of them is
     * there, and there is at least as much of each as is needed. slots: item key per slot, null = empty or not counted.
     */
    static boolean exactly(List<String> keys, List<Integer> amounts, Map<String, Integer> want) {
        if (want.isEmpty()) return false;
        Map<String, Integer> have = new java.util.HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (k == null) continue;
            if (!want.containsKey(k)) return false;
            have.merge(k, amounts.get(i), Integer::sum);
        }
        for (Map.Entry<String, Integer> w : want.entrySet()) if (have.getOrDefault(w.getKey(), 0) < w.getValue()) return false;
        return true;
    }

    /** Whether a machine of this kind may make a part set to {@code machine}: that kind, or ("*") one no earlier part was made in. */
    static boolean kindFits(String machine, String kind, Collection<String> used) {
        if (kind == null) return false;
        if (ANY.equals(machine)) return !used.contains(kind);
        return Objects.equals(machine, kind);
    }

    /**
     * Yaw (about y, from +z towards +x) and pitch (about x, negative = up) that turn a thing facing +z to look along (dx, dy, dz),
     * in radians. Straight up or down keeps the yaw 0.
     */
    static float[] look(double dx, double dy, double dz) {
        double flat = Math.hypot(dx, dz);
        double len = Math.sqrt(flat * flat + dy * dy);
        if (len < 1e-6) return new float[]{0, 0};
        float yaw = flat < 1e-6 ? 0 : (float) Math.atan2(dx, dz);
        float pitch = (float) -Math.asin(Math.clamp(dy / len, -1, 1));
        return new float[]{yaw, pitch};
    }

    /** A piece of the breathing body grown by f about the body's centre: its new centre (size grows by f too). */
    static float[] breathe(float[] center, float[] body, float f) {
        return new float[]{body[0] + (center[0] - body[0]) * f, body[1] + (center[1] - body[1]) * f, body[2] + (center[2] - body[2]) * f};
    }

    /** "world x y z" (a block) for eco.kv. */
    static String encode(String world, int x, int y, int z) {
        return world + " " + x + " " + y + " " + z;
    }

    /** Back from {@link #encode}: {world, x, y, z} or null. */
    static Object[] decode(String s) {
        if (s == null) return null;
        String[] w = s.trim().split(" ");
        if (w.length < 4) return null;
        try {
            return new Object[]{w[0], Integer.parseInt(w[1]), Integer.parseInt(w[2]), Integer.parseInt(w[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
