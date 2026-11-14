package dev.stsv.economy.dock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What /관리자 하역장 설치 builds, in the dock's own coordinates (README "하역장"): x to the right of the admin, z forward
 * (the way they look), y up from where they stand (y = -1 is the pad's floor). Pure (unit-tested; the preview
 * tools/assetgen/dock_preview.py draws it from the JSON a test writes). {@link DockBuilder} turns it into the world.
 *
 * <pre>
 *        z=40 ┌──────────────── 부두 (항구만: z 41 ~ 68, 양옆이 배를 대는 물) ─────────┐
 *             │ 창고 (x -22..-11)  │ 하역장 바닥 (노란 선 x -7..7, z 4..22) │ 컨테이너 더미 │
 *             │ 하역대 (x -10..-9) │   위로 갠트리 크레인 (다리 x ±9)        │ (x 11..21)    │
 *        z=0  └────── 입구 (x -8..8): 국도가 여기로 ──────────────────────────────────────┘
 * </pre>
 *
 * Real blocks for everything built (pad, warehouse, platform, crane, lamps, fences, the pier); display models for what
 * is moved about (the crane's hook, a forklift, a pallet, and every shipping container: one model in a few colours,
 * stacked, with barrier blocks inside so it is as solid as a block: models/dock_*.bbmodel in the pack's style), a sign at
 * the entrance and a floating name over the crane.
 */
public final class DockLayout {

    public enum Kind {
        LAND("육지"), PORT("항구"),
        /** a departure terminal (출발 터미널: /관리자 출발지 설치): where loads are inspected and sealed ({@link #terminal}) */
        TERMINAL("출발 터미널"),
        /** a tuning shop (튜닝샵: /관리자 튜닝샵 설치): a garage a vehicle is driven into to be tuned ({@link #tuning}) */
        TUNING("튜닝샵");

        public final String word;

        Kind(String word) {
            this.word = word;
        }

        /** A dock's kind by its word (육지, 항구; default 육지), or null: never a terminal. */
        public static Kind of(String s) {
            if (s == null) return LAND;
            for (Kind k : docks()) if (k.word.equals(s) || k.name().equalsIgnoreCase(s)) return k;
            return null;
        }

        /** The kinds of dock (not the terminal). */
        public static List<Kind> docks() {
            return List.of(LAND, PORT);
        }
    }

    /** A block (vanilla block data, as /setblock writes it). */
    public record Block(int x, int y, int z, String data) {}

    /** Fill down from y until something solid (the ground or the sea bed), at most depth blocks: foundations, pier posts. */
    public record Column(int x, int z, int fromY, int depth, String data) {}

    /** A box of blocks (inclusive). */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(double x, double y, double z) {
            return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
        }
    }

    /** A display model: economy:model/&lt;model&gt; at a point (its base centre), this big, turned yaw degrees (clockwise from above). */
    public record Prop(double x, double y, double z, String model, float scale, float yaw) {}

    /** A sign standing at a block, facing the way the road comes in, with up to four lines. */
    public record Sign(int x, int y, int z, List<String> lines) {}

    /**
     * @param clear   air first (whatever stood there)
     * @param columns foundations and posts
     * @param blocks  the build, bottom up
     * @param zones   the unloading zone (and a port's mooring water): a vehicle with any unit inside may sell
     * @param counter the 하역 창구 block: right-click = the sale menu
     * @param label   where the dock's name floats
     * @param bounds  everything it touches (claimed as server land)
     */
    public record Layout(Kind kind, List<Box> clear, List<Column> columns, List<Block> blocks, List<Prop> props, List<Sign> signs,
                         List<Box> zones, Block counter, double[] label, Box bounds) {}

    // the plan, in the dock's own coordinates
    public static final int HALF_WIDTH = 22, DEPTH = 40, PIER_END = 68, CLEAR = 22, FOUNDATION = 24;
    public static final Box ZONE = new Box(-7, -3, 4, 7, 6, 22);
    public static final Box MOORING_LEFT = new Box(-12, -6, 42, -4, 4, PIER_END), MOORING_RIGHT = new Box(4, -6, 42, 12, 4, PIER_END);

    private final Map<Long, Block> blocks = new LinkedHashMap<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<Prop> props = new ArrayList<>();
    private final List<Sign> signs = new ArrayList<>();
    private final List<Box> clear = new ArrayList<>();

    private DockLayout() {}

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    private void set(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), new Block(x, y, z, data));
    }

    private void fill(int x0, int y0, int z0, int x1, int y1, int z1, String data) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, data);
    }

    /** The name a dock gets on its sign and over its crane. */
    public static String title(String name) {
        return name + " 하역장";
    }

    public static Layout of(Kind kind, String name) {
        if (kind == Kind.TERMINAL) return terminal(name);
        if (kind == Kind.TUNING) return tuning(name);
        DockLayout d = new DockLayout();
        d.pad(kind);
        d.warehouse();
        d.platform();
        d.zone();
        d.crane();
        d.containers();
        d.lamps(kind);
        if (kind == Kind.PORT) d.pier();
        // the entrance sign, left of the gate, facing the road
        d.signs.add(new Sign(-9, 0, -1, List.of("[하역장]", name, "웅크리고 짐칸 우클릭", "또는 창구 우클릭")));
        d.set(-9, -1, -1, "minecraft:polished_andesite");
        List<Block> out = new ArrayList<>(d.blocks.values());
        out.sort(Comparator.comparingInt(Block::y));
        List<Box> zones = kind == Kind.PORT ? List.of(ZONE, MOORING_LEFT, MOORING_RIGHT) : List.of(ZONE);
        int maxZ = kind == Kind.PORT ? PIER_END : DEPTH;
        Box bounds = new Box(-HALF_WIDTH, -FOUNDATION - 2, -2, HALF_WIDTH, CLEAR, maxZ);
        return new Layout(kind, List.copyOf(d.clear), List.copyOf(d.columns), List.copyOf(out), List.copyOf(d.props), List.copyOf(d.signs),
                zones, new Block(-9, 0, 4, "minecraft:cartography_table"), new double[]{0, 17.5, 13}, bounds);
    }

    // ------------------------------------------------------------------------------------------
    // 출발 터미널
    // ------------------------------------------------------------------------------------------

    /** The terminal's plan: half its width, its depth, how high it is cleared. */
    public static final int T_HALF = 14, T_DEPTH = 28, T_CLEAR = 14;
    /** The inspection bay: a vehicle (any unit of it) or a 지게's wearer inside may be inspected. */
    public static final Box BAY = new Box(-4, -3, 6, 4, 6, 16);
    /** The 화물 검사소's counter (right-click: the inspection menu): by the bay, at the booth's window. */
    public static final Block T_COUNTER = new Block(-6, 0, 11, "minecraft:cartography_table");

    /** The name a terminal gets on its sign and over its bay. */
    public static String terminalTitle(String name) {
        return name + " 출발 터미널";
    }

    /**
     * A departure terminal (README "출발 터미널"), in the same coordinates as a dock (the admin's place, x right, z the way
     * they look, the gate at the front where the 국도 starts):
     *
     * <pre>
     *        z=28 ┌──────── 주차장 (흰 칸, z 19 ~ 27) ─────────────┐
     *             │ 화물 검사소 (x -13..-8)  │ 검사대 (노란 선 x -4..4, z 6..16, 위에 지붕) │
     *             │ 창구 (x -6) · 표지판      │ 출구 차단기 (x 5, z 4)                       │
     *        z=0  └────────── 출구 (x -6..6): 국도가 여기서 출발 ─────────┘
     * </pre>
     */
    static Layout terminal(String name) {
        DockLayout d = new DockLayout();
        d.clear.add(new Box(-T_HALF, 0, -1, T_HALF, T_CLEAR, T_DEPTH));
        for (int x = -T_HALF; x <= T_HALF; x++) {
            for (int z = -1; z <= T_DEPTH; z++) {
                boolean rim = Math.abs(x) == T_HALF || z == -1 || z == T_DEPTH;
                d.set(x, -1, z, rim ? "minecraft:stone_bricks"
                        : (Math.floorMod(x, 4) == 0 || Math.floorMod(z, 4) == 0) ? "minecraft:andesite" : "minecraft:polished_andesite");
                d.columns.add(new Column(x, z, -2, FOUNDATION, rim ? "minecraft:stone_bricks" : "minecraft:stone"));
            }
        }
        // a low wall round it, open at the exit (x -6..6 at the front)
        for (int x = -T_HALF; x <= T_HALF; x++) {
            if (Math.abs(x) > 6) d.set(x, 0, -1, "minecraft:stone_brick_wall");
            d.set(x, 0, T_DEPTH, "minecraft:stone_brick_wall");
        }
        for (int z = 0; z < T_DEPTH; z++) {
            d.set(-T_HALF, 0, z, "minecraft:stone_brick_wall");
            d.set(T_HALF, 0, z, "minecraft:stone_brick_wall");
        }
        d.inspectionBay();
        d.booth();
        // the parking apron behind: white bays four wide
        for (int x = -12; x <= 12; x += 4) for (int z = 20; z <= 26; z++) d.set(x, -1, z, "minecraft:white_concrete");
        for (int x = -12; x <= 12; x++) d.set(x, -1, 19, "minecraft:white_concrete");
        // lamp posts round it and at the exit
        for (int[] p : new int[][]{{-13, 0}, {13, 0}, {-13, 27}, {13, 27}, {-7, 0}, {7, 0}, {0, 27}, {13, 14}}) {
            d.fill(p[0], 0, p[1], p[0], 3, p[1], "minecraft:stone_brick_wall");
            d.set(p[0], 4, p[1], "minecraft:lantern");
        }
        // the sign at the exit, left of it, facing the road
        d.signs.add(new Sign(-8, 0, -1, List.of("[출발 터미널]", name, "검사대에 세우고", "창구 우클릭 = 화물 검사")));
        d.set(-8, -1, -1, "minecraft:polished_andesite");
        d.set(T_COUNTER.x(), T_COUNTER.y(), T_COUNTER.z(), T_COUNTER.data());
        d.set(T_COUNTER.x(), T_COUNTER.y() + 1, T_COUNTER.z(), "minecraft:lantern");
        List<Block> out = new ArrayList<>(d.blocks.values());
        out.sort(Comparator.comparingInt(Block::y));
        return new Layout(Kind.TERMINAL, List.copyOf(d.clear), List.copyOf(d.columns), List.copyOf(out), List.copyOf(d.props), List.copyOf(d.signs),
                List.of(BAY), T_COUNTER, new double[]{0, 9.5, 11}, new Box(-T_HALF, -FOUNDATION - 2, -2, T_HALF, T_CLEAR, T_DEPTH));
    }

    // ------------------------------------------------------------------------------------------
    // 튜닝샵
    // ------------------------------------------------------------------------------------------

    /** The tuning shop's plan: half the lot's width, the hall's half width, its depth, how high the lot is cleared. */
    public static final int S_LOT = 12, S_HALF = 9, S_DEPTH = 21, S_CLEAR = 10, S_WALL = 5, S_DOOR = 5;
    /** The hall's inside: a vehicle anywhere in it may be tuned. */
    public static final Box S_HALL = new Box(-S_HALF + 1, -3, 2, S_HALF - 1, 6, S_DEPTH - 1);
    /** The work bay in the middle of the hall, where the lift is. */
    public static final Box S_BAY = new Box(-3, -1, 7, 3, -1, 15);
    /** The shop's counter (right-click: the tuning menu): a smith's table just inside, left of the door. */
    public static final Block S_COUNTER = new Block(-7, 0, 4, "minecraft:smithing_table");

    /** The name a tuning shop gets on its sign and over its door. */
    public static String tuningTitle(String name) {
        return name + " 튜닝샵";
    }

    /**
     * A tuning shop (README "튜닝샵"), in the same coordinates as a dock (the admin's place, x right, z the way they
     * look: they stand in front of its door, looking in):
     *
     * <pre>
     *        z=21 ┌──────────── 뒷벽: 타이어 더미 · 공구 선반 ────────────┐
     *             │ 창 │   작업 베이 (노란·검정 띠 x -3..3, z 7..15,     │ 창 │
     *             │    │   가운데 리프트 레일, 위에 조명)                 │    │
     *             │ 작업대 (x -7, z 4) · 표지판                            │    │
     *        z=1  └─────── 문 (x -5..5, 높이 5: 차가 그대로 들어감) ───────┘
     *        z=-3      앞마당 (아스팔트, 흰 주차 선) ← 여기에 서서 안을 보고 설치
     * </pre>
     *
     * A hall 19 wide, 21 deep and 6 high inside a lot 25 wide: dark walls with a red band and a row of windows each
     * side, a door 11 wide and 5 high (the tallest vehicle is 3.25), a flat roof with lights set in the ceiling, a
     * checked floor with a work bay marked in hazard stripes. Anything a player could open is left out (no chests, no
     * anvils): the only block that does something is the counter.
     */
    static Layout tuning(String name) {
        DockLayout d = new DockLayout();
        d.clear.add(new Box(-S_LOT, 0, -3, S_LOT, S_CLEAR, S_DEPTH + 1));
        // the lot: asphalt with a kerb, foundations under all of it
        for (int x = -S_LOT; x <= S_LOT; x++) {
            for (int z = -3; z <= S_DEPTH + 1; z++) {
                boolean kerb = Math.abs(x) == S_LOT || z == S_DEPTH + 1;
                d.set(x, -1, z, kerb ? "minecraft:stone_bricks" : "minecraft:gray_concrete");
                d.columns.add(new Column(x, z, -2, FOUNDATION, kerb ? "minecraft:stone_bricks" : "minecraft:stone"));
            }
        }
        // the forecourt's parking lines, either side of the way in
        for (int x : new int[]{-11, -8, 8, 11}) for (int z = -2; z <= 0; z++) d.set(x, -1, z, "minecraft:white_concrete");
        for (int x = -4; x <= 4; x++) d.set(x, -1, 0, Math.floorMod(x, 2) == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete");
        d.hall();
        d.workBay();
        d.fittings();
        // lamp posts at the forecourt's corners
        for (int x : new int[]{-S_LOT, S_LOT}) {
            d.fill(x, 0, -3, x, 3, -3, "minecraft:polished_blackstone_wall");
            d.set(x, 4, -3, "minecraft:lantern");
        }
        // the sign, left of the door, facing whoever comes
        d.signs.add(new Sign(-7, 0, -1, List.of("[튜닝샵]", name, "차를 안에 세우고", "작업대 우클릭 = 튜닝")));
        d.set(S_COUNTER.x(), S_COUNTER.y(), S_COUNTER.z(), S_COUNTER.data());
        d.set(S_COUNTER.x(), S_COUNTER.y() + 1, S_COUNTER.z(), "minecraft:lantern");
        List<Block> out = new ArrayList<>(d.blocks.values());
        out.sort(Comparator.comparingInt(Block::y));
        return new Layout(Kind.TUNING, List.copyOf(d.clear), List.copyOf(d.columns), List.copyOf(out), List.copyOf(d.props), List.copyOf(d.signs),
                List.of(S_HALL), S_COUNTER, new double[]{0, S_WALL + 4.6, 0.5}, new Box(-S_LOT, -FOUNDATION - 2, -4, S_LOT, S_CLEAR, S_DEPTH + 1));
    }

    /** The hall: floor, four walls (the front one mostly door), windows, the roof with its lights and its fascia. */
    private void hall() {
        int x0 = -S_HALF, x1 = S_HALF, z0 = 1, z1 = S_DEPTH;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                // the floor: light tiles, a darker one every third, so a paint shows against it
                set(x, -1, z, Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0 ? "minecraft:gray_concrete" : "minecraft:light_gray_concrete");
                if (!wall) continue;
                boolean door = z == z0 && Math.abs(x) <= S_DOOR;
                boolean window = (x == x0 || x == x1) && ((z >= 5 && z <= 9) || (z >= 13 && z <= 17));
                for (int y = 0; y <= S_WALL; y++) {
                    if (door && y < S_DOOR) continue;                 // the way in: open, no shutter
                    // white panels in a black frame (the corners, the door's posts, the top course), a red band round the hall
                    boolean frame = y == S_WALL || ((x == x0 || x == x1) && (z == z0 || z == z1)) || (z == z0 && Math.abs(x) == S_DOOR + 1);
                    String b = window && (y == 2 || y == 3) ? "minecraft:glass"
                            : y == 0 ? "minecraft:polished_blackstone"
                            : frame ? "minecraft:black_concrete"
                            : y == 3 ? "minecraft:red_concrete" : "minecraft:white_concrete";
                    set(x, y, z, b);
                }
            }
        }
        // the roof: flat, a rim all round, lights in the ceiling over the bay and along the hall
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean rim = x == x0 || x == x1 || z == z0 || z == z1;
                boolean light = !rim && Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 3;
                set(x, S_WALL + 1, z, light ? "minecraft:sea_lantern" : rim ? "minecraft:polished_blackstone" : "minecraft:smooth_stone");
            }
        }
        // the fascia over the door: a red board with a chequered flag along its top (the name floats over it), a light at each end
        for (int x = -S_DOOR - 1; x <= S_DOOR + 1; x++) {
            boolean end = Math.abs(x) == S_DOOR + 1;
            set(x, S_WALL + 2, z0, end ? "minecraft:sea_lantern" : "minecraft:red_concrete");
            set(x, S_WALL + 3, z0, end ? "minecraft:polished_blackstone" : Math.floorMod(x, 2) == 0 ? "minecraft:white_concrete" : "minecraft:black_concrete");
        }
    }

    /** The work bay: hazard stripes round it, two lift rails down its middle. */
    private void workBay() {
        Box b = S_BAY;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                boolean edge = x == b.minX() || x == b.maxX() || z == b.minZ() || z == b.maxZ();
                if (edge) set(x, -1, z, Math.floorMod(x + z, 2) == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete");
                else set(x, -1, z, Math.abs(x) == 1 ? "minecraft:iron_block" : "minecraft:smooth_stone");
            }
        }
    }

    /** What stands along the walls: tyres, a bench, a rack of paint: nothing that opens. */
    private void fittings() {
        int back = S_DEPTH - 1, left = -S_HALF + 1, right = S_HALF - 1;
        // tyre stacks in the back corners (three, two and one high)
        for (int[] t : new int[][]{{right, back, 3}, {right - 1, back, 2}, {right, back - 1, 2}, {right - 2, back, 1}, {left, back, 3}, {left + 1, back, 2}, {left, back - 1, 1}}) {
            fill(t[0], 0, t[1], t[0], t[2] - 1, t[1], "minecraft:coal_block");
        }
        // a long bench along the back wall between them, cans of paint on it
        for (int x = -4; x <= 4; x++) set(x, 0, back, "minecraft:smooth_stone_slab[type=top]");
        String[] cans = {"red", "yellow", "lime", "light_blue", "purple", "white", "orange", "pink", "cyan"};
        for (int x = -4; x <= 4; x++) set(x, 1, back, "minecraft:" + cans[x + 4] + "_candle[candles=" + (1 + Math.floorMod(x, 3)) + "]");
        // a tool wall right of the door: bars and chains
        for (int z = 3; z <= 5; z++) {
            set(right, 1, z, "minecraft:iron_bars");
            set(right, 2, z, "minecraft:iron_bars");
        }
        set(right, 0, 3, "minecraft:polished_blackstone");
        set(right, 0, 4, "minecraft:polished_blackstone");
        set(right, 0, 5, "minecraft:polished_blackstone");
        // a spare set of wheels leaning by the bay
        set(5, 0, 11, "minecraft:coal_block");
        set(5, 0, 12, "minecraft:coal_block");
        set(-5, 0, 11, "minecraft:coal_block");
    }

    /** The bay: painted lines, a hatched stop line, a canopy on four posts with lights, a lift gate at its exit. */
    private void inspectionBay() {
        Box b = BAY;
        for (int x = b.minX(); x <= b.maxX(); x++) {
            set(x, -1, b.minZ(), "minecraft:yellow_concrete");
            set(x, -1, b.maxZ(), "minecraft:yellow_concrete");
            set(x, -1, b.minZ() - 1, Math.floorMod(x, 2) == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete");   // stop line
        }
        for (int z = b.minZ(); z <= b.maxZ(); z++) {
            set(b.minX(), -1, z, "minecraft:yellow_concrete");
            set(b.maxX(), -1, z, "minecraft:yellow_concrete");
        }
        // the canopy: posts just outside the lines, a roof 6 up (yellow fascia, lights in it)
        for (int x : new int[]{b.minX() - 1, b.maxX() + 1}) for (int z : new int[]{b.minZ(), b.maxZ()}) fill(x, 0, z, x, 5, z, "minecraft:gray_concrete");
        for (int x = b.minX() - 1; x <= b.maxX() + 1; x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                boolean edge = x == b.minX() - 1 || x == b.maxX() + 1 || z == b.minZ() || z == b.maxZ();
                set(x, 6, z, edge ? "minecraft:yellow_concrete" : "minecraft:smooth_stone");
            }
        }
        for (int x : new int[]{-2, 2}) for (int z : new int[]{9, 13}) set(x, 6, z, "minecraft:sea_lantern");
        // the lift gate at the exit end: a post and its arm, raised (red and white), beside the lane
        set(b.maxX() + 1, 0, b.minZ() - 2, "minecraft:polished_andesite");
        set(b.maxX() + 1, 1, b.minZ() - 2, "minecraft:stone_brick_wall");
        for (int y = 2; y <= 5; y++) set(b.maxX() + 1, y, b.minZ() - 2, y % 2 == 0 ? "minecraft:red_concrete" : "minecraft:white_concrete");
    }

    /** The 화물 검사소: a small office beside the bay, its window and counter facing it. */
    private void booth() {
        int x0 = -13, x1 = -8, z0 = 7, z1 = 15, top = 4;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (!wall) {
                    set(x, -1, z, "minecraft:smooth_stone");
                    continue;
                }
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = 0; y <= top; y++) {
                    String b = corner || y == 0 ? "minecraft:light_gray_concrete"
                            : y == top ? "minecraft:yellow_concrete"
                            : x == x1 && z >= 9 && z <= 13 && y <= 2 ? "minecraft:glass"
                            : "minecraft:white_concrete";
                    set(x, y, z, b);
                }
            }
        }
        fill(-11, 0, z0, -10, 1, z0, "minecraft:air");   // the door, to the exit side
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++) {
            boolean eave = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
            set(x, top + 1, z, eave ? "minecraft:gray_concrete" : "minecraft:dark_prismarine");
        }
        set(-10, top, 11, "minecraft:sea_lantern");
        set(-12, 0, 11, "minecraft:lectern");
    }

    // ------------------------------------------------------------------------------------------

    private void pad(Kind kind) {
        clear.add(new Box(-HALF_WIDTH, 0, -1, HALF_WIDTH, CLEAR, DEPTH));
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            for (int z = -1; z <= DEPTH; z++) {
                boolean rim = x == -HALF_WIDTH || x == HALF_WIDTH || z == DEPTH || z == -1;
                // concrete slabs in a 4x4 grid of seams
                String floor = rim ? "minecraft:stone_bricks"
                        : (Math.floorMod(x, 4) == 0 || Math.floorMod(z, 4) == 0) ? "minecraft:andesite" : "minecraft:polished_andesite";
                set(x, -1, z, floor);
                columns.add(new Column(x, z, -2, FOUNDATION, rim ? "minecraft:stone_bricks" : "minecraft:stone"));
            }
        }
        // a low wall round it: open at the entrance (x -8..8 at the front) and, for a port, along the quay
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            if (Math.abs(x) > 8) set(x, 0, -1, "minecraft:stone_brick_wall");
            if (kind == Kind.LAND) set(x, 0, DEPTH, "minecraft:stone_brick_wall");
        }
        for (int z = 0; z < DEPTH; z++) {
            set(-HALF_WIDTH, 0, z, "minecraft:stone_brick_wall");
            set(HALF_WIDTH, 0, z, "minecraft:stone_brick_wall");
        }
        if (kind == Kind.PORT) {
            // the quay edge: bollards along it, a yellow safety line
            for (int x = -HALF_WIDTH + 1; x < HALF_WIDTH; x++) set(x, -1, DEPTH - 1, "minecraft:yellow_concrete");
            for (int x = -20; x <= 20; x += 8) set(x, 0, DEPTH, "minecraft:polished_blackstone_wall");
        }
    }

    private void warehouse() {
        int x0 = -21, x1 = -11, z0 = 8, z1 = 38, top = 7;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                if (!wall) {
                    set(x, -1, z, "minecraft:smooth_stone");
                    continue;
                }
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = 0; y <= top; y++) {
                    String b;
                    if (corner) b = "minecraft:gray_concrete";
                    else if (y <= 1) b = "minecraft:gray_concrete";
                    else if (y == 6) b = "minecraft:cyan_terracotta";
                    else if ((y == 4 || y == 5) && (x == x0 || x == x1 ? Math.floorMod(z, 4) == 2 : Math.floorMod(x, 4) == 0)) b = "minecraft:glass";
                    else b = Math.floorMod(x + z, 2) == 0 ? "minecraft:light_gray_concrete" : "minecraft:white_concrete";
                    set(x, y, z, b);
                }
            }
        }
        // roof: a shallow ridge along the length, dark slabs with a grey eave
        for (int x = x0 - 1; x <= x1 + 1; x++) {
            int rise = Math.min(x - (x0 - 1), (x1 + 1) - x) / 3;
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean eave = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
                set(x, top + 1 + rise, z, eave ? "minecraft:gray_concrete" : "minecraft:dark_prismarine");
                for (int y = top + 1; y < top + 1 + rise; y++) if (eave || z == z0 || z == z1) set(x, y, z, "minecraft:light_gray_concrete");
            }
        }
        // two big doors onto the platform (east wall, facing the zone) and a sign of the trade over them
        for (int[] d : new int[][]{{12, 16}, {26, 30}}) {
            fill(x1, 0, d[0], x1, 4, d[1], "minecraft:air");
            fill(x1, 5, d[0] - 1, x1, 5, d[1] + 1, "minecraft:yellow_concrete");
        }
        // inside: stacked crates, sacks, a lamp
        for (int z = z0 + 2; z <= z1 - 2; z += 3) {
            set(x0 + 1, 0, z, "minecraft:spruce_planks");
            set(x0 + 1, 1, z, Math.floorMod(z, 2) == 0 ? "minecraft:oak_planks" : "minecraft:hay_block");
            set(x0 + 2, 0, z, "minecraft:brown_wool");
        }
        for (int z = z0 + 4; z <= z1 - 4; z += 8) set((x0 + x1) / 2, top, z, "minecraft:sea_lantern");
    }

    private void platform() {
        // the loading platform along the warehouse's door side: one block up, yellow edge
        for (int z = 8; z <= 38; z++) {
            set(-10, 0, z, "minecraft:polished_andesite");
            set(-9, 0, z, "minecraft:yellow_terracotta");
        }
        // crates waiting on it
        for (int z : new int[]{10, 11, 20, 23, 24, 33, 35}) {
            set(-10, 1, z, z % 2 == 0 ? "minecraft:spruce_planks" : "minecraft:oak_planks");
            if (z == 23 || z == 33) set(-10, 2, z, "minecraft:spruce_planks");
        }
        // steps up at both ends
        set(-10, 0, 7, "minecraft:polished_andesite_slab");
        set(-9, 0, 7, "minecraft:polished_andesite_slab");
        // the 하역 창구 (counter) at the zone's front corner, a lamp on it
        set(-9, 0, 4, "minecraft:cartography_table");
        set(-9, 1, 4, "minecraft:lantern");
        set(-10, 0, 4, "minecraft:polished_andesite");
    }

    private void zone() {
        // the unloading zone: a yellow border painted on the floor, white lines between three bays, lights at its corners
        Box z = ZONE;
        for (int x = z.minX(); x <= z.maxX(); x++) {
            set(x, -1, z.minZ(), "minecraft:yellow_concrete");
            set(x, -1, z.maxZ(), "minecraft:yellow_concrete");
        }
        for (int zz = z.minZ(); zz <= z.maxZ(); zz++) {
            set(z.minX(), -1, zz, "minecraft:yellow_concrete");
            set(z.maxX(), -1, zz, "minecraft:yellow_concrete");
            if (zz > z.minZ() + 1 && zz < z.maxZ() - 1 && Math.floorMod(zz, 3) != 0) {
                set(-2, -1, zz, "minecraft:white_concrete");
                set(2, -1, zz, "minecraft:white_concrete");
            }
        }
        // the hatched entry strip in front of it
        for (int x = z.minX(); x <= z.maxX(); x++) set(x, -1, z.minZ() - 1, Math.floorMod(x, 2) == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete");
        for (int[] c : new int[][]{{z.minX(), z.minZ()}, {z.maxX(), z.minZ()}, {z.minX(), z.maxZ()}, {z.maxX(), z.maxZ()}})
            set(c[0], -1, c[1], "minecraft:sea_lantern");
    }

    private void crane() {
        // a gantry over the zone: four yellow legs just outside it (x ±8), beams across at the top, a trolley with its hook
        int top = 14;
        for (int x : new int[]{-8, 8}) {
            for (int z : new int[]{10, 16}) {
                fill(x, 0, z, x, top - 1, z, "minecraft:yellow_concrete");
                set(x, 0, z, "minecraft:gray_concrete");   // the wheel truck at its foot
                set(x, 0, z - 1, "minecraft:gray_concrete");
                set(x, 0, z + 1, "minecraft:gray_concrete");
            }
            fill(x, top, 10, x, top, 16, "minecraft:yellow_concrete");
            // diagonal braces
            for (int k = 1; k <= 4; k++) set(x, top - k, 10 + k, "minecraft:yellow_terracotta");
        }
        for (int z : new int[]{10, 16}) fill(-8, top, z, 8, top, z, "minecraft:yellow_concrete");
        // rails for the trolley and the trolley
        for (int x = -8; x <= 8; x++) {
            set(x, top, 12, "minecraft:gray_concrete");
            set(x, top, 14, "minecraft:gray_concrete");
        }
        fill(-1, top - 1, 12, 1, top - 1, 14, "minecraft:gray_concrete");
        // the operator's cab on top, at the right
        fill(5, top + 1, 11, 8, top + 2, 15, "minecraft:yellow_concrete");
        fill(5, top + 2, 12, 5, top + 2, 14, "minecraft:glass");
        set(8, top + 3, 13, "minecraft:red_concrete");   // a warning light
        // the cable down to the hook (a display model)
        for (int y = top - 2; y >= 9; y--) set(0, y, 13, "minecraft:iron_chain");
        props.add(new Prop(0.5, 6.5, 13.5, "dock_crane_hook", 2f, 0));
    }

    /**
     * The container model's colours (models/docks.py: dock_container_&lt;colour&gt;, one model, its paint ramps differing), in
     * the order the stacks take them.
     */
    public static final List<String> CONTAINER_COLOURS = List.of("red", "blue", "green", "yellow", "white", "teal");
    /** A container (the model, at real size): 2 wide, 6 long, 2.5 high; stacked 2.5 apart. */
    public static final int CONTAINER_WIDTH = 2, CONTAINER_LENGTH = 6;
    public static final double CONTAINER_HEIGHT = 2.5;

    /**
     * A container: its footprint's corner block (x0, z0; along z, or along x when turned), how many are stacked there and
     * the first one's colour (the stack takes the next ones up).
     */
    public record Stack(int x0, int z0, int high, boolean turned, int colour) {
        public int width() {
            return turned ? CONTAINER_LENGTH : CONTAINER_WIDTH;
        }

        public int length() {
            return turned ? CONTAINER_WIDTH : CONTAINER_LENGTH;
        }

        /** How high its barriers go: every block the stack's models reach into. */
        public int barrierHeight() {
            return (int) Math.ceil(high * CONTAINER_HEIGHT - 1e-9);
        }
    }

    /** The container yard: four rows of stacks 1 to 3 high, and one set down by the gate, turned across. */
    public static final List<Stack> STACKS = List.of(
            new Stack(11, 6, 3, false, 0), new Stack(11, 14, 2, false, 3), new Stack(11, 22, 1, false, 5), new Stack(11, 30, 2, false, 2),
            new Stack(15, 6, 2, false, 1), new Stack(15, 14, 3, false, 4), new Stack(15, 22, 2, false, 0), new Stack(15, 30, 1, false, 3),
            new Stack(19, 6, 1, false, 5), new Stack(19, 14, 2, false, 2), new Stack(19, 22, 3, false, 1), new Stack(19, 30, 2, false, 4),
            new Stack(14, 1, 1, true, 1));

    private void containers() {
        // shipping containers: all the same 3D model in a few colours, stacked 2.5 apart; inside each, barrier blocks over
        // its whole footprint up to its top, so nobody walks or drives through one
        for (Stack st : STACKS) {
            double cx = st.x0() + st.width() / 2.0, cz = st.z0() + st.length() / 2.0;
            for (int level = 0; level < st.high(); level++) {
                String colour = CONTAINER_COLOURS.get((st.colour() + level) % CONTAINER_COLOURS.size());
                props.add(new Prop(cx, level * CONTAINER_HEIGHT, cz, "dock_container_" + colour, 2f, st.turned() ? 90 : 0));
            }
            fill(st.x0(), 0, st.z0(), st.x0() + st.width() - 1, st.barrierHeight() - 1, st.z0() + st.length() - 1, BARRIER);
        }
        // a forklift and a pallet by the warehouse
        props.add(new Prop(-5.5, 0, 31.5, "dock_forklift", 2f, 200));
        props.add(new Prop(-5.5, 0, 26.5, "dock_pallet", 2f, 0));
    }

    public static final String BARRIER = "minecraft:barrier";

    /**
     * Where the containers stood when they were blocks (docks built before they became models: 3 wide, 3 high, 7 long
     * stacks): what /관리자 하역장 다시꾸미기 clears (where the new layout puts nothing).
     */
    public static List<int[]> legacyContainerCells() {
        int[][] stacks = {{11, 6, 3}, {11, 14, 2}, {11, 22, 1}, {11, 30, 2}, {15, 6, 2}, {15, 14, 3}, {15, 22, 2}, {15, 30, 1},
                {19, 6, 1}, {19, 14, 2}, {19, 22, 3}, {19, 30, 2}};
        List<int[]> out = new ArrayList<>();
        for (int[] st : stacks)
            for (int x = st[0]; x <= st[0] + 2; x++)
                for (int y = 0; y < st[2] * 3; y++)
                    for (int z = st[1]; z <= st[1] + 6; z++) out.add(new int[]{x, y, z});
        return out;
    }

    private void lamps(Kind kind) {
        // lamp posts round the pad (every 10 blocks along its edges) and at the gate
        int[][] posts = {{-21, 0}, {21, 0}, {-21, 20}, {21, 20}, {-8, 0}, {8, 0}, {-8, 39}, {0, 39}, {8, 39}, {21, 39}, {-21, 39}};
        for (int[] p : posts) {
            if (kind == Kind.PORT && p[1] == 39 && Math.abs(p[0]) <= 8) continue;   // the quay stays open to the pier
            fill(p[0], 0, p[1], p[0], 3, p[1], "minecraft:stone_brick_wall");
            set(p[0], 4, p[1], "minecraft:lantern");
        }
        // floodlights on the crane's legs
        for (int x : new int[]{-8, 8}) set(x, 11, 13, "minecraft:sea_lantern");
    }

    private void pier() {
        // a timber pier straight out from the quay: deck, posts down to the sea bed, rails with gaps where boats tie up
        for (int z = DEPTH + 1; z <= PIER_END; z++) {
            for (int x = -3; x <= 3; x++) set(x, -1, z, Math.abs(x) == 3 ? "minecraft:stripped_spruce_wood" : "minecraft:spruce_planks");
            boolean mooring = Math.floorMod(z - DEPTH, 10) >= 6;   // three gaps a side, 4 long
            for (int x : new int[]{-3, 3}) {
                if (Math.floorMod(z, 4) == 0) columns.add(new Column(x, z, -2, FOUNDATION, "minecraft:spruce_log"));
                if (!mooring) set(x, 0, z, "minecraft:spruce_fence");
                else if (Math.floorMod(z - DEPTH, 10) == 6) set(x, 0, z, "minecraft:polished_blackstone_wall");   // a bollard
                if (Math.floorMod(z, 8) == 0 && !mooring) set(x, 1, z, "minecraft:lantern");
            }
        }
        for (int x = -3; x <= 3; x++) set(x, 0, PIER_END, "minecraft:spruce_fence");
        set(0, 0, PIER_END, "minecraft:polished_blackstone_wall");
        set(0, 1, PIER_END, "minecraft:lantern");
        // a quay wall face under the pad's front (down to the sea bed: the pad's columns do it)
        clear.add(new Box(-3, 0, DEPTH + 1, 3, 8, PIER_END));
    }
}
