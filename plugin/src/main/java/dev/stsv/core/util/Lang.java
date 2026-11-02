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
 * player.sendMessage(Lang.msg("wardrobe.zone-only"));
 * player.sendActionBar(Lang.mm("wardrobe.wearing", "cosmetic", name));
 * </pre>
 * Admin command output stays in code (it is for operators, not part of a content's voice).
 */
public final class Lang {

    private static YamlConfiguration messages = new YamlConfiguration();

    private Lang() {}

    public static void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var bundled = plugin.getResource("messages.yml");
        if (bundled != null) {
            y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        messages = y;
    }

    /** For tests and tools: use a given messages file. */
    public static void use(YamlConfiguration y) {
        messages = y;
    }

    /** The raw MiniMessage text with {placeholders} filled: pairs of name, value. */
    public static String raw(String key, Object... pairs) {
        String s = messages.getString(key);
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
        for (String line : messages.getStringList(key)) {
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
