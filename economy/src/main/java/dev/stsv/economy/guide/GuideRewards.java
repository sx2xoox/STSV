package dev.stsv.economy.guide;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** The 길라잡이 rewards that need no server: are all topics done, and which common cosmetic the finisher gets. */
public final class GuideRewards {

    private GuideRewards() {}

    /** Every topic id is in done (and there is at least one topic). */
    public static boolean allDone(Collection<String> topics, Set<String> done) {
        return !topics.isEmpty() && done.containsAll(topics);
    }

    /** One of the common cosmetics the player does not own, at random; empty when they own them all. */
    public static Optional<String> pickCosmetic(List<String> commons, Set<String> owned, Random random) {
        List<String> left = commons.stream().filter(c -> !owned.contains(c)).sorted().toList();
        return left.isEmpty() ? Optional.empty() : Optional.of(left.get(random.nextInt(left.size())));
    }

    /** "jobs,farming" -> the set (stored in eco.kv guide.done.&lt;uuid&gt;). */
    public static Set<String> parseDone(String stored) {
        if (stored == null || stored.isBlank()) return new java.util.LinkedHashSet<>();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String s : stored.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public static String formatDone(Collection<String> done) {
        return String.join(",", done);
    }
}
