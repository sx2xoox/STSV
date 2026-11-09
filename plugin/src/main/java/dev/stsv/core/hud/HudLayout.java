package dev.stsv.core.hud;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the resource-pack HUD draws everything, in the game's GUI pixels, and the fonts that put it there.
 *
 * The game places these lines itself, relative to the screen, the same at every GUI scale and window shape:
 * <ul>
 *   <li>action bar: centred on the screen's middle, pen line at (guiHeight - 72); a glyph's top is at pen line
 *       + 7 - ascent, so a glyph whose top should be D pixels above the bottom of the screen gets ascent D - 65.
 *       The bars take the rows of the game's hearts (top 39 above the bottom) and armor (49), within the
 *       hotbar's width (x -91 .. 91), and leave the middle (|x| &lt; 14) to the experience level the game draws
 *       there.</li>
 *   <li>boss bar title (the first boss bar): centred, pen line at y 3 from the top; a glyph top at y gets ascent
 *       10 - y. The sidebar panel lives there, its right edge {@code right} pixels right of the screen's
 *       middle, below the status effect icons.</li>
 * </ul>
 * Nothing in the game anchors a server's text to the right edge of the screen, and the server is never told the
 * window size or GUI scale, so the panel is placed from the middle: by default where the right edge of a 16:9
 * window at its automatic GUI scale is (720p, 1440p and 4K all give a GUI width of 427: 213 px right of the
 * middle), and per player exactly at their own right edge once they give their window size (HudPosition).
 *
 * A centred line starts at -(width / 2). Every part drawn here is measured exactly (HudSheets, HudText), so each
 * piece is placed by absolute x: the bars do not move when a message of any length is shown on the same action
 * bar, and the panel's right edge stays put whatever its text.
 */
public final class HudLayout {

    /** Positions and sizes from config.yml (hud.sidebar, hud.health). */
    public record Settings(int sidebarRight, int sidebarY, int sidebarMaxWidth, int sidebarRows, boolean armor) {

        public Settings {
            sidebarMaxWidth = Math.max(80, Math.min(260, sidebarMaxWidth));
            sidebarRows = Math.max(1, Math.min(12, sidebarRows));
            sidebarY = Math.max(0, Math.min(400, sidebarY));
            sidebarRight = clampRight(sidebarRight);
        }

        public static Settings defaults() {
            return new Settings(DEFAULT_RIGHT, 54, 132, 7, true);
        }
    }

    public static int clampRight(int right) {
        return Math.max(-400, Math.min(2400, right));
    }

    public enum Tint { NORMAL, POISON, WITHER, FROZEN }

    /** A short flash of the health bar: the part just lost or just gained, for a moment after the change. */
    public enum Flash { LOST, GAIN }

    /** The health of what the player rides (a living mount: it takes the hunger bar's place, as in the game). */
    public record Mount(double health, double max) {}

    /** The air left, shown under water and while it refills (as the game shows its bubbles). */
    public record Air(int remaining, int max) {}

    /**
     * What the bars show. flashFrom: health + absorption before the change the flash shows (with flash only).
     */
    public record Health(double health, double max, double absorption, int food, float saturation, int armor,
                         Tint tint, boolean hungry, Mount mount, Air air, Flash flash, double flashFrom) {

        public Health(double health, double max, double absorption, int food, float saturation, int armor, Tint tint,
                      boolean hungry) {
            this(health, max, absorption, food, saturation, armor, tint, hungry, null, null, null, 0);
        }

        public Health withMount(Mount m) {
            return new Health(health, max, absorption, food, saturation, armor, tint, hungry, m, air, flash, flashFrom);
        }

        public Health withAir(Air a) {
            return new Health(health, max, absorption, food, saturation, armor, tint, hungry, mount, a, flash, flashFrom);
        }

        public Health withFlash(Flash f, double from) {
            return new Health(health, max, absorption, food, saturation, armor, tint, hungry, mount, air, f, f == null ? 0 : from);
        }

        /** Health and absorption together. */
        public double total() {
            return health + Math.max(0, absorption);
        }
    }

    /** One sidebar entry: an icon of the icons sheet (or null), its text (a tab between label and value), and a divider line above it. */
    public record Row(String icon, Component text, boolean divider) {}

