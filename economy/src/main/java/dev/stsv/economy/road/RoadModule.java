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
    private RoadSection builder;
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
        builder = null;   // the new blocks and spacings from the next cross-section on
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

    static final int BRIDGE_GAP = 3, TUNNEL_COVER = 5, MIN_RUN = 4, HALF = RoadSection.HALF;

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

    /** One cross-section of the road at point i ({@link RoadSection}). False: it stopped (its surface would go into a player's land). */
    private boolean section(World w, int i) {
        if (builder == null) builder = new RoadSection(site(w), new RoadSection.Style(settings.surface(), settings.line(), settings.kerb(),
                settings.lightEvery(), settings.markerEvery()), points, levels, parts, job.dock);
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
            }

            public boolean playerLand(int x, int z) {
                return eco.land().playerLand(w.getName(), x, z);
            }

            public void sign(int x, int y, int z, BlockFace facing, String[] lines) {
                if (!inside(y)) return;
                Block b = w.getBlockAt(x, y, z);
                BlockData data = Material.SPRUCE_SIGN.createBlockData();
                if (data instanceof Rotatable r) r.setRotation(facing);
                b.setBlockData(data, false);
                if (b.getState() instanceof org.bukkit.block.Sign s) {
                    for (int k = 0; k < lines.length && k < 4; k++) s.getSide(Side.FRONT).line(k, Component.text(lines[k]));
                    s.setWaxed(true);
                    s.update(true, false);
                }
            }
        };
    }
}
