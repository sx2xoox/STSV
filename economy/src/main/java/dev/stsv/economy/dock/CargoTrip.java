package dev.stsv.economy.dock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An inspected trip (README "출발 터미널"): a load checked at a departure terminal's 화물 검사소 and sealed for one dock.
 * While it is {@link Status#SEALED} the bed (or 지게) cannot be opened; at that dock's unloading zone exactly its manifest
 * sells (at the price shown at inspection, the dock's ratio when sealed: {@link #total}), if the load is still what was sealed and the vehicle drove at least
 * {@link #MIN_DRIVEN} of the straight way from the terminal to the dock since. The owner may cancel it (unsealed, void).
 * Pure (unit-tested): {@link TripStore} keeps it, DockModule runs it.
 *
 * @param vehicle   the vehicle's id (a 지게's: its negated id, as {@code Hauled#id})
 * @param manifest  what was sealed: {@link #manifest} of the goods the dock buys ("id:count;..." sorted)
 * @param total     what they sell for at the dock, at its ratio <b>at seal time</b> (as shown at inspection): the promised
 *                  value. Changing the dock's price afterwards (/관리자 하역장 가격) does not change it.
 * @param changed   when the load last changed, as its trip said at inspection: any later change (the bed opened, a sale,
 *                  a 지게 taken off far away) shows as another time
 * @param odometer  the load's trip odometer at inspection (what it drives after counts from there)
 * @param straight  the straight way from the terminal to the dock (blocks)
 * @param ratio     the dock's ratio when it was sealed (0: a trip sealed before docks had prices of their own)
 */
public record CargoTrip(long id, long vehicle, UUID owner, String terminal, String dock, String manifest, long total, long changed,
                        double odometer, double straight, long sealed, Status status, long ended, double ratio) {

    /** A trip whose ratio is not on record. */
    public CargoTrip(long id, long vehicle, UUID owner, String terminal, String dock, String manifest, long total, long changed,
                     double odometer, double straight, long sealed, Status status, long ended) {
        this(id, vehicle, owner, terminal, dock, manifest, total, changed, odometer, straight, sealed, status, ended, 0);
    }

    /**
     * A load sealed now for a dock: the manifest and the total of the plan at the dock's ratio as it is now (its own price,
     * else the global one). That total is fixed from here on.
     */
    public static CargoTrip seal(long vehicle, UUID owner, String terminal, Dock dock, DockGoods goods, List<DockGoods.Slot> bed, long changed,
                                 double odometer, double straight, long at) {
        double r = dock.ratio(goods.ratio());
        DockGoods.Plan plan = goods.plan(bed, r);
        return new CargoTrip(0, vehicle, owner, terminal, dock.name(), manifest(plan.lines()), plan.total(), changed, odometer, straight, at,
                Status.SEALED, 0, r);
    }

    public enum Status { SEALED, DONE, VOID, CANCELLED }

    /** What may happen at a dock: sell, or why not. */
    public enum Check { OK, OTHER_DOCK, MISMATCH, SHORT }

    /** The share of the straight terminal-to-dock way the vehicle must have driven since inspection. */
    public static final double MIN_DRIVEN = 0.8;

    /** A manifest: the goods (id -> count) in a fixed order, as one line of text. */
    public static String manifest(List<DockGoods.Line> lines) {
        Map<String, Integer> m = new TreeMap<>();
        for (DockGoods.Line l : lines) m.merge(l.id(), l.count(), Integer::sum);
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (!b.isEmpty()) b.append(';');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        return b.toString();
    }

    /** A manifest read back: id -> count, in its order. */
    public static Map<String, Integer> lines(String manifest) {
        Map<String, Integer> out = new TreeMap<>();
        if (manifest == null || manifest.isBlank()) return out;
        for (String p : manifest.split(";")) {
            int c = p.lastIndexOf(':');
            out.put(p.substring(0, c), Integer.parseInt(p.substring(c + 1)));
        }
        return out;
    }

    /** "economy:kimchi x100, ..." for messages and the sale log. */
    public static String words(String manifest) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : lines(manifest).entrySet()) out.add(e.getKey() + " x" + e.getValue());
        return String.join(", ", out);
    }

    /** How far the vehicle drove since inspection (blocks), from its load's trip odometer now. */
    public double driven(double odometerNow) {
        return Math.max(0, odometerNow - odometer);
    }

    /** How far it must drive before it may sell (blocks). */
    public double needed() {
        return straight * MIN_DRIVEN;
    }

    /**
     * Whether it may sell at this dock now: its dock, the load unchanged (the same time of last change, the same goods),
     * driven far enough.
     */
    public Check check(String atDock, long changedNow, String manifestNow, double odometerNow) {
        if (!dock.equalsIgnoreCase(atDock)) return Check.OTHER_DOCK;
        if (changedNow != changed || !manifest.equals(manifestNow)) return Check.MISMATCH;
        if (driven(odometerNow) + 1e-9 < needed()) return Check.SHORT;
        return Check.OK;
    }

    public boolean sealedNow() {
        return status == Status.SEALED;
    }

    /** The trip ended (sold, voided, cancelled) at a time. */
    public CargoTrip end(Status s, long at) {
        return new CargoTrip(id, vehicle, owner, terminal, dock, manifest, total, changed, odometer, straight, sealed, s, at, ratio);
    }

    /** With the id the store gave it. */
    public CargoTrip withId(long newId) {
        return new CargoTrip(newId, vehicle, owner, terminal, dock, manifest, total, changed, odometer, straight, sealed, status, ended, ratio);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "CargoTrip#%d(%d %s->%s %s %s)", id, vehicle, terminal, dock, manifest, status);
    }
}
