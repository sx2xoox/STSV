package dev.stsv.core.data;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import org.bukkit.configuration.ConfigurationSection;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Opens the shared database (config "database"). SQLite needs nothing (one file in the plugin folder);
 * MySQL/MariaDB lets several servers share accounts, cosmetics and records. Drivers are fetched by
 * the server from Maven Central on first start (plugin.yml "libraries").
 */
public final class DatabaseModule implements StsvModule {

    private Db db;

    @Override
    public String id() {
        return "database";
    }

    public Db db() {
        return db;
    }

    @Override
    public void enable(STSVPlugin plugin) throws Exception {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("database");
        String type = c.getString("type").toLowerCase(java.util.Locale.ROOT);
        if (type.equals("mysql") || type.equals("mariadb")) {
            com.zaxxer.hikari.HikariConfig h = new com.zaxxer.hikari.HikariConfig();
            h.setDriverClassName("org.mariadb.jdbc.Driver");   // MariaDB's driver talks to MySQL too
            h.setJdbcUrl("jdbc:mariadb://" + c.getString("mysql.host") + ":" + c.getInt("mysql.port") + "/"
                    + c.getString("mysql.database"));
            h.setUsername(c.getString("mysql.user"));
            h.setPassword(c.getString("mysql.password"));
            h.setMaximumPoolSize(c.getInt("mysql.pool-size"));
            h.setPoolName("STSV");
            com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource(h);
            db = new Db(ds::getConnection, Db.Dialect.MYSQL, c.getInt("mysql.pool-size"), plugin.getLogger(), ds);
            plugin.getLogger().info("DB: MySQL " + c.getString("mysql.host") + "/" + c.getString("mysql.database"));
        } else {
            Path file = plugin.getDataFolder().toPath().resolve(c.getString("sqlite-file"));
            db = sqlite(file, plugin.getLogger());
            plugin.getLogger().info("DB: SQLite " + file);
        }
    }

    /** One SQLite connection for the whole server (SQLite allows one writer), used from one thread. */
    public static Db sqlite(Path file, java.util.logging.Logger log) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        Connection shared = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = shared.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        // callers close what they get; the shared connection must stay open, so hand out a wrapper
        Connection keepOpen = (Connection) Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try {
                        return method.invoke(shared, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new Db(() -> keepOpen, Db.Dialect.SQLITE, 1, log, shared);
    }

    @Override
    public void disable() {
        if (db != null) db.close();
    }

}
