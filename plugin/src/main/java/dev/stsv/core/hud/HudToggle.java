package dev.stsv.core.hud;

import java.util.Locale;

/**
 * The per-player sidebar switch (/사이드바): kept on the player as one byte (0 = hidden), absent = shown, so every
 * player starts with the sidebar and a streamer's choice survives logout, death and restarts.
 */
public final class HudToggle {

    /** Where the byte lives (the player's persistent data in game, a field in tests). */
    public interface Store {
        Byte get();

        /** null removes the value. */
        void set(Byte value);
    }

    private HudToggle() {}

    public static boolean shown(Store store) {
        Byte b = store.get();
        return b == null || b != 0;
    }

    /**
     * Applies a /사이드바 argument: none = switch, 켜기/on = show, 끄기/off = hide. Returns the new state, or null
     * for an argument it does not know (nothing changed).
     */
    public static Boolean apply(Store store, String arg) {
        Boolean want;
        if (arg == null || arg.isBlank()) want = !shown(store);
        else want = switch (arg.trim().toLowerCase(Locale.ROOT)) {
            case "켜기", "켬", "on", "show", "보기" -> Boolean.TRUE;
            case "끄기", "끔", "off", "hide", "숨기기" -> Boolean.FALSE;
            default -> null;
        };
        if (want == null) return null;
        store.set(want ? null : (byte) 0);
        return want;
    }
}