    static final int DEFAULT_RIGHT = 212;
    // action bar rows (distance of a glyph's top from the bottom of the screen)
    static final int ROW1 = 39, ROW2 = 49;
    // a bar: [number][icon][frame] on the left half, mirrored on the right; the fill grows away from the icon
    static final int INNER = 50, FRAME_W = INNER + 2, ICON = 9;
    static final int LEFT_FRAME = -65, LEFT_ICON = -75, LEFT_NUMBER_END = -76;
    static final int RIGHT_FRAME = 14, RIGHT_ICON = 67, RIGHT_NUMBER = 77;
    static final int DIGIT_PITCH = 4;
    static final int[] FILL_PIECES = {32, 16, 8, 4, 2, 1};
    // the panel (HEAD: title strip down to the first row; ROW: row pitch; FOOT: bevel, outline, shadow)
    static final int HEAD = 19, ROW = 13, FOOT = 3, CAP = 4, PAD = 5, ICON_GAP = 3, TEXT_X = PAD + ICON + ICON_GAP;
    static final int VALUE_GAP = 8, MIN_WIDTH = 64, MARK_X = 5;
    static final int[] SEGMENTS = {64, 32, 16, 8, 4, 2, 1};

    static final TextColor GOLD = TextColor.color(0xFFD25A), LOW = TextColor.color(0xFF7A70),
            POISON = TextColor.color(0xC4E67A), WITHER = TextColor.color(0xB4AAB8), FROZEN = TextColor.color(0xB4E6FF),
            HUNGER = TextColor.color(0xC8D278), AIR = TextColor.color(0xB4DCFF);

    private final HudSheets sheets;
    private final Settings settings;
    private final HudFont bar = new HudFont("bar");
    private final HudFont panel = new HudFont("panel");
    private final HudFont line = new HudFont("line");
    private final List<HudFont> rows = new ArrayList<>();

    public HudLayout(HudSheets sheets, Settings settings) {
        this.sheets = sheets;
        this.settings = settings;
        for (int d : new int[]{ROW1, ROW2}) {
            bar.add(sheets.sheet("icons"), action(d));
            bar.add(sheets.sheet("frame"), action(d));
            bar.add(sheets.sheet("fill"), action(d - 1));
            bar.add(sheets.sheet("tick"), action(d - 1));
            bar.add(sheets.sheet("digits"), action(d - 1));
        }
        bar.add(sheets.sheet("line"), action(ROW1 - 7));
        line.text(sheets, 9);
        line.fallback();
        panel.add(sheets.sheet("panel_head"), boss(settings.sidebarY()));
        for (int i = 0; i < settings.sidebarRows(); i++) {
            int top = rowTop(i);
            panel.add(sheets.sheet("panel_row"), boss(top));
            panel.add(sheets.sheet("icons"), boss(top));
            panel.add(sheets.sheet("panel_foot"), boss(rowTop(i + 1)));
            if (i > 0) panel.add(sheets.sheet("panel_div"), boss(top - 2));
            HudFont f = new HudFont("row" + i);
            f.text(sheets, boss(top));
            rows.add(f);
        }
    }

    public Settings settings() {
        return settings;
    }

    /** Ascent of an action-bar glyph whose top is d pixels above the bottom of the screen. */
    static int action(int d) {
        return d - 65;
    }

    /** Ascent of a glyph in the first boss bar's title whose top is y pixels below the top of the screen. */
    static int boss(int y) {
        return 10 - y;
    }

    int rowTop(int i) {
        return settings.sidebarY() + HEAD + ROW * i;
    }

    /** Every font file (pack path -> JSON). */
    public Map<String, JsonObject> fonts() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        List<HudFont> all = new ArrayList<>(List.of(bar, panel, line));
        all.addAll(rows);
        for (HudFont f : all) out.put(f.path(), f.json());
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Action bar: a message (centred, normal line) and the bars
    // ------------------------------------------------------------------------------------------

    /** Width a message takes on the action bar line (the game centres it). */
    int messageWidth(Component message) {
        return HudText.width(line, HudText.untab(HudText.cells(message)));
    }

