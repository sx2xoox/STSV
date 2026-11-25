package dev.stsv.economy.mission;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * contents/economy/missions.yml: the daily and weekly mission pools. Each player gets their own set:
 * one mission of their job (if they have one and the pool has any) and the rest from the missions for
 * everyone, never two on the same stat group. Pure (unit-tested).
 *
 * <pre>
 * daily:
 *   count: 3
 *   rerolls: 1                    # times a player may swap a mission for another, per day
 *   bonus: { gold: 500, xp: 50 }  # for finishing all of them
 *   missions:
 *     ores:
 *       name: "광석 64개 캐기"
 *       stat: ores_mined          # what is counted: how much it grows from when the mission was given
 *       goal: 64
 *       job: miner                # the job mission of miners (none: for everyone)
 *       group: mining             # never two of a group in one set (default: the stat)
 *       unit: "개"
 *       icon: minecraft:iron_ore
 *       weight: 10
 *       gold: 150
 *       xp: 40                    # job XP for the job the player has when it is done
 *       items: ["minecraft:diamond x2"]
 * weekly: { ...the same }
 * </pre>
 */
public record MissionSpec(Kind daily, Kind weekly) {

    public enum Period {
        DAILY("d"), WEEKLY("w");

        /** prefix of the stored period key ("d12" = day 12, "w3" = week 3) */
        public final String prefix;

        Period(String prefix) {
            this.prefix = prefix;
        }

        public String key(int n) {
            return prefix + n;
        }
    }

    public record ItemReward(String id, int amount) {}

    /** gold, job XP, items, and 도장 (stamps.yml: never traded, fill the month's board). */
    public record Reward(long gold, double xp, List<ItemReward> items, int stamps) {
        public static final Reward NONE = new Reward(0, 0, List.of(), 0);

        public Reward(long gold, double xp, List<ItemReward> items) {
            this(gold, xp, items, 0);
        }

        public boolean isEmpty() {
            return gold <= 0 && xp <= 0 && items.isEmpty() && stamps <= 0;
        }
    }

    /** minLevel: drawn only for players whose highest job level is at least this (0: anyone; things a beginner cannot do yet). */
    public record Mission(String id, String name, String stat, double goal, String job, String group, String unit,
                          String icon, int weight, Reward reward, int minLevel) {
        public Mission(String id, String name, String stat, double goal, String job, String group, String unit, String icon, int weight,
                       Reward reward) {
            this(id, name, stat, goal, job, group, unit, icon, weight, reward, 0);
        }
    }

    /**
     * A job level band (missions.yml bands): from this level of the mission's job, its goal is multiplied by goal and its gold
     * and XP by reward, so a job mission is 10~15 minutes of that player's work at any level (stamps stay the same).
     */
    public record Band(int from, double goal, double reward) {
        public static final Band NONE = new Band(0, 1, 1);
    }

    public record Kind(int count, int rerolls, Reward bonus, Map<String, Mission> missions, List<Band> bands) {
        public Kind {
            missions = Map.copyOf(missions);
            bands = List.copyOf(bands);
        }

        public Kind(int count, int rerolls, Reward bonus, Map<String, Mission> missions) {
            this(count, rerolls, bonus, missions, List.of());
        }

        /** The band (index into bands, -1: none) of a job level. */
        public int band(int level) {
            int out = -1;
            for (int i = 0; i < bands.size(); i++) if (level >= bands.get(i).from()) out = i;
            return out;
        }

        public Band bandAt(int index) {
            return index < 0 || index >= bands.size() ? Band.NONE : bands.get(index);
        }

        /** Missions for everyone. */
        public List<Mission> general() {
            return sorted(missions.values().stream().filter(m -> m.job() == null).toList());
        }

        /** The job missions of a job. */
        public List<Mission> ofJob(String job) {
            return job == null ? List.of() : sorted(missions.values().stream().filter(m -> job.equals(m.job())).toList());
        }
    }

    public Kind kind(Period p) {
        return p == Period.DAILY ? daily : weekly;
    }

    // ------------------------------------------------------------------------------------------ parse

    public static MissionSpec parse(ConfigurationSection file, List<String> problems) {
        return new MissionSpec(kind(file.getConfigurationSection("daily"), "daily", problems),
                kind(file.getConfigurationSection("weekly"), "weekly", problems));
    }

