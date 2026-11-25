package dev.stsv.economy.event;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Server events (README "사건"), in two lines that may run side by side: 배경 사건 (품귀, 시장 폭락, 풍년, 광산 호황, 세무 조사,
 * 태풍: hours of changed prices or effects) and 행사 (상인 행렬, 긴급 납품, 고가 매입상: short ones in the evening). The kinds
 * come from events.yml; a running one is kept in the database (a restart does not end it) and shown in the sidebar. Each line
 * follows itself ({@link EventChain}, {@link EventChain.Gatherings}): one at a time, the next one a gap after the last ended.
 * A 덤 (수수료 면제) is never drawn alone but may come with a background event. Other modules hook a kind's start (to pick
 * the dock, the item, add details) and end. /소식 shows what runs, what comes and what happened.
 */
public final class EventModule extends EcoModule {

    private static final String NEXT = "event.next", LAST = "event.last";
    private static final String G_NEXT = "event.gather.next", G_LAST = "event.gather.last", LOG = "event.log";
    /** How many past events /소식 keeps. */
    private static final int LOG_SIZE = 10;

    private Map<String, EventSpec> specs = Map.of();
    private EventChain chain = EventChain.parse(null);
    private EventChain.Gatherings gatherings = EventChain.Gatherings.parse(null);
    private ConfigurationSection dock;
    private EventStore store;
    private final List<Consumer<ActiveEvent>> onPrepare = new ArrayList<>();
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
        hideBars();   // a server updated from a version with event boss bars
        core.hud().sidebar("eco-event-gap", 25, p -> eco.getConfig().getBoolean("hud.event") && (shown(EventSpec.BACKGROUND) != null
                || shown(EventSpec.GATHERING) != null) ? Component.empty() : null);
        core.hud().sidebar("eco-event", 30, p -> {
            ActiveEvent e = eco.getConfig().getBoolean("hud.event") ? shown(EventSpec.BACKGROUND) : null;
            return e == null ? null : Lang.mm("event.sidebar", "event", name(e.type()), "left", hoursText(e.ends() - now()));
        });
        core.hud().sidebar("eco-event-detail", 31, p -> {
            ActiveEvent e = eco.getConfig().getBoolean("hud.event") ? shown(EventSpec.BACKGROUND) : null;
            String d = e == null ? "" : e.params().getOrDefault("detail", "");
            return d.isEmpty() ? null : Lang.mm("event.sidebar-detail", "detail", d);
        });
        core.hud().sidebar("eco-event-gather", 32, p -> {
            ActiveEvent e = eco.getConfig().getBoolean("hud.event") ? shown(EventSpec.GATHERING) : null;
            return e == null ? null : Lang.mm("event.sidebar-gather", "event", name(e.type()), "left", hoursText(e.ends() - now()));
        });
        eco.onReset(this::reset);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    protected void stop() {
        hideBars();
        for (String id : List.of("eco-event-gap", "eco-event", "eco-event-detail", "eco-event-gather")) core.hud().removeSidebar(id);
        dev.stsv.economy.fishing.SeaEvents.clear();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        var y = eco.data("events.yml");
        specs = EventSpec.parse(y, problems);
        chain = EventChain.parse(y.getConfigurationSection("chain"));
        gatherings = EventChain.Gatherings.parse(y.getConfigurationSection("gatherings"));
        dock = y.getConfigurationSection("dock");
        problems.forEach(p -> eco.getLogger().warning("[events.yml] " + p));
    }

    public Map<String, EventSpec> specs() {
        return specs;
    }

    public EventChain chain() {
        return chain;
    }

    /** events.yml dock: the categories of goods the dock events pick from (machine id -> name), in order. */
    public Map<String, String> categories() {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection c = dock == null ? null : dock.getConfigurationSection("categories");
        if (c != null) for (String k : c.getKeys(false)) out.put(k, c.getString(k, k));
        return out;
    }

    /** A category's name for people ("요리"), or the id. */
    public String categoryName(String machine) {
        return machine == null || machine.equals("all") ? Lang.raw("event.all-goods") : categories().getOrDefault(machine, machine);
    }

    /** events.yml dock.recent-days: how far back "sold least / most lately" looks. */
    public double recentDays() {
        return dock == null ? 3 : dock.getDouble("recent-days", 3);
    }

