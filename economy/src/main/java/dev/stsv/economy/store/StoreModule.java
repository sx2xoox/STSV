package dev.stsv.economy.store;

import dev.stsv.core.data.Db;
import dev.stsv.core.furniture.FurnitureBreakEvent;
import dev.stsv.core.furniture.FurnitureInteractEvent;
import dev.stsv.core.hud.HudLayout;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusSpec;
import dev.stsv.economy.hwatu.ActionLine;
import dev.stsv.economy.hwatu.ButtonPick;
import dev.stsv.economy.hwatu.hall.HallBuilder;
import dev.stsv.economy.hwatu.hall.HallLayout;
import dev.stsv.economy.people.PeopleSpec;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 편의점 알바 (README "편의점", docs/plans/편의점_기획안.md 1차): the GS26 직영점s the server builds (/관리자 편의점 건설), a
 * shift at one (the hanger behind the counter: the vest and the scanner), the bus's people coming in to shop (by game hour,
 * each kind its own likes and talk), the till (scan, card or cash with change buttons), restocking from the delivery boxes,
 * the microwave, and the pay at clock-out (40% of what the shift earned: the server's store).
 *
 * <p>Nothing of a shift is saved: the customers, the counter's things, the buttons and the boxes exist only while someone
 * works (non-persistent entities, tagged {@link #GUEST}, cleared when a chunk loads); an empty store is only its furniture.
 * A restart or a reload ends every shift and pays it out.
 */
public final class StoreModule extends EcoModule {

    static final String HUD = "eco-store";
    /** The board of a worker on shift: over the everyone-boards (a fishing tournament, 0), under a seated table's (100). */
    public static final int BOARD_SHIFT = 50;
    /** Everything a shift spawns (not saved; gone when its chunk loads again). */
    static final NamespacedKey GUEST = new NamespacedKey("economy", "store_guest");
    /** The scanner and the vest (taken back at clock-out, never dropped). */
    static final NamespacedKey TOOL = new NamespacedKey("economy", "store_tool");
    static final String SCANNER = "economy:store_scanner", VEST = "economy:store_vest";
    /** How far from the store's middle a worker may go before the away clock starts (blocks). */
    static final double AWAY = 24;
    /** Standing still: told this many seconds before the shift ends by itself. */
    static final int AFK_WARN = 30;
    /** The POS keys' size (store_btn_*: 12 × 6 px at 1), lying on the counter (StoreLayout.posKey). */
    static final float BUTTON = StoreLayout.KEY_SCALE;
    /** A 발주 page left alone this long is forgotten (ms): a sneaking click later does not order it. */
    static final long PAGE_MS = 30_000;

    private StoreSpec spec;
    /** 2차: 내 가게 등급 · 신분증 · 진상 · 폐기 · 청소 (store.yml) and the store's scenes (store_scenes.yml) */
    private StoreRules rules;
    private StoreScenes scenes = StoreScenes.NONE;
    /** A thing a player picked off a shelf back when players could shop here (their store's name): taken away when seen. */
    static final NamespacedKey UNPAID = new NamespacedKey("economy", "store_unpaid");
    /** Korean time: the ID's year, the scenes' months and weekends. */
    static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");
    /** How long a 내 가게 confirmation waits for the second click (ms). */
    static final long CONFIRM_MS = 10_000;
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final Map<UUID, Shop> working = new HashMap<>();
    private final ButtonPick.Debounce clicks = new ButtonPick.Debounce();
    private final ButtonPick.Hint hints = new ButtonPick.Hint();
    private StoreBuilder job;
    /** the store the build job works on (only that one cannot be worked in meanwhile) */
    private String jobStore;
    private long ticks;
    /** customers' speech bubbles: one each, out of each other's way (StoreBubbles) */
    private final StoreBubbles bubbles = new StoreBubbles(this);
    /** a 내 가게 click waiting for its second click: what and where, and when */
    private final Map<UUID, Confirm> confirms = new HashMap<>();
    /** when a worker last saw a store scene (ms) */
    private final Map<UUID, Long> sceneAt = new HashMap<>();
    private int promptIds;
    /** 3차 + 보완안 (store.yml career · night · sticker · order · regulars · staff · bus-link · rush · events) */
    private StoreLife life;
    private CareerBook book;
    private StoreDay day;
    private StoreBus link;
    /** other modules' customers (a hidden secret's), by the visitor's id */
    final Map<String, StoreVisit.Visitor> visitors = new LinkedHashMap<>();
    /** 남의 가게 알바: who asked to work in a 내 가게 (store -> them, in memory until a restart) */
    private final Map<String, Set<UUID>> applied = new HashMap<>();
    /** a player paging the 발주 tablet: the store, the group shown, when */
    final Map<UUID, Paging> ordering = new HashMap<>();
    /** what each worker aims at now (a POS key, a thing on the mat): it glows */
    private final Map<UUID, Entity> aimed = new HashMap<>();
    /** §17: what a worker holds in place of the scanner, and the actions shown in the world */
    private final StoreHands hands = new StoreHands(this);
    private final StoreShow show = new StoreShow(this);

    private record Confirm(String what, String key, long at) {}

    record Paging(String store, String group, long at) {}

    public StoreModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-store";
    }

    @Override
    protected void start() {
        load();
        core.db().migrate("eco_store", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_store (name VARCHAR(64) NOT NULL PRIMARY KEY, site VARCHAR(160) NOT NULL)"),
                // 2차 내 가게: its owner and 등급 (null: a 직영점 the server built)
                List.of("ALTER TABLE eco_store ADD COLUMN owner CHAR(36)", "ALTER TABLE eco_store ADD COLUMN tier VARCHAR(16)")));
        shops.clear();
        core.db().sync(c -> {
            for (String[] r : Db.query(c, "SELECT name, site, owner, tier FROM eco_store",
                    rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})) {
                StoreLayout.Site s = StoreLayout.Site.parse(r[1]);
                if (s == null) continue;
                Shop shop = new Shop(r[0], s, new Chores(spec));
                if (r[2] != null && !r[2].isBlank()) {
                    try {
                        shop.owner = UUID.fromString(r[2].trim());
                    } catch (IllegalArgumentException e) {
                        eco.getLogger().warning("[편의점] " + r[0] + ": 주인 UUID 가 이상함 " + r[2]);
                    }
                    shop.tier = r[3];
                }
                shops.put(r[0], shop);
            }
            return null;
        });
        core.admin().add(EcoPlugin.ADMIN_OWNER, "편의점", "건설 · 철거 · 목록 · 시험 · 손님 · 배송 · 진상 · 신분증 · 장면 · 폐기 · 청소 · 권리증 · 내가게 · 회수 · "
                        + "경력 · 러시 · 정전 · 단체 · 점검 · 발주 · 단골 · 버스 · 알바",
                "편의점(GS26 직영점) 짓기 · 허물기 · 시험 근무 · 손님 · 배송 · 진상 손님 부르기 · 신분증 손님 · 장면 · 폐기 · 청소 · 내 가게 권리증 지급 · 목록 · 회수 · "
                        + "경력 단계 · 러시 · 정전 · 수학여행 · 본사 점검 · 발주 시험 · 단골 손님 · 버스 손님 · 알바 허용",
                this::admin, this::adminComplete);
        book = new CareerBook(core.db());
        book.start();
        day = new StoreDay(this);
        link = new StoreBus(this);
        if (eco.bus() != null) eco.bus().watch("store", link);
        day.relight(shops.values());
        for (Shop s : shops.values()) show.restyle(s);   // §17: pieces left showing a state (a crash) as they are now
        if (core.hud() != null) {
            core.hud().board(HUD, BOARD_SHIFT, p -> working.containsKey(p.getUniqueId()), this::board);
            core.hud().line(HUD, 0, this::actionLine);
        }
        purge();
        every(1, this::frame);
        every(20, this::second);
        every(600, () -> day.labels(shops.values()));
    }

    @Override
    protected void stop() {
        if (job != null && job.running()) job.cancel();
        for (Shop s : shops.values()) if (s.shift != null) clockOut(s, "store.out-reload");
        working.clear();
        bubbles.clear();
        for (Shop s : shops.values()) if (s.label != null) s.label.remove();
        if (eco.bus() != null) eco.bus().watch("store", null);
        if (core.hud() != null) {
            core.hud().removeBoard(HUD);
            core.hud().removeLine(HUD);
        }
    }

    @Override
    public void reload() {
        load();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        org.bukkit.configuration.file.YamlConfiguration y = eco.data("store.yml");
        spec = StoreSpec.parse(y, problems);
        rules = StoreRules.parse(y, spec, problems);
        life = StoreLife.parse(y, spec, problems);
        BusModule bus = eco.bus();
        if (bus != null && bus.spec() != null) problems.addAll(spec.check(bus.spec()));
        problems.forEach(p -> eco.getLogger().warning("[store.yml] " + p));
        List<String> sceneProblems = new ArrayList<>();
        scenes = StoreScenes.parse(eco.data("store_scenes.yml"), spec, bus == null ? null : bus.spec(), sceneProblems);
        sceneProblems.forEach(p -> eco.getLogger().warning("[store_scenes.yml] " + p));
    }

    public StoreRules rules() {
        return rules;
    }

    public StoreSpec spec() {
        return spec;
    }

    public StoreLife life() {
        return life;
    }

    CareerBook book() {
        return book;
    }

    dev.stsv.core.STSVPlugin core() {
        return core;
    }

    StoreHands hands() {
        return hands;
    }

    StoreShow show() {
        return show;
    }

    /** Whether the pack has this item (a §17 model the asset side may not have made yet: the old look then). */
    boolean has(String id) {
        return core != null && core.content().item(id).isPresent();
    }

    /** Whether tomorrow's 발주 is in (the tablet shows it sent). */
    boolean orderedTomorrow(Shop shop) {
        return day != null && day.ordered(shop, StoreDay.gameDay(shop.world()) + 1) != null;
    }

    EcoPlugin eco() {
        return eco;
    }

    /** The worker at the till of a shift (online), or null. */
    Player worker(Shift sh) {
        return Bukkit.getPlayer(sh.worker);
    }

    /**
     * Registers (null: removes) a module's customers under an id: asked every 30 seconds of a real shift for a visit
     * ({@link StoreVisit}). A hidden secret's way into the store.
     */
    /** 숨은 비밀 (새로 온 사람): asked at each sale whether its receipt prints broken; what it gives is what the strip is when picked up. */
    private java.util.function.Function<Player, ItemStack> receiptGlitch;

    public void receiptGlitch(java.util.function.Function<Player, ItemStack> f) {
        receiptGlitch = f;
    }

    /** An admin's look: this worker's printer prints a broken receipt now (false: they are not on a shift). */
    public boolean printGlitch(Player worker, ItemStack prize) {
        return false;
    }

    public void visitor(String id, StoreVisit.Visitor v) {
        if (v == null) visitors.remove(id);
        else visitors.put(id, v);
    }

    /** A player's 편의점 rank (경력), by the customers they served. */
    public StoreLife.Rank rank(UUID player) {
        return life.rank(book == null ? 0 : book.served(player));
    }

    /** Customers this player served at night (야간 근무), ever. */
    public long nightServed(UUID player) {
        return book == null ? 0 : book.night(player);
    }

    // ------------------------------------------------------------------------------------------ state

    /** A built store: where it is, its stock, the shift on now (null: closed, only its furniture). */
    final class Shop {
        final String name;
        final StoreLayout.Site site;
        final Chores chores;
        Shift shift;
        final Map<String, String> shown = new HashMap<>();   // place -> the shelf state shown; "role:" + role -> the model shown
        final Map<String, UUID> pieces = new HashMap<>();   // role -> its furniture display (looked up again when gone)
        /** 발주: the tablet shows a page until (ms) */
        long tabletUntil;
        /** 내 가게 (2차): its owner and 등급 id (null: a 직영점) */
        UUID owner;
        String tier;
        /** 3차: the "이달의 점장" line under the sign; what sells best today (발주) and on which game day; the matched order's
         *  first bonus paid (its day); the day's events (their day, how many); the rush window done (day × 16 + window) */
        TextDisplay label;
        String demand;
        long demandDay = Long.MIN_VALUE, firstDay = Long.MIN_VALUE, eventDay = Long.MIN_VALUE, rushKey = Long.MIN_VALUE;
        int eventsToday;

        Shop(String name, StoreLayout.Site site, Chores chores) {
            this.name = name;
            this.site = site;
            this.chores = chores;
        }

        StoreRules.Tier tier() {
            return tier == null ? null : rules.tiers().get(tier);
        }

        /** Its furniture (a smaller 등급 has less). */
        StoreLayout.Layout layout() {
            StoreRules.Tier t = tier();
            return t == null ? StoreLayout.of() : StoreLayout.of(t.without());
        }

        boolean has(String role) {
            return layout().piece(role) != null;
        }

        /** What it sells (null: everything, a 직영점). */
        Set<String> sold() {
            StoreRules.Tier t = tier();
            return t == null ? null : t.products();
        }

        boolean sells(String product) {
            Set<String> s = sold();
            return spec.products().containsKey(product) && (s == null || s.contains(product));
        }

        int maxInside() {
            StoreRules.Tier t = tier();
            return t == null ? spec.spawn().maxInside() : t.maxInside();
        }

        /** A worker's share of the pay: the 직영점's 40%, all of it for the owner in their own store, a hired worker's share there. */
        double share(UUID worker) {
            return life.staff().share(owner != null, worker != null && worker.equals(owner), spec.pay().share());
        }

        World world() {
            return Bukkit.getWorld(site.world());
        }

        Location at(double x, double y, double z, float yaw) {
            double[] a = site.at(x, y, z);
            return new Location(world(), a[0], a[1], a[2], site.yaw(yaw), 0);
        }

        Location at(HallLayout.Point p) {
            return at(p.x(), p.y(), p.z(), p.yaw());
        }

        boolean loaded() {
            World w = world();
            if (w == null) return false;
            double[] m = site.middle();
            return w.isChunkLoaded((int) Math.floor(m[0]) >> 4, (int) Math.floor(m[1]) >> 4);
        }
    }

    /** One shift: the worker, the tally, the people inside, the till. */
    final class Shift implements Shopper.Env {
        final Shop shop;
        /** the worker at the till (계산); 2인 근무: they may swap with the helper, the helper takes over when they go */
        UUID worker;
        final boolean test;
        ShiftLog log = new ShiftLog();
        long started = System.currentTimeMillis();
        /** 2인 근무 (3차): the second worker (진열), their tally, when they came; null: one worker */
        UUID helper;
        ShiftLog helperLog;
        long helperStarted;
        long helperAway;
        /** 야간 근무: clocked in at night (the night vest) */
        boolean night;
        /** 러시 · 하루 이벤트 · visits (StoreDay) */
        StoreLife.RushRun rush;
        boolean outage;
        long outageUntil, inspectUntil;
        int crowdLeft, crowdClock, eventClock, visitClock;
        Guest inspector;
        /** a visit's thing left on the counter (until an answer takes it) */
        ItemDisplay visitProp;
        /** an admin's 단골: the next customer asks for their usual */
        boolean forceUsual, forceBus;
        final List<Guest> guests = new ArrayList<>();
        final List<Shopper> line = new ArrayList<>();
        final Map<String, ItemDisplay> boxes = new HashMap<>();
        Till till;
        /** paid, their food in the microwave (or about to be): waiting off the line, the counter free for the next one */
        Parked parked;
        Guest warmFor;
        boolean afkWarned;
        /** a shelf empty since (ms; 0: none is), for the quick delivery */
        long emptySince;
        double nextSpawn;
        long lastActive = System.currentTimeMillis();
        long awaySince;
        double lastX, lastZ;
        float lastYaw, lastPitch;
        TextDisplay ovenText;
        /** 2차: the questions over customers, the messes and the 폐기 tags shown */
        final List<Prompt> prompts = new ArrayList<>();
        final Map<String, Entity> messes = new HashMap<>();
        TextDisplay wasteTag;
        int wasteClock;
        /** §17: the shift's short-lived displays, the red 폐기 on the shelf, the shelves filling now, the food in the oven,
         *  the kettle being filled */
        final List<Entity> fx = new ArrayList<>();
        final List<ItemDisplay> wasteItems = new ArrayList<>();
        final Set<String> filling = new java.util.HashSet<>();
        ItemDisplay ovenFood;
        boolean pouring;
        long sceneCheck;
        Playing playing;
        String forceScene;

        Shift(Shop shop, UUID worker, boolean test) {
            this.shop = shop;
            this.worker = worker;
            this.test = test;
        }

        @Override
        public List<StoreLayout.Node> path(String from, String to) {
            return StoreLayout.path(StoreLayout.of(), from, to);
        }

        @Override
        public boolean take(String place) {
            boolean ok = shop.chores.take(place);
            if (ok) refreshShelf(shop, place);
            return ok;
        }

        @Override
        public int line(Shopper s) {
            int i = line.indexOf(s);
            if (i < 0 && s.inLine()) {
                line.add(s);
                i = line.size() - 1;
            }
            return i;
        }

        @Override
        public StoreLayout.Node queueSpot(int i) {
            List<StoreLayout.Node> q = StoreLayout.of().queue();
            return q.get(Math.min(i, q.size() - 1));
        }
    }

    /** A customer: their shopper (where, what), their mannequin (and a partner's), their kind. */
    static final class Guest {
        final Shopper s;
        final BusSpec.Type type;
        final Mannequin m;
        Mannequin partner;
        boolean warm;
        /** 2차: their trouble (진상), what they pocketed, their ID, the eat bar */
        Trouble.Kind trouble;
        boolean troubleStarted, troubleDone, caught, tallied, scened, eats, sleeping;
        String stolen, refundItem, touristPlace;
        double tipX = 1;
        IdCheck.Card card;
        int seat = -1, prize = -1;
        /** 3차: asks for their usual (단골 메뉴: the product, when they asked, the hint said); a regular's basket (묶음 스캔);
         *  asked when the next bus comes; the 본사 점검원; another module's visit (and who sent it); what they bought */
        boolean usual, hinted, regular, busAsk, inspector, visitDone;
        String usualItem;
        long usualAt;
        StoreVisit.Visit visit;
        StoreVisit.Visitor visitor;
        List<String> bought;
        /** accused once already; in through the door (the bell rang); a wrong shelf tried for their usual; their skin */
        boolean accused, entered, wrongUsual;
        String skin;
        /** looking somewhere else than where they walk until (tick): a glance, a nod, the head down asleep */
        long lookUntil;
        float lookYaw, lookPitch;
        boolean lookTurn;
        /** the stool they sit on (the eat bar); the last time their impatience showed (ms) */
        ItemDisplay chair;
        long nagged;

        Guest(Shopper s, BusSpec.Type type, Mannequin m) {
            this.s = s;
            this.type = type;
            this.m = m;
        }
    }

    /** The customer at the counter: their checkout and what shows on the counter. */
    static final class Till {
        final Guest g;
        Checkout c;
        final List<ItemDisplay> items = new ArrayList<>();
        final List<Entity> paying = new ArrayList<>();
        final Map<String, ItemDisplay> buttons = new LinkedHashMap<>();
        final List<Entity> extra = new ArrayList<>();   // a parcel, a thing brought back, an ID card
        TextDisplay screen, face;
        boolean payStarted;
        /** 2차: a question to answer before anything is scanned; all on credit (외상) */
        boolean hold, free;
        TextDisplay idCard;
        /** §17: the ID card lying on the counter, its photo */
        ItemDisplay idModel, idPhoto;
        /** the change counted out so far: each piece on the cash tray, in the order pressed */
        final List<ItemDisplay> tray = new ArrayList<>();
        /** when they had paid (ms; 0: not yet), for what they still wait for */
        long paidAt;

        Till(Guest g, Checkout c) {
            this.g = g;
            this.c = c;
        }
    }

    /** A customer who paid and waits aside for their food: their checkout goes on without the counter. */
    static final class Parked {
        final Guest g;
        final Checkout c;
        final boolean free;
        final long at = System.currentTimeMillis();

        Parked(Guest g, Checkout c, boolean free) {
            this.g = g;
            this.c = c;
            this.free = free;
        }
    }

    // ------------------------------------------------------------------------------------------ ticks

    private void frame() {
        ticks++;
        bubbles.tick();
        if (ticks % 10 == 0 && !hands.book.isEmpty()) {   // §17: brief things go back by themselves
            for (UUID u : hands.book.due(System.currentTimeMillis())) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) hands.forget(u);
                else handsBack(p);
            }
        }
        for (Shop shop : shops.values()) {
            Shift sh = shop.shift;
            if (sh == null) continue;
            for (Guest g : new ArrayList<>(sh.guests)) step(sh, g);
            oven(sh);
        }
        if (ticks % 4 == 0) aim();
    }

    /** What each worker looks at (a POS key, a thing to scan, a box) glows, so a click lands where they think it does. */
    private void aim() {
        for (Map.Entry<UUID, Shop> w : working.entrySet()) {
            Player p = Bukkit.getPlayer(w.getKey());
            Shift sh = w.getValue().shift;
            Entity now = null;
            if (p != null && sh != null) {
                Location eye = p.getEyeLocation();
                org.bukkit.util.Vector d = eye.getDirection();
                ButtonPick.Target hit = ButtonPick.pick(eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), p.getUniqueId(), targets(sh, p));
                if (hit != null) now = shownFor(sh, hit.key());
            }
            Entity before = now == null ? aimed.remove(w.getKey()) : aimed.put(w.getKey(), now);
            if (before != null && before != now && before.isValid() && !aimed.containsValue(before)) before.setGlowing(false);
            if (now != null && !now.isGlowing()) now.setGlowing(true);
        }
        aimed.keySet().removeIf(u -> !working.containsKey(u));
    }

    /** The display a target's key is drawn by (a POS key, a thing on the mat, a box), or null. */
    private Entity shownFor(Shift sh, String key) {
        Till t = sh.till;
        if (key.startsWith("box:")) return sh.boxes.get(key.substring(4));
        if (t == null) return null;
        if (key.startsWith("item:")) {
            int i = Integer.parseInt(key.substring(5));
            return i < t.items.size() ? t.items.get(i) : null;
        }
        return t.buttons.get(key);
    }

    private void second() {
        for (Shop shop : new ArrayList<>(shops.values())) {
            Shift sh = shop.shift;
            if (sh == null) continue;
            Player p = Bukkit.getPlayer(sh.worker);
            if (p == null) {
                clockOut(shop, null);
                continue;
            }
            if (watch(shop, sh, p)) continue;
            if (sh.helper != null) watchHelper(shop, sh);
            World w = shop.world();
            if (w == null) continue;
            int hour = Chores.hour(w.getTime());
            // the truck
            List<String> came = shop.chores.delivery(spec, Chores.slot(w.getFullTime(), spec.deliveries()), day.orderedBox(shop));   // 발주: its box too
            if (!came.isEmpty()) {
                showBoxes(sh);
                p.sendMessage(Lang.msg("store.delivery", "n", came.size()));
                truck(sh);
            }
            // customers
            sh.nextSpawn -= 1;
            if (sh.nextSpawn <= 0) {
                if (sh.guests.size() < shop.maxInside()) spawn(sh, null, hour);
                sh.nextSpawn = spec.interval(hour, ThreadLocalRandom.current(), rules.demand().busy(marketDay())) * day.spawnFactor(sh);   // 장날 · 러시: more
            }
            // patience
            long nowMs = System.currentTimeMillis();
            for (Guest g : new ArrayList<>(sh.guests)) {
                if (!g.s.inLine() || g.visit != null || g.inspector) continue;
                if (sh.till != null && sh.till.g == g && sh.till.c.paid()) continue;   // paid: what they still wait for has its own clock
                if (g.s.held()) continue;   // in a scene, asked something: not waiting on the worker
                if (g.s.fedUp(spec.patience())) {
                    say(g, "angry");
                    fume(g, 3);
                    lose(sh, g, Shopper.Exit.ANGRY);
                } else if (g.s.waitLine(spec.patience())) {
                    say(g, "wait");
                    fidget(g, nowMs);
                } else if (g.s.impatient(spec.patience()) && nowMs - g.nagged >= 5000) fidget(g, nowMs);
            }
            paidWaits(sh, nowMs);
            nightFalls(sh, hour);
            quickDelivery(sh, p, nowMs);
            if (sh.till != null) drawScreen(sh);
            else if (!sh.line.isEmpty()) {   // (the head of the line at the counter with no till: open one)
                for (Guest g : new ArrayList<>(sh.guests)) if (g.s == sh.line.getFirst() && g.s.state() == Shopper.State.COUNTER) atCounter(sh, g);
            }
            chores(shop, sh, p, hour);
            if (shop.tabletUntil > 0 && System.currentTimeMillis() > shop.tabletUntil) {   // the 발주 tablet back to its screen
                shop.tabletUntil = 0;
                show.furnish(shop, "order", show.wanted(shop, "order"));
            }
            sceneTick(sh, p);
            if (shop.shift != sh) continue;
            day.second(shop, sh, p, hour);
            usualClock(sh);
        }
        long now = System.currentTimeMillis();
        ordering.values().removeIf(x -> now - x.at() > PAGE_MS);   // a 발주 page nobody ordered from
        if (core.hud() != null && !working.isEmpty()) core.hud().refreshBoard();
    }

    /** Someone waiting past their patience shows it (the bus's way): an angry puff over the head, a look at the watch, a tap. */
    private void fidget(Guest g, long now) {
        g.nagged = now;
        if (!g.m.isValid()) return;
        fume(g, 1);
        if (ThreadLocalRandom.current().nextBoolean()) look(g, 0, 38, 24, false);   // the wrist
        else g.m.swingMainHand();
    }

    private static void fume(Guest g, int n) {
        if (g.m.isValid()) g.m.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, g.m.getLocation().add(0, 2.1, 0), n, 0.15, 0.05, 0.15);
    }

    /** They look another way for a while (ticks): `yaw` an offset from where they face, or with `turn` a world yaw. */
    void look(Guest g, float yaw, float pitch, int ticksFor, boolean turn) {
        g.lookYaw = yaw;
        g.lookPitch = pitch;
        g.lookTurn = turn;
        g.lookUntil = ticks + ticksFor;
    }

    /**
     * A customer who has paid does not hold the counter for ever: nobody warms their food within warm.wait seconds and they
     * take it as it is (a little sour), at the counter or waiting aside.
     */
    private void paidWaits(Shift sh, long now) {
        long limit = spec.warm().patience() * 1000L;
        Till t = sh.till;
        if (t != null && t.c.paid() && t.c.asks().contains("warm") && sh.warmFor != t.g && t.paidAt > 0 && now - t.paidAt > limit) {
            unwarmed(t.g);
            t.c.answer("warm");
            maybeDone(sh);
        }
        Parked pk = sh.parked;
        if (pk != null && sh.warmFor != pk.g && now - pk.at > limit) {
            unwarmed(pk.g);
            sh.parked = null;
            finish(sh, pk.g, pk.c, pk.free, null);
        }
    }

    private void unwarmed(Guest g) {
        g.warm = false;
        g.s.mood(spec.warm().sour());
        say(g, "unwarmed");
        fume(g, 1);
    }

    /** The night comes (or goes) during a shift: the night vest on (those who may work nights), a line. */
    private void nightFalls(Shift sh, int hour) {
        if (sh.test) return;
        boolean night = life.night().at(hour);
        if (night == sh.night) return;
        if (night && !life.mayWork(hour, book.served(sh.worker))) return;
        sh.night = night;
        for (UUID u : sh.helper == null ? List.of(sh.worker) : List.of(sh.worker, sh.helper)) {
            Player x = Bukkit.getPlayer(u);
            if (x == null) continue;
            if (isTool(x.getInventory().getChestplate())) x.getInventory().setChestplate(tool(vest(u, night)));
            if (night) x.sendMessage(Lang.msg("store.in-night", "x", String.format(java.util.Locale.ROOT, "%.1f", life.night().pay())));
        }
    }

    /** A shelf empty for two minutes with no box for it: one comes at once (the next truck may be a long way off). */
    private void quickDelivery(Shift sh, Player p, long now) {
        Chores ch = sh.shop.chores;
        if (ch.empty().isEmpty()) {
            sh.emptySince = 0;
            return;
        }
        if (sh.emptySince == 0) sh.emptySince = now;
        if (now - sh.emptySince < QUICK_MS) return;
        sh.emptySince = now;
        String box = ch.quick(spec);
        if (box == null) return;
        showBoxes(sh);
        truck(sh);
        p.sendMessage(Lang.msg("store.delivery", "n", 1));
    }

    static final long QUICK_MS = 120_000;

    /** The truck was here: the stockroom door swings open and shut, a thud. */
    private void truck(Shift sh) {
        Shop shop = sh.shop;
        World w = shop.world();
        if (w == null) return;
        Location at = shop.at(5.5, 0, 14.5, 0);
        w.playSound(at, Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 1.0f);
        backDoor(shop, true);
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            backDoor(shop, false);
            w.playSound(at, Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 1.0f);
        }, 24L);
    }

    private static void backDoor(Shop shop, boolean open) {
        World w = shop.world();
        if (w == null || !shop.loaded()) return;
        for (int y = 0; y < 2; y++) {
            int[] at = shop.site.block(StoreLayout.DOOR1, y, StoreLayout.BACK);
            org.bukkit.block.Block b = w.getBlockAt(at[0], at[1], at[2]);
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Door d) {
                d.setOpen(open);
                b.setBlockData(d, false);
            }
        }
    }

    /** AFK and away: true when the shift ended. */
    private boolean watch(Shop shop, Shift sh, Player p) {
        Location l = p.getLocation();
        long now = System.currentTimeMillis();
        if (Math.abs(l.getX() - sh.lastX) > 0.3 || Math.abs(l.getZ() - sh.lastZ) > 0.3 || Math.abs(l.getYaw() - sh.lastYaw) > 4
                || Math.abs(l.getPitch() - sh.lastPitch) > 4) {
            sh.lastActive = now;
            sh.lastX = l.getX();
            sh.lastZ = l.getZ();
            sh.lastYaw = l.getYaw();
            sh.lastPitch = l.getPitch();
        }
        switch (StoreRules.idle((now - sh.lastActive) / 1000, spec.shift().afk(), AFK_WARN)) {
            case OUT -> {
                clockOut(shop, "store.out-afk");
                return true;
            }
            case WARN -> {
                if (!sh.afkWarned) {
                    sh.afkWarned = true;
                    p.sendMessage(Lang.msg("store.afk-warn", "s", AFK_WARN));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.0f);
                }
            }
            default -> sh.afkWarned = false;
        }
        double[] m = shop.site.middle();
        boolean far = !l.getWorld().getName().equals(shop.site.world()) || Math.hypot(l.getX() - m[0], l.getZ() - m[1]) > AWAY;
        if (!far) sh.awaySince = 0;
        else if (sh.awaySince == 0) {
            sh.awaySince = now;
            p.sendMessage(Lang.msg("store.away-warn", "s", spec.shift().away()));
        } else if (now - sh.awaySince > spec.shift().away() * 1000L) {
            clockOut(shop, "store.out-away");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ customers

    /** The bus kinds that may come in (customers of store.yml that the bus has, not secret). */
    private Set<String> allowed() {
        Set<String> out = new LinkedHashSet<>();
        BusModule bus = eco.bus();
        if (bus == null || bus.spec() == null) return out;
        for (String t : spec.customers().keySet()) {
            BusSpec.Type bt = bus.spec().types().get(t);
            if (bt != null && bt.weight() > 0) out.add(t);
        }
        return out;
    }

    /** A customer walks in (a kind, or one picked for the hour). Null when none could. */
    Guest spawn(Shift sh, String kind, int hour) {
        return spawn(sh, kind, hour, null, false, null);
    }

    Guest spawn(Shift sh, String kind, int hour, Trouble.Kind force, boolean young) {
        return spawn(sh, kind, hour, force, young, null);
    }

    /**
     * The same; `force` makes them bring that trouble (진상, an admin's call), `young` makes them look young enough for the ID
     * check (with 술 · 담배 among their things), `visit` is another module's customer (its kind, skin, name, things, lines).
     * A regular (단골, 3차) may come for their usual only.
     */
    Guest spawn(Shift sh, String kind, int hour, Trouble.Kind force, boolean young, StoreVisit.Visit visit) {
        BusModule bus = eco.bus();
        if (bus == null || bus.spec() == null) return null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String typeId = visit != null ? visit.kind() : kind != null ? kind
                : spec.pickType(hour, allowed(), rnd, sh.night ? life.night().boost() : Map.of());   // 야간 근무: 취객 · 새벽 손님 more
        BusSpec.Type type = typeId == null ? null : bus.spec().types().get(typeId);
        if (type == null) return null;
        StoreSpec.Customer c = spec.customers().getOrDefault(typeId,
                new StoreSpec.Customer(typeId, 1, 1, 3, Map.of(), 0, -1, -1, Map.of()));
        Shop shop = sh.shop;
        StoreRules.Tier tier = shop.tier();
        // 단골 메뉴 (3차): one of a kind this worker served often may want only "the usual"
        long seen = book.kind(sh.worker, typeId);
        String usual = life.regulars().usual(typeId);
        boolean asksUsual = visit == null && force == null && !young && usual != null && shop.sells(usual)
                && !shop.layout().spots().getOrDefault(placeOf(usual), List.of()).isEmpty()
                && StoreLife.usualInStock(shop.chores.stocked(placeOf(usual)), shop.chores.stock(placeOf(usual)))   // (only what the worker can fetch)
                && (sh.forceUsual || life.regulars().asks(typeId, seen, rnd.nextDouble()));
        if (asksUsual) sh.forceUsual = false;
        boolean plain = visit != null || asksUsual;   // no extras, no ID, no trouble
        List<String> things = new ArrayList<>(visit != null ? visit.items() : asksUsual ? List.of()
                : spec.pickItems(c, rnd, shop.sold(), tier == null ? 0 : tier.items()));
        // 날씨 · 달력: an umbrella in the rain, an ice cream in summer
        World world = shop.world();
        java.time.ZonedDateTime kst = java.time.ZonedDateTime.now(KST);
        if (!plain) for (String x : rules.demand().extra(world != null && world.hasStorm(), kst.getMonthValue(), shop.sold(), things, rnd)) {
            if (things.size() < 5) things.add(x);
        }
        // 신분증: a kind that looks young now and then tries 술 · 담배; with them in hand it may be asked for an ID
        IdCheck.Kind idKind = plain ? null : rules.id().kinds().get(typeId);
        IdCheck.Card card = null;
        if (idKind != null) {
            List<String> restricted = new ArrayList<>();
            for (String r : rules.id().items()) if (shop.sells(r)) restricted.add(r);
            if (!restricted.isEmpty() && (young || rnd.nextDouble() < idKind.tries()) && !IdCheck.restricted(things, rules.id().items()) && things.size() < 5) {
                things.add(restricted.get(rnd.nextInt(restricted.size())));
            }
            if (IdCheck.restricted(things, rules.id().items()) && (young || rnd.nextDouble() < idKind.young())) {
                card = IdCheck.card(idKind, kst.getYear(), rules.id().age(), rules.id().names(), rnd);
            }
        }
        // 진상: the trouble this kind brings, now and then (or as the admin asked)
        Trouble.Kind trouble = plain ? null : force != null && force.type.equals(typeId) ? force : Trouble.Kind.forType(typeId);
        String refundItem = null;
        if (trouble != null) {
            StoreRules.TroubleText tt = rules.troubles().get(trouble);
            boolean comes = force == trouble || (tt != null && Trouble.comes(tt.chance(), rnd.nextDouble()));
            if (comes) switch (trouble) {
                case DRUNK -> comes = shop.has("eatbar");
                case LOTTERY -> {
                    comes = shop.sells("lottery");
                    if (comes && !things.contains("lottery")) {
                        if (things.size() >= 5) things.removeLast();
                        things.add("lottery");
                    }
                }
                case REFUND -> {   // they bring back something half eaten, and buy nothing
                    List<String> sold = new ArrayList<>();
                    for (String id : spec.products().keySet()) if (shop.sells(id) && rules.refundable(spec, id)) sold.add(id);
                    comes = !sold.isEmpty();
                    if (comes) {
                        refundItem = sold.get(rnd.nextInt(sold.size()));
                        things.clear();
                    }
                }
                case TOURIST -> comes = !Shopper.plan(shop.layout(), things, this::placeOf, "door").isEmpty();
                default -> {}
            }
            if (!comes) trouble = null;
        }
        StoreLayout.Layout l = shop.layout();
        java.util.function.Function<String, String> placeOf = this::placeOf;
        List<Shopper.Stop> round = Shopper.plan(l, things, placeOf, "door");
        double chance = spec.shinyChance() >= 0 ? spec.shinyChance() : bus.spec().shiny().chance();
        boolean shiny = visit == null && bus.spec().shiny().eligible(type) && rnd.nextDouble() < chance;
        double speed = spec.spawn().speed() / Math.max(1, Math.sqrt(type.slow()));
        int browse = (int) Math.round(40 * Math.min(3, Math.max(1, Math.sqrt(type.slow()))));
        // a mess or a 취객 asleep at the bar sours them the moment they walk in
        double start = spec.mood().start() + rules.mess().mood() * Math.min(rules.mess().most(), shop.chores.messes());
        for (Guest x : sh.guests) if (x.sleeping) start -= 5;
        Shopper s = new Shopper(typeId, shiny, round, Shopper.counterThings(things, placeOf), start, speed, type.mood(), browse,
                l.nodes().get(rnd.nextBoolean() ? StoreLayout.STREET_L : StoreLayout.STREET_R));   // walking up the pavement
        Location at = sh.shop.at(s.x(), 0, s.z(), s.yaw());
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        String skin = visit != null && visit.skin() != null ? visit.skin() : shiny ? bus.shinySkin(type, true, false) : null;
        if (skin == null) skin = BusModule.pickSkin(type, false);
        String firstSkin = skin;
        String tag = visit != null && visit.name() != null ? visit.name() : bus.typeTag(type, shiny);
        Mannequin m = bus.spawnGuest(at, type, skin, shiny, x -> tagGuest(x, tag));
        Guest g = new Guest(s, type, m);
        g.trouble = trouble;
        g.card = card;
        g.refundItem = refundItem;
        g.visit = visit;
        g.regular = visit == null && life.regulars().regular(seen);
        g.skin = firstSkin;
        if (visit != null) g.scened = true;   // (not in the scenes)
        if (asksUsual) {
            g.usual = true;
            g.usualItem = usual;
        }
        if (type.pair() && visit == null) {
            String other = shiny ? bus.shinySkin(type, true, true) : BusModule.partnerSkin(type, firstSkin, rnd);
            g.partner = bus.spawnGuest(at, type, other, shiny, x -> tagGuest(x, null));
        }
        if (shiny) BusModule.sparkle(m, true);
        sh.guests.add(g);
        s.begin(sh);
        return g;
    }

    /** They come in through the door: the bell (딸랑), their hello (or they know the bus driver), a word about the floor. */
    private void entered(Shift sh, Guest g) {
        g.entered = true;
        Location door = sh.shop.at(StoreLayout.DOOR0 + 0.5, 2, StoreLayout.FRONT + 1.5, 0);
        World w = door.getWorld();
        w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.7f);
        Bukkit.getScheduler().runTaskLater(eco, () -> w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 2.0f), 3L);
        Player worker = Bukkit.getPlayer(sh.worker);
        if (g.visit == null && !sh.test && worker != null && knows(worker, g.type.id())) say(g, "recognize");
        else say(g, "enter");
        if (g.visit == null && sh.shop.chores.messes() > 0 && ThreadLocalRandom.current().nextDouble() < 0.4) {
            Bukkit.getScheduler().runTaskLater(eco, () -> say(g, "messy"), 50L);
        }
    }

    private String placeOf(String id) {
        StoreSpec.Product p = spec.products().get(id);
        return p == null ? null : p.place();
    }

    /** Whether today is a 장날 (the bus calendar's). */
    boolean marketDay() {
        BusModule bus = eco.bus();
        return bus != null && bus.spec() != null && eco.calendar() != null && bus.spec().marketDay(eco.calendar().day());
    }

    private void tagGuest(Mannequin x, String name) {
        x.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        x.setDescription(Component.empty());   // (a mannequin's default line under its name is "NPC")
        if (name != null) {
            x.customName(Text.mm(name));
            x.setCustomNameVisible(true);
        }
    }

    /** Whether the worker carried this kind on the bus often enough to be known (people met there, recognize-at times). */
    private boolean knows(Player p, String type) {
        if (eco.people() == null) return false;
        PeopleSpec.Entry e = eco.people().of(p.getUniqueId()).get(type);
        return e != null && e.met >= spec.recognizeAt() && e.places.contains(PeopleSpec.PLACE_BUS);
    }

    /** One tick of a customer: their shopper's step, the mannequin moved there, what happened. */
    private void step(Shift sh, Guest g) {
        if (g.inspector) {   // 본사 점검 (StoreDay)
            day.stepInspector(sh, g);
            return;
        }
        if (!g.m.isValid()) {
            drop(sh, g);
            return;
        }
        Shopper.Event e = g.s.tick(sh);
        move(sh, g);
        if (!g.entered && g.s.z() >= StoreLayout.FRONT + 0.5) entered(sh, g);
        switch (e) {
            case BROWSING -> {
                if (browsing(sh, g)) break;   // a 진상 at the shelf (the 슬쩍 손님, the 관광객)
                if (ThreadLocalRandom.current().nextDouble() < 0.35) say(g, "browse");
            }
            case TOOK -> {
                hold(g);
                took(sh, g);
            }
            case EMPTY -> {
                hold(g);
                g.s.mood(spec.mood().empty());
                say(g, "empty");
                took(sh, g);
            }
            case NOTHING_FOUND -> {
                say(g, "empty");
                sh.log.lost();
                sh.line.remove(g.s);
            }
            case AT_COUNTER -> atCounter(sh, g);
            case SAT -> {
                if (sh.parked == null || sh.parked.g != g) sat(sh, g);   // (not the one waiting for the microwave)
            }
            case ATE -> ate(sh, g);
            case LEFT -> {
                if (g.stolen != null && !g.caught) escaped(sh, g);
                if (g.visit != null) day.parasol(sh.shop, g.visit.parasol());
                else if (g.bought != null && !sh.test) link.carry(sh.shop, g.type.id(), g.bought);   // to the bus stop, the bag in hand
                drop(sh, g);
            }
            default -> {}
        }
    }

    /** The mannequin (and a partner) where its shopper is now. */
    void move(Shift sh, Guest g) {
        Location to = sh.shop.at(g.s.x(), 0, g.s.z(), g.s.yaw());
        if (ticks < g.lookUntil) {   // a glance, a nod, the head down
            to.setYaw(g.lookTurn ? g.lookYaw : to.getYaw() + g.lookYaw);
            to.setPitch(g.lookPitch);
        }
        if (g.chair != null) {   // sitting on a stool: only the head turns
            if (g.s.seated() && g.chair.isValid() && g.m.isInsideVehicle()) {
                g.m.setRotation(to.getYaw(), to.getPitch());
                return;
            }
            stand(g);
        }
        Location from = g.m.getLocation();
        if (from.distanceSquared(to) > 1e-6 || Math.abs(from.getYaw() - to.getYaw()) > 1 || Math.abs(from.getPitch() - to.getPitch()) > 1) g.m.teleport(to);
        if (g.partner != null && g.partner.isValid()) {
            // a step behind on their right
            double r = Math.toRadians(g.s.yaw());
            double px = g.s.x() - Math.sin(r) * 0.55 + Math.cos(r) * 0.5, pz = g.s.z() - Math.cos(r) * 0.55 - Math.sin(r) * 0.5;
            g.partner.teleport(sh.shop.at(px, 0, pz, g.s.yaw()));
        }
    }

    /** Off the stool (they go, the shift ends). */
    private static void stand(Guest g) {
        ItemDisplay c = g.chair;
        g.chair = null;
        if (g.m.isValid() && g.m.isInsideVehicle()) g.m.leaveVehicle();
        if (c != null && c.isValid()) c.remove();
    }

    /** What they took shows in their hand (the last thing). */
    private void hold(Guest g) {
        if (g.s.got().isEmpty()) return;
        EntityEquipment eq = g.m.getEquipment();
        if (eq == null) return;
        ItemStack it = StoreBuilder.shown(core, spec.products().get(g.s.got().getLast()).item());
        if (eq.getItemInMainHand().getType().isAir() || eq.getItemInMainHand().getPersistentDataContainer().has(GUEST)) {
            it.editMeta(m -> m.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1));
            eq.setItemInMainHand(it);
        } else eq.setItemInOffHand(it);
    }

    /** They leave without buying (angry, waited too long): ★1, nothing earned. */
    private void lose(Shift sh, Guest g, Shopper.Exit why) {
        clearPrompts(sh, g);
        g.s.hold(false);
        if (sh.till != null && sh.till.g == g) clearTill(sh);
        if (sh.parked != null && sh.parked.g == g) sh.parked = null;
        if (sh.warmFor == g) {
            sh.shop.chores.resetOven();
            show.ovenAbort(sh);
            sh.warmFor = null;
        }
        sh.line.remove(g.s);
        sh.log.lost();
        g.s.leave(sh, why);
    }

    /** A customer gone out the door: their mannequins away. */
    void drop(Shift sh, Guest g) {
        if (g.visit != null && !g.visitDone && g.visitor != null) {
            g.visitDone = true;
            Player p = Bukkit.getPlayer(sh.worker);
            if (p != null) g.visitor.missed(p, sh.shop.name, g.visit);
        }
        if (sh.inspector == g) sh.inspector = null;
        clearPrompts(sh, g);
        if (sh.playing != null && sh.playing.has(g)) endScene(sh, false);
        sh.guests.remove(g);
        sh.line.remove(g.s);
        if (sh.till != null && sh.till.g == g) clearTill(sh);
        if (sh.parked != null && sh.parked.g == g) sh.parked = null;
        bubbles.forget(g.m);
        bubbles.forget(g.partner);
        stand(g);
        if (g.m.isValid()) g.m.remove();
        if (g.partner != null && g.partner.isValid()) g.partner.remove();
    }

    /** A customer says a line of their kind (a speech bubble over them). */
    void say(Guest g, String situation) {
        BusModule bus = eco.bus();
        if (bus == null || !g.m.isValid()) return;
        if (g.visit != null) {   // another module's customer says only its own lines
            sayLine(g, g.visit.lines(situation));
            return;
        }
        List<String> lines = null;
        if (g.s.shiny() && (situation.equals("enter") || situation.equals("thanks"))) {
            lines = bus.spec().shiny().lines(g.type, situation.equals("enter") ? "board" : "thanks");
        }
        if (lines == null) lines = spec.lines(g.type.id(), situation);
        if (lines == null || lines.isEmpty()) return;
        bubbles.say(g.m, lines.get(ThreadLocalRandom.current().nextInt(lines.size())), 60L,
                situation.equals("browse") ? Bubbles.Kind.CHATTER : Bubbles.Kind.LINE);
    }

    // ------------------------------------------------------------------------------------------ the till

    private void atCounter(Shift sh, Guest g) {
        if (sh.till != null) return;   // (one at a time: the line keeps the next one back)
        List<String> things = new ArrayList<>(g.s.got());
        while (things.size() > 5) things.removeLast();
        List<Integer> prices = new ArrayList<>();
        for (String t : things) prices.add(spec.products().get(t).price());
        boolean card = !sh.outage && ThreadLocalRandom.current().nextDouble() < spec.cash().card();   // 정전: cash only
        Checkout c = new Checkout(things, prices, card ? Checkout.Method.CARD : Checkout.Method.CASH);
        Till t = new Till(g, c);
        sh.till = t;
        StoreLayout.Layout l = StoreLayout.of();
        for (int i = 0; i < things.size(); i++) {
            HallLayout.Point p = l.slots().get(i);
            ItemDisplay d = display(sh.shop.at(p), spec.products().get(things.get(i)).item(), 0.75f);
            d.setTeleportDuration(4);   // a scan slides it across (§17)
            putDown(d, 0.75f, i * 3);   // one by one, out of their hands
            t.items.add(d);
        }
        reach(g, Math.min(3, things.size()), 3);
        EntityEquipment eq = g.m.getEquipment();
        if (eq != null) {
            if (eq.getItemInMainHand().getPersistentDataContainer().has(GUEST)) eq.setItemInMainHand(null);
            if (eq.getItemInOffHand().getPersistentDataContainer().has(GUEST)) eq.setItemInOffHand(null);
        }
        if (g.usual) {   // 단골 메뉴: "늘 먹던 걸로" — the worker fetches it from its shelf
            t.hold = true;
            c.ask("usual");
            g.usualAt = System.currentTimeMillis();
            sayLine(g, life.regulars().lines("ask"));
            drawScreen(sh);
            return;
        }
        if (g.visit != null && !g.visit.buttons().isEmpty()) {   // another module's customer: its buttons instead of paying
            t.hold = true;
            c.ask("visit");
            say(g, "pay");
            ask(sh, g, "visit", g.visit.buttons());
            drawScreen(sh);
            return;
        }
        if (g.refundItem == null) say(g, "pay");
        boolean warmable = false;
        for (String s : things) if (spec.warm().items().contains(s)) warmable = true;
        if (warmable && ThreadLocalRandom.current().nextDouble() < spec.warm().chance()) {
            c.ask("warm");
            g.warm = true;
            Bukkit.getScheduler().runTaskLater(eco, () -> say(g, "warm"), 40L);
        }
        // 2차: what they put down besides (a thing to refund, a parcel), then the ID check and the 진상 at the counter
        if (g.refundItem != null) t.extra.add(display(sh.shop.at(l.slots().getFirst()), spec.products().get(g.refundItem).item(), 0.75f));
        if (g.trouble == Trouble.Kind.PARCEL) t.extra.add(display(sh.shop.at(l.slots().get(3)), "economy:store_box_daily", 0.55f));
        if (!t.extra.isEmpty()) reach(g, 1, 1);
        counterQuestions(sh, g);
        // 버스 연동: "다음 버스 언제 와요?" (a stop near the store; nothing else asked)
        if (!t.hold && g.visit == null && (sh.forceBus || ThreadLocalRandom.current().nextDouble() < life.bus().ask()) && link.near(sh.shop) != null) {
            sh.forceBus = false;
            g.busAsk = true;
            sayLine(g, life.bus().lines("ask"));
            ask(sh, g, "bus", Map.of("tell", Lang.raw("store.bus-tell")));
        }
        drawScreen(sh);
    }

    /**
     * A display model (store.py's display convention, the 도박장's: spawned at yaw 0, its north side turned to the point's yaw
     * by the transformation, its bottom or centre on the point as drawn).
     */
    ItemDisplay display(Location at, String item, float scale) {
        ItemStack shown = StoreBuilder.shown(core, item);
        float yaw = at.getYaw();
        Location spot = at.clone();
        spot.setYaw(0);
        spot.setPitch(0);
        return at.getWorld().spawn(spot, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY((float) Math.toRadians(-yaw)),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
            d.setViewRange(0.4f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /**
     * The same, tipped round its own left-right axis by `tip` degrees (a POS key lying back on the counter, the ID card
     * raised to the worker): applied to the model as the display shows it (its face to the point's yaw), before the turn.
     */
    ItemDisplay tipped(Location at, String item, float scale, double tip) {
        ItemDisplay d = display(at, item, scale);
        Transformation t = d.getTransformation();
        d.setTransformation(new Transformation(t.getTranslation(), new Quaternionf(t.getLeftRotation()).rotateX((float) Math.toRadians(tip)), t.getScale(),
                t.getRightRotation()));
        return d;
    }

    /** A POS key at its place on the counter (lying back, StoreLayout.KEY_TILT up from flat). */
    private ItemDisplay posKey(Shop shop, HallLayout.Point at, String item) {
        ItemDisplay d = tipped(shop.at(at), item, BUTTON, -(90 - StoreLayout.KEY_TILT));
        d.setGlowColorOverride(Color.fromRGB(0xFFFFFF));
        return d;
    }

    /** A display put down by a hand: it starts small a little toward `from` and settles after `delay` ticks (the client's motion). */
    private void putDown(ItemDisplay d, float scale, int delay) {
        Transformation t = d.getTransformation();
        d.setTransformation(new Transformation(new Vector3f(0, 0.22f, 0), t.getLeftRotation(), new Vector3f(0.01f, 0.01f, 0.01f), t.getRightRotation()));
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(delay);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), t.getLeftRotation(), new Vector3f(scale, scale, scale), t.getRightRotation()));
        }, 2L);
    }

    /** The customer's arm, `n` times, `gap` ticks apart (putting things down, handing over a card, taking the change). */
    private void reach(Guest g, int n, int gap) {
        for (int i = 0; i < n; i++) Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (g.m.isValid()) g.m.swingMainHand();
        }, 1L + (long) i * gap);
    }

    /** A display slides by (dx, dz) in the store's own frame over `over` ticks (the client's motion), then is gone. */
    private void slideAway(Shop shop, Entity e, double dx, double dz, int over) {
        if (!(e instanceof ItemDisplay d) || !d.isValid()) {
            if (e != null) e.remove();
            return;
        }
        double[] o = shop.site.at(0, 0, 0), to = shop.site.at(dx, 0, dz);
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(over);
        d.setTransformation(new Transformation(new Vector3f((float) (to[0] - o[0]), 0.02f, (float) (to[2] - o[2])), t.getLeftRotation(), t.getScale(),
                t.getRightRotation()));
        Bukkit.getScheduler().runTaskLater(eco, d::remove, over + 2L);
    }

    /** Words over a place for a moment (a scanned price, a customer's ★), turned to whoever looks. */
    void floatText(Location at, Component text, float scale, int forTicks, float rise) {
        if (at.getWorld() == null) return;
        TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, x -> {
            x.setPersistent(false);
            x.text(text);
            x.setBillboard(Display.Billboard.CENTER);
            x.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            x.setShadowed(true);
            x.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            x.setViewRange(0.3f);
            x.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
        if (rise != 0) Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(forTicks);
            d.setTransformation(new Transformation(new Vector3f(0, rise, 0), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        }, 2L);
        Bukkit.getScheduler().runTaskLater(eco, d::remove, forTicks + 2L);
    }

    /** Every thing scanned: the customer pays (card: the terminal; cash: the money on the counter, the change buttons). */
    private void startPaying(Shift sh) {
        Till t = sh.till;
        if (t == null || t.payStarted) return;
        t.payStarted = true;
        if (t.free) {   // 외상: nothing is handed over
            t.c.settle();
            drawScreen(sh);
            maybeDone(sh);
            return;
        }
        if (t.g.visit != null && t.g.visit.exact()) {   // exact coins, counted out one by one
            coins(sh, t);
            return;
        }
        if (trouble(sh, t.g, Trouble.Stage.PAY)) {   // the 초등학생 counting coins, 할머니 looking for her purse
            t.c.ask("trouble");
            drawScreen(sh);
            return;
        }
        StoreLayout.Layout l = StoreLayout.of();
        if (t.c.method() == Checkout.Method.CARD) {
            say(t.g, "card");
            ItemDisplay card = display(sh.shop.at(l.card()), "economy:store_card", StoreLayout.MONEY);
            putDown(card, StoreLayout.MONEY, 0);
            t.paying.add(card);
            t.buttons.put("card", posKey(sh.shop, l.cardKey(), "economy:store_btn_card"));
        } else {
            say(t.g, "cash");
            boolean round = ThreadLocalRandom.current().nextDouble() < spec.cash().roundUp();
            t.c.tender(Checkout.tender(t.c.bill(), spec.cash().bills(), round));
            // what they hand over, on the mat: every note, the same ones in a pile
            List<Integer> notes = Checkout.notes(t.c.tendered(), spec.cash().bills());
            List<double[]> lie = Checkout.piles(notes);
            for (int i = 0; i < notes.size(); i++) {
                HallLayout.Point p = l.slots().get((int) Math.min(l.slots().size() - 1, lie.get(i)[0]));
                ItemDisplay d = display(sh.shop.at(p.x(), p.y() + 0.002 + StoreLayout.PILE * lie.get(i)[1], p.z(), (float) (p.yaw() + 7 * lie.get(i)[1])),
                        "economy:store_cash_" + notes.get(i), StoreLayout.MONEY);
                putDown(d, StoreLayout.MONEY, (int) lie.get(i)[0] * 2);
                t.paying.add(d);
            }
            if (!spec.cash().manual()) {
                t.c.autoChange();
            } else {
                for (Map.Entry<Integer, HallLayout.Point> b : l.changeButtons().entrySet()) {
                    t.buttons.put("chg:" + b.getKey(), posKey(sh.shop, b.getValue(), "economy:store_btn_change_" + b.getKey()));
                }
                t.buttons.put("give", posKey(sh.shop, l.give(), "economy:store_btn_give"));
                t.buttons.put("clear", posKey(sh.shop, l.clear2(), has("economy:store_btn_reset") ? "economy:store_btn_reset" : "economy:store_btn_no"));
            }
        }
        reach(t.g, 1, 1);
        if (t.c.paid()) t.paidAt = System.currentTimeMillis();
        drawScreen(sh);
        maybeDone(sh);
    }

    /** The piece of change a key stands for (a note, a coin). */
    static String changeItem(int amount) {
        return "economy:store_" + (amount >= 1000 ? "cash_" : "coin_") + amount;
    }

    /** A change key pressed: that note or coin from the key onto the cash tray, on its pile. */
    private void toTray(Shift sh, Till t, int amount) {
        List<Integer> before = t.c.counted().subList(0, Math.max(0, t.c.counted().size() - 1));
        double[] at = Checkout.trayPlace(before, amount);
        HallLayout.Point key = StoreLayout.of().changeButtons().get(amount);
        Location to = sh.shop.at(StoreLayout.TRAY_X + at[0], StoreLayout.TRAY_Y + 0.002 + StoreLayout.PILE * at[2], StoreLayout.TRAY_Z + at[1], 270);
        ItemDisplay d = display(to, changeItem(amount), StoreLayout.MONEY);
        if (key != null) {   // from under the worker's finger, over the monitor's foot, down onto the tray
            Location from = sh.shop.at(key);
            Transformation tr = d.getTransformation();
            Vector3f off = new Vector3f((float) (from.getX() - to.getX()), (float) (from.getY() - to.getY()), (float) (from.getZ() - to.getZ()));
            d.setTransformation(new Transformation(off, tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()));
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (!d.isValid()) return;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(4);
                d.setTransformation(new Transformation(new Vector3f(), tr.getLeftRotation(), tr.getScale(), tr.getRightRotation()));
            }, 2L);
        }
        t.tray.add(d);
        to.getWorld().playSound(to, amount >= 1000 ? Sound.ITEM_BOOK_PAGE_TURN : Sound.BLOCK_CHAIN_PLACE, 0.5f, amount >= 1000 ? 1.5f : 1.9f);
    }

    /** The tray's money goes: to the customer (handed over, their arm out) or back to the worker ([다시]). */
    private void clearTray(Shift sh, Till t, boolean toCustomer) {
        for (ItemDisplay d : t.tray) slideAway(sh.shop, d, toCustomer ? 0.34 : -0.4, 0, toCustomer ? 6 : 4);
        boolean any = !t.tray.isEmpty();
        t.tray.clear();
        if (!any || !toCustomer) return;
        reach(t.g, 1, 1);
        Location at = sh.shop.at(StoreLayout.TRAY_X, StoreLayout.TRAY_Y, StoreLayout.TRAY_Z, 0);
        at.getWorld().playSound(at, Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.7f);
        Bukkit.getScheduler().runTaskLater(eco, () -> at.getWorld().playSound(at, Sound.BLOCK_CHAIN_STEP, 0.5f, 2.0f), 3L);
    }

    /** What they paid with goes into the drawer (off the mat, toward the worker). */
    private void takePaid(Shift sh, Till t) {
        for (Entity e : t.paying) slideAway(sh.shop, e, -0.45, 0, 5);
        t.paying.clear();
    }

    /** The counter's screen: the total; paying cash, what was handed over and the change to give, big. */
    private void drawScreen(Shift sh) {
        Till t = sh.till;
        if (t == null) return;
        String text;
        if (t.hold) text = Lang.raw("store.screen-ask");
        else if (t.c.asks().contains("trouble")) text = Lang.raw("store.screen-ask");
        else if (!t.c.allScanned()) text = Lang.raw("store.screen-scan", "total", won(t.c.total()), "n", count(t.c), "all", t.c.size());
        else if (!t.c.paid() && t.c.method() == Checkout.Method.CARD) text = Lang.raw("store.screen-card", "total", won(t.c.bill()));
        else if (!t.c.paid()) text = Lang.raw(t.c.inHand() > 0 ? "store.screen-cash-hand" : "store.screen-cash", "total", won(t.c.bill()),
                "paid", won(t.c.tendered()), "change", won(t.c.change()), "hand", won(t.c.inHand()));
        else if (t.c.asks().contains("warm")) text = Lang.raw("store.screen-warm", "state", ovenWord(sh));
        else text = Lang.raw("store.screen-done");
        Component c = Text.mm(text);
        drawFace(sh, t);
        // on the POS monitor's face (fixed: it leans back with the monitor), its lines centred on the screen
        int lines = text.split("\n", -1).length;
        Transformation tr = new Transformation(posTextAt(lines), new Quaternionf().rotationX((float) Math.toRadians(-StoreLayout.POS_TILT)),
                new Vector3f(StoreLayout.POS_TEXT, StoreLayout.POS_TEXT, StoreLayout.POS_TEXT), new Quaternionf());
        if (t.screen != null && t.screen.isValid()) {
            t.screen.text(c);
            t.screen.setTransformation(tr);
            return;
        }
        Location at = sh.shop.at(StoreLayout.of().screen());
        t.screen = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(c);
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(tr);
            d.setViewRange(0.3f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /**
     * Where the POS text's bottom middle goes, from the middle of the monitor's text area, in the display's own frame (it
     * faces the worker: +z toward them, +y up): half the text's height down the tipped face, a hair off it.
     */
    static Vector3f posTextAt(int lines) {
        double t = Math.toRadians(StoreLayout.POS_TILT), half = StoreLayout.POS_TEXT * Bubbles.LINE * lines / 2;
        return new Vector3f(0, (float) (-half * Math.cos(t) + 0.01 * Math.sin(t)), (float) (half * Math.sin(t) + 0.01 * Math.cos(t)));
    }

    /** The customer display on its pole: the total, facing them. */
    private void drawFace(Shift sh, Till t) {
        Component c = Lang.mm("store.face", "total", won(t.c.allScanned() ? t.c.bill() : t.c.total()));
        if (t.face != null && t.face.isValid()) {
            t.face.text(c);
            return;
        }
        Location at = sh.shop.at(StoreLayout.of().customerScreen());
        t.face = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(c);
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setTransformation(new Transformation(new Vector3f(0, -0.06f, 0.01f), new Quaternionf(), new Vector3f(0.22f, 0.22f, 0.22f), new Quaternionf()));
            d.setViewRange(0.2f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private static int count(Checkout c) {
        int n = 0;
        for (int i = 0; i < c.size(); i++) if (c.scanned(i)) n++;
        return n;
    }

    private String ovenWord(Shift sh) {
        return switch (sh.shop.chores.oven()) {
            case WARMING -> Lang.raw("store.oven-warming", "s", (sh.shop.chores.ovenTicks() + 19) / 20);
            case DONE -> Lang.raw("store.oven-done");
            default -> Lang.raw("store.oven-idle");
        };
    }

    /** Paid and nothing asked: the customer thanks and goes, the shift tallies them. */
    private void maybeDone(Shift sh) {
        Till t = sh.till;
        if (t == null) return;
        if (t.c.paid() && t.c.asks().isEmpty() && trouble(sh, t.g, Trouble.Stage.PAID)) {   // the 복권 scratched right here
            t.c.ask("trouble");
            drawScreen(sh);
            return;
        }
        if (t.c.paid() && t.paidAt == 0) t.paidAt = System.currentTimeMillis();
        boolean paidTrouble = t.g.trouble != null && !t.g.troubleStarted && t.g.trouble.stage == Trouble.Stage.PAID;
        if (!paidTrouble && StoreRules.stepsAside(t.c.paid(), t.c.asks(), sh.parked != null)) {   // only the microwave left: aside, the counter is free
            park(sh, t);
            return;
        }
        if (!t.c.done()) return;
        finish(sh, t.g, t.c, t.free, t);
    }

    /**
     * Paid, their food still to warm: what they bought goes into its bag, they step aside to wait by the aisle (the next in
     * line comes up), and the microwave is theirs when the worker gets to it.
     */
    private void park(Shift sh, Till t) {
        Guest g = t.g;
        sh.parked = new Parked(g, t.c, t.free);
        clearPrompts(sh, g);
        bagUp(sh, t, g);
        clearTill(sh);
        sh.line.remove(g.s);
        g.s.aside(sh, StoreLayout.WARM);
    }

    /** What they bought slides into a GS26 bag on the counter (§17), then is in their hand. */
    private void bagUp(Shift sh, Till t, Guest g) {
        EntityEquipment eq = g.m.getEquipment();
        show.bag(sh, t, g, () -> {
            if (eq == null || !g.m.isValid()) return;
            ItemStack bag = StoreBuilder.shown(core, "economy:store_bag");
            bag.editMeta(m -> m.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1));
            if (eq.getItemInMainHand().getType().isAir()) eq.setItemInMainHand(bag);
            else if (eq.getItemInOffHand().getType().isAir()) eq.setItemInOffHand(bag);
        });
    }

    /**
     * A customer is done (paid, nothing asked any more): the shift tallies them, they thank and go (or eat first). `t`: the
     * till they stand at, null for one who waited aside.
     */
    private void finish(Shift sh, Guest g, Checkout c, boolean free, Till t0) {
        clearPrompts(sh, g);   // (a question nobody answered does not stay behind for the next customer)
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // 컵라면: hot water from the kettle (none left: disappointed); some eat it at the bar
        boolean ramen = false;
        for (String x : c.items()) if ("noodle".equals(placeOf(x))) ramen = true;
        if (ramen && sh.shop.has("kettle")) {
            if (sh.shop.chores.pour(rules.mess().water())) {
                show.kettle(sh.shop);   // §17: the water level
                if (sh.shop.has("eatbar") && rnd.nextDouble() < rules.mess().eat()) g.eats = true;
            } else {
                g.s.mood(rules.mess().dry());
                say(g, "dry");
            }
        }
        double mood = g.type.floor(g.s.mood(spec.patience()));
        int stars = spec.stars(mood);
        StoreRules.Tier tier = sh.shop.tier();
        long earn = free ? 0   // 외상: given away, nothing earned on it
                : tier == null ? spec.earn(mood, g.type.tip() * g.tipX, !c.mistake())
                : tier.earn(spec, mood, g.type.tip() * g.tipX, !c.mistake(), c.bill());
        int hour = hourOf(sh);
        if (!free) earn = life.nightPay(earn, hour, book.served(sh.worker));   // 야간 근무: × night.pay
        sh.log.served(stars, earn, c.bill(), !c.mistake());
        if (!free) sh.log.bonus(day.orderBonus(sh, c.items()));   // 발주: the day's group sold
        long paidNow = payNow(sh);
        assist(sh);
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
            HudModule.flash(p, Lang.mm("store.served", "type", g.visit != null ? Text.plain(String.valueOf(g.visit.name())) : eco.bus().typeTag(g.type, g.s.shiny()),
                    "stars", starText(stars), "g", gold(paidNow)), 2000);
            if (!sh.test && g.visit == null && eco.people() != null) eco.people().met(p, g.type.id(), g.s.shiny(), PeopleSpec.PLACE_STORE);
            career(p, sh, g.type.id(), hour);
        }
        Player helper = sh.helper == null ? null : Bukkit.getPlayer(sh.helper);
        if (helper != null) career(helper, sh, g.type.id(), hour);   // 2인 근무: both served them
        day.served(sh, p);
        g.bought = List.copyOf(c.items());
        say(g, "thanks");
        cheer(g, mood, stars);
        if (g.s.shiny()) BusModule.sparkle(g.m, false);
        boolean leaves = false;
        if (g.visit != null) {   // another module's customer paid: it hears so; maybe something stays on the counter
            g.visitDone = true;
            if (g.visitor != null && p != null) g.visitor.paid(p, sh.shop.name, g.visit);
            if (g.visit.counter() != null) {
                if (sh.visitProp != null) sh.visitProp.remove();
                sh.visitProp = display(sh.shop.at(StoreLayout.PROP_X, StoreLayout.TOP, StoreLayout.PROP_Z, 90), g.visit.counter(), 0.75f);
                leaves = true;
            }
        }
        if (t0 != null) {
            // what they bought slides into a GS26 bag on the counter (§17), then goes with them
            if (!leaves) bagUp(sh, t0, g);
            if (sh.till == t0) clearTill(sh);
        }
        sh.line.remove(g.s);
        g.tallied = true;
        afterPaid(sh, g);
    }

    /** How it went shows over them as they go: their ★ a moment, hearts when they are delighted (the bus's way). */
    private void cheer(Guest g, double mood, int stars) {
        if (!g.m.isValid()) return;
        Location at = g.m.getLocation();
        floatText(at.clone().add(0, 1.92, 0), Text.mm("<yellow>" + starText(stars)), 0.42f, 14, 0.12f);
        if (mood >= 90) at.getWorld().spawnParticle(Particle.HEART, at.clone().add(0, 2.1, 0), 2, 0.3, 0.2, 0.3);
    }

    private void clearTill(Shift sh) {
        Till t = sh.till;
        if (t == null) return;
        for (Entity e : t.items) e.remove();
        for (Entity e : t.paying) e.remove();
        for (Entity e : t.buttons.values()) e.remove();
        for (Entity e : t.extra) e.remove();
        for (Entity e : t.tray) e.remove();
        if (t.idCard != null) t.idCard.remove();
        if (t.idModel != null) t.idModel.remove();
        if (t.idPhoto != null) t.idPhoto.remove();
        if (t.screen != null) t.screen.remove();
        if (t.face != null) t.face.remove();
        sh.till = null;
    }

    // ------------------------------------------------------------------------------------------ the worker's clicks

    /** The things the worker can press now (the till's items, its buttons, the boxes), in the world. */
    private List<ButtonPick.Target> targets(Shift sh, Player viewer) {
        List<ButtonPick.Target> out = new ArrayList<>();   // (2인 근무: either worker may press anything)
        // 2차: the buttons over customers (each turned to the worker), the spills on the floor, the cups on the eat bar
        Location me = viewer.getLocation();
        for (Prompt pr : sh.prompts) for (Map.Entry<String, TextDisplay> b : pr.buttons.entrySet()) {
            TextDisplay d = b.getValue();
            if (!d.isValid()) continue;
            Location at = d.getLocation();
            double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ(), len = Math.hypot(dx, dz);
            if (len < 1e-6) {
                dx = 1;
                len = 1;
            }
            int chars = Text.plain(pr.labels.getOrDefault(b.getKey(), "")).length();
            out.add(new ButtonPick.Target(viewer.getUniqueId(), "ask:" + pr.id + ":" + b.getKey(), at.getX(), at.getY() + 0.07, at.getZ(), -dz / len, dx / len,
                    StoreRules.askHalfWidth(chars), 0.075, 0.08));
        }
        StoreLayout.Site site = sh.shop.site;
        double[] o = site.at(0, 0, 0), zf = site.at(0, 0, 1);
        double rx = zf[0] - o[0], rz = zf[2] - o[2];   // a row along the counter (local +z)
        StoreLayout.Layout l = StoreLayout.of();
        Till t = sh.till;
        if (t != null) {
            for (int i = 0; i < t.items.size(); i++) {
                if (t.c.scanned(i)) continue;
                HallLayout.Point p = l.slots().get(i);
                double[] a = site.at(p.x(), p.y() + 0.12, p.z());
                out.add(new ButtonPick.Target(viewer.getUniqueId(), "item:" + i, a[0], a[1], a[2], rx, rz, 0.14, 0.13, 0.14));
            }
            for (String key : t.buttons.keySet()) {
                HallLayout.Point p = point(l, key);
                if (p == null) continue;
                double[] a = site.at(p.x(), p.y(), p.z());
                double[] h = StoreLayout.keyBox();   // (a plate lying back: flat and deep, not tall)
                out.add(new ButtonPick.Target(viewer.getUniqueId(), key, a[0], a[1], a[2], rx, rz, h[0], h[1], h[2]));
            }
            if (t.buttons.containsKey("card")) {   // the card terminal itself too
                double[] a = site.at(l.terminal().x(), l.terminal().y(), l.terminal().z());
                out.add(new ButtonPick.Target(viewer.getUniqueId(), "card", a[0], a[1], a[2], rx, rz, 0.25, 0.08, 0.2));
            }
        }
        int i = 0;
        if (sh.shop.chores.carrying() == null) for (String box : sh.shop.chores.boxes()) {   // (a box in the arms: the fridge beside the pile is what is meant)
            HallLayout.Point p = l.boxes().get(Math.min(i++, l.boxes().size() - 1));
            double[] a = site.at(p.x(), p.y() + 0.25, p.z());
            out.add(new ButtonPick.Target(viewer.getUniqueId(), "box:" + box, a[0], a[1], a[2], rx, rz, 0.38, 0.25, 0.32));
        }
        for (String node : sh.shop.chores.spills()) {
            StoreLayout.Node n = l.nodes().get(node);
            if (n == null) continue;
            double[] a = site.at(n.x(), 0.05, n.z());
            out.add(new ButtonPick.Target(viewer.getUniqueId(), "spill:" + node, a[0], a[1], a[2], rx, rz, 0.45, 0.12, 0.45));
        }
        for (int seat = 0; seat < StoreLayout.EAT.size(); seat++) {
            if (!sh.shop.chores.cup(seat)) continue;
            double[] a = site.at(cupSpot(seat)[0], cupSpot(seat)[1] + 0.08, cupSpot(seat)[2]);
            out.add(new ButtonPick.Target(viewer.getUniqueId(), "cup:" + seat, a[0], a[1], a[2], rx, rz, 0.18, 0.12, 0.18));
        }
        return out;
    }

    /** Where a cup left at eat bar seat i stands (local x, y, z: on the bar's top). */
    static double[] cupSpot(int seat) {
        return new double[]{StoreLayout.CUP_X + seat, StoreLayout.CUP_Y, StoreLayout.CUP_Z};
    }

    private static HallLayout.Point point(StoreLayout.Layout l, String key) {
        return switch (key) {
            case "card" -> l.cardKey();
            case "give" -> l.give();
            case "clear" -> l.clear2();
            default -> key.startsWith("chg:") ? l.changeButtons().get(Integer.parseInt(key.substring(4))) : null;
        };
    }

    /** A click of a worker (any button, on anything): the thing they look at is pressed. True when something was. */
    private boolean workerClick(Player p) {
        Shop shop = working.get(p.getUniqueId());
        if (shop == null || shop.shift == null) return false;
        Shift sh = shop.shift;
        sh.lastActive = System.currentTimeMillis();
        int now = Bukkit.getCurrentTick();
        List<ButtonPick.Target> targets = targets(sh, p);
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector d = eye.getDirection();
        ButtonPick.Target hit = ButtonPick.pick(eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), p.getUniqueId(), targets);
        if (hit == null) return false;
        if (!clicks.accept(p.getUniqueId(), now)) return true;
        press(p, sh, hit.key());
        return true;
    }

    private void press(Player p, Shift sh, String key) {
        Till t = sh.till;
        Location me = p.getLocation();
        if (key.startsWith("ask:")) {
            String[] k = key.split(":", 3);
            int id = Integer.parseInt(k[1]);
            for (Prompt pr : new ArrayList<>(sh.prompts)) if (pr.id == id) {
                p.playSound(me, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.5f);
                answer(p, sh, pr, k[2]);
                break;
            }
            return;
        }
        if (key.startsWith("spill:")) {
            String node = key.substring(6);
            Chores ch = sh.shop.chores;
            if (has(StoreMotion.MOP)) {   // §17: the mop in this worker's hand, a few strokes
                if (!hands.holds(p, StoreMotion.MOP)) {
                    HudModule.flash(p, Lang.mm("store.need-mop"), 2000);
                    return;
                }
                int after = ch.wipeOnce(node);
                if (after < 0) return;
                hands.touch(p, 30);
                show.stroke(sh, p, node, after);
                if (after == 0) {
                    task(p, sh, life.staff().chore());
                    if (ch.spills().isEmpty()) hands.touch(p, 4);   // all dry: the mop goes back by itself
                }
                return;
            }
            if (!ch.mop()) {
                HudModule.flash(p, Lang.mm("store.need-mop"), 2000);
                return;
            }
            if (ch.wipe(node)) {
                Entity d = sh.messes.remove("spill:" + node);
                if (d != null) d.remove();
                p.playSound(me, Sound.ITEM_BUCKET_EMPTY, 0.6f, 1.4f);
                task(p, sh, life.staff().chore());
            }
            return;
        }
        if (key.startsWith("cup:")) {
            int seat = Integer.parseInt(key.substring(4));
            Chores ch = sh.shop.chores;
            if (!ch.cup(seat)) return;
            if (has(StoreMotion.TRAY) && !freeHands(p, StoreMotion.TRAY)) return;
            if (has(StoreMotion.TRAY) && (hands.holds(p, StoreMotion.TRAY) || hands.hold(p, StoreMotion.TRAY, 0))) {   // §17: onto the tray, to the 폐기 바구니
                ch.trayCup(seat);
                show.cupOff(sh.messes.remove("cup:" + seat), p);
                p.playSound(me, Sound.BLOCK_DECORATED_POT_INSERT, 0.6f, 1.4f);
                return;
            }
            if (ch.clearCup(seat)) {
                Entity d = sh.messes.remove("cup:" + seat);
                if (d != null) d.remove();
                p.playSound(me, Sound.ITEM_BUNDLE_INSERT, 0.6f, 1.2f);
                task(p, sh, life.staff().chore());
            }
            return;
        }
        if (key.startsWith("item:") && t != null) {
            if (!freeHands(p, null)) return;
            if (!isTool(p.getInventory().getItemInMainHand())) {
                HudModule.flash(p, Lang.mm("store.need-scanner"), 2000);
                return;
            }
            if (t.hold) {
                HudModule.flash(p, Lang.mm("store.answer-first"), 2000);
                return;
            }
            int i = Integer.parseInt(key.substring(5));
            // 스캔 단축 (경력): a higher rank scans more with one click, a regular's basket all at once (묶음 스캔)
            boolean regular = t.g.regular;
            int n = StoreLife.scanBatch(rank(p.getUniqueId()), regular, t.c.size() - count(t.c));
            List<Integer> did = t.c.scanMany(i, Math.max(1, n));
            if (did.isEmpty()) return;
            p.getWorld().playSound(me, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 2.0f);   // 삑
            for (int k = 1; k < did.size(); k++) {
                int pitch = k;
                Bukkit.getScheduler().runTaskLater(eco, () -> p.getWorld().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 2.0f - 0.05f * pitch), 2L * k);
            }
            int sum = 0;
            for (int k : did) {   // slides across to the bagging side (the display's teleport duration: the client's)
                ItemDisplay it = t.items.get(k);
                Location to = sh.shop.at(StoreLayout.of().scanned().get(k));
                to.setYaw(0);   // (its turn is in its transformation)
                if (it.isValid()) it.teleport(to);
                sum += spec.products().get(t.c.items().get(k)).price();
            }
            // what it came to, over where it went; the scanner's red dot
            HallLayout.Point first = StoreLayout.of().slots().get(did.getFirst());
            floatText(sh.shop.at(first.x(), first.y() + 0.32, first.z(), 0), Lang.mm("store.scan-plus", "won", won(sum)), 0.4f, 12, 0.1f);
            Location dot = sh.shop.at(first.x(), first.y() + 0.14, first.z(), 0);
            dot.getWorld().spawnParticle(Particle.DUST, dot, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(0xFF3020), 0.6f));
            drawScreen(sh);
            if (t.c.allScanned()) startPaying(sh);
            return;
        }
        if (key.equals("card") && t != null) {
            if (sh.outage && t.c.method() == Checkout.Method.CARD && !t.c.paid()) {   // 정전: the terminal is dead, cash then
                HudModule.flash(p, Lang.mm("store.outage-card"), 2500);
                for (Entity e : t.paying) e.remove();
                t.paying.clear();
                for (Entity e : t.buttons.values()) e.remove();
                t.buttons.clear();
                t.c = t.c.asCash();
                t.payStarted = false;
                say(t.g, "cash");
                startPaying(sh);
                return;
            }
            if (t.c.card()) {
                // the card goes into the terminal, then the beep
                HallLayout.Point term = StoreLayout.of().terminal(), card = StoreLayout.of().card();
                for (Entity e : t.paying) slideAway(sh.shop, e, term.x() - card.x(), term.z() - card.z(), 5);
                t.paying.clear();
                World w = p.getWorld();
                Location at = sh.shop.at(term);
                w.playSound(at, Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.5f, 1.6f);
                Bukkit.getScheduler().runTaskLater(eco, () -> w.playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.8f), 6L);
                for (String k : new ArrayList<>(t.buttons.keySet())) t.buttons.remove(k).remove();
                t.paidAt = System.currentTimeMillis();
                drawScreen(sh);
                maybeDone(sh);
            }
            return;
        }
        if (key.startsWith("chg:") && t != null) {
            int amount = Integer.parseInt(key.substring(4));
            t.c.press(amount);
            p.playSound(me, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.4f);
            pop(t.buttons.get(key));
            toTray(sh, t, amount);
            drawScreen(sh);
            return;
        }
        if (key.equals("clear") && t != null) {
            t.c.clear();
            p.playSound(me, Sound.BLOCK_WOODEN_BUTTON_CLICK_OFF, 0.6f, 1.0f);
            pop(t.buttons.get(key));
            clearTray(sh, t, false);
            drawScreen(sh);
            return;
        }
        if (key.equals("give") && t != null) {
            if (t.c.inHand() == 0 && t.c.change() > 0) {
                HudModule.flash(p, Lang.mm("store.give-empty", "change", won(t.c.change())), 2000);
                return;
            }
            int before = t.c.change();
            p.swingMainHand();
            pop(t.buttons.get(key));
            clearTray(sh, t, true);   // across the counter, into their hand
            switch (t.c.give()) {
                case EXACT -> {
                    t.g.s.mood(spec.mood().exact());
                    p.playSound(me, Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.8f);
                }
                case SHORT -> {
                    t.g.s.mood(spec.mood().shortChange());
                    say(t.g, "short");
                    p.playSound(me, Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
                    HudModule.flash(p, Lang.mm("store.short", "left", won(t.c.change())), 2500);
                }
                case OVER -> {
                    t.g.s.mood(spec.mood().over());
                    say(t.g, "over");
                    sh.log.overpaid(t.c.overpaid());
                    payNow(sh);
                    HudModule.flash(p, Lang.mm("store.over", "won", won(t.c.overpaid()), "change", won(before)), 3000);
                }
                default -> {}
            }
            if (t.c.paid()) {
                takePaid(sh, t);
                for (String k : new ArrayList<>(t.buttons.keySet())) t.buttons.remove(k).remove();
                t.paidAt = System.currentTimeMillis();
            }
            drawScreen(sh);
            maybeDone(sh);
            return;
        }
        if (key.startsWith("box:")) {
            String box = key.substring(4);
            if (sh.shop.chores.carrying() != null) {
                HudModule.flash(p, Lang.mm("store.box-hands-full"), 2000);
                return;
            }
            boolean visible = has(StoreMotion.heldBox(box));
            if (visible && !freeHands(p, null)) return;
            if (sh.shop.chores.pick(box)) {
                p.playSound(me, Sound.ITEM_BUNDLE_INSERT, 0.8f, 0.8f);
                if (!visible || !hands.hold(p, StoreMotion.heldBox(box), 0))   // §17: in both arms; else the old word
                    HudModule.flash(p, Lang.mm("store.box-picked", "box", Lang.raw("store.box." + box)), 2500);
                showBoxes(sh);
            }
        }
    }

    /**
     * The worker's hand for something (§17): free when it holds nothing or `forId`; a brief thing (the mop, the jug, the
     * PDA, an ID) is put back first; a box, the 폐기 bag or the tray must be put where it goes first (false, and they are told).
     */
    private boolean freeHands(Player p, String forId) {
        String h = hands.held(p);
        if (h == null || h.equals(forId)) return true;
        if (StoreMotion.brief(h)) {
            handsBack(p);
            return true;
        }
        HudModule.flash(p, Lang.mm("store.hands-full"), 1500);
        return false;
    }

    /** The held thing goes back (the scanner in hand again); the mop's bucket knows whether anyone still holds it. */
    void handsBack(Player p) {
        String was = hands.release(p);
        if (StoreMotion.MOP.equals(was)) {
            Shop shop = working.get(p.getUniqueId());
            if (shop != null) shop.chores.mop(hands.book.who(StoreMotion.MOP) != null);
            p.playSound(p.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.5f, 1.2f);
        }
    }

    /**
     * A worker leaves the shift (clock-out, a quit, the helper goes): what they held is put back where it belongs (a box by the
     * door, the mop in its bucket), and they are forgotten. Their tools are taken away by the caller.
     */
    private void dropHands(Shift sh, UUID u) {
        String h = hands.book.id(u);
        hands.forget(u);
        if (h == null) return;
        Chores ch = sh.shop.chores;
        if (h.startsWith("economy:store_held_box_") && ch.carrying() != null) {
            ch.putDown();
            showBoxes(sh);
        }
        if (h.equals(StoreMotion.MOP)) ch.mop(hands.book.who(StoreMotion.MOP) != null);
    }

    /** A pressed button grows a moment. */
    private void pop(ItemDisplay d) {
        if (d == null || !d.isValid()) return;
        Transformation tr = d.getTransformation();
        Vector3f s = new Vector3f(tr.getScale());
        d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(s).mul(1.12f), tr.getRightRotation()));
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (d.isValid()) d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), s, tr.getRightRotation()));
        }, 3L);
    }

    // ------------------------------------------------------------------------------------------ chores: boxes, shelves, the oven

    /** The boxes waiting by the back door, as displays (again from the chores). */
    private void showBoxes(Shift sh) {
        for (ItemDisplay d : sh.boxes.values()) d.remove();
        sh.boxes.clear();
        List<HallLayout.Point> spots = StoreLayout.of().boxes();
        int i = 0;
        for (String box : sh.shop.chores.boxes()) {
            HallLayout.Point p = spots.get(Math.min(i++, spots.size() - 1));
            sh.boxes.put(box, display(sh.shop.at(p), "economy:store_box_" + box, 1f));
        }
    }

    /** A shelf's model follows its stock (full / half / empty); a shelf being filled shows its fill. */
    void refreshShelf(Shop shop, String place) {
        if (shop.shift != null && shop.shift.filling.contains(place)) return;
        String state = shop.chores.state(spec, place);
        if (state.equals(shop.shown.get(place))) return;
        showShelf(shop, place, state);
    }

    /** A shelf (or the fridges) shows this state. */
    void showShelf(Shop shop, String place, String state) {
        if (state.equals(shop.shown.get(place))) return;
        if (shop.world() == null) return;
        List<String> roles = new ArrayList<>();
        String item;
        if (place.equals("fridge")) {
            roles.addAll(List.of("fridge_0", "fridge_1", "fridge_2"));
            item = "economy:store_fridge_" + state;
        } else {
            roles.add("shelf_" + place);
            item = "economy:store_shelf_" + place + "_" + state;
        }
        if (!has(item)) return;   // (the pack does not have it yet)
        ItemStack stack = StoreBuilder.shown(core, item);
        for (String r : roles) {
            ItemDisplay d = show.piece(shop, r);
            if (d != null) d.setItemStack(stack);
        }
        shop.shown.put(place, state);
    }

    private void restock(Player p, Shift sh, String place) {
        Chores ch = sh.shop.chores;
        if (ch.carrying() == null) {
            String state = ch.state(spec, place);
            HudModule.flash(p, Lang.mm(state.equals("full") ? "store.shelf-full" : "store.shelf-need-box", "shelf", Lang.raw("store.place." + place)), 2000);
            return;
        }
        String box = ch.carrying();
        UUID carrier = hands.book.who(StoreMotion.heldBox(box));
        if (carrier != null && !carrier.equals(p.getUniqueId())) {   // 2인 근무: the one with the box in their arms fills
            HudModule.flash(p, Lang.mm("store.box-other"), 1500);
            return;
        }
        int from = Math.max(0, ch.stock(place));
        if (!ch.restock(spec, place)) {
            HudModule.flash(p, Lang.mm("store.box-wrong", "box", Lang.raw("store.box." + box), "shelf", Lang.raw("store.place." + place)), 2500);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            return;
        }
        if (hands.holds(p, StoreMotion.heldBox(box))) {   // §17: the box opened by the shelf, its things onto it one by one
            handsBack(p);
            show.fill(sh, p, place, from);
        } else {
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.3f);
            HudModule.flash(p, Lang.mm("store.restocked", "shelf", Lang.raw("store.place." + place)), 2000);
            refreshShelf(sh.shop, place);
        }
        task(p, sh, life.staff().restock());
    }

    /** The microwave: food in (someone asked), the ding, out (warm), or burnt. */
    private void ovenClick(Player p, Shift sh) {
        Chores ch = sh.shop.chores;
        Till t = sh.till;
        switch (ch.oven()) {
            case IDLE -> {
                // whose food: the one waiting aside first, else the one at the counter
                Checkout want = sh.parked != null ? sh.parked.c : t != null && t.c.asks().contains("warm") ? t.c : null;
                if (want == null) {
                    HudModule.flash(p, Lang.mm("store.oven-nothing"), 2000);
                    return;
                }
                ch.startOven(spec.warm().seconds());
                sh.warmFor = sh.parked != null ? sh.parked.g : t.g;
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 1.6f);
                String food = null;
                for (String x : want.items()) if (food == null && spec.warm().items().contains(x)) food = spec.products().get(x).item();
                show.ovenIn(sh, food);   // §17: the door, the food inside, the light
                if (!ovenShown()) HudModule.flash(p, Lang.mm("store.oven-start", "s", spec.warm().seconds()), 2000);
            }
            case WARMING -> {
                if (!ovenShown()) HudModule.flash(p, Lang.mm("store.oven-wait", "s", (ch.ovenTicks() + 19) / 20), 1500);
            }
            case DONE -> {
                ch.takeOut();
                show.ovenOut(sh);
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.6f, 1.6f);
                Guest g = sh.warmFor;
                sh.warmFor = null;
                warmed(sh, g, spec.mood().warmed(), "warmed");
            }
        }
        drawOven(sh);
        drawScreen(sh);
    }

    /** The food comes out for the one who asked (warm, or burnt): they take it and go, from the counter or from aside. */
    private void warmed(Shift sh, Guest g, double mood, String line) {
        if (g == null) return;
        Till t = sh.till;
        Parked pk = sh.parked;
        if (pk != null && pk.g == g) {
            sh.parked = null;
            g.s.mood(mood);
            say(g, line);
            if (g.m.isValid()) g.m.swingMainHand();
            finish(sh, g, pk.c, pk.free, null);
        } else if (t != null && t.g == g) {
            t.c.answer("warm");
            g.s.mood(mood);
            say(g, line);
            maybeDone(sh);
        }
    }

    private void oven(Shift sh) {
        Chores ch = sh.shop.chores;
        Chores.OvenEvent e = ch.tickOven(spec.warm().forget());
        if (e == Chores.OvenEvent.NONE) {
            if (ticks % 20 == 0) {
                drawOven(sh);
                if (ch.oven() == Chores.Oven.WARMING) show.ovenSpin(sh, ticks);
                else if (ch.oven() == Chores.Oven.DONE && ticks % 40 == 0) show.steam(sh);
            }
            return;
        }
        Location at = sh.shop.at(StoreLayout.of().oven());
        if (e == Chores.OvenEvent.DING) {
            at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 2.0f);   // 띵
            show.ding(sh);
            Player p = Bukkit.getPlayer(sh.worker);
            if (p != null && !ovenShown()) HudModule.flash(p, Lang.mm("store.oven-ding"), 2500);
        } else {
            show.burnt(sh);
            Guest g = sh.warmFor;
            sh.warmFor = null;
            warmed(sh, g, spec.mood().burnt(), "burnt");
        }
        drawOven(sh);
        drawScreen(sh);
    }

    /** Whether the microwave's own models show its state (§17): no words over it then. */
    private boolean ovenShown() {
        return has("economy:store_microwave_run");
    }

    private void drawOven(Shift sh) {
        Chores ch = sh.shop.chores;
        if (ch.oven() == Chores.Oven.IDLE || ovenShown()) {
            if (sh.ovenText != null) sh.ovenText.remove();
            sh.ovenText = null;
            return;
        }
        Component c = Text.mm(ovenWord(sh));
        if (sh.ovenText != null && sh.ovenText.isValid()) {
            sh.ovenText.text(c);
            return;
        }
        Location at = sh.shop.at(StoreLayout.of().oven());
        sh.ovenText = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(c);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf()));
            d.setViewRange(0.3f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ------------------------------------------------------------------------------------------ the shift

    /** The hanger: on shift (vest, scanner) or off (pay). */
    private void hanger(Player p, Shop shop) {
        Shift sh = shop.shift;
        UUID u = p.getUniqueId();
        if (sh != null && sh.worker.equals(u)) {
            // customers inside: a second click (they all go when the worker does)
            if (sh.helper == null && !sh.guests.isEmpty() && !confirmed(p, "out|" + shop.name)) {
                HudModule.flash(p, Lang.mm("store.out-confirm", "n", sh.guests.size()), 3000);
                return;
            }
            clockOut(shop, "store.out-hanger");
            return;
        }
        if (sh != null && u.equals(sh.helper)) {
            helperOut(shop, "store.out-hanger");
            return;
        }
        if (shop.owner != null && !shop.owner.equals(u) && !book.hired(shop.name).contains(u)) {   // 내 가게: its owner and those they hired
            apply(p, shop);
            return;
        }
        World w = shop.world();
        if (!life.mayWork(w == null ? 12 : Chores.hour(w.getTime()), book.served(u))) {   // 야간 근무: 알바부터
            StoreLife.Rank need = life.rankOf(life.night().rank());
            p.sendMessage(Lang.msg("store.night-locked", "rank", need == null ? "?" : need.name()));
            return;
        }
        if (sh != null) {
            Player x = Bukkit.getPlayer(sh.worker);
            if (sh.test || sh.helper != null || !life.staff().twoIn(shop.tier) || working.containsKey(u)) {
                p.sendMessage(Lang.msg(life.staff().twoIn(shop.tier) && !sh.test && sh.helper == null ? "store.already" : "store.taken",
                        "player", x == null ? "?" : x.getName()));
                return;
            }
            join(p, shop);   // 2인 근무
            return;
        }
        clockIn(p, shop, false);
    }

    /** 남의 가게 알바: someone asks to work in a store that is not theirs; its owner is told (now, or when they come on). */
    private void apply(Player p, Shop shop) {
        String owner = String.valueOf(Bukkit.getOfflinePlayer(shop.owner).getName());
        if (!applied.computeIfAbsent(shop.name, k -> new java.util.LinkedHashSet<>()).add(p.getUniqueId())) {
            p.sendMessage(Lang.msg("store.apply-waiting", "player", owner));
            return;
        }
        p.sendMessage(Lang.msg("store.apply-sent", "player", owner, "share", Math.round(life.staff().hiredShare() * 100)));
        Player o = Bukkit.getPlayer(shop.owner);
        if (o != null) {
            askOwner(o, shop, p.getUniqueId());
            o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
        }
    }

    /** A chat button the player clicks once (within five minutes). */
    private static Component button(String key, java.util.function.Consumer<Player> act) {
        return Lang.mm(key).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(a -> {
            if (a instanceof Player p) act.accept(p);
        }, o -> o.uses(1).lifetime(java.time.Duration.ofMinutes(5))));
    }

    /** The owner is told who asked to work in their store: their name, [채용] and [거절] to click. */
    private void askOwner(Player owner, Shop shop, UUID who) {
        String name = String.valueOf(Bukkit.getOfflinePlayer(who).getName());
        owner.sendMessage(Lang.msg("store.apply-owner", "player", name).append(Component.space())
                .append(button("store.apply-yes", o -> decide(o, shop, who, true))).append(Component.space())
                .append(button("store.apply-no", o -> decide(o, shop, who, false))));
    }

    /** The owner's answer to someone who asked (only while they still ask, only the owner's). */
    private void decide(Player owner, Shop shop, UUID who, boolean hire) {
        Set<UUID> asked = applied.get(shop.name);
        if (shops.get(shop.name) != shop || !owner.getUniqueId().equals(shop.owner) || asked == null || !asked.remove(who)) return;
        String name = String.valueOf(Bukkit.getOfflinePlayer(who).getName());
        Player x = Bukkit.getPlayer(who);
        if (!hire) {
            owner.sendMessage(Lang.msg("store.apply-refused", "player", name));
            if (x != null) x.sendMessage(Lang.msg("store.apply-refused-you", "store", shop.name));
            return;
        }
        book.hire(shop.name, who);
        owner.sendMessage(Lang.msg("store.hired", "player", name, "cut", Math.round(life.staff().ownerCut() * 100)));
        owner.playSound(owner.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
        if (x != null) x.sendMessage(Lang.msg("store.hired-you", "store", shop.name, "share", Math.round(life.staff().hiredShare() * 100)));
    }

    /** The owner lets a hired worker go (their shift there, if they are on one, ends). */
    private void dismiss(Player owner, Shop shop, UUID who) {
        if (shops.get(shop.name) != shop || !owner.getUniqueId().equals(shop.owner) || !book.fire(shop.name, who)) return;
        String name = String.valueOf(Bukkit.getOfflinePlayer(who).getName());
        Shift sh = shop.shift;
        if (sh != null && who.equals(sh.helper)) helperOut(shop, "store.out-fired");
        else if (sh != null && who.equals(sh.worker)) clockOut(shop, "store.out-fired");
        owner.sendMessage(Lang.msg("store.fired", "player", name));
        Player x = Bukkit.getPlayer(who);
        if (x != null && (sh == null || shop.shift == sh)) x.sendMessage(Lang.msg("store.fired-you", "store", shop.name));
    }

    /**
     * The owner sneak-clicks their store's hanger while it is closed: the store's people in chat (who asked: [채용] [거절];
     * who works there: [해고]) and [철거]. Nothing here happens by a click on the hanger itself.
     */
    private void ownerMenu(Player p, Shop shop) {
        p.sendMessage(Lang.msg("store.manage-head", "store", shop.name, "tier", shop.tier() == null ? "" : shop.tier().name()));
        for (UUID u : new ArrayList<>(applied.getOrDefault(shop.name, Set.of()))) askOwner(p, shop, u);
        for (UUID u : new ArrayList<>(book.hired(shop.name))) {
            p.sendMessage(Lang.mm("store.manage-hired", "player", String.valueOf(Bukkit.getOfflinePlayer(u).getName())).append(Component.space())
                    .append(button("store.manage-fire", o -> dismiss(o, shop, u))));
        }
        p.sendMessage(Lang.mm("store.manage-down").append(Component.space()).append(button("store.manage-down-button", o -> takeDown(o, shop))));
    }

    /** [철거] clicked: only the owner's, only a store nobody works in, with nothing being built there. */
    private void takeDown(Player p, Shop shop) {
        if (shops.get(shop.name) != shop || !p.getUniqueId().equals(shop.owner)) return;
        switch (StoreRules.mayDemolish(true, shop.shift != null, job != null && job.running())) {
            case ON_SHIFT -> p.sendMessage(Lang.msg("store.own-down-shift"));
            case BUILDING -> p.sendMessage(Lang.msg("store.building"));
            case OK -> demolish(shop, p);
            default -> {}
        }
    }

    boolean clockIn(Player p, Shop shop, boolean test) {
        if (working.containsKey(p.getUniqueId())) {
            p.sendMessage(Lang.msg("store.already"));
            return false;
        }
        if (StoreRules.blockedByBuild(job != null && job.running(), jobStore, shop.name)) {   // (another store's building is no reason)
            p.sendMessage(Lang.msg("store.building"));
            return false;
        }
        if (eco.bus() == null || eco.bus().spec() == null) {
            p.sendMessage(Lang.msg("store.no-bus"));
            eco.getLogger().warning("[편의점] bus.yml 손님 정보가 없어 근무를 시작할 수 없음");
            return false;
        }
        if (!giveScanner(p)) {
            p.sendMessage(Lang.msg("store.inventory-full"));
            return false;
        }
        World sw = shop.world();
        boolean night = !test && sw != null && life.night().at(Chores.hour(sw.getTime()));
        PlayerInventory inv = p.getInventory();
        if (inv.getChestplate() == null || inv.getChestplate().getType().isAir()) inv.setChestplate(tool(vest(p.getUniqueId(), night)));
        Shift sh = new Shift(shop, p.getUniqueId(), test);
        sh.night = night;
        Location l = p.getLocation();
        sh.lastX = l.getX();
        sh.lastZ = l.getZ();
        sh.lastYaw = l.getYaw();
        sh.lastPitch = l.getPitch();
        sh.nextSpawn = 3;
        shop.shift = sh;
        working.put(p.getUniqueId(), shop);
        shop.shown.clear();
        show.restyle(shop);   // the shelves, the empty hanger (§17), the kettle's water, the tablet
        World w = shop.world();
        if (w != null) shop.chores.delivery(spec, Chores.slot(w.getFullTime(), spec.deliveries()));   // (the first one only remembers)
        showBoxes(sh);
        drawMesses(sh);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.1f);
        boolean hiredHere = shop.owner != null && !shop.owner.equals(p.getUniqueId());
        p.sendMessage(Lang.msg(test ? "store.in-test" : hiredHere ? "store.in-hired" : shop.owner != null ? "store.in-own" : "store.in", "store", shop.name,
                "share", Math.round(shop.share(p.getUniqueId()) * 100), "tier", shop.tier() == null ? "" : shop.tier().name(),
                "owner", shop.owner == null ? "" : String.valueOf(Bukkit.getOfflinePlayer(shop.owner).getName())));
        if (!test && night) p.sendMessage(Lang.msg("store.in-night", "x", String.format(java.util.Locale.ROOT, "%.1f", life.night().pay())));
        if (core.hud() != null) core.hud().refreshBoard();
        return true;
    }

    /** Ends a shift: everyone out, the tools back, the pay (why: the message key, null: the worker left the server). */
    void clockOut(Shop shop, String why) {
        Shift sh = shop.shift;
        if (sh == null) return;
        if (sh.helper != null && !"store.out-reload".equals(why) && !"store.out-admin".equals(why)) {   // 2인 근무: the helper takes the till
            handOverTill(shop, sh, why);
            return;
        }
        if (sh.helper != null) helperOut(shop, why);
        day.endOutage(sh, false);
        if (sh.visitProp != null) sh.visitProp.remove();
        dropHands(sh, sh.worker);
        shop.shift = null;
        working.remove(sh.worker);
        clearTill(sh);
        Player worker = Bukkit.getPlayer(sh.worker);
        for (Guest g : new ArrayList<>(sh.guests)) {
            if (g.visit != null && !g.visitDone && g.visitor != null && worker != null) {   // another module's customer never served
                g.visitDone = true;
                g.visitor.missed(worker, shop.name, g.visit);
            }
            bubbles.forget(g.m);
            bubbles.forget(g.partner);
            stand(g);
            if (g.m.isValid()) g.m.remove();
            if (g.partner != null && g.partner.isValid()) g.partner.remove();
        }
        sh.guests.clear();
        sh.line.clear();
        sh.parked = null;
        backDoor(shop, false);
        for (ItemDisplay d : sh.boxes.values()) d.remove();
        sh.boxes.clear();
        if (sh.ovenText != null) sh.ovenText.remove();
        for (Prompt pr : sh.prompts) for (Entity e : pr.buttons.values()) e.remove();
        sh.prompts.clear();
        for (Entity e : sh.messes.values()) e.remove();
        sh.messes.clear();
        if (sh.wasteTag != null) sh.wasteTag.remove();
        show.clear(sh);
        sh.playing = null;
        shop.chores.putDown();
        shop.chores.resetOven();
        shop.chores.mop(false);
        shop.chores.dumpCups();
        settleOut(shop, sh, sh.worker, sh.log, sh.test, why);
        show.restyle(shop);   // the vest back on its hanger, the microwave shut, the tablet as the order stands
        if (core.hud() != null) core.hud().refreshBoard();
    }

    /** One worker's end of a shift: the tools back, the rest of the pay (the rating bonus), the receipt, the 발주 reminder. */
    private void settleOut(Shop shop, Shift sh, UUID worker, ShiftLog log, boolean test, String why) {
        Player p = Bukkit.getPlayer(worker);
        hands.forget(worker);
        if (p != null) stripTools(p);
        double share = shop.share(worker);
        ShiftLog.Payout pay = log.payout(spec, share);
        long rest = log.settle(spec, share, true);   // the rating bonus (and anything held back), the rest was paid per customer
        if (!test && rest > 0) {
            core.economy().deposit(worker, rest, "store");
            ownerCut(shop, worker, rest);
        }
        if (p != null) {
            receipt(p, shop, sh, worker, log, pay, why, rest, test);
            if (!test && day.ordered(shop, StoreDay.gameDay(shop.world()) + 1) == null && life.reached(book.served(worker), life.order().rank())
                    && (shop.owner == null || shop.owner.equals(worker) || book.hired(shop.name).contains(worker))) {
                p.sendMessage(Lang.msg("store.order-remind"));
            }
        }
        clicks.forget(worker);
        hints.forget(worker);
    }

    /** The till's worker goes, the helper stays: the helper works on alone at the till. */
    private void handOverTill(Shop shop, Shift sh, String why) {
        UUID gone = sh.worker;
        ShiftLog log = sh.log;
        dropHands(sh, gone);
        working.remove(gone);
        settleOut(shop, sh, gone, log, sh.test, why);
        sh.worker = sh.helper;
        sh.log = sh.helperLog;
        sh.started = sh.helperStarted;
        sh.helper = null;
        sh.helperLog = null;
        sh.lastActive = System.currentTimeMillis();
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) {
            p.sendMessage(Lang.msg("store.two-alone"));
            HudModule.flash(p, Lang.mm("store.two-till"), 2500);
        }
        if (core.hud() != null) core.hud().refreshBoard();
    }

    /** The helper goes (the till's worker stays). */
    void helperOut(Shop shop, String why) {
        Shift sh = shop.shift;
        if (sh == null || sh.helper == null) return;
        UUID u = sh.helper;
        ShiftLog log = sh.helperLog;
        dropHands(sh, u);
        sh.helper = null;
        sh.helperLog = null;
        working.remove(u);
        settleOut(shop, sh, u, log, false, why);
        Player w = Bukkit.getPlayer(sh.worker);
        if (w != null) w.sendMessage(Lang.msg("store.two-left", "player", String.valueOf(Bukkit.getOfflinePlayer(u).getName())));
        if (core.hud() != null) core.hud().refreshBoard();
    }

    /** The helper: gone from the server or away from the store too long = out. */
    private void watchHelper(Shop shop, Shift sh) {
        Player h = Bukkit.getPlayer(sh.helper);
        if (h == null) {
            helperOut(shop, null);
            return;
        }
        Location l = h.getLocation();
        double[] m = shop.site.middle();
        boolean far = !l.getWorld().getName().equals(shop.site.world()) || Math.hypot(l.getX() - m[0], l.getZ() - m[1]) > AWAY;
        long now = System.currentTimeMillis();
        if (!far) sh.helperAway = 0;
        else if (sh.helperAway == 0) {
            sh.helperAway = now;
            h.sendMessage(Lang.msg("store.away-warn", "s", spec.shift().away()));
        } else if (now - sh.helperAway > spec.shift().away() * 1000L) helperOut(shop, "store.out-away");
    }

    /** 2인 근무: the second worker joins (진열); a 점장 대리 or above takes the till from someone below. */
    private void join(Player p, Shop shop) {
        Shift sh = shop.shift;
        if (!giveScanner(p)) {
            p.sendMessage(Lang.msg("store.inventory-full"));
            return;
        }
        PlayerInventory inv = p.getInventory();
        if (inv.getChestplate() == null || inv.getChestplate().getType().isAir()) inv.setChestplate(tool(vest(p.getUniqueId(), sh.night)));
        sh.helper = p.getUniqueId();
        sh.helperLog = new ShiftLog();
        sh.helperStarted = System.currentTimeMillis();
        sh.helperAway = 0;
        working.put(p.getUniqueId(), shop);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.1f);
        Player w = Bukkit.getPlayer(sh.worker);
        int deputy = life.index("deputy");
        boolean senior = rank(p.getUniqueId()).index() >= deputy && (w == null || rank(sh.worker).index() < deputy);
        if (senior) swap(sh);
        p.sendMessage(Lang.msg(senior ? "store.two-in-till" : "store.two-in", "player", w == null ? "?" : w.getName(), "share",
                Math.round(shop.share(p.getUniqueId()) * 100)));
        if (w != null) w.sendMessage(Lang.msg(senior ? "store.two-joined-senior" : "store.two-joined", "player", p.getName()));
        if (core.hud() != null) core.hud().refreshBoard();
    }

    /** The two workers swap: the till (계산) and the shelves (진열), each with their own tally. */
    private void swap(Shift sh) {
        if (sh.helper == null) return;
        UUID w = sh.worker;
        ShiftLog l = sh.log;
        long st = sh.started;
        sh.worker = sh.helper;
        sh.log = sh.helperLog;
        sh.started = sh.helperStarted;
        sh.helper = w;
        sh.helperLog = l;
        sh.helperStarted = st;
        sh.lastActive = System.currentTimeMillis();
        for (UUID u : List.of(sh.worker, sh.helper)) {
            Player x = Bukkit.getPlayer(u);
            if (x != null) HudModule.flash(x, Lang.mm(u.equals(sh.worker) ? "store.two-till" : "store.two-shelf"), 2500);
        }
    }

    /** The vest a worker wears: at night the night vest, a 점장 the manager's, else the green one. */
    private String vest(UUID u, boolean night) {
        if (night) return life.vestNight();
        if (rank(u) == life.top()) return life.vestManager();
        return VEST;
    }

    /** Pays what the shift has earned since the last pay (손님마다 바로; the rating bonus waits for clock-out). */
    long payNow(Shift sh) {
        long g = sh.log.settle(spec, sh.shop.share(sh.worker), false);
        if (g > 0 && !sh.test) {
            core.economy().deposit(sh.worker, g, "store");
            ownerCut(sh.shop, sh.worker, g);
        }
        return g;
    }

    /** 남의 가게 알바: what a hired worker was paid gives the owner their cut at once (online or not). */
    private void ownerCut(Shop shop, UUID worker, long paid) {
        if (shop.owner == null || shop.owner.equals(worker)) return;
        long cut = life.staff().ownerCut(paid);
        if (cut > 0) core.economy().deposit(shop.owner, cut, "store");
    }

    /** 2인 근무: the helper's pay since the last (their tasks, their share of each customer). */
    private long payHelper(Shift sh) {
        if (sh.helper == null || sh.helperLog == null) return 0;
        long g = sh.helperLog.settle(spec, sh.shop.share(sh.helper), false);
        if (g > 0) {
            core.economy().deposit(sh.helper, g, "store");
            ownerCut(sh.shop, sh.helper, g);
        }
        return g;
    }

    /** A customer served while two work: the 진열 worker's share of it. */
    private void assist(Shift sh) {
        if (sh.helper == null || sh.helperLog == null || life.staff().assist() <= 0) return;
        sh.helperLog.bonus(life.staff().assist());
        payHelper(sh);
    }

    /** A 진열 task done (a restock, a chore): when two work and the 진열 worker did it, it pays them. */
    private void task(Player p, Shift sh, long g) {
        if (sh.helper == null || !p.getUniqueId().equals(sh.helper) || g <= 0) return;
        sh.helperLog.bonus(g);
        long paid = payHelper(sh);
        if (paid > 0) HudModule.flash(p, Lang.mm("store.task-paid", "g", gold(paid)), 1500);
    }

    /** A customer served counts on the worker's 경력 (not on a test shift): a new rank is told, 점장's title given. */
    private void career(Player p, Shift sh, String kind, int hour) {
        if (sh.test) return;
        UUID u = p.getUniqueId();
        book.served(u, sh.shop.name, kind, life.night().at(hour));
        announce(p);
    }

    /** Tells a worker their new rank (once), what it opens; at the top the title. */
    void announce(Player p) {
        UUID u = p.getUniqueId();
        StoreLife.Rank r = life.rank(book.served(u));
        int before = book.shown(u, r.index());
        if (r.index() <= before) return;
        p.sendMessage(Lang.msg("store.unlock." + r.id(), "rank", r.name()));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
        HudModule.flash(p, Lang.mm("store.rank-flash", "rank", r.name()), 3500);
        p.getWorld().spawnParticle(Particle.FIREWORK, p.getLocation().add(0, 1.2, 0), 40, 0.4, 0.5, 0.4, 0.12);
        Shop at = working.get(u);
        if (at != null && at.shift != null) {   // the vest of the new rank on at once; whoever works beside them hears
            if (isTool(p.getInventory().getChestplate())) p.getInventory().setChestplate(tool(vest(u, at.shift.night)));
            UUID other = u.equals(at.shift.worker) ? at.shift.helper : at.shift.worker;
            Player x = other == null ? null : Bukkit.getPlayer(other);
            if (x != null) x.sendMessage(Lang.msg("store.rank-other", "player", p.getName(), "rank", r.name()));
        }
        if (r == life.top() && !life.title().isEmpty() && core.profiles() != null) core.profiles().grantTitle(u, life.title());
    }

    private void receipt(Player p, Shop shop, Shift sh, UUID worker, ShiftLog log, ShiftLog.Payout pay, String why, long rest, boolean test) {
        if (why != null) p.sendMessage(Lang.msg(why));
        long minutes = Math.max(1, (System.currentTimeMillis() - (worker.equals(sh.helper) ? sh.helperStarted : sh.started)) / 60000);
        // three lines: where and how long · the customers · the pay (what was taken off it only when something was)
        p.sendMessage(Lang.mm("store.receipt-head", "store", shop.name, "minutes", minutes));
        p.sendMessage(Lang.mm("store.receipt-people", "served", pay.served(), "lost", pay.lost(), "stars", starNumber(pay.stars()), "sales", won(pay.sales()),
                "mistakes", pay.mistakes() > 0 ? Lang.raw("store.receipt-mistakes", "n", pay.mistakes()) : ""));
        p.sendMessage(Lang.mm(test ? "store.receipt-test" : "store.receipt-pay", "pay", gold(test ? pay.pay() : log.paid()),
                "deduct", pay.deduction() > 0 ? Lang.raw("store.receipt-deduct", "g", gold(pay.deduction())) : ""));
        StoreLife.Rank r = rank(worker), next = life.next(r);
        if (!test && next != null) p.sendMessage(Lang.mm("store.receipt-rank", "rank", r.name(), "next", next.name(),
                "left", Math.max(0, next.at() - book.served(worker))));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------------------------------ tools

    ItemStack tool(String id) {
        ItemStack it = dev.stsv.economy.util.Items.create(id, 1).orElse(null);
        if (it == null) {
            boolean vest = id.contains("vest");
            it = new ItemStack(vest ? Material.LEATHER_CHESTPLATE : Material.PAPER);
            it.editMeta(m -> {
                m.customName(Lang.item(vest ? "store.vest-name" : "store.scanner-name"));
                if (m instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) lm.setColor(Color.fromRGB(id.contains("night") ? 0x2B3A67
                        : id.contains("manager") ? 0x8A1F2B : 0x1F9D55));
            });
        }
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(TOOL, PersistentDataType.BYTE, (byte) 1);
            m.lore(null);   // (the name says what it is)
        });
        return it;
    }

    static boolean isTool(ItemStack it) {
        return it != null && !it.getType().isAir() && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(TOOL);
    }

    /** The scanner into the hand (the held slot if empty, else an empty hotbar slot, held; else the held thing moves into the bag). */
    private boolean giveScanner(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack scanner = tool(SCANNER);
        int held = inv.getHeldItemSlot();
        if (inv.getItem(held) == null || inv.getItem(held).getType().isAir()) {
            inv.setItem(held, scanner);
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack at = inv.getItem(i);
            if (at == null || at.getType().isAir()) {
                inv.setItem(i, scanner);
                inv.setHeldItemSlot(i);
                return true;
            }
        }
        int free = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack at = inv.getItem(i);
            if (at == null || at.getType().isAir()) {
                free = i;
                break;
            }
        }
        if (free < 0) return false;
        inv.setItem(free, inv.getItem(held));
        inv.setItem(held, scanner);
        return true;
    }

    private static void stripTools(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isTool(inv.getItem(i))) inv.setItem(i, null);
        if (isTool(inv.getChestplate())) inv.setChestplate(null);
        if (isTool(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (isTool(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    // ------------------------------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFurniture(FurnitureInteractEvent e) {
        String name = e.getFurniture().data().get(StoreBuilder.TAG, PersistentDataType.STRING);
        String role = e.getFurniture().data().get(StoreBuilder.ROLE, PersistentDataType.STRING);
        Player p = e.getPlayer();
        Shop mine = working.get(p.getUniqueId());
        if (mine != null && workerClick(p)) {
            e.setCancelled(true);
            return;
        }
        if (name == null || role == null) return;
        Shop shop = shops.get(name);
        if (shop == null) return;
        Click what = click(role, mine == shop && shop.shift != null);
        if (what == Click.SEAT) {   // seats: anyone sits, but not someone on shift (a click meant for a cup on the bar)
            if (mine != null) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (!clicks.accept(p.getUniqueId(), Bukkit.getCurrentTick())) return;   // one click, however many events
        switch (what) {
            case HANGER -> {
                boolean owner = p.getUniqueId().equals(shop.owner);
                switch (StoreRules.sneakHanger(p.isSneaking(), mine == shop && shop.shift != null, shop.shift != null && shop.shift.helper != null,
                        owner, shop.shift != null)) {
                    case SHIFT -> hanger(p, shop);
                    case SWAP -> swap(shop.shift);   // 2인 근무: swap the till and the shelves
                    case MANAGE -> ownerMenu(p, shop);   // 내 가게, closed: its people and [철거], in chat
                    case BUSY -> HudModule.flash(p, Lang.mm("store.own-down-shift"), 2500);
                    default -> {}
                }
                return;
            }
            case TABLET -> {   // 발주 단말기 (3차): on shift or not
                day.tablet(p, shop, p.isSneaking());
                return;
            }
            case STAFF_ONLY -> {   // not working here: the store's things are the staff's (players do not shop here)
                staffOnly(p, shop);
                return;
            }
            default -> {}
        }
        Shift sh = shop.shift;
        sh.lastActive = System.currentTimeMillis();
        Chores ch = shop.chores;
        String wastePlace = rules.waste().place();
        String placeHere = role.startsWith("shelf_") ? role.substring(6) : role.startsWith("fridge_") ? "fridge" : role.equals("freezer") ? "freezer" : null;
        if (placeHere != null && fetchUsual(p, sh, placeHere)) return;   // 단골 메뉴: their usual from this shelf
        if (role.equals("shelf_" + wastePlace) && ch.carrying() == null && ch.expired(wastePlace) > 0) takeWaste(p, sh);
        else if (role.startsWith("shelf_")) restock(p, sh, role.substring(6));
        else if (role.startsWith("fridge_")) restock(p, sh, "fridge");
        else if (role.equals("microwave")) ovenClick(p, sh);
        else if (role.equals("kettle")) kettle(p, sh);
        else if (role.equals("waste")) dispose(p, sh);
        else if (role.equals("mop")) mop(p, sh);
        else if (role.equals("counter") && sh.till == null) HudModule.flash(p, Lang.mm("store.counter-empty"), 1500);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent e) {
        String name = e.getFurniture().data().get(StoreBuilder.TAG, PersistentDataType.STRING);
        if (name == null || !shops.containsKey(name)) return;
        Player p = e.getPlayer();
        if (p == null || !p.hasPermission("stsv.economy.admin") || !p.isSneaking() || working.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
            if (p != null) HudModule.flash(p, Lang.mm("store.piece-locked"), 2000);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) return;
        if (e.getAction().isRightClick() && e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND && license(e.getPlayer())) {   // 내 가게 권리증 (2차)
            e.setCancelled(true);
            return;
        }
        Shop at = working.get(e.getPlayer().getUniqueId());
        if (at == null) return;
        if (at.shift != null && e.getClickedBlock() != null && e.getAction().isRightClick() && day.breaker(e.getPlayer(), at.shift, e.getClickedBlock())) {   // 정전: the breaker
            e.setCancelled(true);
            return;
        }
        if (workerClick(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityUse(PlayerInteractEntityEvent e) {
        Entity x = e.getRightClicked();
        if (x.getPersistentDataContainer().has(GUEST)) e.setCancelled(true);
        if (working.containsKey(e.getPlayer().getUniqueId()) && (workerClick(e.getPlayer()) || accuse(e.getPlayer(), x))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(PrePlayerAttackEntityEvent e) {
        if (e.getAttacked().getPersistentDataContainer().has(GUEST)) e.setCancelled(true);
        if (working.containsKey(e.getPlayer().getUniqueId()) && (workerClick(e.getPlayer()) || accuse(e.getPlayer(), e.getAttacked()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        if (isTool(it)) e.setCancelled(true);
        else if (unpaid(it) != null) e.getItemDrop().remove();   // a leftover from when players could shop: gone
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventory(InventoryClickEvent e) {
        if (StoreHands.isHeld(e.getCurrentItem()) || StoreHands.isHeld(e.getCursor())
                || (e.getHotbarButton() >= 0 && StoreHands.isHeld(e.getWhoClicked().getInventory().getItem(e.getHotbarButton())))
                || (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && StoreHands.isHeld(e.getWhoClicked().getInventory().getItemInOffHand()))) {
            e.setCancelled(true);   // §17: a held thing stays in the hand
            return;
        }
        if (e.getView().getTopInventory().getType() == InventoryType.CRAFTING) return;   // their own inventory: move it about freely
        boolean tool = isTool(e.getCurrentItem()) || isTool(e.getCursor());
        if (!tool && e.getHotbarButton() >= 0) tool = isTool(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        if (tool) e.setCancelled(true);
    }

    /** §17: while holding something for a moment the hand stays on it (no scrolling away, no swapping hands). */
    @EventHandler(ignoreCancelled = true)
    public void onHeldSlot(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (StoreHands.isHeld(e.getPlayer().getInventory().getItem(e.getPreviousSlot()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (StoreHands.isHeld(e.getMainHandItem()) || StoreHands.isHeld(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getType() != InventoryType.CRAFTING && isTool(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(StoreModule::isTool);
        quit(e.getPlayer().getUniqueId(), "store.out-death");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        quit(e.getPlayer().getUniqueId(), null);
        stripTools(e.getPlayer());
        dropUnpaid(e.getPlayer());
        confirms.remove(e.getPlayer().getUniqueId());
        ordering.remove(e.getPlayer().getUniqueId());
        aimed.remove(e.getPlayer().getUniqueId());
    }

    /** A worker's shift ends (gone, dead): the helper's part, or the till's (the helper, if any, takes over). */
    private void quit(UUID u, String why) {
        Shop s = working.get(u);
        if (s == null || s.shift == null) return;
        if (u.equals(s.shift.helper)) helperOut(s, why);
        else clockOut(s, why);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!working.containsKey(e.getPlayer().getUniqueId())) stripTools(e.getPlayer());
        dropUnpaid(e.getPlayer());
        Shop own = owned(e.getPlayer().getUniqueId());
        Set<UUID> asked = own == null ? null : applied.get(own.name);
        if (asked != null) for (UUID u : new ArrayList<>(asked)) askOwner(e.getPlayer(), own, u);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Set<Shop> again = new LinkedHashSet<>();
        for (Entity x : e.getEntities()) {
            if (x.getPersistentDataContainer().has(GUEST)) {
                x.remove();
                continue;
            }
            String name = x.getPersistentDataContainer().get(StoreBuilder.TAG, PersistentDataType.STRING);
            String role = name == null ? null : x.getPersistentDataContainer().get(StoreBuilder.ROLE, PersistentDataType.STRING);
            Shop shop = role == null ? null : shops.get(name);
            if (shop != null && (StoreShow.STATEFUL.contains(role) || role.startsWith("shelf_") || role.startsWith("fridge_"))) again.add(shop);
        }
        // §17: a piece saved showing a state (the empty hanger, the open microwave, a page) shows the store's state now
        if (!again.isEmpty()) Bukkit.getScheduler().runTask(eco, () -> {
            for (Shop s : again) if (shops.get(s.name) == s) show.restyle(s);
        });
    }

    private void purge() {
        for (World w : Bukkit.getWorlds()) for (Entity x : w.getEntities()) if (x.getPersistentDataContainer().has(GUEST)) x.remove();
    }

    // ------------------------------------------------------------------------------------------ HUD

    private HudLayout.Board board(Player viewer) {
        Shop shop = working.get(viewer.getUniqueId());
        if (shop == null || shop.shift == null) return null;
        Shift sh = shop.shift;
        boolean helper = viewer.getUniqueId().equals(sh.helper) && sh.helperLog != null;
        ShiftLog log = helper ? sh.helperLog : sh.log;
        ShiftLog.Payout pay = log.payout(spec, shop.share(viewer.getUniqueId()));
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        if (helper) rows.add(new HudLayout.Board.Row(null, Lang.mm("store.board-helper", "pay", gold(log.paid()))));
        else {
            rows.add(new HudLayout.Board.Row(null, Lang.mm("store.board-people", "served", pay.served(), "lost", pay.lost(),
                    "stars", pay.served() + pay.lost() == 0 ? "-" : starNumber(pay.stars()))));
            rows.add(new HudLayout.Board.Row(null, Lang.mm("store.board-sales", "sales", won(pay.sales()))));
            rows.add(new HudLayout.Board.Row(null, Lang.mm(sh.test ? "store.board-pay-test" : "store.board-pay", "pay", gold(pay.pay()))));
        }
        String event = eventRow(sh);
        if (event != null) rows.add(new HudLayout.Board.Row(null, Text.mm(event)));
        List<String> todos = todos(sh);
        if (event != null && todos.size() > 3) todos = todos.subList(0, 3);
        for (String todo : todos) rows.add(new HudLayout.Board.Row(null, Text.mm(todo)));
        StoreLife.Rank r = rank(viewer.getUniqueId());
        return new HudLayout.Board(Lang.mm(sh.helper == null ? "store.board-title" : helper ? "store.board-title-shelf" : "store.board-title-till",
                "store", shop.name, "rank", r.name()), rows);
    }

    /** The board's row for what is going on (러시, 정전, 점검, 단체 손님), or null. */
    private String eventRow(Shift sh) {
        long now = System.currentTimeMillis();
        if (sh.rush != null) return Lang.raw("store.board-rush", "n", sh.rush.served(), "all", sh.rush.target(), "s", sh.rush.left(now));
        if (sh.outage) return Lang.raw("store.board-outage", "s", Math.max(0, (sh.outageUntil - now + 999) / 1000));
        if (sh.inspector != null) return Lang.raw("store.board-inspect", "s", Math.max(0, (sh.inspectUntil - now + 999) / 1000));
        if (sh.crowdLeft > 0) return Lang.raw("store.board-crowd", "n", sh.crowdLeft);
        return null;
    }

    /** What wants doing (the board's last rows, most pressing first). */
    private List<String> todos(Shift sh) {
        List<String> out = new ArrayList<>();
        Chores ch = sh.shop.chores;
        if (sh.till != null && sh.till.g.usual) out.add(Lang.raw("store.todo-usual"));
        if (!sh.prompts.isEmpty()) out.add(Lang.raw("store.todo-ask"));
        if (ch.oven() == Chores.Oven.DONE) out.add(Lang.raw("store.todo-ding"));
        else if (ch.oven() == Chores.Oven.IDLE && (sh.parked != null || (sh.till != null && sh.till.c.asks().contains("warm")))) out.add(Lang.raw("store.todo-warm"));
        if (ch.carrying() != null && hands.book.who(StoreMotion.heldBox(ch.carrying())) == null) out.add(Lang.raw("store.todo-carry", "box", Lang.raw("store.box." + ch.carrying())));
        for (String place : ch.empty()) out.add(Lang.raw("store.todo-empty", "shelf", Lang.raw("store.place." + place)));
        if (!ch.boxes().isEmpty()) out.add(Lang.raw("store.todo-boxes", "n", ch.boxes().size()));
        if (ch.carriedWaste() > 0 && hands.book.who(StoreMotion.WASTE_BAG) == null) out.add(Lang.raw("store.todo-waste-carry", "n", ch.carriedWaste()));
        else if (ch.expired(rules.waste().place()) > 0) out.add(Lang.raw("store.todo-waste", "n", ch.expired(rules.waste().place())));
        if (sh.shop.has("kettle") && ch.water(rules.mess().water()) <= 0) out.add(Lang.raw("store.todo-water"));
        if (ch.messes() > 0) out.add(Lang.raw("store.todo-mess", "n", ch.messes()));
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    private Component actionLine(Player viewer) {
        Shop shop = working.get(viewer.getUniqueId());
        if (shop == null || shop.shift == null) return null;
        Shift sh = shop.shift;
        List<ActionLine.Part> parts = new ArrayList<>();
        if (sh.rush != null) {   // 러시!
            String r = Lang.raw("store.line-rush", "n", sh.rush.served(), "all", sh.rush.target());
            parts.add(new ActionLine.Part(r, Text.plain(r), 0));
        } else if (sh.outage) {
            String r = Lang.raw("store.line-outage");
            parts.add(new ActionLine.Part(r, Text.plain(r), 0));
        }
        Till t = sh.till;
        if (t != null) {
            String who = Lang.raw("store.line-who", "type", eco.bus().typeTag(t.g.type, t.g.s.shiny()));
            parts.add(new ActionLine.Part(who, t.g.type.name(), 2));
        } else parts.add(new ActionLine.Part(Lang.raw("store.line-idle"), Text.plain(Lang.raw("store.line-idle")), 0));
        int queue = Math.max(0, sh.line.size() - (t == null ? 0 : 1));
        String q = Lang.raw("store.line-queue", "n", queue);
        parts.add(new ActionLine.Part(q, Text.plain(q), 1));
        List<ActionLine.Part> fit = ActionLine.fit(parts, ActionLine.MOST, 4);
        StringBuilder b = new StringBuilder();
        for (ActionLine.Part p : fit) {
            if (!b.isEmpty()) b.append(' ');
            b.append(p.mm());
        }
        return Text.mm(b.toString());
    }

    // ------------------------------------------------------------------------------------------ admin

    private void admin(CommandSender s, String[] a) {
        if (a.length < 1) {
            s.sendMessage(Lang.msg("store.admin-usage"));
            return;
        }
        switch (a[0]) {
            case "건설" -> adminBuild(s, a.length >= 2 ? a[1] : null);
            case "철거" -> adminRemove(s, a.length >= 2 ? a[1] : null);
            case "목록" -> adminList(s);
            case "시험" -> adminTest(s);
            case "손님" -> adminGuest(s, a.length >= 2 ? a[1] : null);
            case "배송" -> adminDelivery(s);
            case "진상" -> adminTrouble(s, a.length >= 2 ? a[1] : null);
            case "신분증" -> adminId(s);
            case "장면" -> adminScene(s, a.length >= 2 ? a[1] : null);
            case "폐기" -> adminWaste(s);
            case "청소" -> adminMess(s);
            case "권리증" -> adminLicense(s, a.length >= 2 ? a[1] : null, a.length >= 3 ? a[2] : null);
            case "내가게" -> adminOwned(s);
            case "회수" -> adminReclaim(s, a.length >= 2 ? a[1] : null);
            case "경력" -> adminCareer(s, a.length >= 2 ? a[1] : null, a.length >= 3 ? a[2] : null);
            case "러시" -> adminRush(s);
            case "정전" -> adminEvent(s, StoreLife.Event.OUTAGE);
            case "단체" -> adminEvent(s, StoreLife.Event.GROUP);
            case "점검" -> adminEvent(s, StoreLife.Event.INSPECT);
            case "발주" -> adminOrder(s, a.length >= 2 ? a[1] : null);
            case "단골" -> adminUsual(s, a.length >= 2 ? a[1] : null);
            case "버스" -> adminBus(s);
            case "알바" -> adminHire(s, a.length >= 2 ? a[1] : null, a.length >= 3 ? a[2] : null, a.length >= 4 ? a[3] : null);
            default -> s.sendMessage(Lang.msg("store.admin-usage"));
        }
    }

    private List<String> adminComplete(CommandSender s, String[] a) {
        String last = a[a.length - 1];
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("건설", "철거", "목록", "시험", "손님", "배송", "진상", "신분증", "장면", "폐기", "청소", "권리증", "내가게", "회수",
                "경력", "러시", "정전", "단체", "점검", "발주", "단골", "버스", "알바"));
        else if (a.length == 2 && a[0].equals("경력")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equals("경력")) for (StoreLife.Rank r : life.ranks()) out.add(r.name().replace(" ", ""));
        else if (a.length == 2 && a[0].equals("발주")) for (StoreLife.Group g : life.order().groups().values()) out.add(g.id());
        else if (a.length == 2 && a[0].equals("단골")) out.addAll(life.regulars().menu().keySet());
        else if (a.length == 2 && a[0].equals("알바")) out.addAll(shops.keySet());
        else if (a.length == 3 && a[0].equals("알바")) out.addAll(List.of("허용", "해제", "목록"));
        else if (a.length == 4 && a[0].equals("알바")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 2 && a[0].equals("철거")) out.addAll(shops.keySet());
        else if (a.length == 2 && a[0].equals("진상")) for (Trouble.Kind k : Trouble.Kind.values()) out.add(k.id);
        else if (a.length == 2 && a[0].equals("장면")) out.addAll(scenes.scenes().keySet());
        else if (a.length == 2 && (a[0].equals("권리증") || a[0].equals("회수"))) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (a.length == 3 && a[0].equals("권리증")) for (StoreRules.Tier t : rules.tiers().values()) out.add(t.name());
        else if (a.length == 2 && a[0].equals("손님")) {
            out.addAll(spec.customers().keySet());
            BusModule bus = eco.bus();
            if (bus != null && bus.spec() != null) for (BusSpec.Type t : bus.spec().types().values()) {
                if (t.weight() > 0 && !out.contains(t.id())) out.add(t.id());
            }
        } else if (a.length == 2 && a[0].equals("건설")) out.add("마을점");
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }

    private void adminBuild(CommandSender s, String name) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        if (job != null && job.running()) {
            p.sendMessage(Lang.msg("store.building"));
            return;
        }
        if (name == null) name = "마을점";
        if (!name.matches("[0-9A-Za-z가-힣_-]{1,16}")) {
            p.sendMessage(Lang.msg("store.bad-name"));
            return;
        }
        Location l = p.getLocation();
        StoreLayout.Site site = new StoreLayout.Site(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), StoreLayout.Site.facing(l.getYaw()));
        Shop old = shops.get(name);
        if (old != null && !old.site.equals(site)) {
            p.sendMessage(Lang.msg("store.exists", "store", name, "site", old.site.toString()));
            return;
        }
        if (old != null && old.shift != null) clockOut(old, "store.out-admin");
        final String n = name;
        try {
            job = StoreBuilder.build(eco, core, name, site, undoFile(name), settings(),
                    pct -> progress(p, pct), placed -> built(p, placed));
            jobStore = name;
        } catch (IOException | IllegalArgumentException e) {
            p.sendMessage(Lang.msg("store.build-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        if (old == null) shops.put(n, new Shop(n, site, new Chores(spec)));
        core.db().execute(core.db().upsert("eco_store", List.of("name"), List.of("site")), n, site.text());
        p.sendMessage(Lang.msg("store.build-started", "store", n, "site", site.toString()));
    }

    private void built(Player admin, StoreBuilder.Placed placed) {
        Shop shop = shops.get(placed.name());
        if (shop != null) shop.shown.clear();
        if (!admin.isOnline()) return;
        admin.sendMessage(Lang.msg("store.built", "store", placed.name()));
        for (String problem : placed.problems()) admin.sendMessage(Lang.msg("store.build-problem", "problem", problem));
    }

    private void adminRemove(CommandSender s, String name) {
        if (name == null || !shops.containsKey(name)) {
            s.sendMessage(Lang.msg("store.no-store", "stores", String.join(", ", shops.keySet())));
            return;
        }
        if (job != null && job.running()) {
            s.sendMessage(Lang.msg("store.building"));
            return;
        }
        Shop shop = shops.get(name);
        if (shop.shift != null) clockOut(shop, "store.out-admin");
        if (shop.owner != null && shop.tier() != null) {   // a 내 가게: its 권리증 back to its owner's mailbox
            UUID owner = shop.owner;
            dev.stsv.economy.util.Items.create(shop.tier().license(), 1).ifPresent(it -> eco.mail().deliver(owner, it, "store"));
        }
        try {
            job = StoreBuilder.remove(eco, core, name, shop.site, undoFile(name), settings(),
                    pct -> { if (s instanceof Player p) progress(p, pct); },
                    restored -> s.sendMessage(Lang.msg("store.removed", "store", name)));
            jobStore = name;
        } catch (IOException | IllegalArgumentException e) {
            s.sendMessage(Lang.msg("store.build-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        shops.remove(name);
        core.db().execute("DELETE FROM eco_store WHERE name = ?", name);
        book.forget(name);
        applied.remove(name);
    }

    private void adminList(CommandSender s) {
        if (shops.isEmpty()) {
            s.sendMessage(Lang.msg("store.list-empty"));
            return;
        }
        s.sendMessage(Lang.msg("store.list-head", "n", shops.size()));
        for (Shop shop : shops.values()) {
            Player w = shop.shift == null ? null : Bukkit.getPlayer(shop.shift.worker);
            if (shop.owner != null) s.sendMessage(Lang.mm("store.list-own", "store", shop.name, "owner", String.valueOf(Bukkit.getOfflinePlayer(shop.owner).getName()),
                    "tier", shop.tier() == null ? "?" : shop.tier().name()));
            s.sendMessage(Lang.mm("store.list-line", "store", shop.name, "site", shop.site.toString(),
                    "worker", w == null ? Lang.raw("store.list-closed") : Lang.raw("store.list-worker", "player", w.getName(),
                            "n", shop.shift.guests.size())));
        }
    }

    /** /관리자 편의점 시험: a shift at the nearest store without pay or codex (again: off). */
    private void adminTest(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        Shop mine = working.get(p.getUniqueId());
        if (mine != null) {
            clockOut(mine, "store.out-hanger");
            return;
        }
        Shop near = nearest(p.getLocation(), 40);
        if (near == null) {
            p.sendMessage(Lang.msg("store.none-near"));
            return;
        }
        if (near.shift != null) {
            p.sendMessage(Lang.msg("store.taken", "player", String.valueOf(Bukkit.getOfflinePlayer(near.shift.worker).getName())));
            return;
        }
        clockIn(p, near, true);
    }

    /** /관리자 편의점 손님 &lt;종류&gt;: that customer walks in now (the admin on shift). */
    private void adminGuest(CommandSender s, String kind) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        Shop shop = working.get(p.getUniqueId());
        if (shop == null || shop.shift == null) {
            p.sendMessage(Lang.msg("store.not-working"));
            return;
        }
        BusModule bus = eco.bus();
        if (kind == null || bus == null || bus.spec() == null || bus.spec().types().get(kind) == null || bus.spec().types().get(kind).weight() <= 0) {
            p.sendMessage(Lang.msg("store.guest-usage"));
            return;
        }
        if (shop.shift.guests.size() >= shop.maxInside()) {
            p.sendMessage(Lang.msg("store.guest-full", "n", shop.maxInside()));
            return;
        }
        World w = shop.world();
        Guest g = spawn(shop.shift, kind, w == null ? 12 : Chores.hour(w.getTime()));
        p.sendMessage(Lang.msg(g == null ? "store.guest-failed" : "store.guest-done", "type", bus.typeTag(bus.spec().types().get(kind), false)));
    }

    /** /관리자 편의점 배송: the truck now (a box for every shelf not full). */
    private void adminDelivery(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        Shop shop = working.get(p.getUniqueId());
        if (shop == null || shop.shift == null) {
            p.sendMessage(Lang.msg("store.not-working"));
            return;
        }
        List<String> came = shop.chores.deliver(spec);
        showBoxes(shop.shift);
        p.sendMessage(Lang.msg("store.delivery", "n", came.size()));
    }

    private Shop nearest(Location l, double within) {
        Shop best = null;
        double bestD = within;
        for (Shop s : shops.values()) {
            if (!s.site.world().equals(l.getWorld().getName())) continue;
            double[] m = s.site.middle();
            double d = Math.hypot(m[0] - l.getX(), m[1] - l.getZ());
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** The build's progress (through the HUD's own line: a bare action bar is overdrawn the next tick). */
    private static void progress(Player p, int pct) {
        HudModule.flash(p, Lang.mm("store.build-progress", "pct", pct), 1500);
    }

    private Path undoFile(String name) {
        return eco.getDataFolder().toPath().resolve("store").resolve(name + ".undo");
    }

    private StoreBuilder.HallBuilderSettings settings() {
        return StoreBuilder.HallBuilderSettings.of(HallBuilder.Settings.of(eco.getConfig()));
    }

    // ==========================================================================================
    // 2차 (README "편의점", 기획안 §5 §6 §7 §13): questions over customers, 진상 · 신분증, 폐기 · 청소 · 온수, scenes,
    // player customers, 내 가게
    // ==========================================================================================

    /** The bus kinds whose presence makes the 건달 behave (Trouble.skip). */
    static final Set<String> GUARDIANS = Set.of("grandma", "police", "wrestler");

    /** Buttons over a customer: a trouble's choices or the ID check's (choice -> its button), and when they appeared. */
    static final class Prompt {
        final int id;
        final Guest g;
        final String what;
        final long at = System.currentTimeMillis();
        final Map<String, TextDisplay> buttons = new LinkedHashMap<>();
        final Map<String, String> labels = new LinkedHashMap<>();

        Prompt(int id, Guest g, String what) {
            this.id = id;
            this.g = g;
            this.what = what;
        }
    }

    /** A store scene being played: the scene, who plays each role, the next line and when it is due (ms). */
    final class Playing {
        final StoreScenes.Scene scene;
        final Guest[] cast;
        int next;
        long at;

        Playing(StoreScenes.Scene scene, Guest[] cast, long at) {
            this.scene = scene;
            this.cast = cast;
            this.at = at;
        }

        boolean has(Guest g) {
            for (Guest x : cast) if (x == g) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------ the buttons

    /** Shows buttons over a customer (at the counter: over its end), choice -> words, the first lowest. */
    private Prompt ask(Shift sh, Guest g, String what, Map<String, String> choices) {
        clearPrompts(sh, g);
        Prompt pr = new Prompt(++promptIds, g, what);
        boolean till = sh.till != null && sh.till.g == g;
        Location base = till ? sh.shop.at(StoreLayout.ASK_X, StoreLayout.ASK_Y, StoreLayout.ASK_Z, 0) : g.m.getLocation().add(0, StoreLayout.ASK_HEAD, 0);
        base.setYaw(0);
        base.setPitch(0);
        if (!till) g.m.setCustomNameVisible(false);   // (their name tag is where the buttons go)
        int i = 0;
        for (Map.Entry<String, String> c : choices.entrySet()) {
            if (c.getValue() == null || c.getValue().isBlank()) continue;
            Location at = base.clone().add(0, StoreLayout.ASK_STEP * i++, 0);
            pr.labels.put(c.getKey(), c.getValue());
            Component text = Lang.mm("store.ask-button", "label", c.getValue());
            pr.buttons.put(c.getKey(), at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setBackgroundColor(Color.fromARGB(225, 31, 157, 85));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf()));
                d.setViewRange(0.35f);
                d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
            }));
        }
        sh.prompts.add(pr);
        for (Guest x : sh.guests) {   // the bubbles near the buttons out of their way (or gone when there is no room)
            bubbles.refit(x.m);
            bubbles.refit(x.partner);
        }
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.6f);
        }
        return pr;
    }

    private void clearPrompts(Shift sh, Guest g) {
        for (Prompt pr : new ArrayList<>(sh.prompts)) {
            if (pr.g != g) continue;
            sh.prompts.remove(pr);
            for (Entity e : pr.buttons.values()) e.remove();
        }
        nameBack(g);
    }

    /** The name tag hidden under a customer's buttons shows again. */
    private static void nameBack(Guest g) {
        if (g.m.isValid() && g.m.customName() != null) g.m.setCustomNameVisible(true);
    }

    /** A line from a list over a customer (a speech bubble), nothing when the list is empty. */
    void sayLine(Guest g, List<String> lines) {
        sayLine(g, lines, Bubbles.Kind.LINE);
    }

    /** The same; chatter (everyone at once: the 정전, the 수학여행) is let go when there is no room for it now. */
    void sayLine(Guest g, List<String> lines, Bubbles.Kind kind) {
        if (lines == null || lines.isEmpty() || !g.m.isValid()) return;
        bubbles.say(g.m, lines.get(ThreadLocalRandom.current().nextInt(lines.size())), 70L, kind);
    }

    /**
     * The store's own text over its things near a place (a customer's bubble keeps clear of them): the green answer buttons,
     * the 폐기 tag, the microwave's timer. (The till's screen is on the POS monitor, under every bubble.)
     */
    List<Bubbles.Band> fixedBands(Location at) {
        List<Bubbles.Band> out = new ArrayList<>();
        for (Shop shop : shops.values()) {
            Shift sh = shop.shift;
            if (sh == null || !shop.site.inside(at.getWorld().getName(), at.getX(), at.getY(), at.getZ())) continue;
            for (Prompt pr : sh.prompts) for (TextDisplay d : pr.buttons.values()) band(out, d, 1);
            band(out, sh.wasteTag, 1);
            band(out, sh.ovenText, 2);
            break;
        }
        return out;
    }

    private static void band(List<Bubbles.Band> out, TextDisplay d, int lines) {
        if (d == null || !d.isValid()) return;
        Location l = d.getLocation();
        double y = l.getY() + d.getTransformation().getTranslation().y();
        out.add(new Bubbles.Band(l.getX(), l.getZ(), y, y + textTall(d.getTransformation().getScale().y(), lines)));
    }

    /** A text display's height (blocks) at this scale for so many lines (its background's edge too). */
    private static double textTall(float scale, int lines) {
        return scale * (lines * Bubbles.LINE + 0.05);
    }

    private Trouble.Context context(Shift sh, Guest g) {
        boolean guardian = false;
        for (Guest x : sh.guests) if (x != g && GUARDIANS.contains(x.type.id()) && x.s.state() != Shopper.State.LEAVE) guardian = true;
        Till t = sh.till;
        int bill = t != null && t.g == g ? t.c.bill() : 0;
        int refund = g.refundItem == null ? 0 : spec.products().get(g.refundItem).price();
        return new Trouble.Context(g.s.mood(spec.patience()), guardian, sh.shop.chores.expired(rules.waste().place()) > 0, bill, refund, Math.max(0, g.prize));
    }

    // ------------------------------------------------------------------------------------------ 진상

    /** The guest's trouble starts now if this is its stage: true when it did (they wait for the worker's answer). */
    private boolean trouble(Shift sh, Guest g, Trouble.Stage stage) {
        Trouble.Kind k = g.trouble;
        if (k == null || g.troubleStarted || k.stage != stage) return false;
        StoreRules.TroubleText tt = rules.troubles().get(k);
        if (tt == null) return false;
        g.troubleStarted = true;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (Trouble.skip(k, context(sh, g))) {   // the 건달 with a 할머니 in the store: pays like anyone
            sayLine(g, tt.lines("behave"));
            g.troubleDone = true;
            return false;
        }
        Map<String, String> choices = new LinkedHashMap<>();
        switch (k) {
            case LOTTERY -> {
                g.prize = Trouble.scratch(rules.numbers(), rnd.nextDouble());
                sayLine(g, tt.lines("start"));
                Bukkit.getScheduler().runTaskLater(eco, () -> {   // scratch, scratch… the result, then the buttons
                    if (sh.shop.shift != sh || !sh.guests.contains(g)) return;
                    sayLine(g, tt.lines(g.prize >= 2 ? "big" : g.prize == 1 ? "small" : "none"));
                    if (g.prize >= 2) g.m.getWorld().playSound(g.m.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    Map<String, String> c = new LinkedHashMap<>();
                    c.put("cheer", tt.buttons().get(g.prize > 0 ? "cheer" : "cheer-none"));
                    c.put("shrug", tt.buttons().get("shrug"));
                    ask(sh, g, "trouble", c);
                }, 50L);
                return true;
            }
            case TOURIST -> {
                Shopper.Stop st = g.s.browsing();
                if (st == null) return false;
                g.touristPlace = st.place();
                g.s.hold(true);
                List<String> places = new ArrayList<>(sh.shop.layout().spots().keySet());
                List<String> wrong = Trouble.wrong(st.place(), places, rnd);
                List<String> order = new ArrayList<>(List.of("right"));
                if (!wrong.isEmpty()) order.add(rnd.nextBoolean() ? 0 : 1, "wrong");
                for (String o : order) choices.put(o, Lang.raw("store.place." + (o.equals("right") ? st.place() : wrong.getFirst())));
                // what they look for, held up
                EntityEquipment eq = g.m.getEquipment();
                if (eq != null && !st.things().isEmpty()) {
                    ItemStack it = StoreBuilder.shown(core, spec.products().get(st.things().getFirst()).item());
                    it.editMeta(m -> m.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1));
                    eq.setItemInMainHand(it);
                }
            }
            case DRUNK -> {
                choices.put("wake", tt.buttons().get("wake"));
                if (sh.shop.sells("hangover")) choices.put("hangover", tt.buttons().get("hangover"));
            }
            default -> {
                for (String c : k.choices) choices.put(c, tt.buttons().get(c));
            }
        }
        sayLine(g, tt.lines("start"));
        ask(sh, g, "trouble", choices);
        return true;
    }

    /** At the counter: first the ID check (술 · 담배 from someone young-looking), then the counter's 진상. */
    private void counterQuestions(Shift sh, Guest g) {
        Till t = sh.till;
        if (t == null || t.g != g) return;
        if (g.card != null && IdCheck.restricted(t.c.items(), rules.id().items())) {
            t.hold = true;
            t.c.ask("id");
            show.idOut(g);   // §17: the card held out
            sayLine(g, rules.id().lines("ask"));
            Map<String, String> c = new LinkedHashMap<>();
            c.put("check", Lang.raw("store.id-check"));
            c.put("sell", Lang.raw("store.id-sell"));
            c.put("refuse", Lang.raw("store.id-refuse"));
            ask(sh, g, "id", c);
            return;
        }
        if (trouble(sh, g, Trouble.Stage.COUNTER)) {
            t.hold = true;
            t.c.ask("trouble");
        }
    }

    /** A button over a customer pressed. */
    private void answer(Player p, Shift sh, Prompt pr, String choice) {
        Guest g = pr.g;
        sh.prompts.remove(pr);
        for (Entity e : pr.buttons.values()) e.remove();
        nameBack(g);
        if (!sh.guests.contains(g)) return;
        if (pr.what.equals("id")) {
            idAnswer(p, sh, g, choice);
            return;
        }
        if (pr.what.equals("bus")) {   // 버스 연동: the nearest running bus's driver, or none
            g.busAsk = false;
            g.s.mood(life.bus().askMood());
            String line = link.answer(sh.shop);
            if (!line.isEmpty()) bubbles.say(g.m, line, 70L, Bubbles.Kind.LINE);
            return;
        }
        if (pr.what.equals("visit")) {
            visitAnswer(p, sh, g, choice);
            return;
        }
        Trouble.Kind k = g.trouble;
        if (k == null) return;
        Trouble.Outcome o = Trouble.resolve(k, choice, context(sh, g), rules.numbers(), ThreadLocalRandom.current().nextDouble());
        if (o != null) apply(p, sh, g, k, o);
    }

    /** What an answer did: the line, the moods, the pay, then the customer and the till go on (or out). */
    private void apply(Player p, Shift sh, Guest g, Trouble.Kind k, Trouble.Outcome o) {
        StoreRules.TroubleText tt = rules.troubles().get(k);
        g.troubleDone = true;
        if (tt != null) sayLine(g, tt.lines(o.talk()));
        g.s.mood(o.mood());
        if (o.others() != 0) for (Guest x : sh.guests) if (x != g) x.s.mood(o.others());
        g.tipX *= o.tip();
        if (o.bonus() > 0) sh.log.bonus(o.bonus());
        if (o.won() > 0) sh.log.covered(o.won());
        payNow(sh);
        if (o.waste() && sh.shop.chores.giveExpired(rules.waste().place())) {
            drawWaste(sh);
            refreshShelf(sh.shop, rules.waste().place());
        }
        if (o.gift() != null && p != null) giveFood(p, o.gift());
        if (o.fanfare()) fanfare(sh, g);
        moodPuff(g, o.mood());
        String fx = fx(o);   // words only when money moved
        if (p != null && !fx.isEmpty()) HudModule.flash(p, Lang.mm("store.trouble-done", "fx", fx), 2500);
        if (k == Trouble.Kind.TOURIST) {
            EntityEquipment eq = g.m.getEquipment();
            if (eq != null && eq.getItemInMainHand().getPersistentDataContainer().has(GUEST)) eq.setItemInMainHand(null);
        }
        Till t = sh.till;
        boolean mine = t != null && t.g == g;
        switch (o.result()) {
            case GO_ON -> {
                g.s.hold(false);
                if (mine) {
                    t.hold = false;
                    t.c.answer("trouble");
                    drawScreen(sh);
                    maybeDone(sh);
                }
            }
            case LEAVE_LOST -> lose(sh, g, Shopper.Exit.ANGRY);
            case LEAVE_HANDLED -> handledOut(sh, g);
            case FREE -> {
                if (mine) {
                    t.free = true;
                    t.hold = false;
                    t.c.answer("trouble");
                    drawScreen(sh);
                }
            }
            case EXACT -> {
                if (mine) {
                    t.c.answer("trouble");
                    coins(sh, t);
                }
            }
            case PUT_BACK -> {
                if (!mine) break;
                List<String> keep = new ArrayList<>(t.c.items());
                if (!keep.isEmpty()) keep.removeLast();
                if (keep.isEmpty()) {
                    handledOut(sh, g);
                    break;
                }
                retill(sh, keep);
                for (int i = 0; i < t.c.size(); i++) {
                    t.c.scan(i);
                    Location to = sh.shop.at(StoreLayout.of().scanned().get(i));
                    to.setYaw(0);
                    if (t.items.get(i).isValid()) t.items.get(i).teleport(to);
                }
                t.c.answer("trouble");
                coins(sh, t);
            }
        }
    }

    /** They pay the exact bill in coins: nothing to give back. */
    private void coins(Shift sh, Till t) {
        StoreLayout.Layout l = StoreLayout.of();
        HallLayout.Point at = l.slots().get(1);
        t.paying.add(display(sh.shop.at(at.x(), at.y() + 0.01, at.z(), at.yaw()), "economy:store_coin_500", 1f));
        t.paying.add(display(sh.shop.at(at.x() + 0.12, at.y() + 0.02, at.z() + 0.1, at.yaw() + 20), "economy:store_coin_100", 1f));
        t.c.settle();
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.8f);
        drawScreen(sh);
        maybeDone(sh);
    }

    /** "+150 G · -100 G": the money an answer moved, for the worker's flash (empty: none did). */
    private String fx(Trouble.Outcome o) {
        List<String> parts = new ArrayList<>();
        if (o.bonus() > 0) parts.add(Lang.raw("store.fx-bonus", "g", gold(o.bonus())));
        if (o.won() > 0) parts.add(Lang.raw("store.fx-covered", "g", gold(Math.round(o.won() * spec.pay().overpayRate()))));
        return String.join(" <dark_gray>·</dark_gray> ", parts);
    }

    /** How an answer sat with them, over their head (not a number): a heart, a green sparkle, an angry puff. */
    private static void moodPuff(Guest g, double change) {
        if (!g.m.isValid() || change == 0) return;
        Location at = g.m.getLocation().add(0, 2.1, 0);
        if (change >= 10) at.getWorld().spawnParticle(Particle.HEART, at, 2, 0.25, 0.15, 0.25);
        else if (change > 0) at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 5, 0.25, 0.15, 0.25);
        else at.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, at, change <= -15 ? 2 : 1, 0.15, 0.05, 0.15);
    }

    /** A customer out without buying, the worker having done their part (★ by mood, not lost). */
    private void handledOut(Shift sh, Guest g) {
        clearPrompts(sh, g);
        g.s.hold(false);
        if (sh.till != null && sh.till.g == g) clearTill(sh);
        sh.line.remove(g.s);
        g.seat = -1;
        g.sleeping = false;
        stand(g);
        if (!g.tallied) {
            g.tallied = true;
            sh.log.handled(spec.stars(g.type.floor(g.s.mood(spec.patience()))), 0);
            payNow(sh);
            met(sh, g);
        }
        g.s.leave(sh, Shopper.Exit.PAID);
    }

    /** The 인물 도감: met at the store (not on a test shift). */
    private void met(Shift sh, Guest g) {
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null && !sh.test && eco.people() != null) eco.people().met(p, g.type.id(), g.s.shiny(), PeopleSpec.PLACE_STORE);
    }

    /** The till's things again (a refused 술, a thing put back): the same customer, new displays, nothing scanned. */
    private void retill(Shift sh, List<String> keep) {
        Till t = sh.till;
        if (t == null) return;
        for (Entity e : t.items) e.remove();
        t.items.clear();
        List<Integer> prices = new ArrayList<>();
        for (String x : keep) prices.add(spec.products().get(x).price());
        Checkout c = new Checkout(keep, prices, t.c.method());
        for (String a : t.c.asks()) c.ask(a);
        t.c = c;
        StoreLayout.Layout l = StoreLayout.of();
        for (int i = 0; i < keep.size(); i++) t.items.add(display(sh.shop.at(l.slots().get(i)), spec.products().get(keep.get(i)).item(), 0.75f));
        drawScreen(sh);
    }

    /** A button over another module's customer: what it says, the counter's thing taken, a figure outside, it goes. */
    private void visitAnswer(Player p, Shift sh, Guest g, String choice) {
        if (g.visitor == null || g.visit == null) return;
        StoreVisit.Reply r;
        try {
            r = g.visitor.answered(p, sh.shop.name, g.visit, choice);
        } catch (RuntimeException e) {
            eco.getLogger().log(java.util.logging.Level.WARNING, "[편의점] 방문 응답 오류", e);
            r = new StoreVisit.Reply(List.of(), false, null, true);
        }
        g.visitDone = true;
        if (r.takeCounter() && sh.visitProp != null) {
            sh.visitProp.remove();
            sh.visitProp = null;
            p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.6f, 1.1f);
        }
        long at = 0;
        for (String line : r.lines()) {
            long when = at;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (g.m.isValid()) bubbles.say(g.m, line, 70L, Bubbles.Kind.LINE);
            }, when);
            at += 70;
        }
        String figure = r.figure();
        if (figure != null) Bukkit.getScheduler().runTaskLater(eco, () -> day.figure(sh.shop, g.visit.kind(), figure), at + 40);
        Till t = sh.till;
        if (r.leave()) {
            long go = Math.max(20, at);
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (sh.shop.shift != sh || !sh.guests.contains(g)) return;
                if (sh.till != null && sh.till.g == g) clearTill(sh);
                sh.line.remove(g.s);
                g.tallied = true;
                g.s.hold(false);
                g.s.leave(sh, Shopper.Exit.PAID);
            }, go);
            if (t != null && t.g == g) g.s.hold(true);
        } else if (t != null && t.g == g) {
            t.hold = false;
            t.c.answer("visit");
            drawScreen(sh);
        }
    }

    // ------------------------------------------------------------------------------------------ 단골 메뉴

    /**
     * The worker clicked a shelf (this place) while the customer at the till wants their usual: the right shelf hands it over
     * (big mood, a tip, the 도감 learns it), a wrong one disappoints. False when nobody is waiting for theirs.
     */
    private boolean fetchUsual(Player p, Shift sh, String place) {
        Till t = sh.till;
        if (t == null || !t.g.usual || !t.c.asks().contains("usual")) return false;
        Guest g = t.g;
        if (!place.equals(placeOf(g.usualItem))) {
            if (!g.wrongUsual) {   // once: it stings, then they just say what it is
                g.wrongUsual = true;
                g.s.mood(life.regulars().wrong());
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            }
            usualHint(g);
            return true;
        }
        if (!sh.shop.chores.take(place)) {   // sold out meanwhile: they come another day (nobody's fault)
            sayLine(g, life.regulars().lines("none"));
            g.usual = false;
            handledOut(sh, g);
            return true;
        }
        refreshShelf(sh.shop, place);
        g.usual = false;
        g.s.mood(life.regulars().mood());
        g.tipX *= life.regulars().tip();
        sayLine(g, life.regulars().lines("right"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.7f);
        String name = spec.products().get(g.usualItem).name();
        boolean first = !sh.test && book.usual(p.getUniqueId(), g.type.id());
        HudModule.flash(p, Lang.mm(first ? "store.usual-learnt" : "store.usual-right", "item", name, "type", eco.bus().typeTag(g.type, g.s.shiny())), 3000);
        t.hold = false;
        t.c.answer("usual");
        retill(sh, List.of(g.usualItem));
        return true;
    }

    /** Once a second: a regular waiting for their usual says what it is after a while, gives up much later. */
    private void usualClock(Shift sh) {
        Till t = sh.till;
        if (t == null || !t.g.usual || !t.c.asks().contains("usual")) return;
        Guest g = t.g;
        long waited = (System.currentTimeMillis() - g.usualAt) / 1000;
        if (!g.hinted && waited >= life.regulars().hint()) usualHint(g);
    }

    /** The regular says what their usual is (a while unanswered, a wrong shelf tried). */
    private void usualHint(Guest g) {
        g.hinted = true;
        List<String> lines = life.regulars().lines("hint");
        if (!lines.isEmpty()) bubbles.say(g.m, lines.get(ThreadLocalRandom.current().nextInt(lines.size()))
                .replace("{item}", spec.products().get(g.usualItem).name()), 70L, Bubbles.Kind.LINE);
    }

    // ------------------------------------------------------------------------------------------ 신분증

    private void idAnswer(Player p, Shift sh, Guest g, String choice) {
        Till t = sh.till;
        if (t == null || t.g != g || g.card == null) return;
        Location me = p.getLocation();
        switch (choice) {
            case "check" -> {
                if (has(StoreMotion.HELD_ID) && freeHands(p, StoreMotion.HELD_ID)) hands.hold(p, StoreMotion.HELD_ID, 3);   // §17: taken a moment
                showCard(sh, g);
                sayLine(g, rules.id().lines("show"));
                Map<String, String> c = new LinkedHashMap<>();
                c.put("sell", Lang.raw("store.id-sell"));
                c.put("refuse", Lang.raw("store.id-refuse"));
                ask(sh, g, "id", c);
            }
            case "sell" -> {
                IdCheck.Verdict v = IdCheck.judge(g.card, true);
                if (v == IdCheck.Verdict.SOLD_MINOR) {
                    sh.log.fine(rules.id().fine());
                    payNow(sh);
                    sayLine(g, rules.id().lines("sold-minor"));
                    p.playSound(me, Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f);
                    HudModule.flash(p, Lang.mm("store.id-fined", "g", gold(rules.id().fine())), 3500);
                } else {
                    sayLine(g, rules.id().lines("sold"));
                    HudModule.flash(p, Lang.mm("store.id-sold"), 2000);
                }
                idDone(sh, g);
            }
            case "refuse" -> {
                IdCheck.Verdict v = IdCheck.judge(g.card, false);
                List<String> keep = new ArrayList<>();
                for (String x : t.c.items()) if (!rules.id().items().contains(x)) keep.add(x);
                if (v == IdCheck.Verdict.REFUSED_MINOR) {
                    sh.log.bonus(rules.id().bonus());
                    payNow(sh);
                    g.s.mood(rules.id().refusedMinor());
                    sayLine(g, rules.id().lines("refused-minor"));
                    p.playSound(me, Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.8f);
                    HudModule.flash(p, Lang.mm("store.id-right", "g", gold(rules.id().bonus())), 2500);
                } else {
                    g.s.mood(rules.id().refusedAdult());
                    sayLine(g, rules.id().lines("refused-adult"));
                    p.playSound(me, Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
                    HudModule.flash(p, Lang.mm("store.id-wrong"), 2500);
                }
                if (keep.isEmpty()) {   // nothing else to buy: out
                    g.card = null;
                    if (v == IdCheck.Verdict.REFUSED_MINOR) handledOut(sh, g);
                    else lose(sh, g, Shopper.Exit.ANGRY);
                    return;
                }
                retill(sh, keep);
                idDone(sh, g);
            }
            default -> {}
        }
    }

    /** The ID question answered: the card away, the till goes on (or the counter's 진상 now). */
    private void idDone(Shift sh, Guest g) {
        Till t = sh.till;
        g.card = null;
        if (t == null || t.g != g) return;
        if (t.idCard != null) t.idCard.remove();
        t.idCard = null;
        if (t.idModel != null) t.idModel.remove();
        t.idModel = null;
        EntityEquipment eq = g.m.getEquipment();
        if (eq != null && eq.getItemInMainHand().getPersistentDataContainer().has(GUEST)) eq.setItemInMainHand(null);
        t.c.answer("id");
        t.hold = false;
        counterQuestions(sh, g);
        drawScreen(sh);
    }

    /** The ID on the counter: name, birth year, the photo (a sibling's is someone else's face). */
    private void showCard(Shift sh, Guest g) {
        Till t = sh.till;
        IdCheck.Card c = g.card;
        int year = java.time.ZonedDateTime.now(KST).getYear();
        Component text = Lang.mm("store.id-card", "name", c.name(), "year", c.birthYear(), "age", year - c.birthYear());
        if (t.idCard != null) t.idCard.remove();
        if (t.idModel == null) t.idModel = show.idOnCounter(sh, g);   // §17: the card from their hand onto the counter
        if (t.idPhoto == null) t.idPhoto = show.idPhoto(sh, g, c.sibling());   // the face on it: theirs, or a sibling's
        boolean card = t.idModel != null;
        Location at = sh.shop.at(StoreLayout.ID_X, StoreLayout.TOP + (card ? 0.2 : 0.3), StoreLayout.ID_Z + 0.08, 270);   // (over its writing, beside the photo)
        t.idCard = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.LEFT);
            d.setBackgroundColor(card ? Color.fromARGB(150, 228, 236, 246) : Color.fromARGB(240, 228, 236, 246));
            float sc = card ? 0.26f : 0.38f;
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
            d.setViewRange(0.25f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.3f);
    }

    // ------------------------------------------------------------------------------------------ at the shelves, after paying

    /** At a shelf: the 슬쩍 손님 pockets something, the 관광객 asks where theirs is. True when that happened. */
    private boolean browsing(Shift sh, Guest g) {
        if (g.trouble == Trouble.Kind.STEAL && !g.troubleStarted) {
            Shopper.Stop st = g.s.browsing();
            if (st == null) return false;
            List<String> here = new ArrayList<>();
            for (StoreSpec.Product pr : spec.products().values()) if (pr.place().equals(st.place()) && sh.shop.sells(pr.id())) here.add(pr.id());
            if (here.isEmpty() || !sh.shop.chores.take(st.place())) return false;
            g.troubleStarted = true;
            g.stolen = here.get(ThreadLocalRandom.current().nextInt(here.size()));
            refreshShelf(sh.shop, st.place());
            StoreRules.TroubleText tt = rules.troubles().get(Trouble.Kind.STEAL);
            if (tt != null) sayLine(g, tt.lines("start"));
            // in his hand a moment, then into the pocket
            EntityEquipment eq = g.m.getEquipment();
            if (eq != null) {
                ItemStack it = StoreBuilder.shown(core, spec.products().get(g.stolen).item());
                it.editMeta(m -> m.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1));
                eq.setItemInOffHand(it);
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (!g.m.isValid()) return;
                    if (eq.getItemInOffHand().getPersistentDataContainer().has(GUEST)) eq.setItemInOffHand(null);
                    g.m.getWorld().playSound(g.m.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.5f, 1.4f);
                }, 30L);
            }
            return true;
        }
        if (g.trouble == Trouble.Kind.TOURIST && !g.troubleStarted) return trouble(sh, g, Trouble.Stage.BROWSE);
        return false;
    }

    /** A thing off a shelf: a 폐기 picked up (they complain), a drink spilt on the way. */
    private void took(Shift sh, Guest g) {
        Shopper.Stop st = g.s.last();
        if (st == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Chores ch = sh.shop.chores;
        if (st.place().equals(rules.waste().place()) && ch.picksExpired(st.place(), rnd.nextDouble())) {
            if (!sh.test && life.reached(book.served(sh.worker), life.sticker().rank())) {   // 폐기 할인 스티커 (점장 대리): half price, gladly
                g.s.mood(life.sticker().mood());
                bubbles.say(g.m, Lang.raw("store.sticker-line"), 60L, Bubbles.Kind.LINE);
                if (ch.giveExpired(st.place())) {   // that one sold with its sticker: its tag gone
                    drawWaste(sh);
                    refreshShelf(sh.shop, st.place());
                }
                sh.log.bonus(life.sticker().bonus());
                payNow(sh);
            } else {
                g.s.mood(rules.waste().complain());
                say(g, "expired");
            }
        }
        if (st.place().equals("fridge") && rnd.nextDouble() < rules.mess().spillChance(g.type.id())) {
            String node = StoreLayout.AISLE.get(rnd.nextInt(StoreLayout.AISLE.size()));
            if (ch.spill(node, rules.mess().most())) {
                showSpill(sh, node);
                g.m.getWorld().playSound(g.m.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.5f);
                Player p = Bukkit.getPlayer(sh.worker);
                if (p != null) HudModule.flash(p, Lang.mm("store.spilt"), 2500);
            }
        }
    }

    /** Paid: off they go, or to the eat bar first (a 컵라면; a 취객 who nods off there). */
    private void afterPaid(Shift sh, Guest g) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (g.trouble == Trouble.Kind.DRUNK && !g.troubleStarted) {
            int seat = freeSeat(sh);
            if (seat >= 0) {
                g.seat = seat;
                g.sleeping = true;
                g.s.eat(sh, StoreLayout.EAT.get(seat), Integer.MAX_VALUE);
                return;
            }
            g.trouble = null;
        }
        if (g.eats) {
            int seat = freeSeat(sh);
            if (seat >= 0) {
                g.seat = seat;
                int secs = rules.mess().eatMin() + rnd.nextInt(rules.mess().eatMax() - rules.mess().eatMin() + 1);
                g.s.eat(sh, StoreLayout.EAT.get(seat), secs * 20);
                return;
            }
        }
        g.s.leave(sh, Shopper.Exit.PAID);
    }

    private int freeSeat(Shift sh) {
        boolean[] used = new boolean[StoreLayout.EAT.size()];
        for (Guest x : sh.guests) if (x.seat >= 0 && x.seat < used.length) used[x.seat] = true;
        for (int i = 0; i < used.length; i++) if (!used[i]) return i;
        return -1;
    }

    /** At the eat bar: the 취객 nods off (the buttons), a 컵라면 steams. */
    private void sat(Shift sh, Guest g) {
        if (g.seat >= 0 && g.chair == null && g.m.isValid()) {   // onto the stool (as the bus's people sit on its seats)
            Location at = sh.shop.at(StoreLayout.CUP_X + g.seat, StoreLayout.STOOL_Y, StoreLayout.STOOL_Z, 180);
            ItemDisplay chair = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
            });
            g.m.setRotation(at.getYaw(), 0);
            if (chair.addPassenger(g.m)) g.chair = chair;
            else chair.remove();
        }
        if (g.sleeping) {
            trouble(sh, g, Trouble.Stage.AFTER);
            return;
        }
        Location at = g.m.getLocation().add(0, 1.2, 0);
        at.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.add(at.getDirection().multiply(0.4)), 2, 0.05, 0.05, 0.05, 0.005);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_DRINK, 0.4f, 0.8f);
    }

    /** Done eating: maybe the cup stays on the bar (a mess), then out. */
    private void ate(Shift sh, Guest g) {
        if (g.seat >= 0 && ThreadLocalRandom.current().nextDouble() < rules.mess().cup() && sh.shop.chores.leaveCup(g.seat, rules.mess().most())) {
            showCup(sh, g.seat);
        }
        g.seat = -1;
        stand(g);
        g.s.leave(sh, Shopper.Exit.PAID);
    }

    /** The 슬쩍 손님 went out the door with it: the worker covers it. */
    private void escaped(Shift sh, Guest g) {
        StoreSpec.Product pr = spec.products().get(g.stolen);
        if (pr == null) return;
        long w = Trouble.escaped(pr.price());
        sh.log.covered(w);
        payNow(sh);
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) HudModule.flash(p, Lang.mm("store.stolen", "item", pr.name(), "g", gold(Math.round(w * spec.pay().overpayRate()))), 3500);
    }

    /** The worker looked at a customer and clicked them: the 슬쩍 손님 is caught; anyone else is hurt. True when it was a customer. */
    private boolean accuse(Player p, Entity x) {
        Shop shop = working.get(p.getUniqueId());
        if (shop == null || shop.shift == null || !(x instanceof Mannequin)) return false;
        Shift sh = shop.shift;
        Guest g = null;
        for (Guest y : sh.guests) if (y.m.equals(x) || x.equals(y.partner)) g = y;
        if (g == null) return false;
        if (g.inspector || g.visit != null) return false;
        boolean thief = g.stolen != null && !g.caught;
        if (!thief && sh.till != null && sh.till.g == g) return false;   // (the customer being served: a click on the till, not on them)
        if (!Trouble.mayAccuse(p.isSneaking(), g.accused)) return true;   // only a sneaking click means it, once a customer
        if (!clicks.accept(p.getUniqueId(), Bukkit.getCurrentTick())) return true;
        sh.lastActive = System.currentTimeMillis();
        if (g.caught) return true;
        Location l = g.m.getLocation();
        if (thief && g.s.state() == Shopper.State.LEAVE && !shop.site.inside(l.getWorld().getName(), l.getX(), l.getY(), l.getZ())) {
            HudModule.flash(p, Lang.mm("store.too-late"), 2000);
            return true;
        }
        g.accused = true;
        Trouble.Outcome o = Trouble.accuse(thief, rules.numbers());
        StoreRules.TroubleText tt = rules.troubles().get(Trouble.Kind.STEAL);
        if (tt != null) sayLine(g, tt.lines(o.talk()));
        g.s.mood(o.mood());
        if (thief) {
            g.caught = true;
            sh.log.bonus(o.bonus());
            payNow(sh);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
            HudModule.flash(p, Lang.mm("store.caught", "item", spec.products().get(g.stolen).name(), "g", gold(o.bonus())), 3000);
        } else {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
            HudModule.flash(p, Lang.mm("store.accused"), 2500);
        }
        return true;
    }

    /** The 복권 won big: the store cheers. */
    private void fanfare(Shift sh, Guest g) {
        Location at = g.m.getLocation().add(0, 1.6, 0);
        at.getWorld().playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.1f);
        at.getWorld().spawnParticle(Particle.FIREWORK, at, 30, 0.4, 0.4, 0.4, 0.08);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 12, 0.5, 0.5, 0.5, 0.1);
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) HudModule.flash(p, Lang.mm("store.lottery-big"), 3500);
    }

    /** A product's food into the worker's hands (할머니's 사탕: store.yml gifts). */
    private void giveFood(Player p, String product) {
        String id = rules.gifts().get(product);
        if (id == null) return;
        dev.stsv.economy.util.Items.create(id, 1).ifPresent(it -> {
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        });
    }

    // ------------------------------------------------------------------------------------------ 폐기 · 청소 · 온수 · 새벽

    /** Once a second on a shift: a 폐기 more, questions nobody answered, the 새벽 door bell. */
    private void chores(Shop shop, Shift sh, Player p, int hour) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String place = rules.waste().place();
        if (shop.has("shelf_" + place) && ++sh.wasteClock >= rules.waste().every()) {
            sh.wasteClock = 0;
            if (shop.chores.expire(place, rules.waste().most())) drawWaste(sh);   // (red on the shelf)
        }
        long now = System.currentTimeMillis();
        for (Prompt pr : new ArrayList<>(sh.prompts)) {
            Trouble.Kind k = pr.g.trouble;
            if (!pr.what.equals("trouble") || k == null) continue;
            long limit = Trouble.timeout(k);
            if (limit < 0 || now - pr.at <= limit) continue;
            sh.prompts.remove(pr);
            for (Entity e : pr.buttons.values()) e.remove();
            nameBack(pr.g);
            Trouble.Outcome o = k == Trouble.Kind.LOTTERY   // nobody looked: as if they had shrugged, the counter free again
                    ? Trouble.resolve(k, "shrug", context(sh, pr.g), rules.numbers(), rnd.nextDouble()) : Trouble.slept(k);
            if (o != null) apply(p, sh, pr.g, k, o);
        }
        for (Guest g : sh.guests) if (g.sleeping && g.s.seated()) {   // asleep on the bar: the head down, a z drifting up
            look(g, 0, 62, 40, false);
            if (rnd.nextDouble() < 0.3) floatText(g.m.getLocation().add(0, 1.55, 0), Text.mm("<gray>z"), 0.5f, 26, 0.45f);
        }
        if (hour >= 1 && hour < 5 && rnd.nextDouble() < rules.numbers().ghost()) ghostBell(sh, p);
    }

    /** 새벽: the door bell rings and nobody is there (ambience only). */
    private void ghostBell(Shift sh, Player p) {
        Location door = sh.shop.at(StoreLayout.DOOR0 + 0.5, 2, StoreLayout.FRONT + 1.5, 0);
        World w = door.getWorld();
        w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.7f);
        Bukkit.getScheduler().runTaskLater(eco, () -> w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.35f, 2.0f), 4L);
        w.spawnParticle(Particle.WHITE_ASH, door, 20, 0.5, 0.8, 0.3, 0.01);
        if (!sh.outage) {   // the lights blink once
            day.flicker(sh.shop, false);
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (sh.shop.shift == sh && !sh.outage) day.flicker(sh.shop, true);
            }, 5L);
        }
    }

    /** The spills, the cups and the 폐기 tags of a store shown (a shift begins). */
    private void drawMesses(Shift sh) {
        for (Entity e : sh.messes.values()) e.remove();
        sh.messes.clear();
        for (String node : sh.shop.chores.spills()) showSpill(sh, node);
        for (int i = 0; i < StoreLayout.EAT.size(); i++) if (sh.shop.chores.cup(i)) showCup(sh, i);
        drawWaste(sh);
    }

    private void showSpill(Shift sh, String node) {
        StoreLayout.Node n = StoreLayout.of().nodes().get(node);
        if (n == null) return;
        sh.messes.put("spill:" + node, display(sh.shop.at(n.x(), 0.01, n.z(), 0), "economy:store_spill", 1f));
    }

    private void showCup(Shift sh, int seat) {
        double[] c = cupSpot(seat);
        sh.messes.put("cup:" + seat, display(sh.shop.at(c[0], c[1], c[2], 180), "economy:store_trash_cup", StoreLayout.CUP_SCALE));
    }

    /** The red 폐기 tag over the shelf (how many), gone when there are none. */
    private void drawWaste(Shift sh) {
        int n = sh.shop.chores.expired(rules.waste().place());
        show.waste(sh, rules.waste().place(), sh.shop.has("shelf_" + rules.waste().place()) ? Math.max(0, n) : 0);   // §17: on the shelf, red
        if (n <= 0 || !sh.shop.has("shelf_" + rules.waste().place())) {
            if (sh.wasteTag != null) sh.wasteTag.remove();
            sh.wasteTag = null;
            return;
        }
        Component c = Lang.mm("store.waste-tag", "n", n);
        if (sh.wasteTag != null && sh.wasteTag.isValid()) {
            sh.wasteTag.text(c);
            return;
        }
        StoreLayout.Piece shelf = StoreLayout.of().piece("shelf_" + rules.waste().place());
        Location at = sh.shop.at(shelf.x0() + 0.5, 2.15, (shelf.z0() + shelf.z1()) / 2.0 + 0.5, 0);
        sh.wasteTag = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(c);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(220, 200, 30, 30));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
            d.setViewRange(0.35f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void takeWaste(Player p, Shift sh) {
        String place = rules.waste().place();
        boolean bag = has(StoreMotion.WASTE_BAG);
        if (bag && !freeHands(p, StoreMotion.WASTE_BAG)) return;
        int n = sh.shop.chores.takeExpired(place);
        if (n <= 0) return;
        if (bag) {   // §17: the red ones hop off into the 폐기 bag in the worker's hand
            show.takeWaste(sh, p);
            if (!hands.holds(p, StoreMotion.WASTE_BAG)) hands.hold(p, StoreMotion.WASTE_BAG, 0);
        }
        refreshShelf(sh.shop, place);
        drawWaste(sh);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.7f, 0.9f);
        if (!bag) HudModule.flash(p, Lang.mm("store.waste-taken", "n", n), 2500);
    }

    /** Into the 폐기 바구니 (the bag, the tray's cups); one of the 폐기 is the worker's (알바의 낙). */
    private void dispose(Player p, Shift sh) {
        Chores ch = sh.shop.chores;
        int cups = ch.carriedCups() > 0 && (hands.holds(p, StoreMotion.TRAY) || hands.book.who(StoreMotion.TRAY) == null) ? ch.dumpCups() : 0;
        if (cups > 0) {   // §17: the tray's cups into the basket
            if (hands.holds(p, StoreMotion.TRAY)) handsBack(p);
            show.dump(sh, "economy:store_trash_cup");
            for (int i = 0; i < cups; i++) task(p, sh, life.staff().chore());
            if (ch.carriedWaste() <= 0) return;
        }
        int n = ch.dispose();
        if (n <= 0) {
            HudModule.flash(p, Lang.mm("store.waste-nothing"), 2000);
            return;
        }
        boolean bag = hands.holds(p, StoreMotion.WASTE_BAG);
        if (bag) handsBack(p);
        show.dump(sh, bag ? StoreMotion.WASTE_BAG : null);
        String food = rules.waste().food();
        if (!food.isBlank()) dev.stsv.economy.util.Items.create(food, 1).ifPresent(it -> {
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        });
        if (!bag) HudModule.flash(p, Lang.mm("store.waste-done", "n", n), 2000);
        task(p, sh, life.staff().chore());
    }

    /** The worker fills the kettle (§17: the jug in hand, poured in steps, the water level rising). */
    private void kettle(Player p, Shift sh) {
        int cap = rules.mess().water();
        if (sh.pouring) return;
        int from = sh.shop.chores.water(cap);
        if (from >= cap) {
            HudModule.flash(p, Lang.mm("store.water-full", "n", from, "all", cap), 1500);
            return;
        }
        if (has(StoreMotion.JUG) && !freeHands(p, StoreMotion.JUG)) return;
        boolean jug = has(StoreMotion.JUG) && (hands.holds(p, StoreMotion.JUG) || hands.hold(p, StoreMotion.JUG, 5));
        sh.shop.chores.refill(cap);
        task(p, sh, life.staff().chore());
        if (jug) show.pour(sh, p, from, cap);
        else {
            p.playSound(p.getLocation(), Sound.ITEM_BUCKET_EMPTY, 0.7f, 1.2f);
            show.kettle(sh.shop);
        }
    }

    /** The mop from its bucket (or back): §17 into the worker's hand. */
    private void mop(Player p, Shift sh) {
        if (has(StoreMotion.MOP)) {
            if (hands.holds(p, StoreMotion.MOP)) {
                handsBack(p);
                return;
            }
            UUID other = hands.book.who(StoreMotion.MOP);
            if (other != null) {
                HudModule.flash(p, Lang.mm("store.mop-other"), 1500);
                return;
            }
            if (!freeHands(p, StoreMotion.MOP) || !hands.hold(p, StoreMotion.MOP, 45)) return;
            sh.shop.chores.mop(true);
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.6f, 1.2f);
            return;
        }
        boolean on = !sh.shop.chores.mop();
        sh.shop.chores.mop(on);
        p.playSound(p.getLocation(), on ? Sound.ITEM_ARMOR_EQUIP_GENERIC : Sound.BLOCK_WOOD_PLACE, 0.6f, 1.2f);
        if (on) HudModule.flash(p, Lang.mm("store.mop-on"), 2000);
    }

    // ------------------------------------------------------------------------------------------ scenes

    /** Once a second: the scene playing goes on; else every check seconds (2 customers inside) the roll. */
    private void sceneTick(Shift sh, Player p) {
        long now = System.currentTimeMillis();
        if (sh.playing != null) {
            stepScene(sh, now);
            return;
        }
        String force = sh.forceScene;
        if (force == null && (now < sh.sceneCheck || sh.guests.size() < 2)) return;
        if (force == null) sh.sceneCheck = now + scenes.rules().check() * 1000L;
        sh.forceScene = null;
        List<StoreScenes.Guest> inside = new ArrayList<>();
        for (Guest g : sh.guests) inside.add(new StoreScenes.Guest(g.type.id(), g.s.mood(spec.patience()), free(sh, g)));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StoreScenes.Pick pick;
        if (force != null) {
            StoreScenes.Scene s = scenes.scenes().get(force);
            int[] c = s == null ? null : StoreScenes.cast(s, inside);
            if (c == null) {
                p.sendMessage(Lang.msg("store.scene-no-cast", "id", force));
                return;
            }
            pick = new StoreScenes.Pick(s, c);
        } else {
            Long last = sceneAt.get(p.getUniqueId());
            if (!scenes.due(last == null ? -1 : (now - last) / 1000.0, rnd.nextDouble())) return;
            pick = scenes.pick(inside, sceneNow(sh), seenScenes(p), rnd.nextDouble());
            if (pick == null) return;
        }
        Guest[] cast = new Guest[pick.cast().length];
        for (int i = 0; i < cast.length; i++) {
            cast[i] = sh.guests.get(pick.cast()[i]);
            cast[i].scened = true;
            cast[i].s.hold(true);
        }
        sceneAt.put(p.getUniqueId(), now);
        sh.playing = new Playing(pick.scene(), cast, now);
        faceCast(cast);
        HudModule.flash(p, Lang.mm("store.scene-start", "title", esc(pick.scene().title())), 2500);
        stepScene(sh, now);
    }

    /** Whether a customer may play a scene now: not in one yet, not at the till, not asked anything, walking or in line. */
    private boolean free(Shift sh, Guest g) {
        if (g.scened || !g.m.isValid() || g.s.held() || g.sleeping) return false;
        if (sh.till != null && sh.till.g == g) return false;
        for (Prompt pr : sh.prompts) if (pr.g == g) return false;
        Shopper.State st = g.s.state();
        return st == Shopper.State.WALK || st == Shopper.State.BROWSE || st == Shopper.State.QUEUE || st == Shopper.State.EAT;
    }

    private void stepScene(Shift sh, long now) {
        Playing pl = sh.playing;
        for (Guest g : pl.cast) {
            if (!sh.guests.contains(g) || !g.m.isValid()) {
                endScene(sh, false);
                return;
            }
        }
        if (now < pl.at) return;
        List<StoreScenes.Line> lines = pl.scene.lines();
        if (pl.next >= lines.size()) {
            endScene(sh, true);
            return;
        }
        StoreScenes.Line l = lines.get(pl.next++);
        Guest who = pl.cast[l.who()];
        Entity e = l.partner() && who.partner != null && who.partner.isValid() ? who.partner : who.m;
        double secs = scenes.rules().pause(l.text());
        // one line at a time: the cast's bubbles before come down, this one shows for its reading time (the next comes after)
        for (Guest c : pl.cast) {
            bubbles.forget(c.m);
            bubbles.forget(c.partner);
        }
        bubbles.say(e, l.text(), Math.round(secs * 20), Bubbles.Kind.SCENE);
        if (e instanceof Mannequin sp) sp.swingMainHand();
        faceCast(pl.cast);
        pl.at = now + Math.round(secs * 1000);
    }

    /** A scene's people look at each other while it plays (the first at the second, the rest at the first). */
    private void faceCast(Guest[] cast) {
        if (cast.length < 2) return;
        for (int i = 0; i < cast.length; i++) {
            Guest a = cast[i], b = cast[i == 0 ? 1 : 0];
            if (!a.m.isValid() || !b.m.isValid()) continue;
            Location from = a.m.getLocation(), to = b.m.getLocation();
            if (from.distanceSquared(to) < 0.01) continue;
            look(a, (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ())), 0, 20 * 12, true);
        }
    }

    /** The scene's end (done: its effect, the 도감; else it just stops) and everyone goes on. */
    private void endScene(Shift sh, boolean done) {
        Playing pl = sh.playing;
        sh.playing = null;
        if (pl == null) return;
        for (Guest g : pl.cast) {
            g.s.hold(false);
            g.lookUntil = 0;
        }
        if (!done) return;
        StoreScenes.Effect e = pl.scene.effect();
        for (Map.Entry<Integer, Double> m : e.mood().entrySet()) {
            int r = m.getKey();
            if (r == StoreScenes.ALL) for (Guest g : sh.guests) g.s.mood(m.getValue());
            else if (r == StoreScenes.OTHERS) for (Guest g : sh.guests) {
                if (!pl.has(g)) g.s.mood(m.getValue());
            }
            else if (r >= 0 && r < pl.cast.length) pl.cast[r].s.mood(m.getValue());
        }
        for (Map.Entry<Integer, Double> t : e.tip().entrySet()) if (t.getKey() >= 0 && t.getKey() < pl.cast.length) pl.cast[t.getKey()].tipX *= t.getValue();
        if (e.calm() > 0) for (Guest g : sh.guests) {
            double m = g.s.mood(spec.patience());
            if (m < e.calm()) g.s.mood(e.calm() - m);
        }
        for (int r : e.leave()) {   // out without buying (not counted as lost: it was the scene)
            if (r < 0 || r >= pl.cast.length) continue;
            Guest g = pl.cast[r];
            clearPrompts(sh, g);
            if (sh.till != null && sh.till.g == g) clearTill(sh);
            sh.line.remove(g.s);
            g.tallied = true;
            g.s.leave(sh, Shopper.Exit.PAID);
        }
        Player p = Bukkit.getPlayer(sh.worker);
        if (p == null) return;
        if (e.gift() != null && !sh.test) dev.stsv.economy.util.Items.create(e.gift().item(), e.gift().amount()).ifPresent(it -> {
            for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        });
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.8f);
        if (!sh.test && eco.people() != null && eco.people().sawScene(p, StoreScenes.seenId(pl.scene.id()))) {
            p.sendMessage(Lang.mm("store.scene-new", "n", seenScenes(p).stream().filter(scenes.scenes()::containsKey).count(), "all", scenes.scenes().size()));
        }
    }

    private StoreScenes.Now sceneNow(Shift sh) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(KST);
        World w = sh.shop.world();
        return new StoreScenes.Now(w == null ? 12 : Chores.hour(w.getTime()), now.getMonthValue(), w != null && w.hasStorm(), marketDay(),
                now.getDayOfWeek().getValue() >= 6);
    }

    private Set<String> seenScenes(Player p) {
        return eco.people() == null ? Set.of() : StoreScenes.seen(eco.people().scenes(p.getUniqueId()));
    }

    private static String esc(String s) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(s);
    }

    // ------------------------------------------------------------------------------------------ not on shift

    /** What a click on a piece of a store is: a seat (anyone sits), the hanger, the 발주 tablet, a worker's job, or nothing. */
    enum Click { SEAT, HANGER, TABLET, STAFF_ONLY, WORK }

    /**
     * What a click on a piece of a store (its furniture role) does for someone on shift here or not. Players do not shop:
     * someone not working here gets the seats, the hanger (to start a shift, to apply) and the tablet; every other piece
     * (the shelves, the fridges, the counter, the kettle …) is only the staff's.
     */
    static Click click(String role, boolean workingHere) {
        if (role.equals("eatbar") || role.startsWith("parasol")) return Click.SEAT;
        if (role.equals("hanger")) return Click.HANGER;
        if (role.equals("order")) return Click.TABLET;
        return workingHere ? Click.WORK : Click.STAFF_ONLY;
    }

    /** The line for someone clicking the staff's things: how to start a shift when they may and the store is closed. */
    static String staffLine(boolean mayWork, boolean open) {
        return "store.staff-only";
    }

    /**
     * A player not working here clicks a piece of a store (a shelf, the counter, the kettle …): nothing happens but a short
     * line (players do not shop here; only the NPC customers do). The owner or a hired worker is told how to start a shift.
     */
    private void staffOnly(Player p, Shop shop) {
        UUID u = p.getUniqueId();
        boolean mayWork = shop.owner == null || shop.owner.equals(u) || book.hired(shop.name).contains(u);
        HudModule.flash(p, Lang.mm(staffLine(mayWork, shop.shift != null)), 1500);
    }

    /** The store an unpaid thing is from (null: not one). Only leftovers from when players could shop here. */
    static String unpaid(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(UNPAID, PersistentDataType.STRING);
    }

    /** Leftovers from when players could shop here (things picked off a shelf, never paid): out of their inventory. */
    private static void dropUnpaid(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (unpaid(inv.getItem(i)) != null) inv.setItem(i, null);
        if (unpaid(p.getItemOnCursor()) != null) p.setItemOnCursor(null);
    }

    // ------------------------------------------------------------------------------------------ 내 가게

    /** The store this player owns (null: none). */
    private Shop owned(UUID player) {
        for (Shop s : shops.values()) if (player.equals(s.owner)) return s;
        return null;
    }

    /** A 권리증 in hand, used: a new store here, or the one they have made bigger. True when it was a 권리증. */
    private boolean license(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isEmpty() || rules == null) return false;
        StoreRules.Tier tier = rules.byLicense(dev.stsv.economy.util.Items.key(hand));
        if (tier == null) return false;
        if (!clicks.accept(p.getUniqueId(), Bukkit.getCurrentTick())) return true;
        if (job != null && job.running()) {
            p.sendMessage(Lang.msg("store.building"));
            return true;
        }
        Shop own = owned(p.getUniqueId());
        if (own != null) {
            StoreRules.Tier cur = own.tier();
            if (cur != null && tier.rank() <= cur.rank()) {
                p.sendMessage(Lang.msg("store.own-have", "store", own.name, "tier", cur.name()));
                return true;
            }
            if (!confirmed(p, "up|" + own.name + "|" + tier.id())) {
                p.sendMessage(Lang.msg("store.own-up-confirm", "store", own.name, "from", cur == null ? "?" : cur.name(), "to", tier.name(),
                        "g", gold(cur == null ? 0 : cur.price())));
                return true;
            }
            upgrade(p, own, tier);
            return true;
        }
        Location l = p.getLocation();
        StoreLayout.Site site = new StoreLayout.Site(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), StoreLayout.Site.facing(l.getYaw()));
        String problem = landProblem(p, site);
        if (problem != null) {
            p.sendMessage(Lang.msg(problem));
            return true;
        }
        if (!confirmed(p, "new|" + tier.id() + "|" + site.text())) {
            p.sendMessage(Lang.msg("store.own-confirm", "tier", tier.name(), "n", tier.maxInside(), "items", tier.products().size()));
            return true;
        }
        buildOwn(p, tier, site);
        return true;
    }

    /** The second click within CONFIRM_MS of the same thing: true (and forgotten); else this one is remembered. */
    private boolean confirmed(Player p, String key) {
        long now = System.currentTimeMillis();
        Confirm c = confirms.get(p.getUniqueId());
        if (c != null && c.key().equals(key) && now - c.at() <= CONFIRM_MS) {
            confirms.remove(p.getUniqueId());
            return true;
        }
        confirms.put(p.getUniqueId(), new Confirm(key.substring(0, key.indexOf('|')), key, now));
        return false;
    }

    /** Why a store cannot stand here (a message key), or null: the whole lot in land the player owns, clear of other stores. */
    private String landProblem(Player p, StoreLayout.Site site) {
        if (eco.land() == null) return "store.own-no-land";
        HallLayout.Box lot = StoreLayout.of().clear().getFirst();
        int[] b = site.box(lot);
        World w = p.getWorld();
        for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
            dev.stsv.economy.land.Land land = eco.land().at(new Location(w, x, site.y(), z));
            if (land == null || !land.isOwner(p.getUniqueId())) return "store.own-not-land";
        }
        for (Shop s : shops.values()) {
            if (!s.site.world().equals(site.world())) continue;
            int[] o = s.site.box(lot);
            if (b[0] <= o[3] && o[0] <= b[3] && b[2] <= o[5] && o[2] <= b[5]) return "store.own-overlap";
        }
        return null;
    }

    /** One 권리증 of this tier out of the hand (false: it is not there any more). */
    private boolean takeLicense(Player p, StoreRules.Tier tier) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!tier.license().equals(dev.stsv.economy.util.Items.key(hand))) return false;
        hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    private String ownName(Player p) {
        String base = p.getName() + "네";
        String name = base;
        for (int i = 2; shops.containsKey(name); i++) name = base + i;
        return name;
    }

    private void saveOwn(Shop shop) {
        core.db().execute(core.db().upsert("eco_store", List.of("name"), List.of("site", "owner", "tier")), shop.name, shop.site.text(),
                shop.owner == null ? null : shop.owner.toString(), shop.tier);
    }

    private void buildOwn(Player p, StoreRules.Tier tier, StoreLayout.Site site) {
        if (!takeLicense(p, tier)) return;
        String name = ownName(p);
        Shop shop = new Shop(name, site, new Chores(spec));
        shop.owner = p.getUniqueId();
        shop.tier = tier.id();
        try {
            job = StoreBuilder.build(eco, core, name, site, shop.layout(), undoFile(name), settings(),
                    pct -> progress(p, pct), placed -> builtOwn(p, placed));
            jobStore = name;
        } catch (IOException | IllegalArgumentException e) {
            dev.stsv.economy.util.Items.create(tier.license(), 1).ifPresent(it -> dev.stsv.economy.util.Items.give(p, it));
            p.sendMessage(Lang.msg("store.build-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        shops.put(name, shop);
        saveOwn(shop);
        p.sendMessage(Lang.msg("store.own-started", "store", name, "tier", tier.name()));
    }

    /** A bigger 등급: the old one's price back (so only the difference is paid), the store built again bigger. */
    private void upgrade(Player p, Shop own, StoreRules.Tier tier) {
        if (!tier.license().equals(dev.stsv.economy.util.Items.key(p.getInventory().getItemInMainHand()))) return;
        StoreRules.Tier cur = own.tier();
        if (own.shift != null) clockOut(own, "store.out-admin");
        String before = own.tier;
        own.tier = tier.id();   // (the bigger layout is what is built)
        try {
            job = StoreBuilder.build(eco, core, own.name, own.site, own.layout(), undoFile(own.name), settings(),
                    pct -> progress(p, pct), placed -> builtOwn(p, placed));
            jobStore = own.name;
        } catch (IOException | IllegalArgumentException e) {   // nothing built: the 등급 and the 권리증 stay as they were
            own.tier = before;
            p.sendMessage(Lang.msg("store.build-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        // the build is under way: now the 권리증 is used up, the old 등급's price comes back, the new 등급 is saved
        takeLicense(p, tier);
        long refund = cur == null ? 0 : cur.price();
        if (refund > 0) core.economy().deposit(p.getUniqueId(), refund, "store");
        own.shown.clear();
        saveOwn(own);
        p.sendMessage(Lang.msg("store.own-upgraded", "store", own.name, "from", cur == null ? "?" : cur.name(), "to", tier.name(), "g", gold(refund)));
    }

    private void builtOwn(Player owner, StoreBuilder.Placed placed) {
        Shop shop = shops.get(placed.name());
        if (shop != null) shop.shown.clear();
        if (!owner.isOnline()) return;
        owner.sendMessage(Lang.msg("store.own-built", "store", placed.name()));
        if (owner.hasPermission("stsv.economy.admin")) for (String problem : placed.problems()) owner.sendMessage(Lang.msg("store.build-problem", "problem", problem));
    }

    /** Takes a 내 가게 down; its 권리증 to `to` (or the owner's mailbox when null or full). */
    private void demolish(Shop shop, Player to) {
        if (shop.shift != null) clockOut(shop, "store.out-admin");
        StoreRules.Tier tier = shop.tier();
        UUID owner = shop.owner;
        String name = shop.name;
        try {
            job = StoreBuilder.remove(eco, core, name, shop.site, undoFile(name), settings(),
                    pct -> { if (to != null && to.isOnline()) progress(to, pct); },
                    restored -> { if (to != null && to.isOnline()) to.sendMessage(Lang.msg("store.removed", "store", name)); });
            jobStore = name;
        } catch (IOException | IllegalArgumentException e) {
            if (to != null) to.sendMessage(Lang.msg("store.build-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        shops.remove(name);
        core.db().execute("DELETE FROM eco_store WHERE name = ?", name);
        book.forget(name);
        applied.remove(name);
        if (tier != null && owner != null) dev.stsv.economy.util.Items.create(tier.license(), 1).ifPresent(it -> {
            if (to != null && to.getUniqueId().equals(owner)) {
                for (ItemStack left : dev.stsv.economy.util.Items.give(to, it)) eco.mail().deliver(owner, left, "store");
                to.sendMessage(Lang.msg("store.own-down", "store", name, "tier", tier.name()));
            } else eco.mail().deliver(owner, it, "store");
        });
    }

    // ------------------------------------------------------------------------------------------ admin (2차)

    private Shift adminShift(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return null;
        }
        Shop shop = working.get(p.getUniqueId());
        if (shop == null || shop.shift == null) {
            p.sendMessage(Lang.msg("store.not-working"));
            return null;
        }
        return shop.shift;
    }

    int hourOf(Shift sh) {
        World w = sh.shop.world();
        return w == null ? 12 : Chores.hour(w.getTime());
    }

    /** /관리자 편의점 진상 &lt;종류&gt;: that trouble's customer walks in now (유령: the door bell). */
    private void adminTrouble(CommandSender s, String id) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        Trouble.Kind k = Trouble.Kind.of(id);
        if (k == null) {
            List<String> ids = new ArrayList<>();
            for (Trouble.Kind x : Trouble.Kind.values()) ids.add(x.id);
            s.sendMessage(Lang.msg("store.trouble-usage", "ids", String.join(", ", ids)));
            return;
        }
        if (k == Trouble.Kind.GHOST) {
            ghostBell(sh, Bukkit.getPlayer(sh.worker));
            return;
        }
        if (sh.guests.size() >= sh.shop.maxInside()) {
            s.sendMessage(Lang.msg("store.guest-full", "n", sh.shop.maxInside()));
            return;
        }
        Guest g = spawn(sh, k.type, hourOf(sh), k, false);
        if (g == null) s.sendMessage(Lang.msg("store.guest-failed"));
        else s.sendMessage(Lang.msg(g.trouble == k ? "store.trouble-spawned" : "store.trouble-cannot", "id", k.id,
                "type", eco.bus().typeTag(g.type, false)));
    }

    /** /관리자 편의점 신분증: a young-looking customer with 술 · 담배 (a student, maybe with a sibling's card). */
    private void adminId(CommandSender s) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        boolean any = false;
        for (String r : rules.id().items()) if (sh.shop.sells(r)) any = true;
        if (!any) {
            s.sendMessage(Lang.msg("store.id-none-sold"));
            return;
        }
        String kind = ThreadLocalRandom.current().nextBoolean() ? "student" : "univ";
        Guest g = spawn(sh, kind, hourOf(sh), null, true);
        s.sendMessage(Lang.msg(g == null ? "store.guest-failed" : "store.guest-done", "type", g == null ? kind : eco.bus().typeTag(g.type, false)));
    }

    /** /관리자 편의점 장면 [id]: the list, or that scene now (its cast must be inside). */
    private void adminScene(CommandSender s, String id) {
        if (id == null) {
            s.sendMessage(Lang.mm("store.scene-list", "n", scenes.scenes().size(), "chance", Math.round(scenes.rules().chance() * 100),
                    "cooldown", scenes.rules().cooldown()));
            for (StoreScenes.Scene x : scenes.scenes().values()) s.sendMessage(Lang.mm("store.scene-row", "id", x.id(), "title", esc(x.title()),
                    "cast", String.join(" · ", x.cast())));
            return;
        }
        Shift sh = adminShift(s);
        if (sh == null) return;
        if (!scenes.scenes().containsKey(id)) {
            s.sendMessage(Lang.msg("store.scene-no-such", "id", id));
            return;
        }
        if (sh.playing != null) endScene(sh, false);
        sh.forceScene = id;
        s.sendMessage(Lang.msg("store.scene-forced", "id", id, "cast", String.join(" · ", scenes.scenes().get(id).cast())));
    }

    /** /관리자 편의점 폐기: the 폐기 shelf's things expire now (as many as may). */
    private void adminWaste(CommandSender s) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        int n = 0;
        while (sh.shop.chores.expire(rules.waste().place(), rules.waste().most())) n++;
        drawWaste(sh);
        s.sendMessage(Lang.msg("store.admin-waste", "n", sh.shop.chores.expired(rules.waste().place()), "added", n));
    }

    /** /관리자 편의점 청소: a spill on the floor and a cup on the eat bar. */
    private void adminMess(CommandSender s) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        for (String node : StoreLayout.AISLE) if (sh.shop.chores.spill(node, rules.mess().most())) {
            showSpill(sh, node);
            break;
        }
        if (sh.shop.has("eatbar")) for (int i = 0; i < StoreLayout.EAT.size(); i++) if (sh.shop.chores.leaveCup(i, rules.mess().most())) {
            showCup(sh, i);
            break;
        }
        s.sendMessage(Lang.msg("store.admin-mess", "n", sh.shop.chores.messes()));
    }

    /** /관리자 편의점 권리증 &lt;플레이어&gt; &lt;등급&gt;: a 권리증 in their hands. */
    private void adminLicense(CommandSender s, String who, String tierWord) {
        Player p = who == null ? null : Bukkit.getPlayerExact(who);
        StoreRules.Tier tier = rules.tier(tierWord);
        if (p == null || tier == null) {
            List<String> names = new ArrayList<>();
            for (StoreRules.Tier t : rules.tiers().values()) names.add(t.name());
            s.sendMessage(Lang.msg("store.license-usage", "tiers", String.join(", ", names)));
            return;
        }
        var it = dev.stsv.economy.util.Items.create(tier.license(), 1);
        if (it.isEmpty()) {
            s.sendMessage(Lang.msg("store.license-missing", "item", tier.license()));
            return;
        }
        for (ItemStack left : dev.stsv.economy.util.Items.give(p, it.get())) eco.mail().deliver(p.getUniqueId(), left, "store");
        s.sendMessage(Lang.msg("store.license-given", "player", p.getName(), "tier", tier.name()));
    }

    /** /관리자 편의점 내가게: the players' own stores. */
    private void adminOwned(CommandSender s) {
        int n = 0;
        for (Shop shop : shops.values()) {
            if (shop.owner == null) continue;
            n++;
            s.sendMessage(Lang.mm("store.owned-line", "store", shop.name, "owner", String.valueOf(Bukkit.getOfflinePlayer(shop.owner).getName()),
                    "tier", shop.tier() == null ? "?" : shop.tier().name(), "site", shop.site.toString(),
                    "state", shop.shift == null ? Lang.raw("store.list-closed") : Lang.raw("store.list-worker", "player",
                            String.valueOf(Bukkit.getOfflinePlayer(shop.shift.worker).getName()), "n", shop.shift.guests.size())));
        }
        if (n == 0) s.sendMessage(Lang.msg("store.owned-none"));
    }

    /** /관리자 편의점 회수 &lt;플레이어&gt;: their store taken down, its 권리증 to their mailbox. */
    private void adminReclaim(CommandSender s, String who) {
        if (job != null && job.running()) {
            s.sendMessage(Lang.msg("store.building"));
            return;
        }
        Shop found = null;
        if (who != null) for (Shop shop : shops.values()) {
            if (shop.owner != null && who.equalsIgnoreCase(Bukkit.getOfflinePlayer(shop.owner).getName())) found = shop;
        }
        if (found == null) {
            s.sendMessage(Lang.msg("store.owned-no-such", "player", String.valueOf(who)));
            return;
        }
        demolish(found, null);
        s.sendMessage(Lang.msg("store.owned-reclaimed", "store", found.name));
    }

    // ------------------------------------------------------------------------------------------ admin (3차)

    /** /관리자 편의점 경력 &lt;플레이어&gt; [단계]: their served count set to that rank's (or shown). */
    private void adminCareer(CommandSender s, String who, String rankWord) {
        org.bukkit.OfflinePlayer op = who == null ? null : Bukkit.getOfflinePlayerIfCached(who);
        if (op == null) {
            s.sendMessage(Lang.msg("store.career-usage", "ranks", String.join(", ", life.ranks().stream().map(r -> r.name().replace(" ", "")).toList())));
            return;
        }
        UUID u = op.getUniqueId();
        if (rankWord == null) {
            StoreLife.Rank r = rank(u);
            s.sendMessage(Lang.msg("store.career-show", "player", String.valueOf(op.getName()), "rank", r.name(), "served", book.served(u), "night", book.night(u)));
            return;
        }
        StoreLife.Rank r = life.rankOf(rankWord);
        if (r == null) {
            s.sendMessage(Lang.msg("store.career-usage", "ranks", String.join(", ", life.ranks().stream().map(x -> x.name().replace(" ", "")).toList())));
            return;
        }
        book.set(u, r.at(), Math.max(0, r.index() - 1));
        Player p = op.getPlayer();
        if (p != null) announce(p);
        s.sendMessage(Lang.msg("store.career-set", "player", String.valueOf(op.getName()), "rank", r.name(), "served", r.at()));
    }

    /** /관리자 편의점 러시: a rush starts now at the admin's store. */
    private void adminRush(CommandSender s) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        if (sh.rush != null) {
            sh.rush = null;
            s.sendMessage(Lang.msg("store.admin-rush-off"));
            return;
        }
        day.startRush(sh, Bukkit.getPlayer(sh.worker));
    }

    /** /관리자 편의점 정전 · 단체 · 점검: that event now (정전 again: the power back). */
    private void adminEvent(CommandSender s, StoreLife.Event e) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        if (e == StoreLife.Event.OUTAGE && sh.outage) {
            day.endOutage(sh, false);
            return;
        }
        if (!day.start(sh.shop, sh, Bukkit.getPlayer(sh.worker), e, StoreDay.gameDay(sh.shop.world()))) s.sendMessage(Lang.msg("store.admin-event-busy"));
    }

    /** /관리자 편의점 발주 [묶음]: an order for today at the admin's store (to see the bonus now), and what today asks for. */
    private void adminOrder(CommandSender s, String word) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        long d = StoreDay.gameDay(sh.shop.world());
        String want = day.wanted(sh.shop, d);
        StoreLife.Group g = word == null ? life.order().groups().get(want) : life.order().group(word);
        if (g == null) {
            s.sendMessage(Lang.msg("store.admin-order-usage", "groups", String.join(", ", life.order().groups().keySet())));
            return;
        }
        eco.kv().set("store.order." + sh.shop.name, d + "|" + g.id() + "|" + sh.worker);
        sh.shop.firstDay = Long.MIN_VALUE;
        s.sendMessage(Lang.msg("store.admin-order", "group", g.name(), "want", life.order().groups().get(want).name(),
                "hit", Lang.raw(g.id().equals(want) ? "store.order-today-hit" : "store.order-today-miss")));
    }

    /** /관리자 편의점 단골 [종류]: the next customer (that kind now) asks for their usual. */
    private void adminUsual(CommandSender s, String kind) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        if (kind != null && !life.regulars().menu().containsKey(kind)) {
            s.sendMessage(Lang.msg("store.guest-usage"));
            return;
        }
        sh.forceUsual = true;
        if (kind == null) {
            s.sendMessage(Lang.msg("store.admin-usual-next"));
            return;
        }
        if (sh.guests.size() >= sh.shop.maxInside()) {
            s.sendMessage(Lang.msg("store.guest-full", "n", sh.shop.maxInside()));
            return;
        }
        Guest g = spawn(sh, kind, hourOf(sh));
        s.sendMessage(Lang.msg(g == null ? "store.guest-failed" : g.usual ? "store.admin-usual" : "store.admin-usual-cannot", "type", kind,
                "item", spec.products().containsKey(String.valueOf(life.regulars().usual(kind))) ? spec.products().get(life.regulars().usual(kind)).name() : "?"));
    }

    /** /관리자 편의점 버스: the next customer at the counter asks when the next bus comes (a stop must be near). */
    private void adminBus(CommandSender s) {
        Shift sh = adminShift(s);
        if (sh == null) return;
        var stop = link.near(sh.shop);
        if (stop == null) {
            s.sendMessage(Lang.msg("store.admin-bus-none", "r", Math.round(life.bus().radius())));
            return;
        }
        sh.forceBus = true;
        s.sendMessage(Lang.msg("store.admin-bus", "stop", stop.name(), "answer", link.answer(sh.shop)));
    }

    /** /관리자 편의점 알바 &lt;가게&gt; &lt;허용|해제|목록&gt; [플레이어]: a 내 가게's hired workers. */
    private void adminHire(CommandSender s, String store, String what, String who) {
        Shop shop = store == null ? null : shops.get(store);
        if (shop == null || shop.owner == null || what == null) {
            s.sendMessage(Lang.msg("store.admin-hire-usage"));
            return;
        }
        if (what.equals("목록")) {
            List<String> names = new ArrayList<>();
            for (UUID u : book.hired(shop.name)) names.add(String.valueOf(Bukkit.getOfflinePlayer(u).getName()));
            s.sendMessage(Lang.msg("store.admin-hire-list", "store", shop.name, "list", names.isEmpty() ? "-" : String.join(", ", names)));
            return;
        }
        org.bukkit.OfflinePlayer op = who == null ? null : Bukkit.getOfflinePlayerIfCached(who);
        if (op == null || !(what.equals("허용") || what.equals("해제"))) {
            s.sendMessage(Lang.msg("store.admin-hire-usage"));
            return;
        }
        boolean ok = what.equals("허용") ? book.hire(shop.name, op.getUniqueId()) : book.fire(shop.name, op.getUniqueId());
        s.sendMessage(Lang.msg(what.equals("허용") ? "store.admin-hired" : "store.admin-fired", "store", shop.name, "player", String.valueOf(op.getName()),
                "ok", ok ? "" : Lang.raw("store.admin-hire-same")));
    }

    // ------------------------------------------------------------------------------------------ 인물 도감 (3차)

    /** The 도감 page's extra lines of a person for this player: their usual (단골 메뉴) once they fetched it. */
    public List<Component> peopleLines(UUID player, String person) {
        if (book == null || life == null || !book.knowsUsual(player, person)) return List.of();
        StoreSpec.Product p = spec.products().get(life.regulars().usual(person));
        return p == null ? List.of() : List.of(Lang.item("store.codex-usual", "item", p.name()));
    }

    // ------------------------------------------------------------------------------------------ words

    static String won(long n) {
        return String.format("%,d", n);
    }

    static String gold(long n) {
        return String.format("%,d", n);
    }

    static String starNumber(double stars) {
        return String.format(java.util.Locale.ROOT, "%.1f", stars);
    }

    static String starText(int stars) {
        return "★".repeat(Math.clamp(stars, 1, 5)) + "☆".repeat(5 - Math.clamp(stars, 1, 5));
    }

    /** Who works where (another module's question: is this player behind a counter now?). */
    public boolean working(UUID player) {
        return working.containsKey(player);
    }
}
