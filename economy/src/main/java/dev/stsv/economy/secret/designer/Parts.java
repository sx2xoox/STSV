package dev.stsv.economy.secret.designer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The designer's rules that need no server (unit-tested): which part a stage asks for, whether a lane holds exactly a
 * part's materials, which machine kinds may make it, where the 13th machine's eye looks and how it turns.
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

    /**
     * A point of the machine (block-local x, z) turned by quarter turns about the block's vertical centre line (0.5, 0.5), the
     * same way as {@code new Quaternionf().rotateY(quarter * PI / 2)} turns it: one quarter takes +z (south) to +x (east).
     * Exact for whole quarters (no float drift), any quarter (negative too) taken mod 4.
     */
    static float[] turn(float x, float z, int quarter) {
        return switch (Math.floorMod(quarter, 4)) {
            case 1 -> new float[]{z, 1 - x};
            case 2 -> new float[]{1 - x, 1 - z};
            case 3 -> new float[]{1 - z, x};
            default -> new float[]{x, z};
        };
    }

    /**
     * The quarter turn (0 south, 1 east, 2 north, 3 west) nearest to where a Minecraft yaw (degrees: 0 south, -90 east, 90 west,
     * 180 north) looks.
     */
    static int quarter(float yawDegrees) {
        return Math.floorMod(Math.round(-yawDegrees / 90f), 4);
    }

    /** The lens yaw (radians, {@link #look} convention) of the machine's front at this quarter turn. */
    static float frontYaw(int quarter) {
        return (float) (Math.floorMod(quarter, 4) * Math.PI / 2);
    }

    /** Whether the 13th machine may go to a spot its owner looks at, or the first reason it may not. */
    enum Spot { OK, AIM, GROUND, ROOM, LAND, FAR }

    /**
     * The verdict for a spot, checked in this order: something looked at (within reach), solid ground under it, two free blocks
     * on it, land the player may build on, and not too far from where it stands now.
     *
     * @param distSq squared distance from where it stands now; negative = another world
     * @param max    how far it may go (blocks); 0 or less = anywhere, any world
     */
    static Spot spot(boolean target, boolean solid, boolean room, boolean mayBuild, double distSq, int max) {
        if (!target) return Spot.AIM;
        if (!solid) return Spot.GROUND;
        if (!room) return Spot.ROOM;
        if (!mayBuild) return Spot.LAND;
        if (max > 0 && (distSq < 0 || distSq > (double) max * max)) return Spot.FAR;
        return Spot.OK;
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
