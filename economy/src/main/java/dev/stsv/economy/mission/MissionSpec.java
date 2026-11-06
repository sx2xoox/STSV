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

    public record Reward(long gold, double xp, List<ItemReward> items) {
        public static final Reward NONE = new Reward(0, 0, List.of());

        public boolean isEmpty() {
            return gold <= 0 && xp <= 0 && items.isEmpty();
        }
    }

    public record Mission(String id, String name, String stat, double goal, String job, String group, String unit,
                          String icon, int weight, Reward reward) {}

    public record Kind(int count, int rerolls, Reward bonus, Map<String, Mission> missions) {
        public Kind {
            missions = Map.copyOf(missions);
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
            out.put(key, new Mission(key, m.getString("name", key), stat.trim(), goal, job == null || job.isBlank() ? null : job.trim(),
                    m.getString("group", stat).trim(), m.getString("unit", ""), normId(m.getString("icon", "minecraft:paper")),
                    Math.max(1, weight), reward(m, at, problems)));
        }
        int count = s.getInt("count", 3);
        if (count < 1) problems.add(where + ".count: 1 이상");
        int rerolls = s.getInt("rerolls", 0);
        if (rerolls < 0) problems.add(where + ".rerolls: 0 이상");
        ConfigurationSection bonus = s.getConfigurationSection("bonus");
        return new Kind(Math.max(1, count), Math.max(0, rerolls), bonus == null ? Reward.NONE : reward(bonus, where + ".bonus", problems), out);
    }

    private static Reward reward(ConfigurationSection s, String at, List<String> problems) {
        long gold = s.getLong("gold", 0);
        double xp = s.getDouble("xp", 0);
        if (gold < 0) problems.add(at + ": gold 는 0 이상");
        if (xp < 0) problems.add(at + ": xp 는 0 이상");
        List<ItemReward> items = new ArrayList<>();
        for (String i : s.getStringList("items")) {
            ItemReward r = item(i);
            if (r == null) problems.add(at + ": items '" + i + "' (형식: 아이디 또는 '아이디 x개수')");
            else items.add(r);
        }
        return new Reward(Math.max(0, gold), Math.max(0, xp), List.copyOf(items));
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
        List<Mission> out = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        Mission first = pick(k.ofJob(job), groups, Set.of(), r);
        if (first != null) {
            out.add(first);
            groups.add(first.group());
        }
        while (out.size() < k.count()) {
            Mission m = pick(k.general(), groups, Set.of(), r);
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
        Mission old = set.get(index);
        Set<String> groups = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < set.size(); i++) {
            ids.add(set.get(i).id());
            if (i != index) groups.add(set.get(i).group());
        }
        Mission m = old.job() == null ? null : pick(k.ofJob(old.job()), groups, ids, r);
        return m != null ? m : pick(k.general(), groups, ids, r);
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
