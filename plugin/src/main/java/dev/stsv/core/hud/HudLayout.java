package dev.stsv.core.hud;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where the resource-pack HUD draws everything, in the game's GUI pixels, and the fonts that put it there.
 *
 * The game places these lines itself, relative to the screen, the same at every GUI scale and window shape:
 * <ul>
 *   <li>action bar: centred on the screen's middle, pen line at (guiHeight - 72); a glyph's top is at pen line
 *       + 7 - ascent, so a glyph whose top should be D pixels above the bottom of the screen gets ascent D - 65.
 *       The bars take the rows of the game's hearts (top 39 above the bottom) and armor (49), across the
 *       hotbar's width and a little past it (frames and icons within x -94 .. 94, the hotbar is -91 .. 91, the
 *       numbers just past them, as wide on both sides), and leave the middle (|x| &lt; 14) to the experience
 *       level the game draws there.</li>
 *   <li>boss bar title (the first boss bar): centred, pen line at y 3 from the top; a glyph top at y gets ascent
 *       10 - y. The sidebar panel lives there, its right edge {@code right} pixels right of the screen's
 *       middle, its top {@code top} pixels below the top of the screen. So does the overlay, with zero net
 *       advance after it.</li>
 * </ul>
 * The board ({@link Board}: a header and up to 6 rows of text, a fishing tournament's ranking) has no background,
 * like the overlay, and sits in the bottom right corner of the screen, in Galmuri9 (the normal panel's letters). It
 * goes on the action bar, the only line the game anchors to the bottom of the screen: the boss bar title is anchored
 * to the top and the server is never told the screen's height, so a board there (as before) sat at a fixed distance
 * from the top, at the bottom of a GUI 240 high only. On the action bar its lowest row is hud.board.margin (2) px
 * above the bottom at every GUI scale and window size. A glyph can rise at most its own height above the action
 * bar's line (65 px up; the game refuses an ascent over a font's height), so the rows above it (the top one at the
 * default margin) draw from copies of their sheets with see-through rows under the cells (HudSheets.pad): with 11 px
 * rows and no panel that is a few pixels, not the 60 a 120 px panel would need. Each row has its own font at its own
 * height. Its right edge is the sidebar's (the player's own place, the screen's edge as for the overlay) or
 * hud.board.right; it is never wider than reaches x 106, past the hotbar (91) and the bars' numbers (105) beside the
 * same bottom rows, so it cannot cover them; chat is on the left (its default 320 px width ends about x 118 on a GUI
 * 427 wide, so only a board wider than about 94 px there can reach under the chat's background while the chat shows).
 * Nothing in the game anchors a server's text to the right edge of the screen, and the server is never told the
 * window size or GUI scale, so the board is placed from the middle: by default where the right edge of a 16:9
 * window at its automatic GUI scale is (720p, 1440p and 4K all give a GUI width of 427: 213 px right of the
 * middle), and per player against the edge they put their sidebar at (HudEdit).
 *
 * The panel comes in three sizes ({@link Size}): Galmuri7 (small), Galmuri9 (normal) and Galmuri7 drawn twice as
 * big (large), every pixel a whole number of GUI pixels, so all three are as crisp as the game's own text. A
 * glyph's height on screen is fixed by its font, so every row a panel can be drawn at needs its own font: each
 * size has a column of row fonts {@code ROW} px apart (its "slots") from a top a little under the screen's top
 * to a little below the default place, and a panel of n rows takes n consecutive slots. It moves sideways to any
 * pixel and up and down a row at a time.
 *
 * Every font that draws text (the action bar's line, each panel / overlay row, each board row) holds the whole
 * Hangul sheet of its size: the 2,350 syllables of KS X 1001 and every one the repository's texts use, so a
 * nickname, an item name or any other text a player made shows as written wherever the HUD draws it. A syllable
 * outside them (KS X 1001 lacks some of the 11,172, e.g. 똠) is drawn as '?', as wide as '?' (HudText.supported),
 * so the layout stays exact. Each such font costs the client its whole sheet (Galmuri9 0.77 MB, Galmuri7 0.46 MB,
 * see HudFont); about 38 of them: see HudPack.clientMemory and README "HUD".
 *
 * A centred line starts at -(width / 2). Every part drawn here is measured exactly (HudSheets, HudText), so each
 * piece is placed by absolute x: the bars do not move when a message of any length is shown on the same action
 * bar, and the panel's right edge stays put whatever its text.
 */
public final class HudLayout {

    /**
     * Positions and sizes from config.yml (hud.sidebar, hud.health, hud.board). board: whether the board's fonts are
     * made at all; boardMargin: px between the bottom of the screen and the board's lowest row (0 .. 10); boardRight:
     * its right edge (px right of the middle) for players who never moved their sidebar; boardMaxWidth: its widest.
     */
    public record Settings(int sidebarRight, int sidebarY, int sidebarMaxWidth, int sidebarRows, boolean armor,
                           boolean board, int boardMargin, int boardRight, int boardMaxWidth) {

        public Settings {
            sidebarMaxWidth = Math.max(80, Math.min(260, sidebarMaxWidth));
            sidebarRows = Math.max(1, Math.min(12, sidebarRows));
            sidebarY = Math.max(MIN_TOP, Math.min(300, sidebarY));
            sidebarRight = clampPanelRight(sidebarRight);
            boardMargin = Math.max(0, Math.min(BOARD_MAX_MARGIN, boardMargin));
            boardRight = Math.max(BOARD_CLEAR_X + MIN_WIDTH, Math.min(MAX_RIGHT, boardRight));
            boardMaxWidth = Math.max(MIN_WIDTH, Math.min(260, boardMaxWidth));
        }

        /** Without the board (its defaults, off). */
        public Settings(int sidebarRight, int sidebarY, int sidebarMaxWidth, int sidebarRows, boolean armor) {
            this(sidebarRight, sidebarY, sidebarMaxWidth, sidebarRows, armor, false, BOARD_MARGIN, DEFAULT_RIGHT, 132);
        }

        public static Settings defaults() {
            return new Settings(DEFAULT_RIGHT, 54, 132, 7, true, true, BOARD_MARGIN, DEFAULT_RIGHT, 132);
        }
    }

    public enum Tint { NORMAL, POISON, WITHER, FROZEN }

    /**
     * The bars' motion (HudTrail), in pixels of a bar's inside: the light trail of the part just lost past the
     * health, the right bar (hunger or the mount) and the armor, and the glow of the health just gained below it.
     */
    public record Anim(int health, int glow, int right, int armor) {
        public static final Anim NONE = new Anim(0, 0, 0, 0);
    }

    /** The health of what the player rides (a living mount: it takes the hunger bar's place, as in the game). */
    public record Mount(double health, double max) {}

    /** The air left, shown under water and while it refills (as the game shows its bubbles). */
    public record Air(int remaining, int max) {}

    /** What the bars show, and how they are moving (anim). */
    public record Health(double health, double max, double absorption, int food, float saturation, int armor,
                         Tint tint, boolean hungry, Mount mount, Air air, Anim anim) {

        public Health {
            if (anim == null) anim = Anim.NONE;
        }

        public Health(double health, double max, double absorption, int food, float saturation, int armor, Tint tint,
                      boolean hungry) {
            this(health, max, absorption, food, saturation, armor, tint, hungry, null, null, Anim.NONE);
        }

        public Health withMount(Mount m) {
            return new Health(health, max, absorption, food, saturation, armor, tint, hungry, m, air, anim);
        }

        public Health withAir(Air a) {
            return new Health(health, max, absorption, food, saturation, armor, tint, hungry, mount, a, anim);
        }

        public Health withAnim(Anim a) {
            return new Health(health, max, absorption, food, saturation, armor, tint, hungry, mount, air, a);
        }

        /** As the moment after health + absorption went from `from` to what it is: the whole lost part trailing, or the gained part glowing. */
        public Health justChanged(double from) {
            int now = healthPx(this), before = fill(from, Math.max(max, total()), INNER);
            return withAnim(new Anim(Math.max(0, before - now), Math.max(0, now - before), anim.right(), anim.armor()));
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
    // a bar: [number][icon][frame] on the left half, mirrored on the right; the fill grows away from the icon.
    // The frames start at the edge of the level's room (|x| < 14) and, with the icons, reach a little past the
    // hotbar's ends (x -94 .. 94, the hotbar -91 .. 91); the numbers sit just past them (two digits to 105, on
    // screen at GUI 4 of a 1280 x 720 window, 160 px each side; above the off-hand slot, which is under row 23).
    // Parts are a pitch of (INNER + 1) / n px: the fill and a one-pixel tick, the last part ending at the frame.
    // INNER + 1 = 70, so the usual 10 hearts / drumsticks / armor points / bubbles are 7 px each, 14 (more
    // hearts) 5 px, 7, 5 and 2 just as even; other counts differ by a pixel at most (segments).
    static final int INNER = 69, FRAME_W = INNER + 2, ICON = 9, MIDDLE = 14;
    static final int LEFT_FRAME = -(MIDDLE - 1) - FRAME_W, LEFT_ICON = LEFT_FRAME - 1 - ICON, LEFT_NUMBER_END = LEFT_ICON - 1;
    static final int RIGHT_FRAME = MIDDLE, RIGHT_ICON = RIGHT_FRAME + FRAME_W + 1, RIGHT_NUMBER = RIGHT_ICON + ICON + 1;
    static final int DIGIT_PITCH = 4, MAX_SEGMENTS = 14;
    static final int[] FILL_PIECES = {32, 16, 8, 4, 2, 1};
    // the panel at its normal size (HEAD: title strip down to the first row; ROW: row pitch; FOOT: bevel,
    // outline, shadow); every size scales its own cut of these (Style) by its scale
    static final int HEAD = 19, ROW = 13, FOOT = 3, CAP = 4, PAD = 5, ICON_GAP = 3, TEXT_X = PAD + ICON + ICON_GAP;
    static final int VALUE_GAP = 8, MIN_WIDTH = 64, MARK_X = 5;
    static final int[] SEGMENTS = {16, 8, 4, 2, 1};
    /** The highest a panel's top may go, and how far sideways its right edge may go (GUI px from the middle). */
    static final int MIN_TOP = 2, MAX_RIGHT = 960, MIN_RIGHT = -900;
    /** The corner brackets of a panel being moved: cells of 8 x 12 (art 8 x 8), 3 px out from the panel. */
    static final int EDIT_OUT = 3, EDIT_CELL_H = 12;

    /**
     * A cut of the panel art (tools/assetgen/hud.py STYLES): strip heights, the gold line's and the server mark's
     * rows in the title, where a divider goes above a row, how far the letters sit below a row's top.
     */
    enum Style {
        M("_m", 19, 13, 3, 15, 4, -2, 0),
        S("_s", 16, 11, 3, 13, 3, -1, 1);

        final String suffix;
        final int head, row, foot, gold, mark, divider, textDy;

        Style(String suffix, int head, int row, int foot, int gold, int mark, int divider, int textDy) {
            this.suffix = suffix;
            this.head = head;
            this.row = row;
            this.foot = foot;
            this.gold = gold;
            this.mark = mark;
            this.divider = divider;
            this.textDy = textDy;
        }
    }

    /**
     * The panel's sizes: its letters (Galmuri7 or Galmuri9), its art and its scale.
     * maxRows caps the rows of a size (null: hud.sidebar.max-rows); below: slots under the default place for a
     * panel of maxRows rows (a shorter panel goes as many rows lower as it is shorter).
     */
    public enum Size {
        SMALL(1, "작게", "s", Style.S, "text7", HANGUL7, 1, 7, null, 1),
        NORMAL(2, "보통", "m", Style.M, "text", HANGUL, 1, 9, null, 0),
        LARGE(3, "크게", "l", Style.S, "text7", HANGUL7, 2, 14, 6, 0);

        /** What is stored for a player (never reorder these). */
        public final int code;
        public final String label;
        final String id;
        final Style style;
        final String textSheet, hangulSheet;
        final int scale, letterHeight;
        final Integer maxRows;
        final int below;

        Size(int code, String label, String id, Style style, String textSheet, String hangulSheet, int scale,
             int letterHeight, Integer maxRows, int below) {
            this.code = code;
            this.label = label;
            this.id = id;
            this.style = style;
            this.textSheet = textSheet;
            this.hangulSheet = hangulSheet;
            this.scale = scale;
            this.letterHeight = letterHeight;
            this.maxRows = maxRows;
            this.below = below;
        }

        public static Size of(int code) {
            for (Size s : values()) if (s.code == code) return s;
            return NORMAL;
        }

        /** One step bigger (dir > 0) or smaller (dir < 0), staying at the ends. */
        public Size step(int dir) {
            int i = ordinal() + Integer.signum(dir);
            return values()[Math.max(0, Math.min(values().length - 1, i))];
        }

        int head() {
            return style.head * scale;
        }

        int row() {
            return style.row * scale;
        }

        int foot() {
            return style.foot * scale;
        }

        /** A panel length (padding, icon, gaps) at this size. */
        int px(int normal) {
            return normal * scale;
        }
    }

    /** Where a player's panel is: its size, its right edge (px right of the screen's middle) and its top. */
    public record Placement(Size size, int right, int top) {}

    /** The Hangul sheets of Galmuri9 and Galmuri7: every font that draws text holds the whole one of its size. */
    static final String HANGUL = "hangul", HANGUL7 = "hangul7";

    static final TextColor GOLD = TextColor.color(0xFFD25A), LOW = TextColor.color(0xFF7A70),
            POISON = TextColor.color(0xC4E67A), WITHER = TextColor.color(0xB4AAB8), FROZEN = TextColor.color(0xB4E6FF),
            HUNGER = TextColor.color(0xC8D278), AIR = TextColor.color(0xB4DCFF);

    private final HudSheets sheets;
    private final Settings settings;
    private final HudFont bar = new HudFont("bar");
    private final HudFont line = new HudFont("line");
    private final Map<Size, Column> columns = new java.util.EnumMap<>(Size.class);
    /** The board's letter font per slot from the bottom (slot 0) up (empty: hud.board off). */
    private final List<HudFont> boardRows;

    /**
     * One size's column of slots: the top of the highest place (top0; the rest are ROW apart), the number of slots,
     * the most rows a panel of this size has, its picture font and a text font per slot.
     */
    private record Column(int top0, int slots, int maxRows, HudFont pictures, List<HudFont> rows) {}

    /** sheets: the bundled ones ({@link HudSheets#load}). */
    public HudLayout(HudSheets sheets, Settings settings) {
        this.settings = settings;
        if (settings.board()) sheets = boardSheets(sheets, settings.boardMargin());
        this.sheets = sheets;
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
        for (Size size : Size.values()) columns.put(size, column(size));
        if (settings.board()) {
            // the action bar's line is 65 px above the bottom: rows over it need cells as tall as they rise (boardSheets)
            List<HudFont> rows = new ArrayList<>();
            for (int s = 0; s < BOARD_SLOTS; s++) {
                int letters = action(boardTop(settings.boardMargin(), s));
                HudFont f = new HudFont("board_" + s);
                f.text(sheets, padded(BOARD_TEXT, boardPad(letters, sheets.sheet(BOARD_TEXT).height())),
                        padded(BOARD_HANGUL, boardPad(letters, sheets.sheet(BOARD_HANGUL).height())), letters, 1);
                rows.add(f);
            }
            boardRows = List.copyOf(rows);
        } else {
            boardRows = List.of();
        }
    }

    /** A sheet's name with `pad` see-through rows under its cells (itself for 0). */
    private static String padded(String sheet, int pad) {
        return pad <= 0 ? sheet : sheet + "_p" + pad;
    }

    /** The sheets the board draws from: for the rows whose glyphs rise above the action bar's line, its letters with see-through rows under them. */
    static HudSheets boardSheets(HudSheets sheets, int margin) {
        for (int s = 0; s < BOARD_SLOTS; s++) {
            int letters = action(boardTop(margin, s));
            for (String l : new String[]{BOARD_TEXT, BOARD_HANGUL}) {
                sheets = padOnce(sheets, l, boardPad(letters, sheets.sheet(l).height()));
            }
        }
        return sheets;
    }

    private static HudSheets padOnce(HudSheets sheets, String base, int pad) {
        String name = padded(base, pad);
        return pad <= 0 || sheets.has(name) ? sheets : sheets.pad(base, name, pad);
    }

    private Column column(Size size) {
        int pitch = size.row(), k = size.scale;
        int y = Math.max(0, settings.sidebarY());
        int above = Math.max(0, (y - MIN_TOP) / pitch);
        int top0 = y - above * pitch;
        int maxRows = size.maxRows == null ? settings.sidebarRows() : Math.min(size.maxRows, settings.sidebarRows());
        int slots = maxRows + above + size.below;
        HudFont pictures = new HudFont("panel_" + size.id);
        String st = size.style.suffix;
        List<HudFont> rows = new ArrayList<>();
        for (int j = 0; j < slots; j++) {
            int head = top0 + j * pitch, row = head + size.head();
            pictures.add(sheets.sheet("panel_head" + st), boss(head), k);
            pictures.add(sheets.sheet("panel_mark"), boss(head + size.style.mark * k), k);
            pictures.add(sheets.sheet("panel_mark"), boss(head + size.style.gold * k), k);
            pictures.add(sheets.sheet("panel_edit"), editAscent(head - EDIT_OUT * k, k), k);
            pictures.add(sheets.sheet("panel_row" + st), boss(row), k);
            pictures.add(sheets.sheet("icons"), boss(row), k);
            if (size == Size.NORMAL && j < OVERLAY_ROWS && sheets.has("faces")) pictures.add(sheets.sheet("faces"), boss(row + FACE_DY), 1);   // the overlay's
            if (size == Size.NORMAL && j < OVERLAY_ROWS && sheets.has("bubble")) pictures.add(sheets.sheet("bubble"), boss(row + BUBBLE_DY), 1);
            if (j > 0) pictures.add(sheets.sheet("panel_div"), boss(row + size.style.divider * k), k);
            int foot = row + pitch;
            pictures.add(sheets.sheet("panel_foot" + st), boss(foot), k);
            pictures.add(sheets.sheet("panel_edit"), boss(foot - EDIT_OUT * k), k);
            HudFont f = new HudFont("row_" + size.id + j);
            f.text(sheets, size.textSheet, size.hangulSheet, boss(row + size.style.textDy * k), k);
            rows.add(f);
        }
        return new Column(top0, slots, maxRows, pictures, List.copyOf(rows));
    }

    /** A top bracket's ascent: 3 px over the panel, or as high as its cell allows (a bracket cell is 12 px tall). */
    private static int editAscent(int y, int k) {
        return Math.min(boss(y), EDIT_CELL_H * k);
    }

    public Settings settings() {
        return settings;
    }

    /** The sheets the fonts are made of (with the board's padded copies). */
    public HudSheets sheets() {
        return sheets;
    }

    /** Ascent of an action-bar glyph whose top is d pixels above the bottom of the screen. */
    static int action(int d) {
        return d - 65;
    }

    /** Ascent of a glyph in the first boss bar's title whose top is y pixels below the top of the screen. */
    static int boss(int y) {
        return 10 - y;
    }

    /** Every font file (pack path -> JSON). */
    public Map<String, JsonObject> fonts() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        List<HudFont> all = new ArrayList<>(List.of(bar, line));
        for (Column c : columns.values()) {
            all.add(c.pictures());
            all.addAll(c.rows());
        }
        all.addAll(boardRows);
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
        return actionBar(h, message, null);
    }

    /**
     * The action bar with the board ({@link #board}, null for none) too: the bars (or none), the message, the board,
     * each by absolute x, the net advance still the message's own.
     */
    public Component actionBar(Health h, Component message, Component board) {
        List<HudText.Cell> text = message == null ? List.of() : HudText.untab(HudText.cells(message));
        int w = HudText.width(line, text);
        Ink ink = new Ink(-(w / 2));
        ink.text(line, text);
        int end = ink.pen();
        if (h != null) {
            healthBar(ink, h);
            if (h.mount() != null) mountBar(ink, h.mount(), h.anim().right());
            else foodBar(ink, h);
            if ((h.armor() > 0 || h.anim().armor() > 0) && settings.armor()) armorBar(ink, h);
            if (h.air() != null) airBar(ink, h.air());
        }
        if (board != null) {
            ink.to(bar, 0);
            ink.raw(board);
        }
        if (ink.pen() != end) ink.to(bar, end);
        return ink.build();
    }

    /** A run of one fill colour over the inside pixels [from, to), counted from the bar's icon side. */
    record Span(String colour, int from, int to) {}

    private enum Side { LEFT, RIGHT }

    /** Pixels of the health bar filled by health and absorption together (what its trail and glow are measured from). */
    static int healthPx(Health h) {
        return Math.max(fill(h.health(), Math.max(h.max(), h.total()), INNER), fill(h.total(), Math.max(h.max(), h.total()), INNER));
    }

    /** Pixels of the right bar of the first row filled: the mount's health when riding, the hunger otherwise. */
    static int rightPx(Health h) {
        return h.mount() != null ? fill(h.mount().health(), Math.max(1, h.mount().max()), INNER) : fill(h.food(), 20, INNER);
    }

    /** Pixels of the armor bar filled. */
    static int armorPx(Health h) {
        return fill(h.armor(), Math.max(20, h.armor()), INNER);
    }

    /** A trail of `length` px past the fill (never past the inside). */
    private static Span trail(int filled, int length) {
        return new Span("lost", filled, Math.min(INNER, filled + length));
    }

    private void healthBar(Ink ink, Health h) {
        double scale = Math.max(h.max(), h.total());
        int kh = fill(h.health(), scale, INNER), kt = healthPx(h);
        String colour = switch (h.tint()) {
            case POISON -> "poison";
            case WITHER -> "wither";
            case FROZEN -> "frozen";
            default -> "red";
        };
        List<Span> spans = new ArrayList<>(List.of(new Span(colour, 0, kh), new Span("abs", kh, kt)));
        String rim = "frame";
        if (h.anim().health() > 0 && kt < INNER) {
            spans.add(trail(kt, h.anim().health()));
            rim = "frame_hit";
        } else if (h.anim().glow() > 0) {
            spans.add(new Span("gain", Math.max(0, kt - h.anim().glow()), kt));
            rim = "frame_heal";
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
        int k = rightPx(h);
        drawBar(ink, Side.RIGHT, ROW1, h.hungry() ? "food_hunger" : "food", "frame",
                List.of(new Span(h.hungry() ? "hunger" : "food", 0, k), trail(k, h.anim().right())), k, 10);
        int s = fill(Math.min(h.saturation(), Math.max(0, h.food())), 20, INNER);
        if (s > 0) pieces(ink, "line", "sat", action(ROW1 - 7), Side.RIGHT, 0, s);
        number(ink, Side.RIGHT, ROW1, String.valueOf(Math.max(0, h.food())), h.hungry() ? HUNGER : null);
    }

    private void mountBar(Ink ink, Mount m, int trail) {
        double scale = Math.max(1, m.max());
        int k = fill(m.health(), scale, INNER);
        drawBar(ink, Side.RIGHT, ROW1, "mount", "frame", List.of(new Span("mount", 0, k), trail(k, trail)), k, segments(scale, 2));
        number(ink, Side.RIGHT, ROW1, healthText(m.health()), m.health() <= scale / 4 ? LOW : null);
    }

    private void armorBar(Ink ink, Health h) {
        int scale = Math.max(20, h.armor());
        int k = armorPx(h);
        drawBar(ink, Side.LEFT, ROW2, "armor", "frame", List.of(new Span("armor", 0, k), trail(k, h.anim().armor())), k, segments(scale, 2));
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

    /**
     * Where the ticks between `segments` equal parts of an inside `size` px wide go (none for one part): every part
     * is its fill and the tick after it, the last one ends at the frame, so (size + 1) / segments px apart.
     */
    static List<Integer> ticks(int segments, int size) {
        List<Integer> out = new ArrayList<>();
        for (int j = 1; j < segments; j++) out.add((int) Math.round(j * (size + 1.0) / segments) - 1);
        return out;
    }

    /**
     * Parts of `per` (a heart = 2 health) in a bar of max, doubled until there are at most MAX_SEGMENTS: up to 14
     * hearts a part each (14 of 5 px, as even as 10; 11 to 13 a pixel apart at most), more two (or four) a part
     * (40 health: 10 again).
     */
    static int segments(double max, int per) {
        int n = (int) Math.ceil(max / per - 1e-9);
        while (n > MAX_SEGMENTS) {
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

    /**
     * Pixels of a bar of `size` filled for value / max: at least 1 for anything above 0, never past full. Measured
     * like the ticks ({@link #ticks}): a whole number of parts fills them up to the tick after the last one.
     */
    static int fill(double value, double max, int size) {
        if (value <= 0 || max <= 0) return 0;
        return (int) Math.max(1, Math.min(size, Math.round(value / max * (size + 1)) - 1));
    }

    /** Health as shown: whole points rounded up, as the game counts its half hearts (alive is never 0). */
    static String healthText(double hp) {
        if (hp <= 0) return "0";
        return String.valueOf((int) Math.ceil(hp - 1e-6));
    }


    // ------------------------------------------------------------------------------------------
    // Sidebar panel (the first boss bar's title)
    // ------------------------------------------------------------------------------------------

    /** Where the panel goes for players who never moved it: normal size, hud.sidebar.right and hud.sidebar.y. */
    public Placement defaults() {
        return new Placement(Size.NORMAL, settings.sidebarRight(), settings.sidebarY());
    }

    /** The most rows a panel of this size shows. */
    public int maxRows(Size size) {
        return columns.get(size).maxRows();
    }

    /** The highest top of this size. */
    public int minTop(Size size) {
        return columns.get(size).top0();
    }

    /** The lowest top of this size for a panel of `rows` rows. */
    public int maxTop(Size size, int rows) {
        Column c = columns.get(size);
        return c.top0() + (c.slots() - Math.max(1, Math.min(rows, c.maxRows()))) * size.row();
    }

    /**
     * The placement as a panel of `rows` rows is drawn: the right edge within MIN_RIGHT .. MAX_RIGHT, the top on its
     * size's rows (the nearest one) between the highest and the lowest it can take.
     */
    public Placement fit(Placement p, int rows) {
        Size size = p.size() == null ? Size.NORMAL : p.size();
        Column c = columns.get(size);
        int pitch = size.row();
        int k = Math.round((p.top() - c.top0()) / (float) pitch);
        k = Math.max(0, Math.min((maxTop(size, rows) - c.top0()) / pitch, k));
        return new Placement(size, clampPanelRight(p.right()), c.top0() + k * pitch);
    }

    static int clampPanelRight(int right) {
        return Math.max(MIN_RIGHT, Math.min(MAX_RIGHT, right));
    }

    /** A panel line: icon (or null), the text (or the label) and the value against the right edge (or null). */
    private record Line(String icon, List<HudText.Cell> text, List<HudText.Cell> value, boolean divider) {}

    /** The panel at the default place (normal size, its right edge {@code right} px right of the middle). */
    public Component panel(List<Row> entries, int right) {
        return panel(entries, new Placement(Size.NORMAL, right, settings.sidebarY()), false);
    }

    /** How many lines these rows take at a size (wrapped rows take two). */
    public int lines(List<Row> entries, Size size) {
        return lay(entries, size).size();
    }

    private List<Line> lay(List<Row> entries, Size size) {
        HudFont measure = columns.get(size).rows().getFirst();
        int maxRows = maxRows(size);
        int textMax = maxWidth(size) - size.px(TEXT_X) - size.px(PAD);
        int valueGap = size.px(VALUE_GAP);
        List<Line> lines = new ArrayList<>();
        for (Row r : entries) {
            if (lines.size() >= maxRows) break;
            List<HudText.Cell> cells = HudText.onPanel(HudText.supported(measure, HudText.cells(r.text())));
            List<List<HudText.Cell>> parts = HudText.split(cells);
            if (parts != null && !parts.get(1).isEmpty()
                    && HudText.width(measure, parts.get(0)) + valueGap + HudText.width(measure, parts.get(1)) <= textMax) {
                lines.add(new Line(r.icon(), parts.get(0), parts.get(1), r.divider() && !lines.isEmpty()));
                continue;
            }
            List<List<HudText.Cell>> wrapped = HudText.wrap(measure, cells, textMax, Math.min(2, maxRows - lines.size()));
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new Line(i == 0 ? r.icon() : null, wrapped.get(i), null, i == 0 && r.divider() && !lines.isEmpty()));
            }
        }
        return lines;
    }

    /** hud.sidebar.max-width is for the normal size; the others keep the same room for their letters. */
    int maxWidth(Size size) {
        return Math.round(settings.sidebarMaxWidth() * size.letterHeight / 9f);
    }

    /**
     * The panel with the server mark and its rows at a placement (fitted to its rows, see {@link #fit}), or null
     * when there is nothing to show. With edit, corner brackets mark it as being moved. Zero net advance.
     */
    /** How wide the panel of these rows is at a placement's size (0 when it shows nothing). */
    public int panelWidth(List<Row> entries, Placement place) {
        Size size = place.size() == null ? Size.NORMAL : place.size();
        List<Line> lines = lay(entries, size);
        return lines.isEmpty() ? 0 : width(lines, size);
    }

    private int width(List<Line> lines, Size size) {
        int k = size.scale;
        HudFont measure = columns.get(size).rows().getFirst();
        int widest = 0, valueGap = size.px(VALUE_GAP);
        for (Line l : lines) {
            int w = HudText.width(measure, l.text());
            if (l.value() != null) w += valueGap + HudText.width(measure, l.value());
            widest = Math.max(widest, w);
        }
        int markWidth = sheets.sheet("panel_mark").advanceAt(sheets.sheet("panel_mark").index("mark"), k) - 1;
        int pad = size.px(PAD), textX = size.px(TEXT_X);
        int width = Math.max(Math.max(size.px(MIN_WIDTH), size.px(MARK_X) + markWidth + pad), Math.min(maxWidth(size), textX + widest + pad));
        return (width + k - 1) / k * k;   // whole art pixels: every strip and divider is a whole number of pieces
    }

    public Component panel(List<Row> entries, Placement place, boolean edit) {
        Size size = place.size() == null ? Size.NORMAL : place.size();
        Column col = columns.get(size);
        List<Line> lines = lay(entries, size);
        if (lines.isEmpty()) return null;
        Placement at = fit(new Placement(size, place.right(), place.top()), lines.size());
        int k = size.scale, slot0 = (at.top() - col.top0()) / size.row();
        HudFont pic = col.pictures();
        int width = width(lines, size);
        int pad = size.px(PAD), textX = size.px(TEXT_X);
        int right = at.right(), x = right - width, top = at.top();
        String st = size.style.suffix;

        Ink ink = new Ink(0);
        strip(ink, pic, "panel_head" + st, boss(top), x, width, k);
        for (int i = 0; i < lines.size(); i++) strip(ink, pic, "panel_row" + st, boss(rowTop(size, col, slot0 + i)), x, width, k);
        int foot = rowTop(size, col, slot0 + lines.size());
        strip(ink, pic, "panel_foot" + st, boss(foot), x, width, k);
        ink.to(pic, x + size.px(MARK_X)).glyph(pic, pic.glyph("panel_mark", "accent", boss(top + size.style.gold * k), k));
        ink.to(pic, x + size.px(MARK_X)).glyph(pic, pic.glyph("panel_mark", "mark", boss(top + size.style.mark * k), k));
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).divider()) continue;
            int y = rowTop(size, col, slot0 + i) + size.style.divider * k;
            segments(ink, pic, "panel_div", boss(y), x + pad, width - 2 * pad, k);
        }
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            int row = rowTop(size, col, slot0 + i);
            if (l.icon() != null && sheets.sheet("icons").has(l.icon())) {
                ink.to(pic, x + pad).glyph(pic, pic.glyph("icons", l.icon(), boss(row), k));
            }
            HudFont f = col.rows().get(slot0 + i);
            ink.to(f, x + textX).text(f, l.text());
            if (l.value() != null) ink.to(f, x + width - pad - HudText.width(f, l.value())).text(f, l.value());
        }
        if (edit) {
            int up = editAscent(top - EDIT_OUT * k, k), down = boss(foot - EDIT_OUT * k);
            int left = x - EDIT_OUT * k, rightBracket = right - (8 - EDIT_OUT) * k;
            ink.to(pic, left).glyph(pic, pic.glyph("panel_edit", "tl", up, k));
            ink.to(pic, rightBracket).glyph(pic, pic.glyph("panel_edit", "tr", up, k));
            ink.to(pic, left).glyph(pic, pic.glyph("panel_edit", "bl", down, k));
            ink.to(pic, rightBracket).glyph(pic, pic.glyph("panel_edit", "br", down, k));
        }
        ink.to(pic, 0);
        return ink.build();
    }

    // ------------------------------------------------------------------------------------------
    // Board: rows of text against the bottom right, on the action bar (see the class comment)
    // ------------------------------------------------------------------------------------------

    /**
     * What the board shows: a header line over at most {@value #BOARD_SLOTS} - 1 rows (more are dropped), the first
     * row the highest, the last just above the bottom of the screen. A tab in the header or a row puts what follows
     * against the board's right edge and keeps it whole: what is before it ends in an ellipsis when the row is too
     * long (a name), as a row without a tab does. Component.empty() is an empty row.
     */
    public record Board(Component title, List<Row> rows) {

        /** One row: whose row it is (kept for the callers; the board draws no faces) and the text. */
        public record Row(UUID player, Component text) {
            public Row {
                if (text == null) text = Component.empty();
            }
        }

        public Board {
            if (title == null) title = Component.empty();
            List<Row> kept = new ArrayList<>();
            if (rows != null) for (Row r : rows) if (r != null && kept.size() < BOARD_SLOTS - 1) kept.add(r);
            rows = List.copyOf(kept);
        }

        /** The earlier form: a title, lines and an optional footer (null: none) as the last row. */
        public Board(Component title, List<Component> lines, Component footer) {
            this(title, legacy(lines, footer));
        }

        private static List<Row> legacy(List<Component> lines, Component footer) {
            List<Row> out = new ArrayList<>();
            if (lines != null) for (Component c : lines) out.add(new Row(null, c));
            if (footer != null) out.add(new Row(null, footer));
            return out;
        }
    }

    /**
     * The board's grid, in GUI px above the bottom of the screen: slots BOARD_PITCH apart from hud.board.margin up,
     * each the Galmuri9 letters' 10 px cell (BOARD_CELL: 9 px letters and a row for descenders) with its bottom on
     * the slot's foot, so the lowest letters' shadow is the margin's top pixel.
     */
    static final int BOARD_SLOTS = 7, BOARD_PITCH = 11, BOARD_CELL = 10;
    /** The board's letters: Galmuri9, the normal panel's (its ASCII sheet is BOARD_CELL high, its Hangul 9). */
    static final String BOARD_TEXT = "text", BOARD_HANGUL = HANGUL;
    /**
     * The default and the highest margin under the lowest row. The rows whose tops rise more than a cell over the
     * action bar's line (74 px up) draw from padded copies of the letters, 85 kB of client memory a pixel of the
     * Hangul sheet: at 10 the top two (pads of 1 and 12), at 2 the top one (4).
     */
    static final int BOARD_MARGIN = 2, BOARD_MAX_MARGIN = 10;
    /**
     * The board's left edge never comes nearer the middle than x BOARD_CLEAR_X (the hotbar ends at 91, the bars'
     * numbers at 105), so at any screen height it cannot cover them.
     */
    static final int BOARD_CLEAR_X = 106;

    /** The top of slot s's letter cell (0: the lowest), px above the bottom of the screen. */
    static int boardTop(int margin, int s) {
        return margin + s * BOARD_PITCH + BOARD_CELL;
    }

    /** Extra see-through rows a sheet `height` px high needs to be drawn with its top at an action-bar ascent. */
    static int boardPad(int ascent, int height) {
        return Math.max(0, ascent - height);
    }

    /** The board's widest at a right edge: hud.board.max-width, and never left of BOARD_CLEAR_X (but at least MIN_WIDTH). */
    static int boardCap(int right, int maxWidth) {
        return Math.max(MIN_WIDTH, Math.min(maxWidth, right - BOARD_CLEAR_X));
    }

    /**
     * The board's right edge: the configured one for a player who never moved their sidebar (null place), else the
     * screen's right edge as their sidebar tells it (its right edge when it is on the right; on the left, its left
     * edge is the screen's, so the right is as far the other way: see {@link #overlayLeft}), never so far in that the
     * board could not keep clear of the hotbar.
     */
    static int boardRight(int configured, Integer sidebarRight, int sidebarWidth) {
        if (sidebarRight == null) return configured;
        boolean onRight = sidebarRight - sidebarWidth / 2.0 >= 0;
        int edge = onRight ? sidebarRight : sidebarWidth - sidebarRight;
        return Math.max(BOARD_CLEAR_X + MIN_WIDTH, Math.min(MAX_RIGHT, edge));
    }

    /** A board line laid out: its text (from the left edge) and its value against the right edge (or null). */
    private record BoardLine(List<HudText.Cell> text, List<HudText.Cell> value) {}

    /** A board laid out: the header first, then the rows; its width. */
    private record BoardLaid(List<BoardLine> lines, int width) {}

    private BoardLaid layBoard(Board b, int right) {
        HudFont measure = boardRows.getFirst();
        List<List<HudText.Cell>> texts = new ArrayList<>();
        texts.add(HudText.onPanel(HudText.supported(measure, HudText.cells(b.title()))));
        for (Board.Row r : b.rows()) texts.add(HudText.onPanel(HudText.supported(measure, HudText.cells(r.text()))));
        int widest = 0;
        for (List<HudText.Cell> t : texts) {
            List<List<HudText.Cell>> parts = HudText.split(t);
            int w = parts == null ? HudText.width(measure, HudText.untab(t))
                    : HudText.width(measure, parts.get(0)) + (parts.get(1).isEmpty() ? 0 : VALUE_GAP + HudText.width(measure, parts.get(1)));
            widest = Math.max(widest, w);
        }
        int width = Math.min(boardCap(right, settings.boardMaxWidth()), widest);
        List<BoardLine> lines = new ArrayList<>();
        for (List<HudText.Cell> t : texts) {
            List<List<HudText.Cell>> parts = HudText.split(t);
            if (parts != null && !parts.get(1).isEmpty() && HudText.width(measure, parts.get(1)) <= width) {
                // the value stays whole against the right edge; the label gives way
                int room = width - HudText.width(measure, parts.get(1)) - VALUE_GAP;
                List<HudText.Cell> label = room <= 0 ? List.of() : fitLine(measure, parts.get(0), room);
                lines.add(new BoardLine(label, parts.get(1)));
            } else {
                lines.add(new BoardLine(fitLine(measure, HudText.untab(t), width), null));
            }
        }
        return new BoardLaid(lines, width);
    }

    /** One line of at most max px (an ellipsis at the end of what does not fit). */
    private static List<HudText.Cell> fitLine(HudFont f, List<HudText.Cell> cells, int max) {
        List<List<HudText.Cell>> w = HudText.wrap(f, cells, max, 1);
        return w.isEmpty() ? List.of() : w.getFirst();
    }

    /** How wide the board is at a right edge (0 when the board is off or there is none). */
    public int boardWidth(Board b, int right) {
        if (b == null || boardRows.isEmpty()) return 0;
        return layBoard(b, right).width();
    }

    /**
     * The board against its right edge (px right of the screen's middle), its lowest row hud.board.margin above the
     * bottom of the screen; null when there is none or the board is off. For the action bar ({@link #actionBar}):
     * its pen starts at the middle (0) and ends there (zero net advance).
     */
    public Component board(Board b, int right) {
        if (b == null || boardRows.isEmpty()) return null;
        BoardLaid laid = layBoard(b, right);
        int n = laid.lines().size(), x = right - laid.width();
        Ink ink = new Ink(0);
        for (int i = 0; i < n; i++) {
            BoardLine l = laid.lines().get(i);
            HudFont f = boardRows.get(n - 1 - i);   // the header the highest, the last row in slot 0
            ink.to(f, x).text(f, l.text());
            if (l.value() != null) ink.to(f, right - HudText.width(f, l.value())).text(f, l.value());
        }
        ink.to(boardRows.getFirst(), 0);
        return ink.build();
    }

    // ------------------------------------------------------------------------------------------
    // Overlay: a list on the left without a panel (a bus's passengers: face, mood, text)
    // ------------------------------------------------------------------------------------------

    /** One overlay row: a picture of the faces sheet (or null), a mood picture of it (or null) and its text. */
    public record OverlayRow(String face, String mood, Component text, Component bubble) {
        public OverlayRow(String face, String mood, Component text) {
            this(face, mood, text, null);
        }
    }

    /** How far below a row's top its face sits (the face is 10 px, a row 13). */
    static final int FACE_DY = 1;
    /** The overlay's columns: face, mood, text (px from its left edge). */
    static final int OVERLAY_MOOD_X = 12, OVERLAY_TEXT_X = 25;
    /** The bubble: the gap before it, the width of its left cap (the tail and the rim). */
    static final int BUBBLE_GAP = 2, BUBBLE_LEFT = 6;
    /** The bubble's top over the row's (its 13 px round the 9 px letters and their shadow row, a pixel clear above and below). */
    static final int BUBBLE_DY = -2;
    /** At most this many overlay rows (a bus's passengers), from the top slot of the normal size's column. */
    static final int OVERLAY_ROWS = 8;

    /** How wide the overlay of these rows is (its widest row, a speech bubble included). */
    public int overlayWidth(List<OverlayRow> rows) {
        HudFont f = columns.get(Size.NORMAL).rows().getFirst();
        int widest = 0;
        for (OverlayRow r : rows) {
            int w = OVERLAY_TEXT_X + HudText.width(f, HudText.onPanel(HudText.supported(f, HudText.cells(r.text()))));
            if (r.bubble() != null) w += BUBBLE_GAP + BUBBLE_LEFT + HudText.width(f, HudText.supported(f, HudText.cells(r.bubble()))) + 2 + 3;
            widest = Math.max(widest, w);
        }
        return widest;
    }

    /**
     * Where the overlay's left edge goes, opposite the player's sidebar (whose place they set against an edge of their
     * screen; the server never learns the screen's width): a sidebar on the right (its right edge {@code right} px right of
     * the middle) puts the overlay's left edge as far left; a sidebar on the left (its left edge right - width) tells
     * where the left of the screen is, and so the right: the overlay ends there, {@code inset} px in.
     */
    public static int overlayLeft(int sidebarRight, int sidebarWidth, int overlayWidth, int inset) {
        boolean onRight = sidebarRight - sidebarWidth / 2.0 >= 0;
        if (onRight) return -sidebarRight + inset;
        int half = sidebarWidth - sidebarRight;   // the screen's half width: the sidebar's left edge is at -half
        return half - inset - overlayWidth;
    }

    /**
     * The overlay: no background, no frame, only the pictures and the shadowed letters, one row a slot from the top of
     * the normal size's column, its left edge {@code left} px from the screen's middle. Null when there is nothing.
     * Zero net advance (it can follow the panel in the same boss bar title).
     */
    public Component overlay(List<OverlayRow> rows, int left) {
        if (rows == null || rows.isEmpty()) return null;
        Size size = Size.NORMAL;
        Column col = columns.get(size);
        HudFont pic = col.pictures();
        Ink ink = new Ink(0);
        int n = Math.min(rows.size(), Math.min(OVERLAY_ROWS, col.slots()));
        for (int i = 0; i < n; i++) {
            OverlayRow r = rows.get(i);
            int row = rowTop(size, col, i);
            if (r.face() != null && sheets.has("faces") && sheets.sheet("faces").has(r.face())) {
                ink.to(pic, left).glyph(pic, pic.glyph("faces", r.face(), boss(row + FACE_DY), 1));
            }
            if (r.mood() != null && sheets.has("faces") && sheets.sheet("faces").has(r.mood())) {
                ink.to(pic, left + OVERLAY_MOOD_X).glyph(pic, pic.glyph("faces", r.mood(), boss(row + FACE_DY), 1));
            }
            HudFont f = col.rows().get(i);
            List<HudText.Cell> text = HudText.onPanel(HudText.supported(f, HudText.cells(r.text())));
            ink.to(f, left + OVERLAY_TEXT_X).text(f, text);
            if (r.bubble() != null && sheets.has("bubble")) {
                // a speech bubble after the text: a white balloon (its tail towards the face), the words dark inside it
                List<HudText.Cell> words = HudText.onPanel(HudText.supported(f, HudText.cells(r.bubble())));
                int x = left + OVERLAY_TEXT_X + HudText.width(f, text) + BUBBLE_GAP, w = HudText.width(f, words) + 2;
                int asc = boss(row + BUBBLE_DY);
                ink.to(pic, x).glyph(pic, pic.glyph("bubble", "l", asc, 1));
                int px = x + BUBBLE_LEFT, left2 = w;
                for (int seg : new int[]{16, 8, 4, 2, 1}) {
                    while (left2 >= seg) {
                        ink.to(pic, px).glyph(pic, pic.glyph("bubble", String.valueOf(seg), asc, 1));
                        px += seg;
                        left2 -= seg;
                    }
                }
                ink.to(pic, px).glyph(pic, pic.glyph("bubble", "r", asc, 1));
                ink.to(f, x + BUBBLE_LEFT + 1).text(f, words, false);
            }
        }
        ink.to(pic, 0);
        return ink.build();
    }

    /** The top of a size's slot j (where that row's strip, icon and letters start). */
    private static int rowTop(Size size, Column col, int j) {
        return col.top0() + j * size.row() + size.head();
    }

    /** One horizontal strip of the panel: left cap, fills, right cap (with the shadow column). */
    private void strip(Ink ink, HudFont pic, String sheet, int ascent, int x, int width, int k) {
        int cap = CAP * k;
        ink.to(pic, x).glyph(pic, pic.glyph(sheet, "l", ascent, k));
        segments(ink, pic, sheet, ascent, x + cap, width - 2 * cap, k);
        ink.to(pic, x + width - cap).glyph(pic, pic.glyph(sheet, "r", ascent, k));
    }

    /** `length` px of a strip sheet's fill pieces from x on (a piece of n px is n x scale wide on screen). */
    private void segments(Ink ink, HudFont pic, String sheet, int ascent, int x, int length, int k) {
        int px = x, left = length;
        for (int s : SEGMENTS) {
            while (left >= s * k) {
                ink.to(pic, px).glyph(pic, pic.glyph(sheet, String.valueOf(s), ascent, k));
                px += s * k;
                left -= s * k;
            }
        }
    }
}
