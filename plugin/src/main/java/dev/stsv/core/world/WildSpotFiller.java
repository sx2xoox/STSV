package dev.stsv.core.world;

import dev.stsv.core.STSVPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps the 야생월드's ready spots ({@link WildSpotPool}) filled in the background, so a /야생월드 trip does not
 * generate terrain. One chunk at a time (getChunkAtAsync, never a sync load), only while the server is healthy
 * and with a rest between chunks ({@link WildSpotRules#gate}): a random column of the ring is loaded (usually
 * generated), its top checked with {@link SafeSpot}; a safe one then gets the chunks around it generated one by one,
 * and only then joins the pool. Saved to plugins/STSV/data/wild-spots.txt (every 30 s when changed, and on stop).
 * Main thread only.
 */
final class WildSpotFiller {

    private static final long STUCK_MS = 60_000, SAVE_MS = 30_000;
    static final int MAX_TOTAL = 1000;

    private final STSVPlugin plugin;
    private final Supplier<World> wild;
    private final Supplier<WorldRules> rules;
    private final Path file;
    private WildSpotRules settings = WildSpotRules.DEFAULTS;
    private WildSpotPool pool = new WildSpotPool();

    private BukkitTask task;
    private int epoch;                 // bumped on clear: late chunk callbacks of the old run are ignored
    private boolean inFlight;
    private long inFlightSince, lastDone;
    private WildSpotPool.Spot candidate;
    private final Deque<int[]> candidateChunks = new ArrayDeque<>();
    private int candidateTotal;
    private int fastTarget;            // /관리자 야생스팟 채우기: fill to this many at the fast pace (0 = off)
    private WildSpotRules.Gate gate = WildSpotRules.Gate.NO_WORLD;
    private long chunks, found, rejected, used, dropped;
    private boolean dirty;
    private long lastSave, saveSeq, savedSeq;

    WildSpotFiller(STSVPlugin plugin, Supplier<World> wild, Supplier<WorldRules> rules) {
        this.plugin = plugin;
        this.wild = wild;
        this.rules = rules;
        this.file = plugin.getDataFolder().toPath().resolve("data").resolve("wild-spots.txt");
    }

    void settings(WildSpotRules settings) {
        this.settings = settings;
    }

    WildSpotRules settings() {
        return settings;
    }

    void start() {
        try {
            if (Files.exists(file)) pool = WildSpotPool.read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("[월드] " + file + " 을(를) 읽지 못했습니다: " + e.getMessage());
        }
        lastSave = System.currentTimeMillis();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 2L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        epoch++;
        if (dirty) saveNow(pool.write(), ++saveSeq);
    }

    // ------------------------------------------------------------------------------------------

    private void tick() {
        World w = wild.get();
        long now = System.currentTimeMillis();
        if (w != null && !pool.matches(w.getName(), w.getSeed())) {
            // first start, or the wild world was regenerated (new seed): old spots mean nothing there
            if (pool.size() > 0 || !pool.world().isEmpty()) plugin.getLogger().info("[월드] 야생월드가 바뀌어 준비된 자리를 비우고 다시 채웁니다");
            resetRun();
            pool.reset(w.getName(), w.getSeed());
            dirty = true;
        }
        if (inFlight && now - inFlightSince > STUCK_MS) {   // a request that never came back: give up on it
            epoch++;
            inFlight = false;
            lastDone = now;
        }
        if (fastTarget > 0 && pool.size() >= fastTarget) {
            plugin.getLogger().info("[월드] 야생월드 준비된 자리 빠른 채우기 끝: " + pool.size() + "개");
            fastTarget = 0;
        }
        double tps = Bukkit.getTPS()[0], mspt = Bukkit.getAverageTickTime();
        gate = settings.gate(w != null, pool.size(), target(), inFlight, tps, mspt, now - lastDone, fastTarget > 0);
        if (gate == WildSpotRules.Gate.RUN) step(w);
        if (dirty && now - lastSave > SAVE_MS) saveAsync();
    }

    private int target() {
        return fastTarget > 0 ? Math.max(settings.pool(), fastTarget) : settings.pool();
    }

    /** One chunk of work: the next arrival chunk of the current candidate, or a new candidate column. */
    private void step(World w) {
        if (candidate != null) {
            while (!candidateChunks.isEmpty()) {
                int[] c = candidateChunks.peek();
                if (w.isChunkGenerated(c[0], c[1])) {   // already there: nothing to do (no load)
                    candidateChunks.poll();
                    continue;
                }
                load(w, c[0], c[1], chunk -> {
                    candidateChunks.remove(c);
                    chunks++;
                });
                return;
            }
            if (pool.add(candidate)) {
                found++;
                dirty = true;
            }
            candidate = null;
            return;
        }
        Location spawn = w.getSpawnLocation();
        int[] xz = rules.get().randomColumn(ThreadLocalRandom.current(), spawn.getBlockX(), spawn.getBlockZ());
        if (!w.getWorldBorder().isInside(new Location(w, xz[0] + 0.5, spawn.getY(), xz[1] + 0.5))) {
            rejected++;
            return;
        }
        boolean wasGenerated = w.isChunkGenerated(xz[0] >> 4, xz[1] >> 4);
        load(w, xz[0] >> 4, xz[1] >> 4, chunk -> {
            if (!wasGenerated) chunks++;
            Location at = surface(w, xz[0], xz[1]);
            if (at == null) {
                rejected++;
                return;
            }
            candidate = new WildSpotPool.Spot(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            candidateChunks.clear();
            List<int[]> around = WildSpotRules.arrivalChunks(xz[0] >> 4, xz[1] >> 4, settings.arrivalRadius());
            candidateTotal = around.size();
            candidateChunks.addAll(around.subList(1, around.size()));   // the centre is loaded already
        });
    }

    /** getChunkAtAsync (generates when missing); the callback runs on the main thread, unless the run was cleared. */
    private void load(World w, int cx, int cz, Consumer<Chunk> then) {
        int my = epoch;
        inFlight = true;
        inFlightSince = System.currentTimeMillis();
        w.getChunkAtAsync(cx, cz).whenComplete((chunk, err) -> main(() -> {
            if (my != epoch) return;
            inFlight = false;
            lastDone = System.currentTimeMillis();
            if (err != null) {
                plugin.getLogger().warning("[월드] 야생월드 청크 (" + cx + ", " + cz + ") 를 불러오지 못했습니다: " + err.getMessage());
                candidate = null;   // give up on this spot, try another
                candidateChunks.clear();
                return;
            }
            if (chunk != null && w.equals(wild.get())) then.accept(chunk);
        }));
    }

    private void main(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    private void resetRun() {
        epoch++;
        inFlight = false;
        candidate = null;
        candidateChunks.clear();
    }

    // ------------------------------------------------------------------------------------------

    /** A ready spot for a trip (random, removed from the pool); null when there is none for this world. */
    WildSpotPool.Spot take(World w) {
        if (w == null || !pool.matches(w.getName(), w.getSeed())) return null;
        WildSpotPool.Spot s = pool.take(ThreadLocalRandom.current());
        if (s != null) {
            used++;
            dirty = true;
        }
        return s;
    }

    /** A taken spot turned out unsafe (something was built or dug there). */
    void dropped() {
        dropped++;
    }

    /** /관리자 야생초기화: forget every spot (the new world binds a new pool on the next tick). */
    void clear() {
        resetRun();
        fastTarget = 0;
        pool.reset("", 0);
        dirty = true;
        saveAsync();
    }

    /** /관리자 야생스팟 채우기 n: n more spots at the fast pace (still one chunk at a time, still only while healthy). */
    int fill(int more) {
        fastTarget = Math.min(MAX_TOTAL, pool.size() + Math.max(1, more));
        return fastTarget;
    }

    record Status(WildSpotRules.Gate gate, int size, int target, boolean fast, int candidateDone, int candidateTotal,
                  long chunks, long found, long rejected, long used, long dropped) {}

    Status status() {
        int done = candidate == null ? 0 : candidateTotal - candidateChunks.size();
        return new Status(gate, pool.size(), target(), fastTarget > 0, done, candidate == null ? 0 : candidateTotal,
                chunks, found, rejected, used, dropped);
    }

    // ------------------------------------------------------------------------------------------

    private void saveAsync() {
        String text = pool.write();
        long seq = ++saveSeq;
        dirty = false;
        lastSave = System.currentTimeMillis();
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> saveNow(text, seq));
        else saveNow(text, seq);
    }

    /** Writes the file (atomically); a snapshot older than one already written is skipped. */
    private synchronized void saveNow(String text, long seq) {
        if (seq <= savedSeq) return;
        savedSeq = seq;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("[월드] " + file + " 에 저장하지 못했습니다: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Where a player would stand on the top of column (x, z) if it is safe ({@link SafeSpot}), else null. The
     * column's chunk must be loaded (main thread).
     */
    static Location surface(World w, int x, int z) {
        Block ground = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block feet = ground.getRelative(0, 1, 0), head = ground.getRelative(0, 2, 0);
        if (!SafeSpot.safe(ground.getType(), ground.getType().isSolid(), feet.getType(), head.getType(), ground.getY(), w.getMinHeight(), w.getMaxHeight())) return null;
        return new Location(w, x + 0.5, ground.getY() + 1, z + 0.5, ThreadLocalRandom.current().nextFloat() * 360 - 180, 0);
    }

    /** Is a pooled spot still safe (the blocks may have changed since it was found)? The chunk must be loaded. */
    static boolean stillSafe(World w, WildSpotPool.Spot s) {
        Block ground = w.getBlockAt(s.x(), s.y() - 1, s.z());
        Block feet = w.getBlockAt(s.x(), s.y(), s.z()), head = w.getBlockAt(s.x(), s.y() + 1, s.z());
        return SafeSpot.safe(ground.getType(), ground.getType().isSolid(), feet.getType(), head.getType(), ground.getY(), w.getMinHeight(), w.getMaxHeight());
    }
}
