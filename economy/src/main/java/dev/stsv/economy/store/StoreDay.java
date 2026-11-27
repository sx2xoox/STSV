package dev.stsv.economy.store;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.bus.BusModule;
import dev.stsv.economy.bus.BusSpec;
import dev.stsv.economy.hwatu.hall.HallLayout;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A store's day besides the till (3차 + 보완안, README "편의점"): the rush hours, the day's events (정전 · 수학여행 단체 손님 ·
 * 본사 점검), the 발주 and its tablet, the "이달의 점장" line under the sign, and the visits other modules send in
 * ({@link StoreVisit}). Lives beside {@link StoreModule}, which keeps the shifts and the till.
 */
final class StoreDay {

    /** The breaker on the back wall (local block: a lever on the stockroom wall, right of the fridges). */
    static final int[] BREAKER = {-4, 1, StoreLayout.BACK - 1};
    /** What the lamps become while the power is out. */
    static final String DARK = "minecraft:light_gray_concrete", LAMP = "minecraft:sea_lantern";

    private final StoreModule m;

    StoreDay(StoreModule m) {
        this.m = m;
    }

    private StoreLife life() {
        return m.life();
    }

    /** The game day of a world (a new day at game hour 0 = 06:00, as the deliveries count it). */
    static long gameDay(World w) {
        return w == null ? 0 : Math.floorDiv(w.getFullTime() + 6000, 24000);
    }

    // ------------------------------------------------------------------------------------------ every second of a shift

    void second(StoreModule.Shop shop, StoreModule.Shift sh, Player p, int hour) {
        long day = gameDay(shop.world());
        rush(shop, sh, p, hour, day);
        events(shop, sh, p, day);
        crowd(sh, hour);
        outageClock(sh);
        if (++sh.visitClock >= 30) {
            sh.visitClock = 0;
            visit(shop, sh, p, hour);
        }
    }

    /** The customers' gap × this now (a rush, two workers). */
    double spawnFactor(StoreModule.Shift sh) {
        double f = 1;
        if (sh.rush != null) f *= life().rush().every();
        if (sh.helper != null) f *= life().staff().busy();
        return f;
    }

    // ------------------------------------------------------------------------------------------ 러시

    private void rush(StoreModule.Shop shop, StoreModule.Shift sh, Player p, int hour, long day) {
        long now = System.currentTimeMillis();
        if (sh.rush != null) {
            if (sh.rush.tick(now) == StoreLife.RushRun.State.MISSED) {
                HudModule.flash(p, Lang.mm("store.rush-missed", "n", sh.rush.served(), "all", sh.rush.target()), 3000);
                sh.rush = null;
            }
            return;
        }
        int w = life().rush().window(hour);
        if (w < 0) return;
        long key = day * 16 + w;
        if (shop.rushKey == key) return;   // this window's rush came today
        shop.rushKey = key;
        startRush(sh, p);
    }

