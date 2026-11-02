package dev.stsv.economy.request;

import dev.stsv.economy.job.JobSpec;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * contents/economy/requests.yml: the pool daily delivery requests are drawn from, and the weekly
 * shared goal (공동 의뢰). Pure (unit-tested): the same day always draws the same board.
 */
public record RequestSpec(int dailyCount, int perPlayer, double rewardMultiplier, List<Entry> pool, Map<Integer, Community> community) {

    public record Entry(String item, int min, int max, String job, double xp, int fromDay, double value, int weight) {}

    /** A drawn request: deliver amount of item for reward (+ job XP when you have that job). */
    public record Request(int index, String item, int amount, long reward, String job, double xp) {}

    public record Community(int week, String name, String item, long target, long reward, boolean opensEarly) {}

    /**
     * Today's board: dailyCount different items available on this day, amounts at random, rewards =
     * base value x amount x multiplier. price: the admin shop's base price of an item (0 if not sold there).
     */
    public List<Request> draw(int day, long seed, ToDoubleFunction<String> price) {
        Random r = new Random(seed * 31 + day);
        List<Entry> open = new ArrayList<>();
        for (Entry e : pool) if (e.fromDay() <= day) open.add(e);
        List<Request> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int guard = 0;
        while (out.size() < dailyCount && !open.isEmpty() && guard++ < 1000) {
            int total = open.stream().mapToInt(Entry::weight).sum();
            int pick = r.nextInt(Math.max(1, total));
            Entry chosen = open.getFirst();
            for (Entry e : open) {
                pick -= e.weight();
                if (pick < 0) {
                    chosen = e;
                    break;
                }
            }
            open.remove(chosen);
            if (!used.add(chosen.item())) continue;
            int amount = chosen.max() > chosen.min() ? chosen.min() + r.nextInt(chosen.max() - chosen.min() + 1) : chosen.min();
            double unit = chosen.value() > 0 ? chosen.value() : price.applyAsDouble(chosen.item());
            long reward = Math.max(1, Math.round(unit * amount * rewardMultiplier));
            out.add(new Request(out.size(), chosen.item(), amount, reward, chosen.job(), chosen.xp()));
        }
        return out;
    }

    public static RequestSpec parse(ConfigurationSection c, List<String> problems) {
        List<Entry> pool = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("pool")) {
            Object item = m.get("item");
            if (item == null) {
                problems.add("pool: item 필요 (" + m + ")");
                continue;
            }
            pool.add(new Entry(JobSpec.norm(String.valueOf(item)), num(m.get("min"), 1), num(m.get("max"), num(m.get("min"), 1)),
                    m.get("job") == null ? null : String.valueOf(m.get("job")), dbl(m.get("xp"), 0), num(m.get("from-day"), 1),
                    dbl(m.get("value"), 0), Math.max(1, num(m.get("weight"), 10))));
        }
        Map<Integer, Community> community = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("community");
        if (cs != null) {
            for (String k : cs.getKeys(false)) {
                ConfigurationSection w = cs.getConfigurationSection(k);
                if (w == null || w.getString("item") == null) continue;
                int week = Integer.parseInt(k);
                community.put(week, new Community(week, w.getString("name", "공동 의뢰"), JobSpec.norm(w.getString("item")),
                        w.getLong("target", 1000), w.getLong("reward", 500), w.getBoolean("opens-early", true)));
            }
        }
        return new RequestSpec(c.getInt("daily-count", 8), c.getInt("per-player", 3), c.getDouble("reward-multiplier", 1.3), pool, community);
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}
