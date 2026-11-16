package dev.stsv.economy.land;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * What a thing is worth in money: its own shop price, or what it can be turned into that has one (a diamond block is
 * nine diamonds, raw iron smelts into an iron ingot, a log into... nothing sellable). The 땅 타임머신 charges what it
 * brings back by this, so taking things out and turning back time never makes money. Pure (unit-tested).
 */
public final class ValueGraph {

    /** {@code from} turns into {@code ratio} of {@code to} (9 for a diamond block into diamonds). */
    public record Edge(String to, double ratio) {}

    private static final int DEPTH = 4;

    private final ToDoubleFunction<String> price;
    private final Map<String, List<Edge>> edges = new HashMap<>();
    private final Map<String, Double> memo = new HashMap<>();

    public ValueGraph(ToDoubleFunction<String> price) {
        this.price = price;
    }

    public void edge(String from, String to, double ratio) {
        if (from.equals(to) || ratio <= 0) return;
        edges.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, ratio));
        memo.clear();
    }

    /** The most money one of this can become (0: nothing sellable comes of it). */
    public double value(String id) {
        Double known = memo.get(id);
        if (known != null) return known;
        double v = value(id, DEPTH);
        memo.put(id, v);
        return v;
    }

    private double value(String id, int depth) {
        double best = Math.max(0, price.applyAsDouble(id));
        if (depth <= 0) return best;
        for (Edge e : edges.getOrDefault(id, List.of())) best = Math.max(best, e.ratio() * value(e.to(), depth - 1));
        return best;
    }
}