    void startRush(StoreModule.Shift sh, Player p) {
        StoreLife.Rush r = life().rush();
        sh.rush = new StoreLife.RushRun(System.currentTimeMillis(), r.target(), r.seconds());
        sh.nextSpawn = Math.min(sh.nextSpawn, 1);
        if (p == null) return;
        p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.5f, 1.4f);
        p.sendMessage(Lang.msg("store.rush-start", "n", r.target(), "s", r.seconds(), "g", StoreModule.gold(r.bonus())));
    }

    /** A customer served: the rush counts them; cleared = the bonus and the fanfare. */
    void served(StoreModule.Shift sh, Player p) {
        if (sh.rush == null) return;
        if (sh.rush.served(System.currentTimeMillis()) != StoreLife.RushRun.State.CLEARED) return;
        sh.rush = null;
        long bonus = life().rush().bonus();
        sh.log.bonus(bonus);
        long paid = m.payNow(sh);
        if (p == null) return;
        Location at = p.getLocation().add(0, 2, 0);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        Bukkit.getScheduler().runTaskLater(m.eco(), () -> p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.1f), 6L);
        at.getWorld().spawnParticle(Particle.FIREWORK, at, 40, 0.6, 0.4, 0.6, 0.08);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 16, 0.8, 0.5, 0.8, 0.1);
        HudModule.flash(p, Lang.mm("store.rush-cleared-flash", "g", StoreModule.gold(paid)), 3500);
    }

    // ------------------------------------------------------------------------------------------ 하루 이벤트

    private void events(StoreModule.Shop shop, StoreModule.Shift sh, Player p, long day) {
        StoreLife.Events ev = life().events();
        if (++sh.eventClock < ev.check()) return;
        sh.eventClock = 0;
        if (shop.eventDay == day && shop.eventsToday >= ev.perDay()) return;
        if (busy(sh)) return;
        StoreLife.Event e = ev.pick(ThreadLocalRandom.current().nextDouble());
        if (e != null) start(shop, sh, p, e, day);
    }

    /** Whether an event is going on now. */
    boolean busy(StoreModule.Shift sh) {
        return sh.outage || sh.crowdLeft > 0 || sh.inspector != null;
    }

    /** Starts an event now (the roll, or an admin's): false when it cannot (one is on, nothing for it here). */
    boolean start(StoreModule.Shop shop, StoreModule.Shift sh, Player p, StoreLife.Event e, long day) {
        if (busy(sh)) return false;
        boolean ok = switch (e) {
            case OUTAGE -> outage(shop, sh, p);
            case GROUP -> crowdStart(sh, p);
            case INSPECT -> inspect(sh, p);
        };
        if (!ok) return false;
        if (shop.eventDay != day) {
            shop.eventDay = day;
            shop.eventsToday = 0;
        }
        shop.eventsToday++;
        return true;
    }

    // ---- 정전

    private boolean outage(StoreModule.Shop shop, StoreModule.Shift sh, Player p) {
        StoreLife.Outage o = life().events().outage();
        sh.outage = true;
        sh.outageUntil = System.currentTimeMillis() + o.seconds() * 1000L;
        lamps(shop, false);
        World w = shop.world();
        if (w != null) w.playSound(shop.at(0.5, 2, 9.5, 0), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.6f);
        for (StoreModule.Guest g : sh.guests) {
            if (g.inspector || g.visit != null) continue;
            g.s.mood(o.mood());
        }
        if (!sh.guests.isEmpty()) {
            StoreModule.Guest g = sh.guests.get(ThreadLocalRandom.current().nextInt(sh.guests.size()));
            if (!g.inspector && g.visit == null) m.sayLine(g, life().events().lines("outage"), Bubbles.Kind.CHATTER);
        }
        if (p != null) {
            p.sendMessage(Lang.msg("store.outage", "s", o.seconds()));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS, 60, 0, false, false, false));
        }
        return true;
    }

    private void outageClock(StoreModule.Shift sh) {
        if (sh.outage && System.currentTimeMillis() > sh.outageUntil) endOutage(sh, false);
    }

    /** The power back: fixed (the breaker) pays a little. */
    void endOutage(StoreModule.Shift sh, boolean fixed) {
        if (!sh.outage) return;
        sh.outage = false;
        lamps(sh.shop, true);
        Player p = m.worker(sh);
        World w = sh.shop.world();
        if (w != null) w.playSound(sh.shop.at(0.5, 2, 9.5, 0), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        if (!sh.guests.isEmpty()) {
            StoreModule.Guest g = sh.guests.get(ThreadLocalRandom.current().nextInt(sh.guests.size()));
            if (!g.inspector && g.visit == null) m.sayLine(g, life().events().lines("restored"), Bubbles.Kind.CHATTER);
        }
        if (fixed) {
            sh.log.bonus(life().events().outage().fix());
            m.payNow(sh);
        }
        if (p != null) HudModule.flash(p, Lang.mm(fixed ? "store.outage-fixed" : "store.outage-over"), 2500);
    }

    /** The roof's lamps off (the power out) or on; a store whose lamps were left off (a crash) is lit again at start. */
    void lamps(StoreModule.Shop shop, boolean on) {
        World w = shop.world();
        if (w == null) return;
        String key = "store.dark." + shop.name;
        if (on) m.eco().kv().remove(key);
        else m.eco().kv().set(key, "1");
        for (HallLayout.Block b : shop.layout().blocks()) {
            if (!b.data().equals(LAMP)) continue;
            int[] at = shop.site.block(b.x(), b.y(), b.z());
            Block x = w.getBlockAt(at[0], at[1], at[2]);
            if (on && x.getType() == Material.LIGHT_GRAY_CONCRETE) x.setBlockData(Bukkit.createBlockData(LAMP), false);
            else if (!on && x.getType() == Material.SEA_LANTERN) x.setBlockData(Bukkit.createBlockData(DARK), false);
        }
        // the breaker's handle: down while the power is out
        int[] lv = shop.site.block(BREAKER[0], BREAKER[1], BREAKER[2]);
        Block lever = w.getBlockAt(lv[0], lv[1], lv[2]);
        if (lever.getBlockData() instanceof org.bukkit.block.data.type.Switch sw && sw.isPowered() == on) {
            sw.setPowered(!on);
            lever.setBlockData(sw, false);
            if (on) w.spawnParticle(Particle.ELECTRIC_SPARK, lever.getLocation().add(0.5, 0.5, 0.5), 12, 0.15, 0.2, 0.15, 0.05);
        }
    }

    /** The lamps off or on for a moment (the 새벽 door bell): only the blocks, nothing remembered. */
    void flicker(StoreModule.Shop shop, boolean on) {
        World w = shop.world();
        if (w == null || !shop.loaded()) return;
        for (HallLayout.Block b : shop.layout().blocks()) {
            if (!b.data().equals(LAMP)) continue;
            int[] at = shop.site.block(b.x(), b.y(), b.z());
            Block x = w.getBlockAt(at[0], at[1], at[2]);
            if (on && x.getType() == Material.LIGHT_GRAY_CONCRETE) x.setBlockData(Bukkit.createBlockData(LAMP), false);
            else if (!on && x.getType() == Material.SEA_LANTERN) x.setBlockData(Bukkit.createBlockData(DARK), false);
        }
    }

    /** At start: lamps left off by a shift that never ended (a crash) are lit again. */
    void relight(java.util.Collection<StoreModule.Shop> shops) {
        for (StoreModule.Shop s : shops) if (m.eco().kv().has("store.dark." + s.name)) lamps(s, true);
    }

    /** A worker clicked a block: the breaker (or the wall it hangs on) during an outage restores the power. */
    boolean breaker(Player p, StoreModule.Shift sh, Block b) {
        if (!sh.outage || b == null) return false;
        int[] lever = sh.shop.site.block(BREAKER[0], BREAKER[1], BREAKER[2]), wall = sh.shop.site.block(BREAKER[0], BREAKER[1], BREAKER[2] + 1);
        boolean hit = (b.getX() == lever[0] && b.getY() == lever[1] && b.getZ() == lever[2]) || (b.getX() == wall[0] && b.getY() == wall[1] && b.getZ() == wall[2]);
        if (!hit) return false;
        p.playSound(p.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 1f);
        endOutage(sh, true);
        return true;
    }

    // ---- 수학여행 단체 손님

    private boolean crowdStart(StoreModule.Shift sh, Player p) {
        StoreLife.Crowd c = life().events().group();
        sh.crowdLeft = StoreLife.between(c.min(), c.max(), ThreadLocalRandom.current());
        sh.crowdClock = 0;
        if (p != null) {
            HudModule.flash(p, Lang.mm("store.crowd-flash", "n", sh.crowdLeft), 3000);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 0.7f, 1.3f);
        }
        return true;
    }

    private void crowd(StoreModule.Shift sh, int hour) {
        if (sh.crowdLeft <= 0 || --sh.crowdClock > 0) return;
        StoreLife.Crowd c = life().events().group();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sh.crowdClock = StoreLife.between(c.gapMin(), c.gapMax(), rnd);
        if (sh.guests.size() >= sh.shop.maxInside()) return;   // (the store holds as many as it holds: they wait outside)
        StoreModule.Guest g = m.spawn(sh, c.kinds().get(rnd.nextInt(c.kinds().size())), hour, null, false, null);
        if (g == null) {
            sh.crowdLeft = 0;
            return;
        }
        sh.crowdLeft--;
        if (rnd.nextDouble() < 0.6) m.sayLine(g, life().events().lines("group"), Bubbles.Kind.CHATTER);
    }

    // ---- 본사 점검

    /** The inspector's store: they walk the shelves without taking anything and never join the line. */
    private static final class Look implements Shopper.Env {
        private final StoreModule.Shift sh;

        Look(StoreModule.Shift sh) {
            this.sh = sh;
        }

        @Override
        public List<StoreLayout.Node> path(String from, String to) {
            return sh.path(from, to);
        }

        @Override
        public boolean take(String place) {
            return true;
        }

        @Override
        public int line(Shopper s) {
            return -1;
        }

        @Override
        public StoreLayout.Node queueSpot(int i) {
            return sh.queueSpot(i);
        }
    }

    private boolean inspect(StoreModule.Shift sh, Player p) {
        BusModule bus = m.eco().bus();
        StoreLife.Inspect in = life().events().inspect();
        BusSpec.Type type = bus == null || bus.spec() == null ? null : bus.spec().types().get(in.kind());
        if (type == null) return false;
        StoreLayout.Layout l = sh.shop.layout();
        List<String> things = new ArrayList<>();
        for (String place : l.spots().keySet()) {
            for (StoreSpec.Product pr : m.spec().products().values()) if (pr.place().equals(place)) {
                things.add(pr.id());
                break;
            }
        }
        List<Shopper.Stop> round = Shopper.plan(l, things, id -> m.spec().products().get(id).place(), "door");
        Shopper s = new Shopper(type.id(), false, round, List.of(), 80, m.spec().spawn().speed() * 0.8, 1, 140, l.nodes().get("out"));
        Location at = sh.shop.at(s.x(), 0, s.z(), s.yaw());
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return false;
        Mannequin man = bus.spawnGuest(at, type, in.skin(), false, x -> {
            x.getPersistentDataContainer().set(StoreModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            x.setDescription(net.kyori.adventure.text.Component.empty());   // (not "NPC")
            x.customName(Lang.mm("store.inspector-name"));
            x.setCustomNameVisible(true);
        });
        StoreModule.Guest g = new StoreModule.Guest(s, type, man);
        g.inspector = true;
        g.scened = true;
        g.tallied = true;
        sh.guests.add(g);
        sh.inspector = g;
        sh.inspectUntil = System.currentTimeMillis() + in.seconds() * 1000L;
        s.begin(new Look(sh));
        World w = at.getWorld();
        Location door = sh.shop.at(StoreLayout.DOOR0 + 0.5, 2, StoreLayout.FRONT + 1.5, 0);
        w.playSound(door, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.7f);
        if (p != null) {
            p.sendMessage(Lang.msg("store.inspect", "s", in.seconds(), "pass", in.pass()));
        }
        return true;
    }

    /** One tick of the inspector: along the shelves, a word at each, out when done (or the time is up), then the verdict. */
    void stepInspector(StoreModule.Shift sh, StoreModule.Guest g) {
        Look env = new Look(sh);
        Shopper.Event e = g.s.tick(env);
        m.move(sh, g);
        if (e == Shopper.Event.BROWSING && g.s.browsing() != null) {
            boolean bad = "empty".equals(sh.shop.chores.state(m.spec(), g.s.browsing().place()))
                    || (g.s.browsing().place().equals(m.rules().waste().place()) && sh.shop.chores.expired(g.s.browsing().place()) > 0);
            m.sayLine(g, life().events().lines(bad ? "inspect-bad" : "inspect-good"));
        }
        Shopper.State st = g.s.state();
        if ((st == Shopper.State.QUEUE || System.currentTimeMillis() > sh.inspectUntil) && st != Shopper.State.LEAVE && st != Shopper.State.GONE) {
            g.s.leave(env, Shopper.Exit.PAID);
            judge(sh);
        }
        if (e == Shopper.Event.LEFT || !g.m.isValid()) {
            if (sh.inspector == g) judge(sh);
            m.drop(sh, g);
        }
    }

    /** The inspection's verdict (once): its score, a bonus or a fine. */
    private void judge(StoreModule.Shift sh) {
        if (sh.inspector == null) return;
        StoreModule.Guest g = sh.inspector;
        sh.inspector = null;
        g.inspector = true;   // (still walking out)
        Chores ch = sh.shop.chores;
        int empty = 0;
        for (String place : ch.empty()) if (sh.shop.layout().spots().containsKey(place)) empty++;
        boolean dry = sh.shop.has("kettle") && ch.water(m.rules().mess().water()) <= 0;
        int score = StoreLife.Inspect.score(empty, ch.expired(m.rules().waste().place()), ch.messes(), dry, ch.boxes().size());
        StoreLife.Inspect in = life().events().inspect();
        StoreLife.Inspect.Verdict v = in.verdict(score);
        Player p = m.worker(sh);
        switch (v) {
            case GOOD -> {
                sh.log.bonus(in.good());
                long paid = m.payNow(sh);
                if (p != null) {
                    p.sendMessage(Lang.msg("store.inspect-good", "score", score, "g", StoreModule.gold(paid)));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                }
            }
            case BAD -> {
                sh.log.fine(in.fine());
                m.payNow(sh);
                if (p != null) {
                    p.sendMessage(Lang.msg("store.inspect-bad", "score", score, "g", StoreModule.gold(in.fine())));
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 0.8f);
                }
            }
            default -> {
                if (p != null) p.sendMessage(Lang.msg("store.inspect-ok", "score", score));
            }
        }
    }

    // ------------------------------------------------------------------------------------------ 발주

    /** The order of a store: {target game day, group id, who}, or null. */
    String[] order(StoreModule.Shop shop) {
        String v = m.eco().kv().get("store.order." + shop.name);
        if (v == null) return null;
        String[] w = v.split("\\|");
        return w.length == 3 ? w : null;
    }

    /** The group ordered for this game day (null: none). */
    String ordered(StoreModule.Shop shop, long day) {
        String[] o = order(shop);
        return o != null && o[0].equals(Long.toString(day)) && life().order().groups().containsKey(o[1]) ? o[1] : null;
    }

    /** The box kind of an order group (its tablet page, the box the truck brings, §17), null: none. */
    String kind(StoreLife.Group g) {
        Map<String, String> boxOf = new java.util.HashMap<>();
        for (StoreSpec.Shelf s : m.spec().shelves().values()) boxOf.put(s.place(), s.box());
        return StoreMotion.kind(g, id -> {
            StoreSpec.Product p = m.spec().products().get(id);
            return p == null ? null : p.place();
        }, boxOf);
    }

    /** The box of today's 발주 (the truck brings it along), null: no order today. */
    String orderedBox(StoreModule.Shop shop) {
        String o = ordered(shop, gameDay(shop.world()));
        return o == null ? null : kind(life().order().groups().get(o));
    }

    /** The tablet shows a page a while (then its own screen again). */
    private void page(StoreModule.Shop shop, String model, int seconds) {
        m.show().furnish(shop, "order", model);
        shop.tabletUntil = System.currentTimeMillis() + seconds * 1000L;
        Bukkit.getScheduler().runTaskLater(m.eco(), () -> {
            if (shop.tabletUntil > 0 && System.currentTimeMillis() >= shop.tabletUntil) {
                shop.tabletUntil = 0;
                m.show().furnish(shop, "order", m.show().wanted(shop, "order"));
            }
        }, seconds * 20L + 2);
    }

    /** What sells best in this store today (fixed for the game day once asked). */
    String wanted(StoreModule.Shop shop, long day) {
        if (shop.demandDay == day && shop.demand != null) return shop.demand;
        World w = shop.world();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(StoreModule.KST);
        shop.demand = StoreLife.demand(w != null && w.hasStorm(), m.marketDay(), now.getMonthValue(), m.rules().demand().summerMonths(),
                now.getDayOfWeek().getValue() >= 6);
        shop.demandDay = day;
        return shop.demand;
    }

    /** The 발주 bonus for a customer's things (G, before the share); the first on a matched day says so. */
    long orderBonus(StoreModule.Shift sh, List<String> items) {
        StoreModule.Shop shop = sh.shop;
        long day = gameDay(shop.world());
        String o = ordered(shop, day);
        if (o == null) return 0;
        String want = wanted(shop, day);
        long b = life().orderBonus(o, want, items);
        if (o.equals(want) && shop.firstDay != day) {
            shop.firstDay = day;
            b += life().order().first();
            Player p = m.worker(sh);
            if (p != null) {
                p.sendMessage(Lang.msg("store.order-hit", "group", life().order().groups().get(o).name(), "g", StoreModule.gold(life().order().first())));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            }
        }
        return b;
    }

    /** The 발주 tablet: a click shows the next group, a sneaking click orders it for tomorrow (선임 or the owner). */
    void tablet(Player p, StoreModule.Shop shop, boolean sneaking) {
        UUID u = p.getUniqueId();
        boolean owner = u.equals(shop.owner);
        if (shop.owner != null && !owner && !m.book().hired(shop.name).contains(u)) {
            HudModule.flash(p, Lang.mm("store.order-not-yours"), 2500);
            return;
        }
        if (!owner && !life().reached(m.book().served(u), life().order().rank())) {
            StoreLife.Rank need = life().rankOf(life().order().rank());
            HudModule.flash(p, Lang.mm("store.order-rank", "rank", need == null ? "?" : need.name()), 2500);
            return;
        }
        long day = gameDay(shop.world());
        StoreModule.Paging shown = m.ordering.get(u);   // (a page left alone 30 s is forgotten: StoreModule.second)
        String cur = shown != null && shown.store().equals(shop.name) ? shown.group() : null;
        if (sneaking && cur != null) {
            StoreLife.Group g = life().order().groups().get(cur);
            m.eco().kv().set("store.order." + shop.name, (day + 1) + "|" + cur + "|" + u);
            m.ordering.remove(u);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
            boolean sent = m.has(StoreMotion.tabletModel(null, true));
            if (sent) {   // §17: the screen says 보냄, a beep
                shop.tabletUntil = 0;
                m.show().furnish(shop, "order", StoreMotion.tabletModel(null, true));
                Bukkit.getScheduler().runTaskLater(m.eco(), () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 2.0f), 3L);
                if (m.hands().holds(p, StoreMotion.PDA)) m.hands().touch(p, 1);
            }
            HudModule.flash(p, Lang.mm("store.order-done", "group", g.name()), 2500);
            return;
        }
        StoreLife.Group next = life().order().after(cur);
        if (next == null) return;
        m.ordering.put(u, new StoreModule.Paging(shop.name, next.id(), System.currentTimeMillis()));
        String model = StoreMotion.tabletModel(kind(next), false);
        if (m.has(model)) page(shop, model, 10);   // §17: the page turns on the tablet
        if (m.working(u) && m.has(StoreMotion.PDA)) {   // the PDA in hand while ordering
            if (m.hands().holds(p, StoreMotion.PDA)) m.hands().touch(p, 10);
            else if (m.hands().held(p) == null) m.hands().hold(p, StoreMotion.PDA, 10);
        }
        String today = ordered(shop, day), tomorrow = ordered(shop, day + 1);
        List<String> names = new ArrayList<>();
        for (String id : next.products()) if (m.spec().products().containsKey(id)) names.add(m.spec().products().get(id).name());
        if (names.size() > 4) names = names.subList(0, 4);
        HudModule.flash(p, Lang.mm(cur == null ? "store.order-page-first" : "store.order-page", "group", next.name(), "items", String.join(", ", names)), 4000);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        if (cur == null) {   // the first click: what stands now
            p.sendMessage(Lang.msg("store.order-state",
                    "today", today == null ? Lang.raw("store.order-none") : life().order().groups().get(today).name()
                            + (today.equals(wanted(shop, day)) ? Lang.raw("store.order-today-hit") : Lang.raw("store.order-today-miss")),
                    "tomorrow", tomorrow == null ? Lang.raw("store.order-none") : life().order().groups().get(tomorrow).name()));
        }
    }

    // ------------------------------------------------------------------------------------------ 이달의 점장

    /** The 이달의 점장 line's size (read from across the road). */
    static final float MONTH_LABEL = 0.9f;

    /** Every 30 seconds: under each loaded store's sign, this month's top worker. */
    void labels(java.util.Collection<StoreModule.Shop> shops) {
        for (StoreModule.Shop shop : shops) {
            if (!shop.loaded()) {
                shop.label = null;
                continue;
            }
            UUID top = m.book().top(shop.name);
            String name = top == null ? null : Bukkit.getOfflinePlayer(top).getName();
            if (name == null) {
                if (shop.label != null && shop.label.isValid()) shop.label.remove();
                shop.label = null;
                continue;
            }
            net.kyori.adventure.text.Component text = Lang.mm("store.month-label", "player", name);
            if (shop.label != null && shop.label.isValid()) {
                shop.label.text(text);
                continue;
            }
            double[] a = shop.site.at(0.5, 2.62, StoreLayout.FRONT - 0.08);   // under the sign, its middle
            Location at = new Location(shop.world(), a[0], a[1], a[2], shop.site.yaw(180), 0);
            shop.label = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.text(text);
                d.setBillboard(Display.Billboard.FIXED);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setBackgroundColor(Color.fromARGB(200, 31, 157, 85));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(MONTH_LABEL, MONTH_LABEL, MONTH_LABEL), new Quaternionf()));
                d.setViewRange(0.5f);
                d.getPersistentDataContainer().set(StoreModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            });
        }
    }

    // ------------------------------------------------------------------------------------------ visits (another module's customers)

    private void visit(StoreModule.Shop shop, StoreModule.Shift sh, Player p, int hour) {
        for (StoreModule.Guest g : sh.guests) if (g.visit != null) return;
        if (sh.guests.size() >= shop.maxInside()) return;
        for (Map.Entry<String, StoreVisit.Visitor> e : m.visitors.entrySet()) {
            StoreVisit.Visit v;
            try {
                v = e.getValue().due(p, shop.name, hour, m.book().night(p.getUniqueId()), sh.night);
            } catch (RuntimeException ex) {
                m.eco().getLogger().log(java.util.logging.Level.WARNING, "[편의점] 방문 오류 " + e.getKey(), ex);
                continue;
            }
            if (v == null) continue;
            StoreModule.Guest g = m.spawn(sh, null, hour, null, false, v);
            if (g != null) {
                g.visitor = e.getValue();
                return;
            }
        }
    }

    /** Something left on the parasol table outside for a minute (a visit's). */
    void parasol(StoreModule.Shop shop, String item) {
        if (item == null || shop.world() == null) return;
        var d = m.display(shop.at(StoreLayout.PARASOL_X, StoreLayout.PARASOL_Y, StoreLayout.PARASOL_Z, 0), item, 0.6f);
        Bukkit.getScheduler().runTaskLater(m.eco(), () -> {
            if (d.isValid()) d.remove();
        }, 1200L);
    }

    /** A figure seen a moment at the parasol table, looking in, then gone. */
    void figure(StoreModule.Shop shop, String kind, String skin) {
        BusModule bus = m.eco().bus();
        BusSpec.Type type = bus == null || bus.spec() == null ? null : bus.spec().types().get(kind);
        if (type == null || shop.world() == null) return;
        Location at = shop.at(StoreLayout.FIGURE_X, 0, StoreLayout.FIGURE_Z, 0);   // behind the left parasol table, looking in: seen from the till past the kettle
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        Mannequin f = bus.spawnGuest(at, type, skin, false, x -> {
            x.getPersistentDataContainer().set(StoreModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            x.setDescription(net.kyori.adventure.text.Component.empty());   // (not "NPC")
        });
        at.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 8, 0.3, 0.6, 0.3, 0.01);
        StoreModule.Shift sh = shop.shift;   // the worker's eyes go there once (it lasts five seconds)
        Player worker = sh == null ? null : m.worker(sh);
        if (worker != null && shop.site.inside(worker.getWorld().getName(), worker.getLocation().getX(), worker.getLocation().getY(), worker.getLocation().getZ())) {
            worker.lookAt(at.getX(), at.getY() + 1.5, at.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        }
        Bukkit.getScheduler().runTaskLater(m.eco(), () -> {
            if (f.isValid()) f.setRotation(f.getLocation().getYaw(), 25);   // a nod
        }, 40L);
        Bukkit.getScheduler().runTaskLater(m.eco(), () -> {
            if (!f.isValid()) return;
            f.getWorld().spawnParticle(Particle.WHITE_ASH, f.getLocation().add(0, 1, 0), 40, 0.3, 0.8, 0.3, 0.01);
            f.remove();
        }, 100L);
    }
}
