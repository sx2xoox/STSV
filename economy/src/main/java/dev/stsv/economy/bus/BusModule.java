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

        Waiter(Mannequin m, BusSpec.Type type, String face) {
            this.m = m;
            this.type = type;
            this.face = face;
        }
    }

    /** Someone queued to wait at a stop: a transfer passenger (their line and stop) or one of a picnic's students. */
    record Forced(String type, Integer line, Integer dest, boolean transfer) {}

    static final class Shown {
        final List<Waiter> waiters = new ArrayList<>();
        long lastNear;
    }

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
        UUID driver;
        long seen;
        final List<Rider> riders = new ArrayList<>();
        final double[] speeds = new double[64];
        float lastYaw = Float.NaN;
        long joltAt = -1000, bumpAt = -1000, turnAt = -1000, pullInAt = -1000;
        Integer atStop;
        int busy, seats;
        /** the line it runs (its number, 0 = none yet) and which way (+1 towards the dock, -1 towards the terminal) */
        int line, dir = 1;
        long noLineAt = -1000, talkAt = -1000;
        String itemKey = "";
        Integer leftStop;
        final java.util.Set<Integer> rang = new java.util.HashSet<>();
    }

    /** stop id -> its traits (mountain, sea ...: worked out from around it, saved with the stops) */
    private final Map<Integer, java.util.Set<String>> stopTags = new HashMap<>();
    /** stop id -> who is queued to wait there next (transfers, a picnic) */
    private final Map<Integer, Deque<Forced>> forced = new HashMap<>();
    private int picnicDay = -1, marketSaidDay = -1;

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
        every(1L, this::tick);
        every(20L, this::second);
        core.hud().overlay("eco-bus", this::overlayRows);
        Bukkit.getScheduler().runTaskLater(eco, this::rebuildNets, 40L);   // after the roads have loaded
    }

    @Override
    public void reload() {
        load();
        rebuildNets();
    }

    @Override
    protected void stop() {
        if (core != null && core.hud() != null) core.hud().removeOverlay("eco-bus");
        for (Run r : runs.values()) for (Rider x : r.riders) x.m.remove();
        runs.clear();
        for (Shown s : shown.values()) for (Waiter w : s.waiters) w.m.remove();
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

    public BusStop stop(String name) {
        for (BusStop s : stops.values()) if (s.name().equals(name)) return s;
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
                        Integer k = nodes[i] < 0 ? null : at.get(nodes[i]);
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
                nets.clear();
                nets.putAll(out);
            });
        });
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
        if (stop(name) != null) return "<red>같은 이름의 정류장이 있어요: " + name;
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
        Map<Block, BlockData> pa = StopBuilder.plan(w, a, cfg, eco.roads().surfaceBlock()), pb = StopBuilder.plan(w, b, cfg, eco.roads().surfaceBlock());
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
            Map<Block, BlockData> plan = StopBuilder.plan(w, t, cfg, eco.roads().surfaceBlock());
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
    public String remove(String name) {
        BusStop s = stop(name);
        if (s == null) return "<red>없는 정류장: " + name;
        World w = Bukkit.getWorld(s.world());
        hide(s.id());
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
    public String rebuild(String name) {
        BusStop s = stop(name);
        if (s == null) return "<red>없는 정류장: " + name;
        World w = Bukkit.getWorld(s.world());
        if (w == null) return "<red>월드가 없어요: " + s.world();
        hide(s.id());
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

    public String rename(String name, String to) {
        BusStop s = stop(name);
        if (s == null) return "<red>없는 정류장: " + name;
        if (stop(to) != null) return "<red>같은 이름의 정류장이 있어요: " + to;
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
        for (BusStop t : new BusStop[]{s, flip(s)}) {
            if (StopBuilder.siteProblem(w, t, spec.stops()) != null) continue;
            if (StopBuilder.digCost(StopBuilder.plan(w, t, spec.stops(), eco.roads().surfaceBlock())) <= MAX_DIG) return true;
        }
        return false;
    }

    /** Moves a stop to the road point nearest this spot (on the side the spot is): its blocks back, then built there. */
    public String move(String name, Location at) {
        BusStop s = stop(name);
        if (s == null) return "<red>없는 정류장: " + name;
        if (!s.world().equals(at.getWorld().getName())) return "<red>정류장과 같은 월드에서 쓰세요";
        BusRoutes routes = routesNow(at.getWorld().getName());
        int node = routes.attach(at.getX(), at.getZ());
        if (node < 0) return "<red>국도에서 " + (int) BusRoutes.ATTACH + "칸 안에 서서 쓰세요";
        hide(s.id());
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
    public String addWaiting(String name, int n) {
        BusStop s = stop(name);
        if (s == null) return "<red>없는 정류장: " + name;
        waiting.put(s.id(), (double) Math.max(0, Math.min(s.size(), n)));
        return "<green>" + name + ": 기다리는 손님 " + Math.min(s.size(), n) + "명";
    }

    public List<String> listLines(Location me) {
        List<String> out = new ArrayList<>();
        List<BusStop> all = new ArrayList<>(stops.values());
        if (me != null) all.sort(Comparator.comparingDouble(s -> s.world().equals(me.getWorld().getName()) ? Math.hypot(s.x() - me.getX(), s.z() - me.getZ()) : 1e12));
        for (BusStop s : all) {
            Net n = nets.get(s.world());
            String nos = lineNumbers(s);
            boolean cut = nos.isEmpty();
            String km = me != null && s.world().equals(me.getWorld().getName()) ? km(Math.hypot(s.x() - me.getX(), s.z() - me.getZ())) : s.world();
            out.add(Lang.raw("bus.stops-row", "name", s.name(), "km", km, "waiting", (int) Math.floor(waiting.getOrDefault(s.id(), 0.0)),
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
            Run run = runs.computeIfAbsent(d.id(), k -> new Run());
            run.driver = p.getUniqueId();
            run.seen = ticks;
            run.seats = d.seats() - 1;
            run.itemKey = d.itemKey();
            feel(p, run, d);
            if (ticks % 5 == 0) atStops(p, run, d, vm);
        }
        // a bus whose driver got out (or left): its passengers go off to find another
        for (Iterator<Map.Entry<Long, Run>> it = runs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Run> e = it.next();
            Run r = e.getValue();
            if (ticks - r.seen < 60) continue;
            int n = 0;
            for (Rider x : r.riders) {
                vm.unseatGuest(e.getKey(), x.m);
                x.m.remove();
                n++;
            }
            Player p = Bukkit.getPlayer(r.driver);
            if (n > 0 && p != null) p.sendMessage(Lang.msg("bus.lost", "n", n));
            it.remove();
        }
    }

    /** Jolts, bumps and sharp turns: every rider's mood. */
    private void feel(Player p, Run run, VehicleModule.Driven d) {
        BusSpec.Mood m = spec.mood();
        double speed = Math.abs(d.speed());
        run.speeds[(int) (ticks % run.speeds.length)] = speed;
        if (run.riders.isEmpty()) {
            run.lastYaw = d.yaw();
            return;
        }
        if (d.bumped() && ticks - run.bumpAt > m.cooldown()) {
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
        for (Rider r : run.riders) {
            double d = by * r.type.mood() * (jolt ? r.type.jolt() : 1) * (turn ? r.type.turnFactor() : 1);
            r.mood = Math.clamp(r.mood + d, 0, 100);
            r.m.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, r.m.getLocation().add(0, 2.1, 0), 1);
            if (r.dozing && !turn) {   // a jolt wakes a sleeper
                r.dozing = false;
                r.sleepThrough = false;
            }
        }
        Rider r = someone(run);
        if (r != null) talk(run, r.m, r.type, jolt ? "jolt" : turn ? "turn" : "bump", true);
        HudModule.flash(p, Lang.mm(key, "d", (int) Math.round(by)), 1500);
    }

    /** At a stop (stopped in its bay): passengers get off, then on, one at a time. */
    private void atStops(Player p, Run run, VehicleModule.Driven d, VehicleModule vm) {
        BusSpec.Stops cfg = spec.stops();
        BusStop here = null;
        boolean still = Math.abs(d.speed()) < 0.5;
        for (BusStop s : stops.values()) {
            if (!s.world().equals(d.world().getName())) continue;
            if (still && s.standing(d.x(), d.z(), cfg.stopReach(), cfg.bayWidth())) {
                here = s;
                break;
            }
        }
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
            boolean smooth = ticks - run.joltAt > 60;
            if (smooth && !run.riders.isEmpty()) {
                for (Rider r : run.riders) r.mood = Math.min(100, r.mood + spec.mood().smoothStop());
                Rider r = someone(run);
                if (r != null && ThreadLocalRandom.current().nextDouble() < 0.4) talk(run, r.m, r.type, "smooth", false);
            }
            pickLine(p, run, d, here);
            HudModule.flash(p, Lang.mm("bus.arrive", "stop", here.name()), 2000);
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
            boolean off = (r.dest == here.id() && !r.sleepThrough) || r.dest == -1 || r.wanderAt == here.id();
            if (!off) continue;
            it.remove();
            vm.unseatGuest(d.id(), r.m);
            double road = r.dest == here.id() || line == null || !line.has(r.from) ? r.road : line.between(line.at(r.from), line.at(here.id()));
            pay(p, run, r, here, road);
            Mannequin m = r.m;
            m.setDescription(Component.empty());
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (m.isValid()) {
                    m.getWorld().spawnParticle(Particle.POOF, m.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.01);
                    m.remove();
                }
            }, 50L);
            maybeTransfer(n, line, here, r);
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
        for (Waiter x : sh.waiters) {
            if (!bus.takes(x.type)) {
                why = "bus.bus-only";
                continue;
            }
            if (x.forcedLine != null && x.forcedLine != line.number) continue;   // waiting for another line (a transfer)
            if (x.partner != null && free.size() < 2) continue;
            Integer to = x.forcedDest != null && line.ahead(line.at(here.id()), run.dir).contains(x.forcedDest) ? x.forcedDest
                    : x.forcedDest != null ? null : pickDest(line, here, x.type, p.getUniqueId(), run.dir, bus);
            if (to == null) {
                if (why == null) why = bus.minRoad() > 0 ? "bus.bus-far" : "bus.end-of-line";
                continue;
            }
            w = x;
            dest = to;
            break;
        }
        if (w == null) {
            if (why != null && ticks - run.noLineAt > 200) {
                run.noLineAt = ticks;
                HudModule.flash(p, Lang.mm(why, "line", line.name(), "km", km(bus.minRoad())), 3000);
            }
            return;
        }
        sh.waiters.remove(w);
        if (!board(p, run, d, vm, here, line, w, dest, free.getFirst())) return;
        if (w.partner != null && sh.waiters.remove(w.partner)) {
            List<Integer> left = vm.freeSeats(d.id());
            if (!left.isEmpty()) board(p, run, d, vm, here, line, w.partner, dest, left.getFirst());
            else w.partner.m.remove();
        }
        run.busy = (int) (cfg.boardTicks() * w.type.slow());
        remember(p.getUniqueId(), here.id(), dest);
    }

    /** One passenger gets on: seated, counted off the stop, told to the driver. False when they could not sit. */
    private boolean board(Player p, Run run, VehicleModule.Driven d, VehicleModule vm, BusStop here, BusLine line, Waiter w, int dest, int seat) {
        if (!w.m.isValid() || !vm.seatGuest(d.id(), seat, w.m)) {
            if (w.m.isValid()) eco.getLogger().warning("[버스] 손님을 " + seat + "번 자리에 앉히지 못함 (" + here.name() + ")");
            w.m.remove();
            return false;
        }
        waiting.merge(here.id(), -1.0, (a, b) -> Math.max(0, a + b));
        double road = line.between(line.at(here.id()), line.at(dest));
        Rider r = new Rider(w.m, w.type, here.id(), dest, road, spec.mood().start());
        r.face = w.face;
        r.transfer = w.transfer;
        run.riders.add(r);
        BusStop to = stops.get(dest);
        String destName = to == null ? "?" : to.name();
        if (w.transfer) {
            d.world().playSound(d.location(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 2f);
            HudModule.flash(p, Lang.mm("bus.transfer", "type", typeTag(w.type), "dest", destName), 2000);
        } else {
            HudModule.flash(p, Lang.mm("bus.boarded", "type", typeTag(w.type), "dest", destName, "km", km(r.road)), 2000);
        }
        talk(run, r.m, r.type, w.transfer ? "transfer" : "board", false);
        if (w.type.announce()) Bukkit.broadcast(Lang.msg("bus.vip", "player", p.getName(), "dest", destName));
        return true;
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
        BusLine pick = chosen != null && chosen.has(here.id()) ? chosen : cur != null && cur.has(here.id()) ? cur : ls.getFirst();
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

    private void pay(Player p, Run run, Rider r, BusStop at, double road) {
        BusSpec.Fare f = spec.fare();
        BusSpec.Extra x = spec.extra();
        BusSpec.Bus bus = spec.bus(run.itemKey);
        double secs = (System.currentTimeMillis() - r.boarded) / 1000.0;
        boolean onTime = secs <= spec.onTime(road);
        double fare = spec.fare(road, r.type) * bus.fare() * (r.transfer ? 1 + x.transferBonus() : 1);
        double base = fare * spec.moodFactor(r.mood) + r.type.luggage();
        boolean rain = p.getWorld().hasStorm();
        double tip = onTime && r.mood >= r.type.tipMood() ? fare * f.tip() * r.type.tip() * (rain ? 1 + x.rainTip() : 1) : 0;
        String dayKey = "bus.day." + p.getUniqueId();
        String today = eco.calendar().days().date(System.currentTimeMillis()).toString();
        String[] was = eco.kv().get(dayKey, "").split(":");
        double earned = was.length == 2 && was[0].equals(today) ? Double.parseDouble(was[1]) : 0;
        double total = base + tip;
        long paid = Math.round(spec.daily(earned, total));
        eco.kv().set(dayKey, today + ":" + (earned + paid));
        eco.kv().set("bus.riders." + p.getUniqueId(), eco.kv().getLong("bus.riders." + p.getUniqueId(), 0) + 1);
        if (paid > 0) core.economy().deposit(p.getUniqueId(), paid, "bus");
        int rate = total <= 0 ? 100 : (int) Math.round(100.0 * paid / Math.round(total));
        String tipText = tip > 0 ? Lang.raw("bus.fare-tip", "tip", Math.round(tip)) : onTime ? "" : Lang.raw("bus.fare-late");
        if (r.type.luggage() > 0) tipText += Lang.raw("bus.fare-luggage", "g", Math.round(r.type.luggage()));
        String curve = rate < 100 ? Lang.raw("bus.fare-curve", "rate", rate) : "";
        p.sendMessage(Lang.mm("bus.fare", "fare", String.format("%,d", paid), "type", typeTag(r.type), "mood", (int) Math.round(r.mood),
                "tip", tipText, "curve", curve));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        if (r.mood >= 90) r.m.getWorld().spawnParticle(Particle.HEART, r.m.getLocation().add(0, 2.1, 0), 2, 0.3, 0.2, 0.3);
        // a kind passenger leaves something for the driver (할머니: 식혜, 인절미 ...)
        if (!r.type.gifts().isEmpty() && r.mood >= x.giftMood() && ThreadLocalRandom.current().nextDouble() < x.giftChance()) {
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
    // Every second: waiting passengers, who is shown, the driver's screen
    // ------------------------------------------------------------------------------------------

    private void second() {
        long now = System.currentTimeMillis();
        double minutes = (now - lastRefill) / 60000.0;
        lastRefill = now;
        int hour = ZonedDateTime.now(KST).getHour();
        double rate = spec.hourFactor(hour) / Math.max(0.1, spec.stops().refillMinutes());
        for (BusStop s : stops.values()) {
            Net n = nets.get(s.world());
            if (n == null || n.linesOf(s.id()).isEmpty()) continue;
            waiting.merge(s.id(), minutes * rate, (a, b) -> a >= s.size() ? a : Math.min(s.size(), a + b));
        }
        dayEvents();
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
            sh.waiters.removeIf(x -> !x.m.isValid());
            if (nearDriver != null) {
                sh.lastNear = now;
                int want = (int) Math.floor(waiting.getOrDefault(s.id(), 0.0));
                while (sh.waiters.size() < want) {
                    Waiter x = spawnWaiter(w, s, sh.waiters.size(), want, nearDriver.getUniqueId());
                    if (x == null) break;
                    sh.waiters.add(x);
                    if (x.type.pair() && sh.waiters.size() < want + 1) {   // a couple: the other half beside them
                        Waiter y = spawnWaiter(w, s, sh.waiters.size(), want + 1, nearDriver.getUniqueId(), x.type);
                        if (y != null) {
                            x.partner = y;
                            y.partner = x;
                            sh.waiters.add(y);
                            waiting.merge(s.id(), 1.0, Double::sum);
                        }
                    }
                }
                // they look down the road at the bus coming
                for (Waiter x : sh.waiters) {
                    Location from = x.m.getLocation();
                    float yaw = (float) Math.toDegrees(Math.atan2(-(nearDriver.getX() - from.getX()), nearDriver.getZ() - from.getZ()));
                    x.m.setRotation(yaw, 0);
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
            if (comingBus(s, w) != null) nos += "\n<#ffd166><b>곧 도착</b>";
            int key = signN * 31 + nos.hashCode();
            Integer had = signShown.get(s.id());
            if ((had == null || had != key) && w.isChunkLoaded((int) Math.floor(s.x()) >> 4, (int) Math.floor(s.z()) >> 4)) {
                builder.sign(w, s, signN, nos, spec.stops());
                signShown.put(s.id(), key);
            }
        }
        // riders: the time left on their tag, a late office worker sulks
        for (Run r : runs.values()) {
            ride(r, now);
            for (Rider x : r.riders) {
                double left = spec.onTime(x.road) - (now - x.boarded) / 1000.0;
                if (left < 0 && x.type.late() > 0) x.mood = Math.max(0, x.mood - x.type.late() / 60.0);
                BusStop dest = stops.get(x.dest);
                x.m.setDescription(Lang.mm("bus.rider-tag", "dest", dest == null ? "?" : dest.name(), "left", clock(left),
                        "mood", (int) Math.round(x.mood)));
            }
            Player p = Bukkit.getPlayer(r.driver);
            if (p != null) hud(p, r);
        }
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
        double[] off = new double[1];
        int idx = line == null ? -1 : line.nearest(p.getX(), p.getZ(), off);
        if (off[0] > 40) line = null;   // off the line's road: nothing counts as passed
        BusSpec.Extra x = spec.extra();
        for (Rider r : run.riders) {
            double rode = (now - r.boarded) / 1000.0;
            if (!r.lateSaid && rode > spec.onTime(r.road)) {
                r.lateSaid = true;
                talk(run, r.m, r.type, "late", false);
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
                    HudModule.flash(p, Lang.mm("bus.missed", "type", typeTag(r.type)), 2500);
                }
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

    /** The driver's line: the next stop (the nearest any passenger gets off at), how far, how many get off; else the nearest stop. */
    private void hud(Player p, Run r) {
        Net n = nets.get(p.getWorld().getName());
        BusLine line = n == null ? null : n.line(r.line);
        if (line != null) {
            double[] off = new double[1];
            int idx = line.nearest(p.getX(), p.getZ(), off);
            if (off[0] > 40) {
                HudModule.flash(p, Lang.mm("bus.hud-off", "line", line.name()), 1500);
                return;
            }
            Integer nextId = line.next(idx, r.dir);
            BusStop next = nextId == null ? null : stops.get(nextId);
            String arrow = line.arrow(idx, r.dir, p.getX(), p.getZ(), p.getLocation().getYaw());
            BusLine.Turn turn = line.nextJunction(idx, r.dir, 150);
            String junction = turn == null ? "" : Lang.raw("bus.hud-junction", "m", Math.round(turn.distance()),
                    "turn", Lang.raw(turn.turn() < 0 ? "bus.turn-left" : turn.turn() > 0 ? "bus.turn-right" : "bus.turn-straight"));
            if (next == null) {
                HudModule.flash(p, Lang.mm("bus.hud-end", "line", line.name(), "arrow", arrow, "riders", r.riders.size(), "seats", r.seats), 1500);
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
            HudModule.flash(p, Lang.mm("bus.hud-line", "line", line.name(), "arrow", arrow, "stop", next.name(), "km", km(dist),
                    "off", offN > 0 ? Lang.raw("bus.hud-off-n", "n", offN) : "", "junction", junction, "riders", r.riders.size(),
                    "seats", r.seats), 1500);
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
        if (near == null || best > 3000) HudModule.flash(p, Lang.mm("bus.hud-none"), 1500);
        else HudModule.flash(p, Lang.mm("bus.hud-empty", "stop", near.name(), "km", km(best), "waiting",
                (int) Math.floor(waiting.getOrDefault(near.id(), 0.0))), 1500);
    }

    // ------------------------------------------------------------------------------------------
    // Talk bubbles, stop traits
    // ------------------------------------------------------------------------------------------

    /** A passenger says something (a bubble over their head for 3 seconds), at most one bubble a bus in talk-cooldown ticks. */
    private void talk(Run run, Mannequin m, BusSpec.Type type, String situation, boolean force) {
        if (m == null || !m.isValid()) return;
        if (!force && run != null && ticks - run.talkAt < spec.extra().talkCooldown()) return;
        List<String> lines = spec.lines(type, situation);
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

    private void say(Mannequin m, String text) {
        org.bukkit.entity.TextDisplay d = m.getWorld().spawn(m.getLocation().add(0, 2.3, 0), org.bukkit.entity.TextDisplay.class, t -> {
            t.setPersistent(false);
            t.text(dev.stsv.core.util.Text.mm("<black>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(text)));
            t.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            t.setBackgroundColor(org.bukkit.Color.fromARGB(230, 255, 255, 255));
            t.setViewRange(0.5f);
            t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.55f, 0), new org.joml.AxisAngle4f(),
                    new org.joml.Vector3f(0.7f, 0.7f, 0.7f), new org.joml.AxisAngle4f()));
            t.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
        });
        if (!m.addPassenger(d)) d.teleport(m.getLocation().add(0, 2.3, 0));
        Bukkit.getScheduler().runTaskLater(eco, d::remove, 60L);
    }

    /** A random rider of this bus (or null). */
    private Rider someone(Run run) {
        return run.riders.isEmpty() ? null : run.riders.get(ThreadLocalRandom.current().nextInt(run.riders.size()));
    }

    private java.util.Set<String> tagsOf(BusStop s) {
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
        if (line != null) order.sort(Comparator.comparingInt(x -> r.dir * line.at(x.dest)));
        long now = System.currentTimeMillis();
        List<dev.stsv.core.hud.HudLayout.OverlayRow> out = new ArrayList<>();
        for (Rider x : order) {
            BusStop s = stops.get(x.dest);
            double left = spec.onTime(x.road) - (now - x.boarded) / 1000.0;
            String mood = x.mood >= 90 ? "mood_great" : x.mood >= 70 ? "mood_good" : x.mood >= 40 ? "mood_meh" : "mood_bad";
            if (x.said != null && now < x.saidUntil) {
                out.add(new dev.stsv.core.hud.HudLayout.OverlayRow(x.face, mood, Lang.mm("bus.overlay-talk", "color", x.type.color(),
                        "type", x.type.name()), Lang.mm("bus.overlay-said",
                        "said", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(x.said))));
                continue;
            }
            out.add(new dev.stsv.core.hud.HudLayout.OverlayRow(x.face, mood, Lang.mm("bus.overlay-row", "color", x.type.color(),
                    "type", x.type.name(), "dest", x.dest == -1 ? Lang.raw("bus.next-stop") : s == null ? "?" : s.name(),
                    "left", clock(left) + (x.dozing ? Lang.raw("bus.dozing") : ""))));
        }
        return out;
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
        double[] spot = s.waitingSpot(i, Math.max(n, s.size()), spec.stops().bayWidth());
        Location at = new Location(w, spot[0], s.y() + 1, spot[1], s.facingRoad(), 0);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        String lineNos = lineNumbers(s);
        String skin;
        if (type.skins().isEmpty()) skin = null;
        else if (type.pair() && type.skins().size() > 1) {
            // a couple in matching clothes: the first the first look, the other half one of the rest
            skin = only == null ? type.skins().getFirst() : type.skins().get(1 + ThreadLocalRandom.current().nextInt(type.skins().size() - 1));
        } else skin = type.skins().get(ThreadLocalRandom.current().nextInt(type.skins().size()));
        Mannequin m = w.spawn(at, Mannequin.class, x -> {
            x.setPersistent(false);
            x.setInvulnerable(true);
            x.setGravity(false);
            x.setSilent(true);
            x.setImmovable(true);
            x.setCollidable(false);
            x.setRemoveWhenFarAway(false);
            x.getPersistentDataContainer().set(GUEST, PersistentDataType.BYTE, (byte) 1);
            x.customName(dev.stsv.core.util.Text.mm(typeTag(type)));
            x.setCustomNameVisible(true);
            x.setDescription(Lang.mm("bus.waiter-tag", "lines", lineNos));
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
        });
        String face = null;
        if (skin != null) {
            String key = skin.endsWith(" slim") ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
            face = key.substring(key.lastIndexOf('/') + 1);
        }
        Waiter out = new Waiter(m, type, face);
        if (f != null) {
            out.forcedLine = f.line();
            out.forcedDest = f.dest();
            out.transfer = f.transfer();
        }
        return out;
    }

    /** Who comes to wait at this stop now: by kind, its traits, the hour, a weekend, its market day. */
    private BusSpec.Type pickType(BusStop s) {
        ZonedDateTime now = ZonedDateTime.now(KST);
        int hour = now.getHour();
        boolean weekend = now.getDayOfWeek().getValue() >= 6, market = marketHere(s), rush = spec.rush(hour);
        java.util.Set<String> tags = tagsOf(s);
        double total = 0;
        Map<BusSpec.Type, Double> ws = new LinkedHashMap<>();
        for (BusSpec.Type t : spec.types().values()) {
            double w = t.weightAt(tags, hour, weekend, market, rush);
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

    /** Once a day: market day is told; a picnic's students come to one stop in the morning. */
    private void dayEvents() {
        int day = eco.calendar().day();
        if (spec.marketDay(day) && marketSaidDay != day) {
            marketSaidDay = day;
            List<String> names = new ArrayList<>();
            for (BusStop s : stops.values()) if (marketHere(s)) names.add(s.name());
            if (!names.isEmpty()) Bukkit.broadcast(Lang.msg("bus.market", "stops", String.join(", ", names.subList(0, Math.min(4, names.size())))
                    + (names.size() > 4 ? " 외 " + (names.size() - 4) + "곳" : "")));
        }
        int hour = ZonedDateTime.now(KST).getHour();
        BusSpec.Extra x = spec.extra();
        if (picnicDay != day && hour >= x.picnicFrom() && hour < x.picnicTo()) {
            List<BusStop> on = new ArrayList<>();
            for (BusStop s : stops.values()) {
                Net n = nets.get(s.world());
                if (n != null && !n.linesOf(s.id()).isEmpty()) on.add(s);
            }
            if (on.isEmpty() || !spec.types().containsKey("student")) return;
            picnicDay = day;
            BusStop s = on.get(Math.floorMod(Integer.hashCode(day * 31 + 7), on.size()));
            Deque<Forced> q = forced.computeIfAbsent(s.id(), k -> new ArrayDeque<>());
            for (int i = 0; i < x.picnicStudents(); i++) q.addLast(new Forced("student", null, null, false));
            waiting.merge(s.id(), (double) x.picnicStudents(), Double::sum);
            Bukkit.broadcast(Lang.msg("bus.picnic", "stop", s.name(), "n", x.picnicStudents()));
        }
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

    private void hide(int stop) {
        Shown sh = shown.remove(stop);
        if (sh != null) for (Waiter w : sh.waiters) w.m.remove();
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
        if (n == 0) {
            p.sendMessage(Lang.mm("bus.list-empty"));
            return;
        }
        long now = System.currentTimeMillis();
        int i = 1;
        for (Rider x : r.riders) {
            BusStop s = stops.get(x.dest);
            double left = spec.onTime(x.road) - (now - x.boarded) / 1000.0;
            p.sendMessage(Lang.mm("bus.list-row", "i", i++, "type", typeTag(x.type), "dest", s == null ? "?" : s.name(),
                    "km", s == null ? "" : km(Math.hypot(s.x() - p.getX(), s.z() - p.getZ())), "left", clock(left), "mood", (int) Math.round(x.mood)));
        }
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
        for (Entity x : e.getEntities()) if (x instanceof Mannequin && x.getPersistentDataContainer().has(GUEST)) x.remove();
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
