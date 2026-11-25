package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * The action bar's one line for a seated player (README "도박장", HallTable#actionLine): parts joined by a space, kept
 * inside {@link #MOST} px (the screen's middle ± 106: past that it would run into the bottom-right board's top rows).
 * A part that does not fit goes, the least needed first (what I put in, then the pot, then my chips: the table shows
 * the pot, the chips are on the hand's result). Widths are the HUD letters' (Galmuri, the core's HudLayout), estimated
 * from the plain text a little generously. Pure (unit-tested).
 */
public final class ActionLine {

    private ActionLine() {}

    /** The widest the line may be (px): twice the core's HudLayout.BOARD_CLEAR_X. */
    public static final int MOST = 212;

    /** A part of the line: its MiniMessage, its plain text (to measure) and when it goes (0: never, 1 first, 2 next ...). */
    public record Part(String mm, String plain, int drop) {}

    /** A letter's width on the action bar (px, its advance): Hangul and the arrows 10, a space · comma · dot 4, the rest 6. */
    static int width(int cp) {
        if (cp >= 0xAC00 && cp <= 0xD7A3) return 10;
        if (cp == '▶' || cp == '▷' || cp == '▸') return 10;
        if (cp == ' ' || cp == ',' || cp == '.' || cp == '·' || cp == '[' || cp == ']' || cp == '!' || cp == '|' || cp == ':') return 4;
        return 6;
    }

    public static int width(String plain) {
        int w = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            w += width(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    /** The parts that fit, in their order (the droppable ones dropped by their rank until the line fits). */
    public static List<Part> fit(List<Part> parts, int most, int gap) {
        List<Part> kept = new ArrayList<>(parts);
        int rank = 1;
        while (total(kept, gap) > most) {
            final int r = rank;
            boolean any = kept.removeIf(p -> p.drop() == r);
            if (!any && kept.stream().noneMatch(p -> p.drop() > r)) break;   // nothing left to drop
            rank++;
        }
        return kept;
    }

    static int total(List<Part> parts, int gap) {
        int w = 0;
        for (Part p : parts) w += width(p.plain());
        return w + Math.max(0, parts.size() - 1) * gap;
    }
}
