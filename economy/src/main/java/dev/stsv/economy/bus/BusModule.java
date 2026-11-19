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

    record Net(BusRoutes routes, Map<Integer, Integer> index, double[][] dist) {
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
        final int dest;

        Waiter(Mannequin m, BusSpec.Type type, int dest) {
            this.m = m;
            this.type = type;
            this.dest = dest;
        }
    }

    static final class Shown {
        final List<Waiter> waiters = new ArrayList<>();
        long lastNear;
    }

    /** A passenger on a bus. */
    static final class Rider {
        final Mannequin m;
        final BusSpec.Type type;
        final int from, dest;
        final long boarded = System.currentTimeMillis();
        final double road;
        double mood;

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
        final java.util.Set<Integer> rang = new java.util.HashSet<>();
    }

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
        Bukkit.getScheduler().runTaskLater(eco, this::rebuildNets, 40L);   // after the roads have loaded
    }

    @Override
    public void reload() {
        load();
        rebuildNets();
    }

    @Override
    protected void stop() {
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
                    c.getInt("half"), c.getDouble("hx"), c.getDouble("hz", 1), c.getInt("side", 1), c.getInt("size", spec.stops().waitingMax()),
                    c.getBoolean("auto"), c.getBoolean("bay", true), c.getString("dock", "")));
            waiting.put(id, c.getDouble("waiting", 0));
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
                out.put(w, new Net(routes, index, routes.matrix(nodes)));
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
        BusStop[] tries = {s, flip(s), simple(s), simple(flip(s))};
        for (BusStop t : tries) {
            Map<Block, BlockData> plan = StopBuilder.plan(w, t, cfg, eco.roads().surfaceBlock());
            int[] bad = StopBuilder.blocked(plan, (x, z) -> eco.roads().guardAt(w.getName(), x, z) != null);
            if (bad != null || (t.bay() && StopBuilder.overVoid(w, t, cfg))) continue;
            try {
                builder.build(w, t, plan, cfg);
            } catch (IOException e) {
                return "<red>되돌리기 기록을 쓰지 못해 짓지 않았어요: " + e.getMessage();
            }
            add(t);
            return "<green>정류장 <white>" + t.name() + "</white> 설치 (" + (t.bay() ? "버스 베이" : "간이 정류장") + ", "
                    + (t.side() > 0 ? "오른쪽" : "왼쪽") + ", 손님 최대 " + t.size() + "명)";
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

    public String rename(String name, String to) {
        BusStop s = stop(name);
        if (s == null) return "<red>없는 정류장: " + name;
        if (stop(to) != null) return "<red>같은 이름의 정류장이 있어요: " + to;
        BusStop n = s.named(to);
        stops.put(s.id(), n);
        saveStops();
        World w = Bukkit.getWorld(s.world());
        if (w != null) builder.sign(w, n, (int) Math.floor(waiting.getOrDefault(s.id(), 0.0)), spec.stops());
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
            double[] p = routes.place(sp.node());
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                String n = name;
                for (int i = 2; stop(n) != null; i++) n = name + " " + i;
                BusStop s = new BusStop(nextId, n, w.getName(), p[0], p[1], (int) p[2], p[3], p[4], 1, spec.stops().waitingMax(), true, true, sp.dock());
                w.getChunkAtAsync((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4).thenRun(() -> {
                    String r = place(w, s);
                    eco.getLogger().info("[버스] 자동 정류장: " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(dev.stsv.core.util.Text.mm(r)));
                });
            }, delay);
            delay += 10;
        }
        return "<green>정류장 " + spots.size() + "곳을 차례로 지어요 (콘솔에 하나씩 기록)";
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
            boolean cut = n == null || !n.connected(s.id());
            String km = me != null && s.world().equals(me.getWorld().getName()) ? km(Math.hypot(s.x() - me.getX(), s.z() - me.getZ())) : s.world();
            out.add(Lang.raw("bus.stops-row", "name", s.name(), "km", km, "waiting", (int) Math.floor(waiting.getOrDefault(s.id(), 0.0)),
                    "cut", cut ? Lang.raw("bus.stops-cut") : ""));
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
        for (Rider r : run.riders) {
            double d = by * r.type.mood() * (jolt ? r.type.jolt() : 1);
            r.mood = Math.clamp(r.mood + d, 0, 100);
            r.m.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, r.m.getLocation().add(0, 2.1, 0), 1);
        }
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
            if (run.atStop != null) run.atStop = null;
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
            if (ticks - run.joltAt > 60 && !run.riders.isEmpty()) for (Rider r : run.riders) r.mood = Math.min(100, r.mood + spec.mood().smoothStop());
            return;
        }
        if (run.busy > 0) {
            run.busy -= 5;
            return;
        }
        // off first
        for (Iterator<Rider> it = run.riders.iterator(); it.hasNext(); ) {
            Rider r = it.next();
            if (r.dest != here.id()) continue;
            it.remove();
            vm.unseatGuest(d.id(), r.m);
            pay(p, r, here);
            Mannequin m = r.m;
            m.setDescription(Component.empty());
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (m.isValid()) {
                    m.getWorld().spawnParticle(Particle.POOF, m.getLocation().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0.01);
                    m.remove();
                }
            }, 50L);
            run.busy = (int) (cfg.boardTicks() * r.type.slow());
            return;
        }
        // then on
        Shown sh = shown.get(here.id());
        if (sh == null || sh.waiters.isEmpty()) return;
        List<Integer> free = vm.freeSeats(d.id());
        if (free.isEmpty()) return;
        Waiter w = sh.waiters.removeFirst();
        if (!w.m.isValid() || !vm.seatGuest(d.id(), free.getFirst(), w.m)) {
            w.m.remove();
            return;
        }
        waiting.merge(here.id(), -1.0, Double::sum);
        Net n = nets.get(here.world());
        double road = n == null ? 0 : n.road(here.id(), w.dest);
        Rider r = new Rider(w.m, w.type, here.id(), w.dest, Double.isFinite(road) ? road : 0, spec.mood().start());
        run.riders.add(r);
        BusStop dest = stops.get(w.dest);
        String destName = dest == null ? "?" : dest.name();
        HudModule.flash(p, Lang.mm("bus.boarded", "type", typeTag(w.type), "dest", destName, "km", km(r.road)), 2000);
        if (w.type.announce()) Bukkit.broadcast(Lang.msg("bus.vip", "player", p.getName(), "dest", destName));
        run.busy = (int) (cfg.boardTicks() * w.type.slow());
        remember(p.getUniqueId(), here.id(), w.dest);
    }

    private void pay(Player p, Rider r, BusStop at) {
        BusSpec.Fare f = spec.fare();
        double secs = (System.currentTimeMillis() - r.boarded) / 1000.0;
        boolean onTime = secs <= spec.onTime(r.road);
        double base = spec.fare(r.road, r.type) * spec.moodFactor(r.mood);
        double tip = onTime && r.mood >= r.type.tipMood() ? spec.fare(r.road, r.type) * f.tip() * r.type.tip() : 0;
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
        String curve = rate < 100 ? Lang.raw("bus.fare-curve", "rate", rate) : "";
        p.sendMessage(Lang.mm("bus.fare", "fare", String.format("%,d", paid), "type", typeTag(r.type), "mood", (int) Math.round(r.mood),
                "tip", tipText, "curve", curve));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        if (r.mood >= 90) r.m.getWorld().spawnParticle(Particle.HEART, r.m.getLocation().add(0, 2.1, 0), 2, 0.3, 0.2, 0.3);
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
            if (n == null || !n.connected(s.id())) continue;
            waiting.merge(s.id(), minutes * rate, (a, b) -> Math.min(s.size(), a + b));
        }
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
                }
            } else if (!sh.waiters.isEmpty() && now - sh.lastNear > spec.stops().hideAfter() * 1000L) {
                hide(s.id());
            }
            int signN = (int) Math.floor(waiting.getOrDefault(s.id(), 0.0));
            Integer had = signShown.get(s.id());
            if ((had == null || had != signN) && w.isChunkLoaded((int) Math.floor(s.x()) >> 4, (int) Math.floor(s.z()) >> 4)) {
                builder.sign(w, s, signN, spec.stops());
                signShown.put(s.id(), signN);
            }
        }
        // riders: the time left on their tag, a late office worker sulks
        for (Run r : runs.values()) {
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

    /** The driver's line: the next stop (the nearest any passenger gets off at), how far, how many get off; else the nearest stop. */
    private void hud(Player p, Run r) {
        BusStop next = null;
        double best = Double.MAX_VALUE;
        for (Rider x : r.riders) {
            BusStop s = stops.get(x.dest);
            if (s == null || !s.world().equals(p.getWorld().getName())) continue;
            double d = Math.hypot(s.x() - p.getX(), s.z() - p.getZ());
            if (d < best) {
                best = d;
                next = s;
            }
        }
        if (next != null) {
            int off = 0;
            for (Rider x : r.riders) if (x.dest == next.id()) off++;
            if (best < 150 && r.rang.add(next.id())) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);   // 띵동
                Bukkit.getScheduler().runTaskLater(eco, () -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.95f), 4L);
                HudModule.flash(p, Lang.mm("bus.bell", "stop", next.name(), "n", off), 3000);
                return;
            }
            HudModule.flash(p, Lang.mm("bus.hud", "stop", next.name(), "km", km(best), "off", off, "riders", r.riders.size(),
                    "seats", r.seats), 1500);
            return;
        }
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

    private Waiter spawnWaiter(World w, BusStop s, int i, int n, UUID driver) {
        Net net = nets.get(s.world());
        if (net == null) return null;
        int hour = ZonedDateTime.now(KST).getHour();
        BusSpec.Type type = pickType(spec.rush(hour));
        Integer dest = pickDest(net, s, type, driver);
        if (type == null || dest == null) return null;
        double[] spot = s.waitingSpot(i, Math.max(n, s.size()), spec.stops().bayWidth());
        Location at = new Location(w, spot[0], s.y() + 1, spot[1], s.facingRoad(), 0);
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        BusStop to = stops.get(dest);
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
            x.setDescription(dev.stsv.core.util.Text.mm("<gray>→ <white>" + (to == null ? "?" : to.name()) + "</white> · "
                    + km(net.road(s.id(), dest))));
            if (!type.skins().isEmpty()) {
                String skin = type.skins().get(ThreadLocalRandom.current().nextInt(type.skins().size()));
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
        return new Waiter(m, type, dest);
    }

    private BusSpec.Type pickType(boolean rush) {
        double total = 0;
        for (BusSpec.Type t : spec.types().values()) total += t.weight() * (rush ? t.rush() : 1);
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (BusSpec.Type t : spec.types().values()) {
            roll -= t.weight() * (rush ? t.rush() : 1);
            if (roll <= 0) return t;
        }
        return spec.types().values().stream().findFirst().orElse(null);
    }

    /** Where a passenger goes: another stop of the network, near ones more often (far ones only for some kinds), less of a pair driven over and over. */
    Integer pickDest(Net net, BusStop from, BusSpec.Type type, UUID driver) {
        List<Integer> ids = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        for (BusStop s : stops.values()) {
            if (s.id() == from.id() || !s.world().equals(from.world())) continue;
            double d = net.road(from.id(), s.id());
            if (!Double.isFinite(d)) continue;
            if (type != null && type.far() > 0 && d < type.far()) continue;
            double w = type != null && type.far() > 0 ? 1 : 1 / (1 + d / 800);
            if (recent(driver, from.id(), s.id()) >= spec.fare().repeatFree()) w *= spec.fare().repeatFactor();
            ids.add(s.id());
            ws.add(w);
        }
        if (ids.isEmpty() && type != null && type.far() > 0) return pickDest(net, from, null, driver);
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
        p.sendMessage(Lang.mm("bus.list-head", "n", n, "seats", d.seats() - 1, "today", String.format("%,d", earned)));
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
        if (secs < 0) return "<red>늦음</red>";
        int s = (int) secs;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