    /** events.yml dock.pick-from: the dock event picks one of this many least (most) sold. */
    public int pickFrom() {
        return dock == null ? 3 : dock.getInt("pick-from", 3);
    }

    /** When the next background event of the chain starts (0 = waiting for the running one to end). */
    public long next() {
        return running(EventSpec.BACKGROUND).isEmpty() ? eco.kv().getLong(NEXT, 0) : 0;
    }

    /** When the next gathering starts at the earliest (0 = one is running). */
    public long nextGathering() {
        return running(EventSpec.GATHERING).isEmpty() ? eco.kv().getLong(G_NEXT, 0) : 0;
    }

    /** Testing: the next background event in this many minutes. */
    public void scheduleIn(double minutes) {
        eco.kv().set(NEXT, now() + (long) (minutes * 60_000));
    }

    // ------------------------------------------------------------------------------------------
    // Hooks for the other modules
    // ------------------------------------------------------------------------------------------

    /** Runs for every kind before its start hooks: picks what the start hooks then read (the 하역장 events' dock). */
    public void onPrepare(Consumer<ActiveEvent> c) {
        onPrepare.add(c);
    }

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

    /** The running events of a line. */
    public List<ActiveEvent> running(String line) {
        List<ActiveEvent> out = new ArrayList<>();
        for (ActiveEvent e : store.active()) if (line(e.type()).equals(line)) out.add(e);
        return out;
    }

    private String line(String type) {
        EventSpec s = specs.get(type);
        return s == null ? EventSpec.BACKGROUND : s.line();
    }

    public String name(String type) {
        EventSpec es = specs.get(type);
        return es == null ? type : es.name();
    }

    /** A job machine's speed factor now (풍년 · 광산 호황). */
    public double machineSpeed(String job) {
        return EventNews.machineSpeed(store.active(), now(), job);
    }

    /** The running events that speed up this job's machines. */
    public List<ActiveEvent> machineEvents(String job) {
        List<ActiveEvent> out = new ArrayList<>();
        for (ActiveEvent e : store.active()) {
            if (job != null && e.param("machine-speed") != null && EventSpec.list(e.param("jobs")).contains(job)) out.add(e);
        }
        return out;
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
        // the hooks may add details (the chosen dock, the item) before it is stored; prepare ones first (others read them)
        ActiveEvent draft = new ActiveEvent(0, type, started, ends, p);
        List<Consumer<ActiveEvent>> hooks = new ArrayList<>(onPrepare);
        hooks.addAll(onStart.getOrDefault(type, List.of()));
        for (Consumer<ActiveEvent> c : hooks) {
            try {
                c.accept(draft);
            } catch (RuntimeException ex) {
                eco.getLogger().log(java.util.logging.Level.SEVERE, "사건 시작 오류: " + type, ex);
            }
        }
        ActiveEvent e = store.start(type, started, ends, draft.params());
        if (es != null && es.line().equals(EventSpec.GATHERING)) eco.kv().set(G_LAST, type);
        else if (es == null || es.line().equals(EventSpec.BACKGROUND)) eco.kv().set(LAST, type);
        String name = name(type);
        String detail = p.getOrDefault("detail", "");
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.showTitle(Title.title(Lang.mm("event.title", "event", name), Text.mm(detail),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
            pl.playSound(pl.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1f);
        }
        String key = es != null && es.line().equals(EventSpec.GATHERING) ? "event.start-gathering"
                : es != null && es.line().equals(EventSpec.BONUS) ? "event.start-bonus" : "event.start";
        Bukkit.broadcast(ends <= started ? Lang.msg("event.start-instant", "event", name, "detail", detail)
                : Lang.msg(key, "event", name, "detail", detail, "hours", hoursText(ends - started)));
        String hint = p.getOrDefault("hint", "");
        if (!hint.isEmpty()) Bukkit.broadcast(Lang.mm("event.hint", "hint", hint));
        return e;
    }

    /** Changes a running event's details (they are saved); returns the event as it is now. */
    public ActiveEvent update(ActiveEvent e, Map<String, String> params) {
        return store.update(e, params);
    }

    /** Ends this one event now (a delivery that reached its goal). */
    public void end(ActiveEvent e) {
        if (store.endOne(now(), e)) ended(e, false);
    }

    /** Ends every running event of a kind now; returns how many. */
    public int end(String type) {
        List<ActiveEvent> ended = store.end(now(), type);
        ended.forEach(e -> ended(e, false));
        return ended.size();
    }

    private void ended(ActiveEvent e, boolean timeUp) {
        BossBar bar = bars.remove(e.id());
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        for (Consumer<ActiveEvent> c : onEnd.getOrDefault(e.type(), List.of())) {
            try {
                c.accept(e);
            } catch (RuntimeException ex) {
                eco.getLogger().log(java.util.logging.Level.SEVERE, "사건 종료 오류: " + e.type(), ex);
            }
        }
        log(e);
        if (e.ends() > e.started()) Bukkit.broadcast(Lang.msg("event.end", "event", name(e.type())));   // an instant one just happened
        // 태풍 -> 만선: an event that ran its time may bring the next one at once (events.yml then)
        String then = e.param("then");
        if (timeUp && then != null && specs.containsKey(then)) start(then, specs.get(then).hours(), Map.of());
        String line = line(e.type());
        if (line.equals(EventSpec.BACKGROUND) && running(EventSpec.BACKGROUND).isEmpty()) eco.kv().set(NEXT, now() + chain.gapMillis());
        if (line.equals(EventSpec.GATHERING) && running(EventSpec.GATHERING).isEmpty()) eco.kv().set(G_NEXT, now() + gatherings.gapMillis());
    }

    /** A line in /소식's 지난 결과: when, what, and what came of it (who delivered most, what it was about). */
    private void log(ActiveEvent e) {
        String detail = e.params().getOrDefault("detail", "");
        List<String> top = e.params().entrySet().stream().filter(x -> x.getKey().startsWith("by."))
                .sorted(Map.Entry.<String, String>comparingByValue(java.util.Comparator.comparingDouble(Double::parseDouble)).reversed())
                .limit(3).map(x -> dev.stsv.economy.util.Players.name(java.util.UUID.fromString(x.getKey().substring(3))) + " " + x.getValue()).toList();
        String when = DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(e.started()).atZone(zone()));
        String entry = Lang.raw(top.isEmpty() ? "event.log-line" : "event.log-line-top", "when", when, "event", name(e.type()),
                "detail", Text.plain(detail), "top", String.join(", ", top));
        eco.kv().set(LOG, EventNews.appendLog(eco.kv().get(LOG, ""), entry, LOG_SIZE));
    }

