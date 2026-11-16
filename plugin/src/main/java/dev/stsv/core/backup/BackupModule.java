package dev.stsv.core.backup;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.data.Db;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.world.WorldRules;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Automatic backups (config "backup"): every so often a whole copy of the worlds, the plugins' data (the database as a
 * consistent snapshot) and the server's own settings, in a folder named by the time, laid out like the server folder
 * itself, so putting one back is: stop the server, copy the folder's contents over the server folder, start.
 *
 * <p>How a backup is taken without stopping the game: the worlds are saved and their saving paused (the server's
 * save-off) while their files are copied on another thread, then saving goes on; the database is copied by SQLite
 * itself (VACUUM INTO: a clean copy while the game keeps writing). Files that did not change since the last backup are
 * shared with it ({@link Snapshot}), so frequent backups cost little room. Old backups thin out ({@link Retention}):
 * the latest hours, one a day, one a week. A backup is written under "name.partial" and renamed when complete, so a
 * crash mid-way never leaves a half backup that looks whole.
 */
public final class BackupModule implements StsvModule {

    private static final String OWNER = "STSV 백업";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String PARTIAL = ".partial";
    /** The server's own settings, next to the worlds (each only if it is there). */
    private static final List<String> SERVER_FILES = List.of("server.properties", "bukkit.yml", "spigot.yml", "commands.yml", "permissions.yml",
            "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "config");

    private STSVPlugin plugin;
    private BukkitTask timer;
    private TimeMachine timeMachine;
    /** told the name of the next backup when it is done (or why it failed): /땅 타임머신 takes one before it puts anything back */
    private final List<java.util.function.BiConsumer<String, String>> waiting = new ArrayList<>();
    private volatile boolean running;
    private volatile String lastName, lastError;
    /** what the last backup's check found (empty: it opened and looked whole) */
    private volatile List<String> checked = List.of();
    private volatile long lastMillis, lastBytes, lastFiles, lastShared;

    @Override
    public String id() {
        return "backup";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        plugin.admin().add(OWNER, "백업", "[지금|목록]", "자동 백업 상태 / 바로 백업 / 남아 있는 백업 (README \"백업과 복구\")", this::admin,
                (s, a) -> a.length == 1 ? List.of("지금", "목록") : List.of());
        plugin.admin().report(OWNER, (s, a) -> s.sendMessage(Lang.mm("backup.report", "state", state())));
        timeMachine = new TimeMachine(plugin, this);
        schedule();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            clearPartials();
            timeMachine.clean();
        });
    }

    /** The time machine: chunks as they were in a backup (/땅 타임머신, /관리자 타임머신). */
    public TimeMachine timeMachine() {
        return timeMachine;
    }

    /**
     * Takes a backup now and tells {@code then} its name when it is done, or (null, why) when it is not. A backup
     * already under way: told when that one is done (it holds the world as it is now just as well).
     */
    public void backupNow(java.util.function.BiConsumer<String, String> then) {
        waiting.add(then);
        if (!running) start(null);
    }

    private void tellWaiting(String name, String error) {
        List<java.util.function.BiConsumer<String, String>> told = new ArrayList<>(waiting);
        waiting.clear();
        for (var w : told) {
            try {
                w.accept(name, error);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "[백업] 알림 처리 실패", e);
            }
        }
    }

    @Override
    public void reload() {
        schedule();
    }

    @Override
    public void disable() {
        if (timer != null) timer.cancel();
        if (timeMachine != null) timeMachine.shutdown();
        if (plugin != null) plugin.admin().removeAll(OWNER);
    }

    private ConfigurationSection config() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("backup");
        return c != null ? c : plugin.getConfig().createSection("backup");
    }

    private void schedule() {
        if (timer != null) timer.cancel();
        timer = null;
        if (!config().getBoolean("enabled")) return;
        long every = Math.max(10, config().getLong("interval-minutes")) * 60 * 20;
        timer = Bukkit.getScheduler().runTaskTimer(plugin, () -> start(null), every, every);
    }

    /** The server folder (where the worlds are). */
    private Path root() {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    Path folder() {
        Path p = Path.of(config().getString("folder"));
        return (p.isAbsolute() ? p : root().resolve(p)).normalize();
    }

    private Retention retention() {
        return new Retention(config().getInt("keep.recent"), config().getInt("keep.days"), config().getInt("keep.weeks"));
    }

    // ------------------------------------------------------------------------------------------
    // Taking one
    // ------------------------------------------------------------------------------------------

    /** Starts a backup now (main thread). by: who asked (told how it went), null for the timer. */
    public void start(CommandSender by) {
        if (running) {
            if (by != null) by.sendMessage(Lang.msg("backup.busy"));
            return;
        }
        Path folder = folder();
        try {
            Files.createDirectories(folder);
            long free = Files.getFileStore(folder).getUsableSpace();
            long need = (long) (config().getDouble("min-free-gb") * 1024 * 1024 * 1024);
            if (free < need) {
                fail(by, Lang.raw("backup.no-space", "free", gb(free)));
                return;
            }
        } catch (IOException e) {
            fail(by, e.toString());
            return;
        }
        running = true;
        long started = System.currentTimeMillis();
        String name = LocalDateTime.now().format(NAME);
        Path partial = folder.resolve(name + PARTIAL);
        Path previous = newest(folder);
        // the worlds: saved now, their saving paused until their files are copied
        boolean wild = config().getBoolean("wild-worlds");
        List<World> worlds = new ArrayList<>();
        List<World> paused = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (!wild && plugin.worlds() != null && WorldRules.wildSet(plugin.worlds().kind(w))) continue;
            if (timeMachine != null && timeMachine.isTemp(w)) continue;     // the time machine's borrowed copy of a backup
            worlds.add(w);
            w.save();
            if (w.isAutoSave()) {
                w.setAutoSave(false);
                paused.add(w);
            }
        }
        if (by != null) by.sendMessage(Lang.msg("backup.started", "name", name));
        Path root = root();
        List<Path> worldDirs = worlds.stream().map(w -> w.getWorldFolder().toPath().toAbsolutePath().normalize()).toList();
        Snapshot.Stats total = new Snapshot.Stats();
        CompletableFuture.runAsync(() -> {
            try {
                for (Path dir : worldDirs) Snapshot.sum(total, copyUnder(root, dir, partial, previous, rel -> rel.getFileName().toString().equals("session.lock")));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : paused) w.setAutoSave(true);       // saving goes on, whatever happened
            if (err != null) {
                running = false;
                fail(by, cause(err));
                cleanup(partial);
                return;
            }
            rest(by, name, folder, partial, previous, total, started);
        }));
    }

    /** After the worlds (saving already back on): the plugins' folders, the database, the server's settings, then done. */
    private void rest(CommandSender by, String name, Path folder, Path partial, Path previous, Snapshot.Stats total, long started) {
        Path root = root();
        Path plugins = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        Db.Dialect dialect = plugin.db().dialect();
        Path dbFile = plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString("database.sqlite-file")).toAbsolutePath().normalize();
        List<String> extra = config().getStringList("extra");
        Path dbCopy = partial.resolve(root.relativize(dbFile).toString());
        CompletableFuture<Void> db = dialect == Db.Dialect.SQLITE ? plugin.db().async(c -> {
            try {
                Files.createDirectories(dbCopy.getParent());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            try (Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + dbCopy.toString().replace("'", "''") + "'");
            }
            return null;
        }) : CompletableFuture.completedFuture(null);
        db.thenRunAsync(() -> {
            try {
                // the live database (and its journal) is left out of the folder copy: its clean copy is above. So is the
                // backup folder itself, if it was put inside a plugin's folder
                Set<Path> left = Set.of(dbFile, Path.of(dbFile + "-wal"), Path.of(dbFile + "-shm"), Path.of(dbFile + "-journal"), folder);
                for (String own : List.of(plugin.getName(), "STSV-Economy")) {
                    Path dir = plugins.resolve(own);
                    Snapshot.sum(total, copyUnder(root, dir, partial, previous, rel -> left.contains(dir.resolve(rel.toString()).normalize())));
                }
                for (String f : SERVER_FILES) Snapshot.sum(total, copyUnder(root, root.resolve(f), partial, previous, rel -> false));
                for (String e : extra) Snapshot.sum(total, copyUnder(root, root.resolve(e).normalize(), partial, previous, rel -> false));
                // checked before it is trusted (kept even when something is off, but marked and told)
                List<String> problems = Verify.check(partial, dialect == Db.Dialect.SQLITE ? dbCopy : null);
                checked = problems;
                if (!problems.isEmpty()) Files.write(partial.resolve("검사-문제.txt"), problems);
                Files.writeString(partial.resolve("복원방법.txt"), Lang.raw("backup.restore-note", "name", name, "db", dialect == Db.Dialect.SQLITE ? "" : Lang.raw("backup.restore-note-mysql")));
                Path done = folder.resolve(name);
                Files.move(partial, done);
                thin(folder);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            running = false;
            if (err != null) {
                fail(by, cause(err));
                cleanup(partial);
                return;
            }
            lastName = name;
            lastError = null;
            lastMillis = System.currentTimeMillis() - started;
            lastBytes = total.bytes;
            lastFiles = total.files;
            lastShared = total.linked;
            if (!checked.isEmpty()) {
                plugin.getLogger().warning("[백업] " + name + " 검사에서 문제: " + String.join(" / ", checked));
                for (Player p : Bukkit.getOnlinePlayers()) if (p.isOp()) p.sendMessage(Lang.msg("backup.check-failed", "name", name, "problem", checked.getFirst()));
            }
            plugin.getLogger().info("[백업] " + name + ": 파일 " + total.files + "개 (새로 복사 " + total.copied + ", 이전 백업과 공유 " + total.linked + "), "
                    + mb(total.bytes) + " 복사, " + (lastMillis / 1000) + "초");
            if (by != null) by.sendMessage(Lang.msg("backup.done", "name", name, "files", total.files, "copied", mb(total.bytes), "seconds", lastMillis / 1000));
            tellWaiting(name, null);
        }));
    }

    /** Copies a file or folder under the server folder to the same place in the backup (one outside it: under "_outside"). */
    private static Snapshot.Stats copyUnder(Path root, Path source, Path backup, Path previous, java.util.function.Predicate<Path> skip) throws IOException {
        Path abs = source.toAbsolutePath().normalize();
        String rel = abs.startsWith(root) ? root.relativize(abs).toString() : "_outside/" + abs.getFileName();
        if (Files.isRegularFile(abs)) {
            Path parent = abs.getParent();
            Path into = backup.resolve(rel).getParent();
            Path was = previous == null ? null : previous.resolve(rel).getParent();
            String file = abs.getFileName().toString();
            return Snapshot.copy(parent, into, was, p -> !p.toString().equals(file));
        }
        return Snapshot.copy(abs, backup.resolve(rel), previous == null ? null : previous.resolve(rel), skip);
    }

    // ------------------------------------------------------------------------------------------
    // The backups there are
    // ------------------------------------------------------------------------------------------

    /** Complete backups, newest first. */
    static List<Path> backups(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> s = Files.list(folder)) {
            return s.filter(Files::isDirectory).filter(p -> time(p) != null).sorted(Comparator.comparing(BackupModule::time).reversed()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static LocalDateTime time(Path p) {
        try {
            return LocalDateTime.parse(p.getFileName().toString(), NAME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path newest(Path folder) {
        List<Path> all = backups(folder);
        return all.isEmpty() ? null : all.getFirst();
    }

    private void thin(Path folder) throws IOException {
        List<Path> all = backups(folder);
        List<LocalDateTime> drop = retention().drop(all.stream().map(BackupModule::time).toList(), LocalDateTime.now());
        for (Path p : all) if (drop.contains(time(p))) Snapshot.delete(p);
    }

    /** A backup cut short (a crash, an error) is never kept: it would look whole and is not. */
    private void clearPartials() {
        Path folder = folder();
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(PARTIAL)).toList()) {
                Snapshot.delete(p);
                plugin.getLogger().warning("[백업] 끝나지 못한 백업을 지웠습니다: " + p.getFileName());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[백업] 폴더를 읽지 못함", e);
        }
    }

    private void cleanup(Path partial) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Snapshot.delete(partial);
            } catch (IOException ignored) {
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Telling
    // ------------------------------------------------------------------------------------------

    private void fail(CommandSender by, String why) {
        lastError = why;
        tellWaiting(null, why);
        plugin.getLogger().warning("[백업] 실패: " + why);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.isOp()) p.sendMessage(Lang.msg("backup.failed", "reason", why));
        if (by != null && !(by instanceof Player p && p.isOp())) by.sendMessage(Lang.msg("backup.failed", "reason", why));
    }

    private static String cause(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.io.UncheckedIOException) && t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private String state() {
        if (!config().getBoolean("enabled")) return Lang.raw("backup.state-off");
        if (running) return Lang.raw("backup.state-running");
        if (lastError != null) return Lang.raw("backup.state-failed", "reason", lastError);
        if (lastName == null) return Lang.raw("backup.state-none", "every", config().getLong("interval-minutes"));
        return Lang.raw(checked.isEmpty() ? "backup.state-last" : "backup.state-last-bad", "name", lastName, "files", lastFiles, "shared", lastShared, "copied", mb(lastBytes), "seconds", lastMillis / 1000,
                "every", config().getLong("interval-minutes"));
    }

    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0];
        switch (sub) {
            case "지금", "now" -> start(s);
            case "목록", "list" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Path folder = folder();
                List<Path> all = backups(folder);
                long free;
                try {
                    free = Files.isDirectory(folder) ? Files.getFileStore(folder).getUsableSpace() : -1;
                } catch (IOException e) {
                    free = -1;
                }
                long f = free;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    s.sendMessage(Lang.msg("backup.list-head", "count", all.size(), "folder", folder, "free", f < 0 ? "?" : gb(f)));
                    for (Path p : all.stream().limit(40).toList()) s.sendMessage(Lang.mm("backup.list-entry", "name", p.getFileName()));
                });
            });
            default -> s.sendMessage(Lang.mm("backup.status", "state", state(), "folder", folder()));
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%,.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%,.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
