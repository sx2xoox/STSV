package dev.stsv.economy.secret.designer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure parts of 설계도 의뢰 (the 후속 임무 of 설계자): one order of the 13th machine (so many of a good one of the
 * player's machines makes), which order a week gets, how much of a handful it takes, and how long an order is at a
 * machine's own pace. No Bukkit here.
 */
final class Blueprint {

    private Blueprint() {}

    /** One order: its word, the good and how many, the kind of machine that makes it, and the lines that describe it. */
    record Order(String id, String item, int need, String machine, List<String> lines) {}

    /** A steady number of a player and a week (the same after a restart). */
    static long hash(long seed, String week) {
        long h = seed ^ 0x13_13_13_13L;
        for (char c : week.toCharArray()) h = (h ^ c) * 0x100000001B3L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 29);
    }

    /**
     * This week's order among {@code ids}: steady for a seed and a week, never one of the {@code recent} ones (newest last)
     * while another is left; the oldest recent ones are let back in first. Null when there are none.
     */
    static String pick(long seed, String week, List<String> ids, List<String> recent) {
        if (ids.isEmpty()) return null;
        List<String> avoid = new ArrayList<>(recent);
        avoid.retainAll(ids);
        while (!avoid.isEmpty() && avoid.size() >= ids.size()) avoid.removeFirst();
        List<String> rest = new ArrayList<>(ids);
        rest.removeAll(avoid);
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

    /** How many of a handful the machine takes: what is still missing, never more than is held, never less than none. */
    static int take(int held, long got, int need) {
        return (int) Math.max(0, Math.min(held, need - Math.max(0, got)));
    }

    /** Whether the order is filled. */
    static boolean filled(long got, int need) {
        return got >= need;
    }

    /** Minutes one lane needs for an order at the machine's own pace: batches of {@code amount} taking {@code seconds} each. */
    static double minutes(int need, int amount, double seconds) {
        int batches = (int) Math.ceil(need / (double) Math.max(1, amount));
        return batches * seconds / 60.0;
    }

    /** Stacks to give a count back in ({@code max} to a stack). */
    static List<Integer> stacks(long count, int max) {
        List<Integer> out = new ArrayList<>();
        int m = Math.max(1, max);
        for (long left = Math.max(0, count); left > 0; left -= m) out.add((int) Math.min(m, left));
        return out;
    }

    /**
     * What the machine holds of an unfinished order, as saved: {@code count:stack;count:stack} where a stack is one item's
     * bytes in Base64 (no ':' or ';' in it). The very stacks go back, never new ones. Broken parts are left out.
     */
    static Map<String, Long> held(String saved) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (saved == null) return out;
        for (String part : saved.split(";")) {
            int cut = part.indexOf(':');
            if (cut <= 0 || cut == part.length() - 1) continue;
            try {
                long n = Long.parseLong(part.substring(0, cut).trim());
                if (n > 0) out.merge(part.substring(cut + 1).trim(), n, Long::sum);
            } catch (NumberFormatException e) {
                // left out
            }
        }
        return out;
    }

    /** The held stacks with {@code count} more of one kind, as it is saved. */
    static String hold(String saved, String stack, long count) {
        Map<String, Long> all = held(saved);
        if (count > 0 && stack != null && !stack.isEmpty()) all.merge(stack, count, Long::sum);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Long> e : all.entrySet()) {
            if (!b.isEmpty()) b.append(';');
            b.append(e.getValue()).append(':').append(e.getKey());
        }
        return b.toString();
    }

    /** How many items the saved stacks are. */
    static long heldCount(String saved) {
        long n = 0;
        for (long v : held(saved).values()) n += v;
        return n;
    }

    /** Which line of a pool a count gets: one after the other, round again. -1 for an empty pool. */
    static int turn(long count, int size) {
        return size <= 0 ? -1 : (int) Math.floorMod(count, (long) size);
    }
}