    private static Kind kind(ConfigurationSection s, String where, List<String> problems) {
        if (s == null) {
            problems.add(where + ": 없음");
            return new Kind(0, 0, Reward.NONE, Map.of());
        }
        int defStamps = s.getInt("stamps", 0);
        Map<String, Mission> out = new LinkedHashMap<>();
        ConfigurationSection list = s.getConfigurationSection("missions");
        if (list == null) problems.add(where + ".missions: 없음");
        else for (String key : list.getKeys(false)) {
            ConfigurationSection m = list.getConfigurationSection(key);
            String at = where + ".missions." + key;
            if (m == null) {
                problems.add(at + ": 형식이 잘못됨");
                continue;
            }
            String stat = m.getString("stat");
            double goal = m.getDouble("goal", 0);
            if (stat == null || stat.isBlank()) {
                problems.add(at + ": stat 필요");
                continue;
            }
            if (goal <= 0) {
                problems.add(at + ": goal (0보다 큼) 필요");
                continue;
            }
            int weight = m.getInt("weight", 10);
            if (weight <= 0) problems.add(at + ": weight 는 1 이상");
            String job = m.getString("job");
            Reward rw = reward(m, at, problems);
            if (!m.contains("stamps")) rw = new Reward(rw.gold(), rw.xp(), rw.items(), defStamps);
            out.put(key, new Mission(key, m.getString("name", key), stat.trim(), goal, job == null || job.isBlank() ? null : job.trim(),
                    m.getString("group", stat).trim(), m.getString("unit", ""), normId(m.getString("icon", "minecraft:paper")),
                    Math.max(1, weight), rw, Math.max(0, m.getInt("min-level", 0))));
        }
        int count = s.getInt("count", 3);
        if (count < 1) problems.add(where + ".count: 1 이상");
        int rerolls = s.getInt("rerolls", 0);
        if (rerolls < 0) problems.add(where + ".rerolls: 0 이상");
        ConfigurationSection bonus = s.getConfigurationSection("bonus");
        List<Band> bands = new ArrayList<>();
        int last = -1;
        for (Map<?, ?> b : s.getMapList("bands")) {
            int from = b.get("from") instanceof Number n ? n.intValue() : -1;
            double g = b.get("goal") instanceof Number n ? n.doubleValue() : 1, rw = b.get("reward") instanceof Number n ? n.doubleValue() : 1;
            if (from <= last || g <= 0 || rw < 0) {
                problems.add(where + ".bands: from 은 커지는 순서, goal 은 0보다 크게 (" + b + ")");
                continue;
            }
            last = from;
            bands.add(new Band(from, g, rw));
        }
        return new Kind(Math.max(1, count), Math.max(0, rerolls), bonus == null ? Reward.NONE : reward(bonus, where + ".bonus", problems), out, bands);
    }

    private static Reward reward(ConfigurationSection s, String at, List<String> problems) {
        long gold = s.getLong("gold", 0);
        double xp = s.getDouble("xp", 0);
        int stamps = s.getInt("stamps", 0);
        if (stamps < 0) problems.add(at + ": stamps 는 0 이상");
        if (gold < 0) problems.add(at + ": gold 는 0 이상");
        if (xp < 0) problems.add(at + ": xp 는 0 이상");
        List<ItemReward> items = new ArrayList<>();
        for (String i : s.getStringList("items")) {
            ItemReward r = item(i);
            if (r == null) problems.add(at + ": items '" + i + "' (형식: 아이디 또는 '아이디 x개수')");
            else items.add(r);
        }
        return new Reward(Math.max(0, gold), Math.max(0, xp), List.copyOf(items), Math.max(0, stamps));
    }