    /** /소식 지난 결과, newest first. */
    public List<String> log() {
        return EventNews.lines(eco.kv().get(LOG, ""));
    }

    // ------------------------------------------------------------------------------------------

    private ZoneId zone() {
        return ZoneId.of(eco.getConfig().getString("time.zone"));
    }

    private void tick() {
        long now = now();
        for (ActiveEvent e : store.end(now, null)) ended(e, true);
        runChain(now);
        runGatherings(now);
        // 태풍 · 만선: the nets at sea and the extra schools
        dev.stsv.economy.fishing.SeaEvents.tick(eco, EventNews.schoolFactor(store.active(), now), now);
        // running events are shown in the sidebar (see start), not as boss bars any more
    }

    /** The event of a line shown in the sidebar: the one that ends first. */
    private ActiveEvent shown(String line) {
        long now = now();
        ActiveEvent best = null;
        for (ActiveEvent e : running(line)) if (e.runningAt(now) && (best == null || e.ends() < best.ends())) best = e;
        return best;
    }

    private void runChain(long now) {
        if (!running(EventSpec.BACKGROUND).isEmpty()) return;
        long next = eco.kv().getLong(NEXT, 0);
        if (next <= 0) {
            eco.kv().set(NEXT, now + chain.gapMillis());   // a fresh server: the first event after one gap
            return;
        }
        if (now < next) return;
        EventSpec kind = chain.pick(specs, eco.kv().get(LAST, ""), chain.night(now, zone()), ThreadLocalRandom.current());
        if (kind == null) {
            eco.kv().set(NEXT, now + chain.gapMillis());
            return;
        }
        start(kind.id(), kind.hours(), Map.of());
        // a 덤 (수수료 면제) may come with it: never drawn alone
        for (EventSpec b : specs.values()) {
            if (!b.line().equals(EventSpec.BONUS) || isActive(b.id())) continue;
            if (ThreadLocalRandom.current().nextDouble() < b.number("bonus-chance", 0)) start(b.id(), b.hours(), Map.of());
        }
    }

