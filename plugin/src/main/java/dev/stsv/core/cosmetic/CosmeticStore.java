package dev.stsv.core.cosmetic;

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
 * Account-bound cosmetic ownership, kept in the plugin's own data folder (plugins/STSV/data/cosmetics.yml)
 * so it survives season resets that wipe worlds and player data.
 *
 * <pre>
 * &lt;uuid&gt;:
 *   owned: [stsv:tomato_mask, stsv:eggplant_mask]
 *   equipped: stsv:tomato_mask
 * </pre>
 * Writes go to a temp file first and are then moved over the real file, so a crash mid-save never
 * leaves a half-written file.
 */
public final class CosmeticStore {

    public record Entry(Set<String> owned, String equipped) {}

    private final Path file;
    private final Map<UUID, Set<String>> owned = new HashMap<>();
    private final Map<UUID, String> equipped = new HashMap<>();

    public CosmeticStore(Path file) {
        this.file = file;
    }

    public void load() {
        owned.clear();
        equipped.clear();
        if (!Files.exists(file)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        for (String key : y.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = y.getConfigurationSection(key);
            if (s == null) continue;
            owned.put(id, new LinkedHashSet<>(s.getStringList("owned")));
            String eq = s.getString("equipped");
            if (eq != null && !eq.isEmpty()) equipped.put(id, eq);
        }
    }

    public synchronized void save() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("계정별 치장 보유/착용 (시즌 초기화와 무관하게 유지됩니다)"));
        Set<UUID> all = new LinkedHashSet<>(owned.keySet());
        all.addAll(equipped.keySet());
        for (UUID id : all) {
            y.set(id + ".owned", List.copyOf(owned.getOrDefault(id, Set.of())));
            y.set(id + ".equipped", equipped.get(id));
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, y.saveToString());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Set<String> owned(UUID player) {
        return Collections.unmodifiableSet(owned.getOrDefault(player, Set.of()));
    }

    /** @return true if it was newly added */
    public boolean grant(UUID player, String cosmetic) {
        return owned.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(cosmetic);
    }

    /** @return true if the player had it */
    public boolean revoke(UUID player, String cosmetic) {
        boolean had = owned.getOrDefault(player, new LinkedHashSet<>()).remove(cosmetic);
        if (cosmetic.equals(equipped.get(player))) equipped.remove(player);
        return had;
    }

    public Optional<String> equipped(UUID player) {
        return Optional.ofNullable(equipped.get(player));
    }

    public void setEquipped(UUID player, String cosmetic) {
        if (cosmetic == null) equipped.remove(player);
        else equipped.put(player, cosmetic);
    }
}
