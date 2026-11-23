package dev.stsv.economy.bus;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 바둑아 어디 가 (README "마을버스" 이벤트, bus.yml {@code events.lost-dog}): now and then the 강아지 할아버지 boards alone, his
 * dog 바둑이 having run off at another stop of the driver's line. When the driver's bus really pulls into that stop, 바둑이
 * sits whimpering 10–25 blocks away on dry ground (only that driver sees and hears it; a faint trail of paw prints leads
 * there). Right-clicked, it follows the driver and hops onto the bus. The grandfather (still aboard, or waiting where he
 * got off for the rest of the day) takes it back: money, one line of old neighbourhood talk (a hint about the 제사 secret
 * while that is unsolved), +1 towards the achievement. Everything lives in memory (a restart or the driver leaving ends
 * it silently); only the day's count, the total and the lines already heard are saved (eco.kv).
 */
final class LostDog implements Listener {

    static final NamespacedKey DOG = new NamespacedKey("economy", "bus_lost_dog");
    static final String STAT = "bus_dog_found";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // ------------------------------------------------------------------------------------------
    // bus.yml events.lost-dog, and the pure parts (unit-tested)
    // ------------------------------------------------------------------------------------------

    /**
     * `chance`: of a 강아지 할아버지 (`type`) boarding without his dog; `perDay`: rewards a driver a (Korean) day; `rewardMin`
     * ..`rewardMax` G; the dog sits `distMin`..`distMax` blocks from the stop; he laments every `lamentEvery` seconds; the
     * dog whimpers every `whimperMin`..`whimperMax` seconds; things only the driver sees show within `showRange` blocks;
     * the bus waits for a driver fetching the dog `holdSeconds` (within `holdRange` blocks); the dog follows at
     * `followSpeed` blocks a second; `secret`: while this hidden secret is on and unsolved he speaks `hints`, else `memories`.
     */
    record Cfg(boolean enabled, String type, String name, double chance, int perDay, long rewardMin, long rewardMax, double distMin,
               double distMax, int lamentEvery, int whimperMin, int whimperMax, double showRange, int holdSeconds, double holdRange,
               double followSpeed, String secret, List<String> laments, List<String> near, List<String> waiting, List<String> bark,
               List<String> reunion, List<String> hints, List<String> memories) {

        static Cfg parse(ConfigurationSection root) {
            ConfigurationSection e = root == null ? null : root.getConfigurationSection("events.lost-dog");
            if (e == null) e = new org.bukkit.configuration.MemoryConfiguration();
            long[] reward = pair(e, "reward", 3000, 8000);
            long[] dist = pair(e, "distance", 10, 25);
            long[] whimper = pair(e, "whimper-every", 6, 14);
            return new Cfg(e.getBoolean("enabled", true), e.getString("type", "dogwalker"), e.getString("name", "바둑이"),
                    Math.clamp(e.getDouble("chance", 0.10), 0, 1), Math.max(0, e.getInt("per-day", 3)), Math.max(0, reward[0]),
                    Math.max(Math.max(0, reward[0]), reward[1]), Math.max(4, dist[0]), Math.max(Math.max(4, dist[0]), dist[1]),
                    Math.max(10, e.getInt("lament-every", 45)), (int) Math.max(2, whimper[0]), (int) Math.max(Math.max(2, whimper[0]), whimper[1]),
                    Math.max(16, e.getDouble("show-range", 48)), Math.max(0, e.getInt("hold-seconds", 120)), Math.max(8, e.getDouble("hold-range", 48)),
                    Math.clamp(e.getDouble("follow-speed", 6), 1, 12), e.getString("secret", "jesa"),
                    lines(e, "laments", "…아이고 기사 양반, 우리 바둑이가 {stop}에서 줄을 끊고 달아났어…"),
                    lines(e, "near", "…저기, 저 소리 우리 바둑이 아녀?"), lines(e, "waiting", "…바둑아, 어디 갔냐…"),
                    lines(e, "bark", "멍!"), lines(e, "reunion", "아이고 바둑아! 할애비가 얼마나 찾았는 줄 아냐"),
                    List.copyOf(e.getStringList("hints")), List.copyOf(e.getStringList("memories")));
        }

        private static long[] pair(ConfigurationSection e, String key, long lo, long hi) {
            if (e.isList(key)) {
                List<Long> n = e.getLongList(key);
                if (!n.isEmpty()) return new long[]{n.getFirst(), n.getLast()};
            } else if (e.contains(key)) {
                return new long[]{e.getLong(key), e.getLong(key)};
            }
            return new long[]{lo, hi};
        }

        private static List<String> lines(ConfigurationSection e, String key, String def) {
            List<String> l = e.getStringList(key);
            return l.isEmpty() ? List.of(def) : List.copyOf(l);
        }

        /** What a reunion pays by a roll in 0..1 (whole G, both ends possible). */
        long reward(double roll) {
            return rewardMin + (long) Math.floor(Math.clamp(roll, 0, 0.999999) * (rewardMax - rewardMin + 1));
        }
    }

    /**
     * The stop the dog ran off at: one of the driver's line other than where the grandfather got on (`board`) and where he
     * goes (`dest`): of those still `ahead` of the bus when there are any (found on the way, or just past his stop), else
     * of the whole line (`all`: the bus must turn round); with nothing else on the line his own stop; -1 when the line has
     * no stop but the one he got on at. The same for the same seed.
     */
    static int pickStop(List<Integer> ahead, List<Integer> all, int board, int dest, long seed) {
        List<Integer> c = others(ahead, board, dest);
        if (c.isEmpty()) c = others(all, board, dest);
        if (c.isEmpty()) return dest != board && dest >= 0 && all.contains(dest) ? dest : -1;
        return c.get(new Random(seed).nextInt(c.size()));
    }

    private static List<Integer> others(List<Integer> stops, int board, int dest) {
        Set<Integer> out = new LinkedHashSet<>(stops);
        out.remove(board);
        out.remove(dest);
        return new ArrayList<>(out);
    }

