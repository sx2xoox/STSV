package dev.stsv.economy.dock;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * contents/economy/weights.yml: what one of each item weighs in a vehicle's bed (적재 무게, README). Pure (unit-tested).
 *
 * <p>An item's weight, first that applies: its own line under {@code items}, the {@code categories} that list it, the
 * first {@code rules} pattern its id matches ("*_ingot", "minecraft:*_log"; the part after the namespace when the pattern
 * has none), {@code block} for a vanilla item that is a block, else {@code default}. A stack weighs its count times that.
 */
public record WeightTable(double fallback, double block, List<Rule> rules, Map<String, Double> items, Map<String, String> categoryOf) {

    /** A name pattern: '*' matches anything; alternatives with '|'. */
    public record Rule(String pattern, double weight) {
        boolean matches(String id) {
            for (String alt : pattern.split("\\|")) {
                String p = alt.trim();
                String subject = p.contains(":") ? id : id.substring(id.indexOf(':') + 1);
                if (glob(p, subject)) return true;
            }
            return false;
        }

        private static boolean glob(String p, String s) {
            StringBuilder re = new StringBuilder();
            for (char c : p.toCharArray()) re.append(c == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf(c)));
            return s.matches(re.toString());
        }
    }

    public static final WeightTable NONE = new WeightTable(1, 1, List.of(), Map.of(), Map.of());

    /** One item's weight. isBlock: is this vanilla id a block (Material#isBlock at run time). */
    public double weight(String id, Predicate<String> isBlock) {
        String key = norm(id);
        Double own = items.get(key);
        if (own != null) return own;
        for (Rule r : rules) if (r.matches(key)) return r.weight();
        if (key.startsWith("minecraft:") && isBlock.test(key)) return block;
        return fallback;
    }

    /** The category an item's weight comes from (null: a rule, a block or the default). */
    public String category(String id) {
        return categoryOf.get(norm(id));
    }

    static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static WeightTable parse(ConfigurationSection c, List<String> problems) {
        double fallback = positive(c.getDouble("default", 1), "default", problems);
        double block = positive(c.getDouble("block", 2), "block", problems);
        Map<String, Double> items = new LinkedHashMap<>();
        Map<String, String> categoryOf = new HashMap<>();
        ConfigurationSection cats = c.getConfigurationSection("categories");
        if (cats != null) {
            for (String cat : cats.getKeys(false)) {
                ConfigurationSection s = cats.getConfigurationSection(cat);
                if (s == null || !s.contains("weight")) {
                    problems.add("categories." + cat + ": weight, items 필요");
                    continue;
                }
                double w = positive(s.getDouble("weight"), "categories." + cat + ".weight", problems);
                for (String raw : s.getStringList("items")) {
                    String id = norm(raw);
                    if (categoryOf.containsKey(id)) problems.add("categories." + cat + ": " + id + " 은(는) 이미 " + categoryOf.get(id) + " 에 있음");
                    categoryOf.put(id, cat);
                    items.put(id, w);
                }
            }
        }
        ConfigurationSection own = c.getConfigurationSection("items");
        if (own != null) {
            for (String raw : own.getKeys(false)) {
                String id = norm(raw.replace('.', ':'));
                items.put(id, positive(own.getDouble(raw), "items." + raw, problems));
                categoryOf.put(id, "items");
            }
        }
        List<Rule> rules = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("rules")) {
            Object match = m.get("match"), weight = m.get("weight");
            if (match == null || !(weight instanceof Number n) || n.doubleValue() <= 0) {
                problems.add("rules: match, weight(0보다 큼) 필요 (" + m + ")");
                continue;
            }
            rules.add(new Rule(String.valueOf(match).toLowerCase(Locale.ROOT), n.doubleValue()));
        }
        return new WeightTable(fallback, block, List.copyOf(rules), Map.copyOf(items), Map.copyOf(categoryOf));
    }

    private static double positive(double d, String what, List<String> problems) {
        if (!(d > 0)) {
            problems.add(what + ": 0보다 커야 함 (" + d + ")");
            return 1;
        }
        return d;
    }
}
