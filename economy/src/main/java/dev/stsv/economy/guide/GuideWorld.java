package dev.stsv.economy.guide;

import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The 길라잡이 world: an empty world (no terrain, always day, no weather or mobs) with the plaza, the avenue and one station
 * per topic, laid by {@link #build} from {@link GuideLayout} a few thousand blocks a tick. Its signs and floating names are
 * marked {@link #DECO} (taken away and set again on every build); the plaza's 안내원 is marked {@link GuideModule#NPC}.
 */
final class GuideWorld {

    static final NamespacedKey DECO = new NamespacedKey("economy", "guide_deco");
    /** Bump when the plan changes: the next start lays the world again. */
    static final int VERSION = 1;
    private static final int OPS_PER_TICK = 4000;
    /** The band of every pad the build clears (from the pond's bottom to above the tallest decoration). */
    private static final int CLEAR_LOW = 60, CLEAR_HIGH = 72;

    private final EcoPlugin eco;
    private final String name;
    private boolean building;

    GuideWorld(EcoPlugin eco, String name) {
        this.eco = eco;
        this.name = name;
    }

    String name() {
        return name;
    }

    World world() {
        return Bukkit.getWorld(name);
    }

    boolean building() {
        return building;
    }

    /** Loads (or makes) the world and sets its rules. */
    World load() {
        World w = world();
        if (w == null) {
            eco.getLogger().info("[길라잡이] 체험관 월드 '" + name + "' 를 불러옵니다 (없으면 빈 월드로 만듦)");
            w = Bukkit.createWorld(new WorldCreator(name).generator(new Empty()).generateStructures(false).environment(World.Environment.NORMAL));
        }
        if (w == null) return null;
        rule(w, GameRules.KEEP_INVENTORY, true);
        rule(w, GameRules.ADVANCE_TIME, false);
        rule(w, GameRules.ADVANCE_WEATHER, false);
        rule(w, GameRules.SPAWN_MOBS, false);
        rule(w, GameRules.SPAWN_MONSTERS, false);
        rule(w, GameRules.SPAWN_PATROLS, false);
        rule(w, GameRules.SPAWN_PHANTOMS, false);
        rule(w, GameRules.SPAWN_WANDERING_TRADERS, false);
        rule(w, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        rule(w, GameRules.FALL_DAMAGE, false);
        rule(w, GameRules.MOB_GRIEFING, false);
        rule(w, GameRules.SHOW_DEATH_MESSAGES, false);
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
        w.setSpawnLocation(0, GuideLayout.FLOOR_Y, 0);
        return w;
    }

    private static <T> void rule(World w, GameRule<T> rule, T value) {
        if (!value.equals(w.getGameRuleValue(rule))) w.setGameRule(rule, value);
    }

    Location plaza() {
        World w = world();
        return w == null ? null : new Location(w, 0.5, GuideLayout.FLOOR_Y, 0.5, 180, 0);
    }

    // ------------------------------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------------------------------

    private record Op(int x, int y, int z, BlockData data) {}

    /** The whole area the world uses (x1, z1, x2, z2). */
    static int[] area() {
        int x1 = 0, z1 = 0, x2 = 0, z2 = 0;
        for (GuideLayout.Station s : GuideLayout.stations()) {
            for (int[] c : List.of(s.block(-s.half(), 0), s.block(s.half(), s.depth() - 1))) {
                x1 = Math.min(x1, c[0]);
                z1 = Math.min(z1, c[1]);
                x2 = Math.max(x2, c[0]);
                z2 = Math.max(z2, c[1]);
            }
        }
        return new int[]{x1 - 2, z1 - 2, x2 + 2, z2 + 2};
    }

    /**
     * Lays the whole world again (clear = only take it away). Chunks are held loaded while it works; the old signs and names
     * go first (their entities load a moment after their chunks), then the blocks a few thousand a tick, then the new signs.
     * before runs first (the module tidies its sessions' things), done once it is all laid.
     */
    void build(boolean clear, Runnable before, Consumer<String> done) {
        World w = world();
        if (w == null || building) {
            done.accept(w == null ? "체험관 월드가 없습니다" : "이미 짓는 중입니다");
            return;
        }
        building = true;
        int[] a = area();
        List<long[]> chunks = new ArrayList<>();
        for (int cx = a[0] >> 4; cx <= a[2] >> 4; cx++) {
            for (int cz = a[1] >> 4; cz <= a[3] >> 4; cz++) {
                w.addPluginChunkTicket(cx, cz, eco);
                chunks.add(new long[]{cx, cz});
            }
        }
        before.run();
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(DECO) || e.getPersistentDataContainer().has(GuideModule.NPC) && insideArea(e.getLocation())) e.remove();
            }
            List<Op> ops = clear ? clearOps(w) : plan(w);
            List<Runnable> signs = new ArrayList<>();
            if (!clear) decorations(w, signs);
            final int[] at = {0};
            Bukkit.getScheduler().runTaskTimer(eco, task -> {
                int end = Math.min(ops.size(), at[0] + OPS_PER_TICK);
                for (int i = at[0]; i < end; i++) {
                    Op o = ops.get(i);
                    w.getBlockAt(o.x, o.y, o.z).setBlockData(o.data, false);
                }
                at[0] = end;
                if (end < ops.size()) return;
                task.cancel();
                for (Runnable r : signs) {
                    try {
                        r.run();
                    } catch (RuntimeException ex) {
                        eco.getLogger().warning("[길라잡이] 표지판 오류: " + ex);
                    }
                }
                for (long[] c : chunks) w.removePluginChunkTicket((int) c[0], (int) c[1], eco);
                building = false;
                done.accept(null);
            }, 1L, 1L);
        }, 40L);
    }

    private boolean insideArea(Location l) {
        int[] a = area();
        return l.getX() >= a[0] && l.getX() <= a[2] + 1 && l.getZ() >= a[1] && l.getZ() <= a[3] + 1;
    }

    /** Air over every pad, the avenue and the plaza. */
    private List<Op> clearOps(World w) {
        BlockData air = Material.AIR.createBlockData();
        List<Op> ops = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Consumer<int[]> column = c -> {
            if (!seen.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL))) return;
            for (int y = CLEAR_LOW; y <= CLEAR_HIGH; y++) ops.add(new Op(c[0], y, c[1], air));
        };
        for (GuideLayout.Station s : GuideLayout.stations())
            for (int lx = -s.half(); lx <= s.half(); lx++) for (int lz = 0; lz < s.depth(); lz++) column.accept(s.block(lx, lz));
        for (int x = -GuideLayout.AVENUE_X; x <= GuideLayout.AVENUE_X; x++)
            for (int z = -GuideLayout.PLAZA; z <= GuideLayout.PLAZA; z++)
                if (Math.abs(z) <= GuideLayout.AVENUE_Z || Math.abs(x) <= GuideLayout.PLAZA) column.accept(new int[]{x, z});
        return ops;
    }

    /** Every block of the world: cleared first, then floors and each station's things. */
    private List<Op> plan(World w) {
        List<Op> ops = clearOps(w);
        int fy = GuideLayout.FLOOR_Y - 1;
        // plaza and avenue
        for (int x = -GuideLayout.AVENUE_X; x <= GuideLayout.AVENUE_X; x++) {
            for (int z = -GuideLayout.PLAZA; z <= GuideLayout.PLAZA; z++) {
                boolean plaza = Math.abs(x) <= GuideLayout.PLAZA;
                if (!plaza && Math.abs(z) > GuideLayout.AVENUE_Z) continue;
                Material m = plaza ? ((x + z) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE)
                        : Math.abs(z) == GuideLayout.AVENUE_Z ? Material.STONE_BRICKS : Material.SMOOTH_STONE;
                ops.add(new Op(x, fy, z, m.createBlockData()));
            }
        }
        for (int[] c : new int[][]{{-GuideLayout.PLAZA, -GuideLayout.PLAZA}, {GuideLayout.PLAZA, -GuideLayout.PLAZA},
                {-GuideLayout.PLAZA, GuideLayout.PLAZA}, {GuideLayout.PLAZA, GuideLayout.PLAZA}}) {
            ops.add(new Op(c[0], GuideLayout.FLOOR_Y, c[1], Material.LANTERN.createBlockData()));
        }
        for (GuideLayout.Station s : GuideLayout.stations()) station(s, ops);
        return ops;
    }

    private static final Map<String, Material[]> FLOORS = Map.ofEntries(
            Map.entry("jobs", new Material[]{Material.SMOOTH_SANDSTONE, Material.ORANGE_TERRACOTTA}),
            Map.entry("machines", new Material[]{Material.SMOOTH_STONE, Material.GRAY_CONCRETE}),
            Map.entry("farming", new Material[]{Material.GRASS_BLOCK, Material.OAK_PLANKS}),
            Map.entry("mining", new Material[]{Material.TUFF, Material.DEEPSLATE_BRICKS}),
            Map.entry("fishing", new Material[]{Material.SAND, Material.SPRUCE_PLANKS}),
            Map.entry("shop", new Material[]{Material.BIRCH_PLANKS, Material.RED_TERRACOTTA}),
            Map.entry("land", new Material[]{Material.GRASS_BLOCK, Material.STONE_BRICKS}),
            Map.entry("accessories", new Material[]{Material.PURPUR_BLOCK, Material.AMETHYST_BLOCK}),
            Map.entry("trade", new Material[]{Material.SPRUCE_PLANKS, Material.YELLOW_TERRACOTTA}),
            Map.entry("stocks", new Material[]{Material.POLISHED_BLACKSTONE, Material.RED_CONCRETE}),
            Map.entry("missions", new Material[]{Material.CHERRY_PLANKS, Material.PINK_TERRACOTTA}),
            Map.entry("dock", new Material[]{Material.SMOOTH_STONE, Material.BLUE_CONCRETE}),
            Map.entry("bus", new Material[]{Material.GRASS_BLOCK, Material.LIGHT_BLUE_CONCRETE}));

    private static void put(List<Op> ops, GuideLayout.Station s, int lx, int ly, int lz, Material m) {
        int[] c = s.block(lx, lz);
        ops.add(new Op(c[0], GuideLayout.FLOOR_Y + ly, c[1], m.createBlockData()));
    }

    private static boolean in(int[] r, int lx, int lz) {
        return lx >= Math.min(r[0], r[2]) && lx <= Math.max(r[0], r[2]) && lz >= Math.min(r[1], r[3]) && lz <= Math.max(r[1], r[3]);
    }

    private static boolean edge(int[] r, int lx, int lz) {
        return in(r, lx, lz) && (lx == Math.min(r[0], r[2]) || lx == Math.max(r[0], r[2]) || lz == Math.min(r[1], r[3]) || lz == Math.max(r[1], r[3]));
    }

    private void station(GuideLayout.Station s, List<Op> ops) {
        Material[] f = FLOORS.getOrDefault(s.id(), new Material[]{Material.SMOOTH_STONE, Material.STONE_BRICKS});
        for (int lx = -s.half(); lx <= s.half(); lx++) {
            for (int lz = 0; lz < s.depth(); lz++) {
                boolean border = Math.abs(lx) == s.half() || lz == 0 || lz == s.depth() - 1;
                put(ops, s, lx, -1, lz, border ? f[1] : f[0]);
            }
        }
        Map<String, int[]> r = s.regions();
        switch (s.id()) {
            case "machines" -> marked(ops, s, r.get("machine"));
            case "mining" -> {
                marked(ops, s, r.get("bench"));
                for (int lx = -4; lx <= 4; lx++) {
                    for (int ly = 0; ly <= 3; ly++) {
                        boolean ore = Math.abs(lx) <= 3 && ly <= 2;
                        put(ops, s, lx, ly, 12, ore ? Material.valueOf(GuideLayout.ore(lx, ly)) : Material.STONE);
                        put(ops, s, lx, ly, 13, Material.STONE);
                    }
                }
            }
            case "farming" -> {
                int[] fld = r.get("field");
                for (int lx = fld[0]; lx <= fld[2]; lx++) {
                    for (int lz = fld[1]; lz <= fld[3]; lz++) {
                        if (lz == 9) {
                            put(ops, s, lx, -2, lz, Material.STONE);
                            put(ops, s, lx, -1, lz, Material.WATER);
                        } else put(ops, s, lx, -1, lz, Material.DIRT);
                    }
                }
                for (int lx = fld[0] - 1; lx <= fld[2] + 1; lx++) for (int lz = fld[1] - 1; lz <= fld[3] + 1; lz++)
                    if (!in(fld, lx, lz)) put(ops, s, lx, -1, lz, Material.COARSE_DIRT);
            }
            case "fishing" -> {
                int[] pond = r.get("pond");
                for (int lx = pond[0] - 1; lx <= pond[2] + 1; lx++) {
                    for (int lz = pond[1] - 1; lz <= pond[3] + 1; lz++) {
                        boolean water = in(pond, lx, lz);
                        put(ops, s, lx, -4, lz, Material.STONE);
                        put(ops, s, lx, -3, lz, water ? Material.SAND : Material.STONE);
                        put(ops, s, lx, -2, lz, water ? Material.WATER : Material.STONE);
                        put(ops, s, lx, -1, lz, water ? Material.WATER : Material.SPRUCE_PLANKS);
                    }
                }
            }
            case "land" -> {
                int[] out = r.get("outline");
                for (int lx = out[0]; lx <= out[2]; lx++) for (int lz = out[1]; lz <= out[3]; lz++)
                    if (edge(out, lx, lz)) put(ops, s, lx, -1, lz, Material.YELLOW_CONCRETE);
                for (int[] c : new int[][]{{out[0], out[1]}, {out[2], out[1]}, {out[0], out[3]}, {out[2], out[3]}}) {
                    put(ops, s, c[0], 0, c[1], Material.OAK_FENCE);
                    put(ops, s, c[0], 1, c[1], Material.TORCH);
                }
            }
            case "jobs" -> {
                Material[] job = {Material.DEEPSLATE_DIAMOND_ORE, Material.HAY_BLOCK, Material.PRISMARINE, Material.IRON_BLOCK, Material.BRICKS, Material.AMETHYST_BLOCK};
                for (int i = 0; i < job.length; i++) put(ops, s, -5 + 2 * i, 0, 12, job[i]);
            }
            case "shop" -> {
                for (int lx = -4; lx <= 4; lx++) {
                    put(ops, s, lx, 0, 12, Material.SPRUCE_PLANKS);
                    put(ops, s, lx, 3, 11, lx % 2 == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);
                    put(ops, s, lx, 3, 12, lx % 2 == 0 ? Material.RED_WOOL : Material.WHITE_WOOL);
                }
                for (int lx : new int[]{-4, 4}) for (int ly = 1; ly <= 2; ly++) put(ops, s, lx, ly, 12, Material.SPRUCE_FENCE);
                put(ops, s, -2, 1, 12, Material.MELON);
                put(ops, s, 0, 1, 12, Material.PUMPKIN);
                put(ops, s, 2, 1, 12, Material.HAY_BLOCK);
            }
            case "accessories" -> {
                for (int lx = -3; lx <= 3; lx += 3) {
                    put(ops, s, lx, 0, 12, Material.POLISHED_DEEPSLATE);
                    put(ops, s, lx, 1, 12, Material.AMETHYST_CLUSTER);
                }
            }
            case "trade" -> {
                for (int lx = -4; lx <= 4; lx++) put(ops, s, lx, 0, 12, Material.DARK_OAK_PLANKS);
                put(ops, s, 0, 1, 12, Material.BELL);
            }
            case "stocks" -> {
                for (int lx = -4; lx <= 4; lx++) for (int ly = 0; ly <= 3; ly++) put(ops, s, lx, ly, 14, Material.BLACK_CONCRETE);
            }
            case "missions" -> {
                for (int lx = -4; lx <= 4; lx++) put(ops, s, lx, 0, 12, Material.BOOKSHELF);
                put(ops, s, 0, 1, 12, Material.TARGET);
            }
            case "dock" -> {
                int[] lane = r.get("lane");
                for (int lx = lane[0]; lx <= lane[2]; lx++) for (int lz = lane[1]; lz <= lane[3]; lz++) put(ops, s, lx, -1, lz, Material.GRAY_CONCRETE);
                for (String z : List.of("inspect", "unload")) {
                    int[] zr = r.get(z);
                    for (int lx = zr[0] - 1; lx <= zr[2] + 1; lx++) for (int lz = zr[1] - 1; lz <= zr[3] + 1; lz++)
                        if (edge(new int[]{zr[0] - 1, zr[1] - 1, zr[2] + 1, zr[3] + 1}, lx, lz)) put(ops, s, lx, -1, lz, Material.YELLOW_CONCRETE);
                }
                for (String feat : List.of("garage", "unload_desk")) {
                    int[] c = s.features().get(feat);
                    put(ops, s, c[0], c[1], c[2], Material.CARTOGRAPHY_TABLE);
                }
                int[] u = r.get("unload");
                for (int lz = u[1]; lz <= u[3]; lz += 2) {
                    put(ops, s, u[2] + 6, 0, lz, Material.BARREL);
                    put(ops, s, u[2] + 6, 1, lz, Material.BARREL);
                }
            }
            case "bus" -> {
                int[] road = r.get("road"), inner = r.get("inner");
                for (int lx = road[0]; lx <= road[2]; lx++) {
                    for (int lz = road[1]; lz <= road[3]; lz++) {
                        if (in(inner, lx, lz)) continue;
                        put(ops, s, lx, -1, lz, Material.GRAY_CONCRETE);
                    }
                }
                for (String z : List.of("stop_a", "stop_b")) {
                    int[] zr = r.get(z);
                    for (int lx = zr[0]; lx <= zr[2]; lx++) for (int lz = zr[1]; lz <= zr[3]; lz++)
                        if (edge(zr, lx, lz)) put(ops, s, lx, -1, lz, Material.YELLOW_CONCRETE);
                }
                for (String sign : List.of("sign_a", "sign_b")) {
                    int[] c = s.features().get(sign);
                    for (int dx : new int[]{-2, 2}) {
                        put(ops, s, c[0] + dx, 0, c[2], Material.SPRUCE_FENCE);
                        put(ops, s, c[0] + dx, 1, c[2], Material.SPRUCE_FENCE);
                        put(ops, s, c[0] + dx, 2, c[2], Material.SPRUCE_SLAB);
                    }
                    for (int dx = -1; dx <= 1; dx++) put(ops, s, c[0] + dx, 2, c[2], Material.SPRUCE_SLAB);
                    put(ops, s, c[0] - 1, 0, c[2], Material.SPRUCE_STAIRS);
                }
            }
            default -> { }
        }
        // the sign post by the way in
        put(ops, s, -2, 0, 1, Material.OAK_SIGN);
    }

    /** A placing area: light floor, yellow edge. */
    private static void marked(List<Op> ops, GuideLayout.Station s, int[] r) {
        for (int lx = r[0] - 1; lx <= r[2] + 1; lx++) {
            for (int lz = r[1] - 1; lz <= r[3] + 1; lz++) {
                boolean e = !in(r, lx, lz);
                put(ops, s, lx, -1, lz, e ? Material.YELLOW_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Signs and floating names (after the blocks)
    // ------------------------------------------------------------------------------------------

    private void decorations(World w, List<Runnable> out) {
        out.add(() -> label(w, 0.5, GuideLayout.FLOOR_Y + 3.2, -6.5, "<gold><b>길라잡이 체험관</b>\n<white>해 보고 싶은 것을 골라 보세요\n<gray>/길라잡이 · 안내원 우클릭"));
        out.add(() -> GuideModule.spawnNpc(new Location(w, 0.5, GuideLayout.FLOOR_Y, -5.5, 0, 0)));
        for (GuideLayout.Station s : GuideLayout.stations()) {
            out.add(() -> {
                double[] t = s.point(0, 1);
                label(w, t[0], GuideLayout.FLOOR_Y + 3.4, t[1], "<gold><b>" + s.title() + "</b>\n<gray>길라잡이 체험 정거장");
                sign(w, s, -2, 1, s.title(), "길라잡이", "/길라잡이");
                labels(w, s);
            });
        }
    }

    private void labels(World w, GuideLayout.Station s) {
        Map<String, int[]> f = s.features(), r = s.regions();
        switch (s.id()) {
            case "jobs" -> {
                String[] names = {"광부", "농부", "어부", "대장장이", "요리사", "연금술사"};
                for (int i = 0; i < names.length; i++) at(w, s, -5 + 2 * i, 1.6, 12, "<white>" + names[i]);
            }
            case "machines" -> at(w, s, 0, 1.2, 11, "<yellow>기계를 놓는 곳");
            case "mining" -> {
                at(w, s, 0, 4.4, 12, "<white>광석 벽 <gray>(캐면 다시 생겨요)");
                at(w, s, 0, 1.2, 6, "<yellow>세공대를 놓는 곳");
            }
            case "farming" -> at(w, s, 0, 1.6, 6, "<white>밭 <gray>(괭이로 흙을 갈아요)");
            case "fishing" -> at(w, s, 0, 1.6, 7, "<aqua>연못 <gray>(낚싯대를 던져 보세요)");
            case "shop" -> at(w, s, 0, 4.6, 12, "<white>상점 <gray>/상점 · /시세");
            case "land" -> at(w, s, 0, 1.6, 10, "<white>16×16 = 땅 한 칸 (청크)");
            case "accessories" -> at(w, s, 0, 2.6, 12, "<light_purple>장신구 <gray>/장신구");
            case "trade" -> at(w, s, 0, 2.6, 12, "<white>거래소 · 경매 · 직거래 · 우편함");
            case "stocks" -> at(w, s, 0, 2.2, 13, "<red>▲ <white>주식판 <blue>▼\n<gray>/주식");
            case "missions" -> at(w, s, 0, 2.6, 12, "<white>미션 · 업적 · 칭호 · 치장");
            case "dock" -> {
                int[] g = f.get("garage"), u = f.get("unload_desk"), in = r.get("inspect"), un = r.get("unload");
                at(w, s, g[0], 1.6, g[2], "<white>차고 창구 <gray>(우클릭)");
                at(w, s, u[0], 1.6, u[2], "<white>하역 창구 <gray>(우클릭)");
                at(w, s, in[2] + 2, 1.6, (in[1] + in[3]) / 2.0, "<yellow>검사대");
                at(w, s, un[2] + 2, 2.8, (un[1] + un[3]) / 2.0, "<yellow>하역 구역");
            }
            case "bus" -> {
                int[] a = f.get("sign_a"), b = f.get("sign_b");
                at(w, s, a[0], 3.3, a[2], "<white>🚏 정류장 A");
                at(w, s, b[0], 3.3, b[2], "<white>🚏 정류장 B");
            }
            default -> { }
        }
    }

    private void at(World w, GuideLayout.Station s, double lx, double ly, double lz, String text) {
        double[] p = s.point(lx, lz);
        label(w, p[0], GuideLayout.FLOOR_Y + ly, p[1], text);
    }

    private static void label(World w, double x, double y, double z, String text) {
        w.spawn(new Location(w, x, y, z), TextDisplay.class, d -> {
            d.text(Text.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setViewRange(0.6f);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(DECO, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private static void sign(World w, GuideLayout.Station s, int lx, int lz, String... lines) {
        int[] c = s.block(lx, lz);
        Block b = w.getBlockAt(c[0], GuideLayout.FLOOR_Y, c[1]);
        if (!(b.getBlockData() instanceof org.bukkit.block.data.type.Sign data)) return;
        data.setRotation(face(-s.fx(), -s.fz()));
        b.setBlockData(data, false);
        if (!(b.getState() instanceof org.bukkit.block.Sign sign)) return;
        var side = sign.getSide(Side.FRONT);
        for (int i = 0; i < lines.length && i < 4; i++) side.line(i, Text.mm(i == 0 ? "<b>" + lines[i] : lines[i]));
        sign.setWaxed(true);
        sign.update(true, false);
    }

    private static BlockFace face(int x, int z) {
        if (x > 0) return BlockFace.EAST;
        if (x < 0) return BlockFace.WEST;
        return z > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** The world's generator: nothing, the river biome everywhere (fish of the river bite in the pond). */
    static final class Empty extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }

        @Override
        public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
            return new BiomeProvider() {
                @Override
                public org.bukkit.block.Biome getBiome(WorldInfo info, int x, int y, int z) {
                    return org.bukkit.block.Biome.RIVER;
                }

                @Override
                public List<org.bukkit.block.Biome> getBiomes(WorldInfo info) {
                    return List.of(org.bukkit.block.Biome.RIVER);
                }
            };
        }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, GuideLayout.FLOOR_Y, 0.5);
        }
    }

    /** Puts one station's 광산 wall and 밭 back as built (crash leftovers, a long day of digging). */
    void tidy(dev.stsv.core.crop.CropModule crops) {
        World w = world();
        if (w == null) return;
        GuideLayout.Station mine = GuideLayout.station("mining");
        for (int lx = -3; lx <= 3; lx++) for (int ly = 0; ly <= 2; ly++) {
            int[] c = mine.block(lx, 12);
            Block b = w.getBlockAt(c[0], GuideLayout.FLOOR_Y + ly, c[1]);
            Material m = Material.valueOf(GuideLayout.ore(lx, ly));
            if (b.getType() != m) b.setType(m, false);
        }
        GuideLayout.Station farm = GuideLayout.station("farming");
        int[] fld = farm.regions().get("field");
        for (int lx = fld[0]; lx <= fld[2]; lx++) for (int lz = fld[1]; lz <= fld[3]; lz++) {
            if (lz == 9) continue;
            int[] c = farm.block(lx, lz);
            Block top = w.getBlockAt(c[0], GuideLayout.FLOOR_Y, c[1]);
            if (crops != null) crops.clear(top);
            if (!top.getType().isAir()) top.setType(Material.AIR, false);
            Block soil = w.getBlockAt(c[0], GuideLayout.FLOOR_Y - 1, c[1]);
            if (soil.getType() != Material.DIRT) soil.setType(Material.DIRT, false);
        }
        GuideLayout.Station land = GuideLayout.station("land");
        int[] plot = land.regions().get("plot");
        for (int lx = plot[0]; lx <= plot[2]; lx++) for (int lz = plot[1]; lz <= plot[3]; lz++) {
            int[] c = land.block(lx, lz);
            for (int y = GuideLayout.FLOOR_Y; y <= GuideLayout.FLOOR_Y + 8; y++) {
                Block b = w.getBlockAt(c[0], y, c[1]);
                if (!b.getType().isAir()) b.setType(Material.AIR, false);
            }
        }
    }
}
