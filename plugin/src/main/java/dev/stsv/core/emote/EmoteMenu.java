package dev.stsv.core.emote;

/**
 * How the emote wheel is reached: /감정표현 without a word after it, and the quick key (Ctrl + Shift while standing
 * still). The wheel itself is a menu of the pack (menus/emote_wheel.yml, {@link EmoteScreens}); a place clicked runs
 * the emote as the command would, so everything the command checks is checked.
 */
final class EmoteMenu {

    /** The quick key opens the wheel no more often than this. */
    static final long QUICK_KEY_GAP_MS = 1000;

    private EmoteMenu() {}

    /** The quick key: sneak and sprint held, nothing else (not walking, not jumping). */
    static boolean quickKey(boolean sneak, boolean sprint, boolean moving) {
        return sneak && sprint && !moving;
    }

    /** What plays an emote by its name: the emote command. */
    static String command(String name) {
        return "/감정표현 " + name;
    }
}
