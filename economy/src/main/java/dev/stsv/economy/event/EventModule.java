package dev.stsv.economy.event;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Server events (품귀, 시장 폭락, 풍년, 상인 행렬, 세무 조사, 광산 호황, 긴급 납품, 고가 매입상, 수수료 면제):
 * the kinds come from events.yml, a running one is kept in the database (a restart does not end it)
 * and shown to everyone as a boss bar. They follow each other by themselves ({@link EventChain}):
 * one at a time, the next one gapHours after the last one ended. Other modules hook a kind's start
 * (to pick the item, add details) and end.
 */
public final class EventModule extends EcoModule {

    private static final String NEXT = "event.next", LAST = "event.last";

    private Map<String, EventSpec> specs = Map.of();
    private EventChain chain = EventChain.parse(null);
    private EventStore store;
    private final Map<String, List<Consumer<ActiveEvent>>> onStart = new HashMap<>(), onEnd = new HashMap<>();
    private final Map<Long, BossBar> bars = new ConcurrentHashMap<>();

    public EventModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-events";
    }

    @Override
    protected void start() {
        store = new EventStore(core.db());
        store.init();
        load();
        every(20L, this::tick);
        eco.onReset(this::reset);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        hideBars();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        var y = eco.data("events.yml");
        specs = EventSpec.parse(y, problems);
        chain = EventChain.parse(y.getConfigurationSection("chain"));
        problems.forEach(p -> eco.getLogger().warning("[events.yml] " + p));
    }

    public Map<String, EventSpec> specs() {
        return specs;
    }

    public EventChain chain() {
        return chain;
    }

    /** When the next event of the chain starts (0 = waiting for the running one to end). */
    public long next() {
        return active().isEmpty() ? eco.kv().getLong(NEXT, 0) : 0;
    }

    /** Testing: the next event in this many minutes. */
    public void scheduleIn(double minutes) {
        eco.kv().set(NEXT, now() + (long) (minutes * 60_000));
    }

    // ------------------------------------------------------------------------------------------
    // Hooks for the other modules
    // ------------------------------------------------------------------------------------------

    public void onStart(String type, Consumer<ActiveEvent> c) {
        onStart.computeIfAbsent(type, k -> new ArrayList<>()).add(c);
    }

    public void onEnd(String type, Consumer<ActiveEvent> c) {
        onEnd.computeIfAbsent(type, k -> new ArrayList<>()).add(c);
    }

    // ------------------------------------------------------------------------------------------

    public List<ActiveEvent> active() {
        return store.active();
    }

    public List<ActiveEvent> active(String type) {
        return store.active(type, now());
    }

    public boolean isActive(String type) {
        return !active(type).isEmpty();
    }

    public String name(String type) {
        EventSpec es = specs.get(type);
        return es == null ? type : es.name();
    }

    /** Starts an event now for its configured length (params override events.yml). */
    public ActiveEvent start(String type, double hours, Map<String, String> params) {
        long now = now();
        return start(type, now, now + (long) (hours * 3_600_000L), params);
    }

    public ActiveEvent start(String type, long started, long ends, Map<String, String> params) {
        EventSpec es = specs.get(type);
        Map<String, String> p = new LinkedHashMap<>(es == null ? Map.of() : es.params());
        p.putAll(params);
        // the start hooks may add details (the chosen item) before it is stored
        ActiveEvent draft = new ActiveEvent(0, type, started, ends, p);
        for (Consumer<ActiveEvent> c : onStart.getOrDefault(type, List.of())) {
            try {
                c.accept(draft);
            } catch (RuntimeException ex) {
                eco.getLogger().log(java.util.logging.Level.SEVERE, "사건 시작 오류: " + type, ex);
            }
        }
        ActiveEvent e = store.start(type, started, ends, draft.params());
        eco.kv().set(LAST, type);
        String name = name(type);
        String detail = p.getOrDefault("detail", "");
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.showTitle(Title.title(Lang.mm("event.title", "event", name), Text.mm(detail),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
            pl.playSound(pl.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1f);
        }
        Bukkit.broadcast(Lang.msg("event.start", "event", name, "detail", detail, "hours", hoursText(ends - started)));
        return e;
    }

    /** Changes a running event's details (they are saved); returns the event as it is now. */
    public ActiveEvent update(ActiveEvent e, Map<String, String> params) {
        return store.update(e, params);
    }

    /** Ends this one event now (a delivery that reached its goal). */
    public void end(ActiveEvent e) {
        if (store.endOne(now(), e)) ended(e);
    }

    /** Ends every running event of a kind now; returns how many. */
    public int end(String type) {
        List<ActiveEvent> ended = store.end(now(), type);
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
        Bukkit.broadcast(Lang.msg("event.end", "event", name(e.type())));
        if (store.active().isEmpty()) eco.kv().set(NEXT, now() + chain.gapMillis());   // the next one, a gap after this one ended
    }

    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = now();
        for (ActiveEvent e : store.end(now, null)) ended(e);
        runChain(now);
        for (ActiveEvent e : store.active()) {
            if (!e.runningAt(now)) continue;
            EventSpec es = specs.get(e.type());
            float progress = Math.clamp((float) (e.ends() - now) / Math.max(1, e.ends() - e.started()), 0f, 1f);
            var title = Lang.mm("event.bar", "event", name(e.type()), "detail", e.params().getOrDefault("detail", ""), "left", hoursText(e.ends() - now));
            BossBar bar = bars.get(e.id());
            if (bar == null) {
                bar = BossBar.bossBar(title, progress, color(es == null ? "yellow" : es.color()), BossBar.Overlay.NOTCHED_10);
                bars.put(e.id(), bar);
            } else {
                bar.name(title).progress(progress);
            }
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
        }
    }

    private void runChain(long now) {
        if (!store.active().isEmpty()) return;
        long next = eco.kv().getLong(NEXT, 0);
        if (next <= 0) {
            eco.kv().set(NEXT, now + chain.gapMillis());   // a fresh server: the first event after one gap
            return;
        }
        if (now < next) return;
        java.time.ZoneId zone = java.time.ZoneId.of(eco.getConfig().getString("time.zone"));
        EventSpec kind = chain.pick(specs, eco.kv().get(LAST, ""), chain.night(now, zone), java.util.concurrent.ThreadLocalRandom.current());
        if (kind == null) {
            eco.kv().set(NEXT, now + chain.gapMillis());
            return;
        }
        start(kind.id(), kind.hours(), Map.of());
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
        if (m >= 60) return Lang.raw("time.hours", "h", m / 60, "m", m % 60);
        return Lang.raw("time.minutes", "m", m);
    }

    private void hideBars() {
        for (BossBar b : bars.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
        bars.clear();
    }

    private void reset() {
        store.reset();
        hideBars();
    }
}
