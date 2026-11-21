package dev.stsv.economy.secret.doll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Who must hold the doll next (pure; tested by DollRiddlesTest). Each riddle names "the most …" among the candidates: the
 * players online at the moment who have not held the doll in this run. Ties all count. A riddle that cannot be answered
 * (nobody has land, nobody fished, …) is never chosen; when no riddle can be answered the doll asks for any stranger.
 */
final class Riddles {

    private Riddles() {}

    /** The riddles; HOME is always the seventh ("back to the first owner"), STRANGER is the fallback. */
    enum Kind {
        LAND, LATE, EARLY, FRIEND, FISH, LONG, DEEP, HIGH, FAR, RICH, POOR, OLD, NEW, STRANGER, HOME;

        /** Its key under riddles: in doll.yml. */
        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether its answer moves with the moment (asked "just now": the snapshot when it was told matters). */
        boolean moving() {
            return this == DEEP || this == HIGH || this == FAR || this == LATE;
        }

        static Kind of(String s) {
            try {
                return s == null || s.isEmpty() || s.equals("-") ? null : valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The riddles a run picks from (not the fallback, not the last one). */
    static final List<Kind> POOL = List.of(Kind.LAND, Kind.LATE, Kind.EARLY, Kind.FRIEND, Kind.FISH, Kind.LONG, Kind.DEEP, Kind.HIGH,
            Kind.FAR, Kind.RICH, Kind.POOR, Kind.OLD, Kind.NEW);

    /**
     * What is known of one candidate.
     *
     * @param land        chunks of land they own (0 = none)
     * @param session     when this session began (ms)
     * @param firstPlayed when they first came to the server (ms)
     * @param fish        their biggest catch ever (cm, 0 = none)
     * @param y           the block height they stand at
     * @param distance    blocks from the holder; {@link Double#POSITIVE_INFINITY} in another world; negative = unknown
     * @param money       their balance
     * @param friendRank  place in the first owner's "most time near" list (0 = first), -1 when not in it
     */
    record Fact(UUID id, String name, int land, long session, long firstPlayed, double fish, int y, double distance, long money, int friendRank) {}

    /** The candidates that answer this riddle (all who tie at the top), empty when it cannot be answered. */
    static Set<UUID> answers(Kind k, List<Fact> facts) {
        if (k == null || facts.isEmpty()) return Set.of();
        return switch (k) {
            case LAND -> top(facts, f -> f.land() > 0, f -> f.land());
            case LATE -> top(facts, f -> true, f -> f.session());
            case EARLY -> top(facts, f -> true, f -> -f.session());
            case FRIEND -> top(facts, f -> f.friendRank() >= 0, f -> -f.friendRank());
            case FISH -> top(facts, f -> f.fish() > 0, f -> f.fish());
            case LONG -> top(facts, f -> true, f -> f.name() == null ? 0 : f.name().codePointCount(0, f.name().length()));
            case DEEP -> top(facts, f -> true, f -> -f.y());
            case HIGH -> top(facts, f -> true, f -> f.y());
            case FAR -> top(facts, f -> f.distance() >= 0, f -> Double.isInfinite(f.distance()) ? Double.MAX_VALUE : Math.rint(f.distance()));
            case RICH -> top(facts, f -> true, f -> f.money());
            case POOR -> top(facts, f -> true, f -> -f.money());
            case OLD -> top(facts, f -> f.firstPlayed() > 0, f -> -f.firstPlayed());
            case NEW -> top(facts, f -> f.firstPlayed() > 0, f -> f.firstPlayed());
            case STRANGER -> {
                Set<UUID> all = new LinkedHashSet<>();
                for (Fact f : facts) all.add(f.id());
                yield all;
            }
            case HOME -> Set.of();
        };
    }

    private static Set<UUID> top(List<Fact> facts, java.util.function.Predicate<Fact> has, ToDoubleFunction<Fact> score) {
        double best = Double.NEGATIVE_INFINITY;
        Set<UUID> out = new LinkedHashSet<>();
        for (Fact f : facts) {
            if (!has.test(f)) continue;
            double s = score.applyAsDouble(f);
            if (s > best) {
                best = s;
                out.clear();
                out.add(f.id());
            } else if (s == best) {
                out.add(f.id());
            }
        }
        return out;
    }

    /**
     * The riddle for this telling: the first of the pool (in an order fixed by the seed) not used in this run that someone
     * online can answer; STRANGER when none can.
     */
    static Kind choose(long seed, Set<Kind> used, List<Fact> facts) {
        List<Kind> order = new ArrayList<>(POOL);
        Collections.shuffle(order, new Random(seed));
        for (Kind k : order) {
            if (used.contains(k)) continue;
            if (!answers(k, facts).isEmpty()) return k;
        }
        return Kind.STRANGER;
    }
}
