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
public record JobSpec(int maxLevel, double a, double b, ChangeCost changeCost, int changeDays, Map<String, Job> jobs,
                      Map<String, Special> specials) {

    /** jobs.yml without a specials section (older content, tests). */
    public JobSpec(int maxLevel, double a, double b, ChangeCost changeCost, int changeDays, Map<String, Job> jobs) {
        this(maxLevel, a, b, changeCost, changeDays, jobs, Map.of());
    }

    /**
     * A special material (jobs.yml specials): anyone can find it, the job it belongs to far more often. Rolled once per event
     * ({@code from}: harvest = a ripe crop harvested, catch = a fish caught, break = an ore block broken without silk touch). The
     * admin shop does not buy it: players trade it; {@code value} is its reference price (recipes, the codex, the item's lore).
     */
    public record Special(String item, String job, String from, Range chance, double outsider, double value) {
        /** Chance per event for a player of {@code playerJob} at their level in it. */
        public double chance(String playerJob, int level, int maxLevel) {
            return job.equals(playerJob) ? chance.at(level, maxLevel) : outsider;
        }
    }

    /** The specials rolled on an event kind (harvest / catch / break), in file order. */
    public List<Special> specials(String from) {
        List<Special> out = new ArrayList<>();
        for (Special s : specials.values()) if (s.from().equals(from)) out.add(s);
        return out;
    }

    /**
     * What changing jobs costs, by the level the player has in the job they change TO (levels are kept per job): straight
     * lines between the points of jobs.yml change-cost (level: gold), flat before the first and after the last point. The
     * first pick is free (the module's rule, not this table's).
     */
    public record ChangeCost(java.util.NavigableMap<Integer, Long> points) {
        public ChangeCost {
            points = java.util.Collections.unmodifiableNavigableMap(new java.util.TreeMap<>(points));
        }

        public static ChangeCost flat(long cost) {
            return new ChangeCost(new java.util.TreeMap<>(Map.of(1, Math.max(0, cost))));
        }

        /** Gold, rounded to whole gold, for a target job level. */
        public long at(int level) {
            if (points.isEmpty()) return 0;
            Map.Entry<Integer, Long> lo = points.floorEntry(level), hi = points.ceilingEntry(level);
            if (lo == null) return hi.getValue();
            if (hi == null || hi.getKey().equals(lo.getKey())) return lo.getValue();
            double t = (level - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
            return Math.round(lo.getValue() + (hi.getValue() - lo.getValue()) * t);
        }

        public long min() {
            return points.values().stream().mapToLong(Long::longValue).min().orElse(0);
        }

        public long max() {
            return points.values().stream().mapToLong(Long::longValue).max().orElse(0);
        }
    }

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

    /**
     * Finds: gems in ores (drops by block), or the chance of a rare or legend bite (the fish themselves are in
     * fish.yml), with a doubling from a level.
     */
    public record Finds(Range chance, double outsider, Map<String, List<String>> drops, int doubleLevel, Set<String> doubleItems) {

        public List<String> drops(String key) {
            List<String> v = drops.get(key);
            if (v == null && key.startsWith("minecraft:")) v = drops.get(key.substring(10));
            return v == null ? List.of() : v;
        }

    }

    public record Job(String id, String name, String color, String icon, String machine, List<String> description,
                      Map<String, Range> ranges, Map<String, Bonus> bonuses, Map<String, Map<String, Double>> xp, Finds finds,
                      Range harvestBonus, Range harvestPctRange, Bonus growth, int growthRadius, int rowHarvest, int areaHarvest) {

        /** Vanilla crops: extra produce a harvest at a level (the fraction is a chance). */
        public double harvestBonus(int level, int maxLevel) {
            return harvestBonus == null ? 0 : harvestBonus.at(level, maxLevel);
        }

        /** The width of one hand harvest at a level: 9 (3x3) from area-harvest, 3 (a row of three) from row-harvest, else 1. */
        public int harvestSpan(int level) {
            if (areaHarvest > 0 && level >= areaHarvest) return 9;
            if (rowHarvest > 0 && level >= rowHarvest) return 3;
            return 1;
        }

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

        /** Chance per produce unit of one more on a custom crop at a level (before events): harvest-pct [Lv1, Lv30]. */
        public double harvestPct(int level, int maxLevel) {
            return harvestPctRange == null ? 0 : harvestPctRange.at(level, maxLevel);
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

    /**
     * A [Lv1, Lv30] effect, or the older per-level number (value x level: the same straight line, Lv1 = value, Lv30 = 30 x value).
     */
    private static Range perLevelOr(ConfigurationSection c, String key, String perLevelKey, int maxLevel) {
        Range r = range(c, key);
        if (r != null) return r;
        double p = c.getDouble(perLevelKey, 0);
        return new Range(p, p * maxLevel);
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
                for (String k : List.of("extra", "save", "bite")) {
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
                        if (!List.of("speed", "extra", "double", "save", "fast", "aging", "yield").contains(k)) {
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
                    Set<String> dbl = new LinkedHashSet<>(fs.getStringList("double.items").stream().map(JobSpec::norm).toList());
                    Range chance = range(fs, "chance");
                    finds = new Finds(chance == null ? new Range(0, 0) : chance, fs.getDouble("outsider", 0), drops,
                            fs.getInt("double.level", Integer.MAX_VALUE), dbl);
                }
                Bonus growth = j.isConfigurationSection("growth")
                        ? new Bonus(j.getInt("growth.level", 1), j.getDouble("growth.chance", 0)) : null;
                String machine = j.getString("machine");
                jobs.put(id, new Job(id, j.getString("name", id), j.getString("color", "white"), j.getString("icon", "minecraft:paper"),
                        machine, j.getStringList("description"), ranges, bonuses, xp, finds,
                        perLevelOr(j, "harvest-bonus", "harvest-per-level", max), perLevelOr(j, "harvest-pct", "harvest-pct-per-level", max),
                        growth, j.getInt("growth.radius", 6), j.getInt("row-harvest", 0), j.getInt("area-harvest", 0)));
            }
        }
        Map<String, Special> specials = new LinkedHashMap<>();
        ConfigurationSection ss = c.getConfigurationSection("specials");
        if (ss != null) {
            for (String k : ss.getKeys(false)) {
                ConfigurationSection s = ss.getConfigurationSection(k);
                if (s == null) continue;
                String item = norm(k), job = s.getString("job", ""), from = s.getString("from", "");
                Range chance = range(s, "chance");
                if (!jobs.containsKey(job)) problems.add("specials." + k + ": 모르는 직업 " + job);
                if (!List.of("harvest", "catch", "break").contains(from)) problems.add("specials." + k + ": from 은 harvest, catch, break 중 하나");
                if (chance == null) problems.add("specials." + k + ": chance [Lv1, Lv30] 필요");
                specials.put(item, new Special(item, job, from, chance == null ? new Range(0, 0) : chance, s.getDouble("outsider", 0),
                        s.getDouble("value", 0)));
            }
        }
        if (jobs.isEmpty()) problems.add("jobs: 직업이 없음");
        ConfigurationSection curve = c.getConfigurationSection("xp-curve");
        double a = curve == null ? 40 : curve.getDouble("a", 40), b = curve == null ? 60 : curve.getDouble("b", 60);
        return new JobSpec(max, a, b, changeCost(c, problems), c.getInt("change-days", 7), jobs, specials);
    }

    /** change-cost: one number (the same for every level) or { level: gold, ... } points. */
    static ChangeCost changeCost(ConfigurationSection c, List<String> problems) {
        ConfigurationSection s = c.getConfigurationSection("change-cost");
        if (s == null) return ChangeCost.flat(c.getLong("change-cost", 1000));
        java.util.TreeMap<Integer, Long> points = new java.util.TreeMap<>();
        for (String k : s.getKeys(false)) {
            try {
                long v = s.getLong(k, -1);
                if (v < 0) throw new NumberFormatException();
                points.put(Integer.parseInt(k.trim()), v);
            } catch (NumberFormatException e) {
                problems.add("change-cost." + k + ": 레벨(정수): 골드(0 이상) 필요");
            }
        }
        if (points.isEmpty()) {
            problems.add("change-cost: 비어 있음 (1,000G 로)");
            return ChangeCost.flat(1000);
        }
        return new ChangeCost(points);
    }

    /** For tests and messages: the job ids in file order. */
    public List<String> ids() {
        return new ArrayList<>(jobs.keySet());
    }
}
