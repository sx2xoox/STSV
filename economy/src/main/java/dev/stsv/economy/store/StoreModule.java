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
    /** The POS keys' size (store_btn_*: 12 × 6 px at 1, so 0.45 × 0.225 blocks). */
    static final float BUTTON = 0.6f;

    private StoreSpec spec;
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final Map<UUID, Shop> working = new HashMap<>();
    private final ButtonPick.Debounce clicks = new ButtonPick.Debounce();
    private final ButtonPick.Hint hints = new ButtonPick.Hint();
    private StoreBuilder job;
    private long ticks;

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
                "CREATE TABLE IF NOT EXISTS eco_store (name VARCHAR(64) NOT NULL PRIMARY KEY, site VARCHAR(160) NOT NULL)")));
        shops.clear();
        core.db().sync(c -> {
            for (String[] r : Db.query(c, "SELECT name, site FROM eco_store", rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                StoreLayout.Site s = StoreLayout.Site.parse(r[1]);
                if (s != null) shops.put(r[0], new Shop(r[0], s, new Chores(spec)));
            }
            return null;
        });
        core.admin().add(EcoPlugin.ADMIN_OWNER, "편의점", "건설 [이름] · 철거 <이름> · 목록 · 시험 · 손님 <종류> · 배송",
                "편의점(GS26 직영점) 짓기 · 허물기 · 목록 · 혼자 시험 근무 (돈 · 도감 없음) · 근무 중 손님 부르기 · 배송 트럭 부르기",
                this::admin, this::adminComplete);
        if (core.hud() != null) {
            core.hud().board(HUD, BOARD_SHIFT, p -> working.containsKey(p.getUniqueId()), this::board);
            core.hud().line(HUD, 0, this::actionLine);
        }
        purge();
        every(1, this::frame);
        every(20, this::second);
    }

    @Override
    protected void stop() {
        if (job != null && job.running()) job.cancel();
        for (Shop s : shops.values()) if (s.shift != null) clockOut(s, "store.out-reload");
        working.clear();
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
        spec = StoreSpec.parse(eco.data("store.yml"), problems);
        BusModule bus = eco.bus();
        if (bus != null && bus.spec() != null) problems.addAll(spec.check(bus.spec()));
        problems.forEach(p -> eco.getLogger().warning("[store.yml] " + p));
    }

    public StoreSpec spec() {
        return spec;
    }

    // ------------------------------------------------------------------------------------------ state

    /** A built store: where it is, its stock, the shift on now (null: closed, only its furniture). */
    final class Shop {
        final String name;
        final StoreLayout.Site site;
        final Chores chores;
        Shift shift;
        final Map<String, String> shown = new HashMap<>();   // place -> the shelf state shown

        Shop(String name, StoreLayout.Site site, Chores chores) {
            this.name = name;
            this.site = site;
            this.chores = chores;
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
        final UUID worker;
        final boolean test;
        final ShiftLog log = new ShiftLog();
        final long started = System.currentTimeMillis();
        final List<Guest> guests = new ArrayList<>();
        final List<Shopper> line = new ArrayList<>();
        final Map<String, ItemDisplay> boxes = new HashMap<>();
        Till till;
        Guest warmFor;
        double nextSpawn;
        long lastActive = System.currentTimeMillis();
        long awaySince;
        double lastX, lastZ;
        float lastYaw, lastPitch;
        TextDisplay ovenText;

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

        Guest(Shopper s, BusSpec.Type type, Mannequin m) {
            this.s = s;
            this.type = type;
            this.m = m;
        }
    }

    /** The customer at the counter: their checkout and what shows on the counter. */
    static final class Till {
        final Guest g;
        final Checkout c;
        final List<ItemDisplay> items = new ArrayList<>();
        final List<Entity> paying = new ArrayList<>();
        final Map<String, ItemDisplay> buttons = new LinkedHashMap<>();
        TextDisplay screen, face;
        boolean payStarted;

        Till(Guest g, Checkout c) {
            this.g = g;
            this.c = c;
        }
    }

    // ------------------------------------------------------------------------------------------ ticks

    private void frame() {
        ticks++;
        for (Shop shop : shops.values()) {
            Shift sh = shop.shift;
            if (sh == null) continue;
            for (Guest g : new ArrayList<>(sh.guests)) step(sh, g);
            oven(sh);
        }
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
            World w = shop.world();
            if (w == null) continue;
            int hour = Chores.hour(w.getTime());
            // the truck
            List<String> came = shop.chores.delivery(spec, Chores.slot(w.getFullTime(), spec.deliveries()));
            if (!came.isEmpty()) {
                showBoxes(sh);
                p.sendMessage(Lang.msg("store.delivery", "n", came.size()));
                w.playSound(shop.at(5.5, 0, 14.5, 0), Sound.BLOCK_PISTON_EXTEND, 0.7f, 0.6f);
            }
            // customers
            sh.nextSpawn -= 1;
            if (sh.nextSpawn <= 0) {
                if (sh.guests.size() < spec.spawn().maxInside()) spawn(sh, null, hour);
                sh.nextSpawn = spec.interval(hour, ThreadLocalRandom.current());
            }
            // patience
            for (Guest g : new ArrayList<>(sh.guests)) {
                if (!g.s.inLine()) continue;
                if (sh.till != null && sh.till.g == g && sh.till.c.paid()) continue;   // paid, waiting for the microwave
                if (g.s.fedUp(spec.patience())) {
                    say(g, "angry");
                    lose(sh, g, Shopper.Exit.ANGRY);
                } else if (g.s.waitLine(spec.patience())) say(g, "wait");
            }
            if (sh.till != null) drawScreen(sh);
            else if (!sh.line.isEmpty()) {   // (the head of the line at the counter with no till: open one)
                for (Guest g : sh.guests) if (g.s == sh.line.getFirst() && g.s.state() == Shopper.State.COUNTER) atCounter(sh, g);
            }
        }
        if (core.hud() != null && !working.isEmpty()) core.hud().refreshBoard();
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
        if (now - sh.lastActive > spec.shift().afk() * 1000L) {
            clockOut(shop, "store.out-afk");
            return true;
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
        BusModule bus = eco.bus();
        if (bus == null || bus.spec() == null) return null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String typeId = kind != null ? kind : spec.pickType(hour, allowed(), rnd);
        BusSpec.Type type = typeId == null ? null : bus.spec().types().get(typeId);
        if (type == null) return null;
        StoreSpec.Customer c = spec.customers().getOrDefault(typeId,
                new StoreSpec.Customer(typeId, 1, 1, 3, Map.of(), 0, -1, -1, Map.of()));
        List<String> things = spec.pickItems(c, rnd);
        StoreLayout.Layout l = StoreLayout.of();
        java.util.function.Function<String, String> placeOf = id -> spec.products().containsKey(id) ? spec.products().get(id).place() : null;
        List<Shopper.Stop> round = Shopper.plan(l, things, placeOf, "door");
        double chance = spec.shinyChance() >= 0 ? spec.shinyChance() : bus.spec().shiny().chance();
        boolean shiny = bus.spec().shiny().eligible(type) && rnd.nextDouble() < chance;
        double speed = spec.spawn().speed() / Math.max(1, Math.sqrt(type.slow()));
        int browse = (int) Math.round(40 * Math.min(3, Math.max(1, Math.sqrt(type.slow()))));
        Shopper s = new Shopper(typeId, shiny, round, Shopper.counterThings(things, placeOf), spec.mood().start(), speed, type.mood(), browse,
                l.nodes().get("out"));
        Location at = sh.shop.at(s.x(), 0, s.z(), s.yaw());
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        String skin = shiny ? bus.shinySkin(type, true, false) : null;
        if (skin == null) skin = BusModule.pickSkin(type, false);
        String firstSkin = skin;
        Mannequin m = bus.spawnGuest(at, type, skin, shiny, x -> tagGuest(x, bus.typeTag(type, shiny)));
        Guest g = new Guest(s, type, m);
        if (type.pair()) {
            String other = shiny ? bus.shinySkin(type, true, true) : BusModule.partnerSkin(type, firstSkin, rnd);
            g.partner = bus.spawnGuest(at, type, other, shiny, x -> tagGuest(x, null));
        }
        if (shiny) BusModule.sparkle(m, true);
        sh.guests.add(g);
        s.begin(sh);
        // the door bell: 딸랑
        World w = at.getWorld();
        Location door = sh.shop.at(StoreLayout.DOOR0 + 0.5, 2, StoreLayout.FRONT + 1.5, 0);
        w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.7f);
        Bukkit.getScheduler().runTaskLater(eco, () -> w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 2.0f), 3L);
        Player worker = Bukkit.getPlayer(sh.worker);
        if (!sh.test && worker != null && knows(worker, typeId)) say(g, "recognize");
        else say(g, "enter");
        return g;
    }

    private void tagGuest(Mannequin x, String name) {
        x.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
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
        if (!g.m.isValid()) {
            drop(sh, g);
            return;
        }
        Shopper.Event e = g.s.tick(sh);
        Location to = sh.shop.at(g.s.x(), 0, g.s.z(), g.s.yaw());
        Location from = g.m.getLocation();
        if (from.distanceSquared(to) > 1e-6 || Math.abs(from.getYaw() - to.getYaw()) > 1) g.m.teleport(to);
        if (g.partner != null && g.partner.isValid()) {
            // a step behind on their right
            double r = Math.toRadians(g.s.yaw());
            double px = g.s.x() - Math.sin(r) * 0.55 + Math.cos(r) * 0.5, pz = g.s.z() - Math.cos(r) * 0.55 - Math.sin(r) * 0.5;
            g.partner.teleport(sh.shop.at(px, 0, pz, g.s.yaw()));
        }
        switch (e) {
            case BROWSING -> {
                if (ThreadLocalRandom.current().nextDouble() < 0.35) say(g, "browse");
            }
            case TOOK -> hold(g);
            case EMPTY -> {
                hold(g);
                g.s.mood(spec.mood().empty());
                say(g, "empty");
            }
            case NOTHING_FOUND -> {
                say(g, "empty");
                sh.log.lost();
                sh.line.remove(g.s);
            }
            case AT_COUNTER -> atCounter(sh, g);
            case LEFT -> drop(sh, g);
            default -> {}
        }
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
        if (sh.till != null && sh.till.g == g) clearTill(sh);
        if (sh.warmFor == g) {
            sh.shop.chores.resetOven();
            sh.warmFor = null;
        }
        sh.line.remove(g.s);
        sh.log.lost();
        g.s.leave(sh, why);
    }

    /** A customer gone out the door: their mannequins away. */
    private void drop(Shift sh, Guest g) {
        sh.guests.remove(g);
        sh.line.remove(g.s);
        if (sh.till != null && sh.till.g == g) clearTill(sh);
        if (g.m.isValid()) g.m.remove();
        if (g.partner != null && g.partner.isValid()) g.partner.remove();
    }

    /** A customer says a line of their kind (a speech bubble over them). */
    void say(Guest g, String situation) {
        BusModule bus = eco.bus();
        if (bus == null || !g.m.isValid()) return;
        List<String> lines = null;
        if (g.s.shiny() && (situation.equals("enter") || situation.equals("thanks"))) {
            lines = bus.spec().shiny().lines(g.type, situation.equals("enter") ? "board" : "thanks");
        }
        if (lines == null) lines = spec.lines(g.type.id(), situation);
        if (lines == null || lines.isEmpty()) return;
        bus.bubble(g.m, lines.get(ThreadLocalRandom.current().nextInt(lines.size())), 60L);
    }

    // ------------------------------------------------------------------------------------------ the till

    private void atCounter(Shift sh, Guest g) {
        if (sh.till != null) return;   // (one at a time: the line keeps the next one back)
        List<String> things = new ArrayList<>(g.s.got());
        while (things.size() > 5) things.removeLast();
        List<Integer> prices = new ArrayList<>();
        for (String t : things) prices.add(spec.products().get(t).price());
        Checkout c = new Checkout(things, prices, ThreadLocalRandom.current().nextDouble() < spec.cash().card() ? Checkout.Method.CARD : Checkout.Method.CASH);
        Till t = new Till(g, c);
        sh.till = t;
        StoreLayout.Layout l = StoreLayout.of();
        for (int i = 0; i < things.size(); i++) {
            HallLayout.Point p = l.slots().get(i);
            t.items.add(display(sh.shop.at(p), spec.products().get(things.get(i)).item(), 0.75f));
        }
        EntityEquipment eq = g.m.getEquipment();
        if (eq != null) {
            if (eq.getItemInMainHand().getPersistentDataContainer().has(GUEST)) eq.setItemInMainHand(null);
            if (eq.getItemInOffHand().getPersistentDataContainer().has(GUEST)) eq.setItemInOffHand(null);
        }
        say(g, "pay");
        boolean warmable = false;
        for (String s : things) if (spec.warm().items().contains(s)) warmable = true;
        if (warmable && ThreadLocalRandom.current().nextDouble() < spec.warm().chance()) {
            c.ask("warm");
            g.warm = true;
            Bukkit.getScheduler().runTaskLater(eco, () -> say(g, "warm"), 40L);
        }
        drawScreen(sh);
    }

    /**
     * A display model (store.py's display convention, the 도박장's: spawned at yaw 0, its north side turned to the point's yaw
     * by the transformation, its bottom or centre on the point as drawn).
     */
    private ItemDisplay display(Location at, String item, float scale) {
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

    /** Every thing scanned: the customer pays (card: the terminal; cash: the money on the counter, the change buttons). */
    private void startPaying(Shift sh) {
        Till t = sh.till;
        if (t == null || t.payStarted) return;
        t.payStarted = true;
        StoreLayout.Layout l = StoreLayout.of();
        if (t.c.method() == Checkout.Method.CARD) {
            say(t.g, "card");
            t.paying.add(display(sh.shop.at(l.card()), "economy:store_card", 1f));
            t.buttons.put("card", display(sh.shop.at(l.cardKey()), "economy:store_btn_card", BUTTON));
        } else {
            say(t.g, "cash");
            boolean round = ThreadLocalRandom.current().nextDouble() < spec.cash().roundUp();
            t.c.tender(Checkout.tender(t.c.bill(), spec.cash().bills(), round));
            List<Integer> notes = Checkout.notes(t.c.tendered(), spec.cash().bills());
            for (int i = 0; i < Math.min(5, notes.size()); i++) {
                HallLayout.Point p = l.slots().get(i);
                t.paying.add(display(sh.shop.at(p.x(), p.y() + 0.01 * i, p.z(), p.yaw() + 8 * i), "economy:store_cash_" + notes.get(i), 1f));
            }
            if (!spec.cash().manual()) {
                t.c.autoChange();
            } else {
                for (Map.Entry<Integer, HallLayout.Point> b : l.changeButtons().entrySet()) {
                    t.buttons.put("chg:" + b.getKey(), display(sh.shop.at(b.getValue()), "economy:store_btn_change_" + b.getKey(), BUTTON));
                }
                t.buttons.put("give", display(sh.shop.at(l.give()), "economy:store_btn_give", BUTTON));
                t.buttons.put("clear", display(sh.shop.at(l.clear2()), "economy:store_btn_no", BUTTON));
            }
        }
        drawScreen(sh);
        maybeDone(sh);
    }

    /** The counter's screen: the total; paying cash, what was handed over and the change to give, big. */
    private void drawScreen(Shift sh) {
        Till t = sh.till;
        if (t == null) return;
        String text;
        if (!t.c.allScanned()) text = Lang.raw("store.screen-scan", "total", won(t.c.total()), "n", count(t.c), "all", t.c.size());
        else if (!t.c.paid() && t.c.method() == Checkout.Method.CARD) text = Lang.raw("store.screen-card", "total", won(t.c.bill()));
        else if (!t.c.paid()) text = Lang.raw("store.screen-cash", "total", won(t.c.bill()), "paid", won(t.c.tendered()), "change", won(t.c.change()),
                "hand", won(t.c.inHand()));
        else if (t.c.asks().contains("warm")) text = Lang.raw("store.screen-warm", "state", ovenWord(sh));
        else text = Lang.raw("store.screen-done");
        Component c = Text.mm(text);
        drawFace(sh, t);
        if (t.screen != null && t.screen.isValid()) {
            t.screen.text(c);
            return;
        }
        Location at = sh.shop.at(StoreLayout.of().screen());
        t.screen = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(c);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(200, 20, 40, 30));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
            d.setViewRange(0.3f);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
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
        if (t == null || !t.c.done()) return;
        Guest g = t.g;
        double mood = g.type.floor(g.s.mood(spec.patience()));
        int stars = spec.stars(mood);
        long earn = spec.earn(mood, g.type.tip(), !t.c.mistake());
        sh.log.served(stars, earn, t.c.bill(), !t.c.mistake());
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
            HudModule.flash(p, Lang.mm("store.served", "type", eco.bus().typeTag(g.type, g.s.shiny()), "stars", starText(stars), "g", gold(earn)), 2000);
            if (!sh.test && eco.people() != null) eco.people().met(p, g.type.id(), g.s.shiny(), PeopleSpec.PLACE_STORE);
        }
        say(g, "thanks");
        if (g.s.shiny()) BusModule.sparkle(g.m, false);
        // what they bought goes with them in a GS26 bag
        EntityEquipment eq = g.m.getEquipment();
        if (eq != null) {
            ItemStack bag = StoreBuilder.shown(core, "economy:store_bag");
            bag.editMeta(m -> m.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1));
            if (eq.getItemInMainHand().getType().isAir()) eq.setItemInMainHand(bag);
            else if (eq.getItemInOffHand().getType().isAir()) eq.setItemInOffHand(bag);
        }
        clearTill(sh);
        sh.line.remove(g.s);
        g.s.leave(sh, Shopper.Exit.PAID);
    }

    private void clearTill(Shift sh) {
        Till t = sh.till;
        if (t == null) return;
        for (Entity e : t.items) e.remove();
        for (Entity e : t.paying) e.remove();
        for (Entity e : t.buttons.values()) e.remove();
        if (t.screen != null) t.screen.remove();
        if (t.face != null) t.face.remove();
        sh.till = null;
    }

    // ------------------------------------------------------------------------------------------ the worker's clicks

    /** The things the worker can press now (the till's items, its buttons, the boxes), in the world. */
    private List<ButtonPick.Target> targets(Shift sh) {
        List<ButtonPick.Target> out = new ArrayList<>();
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
                out.add(new ButtonPick.Target(sh.worker, "item:" + i, a[0], a[1], a[2], rx, rz, 0.14, 0.13, 0.14));
            }
            for (String key : t.buttons.keySet()) {
                HallLayout.Point p = point(l, key);
                if (p == null) continue;
                double[] a = site.at(p.x(), p.y(), p.z());
                out.add(new ButtonPick.Target(sh.worker, key, a[0], a[1], a[2], rx, rz, 0.225, 0.115, 0.06));
            }
            if (t.buttons.containsKey("card")) {   // the card terminal itself too
                double[] a = site.at(l.terminal().x(), l.terminal().y(), l.terminal().z());
                out.add(new ButtonPick.Target(sh.worker, "card", a[0], a[1], a[2], rx, rz, 0.25, 0.08, 0.2));
            }
        }
        int i = 0;
        for (String box : sh.shop.chores.boxes()) {
            HallLayout.Point p = l.boxes().get(Math.min(i++, l.boxes().size() - 1));
            double[] a = site.at(p.x(), p.y() + 0.25, p.z());
            out.add(new ButtonPick.Target(sh.worker, "box:" + box, a[0], a[1], a[2], rx, rz, 0.38, 0.25, 0.32));
        }
        return out;
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
        List<ButtonPick.Target> targets = targets(sh);
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
        if (key.startsWith("item:") && t != null) {
            if (!isTool(p.getInventory().getItemInMainHand())) {
                HudModule.flash(p, Lang.mm("store.need-scanner"), 2000);
                return;
            }
            int i = Integer.parseInt(key.substring(5));
            if (!t.c.scan(i)) return;
            p.getWorld().playSound(me, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 2.0f);   // 삑
            ItemDisplay it = t.items.get(i);
            Location to = sh.shop.at(StoreLayout.of().scanned().get(i));
            to.setYaw(0);   // (its turn is in its transformation)
            if (it.isValid()) it.teleport(to);
            drawScreen(sh);
            if (t.c.allScanned()) startPaying(sh);
            return;
        }
        if (key.equals("card") && t != null) {
            if (t.c.card()) {
                p.getWorld().playSound(me, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.8f);
                for (Entity e : t.paying) e.remove();
                t.paying.clear();
                drawScreen(sh);
                maybeDone(sh);
            }
            return;
        }
        if (key.startsWith("chg:") && t != null) {
            t.c.press(Integer.parseInt(key.substring(4)));
            p.playSound(me, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.4f);
            pop(t.buttons.get(key));
            drawScreen(sh);
            return;
        }
        if (key.equals("clear") && t != null) {
            t.c.clear();
            p.playSound(me, Sound.BLOCK_WOODEN_BUTTON_CLICK_OFF, 0.6f, 1.0f);
            drawScreen(sh);
            return;
        }
        if (key.equals("give") && t != null) {
            if (t.c.inHand() == 0 && t.c.change() > 0) {
                HudModule.flash(p, Lang.mm("store.give-empty", "change", won(t.c.change())), 2000);
                return;
            }
            int before = t.c.change();
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
                    HudModule.flash(p, Lang.mm("store.over", "won", won(t.c.overpaid()), "change", won(before)), 3000);
                }
                default -> {}
            }
            if (t.c.paid()) {
                for (Entity e : t.paying) e.remove();
                t.paying.clear();
                for (String k : new ArrayList<>(t.buttons.keySet())) t.buttons.remove(k).remove();
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
            if (sh.shop.chores.pick(box)) {
                p.playSound(me, Sound.ITEM_BUNDLE_INSERT, 0.8f, 0.8f);
                HudModule.flash(p, Lang.mm("store.box-picked", "box", Lang.raw("store.box." + box)), 2500);
                showBoxes(sh);
            }
        }
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

    /** A shelf's model follows its stock (full / half / empty). */
    private void refreshShelf(Shop shop, String place) {
        String state = shop.chores.state(spec, place);
        if (state.equals(shop.shown.get(place))) return;
        World w = shop.world();
        if (w == null) return;
        Map<String, UUID> pieces = StoreBuilder.pieces(w, shop.name, shop.site);
        List<String> roles = new ArrayList<>();
        String item;
        if (place.equals("fridge")) {
            roles.addAll(List.of("fridge_0", "fridge_1", "fridge_2"));
            item = "economy:store_fridge_" + state;
        } else {
            roles.add("shelf_" + place);
            item = "economy:store_shelf_" + place + "_" + state;
        }
        if (core.content().item(item).isEmpty()) return;   // (the pack does not have it yet)
        ItemStack stack = StoreBuilder.shown(core, item);
        for (String r : roles) {
            UUID u = pieces.get(r);
            if (u != null && Bukkit.getEntity(u) instanceof ItemDisplay d) d.setItemStack(stack);
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
        if (!ch.restock(spec, place)) {
            HudModule.flash(p, Lang.mm("store.box-wrong", "box", Lang.raw("store.box." + ch.carrying()), "shelf", Lang.raw("store.place." + place)), 2500);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            return;
        }
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.3f);
        HudModule.flash(p, Lang.mm("store.restocked", "shelf", Lang.raw("store.place." + place)), 2000);
        refreshShelf(sh.shop, place);
    }

    /** The microwave: food in (someone asked), the ding, out (warm), or burnt. */
    private void ovenClick(Player p, Shift sh) {
        Chores ch = sh.shop.chores;
        Till t = sh.till;
        switch (ch.oven()) {
            case IDLE -> {
                if (t == null || !t.c.asks().contains("warm")) {
                    HudModule.flash(p, Lang.mm("store.oven-nothing"), 2000);
                    return;
                }
                ch.startOven(spec.warm().seconds());
                sh.warmFor = t.g;
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 1.6f);
                HudModule.flash(p, Lang.mm("store.oven-start", "s", spec.warm().seconds()), 2000);
            }
            case WARMING -> HudModule.flash(p, Lang.mm("store.oven-wait", "s", (ch.ovenTicks() + 19) / 20), 1500);
            case DONE -> {
                ch.takeOut();
                p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.6f, 1.6f);
                Guest g = sh.warmFor;
                sh.warmFor = null;
                if (g != null && t != null && t.g == g) {
                    t.c.answer("warm");
                    g.s.mood(spec.mood().warmed());
                    say(g, "warmed");
                    maybeDone(sh);
                }
            }
        }
        drawOven(sh);
        drawScreen(sh);
    }

    private void oven(Shift sh) {
        Chores ch = sh.shop.chores;
        Chores.OvenEvent e = ch.tickOven(spec.warm().forget());
        if (e == Chores.OvenEvent.NONE) {
            if (ticks % 20 == 0) drawOven(sh);
            return;
        }
        Location at = sh.shop.at(StoreLayout.of().oven());
        if (e == Chores.OvenEvent.DING) {
            at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 2.0f);   // 띵
            Player p = Bukkit.getPlayer(sh.worker);
            if (p != null) HudModule.flash(p, Lang.mm("store.oven-ding"), 2500);
        } else {
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.2, 0.2, 0.2, 0.01);
            Guest g = sh.warmFor;
            sh.warmFor = null;
            Till t = sh.till;
            if (g != null && t != null && t.g == g) {
                t.c.answer("warm");
                g.s.mood(spec.mood().burnt());
                say(g, "burnt");
                maybeDone(sh);
            }
        }
        drawOven(sh);
        drawScreen(sh);
    }

    private void drawOven(Shift sh) {
        Chores ch = sh.shop.chores;
        if (ch.oven() == Chores.Oven.IDLE) {
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
        if (sh != null && sh.worker.equals(p.getUniqueId())) {
            clockOut(shop, "store.out-hanger");
            return;
        }
        if (sh != null) {
            Player w = Bukkit.getPlayer(sh.worker);
            p.sendMessage(Lang.msg("store.taken", "player", w == null ? "?" : w.getName()));
            return;
        }
        clockIn(p, shop, false);
    }

    boolean clockIn(Player p, Shop shop, boolean test) {
        if (working.containsKey(p.getUniqueId())) {
            p.sendMessage(Lang.msg("store.already"));
            return false;
        }
        if (job != null && job.running()) {
            p.sendMessage(Lang.msg("store.building"));
            return false;
        }
        if (eco.bus() == null || eco.bus().spec() == null) {
            p.sendMessage(Lang.msg("store.no-bus"));
            return false;
        }
        if (!giveScanner(p)) {
            p.sendMessage(Lang.msg("store.inventory-full"));
            return false;
        }
        PlayerInventory inv = p.getInventory();
        if (inv.getChestplate() == null || inv.getChestplate().getType().isAir()) inv.setChestplate(tool(VEST));
        Shift sh = new Shift(shop, p.getUniqueId(), test);
        Location l = p.getLocation();
        sh.lastX = l.getX();
        sh.lastZ = l.getZ();
        sh.lastYaw = l.getYaw();
        sh.lastPitch = l.getPitch();
        sh.nextSpawn = 3;
        shop.shift = sh;
        working.put(p.getUniqueId(), shop);
        shop.shown.clear();
        for (String place : spec.shelves().keySet()) refreshShelf(shop, place);
        World w = shop.world();
        if (w != null) shop.chores.delivery(spec, Chores.slot(w.getFullTime(), spec.deliveries()));   // (the first one only remembers)
        showBoxes(sh);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.1f);
        p.sendMessage(Lang.msg(test ? "store.in-test" : "store.in", "store", shop.name, "share", Math.round(spec.pay().share() * 100)));
        HudModule.flash(p, Lang.mm("store.in-flash"), 3000);
        if (core.hud() != null) core.hud().refreshBoard();
        return true;
    }

    /** Ends a shift: everyone out, the tools back, the pay (why: the message key, null: the worker left the server). */
    void clockOut(Shop shop, String why) {
        Shift sh = shop.shift;
        if (sh == null) return;
        shop.shift = null;
        working.remove(sh.worker);
        clearTill(sh);
        for (Guest g : new ArrayList<>(sh.guests)) {
            if (g.m.isValid()) g.m.remove();
            if (g.partner != null && g.partner.isValid()) g.partner.remove();
        }
        sh.guests.clear();
        sh.line.clear();
        for (ItemDisplay d : sh.boxes.values()) d.remove();
        sh.boxes.clear();
        if (sh.ovenText != null) sh.ovenText.remove();
        shop.chores.putDown();
        shop.chores.resetOven();
        Player p = Bukkit.getPlayer(sh.worker);
        if (p != null) stripTools(p);
        ShiftLog.Payout pay = sh.log.payout(spec);
        if (!sh.test && pay.pay() > 0) core.economy().deposit(sh.worker, pay.pay(), "store");
        if (p != null) receipt(p, shop, sh, pay, why);
        clicks.forget(sh.worker);
        hints.forget(sh.worker);
        if (core.hud() != null) core.hud().refreshBoard();
    }

    private void receipt(Player p, Shop shop, Shift sh, ShiftLog.Payout pay, String why) {
        if (why != null) p.sendMessage(Lang.msg(why));
        long minutes = Math.max(1, (System.currentTimeMillis() - sh.started) / 60000);
        p.sendMessage(Lang.mm("store.receipt-head", "store", shop.name, "minutes", minutes));
        p.sendMessage(Lang.mm("store.receipt-people", "served", pay.served(), "lost", pay.lost(), "stars", starNumber(pay.stars())));
        p.sendMessage(Lang.mm("store.receipt-sales", "sales", won(pay.sales()), "mistakes", pay.mistakes()));
        p.sendMessage(Lang.mm("store.receipt-earned", "earned", gold(pay.earned()), "bonus", gold(pay.bonus()), "rate", Math.round(pay.bonusRate() * 100)));
        p.sendMessage(Lang.mm("store.receipt-share", "share", Math.round(spec.pay().share() * 100), "cut", gold(pay.share()),
                "deduct", pay.deduction() > 0 ? Lang.raw("store.receipt-deduct", "g", gold(pay.deduction())) : ""));
        p.sendMessage(Lang.mm(sh.test ? "store.receipt-test" : "store.receipt-pay", "pay", gold(pay.pay())));
        String word = pay.served() == 0 ? "store.word-none" : pay.stars() >= 4.5 ? "store.word-5" : pay.stars() >= 3.5 ? "store.word-4"
                : pay.stars() >= 2.5 ? "store.word-3" : "store.word-low";
        p.sendMessage(Lang.mm("store.receipt-word", "line", Lang.raw(word)));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_WORK_CARTOGRAPHER, 0.8f, 1.2f);
    }

    // ------------------------------------------------------------------------------------------ tools

    private ItemStack tool(String id) {
        ItemStack it = dev.stsv.economy.util.Items.create(id, 1).orElse(null);
        if (it == null) {
            it = new ItemStack(id.equals(VEST) ? Material.LEATHER_CHESTPLATE : Material.PAPER);
            it.editMeta(m -> {
                m.customName(Lang.item(id.equals(VEST) ? "store.vest-name" : "store.scanner-name"));
                if (m instanceof org.bukkit.inventory.meta.LeatherArmorMeta lm) lm.setColor(Color.fromRGB(0x1F9D55));
            });
        }
        it.editMeta(m -> m.getPersistentDataContainer().set(TOOL, PersistentDataType.BYTE, (byte) 1));
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
        if (role.equals("eatbar") || role.startsWith("parasol")) return;   // seats: anyone sits
        e.setCancelled(true);
        if (!clicks.accept(p.getUniqueId(), Bukkit.getCurrentTick())) return;   // one click, however many events
        if (role.equals("hanger")) {
            if (!p.isSneaking()) hanger(p, shop);
            return;
        }
        if (mine != shop) return;
        Shift sh = shop.shift;
        sh.lastActive = System.currentTimeMillis();
        if (role.startsWith("shelf_")) restock(p, sh, role.substring(6));
        else if (role.startsWith("fridge_")) restock(p, sh, "fridge");
        else if (role.equals("microwave")) ovenClick(p, sh);
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
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL || !working.containsKey(e.getPlayer().getUniqueId())) return;
        if (workerClick(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityUse(PlayerInteractEntityEvent e) {
        Entity x = e.getRightClicked();
        if (x.getPersistentDataContainer().has(GUEST)) e.setCancelled(true);
        if (working.containsKey(e.getPlayer().getUniqueId()) && workerClick(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(PrePlayerAttackEntityEvent e) {
        if (e.getAttacked().getPersistentDataContainer().has(GUEST)) e.setCancelled(true);
        if (working.containsKey(e.getPlayer().getUniqueId()) && workerClick(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isTool(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventory(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getType() == InventoryType.CRAFTING) return;   // their own inventory: move it about freely
        boolean tool = isTool(e.getCurrentItem()) || isTool(e.getCursor());
        if (!tool && e.getHotbarButton() >= 0) tool = isTool(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        if (tool) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getType() != InventoryType.CRAFTING && isTool(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(StoreModule::isTool);
        Shop s = working.get(e.getPlayer().getUniqueId());
        if (s != null) clockOut(s, "store.out-death");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Shop s = working.get(e.getPlayer().getUniqueId());
        if (s != null) clockOut(s, null);
        stripTools(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!working.containsKey(e.getPlayer().getUniqueId())) stripTools(e.getPlayer());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) if (x.getPersistentDataContainer().has(GUEST)) x.remove();
    }

    private void purge() {
        for (World w : Bukkit.getWorlds()) for (Entity x : w.getEntities()) if (x.getPersistentDataContainer().has(GUEST)) x.remove();
    }

    // ------------------------------------------------------------------------------------------ HUD

    private HudLayout.Board board(Player viewer) {
        Shop shop = working.get(viewer.getUniqueId());
        if (shop == null || shop.shift == null) return null;
        Shift sh = shop.shift;
        ShiftLog.Payout pay = sh.log.payout(spec);
        List<HudLayout.Board.Row> rows = new ArrayList<>();
        rows.add(new HudLayout.Board.Row(null, Lang.mm("store.board-people", "served", pay.served(), "lost", pay.lost(),
                "stars", pay.served() + pay.lost() == 0 ? "-" : starNumber(pay.stars()))));
        rows.add(new HudLayout.Board.Row(null, Lang.mm("store.board-sales", "sales", won(pay.sales()))));
        rows.add(new HudLayout.Board.Row(null, Lang.mm(sh.test ? "store.board-pay-test" : "store.board-pay", "pay", gold(pay.pay()))));
        for (String todo : todos(sh)) rows.add(new HudLayout.Board.Row(null, Text.mm(todo)));
        return new HudLayout.Board(Lang.mm("store.board-title", "store", shop.name), rows);
    }

    /** What wants doing (the board's last rows, most pressing first). */
    private List<String> todos(Shift sh) {
        List<String> out = new ArrayList<>();
        Chores ch = sh.shop.chores;
        if (ch.oven() == Chores.Oven.DONE) out.add(Lang.raw("store.todo-ding"));
        else if (sh.till != null && sh.till.c.asks().contains("warm") && ch.oven() == Chores.Oven.IDLE) out.add(Lang.raw("store.todo-warm"));
        if (ch.carrying() != null) out.add(Lang.raw("store.todo-carry", "box", Lang.raw("store.box." + ch.carrying())));
        for (String place : ch.empty()) out.add(Lang.raw("store.todo-empty", "shelf", Lang.raw("store.place." + place)));
        if (!ch.boxes().isEmpty()) out.add(Lang.raw("store.todo-boxes", "n", ch.boxes().size()));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private Component actionLine(Player viewer) {
        Shop shop = working.get(viewer.getUniqueId());
        if (shop == null || shop.shift == null) return null;
        Shift sh = shop.shift;
        List<ActionLine.Part> parts = new ArrayList<>();
        Till t = sh.till;
        if (t != null) {
            String who = Lang.raw("store.line-who", "type", eco.bus().typeTag(t.g.type, t.g.s.shiny()));
            parts.add(new ActionLine.Part(who, t.g.type.name(), 2));
            String what = !t.c.allScanned() ? Lang.raw("store.line-scan", "n", count(t.c), "all", t.c.size(), "total", won(t.c.total()))
                    : t.c.method() == Checkout.Method.CARD ? Lang.raw("store.line-card", "total", won(t.c.bill()))
                    : t.c.paid() ? Lang.raw("store.line-done", "total", won(t.c.bill()))
                    : Lang.raw("store.line-change", "change", won(t.c.change()), "hand", won(t.c.inHand()));
            parts.add(new ActionLine.Part(what, Text.plain(what), 0));
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
            default -> s.sendMessage(Lang.msg("store.admin-usage"));
        }
    }

    private List<String> adminComplete(CommandSender s, String[] a) {
        String last = a[a.length - 1];
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("건설", "철거", "목록", "시험", "손님", "배송"));
        else if (a.length == 2 && a[0].equals("철거")) out.addAll(shops.keySet());
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
                    pct -> p.sendActionBar(Lang.mm("store.build-progress", "pct", pct)), placed -> built(p, placed));
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
        try {
            job = StoreBuilder.remove(eco, core, name, shop.site, undoFile(name), settings(),
                    pct -> { if (s instanceof Player p) p.sendActionBar(Lang.mm("store.build-progress", "pct", pct)); },
                    restored -> s.sendMessage(Lang.msg("store.removed", "store", name, "blocks", restored)));
        } catch (IOException | IllegalArgumentException e) {
            s.sendMessage(Lang.msg("store.build-failed", "error", String.valueOf(e.getMessage())));
            return;
        }
        shops.remove(name);
        core.db().execute("DELETE FROM eco_store WHERE name = ?", name);
    }

    private void adminList(CommandSender s) {
        if (shops.isEmpty()) {
            s.sendMessage(Lang.msg("store.list-empty"));
            return;
        }
        s.sendMessage(Lang.msg("store.list-head", "n", shops.size()));
        for (Shop shop : shops.values()) {
            Player w = shop.shift == null ? null : Bukkit.getPlayer(shop.shift.worker);
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
        if (shop.shift.guests.size() >= spec.spawn().maxInside()) {
            p.sendMessage(Lang.msg("store.guest-full", "n", spec.spawn().maxInside()));
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

    private Path undoFile(String name) {
        return eco.getDataFolder().toPath().resolve("store").resolve(name + ".undo");
    }

    private StoreBuilder.HallBuilderSettings settings() {
        return StoreBuilder.HallBuilderSettings.of(HallBuilder.Settings.of(eco.getConfig()));
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
