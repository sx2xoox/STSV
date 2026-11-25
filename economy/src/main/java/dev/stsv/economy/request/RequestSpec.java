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
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * contents/economy/requests.yml: 주민의 부탁 (README "주민의 부탁"). The pool daily requests are drawn from, in tiers by the player's
 * progress (beginners: raw things and 1세대 goods, veterans: 4강 · 4세대 goods), each asked by a 주민 (a person of the 인물 도감) with
 * a one-line story; the weekly 명인 의뢰 for veterans; the weekly chained request (three steps, each opening the next); 친밀도 with
 * each 주민 and its gifts; and the weekly shared goal (공동 의뢰). Pure (unit-tested): the same day always draws the same board.
 */
public record RequestSpec(int dailyCount, int perPlayer, double rewardMultiplier, List<Entry> pool, List<Community> community,
                          List<Tier> tiers, int stampsDaily, Map<String, List<String>> stories, Bond bond, Master master,
                          List<Chain> chains, int chainStepStamps, int chainDoneStamps) {

    /** tier: the board it is on (1 = beginners). */
    public record Entry(String item, int min, int max, String job, double xp, int fromDay, double value, int weight, int tier) {
        public Entry(String item, int min, int max, String job, double xp, int fromDay, double value, int weight) {
            this(item, min, max, job, xp, fromDay, value, weight, 1);
        }
    }

    /**
     * A drawn request: deliver amount of item for reward gold (+ job XP when you have that job) and stamps, asked by person (a
     * bus kind id, null: nobody) with story (a template: {item}, {count}). index: tier x 100 + place on the board (master: -1).
     */
    public record Request(int index, String item, int amount, long reward, String job, double xp, int tier, String person,
                          String story, int stamps) {
        public Request(int index, String item, int amount, long reward, String job, double xp) {
            this(index, item, amount, reward, job, xp, 1, null, null, 0);
        }
    }

    public record Community(String name, String item, long target, long reward) {}

    /** From this highest job level a player is on this tier: gold = value x amount x multiplier, plus stamps. */
    public record Tier(int from, double multiplier, int stamps) {}

    /** 친밀도: points per request, chain step and 명인 의뢰; gifts at thresholds (items "id xN", stamps). */
    public record Bond(int request, int chainStep, int master, Map<Integer, Gift> gifts) {
        public static final Bond NONE = new Bond(1, 1, 5, Map.of());
    }

    public record Gift(List<String> items, int stamps) {}

    /** 명인 의뢰: once a week for players of at least `tier`; the amount is the entry's max x amount. */
    public record Master(int tier, int amount, double multiplier, int stamps, int regulars) {
        public static final Master NONE = new Master(Integer.MAX_VALUE, 1, 1, 0, 0);
    }

    public record Step(String item, int amount, String text) {}

    public record Chain(String name, String person, List<Step> steps) {}

    // ------------------------------------------------------------------------------------------ rules

    /** The shared goal of a week: the list goes round (week 1 = the first entry). Null if there is none. */
    public Community communityFor(int week) {
        return community.isEmpty() ? null : community.get(Math.floorMod(week - 1, community.size()));
    }

    /** The chained request of a week (the list goes round). Null if there is none. */
    public Chain chainFor(int week) {
        return chains.isEmpty() ? null : chains.get(Math.floorMod(week - 1, chains.size()));
    }

    /** The tier (1-based) of a player whose highest job level is `level`. */
    public int tierFor(int level) {
        int out = 1;
        for (int i = 0; i < tiers.size(); i++) if (level >= tiers.get(i).from()) out = i + 1;
        return out;
    }

    public Tier tier(int t) {
        return tiers.isEmpty() ? new Tier(1, rewardMultiplier, 0) : tiers.get(Math.clamp(t, 1, tiers.size()) - 1);
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

    /** The board of the first tier, nobody asking (the old board). */
    public List<Request> draw(int day, long seed, ToDoubleFunction<String> price) {
        return draw(1, day, seed, price, List.of());
    }

    /**
     * A tier's board today: dailyCount different items of that tier available on this day (if it has too few, the tiers below fill
     * it, the nearest first), amounts at random, rewards = base value x amount x the tier's multiplier. Each is asked by one of
     * people (different people while there are enough) with a story of theirs. price: the admin shop's base price of an item.
     */
    public List<Request> draw(int tier, int day, long seed, ToDoubleFunction<String> price, List<String> people) {
        Random r = new Random(seed * 31 + day + (tier - 1) * 7919L);
        List<Request> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> asked = new HashSet<>();
        for (int t = tier; t >= 1 && out.size() < dailyCount; t--) {
            int at = t;
            List<Entry> open = new ArrayList<>();
            for (Entry e : pool) if (e.tier() == at && e.fromDay() <= day && !used.contains(e.item())) open.add(e);
            int guard = 0;
            while (out.size() < dailyCount && !open.isEmpty() && guard++ < 1000) {
                Entry chosen = pick(open, r);
                open.remove(chosen);
                if (!used.add(chosen.item())) continue;
                int amount = chosen.max() > chosen.min() ? chosen.min() + r.nextInt(chosen.max() - chosen.min() + 1) : chosen.min();
                Tier tt = tier(tier);
                long reward = Math.max(1, Math.round(unit(chosen, price) * amount * tt.multiplier()));
                String person = person(people, asked, r);
                out.add(new Request(tier * 100 + out.size(), chosen.item(), amount, reward, chosen.job(), chosen.xp(), tier, person,
                        story(person, r), tt.stamps()));
            }
        }
        return out;
    }

    /**
     * This week's 명인 의뢰: one entry of the master tier's pool available on this day, amount = its max x master.amount, gold =
     * value x amount x master.multiplier. Null if the pool has none.
     */
    public Request master(int week, int day, long seed, ToDoubleFunction<String> price, List<String> people) {
        Random r = new Random(seed * 131 + week * 977L);
        List<Entry> open = new ArrayList<>();
        int t = Math.min(master.tier(), Math.max(1, tiers.size()));
        for (Entry e : pool) if (e.tier() == t && e.fromDay() <= day) open.add(e);
        if (open.isEmpty()) return null;
        Entry e = pick(open, r);
        int amount = Math.max(1, e.max() * master.amount());
        String person = person(people, new HashSet<>(), r);
        return new Request(-1, e.item(), amount, Math.max(1, Math.round(unit(e, price) * amount * master.multiplier())), e.job(), e.xp() * master.amount(),
                t, person, story(person, r), master.stamps());
    }

    /** A step's gold: value x amount x reward-multiplier. */
    public long stepReward(Step s, ToDoubleFunction<String> price) {
        return Math.max(1, Math.round(price.applyAsDouble(s.item()) * s.amount() * rewardMultiplier));
    }

    /** The 친밀도 thresholds whose gift opens going from `before` to `after` points, in order. */
    public List<Integer> giftsCrossed(int before, int after) {
        List<Integer> out = new ArrayList<>();
        for (int n : bond.gifts().keySet()) if (n > before && n <= after) out.add(n);
        return out;
    }

    /** The next threshold with a gift above `points`, or -1. */
    public int nextGift(int points) {
        for (int n : bond.gifts().keySet()) if (n > points) return n;
        return -1;
    }

    private static double unit(Entry e, ToDoubleFunction<String> price) {
        return e.value() > 0 ? e.value() : price.applyAsDouble(e.item());
    }

    private static Entry pick(List<Entry> open, Random r) {
        int total = open.stream().mapToInt(Entry::weight).sum();
        int n = r.nextInt(Math.max(1, total));
        for (Entry e : open) {
            n -= e.weight();
            if (n < 0) return e;
        }
        return open.getFirst();
    }

    private static String person(List<String> people, Set<String> asked, Random r) {
        if (people.isEmpty()) return null;
        List<String> fresh = people.stream().filter(p -> !asked.contains(p)).toList();
        List<String> from = fresh.isEmpty() ? people : fresh;
        String p = from.get(r.nextInt(from.size()));
        asked.add(p);
        return p;
    }

    /** A story template of this person (their own lines, else the default ones); null if there are none. */
    String story(String person, Random r) {
        List<String> lines = person == null ? null : stories.get(person);
        if (lines == null || lines.isEmpty()) lines = stories.get("default");
        if (lines == null || lines.isEmpty()) return null;
        return lines.get(r.nextInt(lines.size()));
    }

    /** A story with its item and count filled in. */
    public static String tell(String template, String item, int count) {
        return template == null ? "" : template.replace("{item}", item).replace("{count}", String.format("%,d", count));
    }

    // ------------------------------------------------------------------------------------------ parse

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
                    dbl(m.get("value"), 0), Math.max(1, num(m.get("weight"), 10)), Math.max(1, num(m.get("tier"), 1))));
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
        double mult = c.getDouble("reward-multiplier", 1.3);
        List<Tier> tiers = new ArrayList<>();
        int last = 0;
        for (Map<?, ?> m : c.getMapList("tiers")) {
            int from = num(m.get("from"), -1);
            if (from <= last && !tiers.isEmpty() || from < 1) problems.add("tiers: from 은 1 이상, 커지는 순서 (" + m + ")");
            last = from;
            tiers.add(new Tier(from, dbl(m.get("multiplier"), mult), Math.max(0, num(m.get("stamps"), 0))));
        }
        for (Entry e : pool) if (!tiers.isEmpty() && e.tier() > tiers.size()) problems.add("pool " + e.item() + ": 없는 tier " + e.tier());
        Map<String, List<String>> stories = new LinkedHashMap<>();
        ConfigurationSection st = c.getConfigurationSection("stories");
        if (st != null) for (String k : st.getKeys(false)) {
            List<String> lines = st.getStringList(k);
            for (String l : lines) if (!l.contains("{item}")) problems.add("stories." + k + ": {item} 이 없는 줄 '" + l + "'");
            stories.put(k, List.copyOf(lines));
        }
        Bond bond = Bond.NONE;
        ConfigurationSection b = c.getConfigurationSection("bond");
        if (b != null) {
            Map<Integer, Gift> gifts = new TreeMap<>();
            ConfigurationSection g = b.getConfigurationSection("gifts");
            if (g != null) for (String k : g.getKeys(false)) {
                int n = num(parse(k), -1);
                ConfigurationSection x = g.getConfigurationSection(k);
                if (n <= 0 || x == null) {
                    problems.add("bond.gifts." + k + ": 1 이상의 수");
                    continue;
                }
                for (String i : x.getStringList("items")) if (dev.stsv.economy.mission.MissionSpec.item(i) == null) problems.add("bond.gifts." + k + ": items '" + i + "'");
                gifts.put(n, new Gift(List.copyOf(x.getStringList("items")), Math.max(0, x.getInt("stamps"))));
            }
            bond = new Bond(b.getInt("request", 1), b.getInt("chain-step", 1), b.getInt("master", 5), gifts);
        }
        Master master = Master.NONE;
        ConfigurationSection ms = c.getConfigurationSection("master");
        if (ms != null) master = new Master(ms.getInt("tier", 4), Math.max(1, ms.getInt("amount", 3)), ms.getDouble("multiplier", 1.0),
                Math.max(0, ms.getInt("stamps", 0)), Math.max(0, ms.getInt("regulars", 5)));
        List<Chain> chains = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("chains")) {
            List<Step> steps = new ArrayList<>();
            if (m.get("steps") instanceof List<?> l) for (Object o : l) {
                if (!(o instanceof Map<?, ?> s) || s.get("item") == null) continue;
                steps.add(new Step(JobSpec.norm(String.valueOf(s.get("item"))), Math.max(1, num(s.get("amount"), 1)),
                        s.get("text") == null ? "" : String.valueOf(s.get("text"))));
            }
            String name = m.get("name") == null ? "이어지는 의뢰" : String.valueOf(m.get("name"));
            if (steps.isEmpty()) problems.add("chains " + name + ": steps 가 없음");
            else chains.add(new Chain(name, m.get("person") == null ? null : String.valueOf(m.get("person")), List.copyOf(steps)));
        }
        return new RequestSpec(c.getInt("daily-count", 8), c.getInt("per-player", 3), mult, pool, community, List.copyOf(tiers),
                Math.max(0, c.getInt("stamps-daily", 0)), stories, bond, master, List.copyOf(chains),
                Math.max(0, c.getInt("chain-stamps.step", 0)), Math.max(0, c.getInt("chain-stamps.done", 0)));
    }

    private static Integer parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }
}
