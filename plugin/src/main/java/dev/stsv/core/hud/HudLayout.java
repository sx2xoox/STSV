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
 *       Hearts, hunger and armor sit at fixed distances from the bottom (hearts 39, armor 49), so the bars land
 *       exactly where the game drew them at any GUI scale.</li>
 *   <li>boss bar title (the first boss bar): centred, pen line at y 3 from the top; a glyph top at y gets ascent
 *       10 - y. The sidebar panel lives there, x pixels right of the screen's middle (right of the 182 px wide
 *       boss bars), below the status effect icons.</li>
 * </ul>
 * A centred line starts at -(width / 2). Every part drawn here is measured exactly (HudSheets, HudText), so each
 * piece is placed by absolute x: the health bar does not move when a message of any length is shown on the same
 * action bar, and the panel is always left-aligned at its x.
 */
public final class HudLayout {

    /** Positions and sizes from config.yml (hud.sidebar, hud.health). */
    public record Settings(int sidebarX, int sidebarY, int sidebarMaxWidth, int sidebarRows, boolean armor) {

        public Settings {
            sidebarMaxWidth = Math.max(60, Math.min(240, sidebarMaxWidth));
            sidebarRows = Math.max(1, Math.min(12, sidebarRows));
            sidebarY = Math.max(0, Math.min(400, sidebarY));
            sidebarX = Math.max(-400, Math.min(400, sidebarX));
        }

        public static Settings defaults() {
            return new Settings(96, 54, 116, 8, true);
        }
    }

    /** What the health bars show. */
    public record Health(double health, double max, double absorption, int food, float saturation, int armor,
                         Tint tint, boolean hungry, boolean showFood) {}

    public enum Tint { NORMAL, POISON, WITHER, FROZEN }

    /** One sidebar entry: an icon of the icons sheet (or null), its text, and a divider line above it. */
    public record Row(String icon, Component text, boolean divider) {}

    // action bar rows (distance of a glyph's top from the bottom of the screen)
    static final int HEARTS = 39, ARMOR = 49;
    // the bars: icon 9 px, bar frame (71 or 41 px) with a 1 px rim, fill inside
    static final int BAR_LEFT = -91, FOOD_RIGHT = 91, FRAME = 71, FRAME_SHORT = 41, INNER = 69, INNER_SHORT = 39;
    static final int DIGIT_PITCH = 4, DIGIT_GAP = 2;
    // the panel
    static final int HEAD = 18, ROW = 11, FOOT = 5, CAP = 4, PAD = 5, ICON = 9, ICON_GAP = 3, TITLE_TOP = 4;
    static final int[] SEGMENTS = {128, 64, 32, 16, 8, 4, 2, 1};

    private static final TextColor GOLD = TextColor.color(0xFFD25A);
    private static final TextColor DIM = TextColor.color(0xC8C8C8);

    private final HudSheets sheets;
    private final Settings settings;
    private final HudFont bar = new HudFont("bar");
    private final HudFont panel = new HudFont("panel");
    private final HudFont line = new HudFont("line");
    private final HudFont head = new HudFont("head");
    private final List<HudFont> rows = new ArrayList<>();

