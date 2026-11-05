package dev.stsv.economy.job;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * contents/economy/jobs.yml: the six jobs, the level curve (Lv1-30, XP kept per job), what each action is
 * worth, the effects that grow with the level and the ones that unlock at a level. Pure data (unit-tested).
 */
public record JobSpec(int maxLevel, double a, double b, long changeCost, int changeDays, Map<String, Job> jobs) {

    /** An effect that grows evenly from its Lv1 value to its max-level value. */
    public record Range(double lo, double hi) {
        public double at(int level, int maxLevel) {
            if (level <= 0) return 0;
            if (maxLevel <= 1) return hi;
            return lo + (hi - lo) * (Math.min(level, maxLevel) - 1) / (maxLevel - 1.0);
        }
    }

    /** An effect that switches on at a level (value = chance, factor or bonus); speed is per level from Lv1. */
    public record Bonus(int level, double value) {}

    /** Finds: gems in ores (drops by block) or rare catches (weighted pool), with a doubling from a level. */
    public record Finds(Range chance, double outsider, Map<String, List<String>> drops, Map<String, Integer> pool,
                        int doubleLevel, Set<String> doubleItems) {

        public List<String> drops(String key) {
            List<String> v = drops.get(key);
            if (v == null && key.startsWith("minecraft:")) v = drops.get(key.substring(10));
            return v == null ? List.of() : v;
        }

        /** Weighted pick from the pool; roll in [0, 1). */
        public String pick(double roll) {
            int total = pool.values().stream().mapToInt(Integer::intValue).sum();
            if (total <= 0) return null;
            double at = roll * total;
            for (Map.Entry<String, Integer> e : pool.entrySet()) {
                at -= e.getValue();
                if (at < 0) return e.getKey();
            }
            return pool.keySet().iterator().next();
        }
    }

    public record Job(String id, String name, String color, String icon, String machine, List<String> description,
                      Map<String, Range> ranges, Map<String, Bonus> bonuses, Map<String, Map<String, Double>> xp, Finds finds,
                      double harvestPerLevel, Bonus growth, int growthRadius, int areaHarvest) {

        public double range(String key, int level, int maxLevel) {
            Range r = ranges.get(key);
            return r == null ? 0 : r.at(level, maxLevel);
        }

        /**
         * A machine bonus at a level: 0 below its level. "speed" is per level (Lv12 with 0.01 = 0.12);
         * "fast" and "aging" are time factors (1 = no change).
         */
        public double bonus(String key, int level) {
            Bonus b = bonuses.get(key);
            boolean factor = key.equals("fast") || key.equals("aging");
            if (b == null || level <= 0) return factor ? 1 : 0;
            if (key.equals("speed")) return b.value() * level;
            if (level < b.level()) return factor ? 1 : 0;
            return b.value();
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

    /** Cumulative XP a level needs: a(n-1)² + b(n-1) (Lv1 = 0). */
    public double xpFor(int level) {
        int n = Math.max(1, Math.min(level, maxLevel)) - 1;
        return a * n * n + b * n;
    }

    /** The level for an amount of XP (1 .. maxLevel). */
    public int levelFor(double xp) {
        int lv = 1;
        while (lv < maxLevel && xp >= xpFor(lv + 1)) lv++;
        return lv;
    }

    /** XP past the last level: the 숙련 that ranks people of the same job. */
    public double mastery(double xp) {
        return Math.max(0, xp - xpFor(maxLevel));
    }

    /** The job whose machine this is, or null. */
    public Job ofMachine(String machine) {
        for (Job j : jobs.values()) if (machine.equals(j.machine())) return j;
        return null;
    }

    public static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("#")) return s;
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static Range range(ConfigurationSection c, String key) {
        List<Double> v = c.getDoubleList(key);
        if (v.isEmpty()) return null;
        return new Range(v.get(0), v.size() > 1 ? v.get(1) : v.get(0));
    }

    public static JobSpec parse(ConfigurationSection c, List<String> problems) {
        int max = Math.max(1, c.getInt("max-level", 30));
        Map<String, Job> jobs = new LinkedHashMap<>();
        ConfigurationSection js = c.getConfigurationSection("jobs");
        if (js != null) {
            for (String id : js.getKeys(false)) {
                ConfigurationSection j = js.getConfigurationSection(id);
                if (j == null) continue;
                Map<String, Range> ranges = new LinkedHashMap<>();
                for (String k : List.of("extra", "save")) {
                    Range r = range(j, k);
                    if (r != null) ranges.put(k, r);
                }
                Map<String, Bonus> bonuses = new LinkedHashMap<>();
                ConfigurationSection mb = j.getConfigurationSection("machine-bonus");
                if (mb != null) {
                    for (String k : mb.getKeys(false)) {
                        if (mb.isConfigurationSection(k)) {
                            ConfigurationSection b = mb.getConfigurationSection(k);
                            double v = b.contains("chance") ? b.getDouble("chance") : b.contains("factor") ? b.getDouble("factor") : b.getDouble("bonus");
                            bonuses.put(k, new Bonus(b.getInt("level", 1), v));
                        } else {
                            bonuses.put(k, new Bonus(1, mb.getDouble(k)));
                        }
                        if (!List.of("speed", "extra", "double", "save", "fast", "aging", "sell").contains(k)) {
                            problems.add(id + ".machine-bonus: 모르는 효과 " + k);
                        }
                    }
                }
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
                Finds finds = null;
                ConfigurationSection fs = j.getConfigurationSection("finds");
                if (fs != null) {
                    Map<String, List<String>> drops = new LinkedHashMap<>();
                    ConfigurationSection ds = fs.getConfigurationSection("drops");
                    if (ds != null) for (String k : ds.getKeys(false)) drops.put(k.toLowerCase(Locale.ROOT), ds.getStringList(k).stream().map(JobSpec::norm).toList());
                    Map<String, Integer> pool = new LinkedHashMap<>();
                    ConfigurationSection ps = fs.getConfigurationSection("pool");
                    if (ps != null) for (String k : ps.getKeys(false)) pool.put(norm(k), Math.max(0, ps.getInt(k)));
                    Set<String> dbl = new LinkedHashSet<>(fs.getStringList("double.items").stream().map(JobSpec::norm).toList());
                    Range chance = range(fs, "chance");
                    finds = new Finds(chance == null ? new Range(0, 0) : chance, fs.getDouble("outsider", 0), drops, pool,
                            fs.getInt("double.level", Integer.MAX_VALUE), dbl);
                }
                Bonus growth = j.isConfigurationSection("growth")
                        ? new Bonus(j.getInt("growth.level", 1), j.getDouble("growth.chance", 0)) : null;
                String machine = j.getString("machine");
                jobs.put(id, new Job(id, j.getString("name", id), j.getString("color", "white"), j.getString("icon", "minecraft:paper"),
                        machine, j.getStringList("description"), ranges, bonuses, xp, finds,
                        j.getDouble("harvest-per-level", 0), growth, j.getInt("growth.radius", 6), j.getInt("area-harvest", 0)));
            }
        }
        if (jobs.isEmpty()) problems.add("jobs: 직업이 없음");
        ConfigurationSection curve = c.getConfigurationSection("xp-curve");
        double a = curve == null ? 40 : curve.getDouble("a", 40), b = curve == null ? 60 : curve.getDouble("b", 60);
        return new JobSpec(max, a, b, c.getLong("change-cost", 1000), c.getInt("change-days", 7), jobs);
    }

    /** For tests and messages: the job ids in file order. */
    public List<String> ids() {
        return new ArrayList<>(jobs.keySet());
    }
}
