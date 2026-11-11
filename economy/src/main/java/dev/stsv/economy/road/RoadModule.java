package dev.stsv.economy.road;

import dev.stsv.core.STSVPlugin;
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
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
 *       blocks, a km sign every roads.marker-every ({@link RoadProfile}). Never in a player's land: a column there is left
 *       alone (the shoulders), and the road stops if its surface would need one (the land was claimed after planning).</li>
 * </ol>
 */
public final class RoadModule extends EcoModule {

    record Settings(Material surface, Material line, Material kerb, double bonus, int lightEvery, int markerEvery, int corridor, int pregen,
                    int perTick, int concurrent) {}

    private Settings settings;
    private RoadJob job;
    private UUID admin;
    private ChunkQueue queue;
    /** PREPARE: points waiting for their chunk; BUILD: the road's heights and parts */
    private final Map<Long, List<Integer>> waiting = new HashMap<>();
    private int[] levels;
    private RoadProfile.Part[] parts;
    private List<double[]> points;
    private final Map<Long, Chunk> held = new HashMap<>();
    private final Set<Long> requested = new HashSet<>();
    private long ticks;

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
            Material m = b.getType();
            return m == settings.surface() || m == settings.line() ? settings.bonus() : 1;
        });
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "국도", "<하역장> [여기서|새로] | 중지 | 상태",
                "국도: 건축월드 스폰(또는 지금 자리)에서 하역장까지 길 놓기 (멈췄던 것은 이어서, 새로 = 처음부터) / 멈추기 / 진행 상황",
                this::command, (s, a) -> {
                    if (a.length == 1) {
                        List<String> out = new ArrayList<>(eco.docks().names());
                        out.add("중지");
                        out.add("상태");
                        return out;
                    }
                    return a.length == 2 ? List.of("여기서", "새로") : List.of();
                });
        // a road that was being built when the server stopped goes on
        try {
            Path dir = folder();
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".txt")).toList()) {
                        RoadJob j = RoadJob.read(Files.readString(f, StandardCharsets.UTF_8));
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
                Math.clamp(eco.getConfig().getDouble("roads.speed-bonus"), 1, 2), Math.max(8, eco.getConfig().getInt("roads.light-every")),
                Math.max(100, eco.getConfig().getInt("roads.marker-every")), Math.clamp(eco.getConfig().getInt("roads.corridor"), 2, 24),
                Math.clamp(eco.getConfig().getInt("roads.pregen-radius"), 0, 6), Math.clamp(eco.getConfig().getInt("roads.build-per-tick"), 1, 20),
                Math.clamp(eco.getConfig().getInt("roads.concurrent"), 1, 16));
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
        try {
            Files.createDirectories(folder());
            Files.writeString(folder().resolve(job.dock + ".txt"), job.write(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            eco.getLogger().warning("[국도] 저장 실패: " + e);
        }
    }

    private RoadJob saved(String dock) {
        Path f = folder().resolve(dock + ".txt");
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
        boolean here = a.length > 1 && a[1].equals("여기서"), fresh = a.length > 1 && a[1].equals("새로");
        World w = Bukkit.getWorld(d.world());
        if (w == null) {
            s.sendMessage(Lang.msg("dock.unknown", "name", a[0]));
            return;
        }
        RoadJob old = here || fresh ? null : saved(d.name());
        if (old != null && old.phase != RoadJob.Phase.DONE) {
            old.running = true;
            job = old;
        } else {
            Location from;
            if (here) {
                if (!(s instanceof Player p) || !p.getWorld().equals(w)) {
                    s.sendMessage(Lang.msg("road.here-world"));
                    return;
                }
                from = p.getLocation();
            } else {
                from = w.getSpawnLocation();
            }
            RoadJob j = new RoadJob();
            j.dock = d.name();
            j.world = w.getName();
            j.startX = from.getBlockX() + 0.5;
            j.startZ = from.getBlockZ() + 0.5;
            int[] gate = d.entrance();
            j.endX = gate[0] + 0.5;
            j.endZ = gate[2] + 0.5;
            j.started = now();
            job = j;
        }
        admin = s instanceof Player p ? p.getUniqueId() : null;
        reset();
        save();
        s.sendMessage(Lang.msg("road.started", "dock", job.dock, "phase", phaseName(job.phase),
                "length", Math.round(Math.hypot(job.endX - job.startX, job.endZ - job.startZ))));
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
        levels = null;
        parts = null;
        points = null;
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
            for (long k : corridor(job.startX, job.startZ, job.endX, job.endZ, settings.corridor())) {
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
        RoadPlanner.Field f = job.field();
        int sx = (int) Math.floor(job.startX) >> 4, sz = (int) Math.floor(job.startZ) >> 4;
        int ex = (int) Math.floor(job.endX) >> 4, ez = (int) Math.floor(job.endZ) >> 4;
        List<int[]> cells = RoadPlanner.path(f, sx, sz, ex, ez, RoadPlanner.Weights.DEFAULT, 400_000);
        if (cells.isEmpty()) {
            job.running = false;
            save();
            reset();
            tell(Lang.msg("road.no-route", "dock", job.dock));
            return;
        }
        List<int[]> corners = RoadPlanner.smooth(f, cells, RoadPlanner.Weights.DEFAULT);
        job.corners.clear();
        job.corners.add(new double[]{job.startX, job.startZ});
        for (int i = 1; i < corners.size() - 1; i++) job.corners.add(new double[]{corners.get(i)[0] * 16 + 8.5, corners.get(i)[1] * 16 + 8.5});
        job.corners.add(new double[]{job.endX, job.endZ});
        job.ground.clear();
        for (int i = 0; i < job.points().size(); i++) job.ground.add(new RoadJob.Ground());
        job.cursor = 0;
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
        levels = RoadProfile.plan(ground, water, surface);
        parts = RoadProfile.parts(levels, ground, water, BRIDGE_GAP, TUNNEL_COVER, MIN_RUN);
    }

    static final int BRIDGE_GAP = 3, TUNNEL_COVER = 5, MIN_RUN = 4, HALF = 5;

    private void build(World w) {
        if (levels == null) profile();
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
            if (!w.isChunkLoaded(((int) Math.floor(p[0]) + dx * (HALF + 1)) >> 4, ((int) Math.floor(p[1]) + dz * (HALF + 1)) >> 4)) return false;
        return true;
    }

    /** One cross-section of the road at point i. False: it stopped (its surface would go into a player's land). */
    private boolean section(World w, int i) {
        double[] p = points.get(i);
        double[] a = points.get(Math.max(0, i - 2)), b = points.get(Math.min(points.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        hx /= len;
        hz /= len;
        double rx = -hz, rz = hx;   // the right hand
        int level = levels[i];
        RoadProfile.Part part = parts[i];
        Set<Long> done = new HashSet<>();
        for (double o = -HALF - 0.5; o <= HALF + 0.5; o += 0.5) {
            int bx = (int) Math.floor(p[0] + rx * o), bz = (int) Math.floor(p[1] + rz * o);
            if (!done.add(RoadJob.key(bx, bz))) continue;
            double ao = Math.abs(o);
            if (eco.land().playerLand(w.getName(), bx, bz)) {
                if (ao <= 3.5) {
                    job.running = false;
                    save();
                    reset();
                    tell(Lang.msg("road.player-land", "dock", job.dock, "x", bx, "z", bz));
                    return false;
                }
                continue;   // a shoulder in someone's land: left alone
            }
            boolean dash = (i / 4) % 2 == 0;
            Material top = ao <= 0.5 && dash ? settings.line() : ao <= 2.5 ? settings.surface() : settings.kerb();
            switch (part) {
                case ROAD -> road(w, bx, bz, level, ao, top);
                case BRIDGE -> bridge(w, bx, bz, level, ao, top, i % 10 == 0);
                case TUNNEL -> tunnel(w, bx, bz, level, ao, top, i % 8 == 0);
            }
        }
        if (i % settings.lightEvery() == 0 && part != RoadProfile.Part.TUNNEL && i > 0 && i < points.size() - 1) {
            int side = (i / settings.lightEvery()) % 2 == 0 ? 1 : -1;
            double o = part == RoadProfile.Part.BRIDGE ? 3.25 : 4.5;
            lamp(w, (int) Math.floor(p[0] + rx * o * side), (int) Math.floor(p[1] + rz * o * side), level);
        }
        if (i > 0 && i % settings.markerEvery() == 0 && part == RoadProfile.Part.ROAD) {
            int sx = (int) Math.floor(p[0] + rx * 5), sz = (int) Math.floor(p[1] + rz * 5);
            if (!eco.land().playerLand(w.getName(), sx, sz)) marker(w, sx, sz, level + 1, -hx, -hz, i);
        }
        return true;
    }

    private static boolean natural(Material m) {
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || m == Material.VINE || m == Material.BEE_NEST || m == Material.MANGROVE_ROOTS
                || m == Material.MUSHROOM_STEM || m == Material.BROWN_MUSHROOM_BLOCK || m == Material.RED_MUSHROOM_BLOCK || m == Material.SNOW;
    }

    private static void set(World w, int x, int y, int z, Material m) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return;
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) b.setType(m, false);
    }

    private static void air(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return;
        Block b = w.getBlockAt(x, y, z);
        if (!b.getType().isAir()) b.setType(Material.AIR, false);
    }

    /** Trees and the like over a column, up to 30 above the road. */
    private static void clearTrees(World w, int x, int z, int from) {
        for (int y = from; y < Math.min(w.getMaxHeight(), from + 30); y++) {
            Block b = w.getBlockAt(x, y, z);
            if (natural(b.getType())) b.setType(Material.AIR, false);
        }
    }

    private static boolean fillable(Block b) {
        Material m = b.getType();
        return m.isAir() || b.isLiquid() || b.isReplaceable() || !m.isSolid() || natural(m);
    }

    private void road(World w, int x, int z, int level, double ao, Material top) {
        if (ao <= 3.5) {
            set(w, x, level, z, top);
            for (int y = level + 1; y <= level + 5; y++) air(w, x, y, z);   // cut
            for (int k = 1; k <= 8; k++) {                                   // fill
                Block b = w.getBlockAt(x, level - k, z);
                if (b.getY() < w.getMinHeight() || !fillable(b)) break;
                b.setType(k == 1 ? Material.STONE : Material.COBBLESTONE, false);
            }
            clearTrees(w, x, z, level + 6);
            return;
        }
        // the shoulders: cut the bank down to the road, fill a dip to it (grass on top), clear trees
        for (int y = level + 1; y <= level + 4; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isAir() && y >= w.getMinHeight() && y < w.getMaxHeight()) b.setType(Material.AIR, false);
        }
        Block at = w.getBlockAt(x, level, z);
        if (fillable(at) && !at.isLiquid()) {
            at.setType(Material.GRASS_BLOCK, false);
            for (int k = 1; k <= 3; k++) {
                Block b = w.getBlockAt(x, level - k, z);
                if (!fillable(b) || b.isLiquid()) break;
                b.setType(Material.DIRT, false);
            }
        }
        clearTrees(w, x, z, level + 1);
    }

    private void bridge(World w, int x, int z, int level, double ao, Material top, boolean pillar) {
        if (ao > 3.5) return;
        set(w, x, level, z, ao > 2.5 ? Material.STONE_BRICKS : top);
        for (int y = level + 1; y <= level + 4; y++) air(w, x, y, z);
        if (ao > 2.5) set(w, x, level + 1, z, Material.STONE_BRICK_WALL);   // the railing
        if (pillar && ao <= 2.5) {
            for (int k = 1; k <= 64; k++) {
                Block b = w.getBlockAt(x, level - k, z);
                if (b.getY() < w.getMinHeight() || !fillable(b)) break;
                b.setType(Material.STONE_BRICKS, false);
            }
        }
        clearTrees(w, x, z, level + 5);
    }

    private void tunnel(World w, int x, int z, int level, double ao, Material top, boolean light) {
        if (ao <= 3.5) {
            set(w, x, level, z, top);
            for (int y = level + 1; y <= level + 4; y++) air(w, x, y, z);
            set(w, x, level + 5, z, light && ao <= 0.5 ? Material.SEA_LANTERN : Material.STONE_BRICKS);
        } else if (ao <= 4.5) {
            for (int y = level; y <= level + 5; y++) set(w, x, y, z, Material.STONE_BRICKS);
        }
    }

    private void lamp(World w, int x, int z, int level) {
        if (eco.land().playerLand(w.getName(), x, z)) return;
        for (int y = level + 1; y <= level + 3; y++) set(w, x, y, z, Material.STONE_BRICK_WALL);
        set(w, x, level + 4, z, Material.LANTERN);
        Block base = w.getBlockAt(x, level, z);
        if (fillable(base)) base.setType(Material.STONE_BRICKS, false);
    }

    private void marker(World w, int x, int z, int y, double fx, double fz, int at) {
        Block b = w.getBlockAt(x, y, z);
        Block under = w.getBlockAt(x, y - 1, z);
        if (fillable(under)) under.setType(Material.STONE_BRICKS, false);
        BlockData data = Material.SPRUCE_SIGN.createBlockData();
        // facing the traffic coming from the start: the sign's front towards (fx, fz)
        double yaw = Math.toDegrees(Math.atan2(-fx, fz));
        if (data instanceof Rotatable r) r.setRotation(face((int) Math.round(yaw / 22.5)));
        b.setBlockData(data, false);
        if (b.getState() instanceof org.bukkit.block.Sign s) {
            int total = points.size();
            s.getSide(Side.FRONT).line(0, Component.text("[국도]"));
            s.getSide(Side.FRONT).line(1, Component.text(job.dock + " 하역장"));
            s.getSide(Side.FRONT).line(2, Component.text(at / 1000 + " km 지점"));
            s.getSide(Side.FRONT).line(3, Component.text("남은 길 " + Math.max(0, (total - at) / 1000) + " km"));
            s.setWaxed(true);
            s.update(true, false);
        }
    }

    private static BlockFace face(int r) {
        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST, BlockFace.WEST,
                BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST, BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST,
                BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST, BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST};
        return faces[Math.floorMod(r, 16)];
    }
}
