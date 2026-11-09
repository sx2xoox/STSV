package dev.stsv.core.hud;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a player's sidebar panel goes (/사이드바 위치): the game tells the server nothing about the window, and a
 * boss bar title is placed from the middle of the screen, so the panel's right edge is a distance from the middle.
 * A player who gives their window size (and GUI scale, or "auto") gets it exactly against their right edge; +N / -N
 * nudge it; 기본 goes back to the server's default (hud.sidebar.right).
 *
 * The game's GUI size: scale "auto" is the largest whole scale that keeps the GUI at least 320 x 240 (a larger
 * chosen scale is cut down to that too); the GUI is ceil(window / scale) wide; the title's middle is at
 * floor(width / 2), so ceil(width / 2) px are right of it.
 */
public final class HudPosition {

    /** GUI pixels between the panel and the screen's right edge. */
    static final int MARGIN = 2;
    static final int MIN_RIGHT = 60, MAX_RIGHT = 2400;

    public enum Kind { RESET, WINDOW, NUDGE, EXACT, USAGE }

    /** What a /사이드바 위치 argument asks for: kind, the new right edge (WINDOW, NUDGE, EXACT), the window. */
    public record Change(Kind kind, int right, int width, int height, int scale) {}

    private static final Pattern WINDOW = Pattern.compile("(\\d{3,5})\\s*[x×*]\\s*(\\d{3,5})");

    private HudPosition() {}

    /** The GUI scale the game uses for a window and a chosen scale (0 or less = auto). */
    public static int guiScale(int width, int height, int wanted) {
        int max = 1;
        while (max < width && max < height && width / (max + 1) >= 320 && height / (max + 1) >= 240) max++;
        return wanted <= 0 ? max : Math.min(wanted, max);
    }

    /** The GUI width of a window at a scale. */
    public static int guiWidth(int width, int scale) {
        return -Math.floorDiv(-width, scale);
    }

    /** The panel's right edge (px right of the middle) that puts it MARGIN px from the right of this window. */
    public static int rightFor(int width, int height, int wanted) {
        int gw = guiWidth(width, guiScale(width, height, wanted));
        return gw - gw / 2 - MARGIN;
    }

    /**
     * Reads the arguments after "위치": "1920x1080" (auto scale), "1920x1080 3", "+10", "-5", "250" (exact), "기본".
     * Anything else: USAGE.
     */
    public static Change parse(String[] args, int current) {
        if (args.length == 0) return new Change(Kind.USAGE, current, 0, 0, 0);
        String a = args[0].trim().toLowerCase(Locale.ROOT);
        if (a.equals("기본") || a.equals("reset") || a.equals("default") || a.equals("초기화")) {
            return new Change(Kind.RESET, current, 0, 0, 0);
        }
        String joined = String.join(" ", args).toLowerCase(Locale.ROOT);
        Matcher m = WINDOW.matcher(joined);
        if (m.lookingAt()) {
            int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
            String rest = joined.substring(m.end()).trim().replace("gui", "").trim();
            int wanted;
            if (rest.isEmpty() || rest.equals("자동") || rest.equals("auto")) wanted = 0;
            else if (rest.matches("\\d{1,2}")) wanted = Integer.parseInt(rest);
            else return new Change(Kind.USAGE, current, 0, 0, 0);
            if (w < 320 || h < 240) return new Change(Kind.USAGE, current, 0, 0, 0);
            int scale = guiScale(w, h, wanted);
            return new Change(Kind.WINDOW, clamp(rightFor(w, h, wanted)), w, h, scale);
        }
        if (args.length == 1 && a.matches("[+-]\\d{1,4}")) {
            return new Change(Kind.NUDGE, clamp(current + Integer.parseInt(a)), 0, 0, 0);
        }
        if (args.length == 1 && a.matches("\\d{2,4}")) {
            return new Change(Kind.EXACT, clamp(Integer.parseInt(a)), 0, 0, 0);
        }
        return new Change(Kind.USAGE, current, 0, 0, 0);
    }

    static int clamp(int right) {
        return Math.max(MIN_RIGHT, Math.min(MAX_RIGHT, right));
    }
}