    /**
     * The action bar: the message (null for none) in its usual place, then the bars (null for none), placed by
     * absolute x with zero net advance, so the game still centres the message by its own width.
     */
    public Component actionBar(Health h, Component message) {
        List<HudText.Cell> text = message == null ? List.of() : HudText.untab(HudText.cells(message));
        int w = HudText.width(line, text);
        Ink ink = new Ink(-(w / 2));
        ink.text(line, text);
        int end = ink.pen();
        if (h != null) {
            healthBar(ink, h);
            if (h.mount() != null) mountBar(ink, h.mount());
            else foodBar(ink, h);
            if (h.armor() > 0 && settings.armor()) armorBar(ink, h);
            if (h.air() != null) airBar(ink, h.air());
            ink.to(bar, end);
        }
        return ink.build();
    }

    /** A run of one fill colour over the inside pixels [from, to), counted from the bar's icon side. */
    record Span(String colour, int from, int to) {}

    private enum Side { LEFT, RIGHT }

    private void healthBar(Ink ink, Health h) {
        double scale = Math.max(h.max(), h.total());
        int kh = fill(h.health(), scale, INNER), kt = Math.max(kh, fill(h.total(), scale, INNER));
        String colour = switch (h.tint()) {
            case POISON -> "poison";
            case WITHER -> "wither";
            case FROZEN -> "frozen";
            default -> "red";
        };
        List<Span> spans = new ArrayList<>(List.of(new Span(colour, 0, kh), new Span("abs", kh, kt)));
        String rim = "frame";
        if (h.flash() != null) {
            int kf = fill(h.flashFrom(), scale, INNER);
            if (h.flash() == Flash.LOST && kf > kt) {
                spans.add(new Span("lost", kt, kf));
                rim = "frame_hit";
            } else if (h.flash() == Flash.GAIN && kf < kt) {
                spans.add(new Span("gain", kf, kt));
                rim = "frame_heal";
            }
        }
        String heart = switch (h.tint()) {
            case POISON -> "heart_poison";
            case WITHER -> "heart_wither";
            case FROZEN -> "heart_frozen";
            default -> "heart";
        };
        drawBar(ink, Side.LEFT, ROW1, heart, rim, spans, kt, segments(scale, 2));
        TextColor c = h.absorption() > 0 ? GOLD : switch (h.tint()) {
            case POISON -> POISON;
            case WITHER -> WITHER;
            case FROZEN -> FROZEN;
            default -> h.health() <= Math.min(4, h.max() / 4) ? LOW : null;
        };
        number(ink, Side.LEFT, ROW1, healthText(h.total()), c);
    }

    private void foodBar(Ink ink, Health h) {
        int k = fill(h.food(), 20, INNER);
        drawBar(ink, Side.RIGHT, ROW1, h.hungry() ? "food_hunger" : "food", "frame",
                List.of(new Span(h.hungry() ? "hunger" : "food", 0, k)), k, 10);
        int s = fill(Math.min(h.saturation(), Math.max(0, h.food())), 20, INNER);
        if (s > 0) pieces(ink, "line", "sat", action(ROW1 - 7), Side.RIGHT, 0, s);
        number(ink, Side.RIGHT, ROW1, String.valueOf(Math.max(0, h.food())), h.hungry() ? HUNGER : null);
    }

    private void mountBar(Ink ink, Mount m) {
        double scale = Math.max(1, m.max());
        int k = fill(m.health(), scale, INNER);
        drawBar(ink, Side.RIGHT, ROW1, "mount", "frame", List.of(new Span("mount", 0, k)), k, segments(scale, 2));
        number(ink, Side.RIGHT, ROW1, healthText(m.health()), m.health() <= scale / 4 ? LOW : null);
    }

    private void armorBar(Ink ink, Health h) {
        int scale = Math.max(20, h.armor());
        int k = fill(h.armor(), scale, INNER);
        drawBar(ink, Side.LEFT, ROW2, "armor", "frame", List.of(new Span("armor", 0, k)), k, segments(scale, 2));
        number(ink, Side.LEFT, ROW2, String.valueOf(h.armor()), null);
    }

    private void airBar(Ink ink, Air a) {
        int max = Math.max(1, a.max()), left = Math.max(0, Math.min(max, a.remaining()));
        int k = fill(left, max, INNER);
        drawBar(ink, Side.RIGHT, ROW2, "air", "frame", List.of(new Span("air", 0, k)), k, segments(max, 30));
        number(ink, Side.RIGHT, ROW2, String.valueOf(bubbles(left, max)), AIR);
    }

    /** Bubbles left out of 10, as the game counts them (a part bubble counts). */
    static int bubbles(int remaining, int max) {
        if (remaining <= 0 || max <= 0) return 0;
        return (int) Math.ceil(remaining * 10.0 / max - 1e-9);
    }

