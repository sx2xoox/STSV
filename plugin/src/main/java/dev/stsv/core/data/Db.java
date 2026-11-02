package dev.stsv.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small database layer shared by every module (no Bukkit types, so it is unit-tested with SQLite).
 *
 * <ul>
 *   <li>All work runs on the database thread(s): {@link #async} for normal use, {@link #sync} only while
 *       a module loads (server start / reload), never from a game tick.</li>
 *   <li>Each module owns its tables and evolves them with numbered {@link #migrate migrations}; the applied
 *       version per module is kept in {@code stsv_schema}, so old servers upgrade in place.</li>
 *   <li>SQLite (one file, default) and MySQL/MariaDB (shared between servers) speak slightly different SQL;
 *       {@link #upsert} hides the one difference modules need.</li>
 * </ul>
 */
public final class Db {

    public enum Dialect { SQLITE, MYSQL }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private final ConnectionSource source;
    private final Dialect dialect;
    private final ExecutorService executor;
    private final Logger log;
    private final AutoCloseable onClose;

    public Db(ConnectionSource source, Dialect dialect, int threads, Logger log, AutoCloseable onClose) {
        this.source = source;
        this.dialect = dialect;
        this.log = log;
        this.onClose = onClose;
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "STSV-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Runs on the database thread; failures are logged and complete the future exceptionally. */
    public <T> CompletableFuture<T> async(Work<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = source.get()) {
                return work.run(c);
            } catch (SQLException e) {
                log.log(Level.SEVERE, "DB 작업 실패", e);
                throw new RuntimeException(e);
            }
        }, executor);
    }

    public CompletableFuture<Void> execute(String sql, Object... args) {
        return async(c -> {
            update(c, sql, args);
            return null;
        });
    }

    /** Blocking: only for loading at startup. Waits for earlier queued writes first (same thread). */
    public <T> T sync(Work<T> work) {
        try {
            return async(work).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("DB 작업 실패: " + e.getMessage(), e);
        }
    }

    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    public interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static <T> List<T> query(Connection c, String sql, Row<T> row, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(row.map(rs));
                return out;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    /**
     * INSERT or UPDATE by primary key. keys = primary key columns, values = the other columns.
     * Arguments: keys first, then values, in the given order.
     */
    public String upsert(String table, List<String> keys, List<String> values) {
        List<String> all = new ArrayList<>(keys);
        all.addAll(values);
        String cols = String.join(", ", all);
        String marks = String.join(", ", all.stream().map(x -> "?").toList());
        if (values.isEmpty()) {
            return dialect == Dialect.MYSQL
                    ? "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")"
                    : "INSERT OR IGNORE INTO " + table + " (" + cols + ") VALUES (" + marks + ")";
        }
        if (dialect == Dialect.MYSQL) {
            return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE "
                    + String.join(", ", values.stream().map(v -> v + " = VALUES(" + v + ")").toList());
        }
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON CONFLICT(" + String.join(", ", keys)
                + ") DO UPDATE SET " + String.join(", ", values.stream().map(v -> v + " = excluded." + v).toList());
    }

    /** INSERT, or add to the existing value: arguments keys..., delta. */
    public String increment(String table, List<String> keys, String column) {
        List<String> all = new ArrayList<>(keys);
        all.add(column);
        String cols = String.join(", ", all);
        String marks = String.join(", ", all.stream().map(x -> "?").toList());
        if (dialect == Dialect.MYSQL) {
            return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON DUPLICATE KEY UPDATE "
                    + column + " = " + column + " + VALUES(" + column + ")";
        }
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ") ON CONFLICT(" + String.join(", ", keys)
                + ") DO UPDATE SET " + column + " = " + column + " + excluded." + column;
    }

    /**
     * Brings a module's tables up to date. steps.get(0) is version 1, and so on; each step is applied
     * once, in a transaction, and recorded. Never edit a released step: add a new one.
     */
    public void migrate(String module, List<List<String>> steps) {
        sync(c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS stsv_schema (module VARCHAR(64) PRIMARY KEY, version INT NOT NULL)");
            }
            List<Integer> v = query(c, "SELECT version FROM stsv_schema WHERE module = ?", rs -> rs.getInt(1), module);
            int current = v.isEmpty() ? 0 : v.getFirst();
            for (int i = current; i < steps.size(); i++) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    for (String sql : steps.get(i)) st.executeUpdate(sql);
                    update(c, upsert("stsv_schema", List.of("module"), List.of("version")), module, i + 1);
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw new SQLException(module + " 마이그레이션 " + (i + 1) + " 실패: " + e.getMessage(), e);
                } finally {
                    c.setAutoCommit(auto);
                }
                log.info("DB: " + module + " 테이블 버전 " + (i + 1));
            }
            return null;
        });
    }

    /** Finishes queued writes, then closes. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) log.warning("DB: 저장 대기 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (onClose != null) onClose.close();
        } catch (Exception e) {
            log.log(Level.WARNING, "DB 닫기 실패", e);
        }
    }
}