    public HudLayout(HudSheets sheets, Settings settings) {
        this.sheets = sheets;
        this.settings = settings;
        for (int d : new int[]{HEARTS, ARMOR}) {
            bar.add(sheets.sheet("icons"), action(d));
            bar.add(sheets.sheet("bar"), action(d));
            bar.add(sheets.sheet("fill"), action(d - 1));
            bar.add(sheets.sheet("digits"), action(d - 1));
        }
        bar.add(sheets.sheet("stripe"), action(HEARTS - 1));
        bar.add(sheets.sheet("stripe"), action(HEARTS - 7));
        line.text(sheets, 7);
        line.fallback();
        int y = settings.sidebarY();
        panel.add(sheets.sheet("panel_head"), boss(y));
        head.text(sheets, boss(y + TITLE_TOP));
        for (int i = 0; i < settings.sidebarRows(); i++) {
            int top = rowTop(i);
            panel.add(sheets.sheet("panel_row"), boss(top));
            panel.add(sheets.sheet("panel_div"), boss(top));
            panel.add(sheets.sheet("icons"), boss(top + 1));
            panel.add(sheets.sheet("panel_foot"), boss(rowTop(i + 1)));
            HudFont f = new HudFont("row" + i);
            f.text(sheets, boss(top + 2));
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
        List<HudFont> all = new ArrayList<>(List.of(bar, panel, line, head));
        all.addAll(rows);
        for (HudFont f : all) out.put(f.path(), f.json());
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Action bar: a message (centred, normal line) and the health bars
    // ------------------------------------------------------------------------------------------

    /** Width a message takes on the action bar line (the game centres it). */
    int messageWidth(Component message) {
        return HudText.width(line, HudText.cells(message));
    }

    /**
     * The action bar: the message (null for none) in its usual place, then the bars (null for none), placed by
     * absolute x with zero net advance, so the game still centres the message by its own width.
     */
    public Component actionBar(Health h, Component message) {
        List<HudText.Cell> text = message == null ? List.of() : HudText.cells(message);
        int w = HudText.width(line, text);
        Ink ink = new Ink(-(w / 2));
        ink.text(line, text);
        int end = ink.pen();
        if (h != null) {
            healthBar(ink, h);
            if (h.showFood()) foodBar(ink, h);
            if (h.armor() > 0 && settings.armor()) armorBar(ink, h);
            ink.to(bar, end);
        }
        return ink.build();
    }

    private void healthBar(Ink ink, Health h) {
        int asc = action(HEARTS), in = action(HEARTS - 1);
        String heart = switch (h.tint()) {
            case POISON -> "heart_poison";
            case WITHER -> "heart_wither";
            case FROZEN -> "heart_frozen";
            default -> "heart";
        };
        String colour = switch (h.tint()) {
            case POISON -> "poison";
            case WITHER -> "wither";
            case FROZEN -> "frozen";
            default -> "red";
        };
        ink.to(bar, BAR_LEFT).glyph(bar, bar.glyph("icons", heart, asc));
        int frame = BAR_LEFT + ICON;
        ink.to(bar, frame).glyph(bar, bar.glyph("bar", "frame", asc));
        int k = fill(h.health(), h.max(), INNER);
        if (k > 0) ink.to(bar, frame + 1).glyph(bar, bar.glyph("fill", colour + "/" + k, in));
        int a = fill(h.absorption(), h.max(), INNER);
        if (a > 0) ink.to(bar, frame + 1).glyph(bar, bar.glyph("stripe", "abs/" + a, in));
        List<Digit> digits = new ArrayList<>(digits(healthText(h.health()), null));
        if (h.absorption() > 0) digits.addAll(digits("+" + healthText(h.absorption()), GOLD));
        digits.add(new Digit(' ', null));
        digits.add(new Digit('/', DIM));
        digits.add(new Digit(' ', null));
        digits.addAll(digits(String.valueOf((int) Math.round(h.max())), DIM));
        numbers(ink, digits, frame + 1, INNER, in);
    }

    private void foodBar(Ink ink, Health h) {
        int asc = action(HEARTS), in = action(HEARTS - 1);
        ink.to(bar, FOOD_RIGHT - ICON).glyph(bar, bar.glyph("icons", h.hungry() ? "food_hunger" : "food", asc));
        int frame = FOOD_RIGHT - ICON - FRAME;
        ink.to(bar, frame).glyph(bar, bar.glyph("bar", "frame", asc));
        int innerRight = frame + 1 + INNER;
        int k = fill(h.food(), 20, INNER);
        if (k > 0) ink.to(bar, innerRight - k).glyph(bar, bar.glyph("fill", (h.hungry() ? "hunger" : "food") + "/" + k, in));
        int s = fill(h.saturation(), 20, INNER);
        if (s > 0) ink.to(bar, innerRight - s).glyph(bar, bar.glyph("stripe", "sat/" + s, action(HEARTS - 7)));
        List<Digit> digits = new ArrayList<>(digits(String.valueOf(Math.max(0, h.food())), null));
        digits.add(new Digit(' ', null));
        digits.add(new Digit('/', DIM));
        digits.add(new Digit(' ', null));
        digits.addAll(digits("20", DIM));
        numbers(ink, digits, frame + 1, INNER, in);
    }

    private void armorBar(Ink ink, Health h) {
        int asc = action(ARMOR), in = action(ARMOR - 1);
        ink.to(bar, BAR_LEFT).glyph(bar, bar.glyph("icons", "armor", asc));
        int frame = BAR_LEFT + ICON;
        ink.to(bar, frame).glyph(bar, bar.glyph("bar", "frame_short", asc));
        int k = fill(h.armor(), 20, INNER_SHORT);
        if (k > 0) ink.to(bar, frame + 1).glyph(bar, bar.glyph("fill", "armor/" + k, in));
        numbers(ink, digits(String.valueOf(h.armor()), null), frame + 1, INNER_SHORT, in);
    }

    /** A number character of the digits sheet (' ' = a small gap) and its colour (null = white). */
    record Digit(char c, TextColor color) {}

    static List<Digit> digits(String s, TextColor color) {
        List<Digit> out = new ArrayList<>();
        for (char c : s.toCharArray()) out.add(new Digit(c, color));
        return out;
    }

    /** Width of outlined digits drawn DIGIT_PITCH apart (outlines overlap by one pixel). */
    static int numbersWidth(List<Digit> digits) {
        int w = 0;
        for (Digit d : digits) w += d.c() == ' ' ? DIGIT_GAP : DIGIT_PITCH;
        return w == 0 ? 0 : w + 1;
    }

    private void numbers(Ink ink, List<Digit> digits, int left, int width, int ascent) {
        int x = left + (width - numbersWidth(digits)) / 2;
        for (Digit d : digits) {
            if (d.c() == ' ') {
                x += DIGIT_GAP;
                continue;
            }
            ink.to(bar, x).glyph(bar, bar.glyph("digits", String.valueOf(d.c()), ascent), d.color());
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

    /** The panel with its title and rows, or null when there is nothing to show. Zero net advance. */
    public Component panel(Component title, List<Row> entries) {
        int textMax = settings.sidebarMaxWidth() - PAD - ICON - ICON_GAP - PAD;
        record Line(String icon, List<HudText.Cell> text, boolean divider) {}
        List<Line> lines = new ArrayList<>();
        for (Row r : entries) {
            if (lines.size() >= settings.sidebarRows()) break;
            int font = lines.size();
            List<HudText.Cell> cells = HudText.onPanel(HudText.supported(rows.get(font), HudText.cells(r.text())));
            List<List<HudText.Cell>> wrapped = HudText.wrap(rows.get(font), cells, textMax,
                    Math.min(2, settings.sidebarRows() - lines.size()));
            for (int i = 0; i < wrapped.size(); i++) lines.add(new Line(i == 0 ? r.icon() : null, wrapped.get(i), i == 0 && r.divider()));
        }
        if (lines.isEmpty()) return null;
        List<HudText.Cell> titleCells = title == null ? List.of() : HudText.onPanel(HudText.supported(head, HudText.cells(title)));
        int widest = HudText.width(head, titleCells) + 2;
        for (int i = 0; i < lines.size(); i++) {
            widest = Math.max(widest, HudText.width(rows.get(i), lines.get(i).text()) + ICON + ICON_GAP);
        }
        int width = Math.max(48, Math.min(settings.sidebarMaxWidth(), PAD + widest + PAD));
        int x = settings.sidebarX(), y = settings.sidebarY();

        Ink ink = new Ink(0);
        strip(ink, "panel_head", boss(y), x, width);
        ink.to(panel, x + CAP).glyph(panel, panel.glyph("panel_head", "gold", boss(y)));
        for (int i = 0; i < lines.size(); i++) {
            strip(ink, lines.get(i).divider() && i > 0 ? "panel_div" : "panel_row", boss(rowTop(i)), x, width);
        }
        strip(ink, "panel_foot", boss(rowTop(lines.size())), x, width);
        ink.to(panel, x + PAD + 1).text(head, titleCells);
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.icon() != null && sheets.sheet("icons").has(l.icon())) {
                ink.to(panel, x + PAD).glyph(panel, panel.glyph("icons", l.icon(), boss(rowTop(i) + 1)));
            }
            ink.to(panel, x + PAD + ICON + ICON_GAP).text(rows.get(i), l.text());
        }
        ink.to(panel, 0);
        return ink.build();
    }

    /** One horizontal strip of the panel: left edge, fills, right edge. */
    private void strip(Ink ink, String sheet, int ascent, int x, int width) {
        ink.to(panel, x).glyph(panel, panel.glyph(sheet, "l", ascent));
        int px = x + CAP, left = width - 2 * CAP;
        for (int s : SEGMENTS) {
            while (left >= s) {
                ink.to(panel, px).glyph(panel, panel.glyph(sheet, String.valueOf(s), ascent));
                px += s;
                left -= s;
            }
        }
        ink.to(panel, x + width - CAP).glyph(panel, panel.glyph(sheet, "r", ascent));
    }
}