    /**
     * Icon, frame, fills (later spans over earlier ones), the bright leading edge of the solid fill (filled px),
     * then the segment ticks: dark over the fill, faint over the empty part.
     */
    private void drawBar(Ink ink, Side side, int row, String icon, String rim, List<Span> spans, int filled, int segments) {
        int asc = action(row), in = action(row - 1);
        ink.to(bar, side == Side.LEFT ? LEFT_ICON : RIGHT_ICON).glyph(bar, bar.glyph("icons", icon, asc));
        ink.to(bar, side == Side.LEFT ? LEFT_FRAME : RIGHT_FRAME).glyph(bar, bar.glyph("frame", rim, asc));
        String edge = null;
        int covered = 0;
        for (Span s : spans) {
            if (s.to() <= s.from()) continue;
            pieces(ink, "fill", s.colour(), in, side, s.from(), s.to());
            covered = Math.max(covered, s.to());
            if (s.to() == filled && !s.colour().equals("lost") && !s.colour().equals("gain")) edge = s.colour();
        }
        if (edge != null && filled >= 3) {
            ink.to(bar, inside(side, filled - 1)).glyph(bar, bar.glyph("fill", edge + "/edge", in));
        }
        for (int t : ticks(segments, INNER)) {
            ink.to(bar, inside(side, t)).glyph(bar, bar.glyph("tick", t < covered ? "on" : "off", in));
        }
    }

    /** Absolute x of the bar's inside pixel i, counted from the icon side. */
    private static int inside(Side side, int i) {
        return side == Side.LEFT ? LEFT_FRAME + 1 + i : RIGHT_FRAME + INNER - i;
    }

    /** Pixels [from, to) of the inside in pieces of 32, 16 .. 1 px. */
    private void pieces(Ink ink, String sheet, String colour, int ascent, Side side, int from, int to) {
        int x = side == Side.LEFT ? inside(side, from) : inside(side, to - 1);
        int left = to - from;
        for (int p : FILL_PIECES) {
            while (left >= p) {
                ink.to(bar, x).glyph(bar, bar.glyph(sheet, colour + "/" + p, ascent));
                x += p;
                left -= p;
            }
        }
    }

    /** Where the ticks between `segments` equal parts of an inside `size` px wide go (none for one part). */
    static List<Integer> ticks(int segments, int size) {
        List<Integer> out = new ArrayList<>();
        for (int j = 1; j < segments; j++) out.add((int) Math.round(j * (double) size / segments));
        return out;
    }

    /** Parts of `per` (a heart = 2 health) in a bar of max, doubled until there are at most 12. */
    static int segments(double max, int per) {
        int n = (int) Math.ceil(max / per - 1e-9);
        while (n > 12) {
            per *= 2;
            n = (int) Math.ceil(max / per - 1e-9);
        }
        return Math.max(1, n);
    }

    /** A number character of the digits sheet and its colour (null = white). */
    record Digit(char c, TextColor color) {}

    static List<Digit> digits(String s, TextColor color) {
        List<Digit> out = new ArrayList<>();
        for (char c : s.toCharArray()) out.add(new Digit(c, color));
        return out;
    }

    /** Width of outlined digits drawn DIGIT_PITCH apart (outlines overlap by one pixel). */
    static int numbersWidth(int count) {
        return count == 0 ? 0 : count * DIGIT_PITCH + 1;
    }

    /** The number beside a bar: right-aligned against the icon on the left half, left-aligned on the right half. */
    private void number(Ink ink, Side side, int row, String text, TextColor color) {
        int x = side == Side.LEFT ? LEFT_NUMBER_END - numbersWidth(text.length()) : RIGHT_NUMBER;
        for (Digit d : digits(text, color)) {
            ink.to(bar, x).glyph(bar, bar.glyph("digits", String.valueOf(d.c()), action(row - 1)), d.color());
            x += DIGIT_PITCH;
        }
    }

    /** Pixels of a bar of `size` filled for value / max: at least 1 for anything above 0, never past full. */
    static int fill(double value, double max, int size) {
        if (value <= 0 || max <= 0) return 0;
        return (int) Math.max(1, Math.min(size, Math.round(value / max * size)));
    }

