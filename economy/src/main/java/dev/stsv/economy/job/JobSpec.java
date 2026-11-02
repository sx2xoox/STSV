package dev.stsv.economy.job;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * contents/economy/jobs.yml: the six jobs, the XP a level needs, what each action is worth, the
 * passives per level and which products only a job (at a level) can make. Pure data (unit-tested).
 */
public record JobSpec(List<Double> levels, long changeCost, int changeDays, Map<String, Job> jobs, Map<String, Product> products) {

    /** One job. passive: name -> value per level (index 0 = Lv1). xp: action -> (thing -> xp). */
    public record Job(String id, String name, String color, String icon, List<String> description,
                      Map<String, List<Double>> passive, Map<String, Map<String, Double>> xp, Map<String, List<String>> drops) {

        /** Drop table by key: "tier1", "redstone_ore", "minecraft:redstone_ore" all work. */
        public List<String> drops(String key) {
            List<String> v = drops.get(key);
            if (v == null && key.startsWith("minecraft:")) v = drops.get(key.substring(10));
            return v == null ? List.of() : v;
        }

        public double passive(String key, int level) {
            List<Double> v = passive.get(key);
            if (v == null || v.isEmpty() || level <= 0) return 0;
            return v.get(Math.min(level, v.size()) - 1);
        }

        /** XP for an action on a thing ("break" / "minecraft:diamond_ore"); "*" is the fallback. */
        public double xp(String action, String thing) {
            Map<String, Double> m = xp.get(action);
            if (m == null) return 0;
            Double v = m.get(thing);
            if (v == null) v = m.get(thing.startsWith("minecraft:") ? thing.substring(10) : thing);
            if (v == null) v = m.get("*");
            return v == null ? 0 : v;
        }
    }

    /** A job product: who may make it, from which level, the XP for making it and enchantments it gets. */
    public record Product(String item, String job, int level, double xp, Map<String, Integer> enchants, boolean quality) {}

    /** The level for an amount of XP (1 to the number of levels), not capped. */
    public int levelFor(double xp) {
        int lv = 1;
        for (int i = 1; i < levels.size(); i++) if (xp >= levels.get(i)) lv = i + 1;
        return lv;
    }

    public int maxLevel() {
        return Math.max(1, levels.size());
    }

    /** XP needed to reach a level (Lv1 = 0). */
    public double xpFor(int level) {
        if (level <= 1 || levels.isEmpty()) return 0;
        return levels.get(Math.min(level, levels.size()) - 1);
    }

    /**
     * Adds XP under a level cap: XP that would go past the cap's ceiling (one short of the next level)
     * becomes mastery instead, so the cap rising next week does not hand out a banked level at once
     * beyond the next one. Returns {newXp, masteryGained}.
     */
    public double[] gain(double xp, double add, int cap) {
        if (add <= 0) return new double[]{xp, 0};
        if (cap >= maxLevel()) return new double[]{xp + add, 0};
        double ceiling = xpFor(cap + 1) - 1;
        if (xp >= ceiling) return new double[]{xp, add};
        double room = ceiling - xp;
        return add <= room ? new double[]{xp + add, 0} : new double[]{ceiling, add - room};
    }

    public static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static JobSpec parse(ConfigurationSection c, List<String> problems) {
        List<Double> levels = new ArrayList<>(c.getDoubleList("levels"));
        if (levels.isEmpty()) levels = List.of(0.0, 1000.0, 3000.0);
        Map<String, Job> jobs = new LinkedHashMap<>();
        ConfigurationSection js = c.getConfigurationSection("jobs");
        if (js != null) {
            for (String id : js.getKeys(false)) {
                ConfigurationSection j = js.getConfigurationSection(id);
                if (j == null) continue;
                Map<String, List<Double>> passive = new LinkedHashMap<>();
                ConfigurationSection ps = j.getConfigurationSection("passive");
                if (ps != null) for (String k : ps.getKeys(false)) passive.put(k, ps.getDoubleList(k));
                Map<String, Map<String, Double>> xp = new LinkedHashMap<>();
                ConfigurationSection xs = j.getConfigurationSection("xp");
                if (xs != null) {
                    for (String action : xs.getKeys(false)) {
                        Map<String, Double> m = new LinkedHashMap<>();
                        if (xs.isConfigurationSection(action)) {
                            ConfigurationSection a = xs.getConfigurationSection(action);
                            for (String thing : a.getKeys(false)) m.put(thing.equals("*") ? "*" : norm(thing), a.getDouble(thing));
                        } else {
                            m.put("*", xs.getDouble(action));
                        }
                        xp.put(action, m);
                    }
                }
                Map<String, List<String>> drops = new LinkedHashMap<>();
                ConfigurationSection ds = j.getConfigurationSection("drops");
                // keys: a block / entity / item id, or a free word (tier1)
                if (ds != null) for (String k : ds.getKeys(false)) drops.put(k.toLowerCase(Locale.ROOT), ds.getStringList(k).stream().map(JobSpec::norm).toList());
                jobs.put(id, new Job(id, j.getString("name", id), j.getString("color", "white"), j.getString("icon", "minecraft:paper"),
                        j.getStringList("description"), passive, xp, drops));
            }
        }
        if (jobs.isEmpty()) problems.add("jobs: 직업이 없음");
        Map<String, Product> products = new LinkedHashMap<>();
        ConfigurationSection pr = c.getConfigurationSection("products");
        if (pr != null) {
            for (String raw : pr.getKeys(false)) {
                ConfigurationSection p = pr.getConfigurationSection(raw);
                if (p == null) continue;
                String job = p.getString("job");
                if (job == null || !jobs.containsKey(job)) {
                    problems.add("products." + raw + ": 없는 직업 " + job);
                    continue;
                }
                Map<String, Integer> ench = new LinkedHashMap<>();
                ConfigurationSection es = p.getConfigurationSection("enchants");
                if (es != null) for (String k : es.getKeys(false)) ench.put(k.toLowerCase(Locale.ROOT), es.getInt(k));
                // products are the pack's own items: a bare id means economy:<id>
                String key = raw.replace('.', ':').toLowerCase(Locale.ROOT);
                String id = key.contains(":") ? key : "economy:" + key;
                products.put(id, new Product(id, job, Math.max(1, p.getInt("level", 1)), p.getDouble("xp", 0), ench, p.getBoolean("quality", false)));
            }
        }
        return new JobSpec(levels, c.getLong("change-cost", 1000), c.getInt("change-days", 7), jobs, products);
    }
}
