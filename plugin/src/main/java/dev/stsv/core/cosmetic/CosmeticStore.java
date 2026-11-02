package dev.stsv.core.cosmetic;

import dev.stsv.core.data.Db;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Account-bound cosmetic ownership in the shared database (tables cosmetic_owned, cosmetic_equipped),
 * so it survives world resets and moves between contents. Everything is kept in memory (it is small) and
 * every change is written straight away on the database thread.
 *
 * Servers from before the database kept this in plugins/STSV/data/cosmetics.yml: on first start the file
 * is imported once and renamed to cosmetics.yml.imported.
 */
public final class CosmeticStore {

    private static final List<List<String>> SCHEMA = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS cosmetic_owned (player CHAR(36) NOT NULL, item VARCHAR(128) NOT NULL, PRIMARY KEY (player, item))",
            "CREATE TABLE IF NOT EXISTS cosmetic_equipped (player CHAR(36) NOT NULL PRIMARY KEY, item VARCHAR(128) NOT NULL)"));

    private final Db db;
    private final Map<UUID, Set<String>> owned = new HashMap<>();
    private final Map<UUID, String> equipped = new HashMap<>();

    public CosmeticStore(Db db) {
        this.db = db;
    }

    /** Creates the tables, imports the old YAML file if there is one, and loads everything. */
    public synchronized void load(Path legacyYaml) throws IOException {
        db.migrate("cosmetics", SCHEMA);
        if (legacyYaml != null && Files.exists(legacyYaml)) importYaml(legacyYaml);
        owned.clear();
        equipped.clear();
        db.sync(c -> {
            for (String[] r : Db.query(c, "SELECT player, item FROM cosmetic_owned", rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                owned.computeIfAbsent(UUID.fromString(r[0]), k -> new LinkedHashSet<>()).add(r[1]);
            }
            for (String[] r : Db.query(c, "SELECT player, item FROM cosmetic_equipped", rs -> new String[]{rs.getString(1), rs.getString(2)})) {
                equipped.put(UUID.fromString(r[0]), r[1]);
            }
            return null;
        });
    }

    private void importYaml(Path file) throws IOException {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        String grant = db.upsert("cosmetic_owned", List.of("player", "item"), List.of());
        String wear = db.upsert("cosmetic_equipped", List.of("player"), List.of("item"));
        db.sync(c -> {
            for (String key : y.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ConfigurationSection s = y.getConfigurationSection(key);
                if (s == null) continue;
                for (String item : s.getStringList("owned")) Db.update(c, grant, id.toString(), item);
                String eq = s.getString("equipped");
                if (eq != null && !eq.isEmpty()) Db.update(c, wear, id.toString(), eq);
            }
            return null;
        });
        Files.move(file, file.resolveSibling(file.getFileName() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized Set<String> owned(UUID player) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(owned.getOrDefault(player, Set.of())));
    }

    /** @return true if it was newly added */
    public synchronized boolean grant(UUID player, String cosmetic) {
        boolean added = owned.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(cosmetic);
        if (added) db.execute(db.upsert("cosmetic_owned", List.of("player", "item"), List.of()), player.toString(), cosmetic);
        return added;
    }

    /** @return true if the player had it */
    public synchronized boolean revoke(UUID player, String cosmetic) {
        boolean had = owned.getOrDefault(player, new LinkedHashSet<>()).remove(cosmetic);
        if (had) db.execute("DELETE FROM cosmetic_owned WHERE player = ? AND item = ?", player.toString(), cosmetic);
        if (cosmetic.equals(equipped.get(player))) setEquipped(player, null);
        return had;
    }

    public synchronized Optional<String> equipped(UUID player) {
        return Optional.ofNullable(equipped.get(player));
    }

    public synchronized void setEquipped(UUID player, String cosmetic) {
        if (cosmetic == null) {
            if (equipped.remove(player) != null) db.execute("DELETE FROM cosmetic_equipped WHERE player = ?", player.toString());
        } else if (!cosmetic.equals(equipped.put(player, cosmetic))) {
            db.execute(db.upsert("cosmetic_equipped", List.of("player"), List.of("item")), player.toString(), cosmetic);
        }
    }
}
