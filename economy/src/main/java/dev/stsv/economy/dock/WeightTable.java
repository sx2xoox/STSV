package dev.stsv.economy.dock;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * contents/economy/weights.yml: what one of each item weighs in a vehicle's bed (적재 무게, README). Pure (unit-tested).
 *
 * <p>An item's weight, first that applies: its own line under {@code items}; for a finished good (what a 하역장 buys,
 * {@link DockGoods#finished}) what one fetches at the default dock price (shop base x docks.price-ratio) divided by
 * {@code finished-value-per-weight}, so every finished good is worth the same per weight unit (500 G); else {@code raw}
 * (crops, seeds, fish, ore, logs, blocks, mob drops, vanilla tools ...). The default ratio, never a dock's own: an item
 * weighs the same whichever dock it goes to. A stack weighs its count times that. Raw materials are so light that a bed's
 * slots, not its payload, limit how much of them it hauls; for finished goods a payload of P carries at most P x 500 G.
 *
 * <p>finished: each finished good's weight, given by {@link #withGoods} (from {@link DockGoods}). A table not yet given them
 * (as parsed) weighs everything without its own entry as raw.
 */
public record WeightTable(double raw, double valuePerWeight, Map<String, Double> items, Map<String, Double> finished) {

    /** raw when weights.yml does not say: a stack of 64 weighs 1. */
    public static final double RAW = 1.0 / 64;

    /** finished-value-per-weight when weights.yml does not say: one weight unit of finished goods is worth 500 G. */
    public static final double VALUE_PER_WEIGHT = 500;

    /** The least a finished good weighs (a very cheap one would otherwise weigh about nothing). */
    public static final double MIN = 0.001;

    /** Keys weights.yml no longer has (finished goods weigh by their price now). */
    private static final List<String> OLD = List.of("default", "block", "categories", "rules");

    public static final WeightTable NONE = new WeightTable(1, VALUE_PER_WEIGHT, Map.of(), Map.of());

    /** This table with these finished goods (what a 하역장 buys) weighed by what one fetches at the default dock price. */
    public WeightTable withGoods(DockGoods goods) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String id : goods.finished()) values.put(id, goods.unit(id));
        return withGoods(values);
    }

    /**
     * This table with these finished goods: id -> what one fetches at the default dock price (gold). Each weighs that /
     * finished-value-per-weight, at least {@link #MIN}; everything else without its own entry weighs raw.
     */
    public WeightTable withGoods(Map<String, Double> values) {
        Map<String, Double> f = new HashMap<>();
        for (Map.Entry<String, Double> e : values.entrySet())
            f.put(norm(e.getKey()), Math.max(MIN, Math.max(0, e.getValue()) / valuePerWeight));
        return new WeightTable(raw, valuePerWeight, items, Map.copyOf(f));
    }

    /** One item's weight. */
    public double weight(String id) {
        String key = norm(id);
        Double own = items.get(key);
        if (own != null) return own;
        Double f = finished.get(key);
        return f != null ? f : raw;
    }

    /** Does this item weigh raw (no entry of its own and not a finished good)? */
    public boolean isRaw(String id) {
        String key = norm(id);
        return !items.containsKey(key) && !finished.containsKey(key);
    }

    /** Does this item have its own line under items (not weighed by its price)? */
    public boolean overridden(String id) {
        return items.containsKey(norm(id));
    }

    static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static WeightTable parse(ConfigurationSection c, List<String> problems) {
        double raw = positive(c.getDouble("raw", RAW), "raw", problems, RAW);
        double vpw = positive(c.getDouble("finished-value-per-weight", VALUE_PER_WEIGHT), "finished-value-per-weight", problems, VALUE_PER_WEIGHT);
        for (String k : OLD)
            if (c.contains(k)) problems.add(k + ": 더 이상 쓰지 않음 (완제품 무게 = 기본 매입가 ÷ finished-value-per-weight, 따로 정하려면 items)");
        Map<String, Double> items = new LinkedHashMap<>();
        ConfigurationSection own = c.getConfigurationSection("items");
        if (own != null) {
            for (String key : own.getKeys(false)) {
                String id = norm(key.replace('.', ':'));
                items.put(id, positive(own.getDouble(key), "items." + key, problems, 1));
            }
        }
        return new WeightTable(raw, vpw, Map.copyOf(items), Map.of());
    }

    private static double positive(double d, String what, List<String> problems, double fallback) {
        if (!(d > 0)) {
            problems.add(what + ": 0보다 커야 함 (" + d + ")");
            return fallback;
        }
        return d;
    }
}
