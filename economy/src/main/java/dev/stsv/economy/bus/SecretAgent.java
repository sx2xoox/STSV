package dev.stsv.economy.bus;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.core.vehicle.VehicleModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import dev.stsv.economy.util.Players;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 수상한 선글라스 남자 (docs/admin/버스_비밀_의뢰.md; bus.yml {@code secret}; not in the README on purpose). Once or twice a
 * day he waits at a random stop; the bus that picks him up gets one of five missions, each worth its own note (쪽지 1..5:
 * a line of the final riddle and a number). A driver who earned all five themselves may, at 03:33 at a stop at a line's
 * terminal end, honk three times: the final run to a closed stop no one else sees. After that, a weekly 비밀 의뢰.
 * The pure parts (mission choice, the 7-minute window, A-B-A, note ownership, the closed stop) are static and unit-tested.
 */
public final class SecretAgent implements Listener {

    static final NamespacedKey NOTE = new NamespacedKey("economy", "bus_note");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** missions 1..5 are the notes' ones; these two are his own */
    static final int FINAL = 6, WEEKLY = 7;

    // ------------------------------------------------------------------------------------------
    // Config (bus.yml secret) and the pure rules
    // ------------------------------------------------------------------------------------------

    public record Note(String name, String line, int number) {}

    public record Config(boolean enabled, String type, int from, int to, int minTimes, int maxTimes, int stay, int giveUp,
                         long tipMin, long tipMax, double allNotesTip, double agentTip, int windowFrom, int windowTo,
                         int finalHour, int finalMinute, int finalSpan, int honks, int honkWindow, double finalMin, double finalMax,
                         double slowSpeed, int slowLimit, long money, String vehicle, List<String> cosmetics, String title,
                         double weeklyMin, double weeklyMax, long weeklyRewardMin, long weeklyRewardMax, double weeklyChance,
                         List<Note> notes, Map<String, String> riddles, Map<String, List<String>> talk) {

        static Config parse(ConfigurationSection c) {
            if (c == null) c = new org.bukkit.configuration.MemoryConfiguration();
            ConfigurationSection f = sub(c, "final"), w = sub(c, "weekly");
            List<Integer> times = c.getIntegerList("times"), tip = c.getIntegerList("tip"), win = c.getIntegerList("window");
            List<Integer> fd = f.getIntegerList("distance"), wd = w.getIntegerList("distance"), wr = w.getIntegerList("reward");
            List<Note> notes = new ArrayList<>();
            for (Map<?, ?> m : c.getMapList("notes")) {
                notes.add(new Note(String.valueOf(m.get("name")), String.valueOf(m.get("line")), m.get("number") instanceof Number n ? n.intValue() : 0));
            }
            Map<String, String> riddles = new LinkedHashMap<>();
            ConfigurationSection rs = c.getConfigurationSection("riddles");
            if (rs != null) for (String k : rs.getKeys(false)) riddles.put(k, rs.getString(k, k));
            Map<String, List<String>> talk = new LinkedHashMap<>();
            ConfigurationSection ts = c.getConfigurationSection("talk");
            if (ts != null) for (String k : ts.getKeys(false)) talk.put(k, List.copyOf(ts.getStringList(k)));
            return new Config(c.getBoolean("enabled", true), c.getString("type", "shades"), c.getInt("from", 10), c.getInt("to", 23),
                    times.isEmpty() ? 1 : times.get(0), times.size() > 1 ? times.get(1) : times.isEmpty() ? 2 : times.get(0),
                    c.getInt("stay", 40), c.getInt("give-up", 15), tip.isEmpty() ? 1000 : tip.get(0), tip.size() > 1 ? tip.get(1) : 3000,
                    c.getDouble("all-notes-tip", 2), c.getDouble("agent-tip", 2), win.isEmpty() ? 420 : win.get(0), win.size() > 1 ? win.get(1) : 450,
                    f.getInt("hour", 3), f.getInt("minute", 33), Math.max(1, f.getInt("minutes", 2)), Math.max(1, f.getInt("honks", 3)),
                    Math.max(1, f.getInt("honk-window", 5)), fd.isEmpty() ? 1000 : fd.get(0), fd.size() > 1 ? fd.get(1) : 3000,
                    f.getDouble("slow-speed", 4), f.getInt("slow-limit", 15), f.getLong("money", 1_000_000), f.getString("vehicle", ""),
                    List.copyOf(f.getStringList("cosmetics")), f.getString("title", ""), wd.isEmpty() ? 1000 : wd.get(0),
                    wd.size() > 1 ? wd.get(1) : 2000, wr.isEmpty() ? 100_000 : wr.get(0), wr.size() > 1 ? wr.get(1) : 300_000,
                    w.getDouble("chance", 0.25), List.copyOf(notes), riddles, talk);
        }

        private static ConfigurationSection sub(ConfigurationSection c, String k) {
            ConfigurationSection s = c.getConfigurationSection(k);
            return s != null ? s : c.createSection(k);
        }

        /** One of his lines for this, its {placeholders} filled (or "……"). */
        String line(String key, double roll, String... kv) {
            List<String> l = talk.getOrDefault(key, List.of());
            String s = l.isEmpty() ? "……" : l.get(Math.min(l.size() - 1, (int) (roll * l.size())));
            for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
            return s;
        }
    }

