package dev.stsv.economy.event;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * contents/economy/events.yml: the kinds of events (품귀, 풍년, ...), how long each lasts, the colour of
 * its boss bar and its own settings. Pure data (unit-tested).
 */
public record EventSpec(String id, String name, double hours, String color, Map<String, String> params) {

    /** Does it come up in the chain by itself? (chain: false = only when an admin starts it) */
    public boolean chained() {
        return !"false".equalsIgnoreCase(params.getOrDefault("chain", "true"));
    }

    /** How often the chain picks it, relative to the others. */
    public double weight() {
        return number("weight", 1);
    }

    /** A list setting ("[farmer, fisher]" or "farmer, fisher") as its items. */
    public static java.util.List<String> list(String v) {
        if (v == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String x : v.replace("[", "").replace("]", "").split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    public double number(String key, double def) {
        try {
            return params.containsKey(key) ? Double.parseDouble(params.get(key)) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static Map<String, EventSpec> parse(ConfigurationSection c, List<String> problems) {
        Map<String, EventSpec> out = new LinkedHashMap<>();
        ConfigurationSection ev = c.getConfigurationSection("events");
        if (ev == null) {
            problems.add("events 가 없습니다");
            return out;
        }
        for (String id : ev.getKeys(false)) {
            ConfigurationSection e = ev.getConfigurationSection(id);
            if (e == null) continue;
            if (e.getDouble("hours", -1) < 0) problems.add(id + ": hours 는 0 이상 (0 = 바로 끝나는 사건)");
            if (e.contains("weight") && e.getDouble("weight") < 0) problems.add(id + ": weight 는 0 이상");
            Map<String, String> params = new LinkedHashMap<>();
            for (String k : e.getKeys(false)) {
                if (k.equals("name") || k.equals("hours") || k.equals("color")) continue;
                params.put(k, String.valueOf(e.get(k)));
            }
            out.put(id, new EventSpec(id, e.getString("name", id), e.getDouble("hours", 24), e.getString("color", "yellow"), params));
        }
        return out;
    }
}
