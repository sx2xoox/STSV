package dev.stsv.economy.secret.letter;

import dev.stsv.economy.secret.Memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pure parts of 어제의 편지 (the 후속 임무 of 내일의 나): which riddles a player's records allow, which one a week gets
 * (the same for a week and a player, not one of the last few), what their sales say (the most, the most this week, the
 * last, the oldest), and how warm the answer is by now. No Bukkit here.
 */
final class Yesterday {

    private Yesterday() {}

    /** What the week's letter asks for. */
    enum Kind {
        /** Stand where they last logged out. */
        QUIT("quit"),
        /** Stand where they first came in. */
        BIRTH("birth"),
        /** Stand on their first land. */
        LAND("land"),
        /** Stand by the one they are near the most. */
        FRIEND("friend"),
        /** Bring the thing they sold the most to their first land. */
        CARRY_MOST_LAND("carry-most-land"),
        /** Bring the thing they sold the most this week to where they first came in. */
        CARRY_WEEK_BIRTH("carry-week-birth"),
        /** Bring the thing they sold last to where they last logged out. */
        CARRY_LAST_QUIT("carry-last-quit"),
        /** Sell the thing they sold the most once more. */
        SELL_MOST("sell-most"),
        /** Sell again the oldest thing the ledger remembers them selling. */
        SELL_OLDEST("sell-oldest"),
        /** From where they last logged out to where they first came in: both, in that order. */
        WALK("walk");

        /** The yml key (sequel.riddles.&lt;key&gt;) and the saved word. */
        final String key;

        Kind(String key) {
            this.key = key;
        }

        static Optional<Kind> of(String key) {
            return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
        }

        boolean sells() {
            return this == SELL_MOST || this == SELL_OLDEST;
        }
    }

    /**
     * What is known of a player: places (quit, first, land), whether the first two lie apart, a friend, and the items of
     * their sales (null = none).
     */
    record Facts(boolean quit, boolean first, boolean land, boolean apart, boolean friend, String most, String weekMost, String last,
                 String oldest) {}

    /** Whether a riddle can be asked of these records. */
    static boolean possible(Kind k, Facts f) {
        return switch (k) {
            case QUIT -> f.quit();
            case BIRTH -> f.first();
            case LAND -> f.land();
            case FRIEND -> f.friend();
            case CARRY_MOST_LAND -> f.most() != null && f.land();
            case CARRY_WEEK_BIRTH -> f.weekMost() != null && f.first();
            case CARRY_LAST_QUIT -> f.last() != null && f.quit();
            case SELL_MOST -> f.most() != null;
            case SELL_OLDEST -> f.oldest() != null && !f.oldest().equals(f.most());
            case WALK -> f.quit() && f.first() && f.apart();
        };
    }

    static List<Kind> possible(Facts f, List<Kind> allowed) {
        List<Kind> out = new ArrayList<>();
        for (Kind k : allowed) if (possible(k, f)) out.add(k);
        return out;
    }

    /** A steady number of a player and a week (the same after a restart). */
    static long hash(long seed, String week) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        for (char c : week.toCharArray()) h = (h ^ c) * 0x100000001B3L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 32);
    }

    /**
     * This week's choice among {@code options}: steady for a seed and a week, and never one of the {@code recent} ones
     * (newest last) while anything else is left — the oldest recent ones are let back in first. Null when there are none.
     */
    static <T> T pick(long seed, String week, List<T> options, List<T> recent) {
        if (options.isEmpty()) return null;
        List<T> rest = new ArrayList<>(options);
        List<T> avoid = new ArrayList<>(recent);
        avoid.retainAll(options);
        while (!avoid.isEmpty() && avoid.size() >= options.size()) avoid.removeFirst();
        rest.removeAll(avoid);
        if (rest.isEmpty()) rest = new ArrayList<>(options);
        return rest.get((int) Math.floorMod(hash(seed, week), (long) rest.size()));
    }

    /** The recent list with one more at its end, cut to {@code keep}. */
    static List<String> remember(List<String> recent, String now, int keep) {
        List<String> out = new ArrayList<>(recent);
        out.remove(now);
        out.add(now);
        while (out.size() > Math.max(0, keep)) out.removeFirst();
        return out;
    }

    static List<String> words(String saved) {
        List<String> out = new ArrayList<>();
        if (saved != null) for (String w : saved.split(",")) if (!w.isBlank()) out.add(w.trim());
        return out;
    }

    /** What the sales say. Items are as the ledger writes them; counts are of the item's own total. */
    record Sold(String most, int mostCount, String weekMost, int weekCount, String last, String oldest) {}

    /** From sales newest first: the most sold overall and since {@code weekAgo}, the last and the oldest one. */
    static Sold sold(List<Memory.Sale> sales, long weekAgo) {
        if (sales == null || sales.isEmpty()) return new Sold(null, 0, null, 0, null, null);
        Map<String, Integer> all = new LinkedHashMap<>(), week = new LinkedHashMap<>();
        for (Memory.Sale s : sales) {
            all.merge(s.item(), s.count(), Integer::sum);
            if (s.at() >= weekAgo) week.merge(s.item(), s.count(), Integer::sum);
        }
        Map.Entry<String, Integer> a = top(all), w = top(week);
        return new Sold(a == null ? null : a.getKey(), a == null ? 0 : a.getValue(), w == null ? null : w.getKey(), w == null ? 0 : w.getValue(),
                sales.getFirst().item(), sales.getLast().item());
    }

    /** The biggest count; of equals, the one met first (the newest sale). */
    private static Map.Entry<String, Integer> top(Map<String, Integer> counts) {
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (best == null || e.getValue() > best.getValue()) best = e;
        return best;
    }

    /**
     * How warm the answer is after {@code done} finished weeks: 0 (still a copy of them), 1, 2 (almost a person).
     * {@code after} = the counts at which it gets warmer ([4, 10]).
     */
    static int warmth(long done, List<Integer> after) {
        int t = 0;
        for (int a : after) if (done >= a) t++;
        return Math.min(2, t);
    }

    /** Which line of a pool a count gets: one after the other, round again when they run out. */
    static int turn(long count, int size) {
        return size <= 0 ? -1 : (int) Math.floorMod(count, (long) size);
    }

    /** Whether two places are far enough apart to be a walk (another world always is). */
    static boolean apart(Memory.Place a, Memory.Place b, int blocks) {
        if (a == null || b == null) return false;
        if (!a.world().equals(b.world())) return true;
        long dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz >= (long) blocks * blocks;
    }
}
