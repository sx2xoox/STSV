package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem.Rarity;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 치장 뽑기 (contents/&lt;pack&gt;/gacha.yml), the pure part: prices, rarity weights, the pity counter, the 10-pull
 * guarantee, the pool, and one pull. No Bukkit state, so it is tested as is.
 *
 * <ul>
 *   <li>A draw first picks a rarity by its weight (rarities with nothing in the pool drop out), then one
 *       cosmetic of that rarity, all equally likely.</li>
 *   <li>Pity: after {@code pity - 1} draws in a row without a {@code pityRarity} (or better), the next one
 *       is one. The counter is kept per player across pulls.</li>
 *   <li>A multi-pull ({@code multiCount} draws for {@code multiPrice}) has at least one {@code multiFloor} or
 *       better: if the draws before the last have none, the last is drawn among those rarities only.</li>
 *   <li>A cosmetic the player already has (or drew earlier in the same pull) is a duplicate: the caller
 *       gives its voucher instead.</li>
 *   <li>Legendary cosmetics never enter the pool (ranking rewards and events only).</li>
 * </ul>
 */
public record GachaRules(long singlePrice, int multiCount, long multiPrice, Map<Rarity, Integer> weights,
                         int pity, Rarity pityRarity, Rarity multiFloor, int dailyLimit, Rarity broadcastFrom,
                         List<String> pool) {

    /** One cosmetic of the pool. */
    public record Entry(String id, Rarity rarity) {}

    /** One draw: what came out, whether the player had it already, and whether pity forced its rarity. */
    public record Draw(Entry entry, boolean duplicate, boolean pity, boolean guaranteed) {}

    /** A whole pull and the pity counter after it. */
    public record Result(List<Draw> draws, int pityAfter) {}

    // ------------------------------------------------------------------------------------------

    /** gacha.yml; problems collects what was wrong (and replaced by the default). */
    public static GachaRules parse(ConfigurationSection c, List<String> problems) {
        long single = c.getLong("price.single", 600);
        int count = c.getInt("price.multi-count", 10);
        long multi = c.getLong("price.multi", 5400);
        if (single <= 0) {
            problems.add("price.single: 0보다 커야 함");
            single = 600;
        }
        if (count < 2 || count > 50) {
            problems.add("price.multi-count: 2~50");
            count = 10;
        }
        if (multi <= 0) {
            problems.add("price.multi: 0보다 커야 함");
            multi = single * count;
        }
        Map<Rarity, Integer> weights = new EnumMap<>(Rarity.class);
        ConfigurationSection w = c.getConfigurationSection("weights");
        if (w != null) {
            for (String k : w.getKeys(false)) {
                Rarity r = rarity(k);
                if (r == null) problems.add("weights." + k + ": common / rare / epic");
                else if (r == Rarity.LEGENDARY) problems.add("weights.legendary: 전설은 뽑기에 나오지 않음 (무시)");
                else if (w.getInt(k) < 0) problems.add("weights." + k + ": 0 이상");
                else weights.put(r, w.getInt(k));
            }
        }
        if (weights.values().stream().mapToInt(Integer::intValue).sum() <= 0) {
            problems.add("weights: 비어 있음 (기본값 사용)");
            weights = new EnumMap<>(Map.of(Rarity.COMMON, 56, Rarity.RARE, 32, Rarity.EPIC, 12));
        }
        Rarity pityRarity = rarityOr(c.getString("pity.rarity", "epic"), Rarity.EPIC, "pity.rarity", problems);
        Rarity floor = rarityOr(c.getString("multi-guarantee", "rare"), Rarity.RARE, "multi-guarantee", problems);
        Rarity broadcast = rarityOr(c.getString("broadcast-from", "epic"), Rarity.EPIC, "broadcast-from", problems);
        List<String> pool = new ArrayList<>();
        for (String id : c.getStringList("pool")) {
            String key = id.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !pool.contains(key)) pool.add(key);
        }
        return new GachaRules(single, count, multi, Map.copyOf(weights), Math.max(0, c.getInt("pity.pulls", 30)), pityRarity, floor,
                Math.max(0, c.getInt("daily-limit", 0)), broadcast, List.copyOf(pool));
    }

    private static Rarity rarityOr(String s, Rarity def, String where, List<String> problems) {
        Rarity r = s == null ? null : rarity(s);
        if (r == null) {
            problems.add(where + ": common / rare / epic (" + s + ")");
            return def;
        }
        return r;
    }

    static Rarity rarity(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (Rarity r : Rarity.values()) if (r.name().toLowerCase(Locale.ROOT).equals(t) || r.label.equals(t)) return r;
        return null;
    }

    /**
     * The pool as cosmetics: the listed ids that exist, have a voucher (for duplicates) and are not
     * legendary. rarityOf/hasVoucher answer null/false for unknown ids.
     */
    public List<Entry> entries(Function<String, Rarity> rarityOf, java.util.function.Predicate<String> hasVoucher, List<String> problems) {
        List<Entry> out = new ArrayList<>();
        for (String id : pool) {
            Rarity r = rarityOf.apply(id);
            if (r == null) problems.add("pool: 치장이 아님 " + id);
            else if (r == Rarity.LEGENDARY) problems.add("pool: 전설 치장은 뽑기에 넣지 않음 (빠짐) " + id);
            else if (!hasVoucher.test(id)) problems.add("pool: 교환권이 없음 (빠짐) " + id);
            else out.add(new Entry(id, r));
        }
        return out;
    }

    /** The price of a pull of n draws (1 or multiCount). */
    public long price(int n) {
        return n == multiCount ? multiPrice : singlePrice * n;
    }

    // ------------------------------------------------------------------------------------------

    /**
     * One pull of n draws.
     *
     * @param pityBefore draws in a row without a pityRarity, before this pull
     * @param owned      what the player has (ids)
     * @param random     uniform numbers in [0, 1)
     */
    public Result pull(List<Entry> pool, int n, int pityBefore, Set<String> owned, DoubleSupplier random) {
        if (pool.isEmpty()) throw new IllegalStateException("empty pool");
        Map<Rarity, List<Entry>> byRarity = new EnumMap<>(Rarity.class);
        for (Entry e : pool) byRarity.computeIfAbsent(e.rarity(), k -> new ArrayList<>()).add(e);
        Set<String> have = new HashSet<>(owned);
        List<Draw> draws = new ArrayList<>();
        int since = Math.max(0, pityBefore);
        boolean floorMet = false;
        for (int i = 0; i < n; i++) {
            Rarity r;
            boolean byPity = false, byFloor = false;
            if (pity > 0 && since + 1 >= pity && hasAtLeast(byRarity, pityRarity)) {
                r = pick(byRarity, pityRarity, random.getAsDouble());
                byPity = true;
            } else if (n >= multiCount && i == n - 1 && !floorMet && hasAtLeast(byRarity, multiFloor)) {
                r = pick(byRarity, multiFloor, random.getAsDouble());
                byFloor = true;
            } else {
                r = pick(byRarity, Rarity.COMMON, random.getAsDouble());
            }
            List<Entry> of = byRarity.get(r);
            Entry e = of.get(Math.min(of.size() - 1, (int) (random.getAsDouble() * of.size())));
            if (r.compareTo(multiFloor) >= 0) floorMet = true;
            since = r.compareTo(pityRarity) >= 0 ? 0 : since + 1;
            draws.add(new Draw(e, !have.add(e.id()), byPity, byFloor));
        }
        return new Result(List.copyOf(draws), since);
    }

    private static boolean hasAtLeast(Map<Rarity, List<Entry>> byRarity, Rarity min) {
        return byRarity.keySet().stream().anyMatch(r -> r.compareTo(min) >= 0);
    }

    /** A rarity of at least min, by weight, among those the pool has (all equal if their weights are 0). */
    Rarity pick(Map<Rarity, List<Entry>> byRarity, Rarity min, double u) {
        List<Rarity> options = byRarity.keySet().stream().filter(r -> r.compareTo(min) >= 0).sorted().toList();
        List<Integer> w = options.stream().map(r -> weights.getOrDefault(r, 0)).toList();
        if (w.stream().mapToInt(Integer::intValue).sum() <= 0) w = options.stream().map(r -> 1).toList();
        return options.get(weighted(w, u));
    }

    /** Index i with probability w[i] / sum(w), for u uniform in [0, 1). */
    static int weighted(List<Integer> w, double u) {
        long total = 0;
        for (int x : w) total += Math.max(0, x);
        double at = Math.clamp(u, 0, Math.nextDown(1.0)) * total;
        long run = 0;
        for (int i = 0; i < w.size(); i++) {
            run += Math.max(0, w.get(i));
            if (at < run) return i;
        }
        for (int i = w.size() - 1; i >= 0; i--) if (w.get(i) > 0) return i;
        return w.size() - 1;
    }

    /** The chance of one draw's rarity (no pity, no guarantee), for the owner's notes and tests. */
    public double chance(Rarity r, Set<Rarity> inPool) {
        int total = 0;
        for (Rarity x : inPool) total += weights.getOrDefault(x, 0);
        return total <= 0 || !inPool.contains(r) ? 0 : weights.getOrDefault(r, 0) / (double) total;
    }

    /** The rarities a pool has, in order. */
    public static Set<Rarity> rarities(List<Entry> pool) {
        Set<Rarity> out = new LinkedHashSet<>();
        pool.stream().map(Entry::rarity).sorted().forEach(out::add);
        return out;
    }
}
