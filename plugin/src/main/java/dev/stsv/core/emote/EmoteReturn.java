package dev.stsv.core.emote;

import org.bukkit.GameMode;

import java.util.Locale;
import java.util.Optional;

/**
 * What an emoting player gets back when the emote ends: where they stood, their game mode, their flight, and
 * whether they were invisible before. While the emote plays the player is a spectator riding a camera (see
 * {@link EmoteModule}); this is written on the player (a persistent data tag, {@link #encode}) for the whole time,
 * so a crash or a kill of the server cannot leave them a spectator at the camera's place: {@link EmoteModule} puts
 * them back when they are next seen.
 *
 * <p>No server is needed for any of this: what to restore for which ending is {@link #plan}.
 */
public record EmoteReturn(String world, double x, double y, double z, float yaw, float pitch, GameMode gameMode,
                          boolean allowFlight, boolean flying, boolean invisible) {

    /** Why an emote ends. */
    public enum End {
        /** The player sneaked (left the camera). */
        SNEAK("emote.stopped", true),
        /** /감정표현 그만. */
        COMMAND("emote.stopped", true),
        /** A played-once emote reached its end. */
        FINISHED(null, true),
        /** emotes.max-seconds. */
        TIME_UP("emote.time-up", true),
        QUIT(null, true),
        /** The player died: they respawn as the game decides. */
        DEATH(null, false),
        /** Something else moved the player (a command, a portal, another plugin): they stay where that put them. */
        TELEPORT("emote.interrupted", false),
        /** Something else changed the game mode: that mode is kept. */
        GAME_MODE("emote.interrupted", true),
        /** The camera was lost (its entity went away, the player got off it some other way). */
        LOST("emote.interrupted", true),
        /** The plugin is going down. */
        DISABLE(null, true),
        /** The emote could not start after all (no spectator mode: another plugin refused it). */
        FAILED("emote.not-now", true);

        /** The message for the player (messages.yml), null for none. */
        public final String message;
        /** Whether the player goes back to where they stood. */
        public final boolean back;

        End(String message, boolean back) {
            this.message = message;
            this.back = back;
        }
    }

    /**
     * What to do to the player at the end, in this order: leave the camera, set the game mode, teleport back (if
     * {@code back}), set the flight, and clear the invisible flag spectator mode set (if {@code visible}).
     */
    public record Plan(boolean back, GameMode gameMode, boolean allowFlight, boolean flying, boolean visible, String message) {}

    /**
     * @param end   why the emote ends
     * @param other for {@link End#GAME_MODE}: the mode something else just gave the player; else ignored
     */
    public Plan plan(End end, GameMode other) {
        GameMode mode = end == End.GAME_MODE && other != null ? other : gameMode;
        // creative and spectator fly by themselves; anything else flies only if it could before
        boolean fly = allowFlight || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
        boolean flyingNow = mode == GameMode.SPECTATOR || (fly && flying);
        return new Plan(end.back, mode, fly, flyingNow, !invisible && mode != GameMode.SPECTATOR, end.message);
    }

    /** The same with another game mode (something else changed it while the emote played). */
    public EmoteReturn withGameMode(GameMode mode) {
        return new EmoteReturn(world, x, y, z, yaw, pitch, mode, allowFlight, flying, invisible);
    }

    /** One line for the player's persistent data. */
    public String encode() {
        return String.join(";", "1", world, Double.toString(x), Double.toString(y), Double.toString(z), Float.toString(yaw),
                Float.toString(pitch), gameMode.name(), Boolean.toString(allowFlight), Boolean.toString(flying), Boolean.toString(invisible));
    }

    /** Reads {@link #encode}; empty for anything else (a damaged tag, another version's). */
    public static Optional<EmoteReturn> decode(String raw) {
        if (raw == null) return Optional.empty();
        String[] a = raw.split(";", -1);
        if (a.length != 11 || !a[0].equals("1") || a[1].isEmpty()) return Optional.empty();
        try {
            double x = Double.parseDouble(a[2]), y = Double.parseDouble(a[3]), z = Double.parseDouble(a[4]);
            float yaw = Float.parseFloat(a[5]), pitch = Float.parseFloat(a[6]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return Optional.empty();
            GameMode mode = GameMode.valueOf(a[7].toUpperCase(Locale.ROOT));
            // an emote never starts from spectator mode: such a tag would leave the player a spectator
            if (mode == GameMode.SPECTATOR) return Optional.empty();
            return Optional.of(new EmoteReturn(a[1], x, y, z, yaw, pitch, mode, bool(a[8]), bool(a[9]), bool(a[10])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean bool(String s) {
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw new IllegalArgumentException(s);
    }

    /**
     * The game mode for a player found with a tag that cannot be read: whoever is a spectator then was left so by
     * the emote and gets the server's default mode (never spectator); anyone else keeps theirs.
     */
    public static GameMode fallbackMode(GameMode current, GameMode serverDefault) {
        if (current != GameMode.SPECTATOR) return current;
        return serverDefault == null || serverDefault == GameMode.SPECTATOR ? GameMode.SURVIVAL : serverDefault;
    }
}
