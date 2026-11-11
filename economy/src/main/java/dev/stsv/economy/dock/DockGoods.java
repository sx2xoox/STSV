package dev.stsv.economy.dock;

import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.shop.ShopCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a 하역장 buys and for how much (README "하역장"). Pure (unit-tested against the real content).
 *
 * <p><b>Finished goods</b> = what a job machine (or the press) makes (machines.yml recipe outputs) ∩ what the admin shop
 * buys (shop.yml sell). Raw materials (ore, crops, fish, logs, mob drops) are not made by a machine, so no dock buys them;
 * nor anything the shop does not buy. Generic: a new recipe whose output the shop buys is a finished good at once.
 *
 * <p><b>Price</b> = the item's shop base price (기준가) x the dock's ratio: its own (/관리자 하역장 가격, {@link Dock#ratio}),
 * else the global docks.price-ratio ({@link #ratio()}). Fixed otherwise: no 수요 고시, no events, no seller's own curve, no
 * income tax. A dock sale never touches the shop's record of what anyone sold, so it moves neither the seller's own shop
 * price nor the market price. A sealed trip ({@link CargoTrip}) keeps the total at the ratio it was sealed with.
 */
public final class DockGoods {

    private final Set<String> finished;
    private final Map<String, Double> base;
    private final double ratio;

    public DockGoods(Set<String> finished, Map<String, Double> base, double ratio) {
        this.finished = Set.copyOf(finished);
        this.base = Map.copyOf(base);
        this.ratio = ratio;
    }

    /** Recipe outputs of every machine that the shop buys, in the shop's order. */
    public static Set<String> finished(Map<String, MachineSpec> machines, ShopCatalog shop) {
        Set<String> outputs = new LinkedHashSet<>();
        for (MachineSpec m : machines.values()) for (MachineSpec.Recipe r : m.recipes()) outputs.add(r.output());
        Set<String> out = new LinkedHashSet<>();
        for (String id : shop.sell().keySet()) if (outputs.contains(id)) out.add(id);
        return out;
    }

    public static DockGoods of(Map<String, MachineSpec> machines, ShopCatalog shop, double ratio) {
        Map<String, Double> base = new LinkedHashMap<>();
        for (ShopCatalog.SellEntry e : shop.sell().values()) base.put(e.id(), e.base());
        return new DockGoods(finished(machines, shop), base, ratio);
    }

    public boolean buys(String id) {
        return finished.contains(id);
    }

    public Set<String> finished() {
        return finished;
    }

    /** The global ratio (docks.price-ratio): what a dock without its own price pays. */
    public double ratio() {
        return ratio;
    }

    /** What a dock on the global ratio pays for one (0 if it does not buy it). */
    public double unit(String id) {
        return unit(id, ratio);
    }

    /** What a dock paying this ratio pays for one (0 if it does not buy it). */
    public double unit(String id, double atRatio) {
        return buys(id) ? base.getOrDefault(id, 0.0) * atRatio : 0;
    }

    /** One slot of a vehicle's bed: its index, item id, count, and whether it is plain (no shop mark, no rename...). */
    public record Slot(int index, String id, int amount, boolean plain) {}

    /** One kind of goods in a sale: how many, what one pays, what they pay together (gold, before rounding). */
    public record Line(String id, int count, double unit, double total) {}

    /**
     * A sale: the lines (by kind, in the bed's order), which slots go, the total paid (whole gold, rounded down), and how many
     * slots the dock does not take (raw materials, shop-bought or renamed things: they stay in the bed).
     */
    public record Plan(List<Line> lines, Set<Integer> slots, long total, int kept) {
        public boolean empty() {
            return slots.isEmpty();
        }
    }

    /** What a dock on the global ratio would take out of a bed and pay. */
    public Plan plan(List<Slot> bed) {
        return plan(bed, ratio);
    }

    /** What this dock would take out of a bed and pay (at its own ratio, else the global one). */
    public Plan plan(List<Slot> bed, Dock dock) {
        return plan(bed, dock.ratio(ratio));
    }

    /**
     * What a dock paying this ratio would take out of a bed and pay. Which slots go (and so the manifest) does not depend
     * on the ratio, only what they fetch.
     */
    public Plan plan(List<Slot> bed, double atRatio) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Set<Integer> slots = new LinkedHashSet<>();
        int kept = 0;
        for (Slot s : bed) {
            if (s == null || s.amount() <= 0) continue;
            if (!s.plain() || !buys(s.id()) || base.getOrDefault(s.id(), 0.0) <= 0) {
                kept++;
                continue;
            }
            counts.computeIfAbsent(s.id(), k -> new int[1])[0] += s.amount();
            slots.add(s.index());
        }
        List<Line> lines = new ArrayList<>();
        double sum = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            double u = unit(e.getKey(), atRatio), t = u * e.getValue()[0];
            lines.add(new Line(e.getKey(), e.getValue()[0], u, t));
            sum += t;
        }
        return new Plan(List.copyOf(lines), Set.copyOf(slots), (long) Math.floor(sum + 1e-6), kept);
    }
}
