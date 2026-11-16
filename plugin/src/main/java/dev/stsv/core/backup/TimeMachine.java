package dev.stsv.core.backup;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.backup.TimeMachinePlan.Chunk;
import dev.stsv.core.backup.TimeMachinePlan.Pos;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 타임머신: some chunks of a world as they were in a backup, shown to one player as a ghost and, if they say so, put back.
 *
 * <p>How "then" is read: the backup's region files for just those chunks (and its entity and point-of-interest files,
 * and level.dat, never uid.dat) are copied into a temporary world folder, which is loaded as a world with a generator
 * that makes nothing; its chunks are then the backup's own, read with the server's own code (block entities, signs,
 * container contents, item frames). A chunk the backup does not have stays missing and is left alone (never replaced
 * by an empty one). Only one session at a time; the temporary world is unloaded and deleted when it ends.
 *
 * <p>What is put back depends on the {@link Mode} and the caller's {@link Rules}: {@link Mode#BUILDING} (a land owner by
 * themselves) puts back blocks only, never what is inside containers nor entities, and the rules keep anything that
 * could be turned into money out of it, so that taking things out and turning back time cannot copy them;
 * {@link Mode#FULL} (an administrator) puts back everything, contents and item frames / armor stands included.
 * Always left as they are now: our furniture (its display entities and the cells they hold) and custom crops.
 */
public final class TimeMachine {

    /** Who may get what back. */
    public enum Mode { BUILDING, FULL }

    /** A backup to choose from. checked: it passed its own check (no 검사-문제.txt). */
    public record Choice(String name, LocalDateTime time, boolean checked) {}

    /** Why it cannot go on. */
    public enum Problem { BUSY, NO_BACKUP, NOT_IN_BACKUP, UNREADABLE, NOTHING }

    public static final class Refused extends RuntimeException {
        public final Problem problem;

        Refused(Problem problem, String detail) {
            super(problem + (detail == null ? "" : ": " + detail));
            this.problem = problem;
        }
    }

    /** The caller's say over single blocks (the land's value rules). */
    public interface Rules {
        /** Never brought back from then (it could be turned into money). */
        boolean refuseThen(Block then);

        /** Left as it is now (e.g. a container with things in it, that putting back would destroy). */
        boolean keepNow(Block now);
    }

    /** One block to put back: what it was, and its block entity then when that comes with it. */
    public record Change(Pos pos, BlockData then, BlockState state) {}

    /** What putting back would do. Kept open (its temporary world loaded) until applied, or closed. */
    public static final class Plan {
        public final String backup;
        public final World world;
        public final List<Chunk> chunks;
        public final Mode mode;
        public final UUID by;
        public final List<Change> changes = new ArrayList<>();
        public final List<Chunk> missing = new ArrayList<>();
        public int skippedFurniture, skippedCrops, skippedValuable, skippedKept, containersEmptied;
        /** FULL: the entities (item frames, armor stands) of then */
        final List<Entity> entitiesThen = new ArrayList<>();
        World temp;
        BukkitTask expiry;
        boolean open = true;

        Plan(String backup, World world, List<Chunk> chunks, Mode mode, UUID by) {
            this.backup = backup;
            this.world = world;
            this.chunks = chunks;
            this.mode = mode;
            this.by = by;
        }

        public int entities() {
            return entitiesThen.size();
        }

        /** The block as it was (while the session is open). */
        public Block then(Pos p) {
            return temp.getBlockAt(p.x(), p.y(), p.z());
        }

        /** The block as it is. */
        public Block now(Pos p) {
            return world.getBlockAt(p.x(), p.y(), p.z());
        }
    }

    private final STSVPlugin plugin;
    private final BackupModule backups;
    private final NamespacedKey cellsKey;
    private Plan session;
    /** player -> the positions shown to them as a ghost */
    private final Map<UUID, Set<Pos>> ghosts = new HashMap<>();
    private final Map<UUID, BukkitTask> ghostEnds = new HashMap<>();

    TimeMachine(STSVPlugin plugin, BackupModule backups) {
        this.plugin = plugin;
        this.backups = backups;
        this.cellsKey = new NamespacedKey(plugin, "furniture_cells");
    }

    private String tempName() {
        return plugin.getConfig().getString("backup.time-machine.world");
    }

    /** Leftovers of a crash: the temporary world folder goes. */
    void clean() {
        Path dir = Bukkit.getWorldContainer().toPath().resolve(tempName());
        try {
            Snapshot.delete(dir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[타임머신] 임시 월드를 지우지 못함", e);
        }
    }

    /** Is this our temporary world (left out of backups, never a land)? */
    public boolean isTemp(World w) {
        return w != null && w.getName().equals(tempName());
    }

    /** The backups to choose from, newest first. */
    public List<Choice> choices() {
        List<Choice> out = new ArrayList<>();
        for (Path p : BackupModule.backups(backups.folder())) {
            out.add(new Choice(p.getFileName().toString(), BackupModule.time(p), !Files.exists(p.resolve("검사-문제.txt"))));
        }
        return out;
    }

    public boolean busy() {
        return session != null && session.open;
    }

    // ------------------------------------------------------------------------------------------
    // Reading "then"
    // ------------------------------------------------------------------------------------------

    /** Opens a session: the backup's copy of these chunks loaded, compared with now. Main thread. */
    public CompletableFuture<Plan> plan(String backup, World world, Collection<Chunk> chunkSet, Mode mode, Rules rules, UUID by) {
        if (busy()) return CompletableFuture.failedFuture(new Refused(Problem.BUSY, null));
        Path dir = backups.folder().resolve(backup);
        if (BackupModule.time(dir) == null || !Files.isDirectory(dir)) return CompletableFuture.failedFuture(new Refused(Problem.NO_BACKUP, backup));
        List<Chunk> chunks = new ArrayList<>(new LinkedHashSet<>(chunkSet));
        Plan plan = new Plan(backup, world, chunks, mode, by);
        session = plan;
        Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path live = world.getWorldPath().toAbsolutePath().normalize();
        Path then = dir.resolve(root.relativize(live).toString());
        Path temp = root.resolve(tempName());
        TimeMachinePlan.Dimension dim = switch (world.getEnvironment()) {
            case NETHER -> TimeMachinePlan.Dimension.NETHER;
            case THE_END -> TimeMachinePlan.Dimension.END;
            default -> TimeMachinePlan.Dimension.OVERWORLD;
        };
        CompletableFuture<Plan> out = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Path rel = TimeMachinePlan.regionDir(live, dim);
                if (rel == null || !Files.isDirectory(then.resolve(rel.toString()))) throw new Refused(Problem.NOT_IN_BACKUP, world.getName());
                Snapshot.delete(temp);
                Files.createDirectories(temp);
                if (Files.isRegularFile(then.resolve("level.dat"))) Files.copy(then.resolve("level.dat"), temp.resolve("level.dat"));
                Path parent = rel.getParent();
                for (String sub : List.of("region", "entities", "poi")) {
                    Path from = then.resolve(parent == null ? sub : parent.resolve(sub).toString());
                    Path to = temp.resolve(parent == null ? sub : parent.resolve(sub).toString());
                    Files.createDirectories(to);
                    for (String f : TimeMachinePlan.regionFiles(chunks)) {
                        if (Files.isRegularFile(from.resolve(f))) Files.copy(from.resolve(f), to.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> load(plan, rules, out));
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(plugin, () -> fail(plan, out, t));
            }
        });
        return out;
    }

    private void fail(Plan plan, CompletableFuture<Plan> out, Throwable t) {
        close(plan);
        out.completeExceptionally(t instanceof Refused ? t : new Refused(Problem.UNREADABLE, t.toString()));
        if (!(t instanceof Refused)) plugin.getLogger().log(Level.WARNING, "[타임머신] 백업을 읽지 못함", t);
    }

    /** A generator that makes nothing: a chunk the backup lacks is never invented. */
    private static final class Nothing extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    private void load(Plan plan, Rules rules, CompletableFuture<Plan> out) {
        if (!plan.open) {
            out.completeExceptionally(new Refused(Problem.BUSY, null));
            return;
        }
        World temp;
        try {
            temp = Bukkit.createWorld(new WorldCreator(tempName()).environment(plan.world.getEnvironment()).generator(new Nothing()));
        } catch (RuntimeException e) {
            fail(plan, out, e);
            return;
        }
        if (temp == null) {
            fail(plan, out, new IllegalStateException("createWorld"));
            return;
        }
        temp.setAutoSave(false);
        plan.temp = temp;
        List<Chunk> present = new ArrayList<>();
        for (Chunk c : plan.chunks) {
            if (temp.isChunkGenerated(c.x(), c.z())) present.add(c);
            else plan.missing.add(c);
        }
        if (present.isEmpty()) {
            fail(plan, out, new Refused(Problem.NOT_IN_BACKUP, plan.world.getName()));
            return;
        }
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (Chunk c : present) {
            loads.add(temp.getChunkAtAsync(c.x(), c.z(), false).thenAccept(ch -> {
                if (ch != null) ch.addPluginChunkTicket(plugin);
            }));
            loads.add(plan.world.getChunkAtAsync(c.x(), c.z(), false));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                fail(plan, out, err);
                return;
            }
            waitEntities(plan, present, rules, out, 0);
        }));
    }

    /** Entities load a moment after their chunk: furniture cells (then and now) are read from them. */
    private void waitEntities(Plan plan, List<Chunk> present, Rules rules, CompletableFuture<Plan> out, int tries) {
        boolean ready = true;
        for (Chunk c : present) {
            if (!plan.temp.getChunkAt(c.x(), c.z()).isEntitiesLoaded() || !plan.world.getChunkAt(c.x(), c.z()).isEntitiesLoaded()) ready = false;
        }
        if (!ready && tries < 60) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> waitEntities(plan, present, rules, out, tries + 1), 2L);
            return;
        }
        try {
            compare(plan, present, rules, out);
        } catch (RuntimeException e) {
            fail(plan, out, e);
        }
    }

    private Set<Pos> furnitureCells(World w, Chunk c) {
        Set<Pos> out = new HashSet<>();
        for (Entity e : w.getChunkAt(c.x(), c.z()).getEntities()) {
            if (!(e instanceof ItemDisplay d)) continue;
            String cells = d.getPersistentDataContainer().get(cellsKey, PersistentDataType.STRING);
            if (cells == null) continue;
            for (String cell : cells.split(";")) {
                String[] xyz = cell.split(",");
                if (xyz.length == 3) out.add(new Pos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim())));
            }
        }
        return out;
    }

    private static boolean furniture(Entity e) {
        return e.getPersistentDataContainer().getKeys().stream().anyMatch(k -> k.getKey().startsWith("furniture"));
    }

    private void compare(Plan plan, List<Chunk> present, Rules rules, CompletableFuture<Plan> out) {
        int minY = plan.world.getMinHeight(), maxY = plan.world.getMaxHeight();
        Map<Chunk, ChunkSnapshot[]> snaps = new HashMap<>();
        Set<Pos> furniture = new HashSet<>();
        Map<Pos, BlockState> statesThen = new HashMap<>();
        for (Chunk c : present) {
            snaps.put(c, new ChunkSnapshot[]{plan.temp.getChunkAt(c.x(), c.z()).getChunkSnapshot(false, false, false),
                    plan.world.getChunkAt(c.x(), c.z()).getChunkSnapshot(false, false, false)});
            furniture.addAll(furnitureCells(plan.temp, c));
            furniture.addAll(furnitureCells(plan.world, c));
            for (BlockState s : plan.temp.getChunkAt(c.x(), c.z()).getTileEntities()) statesThen.put(new Pos(s.getX(), s.getY(), s.getZ()), s);
            if (plan.mode == Mode.FULL) {
                for (Entity e : plan.temp.getChunkAt(c.x(), c.z()).getEntities()) {
                    if ((e instanceof Hanging || e instanceof ArmorStand) && !furniture(e)) plan.entitiesThen.add(e);
                }
            }
        }
        // the comparing itself is plain work on snapshots: off the main thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Pos> differ = new ArrayList<>();
            for (Chunk c : present) {
                ChunkSnapshot[] s = snaps.get(c);
                differ.addAll(TimeMachinePlan.differences(c, minY, maxY, (x, y, z) -> s[0].getBlockData(x, y, z), (x, y, z) -> s[1].getBlockData(x, y, z)));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    decide(plan, differ, statesThen, furniture, rules);
                } catch (RuntimeException e) {
                    fail(plan, out, e);
                    return;
                }
                if (plan.changes.isEmpty() && plan.entitiesThen.isEmpty()) {
                    fail(plan, out, new Refused(Problem.NOTHING, null));
                    return;
                }
                long seconds = plugin.getConfig().getLong("backup.time-machine.session-seconds");
                plan.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> close(plan), seconds * 20);
                out.complete(plan);
            });
        });
    }

    /** Which differing blocks go back, and which are left as they are now (and why). Main thread. */
    private void decide(Plan plan, List<Pos> differ, Map<Pos, BlockState> statesThen, Set<Pos> furniture, Rules rules) {
        Set<Pos> todo = new LinkedHashSet<>(differ);
        // FULL: block entities of then whose block is the same now come back too (what was inside them)
        if (plan.mode == Mode.FULL) todo.addAll(statesThen.keySet());
        List<Pos> sorted = new ArrayList<>(todo);
        TimeMachinePlan.bottomUp(sorted);
        for (Pos p : sorted) {
            if (furniture.contains(p)) {
                plan.skippedFurniture++;
                continue;
            }
            Block now = plan.world.getBlockAt(p.x(), p.y(), p.z());
            Block thenBlock = plan.temp.getBlockAt(p.x(), p.y(), p.z());
            BlockData then = thenBlock.getBlockData();
            if (then.getMaterial() == Material.TRIPWIRE || (plugin.crops() != null && plugin.crops().at(now).isPresent())) {
                plan.skippedCrops++;
                continue;
            }
            BlockState state = statesThen.get(p);
            if (plan.mode == Mode.BUILDING) {
                if (rules.refuseThen(thenBlock)) {
                    plan.skippedValuable++;
                    continue;
                }
                if (rules.keepNow(now)) {
                    plan.skippedKept++;
                    continue;
                }
                // a sign's words, a banner's pattern come back; what was inside a container does not
                if (state instanceof InventoryHolder || state instanceof Campfire) {
                    state = null;
                    plan.containersEmptied++;
                }
            }
            plan.changes.add(new Change(p, then, state));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ghost
    // ------------------------------------------------------------------------------------------

    /** Shows the player the blocks that would come back, as they were (only they see it), for some seconds. */
    public int preview(Player p, Plan plan) {
        endPreview(p);
        int cap = plugin.getConfig().getInt("backup.time-machine.preview-max-blocks");
        Map<Position, BlockData> show = new HashMap<>();
        Set<Pos> shown = new HashSet<>();
        for (Change c : plan.changes) {
            if (shown.size() >= cap) break;
            show.put(Position.block(c.pos().x(), c.pos().y(), c.pos().z()), c.then());
            shown.add(c.pos());
        }
        p.sendMultiBlockChange(show);
        ghosts.put(p.getUniqueId(), shown);
        long seconds = plugin.getConfig().getLong("backup.time-machine.preview-seconds");
        ghostEnds.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> endPreview(p), seconds * 20));
        return shown.size();
    }

    /** The ghost goes: the player sees the world as it is again. */
    public void endPreview(Player p) {
        BukkitTask t = ghostEnds.remove(p.getUniqueId());
        if (t != null) t.cancel();
        Set<Pos> shown = ghosts.remove(p.getUniqueId());
        if (shown == null || !p.isOnline()) return;
        Map<Position, BlockData> real = new HashMap<>();
        for (Pos pos : shown) {
            if (!p.getWorld().isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue;
            real.put(Position.block(pos.x(), pos.y(), pos.z()), p.getWorld().getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData());
        }
        p.sendMultiBlockChange(real);
    }

    // ------------------------------------------------------------------------------------------
    // Putting back
    // ------------------------------------------------------------------------------------------

    /** Puts the plan's blocks back, a batch a tick, then (FULL) the entities; closes the session. Main thread. */
    public CompletableFuture<Integer> apply(Plan plan) {
        if (!plan.open || plan != session) return CompletableFuture.failedFuture(new Refused(Problem.BUSY, null));
        if (plan.expiry != null) plan.expiry.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) if (ghosts.containsKey(p.getUniqueId())) endPreview(p);
        CompletableFuture<Integer> out = new CompletableFuture<>();
        List<List<Change>> batches = TimeMachinePlan.batches(plan.changes, plugin.getConfig().getInt("backup.time-machine.blocks-per-tick"));
        int[] done = {0};
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            try {
                if (done[0] < batches.size()) {
                    for (Change c : batches.get(done[0])) put(plan, c);
                    done[0]++;
                    Bukkit.getScheduler().runTask(plugin, step[0]);
                    return;
                }
                if (plan.mode == Mode.FULL) entities(plan);
                rescue(plan);
                int n = plan.changes.size();
                close(plan);
                out.complete(n);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "[타임머신] 되돌리는 중 오류 (" + done[0] + "/" + batches.size() + " 묶음까지 됨)", e);
                rescue(plan);
                close(plan);
                out.completeExceptionally(e);
            }
        };
        step[0].run();
        return out;
    }

    private void put(Plan plan, Change c) {
        Block b = plan.world.getBlockAt(c.pos().x(), c.pos().y(), c.pos().z());
        if (c.state() != null) {
            c.state().copy(b.getLocation()).update(true, false);
        } else {
            b.setBlockData(c.then(), false);
        }
    }

    private void entities(Plan plan) {
        Set<Chunk> set = new HashSet<>(plan.chunks);
        set.removeAll(plan.missing);
        for (Chunk c : set) {
            for (Entity e : plan.world.getChunkAt(c.x(), c.z()).getEntities()) {
                if ((e instanceof Hanging || e instanceof ArmorStand) && !furniture(e)) e.remove();
            }
        }
        for (Entity e : plan.entitiesThen) {
            if (!e.isValid()) continue;
            Location at = e.getLocation();
            at.setWorld(plan.world);
            e.copy(at);
        }
    }

    /** Nobody is left inside a block: whoever is, goes up to the top. */
    private void rescue(Plan plan) {
        Set<Chunk> set = new HashSet<>(plan.chunks);
        for (Player p : plan.world.getPlayers()) {
            Location l = p.getLocation();
            if (!set.contains(new Chunk(l.getBlockX() >> 4, l.getBlockZ() >> 4))) continue;
            Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0);
            if (feet.isPassable() && head.isPassable()) continue;
            Block top = plan.world.getHighestBlockAt(l.getBlockX(), l.getBlockZ());
            p.teleport(new Location(plan.world, top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, l.getYaw(), l.getPitch()));
        }
    }

    /** Keeps an open session from closing by itself (while a backup is taken before it is applied). */
    public void hold(Plan plan) {
        if (plan.expiry != null) plan.expiry.cancel();
        plan.expiry = null;
    }

    /** Ends a session: its temporary world unloaded and deleted. Safe to call twice. */
    public void close(Plan plan) {
        if (plan == null) return;
        plan.open = false;
        if (plan.expiry != null) plan.expiry.cancel();
        if (session == plan) session = null;
        World temp = plan.temp;
        plan.temp = null;
        plan.entitiesThen.clear();
        if (temp != null) {
            for (Player p : temp.getPlayers()) p.teleport(plan.world.getSpawnLocation());
            Bukkit.unloadWorld(temp, false);
        }
        // its folder is cleared by the next session before it copies (deleting it here, on another thread, could race that copy)
    }

    /** The open session's plan for this player, or null. */
    public Plan sessionOf(UUID by) {
        return session != null && session.open && by != null && by.equals(session.by) ? session : null;
    }

    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) endPreview(p);
        if (session != null) {
            Plan s = session;
            s.open = false;
            session = null;
            if (s.temp != null) Bukkit.unloadWorld(s.temp, false);
        }
        clean();
    }
}