    /** Which of `size` lines he says: one not `heard` yet while there is one, else any. -1 for an empty pool. */
    static int pickLine(int size, Set<Integer> heard, long seed) {
        if (size <= 0) return -1;
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < size; i++) if (!heard.contains(i)) fresh.add(i);
        Random r = new Random(seed);
        return fresh.isEmpty() ? r.nextInt(size) : fresh.get(r.nextInt(fresh.size()));
    }

    /** The lines heard once `picked` was said: when all had been heard before, the round starts again with it. */
    static Set<Integer> heardAfter(int size, Set<Integer> heard, int picked) {
        Set<Integer> out = new TreeSet<>();
        for (int i : heard) if (i >= 0 && i < size) out.add(i);
        if (out.size() >= size) out.clear();
        if (picked >= 0 && picked < size) out.add(picked);
        return out;
    }

    static Set<Integer> parseHeard(String s) {
        Set<Integer> out = new TreeSet<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            try {
                if (!p.isBlank()) out.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
                // a damaged entry: as if not heard
            }
        }
        return out;
    }

    static String formatHeard(Set<Integer> heard) {
        StringBuilder b = new StringBuilder();
        for (int i : new TreeSet<>(heard)) b.append(b.isEmpty() ? "" : ",").append(i);
        return b.toString();
    }

    /** Rewards already given on `today`, from what is saved ("2026-11-23:2"); another day's count is 0. */
    static int rewardsOn(String stored, String today) {
        if (stored == null) return 0;
        String[] p = stored.split(":");
        if (p.length != 2 || !p[0].equals(today)) return 0;
        try {
            return Math.max(0, Integer.parseInt(p[1]));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether a driver with this saved count may still be rewarded today. */
    static boolean capLeft(String stored, String today, int perDay) {
        return rewardsOn(stored, today) < perDay;
    }

    /** The saved count after one more reward today. */
    static String counted(String stored, String today) {
        return today + ":" + (rewardsOn(stored, today) + 1);
    }

    /**
     * 공정 운행 (ServiceGate's rules): the bus pulling into the dog's stop counts only when the driver really drove a bus
     * `driven` blocks since the grandfather got on: at least `minDrive`, and `roadShare` of the `road` between the two stops.
     */
    static boolean honest(double driven, double road, ServiceGate.Rules r) {
        double need = Math.max(r.minDrive(), Double.isFinite(road) ? road * r.roadShare() : 0);
        return driven + 1e-9 >= need;
    }

    // ------------------------------------------------------------------------------------------
    // State (in memory)
    // ------------------------------------------------------------------------------------------

    private static final int SEEK = 0, SIT = 1, FOLLOW = 2;

    static final class Case {
        final UUID driver;
        final boolean forced;
        final int board, dest, target;
        final BusSpec.Type type;
        final String skin;
        final LocalDate day = LocalDate.now(KST);
        final double droveAt;
        /** the grandfather aboard (null once he is off: then he waits at `waitStop`) and the bus he rides */
        BusModule.Rider rider;
        long busId;
        int waitStop = -1;
        long waitFrom;
        Mannequin waiter;
        int phase = SEEK;
        Wolf dog;
        Location sit;
        List<double[]> trail = List.of();
        /** on a seat of this bus (while it drives), aboard it at all */
        boolean seated, aboard;
        long seatBus;
        long lamentAt = System.currentTimeMillis(), whimperAt, n;

        Case(UUID driver, boolean forced, int board, int dest, int target, BusSpec.Type type, String skin, double droveAt) {
            this.driver = driver;
            this.forced = forced;
            this.board = board;
            this.dest = dest;
            this.target = target;
            this.type = type;
            this.skin = skin;
            this.droveAt = droveAt;
        }
    }

    private final BusModule bus;
    private final EcoPlugin eco;
    private final STSVPlugin core;
    private Cfg cfg;
    private final Map<UUID, Case> cases = new HashMap<>();
    /** driver -> blocks they really drove a bus (since they came on) */
    private final Map<UUID, double[]> odo = new HashMap<>();
    /** drivers whose next 강아지 할아버지 comes without the dog (an admin's test) */
    private final Set<UUID> forcedNext = new HashSet<>();
    /** what a reunion still shows (removed when its little scene ends, or the module stops) */
    private final Set<Entity> scene = new HashSet<>();

    LostDog(BusModule bus, EcoPlugin eco, STSVPlugin core) {
        this.bus = bus;
        this.eco = eco;
        this.core = core;
        load();
    }

    void load() {
        cfg = Cfg.parse(eco.data("bus.yml"));
    }

    Cfg cfg() {
        return cfg;
    }

    void stop() {
        for (Case c : new ArrayList<>(cases.values())) end(c);
        for (Entity e : scene) e.remove();
        scene.clear();
        forcedNext.clear();
        odo.clear();
    }

    private static String today() {
        return LocalDate.now(KST).toString();
    }

    private double driven(UUID u) {
        double[] d = odo.get(u);
        return d == null ? 0 : d[0];
    }

    /** BusModule, every tick a bus is driven: how far (ServiceGate.Odometer's honest step). */
    void drove(UUID driver, double blocks) {
        if (blocks > 0) odo.computeIfAbsent(driver, k -> new double[1])[0] += blocks;
    }

    // ------------------------------------------------------------------------------------------
    // He boards alone
    // ------------------------------------------------------------------------------------------

    /**
     * A 강아지 할아버지 just sat down on this driver's bus (before his dog is seated): true when 바둑이 is lost this time (no
     * dog beside him; the event runs).
     */
    boolean boarding(Player p, BusModule.Run run, BusModule.Rider r, BusModule.Waiter w, BusStop here, BusLine line) {
        UUID u = p.getUniqueId();
        if (!cfg.enabled() || !r.type.id().equals(cfg.type()) || cases.containsKey(u)) return false;
        if (w.team != 0 || w.special != null || line == null) return false;
        if (bus.secretAgent() != null && bus.secretAgent().onJob(u)) return false;   // not with the 선글라스 남자 aboard
        boolean forced = forcedNext.contains(u);
        if (!forced) {
            if (!capLeft(eco.kv().get(dayKey(u), ""), today(), cfg.perDay())) return false;
            if (ThreadLocalRandom.current().nextDouble() >= cfg.chance()) return false;
        }
        return start(p, run, r, line, forced, w.face);
    }

    private boolean start(Player p, BusModule.Run run, BusModule.Rider r, BusLine line, boolean forced, String face) {
        UUID u = p.getUniqueId();
        List<Integer> all = new ArrayList<>(line.stops());
        all.removeIf(id -> !bus.stopMap().containsKey(id));
        List<Integer> ahead = line.has(r.from) ? new ArrayList<>(line.ahead(line.at(r.from), run.dir)) : new ArrayList<>();
        ahead.retainAll(all);
        long seed = u.getMostSignificantBits() ^ u.getLeastSignificantBits() ^ (LocalDate.now(KST).toEpochDay() * 1_000_003L)
                ^ ((long) r.from << 20) ^ ((long) r.dest << 8) ^ rewardsOn(eco.kv().get(dayKey(u), ""), today());
        int target = pickStop(ahead, all, r.from, r.dest, seed);
        if (target < 0) return false;
        forcedNext.remove(u);
        String skin = null;
        for (String s : r.type.skins()) {
            String key = s.endsWith(" slim") ? s.substring(0, s.length() - 5).trim() : s.trim();
            if (face != null && key.endsWith("/" + face)) skin = s;
        }
        if (skin == null) skin = BusModule.pickSkin(r.type, false);
        Case c = new Case(u, forced, r.from, r.dest, target, r.type, skin, driven(u));
        c.rider = r;
        c.busId = run.id;
        cases.put(u, c);
        p.sendMessage(Lang.mm("bus.dog-lost", "type", bus.typeTag(r.type), "name", cfg.name(), "stop", stopName(target)));
        p.playSound(p.getLocation(), Sound.ENTITY_WOLF_WHINE, 0.6f, 1.3f);
        return true;
    }

    private String stopName(int id) {
        BusStop s = bus.stopMap().get(id);
        return s == null ? "?" : s.name();
    }

    private static String pick(List<String> lines) {
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    /**
     * What this passenger says instead of his kind's usual lines (those are all about the dog beside him): a lament with
     * the stop's name while 바둑이 is lost, or null (not him: the usual lines).
     */
    String lamentFor(Entity m) {
        for (Case c : cases.values()) {
            if (c.rider == null || !c.rider.m.equals(m)) continue;
            c.lamentAt = System.currentTimeMillis();
            return lament(c);
        }
        return null;
    }

    private String lament(Case c) {
        return c.phase == SEEK ? pick(cfg.laments()).replace("{stop}", stopName(c.target)) : pick(cfg.near());
    }

    // ------------------------------------------------------------------------------------------
    // The bus pulls into a stop
    // ------------------------------------------------------------------------------------------

    /** BusModule: this driver's bus just stopped in a stop's bay. */
    void arrived(Player p, BusModule.Run run, BusStop here) {
        Case c = cases.get(p.getUniqueId());
        if (c == null) return;
        if (c.phase == SEEK && here.id() == c.target) {
            BusModule.Net net = bus.net(here.world());
            double road = net == null ? Double.NaN : net.road(c.board, c.target);
            if (!honest(driven(c.driver) - c.droveAt, road, bus.spec().fair())) return;   // not really driven here: nothing yet
            World w = Bukkit.getWorld(here.world());
            Location sit = w == null ? null : findSpot(w, here, c);
            if (sit == null) return;   // no dry, free ground just now: tried again the next time the bus pulls in
            c.sit = sit;
            c.trail = trail(w, here, sit);
            c.phase = SIT;
            c.dog = spawnDog(p, sit, true);
            c.whimperAt = System.currentTimeMillis() + 1500;
            p.sendMessage(Lang.mm("bus.dog-near", "name", cfg.name()));
            p.playSound(sit, Sound.ENTITY_WOLF_WHINE, 1f, 1.5f);
            if (c.rider != null && c.rider.m.isValid()) bus.sayLine(run, c.rider, List.of(pick(cfg.near())));
            return;
        }
        if (c.phase == FOLLOW && c.rider == null && here.id() == c.waitStop) {
            if (c.waiter == null || !c.waiter.isValid()) c.waiter = spawnWaiter(p, c, here);
            if (c.waiter != null) reunion(p, c, run, false);
        }
    }

    /**
     * Where the dog sits: a spot `distMin`..`distMax` blocks from the stop (the same tries for the same driver and stop),
     * off the road, the bay and the shelter, on dry ground (DryGround: it and the columns beside it), not in a player's
     * land. None of those: right behind the shelter, if that is dry. Else null.
     */
    private Location findSpot(World w, BusStop s, Case c) {
        DryGround.Probe probe = DryGround.world(w);
        double clear = s.shelterOut(bus.spec().stops().bayWidth()) + 4;
        Random r = new Random(c.driver.getMostSignificantBits() ^ c.driver.getLeastSignificantBits() ^ (s.id() * 7919L));
        for (int i = 0; i < 64; i++) {
            double ang = r.nextDouble() * Math.PI * 2, rad = cfg.distMin() + r.nextDouble() * (cfg.distMax() - cfg.distMin());
            int x = (int) Math.floor(s.x() + Math.cos(ang) * rad), z = (int) Math.floor(s.z() + Math.sin(ang) * rad);
            if (Math.abs(s.local(x + 0.5, z + 0.5)[1]) < clear) continue;
            Location at = spotAt(w, probe, s, x, z, true);
            if (at != null) return at;
        }
        for (int along = -6; along <= 6; along += 3) {
            for (int out = 2; out <= 4; out++) {
                double[] p = s.at(along, s.shelterOut(bus.spec().stops().bayWidth()) + out);
                Location at = spotAt(w, probe, s, (int) Math.floor(p[0]), (int) Math.floor(p[1]), false);
                if (at != null) return at;
            }
        }
        return null;
    }

    private Location spotAt(World w, DryGround.Probe probe, BusStop s, int x, int z, boolean wide) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return null;
        if (eco.land() != null && eco.land().playerLand(w.getName(), x, z)) return null;
        int y = wide ? DryGround.spot(probe, x, z, s.y() + 8, s.y() - 8) : DryGround.ground(probe, x, z, s.y() + 3, s.y() - 3);
        if (!DryGround.found(y)) return null;
        return new Location(w, x + 0.5, y, z + 0.5, BusModule.yawTo(new Location(w, x + 0.5, y, z + 0.5), s.x(), s.z()), 0);
    }

    /** Paw prints from the stop's sign towards the dog: a point every 1.2 blocks, on the ground where there is some. */
    private List<double[]> trail(World w, BusStop s, Location sit) {
        int f = s.side() >= 0 ? 1 : -1;
        double out0 = s.shelterOut(bus.spec().stops().bayWidth());
        double[] from = s.bay() ? s.at(f * 3.6, out0 - 0.5) : s.at(f * 2.2, out0 + 0.2);
        double fy = s.y() + 1, dx = sit.getX() - from[0], dz = sit.getZ() - from[1], len = Math.hypot(dx, dz);
        List<double[]> out = new ArrayList<>();
        if (len < 1) return out;
        double px = -dz / len, pz = dx / len;   // prints left and right of the line in turn
        int n = (int) (len / 1.2);
        for (int i = 1; i < n; i++) {
            double t = i / (double) n, side = (i % 2 == 0 ? 0.14 : -0.14);
            double x = from[0] + dx * t + px * side, z = from[1] + dz * t + pz * side, y = fy + (sit.getY() - fy) * t;
            Double ground = footY(w, x, z, y);
            out.add(new double[]{x, (ground == null ? y : ground) + 0.06, z});
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Every 2 ticks
    // ------------------------------------------------------------------------------------------

    void tick() {
        if (cases.isEmpty()) return;
        for (Case c : new ArrayList<>(cases.values())) {
            Player p = Bukkit.getPlayer(c.driver);
            if (p == null) {
                end(c);
                continue;
            }
            try {
                if (c.n++ % 10 == 0 && !upkeep(p, c)) continue;
                if (c.phase == FOLLOW) follow(p, c);
            } catch (RuntimeException e) {
                eco.getLogger().log(java.util.logging.Level.WARNING, "[버스] 바둑이 오류 (이벤트를 끝냄)", e);
                end(c);
            }
        }
    }

    /** Whether the grandfather still rides the bus he got on, with this driver. */
    private boolean riding(Case c) {
        if (c.rider == null) return false;
        BusModule.Run run = bus.runMap().get(c.busId);
        return run != null && c.driver.equals(run.driver) && run.riders.contains(c.rider) && c.rider.m.isValid();
    }

    /** Once a second. False when the event ended. */
    private boolean upkeep(Player p, Case c) {
        long now = System.currentTimeMillis();
        Map<Integer, BusStop> stops = bus.stopMap();
        if (!LocalDate.now(KST).equals(c.day) || (c.phase == SEEK && !stops.containsKey(c.target))) {
            end(c);   // the day is over, or the stop he named is gone: silently over
            return false;
        }
        if (c.rider != null && !riding(c)) {   // he is off: at a stop, or put off (the bus left alone, crashed out of the run)
            BusModule.Run run = bus.runMap().get(c.busId);
            int at = run != null && c.driver.equals(run.driver) && run.atStop != null ? run.atStop : c.dest;
            if (!stops.containsKey(at)) at = c.dest;
            if (!stops.containsKey(at)) at = c.board;
            c.rider = null;
            c.waitStop = at;
            c.waitFrom = now + 3000;
            if (stops.containsKey(at)) p.sendMessage(Lang.mm("bus.dog-waiting", "type", bus.typeTag(c.type), "name", cfg.name(), "stop", stopName(at)));
        }
        if (c.rider == null && !stops.containsKey(c.waitStop)) {
            end(c);
            return false;
        }
        if (c.rider != null) {
            if (c.phase != FOLLOW && now - c.lamentAt >= cfg.lamentEvery() * 1000L) {
                c.lamentAt = now;
                bus.sayLine(bus.runMap().get(c.busId), c.rider, List.of(lament(c)));
            }
        } else {
            waiter(p, c, stops.get(c.waitStop), now);
        }
        if (c.phase == SIT) sitting(p, c, now);
        return true;
    }

    /** The grandfather by the sign of the stop he got off at (only the driver sees him), while the driver is near. */
    private void waiter(Player p, Case c, BusStop s, long now) {
        boolean sameWorld = p.getWorld().getName().equals(s.world());
        double d = sameWorld ? Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) : Double.MAX_VALUE;
        if (c.waiter != null && (!c.waiter.isValid() || d > cfg.showRange() + 16)) {
            c.waiter.remove();
            c.waiter = null;
        }
        if (c.waiter == null && d <= cfg.showRange() && now >= c.waitFrom) c.waiter = spawnWaiter(p, c, s);
        if (c.waiter == null) return;
        Location at = c.waiter.getLocation();
        if (at.distanceSquared(p.getLocation()) < 20 * 20) c.waiter.setRotation(BusModule.yawTo(at, p.getX(), p.getZ()), 0);
        if (c.phase != FOLLOW && at.distanceSquared(p.getLocation()) < 12 * 12 && now - c.lamentAt >= cfg.lamentEvery() * 1000L) {
            c.lamentAt = now;
            bus.bubble(c.waiter, pick(cfg.waiting()), 60L, p);
        }
    }

    private Mannequin spawnWaiter(Player p, Case c, BusStop s) {
        World w = Bukkit.getWorld(s.world());
        if (w == null) return null;
        int f = s.side() >= 0 ? 1 : -1;
        double out0 = s.shelterOut(bus.spec().stops().bayWidth());
        double[] at = s.bay() ? s.at(f * 2.4, out0 - 0.5) : s.at(f * 1.4, out0 + 0.2);
        Location loc = new Location(w, at[0], s.y() + 1, at[1], s.facingRoad(), 0);
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return null;
        Mannequin m = bus.spawnGuest(loc, c.type, c.skin, x -> {
            x.setVisibleByDefault(false);
            x.customName(Text.mm(bus.typeTag(c.type)));
            x.setCustomNameVisible(true);
            x.setDescription(Lang.mm("bus.dog-waiter-tag", "name", cfg.name()));
        });
        p.showEntity(eco, m);
        return m;
    }

    /** 바둑이 sitting near the stop: there while the driver is near (its chunk may have gone), whimpering, the paw prints. */
    private void sitting(Player p, Case c, long now) {
        boolean sameWorld = p.getWorld().equals(c.sit.getWorld());
        double d = sameWorld ? p.getLocation().distance(c.sit) : Double.MAX_VALUE;
        if (c.dog != null && (!c.dog.isValid() || d > cfg.showRange() + 16)) {
            c.dog.remove();
            c.dog = null;
        }
        if (d > cfg.showRange()) return;
        if (c.dog == null) c.dog = spawnDog(p, c.sit, true);
        if (c.dog == null) return;
        if (now >= c.whimperAt) {
            c.whimperAt = now + 1000L * ThreadLocalRandom.current().nextInt(cfg.whimperMin(), cfg.whimperMax() + 1);
            p.playSound(c.sit, ThreadLocalRandom.current().nextBoolean() ? Sound.ENTITY_WOLF_WHINE : Sound.ENTITY_WOLF_AMBIENT, 1.2f, 1.6f);
        }
        // a few prints at a time, running from the stop towards the dog
        Particle.DustOptions paw = new Particle.DustOptions(Color.fromRGB(0x9a7b5c), 0.55f);
        int k = (int) (c.n / 10 * 2 % Math.max(1, c.trail.size() + 4));
        for (int i = Math.max(0, k - 3); i <= k && i < c.trail.size(); i++) {
            double[] t = c.trail.get(i);
            if (Math.abs(t[0] - p.getX()) > 40 || Math.abs(t[2] - p.getZ()) > 40) continue;
            p.spawnParticle(Particle.DUST, t[0], t[1], t[2], 2, 0.05, 0, 0.05, 0, paw);
        }
    }

    private Wolf spawnDog(Player p, Location at, boolean sitting) {
        World w = at.getWorld();
        if (w == null || !w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        Wolf dog = w.spawn(at, Wolf.class, x -> {
            x.setPersistent(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setAI(false);
            x.setCollidable(false);
            x.setRemoveWhenFarAway(false);
            x.setBaby();
            x.setAgeLock(true);
            x.setSitting(sitting);
            x.setVisibleByDefault(false);
            x.customName(Text.mm("<#d9b38c>" + cfg.name()));
            x.setCustomNameVisible(true);
            x.getPersistentDataContainer().set(BusModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            x.getPersistentDataContainer().set(DOG, PersistentDataType.BYTE, (byte) 1);
        });
        p.showEntity(eco, dog);
        return dog;
    }

    // ------------------------------------------------------------------------------------------
    // It follows the driver, onto the bus
    // ------------------------------------------------------------------------------------------

    /** Right-click on 바둑이: it gets up and follows. Any other click on it does nothing (no bone, no lead, no name tag). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Wolf dog) || !dog.getPersistentDataContainer().has(DOG)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Case c = cases.get(p.getUniqueId());
        if (c == null || !dog.equals(c.dog) || c.phase != SIT) return;
        c.phase = FOLLOW;
        dog.setSitting(false);
        Location at = dog.getLocation();
        p.playSound(at, Sound.ENTITY_WOLF_AMBIENT, 1f, 1.5f);
        p.spawnParticle(Particle.HEART, at.clone().add(0, 0.7, 0), 3, 0.2, 0.15, 0.2);
        bus.bubble(dog, pick(cfg.bark()), 40L, p);
        p.sendMessage(Lang.mm(c.rider != null ? "bus.dog-follow" : "bus.dog-follow-stop", "name", cfg.name(), "stop", stopName(c.waitStop)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        Case c = cases.get(u);
        if (c != null) end(c);
        forcedNext.remove(u);
        odo.remove(u);
    }

    private void follow(Player p, Case c) {
        VehicleModule vm = core.vehicles();
        VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
        boolean driving = d != null && bus.spec().vehicles().contains(d.itemKey());
        if (c.dog == null || !c.dog.isValid()) {   // its chunk went away: back beside the driver
            c.seated = false;
            c.dog = spawnDog(p, beside(p, 0.9), false);
            if (c.dog == null) return;
        }
        if (driving) {
            BusModule.Run run = bus.runMap().get(d.id());
            if (c.rider != null && run != null && run.riders.contains(c.rider) && c.rider.m.isValid()) {
                reunion(p, c, run, true);
                return;
            }
            if (!c.aboard) {
                c.aboard = true;
                p.playSound(p.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 0.9f, 1.6f);
                p.sendMessage(Lang.mm("bus.dog-aboard", "name", cfg.name(), "stop", stopName(c.waitStop)));
            }
            // while the bus drives it has a free seat (if there is one); at a stop it gets down, so it never takes a passenger's
            boolean atStop = run == null || run.atStop != null;
            if (c.seated && (atStop || c.seatBus != d.id())) unseat(c);
            if (!c.seated && !atStop && Math.abs(d.speed()) > 0.5) {
                List<Integer> free = vm.freeSeats(d.id());
                if (!free.isEmpty() && vm.seatGuest(d.id(), free.getLast(), c.dog)) {
                    c.seated = true;
                    c.seatBus = d.id();
                }
            }
            if (!c.seated) c.dog.teleport(beside(p, 0.8));
            return;
        }
        if (c.seated) unseat(c);
        c.aboard = false;
        Location at = c.dog.getLocation(), to = p.getLocation();
        if (!at.getWorld().equals(to.getWorld()) || at.distanceSquared(to) > 24 * 24) {
            c.dog.teleport(beside(p, 0.9));
            return;
        }
        if (c.rider == null && c.waiter != null && c.waiter.isValid() && c.waiter.getWorld().equals(to.getWorld())
                && (c.waiter.getLocation().distanceSquared(to) < 16 || c.waiter.getLocation().distanceSquared(at) < 9)) {
            reunion(p, c, null, false);
            return;
        }
        double dx = to.getX() - at.getX(), dz = to.getZ() - at.getZ(), len = Math.hypot(dx, dz);
        float yaw = BusModule.yawTo(at, to.getX(), to.getZ());
        if (len <= 2.2) {
            if (Math.abs(to.getY() - at.getY()) > 3) c.dog.teleport(beside(p, 0.9));
            else c.dog.setRotation(yaw, 0);
            return;
        }
        // a small hop towards the driver (no path finding: over a wall or water it simply comes to their side)
        double step = Math.min(len - 1.8, cfg.followSpeed() / 10.0);
        double x = at.getX() + dx / len * step, z = at.getZ() + dz / len * step;
        Double y = footY(at.getWorld(), x, z, at.getY());
        if (y == null || Math.abs(y - to.getY()) > 6) {
            c.dog.teleport(beside(p, 0.9));
            return;
        }
        c.dog.teleport(new Location(at.getWorld(), x, y + (c.n % 4 < 2 ? 0.28 : 0), z, yaw, 0));
    }

    private void unseat(Case c) {
        c.seated = false;
        VehicleModule vm = core.vehicles();
        if (c.dog == null) return;
        if (vm == null || !vm.unseatGuest(c.seatBus, c.dog)) c.dog.leaveVehicle();
    }

    /** A spot at the player's right hand (on the ground there when they stand on some). */
    private static Location beside(Player p, double out) {
        Location l = p.getLocation();
        double r = Math.toRadians(l.getYaw());
        Location to = new Location(l.getWorld(), l.getX() - Math.cos(r) * out, l.getY(), l.getZ() - Math.sin(r) * out, l.getYaw(), 0);
        if (!p.isInsideVehicle()) {
            Double y = footY(l.getWorld(), to.getX(), to.getZ(), l.getY());
            if (y == null) return l.clone();
            to.setY(y);
        }
        return to;
    }

    /** Where something stands in this column near height `near` (within 3 blocks: dry, on something solid), or null. */
    static Double footY(World w, double x, double z, double near) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(near + 0.01);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
            int y = by + dy;
            if (y <= w.getMinHeight() || y >= w.getMaxHeight()) continue;
            Block below = w.getBlockAt(bx, y - 1, bz), at = w.getBlockAt(bx, y, bz);
            if (!below.isSolid() || !at.isPassable() || at.isLiquid()) continue;
            double top = 0;
            for (var bb : below.getCollisionShape().getBoundingBoxes()) top = Math.max(top, bb.getMaxY());
            return below.getY() + (top <= 0 ? 1 : top);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Giving back
    // ------------------------------------------------------------------------------------------

    /**
     * 바둑이 is back with the grandfather (`aboard`: he rides the driver's bus; else he waited at a stop): his cry, the dog
     * hopping about with hearts, the reward, then one line of old talk. The event is over at once (paid once).
     */
    private void reunion(Player p, Case c, BusModule.Run run, boolean aboard) {
        cases.remove(c.driver);
        if (c.seated) unseat(c);
        Wolf dog = c.dog;
        BusModule.Rider rider = aboard ? c.rider : null;
        Mannequin who = aboard ? c.rider.m : c.waiter;
        if (dog != null) scene.add(dog);
        if (!aboard && who != null) scene.add(who);
        String cry = pick(cfg.reunion());
        if (aboard) bus.sayLine(run, rider, List.of(cry));
        else if (who != null && who.isValid()) bus.bubble(who, cry, 70L, p);
        Location base = null;
        if (dog != null && dog.isValid()) {
            if (aboard) base = beside(p, 0.8);
            else if (who != null && who.isValid()) {
                Location wl = who.getLocation();
                double r = Math.toRadians(wl.getYaw());
                base = new Location(wl.getWorld(), wl.getX() - Math.cos(r) * 0.9, wl.getY(), wl.getZ() - Math.sin(r) * 0.9,
                        BusModule.yawTo(wl, wl.getX() + Math.cos(r), wl.getZ() + Math.sin(r)), 0);
            } else base = dog.getLocation();
            dog.teleport(base);
            p.playSound(base, Sound.ENTITY_WOLF_AMBIENT, 1f, 1.6f);
        }
        pay(p, c, run);
        String hint = hint(c.driver);
        Location hop = base;
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                i++;
                boolean on = p.isOnline();
                if (on && dog != null && dog.isValid() && hop != null && i <= 60) {   // wagging: small hops, hearts
                    if (i % 3 == 0) dog.teleport(hop.clone().add(0, i % 6 == 0 ? 0.32 : 0, 0));
                    if (i % 8 == 0) p.spawnParticle(Particle.HEART, dog.getLocation().add(0, 0.7, 0), 1, 0.25, 0.15, 0.25);
                    if (i == 24) p.playSound(hop, Sound.ENTITY_WOLF_PANT, 1f, 1.5f);
                }
                if (on && i == 64 && hint != null) {   // his old talk: a bubble, and a copy in the chat to read again
                    if (aboard && rider.m.isValid()) bus.sayLine(runOf(rider), rider, List.of(hint));
                    else if (!aboard && who != null && who.isValid()) bus.bubble(who, hint, 110L, p);
                    p.sendMessage(Lang.mm("bus.dog-hint", "type", bus.typeTag(c.type),
                            "line", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(hint)));
                }
                if (aboard && i == 62) {   // on the bus: 바둑이 is his dog beside him again, for everyone to see
                    removeScene(dog, null);
                    BusModule.Run now = runOf(rider);
                    if (now != null && rider.m.isValid()) bus.seatDog(now.id, rider);
                }
                if (!on || i >= (aboard ? 66 : 180)) {
                    cancel();
                    removeScene(dog, on ? p : null);
                    if (!aboard) removeScene(who, on ? p : null);
                }
            }
        }.runTaskTimer(eco, 1L, 1L);
    }

    private BusModule.Run runOf(BusModule.Rider r) {
        for (BusModule.Run run : bus.runMap().values()) if (run.riders.contains(r)) return run;
        return null;
    }

    private void removeScene(Entity e, Player poofFor) {
        if (e == null) return;
        scene.remove(e);
        if (e.isValid() && poofFor != null) poofFor.spawnParticle(Particle.POOF, e.getLocation().add(0, 0.6, 0), 5, 0.2, 0.3, 0.2, 0.01);
        e.remove();
    }

    private String dayKey(UUID u) {
        return "bus.dog.day." + u;
    }

    /** The reward: money (like the bus's event rewards: a rented bus keeps its share), a gift of his if his kind has any, +1. */
    private void pay(Player p, Case c, BusModule.Run run) {
        UUID u = c.driver;
        String today = today(), stored = eco.kv().get(dayKey(u), "");
        int n = rewardsOn(stored, today);
        if (!c.forced && n >= cfg.perDay()) {   // (only when per-day was lowered meanwhile) thanks, no more today
            p.sendMessage(Lang.mm("bus.dog-reunion-cap", "name", cfg.name(), "max", cfg.perDay()));
            return;
        }
        eco.kv().set(dayKey(u), counted(stored, today));   // counted before it pays: never twice
        long total = eco.kv().getLong("bus.dog.found." + u, 0) + 1;
        eco.kv().set("bus.dog.found." + u, String.valueOf(total));
        long[] paid = bus.bonus(p, run, cfg.reward(ThreadLocalRandom.current().nextDouble()), "bus_lost_dog");
        if (core.profiles() != null) core.profiles().add(u, STAT, 1);
        p.sendMessage(Lang.mm("bus.dog-reunion", "name", cfg.name(), "g", BusModule.g(paid[0]), "n", n + 1, "max", cfg.perDay(),
                "count", total, "fee", paid[1] > 0 ? Lang.raw("bus.bonus-fee", "fee", BusModule.g(paid[1])) : ""));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        if (!c.type.gifts().isEmpty()) {
            BusSpec.Gift g = c.type.gifts().get(ThreadLocalRandom.current().nextInt(c.type.gifts().size()));
            dev.stsv.economy.util.Items.create(g.item(), g.amount()).ifPresent(item -> {
                for (org.bukkit.inventory.ItemStack left : p.getInventory().addItem(item).values()) p.getWorld().dropItem(p.getLocation(), left);
                p.sendMessage(Lang.mm("bus.gift", "type", bus.typeTag(c.type), "item", item.effectiveName() == null ? g.item()
                        : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(item.effectiveName()), "n", g.amount()));
            });
        }
    }

    /** Whether the hidden secret his old talk hints at is switched on and still unsolved. */
    private boolean secretOpen() {
        var secrets = eco.secrets();
        if (secrets == null || cfg.secret().isBlank()) return false;
        return secrets.find(cfg.secret()).map(s -> secrets.enabled(s) && !secrets.closed(s)).orElse(false);
    }

    /** One line of his old neighbourhood talk: a hint while the secret is open, else a plain memory; unheard ones first. */
    private String hint(UUID u) {
        boolean open = secretOpen() && !cfg.hints().isEmpty();
        List<String> pool = open ? cfg.hints() : cfg.memories();
        if (pool.isEmpty()) return null;
        String key = "bus.dog." + (open ? "hints" : "memories") + "." + u;
        Set<Integer> heard = parseHeard(eco.kv().get(key, ""));
        int i = pickLine(pool.size(), heard, ThreadLocalRandom.current().nextLong());
        eco.kv().set(key, formatHeard(heardAfter(pool.size(), heard, i)));
        return pool.get(i);
    }

    /** Over without a word: everything only the driver saw is taken away. The grandfather aboard rides on as he is. */
    private void end(Case c) {
        cases.remove(c.driver);
        if (c.seated) unseat(c);
        if (c.dog != null) c.dog.remove();
        if (c.waiter != null) c.waiter.remove();
        c.dog = null;
        c.waiter = null;
    }

    // ------------------------------------------------------------------------------------------
    // For BusModule: the bus waits, /버스, the admin's test
    // ------------------------------------------------------------------------------------------

    /**
     * Whether a bus left by its driver for `staleTicks` keeps its passengers a while longer: the grandfather is aboard,
     * 바둑이 has shown up, and the driver is out fetching it nearby (hold-seconds, hold-range).
     */
    boolean holds(BusModule.Run run, long staleTicks) {
        Case c = run.driver == null ? null : cases.get(run.driver);
        if (c == null || c.rider == null || c.phase == SEEK || run.id != c.busId || !run.riders.contains(c.rider)) return false;
        if (staleTicks > cfg.holdSeconds() * 20L || !c.rider.m.isValid()) return false;
        Player p = Bukkit.getPlayer(c.driver);
        if (p == null || !p.getWorld().equals(c.rider.m.getWorld())) return false;
        return p.getLocation().distanceSquared(c.rider.m.getLocation()) <= cfg.holdRange() * cfg.holdRange();
    }

    /** A row for the driver's /버스: where 바둑이 ran off, or where the grandfather waits. Null: nothing going. */
    net.kyori.adventure.text.Component row(Player p) {
        Case c = cases.get(p.getUniqueId());
        if (c == null) return null;
        if (c.phase == SEEK) return Lang.mm("bus.dog-row-seek", "name", cfg.name(), "stop", stopName(c.target));
        if (c.phase == SIT) return Lang.mm("bus.dog-row-sit", "name", cfg.name(), "stop", stopName(c.target));
        return c.rider != null ? Lang.mm("bus.dog-row-bus", "name", cfg.name()) : Lang.mm("bus.dog-row-stop", "name", cfg.name(), "stop", stopName(c.waitStop));
    }

    /** One line for an admin: this player's event right now, today's count, the total, the lines heard. */
    String state(UUID u) {
        Case c = cases.get(u);
        String now;
        if (c == null) now = forcedNext.contains(u) ? "없음 (다음 할아버지는 강아지 없이 탐)" : "없음";
        else {
            BusModule.Net net = null;
            BusStop t = bus.stopMap().get(c.target);
            if (t != null) net = bus.net(t.world());
            double road = net == null ? Double.NaN : net.road(c.board, c.target);
            double need = Math.max(bus.spec().fair().minDrive(), Double.isFinite(road) ? road * bus.spec().fair().roadShare() : 0);
            now = (c.phase == SEEK ? "찾는 중" : c.phase == SIT ? "정류장 근처에 앉아 있음" : c.aboard ? "버스에 탐" : "따라오는 중")
                    + (c.forced ? " (강제)" : "") + " · 달아난 곳 <white>" + stopName(c.target) + "</white> · 할아버지 <white>"
                    + (c.rider != null ? "탑승 중" : stopName(c.waitStop) + "에서 기다림") + "</white> · 운전 <white>"
                    + Math.round(driven(u) - c.droveAt) + "/" + Math.round(need) + "칸</white>"
                    + (c.sit == null ? "" : " · 자리 <white>" + c.sit.getBlockX() + " " + c.sit.getBlockY() + " " + c.sit.getBlockZ() + "</white>");
        }
        return "<gold>" + cfg.name() + " <gray>" + (cfg.enabled() ? "" : "<red>(꺼짐)</red> ") + "진행 <white>" + now + "</white> · 오늘 <white>"
                + rewardsOn(eco.kv().get(dayKey(u), ""), today()) + "/" + cfg.perDay() + "</white> · 모두 <white>"
                + eco.kv().getLong("bus.dog.found." + u, 0) + "번</white> · 들은 옛이야기 <white>"
                + parseHeard(eco.kv().get("bus.dog.hints." + u, "")).size() + "/" + cfg.hints().size() + "</white> (힌트 "
                + (secretOpen() ? "켜짐" : "꺼짐: 추억담 " + parseHeard(eco.kv().get("bus.dog.memories." + u, "")).size() + "/" + cfg.memories().size()) + ")";
    }

    /** /관리자 버스 바둑이 [플레이어] | 상태 [플레이어] | 끝 [플레이어] */
    void admin(CommandSender s, String[] args) {
        String sub = args.length > 1 && (args[1].equals("상태") || args[1].equals("끝")) ? args[1] : "";
        String who = sub.isEmpty() ? (args.length > 1 ? args[1] : null) : (args.length > 2 ? args[2] : null);
        Player p = who != null ? Bukkit.getPlayerExact(who) : s instanceof Player self ? self : null;
        if (p == null) {
            s.sendMessage(Text.msg(who == null ? "<red>/관리자 버스 바둑이 [플레이어] | 상태 [플레이어] | 끝 [플레이어]" : "<red>접속 중인 플레이어가 아니에요: " + who));
            return;
        }
        UUID u = p.getUniqueId();
        if (sub.equals("상태")) {
            s.sendMessage(Text.msg(state(u)));
            return;
        }
        if (sub.equals("끝")) {
            Case c = cases.get(u);
            if (c != null) end(c);
            forcedNext.remove(u);
            s.sendMessage(Text.msg("<green>" + p.getName() + ": " + cfg.name() + " 이벤트를 끝냈어요 (보상 없음)"));
            return;
        }
        if (cases.containsKey(u)) {
            s.sendMessage(Text.msg("<red>" + p.getName() + ": 이미 진행 중이에요 <gray>(/관리자 버스 바둑이 끝 " + p.getName() + ")"));
            s.sendMessage(Text.msg(state(u)));
            return;
        }
        // a 강아지 할아버지 aboard the bus they drive: his dog is gone right now
        for (BusModule.Run run : bus.runMap().values()) {
            if (!u.equals(run.driver)) continue;
            BusModule.Net net = bus.net(p.getWorld().getName());
            BusLine line = net == null ? null : net.line(run.line);
            for (BusModule.Rider r : run.riders) {
                if (r.secret || !r.type.id().equals(cfg.type()) || line == null) continue;
                bus.dropDog(run.id, r, false);
                if (start(p, run, r, line, true, r.face)) {
                    bus.sayLine(run, r, List.of(lamentFor(r.m)));
                    s.sendMessage(Text.msg("<green>" + p.getName() + ": 타고 있던 할아버지의 " + cfg.name() + "가 사라졌어요"));
                    s.sendMessage(Text.msg(state(u)));
                    return;
                }
                bus.seatDog(run.id, r);
            }
        }
        forcedNext.add(u);
        s.sendMessage(Text.msg("<green>" + p.getName() + ": 다음에 타는 " + cfg.type() + " 손님은 강아지 없이 타요 <gray>(확률 · 하루 횟수 무시, 접속을 끊으면 취소)"));
    }

    List<String> complete(String[] args) {
        if (args.length == 2) {
            List<String> out = new ArrayList<>(List.of("상태", "끝"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out.stream().filter(k -> k.startsWith(args[1])).toList();
        }
        if (args.length == 3 && (args[1].equals("상태") || args[1].equals("끝")))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.startsWith(args[2])).toList();
        return List.of();
    }
}
