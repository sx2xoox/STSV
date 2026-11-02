package dev.stsv.economy.job;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * contents/economy/machines.yml: the job machines (furniture) and what they turn into what.
 * Pure data and matching (unit-tested).
 */
public record MachineSpec(String id, String name, String item, String job, List<Recipe> recipes) {

    /** inputs: item id -> count; output id and amount; seconds per batch; job level needed. */
    public record Recipe(int index, Map<String, Integer> inputs, String output, int amount, double seconds, int level, double xp) {}

    /**
     * The first recipe the inputs can make, for an operator of this level. have = item id -> count in
     * the input slots.
     */
    public Recipe match(Map<String, Integer> have, int level) {
        for (Recipe r : recipes) {
            if (r.level() > level) continue;
            boolean ok = true;
            for (Map.Entry<String, Integer> in : r.inputs().entrySet()) {
                if (have.getOrDefault(in.getKey(), 0) < in.getValue()) {
                    ok = false;
                    break;
                }
            }
            if (ok) return r;
        }
        return null;
    }

    public static Map<String, MachineSpec> parse(ConfigurationSection c, List<String> problems) {
        Map<String, MachineSpec> out = new LinkedHashMap<>();
        for (String id : c.getKeys(false)) {
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
                        r.get("seconds") instanceof Number n ? n.doubleValue() : 10, num(r.get("level"), 1),
                        r.get("xp") instanceof Number x ? x.doubleValue() : 0));
            }
            out.put(id, new MachineSpec(id, m.getString("name", id), JobSpec.norm(item), m.getString("job"), recipes));
        }
        return out;
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
