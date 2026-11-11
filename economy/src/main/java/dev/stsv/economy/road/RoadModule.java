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
import org.bukkit.event.block.BlockFormEvent;
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

    /**
     * @param blocksPerTick BUILD: blocks set a tick ({@link BlockBudget}: a cross-section's blocks, railings joined included)
     * @param gate          when SURVEY, PREPARE, BUILD and the sign / rail batches pause or slow down, chunk loads at once
     */
    record Settings(Material surface, Material line, Material kerb, Material edge, double bonus, int lightEvery, int markerEvery, int corridor,
                    int pregen, int blocksPerTick, LoadGate.Settings gate) {

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
    /** the saved roads, written off the main thread */
    private JobFiles files;
    /** BUILD's blocks a tick, and the blocks set so far (the world as RoadSection sees it counts them: {@link #site}) */
    private BlockBudget budget;
    private long setCount;
    /** what the server lets the work do this tick ({@link LoadGate}); when a pause was last logged (ticks) */
    private LoadGate.Allow allow = new LoadGate.Allow(true, 1, 1);
    private long pausedNote = -100_000;
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
        files = new JobFiles(folder(), m -> eco.getLogger().warning(m));
        load();
        if (core.vehicles() != null) core.vehicles().surfaceBonus(b -> {
            return paved.contains(b.getType()) ? settings.bonus() : 1;
        });
        STSVPlugin.get().admin().add(EcoPlugin.ADMIN_OWNER, "국도",
                "<하역장> [기준점|여기서|새로|이어짓기] | 기준점 [설정|보기|초기화] | 중지 | 상태 | 표지판 정리 <하역장> | "
                        + "표지판 다시세우기 [하역장|전체] | 난간고치기 [하역장|전체] | 눈치우기 [반경]",
                "국도: 기준점(없으면 건축월드 스폰, 여기서 = 지금 자리)에서 하역장 입구까지 길 놓기 (멈췄던 것은 이어서, 새로 = 처음부터, "
                        + "이어짓기 = 다 지은 길의 끝에서 입구까지 모자란 곳만) / 기준점 정하기 / 멈추기 / 진행 상황 / 그 길의 표지판 모두 치우기 / "
                        + "다 지은 길의 표지판(갈림길 안내·돌아가는 길·km)을 저장된 길에서 다시 계산해 바꿔 세우기 / 반 칸 위에 떠 있던 난간·기둥 밑에 받침 넣기 / "
                        + "둘레(기본 64, 최대 " + SNOW_MAX + "블록) 도로·하역장·터미널의 눈 치우기",
                this::command, (s, a) -> {
                    if (a.length == 1) {
                        List<String> out = new ArrayList<>(eco.docks().names());
                        out.addAll(List.of("기준점", "중지", "상태", "표지판", "눈치우기", "난간고치기"));
                        return out;
                    }
                    if (a[0].equals("기준점")) return a.length == 2 ? List.of("설정", "보기", "초기화") : List.of();
                    if (a[0].equals("난간고치기")) return a.length == 2 ? withAll() : List.of();
                    if (a[0].equals("표지판")) return a.length == 2 ? List.of("정리", "다시세우기")
                            : a.length == 3 ? (a[1].equals("다시세우기") ? withAll() : new ArrayList<>(eco.docks().names())) : List.of();
                    return a.length == 2 ? List.of("기준점", "여기서", "새로", "이어짓기") : List.of();
                });
        // a road that was being built when the server stopped goes on
        try {
            for (String text : files.texts(ORIGIN)) {
                RoadJob j = RoadJob.read(text);
                if (j.dock == null) continue;
                known(j);
                if (j.running && j.phase != RoadJob.Phase.DONE && job == null) {
                    job = j;
                    eco.getLogger().info("[국도] " + j.dock + " 까지 짓던 길을 이어서 (" + j.phase + ")");
                }
            }
        } catch (IOException | RuntimeException e) {
            eco.getLogger().warning("[국도] 저장된 작업을 읽지 못함: " + e);
        }
        every(1L, this::tick);
        snowNets.refresh();
    }

    @Override
    public void reload() {
        load();
        snowNets.refresh();
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

    // ------------------------------------------------------------------------------------------
    // Snow: never settles on a road (its lanes, kerbs, shoulders) nor in a dock's or terminal's chunks
    // ------------------------------------------------------------------------------------------

    /** Every road built, by world, built off the main thread (SnowNets): a snow event only looks a column up. */
    private final SnowNets snowNets = new SnowNets(this::buildSnowNets, r -> Bukkit.getScheduler().runTaskAsynchronously(eco, r));

    /** Every road built in this world, as last read (a road saved or finished, a reload: read again, off the main thread). */
    RoadNetwork roads(String world) {
        return snowNets.get(world);
    }

    /** Reads every saved road (on an async thread: files and pure work only) and lays out each world's network. */
    private Map<String, RoadNetwork> buildSnowNets() {
        Map<String, List<RoadNetwork.Road>> byWorld = new HashMap<>();
        try {
            for (String text : files.texts(ORIGIN)) {
                RoadJob o = RoadJob.read(text);
                if (o.dock == null || o.world == null) continue;
                RoadNetwork.Road r = built(o);
                if (r != null) byWorld.computeIfAbsent(o.world, k -> new ArrayList<>()).add(r);
            }
        } catch (IOException | RuntimeException e) {
            eco.getLogger().warning("[국도] 눈 막기용 길을 읽지 못함: " + e);
        }
        Map<String, RoadNetwork> out = new HashMap<>();
        byWorld.forEach((w, rs) -> out.put(w, RoadNetwork.of(rs)));
        return out;
    }

    /** Whether snow may not lie at this block (it is over a road's surface, or in a dock's or terminal's chunks). */
    boolean snowFree(Block b) {
        String w = b.getWorld().getName();
        if (eco.docks() != null && eco.docks().covers(w, b.getX() >> 4, b.getZ() >> 4)) return true;
        RoadNetwork.Spot spot = roads(w).at(b.getX(), b.getZ());
        return spot != null && b.getY() >= spot.y() && b.getY() <= spot.y() + 3;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSnow(BlockFormEvent e) {
        Material m = e.getNewState().getType();
        if ((m == Material.SNOW || m == Material.POWDER_SNOW) && snowFree(e.getBlock())) e.setCancelled(true);
    }

    /** The widest /관리자 국도 눈치우기 (blocks each way): 128 is about 66k columns, done at once. */
    static final int SNOW_MAX = 128;

    /** /관리자 국도 눈치우기 [반경]: snow layers on roads and in docks and terminals round the player go (loaded chunks). */
    private int clearSnow(Player p, int radius) {
        World w = p.getWorld();
        int px = p.getLocation().getBlockX(), pz = p.getLocation().getBlockZ(), n = 0;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block top = w.getHighestBlockAt(x, z);
                for (Block b = top; b.getY() >= top.getY() - 2; b = b.getRelative(BlockFace.DOWN)) {
                    if ((b.getType() == Material.SNOW || b.getType() == Material.POWDER_SNOW) && snowFree(b)) {
                        b.setType(Material.AIR, false);
                        n++;
                    }
                }
            }
        }
        return n;
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
            String also = RoadSigns.also(en);   // a junction's sign: it goes with either road's signs
            boolean old = also != null && ((eco.docks() != null && eco.docks().dock(also) == null) || RoadSigns.alsoSet(en) != sets.getOrDefault(also, 0));
            if (old || (eco.docks() != null && eco.docks().dock(dock) == null) || RoadSigns.set(en) != sets.getOrDefault(dock, 0)) {
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
        for (Batch b : batches) b.finish();
        batches.clear();
        files.close();   // what is waiting is written now
    }

    private void load() {
        settings = new Settings(material(eco.getConfig().getString("roads.surface"), Material.GRAY_CONCRETE),
                material(eco.getConfig().getString("roads.center-line"), Material.YELLOW_CONCRETE),
                material(eco.getConfig().getString("roads.kerb"), Material.SMOOTH_STONE),
                material(eco.getConfig().getString("roads.edge-line"), Material.SMOOTH_QUARTZ),
                Math.clamp(eco.getConfig().getDouble("roads.speed-bonus"), 1, 2), Math.max(8, eco.getConfig().getInt("roads.light-every")),
                Math.max(100, eco.getConfig().getInt("roads.marker-every")), Math.clamp(eco.getConfig().getInt("roads.corridor"), 2, 24),
                Math.clamp(eco.getConfig().getInt("roads.pregen-radius"), 0, 6), Math.clamp(eco.getConfig().getInt("roads.blocks-per-tick"), 50, 20000),
                new LoadGate.Settings(eco.getConfig().getDouble("roads.max-mspt"), Math.clamp(eco.getConfig().getInt("roads.concurrent"), 1, 16),
                        Math.clamp(eco.getConfig().getInt("roads.concurrent-online"), 1, 16)));
        budget = new BlockBudget(settings.blocksPerTick());
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

    /** Saves a road: a copy taken now, its text made and written off the main thread ({@link JobFiles}; reads see it at once). */
    private void write(RoadJob j) {
        known(j);
        RoadJob copy = j.snapshot();
        files.write(j.file() + ".txt", copy::write);
        if (j.phase == RoadJob.Phase.BUILD || j.phase == RoadJob.Phase.DONE) snowNets.refresh();   // more road is built: read again, off the main thread
    }

    private RoadJob saved(String dock) {
        return savedFile(dock);
    }

    /** A dock's patch (/관리자 국도 이어짓기), or null. */
    private RoadJob savedTail(String dock) {
        return savedFile(dock + ".tail");
    }

    private RoadJob savedFile(String file) {
        try {
            String text = files.read(file + ".txt");
            return text == null ? null : RoadJob.read(text);
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
        if (a[0].equals("눈치우기")) {
            if (!(s instanceof Player p)) return;
            int r = 64;
            try {
                if (a.length > 1) r = Math.clamp(Integer.parseInt(a[1]), 1, SNOW_MAX);
            } catch (NumberFormatException ignored) {
            }
            s.sendMessage(Lang.msg("road.snow-cleared", "blocks", clearSnow(p, r), "radius", r));
            return;
        }
        if (a[0].equals("난간고치기") || (a[0].equals("표지판") && a.length > 1 && a[1].equals("다시세우기"))) {
            boolean rails = a[0].equals("난간고치기");
            String target = a.length > (rails ? 1 : 2) ? a[rails ? 1 : 2] : ALL;
            if (!target.equals(ALL) && eco.docks().dock(target) == null) {
                s.sendMessage(Lang.msg("dock.unknown", "name", target));
                return;
            }
            if (!target.equals(ALL)) target = eco.docks().dock(target).name();
            Player p = s instanceof Player pl ? pl : null;
            UUID who = p == null ? null : p.getUniqueId();
            java.util.function.Consumer<Component> to = c -> {
                Player q = who == null ? null : Bukkit.getPlayer(who);
                if (q != null) q.sendMessage(c);
                else Bukkit.getConsoleSender().sendMessage(c);
            };
            java.util.function.Consumer<Component> bar = c -> {
                Player q = who == null ? null : Bukkit.getPlayer(who);
                if (q != null) q.sendActionBar(c);
            };
            if (rails) fixRails(to, bar, target);
            else resign(to, bar, target);
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
        boolean working = job != null && job.running;
        if (batches.isEmpty() && !working) return;
        allow = LoadGate.now(settings.gate());   // the server busy: pause or slow down (chunks asked for, blocks set)
        if (!allow.run() && ticks - pausedNote >= 1200) {
            pausedNote = ticks;
            eco.getLogger().info(String.format(java.util.Locale.ROOT, "[국도] 서버가 바빠서 (평균 %.1f ms/틱, 한도 %.0f) 잠시 쉼",
                    Bukkit.getServer().getAverageTickTime(), settings.gate().maxMspt()));
        }
        if (!batches.isEmpty()) {
            Batch b = batches.peek();
            try {
                if (b.tick()) {
                    batches.poll();
                    b.finish();
                }
            } catch (RuntimeException e) {
                eco.getLogger().log(java.util.logging.Level.WARNING, "[국도] " + b.what + " 오류: 멈춤", e);
                batches.poll();
                b.finish();
            }
        }
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
            queue = new ChunkQueue(w, settings.gate().most(), c -> {
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
        queue.pump(allow.concurrent());
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
            queue = new ChunkQueue(w, settings.gate().most(), c -> {
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
        queue.pump(allow.concurrent());
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
            for (String text : files.texts(ORIGIN)) {
                RoadJob o = RoadJob.read(text);
                if (o.dock == null || o.dock.equals(j.dock) || !j.world.equals(o.world)) continue;
                RoadNetwork.Road r = built(o);
                if (r != null) roads.add(r);
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

    // ------------------------------------------------------------------------------------------
    // Signs put up again (/관리자 국도 표지판 다시세우기), rails' plinths (/관리자 국도 난간고치기)
    // ------------------------------------------------------------------------------------------

    static final String ALL = "전체";

    private List<String> withAll() {
        List<String> out = new ArrayList<>(eco.docks().names());
        out.add(ALL);
        return out;
    }

    /**
     * A finished road as the guide sees it ({@link RoadGuide.Road}): its points, heights (worked out again for an older
     * road) and parts, with its patch (/관리자 국도 이어짓기) in place of its last stretch. Null: not finished.
     */
    static RoadGuide.Road guideRoad(RoadJob main, RoadJob tail, String home) {
        if (main == null || main.phase != RoadJob.Phase.DONE) return null;
        RoadNetwork.Road m = built(main);
        if (m == null) return null;
        List<double[]> pts = new ArrayList<>(m.points());
        int[] h = m.halves();
        RoadProfile.Part[] parts = parts(main, h);
        RoadNetwork.Road t = tail != null && tail.phase == RoadJob.Phase.DONE ? built(tail) : null;
        if (t != null) {   // the patch starts TAIL_BACK points before the old end, on the old road
            int cut = Math.max(0, pts.size() - 1 - TAIL_BACK);
            int[] th = t.halves();
            RoadProfile.Part[] tp = parts(tail, th);
            List<double[]> all = new ArrayList<>(pts.subList(0, cut));
            all.addAll(t.points());
            int[] hh = java.util.Arrays.copyOf(h, cut + th.length);
            System.arraycopy(th, 0, hh, cut, th.length);
            RoadProfile.Part[] pp = java.util.Arrays.copyOf(parts, cut + tp.length);
            System.arraycopy(tp, 0, pp, cut, tp.length);
            pts = all;
            h = hh;
            parts = pp;
        }
        return new RoadGuide.Road(main.dock, pts, h, parts, main.route, home);
    }

    /** What a saved road is at each point (road, bridge, tunnel) from its heights and the ground it read. */
    static RoadProfile.Part[] parts(RoadJob j, int[] halves) {
        int n = halves.length;
        int[] ground = new int[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            RoadJob.Ground g = j.ground.get(i);
            ground[i] = g.ground;
            water[i] = g.water;
        }
        if (j.gated() && n == j.ground.size() && n > 0) {
            ground[n - 1] = j.gateY;
            water[n - 1] = false;
        }
        return RoadProfile.partsHalves(halves, ground, water, BRIDGE_GAP, TUNNEL_COVER, MIN_RUN);
    }

    /** Every saved road (its job and its patch, or null) by world, then dock. */
    private Map<String, Map<String, RoadJob[]>> savedRoads() {
        Map<String, Map<String, RoadJob[]>> out = new java.util.TreeMap<>();
        try {
            for (String text : files.texts(ORIGIN)) {   // by file name
                RoadJob o = RoadJob.read(text);
                if (o.dock == null || o.world == null) continue;
                RoadJob[] pair = out.computeIfAbsent(o.world, k -> new java.util.TreeMap<>()).computeIfAbsent(o.dock, k -> new RoadJob[2]);
                pair[o.tail ? 1 : 0] = o;
            }
        } catch (IOException | RuntimeException e) {
            eco.getLogger().warning("[국도] 저장된 길을 읽지 못함: " + e);
        }
        return out;
    }

    /**
     * What the way back from a road's start is called, and which docks still stand: taken on the main thread (the
     * terminals, the reference point), used off it.
     */
    record Homes(List<Dock> terminals, RoadOrigin origin, Set<String> docks) {
        /** The terminal there, the reference point, else its start. */
        String home(String world, double x, double z) {
            for (Dock t : terminals) {
                int[] g = t.gate();
                if (t.world().equals(world) && Math.hypot(g[0] + 0.5 - x, g[2] + 0.5 - z) <= 32) return t.name() + " 출발 터미널";
            }
            if (origin != null && origin.world().equals(world) && Math.hypot(origin.x() - x, origin.z() - z) <= 32) return "국도 기준점";
            return "출발점";
        }

        boolean stands(String dock) {
            return docks == null || docks.contains(dock.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private Homes homes() {
        if (eco.docks() == null) return new Homes(List.of(), origin, null);
        Set<String> docks = new HashSet<>();
        for (String n : eco.docks().names()) docks.add(n.toLowerCase(java.util.Locale.ROOT));
        return new Homes(List.copyOf(eco.docks().terminals()), origin, docks);
    }

    /** Every finished road of a world whose dock still stands, for the guide (pure: off the main thread). */
    static List<RoadGuide.Road> guideRoads(Homes homes, String world, Map<String, RoadJob[]> jobs) {
        List<RoadGuide.Road> out = new ArrayList<>();
        for (RoadJob[] pair : jobs.values()) {
            RoadJob m = pair[0];
            if (m == null || m.phase != RoadJob.Phase.DONE || !homes.stands(m.dock)) continue;
            double[] from = m.points().isEmpty() ? new double[]{m.startX, m.startZ} : m.points().getFirst();
            RoadGuide.Road r = guideRoad(m, pair[1], homes.home(world, from[0], from[1]));
            if (r != null) out.add(r);
        }
        return out;
    }

    private final java.util.ArrayDeque<Batch> batches = new java.util.ArrayDeque<>();

    /**
     * /관리자 국도 표지판 다시세우기 [하역장|전체] (and once a road is finished, for it): every sign of those roads, worked
     * out again from the saved roads ({@link RoadGuide}: the junctions' guide signs, the way back, confirmation, the
     * road's own), put up in place of the old ones: their sets of signs move on (the old ones go now, or when their chunk
     * loads), the new ones go up chunk by chunk. No road block is touched but a sign's footing on the verge. The roads are
     * read and the signs planned off the main thread.
     */
    void resign(java.util.function.Consumer<Component> to, java.util.function.Consumer<Component> bar, String target) {
        Homes homes = homes();
        int every = settings.markerEvery(), lights = settings.lightEvery();
        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            Map<String, RoadGuide.Plan> plans = new HashMap<>();
            Set<String> targets = new HashSet<>();
            for (Map.Entry<String, Map<String, RoadJob[]>> e : savedRoads().entrySet()) {
                List<RoadGuide.Road> rs = guideRoads(homes, e.getKey(), e.getValue());
                for (RoadGuide.Road r : rs) if (target.equals(ALL) || r.dock().equals(target)) targets.add(r.dock());
                plans.put(e.getKey(), RoadGuide.plan(rs, every, lights));
            }
            Bukkit.getScheduler().runTask(eco, () -> {
                if (targets.isEmpty()) to.accept(Lang.msg("road.resign-none"));
                else resign(to, bar, targets, plans);
            });
        });
    }

    private void resign(java.util.function.Consumer<Component> to, java.util.function.Consumer<Component> bar, Set<String> targets, Map<String, RoadGuide.Plan> plans) {
        int[] removed = {0};
        Set<String> cleared = new HashSet<>();
        int signs = 0, junctions = 0;
        int[] placed = {0, 0};
        DisplayView.Settings v = view();
        for (Map.Entry<String, RoadGuide.Plan> e : plans.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) continue;
            RoadGuide.Plan plan = e.getValue();
            Batch b = new Batch(w, Lang.raw("road.batch-signs"));
            signs += RoadGuide.retrofit(plan, targets, new RoadGuide.Board() {
                public void clear(String dock) {
                    if (cleared.add(dock)) removed[0] += clearSigns(dock);   // their sets move on: the old ones go
                }

                public void put(RoadGuide.Sign s) {
                    b.add(s.x(), s.z(), 3, () -> {
                        if (putUp(w, s, v)) placed[0]++;
                        else placed[1]++;
                    });
                }

                public void paint(RoadGuide.Paint p) {
                    b.add(p.x(), p.z(), 3, () -> RoadSigns.paint(w, p.x(), p.y(), p.z(), p.yaw(), p.text(), p.host(), sets.getOrDefault(p.host(), 0), v));
                }
            });
            junctions += plan.junctions().size();
            b.done = () -> to.accept(Lang.msg("road.resign-done", "placed", placed[0], "skipped", placed[1]));
            b.bar = bar;
            if (!b.empty()) batches.add(b);
        }
        to.accept(Lang.msg("road.resign-start", "roads", targets.size(), "signs", signs, "junctions", junctions, "removed", removed[0]));
    }

    /** One planned sign in the world: its footings (where open on the verge), the model and words. False: a post in a player's land. */
    private boolean putUp(World w, RoadGuide.Sign s, DisplayView.Settings v) {
        for (int[] c : s.posts()) if (eco.land().playerLand(w.getName(), c[0], c[1])) return false;
        if (!(s.level() >= w.getMinHeight() && s.level() + 6 < w.getMaxHeight())) return false;
        for (int[] c : s.posts()) {
            Block b = w.getBlockAt(c[0], s.level(), c[1]);
            Material m = b.getType();
            boolean open = m.isAir() || (!b.isLiquid() && (b.isReplaceable() || !m.isSolid() || natural(m)));
            if (open) b.setType(Material.STONE_BRICKS, false);
        }
        RoadSigns.Owner owner = new RoadSigns.Owner(s.host(), sets.getOrDefault(s.host(), 0), s.also(), s.also() == null ? 0 : sets.getOrDefault(s.also(), 0));
        if (s.guide()) RoadSigns.guide(w, s.x(), s.y(), s.z(), s.yaw(), s.rows(), owner, v);
        else RoadSigns.place(w, s.x(), s.y(), s.z(), s.yaw(), s.words(), owner, v);
        return true;
    }

    /**
     * /관리자 국도 난간고치기 [하역장|전체]: on roads already built, a wall, fence or post standing on a slab (a bridge's
     * railing on a half-step deck, half a block over it) or on a verge's path gets a full block under it, and one standing
     * over air its footing down to the ground ({@link RoadSection#plinths}). Never in any road's lanes.
     */
    void fixRails(java.util.function.Consumer<Component> to, java.util.function.Consumer<Component> bar, String target) {
        Homes homes = homes();
        Bukkit.getScheduler().runTaskAsynchronously(eco, () -> {
            // off the main thread: the roads read and laid out (which columns are whose lanes)
            Map<String, List<RoadGuide.Road>> mine = new HashMap<>();
            Map<String, RoadNetwork> nets = new HashMap<>();
            for (Map.Entry<String, Map<String, RoadJob[]>> e : savedRoads().entrySet()) {
                List<RoadNetwork.Road> all = new ArrayList<>();
                List<RoadGuide.Road> these = new ArrayList<>();
                for (RoadGuide.Road r : guideRoads(homes, e.getKey(), e.getValue())) {
                    all.add(new RoadNetwork.Road(r.dock(), r.points(), r.halves()));
                    if (target.equals(ALL) || r.dock().equals(target)) these.add(r);
                }
                if (these.isEmpty()) continue;
                mine.put(e.getKey(), these);
                nets.put(e.getKey(), RoadNetwork.of(all));
            }
            Bukkit.getScheduler().runTask(eco, () -> fixRails(to, bar, mine, nets));
        });
    }

    private void fixRails(java.util.function.Consumer<Component> to, java.util.function.Consumer<Component> bar, Map<String, List<RoadGuide.Road>> mine,
                          Map<String, RoadNetwork> nets) {
        int roadsN = 0;
        int[] fixed = {0};
        for (Map.Entry<String, List<RoadGuide.Road>> e : mine.entrySet()) {
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) continue;
            RoadNetwork net = nets.get(e.getKey());
            RoadSection.Site site = site(w);
            Set<Long> seen = new HashSet<>();
            Batch b = new Batch(w, Lang.raw("road.batch-rails"));
            for (RoadGuide.Road r : e.getValue()) {
                roadsN++;
                List<double[]> pts = r.points();
                for (int i = 0; i < pts.size(); i++) {
                    double[] p = pts.get(i);
                    int y = RoadSection.surfaceY(r.halves()[i]);
                    b.add(p[0], p[1], RoadSection.HALF + 2, () -> {
                        int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
                        for (int x = px - 6; x <= px + 6; x++) for (int z = pz - 6; z <= pz + 6; z++) {
                            RoadNetwork.Spot o = net.at(x, z);
                            if (o == null || o.lane() || !seen.add(RoadNetwork.key(x, z))) continue;   // off the roads, or a lane: never
                            if (eco.land().playerLand(w.getName(), x, z)) continue;
                            fixed[0] += RoadSection.plinths(site, x, z, RoadSection.surfaceY(o.half()) - 1, Math.max(y, RoadSection.surfaceY(o.half())) + 3);
                        }
                    });
                }
            }
            b.done = () -> to.accept(Lang.msg("road.rails-done", "blocks", fixed[0]));
            b.bar = bar;
            batches.add(b);
        }
        if (roadsN == 0) {
            to.accept(Lang.msg("road.resign-none"));
            return;
        }
        to.accept(Lang.msg("road.rails-start", "roads", roadsN));
    }

    /**
     * Work done chunk by chunk: each piece needs the chunks round a point loaded; they are loaded (or generated) a few at a
     * time and held while needed (at most about 32), and a piece runs once all of its are in. A chunk that fails to load:
     * its pieces are left out.
     */
    private final class Batch {
        final World w;
        final String what;
        final List<long[]> need = new ArrayList<>();
        final List<Runnable> work = new ArrayList<>();
        final Map<Long, Chunk> holding = new HashMap<>();
        final Set<Long> asked = new HashSet<>();
        Runnable done;
        /** where its progress goes (an admin's action bar), or null */
        java.util.function.Consumer<Component> bar;
        ChunkQueue q;
        int cursor, queued, age;

        Batch(World w, String what) {
            this.w = w;
            this.what = what;
        }

        boolean empty() {
            return work.isEmpty();
        }

        /** A piece of work that needs the chunks within r of (x, z). */
        void add(double x, double z, int r, Runnable run) {
            Set<Long> ks = new java.util.LinkedHashSet<>();
            for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2)
                ks.add(RoadJob.key((int) Math.floor(x + dx * r) >> 4, (int) Math.floor(z + dz * r) >> 4));
            need.add(ks.stream().mapToLong(Long::longValue).toArray());
            work.add(run);
        }

        /** True when all is done. */
        boolean tick() {
            if (q == null) q = new ChunkQueue(w, settings.gate().most(), c -> {
                c.addPluginChunkTicket(eco);
                holding.put(RoadJob.key(c.getX(), c.getZ()), c);
            });
            while (queued < work.size() && holding.size() + q.left() < 32) {
                for (long k : need.get(queued)) if (asked.add(k)) q.add(RoadJob.cx(k), RoadJob.cz(k));
                queued++;
            }
            q.pump(allow.concurrent());
            int n = 0, most = allow.run() ? (int) Math.ceil(60 * allow.share()) : 0;   // the server busy: fewer pieces, or none
            while (cursor < work.size() && n < most) {
                boolean ready = true;
                for (long k : need.get(cursor)) ready &= holding.containsKey(k);
                if (!ready) {
                    if (cursor < queued && q.finished()) {   // a chunk that did not load
                        cursor++;
                        continue;
                    }
                    break;
                }
                work.get(cursor).run();
                cursor++;
                n++;
            }
            // let go of the chunks the pieces still waiting do not need
            Set<Long> still = new HashSet<>();
            for (int i = cursor; i < queued; i++) for (long k : need.get(i)) still.add(k);
            holding.entrySet().removeIf(e -> {
                if (still.contains(e.getKey())) return false;
                e.getValue().removePluginChunkTicket(eco);
                return true;
            });
            if (++age % 20 == 0 && bar != null) bar.accept(Lang.mm("road.bar-batch", "what", what, "done", cursor, "total", work.size()));
            return cursor >= work.size();
        }

        void finish() {
            if (q != null) q.stop();
            for (Chunk c : holding.values()) c.removePluginChunkTicket(eco);
            holding.clear();
            if (done != null) done.run();
        }
    }

    private void build(World w) {
        if (halves == null) profile();
        if (queue == null) queue = new ChunkQueue(w, settings.gate().most(), c -> {
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
        queue.pump(allow.concurrent());
        // cross-sections while there are blocks left this tick (one bigger than that: the next ticks pay for it)
        budget.tick(allow.share());
        while (budget.any() && job.cursor < points.size()) {
            double[] p = points.get(job.cursor);
            if (!ready(w, p)) break;
            long before = setCount;
            if (!section(w, job.cursor)) return;   // stopped (a player's land)
            budget.spend(Math.max(1, setCount - before));
            job.cursor++;
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
            // its signs where it meets other roads, for the way back, confirming the way (RoadGuide)
            resign(this::tell, this::bar, job.dock);
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
        }
        int[] land = builder.build(i);
        if (land == null) return true;
        job.running = false;
        save();
        reset();
        tell(Lang.msg("road.player-land", "dock", job.dock, "x", land[0], "z", land[1]));
        return false;
    }

    /** What a sign's or a painted word's display entities count for in BUILD's blocks a tick. */
    static final int ENTITY_COST = 8;

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
    static int connect(Block b) {
        int n = 0;
        if (joins(b.getBlockData())) n += join(b);
        for (BlockFace f : SIDES) {
            Block side = b.getRelative(f);
            if (joins(side.getBlockData())) n += join(side);
        }
        Block below = b.getRelative(BlockFace.DOWN);
        if (below.getBlockData() instanceof Wall) n += join(below);   // a post under an arm stays a post
        return n;
    }

    /** Sets a wall's or a fence's sides from what is next to it. 1: what it costs (a block looked at, set when changed). */
    private static int join(Block b) {
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
        return 1;
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
                setCount += 1 + RoadModule.connect(b);
            }

            public void set(int x, int y, int z, String data) {
                if (!inside(y)) return;
                Block b = w.getBlockAt(x, y, z);
                b.setBlockData(blockData.computeIfAbsent(data, Bukkit::createBlockData), false);
                setCount += 1 + RoadModule.connect(b);
            }

            public void roadText(double x, double y, double z, float yaw, String text) {
                if (!inside((int) Math.floor(y))) return;
                RoadSigns.paint(w, x, y, z, yaw, text, job.dock, job.signs, view());
                setCount += ENTITY_COST;
            }

            public boolean playerLand(int x, int z) {
                return eco.land().playerLand(w.getName(), x, z);
            }

            public void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind) {
                if (!inside((int) Math.floor(y))) return;
                RoadSigns.place(w, x, y, z, yaw, RoadSign.words(kind, dock, km, route), dock, job.signs, view());
                setCount += ENTITY_COST;
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
