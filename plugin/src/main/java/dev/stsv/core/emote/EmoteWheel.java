package dev.stsv.core.emote;

import java.util.List;
import java.util.Set;

/**
 * The emote wheel (감정표현 휠): seven places, six round the rim and one in the middle, each holding one of the
 * player's emotes. Pure (unit-tested): what a wheel shows for what a player arranged and what they own.
 */
public final class EmoteWheel {

    /** Places of the wheel: 0 at the top, on clockwise, the last in the middle (menus/emote_wheel.yml's wheel letters). */
    public static final int SLOTS = 7;
    /** The emotes the pack has an icon of its own for (stsv:gui/emote_&lt;id&gt;); any other shows {@link #ICON_ANY}. */
    public static final Set<String> DRAWN = Set.of("twerk", "take_l", "bow", "disco", "cheer");
    public static final String ICON_ANY = "emote_any", ICON_EMPTY = "emote_empty";

    private EmoteWheel() {}

    /** The pack's icon of an emote ("emote_twerk"; a new emote nobody drew yet: the plain one). */
    public static String icon(String emoteId) {
        return DRAWN.contains(emoteId) ? "emote_" + emoteId : ICON_ANY;
    }

    /**
     * What a wheel shows.
     *
     * @param arranged what the player put where (null in a place: empty), or null when they never arranged one
     * @param owned    the emotes the player may play now, in the order the emotes file has them
     * @return an emote's id a place, null for an empty one. Never arranged: the owned emotes in order from the top.
     *         Arranged: as arranged, without what the player no longer has.
     */
    public static String[] shown(String[] arranged, List<String> owned) {
        String[] out = new String[SLOTS];
        if (arranged == null) {
            for (int k = 0; k < SLOTS && k < owned.size(); k++) out[k] = owned.get(k);
            return out;
        }
        for (int k = 0; k < SLOTS && k < arranged.length; k++) {
            if (arranged[k] != null && owned.contains(arranged[k])) out[k] = arranged[k];
        }
        return out;
    }

    /**
     * Puts an emote into a place (null: empties it). An emote sits in one place only: where it was before is emptied.
     *
     * @param shown the wheel as it is shown now ({@link #shown})
     */
    public static String[] placed(String[] shown, int slot, String emote) {
        String[] out = new String[SLOTS];
        System.arraycopy(shown, 0, out, 0, Math.min(SLOTS, shown.length));
        if (slot < 0 || slot >= SLOTS) return out;
        if (emote != null) {
            for (int k = 0; k < SLOTS; k++) if (emote.equals(out[k])) out[k] = null;
        }
        out[slot] = emote;
        return out;
    }
}
