package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A 국도 direction sign (README "국도"): what it says and where its words go on the panel. Pure (unit-tested; RoadModule
 * puts it in the world as display entities, RoadPreviewTest hands it to the preview).
 *
 * <p>The sign is one model (economy:model/road_sign, tools/assetgen/models/roads.py: two grey steel posts, a green panel
 * 3 wide and 1.5 tall with a white border, a drawn arrow, a route shield, a backing frame) and a few text displays on its
 * face. It stands beside the road on the right (the kerb side for traffic coming from the start), its face towards that
 * traffic, the panel {@link #PANEL_BOTTOM} over the road so nothing hangs over the lanes below head height.
 *
 * <pre>
 *   +------------------------------------------------+
 *   |  ^                                             |
 *   | /|\      서해 하역장             (big)          |
 *   |  |                                             |
 *   | [7]  국도 7호선 (small)        7.0 km (medium)  |
 *   +------------------------------------------------+
 * </pre>
 *
 * Distances on the panel are model pixels (16 a block) from its bottom-left corner as the driver sees it: u to the right,
 * v up. The model (roads.py) draws the arrow and the shield in the same places.
 */
public final class RoadSign {

    private RoadSign() {}

    public enum Kind {
        /** a little way from the start: "서해 하역장 방면", the whole length */
        START,
        /** every roads.marker-every: the distance left */
        KM,
        /** just before the gate: "서해 하역장 도착" */
        ARRIVAL,
        /** facing the traffic going back: where the road started ("본사 출발 터미널") and how far ({@link RoadGuide}) */
        RETURN
    }

    /** How far right of the road's middle the sign stands (blocks): its panel spans 2.5 .. 5.5, over the kerb and shoulder. */
    public static final double LATERAL = 4.0;
    /** The panel's bottom and top over the road's surface, its width (blocks). */
    public static final double PANEL_BOTTOM = 3.0, PANEL_TOP = 4.5, PANEL_WIDTH = 3.0;
    /** How far in front of the sign's point (between the posts) the panel's face is, and the shield's (blocks). */
    public static final double FACE = 3 / 16.0, SHIELD_FACE = 3.5 / 16.0;
    /** The posts: this far either side of the middle (blocks). */
    public static final double POSTS = 0.75;

    /** Panel pixels: 48 x 24. */
    static final double PW = 48, PH = 24;

    /** What the sign says: the big line, the distance (or ""), the route line, the shield's number. */
    public record Words(String big, String km, String route, String number) {}

    /** A text display on the sign: its words, where its bottom middle is (blocks: right of the sign's middle, over the road, out of the face), scale. */
    public record Text(String text, double right, double up, double out, float scale) {}

    /** The words of a sign of a kind: dock is the dock's name (RETURN: the name of where the road started, as it is). */
    public static Words words(Kind kind, String dock, double km, int route) {
        String name = dock + " 하역장";
        String big = switch (kind) {
            case START -> name + " 방면";
            case KM -> name;
            case ARRIVAL -> name + " 도착";
            case RETURN -> dock;
        };
        return new Words(big, kind == Kind.ARRIVAL ? "" : km(km), "국도 " + route + "호선", String.valueOf(route));
    }

    // ------------------------------------------------------------------------------------------
    // Guide signs: a line for each way, its own arrow, where it goes and how far (RoadGuide)
    // ------------------------------------------------------------------------------------------

    /**
     * Which way a line of a guide sign points (an arrow model of its own: economy:model/road_arrow_&lt;name&gt;, drawn white
     * on the green panel). Ahead within 22.5 degrees: UP; up to 67.5: the slanted ones; beyond: LEFT / RIGHT.
     */
    public enum Arrow {
        LEFT("left"), UP_LEFT("up_left"), UP("up"), UP_RIGHT("up_right"), RIGHT("right");

        public final String model;

        Arrow(String model) {
            this.model = model;
        }

        /** The arrow for a turn of this many degrees (minus: to the left). */
        public static Arrow of(double degrees) {
            double a = Math.abs(degrees);
            if (a < 22.5) return UP;
            if (a < 67.5) return degrees < 0 ? UP_LEFT : UP_RIGHT;
            return degrees < 0 ? LEFT : RIGHT;
        }
    }

    /** A line of a guide sign: its arrow, where that way goes ("동해 하역장", "본사 출발 터미널"), how far ("3.2 km"). */
    public record Row(Arrow arrow, String name, String km) {}

    /** An arrow on a guide sign: which, its middle (as a {@link Text}'s place), its size (blocks). */
    public record ArrowAt(Arrow arrow, double right, double up, double out, double size) {}

    /**
     * The guide sign's panel (economy:model/road_sign_guide): 4 wide and 2 tall, its bottom as high as the small sign's,
     * standing a little further out ({@link #GUIDE_LATERAL}: 2.5 .. 6.5 from the road's middle, never over the lanes), its
     * posts this far either side of its middle.
     */
    public static final double GUIDE_LATERAL = 4.5, GUIDE_WIDTH = 4.0, GUIDE_TOP = 5.0, GUIDE_POSTS = 1.0;
    /** Guide panel pixels: 64 x 32. */
    static final double GW = 64, GH = 32;

    /** How big (px) the arrows of a sign with this many lines are, the largest scale its words take. */
    static double arrowPx(int lines) {
        return lines >= 3 ? 8 : 10;
    }

    static float guideMax(int lines) {
        return lines >= 3 ? 1.25f : lines == 2 ? 1.5f : 1.75f;
    }

    /** The middle (v, panel px up from its bottom) of line k of n: the lines share the panel inside its border. */
    static double rowV(int k, int n) {
        return GH - 2 - (k + 0.5) * (GH - 4) / n;
    }

    /** Where a line's words start (u) and end. */
    static double nameU(int n) {
        return 3 + arrowPx(n) + 2.5;
    }

    static final double KM_END = GW - 3;

    /** The one scale every line's words take: the largest (to 1/16) at which the longest line fits, at most guideMax. */
    static float guideScale(List<Row> rows) {
        int n = rows.size();
        float s = guideMax(n);
        for (Row r : rows) {
            double w = (width(r.name()) + width(r.km())) * PX;
            float fit = (float) Math.floor((KM_END - nameU(n) - 3) / w * 16) / 16f;
            s = Math.min(s, fit);
        }
        return Math.max(1 / 16f, s);
    }

    /** The words of a guide sign: each line's name from just right of its arrow, its distance to the right edge. */
    public static List<Text> guideTexts(List<Row> rows) {
        List<Text> out = new ArrayList<>();
        int n = rows.size();
        float s = guideScale(rows);
        for (int k = 0; k < n; k++) {
            Row r = rows.get(k);
            double v = rowV(k, n);
            double nw = width(r.name()) * PX * s, kw = width(r.km()) * PX * s;
            guide(out, r.name(), nameU(n) + nw / 2, v, s);
            if (!r.km().isEmpty()) guide(out, r.km(), KM_END - kw / 2, v, s);
        }
        return out;
    }

    private static void guide(List<Text> out, String text, double u, double v, float scale) {
        double right = (u - GW / 2 - PX * scale) / 16.0;   // as add(): the game draws a text display 1 text pixel right of its point
        double up = PANEL_BOTTOM + (v - middle(text) * PX * scale) / 16.0;
        out.add(new Text(text, right, up, FACE + 0.01, scale));
    }

    /** The arrows of a guide sign, one at the left of each line, just in front of the face. */
    public static List<ArrowAt> guideArrows(List<Row> rows) {
        List<ArrowAt> out = new ArrayList<>();
        int n = rows.size();
        double size = arrowPx(n);
        for (int k = 0; k < n; k++) {
            double u = 3 + size / 2;
            out.add(new ArrowAt(rows.get(k).arrow(), (u - GW / 2) / 16.0, PANEL_BOTTOM + rowV(k, n) / 16.0, FACE + 0.02, size / 16.0));
        }
        return out;
    }

    /** "7.0 km": to 0.1 km, never below 0.1 (a sign never says 0 km left). */
    public static String km(double km) {
        return String.format(Locale.ROOT, "%.1f km", Math.max(0.1, Math.round(km * 10) / 10.0));
    }

    /** The places on the panel (u, v of the middle; the width it may take), panel pixels. */
    record Slot(double u, double v, double width, float maxScale) {}

    static final Slot BIG = new Slot(29, 16.5, 32, 2.0f), ROUTE = new Slot(19.5, 6.5, 15, 1.0f), KM = new Slot(37, 6.5, 15.5, 1.5f),
            NUMBER = new Slot(6.5, 7, 5, 1.25f);

    /** A text display's pixel in model pixels at scale 1 (the game draws text at 1/40 block a pixel). */
    static final double PX = 16 / 40.0;
    /**
     * The middle of a line of text over the display's position (text pixels): the game stands a line 10 high on it and
     * draws the glyphs from its top, the game's digits 7 high (middle 6.5 up), the pack's Hangul 8 (0.5 lower: 5.5 up).
     */
    static double middle(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 0x1100) return 5.5;
        return 6.5;
    }

    /** The text displays of a sign. */
    public static List<Text> texts(Words w) {
        List<Text> out = new ArrayList<>();
        add(out, w.big(), BIG, FACE);
        add(out, w.route(), ROUTE, FACE);
        if (!w.km().isEmpty()) add(out, w.km(), KM, FACE);
        add(out, w.number(), NUMBER, SHIELD_FACE);
        return out;
    }

    private static void add(List<Text> out, String text, Slot s, double face) {
        float scale = scale(text, s);
        double right = (s.u() - PW / 2 - PX * scale) / 16.0;   // the game draws a text display 1 text pixel right of its point
        double up = PANEL_BOTTOM + (s.v() - middle(text) * PX * scale) / 16.0;
        out.add(new Text(text, right, up, face + 0.01, scale));
    }

    /** The largest scale (to 1/16) at which the text fits its slot, at most the slot's own. */
    static float scale(String text, Slot s) {
        double w = width(text) * PX;
        float fit = (float) Math.floor(s.width() / w * 16) / 16f;
        return Math.max(1 / 16f, Math.min(s.maxScale(), fit));
    }

    /**
     * How wide the game draws this text (text pixels, about): Hangul from the pack's Korean font about 8.5 a syllable, the
     * game's own letters and digits 6 (a few narrower), a space 4; the {@link #bold} ones 1 more.
     */
    public static double width(String text) {
        double w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            w += switch (c) {
                case ' ' -> 4;
                case '.', ',', ':', '!', 'i', '\'' -> 2;
                case 'l' -> 3;
                case 't', 'I', '[', ']' -> 4;
                case 'k', 'f', '<', '>' -> 5;
                default -> c >= 0x1100 ? 7.5 : 6;
            };
            if (c != ' ') w += bold(c) ? 2 : 1;
        }
        return Math.max(0, w - 1);
    }

    /**
     * Drawn in bold: digits and the decimal point (the distance, the route's number: heavy enough to read from afar). Not
     * Hangul: the pack's Korean font has strokes half a text pixel apart, and bold (half a pixel over) would close them
     * up (해 would read as a blot). Not the letters either ("km": bold m closes up the same way).
     */
    public static boolean bold(char c) {
        return (c >= '0' && c <= '9') || c == '.';
    }

    /**
     * World offset {dx, dz} of a point `right` to the right of a sign facing yaw (as its viewer sees it) and `out` in front
     * of it. Minecraft yaw: 0 faces south (+z), 90 west.
     */
    public static double[] offset(float yaw, double right, double out) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);   // the way the face looks
        return new double[]{right * fz + out * fx, -right * fx + out * fz};
    }

    /** The yaw that faces (fx, fz). */
    public static float yaw(double fx, double fz) {
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }
}
