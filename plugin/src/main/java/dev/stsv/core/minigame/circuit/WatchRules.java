package dev.stsv.core.minigame.circuit;

import java.util.List;
import java.util.UUID;

/**
 * 관전 (watching a race): the rules that need no server, unit-tested. Who may start watching, which racer the camera goes
 * to next, the views and the chase camera's place.
 */
public final class WatchRules {

    private WatchRules() {}

    /** The camera: behind the racer's kart (추격), through their eyes (1인칭), or flying freely over the track (자유). */
    public enum View {
        CHASE("chase"), ONBOARD("onboard"), FREE("free");

        public final String id;

        View(String id) {
            this.id = id;
        }

        public View next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** Does the camera hang on a racer (A·D change the racer, sneaking twice leaves)? */
        public boolean follows() {
            return this != FREE;
        }
    }

    /** The race's stage as far as watching goes. */
    public enum Stage { LOBBY, RUNNING, ENDING, CLOSED }

    /**
     * Why a player may not start watching (a message key), or null when they may.
     *
     * @param enabled    minigames.circuit.spectate.enabled
     * @param stage      the race's stage
     * @param watchers   how many watch it now
     * @param max        minigames.circuit.spectate.max-per-race
     * @param inRace     the player is in a race (its waiting room too)
     * @param watching   the player already watches a race
     * @param returnLeft the player still carries a way back from an earlier game (never written over)
     * @param busy       dead, in the wardrobe, in an emote, already a spectator
     */
    public static String check(boolean enabled, Stage stage, int watchers, int max, boolean inRace, boolean watching, boolean returnLeft, boolean busy) {
        if (!enabled) return "circuit.watch-off";
        if (inRace) return "circuit.already";
        if (watching) return "circuit.watch-already";
        if (returnLeft || busy) return "circuit.not-now";
        if (stage == Stage.LOBBY) return "circuit.watch-lobby";
        if (stage != Stage.RUNNING) return "circuit.watch-none";
        if (watchers >= Math.max(0, max)) return "circuit.watch-full";
        return null;
    }

    /**
     * The racer after (dir = 1) or before (dir = -1) current in the race's order, round the end; the first (or the
     * last) when current is not in it; null when nobody races.
     */
    public static UUID next(List<UUID> order, UUID current, int dir) {
        if (order.isEmpty()) return null;
        int i = current == null ? -1 : order.indexOf(current);
        if (i < 0) return dir >= 0 ? order.getFirst() : order.getLast();
        return order.get(Math.floorMod(i + (dir >= 0 ? 1 : -1), order.size()));
    }

    /** The racer the camera keeps: current while they race, else the one now in their place (or the first). */
    public static UUID keep(List<UUID> order, UUID current, int lastPlace) {
        if (order.isEmpty()) return null;
        if (current != null && order.contains(current)) return current;
        return order.get(Math.clamp(lastPlace, 0, order.size() - 1));
    }

    /** Chase camera: this far behind the kart and this high above it (blocks), looking down this much (degrees). */
    public static final double CHASE_BACK = 5.2, CHASE_UP = 2.3, CHASE_PITCH = 13;

    /**
     * Turns a heading towards another by a share of the way (the short way round), so the chase camera swings round a
     * turn instead of snapping with every twitch of a drift. Degrees.
     */
    public static float approach(float from, float to, double share) {
        double d = ((to - from) % 360 + 540) % 360 - 180;
        double out = (from + d * Math.clamp(share, 0, 1)) % 360;
        return (float) (out < 0 ? out + 360 : out);
    }

    /** Where the chase camera hangs for a kart at (x, y, z) heading yaw (Minecraft degrees): {x, y, z}. */
    public static double[] chase(double x, double y, double z, float yaw, double back, double up) {
        double r = Math.toRadians(yaw);
        // Minecraft: yaw 0 faces +z, 90 faces -x
        double fx = -Math.sin(r), fz = Math.cos(r);
        return new double[]{x - fx * back, y + up, z - fz * back};
    }

    /**
     * Keeps a free camera over the track: a point moved back inside the box (min, max inclusive) widened by margin;
     * null when it is inside already.
     */
    public static double[] inside(double x, double y, double z, double minX, double maxX, double minY, double maxY, double minZ, double maxZ, double margin) {
        double cx = Math.clamp(x, minX - margin, maxX + margin), cy = Math.clamp(y, minY - margin, maxY + margin), cz = Math.clamp(z, minZ - margin, maxZ + margin);
        return cx == x && cy == y && cz == z ? null : new double[]{cx, cy, cz};
    }

    /** Two sneaks this close (ticks) leave; a first one only asks. */
    public static final int LEAVE_TICKS = 60;

    /** Does a sneak at tick now leave (the one before was at last, -1 = none)? */
    public static boolean leaves(int last, int now) {
        return last >= 0 && now - last <= LEAVE_TICKS;
    }
}
