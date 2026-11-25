package dev.stsv.core.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who sees which board ({@link HudModule#board}): the producers, each with a priority and an audience. For a viewer the
 * producers whose audience takes them are asked from the highest priority down (the same priority: in the order they
 * were first registered); the first that answers a board (not null) is the one shown. So a 도박장 table (priority
 * {@link HudModule#BOARD_SEATED}, audience: whoever sits at one) and a fishing tournament (priority 0, everyone) never
 * fight over the corner: a seated player sees their table, everyone else the tournament. Pure (unit-tested): the
 * viewer is any type.
 */
final class HudBoards<P> {

    private record Maker<P>(String id, int priority, int order, Predicate<P> audience, Function<P, HudLayout.Board> render) {}

    private final Map<String, Maker<P>> makers = new LinkedHashMap<>();
    private int next;

    /** Adds or replaces a producer (a replaced one keeps its place among those of its priority). */
    void put(String id, int priority, Predicate<P> audience, Function<P, HudLayout.Board> render) {
        Maker<P> before = makers.get(id);
        makers.put(id, new Maker<>(id, priority, before != null ? before.order() : next++, audience == null ? p -> true : audience, render));
    }

    boolean remove(String id) {
        return makers.remove(id) != null;
    }

    boolean isEmpty() {
        return makers.isEmpty();
    }

    /** The producers in the order they are asked (highest priority first). */
    List<String> order() {
        List<String> out = new ArrayList<>();
        for (Maker<P> m : sorted()) out.add(m.id());
        return out;
    }

    private List<Maker<P>> sorted() {
        List<Maker<P>> l = new ArrayList<>(makers.values());
        l.sort(Comparator.comparingInt((Maker<P> m) -> -m.priority()).thenComparingInt(Maker::order));
        return l;
    }

    /**
     * The board this viewer sees, or null. A producer that throws is skipped (reported to `error` with its id), as is
     * one whose audience test throws.
     */
    HudLayout.Board pick(P viewer, BiConsumer<String, RuntimeException> error) {
        for (Maker<P> m : sorted()) {
            try {
                if (!m.audience().test(viewer)) continue;
                HudLayout.Board b = m.render().apply(viewer);
                if (b != null) return b;
            } catch (RuntimeException e) {
                if (error != null) error.accept(m.id(), e);
            }
        }
        return null;
    }
}
