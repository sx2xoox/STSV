package dev.stsv.core.emote;

import org.joml.Vector3d;

import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * The rules of an emote that need no server: whether one may start here and now, the wait between two, and where the
 * watching camera goes.
 */
public final class EmoteRules {

    /** No emote within this long of taking damage: it is no way out of a fight (the wardrobe's rule). */
    public static final long HURT_MILLIS = 10_000;
    /** The camera looks at this height of the puppet (blocks over its feet). */
    public static final double AIM_HEIGHT = 0.95;
    /** The camera stays this far off whatever is behind it, and is never closer than this to the puppet. */
    public static final double WALL_MARGIN = 0.35, MIN_DISTANCE = 1.4;
    /**
     * Where the camera is tried, in this order: degrees added to the emote's own place around the puppet, and
     * degrees added to its height. The first with the whole distance free wins; else the one with the most room.
     */
    static final double[][] TRIES = {{0, 0}, {30, 0}, {-30, 0}, {0, 20}, {60, 0}, {-60, 0}, {60, 20}, {-60, 20},
            {105, 0}, {-105, 0}, {150, 0}, {-150, 0}, {180, 0}, {0, 45}, {180, 45}, {0, 75}};

    /** How an emote is watched (config emotes.view). */
    public enum View {
        /** The puppet rides the player, who is hidden: they walk and look around as they like. */
        BODY,
        /** The puppet stands where the player stood; the player watches it from a camera and cannot move. */
        CAMERA;

        /** Anything but "camera" is the body. */
        public static View of(String setting) {
            return setting != null && setting.trim().equalsIgnoreCase("camera") ? CAMERA : BODY;
        }
    }

    /** What a player who emotes in their own body is doing in a tick. */
    public record Body(boolean dead, boolean spectator, boolean inVehicle, boolean sneaking, boolean lying, boolean hidden) {}

    /**
     * Why an emote in the player's own body ends this tick, or null. The puppet stands upright on its player's
     * feet, so it fits nothing but a standing player; and it is only there while the player's own body is hidden.
     *
     * @param b {@code lying}: swimming, crawling, gliding, sleeping, a riptide; {@code hidden}: the invisibility
     *          the emote gave is still on
     */
    public static EmoteReturn.End bodyEnd(Body b) {
        if (b.dead()) return EmoteReturn.End.DEATH;
        if (b.sneaking()) return EmoteReturn.End.SNEAK;
        if (b.spectator() || b.inVehicle() || b.lying() || !b.hidden()) return EmoteReturn.End.LOST;
        return null;
    }

    /** Whether a riding puppet is turned again: its player looks {@code step} degrees or more away from where it does. */
    public static boolean turned(float sent, float yaw, float step) {
        float d = (yaw - sent) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return Math.abs(d) >= step;
    }

    /** What the player is doing when they ask for an emote. */
    public record Situation(boolean dead, boolean inVehicle, boolean spectator, boolean inWardrobe, boolean moving, boolean inLiquid,
                            boolean burning, boolean onGround, float fallDistance, boolean invisible, long sinceHurtMillis) {}

    private EmoteRules() {}

    /**
     * Why an emote cannot start (message key), or null. The player is safe while it plays and comes back to the same
     * spot, so it must not cut a fall, a fight or a fire short; and the puppet shows where an invisible player stands.
     *
     * @param s {@code moving}: gliding, swimming, sleeping, riptiding; {@code sinceHurtMillis}: negative when never hurt
     */
    public static String refusal(Situation s) {
        if (s.dead() || s.spectator() || s.inWardrobe() || s.moving() || s.invisible()) return "emote.not-now";
        if (s.inVehicle()) return "emote.dismount";
        if (s.inLiquid()) return "emote.in-water";
        if (s.burning()) return "emote.burning";
        if (!s.onGround() || s.fallDistance() > 0.5f) return "emote.not-ground";
        if (s.sinceHurtMillis() >= 0 && s.sinceHurtMillis() < HURT_MILLIS) return "emote.in-combat";
        return null;
    }

    /** Seconds still to wait after an emote that ended at {@code endedAt} (0: go ahead). */
    public static long cooldownLeft(long endedAt, long now, int cooldownSeconds) {
        if (endedAt <= 0 || cooldownSeconds <= 0) return 0;
        long left = endedAt + cooldownSeconds * 1000L - now;
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** The emote's tick limit from emotes.max-seconds (at least a second). */
    public static long maxTicks(int maxSeconds) {
        return Math.max(1, maxSeconds) * 20L;
    }

    /**
     * The unit vector from the puppet's middle towards a camera place, in the world.
     *
     * @param bodyYaw the puppet's yaw (degrees, the game's: 0 looks at +z, 90 at -x)
     * @param around  degrees around the puppet from its front towards its left
     * @param up      degrees above the horizon
     */
    public static Vector3d direction(float bodyYaw, double around, double up) {
        double a = Math.toRadians(around), e = Math.toRadians(up);
        // the puppet's own frame (+x its left, +z its front), turned to the world like the puppet (PuppetModel.place)
        return new Vector3d(Math.sin(a) * Math.cos(e), Math.sin(e), Math.cos(a) * Math.cos(e)).rotateY(-Math.toRadians(bodyYaw));
    }

    /** A camera place: the offset from the point looked at (the camera looks back along it). */
    public record CameraSpot(Vector3d offset) {}

    /**
     * Where the camera goes: the emote's place if nothing is in the way, else the nearest other try with the whole
     * distance free, else the try with the most room (pulled in front of the wall), or empty when even that is
     * closer than {@link #MIN_DISTANCE}.
     *
     * @param free how far it is free from the point looked at along a direction, up to the distance asked (the ray
     *             to the first block in the way)
     */
    public static Optional<CameraSpot> camera(EmoteSpec.Camera camera, float bodyYaw, ToDoubleFunction<Vector3d> free) {
        Vector3d best = null;
        double bestRoom = 0;
        for (double[] t : TRIES) {
            Vector3d dir = direction(bodyYaw, camera.around() + t[0], Math.min(camera.up() + t[1], 85));
            double room = Math.min(free.applyAsDouble(new Vector3d(dir)), camera.distance() + WALL_MARGIN) - WALL_MARGIN;
            if (room >= camera.distance() - 1e-6) return Optional.of(new CameraSpot(dir.mul(camera.distance())));
            if (room > bestRoom) {
                bestRoom = room;
                best = dir;
            }
        }
        return best == null || bestRoom < MIN_DISTANCE ? Optional.empty() : Optional.of(new CameraSpot(best.mul(bestRoom)));
    }
}
