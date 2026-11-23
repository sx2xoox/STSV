package dev.stsv.economy.bus;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.road.RoadNetwork;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 마을버스 (README "마을버스", docs/plans/마을버스_승객운송_기획안.md): 정류장 on the 국도 (paved bays with a shelter and a sign,
 * placed by an admin or along the roads by themselves), passengers that wait there (a number that fills with the hour; shown
 * as mannequins only while a bus is near), board a bus stopped in the bay and ride to another stop of the same road network,
 * where they pay the driver: a fare by the road distance, times their kind and their mood (jolts, bumps, sharp turns), a tip
 * on time, less once the driver earned a lot that day.
 */
public final class BusModule extends EcoModule {

    static final NamespacedKey GUEST = new NamespacedKey("economy", "bus_guest");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private BusSpec spec;
    /** 수상한 선글라스 남자 (SecretAgent: docs/admin/버스_비밀_의뢰.md) */
    private SecretAgent secret;

    /** The 선글라스 남자 (for /관리자 비밀 목록 · 켜기 · 끄기). */
    public SecretAgent secretAgent() {
        return secret;
    }
    private PassengerPreview preview;
    /** 버스 내비게이션 (/버스 내비) */
    private BusNavigator navigator;
    /** 분실물 (LostItems: their items, owners waiting at the stops, the phone ringing) */
    private LostItems lostItems;
    /** 바둑아 어디 가 (LostDog: the 강아지 할아버지's dog ran off at a stop) */
    private LostDog lostDog;
    private final Map<Integer, BusStop> stops = new LinkedHashMap<>();
    private int nextId = 1;
    private StopBuilder builder;
    /** world -> the roads as a graph and every stop's road distance to every other */
    private final Map<String, Net> nets = new HashMap<>();
    /** stop id -> passengers waiting (a number; whole ones are shown near a bus) */
    private final Map<Integer, Double> waiting = new HashMap<>();
    private final Map<Integer, Integer> signShown = new HashMap<>();
    private final Map<Integer, Shown> shown = new HashMap<>();
    /** vehicle id -> a bus being driven */
    private final Map<Long, Run> runs = new HashMap<>();
    /** driver -> the stop pairs they carried passengers between lately (from, to, when) */
    private final Map<UUID, Deque<long[]>> pairs = new HashMap<>();
    private long ticks, lastRefill = System.currentTimeMillis();

    record Net(BusRoutes routes, Map<Integer, Integer> index, double[][] dist, List<BusLine> lines) {
        /** The lines a stop is on. */
        List<BusLine> linesOf(int stop) {
            List<BusLine> out = new ArrayList<>();
            for (BusLine l : lines) if (l.has(stop)) out.add(l);
            return out;
        }

        BusLine line(int number) {
            for (BusLine l : lines) if (l.number == number) return l;
            return null;
        }

        double road(int from, int to) {
            Integer a = index.get(from), b = index.get(to);
            return a == null || b == null ? Double.POSITIVE_INFINITY : dist[a][b];
        }

        boolean connected(int stop) {
            Integer a = index.get(stop);
            if (a == null) return false;
            for (int j = 0; j < dist.length; j++) if (j != a && Double.isFinite(dist[a][j])) return true;
            return false;
        }
    }

    /** A passenger waiting in a shelter. */
    static final class Waiter {
        final Mannequin m;
        final BusSpec.Type type;
        /** the skin's file name (its face in the HUD's faces sheet), or null */
        final String face;
        /** a transfer passenger: the line they wait for and where they go (else null) */
        Integer forcedLine, forcedDest;
        boolean transfer;
        /** a couple's other half (boards and gets off with this one) */
        Waiter partner;
        /** when they came to the stop (millis: the longer, the crosser when they board) */
        long since = System.currentTimeMillis();
        /** where they stand in the line (a hop comes back here), and the bench seat they sit on (or null) */
        Location home;
        Entity seat;
        int seatAt = -1;
        /** an event team's number (0: none) and a 특급 손님's kind (or null) */
        int team;
        String special;

        Waiter(Mannequin m, BusSpec.Type type, String face) {
            this.m = m;
            this.type = type;
            this.face = face;
        }
    }

    /**
     * Someone queued to wait at a stop: a transfer passenger (their line and stop), one of an event team (출근 대란, 야구 끝,
     * 소풍날: its number) or a 특급 손님 (its kind).
     */
    record Forced(String type, Integer line, Integer dest, boolean transfer, int team, String special) {
        Forced(String type, Integer line, Integer dest, boolean transfer) {
            this(type, line, dest, transfer, 0, null);
        }
    }

    static final class Shown {
        final List<Waiter> waiters = new ArrayList<>();
        long lastNear;
        /** the driver near (they look at the bus once it is within queue.notice), the line to be placed again, in the rain */
        UUID driver;
        boolean dirty = true, rain;
    }

    /** stop id -> when each of its waiting passengers came (in memory, like the shown ones) */
    private final Map<Integer, StopQueue> queues = new HashMap<>();
    /** stop id -> the arrival board's rows on the sign (refreshed every board.refresh seconds) and what its 노선도 shows */
    private final Map<Integer, String> bisRows = new HashMap<>();
    private final Map<Integer, Integer> boardShown = new HashMap<>();
    /** stop id -> whether its ceiling lamp was last lit (set again only when day and night turn) */
    private final Map<Integer, Boolean> lampShown = new HashMap<>();

    /** A passenger on a bus. */
    static final class Rider {
        final Mannequin m;
        final BusSpec.Type type;
        final int from;
        /** where they get off; -1: at the next stop the bus stops at (they missed theirs at the end of the line) */
        int dest;
        final long boarded = System.currentTimeMillis();
        final double road;
        double mood;
        String face;
        boolean transfer, dozing, dozeChecked, sleepThrough, lateSaid;
        /** what they said last and until when it shows on the driver's overlay (millis) */
        String said;
        long saidUntil;
        /** a stop a wandering passenger gets off at instead (or -1) */
        int wanderAt = -1;
        /** an event team's number (0: none); a 특급 손님's kind (or null) and the jolts and bumps it felt; mood losses × */
        int team;
        BusEvents.Kind special;
        int hits;
        double moodX = 1;
        /** 3차 kinds: the dog in the seat beside (강아지 할아버지), when they last chattered, whether they felt sick (멀미) */
        Entity dog;
        long chatAt = System.currentTimeMillis();
        boolean sick;
        /** the 선글라스 남자 (SecretAgent: he gets off by his own rules, pays nothing as a fare) */
        boolean secret;
        /** 공정 운행: what the bus had driven when they got on (Run.meter); their stop was changed on the way (passed, turned round) */
        double odoAt;
        boolean rerouted;

        Rider(Mannequin m, BusSpec.Type type, int from, int dest, double road, double mood) {
            this.m = m;
            this.type = type;
            this.from = from;
            this.dest = dest;
            this.road = road;
            this.mood = mood;
        }
    }

    static final class Run {
        long id;
        UUID driver;
        long seen;
        final List<Rider> riders = new ArrayList<>();
        final double[] speeds = new double[64];
        float lastYaw = Float.NaN;
        long joltAt = -1000, bumpAt = -1000, turnAt = -1000, pullInAt = -1000;
        Integer atStop;
        int busy, seats;
        /** how long (ticks) it has stood still (a stop's safety net: STILL_TICKS) */
        int stillTicks;
        /** the line it runs (its number, 0 = none yet) and which way (+1 towards the dock, -1 towards the terminal) */
        int line, dir = 1;
        long noLineAt = -1000, talkAt = -1000;
        String itemKey = "";
        /** a bus rented from the server (/버스 렌트): the driver keeps only the rental's share of each fare */
        boolean rented;
        Integer leftStop;
        final java.util.Set<Integer> rang = new java.util.HashSet<>();
        /** the event team it carries (0: none; one at a time) and whether a 특급 손님 rides (one at a time) */
        int team;
        boolean special;
        /** at the stop it stands at: someone got on or off, something was left behind; the first passenger was greeted */
        boolean served, lostHere, greeted;
        /** 공정 운행: how far it was really driven (fares: a ride it did not drive pays nothing) */
        final ServiceGate.Odometer meter = new ServiceGate.Odometer();
        /** the last stop it stopped at (a smooth stop cheers the riders once: not pulling out of a bay and in again) */
        int smoothStop = -1;
        /** 노선 완주: the stops of its line it stopped at in order this run */
        final BusEvents.CourseRun course = new BusEvents.CourseRun();
    }

    /** 버스 렌트: renter -> their rented bus (a temporary vehicle of the core) and since when they are out of it (0: in it) */
    private final Map<UUID, long[]> rentals = new HashMap<>();

    /** stop id -> its traits (mountain, sea ...: worked out from around it, saved with the stops) */
    private final Map<Integer, java.util.Set<String>> stopTags = new HashMap<>();
    /** stop id -> who is queued to wait there next (transfers, a picnic) */
    private final Map<Integer, Deque<Forced>> forced = new HashMap<>();
    private int marketSaidDay = -1;

    /** An event team (단체 손님): up to events.team.size passengers of one crowd, going to one stop together. */
    static final class Team {
        final int id, no, size, stop;
        final String event;
        final BusEvents.Crowd crowd;
        final String color;
        /** the line it must ride (출근 대란: each team its own stop ahead), where it goes (null: chosen as its first boards) */
        Integer line, dest;
        /** when it must be there (millis, 0: no deadline); a won or lost ball game */
        final long deadline, made = System.currentTimeMillis();
        final boolean won, lost;
        int boarded, off;
        boolean broken, lateHit, allOnTime = true;

        Team(int id, int no, int size, int stop, String event, BusEvents.Crowd crowd, String color, long deadline, boolean won, boolean lost) {
            this.id = id;
            this.no = no;
            this.size = size;
            this.stop = stop;
            this.event = event;
            this.crowd = crowd;
            this.color = color;
            this.deadline = deadline;
            this.won = won;
            this.lost = lost;
        }
    }

    private final Map<Integer, Team> teams = new HashMap<>();
    /** stop id -> when a 특급 손님 came to wait there */
    private final Map<Integer, Long> specialSince = new HashMap<>();
    private int nextTeam = 1;
    /** 안전 운행 연속: driver -> {stops served in a row} (in memory) */
    private final Map<UUID, int[]> streaks = new HashMap<>();
    /** 공정 운행: driver -> the last stop that counted for them and how far they drove a bus since (in memory; ServiceGate) */
    private final Map<UUID, ServiceGate> gates = new HashMap<>();
    /** 외딴 정류장 구조: stop id -> when a bus last took someone there (in memory: from the server's start); the stop marked */
    private final Map<Integer, Long> lastServed = new HashMap<>();
    private Integer rescueStop;
    /** 버스 대축제: on, warned it ends soon; driver -> {points}, when they last scored (in memory) */
    private boolean festOn, festWarned;
    private final Map<UUID, int[]> festPoints = new HashMap<>();
    private final Map<UUID, Long> festAt = new HashMap<>();
    /** 분실물: what a lost item carries (see BusEvents.LostTag) */
    static final NamespacedKey LOST = new NamespacedKey("economy", "bus_lost");