    private void runGatherings(long now) {
        if (!running(EventSpec.GATHERING).isEmpty()) return;
        long next = eco.kv().getLong(G_NEXT, 0);
        if (next <= 0) {
            eco.kv().set(G_NEXT, now + gatherings.gapMillis());
            return;
        }
        if (now < next || !gatherings.open(now, zone())) return;
        EventSpec kind = chain.pick(specs, eco.kv().get(G_LAST, ""), false, EventSpec.GATHERING, ThreadLocalRandom.current());
        if (kind == null) {
            eco.kv().set(G_NEXT, now + gatherings.gapMillis());
            return;
        }
        start(kind.id(), kind.hours(), Map.of());
    }

    // ------------------------------------------------------------------------------------------
    // /소식
    // ------------------------------------------------------------------------------------------

    /** /소식: what runs now (both lines), what comes (the chain, the fishing tournament, the bus's 대축제 and 장날, 실적 발표), what happened. */
    public void news(CommandSender s) {
        long now = now();
        ZoneId zone = zone();
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("M/d(E) HH:mm", java.util.Locale.KOREAN);
        s.sendMessage(Lang.mm("event.news-header"));
        List<ActiveEvent> now1 = new ArrayList<>(running(EventSpec.BACKGROUND));
        now1.addAll(running(EventSpec.GATHERING));
        now1.addAll(running(EventSpec.BONUS));
        if (now1.isEmpty()) s.sendMessage(Lang.mm("event.news-none"));
        for (ActiveEvent e : now1) {
            String key = line(e.type()).equals(EventSpec.GATHERING) ? "event.news-now-gathering" : "event.news-now";
            s.sendMessage(Lang.mm(key, "event", name(e.type()), "detail", e.params().getOrDefault("detail", ""), "left", hoursText(e.ends() - now)));
        }
        // the market day (bus 5일장) is a gathering of its own, told by the bus too
        var bus = eco.bus();
        int market = bus == null || bus.spec() == null ? -1 : EventNews.daysToMarket(d -> bus.spec().marketDay(d), eco.calendar().day(), 30);
        if (market == 0) s.sendMessage(Lang.mm("event.news-market-today"));
        s.sendMessage(Lang.mm("event.news-next-header"));
        long next = next();
        if (next > 0) s.sendMessage(Lang.mm("event.news-next", "left", hoursText(next - now)));
        long g = nextGathering();
        if (g > 0) s.sendMessage(Lang.mm("event.news-next-gathering", "left", hoursText(Math.max(0, g - now)), "from", gatherings.from(),
                "to", gatherings.to()));
        if (eco.getConfig().getBoolean("fishing-tournament.enabled")) {
            boolean on = eco.tournament() != null && eco.tournament().running();
            s.sendMessage(Lang.mm(on ? "event.news-tournament-on" : "event.news-tournament", "at", clock.format(Instant.ofEpochMilli(
                    EventNews.nextHour(now, zone)).atZone(zone)), "minutes", eco.getConfig().getInt("fishing-tournament.minutes")));
        }
        if (bus != null && bus.spec() != null && bus.spec().events().festival().enabled()) {
            var f = bus.spec().events().festival();
            long at = EventNews.nextWeekly(now, java.time.ZoneId.of("Asia/Seoul"), f.day(), f.from(), f.to());
            s.sendMessage(Lang.mm(at <= now ? "event.news-festival-on" : "event.news-festival", "at",
                    clock.format(Instant.ofEpochMilli(at).atZone(zone)), "to", f.to()));
        }
        if (market > 0) s.sendMessage(Lang.mm("event.news-market", "days", market));
        if (eco.stocks() != null && eco.stocks().nextEarnings() > now) {
            s.sendMessage(Lang.mm("event.news-earnings", "left", hoursText(eco.stocks().nextEarnings() - now)));
        }
        List<String> log = log();
        if (!log.isEmpty()) {
            s.sendMessage(Lang.mm("event.news-log-header"));
            for (String l : log) s.sendMessage(Lang.mm("event.news-log", "line", l));
        }
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
        eco.kv().remove(LOG);
    }
}
