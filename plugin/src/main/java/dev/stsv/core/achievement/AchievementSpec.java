package dev.stsv.core.achievement;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Achievement definitions: contents/&lt;pack&gt;/achievements/&lt;category&gt;.yml, one file per category.
 *
 * <pre>
 * category: { name: "낚시", icon: minecraft:fishing_rod, order: 40 }
 * achievements:
 *   first_fish:
 *     name: "첫 입질"
 *     description: "물고기 1마리 낚기"
 *     tier: easy                 # easy / normal / hard / very_hard
 *     icon: minecraft:cod        # vanilla item, or an item model of a pack (stsv:crown)
 *     stat: fish_caught          # an all-time stat ... or
 *     value: economy:job_level:fisher   # a value a plugin provides (with an optional argument)
 *     goal: 1
 *     unit: "마리"               # shown after the numbers (stats like playtime format themselves)
 *     hidden: false              # true: name and description show only once done
 *     rewards:
 *       gold: 500
 *       items: ["minecraft:diamond x3", "economy:gem_bench"]
 *       title: economy:angler
 *       cosmetic: stsv:chick
 *       commands: ["give {player} minecraft:cake"]   # run by the console
 * </pre>
 * Pure (unit-tested).
 */
public record AchievementSpec(String id, String name, String description, String category, Tier tier, String icon,
                              Condition condition, String unit, boolean hidden, Rewards rewards, int order) {

    public enum Tier {
        EASY("쉬움", "<#8fd16a>", 10), NORMAL("보통", "<#5ab0ff>", 25), HARD("어려움", "<#c58cff>", 50),
        VERY_HARD("매우 어려움", "<gradient:#ffd36b:#ff8a3d>", 100);

        public final String label, color;
        /** 업적 점수 */
        public final int points;

        Tier(String label, String color, int points) {
            this.label = label;
            this.color = color;
            this.points = points;
        }

        public static Tier parse(String s) {
            String t = s.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Tier x : values()) if (x.name().toLowerCase(Locale.ROOT).equals(t) || x.label.replace(" ", "_").equals(t)) return x;
            return null;
        }
    }

    /**
     * What is measured: a stat (kind "stat", key = stat name) or a provided value (kind "value", key =
     * provider id, arg = what follows it, may be null).
     */
    public record Condition(String kind, String key, String arg, double goal) {
        public boolean stat() {
            return kind.equals("stat");
        }
    }

    public record ItemReward(String id, int amount) {}

    public record Rewards(long gold, List<ItemReward> items, String title, String cosmetic, List<String> commands) {
        public boolean isEmpty() {
            return gold <= 0 && items.isEmpty() && title == null && cosmetic == null && commands.isEmpty();
        }
    }

    public record Category(String id, String name, String icon, int order) {}

    /** Parses one category file; problems are reported with "file: key: what". */
    public static Map<String, AchievementSpec> parse(String namespace, String categoryId, ConfigurationSection file,
                                                     Map<String, Category> categories, List<String> problems, String where) {
        Map<String, AchievementSpec> out = new LinkedHashMap<>();
        ConfigurationSection cat = file.getConfigurationSection("category");
        String catKey = namespace + ":" + categoryId;
        categories.put(catKey, new Category(catKey,
                cat == null ? categoryId : cat.getString("name", categoryId),
                cat == null ? "minecraft:book" : normId(cat.getString("icon", "minecraft:book")),
                cat == null ? 100 : cat.getInt("order", 100)));
        ConfigurationSection list = file.getConfigurationSection("achievements");
        if (list == null) {
            problems.add(where + ": achievements 가 없음");
            return out;
        }
        int order = 0;
        for (String key : list.getKeys(false)) {
            ConfigurationSection a = list.getConfigurationSection(key);
            String at = where + ": " + key;
            if (a == null) {
                problems.add(at + ": 형식이 잘못됨");
                continue;
            }
            Tier tier = Tier.parse(a.getString("tier", "easy"));
            if (tier == null) {
                problems.add(at + ": tier 는 easy / normal / hard / very_hard");
                continue;
            }
            Condition cond;
            double goal = a.getDouble("goal", 0);
            if (goal <= 0) {
                problems.add(at + ": goal (0보다 큼) 필요");
                continue;
            }
            if (a.contains("stat")) {
                cond = new Condition("stat", a.getString("stat").trim(), null, goal);
            } else if (a.contains("value")) {
                String v = a.getString("value").trim();
                // namespace:provider[:arg]
                int first = v.indexOf(':');
                int second = first < 0 ? -1 : v.indexOf(':', first + 1);
                String provider = second < 0 ? v : v.substring(0, second);
                String arg = second < 0 ? null : v.substring(second + 1);
                cond = new Condition("value", provider.contains(":") ? provider : "stsv:" + provider, arg, goal);
            } else {
                problems.add(at + ": stat 또는 value 필요");
                continue;
            }
            Rewards rewards = rewards(a.getConfigurationSection("rewards"), at, problems);
            out.put(namespace + ":" + key, new AchievementSpec(namespace + ":" + key, a.getString("name", key),
                    a.getString("description", ""), catKey, tier, normId(a.getString("icon", "minecraft:paper")),
                    cond, a.getString("unit", ""), a.getBoolean("hidden"), rewards, order++));
        }
        return out;
    }

    private static Rewards rewards(ConfigurationSection r, String at, List<String> problems) {
        if (r == null) return new Rewards(0, List.of(), null, null, List.of());
        List<ItemReward> items = new ArrayList<>();
        for (String s : r.getStringList("items")) {
            ItemReward i = item(s);
            if (i == null) problems.add(at + ": rewards.items '" + s + "' (형식: 아이디 또는 '아이디 x개수')");
            else items.add(i);
        }
        long gold = r.getLong("gold", 0);
        if (gold < 0) problems.add(at + ": rewards.gold 는 0 이상");
        String title = r.getString("title");
        String cosmetic = r.getString("cosmetic");
        return new Rewards(Math.max(0, gold), List.copyOf(items), title == null ? null : withNs(title),
                cosmetic == null ? null : withNs(cosmetic), List.copyOf(r.getStringList("commands")));
    }

    /** "minecraft:diamond x3" / "diamond" / "economy:gem_bench x1" */
    static ItemReward item(String s) {
        String t = s.trim();
        if (t.isEmpty()) return null;
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
        if (amount < 1 || t.contains(" ")) return null;
        return new ItemReward(normId(t), amount);
    }

    static String normId(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static String withNs(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "stsv:" + s;
    }

    /** "■■■■□□□□□□" for progress 0..1 */
    public static String bar(double progress, int cells) {
        int full = (int) Math.floor(Math.max(0, Math.min(1, progress)) * cells + 1e-9);
        return "■".repeat(full) + "□".repeat(cells - full);
    }
}
