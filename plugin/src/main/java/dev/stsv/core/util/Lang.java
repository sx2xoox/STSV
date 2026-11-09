package dev.stsv.core.util;

import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Player-facing texts, from plugins/STSV/messages.yml (created on first start; keys missing from an older
 * file fall back to the bundled messages.yml, like config.yml). A content can reword everything without
 * touching code. Placeholders are {name}; values are MiniMessage too.
 *
 * <pre>
 * player.sendMessage(Lang.msg("wardrobe.dismount"));
 * player.sendActionBar(Lang.mm("wardrobe.wearing", "cosmetic", name));
 * </pre>
 * Admin command output stays in code (it is for operators, not part of a content's voice).
 */
public final class Lang {

    private static YamlConfiguration messages = new YamlConfiguration();
    /** messages of content plugins (their own messages.yml), looked up after the core's */
    private static final java.util.Map<String, YamlConfiguration> extra = new java.util.LinkedHashMap<>();

    private Lang() {}

    public static void load(JavaPlugin plugin) {
        messages = open(plugin);
    }

    /** plugins/&lt;plugin&gt;/messages.yml with the jar's as defaults; old untouched defaults updated first. */
    private static YamlConfiguration open(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var bundled = plugin.getResource("messages.yml");
        if (bundled != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8));
            java.util.Set<String> history = history(plugin);
            int changed = upgrade(y, defaults, history);
            if (changed > 0) {
                try {
                    y.save(file);
                    plugin.getLogger().info("messages.yml: 예전 기본 문구 " + changed + "개를 새 문구로 바꿨습니다 (직접 고친 문구는 그대로).");
                } catch (java.io.IOException e) {
                    plugin.getLogger().warning("messages.yml 저장 실패: " + e.getMessage());
                }
            }
            y.setDefaults(defaults);
        }
        return y;
    }

    /** SHA-1s of every committed default value (tools/messages_history.py), empty if the jar has none. */
    private static java.util.Set<String> history(JavaPlugin plugin) {
        var in = plugin.getResource("default-messages-history.txt");
        if (in == null) return java.util.Set.of();
        try (var r = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().map(String::trim).filter(l -> !l.isEmpty()).collect(java.util.stream.Collectors.toSet());
        } catch (java.io.IOException e) {
            return java.util.Set.of();
        }
    }

    /**
     * Replaces every value of the server's file that is a former default (its hash is in the history)
     * and no longer the default by the jar's current text. A text the owner wrote stays. Returns how many.
     */
    public static int upgrade(YamlConfiguration user, YamlConfiguration bundled, java.util.Set<String> history) {
        int n = 0;
        for (String key : user.getKeys(true)) {
            if (user.isConfigurationSection(key) || !bundled.contains(key) || bundled.isConfigurationSection(key)) continue;
            String mine = flat(user, key), now = flat(bundled, key);
            if (mine == null || now == null || mine.equals(now)) continue;
            if (history.contains(sha1(key + "\0" + mine))) {
                user.set(key, bundled.get(key));
                n++;
            }
        }
        return n;
    }

    private static String flat(YamlConfiguration y, String key) {
        if (y.isList(key)) return String.join("\n", y.getStringList(key));
        Object o = y.get(key);
        return o == null ? null : String.valueOf(o);
    }

    static String sha1(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A content plugin's own plugins/&lt;plugin&gt;/messages.yml (defaults from its jar). Call again to
     * reload it (e.g. on /관리자 리로드).
     */
    public static void register(JavaPlugin plugin) {
        extra.put(plugin.getName(), open(plugin));
    }

    /** For tests: add a messages file as if a content plugin registered it. */
    public static void useExtra(String name, YamlConfiguration y) {
        extra.put(name, y);
    }

    private static String find(String key) {
        String s = messages.getString(key);
        if (s != null) return s;
        for (YamlConfiguration y : extra.values()) {
            s = y.getString(key);
            if (s != null) return s;
        }
        return null;
    }

    private static java.util.List<String> findList(String key) {
        if (messages.contains(key)) return messages.getStringList(key);
        for (YamlConfiguration y : extra.values()) if (y.contains(key)) return y.getStringList(key);
        return java.util.List.of();
    }

    /** Every text of every messages file (the core's and the content plugins', with their defaults). */
    public static java.util.List<String> allText() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<org.bukkit.configuration.Configuration> all = new java.util.ArrayList<>();
        all.add(messages);
        all.addAll(extra.values());
        for (org.bukkit.configuration.Configuration y : new java.util.ArrayList<>(all)) {
            if (y.getDefaults() != null) all.add(y.getDefaults());
        }
        for (org.bukkit.configuration.Configuration y : all) {
            for (String key : y.getKeys(true)) {
                if (y.isList(key)) out.addAll(y.getStringList(key));
                else if (y.isString(key)) out.add(y.getString(key));
            }
        }
        return out;
    }

    /** For tests and tools: use a given messages file. */
    public static void use(YamlConfiguration y) {
        messages = y;
    }

    /** The raw MiniMessage text with {placeholders} filled: pairs of name, value. */
    public static String raw(String key, Object... pairs) {
        String s = find(key);
        if (s == null) return "<red>[" + key + "]";
        for (int i = 0; i + 1 < pairs.length; i += 2) s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        return s;
    }

    public static Component mm(String key, Object... pairs) {
        return Text.mm(raw(key, pairs));
    }

    /** Item names/lore (no default italic). */
    public static Component item(String key, Object... pairs) {
        return Text.item(raw(key, pairs));
    }

    /** A list of item lore lines. */
    public static java.util.List<Component> items(String key, Object... pairs) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (String line : findList(key)) {
            for (int i = 0; i + 1 < pairs.length; i += 2) line = line.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
            out.add(Text.item(line));
        }
        return out;
    }

    /** Chat message with the server prefix. */
    public static Component msg(String key, Object... pairs) {
        return Text.mm(prefix() + raw(key, pairs));
    }

    public static String prefix() {
        String p = messages.getString("prefix");
        return p == null ? Text.PREFIX : p;
    }
}