    /**
     * The mission he gives a driver who earned these notes (of `count`): one whose note they lack (by the roll), else any
     * (then for a big tip only).
     */
    static int pickMission(Set<Integer> earned, int count, double roll) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 1; i <= count; i++) if (!earned.contains(i)) pool.add(i);
        if (pool.isEmpty()) for (int i = 1; i <= count; i++) pool.add(i);
        return pool.get(Math.min(pool.size() - 1, (int) (Math.max(0, roll) * pool.size())));
    }

    /** 정확히 7분: -1 too early, 0 in the window, 1 too late (seconds since he boarded). */
    static int window(double secs, int from, int to) {
        return secs < from ? -1 : secs <= to ? 0 : 1;
    }

    /**
     * 같은 곳, 세 번: the progress after the bus stopped at `stop` (0 none yet, 1 at A, 2 then at B, 3 done: A again;
     * -1 failed: another stop in between once it began). Stops before the first A do not count.
     */
    static int aba(int progress, int stop, int a, int b) {
        return switch (progress) {
            case 0 -> stop == a ? 1 : 0;
            case 1 -> stop == b ? 2 : stop == a ? 1 : -1;
            case 2 -> stop == a ? 3 : stop == b ? 2 : -1;
            default -> progress;
        };
    }

    /** The notes a player earned themselves (kv "1,3,5"). */
    static Set<Integer> parseNotes(String s) {
        Set<Integer> out = new TreeSet<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            try {
                if (!p.isBlank()) out.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited value
            }
        }
        return out;
    }

    static String formatNotes(Set<Integer> notes) {
        StringBuilder b = new StringBuilder();
        for (int n : new TreeSet<>(notes)) b.append(b.isEmpty() ? "" : ",").append(n);
        return b.toString();
    }

    /** Whether they earned every one of `count` notes themselves (traded ones do not count: they are not recorded). */
    static boolean hasAll(Set<Integer> earned, int count) {
        for (int i = 1; i <= count; i++) if (!earned.contains(i)) return false;
        return count > 0;
    }

    /** The seed the notes' numbers make (their digits in order). */
    static long seed(List<Note> notes) {
        long s = 0;
        for (Note n : notes) s = s * 10 + Math.floorMod(n.number(), 10);
        return s;
    }

    /**
     * The closed stop: of the road points (x, z) between `min` and `max` blocks (straight) from (x, z), the one the notes'
     * seed picks; none that far: the one nearest to half way. Returns {x, z, along-x, along-z} (the road's way there), or
     * null without roads.
     */
    static double[] closedStop(long seed, List<double[]> points, double x, double z, double min, double max) {
        if (points.isEmpty()) return null;
        List<Integer> in = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double d = Math.hypot(points.get(i)[0] - x, points.get(i)[1] - z);
            if (d >= min && d <= max) in.add(i);
        }
        int pick;
        if (!in.isEmpty()) pick = in.get((int) Math.floorMod(seed * 2_654_435_761L, (long) in.size()));
        else {
            pick = 0;
            double best = Double.MAX_VALUE, mid = (min + max) / 2;
            for (int i = 0; i < points.size(); i++) {
                double d = Math.abs(Math.hypot(points.get(i)[0] - x, points.get(i)[1] - z) - mid);
                if (d < best) {
                    best = d;
                    pick = i;
                }
            }
        }
        double[] p = points.get(pick);
        double[] q = points.get(Math.min(points.size() - 1, pick + 1)), o = points.get(Math.max(0, pick - 1));
        double dx = q[0] - o[0], dz = q[1] - o[1], len = Math.hypot(dx, dz);
        return new double[]{p[0], p[1], len > 0 ? dx / len : 1, len > 0 ? dz / len : 0};
    }

    /** A stop's name read backwards (his way of naming it). */
    static String reversed(String name) {
        return new StringBuilder(name.replace(" ", "")).reverse().toString();
    }

    /** Whether this (Korean) time is the final's minute(s): finalHour:finalMinute for `span` minutes. */
    static boolean finalTime(int hour, int minute, int fh, int fm, int span) {
        int m = hour * 60 + minute, at = fh * 60 + fm;
        return m >= at && m < at + span;
    }

    /** Honk times (millis) within the window, the newest at `now`: whether `n` of them came. */
    static boolean honked(Deque<Long> times, long now, int n, long windowMs) {
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > windowMs) times.removeFirst();
        return times.size() >= n;
    }

    static String week(LocalDate d) {
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    private final BusModule bus;
    private final EcoPlugin eco;
    private final STSVPlugin core;
    private Config cfg;

    /** him waiting at a stop (one at a time): its id, until when, the mission an admin chose (0: his choice), his mannequin */
    private Integer waitStop;
    private long waitUntil, lastNear;
    private int forcedMission;
    private Mannequin waiting;

    /** A job aboard a bus. */
    static final class Job {
        UUID driver;
        long bus;
        BusModule.Rider rider;
        int mission, from, a, b, progress;
        Integer target;
        long boarded = System.currentTimeMillis();
        boolean bigTip;
        // the final run
        double[] closed;
        String world;
        double slow;
        boolean moved;
        Entity car;
        final List<Entity> shown = new ArrayList<>();
    }

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> honks = new HashMap<>();
    private final Map<UUID, Boolean> jumpWas = new HashMap<>();

    SecretAgent(BusModule bus, EcoPlugin eco, STSVPlugin core) {
        this.bus = bus;
        this.eco = eco;
        this.core = core;
        load();
    }

    void load() {
        cfg = Config.parse(eco.data("bus.yml").getConfigurationSection("secret"));
    }

    Config config() {
        return cfg;
    }

    void stop() {
        for (Job j : new ArrayList<>(jobs.values())) end(j, null);
        jobs.clear();
        if (waiting != null) waiting.remove();
        waiting = null;
    }

    private String key(UUID u, String what) {
        return "bus.secret." + u + "." + what;
    }

    Set<Integer> earned(UUID u) {
        return parseNotes(eco.kv().get(key(u, "notes"), ""));
    }

    private void earn(UUID u, int note) {
        Set<Integer> e = earned(u);
        e.add(note);
        eco.kv().set(key(u, "notes"), formatNotes(e));
    }

    boolean agent(UUID u) {
        return eco.kv().has(key(u, "agent"));
    }

    private BusSpec.Type type() {
        return bus.spec().types().get(cfg.type());
    }

    private double roll() {
        return ThreadLocalRandom.current().nextDouble();
    }

    // ------------------------------------------------------------------------------------------
    // Hooks from BusModule
    // ------------------------------------------------------------------------------------------

    /** Every second: he comes (1-2 times a day), is shown at his stop near a bus, jobs time out or lose him. */
    void second(long now) {
        if (!cfg.enabled() || type() == null) return;
        ZonedDateTime t = ZonedDateTime.now(KST);
        if (waitStop == null) {
            long day = t.toLocalDate().toEpochDay();
            int span = Math.max(0, cfg.maxTimes() - cfg.minTimes());
            int times = cfg.minTimes() + (int) Math.floorMod(day * 31 + 7, span + 1);
            int[] at = BusEvents.minutes(day, 0x5EC2E7, cfg.from(), cfg.to(), times);
            String date = t.toLocalDate().toString();
            String[] was = eco.kv().get("bus.secret.day", "").split(":");
            int done = was.length == 2 && was[0].equals(date) ? Integer.parseInt(was[1]) : 0;
            int m = t.getHour() * 60 + t.getMinute();
            if (done < at.length && m >= at[done]) {
                List<BusStop> on = new ArrayList<>();
                for (BusStop s : bus.stopMap().values()) {
                    BusModule.Net n = bus.net(s.world());
                    if (n != null && !n.linesOf(s.id()).isEmpty()) on.add(s);
                }
                if (!on.isEmpty()) {
                    appear(on.get(ThreadLocalRandom.current().nextInt(on.size())), 0);
                    eco.kv().set("bus.secret.day", date + ":" + (done + 1));
                }
            }
        } else showWaiting(now);
        for (Job j : new ArrayList<>(jobs.values())) {
            Player p = Bukkit.getPlayer(j.driver);
            BusModule.Run run = bus.runMap().get(j.bus);
            if (p == null || run == null || !j.rider.m.isValid() || !run.riders.contains(j.rider)) {   // the driver got out, left
                end(j, p);
                continue;
            }
            double secs = (now - j.boarded) / 1000.0;
            if (j.mission == 4) {
                if (run.atStop != null && run.atStop.equals(j.target) && window(secs, cfg.windowFrom(), cfg.windowTo()) == 0) {
                    BusStop s = bus.stopMap().get(j.target);
                    if (s != null) success(p, run, j, s);
                } else if (window(secs, cfg.windowFrom(), cfg.windowTo()) > 0) fail(p, run, j, "late");
            } else if (j.mission == FINAL) {
                if (secs > 30 * 60) fail(p, run, j, "fail");
                else if (j.closed != null) showClosed(p, j);
            } else if (secs > cfg.giveUp() * 60.0 * (j.mission == WEEKLY ? 2 : 1)) fail(p, run, j, "time");
        }
    }

    /** Every tick for a bus being driven: the horn (space) counted, the final run's black car and its speed watched. */
    void drive(Player p, BusModule.Run run, VehicleModule.Driven d) {
        boolean jump = p.getCurrentInput().isJump();
        Boolean was = jumpWas.put(p.getUniqueId(), jump);
        if (jump && (was == null || !was)) honk(p, run);
        Job j = jobs.get(p.getUniqueId());
        if (j == null || j.mission != FINAL || j.bus != run.id || j.closed == null) return;
        double speed = Math.abs(d.speed());
        double toStop = Math.hypot(d.x() - j.closed[0], d.z() - j.closed[1]);
        if (speed >= cfg.slowSpeed()) j.moved = true;
        else if (j.moved && toStop > 60) j.slow += 1 / 20.0;
        if (j.slow > cfg.slowLimit()) {
            fail(p, run, j, "slow");
            return;
        }
        if (j.car != null && j.car.isValid() && run.id == d.id()) {   // the black car, 10 blocks behind (only they see it)
            double yaw = Math.toRadians(d.yaw());
            Location at = new Location(d.world(), d.x() + Math.sin(yaw) * 10, d.y(), d.z() - Math.cos(yaw) * 10, d.yaw(), 0);
            if (ThreadLocalRandom.current().nextInt(2) == 0 || j.car.getLocation().distanceSquared(at) > 4) j.car.teleport(at);
        }
        if (speed < 0.5 && toStop <= 10) finalDone(p, run, j);
    }

    /** A jolt or a bump: the final run and the weekly delivery fail. */
    void hurt(Player p, BusModule.Run run) {
        Job j = jobs.get(p.getUniqueId());
        if (j != null && j.bus == run.id && (j.mission == FINAL || j.mission == WEEKLY)) fail(p, run, j, "shaken");
    }

    /** Someone else got on (빈 버스: nobody may). */
    void boarded(Player p, BusModule.Run run) {
        Job j = jobs.get(p.getUniqueId());
        if (j != null && j.bus == run.id && j.mission == 2) fail(p, run, j, "boarded");
    }

    /** The bus just stopped at a stop: his missions' progress; he gets on if he waits here; the weekly job. */
    void arrived(Player p, BusModule.Run run, VehicleModule.Driven d, BusStop here) {
        if (!cfg.enabled()) return;
        Job j = jobs.get(p.getUniqueId());
        if (j != null && j.bus == run.id) {
            double secs = (System.currentTimeMillis() - j.boarded) / 1000.0;
            switch (j.mission) {
                case 1, 2, 3, WEEKLY -> {
                    if (j.target != null && j.target == here.id()) success(p, run, j, here);
                }
                case 4 -> {
                    if (j.target != null && j.target == here.id()) {
                        int w = window(secs, cfg.windowFrom(), cfg.windowTo());
                        if (w == 0) success(p, run, j, here);
                        else if (w < 0) speak(p, j.rider.m, cfg.line("early", roll()));
                        else fail(p, run, j, "late");
                    }
                }
                case 5 -> {
                    int was = j.progress;
                    j.progress = aba(j.progress, here.id(), j.a, j.b);
                    if (j.progress < 0) fail(p, run, j, "order");
                    else if (j.progress == 3) success(p, run, j, here);
                    else if (j.progress != was) speak(p, j.rider.m, cfg.line("step", roll()));
                }
                default -> { }
            }
            return;
        }
        if (j != null) return;
        if (waitStop != null && waitStop == here.id()) {
            board(p, run, d, here, forcedMission);
            return;
        }
        // 비밀 의뢰: once a week he finds an agent at one of their stops (only they see him)
        if (agent(p.getUniqueId()) && !week(LocalDate.now(KST)).equals(eco.kv().get(key(p.getUniqueId(), "week"), ""))
                && roll() < cfg.weeklyChance()) weekly(p, run, d, here);
    }

    /** What his stop's sign adds: "???" while he waits there; the first agent's gold plaque. */
    String sign(BusStop s) {
        StringBuilder b = new StringBuilder();
        if (waitStop != null && waitStop == s.id()) b.append("\n<dark_gray>???");
        if (String.valueOf(s.id()).equals(eco.kv().get("bus.secret.plaque", ""))) b.append("\n").append(Lang.raw("bus.secret-plaque"));
        return b.toString();
    }

    // ---- 침묵: a word or a command (but /버스) and he is gone

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        Job j = jobs.get(u);
        if (j != null && j.mission == 1) Bukkit.getScheduler().runTask(eco, () -> failSilence(u));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Job j = jobs.get(e.getPlayer().getUniqueId());
        if (j == null || j.mission != 1) return;
        String m = e.getMessage().trim();
        if (m.startsWith("/버스") || m.startsWith("/bus")) return;   // the bus's own (info) commands are allowed
        failSilence(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Job j = jobs.get(e.getPlayer().getUniqueId());
        if (j != null) end(j, e.getPlayer());
        jumpWas.remove(e.getPlayer().getUniqueId());
        honks.remove(e.getPlayer().getUniqueId());
    }

    private void failSilence(UUID u) {
        Job j = jobs.get(u);
        Player p = Bukkit.getPlayer(u);
        if (j == null || j.mission != 1 || p == null) return;
        fail(p, bus.runMap().get(j.bus), j, "silence");
    }

    // ------------------------------------------------------------------------------------------
    // Him at a stop, boarding, his missions
    // ------------------------------------------------------------------------------------------

    /** He comes to wait at this stop (mission: an admin's choice, 0 his own). */
    void appear(BusStop s, int mission) {
        if (waiting != null) waiting.remove();
        waiting = null;
        waitStop = s.id();
        waitUntil = System.currentTimeMillis() + cfg.stay() * 60_000L;
        lastNear = System.currentTimeMillis();
        forcedMission = mission;
        showWaiting(System.currentTimeMillis());
    }

    private void showWaiting(long now) {
        BusStop s = waitStop == null ? null : bus.stopMap().get(waitStop);
        if (s == null || now > waitUntil) {
            vanishWaiting();
            return;
        }
        World w = Bukkit.getWorld(s.world());
        if (w == null) return;
        boolean near = false;
        for (BusModule.Run r : bus.runMap().values()) {
            Player p = Bukkit.getPlayer(r.driver);
            if (p != null && p.getWorld().equals(w) && Math.hypot(p.getX() - s.x(), p.getZ() - s.z()) <= bus.spec().stops().showRange()) near = true;
        }
        if (near) lastNear = now;
        if (near && (waiting == null || !waiting.isValid())) {
            int bw = bus.spec().stops().bayWidth();
            double[] at = s.at(-(s.side() >= 0 ? 1 : -1) * 9, s.shelterOut(bw) - 0.6);
            Double y = BusModule.standY(w, at[0], at[1], s.y());
            Location l = new Location(w, at[0], y == null ? s.y() + 1 : y, at[1], s.facingRoad(), 0);
            if (w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) waiting = spawn(l, null);
        } else if (!near && waiting != null && now - lastNear > bus.spec().stops().hideAfter() * 1000L) {
            waiting.remove();
            waiting = null;
        }
    }

    private void vanishWaiting() {
        if (waiting != null && waiting.isValid()) {
            waiting.getWorld().spawnParticle(Particle.SMOKE, waiting.getLocation().add(0, 1, 0), 15, 0.2, 0.6, 0.2, 0.01);
            waiting.remove();
        }
        waiting = null;
        waitStop = null;
        forcedMission = 0;
    }

    /** His mannequin (black suit, '???'); only `viewer` sees it when one is given. */
    private Mannequin spawn(Location at, Player viewer) {
        BusSpec.Type t = type();
        String skin = t == null || t.skins().isEmpty() ? null : t.skins().getFirst();
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, x -> {
            x.setPersistent(false);
            x.setInvulnerable(true);
            x.setGravity(false);
            x.setSilent(true);
            x.setImmovable(true);
            x.setCollidable(false);
            x.setRemoveWhenFarAway(false);
            x.getPersistentDataContainer().set(BusModule.GUEST, PersistentDataType.BYTE, (byte) 1);
            x.customName(Text.mm("<dark_gray>???"));
            x.setCustomNameVisible(true);
            x.setDescription(Text.mm("<dark_gray>→ ???"));
            if (viewer != null) x.setVisibleByDefault(false);
            if (skin != null) {
                boolean slim = skin.endsWith(" slim");
                String k = slim ? skin.substring(0, skin.length() - 5).trim() : skin.trim();
                try {
                    x.setProfile(ResolvableProfile.resolvableProfile().name("bus_shades").skinPatch(sp -> sp.body(Key.key(k))
                            .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC)).build());
                } catch (RuntimeException e) {
                    eco.getLogger().warning("[bus.yml] 스킨 키가 잘못됨: " + skin);
                }
            }
        });
        if (viewer != null) viewer.showEntity(eco, m);
        return m;
    }

    /** Seats him on the bus as a rider (going '???'); null when there is no seat. */
    private BusModule.Rider seat(Player p, BusModule.Run run, VehicleModule.Driven d, BusStop here, Mannequin m) {
        VehicleModule vm = core.vehicles();
        List<Integer> free = vm == null ? List.of() : vm.freeSeats(run.id);
        if (free.isEmpty() || !vm.seatGuest(run.id, free.getFirst(), m)) {
            HudModule.flash(p, Lang.mm("bus.secret-no-seat"), 2500);
            return null;
        }
        BusModule.Rider r = new BusModule.Rider(m, type(), here == null ? -1 : here.id(), -2, 0, 100);
        r.secret = true;
        r.lateSaid = true;
        r.dozeChecked = true;
        BusSpec.Type t = type();
        if (t != null && !t.skins().isEmpty()) {
            String k = t.skins().getFirst().replace(" slim", "").trim();
            r.face = k.substring(k.lastIndexOf('/') + 1);
        }
        run.riders.add(r);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
        return r;
    }

    /** He gets on at his stop: one of the five missions (one whose note the driver lacks, if any). */
    private void board(Player p, BusModule.Run run, VehicleModule.Driven d, BusStop here, int forced) {
        BusModule.Net n = bus.net(here.world());
        BusLine line = n == null ? null : n.line(run.line);
        if (line == null || !line.has(here.id())) return;
        Set<Integer> earned = earned(p.getUniqueId());
        int count = Math.max(1, Math.min(5, cfg.notes().size()));
        int mission = forced >= 1 && forced <= 5 ? forced : pickMission(earned, count, roll());
        Job j = plan(line, here, run.dir, mission);
        if (j == null && forced == 0) {   // that one cannot be done from here: another
            for (int k = 1; k <= 5 && j == null; k++) if (k != mission) j = plan(line, here, run.dir, k);
        }
        if (j == null) {
            HudModule.flash(p, Lang.mm("bus.secret-no-way"), 2500);
            return;
        }
        Mannequin m = waiting != null && waiting.isValid() ? waiting : spawn(d.location(), null);
        BusModule.Rider r = seat(p, run, d, here, m);
        if (r == null) {
            if (m != waiting) m.remove();
            return;
        }
        waiting = null;
        waitStop = null;
        forcedMission = 0;
        j.driver = p.getUniqueId();
        j.bus = run.id;
        j.rider = r;
        j.bigTip = hasAll(earned, count) && forced == 0;
        jobs.put(p.getUniqueId(), j);
        String say = switch (j.mission) {
            case 3 -> cfg.line("mission3", roll());
            case 5 -> cfg.line("mission5", roll(), "a", name(j.a), "b", name(j.b));
            default -> cfg.line("mission" + j.mission, roll(), "riddle", riddle(line, j.target));
        };
        p.sendMessage(Lang.mm("bus.secret-board"));
        speak(p, m, say);
    }

    /** A mission's stops from here on this line going dir, or null when it cannot be done from here. */
    private Job plan(BusLine line, BusStop here, int dir, int mission) {
        int at = line.at(here.id());
        List<Integer> ahead = new ArrayList<>(line.ahead(at, dir)), behind = new ArrayList<>(line.ahead(at, -dir));
        ahead.removeIf(id -> !bus.stopMap().containsKey(id));
        behind.removeIf(id -> !bus.stopMap().containsKey(id));
        Job j = new Job();
        j.mission = mission;
        j.from = here.id();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        switch (mission) {
            case 1, 2 -> {
                List<Integer> pool = ahead.isEmpty() ? behind : ahead;
                if (pool.isEmpty()) return null;
                j.target = pool.get(rnd.nextInt(Math.min(3, pool.size())));
            }
            case 3 -> {
                if (behind.isEmpty()) return null;
                j.target = behind.get(Math.min(1, behind.size() - 1));
            }
            case 4 -> {
                List<Integer> pool = ahead.isEmpty() ? behind : ahead;
                if (pool.isEmpty()) return null;
                Integer best = null;
                for (int id : pool) {
                    double road = line.between(at, line.at(id));
                    if (road >= 300 && road <= 2200) {
                        best = id;
                        if (rnd.nextBoolean()) break;
                    }
                }
                j.target = best != null ? best : pool.getFirst();
            }
            case 5 -> {
                if (ahead.size() >= 2) {
                    j.a = ahead.get(0);
                    j.b = ahead.get(1);
                } else if (!ahead.isEmpty() && !behind.isEmpty()) {
                    j.a = ahead.get(0);
                    j.b = here.id();
                } else return null;
            }
            default -> {
                return null;
            }
        }
        return j;
    }

    /** His riddle for a stop: a trait no other stop of the line has (갈매기가 우는 곳 …), else its name backwards. */
    private String riddle(BusLine line, Integer target) {
        BusStop s = target == null ? null : bus.stopMap().get(target);
        if (s == null) return "???";
        List<String> unique = new ArrayList<>();
        for (String t : bus.tagsOf(s)) {
            boolean other = false;
            for (int id : line.stops()) {
                BusStop o = bus.stopMap().get(id);
                if (o != null && id != s.id() && bus.tagsOf(o).contains(t)) other = true;
            }
            if (!other && cfg.riddles().containsKey(t)) unique.add(t);
        }
        if (!unique.isEmpty() && roll() < 0.6) return cfg.riddles().get(unique.get(ThreadLocalRandom.current().nextInt(unique.size())));
        return cfg.riddles().getOrDefault("reversed", "'{name}'").replace("{name}", reversed(s.name()));
    }

    private String name(int stop) {
        BusStop s = bus.stopMap().get(stop);
        return s == null ? "???" : s.name();
    }

    /** He says something: a bubble over his head and, for the driver, a line in chat. */
    private void speak(Player p, Entity m, String line) {
        if (m != null && m.isValid()) bus.say(m, line);
        if (p != null) p.sendMessage(Lang.mm("bus.secret-says", "line", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(line)));
    }

    /** He gets off the bus (and is gone), the job over; the final's sky and sights put back. */
    private void end(Job j, Player p) {
        jobs.remove(j.driver);
        VehicleModule vm = core.vehicles();
        BusModule.Run run = bus.runMap().get(j.bus);
        if (run != null) run.riders.remove(j.rider);
        if (vm != null) vm.unseatGuest(j.bus, j.rider.m);
        Mannequin m = j.rider.m;
        if (m.isValid()) {
            m.getWorld().spawnParticle(Particle.SMOKE, m.getLocation().add(0, 1, 0), 15, 0.2, 0.6, 0.2, 0.01);
            Bukkit.getScheduler().runTaskLater(eco, m::remove, 4L);
        }
        if (j.car != null) j.car.remove();
        for (Entity e : j.shown) e.remove();
        j.shown.clear();
        if (j.mission == FINAL && p != null) p.resetPlayerTime();
    }

    private void fail(Player p, BusModule.Run run, Job j, String why) {
        speak(p, j.rider.m, cfg.line("fail", roll()));
        String reason = switch (why) {
            case "silence" -> Lang.raw("bus.secret-why-silence");
            case "boarded" -> Lang.raw("bus.secret-why-boarded");
            case "late" -> Lang.raw("bus.secret-why-late");
            case "order" -> Lang.raw("bus.secret-why-order");
            case "slow" -> Lang.raw("bus.secret-why-slow");
            case "shaken" -> Lang.raw("bus.secret-why-shaken");
            default -> Lang.raw("bus.secret-why-time");
        };
        p.sendMessage(Lang.mm("bus.secret-fail", "why", reason));
        p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 0.8f);
        end(j, p);
    }

    /** A mission done: his tip (and the note of that mission, once ever, when earned for the first time). */
    private void success(Player p, BusModule.Run run, Job j, BusStop here) {
        if (j.mission == WEEKLY) {
            long reward = cfg.weeklyRewardMin() + (long) (roll() * Math.max(0, cfg.weeklyRewardMax() - cfg.weeklyRewardMin()));
            reward = Math.round(reward / 1000.0) * 1000;
            core.economy().deposit(p.getUniqueId(), reward, "bus_secret");
            speak(p, j.rider.m, cfg.line("success", roll()));
            p.sendMessage(Lang.mm("bus.secret-weekly-done", "g", BusModule.g(reward)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 0.7f);
            end(j, p);
            return;
        }
        UUID u = p.getUniqueId();
        long tip = cfg.tipMin() + (long) (roll() * Math.max(0, cfg.tipMax() - cfg.tipMin()));
        if (j.bigTip) tip = Math.round(tip * cfg.allNotesTip());
        if (agent(u)) tip = Math.round(tip * cfg.agentTip());
        tip = Math.round(tip / 10.0) * 10;
        long[] paid = bus.bonus(p, run, tip, "bus_secret");
        speak(p, j.rider.m, cfg.line("success", roll()));
        boolean fresh = !earned(u).contains(j.mission);
        if (fresh && j.mission <= cfg.notes().size()) {
            earn(u, j.mission);
            ItemStack note = note(j.mission);
            for (ItemStack left : p.getInventory().addItem(note).values()) p.getWorld().dropItem(p.getLocation(), left);
            p.sendMessage(Lang.mm("bus.secret-done", "g", BusModule.g(paid[0]), "note", cfg.notes().get(j.mission - 1).name(),
                    "n", earned(u).size(), "max", cfg.notes().size()));
        } else {
            p.sendMessage(Lang.mm("bus.secret-tip-only", "g", BusModule.g(paid[0])));
        }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.7f);
        end(j, p);
    }

    /** Note n (1..5): a line of the final riddle and a number on its back. Not sold to the shop; may be traded or given. */
    ItemStack note(int n) {
        Note no = cfg.notes().get(n - 1);
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            m.itemName(Lang.mm("bus.note-name", "n", n, "name", no.name()));
            m.lore(List.of(Lang.mm("bus.note-lore-1", "line", no.line()), Lang.mm("bus.note-lore-2", "number", no.number()),
                    Lang.mm("bus.note-lore-3")).stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
            m.getPersistentDataContainer().set(NOTE, PersistentDataType.INTEGER, n);
        });
        Items.markBought(it);
        return it;
    }

    // ------------------------------------------------------------------------------------------
    // The final run (03:33, a terminal-end stop, three honks)
    // ------------------------------------------------------------------------------------------

    private void honk(Player p, BusModule.Run run) {
        UUID u = p.getUniqueId();
        Deque<Long> d = honks.computeIfAbsent(u, k -> new ArrayDeque<>());
        if (!honked(d, System.currentTimeMillis(), cfg.honks(), cfg.honkWindow() * 1000L)) return;
        d.clear();
        if (!cfg.enabled() || jobs.containsKey(u) || agent(u) || run.atStop == null) return;
        ZonedDateTime t = ZonedDateTime.now(KST);
        if (!finalTime(t.getHour(), t.getMinute(), cfg.finalHour(), cfg.finalMinute(), cfg.finalSpan())) return;
        if (!hasAll(earned(u), cfg.notes().size())) return;
        BusStop here = bus.stopMap().get(run.atStop);
        if (here == null || !terminalSide(here)) return;
        startFinal(p, run, here);
    }

    /** Whether a stop is the first of a line (its terminal end). */
    private boolean terminalSide(BusStop s) {
        BusModule.Net n = bus.net(s.world());
        if (n == null) return false;
        for (BusLine l : n.lines()) {
            for (int id : l.stops()) {
                if (!bus.stopMap().containsKey(id)) continue;
                if (id == s.id()) return true;
                break;
            }
        }
        return false;
    }

    /** The final run starts: night for them alone, everyone else gets off and fades, he gets on, the black car behind. */
    String startFinal(Player p, BusModule.Run run, BusStop here) {
        VehicleModule vm = core.vehicles();
        VehicleModule.Driven d = vm == null ? null : vm.drivenBy(p);
        if (d == null || d.id() != run.id) return "<red>버스를 운전 중이 아니에요";
        if (type() == null) return "<red>bus.yml secret.type 의 손님 종류가 없어요";
        List<double[]> pts = new ArrayList<>();
        if (eco.roads() != null) for (var r : eco.roads().builtRoads(d.world().getName())) pts.addAll(r.points());
        double[] closed = closedStop(seed(cfg.notes()), pts, d.x(), d.z(), cfg.finalMin(), cfg.finalMax());
        if (closed == null) return "<red>이 월드에 국도가 없어요";
        Job old = jobs.get(p.getUniqueId());
        if (old != null) end(old, p);
        if (here != null) bus.dropAll(p, run, here);
        p.setPlayerTime(18000, false);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false));
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.6f);
        Mannequin m = spawn(d.location(), p);
        BusModule.Rider r = seat(p, run, d, here, m);
        if (r == null) {
            m.remove();
            p.resetPlayerTime();
            return "<red>빈 자리가 없어요";
        }
        Job j = new Job();
        j.driver = p.getUniqueId();
        j.bus = run.id;
        j.rider = r;
        j.mission = FINAL;
        j.closed = closed;
        j.world = d.world().getName();
        j.car = car(p, d);
        jobs.put(p.getUniqueId(), j);
        p.sendMessage(Lang.mm("bus.secret-final-start", "km", BusModule.km(Math.hypot(closed[0] - d.x(), closed[1] - d.z())),
                "dir", compass(closed[0] - d.x(), closed[1] - d.z())));
        Bukkit.getScheduler().runTaskLater(eco, () -> {
            if (jobs.get(p.getUniqueId()) == j) speak(p, m, cfg.line("final-board", roll()));
        }, 30L);
        return "<green>" + p.getName() + ": 최종 미션 시작 (폐쇄된 정류장 " + Math.round(closed[0]) + ", " + Math.round(closed[1]) + ")";
    }

    private static String compass(double dx, double dz) {
        String[] dirs = {"남", "남서", "서", "북서", "북", "북동", "동", "남동"};
        double a = Math.toDegrees(Math.atan2(-dx, dz));
        return dirs[(int) Math.floorMod(Math.round(a / 45), 8)];
    }

    /** The black car that follows: the agent sedan's item model when the pack has it, else a black block (only they see it). */
    private Entity car(Player p, VehicleModule.Driven d) {
        Location at = d.location();
        java.util.Optional<ItemStack> sedan = cfg.vehicle().isEmpty() ? java.util.Optional.empty() : Items.create(cfg.vehicle(), 1);
        Entity e;
        if (sedan.isPresent()) {
            e = at.getWorld().spawn(at, ItemDisplay.class, x -> {
                x.setItemStack(sedan.get());
                x.setTransformation(new Transformation(new Vector3f(0, 1.2f, 0), new AxisAngle4f(), new Vector3f(3, 3, 3), new AxisAngle4f()));
                prep(x);
            });
        } else {
            BlockData black = Material.BLACK_CONCRETE.createBlockData();
            e = at.getWorld().spawn(at, BlockDisplay.class, x -> {
                x.setBlock(black);
                x.setTransformation(new Transformation(new Vector3f(-1f, 0.3f, -2.2f), new AxisAngle4f(), new Vector3f(2f, 1.3f, 4.4f), new AxisAngle4f()));
                prep(x);
            });
        }
        p.showEntity(eco, e);
        return e;
    }

    private static void prep(Display x) {
        x.setPersistent(false);
        x.setVisibleByDefault(false);
        x.setTeleportDuration(3);
        x.setViewRange(2f);
        x.getPersistentDataContainer().set(BusModule.GUEST, PersistentDataType.BYTE, (byte) 1);
    }

    /** The closed stop's ghostly shelter, made when they come near (only they see it). */
    private void showClosed(Player p, Job j) {
        if (!j.shown.isEmpty() || !p.getWorld().getName().equals(j.world)) return;
        double x = j.closed[0], z = j.closed[1];
        if (Math.hypot(p.getX() - x, p.getZ() - z) > 120) return;
        World w = p.getWorld();
        if (!w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) return;
        double ax = j.closed[2], az = j.closed[3];
        double nx = -az, nz = ax;   // beside the road
        double bx = x + nx * 7, bz = z + nz * 7;
        int y = w.getHighestBlockYAt((int) Math.floor(bx), (int) Math.floor(bz)) + 1;
        float yaw = (float) Math.toDegrees(Math.atan2(-ax, az)) + 90;
        Location base = new Location(w, bx, y, bz, yaw, 0);
        block(p, j, base, Material.CRACKED_STONE_BRICKS, -2f, 0f, 0.8f, 4f, 0.2f, 0.2f);          // floor
        block(p, j, base, Material.STRIPPED_DARK_OAK_LOG, -2f, 0.2f, -0.8f, 0.25f, 2.4f, 0.25f);  // posts
        block(p, j, base, Material.STRIPPED_DARK_OAK_LOG, 1.75f, 0.2f, -0.8f, 0.25f, 2.4f, 0.25f);
        block(p, j, base, Material.MOSSY_COBBLESTONE, -2f, 0.2f, 0.8f, 4f, 2.2f, 0.2f);           // back wall
        block(p, j, base, Material.WEATHERED_CUT_COPPER, -2.3f, 2.6f, -1.2f, 4.6f, 0.12f, 2.4f);  // roof
        block(p, j, base, Material.DARK_OAK_PLANKS, -1.5f, 0.6f, 0.2f, 3f, 0.12f, 0.5f);          // bench
        block(p, j, base, Material.COBWEB, 1.2f, 1.6f, 0.3f, 0.6f, 0.6f, 0.4f);
        Location sign = base.clone().add(nx * -2.5, 2.2, nz * -2.5);
        TextDisplay t = w.spawn(sign, TextDisplay.class, x2 -> {
            x2.text(Lang.mm("bus.secret-closed-sign"));
            x2.setBillboard(Display.Billboard.CENTER);
            x2.setBackgroundColor(Color.fromARGB(160, 20, 20, 20));
            prep(x2);
        });
        p.showEntity(eco, t);
        j.shown.add(t);
        w.playSound(base, Sound.AMBIENT_CAVE, 0.6f, 0.5f);
    }

    private void block(Player p, Job j, Location base, Material mat, float tx, float ty, float tz, float sx, float sy, float sz) {
        BlockData bd = mat.createBlockData();
        BlockDisplay d = base.getWorld().spawn(base, BlockDisplay.class, x -> {
            x.setBlock(bd);
            x.setTransformation(new Transformation(new Vector3f(tx, ty, tz), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f()));
            prep(x);
        });
        p.showEntity(eco, d);
        j.shown.add(d);
    }

    /** Stopped at the closed stop: the briefcase, his last words, the rewards (in full), the sky put back. */
    private void finalDone(Player p, BusModule.Run run, Job j) {
        UUID u = p.getUniqueId();
        speak(p, j.rider.m, cfg.line("final-done", roll()));
        if (j.rider.m.isValid()) {   // the briefcase he leaves on the seat
            ItemDisplay bag = j.rider.m.getWorld().spawn(j.rider.m.getLocation().add(0, 0.6, 0), ItemDisplay.class, x -> {
                x.setItemStack(new ItemStack(Material.BROWN_SHULKER_BOX));
                x.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.5f, 0.35f, 0.15f), new AxisAngle4f()));
                prep(x);
            });
            p.showEntity(eco, bag);
            Bukkit.getScheduler().runTaskLater(eco, bag::remove, 100L);
        }
        eco.kv().set(key(u, "agent"), String.valueOf(System.currentTimeMillis()));
        end(j, p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0, false, false));
        rewards(p);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 0.6f);
        Bukkit.broadcast(Lang.msg("bus.secret-final-all"));
        if (!eco.kv().has("bus.secret.first")) {
            eco.kv().set("bus.secret.first", u.toString());
            p.sendMessage(Lang.mm("bus.secret-first"));
        }
    }

    /** The final's rewards: money (not shared with a rental), the sedan (bound to them), the three cosmetics, the title. */
    private void rewards(Player p) {
        UUID u = p.getUniqueId();
        if (cfg.money() > 0) core.economy().deposit(u, cfg.money(), "bus_secret");
        List<String> got = new ArrayList<>();
        got.add(BusModule.g(cfg.money()) + " G");
        if (!cfg.vehicle().isEmpty()) {
            Items.create(cfg.vehicle(), 1).ifPresentOrElse(it -> {
                if (core.vehicles() != null) core.vehicles().bind(it, u, p.getName());
                for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
                got.add(Items.mmName(it));
            }, () -> eco.getLogger().warning("[버스 비밀] 없는 탈것: " + cfg.vehicle()));
        }
        for (String id : cfg.cosmetics()) {
            var c = core.content().item(id).filter(i -> i.cosmetic() != null);
            if (c.isEmpty() || core.cosmetics() == null) {
                eco.getLogger().warning("[버스 비밀] 없는 치장: " + id);
                continue;
            }
            core.cosmetics().grant(p, c.get(), true);
            core.cosmetics().celebrate(p, c.get());
            got.add(c.get().name() == null ? id : c.get().name());
        }
        if (!cfg.title().isEmpty() && core.profiles() != null) {
            core.profiles().grantTitle(u, cfg.title());
            got.add(core.profiles().title(cfg.title()).map(t -> t.name()).orElse(cfg.title()));
        }
        p.sendMessage(Lang.mm("bus.secret-final-done", "got", String.join("<gray>, </gray>", got)));
    }

    // ---- 비밀 의뢰 (weekly, agents only)

    private void weekly(Player p, BusModule.Run run, VehicleModule.Driven d, BusStop here) {
        List<BusStop> pool = new ArrayList<>();
        for (BusStop s : bus.stopMap().values()) {
            if (!s.world().equals(here.world()) || s.id() == here.id()) continue;
            double dist = Math.hypot(s.x() - here.x(), s.z() - here.z());
            if (dist >= cfg.weeklyMin() && dist <= cfg.weeklyMax()) pool.add(s);
        }
        if (pool.isEmpty() || type() == null) return;
        BusStop to = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        Mannequin m = spawn(d.location(), p);
        BusModule.Rider r = seat(p, run, d, here, m);
        if (r == null) {
            m.remove();
            return;
        }
        eco.kv().set(key(p.getUniqueId(), "week"), week(LocalDate.now(KST)));
        Job j = new Job();
        j.driver = p.getUniqueId();
        j.bus = run.id;
        j.rider = r;
        j.mission = WEEKLY;
        j.target = to.id();
        jobs.put(p.getUniqueId(), j);
        p.sendMessage(Lang.mm("bus.secret-weekly", "stop", to.name(), "km", BusModule.km(Math.hypot(to.x() - here.x(), to.z() - here.z()))));
        speak(p, m, cfg.line("weekly", roll(), "stop", to.name()));
    }

    // ------------------------------------------------------------------------------------------
    // /버스 요원정류장, /관리자 버스 비밀
    // ------------------------------------------------------------------------------------------

    /** The first agent names one stop (the nearest within 30 blocks), once; its sign gets a gold plaque. */
    void renameStop(Player p, String name) {
        if (!p.getUniqueId().toString().equals(eco.kv().get("bus.secret.first", "")) || eco.kv().has("bus.secret.plaque")) {
            p.sendMessage(Lang.msg("bus.secret-rename-no"));
            return;
        }
        if (name == null || name.isBlank() || name.length() > 16) {
            p.sendMessage(Lang.msg("bus.secret-rename-usage"));
            return;
        }
        BusStop near = null;
        double best = 30;
        for (BusStop s : bus.stopMap().values()) {
            if (!s.world().equals(p.getWorld().getName())) continue;
            double d = Math.hypot(s.x() - p.getX(), s.z() - p.getZ());
            if (d <= best) {
                best = d;
                near = s;
            }
        }
        if (near == null) {
            p.sendMessage(Lang.msg("bus.secret-rename-far"));
            return;
        }
        String r = bus.rename(near.name(), name.trim());
        if (r.startsWith("<green>")) eco.kv().set("bus.secret.plaque", String.valueOf(near.id()));
        p.sendMessage(Text.msg(r));
    }

    void admin(CommandSender s, String[] args) {
        if (args.length < 2 || !args[0].equals("비밀")) {
            s.sendMessage(Text.msg("<red>/관리자 버스 비밀 소환 [1-5] | 쪽지 <플레이어> <1-5|전부> | 최종 [플레이어] | 초기화 <플레이어> | 상태 <플레이어>"));
            return;
        }
        String who = args.length > 2 ? args[2] : null;
        switch (args[1]) {
            case "소환" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(Text.msg("<red>게임 안에서 쓰세요"));
                    return;
                }
                int mission = 0;
                if (who != null) {
                    try {
                        mission = Math.clamp(Integer.parseInt(who), 1, 5);
                    } catch (NumberFormatException e) {
                        s.sendMessage(Text.msg("<red>미션 번호는 1~5"));
                        return;
                    }
                }
                BusStop near = null;
                double best = Double.MAX_VALUE;
                for (BusStop st : bus.stopMap().values()) {
                    if (!st.world().equals(p.getWorld().getName())) continue;
                    double d = Math.hypot(st.x() - p.getX(), st.z() - p.getZ());
                    if (d < best) {
                        best = d;
                        near = st;
                    }
                }
                if (near == null) {
                    s.sendMessage(Text.msg("<red>이 월드에 정류장이 없어요"));
                    return;
                }
                appear(near, mission);
                s.sendMessage(Text.msg("<green>선글라스 남자가 <white>" + near.name() + "</white>에서 기다려요 (" + cfg.stay() + "분, 미션 "
                        + (mission == 0 ? "자동" : mission) + "). 버스를 그 정류장 베이에 세우면 탑니다"));
            }
            case "쪽지" -> {
                if (who == null || args.length < 4) {
                    s.sendMessage(Text.msg("<red>/관리자 버스 비밀 쪽지 <플레이어> <1-5|전부>"));
                    return;
                }
                List<Integer> give = new ArrayList<>();
                if (args[3].equals("전부")) for (int i = 1; i <= cfg.notes().size(); i++) give.add(i);
                else {
                    try {
                        int n = Integer.parseInt(args[3]);
                        if (n < 1 || n > cfg.notes().size()) throw new NumberFormatException();
                        give.add(n);
                    } catch (NumberFormatException e) {
                        s.sendMessage(Text.msg("<red>쪽지 번호는 1~" + cfg.notes().size() + " 또는 전부"));
                        return;
                    }
                }
                Players.find(who, found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>없는 플레이어"));
                        return;
                    }
                    for (int n : give) earn(found.get(), n);
                    Player t = Bukkit.getPlayer(found.get());
                    if (t != null) for (int n : give) for (ItemStack left : t.getInventory().addItem(note(n)).values()) t.getWorld().dropItem(t.getLocation(), left);
                    s.sendMessage(Text.msg("<green>" + who + ": 쪽지 " + give + " 를 직접 얻은 것으로 기록" + (t != null ? " (아이템도 줌)" : "")));
                });
            }
            case "최종" -> {
                Player t = who == null ? (s instanceof Player p ? p : null) : Bukkit.getPlayerExact(who);
                if (t == null) {
                    s.sendMessage(Text.msg("<red>접속한 플레이어를 정하세요"));
                    return;
                }
                VehicleModule vm = core.vehicles();
                VehicleModule.Driven d = vm == null ? null : vm.drivenBy(t);
                BusModule.Run run = d == null ? null : bus.runMap().get(d.id());
                if (run == null) {
                    s.sendMessage(Text.msg("<red>" + t.getName() + " 님이 버스를 운전 중이 아니에요"));
                    return;
                }
                BusStop here = run.atStop == null ? null : bus.stopMap().get(run.atStop);
                if (here == null) {   // anywhere: the riders get off as if at the nearest stop
                    double best = Double.MAX_VALUE;
                    for (BusStop st : bus.stopMap().values()) {
                        if (!st.world().equals(t.getWorld().getName())) continue;
                        double dd = Math.hypot(st.x() - t.getX(), st.z() - t.getZ());
                        if (dd < best) {
                            best = dd;
                            here = st;
                        }
                    }
                }
                s.sendMessage(Text.msg(startFinal(t, run, here)));
            }
            case "초기화" -> {
                if (who == null) {
                    s.sendMessage(Text.msg("<red>/관리자 버스 비밀 초기화 <플레이어>"));
                    return;
                }
                Players.find(who, found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>없는 플레이어"));
                        return;
                    }
                    eco.kv().removePrefix("bus.secret." + found.get() + ".");
                    Job j = jobs.get(found.get());
                    if (j != null) end(j, Bukkit.getPlayer(found.get()));
                    s.sendMessage(Text.msg("<green>" + who + ": 비밀 의뢰 기록을 지웠어요 (쪽지 · 최종 · 주간; 최초의 요원 · 정류장 이름은 그대로)"));
                });
            }
            case "상태" -> {
                if (who == null) {
                    s.sendMessage(Text.msg("<red>/관리자 버스 비밀 상태 <플레이어>"));
                    return;
                }
                Players.find(who, found -> {
                    if (found.isEmpty()) {
                        s.sendMessage(Text.msg("<red>없는 플레이어"));
                        return;
                    }
                    UUID u = found.get();
                    Job j = jobs.get(u);
                    BusStop ws = waitStop == null ? null : bus.stopMap().get(waitStop);
                    s.sendMessage(Text.msg("<gold>" + who + " <gray>쪽지(직접 얻음) <white>" + earned(u) + "</white> · 최종 <white>"
                            + (agent(u) ? "완료" : "아직") + "</white> · 이번 주 의뢰 <white>"
                            + (week(LocalDate.now(KST)).equals(eco.kv().get(key(u, "week"), "")) ? "받음" : "아직") + "</white> · 유령 조각 <white>"
                            + eco.kv().getLong("bus.ghost." + u, 0) + "</white> · 진행 중 <white>" + (j == null ? "없음" : "미션 " + j.mission)
                            + "</white> · 기다리는 곳 <white>" + (ws == null ? "없음" : ws.name()) + "</white> · 최초의 요원 <white>"
                            + (u.toString().equals(eco.kv().get("bus.secret.first", "")) ? "예" : "아니오")));
                });
            }
            default -> s.sendMessage(Text.msg("<red>/관리자 버스 비밀 소환|쪽지|최종|초기화|상태"));
        }
    }

    List<String> complete(String[] args) {
        if (args.length == 1) return List.of("비밀");
        if (args.length == 2) return List.of("소환", "쪽지", "최종", "초기화", "상태");
        if (args.length == 3 && args[1].equals("소환")) return List.of("1", "2", "3", "4", "5");
        if (args.length == 3) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.startsWith(args[2])).toList();
        if (args.length == 4 && args[1].equals("쪽지")) return List.of("1", "2", "3", "4", "5", "전부");
        return List.of();
    }
}