    public BusModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "economy-bus";
    }

    @Override
    protected void start() {
        builder = new StopBuilder(folder().resolve("undo"));
        load();
        readStops();
        secret = new SecretAgent(this, eco, core);
        preview = new PassengerPreview(this, eco);
        navigator = new BusNavigator(this, eco, core);
        lostItems = new LostItems(this, eco, core);
        lostDog = new LostDog(this, eco, core);
        every(10L, navigator::tick);
        every(2L, lostDog::tick);
        Bukkit.getPluginManager().registerEvents(secret, eco);
        Bukkit.getPluginManager().registerEvents(lostDog, eco);
        every(1L, this::tick);
        every(20L, this::second);
        every(spec.queue().idleEvery(), this::idle);
        core.hud().overlay("eco-bus", this::overlayRows);
        Bukkit.getScheduler().runTaskLater(eco, this::rebuildNets, 40L);   // after the roads have loaded
    }

    @Override
    public void reload() {
        load();
        if (secret != null) secret.load();
        if (lostDog != null) lostDog.load();
        rebuildNets();
    }

    @Override
    protected void stop() {
        if (core != null && core.hud() != null) core.hud().removeOverlay("eco-bus");
        if (preview != null) preview.stop();
        if (navigator != null) navigator.stop();
        if (lostItems != null) lostItems.stop();
        if (lostDog != null) {
            lostDog.stop();
            org.bukkit.event.HandlerList.unregisterAll(lostDog);
        }
        if (secret != null) {
            secret.stop();
            org.bukkit.event.HandlerList.unregisterAll(secret);
        }
        for (Run r : runs.values()) for (Rider x : r.riders) {
            x.m.remove();
            if (x.dog != null) x.dog.remove();
        }
        runs.clear();
        VehicleModule vm = core == null ? null : core.vehicles();
        if (vm != null) for (long[] r : rentals.values()) vm.removeTemporary(r[0]);
        rentals.clear();
        for (Shown s : shown.values()) for (Waiter w : s.waiters) gone(w);
        shown.clear();
    }

    private void load() {
        List<String> problems = new ArrayList<>();
        spec = BusSpec.parse(eco.data("bus.yml"), problems);
        problems.forEach(p -> eco.getLogger().warning("[bus.yml] " + p));
    }

    public BusSpec spec() {
        return spec;
    }

    private Path folder() {
        return eco.getDataFolder().toPath().resolve("bus");
    }

    // ------------------------------------------------------------------------------------------
    // Stops: saved in bus/stops.yml
    // ------------------------------------------------------------------------------------------

    private void readStops() {
        stops.clear();
        File f = folder().resolve("stops.yml").toFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        nextId = Math.max(1, y.getInt("next-id", 1));
        var list = y.getConfigurationSection("stops");
        if (list == null) return;
        for (String k : list.getKeys(false)) {
            var c = list.getConfigurationSection(k);
            if (c == null) continue;
            int id = Integer.parseInt(k);
            stops.put(id, new BusStop(id, c.getString("name", "정류장 " + id), c.getString("world", "world"), c.getDouble("x"), c.getDouble("z"),
                    c.getInt("half"), c.getDouble("hx"), c.getDouble("hz", 1), c.getInt("side", 1), Math.max(c.getInt("size", spec.stops().waitingMax()), spec.stops().waitingMax()),
                    c.getBoolean("auto"), c.getBoolean("bay", true), c.getString("dock", "")));
            waiting.put(id, c.getDouble("waiting", 0));
            if (c.isList("tags")) stopTags.put(id, new java.util.TreeSet<>(c.getStringList("tags")));
            nextId = Math.max(nextId, id + 1);
        }
    }

    private void saveStops() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (BusStop s : stops.values()) {
            String k = "stops." + s.id() + ".";
            y.set(k + "name", s.name());
            y.set(k + "world", s.world());
            y.set(k + "x", s.x());
            y.set(k + "z", s.z());
            y.set(k + "half", s.half());
            y.set(k + "hx", s.hx());
            y.set(k + "hz", s.hz());
            y.set(k + "side", s.side());
            y.set(k + "size", s.size());
            y.set(k + "auto", s.auto());
            y.set(k + "bay", s.bay());
            y.set(k + "dock", s.dock());
            y.set(k + "waiting", Math.round(waiting.getOrDefault(s.id(), 0.0) * 100) / 100.0);
            if (stopTags.containsKey(s.id())) y.set(k + "tags", new ArrayList<>(stopTags.get(s.id())));
        }
        try {
            folder().toFile().mkdirs();
            y.save(folder().resolve("stops.yml").toFile());
        } catch (IOException e) {
            eco.getLogger().warning("[버스] stops.yml 저장 실패: " + e);
        }
    }

    public List<BusStop> stops() {
        return List.copyOf(stops.values());
    }

    /** The stop typed: its name (words joined, or in quotes) or its number (#12, 12); exactly, then ignoring case and spaces (StopNames). */
    public BusStop stop(String name) {
        return StopNames.find(stops.values(), name);
    }

    /** A new stop name's problem, or null: empty, read as a number (#12), or another stop's (ignoring case and spaces). */
    private String badName(String name, Integer except) {
        if (name.isBlank()) return "<red>이름을 적어 주세요";
        if (StopNames.numberLike(name)) return "<red>숫자만으로 된 이름은 정류장 번호(#12)와 헷갈려서 쓸 수 없어요: " + name;
        if (StopNames.taken(stops.values(), name, except)) return "<red>같은 이름의 정류장이 있어요: " + name;
        return null;
    }

    /** The roads as a graph again (the roads or the stops changed): the roads read here, the ways worked out off the main thread. */
    public void rebuildNets() {
        if (eco.roads() == null) return;
        Map<String, List<RoadNetwork.Road>> roads = new HashMap<>();
        Map<String, List<BusStop>> byWorld = new HashMap<>();
        for (BusStop s : stops.values()) byWorld.computeIfAbsent(s.world(), w -> new ArrayList<>()).add(s);
        for (String w : byWorld.keySet()) roads.put(w, eco.roads().builtRoads(w));
        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            Map<String, Net> out = new HashMap<>();
            roads.forEach((w, rs) -> {
                BusRoutes routes = new BusRoutes(rs);
                List<BusStop> here = byWorld.get(w);
                int[] nodes = new int[here.size()];
                Map<Integer, Integer> index = new HashMap<>();
                for (int i = 0; i < here.size(); i++) {
                    nodes[i] = routes.attach(here.get(i).x(), here.get(i).z());
                    index.put(here.get(i).id(), i);
                }
                // the lines: every road's way from the terminal to its dock, with the stops on it
                List<BusLine> lines = new ArrayList<>();
                int number = 1;
                for (BusRoutes.Line l : routes.lines()) {
                    double[] lx = new double[l.path().length], lz = new double[l.path().length];
                    boolean[] jn = new boolean[l.path().length];
                    Map<Integer, Integer> at = new HashMap<>();
                    for (int k = 0; k < l.path().length; k++) {
                        double[] xz = routes.xz(l.path()[k]);
                        lx[k] = xz[0];
                        lz[k] = xz[1];
                        jn[k] = routes.junction(l.path()[k]);
                        at.put(l.path()[k], k);
                    }
                    BusLine bl = new BusLine(number, l.dock(), lx, lz, jn);
                    for (int i = 0; i < here.size(); i++) {
                        Integer k = onLine(at, nodes[i], bl, here.get(i));
                        if (k != null) bl.addStop(here.get(i).id(), k);
                    }
                    if (bl.stops().size() >= 2) {
                        lines.add(bl);
                        number++;
                    }
                }
                out.put(w, new Net(routes, index, routes.matrix(nodes), lines));
            });
            Bukkit.getScheduler().runTask(eco, () -> {
                Map<String, Net> old = new HashMap<>(nets);
                nets.clear();
                nets.putAll(out);
                // the lines are numbered again: a bus keeps running the same line (by its dock and stops), not the number
                for (Run r : runs.values()) {
                    Player p = Bukkit.getPlayer(r.driver);
                    if (p != null) r.line = renumber(old.get(p.getWorld().getName()), out.get(p.getWorld().getName()), r.line);
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Integer c = chosenLine.get(p.getUniqueId());
                    if (c != null) chosenLine.put(p.getUniqueId(), renumber(old.get(p.getWorld().getName()), out.get(p.getWorld().getName()), c));
                }
            });
        });
    }

    /** How far (blocks) a stop's road point may be from a line's way and still be on the line. */
    static final double ON_LINE = 3;

    /**
     * Where a stop is on a line (its way index), or null: its road point's node on the line's way, else the way's point
     * within {@link #ON_LINE} of the stop (a stop by where a branch leaves hangs on the branch's first point or the old
     * road's: the same place, two nodes; it is on the lines of both).
     */
    static Integer onLine(Map<Integer, Integer> at, int node, BusLine line, BusStop s) {
        Integer k = node < 0 ? null : at.get(node);
        if (k != null) return k;
        double[] off = new double[1];
        int i = line.nearest(s.x(), s.z(), off);
        return off[0] <= ON_LINE ? i : null;
    }

    /** The number in `now` of line `number` in `before` (the same dock, most stops in common), or 0. */
    static int renumber(Net before, Net now, int number) {
        if (number == 0 || now == null) return 0;
        BusLine was = before == null ? null : before.line(number);
        if (was == null) return now.line(number) != null ? number : 0;
        BusLine best = null;
        int common = -1;
        for (BusLine l : now.lines()) {
            if (!l.dock.equals(was.dock)) continue;
            int c = 0;
            for (int id : was.stops()) if (l.has(id)) c++;
            if (c > common) {
                common = c;
                best = l;
            }
        }
        return best == null ? 0 : best.number;
    }

    /** The routes of a world for placing stops (worked out now, on this thread). */
    private BusRoutes routesNow(String world) {
        return new BusRoutes(eco.roads().builtRoads(world));
    }

    // ------------------------------------------------------------------------------------------
    // Placing and removing stops (/관리자 정류장)
    // ------------------------------------------------------------------------------------------

    /** Places a stop on the road nearest this spot (on the side the spot is), and builds it. Returns a message. */
    public String install(Location at, String name, int size) {
        name = StopNames.unquote(name);
        String bad = badName(name, null);
        if (bad != null) return bad;
        BusRoutes routes = routesNow(at.getWorld().getName());
        int node = routes.attach(at.getX(), at.getZ());
        if (node < 0) return "<red>국도에서 " + (int) BusRoutes.ATTACH + "칸 안에 서서 쓰세요 (지은 국도의 길 점에만 정류장이 붙어요)";
        double[] p = routes.place(node);
        double nx = -p[4], nz = p[3];   // the right of the way
        int side = (at.getX() - p[0]) * nx + (at.getZ() - p[1]) * nz >= 0 ? 1 : -1;
        BusStop s = new BusStop(nextId, name, at.getWorld().getName(), p[0], p[1], (int) p[2], p[3], p[4], side,
                Math.max(1, size), false, true, routes.dock(node));
        return place(at.getWorld(), s);
    }

    /** Builds a stop: its bay on its side, else on the other, else a simple stop (shelter only, or nothing where even that is protected). */
    private String place(World w, BusStop s) {
        BusSpec.Stops cfg = spec.stops();
        // the side with less hill to dig first (a stop is not buried in a mountainside); a simple stop after
        BusStop a = s, b = flip(s);
        BusStop.Levels lv = levels(w, s);
        Map<Block, BlockData> pa = StopBuilder.plan(w, a, cfg, eco.roads().surfaceBlock(), lv), pb = StopBuilder.plan(w, b, cfg, eco.roads().surfaceBlock(), lv);
        if (StopBuilder.digCost(pb) + 40 < StopBuilder.digCost(pa)) {
            BusStop t = a;
            a = b;
            b = t;
        }
        // never on a bridge, over water or a drop, nor in a tunnel (bridges carry railings, a shelter would hang over the water)
        String whyA = StopBuilder.siteProblem(w, a, cfg), whyB = StopBuilder.siteProblem(w, b, cfg);
        if (whyA != null && whyB != null) return "<red>정류장 <white>" + s.name() + "</white>: 여기는 " + whyA + "라서 지을 수 없어요";
        List<BusStop> tries = new ArrayList<>();
        if (whyA == null) tries.add(a);
        if (whyB == null) tries.add(b);
        if (whyA == null) tries.add(simple(a));
        if (whyB == null) tries.add(simple(b));
        for (BusStop t : tries) {
            Map<Block, BlockData> plan = StopBuilder.plan(w, t, cfg, eco.roads().surfaceBlock(), StopBuilder.theme(detectTags(w, t)), lv);
            int[] bad = StopBuilder.blocked(plan, (x, z) -> eco.roads().guardAt(w.getName(), x, z) != null);
            if (bad != null || (t.bay() && StopBuilder.overVoid(w, t, cfg))) continue;
            try {
                builder.build(w, t, plan, cfg);
            } catch (IOException e) {
                return "<red>되돌리기 기록을 쓰지 못해 짓지 않았어요: " + e.getMessage();
            }
            stopTags.put(t.id(), detectTags(w, t));
            add(t);
            String words = tagWords(t);
            return "<green>정류장 <white>" + t.name() + "</white> 설치 (" + (t.bay() ? "버스 베이" : "간이 정류장") + ", "
                    + (t.side() > 0 ? "오른쪽" : "왼쪽") + ", 손님 최대 " + t.size() + "명" + (words.isEmpty() ? "" : ", " + words) + ")";
        }
        add(simple(s));
        return "<yellow>정류장 <white>" + s.name() + "</white>: 주변이 보호 구역이라 아무것도 짓지 않고 표지판 없이 정류장만 만들었어요";
    }

    /** The road's surface by each column of a stop's bay and shoulder (the lane beside it: BusRoutes.levels). */
    private BusStop.Levels levels(World w, BusStop s) {
        BusRoutes routes = routesNow(w.getName());
        return routes.levels(routes.attach(s.x(), s.z()), spec.stops().bayLength() / 2 + 24, s.half());
    }

    private static BusStop flip(BusStop s) {
        return new BusStop(s.id(), s.name(), s.world(), s.x(), s.z(), s.half(), s.hx(), s.hz(), -s.side(), s.size(), s.auto(), s.bay(), s.dock());
    }

    private static BusStop simple(BusStop s) {
        return new BusStop(s.id(), s.name(), s.world(), s.x(), s.z(), s.half(), s.hx(), s.hz(), s.side(), s.size(), s.auto(), false, s.dock());
    }

    private void add(BusStop s) {
        stops.put(s.id(), s);
        nextId = Math.max(nextId, s.id() + 1);
        waiting.putIfAbsent(s.id(), 1.0);
        saveStops();
        rebuildNets();
    }

    /** Takes a stop away, its blocks back as they were. */
    public String remove(String typed) {
        BusStop s = stop(typed);
        if (s == null) return "<red>없는 정류장: " + typed;
        String name = s.name();
        World w = Bukkit.getWorld(s.world());
        hide(s.id());
        if (w != null) unboard(w, s);
        boolean undone = false;
        if (w != null) {
            try {
                undone = builder.undo(w, s);
            } catch (IOException e) {
                return "<red>되돌리기 기록을 읽지 못했어요: " + e.getMessage();
            }
        }
        stops.remove(s.id());
        waiting.remove(s.id());
        saveStops();
        rebuildNets();
        return "<green>정류장 <white>" + name + "</white> 삭제" + (undone ? " (블록 되돌림)" : "");
    }

    /** Takes a stop's blocks back and builds it again as it is built now (same name and number). */
    public String rebuild(String typed) {
        BusStop s = stop(typed);
        if (s == null) return "<red>없는 정류장: " + typed;
        World w = Bukkit.getWorld(s.world());
        if (w == null) return "<red>월드가 없어요: " + s.world();
        hide(s.id());
        unboard(w, s);
        try {
            builder.undo(w, s);
        } catch (IOException e) {
            return "<red>되돌리기 기록을 읽지 못했어요: " + e.getMessage();
        }
        stops.remove(s.id());
        // its place and way worked out again from the road (stops from before took the way over 2 points: a pixel's step)
        BusRoutes routes = routesNow(s.world());
        int node = routes.attach(s.x(), s.z());
        double[] p = node < 0 ? new double[]{s.x(), s.z(), s.half(), s.hx(), s.hz()} : routes.place(node);
        int side = p[3] * s.hx() + p[4] * s.hz() >= 0 ? s.side() : -s.side();   // the same side of the road as before
        BusStop again = new BusStop(s.id(), s.name(), s.world(), p[0], p[1], (int) p[2], p[3], p[4], side, s.size(), s.auto(), true, s.dock());
        String r = place(w, again).replace("설치", "다시 지음");
        if (r.startsWith("<red>")) {
            waiting.remove(s.id());
            saveStops();
            rebuildNets();
            r += " (블록을 되돌리고 정류장을 지웠어요)";
        }
        return r;
    }

    public String rename(String typed, String to) {
        BusStop s = stop(typed);
        if (s == null) return "<red>없는 정류장: " + typed;
        return rename(s, to);
    }

    /** Renames a stop (its sign too). */
    public String rename(BusStop s, String to) {
        to = StopNames.unquote(to);
        String bad = badName(to, s.id());
        if (bad != null) return bad;
        String name = s.name();
        BusStop n = s.named(to);
        stops.put(s.id(), n);
        saveStops();
        World w = Bukkit.getWorld(s.world());
        if (w != null) builder.sign(w, n, (int) Math.floor(waiting.getOrDefault(s.id(), 0.0)), lineNumbers(n), spec.stops());
        return "<green>이름 바꿈: " + name + " → " + to;
    }

    /** Places stops along every road of this world by themselves (README "마을버스", 자동 정류장): one at a time. */
    public String autoPlace(World w) {
        BusRoutes routes = routesNow(w.getName());
        if (routes.nodes() == 0) return "<red>이 월드에 지은 국도가 없어요";
        List<double[]> taken = new ArrayList<>();
        for (BusStop s : stops.values()) if (s.world().equals(w.getName())) taken.add(new double[]{s.x(), s.z()});
        List<BusRoutes.Spot> spots = routes.autoSpots(spec.stops().auto(), spec.stops().bayLength(), taken);
        if (spots.isEmpty()) return "<yellow>새로 놓을 자리가 없어요 (이미 정류장이 있거나 길이 짧음)";
        Map<String, Integer> counts = new HashMap<>();
        long delay = 1;
        for (BusRoutes.Spot sp : spots) {
            String name = switch (sp.kind()) {
                case START -> sp.dock() + "선 출발";
                case END -> sp.dock() + " 앞";
                case MIDDLE -> sp.dock() + "선 " + counts.merge(sp.dock(), 1, Integer::sum);
            };
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                String n = name;
                for (int i = 2; stop(n) != null; i++) n = name + " " + i;
                autoOne(w, routes, sp, n, 0);
            }, delay);
            delay += 10;
        }
        return "<green>정류장 " + spots.size() + "곳을 차례로 지어요 (콘솔에 하나씩 기록)";
    }

    /** Steps along the road from an automatic spot (0, +20, -20, +40 ... ±200 points) until a place a stop may stand. */
    private static final int[] SHIFTS = {0, 20, -20, 40, -40, 60, -60, 80, -80, 100, -100, 130, -130, 160, -160, 200, -200};

    private void autoOne(World w, BusRoutes routes, BusRoutes.Spot sp, String name, int i) {
        String log = "[버스] 자동 정류장: ";
        if (i >= SHIFTS.length) {
            eco.getLogger().info(log + name + ": 근처 200칸이 다리 · 물가 · 굴이라 건너뜀");
            return;
        }
        int node = routes.shift(sp.node(), SHIFTS[i]);
        if (node < 0) {
            autoOne(w, routes, sp, name, i + 1);
            return;
        }
        double[] p = routes.place(node);
        w.getChunkAtAsync((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4).thenRun(() -> {
            BusStop s = new BusStop(nextId, name, w.getName(), p[0], p[1], (int) p[2], p[3], p[4], 1, spec.stops().waitingMax(), true, true, sp.dock());
            if (!goodSite(w, s)) {
                autoOne(w, routes, sp, name, i + 1);
                return;
            }
            // a cute name from what is around it (the stop before a dock's gate keeps "<dock> 앞")
            if (sp.kind() != BusRoutes.Kind.END) s = s.named(cuteName(detectTags(w, s)));
            String r = place(w, s);
            eco.getLogger().info(log + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(dev.stsv.core.util.Text.mm(r)));
        });
    }

    /** A cute name for a stop of these traits, not yet used by another stop. */
    String cuteName(java.util.Set<String> tags) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (BusStop s : stops.values()) used.add(s.name());
        return spec.stopName(tags, used, ThreadLocalRandom.current().nextDouble());
    }

    /**
     * Gives the automatic stops cute names (/관리자 정류장 이름짓기): every auto stop but the ones before a dock's gate
     * ("<dock> 앞"), by its traits. Returns how many were renamed.
     */
    public int cuteNames() {
        int n = 0;
        for (BusStop s : new ArrayList<>(stops.values())) {
            if (!s.auto() || s.name().equals(s.dock() + " 앞")) continue;
            BusStop named = s.named(cuteName(tagsOf(s)));
            stops.put(s.id(), named);
            World w = Bukkit.getWorld(s.world());
            if (w != null) builder.sign(w, named, (int) Math.floor(waiting.getOrDefault(s.id(), 0.0)), lineNumbers(named), spec.stops());
            signShown.remove(s.id());
            n++;
        }
        saveStops();
        return n;
    }

    /** Most ground a stop may dig away (a deep cut into a hillside is moved along the road instead). */
    static final int MAX_DIG = 260;

    /**
     * Whether a stop may go here by itself: on one side at least, not on a bridge, by water or a drop, nor in a tunnel,
     * and without a deep cut into a hill.
     */
    private boolean goodSite(World w, BusStop s) {
        BusStop.Levels lv = levels(w, s);
        for (BusStop t : new BusStop[]{s, flip(s)}) {
            if (StopBuilder.siteProblem(w, t, spec.stops()) != null) continue;
            if (StopBuilder.digCost(StopBuilder.plan(w, t, spec.stops(), eco.roads().surfaceBlock(), lv)) <= MAX_DIG) return true;
        }
        return false;
    }

    /** Moves a stop to the road point nearest this spot (on the side the spot is): its blocks back, then built there. */
    public String move(String typed, Location at) {
        BusStop s = stop(typed);
        if (s == null) return "<red>없는 정류장: " + typed;
        if (!s.world().equals(at.getWorld().getName())) return "<red>정류장과 같은 월드에서 쓰세요";
        BusRoutes routes = routesNow(at.getWorld().getName());
        int node = routes.attach(at.getX(), at.getZ());
        if (node < 0) return "<red>국도에서 " + (int) BusRoutes.ATTACH + "칸 안에 서서 쓰세요";
        hide(s.id());
        unboard(at.getWorld(), s);
        try {
            builder.undo(at.getWorld(), s);
        } catch (IOException e) {
            return "<red>되돌리기 기록을 읽지 못했어요: " + e.getMessage();
        }
        stops.remove(s.id());
        stopTags.remove(s.id());
        double[] p = routes.place(node);
        double nx = -p[4], nz = p[3];
        int side = (at.getX() - p[0]) * nx + (at.getZ() - p[1]) * nz >= 0 ? 1 : -1;
        BusStop moved = new BusStop(s.id(), s.name(), s.world(), p[0], p[1], (int) p[2], p[3], p[4], side, s.size(), s.auto(), true, routes.dock(node));
        String r = place(at.getWorld(), moved);
        if (r.startsWith("<red>")) {
            waiting.remove(s.id());
            saveStops();
            rebuildNets();
            return r + " (원래 자리도 되돌렸어요: 다른 곳에서 다시 옮기세요)";
        }
        return r.replace("설치", "옮김");
    }

    /** Puts passengers at a stop (an admin's test). */
    public String addWaiting(String typed, int n) {
        BusStop s = stop(typed);
        if (s == null) return "<red>없는 정류장: " + typed;
        String name = s.name();
        waiting.put(s.id(), (double) Math.max(0, Math.min(s.size(), n)));
        return "<green>" + name + ": 기다리는 손님 " + Math.min(s.size(), n) + "명";
    }

    public List<String> listLines(Location me) {
        return listLines(me, false);
    }

    /** The stops nearest first; with their numbers (#12: what an admin command takes as well as the name). */
    public List<String> listLines(Location me, boolean ids) {
        List<String> out = new ArrayList<>();
        List<BusStop> all = new ArrayList<>(stops.values());
        if (me != null) all.sort(Comparator.comparingDouble(s -> s.world().equals(me.getWorld().getName()) ? Math.hypot(s.x() - me.getX(), s.z() - me.getZ()) : 1e12));
        for (BusStop s : all) {
            Net n = nets.get(s.world());
            String nos = lineNumbers(s);
            boolean cut = nos.isEmpty();
            String km = me != null && s.world().equals(me.getWorld().getName()) ? km(Math.hypot(s.x() - me.getX(), s.z() - me.getZ())) : s.world();
            out.add(Lang.raw("bus.stops-row", "name", ids ? s.name() + " <dark_gray>#" + s.id() + "</dark_gray>" : s.name(), "km", km, "waiting", (int) Math.floor(waiting.getOrDefault(s.id(), 0.0)),
                    "cut", cut ? Lang.raw("bus.stops-cut") : Lang.raw("bus.stops-lines", "lines", nos)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Every tick: the buses being driven
    // ------------------------------------------------------------------------------------------

    private void tick() {
        ticks++;
        VehicleModule vm = core.vehicles();
        if (vm == null || spec.vehicles().isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            VehicleModule.Driven d = vm.drivenBy(p);
            if (d == null || !spec.vehicles().contains(d.itemKey())) continue;
            if (eco.guide() != null && eco.guide().active(p)) continue;   // 길라잡이: the tutorial's own bus, no real stops
            Run run = runs.computeIfAbsent(d.id(), k -> new Run());
            run.id = d.id();
            run.driver = p.getUniqueId();
            run.seen = ticks;
            run.seats = d.seats() - 1;
            run.itemKey = d.itemKey();
            run.rented = rentedId(d.id());
            // 공정 운행: what the bus really drove (its riders' fares) and this driver with it (their counted stops)
            double drove = run.meter.move(d.world().getName(), d.x(), d.z(), ticks, spec.fair().maxSpeed());
            gates.computeIfAbsent(p.getUniqueId(), k -> new ServiceGate()).drove(drove);
            lostDog.drove(p.getUniqueId(), drove);
            feel(p, run, d);
            if (ticks % 5 == 0) atStops(p, run, d, vm);
            secret.drive(p, run, d);
        }
        // a bus whose driver got out (or left): its passengers go off to find another
        for (Iterator<Map.Entry<Long, Run>> it = runs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Run> e = it.next();
            Run r = e.getValue();
            if (ticks - r.seen < 60) continue;
            if (lostDog.holds(r, ticks - r.seen)) continue;   // the driver is out fetching 바둑이: everyone waits a while
            int n = 0;
            for (Rider x : r.riders) {
                vm.unseatGuest(e.getKey(), x.m);
                x.m.remove();
                dropDog(e.getKey(), x, false);
                if (x.team != 0) teams.remove(x.team);   // the team went off to find another way
                n++;
            }
            Player p = Bukkit.getPlayer(r.driver);
            if (n > 0 && p != null) p.sendMessage(Lang.msg("bus.lost", "n", n));
            it.remove();
        }
    }

    /**
     * A bump the riders feel (부딪힘): the bus hit something going at least this fast (blocks/s, the tick before). The core
     * calls a move that lost half its way at over 2 b/s a bump; a nudge at a crawl (a long bus easing round a post) is
     * none to them.
     */
    public static final double BUMP_FROM = 4;

    /** Jolts, bumps and sharp turns: every rider's mood. */
    private void feel(Player p, Run run, VehicleModule.Driven d) {
        BusSpec.Mood m = spec.mood();
        double speed = Math.abs(d.speed());
        double before = run.speeds[(int) Math.floorMod(ticks - 1, (long) run.speeds.length)];
        run.speeds[(int) (ticks % run.speeds.length)] = speed;
        if (run.riders.isEmpty()) {
            run.lastYaw = d.yaw();
            return;
        }
        if (d.bumped() && before >= BUMP_FROM && ticks - run.bumpAt > m.cooldown()) {
            run.bumpAt = ticks;
            hurt(p, run, m.bump(), "bus.mood-bump", false);
        }
        if (speed < 1 && ticks - run.joltAt > m.cooldown()) {
            double fast = 0;
            for (int i = 1; i <= Math.min(m.joltWindow(), run.speeds.length - 1); i++) fast = Math.max(fast, run.speeds[(int) ((ticks - i) % run.speeds.length)]);
            if (fast >= m.joltFrom()) {
                run.joltAt = ticks;
                hurt(p, run, m.jolt(), "bus.mood-jolt", true);
                java.util.Arrays.fill(run.speeds, 0);
            }
        }
        if (!Float.isNaN(run.lastYaw) && speed > m.turnSpeed() && ticks - run.turnAt > m.cooldown()) {
            double turn = Math.abs(((d.yaw() - run.lastYaw) % 360 + 540) % 360 - 180);
            if (turn > m.turnDegrees()) {
                run.turnAt = ticks;
                hurt(p, run, m.turn(), "bus.mood-turn", false);
            }
        }
        run.lastYaw = d.yaw();
    }

    private void hurt(Player p, Run run, double by, String key, boolean jolt) {
        // snow on the road: a sudden stop is half as bad (everyone knows it slides)
        if (jolt && p.getWorld().hasStorm() && p.getLocation().getBlock().getTemperature() < 0.15) by *= spec.extra().snowJolt();
        boolean turn = key.endsWith("turn");
        if (!turn) broke(p, run);   // 안전 운행 연속: a jolt or a bump ends it; a 특급 손님 counts it
        if (!turn) secret.hurt(p, run);
        // 애 업은 엄마: the baby wakes and cries, everyone aboard feels it
        Rider baby = null;
        for (Rider r : run.riders) if (!turn && r.type.x().babyCry() != 0 && baby == null) baby = r;
        for (Rider r : run.riders) {
            double d = by * r.type.mood() * (jolt ? r.type.jolt() : 1) * (turn ? r.type.turnFactor() : 1) * r.moodX;
            if (baby != null) d += baby.type.x().babyCry() * r.type.mood();
            r.mood = r.type.floor(Math.clamp(r.mood + d, 0, 100));
            r.m.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, r.m.getLocation().add(0, 2.1, 0), 1);
            if (r.dozing && !turn) {   // a jolt wakes a sleeper
                r.dozing = false;
                r.sleepThrough = false;
            }
        }
        HudModule.flash(p, Lang.mm(key, "d", (int) Math.round(by)), 1500);
        if (baby != null) {
            sayLine(run, baby, spec.lines(baby.type, "cry"));
            if (baby.m.isValid()) baby.m.getWorld().playSound(baby.m.getLocation(), Sound.ENTITY_VILLAGER_HURT, 0.6f, 1.9f);
            return;
        }
        Rider r = someone(run);
        if (r != null) talk(run, r.m, r.type, jolt ? "jolt" : turn ? "turn" : "bump", true);
    }

    /** At a stop (stopped in its bay): passengers get off, then on, one at a time. */
    private void atStops(Player p, Run run, VehicleModule.Driven d, VehicleModule vm) {
        BusSpec.Stops cfg = spec.stops();
        boolean still = Math.abs(d.speed()) < 0.5;
        run.stillTicks = still ? run.stillTicks + 5 : 0;
        BusStop here = still ? stopAt(run, d.world().getName(), d.x(), d.y(), d.z(), cfg) : null;
        if (here == null) {
            if (run.atStop != null) {
                departed(p, run, run.atStop);   // pulling out: the next stop is announced
                run.atStop = null;
            }
            if (still && ticks - run.pullInAt > 200) {
                for (BusStop s : stops.values()) {
                    if (s.world().equals(d.world().getName()) && s.bay() && s.byTheStop(d.x(), d.z(), cfg.stopReach())) {
                        run.pullInAt = ticks;
                        HudModule.flash(p, Lang.mm("bus.pull-in"), 2500);
                        break;
                    }
                }
            }
            return;
        }
        if (run.atStop == null || run.atStop != here.id()) {
            run.atStop = here.id();
            run.busy = 10;
            run.rang.remove(here.id());
            d.world().playSound(d.location(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1.6f);   // 삑 (교통카드)
            // a smooth stop cheers the riders once a stop (not out of the bay and in again, over and over)
            boolean smooth = ticks - run.joltAt > 60 && run.smoothStop != here.id();
            run.smoothStop = here.id();
            if (smooth && !run.riders.isEmpty()) {
                Rider baby = null;
                for (Rider r : run.riders) if (r.type.x().babySmile() != 0 && baby == null) baby = r;
                for (Rider r : run.riders) r.mood = Math.min(100, r.mood + spec.mood().smoothStop() + (baby == null ? 0 : baby.type.x().babySmile()));
                Rider r = baby != null ? baby : someone(run);
                if (baby != null) sayLine(run, baby, spec.lines(baby.type, "smile"));   // 아기가 웃어요
                else if (r != null && ThreadLocalRandom.current().nextDouble() < 0.4) talk(run, r.m, r.type, "smooth", false);
            }
            pickLine(p, run, d, here);
            HudModule.flash(p, Lang.mm("bus.arrive", "stop", here.name()), 2000);
            arrived(p, run, here);
            secret.arrived(p, run, d, here);
            lostDog.arrived(p, run, here);
            forWatchers(x -> x.stopped(p, here));
            // a wandering passenger (술 취한 아저씨) may get off here instead
            for (Rider r : run.riders) {
                if (r.dest != here.id() && r.type.wander() > 0 && ThreadLocalRandom.current().nextDouble() < r.type.wander()) {
                    r.wanderAt = here.id();
                    talk(run, r.m, r.type, "missed", true);
                }
            }
            return;
        }
        if (run.busy > 0) {
            run.busy -= 5;
            return;
        }
        Net n = nets.get(here.world());
        BusLine line = n == null ? null : n.line(run.line);
        // off first (a sleeper who did not wake stays on; a couple gets off together, one after the other)
        for (Iterator<Rider> it = run.riders.iterator(); it.hasNext(); ) {
            Rider r = it.next();
            if (r.secret) continue;   // the 선글라스 남자 gets off by his own rules (SecretAgent)
            boolean off = (r.dest == here.id() && !r.sleepThrough) || r.dest == -1 || r.wanderAt == here.id();
            if (!off) continue;
            it.remove();
            vm.unseatGuest(d.id(), r.m);
            dropDog(d.id(), r, true);
            // 공정 운행: paid for what the bus really drove them; `atDest`: where they asked to go (or as far), not a stop
            // they were moved to by a bus that turned round
            double road = paidRoad(run, r, n, line, here);
            boolean rode = road > 0, atDest = r.dest == here.id() && rode && (!r.rerouted || road + 1e-6 >= r.road);
            // a 특급 손님: its bonus, or (failed) its fare × fail-fare
            long special = 0;
            String why = null;
            if (r.special != null) {
                double secs = (System.currentTimeMillis() - r.boarded) / 1000.0, ot = spec.onTime(r.road);
                special = atDest ? r.special.pay(secs, ot, r.mood, r.hits) : 0;
                if (special <= 0) why = !atDest ? Lang.raw("bus.special-why-place") : r.mood < r.special.minMood()
                        ? Lang.raw("bus.special-why-mood", "mood", (int) Math.round(r.mood)) : r.special.limit(ot) > 0 && secs > r.special.limit(ot)
                        ? Lang.raw("bus.special-why-late") : Lang.raw("bus.special-why-hits", "n", r.hits);
            }
            boolean onTime = rode && pay(p, run, r, here, road, r.special != null && special <= 0 ? r.special.failFare() : 1);
            if (rode) served(p, run, here, false);
            else noRide(p, r, here);
            if (r.special != null) specialOff(p, run, r, here, special, why);
            if (r.team != 0) teamOff(p, run, r, here, atDest, onTime);
            if (rode) maybeLose(p, run, r, here);
            if (atDest) alightTraits(p, run, r);
            walkOff(r.m, here, d.world().hasStorm());
            if (rode) maybeTransfer(n, line, here, r);
            run.busy = (int) (cfg.boardTicks() * r.type.slow());
            return;
        }
        // then on: the first waiting passenger this bus takes, going somewhere ahead on its line
        Shown sh = shown.get(here.id());
        if (sh == null || sh.waiters.isEmpty()) return;
        List<Integer> free = vm.freeSeats(d.id());
        if (free.isEmpty()) return;
        if (line == null || !line.has(here.id())) return;
        BusSpec.Bus bus = spec.bus(run.itemKey);
        Waiter w = null;
        Integer dest = null;
        String why = null;
        // the event team this bus carries boards first, all of it, one after another
        List<Waiter> order = sh.waiters;
        if (run.team != 0) {
            order = new ArrayList<>(sh.waiters);
            order.sort(Comparator.comparingInt(x -> x.team == run.team ? 0 : 1));
        }
        List<Integer> ahead = line.ahead(line.at(here.id()), run.dir);
        for (Waiter x : order) {
            if (!bus.takes(x.type)) {
                why = "bus.bus-only";
                continue;
            }
            if (x.forcedLine != null && x.forcedLine != line.number) continue;   // waiting for another line (a transfer, 출근 대란)
            if (x.partner != null && free.size() < 2) continue;
            if (free.size() < x.type.x().seats()) continue;   // 강아지 할아버지: his dog sits beside him
            if (x.special != null && run.special) continue;   // one 특급 손님 a bus
            Team tm = x.team == 0 ? null : teams.get(x.team);
            if (x.team != 0 && tm == null) {   // its team is over: an ordinary passenger now
                x.team = 0;
                if (!x.transfer) x.forcedLine = x.forcedDest = null;
            }
            Integer fixed = x.forcedDest != null ? x.forcedDest : tm != null ? tm.dest : null;
            if (tm != null && run.team != tm.id) {
                if (run.team != 0) continue;   // one event team a bus (its other seats take ordinary passengers)
                int members = 0;
                for (Waiter y : sh.waiters) if (y.team == tm.id) members++;
                if (free.size() < members) {   // the team goes together or not at all
                    if (why == null) why = "bus.team-room";
                    continue;
                }
            }
            Integer to;
            if (fixed != null) to = ahead.contains(fixed) ? fixed : null;
            else if (x.special != null) {
                BusEvents.Kind k = spec.events().special().kinds().get(x.special);
                double min = Math.max(bus.minRoad(), k == null ? 0 : k.minRoad());
                to = pickDest(line, here, x.type, p.getUniqueId(), run.dir, new BusSpec.Bus(min, bus.only(), bus.fare()));
                if (to == null) to = pickDest(line, here, x.type, p.getUniqueId(), run.dir, bus);
            } else to = pickDest(line, here, x.type, p.getUniqueId(), run.dir, bus);
            if (to == null) {
                if (why == null) why = fixed != null && tm != null ? "bus.team-other-way" : bus.minRoad() > 0 ? "bus.bus-far" : "bus.end-of-line";
                continue;
            }
            w = x;
            dest = to;
            break;
        }
        if (w == null) {
            if (why != null && ticks - run.noLineAt > 200) {
                run.noLineAt = ticks;
                HudModule.flash(p, Lang.mm(why, "line", line.name(), "km", km(bus.minRoad()), "n", free.size()), 3000);
            }
            return;
        }
        sh.waiters.remove(w);
        sh.dirty = true;   // the rest move up the line
        if (w.partner == null && w.type.slow() >= SHUFFLE_SLOW && w.m.isValid()) {   // 할머니: shuffles to the door first
            shuffle(p, run, d, vm, here, line, w, dest, sh, (int) (cfg.boardTicks() * w.type.slow()));
            return;
        }
        if (!board(p, run, d, vm, here, line, w, dest, free.getFirst())) return;
        if (w.partner != null && sh.waiters.remove(w.partner)) {
            List<Integer> left = vm.freeSeats(d.id());
            if (!left.isEmpty()) board(p, run, d, vm, here, line, w.partner, dest, left.getFirst());
            else gone(w.partner);
        }
        run.busy = (int) (cfg.boardTicks() * w.type.slow());
        remember(p.getUniqueId(), here.id(), dest);
    }

    /** A passenger this slow (bus.yml types.*.slow) walks to the bus door step by step before taking a seat. */
    static final double SHUFFLE_SLOW = 2.0;

    /**
     * A slow passenger (할머니) gets up, says so, and shuffles to the driver's door over {@code ticks}, the bus waiting
     * (run.busy); then sits. If the bus has gone by then (the driver more than 12 blocks away, or no seat left), they go
     * back to the front of the line.
     */
    private void shuffle(Player p, Run run, VehicleModule.Driven d, VehicleModule vm, BusStop here, BusLine line, Waiter w, int dest,
                         Shown sh, int ticks) {
        if (w.seat != null) {   // up off the bench
            w.m.leaveVehicle();
            w.seat.remove();
            w.seat = null;
        }
        run.busy = ticks + 10;
        talk(run, w.m, w.type, "slow", true);
        int steps = Math.max(1, ticks / 2);
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!w.m.isValid()) {
                    cancel();
                    return;
                }
                i++;
                org.bukkit.Location at = w.m.getLocation(), door = p.getLocation();
                boolean near = p.isOnline() && door.getWorld() == at.getWorld() && door.distanceSquared(at) < 144;
                if (i < steps && near) {   // a small step towards the door, facing it (a slow, bobbing walk)
                    double dx = door.getX() - at.getX(), dz = door.getZ() - at.getZ(), len = Math.hypot(dx, dz);
                    double step = len > 1.2 ? Math.min(len - 1.2, len / (steps - i + 1)) : 0;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    org.bukkit.Location to = at.clone().add(len > 0 ? dx / len * step : 0, 0, len > 0 ? dz / len * step : 0);
                    to.setYaw(yaw);
                    to.setPitch(i % 8 < 4 ? 20 : 12);   // head bobbing down, watching her feet
                    w.m.teleport(to);
                    return;
                }
                cancel();
                List<Integer> free = vm.freeSeats(d.id());
                if (!near || free.isEmpty() || !board(p, run, d, vm, here, line, w, dest, free.getFirst())) {
                    if (w.m.isValid()) {   // missed the bus: back to the front of the line
                        sh.waiters.add(0, w);
                        sh.dirty = true;
                    }
                    return;
                }
                remember(p.getUniqueId(), here.id(), dest);
            }
        }.runTaskTimer(eco, 2L, 2L);
    }

    /** One passenger gets on: seated, counted off the stop, told to the driver. False when they could not sit. */
    private boolean board(Player p, Run run, VehicleModule.Driven d, VehicleModule vm, BusStop here, BusLine line, Waiter w, int dest, int seat) {
        if (w.seat != null) {   // up off the bench first
            w.m.leaveVehicle();
            w.seat.remove();
            w.seat = null;
        }
        if (!w.m.isValid() || !vm.seatGuest(d.id(), seat, w.m)) {
            if (w.m.isValid()) eco.getLogger().warning("[버스] 손님을 " + seat + "번 자리에 앉히지 못함 (" + here.name() + ")");
            w.m.remove();
            return false;
        }
        waiting.merge(here.id(), -1.0, (a, b) -> Math.max(0, a + b));
        StopQueue q = queues.get(here.id());
        if (q != null) q.take(w.since);
        // 기다림 짜증: a long wait, a crosser passenger
        double waited = (System.currentTimeMillis() - w.since) / 60000.0, start = spec.mood().start();
        double mood = Math.min(100, spec.patience().mood(start, waited) + lostItems.cheer(here.id(), w.since));   // saw a lost thing come back
        double road = line.between(line.at(here.id()), line.at(dest));
        Rider r = new Rider(w.m, w.type, here.id(), dest, road, mood);
        r.odoAt = run.meter.total();
        r.face = w.face;
        r.transfer = w.transfer;
        run.riders.add(r);
        // 바둑아 어디 가: now and then the 강아지 할아버지 boards alone (his dog ran off at a stop of this line)
        boolean dogLost = w.type.x().seats() > 1 && lostDog.boarding(p, run, r, w, here, line);
        if (w.type.x().seats() > 1 && !dogLost) seatDog(d.id(), r);
        secret.boarded(p, run);
        BusStop to = stops.get(dest);
        String destName = to == null ? "?" : to.name();
        if (run.riders.size() == 1 && !run.greeted) {   // the run's first passenger: the driver greets them
            run.greeted = true;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
            Bukkit.getScheduler().runTaskLater(eco, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.5f), 3L);
            p.sendMessage(Lang.mm("bus.first-rider"));
        }
        served(p, run, here, true);
        List<String> eventTalk = teamBoarded(p, run, w, r, destName);
        if (w.special != null) eventTalk = specialBoarded(p, run, w, r, destName);
        if (eventTalk != null) {
            sayLine(run, r, eventTalk);
            return true;
        }
        if (w.transfer) {
            d.world().playSound(d.location(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 2f);
            HudModule.flash(p, Lang.mm("bus.transfer", "type", typeTag(w.type), "dest", destName), 2000);
        } else {
            HudModule.flash(p, Lang.mm("bus.boarded", "type", typeTag(w.type), "dest", destName, "km", km(r.road)), 2000);
        }
        talk(run, r.m, r.type, w.transfer ? "transfer" : mood <= start - 5 ? "waited" : "board", mood <= start - 5);
        if (w.type.announce()) Bukkit.broadcast(Lang.msg("bus.vip", "player", p.getName(), "dest", destName));
        return true;
    }

    /** How far (blocks) a bus's height may be from a stop's road surface and still be at it (a bay on a slope; not a road over or under it). */
    static final double STOP_DY = 4;
    /** How long (ticks) a bus stands still beside a stop (not in its bay) before those going there get off anyway. */
    static final int STILL_TICKS = 40;

    /**
     * The stop a bus standing still at (x, y, z) is at, or null. In a stop's bay (or by a simple stop): of two such (stops
     * close together) the one a passenger aboard is going to, else the nearest along. Standing still beside a stop for
     * {@link #STILL_TICKS} (in the lane by its bay, a little before or after it) with someone aboard going there: that stop
     * too (a safety net: they always get off where they are going).
     */
    BusStop stopAt(Run run, String world, double x, double y, double z, BusSpec.Stops cfg) {
        return stopAt(stops.values(), run.riders, run.stillTicks, world, x, y, z, cfg.stopReach(), cfg.bayWidth());
    }

    static BusStop stopAt(java.util.Collection<BusStop> all, List<Rider> riders, int stillTicks, String world, double x, double y, double z,
                          double reach, int width) {
        java.util.Set<Integer> going = new java.util.HashSet<>();
        for (Rider r : riders) if (!r.secret && r.dest >= 0) going.add(r.dest);
        BusStop best = null;
        double bestScore = Double.MAX_VALUE;
        for (BusStop s : all) {
            if (!s.world().equals(world) || !s.standing(x, y, z, reach, width, STOP_DY)) continue;
            double score = Math.abs(s.local(x, z)[0]) - (going.contains(s.id()) ? 1000 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best != null || stillTicks < STILL_TICKS || going.isEmpty()) return best;
        for (BusStop s : all) {
            if (!s.world().equals(world) || !going.contains(s.id()) || Math.abs(y - s.top()) > STOP_DY || !s.around(x, z, reach, width)) continue;
            double score = Math.abs(s.local(x, z)[0]);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /**
     * How far a passenger rode from `from` to `here` (blocks, what they pay for when they get off before where they were
     * going): along the line when both are on it, else the shortest way on the roads, else what they were to ride.
     */
    static double rode(Net n, BusLine line, int from, int here, double planned) {
        if (line != null && line.has(from) && line.has(here)) return line.between(line.at(from), line.at(here));
        double r = n == null ? Double.POSITIVE_INFINITY : n.road(from, here);
        return Double.isFinite(r) ? r : planned;
    }

    /**
     * 공정 운행: the blocks a passenger getting off here pays for (0: no ride, no fare). Nothing where they got on; where their
     * stop was changed on the way (passed, the bus turned round) no more than they really rode; and no more than the bus
     * really drove with them aboard (ServiceGate).
     */
    private double paidRoad(Run run, Rider r, Net n, BusLine line, BusStop here) {
        if (r.from == here.id()) return 0;
        boolean atDest = r.dest == here.id();
        double rode = atDest && !r.rerouted ? r.road : rode(n, line, r.from, here.id(), r.road);
        return ServiceGate.paidRoad(ServiceGate.rodeRoad(r.road, rode, atDest, r.rerouted), run.meter.total() - r.odoAt, spec.fair());
    }

    /** A passenger got off without a ride (where they got on, or the bus did not drive them): no fare; an ordinary one waits again. */
    private void noRide(Player p, Rider r, BusStop here) {
        p.sendMessage(Lang.mm("bus.no-ride", "type", typeTag(r.type)));
        if (r.from == here.id() && r.team == 0 && r.special == null && !r.transfer) {
            waiting.merge(here.id(), 1.0, (a, b) -> Math.max(a, Math.min(here.size(), a + b)));
        }
    }

    /** Pulling out of a stop: "다음 정류장은 …" to the driver (and the riders' bell is reset for it). */
    private void departed(Player p, Run run, int from) {
        Net n = nets.get(p.getWorld().getName());
        BusLine line = n == null ? null : n.line(run.line);
        if (line == null || !line.has(from)) return;
        Integer next = line.next(line.at(from), run.dir);
        BusStop s = next == null ? null : stops.get(next);
        if (s == null) return;
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.5f);
        HudModule.flash(p, Lang.mm("bus.announce-next", "stop", s.name()), 2500);
    }

    /** At a stop of two lines or more, a passenger who got off may wait there for another line (환승). */
    private void maybeTransfer(Net n, BusLine line, BusStop here, Rider r) {
        if (n == null || line == null || r.transfer || ThreadLocalRandom.current().nextDouble() >= spec.extra().transfer()) return;
        List<BusLine> others = new ArrayList<>();
        for (BusLine l : n.linesOf(here.id())) if (l.number != line.number) others.add(l);
        if (others.isEmpty()) return;
        BusLine other = others.get(ThreadLocalRandom.current().nextInt(others.size()));
        List<Integer> targets = new ArrayList<>();
        for (int id : other.stops()) if (id != here.id() && !line.has(id) && stops.containsKey(id)) targets.add(id);
        if (targets.isEmpty()) return;
        int to = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        forced.computeIfAbsent(here.id(), k -> new ArrayDeque<>()).addLast(new Forced(r.type.id(), other.number, to, true));
        waiting.merge(here.id(), 1.0, Double::sum);
        talk(null, r.m, r.type, "transfer", true);
    }

    /** The driver's chosen line (/버스 노선), or 0. */
    private final Map<UUID, Integer> chosenLine = new HashMap<>();

    /**
     * At a stop: the line the bus runs (the one chosen with /버스 노선 if this stop is on it, else the one it ran if the stop
     * is on it, else the stop's first) and which way, by where the bus faces.
     */
    private void pickLine(Player p, Run run, VehicleModule.Driven d, BusStop here) {
        Net n = nets.get(here.world());
        if (n == null) return;
        List<BusLine> ls = n.linesOf(here.id());
        if (ls.isEmpty()) return;
        BusLine chosen = n.line(chosenLine.getOrDefault(p.getUniqueId(), 0)), cur = n.line(run.line);
        BusLine pick = chosen != null && chosen.has(here.id()) ? chosen : cur != null && cur.has(here.id()) ? cur : mostGoing(ls, run.riders);
        boolean changed = pick.number != run.line;
        run.line = pick.number;
        double r = Math.toRadians(d.yaw());
        int dir = pick.dir(pick.at(here.id()), -Math.sin(r), Math.cos(r));
        changed |= dir != run.dir;
        run.dir = dir;
        if (changed) {
            Integer last = run.dir > 0 ? pick.stops().getLast() : pick.stops().getFirst();
            BusStop end = stops.get(last);
            p.sendMessage(Lang.msg("bus.line-set", "line", pick.name(), "end", end == null ? "?" : end.name()));
            if (ls.size() > 1 && chosen == null) p.sendMessage(Lang.msg("bus.line-many", "n", ls.size()));
        }
    }

    /** Of the lines of a stop, the one most passengers aboard are going on (their stops on it), the first of equals. */
    static BusLine mostGoing(List<BusLine> ls, List<Rider> riders) {
        BusLine best = ls.getFirst();
        int most = -1;
        for (BusLine l : ls) {
            int c = 0;
            for (Rider r : riders) if (!r.secret && r.dest >= 0 && l.has(r.dest)) c++;
            if (c > most) {
                most = c;
                best = l;
            }
        }
        return best;
    }

    /** /버스 노선 [번호]: the lines of this world, or choose one. */
    public void lines(Player p, String arg) {
        Net n = nets.get(p.getWorld().getName());
        if (n == null || n.lines().isEmpty()) {
            p.sendMessage(Lang.msg("bus.lines-none"));
            return;
        }
        if (arg != null) {
            int num;
            try {
                num = Integer.parseInt(arg.replace("번", ""));
            } catch (NumberFormatException e) {
                num = -1;
            }
            BusLine l = n.line(num);
            if (l == null) {
                p.sendMessage(Lang.msg("bus.lines-unknown", "n", arg));
                return;
            }
            chosenLine.put(p.getUniqueId(), num);
            p.sendMessage(Lang.msg("bus.lines-chosen", "line", l.name()));
            return;
        }
        p.sendMessage(Lang.mm("bus.lines-head", "n", n.lines().size()));
        for (BusLine l : n.lines()) {
            List<String> names = new ArrayList<>();
            for (int id : l.stops()) {
                BusStop s = stops.get(id);
                if (s != null) names.add(s.name());
            }
            p.sendMessage(Lang.mm("bus.lines-row", "line", l.name(), "stops", String.join(" <dark_gray>-</dark_gray> ", names)));
        }
    }

    /** The line numbers a stop is on ("1 · 3"), or "". */
    String lineNumbers(BusStop s) {
        Net n = nets.get(s.world());
        if (n == null) return "";
        List<String> out = new ArrayList<>();
        for (BusLine l : n.linesOf(s.id())) out.add(l.number + "번");
        return String.join(" · ", out);
    }

    /** A passenger's fare (× factor: a failed 특급 손님 may pay less) to the driver. Returns whether they came on time. */
    private boolean pay(Player p, Run run, Rider r, BusStop at, double road, double factor) {
        BusSpec.Fare f = spec.fare();
        BusSpec.Extra x = spec.extra();
        BusSpec.Bus bus = spec.bus(run.itemKey);
        double secs = (System.currentTimeMillis() - r.boarded) / 1000.0;
        boolean onTime = secs <= spec.onTime(road, r.type);
        r.mood = r.type.floor(r.mood);
        // a stop on a hard road (README "국도", 성격): its fares ×1.4 (roads.characters.hard.fare), either end of the ride
        double hard = dev.stsv.economy.road.RoadRewards.fare(roadAt(stops.get(r.from)), roadAt(at));
        double fare = spec.fare(road, r.type) * bus.fare() * (r.transfer ? 1 + x.transferBonus() : 1) * Math.max(0, factor) * hard;
        double base = fare * spec.moodFactor(r.mood) + r.type.luggage();
        boolean rain = p.getWorld().hasStorm();
        double tip = onTime && r.mood >= r.type.tipMood() ? fare * f.tip() * r.type.tip() * (rain ? 1 + x.rainTip() : 1) : 0;
        int[] bless = blessed.get(p.getUniqueId());   // 스님's blessing: the next passengers tip more
        if (bless != null && bless[0] > 0) {
            bless[0]--;
            if (tip > 0) tip *= bless[1] / 100.0;
            if (bless[0] <= 0) blessed.remove(p.getUniqueId());
        }
        String dayKey = "bus.day." + p.getUniqueId();
        String today = eco.calendar().days().date(System.currentTimeMillis()).toString();
        String[] was = eco.kv().get(dayKey, "").split(":");
        double earned = was.length == 2 && was[0].equals(today) ? Double.parseDouble(was[1]) : 0;
        double total = base + tip;
        long paid = Math.round(spec.daily(earned, total));
        long fee = 0;
        if (run.rented) {   // 렌트 버스: the server takes its fee
            long kept = spec.rental().keep(paid);
            fee = paid - kept;
            paid = kept;
        }
        eco.kv().set(dayKey, today + ":" + (earned + paid));
        eco.kv().set("bus.riders." + p.getUniqueId(), eco.kv().getLong("bus.riders." + p.getUniqueId(), 0) + 1);
        if (paid > 0) core.economy().deposit(p.getUniqueId(), paid, "bus");
        int rate = total <= 0 ? 100 : (int) Math.round(100.0 * (paid + fee) / Math.round(total));
        String tipText = tip > 0 ? Lang.raw("bus.fare-tip", "tip", Math.round(tip)) : onTime ? "" : Lang.raw("bus.fare-late");
        if (r.type.luggage() > 0) tipText += Lang.raw("bus.fare-luggage", "g", Math.round(r.type.luggage()));
        if (fee > 0) tipText += Lang.raw("bus.fare-rental", "fee", String.format("%,d", fee));
        String curve = rate < 100 ? Lang.raw("bus.fare-curve", "rate", rate) : "";
        if (!r.type.x().ghost() || paid > 0) p.sendMessage(Lang.mm("bus.fare", "fare", String.format("%,d", paid), "type", typeTag(r.type),
                "mood", (int) Math.round(r.mood), "tip", tipText, "curve", curve));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        if (r.mood >= 90) r.m.getWorld().spawnParticle(Particle.HEART, r.m.getLocation().add(0, 2.1, 0), 2, 0.3, 0.2, 0.3);
        // a kind passenger leaves something for the driver (할머니: 식혜, 인절미 ...)
        double giftMood = r.type.x().giftMood() >= 0 ? r.type.x().giftMood() : x.giftMood();
        double giftChance = r.type.x().giftChance() >= 0 ? r.type.x().giftChance() : x.giftChance();
        if (!r.type.gifts().isEmpty() && r.mood >= giftMood && ThreadLocalRandom.current().nextDouble() < giftChance) {
            BusSpec.Gift g = r.type.gifts().get(ThreadLocalRandom.current().nextInt(r.type.gifts().size()));
            dev.stsv.economy.util.Items.create(g.item(), g.amount()).ifPresent(item -> {
                for (org.bukkit.inventory.ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), left);
                p.sendMessage(Lang.mm("bus.gift", "type", typeTag(r.type), "item", item.effectiveName() == null ? g.item()
                        : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(item.effectiveName()), "n", g.amount()));
            });
            talk(null, r.m, r.type, "thanks", true);
        } else if (r.mood >= 80 && ThreadLocalRandom.current().nextDouble() < 0.3) {
            talk(run, r.m, r.type, "thanks", false);
        }
        return onTime;
    }

    private void remember(UUID driver, int from, int to) {
        Deque<long[]> d = pairs.computeIfAbsent(driver, k -> new ArrayDeque<>());
        d.addLast(new long[]{Math.min(from, to), Math.max(from, to), System.currentTimeMillis()});
        while (d.size() > 64) d.removeFirst();
    }

    private int recent(UUID driver, int a, int b) {
        Deque<long[]> d = pairs.get(driver);
        if (d == null) return 0;
        long since = System.currentTimeMillis() - spec.fare().repeatWindow() * 60_000L;
        int n = 0;
        for (long[] x : d) if (x[2] >= since && x[0] == Math.min(a, b) && x[1] == Math.max(a, b)) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------
    // 버스 렌트: the server's small bus lent at a stop, a fee off every fare, no time limit
    // ------------------------------------------------------------------------------------------

    private boolean rentedId(long id) {
        for (long[] r : rentals.values()) if (r[0] == id) return true;
        return false;
    }

    /** /버스 렌트: a server bus in the bay of the nearest stop (within the rental's range), the player at its wheel. */
    public void rent(Player p) {
        if (eco.guide() != null && eco.guide().busRent(p)) return;   // 길라잡이: the tutorial's bus
        BusSpec.Rental rn = spec.rental();
        VehicleModule vm = core.vehicles();
        var item = rn.vehicle().isEmpty() || vm == null ? java.util.Optional.<dev.stsv.core.content.CustomItem>empty() : core.content().item(rn.vehicle());
        if (item.isEmpty()) {
            p.sendMessage(Lang.msg("bus.rent-off"));
            return;
        }
        long[] had = rentals.get(p.getUniqueId());
        if (had != null && vm.temporaryAlive(had[0])) {
            p.sendMessage(Lang.msg(vm.drivesTemporary(had[0], p) ? "bus.rent-driving" : "bus.rent-have"));
            return;
        }
        rentals.remove(p.getUniqueId());
        if (p.isInsideVehicle()) {
            p.sendMessage(Lang.msg("bus.rent-get-out"));
            return;
        }
        BusStop near = null;
        double best = rn.range();
        for (BusStop s : stops.values()) {
            if (!s.world().equals(p.getWorld().getName())) continue;
            double dist = Math.hypot(p.getX() - s.x(), p.getZ() - s.z());
            if (dist <= best) {
                best = dist;
                near = s;
            }
        }
        if (near == null) {
            p.sendMessage(Lang.msg("bus.rent-far", "range", rn.range()));
            return;
        }
        // in the bay (a simple stop: its lane), facing the way traffic goes on its side (right-hand traffic)
        double out = near.bay() ? BusStop.ROAD_HALF + spec.stops().bayWidth() / 2.0 : 2.5;
        double[] at = near.at(0, out);
        float yaw = (float) Math.toDegrees(Math.atan2(-near.hx() * near.side(), near.hz() * near.side()));
        long id = vm.spawnTemporary(item.get(), p.getWorld(), at[0], near.top(), at[1], yaw, null, p);
        if (id == 0) {
            p.sendMessage(Lang.msg("bus.rent-failed"));
            return;
        }
        vm.letOutOfTemporary(id);
        rentals.put(p.getUniqueId(), new long[]{id, 0});
        p.sendMessage(Lang.mm("bus.rent-ok", "stop", near.name(), "share", (int) Math.round(rn.share() * 100),
                "fee", (int) Math.round((1 - rn.share()) * 100), "after", rn.returnAfter()));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1.6f);
    }

    /** /버스 반납: the rented bus goes back (not with passengers aboard: they get off first). */
    public void giveBack(Player p) {
        if (eco.guide() != null && eco.guide().busReturn(p)) return;   // 길라잡이: the tutorial's bus
        long[] r = rentals.get(p.getUniqueId());
        VehicleModule vm = core.vehicles();
        if (r == null || vm == null || !vm.temporaryAlive(r[0])) {
            rentals.remove(p.getUniqueId());
            p.sendMessage(Lang.msg("bus.rent-none"));
            return;
        }
        Run run = runs.get(r[0]);
        if (run != null && !run.riders.isEmpty()) {
            p.sendMessage(Lang.msg("bus.rent-riders", "n", run.riders.size()));
            return;
        }
        vm.removeTemporary(r[0]);
        rentals.remove(p.getUniqueId());
        p.sendMessage(Lang.msg("bus.rent-back"));
    }

    /** Every second: a rented bus whose renter left the game or has been out of it too long goes back. */
    private void rentals() {
        VehicleModule vm = core.vehicles();
        if (vm == null || rentals.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, long[]>> it = rentals.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, long[]> e = it.next();
            long[] r = e.getValue();
            Player p = Bukkit.getPlayer(e.getKey());
            if (!vm.temporaryAlive(r[0])) {   // its chunk unloaded, it fell out of the world ...
                vm.removeTemporary(r[0]);
                it.remove();
                if (p != null) p.sendMessage(Lang.msg("bus.rent-back"));
                continue;
            }
            if (p != null && vm.drivesTemporary(r[0], p)) {
                r[1] = 0;
                continue;
            }
            if (r[1] == 0) {
                r[1] = now;
                if (p != null) p.sendMessage(Lang.msg("bus.rent-left", "after", spec.rental().returnAfter()));
            }
            if (p == null || now - r[1] >= spec.rental().returnAfter() * 1000L) {
                vm.removeTemporary(r[0]);
                it.remove();
                if (p != null) p.sendMessage(Lang.msg("bus.rent-back"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Every second: waiting passengers, who is shown, the driver's screen
    // ------------------------------------------------------------------------------------------

    private void second() {
        long now = System.currentTimeMillis();
        rentals();
        double minutes = (now - lastRefill) / 60000.0;
        lastRefill = now;
        int hour = ZonedDateTime.now(KST).getHour();
        festival();
        double rate = spec.hourFactor(hour) / Math.max(0.1, spec.stops().refillMinutes()) * (festOn ? spec.events().festival().refill() : 1);
        for (BusStop s : stops.values()) {
            Net n = nets.get(s.world());
            if (n == null || n.linesOf(s.id()).isEmpty()) continue;
            double before = waiting.getOrDefault(s.id(), 0.0);
            double after = waiting.merge(s.id(), minutes * rate, (a, b) -> a >= s.size() ? a : Math.min(s.size(), a + b));
            if (Math.floor(after) > Math.floor(before)) maybeSpecial(s);   // someone new came: now and then a 특급 손님
        }
        dayEvents();
        teamsUpkeep(now);
        // who came when (the line's order, how long each has waited); now and then the one waiting longest gives up
        boolean giveUps = ticks % (spec.patience().every() * 20L) < 20;
        for (BusStop s : stops.values()) {
            StopQueue q = queues.computeIfAbsent(s.id(), k -> new StopQueue());
            q.sync((int) Math.floor(waiting.getOrDefault(s.id(), 0.0)), now);
            if (giveUps) giveUp(s, q, now);
        }
        if (giveUps) rescueScan(now);
        boolean bisNow = ticks % (spec.board().refresh() * 20L) < 20, lampNow = ticks % 200 < 20;
        Map<Run, int[]> runAt = new HashMap<>();
        // a bus near a stop: its passengers stand in the shelter
        for (BusStop s : stops.values()) {
            World w = Bukkit.getWorld(s.world());
            if (w == null) continue;
            Player nearDriver = null;
            double range = spec.stops().showRange();
            for (Run r : runs.values()) {
                Player p = Bukkit.getPlayer(r.driver);
                if (p != null && p.getWorld().equals(w) && Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) <= range) {
                    nearDriver = p;
                    break;
                }
            }
            Shown sh = shown.computeIfAbsent(s.id(), k -> new Shown());
            if (sh.waiters.removeIf(x -> {
                if (x.m.isValid()) return false;
                requeue(s.id(), x);   // (its chunk went away) still waiting, unseen
                gone(x);
                return true;
            })) sh.dirty = true;
            if (nearDriver != null) {
                sh.lastNear = now;
                sh.driver = nearDriver.getUniqueId();
                int want = (int) Math.floor(waiting.getOrDefault(s.id(), 0.0));
                StopQueue q = queues.computeIfAbsent(s.id(), k -> new StopQueue());
                while (sh.waiters.size() < want) {
                    Waiter x = spawnWaiter(w, s, sh.waiters.size(), want, nearDriver.getUniqueId());
                    if (x == null) break;
                    x.since = q.at(sh.waiters.size(), now);
                    sh.waiters.add(x);
                    sh.dirty = true;
                    if (x.type.pair() && x.team == 0 && x.special == null && sh.waiters.size() < want + 1) {   // a couple: the other half beside them
                        Waiter y = spawnWaiter(w, s, sh.waiters.size(), want + 1, nearDriver.getUniqueId(), x.type);
                        if (y != null) {
                            x.partner = y;
                            y.partner = x;
                            sh.waiters.add(y);
                            waiting.merge(s.id(), 1.0, Double::sum);
                            q.since.add(x.since);   // came together
                            y.since = x.since;
                        }
                    }
                }
                // in a line in the order they came (under the roof in the rain)
                boolean rain = w.hasStorm();
                if (sh.dirty || rain != sh.rain) {
                    sh.rain = rain;
                    sh.dirty = false;
                    arrange(w, s, sh);
                }
            } else if (!sh.waiters.isEmpty() && now - sh.lastNear > spec.stops().hideAfter() * 1000L) {
                hide(s.id());
            }
            if (!stopTags.containsKey(s.id()) && w.isChunkLoaded((int) Math.floor(s.x()) >> 4, (int) Math.floor(s.z()) >> 4)) {
                stopTags.put(s.id(), detectTags(w, s));   // a stop from before traits: worked out once its place is loaded
            }
            int signN = (int) Math.floor(waiting.getOrDefault(s.id(), 0.0));
            String nos = lineNumbers(s);
            String words = tagWords(s);
            if (!words.isEmpty()) nos = nos.isEmpty() ? "<gray>" + words : nos + " <gray>" + words;
            if (marketHere(s)) nos += " <#ff9e5c>장날";
            nos += eventSign(s, now);
            boolean loaded = w.isChunkLoaded((int) Math.floor(s.x()) >> 4, (int) Math.floor(s.z()) >> 4);
            // 버스 도착 안내판: every line through it, the nearest bus coming its way and when (every few seconds, loaded stops only)
            if (loaded && (bisNow || !bisRows.containsKey(s.id()))) {
                bisRows.put(s.id(), arrivals(s, w, runAt));
                routeBoard(w, s);
            }
            // the ceiling lamp: lit from dusk to dawn (only when it turns, loaded stops only)
            if (loaded && lampNow) {
                boolean night = StopBuilder.night(w.getTime());
                Boolean lit = lampShown.get(s.id());
                if (lit == null || lit != night) {
                    StopBuilder.setLamp(w, s, spec.stops(), night);
                    lampShown.put(s.id(), night);
                }
            }
            String rows = bisRows.getOrDefault(s.id(), "");
            if (!rows.isEmpty()) nos = nos.isEmpty() ? rows.substring(1) : nos + rows;
            else if (comingBus(s, w) != null) nos += "\n<#ffd166><b>곧 도착</b>";   // a bus on no line yet
            int key = signN * 31 + nos.hashCode();
            Integer had = signShown.get(s.id());
            if ((had == null || had != key) && loaded) {
                builder.sign(w, s, signN, nos, spec.stops());
                signShown.put(s.id(), key);
            }
        }
        // riders: the time left on their tag, a late office worker sulks
        for (Run r : runs.values()) {
            ride(r, now);
            for (Rider x : r.riders) {
                double left = spec.onTime(x.road, x.type) - (now - x.boarded) / 1000.0;
                if (left < 0 && x.type.late() > 0) x.mood = x.type.floor(Math.max(0, x.mood - x.type.late() / 60.0));
                BusStop dest = stops.get(x.dest);
                x.m.setDescription(Lang.mm("bus.rider-tag", "dest", x.secret ? "???" : dest == null ? "?" : dest.name(),
                        "left", x.secret ? "???" : clock(timeLeft(x, now)), "mood", (int) Math.round(x.mood)));
            }
            Player p = Bukkit.getPlayer(r.driver);
            if (p != null) hud(p, r);
        }
        secret.second(now);
        lostItems.second(now);
        if (ticks % 1200 < 20) saveStops();   // the waiting numbers now and then
    }

    /**
     * Once a second for a bus: someone running late says so, a long ride makes some nod off (Zz), a passenger whose stop the
     * bus went past calls out (or wakes with a start) and gets off at the next one, and near the sea or the mountains a
     * tourist takes a picture.
     */
    private void ride(Run run, long now) {
        Player p = Bukkit.getPlayer(run.driver);
        if (p == null || run.riders.isEmpty()) return;
        Net n = nets.get(p.getWorld().getName());
        BusLine line = n == null ? null : n.line(run.line);
        lost(run, n == null ? null : line);
        double[] off = new double[1];
        int idx = line == null ? -1 : line.nearest(p.getX(), p.getZ(), off);
        if (off[0] > 40) line = null;   // off the line's road: nothing counts as passed
        BusSpec.Extra x = spec.extra();
        traits(p, run, now);
        for (Rider r : run.riders) {
            double rode = (now - r.boarded) / 1000.0;
            if (!r.lateSaid && rode > spec.onTime(r.road, r.type)) {
                r.lateSaid = true;
                if (r.type.x().lateHit() != 0) {   // 배달 라이더: late is a disaster
                    r.mood = r.type.floor(Math.clamp(r.mood + r.type.x().lateHit(), 0, 100));
                    talk(run, r.m, r.type, "late", true);
                } else talk(run, r.m, r.type, "late", false);
            }
            if (!r.dozeChecked && rode > x.dozeAfter()) {
                r.dozeChecked = true;
                r.dozing = ThreadLocalRandom.current().nextDouble() < r.type.doze();
            }
            if (r.dozing && ticks % 80 < 20) {   // nodding: the head drops and comes up, Zz now and then
                r.m.setRotation(r.m.getLocation().getYaw(), ticks % 160 < 20 ? 35 : 5);
                if (ticks % 160 < 20) say(r.m, "Zz…");
            }
            if (line != null && r.dest >= 0 && line.has(r.dest)) {
                int at = line.at(r.dest);
                if (run.dir * (idx - at) > x.passed()) {   // gone past their stop
                    talk(run, r.m, r.type, r.sleepThrough || r.dozing ? "doze-missed" : "missed", true);
                    r.mood = Math.clamp(r.mood + x.missedMood(), 0, 100);
                    r.dozing = false;
                    r.sleepThrough = false;
                    Integer next = line.next(idx, run.dir);
                    r.dest = next == null ? -1 : next;
                    r.rerouted = true;   // paid as far as they really rode (a bus that turned round: not the ride they asked for)
                    HudModule.flash(p, Lang.mm("bus.missed", "type", typeTag(r.type)), 2500);
                }
            }
        }
        // an event team aboard cheers now and then (야구팬's chants, 소풍 songs); a 특급 손님 frets
        if (ticks - run.talkAt > x.talkCooldown() * 2 && ThreadLocalRandom.current().nextDouble() < 0.06) {
            for (Rider r : run.riders) {
                Team t = r.team == 0 ? null : teams.get(r.team);
                List<String> lines = t != null ? t.crowd.talk().get(t.lost ? "lose" : "cheer") : r.special != null ? r.special.talk() : null;
                if (lines == null || lines.isEmpty()) continue;
                sayLine(run, r, lines);
                return;
            }
        }
        // a view: by a sea, mountain or forest stop, someone who likes it says so
        if (ticks - run.talkAt > x.talkCooldown() * 3 && ThreadLocalRandom.current().nextDouble() < 0.08) {
            for (BusStop s : stops.values()) {
                if (!s.world().equals(p.getWorld().getName()) || Math.hypot(s.x() - p.getX(), s.z() - p.getZ()) > 120) continue;
                java.util.Set<String> t = tagsOf(s);
                if (!(t.contains("sea") || t.contains("mountain") || t.contains("forest"))) continue;
                for (Rider r : run.riders) {
                    if (r.type.talk().containsKey("view")) {
                        talk(run, r.m, r.type, "view", false);
                        if (r.type.id().equals("tourist")) p.playSound(r.m.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 1.8f);   // 찰칵
                        break;
                    }
                }
                break;
            }
        }
    }

    /**
     * A safety net: a passenger going to a stop that is no more (taken away), or that is not on the line the bus now runs
     * (it changed lines at a stop of two), gets off at the next stop the bus stops at (paid as far as they rode) instead of
     * riding on for ever. Not the 선글라스 남자 (his own rules). Returns how many.
     */
    static int lost(java.util.Map<Integer, BusStop> stops, List<Rider> riders, BusLine line) {
        int n = 0;
        for (Rider r : riders) {
            if (r.secret || r.dest < 0) continue;
            if (!stops.containsKey(r.dest) || (line != null && !line.has(r.dest))) {
                r.dest = -1;
                r.sleepThrough = false;
                n++;
            }
        }
        return n;
    }

    private void lost(Run run, BusLine line) {
        if (lost(stops, run.riders, line) > 0) {
            Player p = Bukkit.getPlayer(run.driver);
            if (p != null) HudModule.flash(p, Lang.mm("bus.dest-gone"), 2500);
        }
    }

    /** The driver's line: the next stop (the nearest any passenger gets off at), how far, how many get off; else the nearest stop. */
    private void hud(Player p, Run r) {
        // 버스 내비게이션: its line takes the place of the line's (the bell below still rings)
        net.kyori.adventure.text.Component navLine = navigator.hud(p, r, hudExtra(p, r));
        boolean nav = navLine != null;
        if (nav) HudModule.flash(p, navLine, 1500);
        Net n = nets.get(p.getWorld().getName());
        BusLine line = n == null ? null : n.line(r.line);
        if (line != null) {
            double[] off = new double[1];
            int idx = line.nearest(p.getX(), p.getZ(), off);
            if (off[0] > 40) {
                if (!nav) HudModule.flash(p, Lang.mm("bus.hud-off", "line", line.name()), 1500);
                return;
            }
            Integer nextId = line.next(idx, r.dir);
            BusStop next = nextId == null ? null : stops.get(nextId);
            String arrow = line.arrow(idx, r.dir, p.getX(), p.getZ(), p.getLocation().getYaw());
            BusLine.Turn turn = line.nextJunction(idx, r.dir, 150);
            String junction = turn == null ? "" : Lang.raw("bus.hud-junction", "m", Math.round(turn.distance()),
                    "turn", Lang.raw(turn.turn() < 0 ? "bus.turn-left" : turn.turn() > 0 ? "bus.turn-right" : "bus.turn-straight"));
            if (next == null) {
                if (!nav) HudModule.flash(p, Lang.mm("bus.hud-end", "line", line.name(), "arrow", arrow, "riders", r.riders.size(), "seats", r.seats,
                        "extra", hudExtra(p, r)), 1500);
                return;
            }
            double dist = line.between(idx, line.at(next.id()));
            int offN = 0;
            for (Rider x : r.riders) if (x.dest == next.id()) offN++;
            if (offN > 0 && dist < 150 && r.rang.add(next.id())) {
                // the bell: those getting off raise a hand; a sleeper may not wake (and rides past)
                boolean said = false;
                for (Rider x : r.riders) {
                    if (x.dest != next.id()) continue;
                    if (x.dozing && ThreadLocalRandom.current().nextDouble() < spec.extra().dozeMiss()) {
                        x.sleepThrough = true;
                        continue;
                    }
                    x.dozing = false;
                    x.m.swingMainHand();
                    if (!said) talk(r, x.m, x.type, "bell", true);
                    said = true;
                }
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);   // 띵동
                Bukkit.getScheduler().runTaskLater(eco, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.95f), 4L);
                HudModule.flash(p, Lang.mm("bus.bell", "stop", next.name(), "n", offN), 3000);
                return;
            }
            if (!nav) HudModule.flash(p, Lang.mm("bus.hud-line", "line", line.name(), "arrow", arrow, "stop", next.name(), "km", km(dist),
                    "off", offN > 0 ? Lang.raw("bus.hud-off-n", "n", offN) : "", "junction", junction, "riders", r.riders.size(),
                    "seats", r.seats, "extra", hudExtra(p, r)), 1500);
            return;
        }
        double best = Double.MAX_VALUE;
        BusStop near = null;
        for (BusStop s : stops.values()) {
            if (!s.world().equals(p.getWorld().getName())) continue;
            double d = Math.hypot(s.x() - p.getX(), s.z() - p.getZ());
            if (d < best) {
                best = d;
                near = s;
            }
        }
        if (nav) return;
        if (near == null || best > 3000) HudModule.flash(p, Lang.mm("bus.hud-none"), 1500);
        else HudModule.flash(p, Lang.mm("bus.hud-empty", "stop", near.name(), "km", km(best), "waiting",
                (int) Math.floor(waiting.getOrDefault(near.id(), 0.0)), "extra", hudExtra(p, r)), 1500);
    }

    // ------------------------------------------------------------------------------------------
    // 이벤트 (bus.yml events): every reward per team, per run, per stop or per skill (never per head), rentals keep their share
    // ------------------------------------------------------------------------------------------

    private final long startedAt = System.currentTimeMillis();

    /**
     * An event reward to the driver: a rented bus keeps only rental.share of it (like its fares). Counted in today's bus
     * earnings (/버스). Returns {paid, fee}.
     */
    long[] bonus(Player p, Run run, long amount, String reason) {
        if (amount <= 0) return new long[]{0, 0};
        long paid = amount, fee = 0;
        if (run != null && run.rented) {
            paid = spec.rental().keep(amount);
            fee = amount - paid;
        }
        if (paid > 0) core.economy().deposit(p.getUniqueId(), paid, reason);
        String dayKey = "bus.day." + p.getUniqueId();
        String today = eco.calendar().days().date(System.currentTimeMillis()).toString();
        String[] was = eco.kv().get(dayKey, "").split(":");
        double earned = was.length == 2 && was[0].equals(today) ? Double.parseDouble(was[1]) : 0;
        eco.kv().set(dayKey, today + ":" + (earned + paid));
        return new long[]{paid, fee};
    }

    static String g(long amount) {
        return String.format("%,d", amount);
    }

    private static String feeText(long fee) {
        return fee > 0 ? Lang.raw("bus.bonus-fee", "fee", g(fee)) : "";
    }

    /** Seconds a rider has left: their on-time (a 특급 손님's limit, an event team's deadline when sooner). */
    private double timeLeft(Rider x, long now) {
        double ot = spec.onTime(x.road, x.type), lim = x.special != null && x.special.limit(ot) > 0 ? x.special.limit(ot) : ot;
        double left = lim - (now - x.boarded) / 1000.0;
        Team t = x.team == 0 ? null : teams.get(x.team);
        if (t != null && t.deadline > 0) left = Math.min(left, (t.deadline - now) / 1000.0);
        return left;
    }

    /** A rider says one of these lines (a bubble, and beside their face on the driver's overlay). */
    void sayLine(Run run, Rider r, List<String> lines) {
        if (lines == null || lines.isEmpty() || !r.m.isValid()) return;
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        say(r.m, line);
        r.said = line;
        r.saidUntil = System.currentTimeMillis() + 3000;
        if (run != null) run.talkAt = ticks;
    }

    /** A festival point for the driver (버스 대축제: stop, team, special, rescue, course; never fares or heads). */
    private void festPoint(Player p, String what) {
        if (!festOn) return;
        int n = spec.events().festival().point(what);
        if (n <= 0) return;
        festPoints.computeIfAbsent(p.getUniqueId(), k -> new int[1])[0] += n;
        festAt.put(p.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Someone got on or off at a stop (boarded: got on). Once a stop visit that counts (공정 운행, ServiceGate: another stop
     * than the last counted one, really driven to): a festival point and 안전 운행 연속; someone getting on also marks the
     * stop served (외딴 정류장 구조 pays when it was marked).
     */
    private void served(Player p, Run run, BusStop here, boolean boarded) {
        long now = System.currentTimeMillis();
        if (boarded) {
            Long since = lastServed.put(here.id(), now);
            if (rescueStop != null && rescueStop == here.id()) {
                rescueStop = null;
                double min = (now - (since == null ? startedAt : since)) / 60000.0;
                long[] paid = bonus(p, run, Math.max(spec.events().rescue().base(), spec.events().rescue().bonus(min)), "bus_event");
                p.sendMessage(Lang.mm("bus.rescue-done", "g", g(paid[0]), "min", (int) min, "fee", feeText(paid[1])));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
                Bukkit.broadcast(Lang.msg("bus.rescue-all", "player", p.getName(), "stop", here.name()));
                festPoint(p, "rescue");
            }
        }
        if (run.served) return;
        run.served = true;
        // 공정 운행: the visit counts (a festival point, a 산길 정류장, 안전 운행 연속) only at another stop than the last counted
        // one, after the driver really drove a bus there from it (not one stop over and over, not without driving)
        ServiceGate gate = gates.computeIfAbsent(p.getUniqueId(), u -> new ServiceGate());
        Net net = nets.get(here.world());
        double road = net == null || gate.last() < 0 ? Double.NaN : net.road(gate.last(), here.id());
        if (!gate.visit(here.id(), road, spec.fair())) return;
        festPoint(p, "stop");
        dev.stsv.economy.road.RoadCharacter on = roadAt(here);
        if (dev.stsv.economy.road.RoadRewards.mountain(on)) mountainStop(p);
        BusEvents.Streak st = spec.events().streak();
        if (!st.enabled()) return;
        int[] k = streaks.computeIfAbsent(p.getUniqueId(), u -> new int[]{0});
        k[0]++;
        long bonus = Math.round(st.bonus(k[0]) * dev.stsv.economy.road.RoadRewards.streak(on));   // a hard road's stop: ×2
        if (bonus <= 0) return;
        long[] paid = bonus(p, run, bonus, "bus_event");
        p.sendMessage(Lang.mm("bus.streak-bonus", "n", k[0], "g", g(paid[0]), "fee", feeText(paid[1])));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
    }

    /** A jolt or a bump: 안전 운행 연속 ends, a 특급 손님 counts it (응급 환자: every one costs). */
    private void broke(Player p, Run run) {
        for (Rider r : run.riders) {
            if (r.special == null) continue;
            r.hits++;
            if (r.special.hit() > 0) p.sendMessage(Lang.mm("bus.special-hit", "name", r.special.name(), "hit", g(r.special.hit())));
        }
        int[] k = streaks.get(p.getUniqueId());
        if (k != null && k[0] > 0) {
            if (spec.events().streak().enabled()) p.sendMessage(Lang.mm("bus.streak-reset", "n", k[0]));
            k[0] = 0;
        }
    }

    /** The bus just stopped in a stop's bay: 노선 완주 counts it, lost items for this stop go back. */
    private void arrived(Player p, Run run, BusStop here) {
        run.served = false;
        run.lostHere = false;
        Net n = nets.get(here.world());
        BusLine line = n == null ? null : n.line(run.line);
        BusEvents.Course co = spec.events().course();
        if (line != null && line.has(here.id())) {
            List<Integer> ordered = new ArrayList<>(line.stops());
            ordered.removeIf(id -> !stops.containsKey(id));
            if (run.dir < 0) java.util.Collections.reverse(ordered);
            int done = run.course.arrive(line.number, run.dir, ordered, here.id());
            long bonus = co.enabled() ? co.bonus(done) : 0;
            if (bonus > 0) {
                String key = "bus.course." + p.getUniqueId();
                long count = eco.kv().getLong(key, 0) + 1;
                eco.kv().set(key, String.valueOf(count));
                long[] paid = bonus(p, run, bonus, "bus_event");
                p.sendMessage(Lang.mm("bus.course-done", "line", line.name(), "n", done, "g", g(paid[0]), "count", count, "fee", feeText(paid[1])));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                Bukkit.broadcast(Lang.msg("bus.course-all", "player", p.getName(), "line", line.name(), "n", done));
                festPoint(p, "course");
            }
        }
        returnLost(p, run, here);
    }

    // ---- 단체 손님 (teams of four): 출근 대란, 야구 끝, 소풍날

    /** A crowd event's turn (once a second): it comes at its minutes of the day (the same after a restart; kv keeps how many came). */
    private void crowd(String key, BusEvents.Crowd c, ZonedDateTime now) {
        if (!c.enabled()) return;
        int times = c.timesOn(now.getDayOfWeek());
        if (times <= 0) return;
        int m = now.getHour() * 60 + now.getMinute();
        if (m < c.from() * 60 || m >= c.to() * 60) return;
        String date = now.toLocalDate().toString(), kvKey = "bus.event." + key;
        String[] was = eco.kv().get(kvKey, "").split(":");
        int done = was.length == 2 && was[0].equals(date) ? Integer.parseInt(was[1]) : 0;
        int[] at = BusEvents.minutes(now.toLocalDate().toEpochDay(), key.hashCode(), c.from(), c.to(), times);
        if (done >= at.length || m < at[done]) return;
        if (startCrowd(key, c, now)) eco.kv().set(kvKey, date + ":" + (done + 1));
    }

    /** A crowd comes to one stop (of the crowd's traits if any), in teams; told to everyone. False when there is no stop yet. */
    private boolean startCrowd(String key, BusEvents.Crowd c, ZonedDateTime now) {
        List<BusStop> on = new ArrayList<>(), tagged = new ArrayList<>();
        for (BusStop s : stops.values()) {
            Net n = nets.get(s.world());
            if (n == null || n.linesOf(s.id()).isEmpty()) continue;
            boolean busy = false;
            for (Team t : teams.values()) if (t.stop == s.id() && t.boarded < t.size) busy = true;
            if (busy) continue;   // another crowd waits there
            on.add(s);
            for (String t : c.tags()) {
                if (tagsOf(s).contains(t)) {
                    tagged.add(s);
                    break;
                }
            }
        }
        if (on.isEmpty()) return false;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<BusStop> pool = tagged.isEmpty() ? on : tagged;
        BusStop s = pool.get(rnd.nextInt(pool.size()));
        String type = !c.alt().isEmpty() && spec.types().containsKey(c.alt()) && rnd.nextDouble() < c.altChance() ? c.alt() : c.type();
        BusSpec.Type kind = spec.types().get(type);
        if (kind == null) return false;
        int n = c.min() + rnd.nextInt(c.max() - c.min() + 1);
        BusEvents.Team cfg = spec.events().team();
        int[] sizes = BusEvents.split(n, cfg.size());
        boolean won = c.winChance() > 0 && rnd.nextDouble() < c.winChance(), lost = c.winChance() > 0 && !won;
        long deadline = c.deadline() >= 0 ? now.toLocalDate().atTime(c.deadline(), 0).atZone(KST).toInstant().toEpochMilli() : 0;
        // with a deadline (출근 대란): each team its own stop ahead on a line through the stop (the way with the most)
        BusLine line = null;
        List<Integer> dests = new ArrayList<>();
        if (deadline > 0) {
            Net net = nets.get(s.world());
            for (BusLine l : net.linesOf(s.id())) {
                for (int dir : new int[]{1, -1}) {
                    List<Integer> a = new ArrayList<>(l.ahead(l.at(s.id()), dir));
                    a.removeIf(id -> !stops.containsKey(id));
                    if (a.size() > dests.size()) {
                        dests = a;
                        line = l;
                    }
                }
            }
            java.util.Collections.shuffle(dests);
        }
        Deque<Forced> q = forced.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
        for (int i = 0; i < sizes.length; i++) {
            Team t = new Team(nextTeam++, i + 1, sizes[i], s.id(), key, c, cfg.color(i + 1), deadline, won, lost);
            if (line != null && !dests.isEmpty()) {
                t.line = line.number;
                t.dest = dests.get(i % dests.size());
            }
            teams.put(t.id, t);
            for (int k = 0; k < sizes[i]; k++) q.addLast(new Forced(type, t.line, t.dest, false, t.id, null));
        }
        waiting.merge(s.id(), (double) n, Double::sum);
        switch (key) {
            case "rush" -> Bukkit.broadcast(Lang.msg("bus.event-rush", "stop", s.name(), "n", n, "teams", sizes.length, "deadline", c.deadline(),
                    "line", line == null ? "" : line.name()));
            case "ballgame" -> Bukkit.broadcast(Lang.msg(won ? "bus.event-ball-win" : "bus.event-ball-lose", "stop", s.name(), "n", n,
                    "teams", sizes.length, "x", String.format("%.1f", c.winBonus())));
            default -> Bukkit.broadcast(Lang.msg("bus.picnic", "stop", s.name(), "n", n, "teams", sizes.length, "who", kind.name()));
        }
        return true;
    }

    private static String eventName(String key) {
        return switch (key) {
            case "rush" -> Lang.raw("bus.event-name-rush");
            case "ballgame" -> Lang.raw("bus.event-name-ballgame");
            default -> Lang.raw("bus.event-name-picnic");
        };
    }

    /** A team member got on: the bus now carries that team (one at a time); the first says the crowd's line. Null: no line. */
    private List<String> teamBoarded(Player p, Run run, Waiter w, Rider r, String destName) {
        Team t = w.team == 0 ? null : teams.get(w.team);
        if (t == null) return null;
        r.team = t.id;
        t.boarded++;
        if (t.won) r.mood = Math.min(100, r.mood + t.crowd.winMood());
        if (t.lost) r.moodX = Math.max(0, t.crowd.loseMood());
        if (run.team == t.id) return null;
        run.team = t.id;
        if (t.dest == null) t.dest = r.dest;
        long preview = t.crowd.teamBonus(spec.events().team(), false, true, t.won);
        p.sendMessage(Lang.mm("bus.team-board", "color", t.color, "no", t.no, "n", t.size, "dest", destName, "g", g(preview),
                "deadline", t.deadline > 0 ? Lang.raw("bus.team-deadline", "h", t.crowd.deadline()) : ""));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
        List<String> l = t.crowd.talk().get(t.won ? "win" : t.lost ? "lose" : "board");
        return l == null || l.isEmpty() ? null : l;
    }

    /** A team member got off: once the whole team is off, its bonus (whole, at its stop, before its deadline). */
    private void teamOff(Player p, Run run, Rider r, BusStop here, boolean atDest, boolean onTime) {
        Team t = teams.get(r.team);
        if (t == null) return;
        t.off++;
        if (!atDest || (t.dest != null && t.dest != here.id())) t.broken = true;
        t.allOnTime &= onTime;
        boolean more = false;
        for (Rider x : run.riders) if (x.team == t.id) more = true;
        if (!more && run.team == t.id) run.team = 0;
        if (t.off < t.size) return;   // some still to come (on this bus, or still waiting)
        teams.remove(t.id);
        if (t.broken) {
            p.sendMessage(Lang.mm("bus.team-broken", "color", t.color, "no", t.no));
            return;
        }
        boolean late = t.deadline > 0 && System.currentTimeMillis() > t.deadline;
        long bonus = t.crowd.teamBonus(spec.events().team(), late, t.allOnTime, t.won);
        if (bonus <= 0) {
            p.sendMessage(Lang.mm("bus.team-late", "color", t.color, "no", t.no));
            return;
        }
        long[] paid = bonus(p, run, bonus, "bus_event");
        p.sendMessage(Lang.mm("bus.team-done", "color", t.color, "no", t.no, "g", g(paid[0]),
                "ontime", t.allOnTime ? Lang.raw("bus.team-ontime") : "", "won", t.won ? Lang.raw("bus.team-won") : "", "fee", feeText(paid[1])));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.5f);
        Bukkit.broadcast(Lang.msg("bus.team-all", "player", p.getName(), "event", eventName(t.event), "no", t.no, "stop", here.name()));
        festPoint(p, "team");
    }

    /**
     * Once a second: at an event's deadline the riders of its late teams sulk (late-mood) and their driver is told; a crowd
     * nobody came for ends after a while (an hour past its deadline, else two hours): its people wait on as ordinary ones.
     */
    private void teamsUpkeep(long now) {
        if (teams.isEmpty()) return;
        for (Iterator<Team> it = teams.values().iterator(); it.hasNext(); ) {
            Team t = it.next();
            if (t.deadline > 0 && now > t.deadline && !t.lateHit) {
                t.lateHit = true;
                for (Run r : runs.values()) {
                    boolean has = false;
                    for (Rider x : r.riders) {
                        if (x.team != t.id) continue;
                        x.mood = Math.clamp(x.mood + t.crowd.lateMood(), 0, 100);
                        has = true;
                    }
                    Player p = has ? Bukkit.getPlayer(r.driver) : null;
                    if (p != null) p.sendMessage(Lang.mm("bus.team-late", "color", t.color, "no", t.no));
                }
            }
            long end = t.deadline > 0 ? t.deadline + 3_600_000L : t.made + 7_200_000L;
            if ((t.boarded == 0 && now > end) || now > t.made + 4 * 3_600_000L) it.remove();
        }
    }

    // ---- 특급 손님

    /** Someone new came to this stop: with events.special's chance (more in the festival) it is a 특급 손님 (gold name). */
    private void maybeSpecial(BusStop s) {
        BusEvents.Special sp = spec.events().special();
        if (!sp.enabled() || sp.kinds().isEmpty()) return;
        double chance = sp.chance() * (festOn ? spec.events().festival().chance() : 1);
        if (ThreadLocalRandom.current().nextDouble() >= chance || hasSpecial(s.id())) return;
        // one nobody came for in half an hour (unseen at a stop no bus passes) is an ordinary passenger again
        long now = System.currentTimeMillis();
        specialSince.entrySet().removeIf(e -> {
            if (now - e.getValue() < 30 * 60_000L) return false;
            Deque<Forced> q = forced.get(e.getKey());
            if (q != null) q.removeIf(f -> f.special() != null);
            return true;
        });
        int out = 0;
        for (Deque<Forced> q : forced.values()) for (Forced f : q) if (f.special() != null) out++;
        for (Shown sh : shown.values()) for (Waiter w : sh.waiters) if (w.special != null) out++;
        for (Run r : runs.values()) if (r.special) out++;
        if (out >= sp.max()) return;
        BusEvents.Kind k = sp.pick(ThreadLocalRandom.current().nextDouble());
        if (k == null || !spec.types().containsKey(k.type())) return;
        // the newcomer just counted is this one
        forced.computeIfAbsent(s.id(), x -> new ArrayDeque<>()).addLast(new Forced(k.type(), null, null, false, 0, k.id()));
        specialSince.put(s.id(), now);
    }

    private boolean hasSpecial(int stop) {
        Deque<Forced> q = forced.get(stop);
        if (q != null) for (Forced f : q) if (f.special() != null) return true;
        Shown sh = shown.get(stop);
        if (sh != null) for (Waiter w : sh.waiters) if (w.special != null) return true;
        return false;
    }

    /** A 특급 손님 got on: its rules told to the driver. Returns its line. */
    private List<String> specialBoarded(Player p, Run run, Waiter w, Rider r, String destName) {
        BusEvents.Kind k = spec.events().special().kinds().get(w.special);
        if (k == null) return null;
        r.special = k;
        run.special = true;
        double ot = spec.onTime(r.road);
        String rule = (k.limit(ot) > 0 ? Lang.raw("bus.special-rule-time", "left", clock(k.limit(ot))) : "")
                + (k.minMood() > 0 ? Lang.raw("bus.special-rule-mood", "mood", (int) k.minMood()) : "")
                + (k.hit() > 0 ? Lang.raw("bus.special-rule-hit", "hit", g(k.hit())) : "");
        p.sendMessage(Lang.mm("bus.special-board", "name", k.name(), "dest", destName, "g", g(k.bonus()), "rule", rule));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
        return k.talk().isEmpty() ? null : k.talk();
    }

    /** A 특급 손님 got off: its bonus (told to everyone), or why not. */
    private void specialOff(Player p, Run run, Rider r, BusStop here, long bonus, String why) {
        run.special = false;
        for (Rider x : run.riders) if (x.special != null) run.special = true;
        if (bonus <= 0) {
            p.sendMessage(Lang.mm("bus.special-fail", "name", r.special.name(), "why", why == null ? "" : why));
            return;
        }
        long[] paid = bonus(p, run, bonus, "bus_event");
        p.sendMessage(Lang.mm("bus.special-done", "name", r.special.name(), "g", g(paid[0]), "fee", feeText(paid[1])));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.3f);
        if (r.m.isValid()) r.m.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, r.m.getLocation().add(0, 1.5, 0), 12, 0.4, 0.5, 0.4);
        Bukkit.broadcast(Lang.msg("bus.special-all", "player", p.getName(), "name", r.special.name(), "stop", here.name(), "g", g(paid[0])));
        festPoint(p, "special");
    }

    // ---- 외딴 정류장 구조

    /**
     * Every patience.every seconds: the stop (with someone waiting) no bus has taken anyone from for longest, past
     * rescue.after minutes, is marked (its sign, told to everyone) till a bus takes someone there.
     */
    private void rescueScan(long now) {
        BusEvents.Rescue rc = spec.events().rescue();
        if (!rc.enabled()) {
            rescueStop = null;
            return;
        }
        if (rescueStop != null && stops.containsKey(rescueStop) && waiting.getOrDefault(rescueStop, 0.0) >= 1) return;
        rescueStop = null;
        BusStop best = null;
        double most = 0;
        for (BusStop s : stops.values()) {
            Net n = nets.get(s.world());
            if (n == null || n.linesOf(s.id()).isEmpty() || waiting.getOrDefault(s.id(), 0.0) < 1) continue;
            double min = (now - lastServed.getOrDefault(s.id(), startedAt)) / 60000.0;
            if (min > most) {
                most = min;
                best = s;
            }
        }
        if (best == null || most < rc.after()) return;
        rescueStop = best.id();
        Bukkit.broadcast(Lang.msg("bus.rescue-call", "stop", best.name(), "min", (int) most, "g", g(rc.bonus(most))));
    }

    /** What a stop's sign adds for the events: 기다리다 지친 손님 (구조), a crowd in teams, a 특급 손님. */
    private String eventSign(BusStop s, long now) {
        StringBuilder b = new StringBuilder();
        if (rescueStop != null && rescueStop == s.id()) {
            double min = (now - lastServed.getOrDefault(s.id(), startedAt)) / 60000.0;
            b.append("\n").append(Lang.raw("bus.rescue-sign", "g", g(spec.events().rescue().bonus(min))));
        }
        int n = 0;
        for (Team t : teams.values()) if (t.stop == s.id() && t.boarded < t.size) n++;
        if (n > 0) b.append("\n").append(Lang.raw("bus.team-sign", "n", n));
        if (hasSpecial(s.id())) b.append("\n").append(Lang.raw("bus.special-sign"));
        b.append(secret.sign(s));
        forWatchers(x -> b.append(x.sign(s)));
        return b.toString();
    }

    // ---- 분실물

    /** A lost item's tag, or null (not one). */
    static BusEvents.LostTag lostTag(org.bukkit.inventory.ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        return BusEvents.LostTag.decode(it.getItemMeta().getPersistentDataContainer().get(LOST, PersistentDataType.STRING));
    }

    /** Someone got off: now and then they left something behind (LostItems). */
    private void maybeLose(Player p, Run run, Rider r, BusStop here) {
        lostItems.drop(p, run, r, here);
    }

    /** At a stop: the driver's lost items for it go back to their owners; at a line's end, the ones nobody came for (LostItems). */
    private void returnLost(Player p, Run run, BusStop here) {
        lostItems.returnAt(p, run, here);
    }

    /** What a lost item is (its kind's name). */
    String lostName(BusEvents.LostTag t) {
        return lostItems == null ? t.what() : lostItems.thingName(t);
    }

    // ---- 3차 손님: 수다쟁이 아줌마, 멀미하는 아저씨, 애 업은 엄마, 강아지 할아버지, 산타, 유령, 배달 라이더, 스님, 유튜버

    /** 유령 승차권 조각 a driver needs for the title, and the title */
    static final int GHOST_TOKENS = 10;
    static final String GHOST_TITLE = "economy:night_driver";
    /** driver -> {passengers still blessed, their tip × 100} (스님: 성불하세요; in memory) */
    private final Map<UUID, int[]> blessed = new HashMap<>();

    /**
     * Once a second for a bus: chatter bubbles (수다쟁이 아줌마: everyone else a little crosser each time; 유튜버: 구독 좋아요~),
     * the 멀미하는 아저씨 at his limit ("웁…": off at the next stop the bus stops at), every mood kept above its kind's floor.
     */
    private void traits(Player p, Run run, long now) {
        for (Rider r : run.riders) {
            BusSpec.Traits t = r.type.x();
            if (t.chatter() > 0 && now - r.chatAt >= t.chatter() * 1000) {
                r.chatAt = now;
                sayLine(run, r, spec.lines(r.type, "chat"));
                if (t.chatterMood() != 0) {
                    for (Rider o : run.riders) if (o != r) o.mood = o.type.floor(Math.clamp(o.mood + t.chatterMood() * o.type.mood(), 0, 100));
                }
            }
            if (t.sickAt() > 0 && !r.sick && r.mood <= t.sickAt()) {
                r.sick = true;
                r.dest = -1;   // off at the next stop the bus stops at (paid as far as he rode)
                sayLine(run, r, spec.lines(r.type, "sick"));
                HudModule.flash(p, Lang.mm("bus.sick", "type", typeTag(r.type)), 2500);
            }
            r.mood = r.type.floor(r.mood);
        }
    }

    /** Got off where they were going: 유령 승차권 조각, 스님's blessing, 유튜버's ★5 review. */
    private void alightTraits(Player p, Run run, Rider r) {
        BusSpec.Traits t = r.type.x();
        UUID u = p.getUniqueId();
        if (t.token() > 0) {
            String key = "bus.ghost." + u;
            long had = eco.kv().getLong(key, 0), now = had + t.token();
            eco.kv().set(key, String.valueOf(now));
            p.sendMessage(Lang.mm("bus.ghost-token", "n", Math.min(now, GHOST_TOKENS), "max", GHOST_TOKENS));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.6f);
            if (had < GHOST_TOKENS && now >= GHOST_TOKENS && core.profiles() != null) {
                core.profiles().grantTitle(u, GHOST_TITLE);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 0.8f);
                Bukkit.broadcast(Lang.msg("bus.ghost-title", "player", p.getName()));
            }
        }
        if (t.bless() > 0) {
            blessed.put(u, new int[]{t.bless(), (int) Math.round(t.blessTip() * 100)});
            p.sendMessage(Lang.mm("bus.blessed", "n", t.bless(), "x", String.format("%.1f", t.blessTip())));
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.5, 0), 12, 0.5, 0.6, 0.5, 0.01);
        }
        if (t.review() > 0 && r.mood >= t.review()) {
            String key = "bus.review." + u, today = eco.calendar().days().date(System.currentTimeMillis()).toString();
            if (!today.equals(eco.kv().get(key, ""))) {
                eco.kv().set(key, today);
                Bukkit.broadcast(Lang.msg("bus.review-all", "player", p.getName()));
            }
        }
    }

    /** 강아지 할아버지: his dog (a baby wolf, not saved) in the seat beside him; none when no seat is left. */
    void seatDog(long bus, Rider r) {
        VehicleModule vm = core.vehicles();
        if (vm == null || r.dog != null || !"wolf".equals(r.type.x().pet()) || !r.m.isValid()) return;
        List<Integer> free = vm.freeSeats(bus);
        if (free.isEmpty()) return;
        org.bukkit.entity.Wolf dog = spawnDog(r.m.getLocation());
        if (!vm.seatGuest(bus, free.getFirst(), dog)) {
            dog.remove();
            return;
        }
        r.dog = dog;
    }

    /** The dog off the bus with its master ("멍!" and a bark when they got off at a stop), then gone. */
    void dropDog(long bus, Rider r, boolean bark) {
        Entity dog = r.dog;
        if (dog == null) return;
        r.dog = null;
        VehicleModule vm = core == null ? null : core.vehicles();
        if (vm != null) vm.unseatGuest(bus, dog);
        if (!bark || !dog.isValid()) {
            dog.remove();
            return;
        }
        dog.getWorld().playSound(dog.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 1f, 1.4f);
        List<String> lines = spec.lines(r.type, "bark");
        say(dog, lines == null ? "멍!" : lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (dog.isValid()) dog.getWorld().spawnParticle(Particle.POOF, dog.getLocation().add(0, 0.5, 0), 4, 0.2, 0.2, 0.2, 0.01);
            dog.remove();
        }, 30L);
    }

    // ---- for SecretAgent (the 선글라스 남자)

    Map<Integer, BusStop> stopMap() {
        return stops;
    }

    Net net(String world) {
        return nets.get(world);
    }

    Map<Long, Run> runMap() {
        return runs;
    }

    // ---- for hidden secrets outside this package (dev.stsv.economy.secret.lostkey: 분실물센터): callbacks and looks

    /**
     * Something else that rides along with the buses (a hidden secret). Registered by id ({@link #watch}); every call is
     * guarded, so a watcher's mistake never stops a bus.
     */
    public interface Watcher {
        /**
         * A passenger is leaving a lost item on this driver's bus (events.lost's chance passed, the driver has room for it):
         * true = the watcher left something of its own instead (no lost item this stop).
         */
        default boolean leftBehind(Player driver, Entity passenger, String passengerTag, BusStop gotOn, BusStop here) {
            return false;
        }

        /** The driver's bus pulled into a stop's bay. */
        default void stopped(Player driver, BusStop here) {}

        /** The driver gave a lost item back to its owner: what it was ("지갑") and who left it (their kind's name, "할머니"). */
        default void gaveBack(Player driver, String thing, String owner, BusStop here) {}

        /** Rows shown under the driver's /버스 list, after its n passengers. */
        default List<Component> riderRows(Player driver, int n) {
            return List.of();
        }

        /** Lines a stop's sign adds ("\n…" each), or "". */
        default String sign(BusStop s) {
            return "";
        }
    }

    private final Map<String, Watcher> watchers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Registers (or with null, removes) a watcher under an id; a later one of the same id replaces the earlier. */
    public void watch(String id, Watcher w) {
        if (w == null) watchers.remove(id);
        else watchers.put(id, w);
    }

    private void forWatchers(java.util.function.Consumer<Watcher> call) {
        for (Watcher w : watchers.values()) {
            try {
                call.accept(w);
            } catch (RuntimeException e) {
                eco.getLogger().log(java.util.logging.Level.WARNING, "[버스] watcher 오류", e);
            }
        }
    }

    /** LostItems: a lost item went back to its owner. */
    void watcherGaveBack(Player driver, String thing, String owner, BusStop here) {
        forWatchers(x -> x.gaveBack(driver, thing, owner, here));
    }

    /** LostItems: whether a watcher left something of its own in place of a lost item. */
    boolean watcherLeft(Player driver, Rider r, BusStop gotOn, BusStop here) {
        for (Watcher w : watchers.values()) {
            try {
                if (w.leftBehind(driver, r.m, typeTag(r.type), gotOn, here)) return true;
            } catch (RuntimeException e) {
                eco.getLogger().log(java.util.logging.Level.WARNING, "[버스] watcher 오류", e);
            }
        }
        return false;
    }

    private Run runOf(Player p) {
        for (Run r : runs.values()) if (p.getUniqueId().equals(r.driver)) return r;
        return null;
    }

    /** How many passengers ride the bus this player drives, or -1 when they drive none. */
    public int ridersOf(Player p) {
        Run r = runOf(p);
        return r == null ? -1 : r.riders.size();
    }

    /** The passengers of one kind (a bus.yml types id: "grandma") riding the bus this player drives; none when they drive none. */
    public List<Entity> ridersOf(Player p, String type) {
        Run r = runOf(p);
        List<Entity> out = new ArrayList<>();
        if (r != null) for (Rider x : r.riders) if (!x.secret && x.type.id().equals(type) && x.m.isValid()) out.add(x.m);
        return out;
    }

    /** The stop whose bay this player's bus stands in, or null. */
    public Integer stoppedAt(Player p) {
        Run r = runOf(p);
        return r == null ? null : r.atStop;
    }

    /** The passengers shown waiting at a stop right now (only while a bus is near), front of the line first. */
    public List<Entity> waitingAt(int stop) {
        Shown sh = shown.get(stop);
        List<Entity> out = new ArrayList<>();
        if (sh != null) for (Waiter w : sh.waiters) if (w.m.isValid()) out.add(w.m);
        return out;
    }

    /** The lines of a world's network (none before it is built). */
    public List<BusLine> linesIn(String world) {
        Net n = nets.get(world);
        return n == null ? List.of() : List.copyOf(n.lines());
    }

    /** A stop's traits (mountain, sea, forest, field, village, port). */
    public java.util.Set<String> stopTags(BusStop s) {
        return java.util.Set.copyOf(tagsOf(s));
    }

    /** How many lost items this driver has given back to their owners. */
    public long lostReturned(UUID driver) {
        return eco.kv().getLong(LostItems.backKey(driver), 0);
    }

    // ---- for BusNavigator (버스 내비게이션)

    /** The stops an event waits at: stop id -> what (구조, 단체 손님 n팀, 특급 손님). */
    Map<Integer, List<String>> eventStops() {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        if (rescueStop != null && stops.containsKey(rescueStop)) out.computeIfAbsent(rescueStop, k -> new ArrayList<>()).add(Lang.raw("bus.nav-ev-rescue"));
        Map<Integer, Integer> waitingTeams = new HashMap<>();
        for (Team t : teams.values()) if (t.boarded < t.size) waitingTeams.merge(t.stop, 1, Integer::sum);
        waitingTeams.forEach((s, n) -> out.computeIfAbsent(s, k -> new ArrayList<>()).add(Lang.raw("bus.nav-ev-team", "n", n)));
        for (BusStop s : stops.values()) if (hasSpecial(s.id())) out.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(Lang.raw("bus.nav-ev-special"));
        return out;
    }

    /** /버스 내비 … (args after 내비). */
    public void nav(Player p, String[] args) {
        navigator.command(p, args);
    }

    public List<String> navComplete(String[] args) {
        return navigator == null ? List.of() : navigator.complete(args);
    }

    /** /버스 요원정류장 <이름> (the first to solve the bus's secret). */
    public void agentStop(Player p, String name) {
        secret.renameStop(p, name);
    }

    /** /관리자 버스 비밀 ... */
    public void secretAdmin(org.bukkit.command.CommandSender s, String[] args) {
        secret.admin(s, args);
    }

    public List<String> secretComplete(String[] args) {
        return secret.complete(args);
    }

    /** /관리자 버스 손님 <종류|전체> [상황]: a passenger kind shown for a few seconds (PassengerPreview). */
    public void previewAdmin(org.bukkit.command.CommandSender s, String[] args) {
        preview.admin(s, args);
    }

    /** /관리자 버스 바둑이 [플레이어] | 상태 [플레이어] | 끝 [플레이어] (LostDog) */
    public void dogAdmin(org.bukkit.command.CommandSender s, String[] args) {
        lostDog.admin(s, args);
    }

    public List<String> dogComplete(String[] args) {
        return lostDog.complete(args);
    }

    /** 바둑아 어디 가: one line of a driver's state (for /관리자 버스 비밀 상태 too). */
    String dogState(UUID u) {
        return lostDog.state(u);
    }

    public List<String> previewComplete(String[] args) {
        return preview.complete(args);
    }

    /**
     * Everyone aboard but the 선글라스 남자 gets off where the bus is (each paid as far as they rode, events as if they got
     * off early) and fades away.
     */
    void dropAll(Player p, Run run, BusStop here) {
        VehicleModule vm = core.vehicles();
        Net n = nets.get(here.world());
        BusLine line = n == null ? null : n.line(run.line);
        for (Iterator<Rider> it = run.riders.iterator(); it.hasNext(); ) {
            Rider r = it.next();
            if (r.secret) continue;
            it.remove();
            if (vm != null) vm.unseatGuest(run.id, r.m);
            dropDog(run.id, r, false);
            double road = paidRoad(run, r, n, line, here);
            boolean atDest = r.dest == here.id() && road > 0 && (!r.rerouted || road + 1e-6 >= r.road);
            if (road > 0) pay(p, run, r, here, road, r.special != null && !atDest ? r.special.failFare() : 1);
            else noRide(p, r, here);
            if (r.special != null) specialOff(p, run, r, here, 0, Lang.raw("bus.special-why-place"));
            if (r.team != 0) teamOff(p, run, r, here, atDest, false);
            Mannequin m = r.m;
            m.setDescription(Component.empty());
            if (m.isValid()) m.getWorld().spawnParticle(Particle.SMOKE, m.getLocation().add(0, 1, 0), 12, 0.2, 0.5, 0.2, 0.01);
            Bukkit.getScheduler().runTaskLater(eco, m::remove, 6L);
        }
    }

    // ---- 내린 손님: a few steps off towards the shelter, a wave, gone (in the rain: hurrying, head down)

    private void walkOff(Mannequin m, BusStop here, boolean rain) {
        m.setDescription(Component.empty());
        if (!m.isValid()) return;
        Location from = m.getLocation();
        double[] l = here.local(from.getX(), from.getZ());
        double[] to = here.at(l[0] + ThreadLocalRandom.current().nextDouble(-2.5, 2.5), here.shelterOut(spec.stops().bayWidth()) + 2.5);
        double step = rain ? 0.34 : 0.17;
        World w = m.getWorld();
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!m.isValid()) {
                    cancel();
                    return;
                }
                i++;
                Location at = m.getLocation();
                double dx = to[0] - at.getX(), dz = to[1] - at.getZ(), len = Math.hypot(dx, dz);
                if (len > 0.4 && i < 40) {
                    double st = Math.min(step, len);
                    Location n = at.clone().add(dx / len * st, 0, dz / len * st);
                    Double y = standY(w, n.getX(), n.getZ(), here.y());
                    if (y != null) n.setY(y);
                    n.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    n.setPitch(rain ? 25 : (i % 6 < 3 ? 4 : 0));
                    m.teleport(n);
                    return;
                }
                cancel();
                if (!rain) {   // turns to the bus and waves
                    m.setRotation(yawTo(at, from.getX(), from.getZ()), 0);
                    m.swingMainHand();
                    Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (m.isValid()) m.swingMainHand();
                    }, 6L);
                }
                Bukkit.getScheduler().runTaskLater(eco, () -> {
                    if (m.isValid()) {
                        m.getWorld().spawnParticle(Particle.POOF, m.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.01);
                        m.remove();
                    }
                }, rain ? 4L : 16L);
            }
        }.runTaskTimer(eco, 6L, 2L);
    }

    // ---- 버스 대축제

    /** Once a second: the festival starts (told), is about to end (told, with who leads), ends (the top drivers paid). */
    private void festival() {
        BusEvents.Festival f = spec.events().festival();
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean on = f.on(now);
        if (on && !festOn) {
            festOn = true;
            festWarned = false;
            festPoints.clear();
            festAt.clear();
            String today = now.toLocalDate().toString();
            if (!today.equals(eco.kv().get("bus.festival", ""))) {   // not told again after a restart
                eco.kv().set("bus.festival", today);
                Bukkit.broadcast(Lang.msg("bus.fest-start", "to", f.to(), "x", String.format("%.0f", f.refill())));
            }
        } else if (on && !festWarned && f.minutesLeft(now) <= f.warn()) {
            festWarned = true;
            List<BusEvents.Score> top = BusEvents.ranking(festPoints, festAt, 1);
            Bukkit.broadcast(Lang.msg("bus.fest-warn", "min", f.minutesLeft(now),
                    "top", top.isEmpty() ? Lang.raw("bus.fest-nobody") : name(top.getFirst().player()) + " (" + top.getFirst().points() + "점)"));
        } else if (!on && festOn) {
            festOn = false;
            List<BusEvents.Score> top = BusEvents.ranking(festPoints, festAt, f.prizes().size());
            Bukkit.broadcast(Lang.msg("bus.fest-end"));
            if (top.isEmpty()) Bukkit.broadcast(Lang.mm("bus.fest-none"));
            for (int i = 0; i < top.size(); i++) {
                BusEvents.Score sc = top.get(i);
                long prize = f.prizes().get(i);
                if (prize > 0) core.economy().deposit(sc.player(), prize, "bus_festival");
                if (i < f.titles().size() && core.profiles() != null) core.profiles().grantTitle(sc.player(), f.titles().get(i));
                Bukkit.broadcast(Lang.mm("bus.fest-rank", "i", i + 1, "player", name(sc.player()), "points", sc.points(), "g", g(prize)));
            }
            festPoints.clear();
            festAt.clear();
        }
    }

    private static String name(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(u).getName();
        return n == null ? "?" : n;
    }

    /** The events part of the driver's action bar: the safe streak, the team aboard, a 특급 손님's time, festival points. */
    private String hudExtra(Player p, Run r) {
        StringBuilder b = new StringBuilder();
        BusEvents ev = spec.events();
        long now = System.currentTimeMillis();
        int[] k = streaks.get(p.getUniqueId());
        if (ev.streak().enabled() && k != null && k[0] > 0) b.append(Lang.raw("bus.hud-streak", "n", k[0], "next", ev.streak().every() - k[0] % ev.streak().every()));
        if (r != null && r.team != 0) {
            Team t = teams.get(r.team);
            if (t != null) {
                BusStop d = t.dest == null ? null : stops.get(t.dest);
                b.append(Lang.raw("bus.hud-team", "color", t.color, "no", t.no, "dest", d == null ? "?" : d.name(),
                        "left", t.deadline > 0 ? " " + clock((t.deadline - now) / 1000.0) : ""));
            }
        }
        if (r != null) {
            for (Rider x : r.riders) {
                if (x.special == null) continue;
                b.append(Lang.raw("bus.hud-special", "name", x.special.name(), "left", clock(timeLeft(x, now))));
                break;
            }
        }
        if (festOn) {
            int[] fp = festPoints.get(p.getUniqueId());
            b.append(Lang.raw("bus.hud-fest", "points", fp == null ? 0 : fp[0]));
        }
        b.append(lostItems.hud(p, navigator.navigating(p.getUniqueId())));   // 분실물: where to take it (or the phone ringing)
        return b.toString();
    }

    /** /버스 이벤트: today's events, the festival, the stop to rescue, the player's streak, completed lines and lost items. */
    public void showEvents(Player p) {
        BusEvents ev = spec.events();
        ZonedDateTime now = ZonedDateTime.now(KST);
        String date = now.toLocalDate().toString();
        p.sendMessage(Lang.mm("bus.events-head"));
        String[] crowds = {"rush", "ballgame", "picnic"};
        BusEvents.Crowd[] cs = {ev.rush(), ev.ballgame(), ev.picnic()};
        for (int i = 0; i < cs.length; i++) {
            BusEvents.Crowd c = cs[i];
            int times = c.enabled() ? c.timesOn(now.getDayOfWeek()) : 0;
            String[] was = eco.kv().get("bus.event." + crowds[i], "").split(":");
            int done = was.length == 2 && was[0].equals(date) ? Integer.parseInt(was[1]) : 0;
            String when = times <= 0 ? Lang.raw("bus.events-not-today")
                    : Lang.raw("bus.events-when", "from", c.from(), "to", c.to(), "times", times, "done", Math.min(done, times));
            p.sendMessage(Lang.mm("bus.events-row", "name", eventName(crowds[i]), "when", when));
        }
        BusEvents.Festival f = ev.festival();
        if (f.enabled()) {
            String day = Lang.raw("weekday." + java.time.DayOfWeek.of(f.day()));
            int[] mine = festPoints.get(p.getUniqueId());
            p.sendMessage(Lang.mm("bus.events-fest", "day", day, "from", f.from(), "to", f.to(),
                    "now", festOn ? Lang.raw("bus.events-fest-on", "points", mine == null ? 0 : mine[0]) : ""));
        }
        if (rescueStop != null && stops.containsKey(rescueStop)) {
            BusStop s = stops.get(rescueStop);
            double min = (System.currentTimeMillis() - lastServed.getOrDefault(s.id(), startedAt)) / 60000.0;
            p.sendMessage(Lang.mm("bus.events-rescue", "stop", s.name(), "min", (int) min, "g", g(ev.rescue().bonus(min))));
        }
        int[] k = streaks.get(p.getUniqueId());
        int lost = 0;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getStorageContents()) {
            BusEvents.LostTag t = lostTag(it);
            if (t != null && !t.expired(System.currentTimeMillis())) lost++;
        }
        p.sendMessage(Lang.mm("bus.events-me", "streak", k == null ? 0 : k[0], "course", eco.kv().getLong("bus.course." + p.getUniqueId(), 0), "lost", lost));
    }

    // ------------------------------------------------------------------------------------------
    // Talk bubbles, stop traits
    // ------------------------------------------------------------------------------------------

    /** A passenger says something (a bubble over their head for 3 seconds), at most one bubble a bus in talk-cooldown ticks. */
    private void talk(Run run, Mannequin m, BusSpec.Type type, String situation, boolean force) {
        if (m == null || !m.isValid()) return;
        if (!force && run != null && ticks - run.talkAt < spec.extra().talkCooldown()) return;
        // 바둑아 어디 가: the 강아지 할아버지 without his dog laments instead (his usual lines all speak to the dog)
        String lament = lostDog == null ? null : lostDog.lamentFor(m);
        List<String> lines = lament != null ? List.of(lament) : spec.lines(type, situation);
        if (lines == null) return;
        if (run != null) run.talkAt = ticks;
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        say(m, line);
        if (run != null) {   // the driver's overlay shows it beside their face for as long as the bubble
            for (Rider r : run.riders) {
                if (r.m.equals(m)) {
                    r.said = line;
                    r.saidUntil = System.currentTimeMillis() + 3000;
                }
            }
        }
    }

    void say(Entity m, String text) {
        bubble(m, text, 60L);
    }

    /** A speech bubble over this entity (riding it), gone after `life` ticks. */
    org.bukkit.entity.TextDisplay bubble(Entity m, String text, long life) {
        return bubble(m, text, life, null);
    }

    /** A speech bubble; with `only`, one that only this player sees (over something only they see). */
    org.bukkit.entity.TextDisplay bubble(Entity m, String text, long life, Player only) {
        org.bukkit.entity.TextDisplay d = m.getWorld().spawn(m.getLocation().add(0, 2.3, 0), org.bukkit.entity.TextDisplay.class, t -> {
            t.setPersistent(false);
            if (only != null) t.setVisibleByDefault(false);
            t.text(dev.stsv.core.util.Text.mm("<black>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text)));
            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(230, 255, 255, 255));
            t.setViewRange(0.5f);
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.55f, 0), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.AxisAngle4f()));
            t.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
        if (only != null) only.showEntity(eco, d);
        if (!m.addPassenger(d)) d.teleport(m.getLocation().add(0, 2.3, 0));
        Bukkit.getScheduler().runTaskLater(eco, d::remove, life);
        return d;
    }

    /** A random rider of this bus (or null). */
    private Rider someone(Run run) {
        return run.riders.isEmpty() ? null : run.riders.get(ThreadLocalRandom.current().nextInt(run.riders.size()));
    }

    java.util.Set<String> tagsOf(BusStop s) {
        return stopTags.getOrDefault(s.id(), java.util.Set.of());
    }

    /**
     * What a stop is like, from around its shelter: the biome (mountain, sea, forest, field), much water near, players'
     * land near (village), a dock near (port).
     */
    java.util.Set<String> detectTags(World w, BusStop s) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        double[] c = s.at(0, s.shelterOut(spec.stops().bayWidth()) + 1);
        int x = (int) Math.floor(c[0]), z = (int) Math.floor(c[1]);
        String biome = w.getBiome(x, s.y(), z).getKey().getKey();
        if (biome.matches(".*(peak|slope|hill|mountain|grove|windswept|stony|badlands).*") || s.y() >= 100) out.add("mountain");
        if (biome.matches(".*(ocean|beach|river|swamp|shore).*")) out.add("sea");
        if (biome.matches(".*(forest|taiga|jungle|birch|cherry|grove).*")) out.add("forest");
        if (biome.matches(".*(plains|savanna|meadow|sunflower).*")) out.add("field");
        int water = 0, all = 0;
        for (int dx = -24; dx <= 24; dx += 6) {
            for (int dz = -24; dz <= 24; dz += 6) {
                all++;
                if (w.getHighestBlockAt(x + dx, z + dz).getType() == org.bukkit.Material.WATER) water++;
            }
        }
        if (water * 5 >= all) out.add("sea");
        if (eco.land() != null) {
            int owned = 0;
            for (int cx = (x >> 4) - 4; cx <= (x >> 4) + 4; cx++) {
                for (int cz = (z >> 4) - 4; cz <= (z >> 4) + 4; cz++) if (eco.land().owner(w.getName(), cx, cz) == 1) owned++;
            }
            if (owned >= 2) out.add("village");
        }
        if (eco.docks() != null) {
            for (dev.stsv.economy.dock.Dock d : eco.docks().docks()) {
                if (d.world().equals(w.getName()) && Math.hypot(d.x() - x, d.z() - z) <= 300) out.add("port");
            }
        }
        return out;
    }

    /** The trait words of a stop ("들판 · 마을"), or "". */
    String tagWords(BusStop s) {
        List<String> out = new ArrayList<>();
        for (String t : tagsOf(s)) out.add(spec.extra().tags().getOrDefault(t, t));
        return String.join(" · ", out);
    }

    /**
     * The driver's overlay (top left, no panel): every passenger's face, mood and where they go, the next to get off
     * first. Empty when they drive no bus or carry nobody.
     */
    private List<dev.stsv.core.hud.HudLayout.OverlayRow> overlayRows(Player p) {
        VehicleModule vm = core.vehicles();
        VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
        Run r = d == null ? null : runs.get(d.id());
        if (r == null || r.riders.isEmpty()) return List.of();
        Net n = nets.get(p.getWorld().getName());
        BusLine line = n == null ? null : n.line(r.line);
        List<Rider> order = new ArrayList<>(r.riders);
        if (line != null) order.sort(Comparator.comparingInt(x -> x.secret ? Integer.MAX_VALUE : r.dir * line.at(x.dest)));
        long now = System.currentTimeMillis();
        List<dev.stsv.core.hud.HudLayout.OverlayRow> out = new ArrayList<>();
        for (Rider x : order) {
            BusStop s = stops.get(x.dest);
            double left = timeLeft(x, now);
            Team tm = x.team == 0 ? null : teams.get(x.team);
            String color = x.special != null ? "#ffcf40" : x.type.color();
            String name = x.special != null ? x.special.name() : tm != null ? x.type.name() + " " + Lang.raw("bus.overlay-team", "no", tm.no) : x.type.name();
            String mood = x.mood >= 90 ? "mood_great" : x.mood >= 70 ? "mood_good" : x.mood >= 40 ? "mood_meh" : "mood_bad";
            if (x.said != null && now < x.saidUntil) {
                out.add(new dev.stsv.core.hud.HudLayout.OverlayRow(x.face, mood, Lang.mm("bus.overlay-talk", "color", color,
                        "type", name), Lang.mm("bus.overlay-said",
                        "said", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(x.said))));
                continue;
            }
            out.add(new dev.stsv.core.hud.HudLayout.OverlayRow(x.face, mood, Lang.mm("bus.overlay-row", "color", color,
                    "type", name, "dest", x.secret ? "???" : x.dest == -1 ? Lang.raw("bus.next-stop") : s == null ? "?" : s.name(),
                    "left", x.secret ? "???" : clock(left) + (x.dozing ? Lang.raw("bus.dozing") : ""))));
        }
        return out;
    }

    /**
     * Which skin (bus.yml types.*.skins) a passenger of this kind wears: one at random; a couple in matching clothes, the
     * first the first look and the other half (`partner`) one of the rest. Null when the kind has none.
     */
    static String pickSkin(BusSpec.Type type, boolean partner) {
        if (type.skins().isEmpty()) return null;
        if (type.pair() && type.skins().size() > 1)
            return partner ? type.skins().get(1 + ThreadLocalRandom.current().nextInt(type.skins().size() - 1)) : type.skins().getFirst();
        return type.skins().get(ThreadLocalRandom.current().nextInt(type.skins().size()));
    }

    /**
     * A passenger mannequin (not saved, can't be hurt or pushed, no gravity, marked a guest): in this skin, at this kind's
     * scale, see-through and glowing for a ghost. `more` sets the name tag and the rest before it appears.
     */
    Mannequin spawnGuest(Location at, BusSpec.Type type, String skin, java.util.function.Consumer<Mannequin> more) {
        return at.getWorld().spawn(at, Mannequin.class, x -> {
            x.setPersistent(false);
            x.setInvulnerable(true);
            x.setGravity(false);
            x.setSilent(true);
            x.setImmovable(true);
            x.setCollidable(false);
            x.setRemoveWhenFarAway(false);
            x.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
            more.accept(x);
            if (skin != null) {
                boolean slim = skin.endsWith(" slim");
                String key = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
                try {
                    x.setProfile(ResolvableProfile.resolvableProfile().name("bus_" + type.id()).skinPatch(sp -> sp.body(Key.key(key))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
                } catch (RuntimeException e) {
                    eco.getLogger().warning("[bus.yml] 스킨 키가 잘못됨: " + skin);
                }
            }
            if (type.scale() != 1) {
                AttributeInstance sc = x.getAttribute(Attribute.SCALE);
                if (sc != null) sc.setBaseValue(type.scale());
            }
            if (type.x().ghost()) {   // 유령 손님: see-through, only a pale glowing outline (and the name) shows
                x.setInvisible(true);
                x.setGlowing(true);
            }
        });
    }

    /** An invisible seat (a display, not saved) for someone to sit on at this spot (the bench; the preview's 'sit'). */
    static org.bukkit.entity.ItemDisplay spawnSeat(Location at) {
        return at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** 강아지 할아버지's dog: a baby wolf, not saved, can't be hurt, no AI. */
    static org.bukkit.entity.Wolf spawnDog(Location at) {
        return at.getWorld().spawn(at, org.bukkit.entity.Wolf.class, w -> {
            w.setPersistent(false);
            w.setInvulnerable(true);
            w.setSilent(true);
            w.setAI(false);
            w.setCollidable(false);
            w.setRemoveWhenFarAway(false);
            w.setBaby();
            w.setAgeLock(true);
            w.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private Waiter spawnWaiter(World w, BusStop s, int i, int n, UUID driver) {
        return spawnWaiter(w, s, i, n, driver, null);
    }

    private Waiter spawnWaiter(World w, BusStop s, int i, int n, UUID driver, BusSpec.Type only) {
        Net net = nets.get(s.world());
        if (net == null) return null;
        if (net.linesOf(s.id()).isEmpty()) return null;
        Deque<Forced> queue = forced.get(s.id());
        Forced f = queue == null ? null : queue.peekFirst();
        if (only != null) f = null;
        BusSpec.Type type = only != null ? only : f != null ? spec.types().get(f.type()) : pickType(s);
        if (f != null) queue.pollFirst();
        if (type == null) return null;
        Team team = f != null && f.team() != 0 ? teams.get(f.team()) : null;
        BusEvents.Kind kind = f != null && f.special() != null ? spec.events().special().kinds().get(f.special()) : null;
        boolean stale = f != null && f.team() != 0 && team == null;   // its team is over: an ordinary passenger
        BusStop teamDest = team == null || team.dest == null ? null : stops.get(team.dest);
        double[] spot = s.waitingSpot(i, Math.max(n, s.size()), spec.stops().bayWidth());
        Location at = new Location(w, spot[0], s.y() + 1, spot[1], s.facingRoad(), 0);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        String lineNos = lineNumbers(s);
        String skin = pickSkin(type, only != null);
        Mannequin m = spawnGuest(at, type, skin, x -> {
            x.customName(dev.stsv.core.util.Text.mm(kind != null ? Lang.raw("bus.special-tag", "name", kind.name())
                    : team != null ? typeTag(type) + " " + Lang.raw("bus.team-tag", "color", team.color, "no", team.no) : typeTag(type)));
            x.setCustomNameVisible(true);
            x.setDescription(team != null ? Lang.mm("bus.waiter-team", "color", team.color, "no", team.no, "n", team.size,
                    "dest", teamDest == null ? lineNos : teamDest.name()) : Lang.mm("bus.waiter-tag", "lines", lineNos));
        });
        String face = null;
        if (skin != null) {
            String key = skin.endsWith(" slim") ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
            face = key.substring(key.lastIndexOf('/') + 1);
        }
        Waiter out = new Waiter(m, type, face);
        if (f != null && !stale) {
            out.forcedLine = f.line();
            out.forcedDest = f.dest();
            out.transfer = f.transfer();
            out.team = team == null ? 0 : team.id;
            out.special = kind == null ? null : kind.id();
        }
        return out;
    }

    /** Who comes to wait at this stop now: by kind, its traits, the hour, a weekend, its market day. */
    private BusSpec.Type pickType(BusStop s) {
        ZonedDateTime now = ZonedDateTime.now(KST);
        int hour = now.getHour();
        boolean weekend = now.getDayOfWeek().getValue() >= 6, market = marketHere(s), rush = spec.rush(hour);
        java.util.Set<String> tags = tagsOf(s);
        dev.stsv.economy.road.RoadCharacter on = roadAt(s);   // a hard road's stop: more hikers and campers
        double total = 0;
        Map<BusSpec.Type, Double> ws = new LinkedHashMap<>();
        for (BusSpec.Type t : spec.types().values()) {
            double w = t.weightAt(tags, hour, weekend, market, rush, now.getMonthValue(), now.getDayOfMonth())
                    * dev.stsv.economy.road.RoadRewards.weight(t.id(), on);
            if (w <= 0) continue;
            ws.put(t, w);
            total += w;
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (var e : ws.entrySet()) {
            roll -= e.getValue();
            if (roll <= 0) return e.getKey();
        }
        return spec.types().values().stream().findFirst().orElse(null);
    }

    /** The character of the 국도 a stop stands by (README "국도", 성격), or null: none (or no roads). */
    private dev.stsv.economy.road.RoadCharacter roadAt(BusStop s) {
        if (s == null || eco.roads() == null) return null;
        return eco.roads().characterNear(s.world(), (int) Math.floor(s.x()), (int) Math.floor(s.z()), dev.stsv.economy.road.RoadRewards.STOP_REACH);
    }

    /** A stop served on a hard road: counted (eco.kv), 산길 기사 at {@link dev.stsv.economy.road.RoadRewards#MOUNTAIN_STOPS}. */
    private void mountainStop(Player p) {
        String key = dev.stsv.economy.road.RoadRewards.MOUNTAIN_KEY + p.getUniqueId();
        long had = eco.kv().getLong(key, 0), now = had + 1;
        eco.kv().set(key, String.valueOf(now));
        if (dev.stsv.economy.road.RoadRewards.titleNow(had, now) && core.profiles() != null) {
            core.profiles().grantTitle(p.getUniqueId(), dev.stsv.economy.road.RoadRewards.MOUNTAIN_TITLE);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
            Bukkit.broadcast(dev.stsv.core.util.Text.msg("<gold>" + p.getName() + " <gray>님이 어려운길(산길) 정류장 "
                    + dev.stsv.economy.road.RoadRewards.MOUNTAIN_STOPS + "곳을 돌고 칭호 <white>[산길 기사]<gray>를 받았어요!"));
        }
    }

    /** Once a day: market day is told; the event crowds come (출근 대란, 야구 끝, 소풍날). */
    private void dayEvents() {
        int day = eco.calendar().day();
        if (spec.marketDay(day) && marketSaidDay != day) {
            marketSaidDay = day;
            List<String> names = new ArrayList<>();
            for (BusStop s : stops.values()) if (marketHere(s)) names.add(s.name());
            if (!names.isEmpty()) Bukkit.broadcast(Lang.msg("bus.market", "stops", String.join(", ", names.subList(0, Math.min(4, names.size())))
                    + (names.size() > 4 ? " 외 " + (names.size() - 4) + "곳" : "")));
        }
        // 출근 대란, 야구 끝, 소풍날: crowds in teams (bus.yml events)
        ZonedDateTime now = ZonedDateTime.now(KST);
        crowd("rush", spec.events().rush(), now);
        crowd("ballgame", spec.events().ballgame(), now);
        crowd("picnic", spec.events().picnic(), now);
    }

    /** Whether today is market day at this stop (5일장: a field or village stop). */
    boolean marketHere(BusStop s) {
        if (!spec.marketDay(eco.calendar().day())) return false;
        for (String t : tagsOf(s)) if (spec.extra().marketTags().contains(t)) return true;
        return false;
    }

    /** A driver whose bus is within 200 blocks of this stop and moving (the sign says 곧 도착), or null. */
    Player comingBus(BusStop s, World w) {
        for (Run r : runs.values()) {
            Player p = Bukkit.getPlayer(r.driver);
            if (p != null && p.getWorld().equals(w) && Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) <= 200
                    && Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) > 10) return p;
        }
        return null;
    }

    /**
     * Where a passenger boarding at `from` goes: a stop ahead on the line the bus runs, the way it runs (never back the way
     * it came), near ones more often (far ones only for some kinds), less of a pair driven over and over. Null: none
     * ahead (the end of the line).
     */
    Integer pickDest(BusLine line, BusStop from, BusSpec.Type type, UUID driver, int dir) {
        return pickDest(line, from, type, driver, dir, BusSpec.Bus.ANY);
    }

    Integer pickDest(BusLine line, BusStop from, BusSpec.Type type, UUID driver, int dir, BusSpec.Bus bus) {
        int at = line.at(from.id());
        List<Integer> ids = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        boolean far = type != null && type.far() > 0;
        List<Integer> ahead = new ArrayList<>(line.ahead(at, dir));
        ahead.removeIf(id -> !stops.containsKey(id) || line.between(at, line.at(id)) < bus.minRoad());
        if (type != null && type.toEnd()) return ahead.isEmpty() ? null : ahead.getLast();   // 군인: all the way (the terminal or the port)
        for (int id : ahead) {
            double d = line.between(at, line.at(id));
            if (far && d < type.far()) continue;
            double w = far ? 1 : 1 / (1 + d / 800);
            if (recent(driver, from.id(), id) >= spec.fare().repeatFree()) w *= spec.fare().repeatFactor();
            ids.add(id);
            ws.add(w);
        }
        if (ids.isEmpty() && far) return pickDest(line, from, null, driver, dir, bus);
        if (ids.isEmpty()) return null;
        double total = ws.stream().mapToDouble(Double::doubleValue).sum(), roll = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < ids.size(); i++) {
            roll -= ws.get(i);
            if (roll <= 0) return ids.get(i);
        }
        return ids.getLast();
    }

    // ------------------------------------------------------------------------------------------
    // Waiting: the line, what they do while they wait, giving up; the arrival board and the route map
    // ------------------------------------------------------------------------------------------

    /**
     * Places a stop's shown passengers: in a line in the order they came, front first by the bus's door (StopQueue.line; in
     * the rain under the roof), those who sit (할머니) on the bench. Only when the line changed or the rain came or went.
     */
    private void arrange(World w, BusStop s, Shown sh) {
        BusSpec.Stops cfg = spec.stops();
        double out0 = s.shelterOut(cfg.bayWidth());
        int n = sh.waiters.size();
        boolean[] sits = new boolean[n];
        for (int i = 0; i < n; i++) sits[i] = "sit".equals(spec.queue().idleOf(sh.waiters.get(i).type.id()));
        List<StopQueue.Spot> bench = StopQueue.bench(s.side(), out0);
        List<StopQueue.Spot> line = StopQueue.line(n, s.side(), s.bay(), sh.rain, out0, spec.queue().gap(), cfg.bayLength());
        int[] where = StopQueue.assign(sits, bench.size());
        float face = s.facingRoad();
        for (int i = 0; i < n; i++) {
            Waiter x = sh.waiters.get(i);
            if (where[i] < 0) {   // on the bench: sat on a seat of their own (a display under them)
                int k = -1 - where[i];
                if (x.seat != null && x.seatAt == k && x.seat.isValid()) continue;
                StopQueue.Spot sp = bench.get(k);
                double[] p = s.at(sp.along(), sp.out());
                Location at = new Location(w, p[0], s.y() + 1.5, p[1], face, 0);
                if (x.seat != null) {
                    x.m.leaveVehicle();
                    x.seat.remove();
                }
                x.m.teleport(at);
                x.home = at;
                x.seat = spawnSeat(at);
                x.seatAt = k;
                if (!x.seat.addPassenger(x.m)) {   // could not sit: stands on the bench's spot
                    x.seat.remove();
                    x.seat = null;
                    x.seatAt = -1;
                }
                continue;
            }
            StopQueue.Spot sp = line.get(Math.min(where[i], line.size() - 1));
            double[] p = s.at(sp.along(), sp.out());
            Double y = sp.roofed() && sp.out() > out0 ? Double.valueOf(s.y() + 1) : standY(w, p[0], p[1], s.y());
            if (y == null) {   // no ground on the strip by the bay (a dip, water): inside instead
                double[] in = s.at((s.side() >= 0 ? 1 : -1) * (1.2 - 0.8 * (where[i] % 4)), out0 + 0.5);
                p = in;
                y = (double) (s.y() + 1);
            }
            Location at = new Location(w, p[0], y, p[1], face, 0);
            if (x.seat != null) {
                x.m.leaveVehicle();
                x.seat.remove();
                x.seat = null;
                x.seatAt = -1;
            }
            x.home = at;
            if (x.m.getLocation().distanceSquared(at) > 0.01) x.m.teleport(at);
        }
    }

    /** Where someone stands on this column (the top of its ground within a block of the floor), or null (no ground there). */
    static Double standY(World w, double x, double z, int y) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int yy = y + 1; yy >= y - 1; yy--) {
            Block below = w.getBlockAt(bx, yy - 1, bz), at = w.getBlockAt(bx, yy, bz);
            if (!below.isSolid() || at.isSolid()) continue;
            double top = 0;
            for (var bb : below.getCollisionShape().getBoundingBoxes()) top = Math.max(top, bb.getMaxY());
            return below.getY() + (top <= 0 ? 1 : top);
        }
        return null;
    }

    /**
     * Every second or so (queue.idle-every ticks), the shown waiting passengers: once the bus is near (queue.notice) they
     * all look at it; till then each passes the time as their kind does (bus.yml queue.idle): sit (on the bench), phone
     * (looks down at it), fidget (glances down the road, checks the time), hop (little jumps); the rest look about now
     * and then. Only stops with someone shown: a few mannequins, nothing heavy.
     */
    private void idle() {
        if (shown.isEmpty()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Map.Entry<Integer, Shown> e : shown.entrySet()) {
            Shown sh = e.getValue();
            BusStop s = stops.get(e.getKey());
            if (s == null || sh.waiters.isEmpty()) continue;
            Player bus = sh.driver == null ? null : Bukkit.getPlayer(sh.driver);
            boolean notice = bus != null && bus.getWorld().getName().equals(s.world())
                    && Math.hypot(bus.getX() - s.x(), bus.getZ() - s.z()) <= spec.queue().notice();
            float face = s.facingRoad();
            // down the road where the bus comes from (upstream on the stop's side)
            double[] up = s.at(-(s.side() >= 0 ? 1 : -1) * 30, BusStop.ROAD_HALF);
            for (Waiter x : sh.waiters) {
                if (!x.m.isValid()) continue;
                Location at = x.m.getLocation();
                if (notice) {   // the bus: everyone looks at it
                    x.m.setRotation(yawTo(at, bus.getX(), bus.getZ()), 0);
                    continue;
                }
                idleBeat(x.m, spec.queue().idleOf(x.type.id()), face, up, x.seat == null ? x.home : null, rnd);
            }
        }
    }

    /**
     * One idle beat of a waiting passenger, as their kind does (bus.yml queue.idle): sit (looks about a little), phone (head
     * down at it), fidget (glances toward `up`, down the road; shifts; looks at the watch), hop (a little jump back to
     * `home`; none when null, e.g. seated); the rest look about now and then. `face` is where they normally look.
     */
    void idleBeat(Mannequin m, String act, float face, double[] up, Location home, ThreadLocalRandom rnd) {
        Location at = m.getLocation();
        switch (act) {
            case "sit" -> {
                if (rnd.nextDouble() < 0.3) m.setRotation(face + (float) rnd.nextDouble(-25, 25), (float) rnd.nextDouble(0, 15));
            }
            case "phone" -> {   // head down at the phone, now and then a look up
                boolean look = rnd.nextDouble() < 0.12;
                m.setRotation(face + (float) rnd.nextDouble(-10, 10), look ? 0 : 40 + (float) rnd.nextDouble(0, 10));
            }
            case "fidget" -> {   // glances down the road, shifts about, looks at the watch
                double r = rnd.nextDouble();
                if (r < 0.4) m.setRotation(yawTo(at, up[0], up[1]) + (float) rnd.nextDouble(-8, 8), 0);
                else if (r < 0.75) m.setRotation(face + (float) rnd.nextDouble(-35, 35), (float) rnd.nextDouble(-5, 10));
                if (rnd.nextDouble() < 0.15) m.swingMainHand();
            }
            case "hop" -> {
                m.setRotation(face + (float) rnd.nextDouble(-60, 60), 0);
                if (home != null && m.getVehicle() == null && rnd.nextDouble() < 0.45) {
                    Location back = home.clone();
                    back.setYaw(m.getLocation().getYaw());
                    m.teleport(back.clone().add(0, 0.35, 0));
                    Bukkit.getScheduler().runTaskLater(eco, () -> {
                        if (m.isValid() && m.getVehicle() == null) m.teleport(back);
                    }, 4L);
                }
            }
            default -> {
                if (rnd.nextDouble() < 0.2) m.setRotation(face + (float) rnd.nextDouble(-30, 30), 0);
            }
        }
    }

    static float yawTo(Location from, double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-(x - from.getX()), z - from.getZ()));
    }

    /**
     * 기다림 짜증: the passenger waiting longest at a stop, past patience.give-up minutes, walks off with patience's chance
     * ("에이, 걸어가야지" when shown). Not while a picnic or a transfer is queued there.
     */
    private void giveUp(BusStop s, StopQueue q, long now) {
        long oldest = q.oldest();
        if (oldest < 0 || !spec.patience().fedUp((now - oldest) / 60000.0)) return;
        Deque<Forced> f = forced.get(s.id());
        if (f != null && !f.isEmpty()) return;
        if (ThreadLocalRandom.current().nextDouble() >= spec.patience().chance()) return;
        Shown sh = shown.get(s.id());
        if (sh != null && !sh.waiters.isEmpty()) {
            Waiter who = null;
            for (Waiter x : sh.waiters) {
                if (x.forcedLine != null || x.partner != null || x.team != 0 || x.special != null) continue;
                if (who == null || x.since < who.since) who = x;
            }
            if (who == null) return;
            sh.waiters.remove(who);
            sh.dirty = true;
            q.take(who.since);
            List<String> lines = spec.lines(who.type, "give-up");
            if (lines != null) say(who.m, lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
            Waiter leaving = who;
            leaving.m.setDescription(Component.empty());
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (leaving.m.isValid()) leaving.m.getWorld().spawnParticle(Particle.POOF, leaving.m.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.01);
                gone(leaving);
            }, 50L);
        } else {
            q.take(oldest);
        }
        waiting.merge(s.id(), -1.0, (a, b) -> Math.max(0, a + b));
    }

    /**
     * 버스 도착 안내판: for every line through the stop (bus.yml board.lines at most), the nearest bus driven on it that
     * is coming this way, with how long till it comes (its road distance at fare.on-time-speed), else 운행 버스 없음.
     * Each bus's place on its line is worked out once a round (runAt).
     */
    private String arrivals(BusStop s, World w, Map<Run, int[]> runAt) {
        Net n = nets.get(s.world());
        if (n == null) return "";
        StringBuilder out = new StringBuilder();
        int shownLines = 0;
        for (BusLine l : n.linesOf(s.id())) {
            if (shownLines++ >= spec.board().lines()) break;
            int si = l.at(s.id());
            double best = Double.MAX_VALUE;
            Player who = null;
            for (Run r : runs.values()) {
                if (r.line != l.number) continue;
                Player p = Bukkit.getPlayer(r.driver);
                if (p == null || !p.getWorld().equals(w)) continue;
                int[] ix = runAt.computeIfAbsent(r, k -> {
                    double[] off = new double[1];
                    int i = l.nearest(p.getX(), p.getZ(), off);
                    return new int[]{i, off[0] > 40 ? 1 : 0};
                });
                if (ix[1] == 1 || r.dir * (si - ix[0]) < 0) continue;   // off its road, or gone past
                double d = l.between(ix[0], si);
                if (d < best) {
                    best = d;
                    who = p;
                }
            }
            String line = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(l.name());
            if (who == null) out.append("\n").append(Lang.raw("bus.bis-none", "line", line));
            else if (best <= 12) out.append("\n").append(Lang.raw("bus.bis-here", "line", line, "driver", who.getName()));
            else if (best <= 200) out.append("\n").append(Lang.raw("bus.bis-soon", "line", line, "driver", who.getName()));
            else {
                long min = Math.max(1, Math.round(best / Math.max(0.1, spec.fare().onTimeSpeed()) / 60));
                out.append("\n").append(Lang.raw("bus.bis-eta", "line", line, "min", min, "driver", who.getName()));
            }
        }
        return out.toString();
    }

    /** 노선도: the stops of every line through this stop on its shelter's back wall (made again only when it changes). */
    private void routeBoard(World w, BusStop s) {
        Net n = nets.get(s.world());
        if (n == null) return;
        List<BusLine> ls = n.linesOf(s.id());
        if (ls.isEmpty()) return;
        var mm = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        StringBuilder text = new StringBuilder(Lang.raw("bus.map-head"));
        for (BusLine l : ls.subList(0, Math.min(3, ls.size()))) {
            List<String> names = new ArrayList<>();
            int here = -1;
            for (int id : l.stops()) {
                BusStop t = stops.get(id);
                if (t == null) continue;
                if (id == s.id()) here = names.size();
                names.add(mm.escapeTags(t.name()));
            }
            BusStop end = stops.get(l.stops().getLast());
            text.append("\n").append(Lang.raw("bus.map-line", "line", mm.escapeTags(l.name()), "end", end == null ? "" : mm.escapeTags(end.name())))
                    .append("\n").append(StopBoard.row(names, here, spec.board().around()));
        }
        String t = text.toString();
        Integer had = boardShown.get(s.id());
        if (had != null && had == t.hashCode()) return;
        StopBoard.show(w, s, spec.stops(), dev.stsv.core.util.Text.mm(t));
        if (StopBoard.find(w, s) != null) boardShown.put(s.id(), t.hashCode());
    }

    /** A stop's route map taken away (the stop removed, moved or built again: it is made again with its sign). */
    private void unboard(World w, BusStop s) {
        StopBoard.remove(w, s);
        boardShown.remove(s.id());
        lampShown.remove(s.id());
        bisRows.remove(s.id());
        signShown.remove(s.id());
        queues.remove(s.id());
    }

    private void hide(int stop) {
        Shown sh = shown.remove(stop);
        if (sh == null) return;
        // those waiting for something of their own (a transfer, an event team, a 특급 손님) wait on unseen, at the front
        for (int i = sh.waiters.size() - 1; i >= 0; i--) {
            requeue(stop, sh.waiters.get(i));
            gone(sh.waiters.get(i));
        }
    }

    /** A waiting passenger with something of their own (a transfer, an event team, a 특급 손님) back in the stop's queue, at the front. */
    private void requeue(int stop, Waiter w) {
        if (w.forcedLine == null && w.forcedDest == null && w.team == 0 && w.special == null) return;
        forced.computeIfAbsent(stop, k -> new ArrayDeque<>()).addFirst(new Forced(w.type.id(), w.forcedLine, w.forcedDest, w.transfer, w.team, w.special));
    }

    /** A waiting passenger taken away (and the bench seat they sat on). */
    private static void gone(Waiter w) {
        if (w.seat != null) w.seat.remove();
        w.seat = null;
        w.m.remove();
    }

    // ------------------------------------------------------------------------------------------
    // What /버스 shows
    // ------------------------------------------------------------------------------------------

    public void showRiders(Player p) {
        VehicleModule vm = core.vehicles();
        VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
        Run r = d == null ? null : runs.get(d.id());
        if (d == null || !spec.vehicles().contains(d.itemKey())) {
            p.sendMessage(Lang.msg("bus.not-driving"));
            return;
        }
        String today = eco.calendar().days().date(System.currentTimeMillis()).toString();
        String[] was = eco.kv().get("bus.day." + p.getUniqueId(), "").split(":");
        long earned = was.length == 2 && was[0].equals(today) ? Math.round(Double.parseDouble(was[1])) : 0;
        int n = r == null ? 0 : r.riders.size();
        Net net = nets.get(p.getWorld().getName());
        BusLine line = net == null || r == null ? null : net.line(r.line);
        p.sendMessage(Lang.mm("bus.list-head", "n", n, "seats", d.seats() - 1, "today", String.format("%,d", earned),
                "line", line == null ? Lang.raw("bus.no-line") : line.name()));
        secret.listLine(p);   // the 선글라스 남자's coordinate, while his final runs
        Component dogRow = lostDog.row(p);   // 바둑아 어디 가: where the dog ran off, where the grandfather waits
        if (dogRow != null) p.sendMessage(dogRow);
        if (n == 0) {
            forWatchers(x -> x.riderRows(p, 0).forEach(p::sendMessage));
            p.sendMessage(Lang.mm("bus.list-empty"));
            return;
        }
        long now = System.currentTimeMillis();
        int i = 1;
        for (Rider x : r.riders) {
            BusStop s = stops.get(x.dest);
            double left = timeLeft(x, now);
            p.sendMessage(Lang.mm("bus.list-row", "i", i++, "type", typeTag(x.type), "dest", x.secret || s == null ? "???" : s.name(),
                    "km", s == null ? "" : km(Math.hypot(s.x() - p.getX(), s.z() - p.getZ())), "left", clock(left), "mood", (int) Math.round(x.mood)));
        }
        forWatchers(x -> x.riderRows(p, n).forEach(p::sendMessage));
    }

    public void showStops(Player p) {
        List<String> lines = listLines(p.getLocation());
        if (lines.isEmpty()) {
            p.sendMessage(Lang.msg("bus.stops-none"));
            return;
        }
        p.sendMessage(Lang.mm("bus.stops-head", "n", lines.size()));
        for (String l : lines.subList(0, Math.min(10, lines.size()))) p.sendMessage(dev.stsv.core.util.Text.mm(l));
    }

    // ------------------------------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------------------------------

    /** A passenger left behind (the server stopped while a chunk held one): gone. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) {
            if ((x instanceof Mannequin || x instanceof org.bukkit.entity.Wolf) && x.getPersistentDataContainer().has(GUEST)) x.remove();
            // a route map whose stop is gone (removed while its place was not loaded)
            Integer board = x.getPersistentDataContainer().get(StopBoard.BOARD, PersistentDataType.INTEGER);
            if (board != null && !stops.isEmpty() && !stops.containsKey(board)) x.remove();
        }
    }

    /** No snow on a bay or in a shelter. */
    @EventHandler(ignoreCancelled = true)
    public void onSnow(BlockFormEvent e) {
        if (e.getNewState().getType() != org.bukkit.Material.SNOW) return;
        Block b = e.getBlock();
        for (BusStop s : stops.values()) {
            if (!s.world().equals(b.getWorld().getName()) || Math.abs(b.getY() - s.y()) > 5) continue;
            double[] l = s.local(b.getX() + 0.5, b.getZ() + 0.5);
            if (Math.abs(l[0]) <= spec.stops().bayLength() / 2.0 + 1 && l[1] >= BusStop.ROAD_HALF - 0.5 && l[1] <= s.shelterOut(spec.stops().bayWidth()) + 3.5) {
                e.setCancelled(true);
                return;
            }
        }
    }

    String typeTag(BusSpec.Type t) {
        return "<" + t.color() + ">[" + t.name() + "]</" + t.color() + ">";
    }

    static String km(double blocks) {
        if (!Double.isFinite(blocks)) return "-";
        return blocks >= 1000 ? String.format("%.1fkm", blocks / 1000) : Math.round(blocks) + "m";
    }

    static String clock(double secs) {
        if (secs < 0) return "<red>지각</red>";
        int s = (int) secs;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
