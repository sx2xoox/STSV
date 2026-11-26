package dev.stsv.economy.hwatu.hall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The 도박장 (docs/plans/도박장_기획.md §1): a construction-site office (공사장 가설 사무소) that /관리자 도박장 건설 puts
 * where the admin stands, looking the way they look. Pure (unit-tested; tools/assetgen/gamble_hall_preview.py draws it
 * from the JSON a test writes); {@link HallBuilder} turns it into the world.
 *
 * <p>Coordinates are the hall's own, as a 하역장's ({@code DockLayout}): x to the admin's right, z forward (the way they
 * look), y up from where they stand (y = -1 is the ground block under their feet). A point (props, anchors) is in block
 * space: block b spans b .. b + 1, so 0.5 is the middle of block 0. Block states are written as for a hall facing south
 * (local +z = south, -z = north, +x = west, -x = east): {@link #rotate} turns them with the hall, {@link Site} turns
 * places and yaws (Minecraft degrees, 0 = +z, 90 = +x, 180 = -z, 270 = -x).
 *
 * <pre>
 *  z=28 ┌────────── 파란 가림막 (x ±16) ─ 뒷마당: 간이 화장실 · 자갈 더미 · 폐기물 통 · 철근 ──────────┐
 *       │ 비계  ┌ z=23 ───────────── 본관: 컨테이너 3동 (빨강 x -10..-4 · 초록 -3..3 · 파랑 4..10) ┐ 철계단 │
 *       │ (x    │  화이트보드 (뒷벽 가운데)                                                    │ (x 11..12,│
 *       │ -13.. │  [섯다 일반방]   [홀짝 일반방]   [섯다 큰손방]   z 17 ← 위가 뚫린 2층 구경석 │ z 8..18)  │
 *       │ -11)  │  ─ 2층: 노란 컨테이너 1동 (z 13..21, 바닥에 구멍 x -7..7 z 15..19 · 난간) ─  │ → 2층 문  │
 *       │       │  [섯다 초보방]   [홀짝 초보방]   [주사위 초보방] z 10                        │  (x 10)   │
 *       │       │  환전 창구 (책상 x -6, 소장 x -8)      휴게 (소파 · 자판기 · 정수기)          │           │
 *       │       └ z=1 ───────── 열린 컨테이너 문 (x -2..2) · 발판 ─────────────────────────────┘           │
 *       │   드럼통 모닥불 · 팔레트 · 시멘트 · 고깔 · 작업등          작업등 · 고깔                          │
 *  z=-6 └──────────── 정문 (x -2..2, 노랑·검정 줄무늬 기둥) · "공사중 · 관계자 외 출입금지" ─────────────────┘
 * </pre>
 *
 * <p>별관 (annex, docs/plans/도박장_기획.md §7): two more containers joined side by side to the right of the stairs
 * (x {@link #ANNEX_X0} .. {@link #ANNEX_X1}, z 1 .. 23: a white one and an orange one, inner walls cut out), its own open
 * doors onto the yard (x 15 .. 19), a covered passage from the hall's back right corner (x 11 .. 13, z 19 .. 23,
 * doorways z 20 .. 22): 세븐 포커 초보방 · 일반방 (poker_table) and 바카라 일반방 (baccarat_table, its dealer at the back,
 * facing the door) down its middle, a second chip counter by its door, a red runner, lamps. The lot grows to the right
 * (x -16 .. {@link #LOT_RIGHT}: 47 wide).
 *
 * <pre>
 *  z=23 ┌─ 통로 (x 11..13, z 19..23) ─┬────────── 별관 (x 14..27) ───────────┐
 *       │  (본관 뒤 오른쪽 구석과 이어짐) │ [바카라 일반방] z 19, 딜러 z 21 (뒤)  │
 *       │                              │ [세븐 포커 일반방] z 13                │
 *       │  철계단 (x 11..13)            │ [세븐 포커 초보방] z 7                 │
 *       │                              │ 문 (x 15..19)     환전 창구 2 (x 24)    │
 *  z=1  └──────────────────────────────┴───────────────────────────────────────┘
 * </pre>
 *
 * Real blocks for everything built (ground, hoarding, scaffolding, the containers, stairs, the inside), display models
 * ({@link Prop}: the economy:gamble_prop_* items) for the clutter, text displays ({@link Label}) for printed signs, and
 * the nine tables ({@link Table}: core furniture, placed by the builder; six in the hall, three in the 별관). Nothing a player could open (no chests, no
 * barrels, no doors): the only things that do something are the tables and the counter's NPC (HwatuModule's).
 */
public final class HallLayout {

    /** A block (vanilla block data, as /setblock writes it, for a hall facing south). */
    public record Block(int x, int y, int z, String data) {}

    /** Fill down from y until something solid (the ground), at most depth blocks: the lot's foundations. */
    public record Column(int x, int z, int fromY, int depth, String data) {}

    /** A box of blocks (inclusive). */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
        }

        public boolean containsBlock(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** A point in block space with a yaw (where someone stands and looks, where a display goes and faces). */
    public record Point(double x, double y, double z, float yaw) {}

    /**
     * A display model: the item {@code item} (an economy:gamble_* id, or an item model such as economy:model/dock_forklift)
     * shown FIXED at a point (its base centre: the
     * model's y 0 on the point), this big, its front turned to yaw.
     */
    public record Prop(double x, double y, double z, String item, float scale, float yaw) {}

    /**
     * Printed text (a text display, fixed, facing yaw): a banner, a slogan on the hoarding.
     *
     * @param text       MiniMessage
     * @param background ARGB (0 = none)
     */
    public record Label(double x, double y, double z, float yaw, String text, float scale, int background) {}

    /** What is played at a table (HwatuModule's game ids). */
    public enum Game {
        SUTDA("sutda", "섯다"), DICE("dice", "주사위"), HOLJJAK("holjjak", "홀짝"),
        /** 별관: 세븐 포커 (poker_table). */
        SEVEN_POKER("seven_poker", "세븐 포커"),
        /** 별관: 바카라 (baccarat_table, a dealer NPC at {@link Table#dealer}). */
        BACCARAT("baccarat", "바카라");

        public final String id, word;

        Game(String id, String word) {
            this.id = id;
            this.word = word;
        }
    }

    /** A table's room (stakes). */
    public enum Room {
        BEGINNER("beginner", "초보방"), NORMAL("normal", "일반방"), HIGH("high", "큰손방");

        public final String id, word;

        Room(String id, String word) {
            this.id = id;
            this.word = word;
        }
    }

    /**
     * The whiteboard (gamble_whiteboard, centred on its display: the frame 12.5 px over the middle, the marker tray
     * 13.75 px under it) hangs with its middle this far over the floor (the ceiling is 3 over it): the tray 0.69 over
     * the floor, the frame's top 0.67 under the ceiling, the writing about a standing player's eye (1.62).
     */
    public static final double WHITEBOARD_MIDDLE = 1.55, WHITEBOARD_UP = 12.5 / 16, WHITEBOARD_DOWN = 13.75 / 16;
    /** Its text display's point over the middle (text grows up from it, and HwatuModule draws it 0.45 lower). */
    public static final double WHITEBOARD_TEXT = 0.1;

    /** The two table items (core furniture with seats: docs/plans/도박장_기획.md §2). */
    public static final String ROUND = "economy:gamble_table_round", DUEL = "economy:gamble_table_duel";
    /** The 별관's two table items (items/gamble.yml): 세븐 포커 (6 seats as the round table's) and 바카라 (6 seats round its curve). */
    public static final String POKER = "economy:poker_table", BACCARAT = "economy:baccarat_table";

    /**
     * One of the nine tables (six in the hall, three in the 별관).
     *
     * @param index     0 .. 5 in the plan's order (섯다 초보 · 섯다 일반 · 섯다 큰손 · 주사위 초보 · 홀짝 초보 · 홀짝 일반), then the
     *                  별관's 6 .. 8 (세븐 포커 초보 · 세븐 포커 일반 · 바카라 일반)
     * @param x         its anchor block (the furniture's own block: its middle), y the floor it stands on (+1 of the floor block)
     * @param yaw       the way its front (seat 0) faces
     * @param footprint the blocks it keeps clear for itself and its chairs (kept free of everything else)
     * @param seats     where the chairs are, assumed (seat 0 in front, then clockwise seen from above; the baccarat
     *                  table's round its curve from the dealer's left): for the
     *                  layout's checks only, the furniture's own seats are what count
     * @param centre    the middle of its top (cards and chips go round it)
     * @param sign      where its 팻말 (종목 · 방 · 판돈) floats
     * @param dealer    the baccarat table's dealer: standing in the cell behind its straight edge (the furniture's cell
     *                  (0, 0, 2), 2 blocks behind the anchor, away from the door), facing the players; null for the others
     */
    public record Table(int index, int x, int y, int z, float yaw, String item, Game game, Room room, List<int[]> footprint,
                        List<int[]> seats, Point centre, Point sign, Point dealer) {
        public boolean round() {
            return ROUND.equals(item);
        }

        /** In the 별관 (세븐 포커 · 바카라)? */
        public boolean annex() {
            return POKER.equals(item) || BACCARAT.equals(item);
        }

        /** "섯다 큰손방". */
        public String title() {
            return game.word + " " + room.word;
        }
    }

    /**
     * Everything that is built.
     *
     * @param clear      air first (whatever stood there)
     * @param columns    foundations under the lot
     * @param blocks     the build, bottom up
     * @param props      display models (clutter)
     * @param labels     printed text
     * @param tables     the nine tables, by index (0 .. 5 the hall's, 6 .. 8 the 별관's)
     * @param gate       outside the lot's gate, looking in
     * @param warp       just inside the hall's door, looking in (where /화투방 brings people)
     * @param counterNpc where the 현장 소장 (the chip exchange's NPC) stands behind the desk, facing the customers
     * @param counter    where a customer stands at the desk, facing the boss
     * @param whiteboard the whiteboard's face (its middle, a little in front of it), facing the room: text goes here
     * @param gallery    the 2층 구경석, on its front walkway, looking down into the hall
     * @param smoke      where a wisp of 담배연기 may rise (over each table, by the drum fire)
     * @param hall       the inside of the hall (both floors): "in the 도박장"
     * @param bounds     everything it touches
     * @param annex      the inside of the 별관 and its passage from the hall (also "in the 도박장")
     * @param annexCounterNpc the 별관 chip counter's NPC place (behind its desk by the door), facing the customers
     * @param annexCounter    where a customer stands at that desk, facing the NPC
     * @param annexDoor  just inside the 별관's own doors, looking in
     */
    public record Layout(List<Box> clear, List<Column> columns, List<Block> blocks, List<Prop> props, List<Label> labels, List<Table> tables,
                         Point gate, Point warp, Point counterNpc, Point counter, Point whiteboard, Point gallery, List<Point> smoke,
                         Box hall, Box bounds, List<Box> annex, Point annexCounterNpc, Point annexCounter, Point annexDoor) {

        /** Is a point in the hall or its 별관 (either floor, the passage)? */
        public boolean inside(double x, double y, double z) {
            if (hall.contains(x, y, z)) return true;
            for (Box b : annex) if (b.contains(x, y, z)) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // the plan
    // ------------------------------------------------------------------------------------------

    /** The lot (inside the hoarding at x ±LOT, z FRONT .. BACK), how high it is cleared, how deep its foundations go. */
    public static final int LOT = 16, FRONT = -6, BACK = 28, CLEAR = 20, FOUNDATION = 24;
    /** The lot's right edge (the hoarding at x LOT_RIGHT; on the left it stays at -LOT): the 별관 made it wider. */
    public static final int LOT_RIGHT = 30;
    /** The 별관: two containers (x ANNEX_X0 .. ANNEX_X0 + 6 white, .. ANNEX_X1 orange), z HALL_Z0 .. HALL_Z1, floor y 0, roof 4. */
    public static final int ANNEX_X0 = 14, ANNEX_X1 = 27;
    /** The passage from the hall's back right corner to the 별관 (x 11 .. 13), its doorways z PASS_Z0 .. PASS_Z1. */
    public static final int PASS_Z0 = 20, PASS_Z1 = 22;
    /** The apron outside the gate (x ±APRON_HALF, z APRON .. FRONT - 1): where the sign stands, the plates come out. */
    public static final int APRON = -9, APRON_HALF = 7;
    /** The hall: three containers joined side by side (x ±HALF, z HALL_Z0 .. HALL_Z1), floor at y 0, walls 1..3, roof 4. */
    public static final int HALF = 10, HALL_Z0 = 1, HALL_Z1 = 23, ROOF = 4;
    /** The 2층: one container across the back half, on the roof (z UP_Z0 .. UP_Z1), walls 5..7, roof 8. */
    public static final int UP_Z0 = 13, UP_Z1 = 21, UP_ROOF = 8;
    /** The hole in the 2층's floor the gallery looks down through (the hall's roof cut away). */
    public static final Box VOID = new Box(-7, ROOF, 15, 7, ROOF, 19);

    private static final String AIR = "minecraft:air";

    private final Map<Long, Block> blocks = new LinkedHashMap<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<Prop> props = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Table> tables = new ArrayList<>();
    private final List<Point> smoke = new ArrayList<>();

    private HallLayout() {}

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    private void set(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), new Block(x, y, z, data.startsWith("minecraft:") ? data : "minecraft:" + data));
    }

    private String at(int x, int y, int z) {
        Block b = blocks.get(key(x, y, z));
        return b == null ? null : b.data();
    }

    private void fill(int x0, int y0, int z0, int x1, int y1, int z1, String data) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, data);
    }

    private void prop(double x, double y, double z, String what, float yaw) {
        props.add(new Prop(x, y, z, "economy:gamble_prop_" + what, 1f, yaw));
    }

    /** Made on first use (after every constant of the class is set). */
    private static final class Holder {
        static final Layout LAYOUT = build();
    }

    /** The 도박장 (the same object every time: it is pure). */
    public static Layout of() {
        return Holder.LAYOUT;
    }

    private static Layout build() {
        HallLayout d = new HallLayout();
        d.lot();
        d.hoarding();
        d.containers();
        d.upper();
        d.stairs();
        d.scaffold();
        d.inside();
        d.annex();
        d.yard();
        d.connect();
        List<Block> out = new ArrayList<>(d.blocks.values());
        out.sort(Comparator.comparingInt(Block::y));
        Box clear = new Box(-LOT, 0, APRON, LOT_RIGHT, CLEAR, BACK);
        Box bounds = new Box(-LOT - 1, -FOUNDATION - 2, APRON - 1, LOT_RIGHT + 1, CLEAR, BACK + 1);
        return new Layout(List.of(clear), List.copyOf(d.columns), List.copyOf(out), List.copyOf(d.props), List.copyOf(d.labels),
                List.copyOf(d.tables),
                new Point(0.5, 0, FRONT + 1.5, 0),
                new Point(0.5, 1, HALL_Z0 + 2.5, 0),
                new Point(-7.5, 1, 4.5, 90),
                new Point(-4.5, 1, 4.5, 270),
                new Point(0.5, 1 + WHITEBOARD_MIDDLE + WHITEBOARD_TEXT, HALL_Z1 - 0.85, 180),
                new Point(0.5, ROOF + 1, UP_Z0 + 1.5, 0),
                List.copyOf(d.smoke),
                new Box(-HALF + 1, 1, HALL_Z0 + 1, HALF - 1, UP_ROOF - 1, HALL_Z1 - 1),
                bounds,
                List.of(new Box(ANNEX_X0 + 1, 1, HALL_Z0 + 1, ANNEX_X1 - 1, ROOF - 1, HALL_Z1 - 1), new Box(HALF, 1, PASS_Z0, ANNEX_X0, 2, PASS_Z1)),
                new Point(25.5, 1, 3.5, 270),
                new Point(23.5, 1, 3.5, 90),
                new Point(17.5, 1, HALL_Z0 + 1.5, 0));
    }

    // ------------------------------------------------------------------------------------------
    // the lot: packed ground, a steel-plate track from the gate to the door
    // ------------------------------------------------------------------------------------------

    /** A small, fixed scatter (the same build every time): 0 .. n-1 for a block. */
    static int noise(int x, int z, int n) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return Math.floorMod(h, n);
    }

    private void lot() {
        for (int x = -LOT; x <= LOT_RIGHT; x++) {
            for (int z = FRONT; z <= BACK; z++) {
                // patches a few blocks across (trodden dirt, spread gravel), a little scatter inside them
                int patch = noise(Math.floorDiv(x + noise(z, 7, 2), 3), Math.floorDiv(z + noise(x, 3, 2), 3), 10), fine = noise(x, z, 9);
                String g = fine == 0 ? "packed_mud" : fine == 1 && patch < 7 ? "gravel" : patch < 5 ? "coarse_dirt" : patch < 7 ? "dirt" : "gravel";
                set(x, -1, z, g);
                columns.add(new Column(x, z, -2, FOUNDATION, "minecraft:stone"));
            }
        }
        for (int x = -APRON_HALF; x <= APRON_HALF; x++) {
            for (int z = APRON; z < FRONT; z++) {
                set(x, -1, z, noise(x, z, 3) == 0 ? "gravel" : "coarse_dirt");
                columns.add(new Column(x, z, -2, FOUNDATION, "minecraft:stone"));
            }
        }
        // 복공판: the steel road plates laid from the gate to the door, and the strip along the front of the hall
        for (int z = APRON; z <= 0; z++) for (int x = -2; x <= 2; x++) set(x, -1, z, Math.floorMod(z, 2) == 0 ? "polished_deepslate" : "deepslate_tiles");
        // under the hall: gravel (it sits on it)
        for (int x = -HALF; x <= HALF; x++) for (int z = HALL_Z0; z <= HALL_Z1; z++) set(x, -1, z, "gravel");
        for (int x = HALF + 1; x <= ANNEX_X1; x++) for (int z = HALL_Z0; z <= HALL_Z1; z++) if (x >= ANNEX_X0 || z >= PASS_Z0 - 1) set(x, -1, z, "gravel");
        // the plates on to the 별관's doors
        for (int z = FRONT + 1; z <= 0; z++) for (int x = 15; x <= 19; x++) set(x, -1, z, Math.floorMod(z, 2) == 0 ? "polished_deepslate" : "deepslate_tiles");
    }

    // ------------------------------------------------------------------------------------------
    // 가림막: blue steel hoarding all round, a white band at the top, the gate in hazard stripes
    // ------------------------------------------------------------------------------------------

    private void hoarding() {
        for (int x = -LOT; x <= LOT_RIGHT; x++) {
            fence(x, FRONT);
            fence(x, BACK);
        }
        for (int z = FRONT + 1; z < BACK; z++) {
            fence(-LOT, z);
            fence(LOT_RIGHT, z);
        }
        // the gate: two striped posts, a striped beam over it (5 wide, 3 high), the opening onto the plates
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) set(x, y, FRONT, AIR);
        for (int x : new int[]{-3, 3}) for (int y = 0; y <= 3; y++) set(x, y, FRONT, y % 2 == 0 ? "yellow_concrete" : "black_concrete");
        for (int x = -2; x <= 2; x++) set(x, 3, FRONT, Math.floorMod(x, 2) == 0 ? "black_concrete" : "yellow_concrete");
        // the folding mesh gate, pushed open along the inside of the hoarding
        for (int x = -8; x <= -4; x++) for (int y = 0; y <= 1; y++) set(x, y, FRONT + 1, "iron_bars");
        // printed on the hoarding, either side of the gate (outside)
        labels.add(new Label(-9.5, 0.55, FRONT - 0.02, 180, "<white><bold>안 전 제 일", 3.5f, 0));
        labels.add(new Label(12.5, 0.7, FRONT - 0.02, 180, "<white><bold>무 재 해</bold> 현 장", 2.2f, 0));
        labels.add(new Label(7.0, 0.75, FRONT - 0.02, 180, "<red><bold>공사중</bold></red> <black>· 관계자 외 출입금지", 1.25f, 0xF0FFFFFF));
        labels.add(new Label(0.5, 0.55, BACK + 1.02, 0, "<white><bold>안 전 제 일", 3.5f, 0));
        labels.add(new Label(23.5, 0.55, FRONT - 0.02, 180, "<white><bold>안 전 제 일", 3.0f, 0));
    }

    private void fence(int x, int z) {
        boolean post = ((x == -LOT || x == LOT_RIGHT) && (z == FRONT || z == BACK)) || (z == FRONT || z == BACK ? Math.floorMod(x, 4) == 0 : Math.floorMod(z, 4) == 2);
        set(x, 0, z, post ? "light_gray_concrete" : "blue_concrete");
        set(x, 1, z, post ? "light_gray_concrete" : "blue_concrete");
        set(x, 2, z, "white_concrete");
    }

    // ------------------------------------------------------------------------------------------
    // 본관: three shipping containers joined along their long sides (inner walls cut out), on the ground
    // ------------------------------------------------------------------------------------------

    /** A container's paint: its panels, the ribs between them (every other block), its rails and corner posts. */
    private record Paint(String panel, String rib, String rail) {}

    private static final Paint RED = new Paint("red_terracotta", "red_concrete", "brown_terracotta");
    private static final Paint GREEN = new Paint("green_concrete", "green_terracotta", "brown_terracotta");
    private static final Paint BLUE = new Paint("blue_concrete", "blue_terracotta", "gray_concrete");
    private static final Paint YELLOW = new Paint("yellow_concrete", "yellow_terracotta", "gray_concrete");
    private static final Paint WHITE = new Paint("white_concrete", "light_gray_concrete", "gray_concrete");
    private static final Paint ORANGE = new Paint("orange_terracotta", "orange_concrete", "brown_terracotta");

    /** Which container a hall x is in (its paint) and its walls' x. */
    private static Paint module(int x) {
        return x <= -4 ? RED : x <= 3 ? GREEN : BLUE;
    }

    private static boolean moduleEdge(int x) {
        return x == -HALF || x == -4 || x == -3 || x == 3 || x == 4 || x == HALF;
    }

    private void containers() {
        int x0 = -HALF, x1 = HALF, z0 = HALL_Z0, z1 = HALL_Z1;
        for (int x = x0; x <= x1; x++) {
            Paint p = module(x);
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                // the floor: plywood sheets (2 x 4, the seams darker); the steel bottom rail under the walls
                set(x, 0, z, wall ? p.rail() : plywood(x, z));
                // the roof: the containers' tops, ribbed across, a rail round each
                boolean rim = wall || moduleEdge(x);
                set(x, ROOF, z, rim ? p.rail() : Math.floorMod(z, 2) == 0 ? p.rib() : p.panel());
                if (!wall) continue;
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean post = corner || (z == z0 || z == z1) && moduleEdge(x);
                for (int y = 1; y < ROOF; y++) {
                    int along = z == z0 || z == z1 ? x : z;
                    set(x, y, z, post ? p.rail() : Math.floorMod(along, 2) == 0 ? p.rib() : p.panel());
                }
            }
        }
        // the middle container's doors, open: the doorway (5 wide, 2 high, its header the door frame), the two leaves
        // swung right round to stand out in front of it, a pallet for a step
        for (int x = -2; x <= 2; x++) {
            set(x, 1, z0, AIR);
            set(x, 2, z0, AIR);
            set(x, 3, z0, GREEN.rail());
            set(x, 0, z0, plywood(x, z0));
            set(x, 0, z0 - 1, "spruce_slab[type=bottom]");
        }
        for (int x : new int[]{-3, 3}) {
            for (int z = z0 - 2; z <= z0 - 1; z++) for (int y = 1; y <= 3; y++) set(x, y, z, z == z0 - 2 ? GREEN.rail() : y == 2 ? GREEN.rib() : GREEN.panel());
        }
        // hazard tape across the step
        for (int x = -2; x <= 2; x++) set(x, -1, z0 - 2, Math.floorMod(x, 2) == 0 ? "yellow_concrete" : "black_concrete");
        // the back doors, shut: their locking bars
        for (int x : new int[]{-2, -1, 1, 2}) for (int y = 1; y <= 3; y++) set(x, y, z1 + 1, "iron_chain");
        // windows: frosted (grey) glass with bars outside, in the side containers' ends and long walls
        for (int x : new int[]{-8, -7, -6, 6, 7, 8}) set(x, 2, z0, "gray_stained_glass_pane");
        for (int x : new int[]{-8, -7, -6, 6, 7, 8}) set(x, 2, z1, "gray_stained_glass_pane");
        for (int z : new int[]{4, 5, 6, 9, 10, 11, 17, 18}) {
            if (z > 6) set(-HALF, 2, z, "gray_stained_glass_pane");   // the counter's wall has its notices instead
            set(HALF, 2, z, "gray_stained_glass_pane");
        }
        // an air conditioner's outdoor unit by the left container's end, its pipe up the wall
        set(-8, 0, z0 - 1, "white_concrete");
        set(-7, 0, z0 - 1, "light_gray_concrete");
        set(-9, 1, z0 - 1, "lightning_rod");
        // the office's placard over the door
        labels.add(new Label(0.5, 3.2, z0 - 0.03, 180, "<black><bold>현 장 사 무 소", 1.6f, 0xF0F0F0F0));
        // floodlights on the hall's front corners
        set(-HALF, ROOF + 1, z0, "lantern");
        set(HALF, ROOF + 1, z0, "lantern");
    }

    private static String plywood(int x, int z) {
        // 2 wide, 4 long sheets, staggered, in two tones (new board and board that has seen some boots)
        int sheet = Math.floorDiv(x, 2) + Math.floorDiv(z + (Math.floorMod(Math.floorDiv(x, 2), 2) == 0 ? 0 : 2), 4);
        return Math.floorMod(sheet, 2) == 0 ? "birch_planks" : "stripped_birch_wood[axis=z]";
    }

    // ------------------------------------------------------------------------------------------
    // 2층: a yellow container across the back half, its floor cut open over the tables (구경석)
    // ------------------------------------------------------------------------------------------

    private void upper() {
        int x0 = -HALF, x1 = HALF, z0 = UP_Z0, z1 = UP_Z1, top = UP_ROOF;
        Paint p = YELLOW;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (!wall) set(x, ROOF, z, VOID.containsBlock(x, ROOF, z) ? AIR : plywood(x, z));
                set(x, top, z, wall ? p.rail() : Math.floorMod(x, 2) == 0 ? p.rib() : p.panel());
                if (!wall) continue;
                set(x, ROOF, z, p.rail());
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = ROOF + 1; y < top; y++) {
                    int along = z == z0 || z == z1 ? x : z;
                    set(x, y, z, corner ? p.rail() : Math.floorMod(along, 2) == 0 ? p.rib() : p.panel());
                }
            }
        }
        // long windows front and back (looking out over the yard), small ones in the ends
        for (int x = -8; x <= 8; x++) {
            if (Math.abs(x) <= 3) continue;                       // the banner hangs there
            set(x, ROOF + 2, z0, "glass_pane");
            set(x, ROOF + 2, z1, "glass_pane");
        }
        for (int z = 14; z <= 15; z++) set(-HALF, ROOF + 2, z, "glass_pane");
        // the door from the stair landing (right end)
        for (int z = 16; z <= 17; z++) for (int y = ROOF + 1; y <= ROOF + 2; y++) set(HALF, y, z, AIR);
        // the rail round the hole: steel bars on its rim, over the drop
        for (int x = VOID.minX(); x <= VOID.maxX(); x++) {
            set(x, ROOF + 1, VOID.minZ(), "iron_bars");
            set(x, ROOF + 1, VOID.maxZ(), "iron_bars");
        }
        for (int z = VOID.minZ(); z <= VOID.maxZ(); z++) {
            set(VOID.minX(), ROOF + 1, z, "iron_bars");
            set(VOID.maxX(), ROOF + 1, z, "iron_bars");
        }
        // benches for the watchers at the left end (facing the hole), a bulb, a tube over the hole
        for (int z = 15; z <= 19; z++) set(-9, ROOF + 1, z, "spruce_stairs[facing=east,half=bottom,shape=straight]");
        set(-8, top - 1, 14, "lantern[hanging=true]");
        set(8, top - 1, 20, "lantern[hanging=true]");
        set(-8, top - 1, 20, "lantern[hanging=true]");
        for (int x = -1; x <= 1; x++) set(x, top - 1, 14, "end_rod[facing=west]");
        // the bulbs over the back row hang down through the hole on chains from this roof
        for (int x : new int[]{-6, 0, 6}) {
            for (int y = ROOF + 1; y < top; y++) set(x, y, 17, "iron_chain");
            set(x, ROOF, 17, "lantern[hanging=true]");
        }
        // 안전제일: the green-cross banner on its front, over the yard
        labels.add(new Label(0.5, ROOF + 1.35, z0 - 0.03, 180, "<#1f8a3b><bold>✚</bold> <black><bold>안 전 제 일", 3.0f, 0xF2FFFFFF));
        // a water tank and a vent on its roof
        fill(6, top + 1, 15, 7, top + 2, 16, "white_concrete");
        set(-6, top + 1, 18, "andesite_wall");
    }

    // ------------------------------------------------------------------------------------------
    // 철계단: steel stairs up the right side to a landing at the 2층's door
    // ------------------------------------------------------------------------------------------

    private void stairs() {
        // five steps up from the ground (z 8 .. 12), the landing (steel grating, z 13 .. 19) at the door
        for (int k = 0; k <= 4; k++) {
            for (int x = 11; x <= 12; x++) set(x, k, 8 + k, "polished_deepslate_stairs[facing=south,half=bottom,shape=straight]");
            set(13, k + 1, 8 + k, "iron_bars");                     // the handrail
            if (k > 0) set(13, k, 8 + k, "iron_bars");
        }
        for (int z = 13; z <= 19; z++) {
            for (int x = 11; x <= 12; x++) set(x, ROOF, z, "iron_trapdoor[facing=north,half=top,open=false]");
            set(13, ROOF + 1, z, "iron_bars");
        }
        for (int x = 11; x <= 13; x++) set(x, ROOF + 1, 19, "iron_bars");
        // posts under the landing and the top step
        for (int[] c : new int[][]{{12, 12}, {12, 15}, {12, 19}, {11, 19}}) for (int y = 0; y < (c[1] == 12 ? 4 : ROOF); y++) set(c[0], y, c[1], "polished_deepslate_wall");
        // hazard stripes on the first step's nose and a bulb at the door
        set(11, -1, 7, "yellow_concrete");
        set(12, -1, 7, "black_concrete");
        set(11, ROOF + 3, 18, "lantern");
    }

    // ------------------------------------------------------------------------------------------
    // 비계: steel tube scaffolding up the left side, plank decks at two levels, dust net on part of it
    // ------------------------------------------------------------------------------------------

    private void scaffold() {
        int[] zs = {2, 6, 10, 14, 18, 22};
        for (int x : new int[]{-13, -11}) for (int z : zs) for (int y = 0; y <= 9; y++) set(x, y, z, "iron_bars");
        for (int z = 2; z <= 22; z++) {
            for (int y : new int[]{3, 7}) set(-12, y, z, "iron_trapdoor[facing=east,half=top,open=false]");
            for (int y : new int[]{4, 8}) set(-13, y, z, "iron_bars");     // guard rails
            if (z >= 14) for (int y = 5; y <= 7; y++) if (Math.floorMod(z - 2, 4) != 0) set(-13, y, z, "green_stained_glass_pane");
        }
        // a ladder up its front bay
        for (int y = 0; y <= 7; y++) set(-12, y, 1, "ladder[facing=north]");
    }

    // ------------------------------------------------------------------------------------------
    // inside: the counter (site-office desk), the lounge, the whiteboard, six tables, bulbs and tubes
    // ------------------------------------------------------------------------------------------

    /** The tables: anchor x, z (on the floor), their item, game, room. */
    private static final Object[][] TABLES = {
            {-6, 10, ROUND, Game.SUTDA, Room.BEGINNER},
            {-6, 17, ROUND, Game.SUTDA, Room.NORMAL},
            {6, 17, ROUND, Game.SUTDA, Room.HIGH},
            {6, 10, ROUND, Game.DICE, Room.BEGINNER},
            {0, 10, DUEL, Game.HOLJJAK, Room.BEGINNER},
            {0, 17, DUEL, Game.HOLJJAK, Room.NORMAL},
    };

    /** A round table's chairs round its middle (seat 0 in front: -z for a table facing -z), clockwise from above. */
    static List<int[]> roundSeats() {
        return List.of(new int[]{0, -2}, new int[]{-2, -1}, new int[]{-2, 1}, new int[]{0, 2}, new int[]{2, 1}, new int[]{2, -1});
    }

    /** A duel table's two chairs, face to face (seat 0 in front). */
    static List<int[]> duelSeats() {
        return List.of(new int[]{0, -2}, new int[]{0, 2});
    }

    private void inside() {
        int y = 1;
        // --- the six tables (yaw 180: their fronts toward the door) and what hangs over them
        for (int i = 0; i < TABLES.length; i++) {
            Object[] t = TABLES[i];
            int x = (int) t[0], z = (int) t[1];
            boolean round = ROUND.equals(t[2]);
            List<int[]> foot = new ArrayList<>();
            int hx = round ? 2 : 1, hz = 2;
            for (int dx = -hx; dx <= hx; dx++) for (int dz = -hz; dz <= hz; dz++) foot.add(new int[]{x + dx, y, z + dz});
            List<int[]> seats = new ArrayList<>();
            for (int[] s : round ? roundSeats() : duelSeats()) seats.add(new int[]{x + s[0], y, z + s[1]});
            Point centre = new Point(x + 0.5, y + 1.0, z + 0.5, 180);
            boolean open = VOID.containsBlock(x, ROOF, z);
            Point sign = new Point(x + 0.5, y + dev.stsv.economy.hwatu.TableArt.SIGN_UP, z + 0.5, 180);   // between its bulb and the ceiling (TableScene)
            tables.add(new Table(i, x, y, z, 180, (String) t[2], (Game) t[3], (Room) t[4], List.copyOf(foot), List.copyOf(seats), centre, sign, null));
            smoke.add(new Point(x + 0.5, y + 1.8, z + 0.5, 0));
            if (!open) set(x, ROOF - 1, z, "lantern[hanging=true]");   // a bare bulb over each (the back row's hang through the hole)
            // an old grey blanket thrown on the plywood under it (the chairs scrape on it, not the floor)
            for (int[] c : foot) if (Math.abs(c[0] - x) + Math.abs(c[2] - z) <= (round ? 3 : 2)) set(c[0], 0, c[2], "gray_wool");
        }
        // --- the 환전 창구: a grey steel desk with a green mat, the boss behind it, filing cabinets, a safe
        for (int z = 3; z <= 5; z++) set(-6, y, z, "light_gray_concrete");
        set(-6, y + 1, 3, "green_carpet");
        set(-6, y + 1, 4, "light_weighted_pressure_plate");      // the chip tray
        set(-6, y + 1, 5, "lantern");                             // a desk lamp
        set(-7, y, 2, "light_gray_concrete");                    // the desk's return against the wall
        for (int z = 2; z <= 3; z++) {
            set(-9, y, z, "light_gray_concrete");
            set(-9, y + 1, z, "light_gray_concrete");
            set(-8, y, z, "iron_trapdoor[facing=west,half=bottom,open=true]");   // drawer fronts
            set(-8, y + 1, z, "iron_trapdoor[facing=west,half=bottom,open=true]");
        }
        set(-9, y, 6, "black_concrete");                          // the safe
        set(-9, y + 1, 6, "dark_oak_slab[type=bottom]");
        set(-9, y, 7, "spruce_stairs[facing=east,half=bottom,shape=straight]");   // the boss's chair, pushed back
        set(-8, ROOF - 1, 4, "lantern[hanging=true]");
        labels.add(new Label(-HALF + 1.02, 2.3, 4.5, 90, "<#ffd84a><bold>칩 환전</bold>\n<white>현장 소장", 1.0f, 0xD0202020));
        // --- the lounge right of the door: an old sofa, a vending machine, a water cooler, a heater
        for (int x = 6; x <= 8; x++) set(x, y, 2, "spruce_stairs[facing=north,half=bottom,shape=straight]");
        set(5, y, 2, "spruce_slab[type=bottom]");
        set(9, y, 2, "spruce_slab[type=bottom]");
        set(9, y, 3, "red_concrete");
        set(9, y + 1, 3, "red_stained_glass");
        set(9, y + 2, 3, "red_concrete");
        set(9, y, 7, "white_concrete");
        set(9, y + 1, 7, "light_blue_stained_glass");
        set(5, y, 5, "cauldron");                                 // the ashtray stand
        labels.add(new Label(HALF - 0.02, 3.08, 5.5, 270, "<red><bold>금 연", 1.2f, 0xE0FFFFFF));
        // --- the whiteboard on the back wall, crates of drinks in the back corners
        // its model is centred on its point up and down (not stood on it like the others): HallBuilder lifts every prop
        // half a block (model floor on the point), so the point is half a block under the middle it should hang at
        props.add(new Prop(0.5, y + WHITEBOARD_MIDDLE - 0.5, HALL_Z1 - 0.55, "economy:gamble_whiteboard", 1f, 180));
        for (int x = -9; x <= -8; x++) {
            set(x, y, 22, "green_terracotta");
            set(x, y + 1, 22, x == -9 ? "green_terracotta" : AIR);
        }
        set(-9, y, 21, "green_terracotta");
        set(8, y, 22, "spruce_planks");                         // drinks crates (clear of the 별관 passage's doorway at x 9)
        set(8, y + 1, 22, "oak_planks");
        set(7, y, 22, "cauldron");
        // air conditioners high on the long walls
        for (int x : new int[]{-9, 9}) set(x, ROOF - 1, 14, "white_concrete");
        // high-vis vests hung on the front wall by the door, posters
        set(-3, 2, HALL_Z0 + 1, "lime_wall_banner[facing=south]");
        set(-4, 2, HALL_Z0 + 1, "orange_wall_banner[facing=south]");
        set(3, 2, HALL_Z0 + 1, "lime_wall_banner[facing=south]");
        labels.add(new Label(-HALF + 1.02, 2.45, 5.55, 90, "<red><bold>외 상 사 절", 0.75f, 0xF0FFFFFF));
        labels.add(new Label(-7.0, 2.4, HALL_Z0 + 1.02, 0, "<#1f8a3b><bold>✚</bold> <black>정 리 정 돈", 0.7f, 0xF0FFFFFF));
        labels.add(new Label(0.5, ROOF + 2.4, UP_Z1 - 0.02, 180, "<white><bold>구 경 석</bold>\n<gray>훈수 금지", 1.2f, 0xC0202020));
        // fluorescent tubes (end rods along the ceiling) over the front and the cross aisle, a bulb over the lounge
        for (int x = -1; x <= 1; x++) {
            set(x, ROOF - 1, 5, "end_rod[facing=west]");
            set(x, ROOF - 1, 13, "end_rod[facing=west]");
        }
        set(7, ROOF - 1, 4, "lantern[hanging=true]");
        smoke.add(new Point(5.5, 2.2, 5.5, 0));
    }

    // ------------------------------------------------------------------------------------------
    // 별관: two more containers to the right of the stairs, a passage from the hall's back right corner, the card room
    // ------------------------------------------------------------------------------------------

    /** The 별관's tables: anchor x, z, item, game, room (all facing the door, as the hall's: yaw 180). */
    private static final Object[][] ANNEX_TABLES = {
            {20, 7, POKER, Game.SEVEN_POKER, Room.BEGINNER},
            {20, 13, POKER, Game.SEVEN_POKER, Room.NORMAL},
            {20, 19, BACCARAT, Game.BACCARAT, Room.NORMAL},
    };

    /** The baccarat table's chairs round its curve (hall cells, as roundSeats: the furniture's x turned), from the dealer's left. */
    static List<int[]> baccaratSeats() {
        return List.of(new int[]{2, 0}, new int[]{2, -1}, new int[]{1, -2}, new int[]{-1, -2}, new int[]{-2, -1}, new int[]{-2, 0});
    }

    /** Where the baccarat dealer stands from the table's anchor (the furniture's cell (0, 0, 2): behind the straight edge). */
    public static final int DEALER_BACK = 2;

    private static Paint annexModule(int x) {
        return x <= ANNEX_X0 + 6 ? WHITE : ORANGE;
    }

    private static boolean annexEdge(int x) {
        return x == ANNEX_X0 || x == ANNEX_X0 + 6 || x == ANNEX_X0 + 7 || x == ANNEX_X1;
    }

    private void annex() {
        int x0 = ANNEX_X0, x1 = ANNEX_X1, z0 = HALL_Z0, z1 = HALL_Z1, y = 1;
        for (int x = x0; x <= x1; x++) {
            Paint p = annexModule(x);
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                set(x, 0, z, wall ? p.rail() : plywood(x, z));
                boolean rim = wall || annexEdge(x);
                set(x, ROOF, z, rim ? p.rail() : Math.floorMod(z, 2) == 0 ? p.rib() : p.panel());
                if (!wall) continue;
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                boolean post = corner || (z == z0 || z == z1) && annexEdge(x);
                for (int yy = 1; yy < ROOF; yy++) {
                    int along = z == z0 || z == z1 ? x : z;
                    set(x, yy, z, post ? p.rail() : Math.floorMod(along, 2) == 0 ? p.rib() : p.panel());
                }
            }
        }
        // its own doors, open on to the yard (the white container's end: 5 wide, 2 high), the leaves swung round in front
        for (int x = 15; x <= 19; x++) {
            set(x, 1, z0, AIR);
            set(x, 2, z0, AIR);
            set(x, 3, z0, Math.floorMod(x, 2) == 0 ? WHITE.panel() : WHITE.rib());   // the header: the container's end frame
            set(x, 0, z0, plywood(x, z0));
            set(x, 0, z0 - 1, "spruce_slab[type=bottom]");
            set(x, -1, z0 - 2, Math.floorMod(x, 2) == 0 ? "yellow_concrete" : "black_concrete");
        }
        for (int x : new int[]{x0, 20}) {
            for (int z = z0 - 2; z <= z0 - 1; z++) for (int yy = 1; yy <= 3; yy++) set(x, yy, z, z == z0 - 2 ? WHITE.rail() : yy == 2 ? WHITE.rib() : WHITE.panel());
        }
        labels.add(new Label(17.5, 3.2, z0 - 0.03, 180, "<black><bold>별 관</bold> <dark_gray>· 카 드 룸", 1.4f, 0xF0F0F0F0));
        labels.add(new Label(23.5, 3.15, z0 - 0.03, 180, "<#ffd84a><bold>세븐 포커 · 바카라", 1.0f, 0xE0202020));
        // windows: frosted glass with bars, in the far long wall, the ends
        for (int z : new int[]{6, 7, 8, 12, 13, 14, 18, 19}) set(x1, 2, z, "gray_stained_glass_pane");
        for (int x : new int[]{22, 23, 24}) set(x, 2, z0, "gray_stained_glass_pane");
        for (int x : new int[]{16, 17, 23, 24, 25}) set(x, 2, z1, "gray_stained_glass_pane");
        // the passage from the hall's back right corner: floor, two walls, a roof (the stair landing keeps its end)
        for (int x = HALF + 1; x < x0; x++) {
            for (int z = PASS_Z0 - 1; z <= PASS_Z1 + 1; z++) {
                boolean wall = z == PASS_Z0 - 1 || z == PASS_Z1 + 1;
                set(x, 0, z, wall ? WHITE.rail() : plywood(x, z));
                if (wall) for (int yy = 1; yy < ROOF; yy++) set(x, yy, z, Math.floorMod(x, 2) == 0 ? WHITE.rib() : WHITE.panel());
                if (at(x, ROOF, z) == null) set(x, ROOF, z, Math.floorMod(z, 2) == 0 ? WHITE.rib() : WHITE.rail());
            }
        }
        for (int z = PASS_Z0; z <= PASS_Z1; z++) {
            for (int yy = 1; yy <= 2; yy++) {
                set(HALF, yy, z, AIR);
                set(x0, yy, z, AIR);
            }
            set(HALF, 0, z, plywood(HALF, z));
            set(x0, 0, z, plywood(x0, z));
        }
        labels.add(new Label(HALF - 0.02, 3.1, 21.5, 270, "<#ffd84a><bold>별관 →</bold> <white>세븐 포커 · 바카라", 0.8f, 0xD0202020));
        set(12, ROOF - 1, 21, "lantern[hanging=true]");
        // --- the three tables, a bulb over each, a blanket (grey under the poker tables, red under the baccarat one)
        for (int i = 0; i < ANNEX_TABLES.length; i++) {
            Object[] t = ANNEX_TABLES[i];
            int x = (int) t[0], z = (int) t[1];
            boolean bac = BACCARAT.equals(t[2]);
            List<int[]> foot = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) foot.add(new int[]{x + dx, y, z + dz});
            List<int[]> seats = new ArrayList<>();
            for (int[] c : bac ? baccaratSeats() : roundSeats()) seats.add(new int[]{x + c[0], y, z + c[1]});
            Point centre = new Point(x + 0.5, y + 1.0, z + 0.5, 180);
            Point sign = new Point(x + 0.5, y + dev.stsv.economy.hwatu.TableArt.SIGN_UP, z + 0.5, 180);
            Point dealer = bac ? new Point(x + 0.5, y, z + DEALER_BACK + 0.5, 180) : null;
            tables.add(new Table(TABLES.length + i, x, y, z, 180, (String) t[2], (Game) t[3], (Room) t[4], List.copyOf(foot), List.copyOf(seats),
                    centre, sign, dealer));
            smoke.add(new Point(x + 0.5, y + 1.8, z + 0.5, 0));
            set(x, ROOF - 1, z, "lantern[hanging=true]");
            for (int[] c : foot) if (Math.abs(c[0] - x) + Math.abs(c[2] - z) <= 3) set(c[0], 0, c[2], bac ? "red_wool" : "gray_wool");
        }
        // the dealer's back wall: the game's name, the house rules
        labels.add(new Label(20.5, 2.55, z1 - 0.03, 0, "<#ffd84a><bold>바 카 라", 1.3f, 0xE0202020));
        labels.add(new Label(20.5, 1.95, z1 - 0.03, 0, "<white>뱅커 승 0.95배 · 타이 8배 · 플레이어 1배", 0.55f, 0xC0202020));
        // --- the second chip counter by the door: a desk, the NPC behind it, a cabinet and a safe, a lamp
        for (int z = 2; z <= 4; z++) set(24, y, z, "light_gray_concrete");
        set(24, y + 1, 2, "green_carpet");
        set(24, y + 1, 3, "light_weighted_pressure_plate");
        set(24, y + 1, 4, "lantern");
        set(26, y, 2, "light_gray_concrete");
        set(26, y + 1, 2, "light_gray_concrete");
        set(25, y, 2, "iron_trapdoor[facing=east,half=bottom,open=true]");   // a drawer front
        set(26, y, 5, "black_concrete");
        set(26, y + 1, 5, "dark_oak_slab[type=bottom]");
        set(25, ROOF - 1, 3, "lantern[hanging=true]");
        labels.add(new Label(x1 - 0.02, 2.3, 3.5, 270, "<#ffd84a><bold>칩 환전</bold>\n<white>별관 창구", 1.0f, 0xD0202020));
        // --- a red runner down the left aisle from the doors to the passage, a sofa and a water cooler on the right
        for (int z = 2; z <= 21; z++) set(16, y, z, "red_carpet");
        for (int z = PASS_Z0; z <= PASS_Z1; z++) set(15, y, z, "red_carpet");
        for (int z = 10; z <= 12; z++) set(26, y, z, "spruce_stairs[facing=west,half=bottom,shape=straight]");
        set(26, y, 9, "spruce_slab[type=bottom]");
        set(26, y, 13, "spruce_slab[type=bottom]");
        set(26, y, 16, "white_concrete");
        set(26, y + 1, 16, "light_blue_stained_glass");
        set(26, y, 22, "green_terracotta");
        set(25, y, 22, "green_terracotta");
        set(26, y + 1, 22, "green_terracotta");
        set(21, y, 2, "potted_fern");
        set(15, y, 10, "cauldron");                               // the ashtray stand
        // fluorescent tubes over the aisles, a bulb by the passage
        for (int z = 9; z <= 11; z++) {
            set(16, ROOF - 1, z, "end_rod[facing=north]");
            set(24, ROOF - 1, z + 6, "end_rod[facing=north]");
        }
        set(16, ROOF - 1, 21, "lantern[hanging=true]");
        labels.add(new Label(ANNEX_X0 + 1.02, 2.4, 13.5, 90, "<red><bold>금 연</bold>\n<black>외 상 사 절", 0.8f, 0xF0FFFFFF));
        // an air conditioner high on the far wall, its outdoor unit outside
        set(x1 - 1, ROOF - 1, 10, "white_concrete");
        set(x1 + 1, 0, 10, "white_concrete");
        set(x1 + 1, 0, 11, "light_gray_concrete");
        set(x0, ROOF + 1, z0, "lantern");
        set(x1, ROOF + 1, z0, "lantern");
    }

    // ------------------------------------------------------------------------------------------
    // the yard: the clutter of a site (models), a skip, a portable toilet, a heap of gravel, timber
    // ------------------------------------------------------------------------------------------

    private void yard() {
        // by the gate and along the plates: cones
        for (double[] c : new double[][]{{-3.5, -3.5}, {4.5, -4.5}, {-3.5, -1.5}, {4.5, -1.5}, {13.5, 7.5}, {10.5, -0.5}})
            prop(c[0], 0, c[1], "cone", 0);
        // the sign at the gate, outside, facing whoever comes
        prop(-5.5, 0, FRONT - 1.5, "sign", 180);
        // tripod work lights at the yard's front corners and by the stairs, turned on the hall
        prop(-13.5, 0, -3.5, "worklight", 45);
        prop(14.5, 0, -3.5, "worklight", 315);
        prop(14.5, 0, 24.5, "worklight", 225);
        // the drum fire where they stand about, pallets round it to sit on
        prop(-8.5, 0, -2.5, "drumfire", 0);
        smoke.add(new Point(-8.5, 1.6, -2.5, 0));
        prop(-10.5, 0, -2.5, "pallet", 90);
        prop(-8.5, 0, -0.5, "pallet", 0);
        // materials by the scaffolding: cement on pallets, rebar, timber
        prop(-14.5, 0, 4.5, "cement", 0);
        prop(-14.5, 0, 8.5, "pallet", 0);
        prop(-14.5, 0.15, 8.5, "pallet", 0);
        prop(-14.5, 0, 12.5, "cement", 90);
        prop(-14.5, 0, 18.5, "rebar", 0);
        prop(28.5, 0, 17.5, "rebar", 90);
        prop(-6.5, 0, 25.5, "rebar", 90);
        prop(28.5, 0, 6.5, "pallet", 0);                          // (the 별관 took the old spot by the stairs)
        for (int z = 22; z <= 24; z++) set(-15, 0, z, "stripped_spruce_log[axis=z]");
        set(-14, 0, 23, "stripped_spruce_log[axis=z]");
        set(-14, 1, 23, "stripped_spruce_log[axis=z]");
        // the site's forklift (the 하역장's model), parked by the skip
        props.add(new Prop(9.5, 0, 25.5, "economy:model/dock_forklift", 2f, 270));
        // a heap of gravel in the back left
        for (int x = -12; x <= -8; x++) for (int z = 25; z <= 27; z++) {
            int h = 2 - Math.max(Math.abs(x + 10), Math.abs(z - 26));
            if (h >= 0) set(x, 0, z, "gravel");
            if (h >= 1) set(x, 1, z, "gravel");
        }
        // the skip (open-top steel bin), rubbish in it
        for (int x = 2; x <= 6; x++) for (int z = 25; z <= 27; z++) {
            boolean rim = x == 2 || x == 6 || z == 25 || z == 27;
            set(x, 0, z, rim ? "yellow_terracotta" : noise(x, z, 3) == 0 ? "dark_oak_planks" : "gravel");
            if (rim) set(x, 1, z, "yellow_terracotta");
        }
        // the portable toilet in the back right corner
        fill(13, 0, 26, 14, 2, 27, "light_blue_concrete");
        fill(13, 3, 26, 14, 3, 27, "white_concrete");
        set(13, 0, 26, "light_blue_terracotta");               // its door
        set(13, 1, 26, "light_blue_terracotta");
        // a hose reel / barrels by the stairs
        set(28, 0, 12, "blue_terracotta");
        set(28, 0, 13, "blue_terracotta");
        // by the 별관: a work light on the yard's new front right corner, cones at its doors, cement on a pallet
        prop(28.5, 0, -3.5, "worklight", 315);
        prop(13.5, 0, -1.5, "cone", 0);
        prop(21.5, 0, -1.5, "cone", 0);
        prop(28.5, 0, 9.5, "cement", 90);
        prop(28.5, 0, 25.5, "drumfire", 0);
        smoke.add(new Point(28.5, 1.6, 25.5, 0));
    }

    // ------------------------------------------------------------------------------------------
    // panes, bars, fences and walls: their connections (blocks are set without physics, so the build gives them)
    // ------------------------------------------------------------------------------------------

    /** The local directions (a hall facing south): north = -z, south = +z, east = -x, west = +x. */
    private static final String[] DIRS = {"north", "east", "south", "west"};
    private static final int[][] STEP = {{0, -1}, {-1, 0}, {0, 1}, {1, 0}};

    private static String family(String data) {
        if (data == null) return null;
        String n = name(data);
        if (n.endsWith("_pane") || n.equals("iron_bars")) return "pane";
        if (n.endsWith("_wall")) return "wall";
        if (n.endsWith("_fence")) return "fence";
        return null;
    }

    /** "minecraft:spruce_stairs[facing=east]" -> "spruce_stairs". */
    static String name(String data) {
        int c = data.indexOf(':'), b = data.indexOf('[');
        return data.substring(c + 1, b < 0 ? data.length() : b);
    }

    /** A full, solid block a pane or a wall joins onto. */
    static boolean full(String data) {
        if (data == null) return false;
        String n = name(data);
        for (String s : new String[]{"_slab", "_stairs", "_trapdoor", "_carpet", "_pane", "_wall", "_fence", "lantern", "end_rod", "chain", "air",
                "pressure_plate", "ladder", "lightning_rod", "cauldron", "iron_bars"}) if (n.endsWith(s)) return false;
        return true;
    }

    private void connect() {
        for (Block b : new ArrayList<>(blocks.values())) {
            String fam = family(b.data());
            if (fam == null) continue;
            StringBuilder st = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                String n = at(b.x() + STEP[i][0], b.y(), b.z() + STEP[i][1]);
                boolean join = fam.equals(family(n)) || full(n);
                if (!st.isEmpty()) st.append(',');
                st.append(DIRS[i]).append('=').append(fam.equals("wall") ? (join ? "low" : "none") : join);
            }
            if (fam.equals("wall")) st.append(",up=true");
            set(b.x(), b.y(), b.z(), "minecraft:" + name(b.data()) + "[" + st + "]");
        }
    }

    // ------------------------------------------------------------------------------------------
    // turning: block states and places for a hall facing another way
    // ------------------------------------------------------------------------------------------

    private static final List<String> TURN = List.of("south", "west", "north", "east");
    private static final Set<String> HORIZONTAL = Set.of("north", "east", "south", "west");

    /**
     * A block state of the layout (written for a hall facing south) for a hall facing {@code facing} (0 south, 1 west,
     * 2 north, 3 east): facing= and the connection keys (north=, east=...) turn with it, axis= x and z swap on a quarter
     * turn, a sign's rotation= goes round by 4 a turn. Anything else stays.
     */
    public static String rotate(String data, int facing) {
        int f = Math.floorMod(facing, 4);
        int b = data.indexOf('[');
        if (f == 0 || b < 0) return data;
        String head = data.substring(0, b), body = data.substring(b + 1, data.length() - 1);
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : body.split(",")) {
            int e = kv.indexOf('=');
            String k = kv.substring(0, e), v = kv.substring(e + 1);
            if (HORIZONTAL.contains(k)) out.put(turn(k, f), v);
            else if (k.equals("facing") && HORIZONTAL.contains(v)) out.put(k, turn(v, f));
            else if (k.equals("axis") && f % 2 == 1 && !v.equals("y")) out.put(k, v.equals("x") ? "z" : "x");
            else if (k.equals("rotation")) out.put(k, Integer.toString(Math.floorMod(Integer.parseInt(v) + 4 * f, 16)));
            else out.put(k, v);
        }
        StringBuilder sb = new StringBuilder(head).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : out.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    private static String turn(String dir, int f) {
        return TURN.get(Math.floorMod(TURN.indexOf(dir) + f, 4));
    }

    /**
     * Where a hall stands: the admin's block (x, y, z: y where their feet were) and the way they looked (0 south, 1
     * west, 2 north, 3 east: Minecraft's yaw / 90). Pure.
     */
    public record Site(String world, int x, int y, int z, int facing) {

        /** The facing of a yaw (degrees, Minecraft: 0 = south, 90 = west). */
        public static int facing(float yaw) {
            return Math.floorMod(Math.round(yaw / 90f), 4);
        }

        private int[] forward() {
            return switch (Math.floorMod(facing, 4)) {
                case 0 -> new int[]{0, 1};
                case 1 -> new int[]{-1, 0};
                case 2 -> new int[]{0, -1};
                default -> new int[]{1, 0};
            };
        }

        /** A hall block (right, up, forward) as a world block. */
        public int[] block(int right, int up, int forward) {
            int[] f = forward();
            return new int[]{x + (-f[1]) * right + f[0] * forward, y + up, z + f[0] * right + f[1] * forward};
        }

        /** A hall point (block space: 0.5 = the middle of block 0) in the world. */
        public double[] at(double right, double up, double forward) {
            int[] f = forward();
            double r = right - 0.5, fw = forward - 0.5;
            return new double[]{x + 0.5 + (-f[1]) * r + f[0] * fw, y + up, z + 0.5 + f[0] * r + f[1] * fw};
        }

        /** A hall yaw in the world. */
        public float yaw(float local) {
            return ((facing * 90f + local) % 360f + 360f) % 360f;
        }

        /** A hall block state in the world. */
        public String state(String data) {
            return rotate(data, facing);
        }

        /** A box of the hall as world blocks {minX, minY, minZ, maxX, maxY, maxZ}. */
        public int[] box(Box b) {
            int[] a = block(b.minX(), b.minY(), b.minZ()), c = block(b.maxX(), b.maxY(), b.maxZ());
            return new int[]{Math.min(a[0], c[0]), Math.min(a[1], c[1]), Math.min(a[2], c[2]), Math.max(a[0], c[0]), Math.max(a[1], c[1]), Math.max(a[2], c[2])};
        }

        /** The chunks the hall touches (x, z). */
        public List<long[]> chunks() {
            int[] b = box(of().bounds());
            List<long[]> out = new ArrayList<>();
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) out.add(new long[]{cx, cz});
            return out;
        }

        /** Is a world point inside the hall (either floor) or its 별관 (or the passage to it)? */
        public boolean inside(String inWorld, double px, double py, double pz) {
            if (!world.equals(inWorld)) return false;
            List<Box> rooms = new ArrayList<>(of().annex());
            rooms.addFirst(of().hall());
            for (Box r : rooms) {
                int[] b = box(r);
                if (px >= b[0] && px < b[3] + 1 && py >= b[1] && py < b[4] + 1 && pz >= b[2] && pz < b[5] + 1) return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %d %d %d facing %d", world, x, y, z, facing);
        }
    }
}
