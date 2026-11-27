package dev.stsv.economy.store;

import dev.stsv.economy.hwatu.hall.HallLayout;
import dev.stsv.economy.hwatu.hall.HallLayout.Block;
import dev.stsv.economy.hwatu.hall.HallLayout.Box;
import dev.stsv.economy.hwatu.hall.HallLayout.Column;
import dev.stsv.economy.hwatu.hall.HallLayout.Label;
import dev.stsv.economy.hwatu.hall.HallLayout.Point;
import dev.stsv.economy.hwatu.hall.HallLayout.Prop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The GS26 편의점 (docs/plans/편의점_기획안.md §2, §15) that /관리자 편의점 건설 puts where the admin stands, looking the way
 * they look (the store in front of them, its glass front and door facing them). Pure (unit-tested); {@link StoreBuilder}
 * builds it. Coordinates as {@link HallLayout}'s: x to the admin's right, z forward, y up from their feet (y = -1 the ground
 * block); block states written for a store facing south ({@link HallLayout#rotate} turns them), points in block space.
 *
 * <pre>
 *  z=15 ─────────────── back wall (창고 문 x 5) ───────────────
 *  z=14  [박스]  ·  [냉장 x-1..0][냉장 1..2][냉장 3..4]   [배송 상자 x 5]
 *  z=13  대걸레 ·         뒤 통로 (BL BM BR)
 *  z=10..12 옷걸이 x-5 · 폐기 x-3 │ 줄 x-2 │ [라면 x0] │   │ [도시락 x3] │
 *  z=9   전자레인지 x-3 (문은 근무 통로 쪽) │  ML    MM      MR
 *  z=6..8 담배 x-5 · 일하는 자리 x-4 · [계산대 x-3] · 손님 x-2 │ [과자 x0] │ │ [생활 x3] │
 *  z=5   온수기 x-3               FL         FM          FR
 *  z=4   ·                [시식대 x-2..0] [냉동고 1..2]   문 안 (x 4..5)
 *  z=3  ════ 통유리 · GS26 간판 (위) ════════════════ 문 (x 4..5) ═══
 *  z=0..2 보도: 파라솔 테이블 x-6..-4 · x-2..0, 휴지통 x 2, 손님이 오는 곳 (x 5)
 * </pre>
 *
 * Shoppers walk only between the named {@link Node}s along the {@link #edges} (no pathfinding in the world).
 */
public final class StoreLayout {

    /** A point shoppers walk between (x, z in block space, feet on the floor), the way they face when they stop there. */
    public record Node(String id, double x, double z, float yaw) {}

    /**
     * A piece of furniture: its item (economy:store_*), what it does in the store (role), the blocks it should fill (x0..x1,
     * z0..z1 inclusive, from floor y), and the way its front should face (local yaw). The builder turns and moves the real
     * piece (its cells from items/store.yml) so it fills that box.
     */
    public record Piece(String role, String item, int x0, int z0, int x1, int z1, int y, float yaw) {
        public int width() {
            return x1 - x0 + 1;
        }

        public int depth() {
            return z1 - z0 + 1;
        }
    }

    /** Where a piece's anchor block goes (local), turned to yaw; exact = its cells fill the box. */
    public record Fit(int x, int y, int z, float yaw, boolean exact) {}

    /**
     * Everything.
     *
     * @param pieces  the furniture, by role
     * @param nodes   the walk graph's points by id
     * @param edges   node id -> its neighbours
     * @param spots   a place (snack, noodle, daily, meal, fridge, freezer) -> the nodes a shopper browses it from
     * @param queue   the line: 0 at the counter (facing it), then back along the lane
     * @param slots   where the things put on the counter lie (the customer's mat), in order
     * @param scanned where they slide when scanned (the worker's edge)
     * @param screen  the till's screen (total, change): the middle of the POS monitor's text area
     * @param customerScreen the customer display on its pole (the total, facing them)
     * @param card    a card customer's card on the counter; terminal: the card terminal (tapped); cardKey: the [카드] key
     *                (the POS keys lie on the counter's worker edge: {@link #posKey})
     * @param inside  the shop floor (where the worker works, shoppers browse)
     */
    public record Layout(List<Box> clear, List<Column> columns, List<Block> blocks, List<Prop> props, List<Label> labels, List<Piece> pieces,
                         Map<String, Node> nodes, Map<String, List<String>> edges, Map<String, List<String>> spots, List<Node> queue,
                         List<Point> slots, List<Point> scanned, Point screen, Point customerScreen, Point card, Point terminal, Point cardKey,
                         Map<Integer, Point> changeButtons, Point give, Point clear2, Point oven, List<Point> boxes, Point worker, Box inside,
                         Box bounds) {

        public Piece piece(String role) {
            for (Piece p : pieces) if (p.role().equals(role)) return p;
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------ the plan

    /** The lot (x ±LOT, z 0 .. BACK + 1), the store's walls (x ±HALF, z FRONT .. BACK), roof y ROOF. */
    public static final int LOT = 8, HALF = 6, FRONT = 3, BACK = 15, ROOF = 3, CLEAR = 8, FOUNDATION = 10;
    /** The counter's mat over the floor (blocks: 15.25 px, models/store.py). */
    public static final double TOP = 15.25 / 16;
    /** The door (x DOOR0 .. DOOR1 in the front wall). */
    public static final int DOOR0 = 4, DOOR1 = 5;
    /** The places a shopper fetches from (the counter's things are added at the till). */
    public static final List<String> SHELVES = List.of("snack", "noodle", "daily", "meal");

    private static final String AIR = "minecraft:air";

    private final Map<Long, Block> blocks = new LinkedHashMap<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<Prop> props = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Piece> pieces = new ArrayList<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> edges = new LinkedHashMap<>();
    private final Map<String, List<String>> spots = new LinkedHashMap<>();

    private StoreLayout() {}

    private static final class Holder {
        static final Layout LAYOUT = build();
    }

    /** The store (the same object every time). */
    public static Layout of() {
        return Holder.LAYOUT;
    }

    /** Which piece a browse spot stands at (a spot whose piece a smaller store does not have is not walked to). */
    static final Map<String, String> SPOT_PIECE = Map.ofEntries(Map.entry("snack_a", "shelf_snack"), Map.entry("snack_b", "shelf_snack"),
            Map.entry("noodle_a", "shelf_noodle"), Map.entry("noodle_b", "shelf_noodle"), Map.entry("daily_a", "shelf_daily"),
            Map.entry("daily_b", "shelf_daily"), Map.entry("meal_a", "shelf_meal"), Map.entry("meal_b", "shelf_meal"),
            Map.entry("fridge_a", "fridge_0"), Map.entry("fridge_b", "fridge_1"), Map.entry("fridge_c", "fridge_2"), Map.entry("freezer_a", "freezer"));

    private static final Map<Set<String>, Layout> SMALLER = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The store without some of its furniture (a 내 가게 등급, 기획안 §13: the 골목점 has two shelves and one fridge): the same
     * building and walk, those pieces left out, nobody browsing where they would stand.
     */
    public static Layout of(Set<String> without) {
        if (without == null || without.isEmpty()) return of();
        return SMALLER.computeIfAbsent(Set.copyOf(without), w -> {
            Layout l = of();
            List<Piece> pieces = new ArrayList<>();
            for (Piece p : l.pieces()) if (!w.contains(p.role())) pieces.add(p);
            Map<String, List<String>> spots = new LinkedHashMap<>();
            l.spots().forEach((place, ids) -> {
                List<String> kept = new ArrayList<>();
                for (String id : ids) if (!w.contains(SPOT_PIECE.getOrDefault(id, ""))) kept.add(id);
                if (!kept.isEmpty()) spots.put(place, List.copyOf(kept));
            });
            return new Layout(l.clear(), l.columns(), l.blocks(), l.props(), l.labels(), List.copyOf(pieces), l.nodes(), l.edges(), Map.copyOf(spots),
                    l.queue(), l.slots(), l.scanned(), l.screen(), l.customerScreen(), l.card(), l.terminal(), l.cardKey(), l.changeButtons(), l.give(),
                    l.clear2(), l.oven(), l.boxes(), l.worker(), l.inside(), l.bounds());
        });
    }

    /** The pavement's ends a customer comes from. */
    public static final String STREET_L = "street_l", STREET_R = "street_r";
    /** Where the eat bar's customers walk to (then they sit on the stool in front, facing the window), seat 0 .. 2. */
    public static final List<String> EAT = List.of("eat_0", "eat_1", "eat_2");
    /** The aisle spots a drink can be spilt on. */
    public static final List<String> AISLE = List.of("fm", "mm", "ml", "mr", "bm");

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    private void set(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), new Block(x, y, z, data.startsWith("minecraft:") ? data : "minecraft:" + data));
    }

    private void fill(int x0, int y0, int z0, int x1, int y1, int z1, String data) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, data);
    }

    private static Layout build() {
        StoreLayout d = new StoreLayout();
        d.ground();
        d.shell();
        d.inside();
        d.outside();
        d.walk();
        List<Block> out = new ArrayList<>(d.blocks.values());
        out.sort(Comparator.comparingInt(Block::y));
        // the counter (store_counter at x -3, z 6..8, its front to the customers at +x; tools/assetgen/models/store.py): a model
        // px (mx, my, mz) is local (8 - mx / 16, my / 16, -2 - mz / 16). Its top 15 px up, the customer's mat on its +z end
        // (x -2.75 .. -2.125, z 7.9 .. 8.9), the customer display's screen at (-2.09, 1.37, 7.70) facing them, the POS monitor
        // facing the worker (POS_*), the cash tray (TRAY), the card terminal at (-2.38, z 6.69)
        double y = TOP;
        List<Point> slots = List.of(new Point(-2.3, y, 8.05, 90), new Point(-2.3, y, 8.4, 90), new Point(-2.3, y, 8.75, 90),
                new Point(-2.6, y, 8.22, 90), new Point(-2.6, y, 8.6, 90));
        List<Point> scanned = new ArrayList<>();
        for (int i = 0; i < 5; i++) scanned.add(new Point(SCANNED_X, y, SCANNED_Z + i * SCANNED_GAP, 270));
        Map<Integer, Point> change = new LinkedHashMap<>();
        int[] top = {50000, 10000, 5000}, low = {1000, 500, 100};
        for (int i = 0; i < 3; i++) {
            change.put(top[i], posKey(2 - i, 1));
            change.put(low[i], posKey(2 - i, 0));
        }
        return new Layout(List.of(new Box(-LOT, 0, 0, LOT, CLEAR, BACK + 1)), List.copyOf(d.columns), List.copyOf(out), List.copyOf(d.props),
                List.copyOf(d.labels), List.copyOf(d.pieces), Map.copyOf(d.nodes), Map.copyOf(d.edges), Map.copyOf(d.spots),
                List.of(new Node("q0", -1.5, 7.5, 270), new Node("q1", -1.5, 8.7, 180), new Node("q2", -1.5, 9.9, 180), new Node("q3", -1.5, 11.1, 180),
                        new Node("q4", -1.5, 12.3, 180), new Node("q5", -1.5, 13.5, 180)),
                slots, List.copyOf(scanned),
                new Point(POS_X, POS_Y, POS_Z, 270),
                new Point(-2.09, 1.32, 7.70, 90),
                new Point(CARD_X, y + 0.005, CARD_Z, 0),
                new Point(-2.38, y + 0.05, 6.69, 270),
                posKey(3, 0),
                change,
                posKey(3, 0),
                posKey(3, 1),
                new Point(-2.5, 1.95, 9.5, 270),
                List.of(new Point(5.5, 0, 14.5, 180), new Point(5.5, 0, 13.5, 180), new Point(5.5, 0.5, 14.5, 180), new Point(5.5, 0.5, 13.5, 180),
                        new Point(5.5, 1.0, 14.5, 180)),
                new Point(-3.5, 0, 7.5, 90),
                new Box(-HALF + 1, 0, FRONT + 1, HALF - 1, ROOF - 1, BACK - 1),
                new Box(-LOT - 1, -FOUNDATION - 2, -1, LOT + 1, CLEAR, BACK + 2));
    }

    // ------------------------------------------------------------------------------------------ on the counter

    /**
     * The POS keys (거스름돈 · 건네기 · 다시 · 카드): a little keyboard lying on the counter's worker edge under the POS monitor,
     * KEY_TILT degrees up from flat (its far edge raised), so the worker looks down onto it and nothing stands between them
     * and the customer or the monitor; from the customers' side only its thin edge shows. Column 0 is the worker's right
     * (z KEY_Z), each KEY_GAP further left; row 0 is the near one, row 1 KEY_ROW up the slope. KEY_SCALE: store_btn_* are
     * 12 × 6 px at 1.
     */
    public static final double KEY_TILT = 35, KEY_GAP = 0.34, KEY_ROW = 0.185;
    public static final double KEY_X = -3.06, KEY_Y = 1.02, KEY_Z = 6.74;
    public static final float KEY_SCALE = 0.4f;

    /** Half the sizes of a key's click box (along the row, up, across the rows): a 12 × 6 px plate lying back. */
    public static double[] keyBox() {
        double t = Math.toRadians(KEY_TILT), wide = 12 / 16.0 * KEY_SCALE, tall = 6 / 16.0 * KEY_SCALE;
        return new double[]{wide / 2, tall / 2 * Math.sin(t) + 0.015, tall / 2 * Math.cos(t) + 0.01};
    }

    /** Notes and coins on a pile lie this much higher each (blocks). */
    public static final double PILE = 0.004;

    /** The key in column c (0 .. 3), row r (0 near, 1 far). */
    public static Point posKey(int c, int r) {
        double t = Math.toRadians(KEY_TILT);
        return new Point(KEY_X + r * KEY_ROW * Math.cos(t), KEY_Y + r * KEY_ROW * Math.sin(t), KEY_Z + c * KEY_GAP, 270);
    }

    /**
     * The POS monitor's text area (items/store.yml's header: the model's pos_screen under its green band, 7 px wide, 3.9 px
     * up its face, tipped back POS_TILT degrees so its top leans away from the worker): its middle, its size (blocks), the
     * size of the text on it. The till's total and change are written there (a fixed text display, 0.01 off the face).
     */
    public static final double POS_X = -2.658, POS_Y = 1.371, POS_Z = 7.1875, POS_TILT = 22.5, POS_WIDE = 0.4375, POS_TALL = 0.244;
    public static final float POS_TEXT = 0.3f;
    /** The cash tray on the customers' side (the model's cash_tray: the middle of its dish, 15.5 px up). */
    public static final double TRAY_X = -2.266, TRAY_Y = 15.5 / 16, TRAY_Z = 7.1875;
    /** A card customer's card: on the clear top between the card terminal and the cash tray. */
    public static final double CARD_X = -2.3, CARD_Z = 6.905;
    /** Money and cards lie on the counter at this size (the goods at 0.75). */
    public static final float MONEY = 0.55f;
    /** Scanned things: a row along the worker's edge past the keys, this far apart. */
    public static final double SCANNED_X = -2.9, SCANNED_Z = 8.03, SCANNED_GAP = 0.23;
    /** The ID card on the worker's edge of the counter, tipped up to them (degrees). */
    public static final double ID_X = -2.85, ID_Z = 7.72, ID_TILT = 30;
    /** The green answer buttons of the customer at the counter: between them and the worker, over the mat. */
    public static final double ASK_X = -2.45, ASK_Y = 1.45, ASK_Z = 7.85, ASK_STEP = 0.3;
    /** Over a customer in the aisle the buttons start here over their feet (their name tag is hidden meanwhile). */
    public static final double ASK_HEAD = 2.15;
    /** A cup left on the eat bar (its top: 14 px up, z 4.0 .. 4.25), seat 0 at CUP_X; the cup's size. */
    public static final double CUP_X = -1.5, CUP_Y = 14.0 / 16, CUP_Z = 4.13;
    public static final float CUP_SCALE = 0.5f;
    /** A customer sitting at the eat bar: on the stool (x -1.5 + seat). */
    public static final double STOOL_Y = 0.5, STOOL_Z = 4.6;
    /** Where the food stands inside the microwave (its door to the worker's lane). */
    public static final double OVEN_X = -2.52, OVEN_Y = 0.92, OVEN_Z = 9.6;
    /** A visit's thing left on the counter: by the customer display's pole. */
    public static final double PROP_X = -2.3, PROP_Z = 7.52;
    /**
     * A figure seen outside from the till (a visit's last beat) and the thing a visit leaves on a parasol table: the left
     * table (x -6 .. -4), which the worker sees past the kettle's left through the glass (the right one is behind the kettle).
     */
    public static final double FIGURE_X = -4.5, FIGURE_Z = 2.3, PARASOL_X = -4.5, PARASOL_Y = 0.78, PARASOL_Z = 1.5;
    /** Where a customer whose food is in the microwave waits (the node "warm"), off the line. */
    public static final String WARM = "warm";

    // ------------------------------------------------------------------------------------------ ground and shell

    private void ground() {
        for (int x = -LOT; x <= LOT; x++) {
            for (int z = 0; z <= BACK + 1; z++) {
                boolean walk = z < FRONT;   // the pavement in front
                boolean shop = Math.abs(x) <= HALF && z >= FRONT && z <= BACK;
                String g;
                if (walk) g = Math.floorMod(x, 3) == 0 ? "smooth_stone" : "polished_andesite";
                else if (shop) g = Math.floorMod(x + z, 2) == 0 ? "white_concrete" : "light_gray_concrete";
                else g = "grass_block";
                set(x, -1, z, g);
                columns.add(new Column(x, z, -2, FOUNDATION, shop || walk ? "minecraft:stone" : "minecraft:dirt"));
            }
        }
        // the kerb along the road side
        for (int x = -LOT; x <= LOT; x++) set(x, -1, 0, "stone_bricks");
    }

    private void shell() {
        // side and back walls: white, a green stripe under the roof
        for (int z = FRONT; z <= BACK; z++) {
            for (int x : new int[]{-HALF, HALF}) for (int y = 0; y < ROOF; y++) set(x, y, z, y == ROOF - 1 ? "green_concrete" : "white_concrete");
        }
        for (int x = -HALF; x <= HALF; x++) for (int y = 0; y < ROOF; y++) set(x, y, BACK, y == ROOF - 1 ? "green_concrete" : "white_concrete");
        // the glass front, the door opening (2 wide, 2 high), green posts
        for (int x = -HALF + 1; x <= HALF - 1; x++) for (int y = 0; y < ROOF; y++) set(x, y, FRONT, "glass");
        for (int x = DOOR0; x <= DOOR1; x++) {
            set(x, 0, FRONT, AIR);
            set(x, 1, FRONT, AIR);
            set(x, 2, FRONT, "green_concrete");
        }
        for (int x : new int[]{-HALF, HALF}) for (int y = 0; y < ROOF; y++) set(x, y, FRONT, "green_concrete");
        // the roof, lights let into it; the fascia over the front (the sign hangs on it)
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = FRONT; z <= BACK; z++) {
                boolean lamp = Math.abs(x) < HALF && z > FRONT && z < BACK && Math.floorMod(x + 5, 3) == 1 && Math.floorMod(z - 4, 3) == 1;
                set(x, ROOF, z, lamp ? "sea_lantern" : "white_concrete");
            }
            set(x, ROOF, FRONT, "green_concrete");
            set(x, ROOF + 1, FRONT, Math.abs(x) == HALF ? "green_concrete" : "white_concrete");
        }
        // the back door to the stockroom (shut, its leaf on the wall's inner face), the door bell over the front door (inside)
        set(DOOR1, 0, BACK, "iron_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        set(DOOR1, 1, BACK, "iron_door[facing=south,half=upper,hinge=left,open=false,powered=false]");
        set(DOOR0, 2, FRONT + 1, "bell[attachment=ceiling,facing=north,powered=false]");
        // the sign (lit, GS26: an item display on the fascia), the 24시 sticker on the glass
        piece("sign", "economy:store_sign", -2, FRONT - 1, 2, FRONT - 1, ROOF, 180);
        labels.add(new Label(2.5, 1.55, FRONT - 0.03, 180, "<#1f9d55><b>24시</b></#1f9d55><dark_gray> 연중무휴", 0.6f, 0xC0FFFFFF));
        labels.add(new Label(DOOR1 + 0.5, 2.15, BACK - 0.03, 180, "<white><b>STAFF ONLY", 0.5f, 0xC0303030));   // over the door
    }

    // ------------------------------------------------------------------------------------------ inside

    private void inside() {
        // behind the counter: the worker's lane x -4, the shelf of cigarettes on the wall, the hanger, the mop
        piece("cig", "economy:store_cig_shelf", -5, 6, -5, 8, 0, 90);
        piece("counter", "economy:store_counter", -3, 6, -3, 8, 0, 90);
        // the kettle and the microwave (each on its own steel cabinet) at the counter's two ends, the 폐기 basket: their fronts
        // to the worker's lane (the door, the water level, the basket's mouth are the worker's)
        piece("kettle", "economy:store_kettle", -3, 5, -3, 5, 0, 270);
        piece("microwave", "economy:store_microwave", -3, 9, -3, 9, 0, 270);
        set(-3, 0, 4, "white_concrete");
        piece("hanger", "economy:store_hanger", -5, 10, -5, 10, 0, 90);
        piece("waste", "economy:store_waste", -3, 10, -3, 10, 0, 270);
        set(-3, 0, 11, "white_concrete");
        set(-3, 0, 12, "white_concrete");
        piece("mop", "economy:store_mop", -5, 13, -5, 13, 0, 90);
        // 3차: the 발주 tablet (on its stand, between the cigarette shelf and the hanger, its screen to the worker's lane) and
        // the breaker on the stockroom wall (정전: a lever, right-clicked)
        piece("order", "economy:store_order_tablet", -5, 9, -5, 9, 0, 90);
        set(-4, 1, BACK - 1, "lever[face=wall,facing=north,powered=false]");   // (facing away from the back wall it hangs on)
        labels.add(new Label(-3.5, 1.75, BACK - 0.03, 180, "<white><b>차단기</b>", 0.4f, 0xC0303030));
        // the four shelves (double-sided, along z), the fridges on the back wall, the freezer and the eat bar at the window
        piece("shelf_snack", "economy:store_shelf_snack_full", 0, 6, 0, 8, 0, 0);
        piece("shelf_noodle", "economy:store_shelf_noodle_full", 0, 10, 0, 12, 0, 0);
        piece("shelf_daily", "economy:store_shelf_daily_full", 3, 6, 3, 8, 0, 0);
        piece("shelf_meal", "economy:store_shelf_meal_full", 3, 10, 3, 12, 0, 0);
        piece("fridge_0", "economy:store_fridge_full", -1, 14, 0, 14, 0, 180);
        piece("fridge_1", "economy:store_fridge_full", 1, 14, 2, 14, 0, 180);
        piece("fridge_2", "economy:store_fridge_full", 3, 14, 4, 14, 0, 180);
        piece("freezer", "economy:store_freezer", 1, 4, 2, 4, 0, 0);
        piece("eatbar", "economy:store_eat_bar", -2, 4, 0, 4, 0, 0);
        labels.add(new Label(-4.4, 2.55, 8.2, 90, "<white><b>계산대", 0.55f, 0xD01F9D55));
    }

    private void outside() {
        piece("parasol_0", "economy:store_parasol", -6, 1, -4, 1, 0, 0);
        piece("parasol_1", "economy:store_parasol", -2, 1, 0, 1, 0, 0);
        set(2, 0, 2, "cauldron");
        set(-7, 0, 2, "lantern");
        set(7, 0, 2, "lantern");
    }

    private void piece(String role, String item, int x0, int z0, int x1, int z1, int y, float yaw) {
        pieces.add(new Piece(role, item, x0, z0, x1, z1, y, yaw));
    }

    // ------------------------------------------------------------------------------------------ the walk

    private void node(String id, double x, double z, float yaw) {
        nodes.put(id, new Node(id, x, z, yaw));
    }

    private void edge(String a, String b) {
        edges.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        edges.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
    }

    private void spot(String place, String id, double x, double z, float yaw, String... near) {
        node(id, x, z, yaw);
        spots.computeIfAbsent(place, k -> new ArrayList<>()).add(id);
        for (String n : near) edge(id, n);
    }

    private void walk() {
        node("out", 5.0, 1.0, 0);
        node("door", 5.0, 4.5, 0);
        node("fr", 4.5, 5.5, 0);
        node("fm", 1.5, 5.5, 0);
        node("fl", -0.5, 5.5, 0);
        node("mr", 4.5, 9.5, 0);
        node("mm", 1.5, 9.5, 0);
        node("ml", -0.5, 9.5, 0);
        node("br", 4.5, 13.5, 0);
        node("bm", 1.5, 13.5, 0);
        node("bl", -0.5, 13.5, 0);
        edge("out", "door");
        edge("door", "fr");
        edge("fr", "fm");
        edge("fm", "fl");
        edge("fr", "mr");
        edge("mr", "br");
        edge("fm", "mm");
        edge("mm", "bm");
        edge("fl", "ml");
        edge("ml", "bl");
        edge("mr", "mm");
        edge("mm", "ml");
        edge("br", "bm");
        edge("bm", "bl");
        // where each place is browsed from, facing it
        spot("snack", "snack_a", -0.5, 7.5, 90, "fl", "ml");
        spot("snack", "snack_b", 1.5, 7.5, 270, "fm", "mm");
        spot("noodle", "noodle_a", -0.5, 11.5, 90, "ml", "bl");
        spot("noodle", "noodle_b", 1.5, 11.5, 270, "mm", "bm");
        spot("daily", "daily_a", 2.5, 7.5, 90, "fm", "mm");
        spot("daily", "daily_b", 4.5, 7.5, 270, "fr", "mr");
        spot("meal", "meal_a", 2.5, 11.5, 90, "mm", "bm");
        spot("meal", "meal_b", 4.5, 11.5, 270, "mr", "br");
        spot("fridge", "fridge_a", 0.0, 13.5, 0, "bl", "bm");
        spot("fridge", "fridge_b", 2.0, 13.5, 0, "bm");
        spot("fridge", "fridge_c", 4.0, 13.5, 0, "bm", "br");
        spot("freezer", "freezer_a", 2.0, 5.5, 180, "fm", "fr");
        // the eat bar (x -2..0 at the window): a 컵라면 customer or a dozing 취객 walks up behind a stool, then sits on it
        for (int i = 0; i < 3; i++) {
            node("eat_" + i, -1.5 + i, 5.2, 180);
            edge("eat_" + i, "fl");
        }
        // someone whose food is in the microwave waits here, off the line (a dead end: nobody walks through)
        node(WARM, -0.55, 8.55, 270);
        edge(WARM, "ml");
        // the pavement's two ends: customers come walking from one and go back to it (the left one passes in front of the
        // parasol tables)
        node(STREET_R, 7.6, 1.0, 270);
        node("street_m", 3.4, 0.4, 270);
        node(STREET_L, -7.6, 0.4, 90);
        edge(STREET_R, "out");
        edge("street_m", "out");
        edge("street_m", STREET_L);
    }

    // ------------------------------------------------------------------------------------------ paths

    /** The node a shopper joins the line from (then straight to their place in it). */
    public static final String QUEUE_FROM = "ml";
    /** The node a shopper leaves the counter by (from the head of the line). */
    public static final String COUNTER_TO = "fl";

    /** The shortest walk between two nodes (both included), by the edges' lengths; empty if there is none. */
    public static List<Node> path(Layout l, String from, String to) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<Map.Entry<String, Double>> open = new PriorityQueue<>(Map.Entry.comparingByValue());
        if (!l.nodes().containsKey(from) || !l.nodes().containsKey(to)) return List.of();
        dist.put(from, 0.0);
        open.add(Map.entry(from, 0.0));
        while (!open.isEmpty()) {
            var e = open.poll();
            String at = e.getKey();
            if (e.getValue() > dist.getOrDefault(at, Double.MAX_VALUE)) continue;
            if (at.equals(to)) break;
            for (String n : l.edges().getOrDefault(at, List.of())) {
                double d = e.getValue() + len(l.nodes().get(at), l.nodes().get(n));
                if (d < dist.getOrDefault(n, Double.MAX_VALUE)) {
                    dist.put(n, d);
                    prev.put(n, at);
                    open.add(Map.entry(n, d));
                }
            }
        }
        if (!dist.containsKey(to)) return List.of();
        List<Node> out = new ArrayList<>();
        for (String at = to; at != null; at = prev.get(at)) out.addFirst(l.nodes().get(at));
        return out;
    }

    static double len(Node a, Node b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    // ------------------------------------------------------------------------------------------ fitting a piece

    /**
     * Where to anchor a piece whose cells (items/store.yml furniture cells: [x, y, z], front -z) should fill its box: the
     * piece is turned to its yaw (else a quarter turn either way, for a piece whose footprint is the other way round) and
     * moved so its cells fill the box exactly; when no turn fits, at its yaw with its cells centred on the box.
     */
    public static Fit fit(List<int[]> cells, Piece p) {
        for (float yaw : new float[]{p.yaw(), p.yaw() + 90, p.yaw() + 270, p.yaw() + 180}) {
            int[] b = bounds(cells, yaw);
            if (b[1] - b[0] + 1 == p.width() && b[3] - b[2] + 1 == p.depth()) {
                return new Fit(p.x0() - b[0], p.y(), p.z0() - b[2], norm(yaw), true);
            }
        }
        int[] b = bounds(cells, p.yaw());
        int ax = (int) Math.round((p.x0() + p.x1()) / 2.0 - (b[0] + b[1]) / 2.0), az = (int) Math.round((p.z0() + p.z1()) / 2.0 - (b[2] + b[3]) / 2.0);
        return new Fit(ax, p.y(), az, norm(p.yaw()), false);
    }

    private static float norm(float yaw) {
        return ((yaw % 360f) + 360f) % 360f;
    }

    /** The local offsets of a piece's cells at a local yaw: {minX, maxX, minZ, maxZ}. */
    static int[] bounds(List<int[]> cells, float yaw) {
        int[] b = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        if (cells.isEmpty()) return new int[]{0, 0, 0, 0};
        for (int[] c : cells) {
            int[] o = offset(c, yaw);
            b[0] = Math.min(b[0], o[0]);
            b[1] = Math.max(b[1], o[0]);
            b[2] = Math.min(b[2], o[1]);
            b[3] = Math.max(b[3], o[1]);
        }
        return b;
    }

    /**
     * A furniture cell's offset from its anchor in the store's frame (right, forward) at a local yaw: the core's turn
     * (FurnitureModule.rotate: the model turned 180°, then by the yaw) seen in a store facing south (world x = -right).
     */
    public static int[] offset(int[] cell, float yaw) {
        double r = Math.toRadians(yaw);
        double x = -cell[0], z = -cell[2];
        double wx = x * Math.cos(r) - z * Math.sin(r);
        double wz = x * Math.sin(r) + z * Math.cos(r);
        return new int[]{(int) Math.round(-wx), (int) Math.round(wz)};
    }

    // ------------------------------------------------------------------------------------------ where it stands

    /** Where a store stands: as {@link HallLayout.Site} (the admin's block and facing), its own bounds. */
    public record Site(String world, int x, int y, int z, int facing) {

        public static int facing(float yaw) {
            return HallLayout.Site.facing(yaw);
        }

        private HallLayout.Site hall() {
            return new HallLayout.Site(world, x, y, z, facing);
        }

        public int[] block(int right, int up, int forward) {
            return hall().block(right, up, forward);
        }

        public double[] at(double right, double up, double forward) {
            return hall().at(right, up, forward);
        }

        public float yaw(float local) {
            return hall().yaw(local);
        }

        public String state(String data) {
            return HallLayout.rotate(data, facing);
        }

        public int[] box(Box b) {
            return hall().box(b);
        }

        /** The chunks the store touches (x, z). */
        public List<long[]> chunks() {
            int[] b = box(of().bounds());
            List<long[]> out = new ArrayList<>();
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) out.add(new long[]{cx, cz});
            return out;
        }

        /** Is a world point on the shop floor? */
        public boolean inside(String inWorld, double px, double py, double pz) {
            if (!world.equals(inWorld)) return false;
            int[] b = box(of().inside());
            return px >= b[0] && px < b[3] + 1 && py >= b[1] - 0.5 && py < b[4] + 1 && pz >= b[2] && pz < b[5] + 1;
        }

        /** The middle of the shop floor (world x, z). */
        public double[] middle() {
            double[] a = at(0.5, 0, 9.5);
            return new double[]{a[0], a[2]};
        }

        /** As stored: world;x;y;z;facing. */
        public String text() {
            return world + ";" + x + ";" + y + ";" + z + ";" + facing;
        }

        public static Site parse(String s) {
            if (s == null) return null;
            String[] v = s.split(";");
            if (v.length != 5) return null;
            try {
                return new Site(v[0], Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3]), Integer.parseInt(v[4]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %d %d %d", world, x, y, z);
        }
    }
}
