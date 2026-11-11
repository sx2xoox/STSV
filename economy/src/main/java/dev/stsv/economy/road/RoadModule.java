package dev.stsv.economy.road;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.furniture.DisplayView;
import dev.stsv.core.util.Lang;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.dock.Dock;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.block.data.type.Wall;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 국도: /관리자 국도 &lt;하역장&gt; [여기서] lays a road from the build world's spawn (or where the admin stands) to a dock's gate
 * (README "국도"). Time-sliced, shown to the admin, saved as it goes ({@link RoadJob}) and resumed after a restart;
 * /관리자 국도 중지 stops it. Also gives vehicles on its surface their speed bonus (roads.speed-bonus).
 *
 * <ol>
 *   <li>SURVEY: every chunk of a corridor round the straight line (roads.corridor chunks each side) is loaded or generated
 *       asynchronously, a few at a time, and its heightmap read: ground, water depth, unevenness, a player's land.</li>
 *   <li>PLAN: A* over those chunks, then pulled straight ({@link RoadPlanner}).</li>
 *   <li>PREPARE: the chunks along the route and roads.pregen-radius round it generated; the ground under every point read.</li>
 *   <li>BUILD: a 5-wide surface with a dashed centre line and kerbs, cut and fill at a grade of 1 in 3 at most, bridges with
 *       railings over water and ravines, tunnels through steep hills, trees cleared, street lights every roads.light-every
 *       blocks, direction signs ({@link RoadSign}: at the start, every roads.marker-every, before the gate) ({@link RoadProfile},
 *       {@link RoadSection}). Never in a player's land: a column there is left alone (the shoulders), and the road stops if
 *       its surface would need one (the land was claimed after planning).</li>
 * </ol>
 *
 * Each road has a number (국도 n호선, {@link RoadJob#route}: in the order the roads were first made) and a set of signs
 * ({@link RoadJob#signs}); /관리자 국도 표지판 정리 &lt;하역장&gt; removes its signs (those in unloaded chunks when they load).
 */
public final class RoadModule extends EcoModule {

    record Settings(Material surface, Material line, Material kerb, Material edge, double bonus, int lightEvery, int markerEvery, int corridor,
                    int pregen, int perTick, int concurrent) {

        RoadSection.Style style() {
            return new RoadSection.Style(surface, line, kerb, edge, lightEvery, markerEvery);
        }
    }

    private Settings settings;
    private java.util.Set<Material> paved = java.util.Set.of();
    /** the network's reference point (/관리자 국도 기준점), or null: the build world's spawn */
    private RoadOrigin origin;
    private RoadJob job;
    private UUID admin;
    private ChunkQueue queue;
    /** PREPARE: points waiting for their chunk; BUILD: the road's heights and parts */
    private final Map<Long, List<Integer>> waiting = new HashMap<>();
    private int[] halves, cross;
    private boolean[] over;
    private RoadNetwork net;
    private RoadProfile.Part[] parts;
    private List<double[]> points;
    private RoadSection builder;
    private final Map<Long, Chunk> held = new HashMap<>();
    private final Set<Long> requested = new HashSet<>();
    private long ticks;
    /** every saved road's number and set of signs, by dock */
    private final Map<String, Integer> routes = new HashMap<>(), sets = new HashMap<>();

    public RoadModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-roads";
    }

    @Override
    protected void start() {
        load();
        if (core.vehicles() != null) core.vehicles().surfaceBonus(b -> {
            return paved.contains(b.getType()) ? settings.bonus() : 1;
        });
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "국도",
                "<하역장> [기준점|여기서|새로|이어짓기] | 기준점 [설정|보기|초기화] | 중지 | 상태 | 표지판 정리 <하역장>",
                "국도: 기준점(없으면 건축월드 스폰, 여기서 = 지금 자리)에서 하역장 입구까지 길 놓기 (멈췄던 것은 이어서, 새로 = 처음부터, "
                        + "이어짓기 = 다 지은 길의 끝에서 입구까지 모자란 곳만) / 기준점 정하기 / 멈추기 / 진행 상황 / 그 길의 표지판 모두 치우기",
                this::command, (s, a) -> {
                    if (a.length == 1) {
                        List<String> out = new ArrayList<>(eco.docks().names());
                        out.addAll(List.of("기준점", "중지", "상태", "표지판"));
                        return out;
                    }
                    if (a[0].equals("기준점")) return a.length == 2 ? List.of("설정", "보기", "초기화") : List.of();
                    if (a[0].equals("표지판")) return a.length == 2 ? List.of("정리") : a.length == 3 ? new ArrayList<>(eco.docks().names()) : List.of();
                    return a.length == 2 ? List.of("기준점", "여기서", "새로", "이어짓기") : List.of();
                });
        // a road that was being built when the server stopped goes on
        try {
            Path dir = folder();
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".txt") && !p.getFileName().toString().equals(ORIGIN)).toList()) {
                        RoadJob j = RoadJob.read(Files.readString(f, StandardCharsets.UTF_8));
                        if (j.dock == null) continue;
                        known(j);
                        if (j.running && j.phase != RoadJob.Phase.DONE && job == null) {
                            job = j;
                            eco.getLogger().info("[국도] " + j.dock + " 까지 짓던 길을 이어서 (" + j.phase + ")");
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            eco.getLogger().warning("[국도] 저장된 작업을 읽지 못함: " + e);
        }
        every(1L, this::tick);
    }

    @Override
    public void reload() {
        load();
        builder = null;   // the new blocks and spacings from the next cross-section on
        DisplayView.Settings v = view();   // the signs already up: display.view-distance.road-signs
        for (World w : Bukkit.getWorlds()) for (Display d : w.getEntitiesByClass(Display.class)) if (RoadSigns.dock(d) != null) RoadSigns.view(d, v);
    }

    /** The core's display settings (view distances, culling). */
    private DisplayView.Settings view() {
        return core.furniture() != null ? core.furniture().view() : DisplayView.Settings.of(core.getConfig());
    }

    /** A road's number and set of signs, remembered. */
    private void known(RoadJob j) {
        if (j.dock == null) return;
        if (j.route > 0) routes.put(j.dock, j.route);
        sets.put(j.dock, j.signs);
    }

    /** The number of the next new road: one more than any road's so far. */
    private int nextRoute() {
        int max = 0;
        for (int r : routes.values()) max = Math.max(max, r);
        return max + 1;
    }

    /**
     * A sign whose dock is gone (/관리자 하역장 제거) or that belongs to an older set of its road's signs (rebuilt, or cleared
     * with /관리자 국도 표지판 정리) goes when its chunk loads; the others take the current view distance.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        DisplayView.Settings v = null;
        for (Entity en : e.getEntities()) {
            String dock = RoadSigns.dock(en);
            if (dock == null) continue;
            if ((eco.docks() != null && eco.docks().dock(dock) == null) || RoadSigns.set(en) != sets.getOrDefault(dock, 0)) {
                en.remove();
                continue;
            }
            if (en instanceof Display d) {
                if (v == null) v = view();
                RoadSigns.view(d, v);
                if (d instanceof org.bukkit.entity.ItemDisplay m && m.getItemDisplayTransform() == org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED
                        && m.getTransformation().getScale().y() == RoadSigns.SCALE) RoadSigns.model(m);   // signs placed by 0.51.0 stood low
            }
        }
    }

    /**
     * Removes a dock's road signs: those in loaded chunks now, the rest when their chunk loads (its road's set of signs moves
     * on). How many entities went now.
     */
    public int clearSigns(String dock) {
        boolean running = job != null && job.dock.equals(dock);
        RoadJob j = running && !job.tail ? job : saved(dock), t = running && job.tail ? job : savedTail(dock);
        if (j != null) {
            j.signs++;
            write(j);
            if (t != null) {   // its patch keeps the same set of signs
                t.signs = j.signs;
                write(t);
            }
            known(j);
        } else {
            sets.merge(dock, 1, Integer::sum);
        }
        return RoadSigns.remove(dock);
    }

    @Override
    protected void stop() {
        if (core.vehicles() != null) core.vehicles().surfaceBonus(null);
        if (job != null) save();
        release();
        if (queue != null) queue.stop();
    }

    private void load() {
        settings = new Settings(material(eco.getConfig().getString("roads.surface"), Material.GRAY_CONCRETE),
                material(eco.getConfig().getString("roads.center-line"), Material.YELLOW_CONCRETE),
                material(eco.getConfig().getString("roads.kerb"), Material.SMOOTH_STONE),
                material(eco.getConfig().getString("roads.edge-line"), Material.SMOOTH_QUARTZ),
                Math.clamp(eco.getConfig().getDouble("roads.speed-bonus"), 1, 2), Math.max(8, eco.getConfig().getInt("roads.light-every")),
                Math.max(100, eco.getConfig().getInt("roads.marker-every")), Math.clamp(eco.getConfig().getInt("roads.corridor"), 2, 24),
                Math.clamp(eco.getConfig().getInt("roads.pregen-radius"), 0, 6), Math.clamp(eco.getConfig().getInt("roads.build-per-tick"), 1, 20),
                Math.clamp(eco.getConfig().getInt("roads.concurrent"), 1, 16));
        paved = settings.style().paved();
        origin = readOrigin();
    }

    // ------------------------------------------------------------------------------------------
    // The reference point (/관리자 국도 기준점)
    // ------------------------------------------------------------------------------------------

    static final String ORIGIN = "origin.txt";
    /** How far a road runs straight out of a terminal's exit before its route turns (blocks). */
    static final int LEAD = 20;

    private RoadOrigin readOrigin() {
        Path f = folder().resolve(ORIGIN);
        try {
            return Files.exists(f) ? RoadOrigin.read(Files.readString(f, StandardCharsets.UTF_8)) : null;
        } catch (IOException e) {
            eco.getLogger().warning("[국도] 기준점을 읽지 못함: " + e);
            return null;
        }
    }

    /** The network's reference point, or null (none set: the build world's spawn). */
    public RoadOrigin origin() {
        return origin;
    }

    /** Sets (or with null clears) the reference point and saves it. */
    public void origin(RoadOrigin o) {
        origin = o;
        Path f = folder().resolve(ORIGIN);
        try {
            if (o == null) Files.deleteIfExists(f);
            else {
                Files.createDirectories(folder());
                Files.writeString(f, o.write(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            eco.getLogger().warning("[국도] 기준점 저장 실패: " + e);
        }
    }

    /** The build world (or this one): where measuring falls back to its spawn. */
    public World buildWorld(World fallback) {
        return core.worlds() != null && core.worlds().buildWorld() != null ? core.worlds().buildWorld() : fallback;
    }

    /**
     * Where roads start and docks are measured from: the reference point when set (and its world exists), else the build
     * world's spawn ({@link RoadOrigin#pick}).
     */
    public RoadOrigin.Use from(World fallback) {
        World bw = buildWorld(fallback);
        Location sp = bw.getSpawnLocation();
        return RoadOrigin.pick(origin, n -> Bukkit.getWorld(n) != null, bw.getName(), sp.getX(), sp.getY(), sp.getZ());
    }

    /** "기준점 (x, y, z)" or "건축월드 스폰 (x, y, z)": which one a message measured from. */
    public static String fromWords(RoadOrigin.Use u) {
        return Lang.raw(u.origin() ? "road.from-origin" : "road.from-spawn", "x", Math.round(Math.floor(u.x())), "y", Math.round(Math.floor(u.y())),
                "z", Math.round(Math.floor(u.z())), "world", u.world());
    }

    private void originCommand(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1] : "보기";
        switch (sub) {
            case "설정" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage(Lang.msg("common.players-only"));
                    return;
                }
                Location l = p.getLocation();
                origin(new RoadOrigin(l.getWorld().getName(), l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5));
                s.sendMessage(Lang.msg("road.origin-set", "world", l.getWorld().getName(), "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ()));
            }
            case "초기화" -> {
                origin(null);
                s.sendMessage(Lang.msg("road.origin-cleared", "from", fromWords(from(s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst()))));
            }
            default -> {
                RoadOrigin.Use u = from(s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst());
                s.sendMessage(Lang.msg(u.origin() ? "road.origin-show" : "road.origin-none", "from", fromWords(u)));
            }
        }
    }

    private Material material(String id, Material fallback) {
        Material m = id == null ? null : Material.matchMaterial(id);
        if (m == null || !m.isBlock()) {
            eco.getLogger().warning("[국도] 블록이 아님: " + id + " (" + fallback.getKey().getKey() + " 로)");
            return fallback;
        }
        return m;
    }

    private Path folder() {
        return eco.getDataFolder().toPath().resolve("roads");
    }

    private void save() {
        write(job);
    }

    private void write(RoadJob j) {
        known(j);
        try {
            Files.createDirectories(folder());
            Files.writeString(folder().resolve(j.file() + ".txt"), j.write(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            eco.getLogger().warning("[국도] 저장 실패: " + e);
        }
    }

    private RoadJob saved(String dock) {
        return savedFile(dock);
    }

    /** A dock's patch (/관리자 국도 이어짓기), or null. */
    private RoadJob savedTail(String dock) {
        return savedFile(dock + ".tail");
    }

    private RoadJob savedFile(String file) {
        Path f = folder().resolve(file + ".txt");
        try {
            return Files.exists(f) ? RoadJob.read(Files.readString(f, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 국도
    // ------------------------------------------------------------------------------------------

    private void command(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equals("상태")) {
            status(s);
            return;
        }
        if (a[0].equals("표지판")) {
            Dock d = a.length > 2 && a[1].equals("정리") ? eco.docks().dock(a[2]) : null;
            if (d == null) {
                s.sendMessage(a.length > 2 ? Lang.msg("dock.unknown", "name", a[2]) : Lang.msg("road.signs-usage"));
                return;
            }
            s.sendMessage(Lang.msg("road.signs-cleared", "dock", d.name(), "entities", clearSigns(d.name())));
            return;
        }
        if (a[0].equals("기준점")) {
            originCommand(s, a);
            return;
        }
        if (a[0].equals("중지")) {
            if (job == null || !job.running) {
                s.sendMessage(Lang.msg("road.none"));
                return;
            }
            job.running = false;
            save();
            s.sendMessage(Lang.msg("road.stopped", "dock", job.dock, "pct", Math.round(job.progress() * 100)));
            reset();
            return;
        }
        Dock d = eco.docks().dock(a[0]);
        if (d == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", a[0]));
            return;
        }
        if (job != null && job.running) {
            s.sendMessage(Lang.msg("road.busy", "dock", job.dock));
            return;
        }
        String how = a.length > 1 ? a[1] : "";
        boolean here = how.equals("여기서"), fresh = how.equals("새로"), atOrigin = how.equals("기준점");
        World w = Bukkit.getWorld(d.world());
        if (w == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", a[0]));
            return;
        }
        if (how.equals("이어짓기")) {
            patch(s, d, w);
            return;
        }
        RoadJob old = here || fresh || atOrigin ? null : saved(d.name());
        String fromText;
        if (old != null && old.phase != RoadJob.Phase.DONE) {
            old.running = true;
            job = old;
            fromText = Lang.raw("road.from-saved");
        } else {
            Location from;
            boolean fromOrigin = false;
            if (here) {
                if (!(s instanceof Player p) || !p.getWorld().equals(w)) {
                    s.sendMessage(Lang.msg("road.here-world"));
                    return;
                }
                from = p.getLocation();
                fromText = Lang.raw("road.from-here", "x", from.getBlockX(), "z", from.getBlockZ());
            } else {
                RoadOrigin.Use u = from(w);
                if (atOrigin && !u.origin()) {
                    s.sendMessage(Lang.msg("road.origin-missing"));
                    return;
                }
                if (!u.world().equals(w.getName())) {
                    // the reference point is in another world than the dock: from the dock world's spawn
                    s.sendMessage(Lang.msg("road.origin-other-world", "world", u.world()));
                    Location sp = w.getSpawnLocation();
                    u = new RoadOrigin.Use(w.getName(), sp.getX(), sp.getY(), sp.getZ(), false);
                }
                from = new Location(w, u.x(), u.y(), u.z());
                fromText = fromWords(u);
                fromOrigin = u.origin();
            }
            RoadJob j = new RoadJob();
            j.dock = d.name();
            // a new road to the dock: its number stays, the old road's signs go (a set of its own)
            RoadJob prev = saved(d.name());
            j.route = prev != null && prev.route > 0 ? prev.route : nextRoute();
            if (prev != null) {
                j.signs = prev.signs + 1;
                RoadSigns.remove(d.name());
            }
            j.world = w.getName();
            j.startX = from.getBlockX() + 0.5;
            j.startZ = from.getBlockZ() + 0.5;
            if (fromOrigin && origin != null && origin.directed()) {
                // a terminal's exit: straight out for a stretch first
                j.startX = origin.x();
                j.startZ = origin.z();
                j.leadX = origin.x() + origin.dx() * LEAD;
                j.leadZ = origin.z() + origin.dz() * LEAD;
            }
            int[] in = d.entrance(), gate = d.gate();
            j.endX = in[0] + 0.5;
            j.endZ = in[2] + 0.5;
            j.gateX = gate[0] + 0.5;
            j.gateZ = gate[2] + 0.5;
            j.gateY = gate[1];
            j.started = now();
            job = j;
        }
        admin = s instanceof Player p ? p.getUniqueId() : null;
        reset();
        save();
        double[] last = job.last();
        s.sendMessage(Lang.msg("road.started", "dock", job.dock, "phase", phaseName(job.phase),
                "length", Math.round(Math.hypot(last[0] - job.startX, last[1] - job.startZ)), "from", fromText));
    }

    /**
     * /관리자 국도 &lt;하역장&gt; 이어짓기: an unfinished road (or patch) goes on; a finished road that stops short of the dock's
     * gate (built before roads went up to it, or the dock's gate moved) gets the missing piece: a short road of its own
     * from the old road's end to the gate, built on the old road's level there, without touching the rest.
     */
    private void patch(CommandSender s, Dock d, World w) {
        RoadJob old = saved(d.name()), tail = savedTail(d.name());
        if (old == null) {
            s.sendMessage(Lang.msg("road.patch-none", "dock", d.name()));
            return;
        }
        RoadJob go;
        if (old.phase != RoadJob.Phase.DONE) go = old;
        else if (tail != null && tail.phase != RoadJob.Phase.DONE) go = tail;
        else {
            int[] gate = d.gate();
            double gx = gate[0] + 0.5, gz = gate[2] + 0.5;
            RoadJob from = tail != null ? tail : old;
            double[] end = from.last();
            if (Math.hypot(end[0] - gx, end[1] - gz) < 1) {
                s.sendMessage(Lang.msg("road.patch-connected", "dock", d.name()));
                return;
            }
            go = tailJob(from, gate);
        }
        go.running = true;
        job = go;
        admin = s instanceof Player p ? p.getUniqueId() : null;
        reset();
        save();
        double[] last = job.last();
        s.sendMessage(Lang.msg(job.tail ? "road.patch-started" : "road.patch-resumed", "dock", job.dock, "phase", phaseName(job.phase),
                "length", Math.round(Math.hypot(last[0] - job.startX, last[1] - job.startZ)), "x", Math.round(Math.floor(job.startX)),
                "z", Math.round(Math.floor(job.startZ))));
    }

    /** How far back along the old road a patch starts (points): room to meet the gate's level at the road's grade. */
    static final int TAIL_BACK = 16;

    /**
     * The missing piece of a finished road: from {@link #TAIL_BACK} points before its end, along it to its end, then
     * straight to the gate (no survey: it is short and in the open). Its first point stands on the old road, so it starts
     * at the old road's level (read from the world like any ground); the old road's last stretch is built again on the way.
     */
    static RoadJob tailJob(RoadJob old, int[] gate) {
        RoadJob j = new RoadJob();
        j.tail = true;
        j.dock = old.dock;
        j.world = old.world;
        j.route = old.route;
        j.signs = old.signs;
        List<double[]> pts = old.points();
        double[] end = old.last(), from = pts.isEmpty() ? end : pts.get(Math.max(0, pts.size() - 1 - TAIL_BACK));
        j.startX = from[0];
        j.startZ = from[1];
        j.endX = j.gateX = gate[0] + 0.5;
        j.endZ = j.gateZ = gate[2] + 0.5;
        j.gateY = gate[1];
        j.corners.add(new double[]{j.startX, j.startZ});
        if (Math.hypot(end[0] - from[0], end[1] - from[1]) > 0.5) j.corners.add(end.clone());
        j.corners.add(new double[]{j.endX, j.endZ});
        for (int i = 0; i < j.points().size(); i++) j.ground.add(new RoadJob.Ground());
        j.phase = RoadJob.Phase.PREPARE;
        return j;
    }

    private String phaseName(RoadJob.Phase p) {
        return Lang.raw("road.phases." + p.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void status(CommandSender s) {
        if (job == null) {
            s.sendMessage(Lang.msg("road.none"));
            return;
        }
        s.sendMessage(Lang.msg("road.status", "dock", job.dock, "phase", phaseName(job.phase), "running", job.running ? "진행 중" : "멈춤",
                "pct", Math.round(job.progress() * 100), "cells", job.cells.size(), "points", job.ground.size(),
                "loading", queue == null ? 0 : queue.left()));
    }

    /** Forgets what was in flight (a new phase, a stop): the next tick starts the phase again from the saved job. */
    private void reset() {
        if (queue != null) queue.stop();
        queue = null;
        waiting.clear();
        requested.clear();
        halves = null;
        cross = null;
        over = null;
        net = null;
        parts = null;
        points = null;
        builder = null;
        release();
    }

    private void release() {
        for (Chunk c : held.values()) c.removePluginChunkTicket(eco);
        held.clear();
    }

    private void tell(Component c) {
        Player p = admin == null ? null : Bukkit.getPlayer(admin);
        if (p != null) p.sendMessage(c);
        else Bukkit.getConsoleSender().sendMessage(c);
    }

    private void bar(Component c) {
        Player p = admin == null ? null : Bukkit.getPlayer(admin);
        if (p != null) p.sendActionBar(c);
    }

    // ------------------------------------------------------------------------------------------
    // Every tick
    // ------------------------------------------------------------------------------------------

    private void tick() {
        if (job == null || !job.running) return;
        World w = Bukkit.getWorld(job.world);
        if (w == null) return;
        ticks++;
        try {
            switch (job.phase) {
                case SURVEY -> survey(w);
                case PLAN -> plan();
                case PREPARE -> prepare(w);
                case BUILD -> build(w);
                case DONE -> { }
            }
        } catch (RuntimeException e) {
            eco.getLogger().log(java.util.logging.Level.WARNING, "[국도] 오류: 멈춤", e);
            job.running = false;
            save();
            reset();
            tell(Lang.msg("road.error"));
            return;
        }
        if (ticks % 200 == 0) save();
    }

    private void next(RoadJob.Phase phase) {
        job.phase = phase;
        reset();
        save();
        tell(Lang.msg("road.phase", "dock", job.dock, "phase", phaseName(phase)));
    }

    /** The corridor's chunks: within roads.corridor chunks of the straight line (and round both ends). */
    static Set<Long> corridor(double sx, double sz, double ex, double ez, int width) {
        Set<Long> out = new HashSet<>();
        double len = Math.hypot(ex - sx, ez - sz);
        int steps = Math.max(1, (int) Math.ceil(len / 8));
        for (int k = 0; k <= steps; k++) {
            int cx = (int) Math.floor((sx + (ex - sx) * k / steps)) >> 4, cz = (int) Math.floor((sz + (ez - sz) * k / steps)) >> 4;
            for (int dx = -width; dx <= width; dx++) for (int dz = -width; dz <= width; dz++) out.add(RoadJob.key(cx + dx, cz + dz));
        }
        return out;
    }

    private void survey(World w) {
        if (queue == null) {
            queue = new ChunkQueue(w, settings.concurrent(), c -> {
                int bx = c.getX() << 4, bz = c.getZ() << 4;
                int[] mid = sample(w, bx + 8, bz + 8);
                int lo = mid[0], hi = mid[0];
                for (int[] o : new int[][]{{2, 2}, {13, 2}, {2, 13}, {13, 13}}) {
                    int g = sample(w, bx + o[0], bz + o[1])[0];
                    lo = Math.min(lo, g);
                    hi = Math.max(hi, g);
                }
                boolean blocked = eco.land().playerLand(w.getName(), bx + 8, bz + 8);
                job.cells.put(RoadJob.key(c.getX(), c.getZ()),
                        new RoadJob.Cell(mid[0], mid[1] == 1 ? Math.max(1, mid[2] - mid[0]) : 0, hi - lo, blocked));
            });
            int n = 0;
            double[] from = job.from();
            for (long k : corridor(from[0], from[1], job.endX, job.endZ, settings.corridor())) {
                if (job.cells.containsKey(k)) continue;
                queue.add(RoadJob.cx(k), RoadJob.cz(k));
                n++;
            }
            tell(Lang.msg("road.survey", "dock", job.dock, "chunks", n));
        }
        queue.pump();
        if (ticks % 20 == 0) bar(Lang.mm("road.bar-survey", "dock", job.dock, "done", queue.done(), "left", queue.left()));
        if (queue.finished()) next(RoadJob.Phase.PLAN);
    }

    /** A column: its ground (top block of the ground, the sea bed under water), water or not, the surface's top. */
    static int[] sample(World w, int x, int z) {
        return dev.stsv.economy.dock.DockModule.sampleColumn(w, x, z);
    }

    private void plan() {
        RoadNetwork net = network(job);
        RoadPlanner.Field f = job.field(net);
        double[] from = job.from();
        int sx = (int) Math.floor(from[0]) >> 4, sz = (int) Math.floor(from[1]) >> 4;
        int ex = (int) Math.floor(job.endX) >> 4, ez = (int) Math.floor(job.endZ) >> 4;
        List<int[]> cells = RoadPlanner.path(f, sx, sz, ex, ez, RoadPlanner.Weights.DEFAULT, 400_000);
        if (cells.isEmpty()) {
            job.running = false;
            save();
            reset();
            tell(Lang.msg("road.no-route", "dock", job.dock));
            return;
        }
        // along the roads already there from the start: the new road begins where the route leaves them (a branch)
        RoadNetwork.Branch b = net.branch(cells, job.endX, job.endZ, settings.lightEvery());
        job.branchOf = null;
        if (b != null) {   // (no straight way out of the terminal: the road it follows has it)
            job.startX = b.x();
            job.startZ = b.z();
            job.branchOf = b.dock();
            job.branchKm = b.left() / 1000.0;
            cells = cells.subList(b.cell(), cells.size());
            tell(Lang.msg("road.branch", "dock", job.dock, "other", b.dock(), "x", Math.round(Math.floor(b.x())), "z", Math.round(Math.floor(b.z()))));
        }
        List<int[]> corners = RoadPlanner.smooth(f, cells, RoadPlanner.Weights.DEFAULT);
        job.route(corners);   // start ... the end in front of the gate, then the gate itself
        tell(Lang.msg("road.planned", "dock", job.dock, "length", job.ground.size(), "corners", job.corners.size(),
                "cost", Math.round(RoadPlanner.cost(f, cells, RoadPlanner.Weights.DEFAULT))));
        next(RoadJob.Phase.PREPARE);
    }

    private void prepare(World w) {
        if (queue == null) {
            points = job.points();
            Set<Long> chunks = new HashSet<>();
            for (int i = 0; i < points.size(); i++) {
                double[] p = points.get(i);
                int cx = (int) Math.floor(p[0]) >> 4, cz = (int) Math.floor(p[1]) >> 4;
                if (!job.ground.get(i).read()) waiting.computeIfAbsent(RoadJob.key(cx, cz), k -> new ArrayList<>()).add(i);
                int r = settings.pregen();
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) chunks.add(RoadJob.key(cx + dx, cz + dz));
            }
            // the chunks under the road first (their ground is needed), then the ones round it (generated, never changed)
            queue = new ChunkQueue(w, settings.concurrent(), c -> {
                List<Integer> want = waiting.remove(RoadJob.key(c.getX(), c.getZ()));
                if (want == null) return;
                for (int i : want) {
                    double[] p = points.get(i);
                    int[] s = sample(w, (int) Math.floor(p[0]), (int) Math.floor(p[1]));
                    RoadJob.Ground g = job.ground.get(i);
                    g.ground = s[0];
                    g.water = s[1] == 1;
                    g.surface = s[2];
                }
            });
            for (long k : waiting.keySet()) queue.add(RoadJob.cx(k), RoadJob.cz(k));
            for (long k : chunks) if (!waiting.containsKey(k)) queue.add(RoadJob.cx(k), RoadJob.cz(k));
        }
        queue.pump();
        if (ticks % 20 == 0) bar(Lang.mm("road.bar-prepare", "dock", job.dock, "done", queue.done(), "left", queue.left()));
        if (!queue.finished()) return;
        // a chunk that failed to load: its points take their neighbour's ground
        for (int i = 0; i < job.ground.size(); i++) {
            RoadJob.Ground g = job.ground.get(i);
            if (g.read()) continue;
            RoadJob.Ground n = i > 0 ? job.ground.get(i - 1) : null;
            g.ground = n != null && n.read() ? n.ground : 64;
            g.surface = g.ground;
        }
        next(RoadJob.Phase.BUILD);
    }

    private void profile() {
        points = job.points();
        int n = job.ground.size();
        int[] ground = new int[n], surface = new int[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            RoadJob.Ground g = job.ground.get(i);
            ground[i] = g.ground;
            water[i] = g.water;
            surface[i] = g.surface;
        }
        if (job.gated() && n > 0) {   // the last point is the gate: the road ends level with the dock's floor
            ground[n - 1] = job.gateY;
            water[n - 1] = false;
        }
        net = network(job);
        cross = net.crossings(points);
        RoadProfile.Plan plan = RoadProfile.planNetwork(ground, water, surface, cross);
        halves = plan.halves();
        over = plan.over();
        parts = RoadProfile.partsHalves(halves, ground, water, BRIDGE_GAP, TUNNEL_COVER, MIN_RUN);
        for (int i = 0; i < n; i++) if (over[i]) parts[i] = RoadProfile.Part.BRIDGE;   // over another road
        job.halves = halves;   // what roads built later keep clear of
    }

    /** The other roads already built in the job's world (every dock's but its own): {@link RoadNetwork}. */
    private RoadNetwork network(RoadJob j) {
        List<RoadNetwork.Road> roads = new ArrayList<>();
        try {
            Path dir = folder();
            if (!Files.isDirectory(dir)) return RoadNetwork.NONE;
            try (var files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".txt") && !p.getFileName().toString().equals(ORIGIN)).toList()) {
                    RoadJob o = RoadJob.read(Files.readString(f, StandardCharsets.UTF_8));
                    if (o.dock == null || o.dock.equals(j.dock) || !j.world.equals(o.world)) continue;
                    RoadNetwork.Road r = built(o);
                    if (r != null) roads.add(r);
                }
            }
        } catch (IOException | RuntimeException e) {
            eco.getLogger().warning("[국도] 다른 길을 읽지 못함: " + e);
        }
        return RoadNetwork.of(roads);
    }

    /** What of a saved road is built: its points up to where building got, their heights (worked out again for an older road). */
    static RoadNetwork.Road built(RoadJob o) {
        if (o.phase != RoadJob.Phase.BUILD && o.phase != RoadJob.Phase.DONE) return null;
        List<double[]> pts = o.points();
        int upTo = o.phase == RoadJob.Phase.DONE ? pts.size() : Math.min(o.cursor, pts.size());
        if (upTo <= 0 || o.ground.size() < pts.size()) return null;
        int[] h = o.halves;
        if (h == null || h.length != pts.size()) {   // saved before heights were: as that road was built (whole blocks)
            int n = pts.size();
            int[] g = new int[n], sf = new int[n];
            boolean[] wt = new boolean[n];
            for (int i = 0; i < n; i++) {
                g[i] = o.ground.get(i).ground;
                wt[i] = o.ground.get(i).water;
                sf[i] = o.ground.get(i).surface;
            }
            int[] lv = RoadProfile.plan(g, wt, sf);
            h = new int[n];
            for (int i = 0; i < n; i++) h[i] = lv[i] * 2;
        }
        return new RoadNetwork.Road(o.dock, pts.subList(0, upTo), java.util.Arrays.copyOf(h, upTo));
    }

    static final int BRIDGE_GAP = 3, TUNNEL_COVER = 5, MIN_RUN = 4, HALF = RoadSection.HALF, REACH = HALF + RoadSection.WIDEN + 1;

    private void build(World w) {
        if (halves == null) profile();
        if (queue == null) queue = new ChunkQueue(w, settings.concurrent(), c -> {
            c.addPluginChunkTicket(eco);
            held.put(RoadJob.key(c.getX(), c.getZ()), c);
        });
        // chunks ahead: asked for, held while the road passes
        for (int i = job.cursor; i < Math.min(points.size(), job.cursor + 48); i += 4) {
            double[] p = points.get(i);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int cx = ((int) Math.floor(p[0]) + dx * 8) >> 4, cz = ((int) Math.floor(p[1]) + dz * 8) >> 4;
                long k = RoadJob.key(cx, cz);
                if (requested.add(k)) queue.add(cx, cz);
            }
        }
        queue.pump();
        int done = 0;
        while (done < settings.perTick() && job.cursor < points.size()) {
            double[] p = points.get(job.cursor);
            if (!ready(w, p)) break;
            if (!section(w, job.cursor)) return;   // stopped (a player's land)
            job.cursor++;
            done++;
        }
        // let go of chunks well behind
        if (job.cursor < points.size() && ticks % 40 == 0) {
            double[] p = points.get(job.cursor);
            int cx = (int) Math.floor(p[0]) >> 4, cz = (int) Math.floor(p[1]) >> 4;
            held.entrySet().removeIf(e -> {
                boolean far = Math.abs(RoadJob.cx(e.getKey()) - cx) > 3 || Math.abs(RoadJob.cz(e.getKey()) - cz) > 3;
                if (far) {
                    e.getValue().removePluginChunkTicket(eco);
                    requested.remove(e.getKey());
                }
                return far;
            });
        }
        if (ticks % 20 == 0) bar(Lang.mm("road.bar-build", "dock", job.dock, "pct", Math.round(job.progress() * 100), "at", job.cursor,
                "total", points.size()));
        if (job.cursor >= points.size()) {
            job.phase = RoadJob.Phase.DONE;
            job.running = false;
            save();
            reset();
            tell(Lang.msg("road.done", "dock", job.dock, "length", job.ground.size()));
            eco.getLogger().info("[국도] " + job.dock + " 까지 " + job.ground.size() + " 블록 완성");
        }
    }

    private boolean ready(World w, double[] p) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (!w.isChunkLoaded(((int) Math.floor(p[0]) + dx * REACH) >> 4, ((int) Math.floor(p[1]) + dz * REACH) >> 4)) return false;
        return true;
    }

    /** One cross-section of the road at point i ({@link RoadSection}). False: it stopped (its surface would go into a player's land). */
    private boolean section(World w, int i) {
        if (builder == null) {
            if (job.route <= 0) {   // a road saved before roads had numbers
                job.route = nextRoute();
                save();
            }
            builder = new RoadSection(site(w), settings.style(), points, halves, parts, job.dock, job.route).network(cross, over);
            if (job.branchOf != null) builder.branch(job.branchOf, job.branchKm * 1000);
        }
        int[] land = builder.build(i);
        if (land == null) return true;
        job.running = false;
        save();
        reset();
        tell(Lang.msg("road.player-land", "dock", job.dock, "x", land[0], "z", land[1]));
        return false;
    }

    private static boolean natural(Material m) {
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || m == Material.VINE || m == Material.BEE_NEST || m == Material.MANGROVE_ROOTS
                || m == Material.MUSHROOM_STEM || m == Material.BROWN_MUSHROOM_BLOCK || m == Material.RED_MUSHROOM_BLOCK || m == Material.SNOW;
    }

    private final Map<String, BlockData> blockData = new HashMap<>();
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private static boolean joins(BlockData d) {
        return d instanceof Wall || d instanceof Fence || d instanceof GlassPane;
    }

    /**
     * A wall, fence or pane placed without physics stands alone (a post): this joins it to its neighbours (and them to it),
     * as placing it by hand would, so railings, guardrails and lamp arms run as one.
     */
    static void connect(Block b) {
        if (joins(b.getBlockData())) join(b);
        for (BlockFace f : SIDES) {
            Block n = b.getRelative(f);
            if (joins(n.getBlockData())) join(n);
        }
        Block below = b.getRelative(BlockFace.DOWN);
        if (below.getBlockData() instanceof Wall) join(below);   // a post under an arm stays a post
    }

    /** Sets a wall's or a fence's sides from what is next to it. */
    private static void join(Block b) {
        BlockData d = b.getBlockData();
        boolean changed = false;
        boolean[] on = new boolean[4];
        int count = 0;
        for (int k = 0; k < 4; k++) {
            Block n = b.getRelative(SIDES[k]);
            on[k] = joins(n.getBlockData()) || n.getType().isOccluding();
            if (on[k]) count++;
        }
        if (d instanceof Wall wall) {
            for (int k = 0; k < 4; k++) {
                Wall.Height h = on[k] ? Wall.Height.LOW : Wall.Height.NONE;
                if (wall.getHeight(SIDES[k]) != h) {
                    wall.setHeight(SIDES[k], h);
                    changed = true;
                }
            }
            boolean straight = count == 2 && ((on[0] && on[2]) || (on[1] && on[3]));
            BlockData above = b.getRelative(BlockFace.UP).getBlockData();
            boolean up = !straight || above instanceof Wall || above.getMaterial() == Material.LANTERN;
            if (wall.isUp() != up) {
                wall.setUp(up);
                changed = true;
            }
        } else if (d instanceof MultipleFacing mf) {
            for (int k = 0; k < 4; k++) {
                if (mf.getAllowedFaces().contains(SIDES[k]) && mf.hasFace(SIDES[k]) != on[k]) {
                    mf.setFace(SIDES[k], on[k]);
                    changed = true;
                }
            }
        }
        if (changed) b.setBlockData(d, false);
    }

    /** The world as {@link RoadSection} sees it. */
    private RoadSection.Site site(World w) {
        return new RoadSection.Site() {
            public boolean inside(int y) {
                return y >= w.getMinHeight() && y < w.getMaxHeight();
            }

            public boolean air(int x, int y, int z) {
                return !inside(y) || w.getBlockAt(x, y, z).getType().isAir();
            }

            public boolean liquid(int x, int y, int z) {
                return inside(y) && w.getBlockAt(x, y, z).isLiquid();
            }

            public boolean natural(int x, int y, int z) {
                return inside(y) && RoadModule.natural(w.getBlockAt(x, y, z).getType());
            }

            public boolean fillable(int x, int y, int z) {
                if (!inside(y)) return false;
                Block b = w.getBlockAt(x, y, z);
                Material m = b.getType();
                return m.isAir() || b.isLiquid() || b.isReplaceable() || !m.isSolid() || RoadModule.natural(m);
            }

            public void set(int x, int y, int z, Material m) {
                if (!inside(y)) return;
                Block b = w.getBlockAt(x, y, z);
                if (b.getType() != m) b.setType(m, false);
                RoadModule.connect(b);
            }

            public void set(int x, int y, int z, String data) {
                if (!inside(y)) return;
                Block b = w.getBlockAt(x, y, z);
                b.setBlockData(blockData.computeIfAbsent(data, Bukkit::createBlockData), false);
                RoadModule.connect(b);
            }

            public void roadText(double x, double y, double z, float yaw, String text) {
                if (!inside((int) Math.floor(y))) return;
                RoadSigns.paint(w, x, y, z, yaw, text, job.dock, job.signs, view());
            }

            public boolean playerLand(int x, int z) {
                return eco.land().playerLand(w.getName(), x, z);
            }

            public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {
                if (!inside((int) Math.floor(y))) return;
                RoadSigns.place(w, x, y, z, yaw, RoadSign.words(kind, dock, km, route), dock, job.signs, view());
            }

            public void junctionSign(double x, double y, double z, float yaw, RoadSign.Words words) {
                if (!inside((int) Math.floor(y))) return;
                RoadSigns.place(w, x, y, z, yaw, words, job.dock, job.signs, view());
            }

            public RoadNetwork.Spot road(int x, int z) {
                return net == null ? null : net.at(x, z);
            }

            public Material get(int x, int y, int z) {
                return inside(y) ? w.getBlockAt(x, y, z).getType() : Material.AIR;
            }
        };
    }
}