    /** Health as shown: whole points rounded up, as the game counts its half hearts (alive is never 0). */
    static String healthText(double hp) {
        if (hp <= 0) return "0";
        return String.valueOf((int) Math.ceil(hp - 1e-6));
    }

    // ------------------------------------------------------------------------------------------
    // Sidebar panel (the first boss bar's title)
    // ------------------------------------------------------------------------------------------

    /** A panel line: icon (or null), the text (or the label) and the value against the right edge (or null). */
    private record Line(String icon, List<HudText.Cell> text, List<HudText.Cell> value, boolean divider) {}

    /**
     * The panel with the server mark and its rows, its right edge {@code right} px right of the screen's middle,
     * or null when there is nothing to show. Zero net advance.
     */
    public Component panel(List<Row> entries, int right) {
        HudFont measure = rows.getFirst();
        int textMax = settings.sidebarMaxWidth() - TEXT_X - PAD;
        List<Line> lines = new ArrayList<>();
        for (Row r : entries) {
            if (lines.size() >= settings.sidebarRows()) break;
            List<HudText.Cell> cells = HudText.onPanel(HudText.supported(measure, HudText.cells(r.text())));
            List<List<HudText.Cell>> parts = HudText.split(cells);
            if (parts != null && !parts.get(1).isEmpty()
                    && HudText.width(measure, parts.get(0)) + VALUE_GAP + HudText.width(measure, parts.get(1)) <= textMax) {
                lines.add(new Line(r.icon(), parts.get(0), parts.get(1), r.divider() && !lines.isEmpty()));
                continue;
            }
            List<List<HudText.Cell>> wrapped = HudText.wrap(measure, cells, textMax, Math.min(2, settings.sidebarRows() - lines.size()));
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(i == 0 ? r.icon() : null, wrapped.get(i), null, i == 0 && r.divider() && !lines.isEmpty()));
            }
        }
        if (lines.isEmpty()) return null;
        int widest = 0;
        for (Line l : lines) {
            int w = HudText.width(measure, l.text());
            if (l.value() != null) w += VALUE_GAP + HudText.width(measure, l.value());
            widest = Math.max(widest, w);
        }
        int markWidth = sheets.sheet("panel_head").width("mark");
        int width = Math.max(Math.max(MIN_WIDTH, MARK_X + markWidth + PAD), Math.min(settings.sidebarMaxWidth(), TEXT_X + widest + PAD));
        int x = right - width, y = settings.sidebarY();

        Ink ink = new Ink(0);
        strip(ink, "panel_head", boss(y), x, width);
        for (int i = 0; i < lines.size(); i++) strip(ink, "panel_row", boss(rowTop(i)), x, width);
        strip(ink, "panel_foot", boss(rowTop(lines.size())), x, width);
        ink.to(panel, x + MARK_X).glyph(panel, panel.glyph("panel_head", "accent", boss(y)));
        ink.to(panel, x + MARK_X).glyph(panel, panel.glyph("panel_head", "mark", boss(y)));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).divider()) segments(ink, "panel_div", boss(rowTop(i) - 2), x + PAD, width - 2 * PAD);
        }
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.icon() != null && sheets.sheet("icons").has(l.icon())) {
                ink.to(panel, x + PAD).glyph(panel, panel.glyph("icons", l.icon(), boss(rowTop(i))));
            }
            HudFont f = rows.get(i);
            ink.to(f, x + TEXT_X).text(f, l.text());
            if (l.value() != null) ink.to(f, x + width - PAD - HudText.width(f, l.value())).text(f, l.value());
        }
        ink.to(panel, 0);
        return ink.build();
    }

    /** One horizontal strip of the panel: left cap, fills, right cap (with the shadow column). */
    private void strip(Ink ink, String sheet, int ascent, int x, int width) {
        ink.to(panel, x).glyph(panel, panel.glyph(sheet, "l", ascent));
        segments(ink, sheet, ascent, x + CAP, width - 2 * CAP);
        ink.to(panel, x + width - CAP).glyph(panel, panel.glyph(sheet, "r", ascent));
    }

    /** `length` px of a strip sheet's fill pieces from x on. */
    private void segments(Ink ink, String sheet, int ascent, int x, int length) {
        int px = x, left = length;
        for (int s : SEGMENTS) {
            while (left >= s) {
                ink.to(panel, px).glyph(panel, panel.glyph(sheet, String.valueOf(s), ascent));
                px += s;
                left -= s;
            }
        }
    }
}
