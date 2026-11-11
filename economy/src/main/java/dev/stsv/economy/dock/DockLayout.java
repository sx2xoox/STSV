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
 * Real blocks for everything that stands (pad, warehouse, platform, crane, containers, lamps, fences, the pier), a few
 * display models for detail (the crane's hook, a forklift, loose containers: models/dock_*.bbmodel in the pack's style),
 * a sign at the entrance and a floating name over the crane.
 */
public final class DockLayout {

    public enum Kind {
        LAND("육지"), PORT("항구");

        public final String word;

        Kind(String word) {
            this.word = word;
        }

        public static Kind of(String s) {
            if (s == null) return LAND;
            for (Kind k : values()) if (k.word.equals(s) || k.name().equalsIgnoreCase(s)) return k;
            return null;
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
        for (int y = top - 2; y >= 9; y--) set(0, y, 13, "minecraft:chain");
        props.add(new Prop(0.5, 7.0, 13.5, "dock_crane_hook", 1.5f, 0));
        // a container on its way, hanging under the hook? no: the hook stays clear of the zone's vehicles (above 7)
    }

    private static final String[][] COLOURS = {
            {"minecraft:red_concrete", "minecraft:red_terracotta"}, {"minecraft:blue_concrete", "minecraft:blue_terracotta"},
            {"minecraft:green_concrete", "minecraft:green_terracotta"}, {"minecraft:orange_concrete", "minecraft:orange_terracotta"},
            {"minecraft:white_concrete", "minecraft:white_terracotta"}, {"minecraft:cyan_concrete", "minecraft:cyan_terracotta"}};

    private void containers() {
        // stacks of shipping containers: 3 wide, 3 high, 7 long, ribbed sides, a door end; 1 to 3 high
        int[][] stacks = {{11, 6, 3}, {11, 14, 2}, {11, 22, 1}, {11, 30, 2}, {15, 6, 2}, {15, 14, 3}, {15, 22, 2}, {15, 30, 1},
                {19, 6, 1}, {19, 14, 2}, {19, 22, 3}, {19, 30, 2}};
        int n = 0;
        for (int[] s : stacks) {
            for (int level = 0; level < s[2]; level++) {
                String[] c = COLOURS[(n++ * 5 + 3) % COLOURS.length];
                container(s[0], level * 3, s[1], c);
            }
        }
        // loose containers and a forklift as display models (in the pack's style)
        props.add(new Prop(16.5, 0, 2.5, "dock_container_blue", 2f, 90));
        props.add(new Prop(13.0, 9, 23.0, "dock_container_red", 2f, 0));
        props.add(new Prop(-5.5, 0, 31.5, "dock_forklift", 1.5f, 200));
        props.add(new Prop(-5.5, 0, 26.5, "dock_pallet", 1f, 0));
    }

    private void container(int x0, int y0, int z0, String[] c) {
        for (int x = x0; x <= x0 + 2; x++) {
            for (int y = y0; y <= y0 + 2; y++) {
                for (int z = z0; z <= z0 + 6; z++) {
                    boolean door = z == z0;
                    boolean rib = Math.floorMod(z, 2) == 0;
                    boolean edge = (x == x0 || x == x0 + 2) && (y == y0 || y == y0 + 2) || (z == z0 || z == z0 + 6) && (x == x0 || x == x0 + 2);
                    String b = edge ? "minecraft:gray_concrete" : door ? c[1] : rib ? c[0] : c[1];
                    set(x, y, z, b);
                }
            }
        }
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