    /** "minecraft:diamond x3" / "diamond" / "economy:ruby x2" */
    public static ItemReward item(String s) {
        String t = s.trim();
        int amount = 1;
        int x = t.lastIndexOf(" x");
        if (x > 0) {
            try {
                amount = Integer.parseInt(t.substring(x + 2).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            t = t.substring(0, x).trim();
        }
        if (t.isEmpty() || amount < 1 || t.contains(" ")) return null;
        return new ItemReward(normId(t), amount);
    }

    private static String normId(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static List<Mission> sorted(List<Mission> l) {
        return l.stream().sorted(java.util.Comparator.comparing(Mission::id)).toList();
    }

    // ------------------------------------------------------------------------------------------ draw

    /**
     * A new set for a player whose job is job (null: none): one of the job's missions first, then missions
     * for everyone, no two of the same group. Fewer than count if the pool runs out.
     */
    public static List<Mission> draw(Kind k, String job, Random r) {
        return draw(k, job, Integer.MAX_VALUE, r);
    }

    /** As {@link #draw(Kind, String, Random)} for a player whose highest job level is `level` (missions above it are left out). */
    public static List<Mission> draw(Kind k, String job, int level, Random r) {
        List<Mission> out = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        Mission first = pick(k.ofJob(job), groups, Set.of(), r);
        if (first != null) {
            out.add(first);
            groups.add(first.group());
        }
        List<Mission> general = open(k.general(), level);
        while (out.size() < k.count()) {
            Mission m = pick(general, groups, Set.of(), r);
            if (m == null) break;
            out.add(m);
            groups.add(m.group());
        }
        return out;
    }

    /**
     * Another mission for slot index of the set: a job mission is swapped for another of the same job
     * (else one for everyone), a mission for everyone for another for everyone; never one already in the
     * set, never one of a group the other missions have. Null if there is none.
     */
    public static Mission reroll(Kind k, List<Mission> set, int index, Random r) {
        return reroll(k, set, index, Integer.MAX_VALUE, r);
    }

    public static Mission reroll(Kind k, List<Mission> set, int index, int level, Random r) {
        Mission old = set.get(index);
        Set<String> groups = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < set.size(); i++) {
            ids.add(set.get(i).id());
            if (i != index) groups.add(set.get(i).group());
        }
        Mission m = old.job() == null ? null : pick(k.ofJob(old.job()), groups, ids, r);
        return m != null ? m : pick(open(k.general(), level), groups, ids, r);
    }

    private static List<Mission> open(List<Mission> l, int level) {
        return l.stream().filter(m -> m.minLevel() <= level).toList();
    }

    // ------------------------------------------------------------------------------------------ bands

    /** A job mission at a band: goal x band.goal (rounded up to a whole number), gold and XP x band.reward. */
    public static Mission scaled(Mission m, Band b) {
        if (b == null || (b.goal() == 1 && b.reward() == 1)) return m;
        double goal = Math.ceil(m.goal() * b.goal() - 1e-9);
        Reward r = m.reward();
        Reward scaled = new Reward(Math.round(r.gold() * b.reward()), Math.round(r.xp() * b.reward()), r.items(), r.stamps());
        return new Mission(m.id(), m.name(), m.stat(), goal, m.job(), m.group(), m.unit(), m.icon(), m.weight(), scaled, m.minLevel());
    }

    /** How a slot stores its mission: "ores" (no band) or "ores@2" (band index 2). */
    public static String slotKey(String id, int band) {
        return band < 0 ? id : id + "@" + band;
    }

    /** The mission id of a stored slot key. */
    public static String slotMission(String key) {
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    /** The band index of a stored slot key (-1: none). */
    public static int slotBand(String key) {
        int at = key.indexOf('@');
        if (at < 0) return -1;
        try {
            return Integer.parseInt(key.substring(at + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "{goal}" in a name becomes the (scaled) goal: "광석 {goal}개 캐기". */
    public static String name(Mission m) {
        if (!m.name().contains("{goal}")) return m.name();
        return m.name().replace("{goal}", String.format(java.util.Locale.ROOT, "%,d", (long) Math.ceil(m.goal() - 1e-9)));
    }

    private static Mission pick(List<Mission> from, Set<String> groups, Set<String> ids, Random r) {
        List<Mission> open = from.stream().filter(m -> !groups.contains(m.group()) && !ids.contains(m.id())).toList();
        if (open.isEmpty()) return null;
        int total = open.stream().mapToInt(Mission::weight).sum();
        int n = r.nextInt(total);
        for (Mission m : open) {
            n -= m.weight();
            if (n < 0) return m;
        }
        return open.getLast();
    }
}
