package dev.stsv.economy.season;

import dev.stsv.core.data.Db;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.DailyListener;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Days;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * The season clock: day and week, the 05:00 daily reset, the schedule of season.yml (events,
 * announcements, the finale), what is open on which day, running events (with boss bars), PvP (off
 * except inside a 무법 지대), locked regions and the nether gate.
 */
public final class SeasonModule extends EcoModule {

    public record Region(String name, String world, int x1, int z1, int x2, int z2, Integer maxY, int openDay) {
        public boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getBlockX() >= Math.min(x1, x2) && l.getBlockX() <= Math.max(x1, x2)
                    && l.getBlockZ() >= Math.min(z1, z2) && l.getBlockZ() <= Math.max(z1, z2)
                    && (maxY == null || l.getBlockY() <= maxY);
        }
    }

    private SeasonSpec spec;
    private EventStore events;
    private final List<DailyListener> daily = new ArrayList<>();
    private final Map<String, List<Consumer<ActiveEvent>>> onStart = new HashMap<>(), onEnd = new HashMap<>();
    private final Map<String, Consumer<SeasonSpec.Entry>> actions = new HashMap<>();
    private final Map<String, Runnable> clock = new LinkedHashMap<>();
    private final Map<String, LocalTime> clockTimes = new HashMap<>();
    private final Map<Long, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<UUID, Long> warned = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> inLawless = new ConcurrentHashMap<>();
    private Db db;

    public SeasonModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-season";
    }

    @Override
    protected void start() {
        db = core.db();
        events = new EventStore(db);
        events.init();
        db.migrate("eco_region", List.of(List.of(
                "CREATE TABLE IF NOT EXISTS eco_region (name VARCHAR(64) NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                        + "x1 INT NOT NULL, z1 INT NOT NULL, x2 INT NOT NULL, z2 INT NOT NULL, max_y INT, open_day INT NOT NULL)")));
        loadRegions();
        load();
        every(200L, this::tick);
        every(20L, this::bars);
    }

    @Override
    public void reload() {
        eco.reloadRules();
        load();
    }

    @Override
    protected void stop() {
        for (BossBar b : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
        bars.clear();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = eco.data("season.yml");
        spec = SeasonSpec.parse(y, problems);
        problems.forEach(p -> eco.getLogger().warning("[season.yml] " + p));
    }

    private void loadRegions() {
        regions.clear();
        for (Region r : db.sync(c -> Db.query(c, "SELECT name, world, x1, z1, x2, z2, max_y, open_day FROM eco_region",
                rs -> new Region(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getObject(7) == null ? null : rs.getInt(7), rs.getInt(8))))) {
            regions.put(r.name(), r);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Calendar
    // ------------------------------------------------------------------------------------------

    public SeasonSpec spec() {
        return spec;
    }

    public boolean running() {
        return core.profiles().store().current().isPresent();
    }

    public Days days() {
        long start = core.profiles().store().current().map(p -> p.started()).orElse(0L);
        return new Days(ZoneId.of(eco.getConfig().getString("time.zone")), eco.getConfig().getInt("time.reset-hour"),
                start, (int) eco.kv().getLong("season.offset", 0));
    }

    public int day() {
        return days().day(now());
    }

    public int week() {
        return days().week(now());
    }

    public int levelCap() {
        return spec.levelCap(day());
    }

    public int landPrice() {
        return spec.landPrice(day());
    }

    public int openDay(String feature) {
        return spec.openDay(feature, w -> eco.kv().has("season.early-week." + w));
    }

    public boolean isOpen(String feature) {
        return day() >= openDay(feature);
    }

    /** Sends "열리지 않음" and returns false when a feature is still closed (admins pass). */
    public boolean checkOpen(org.bukkit.command.CommandSender who, String feature) {
        if (isOpen(feature) || who.hasPermission("stsv.economy.admin")) return true;
        who.sendMessage(Lang.msg("season.closed", "feature", Lang.raw("season.feature." + feature), "day", openDay(feature)));
        return false;
    }

    /** A shared goal of week w was reached: what opens in week w+1 opens a day early. */
    public void markEarly(int week) {
        eco.kv().set("season.early-week." + week, "1");
    }

    // ------------------------------------------------------------------------------------------
    // Hooks for the other modules
    // ------------------------------------------------------------------------------------------

    public void onDaily(DailyListener l) {
        daily.add(l);
    }

    public void onEventStart(String type, Consumer<ActiveEvent> c) {
        onStart.computeIfAbsent(type, k -> new ArrayList<>()).add(c);
    }

    public void onEventEnd(String type, Consumer<ActiveEvent> c) {
        onEnd.computeIfAbsent(type, k -> new ArrayList<>()).add(c);
    }

    /** schedule entries with "action: name". */
    public void onAction(String name, Consumer<SeasonSpec.Entry> c) {
        actions.put(name, c);
    }

    /** Runs once a day at a clock time (e.g. the 21:00 ranking). */
    public void daily(String key, LocalTime time, Runnable r) {
        clock.put(key, r);
        clockTimes.put(key, time);
    }

    // ------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = now();
        Days d = days();
        // the daily reset: once per day, also on the first start of a fresh install
        long dayStart = d.dayStart(now);
        if (eco.kv().getLong("season.last-reset", 0) < dayStart) {
            eco.kv().set("season.last-reset", dayStart);
            int day = d.day(now);
            eco.getLogger().info("새 날: " + day + "일차");
            for (DailyListener l : daily) {
                try {
                    l.newDay(day, dayStart);
                } catch (RuntimeException e) {
                    eco.getLogger().log(java.util.logging.Level.SEVERE, "일일 초기화 오류 (" + l.getClass().getSimpleName() + ")", e);
                }
            }
        }
        // daily clock hooks
        int day = d.day(now);
        for (Map.Entry<String, Runnable> e : clock.entrySet()) {
            String key = "season.clock." + e.getKey() + "." + day;
            long at = d.at(day, clockTimes.get(e.getKey()));
            if (now >= at && now - at < 3_600_000L && !eco.kv().has(key)) {
                eco.kv().set(key, "1");
                e.getValue().run();
            }
        }
        // the schedule (only while a season runs)
        if (running()) {
            for (SeasonSpec.Entry entry : spec.schedule()) {
                long at = d.at(entry.day(), entry.time());
                String key = "season.done." + entry.key();
                if (now < at || eco.kv().has(key)) continue;
                eco.kv().set(key, "1");
                runEntry(entry, at, now);
            }
        }
        // events whose time is up
        for (ActiveEvent e : events.end(now, null)) ended(e);
    }

    private void runEntry(SeasonSpec.Entry entry, long at, long now) {
        if (entry.event() != null) {
            SeasonSpec.EventSpec es = spec.events().get(entry.event());
            if (es == null) return;
            long ends = at + (long) (es.hours() * 3_600_000L);
            if (now >= ends) return;   // missed entirely while the server was down
            Map<String, String> params = new LinkedHashMap<>(es.params());
            params.putAll(entry.params());
            startEvent(es.id(), at, ends, params);
        }
        if (entry.announce() != null && now - at < 7_200_000L) broadcast(Text.mm(Lang.prefix() + entry.announce()));
        if (entry.action() != null) {
            Consumer<SeasonSpec.Entry> a = actions.get(entry.action());
            if (a == null) eco.getLogger().warning("알 수 없는 일정 동작: " + entry.action());
            else a.accept(entry);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------

    public List<ActiveEvent> active() {
        return events.active();
    }

    public List<ActiveEvent> active(String type) {
        return events.active(type, now());
    }

    public boolean isActive(String type) {
        return !active(type).isEmpty();
    }

    public ActiveEvent startEvent(String type, long started, long ends, Map<String, String> params) {
        SeasonSpec.EventSpec es = spec.events().get(type);
        Map<String, String> p = new LinkedHashMap<>(es == null ? Map.of() : es.params());
        p.putAll(params);
        // the start hooks may add details (the chosen item, the zone centre) before it is stored
        ActiveEvent draft = new ActiveEvent(0, type, started, ends, p);
        for (Consumer<ActiveEvent> c : onStart.getOrDefault(type, List.of())) {
            try {
                c.accept(draft);
            } catch (RuntimeException ex) {
                eco.getLogger().log(java.util.logging.Level.SEVERE, "사건 시작 오류: " + type, ex);
            }
        }
        ActiveEvent e = events.start(type, started, ends, draft.params());
        String name = es == null ? type : es.name();
        String detail = p.getOrDefault("detail", "");
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.showTitle(Title.title(Lang.mm("season.event-title", "event", name), Text.mm(detail),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
            pl.playSound(pl.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1f);
        }
        broadcast(Lang.msg("season.event-start", "event", name, "detail", detail, "hours", hoursText(ends - started)));
        return e;
    }

    public int endEvent(String type) {
        List<ActiveEvent> ended = events.end(now(), type);
        ended.forEach(this::ended);
        return ended.size();
    }

    private void ended(ActiveEvent e) {
        BossBar bar = bars.remove(e.id());
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        for (Consumer<ActiveEvent> c : onEnd.getOrDefault(e.type(), List.of())) {
            try {
                c.accept(e);
            } catch (RuntimeException ex) {
                eco.getLogger().log(java.util.logging.Level.SEVERE, "사건 종료 오류: " + e.type(), ex);
            }
        }
        broadcast(Lang.msg("season.event-end", "event", eventName(e.type())));
    }

    public String eventName(String type) {
        SeasonSpec.EventSpec es = spec.events().get(type);
        return es == null ? type : es.name();
    }

    private void bars() {
        long now = now();
        for (ActiveEvent e : events.active()) {
            if (!e.runningAt(now)) continue;
            SeasonSpec.EventSpec es = spec.events().get(e.type());
            float progress = (float) (e.ends() - now) / Math.max(1, e.ends() - e.started());
            var title = Lang.mm("season.event-bar", "event", es == null ? e.type() : es.name(),
                    "detail", e.params().getOrDefault("detail", ""), "left", hoursText(e.ends() - now));
            BossBar.Color color = color(es == null ? "yellow" : es.color());
            BossBar bar = bars.get(e.id());
            if (bar == null) {
                bar = BossBar.bossBar(title, Math.clamp(progress, 0f, 1f), color, BossBar.Overlay.NOTCHED_10);
                bars.put(e.id(), bar);
            } else {
                bar.name(title).progress(Math.clamp(progress, 0f, 1f));
            }
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
        }
        lawlessBorders(now);
    }

    private static BossBar.Color color(String s) {
        try {
            return BossBar.Color.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.YELLOW;
        }
    }

    public static String hoursText(long millis) {
        long m = Math.max(0, millis / 60_000);
        if (m >= 60) return Lang.raw("season.hours", "h", m / 60, "m", m % 60);
        return Lang.raw("season.minutes", "m", m);
    }

    public void broadcast(net.kyori.adventure.text.Component c) {
        Bukkit.broadcast(c);
    }

    // ------------------------------------------------------------------------------------------
    // PvP and 무법 지대
    // ------------------------------------------------------------------------------------------

    /** Is this spot inside a running 무법 지대 (PvP allowed)? */
    public boolean lawless(Location l) {
        for (ActiveEvent e : active("lawless")) {
            String w = e.param("world");
            if (w == null || l.getWorld() == null || !l.getWorld().getName().equals(w)) continue;
            double dx = l.getX() - e.number("x", 0), dz = l.getZ() - e.number("z", 0), r = e.number("radius", 150);
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    private boolean pvpAllowed(Player attacker, Player victim) {
        if (eco.getConfig().getBoolean("pvp.always")) return true;
        return lawless(attacker.getLocation()) && lawless(victim.getLocation());
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player by = attacker(e.getDamager());
        if (by == null || by.equals(victim) || pvpAllowed(by, victim)) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Projectile pr) pr.remove();
        HudModule.flash(by, Lang.mm("season.no-pvp"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player by)) return;
        boolean harmful = e.getPotion().getEffects().stream().anyMatch(fx -> {
            var t = fx.getType();
            return t.equals(org.bukkit.potion.PotionEffectType.INSTANT_DAMAGE) || t.equals(org.bukkit.potion.PotionEffectType.POISON)
                    || t.equals(org.bukkit.potion.PotionEffectType.WEAKNESS) || t.equals(org.bukkit.potion.PotionEffectType.SLOWNESS)
                    || t.equals(org.bukkit.potion.PotionEffectType.WITHER) || t.equals(org.bukkit.potion.PotionEffectType.BLINDNESS);
        });
        if (!harmful) return;
        for (var hit : e.getAffectedEntities()) {
            if (hit instanceof Player victim && !victim.equals(by) && !pvpAllowed(by, victim)) e.setIntensity(victim, 0);
        }
    }

    /** Zone centre: the admin-set spot, else near the world spawn. Plants rare ore veins underground. */
    public void prepareLawless(ActiveEvent draft) {
        Location c = eco.location("lawless");
        if (c == null) c = Bukkit.getWorlds().getFirst().getSpawnLocation().add(500, 0, 0);
        draft.params().put("world", c.getWorld().getName());
        draft.params().put("x", String.valueOf(c.getBlockX()));
        draft.params().put("z", String.valueOf(c.getBlockZ()));
        draft.params().put("detail", Lang.raw("season.lawless-detail", "x", c.getBlockX(), "z", c.getBlockZ(), "radius", (int) draft.number("radius", 150)));
        int veins = (int) draft.number("ore-veins", 40);
        World w = c.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double radius = draft.number("radius", 150);
        Material[][] ores = {
                {Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE},
                {Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE},
                {Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE}};
        int placed = 0;
        for (int i = 0; i < veins; i++) {
            double a = r.nextDouble(Math.PI * 2), dist = Math.sqrt(r.nextDouble()) * radius;
            int x = c.getBlockX() + (int) (Math.cos(a) * dist), z = c.getBlockZ() + (int) (Math.sin(a) * dist);
            int y = r.nextInt(Math.max(w.getMinHeight() + 5, -50), 30);
            Material[] ore = ores[r.nextInt(ores.length)];
            for (int k = 0; k < 6; k++) {
                Block b = w.getBlockAt(x + r.nextInt(-1, 2), y + r.nextInt(-1, 2), z + r.nextInt(-1, 2));
                if (b.getType() == Material.STONE) { b.setType(ore[0], false); placed++; }
                else if (b.getType() == Material.DEEPSLATE) { b.setType(ore[1], false); placed++; }
            }
        }
        eco.getLogger().info("무법 지대 희귀 광맥: 광석 " + placed + "개");
    }

    private void lawlessBorders(long now) {
        List<ActiveEvent> zones = active("lawless");
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean in = !zones.isEmpty() && lawless(p.getLocation());
            Boolean was = inLawless.put(p.getUniqueId(), in);
            if (was != null && was != in) HudModule.flash(p, Lang.mm(in ? "season.lawless-enter" : "season.lawless-leave"));
            for (ActiveEvent e : zones) {
                if (!p.getWorld().getName().equals(e.param("world"))) continue;
                double cx = e.number("x", 0) + 0.5, cz = e.number("z", 0) + 0.5, r = e.number("radius", 150);
                double d = Math.hypot(p.getX() - cx, p.getZ() - cz);
                if (Math.abs(d - r) > 40) continue;
                // a ring of red dust near the player, where the border crosses their view
                double base = Math.atan2(p.getZ() - cz, p.getX() - cx);
                for (int k = -12; k <= 12; k++) {
                    double ang = base + k * (1.5 / r);
                    for (int h = -1; h <= 3; h++) {
                        p.spawnParticle(Particle.DUST, cx + Math.cos(ang) * r, p.getY() + h, cz + Math.sin(ang) * r, 1,
                                new Particle.DustOptions(org.bukkit.Color.fromRGB(220, 40, 40), 1.4f));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Locked regions and the nether
    // ------------------------------------------------------------------------------------------

    public Map<String, Region> regions() {
        return regions;
    }

    public void putRegion(Region r) {
        regions.put(r.name(), r);
        db.execute(db.upsert("eco_region", List.of("name"), List.of("world", "x1", "z1", "x2", "z2", "max_y", "open_day")),
                r.name(), r.world(), r.x1(), r.z1(), r.x2(), r.z2(), r.maxY(), r.openDay());
    }

    public boolean removeRegion(String name) {
        if (regions.remove(name) == null) return false;
        db.execute("DELETE FROM eco_region WHERE name = ?", name);
        return true;
    }

    private Region lockedAt(Location l) {
        int day = day();
        for (Region r : regions.values()) {
            int open = spec.weekOf(r.openDay()) > 1 && eco.kv().has("season.early-week." + (spec.weekOf(r.openDay()) - 1)) ? r.openDay() - 1 : r.openDay();
            if (day < open && r.contains(l)) return r;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (regions.isEmpty() || e.getTo().getBlockX() == e.getFrom().getBlockX() && e.getTo().getBlockZ() == e.getFrom().getBlockZ()
                && e.getTo().getBlockY() == e.getFrom().getBlockY()) return;
        if (e.getPlayer().hasPermission("stsv.economy.admin")) return;
        Region r = lockedAt(e.getTo());
        if (r == null || lockedAt(e.getFrom()) != null) return;
        e.setCancelled(true);
        warn(e.getPlayer(), r);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (regions.isEmpty() || e.getPlayer().hasPermission("stsv.economy.admin")) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && e.getCause() != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) return;
        Region r = lockedAt(e.getTo());
        if (r == null) return;
        e.setCancelled(true);
        warn(e.getPlayer(), r);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakLocked(BlockBreakEvent e) {
        if (regions.isEmpty() || e.getPlayer().hasPermission("stsv.economy.admin")) return;
        Region r = lockedAt(e.getBlock().getLocation());
        if (r != null) {
            e.setCancelled(true);
            warn(e.getPlayer(), r);
        }
    }

    private void warn(Player p, Region r) {
        long now = now();
        Long last = warned.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        warned.put(p.getUniqueId(), now);
        HudModule.flash(p, Lang.mm("season.region-locked", "region", r.name(), "day", r.openDay()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (e.getTo().getWorld() == null || e.getTo().getWorld().getEnvironment() != World.Environment.NETHER) return;
        if (isOpen("nether") || e.getPlayer().hasPermission("stsv.economy.admin")) return;
        e.setCancelled(true);
        HudModule.flash(e.getPlayer(), Lang.mm("season.closed-short", "feature", Lang.raw("season.feature.nether"), "day", openDay("nether")));
    }

    public void reset() {
        events.reset();
        for (BossBar b : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
        bars.clear();
        eco.kv().removePrefix("season.done.");
        eco.kv().removePrefix("season.clock.");
        eco.kv().removePrefix("season.early-week.");
        eco.kv().remove("season.offset");
    }

}
