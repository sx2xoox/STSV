package dev.stsv.economy.job;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * contents/economy/machines.yml: the job machines (furniture), what they turn into what at which upgrade
 * tier, and the upgrade table. Pure data and matching (unit-tested).
 */
public record MachineSpec(String id, String name, String item, String job, String model, List<String> parts, Effect effect,
                          List<Recipe> recipes) {

    /**
     * inputs: item id (or "#tag") -> count; output id and amount; seconds at base speed; tier = the upgrade
     * it opens at (0 = 기본); aging = a 숙성 recipe (연금술사 Lv20 halves it).
     */
    public record Recipe(int index, Map<String, Integer> inputs, String output, int amount, double seconds, int tier, boolean aging) {}

    /** A named set of items that counts as one recipe input ("#economy:white_fish"): any of them will do. */
    public record Group(String id, String name, List<String> items) {}

    /** machines.yml groups: id -> name and items; the key is "economy:<id>" as recipes write it after the '#'. */
    public static Map<String, Group> groups(ConfigurationSection c, List<String> problems) {
        Map<String, Group> out = new LinkedHashMap<>();
        ConfigurationSection gs = c.getConfigurationSection("groups");
        if (gs == null) return out;
        for (String id : gs.getKeys(false)) {
            List<String> items = gs.getStringList(id + ".items").stream().map(JobSpec::norm).toList();
            if (items.isEmpty()) problems.add("groups." + id + ": items 필요");
            out.put("economy:" + id, new Group("economy:" + id, gs.getString(id + ".name", id), items));
        }
        return out;
    }

    /** While working: a particle and a sound at a point in model coordinates (floor = y 0). */
    public record Effect(String particle, double[] at, String sound, float volume, float pitch) {}

    /** The upgrade table: index 0 = 1강 for cost / level, index = tier for lanes / speed. */
    public record Settings(List<Long> cost, List<Integer> level, List<Integer> lanes, List<Double> speed, double xpPerGold) {

        public int maxTier() {
            return Math.min(cost.size(), level.size());
        }

        public int lanes(int tier) {
            return lanes.isEmpty() ? 1 : lanes.get(Math.clamp(tier, 0, lanes.size() - 1));
        }

        public double speed(int tier) {
            return speed.isEmpty() ? 0 : speed.get(Math.clamp(tier, 0, speed.size() - 1));
        }

        /** Gold for going up to this tier (1..max). */
        public long cost(int tier) {
            return cost.get(tier - 1);
        }

        /** Job level the installer needs for this tier (1..max). */
        public int level(int tier) {
            return level.get(tier - 1);
        }

        public static Settings parse(ConfigurationSection c) {
            ConfigurationSection u = c.getConfigurationSection("upgrade");
            List<Long> cost = u == null ? List.of() : u.getLongList("cost");
            List<Integer> level = u == null ? List.of() : u.getIntegerList("level");
            List<Integer> lanes = u == null ? List.of(1) : u.getIntegerList("lanes");
            List<Double> speed = u == null ? List.of(0.0) : u.getDoubleList("speed");
            return new Settings(cost, level, lanes, speed, c.getDouble("xp-per-gold", 0.2));
        }
    }

    /** Does an item id count for an input key: the same id, or a "#tag" the item is in. */
    public static boolean fits(String input, String item, BiPredicate<String, String> tagged) {
        if (input.equals(item)) return true;
        return input.startsWith("#") && tagged.test(input.substring(1), item);
    }

    /** How many of the items in a lane count for an input key. */
    public static int count(Map<String, Integer> have, String input, BiPredicate<String, String> tagged) {
        int n = 0;
        for (Map.Entry<String, Integer> e : have.entrySet()) if (fits(input, e.getKey(), tagged)) n += e.getValue();
        return n;
    }

    /** True if the lane holds enough of every input. */
    public static boolean enough(Recipe r, Map<String, Integer> have, BiPredicate<String, String> tagged) {
        for (Map.Entry<String, Integer> in : r.inputs().entrySet()) {
            if (count(have, in.getKey(), tagged) < in.getValue()) return false;
        }
        return true;
    }

    /** True if every kind of item in the lane is one of the recipe's inputs. */
    public static boolean only(Recipe r, Map<String, Integer> have, BiPredicate<String, String> tagged) {
        for (String item : have.keySet()) {
            if (r.inputs().keySet().stream().noneMatch(in -> fits(in, item, tagged))) return false;
        }
        return true;
    }

    /**
     * The recipe a lane makes by itself (no recipe chosen): among the recipes open at this tier whose
     * inputs are all there and which use every kind of item in the lane, the highest tier first (4 rough
     * gems make 5 gems once the bulk recipe is open), then the file order.
     */
    public Recipe auto(Map<String, Integer> have, int tier, BiPredicate<String, String> tagged) {
        if (have.isEmpty()) return null;
        return recipes.stream()
                .filter(r -> r.tier() <= tier && enough(r, have, tagged) && only(r, have, tagged))
                .min(Comparator.comparingInt((Recipe r) -> -r.tier()).thenComparingInt(Recipe::index))
                .orElse(null);
    }

    public static Map<String, MachineSpec> parse(ConfigurationSection c, List<String> problems) {
        Map<String, MachineSpec> out = new LinkedHashMap<>();
        for (String id : c.getKeys(false)) {
            if (id.equals("upgrade") || id.equals("xp-per-gold") || id.equals("groups")) continue;
            ConfigurationSection m = c.getConfigurationSection(id);
            if (m == null) continue;
            String item = m.getString("item");
            if (item == null) {
                problems.add(id + ": item 필요");
                continue;
            }
            List<Recipe> recipes = new ArrayList<>();
            int i = 0;
            for (Map<?, ?> r : m.getMapList("recipes")) {
                Object in = r.get("in"), outId = r.get("out");
                if (!(in instanceof Map<?, ?> inputs) || outId == null) {
                    problems.add(id + " 레시피 " + (i + 1) + ": in(맵), out 필요");
                    continue;
                }
                Map<String, Integer> ins = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : inputs.entrySet()) {
                    ins.put(JobSpec.norm(String.valueOf(e.getKey())), e.getValue() instanceof Number n ? Math.max(1, n.intValue()) : 1);
                }
                if (ins.size() > 3) problems.add(id + " 레시피 " + (i + 1) + ": 재료는 3종류까지");
                recipes.add(new Recipe(i++, ins, JobSpec.norm(String.valueOf(outId)), num(r.get("amount"), 1),
                        r.get("seconds") instanceof Number n ? n.doubleValue() : 10, Math.clamp(num(r.get("tier"), 0), 0, 3),
                        Boolean.TRUE.equals(r.get("aging"))));
            }
            Effect effect = null;
            ConfigurationSection ef = m.getConfigurationSection("effect");
            if (ef != null) {
                List<Double> at = ef.getDoubleList("at");
                effect = new Effect(ef.getString("particle"), at.size() == 3 ? new double[]{at.get(0), at.get(1), at.get(2)} : new double[]{8, 16, 8},
                        ef.getString("sound"), (float) ef.getDouble("volume", 0.4), (float) ef.getDouble("pitch", 1));
            }
            out.put(id, new MachineSpec(id, m.getString("name", id), JobSpec.norm(item), m.getString("job"), m.getString("model", id),
                    m.getStringList("parts"), effect, recipes));
        }
        return out;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
