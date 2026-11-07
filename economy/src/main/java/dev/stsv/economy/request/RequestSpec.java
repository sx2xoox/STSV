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
public record RequestSpec(int dailyCount, int perPlayer, double rewardMultiplier, List<Entry> pool, List<Community> community) {

    public record Entry(String item, int min, int max, String job, double xp, int fromDay, double value, int weight) {}

    /** A drawn request: deliver amount of item for reward (+ job XP when you have that job). */
    public record Request(int index, String item, int amount, long reward, String job, double xp) {}

    public record Community(String name, String item, long target, long reward) {}

    /** The shared goal of a week: the list goes round (week 1 = the first entry). Null if there is none. */
    public Community communityFor(int week) {
        return community.isEmpty() ? null : community.get(Math.floorMod(week - 1, community.size()));
    }

    /**
     * The least a contributor hands in to be paid when a community goal completes: 0.5% of the target, and enough that the items are
     * worth the reward at request prices (shop base x reward-multiplier), so an alt with the floor earns no more than a request pays for
     * the same items. base <= 0 (the shop does not buy it): the 0.5% only (BalanceTest R14c refuses such a goal).
     */
    public static long minShare(long target, long reward, double base, double multiplier) {
        long byTarget = (target * 5 + 999) / 1000;                                  // ceil(0.5%), exact
        long byValue = base <= 0 || multiplier <= 0 ? 0 : (long) Math.ceil(reward / (base * multiplier) - 1e-9);
        return Math.max(1, Math.max(byTarget, byValue));
    }

    public long minShare(Community c, double base) {
        return minShare(c.target(), c.reward(), base, rewardMultiplier);
    }

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
        List<Community> community = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("community")) {
            Object item = m.get("item");
            if (item == null) {
                problems.add("community: item 필요 (" + m + ")");
                continue;
            }
            community.add(new Community(m.get("name") == null ? "공동 의뢰" : String.valueOf(m.get("name")), JobSpec.norm(String.valueOf(item)),
                    Math.max(1, num(m.get("target"), 1000)), Math.max(0, num(m.get("reward"), 500))));
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
