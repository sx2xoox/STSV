package dev.stsv.economy.secret.scarecrow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The seven things the scarecrow asks for (scarecrow.yml {@code asks}), in order: each a riddle line and the item ids
 * that answer it. One ask is "the thing you planted most" ({@code most: true}): its answer is read from the field when
 * it is asked ({@link #most}). An item already given this run does not answer again (every offering is a different
 * thing). Pure: no server needed (tests).
 */
final class Riddles {

    /** One ask: its id, the lines it says (the first is the riddle; the rest are said fainter), the answers. */
    record Ask(String id, List<String> say, Set<String> items, boolean most, Set<String> fallback) {}

    /** Vanilla crop blocks (their key path) and what they give: the produce first, then the seed (both answer "most"). */
    static final Map<String, List<String>> VANILLA = Map.ofEntries(
            Map.entry("wheat", List.of("minecraft:wheat", "minecraft:wheat_seeds")),
            Map.entry("carrots", List.of("minecraft:carrot")),
            Map.entry("potatoes", List.of("minecraft:potato")),
            Map.entry("beetroots", List.of("minecraft:beetroot", "minecraft:beetroot_seeds")),
            Map.entry("melon_stem", List.of("minecraft:melon_slice", "minecraft:melon_seeds")),
            Map.entry("attached_melon_stem", List.of("minecraft:melon_slice", "minecraft:melon_seeds")),
            Map.entry("pumpkin_stem", List.of("minecraft:pumpkin", "minecraft:pumpkin_seeds")),
            Map.entry("attached_pumpkin_stem", List.of("minecraft:pumpkin", "minecraft:pumpkin_seeds")),
            Map.entry("sweet_berry_bush", List.of("minecraft:sweet_berries")),
            Map.entry("sugar_cane", List.of("minecraft:sugar_cane")),
            Map.entry("cocoa", List.of("minecraft:cocoa_beans")),
            Map.entry("nether_wart", List.of("minecraft:nether_wart")),
            Map.entry("torchflower_crop", List.of("minecraft:torchflower", "minecraft:torchflower_seeds")),
            Map.entry("pitcher_crop", List.of("minecraft:pitcher_plant", "minecraft:pitcher_pod")));

    private final List<Ask> asks;

    Riddles(List<Ask> asks) {
        this.asks = List.copyOf(asks);
    }

    /** Reads {@code asks} (a list of maps: id, say (text or list), items, most, fallback). */
    static Riddles parse(List<Map<?, ?>> list) {
        List<Ask> out = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> m : list) {
            i++;
            String id = String.valueOf(m.get("id") == null ? "ask" + i : m.get("id"));
            List<String> say = new ArrayList<>();
            Object s = m.get("say");
            if (s instanceof List<?> l) for (Object o : l) say.add(String.valueOf(o));
            else if (s != null) say.add(String.valueOf(s));
            if (say.isEmpty()) say.add("…");
            boolean most = Boolean.TRUE.equals(m.get("most"));
            out.add(new Ask(id, List.copyOf(say), ids(m.get("items")), most, ids(m.get("fallback"))));
        }
        return new Riddles(out);
    }

    private static Set<String> ids(Object o) {
        Set<String> out = new LinkedHashSet<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(norm(String.valueOf(x)));
        else if (o != null) out.add(norm(String.valueOf(o)));
        return out;
    }

    /** "wheat" and "minecraft:wheat" alike; lower case. */
    static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    int size() {
        return asks.size();
    }

    List<Ask> all() {
        return asks;
    }

    /** Ask n (1-based), clamped. */
    Ask ask(int n) {
        return asks.get(Math.max(0, Math.min(asks.size() - 1, n - 1)));
    }

    /** What answers this ask: its items, or for "most" the given field answer (its fallback when that is empty). */
    static Set<String> answers(Ask a, Set<String> mostPlanted) {
        if (!a.most()) return a.items();
        return mostPlanted == null || mostPlanted.isEmpty() ? a.fallback() : mostPlanted;
    }

    /** The verdict on one offered item. */
    enum Verdict { RIGHT, WRONG, AGAIN }

    /** RIGHT when it answers and was not given before; AGAIN when it answers but was already given; else WRONG. */
    static Verdict judge(Ask a, String item, Set<String> mostPlanted, Set<String> given) {
        String k = norm(item);
        if (!answers(a, mostPlanted).contains(k)) return Verdict.WRONG;
        return given.contains(k) ? Verdict.AGAIN : Verdict.RIGHT;
    }

    /**
     * The crop planted most, from counts by produce id (custom crops planted by the player, vanilla crops on their
     * field): every id tied for the most, with the seeds that go with them (seedOf: produce -> seed ids). Empty when
     * nothing was counted.
     */
    static Set<String> most(Map<String, Integer> counts, Map<String, List<String>> seedOf) {
        int best = 0;
        for (int n : counts.values()) best = Math.max(best, n);
        Set<String> out = new LinkedHashSet<>();
        if (best <= 0) return out;
        for (Map.Entry<String, Integer> e : new TreeMap<>(counts).entrySet()) {
            if (e.getValue() != best) continue;
            out.add(norm(e.getKey()));
            for (String s : seedOf.getOrDefault(e.getKey(), List.of())) out.add(norm(s));
        }
        return out;
    }

    /** "a,b,c" for saving; and back. */
    static String join(Set<String> ids) {
        return String.join(",", ids);
    }

    static Set<String> split(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isBlank()) return out;
        for (String w : s.split(",")) if (!w.isBlank()) out.add(norm(w));
        return out;
    }
}
