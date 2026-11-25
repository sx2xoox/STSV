package dev.stsv.economy.dock;

import dev.stsv.economy.event.ActiveEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * A running event's effect on what the 하역장 pay (README "사건"): at one dock (or every dock), for one category of goods (the
 * machine that makes them, events.yml dock.categories) or all of them, the price x factor. 품귀 +12%, 시장 폭락 -10%,
 * 고가 매입상 +10% on everything at one dock. Several multiply. Only a load <b>sealed</b> while it runs gets it: the factor
 * goes into the trip's promised value at seal time ({@link CargoTrip#boost}), like the dock's own price. Pure (unit-tested).
 *
 * @param type     the event's kind (shortage, ...), for the "품귀!" marks
 * @param dock     the dock's name, null = every dock
 * @param category the machine id of the goods, null = every finished good
 */
public record DockBoost(String type, String dock, String category, double factor) {

    /** events.yml category: all = every finished good (고가 매입상). */
    public static final String ALL = "all";

    public boolean covers(String atDock, String itemCategory) {
        return (dock == null || dock.equalsIgnoreCase(atDock)) && (category == null || category.equals(itemCategory));
    }

    /** What an event does at the docks, or null (it has no dock-factor, or no dock was picked). */
    public static DockBoost of(ActiveEvent e) {
        if (e.param("dock-factor") == null || e.param("dock") == null) return null;
        double f = e.number("dock-factor", 1);
        if (f <= 0 || Math.abs(f - 1) < 1e-9) return null;
        String d = e.param("dock"), c = e.param("category");
        return new DockBoost(e.type(), d.equals(ALL) ? null : d, c == null || c.equals(ALL) ? null : c, f);
    }

    /** Every running event's dock effect. */
    public static List<DockBoost> of(Collection<ActiveEvent> events, long now) {
        List<DockBoost> out = new ArrayList<>();
        for (ActiveEvent e : events) {
            if (!e.runningAt(now)) continue;
            DockBoost b = of(e);
            if (b != null) out.add(b);
        }
        return out;
    }

    /** The factor on one good at one dock and the kinds of event behind it. */
    public record Hit(double factor, List<String> types) {
        public static final Hit NONE = new Hit(1, List.of());

        public boolean none() {
            return Math.abs(factor - 1) < 1e-9;
        }

        /** "+12", "-10": the change in whole percent (rounded). */
        public String pct() {
            long p = Math.round((factor - 1) * 100);
            return (p >= 0 ? "+" : "") + p;
        }
    }

    public static Hit at(List<DockBoost> boosts, String atDock, String itemCategory) {
        double f = 1;
        List<String> types = new ArrayList<>();
        for (DockBoost b : boosts) {
            if (!b.covers(atDock, itemCategory)) continue;
            f *= b.factor;
            if (!types.contains(b.type)) types.add(b.type);
        }
        return types.isEmpty() ? Hit.NONE : new Hit(f, List.copyOf(types));
    }

    // ------------------------------------------------------------------------------------------
    // On a sealed trip: "id=1.12:shortage+premium_buyer;..." (only goods whose factor is not 1)
    // ------------------------------------------------------------------------------------------

    public static String encode(Map<String, Hit> perItem) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Hit> e : new TreeMap<>(perItem).entrySet()) {
            if (e.getValue().none()) continue;
            if (!b.isEmpty()) b.append(';');
            b.append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.4f", e.getValue().factor()))
                    .append(':').append(String.join("+", e.getValue().types()));
        }
        return b.toString();
    }

    public static Map<String, Hit> decode(String s) {
        Map<String, Hit> out = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('='), colon = part.indexOf(':', eq);
            if (eq <= 0) continue;
            try {
                double f = Double.parseDouble(colon < 0 ? part.substring(eq + 1) : part.substring(eq + 1, colon));
                List<String> types = colon < 0 ? List.of() : List.of(part.substring(colon + 1).split("\\+"));
                out.put(part.substring(0, eq), new Hit(f, types));
            } catch (NumberFormatException ignored) {
                // a broken entry: that good at its plain price
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Reacting to the server: what was sold where lately
    // ------------------------------------------------------------------------------------------

    /**
     * What the docks bought lately, by dock and category, in base-price gold (the sales' item lines "id xN, ..."; a good
     * no machine makes any more counts nowhere).
     */
    public static Map<String, Map<String, Double>> sold(Collection<DockStore.Sale> sales, DockGoods goods) {
        Map<String, Map<String, Double>> out = new HashMap<>();
        for (DockStore.Sale s : sales) {
            if (s.items() == null) continue;
            for (String part : s.items().split(", ")) {
                int x = part.lastIndexOf(" x");
                if (x <= 0) continue;
                String id = part.substring(0, x);
                String cat = goods.category(id);
                if (cat == null) continue;
                int n;
                try {
                    n = Integer.parseInt(part.substring(x + 2).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                out.computeIfAbsent(s.dock().toLowerCase(Locale.ROOT), k -> new HashMap<>()).merge(cat, n * goods.unit(id, 1), Double::sum);
            }
        }
        return out;
    }

    /**
     * A dock and a category for an event: of every pair (dock x category), the `from` sold least lately (least = true: 품귀,
     * to bring goods where nobody takes them) or most (시장 폭락, the one everybody hauls), one of them at random. null if
     * there is no dock or category.
     */
    public static String[] pick(Map<String, Map<String, Double>> sold, List<String> docks, List<String> categories, boolean least, int from,
                                Random r) {
        if (docks.isEmpty() || categories.isEmpty()) return null;
        record Pair(String dock, String cat, double value) {}
        List<Pair> all = new ArrayList<>();
        for (String d : docks) {
            Map<String, Double> m = sold.getOrDefault(d.toLowerCase(Locale.ROOT), Map.of());
            for (String c : categories) all.add(new Pair(d, c, m.getOrDefault(c, 0.0)));
        }
        java.util.Collections.shuffle(all, r);   // ties (nothing sold anywhere) fall at random
        all.sort(least ? Comparator.comparingDouble(Pair::value) : Comparator.comparingDouble(Pair::value).reversed());
        Pair p = all.get(r.nextInt(Math.min(Math.max(1, from), all.size())));
        return new String[]{p.dock, p.cat};
    }
}
