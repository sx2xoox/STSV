package dev.stsv.economy.land.log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an undo may put back, given what the person who did it still holds. Pure (unit-tested).
 *
 * <p>The rule that keeps an undo from making items: <b>only what is taken back from them is put back</b> (회수한 만큼만
 * 되돌림). Each unit of an undo (a block, a container take, a frame's item, an animal...) carries what that person gained
 * by it (a broken block's real drops, the items taken). A unit that gained them nothing (a block broken in creative or
 * with no drops, a block they placed, items they put in) goes back freely. Otherwise:
 * <ul>
 *   <li>{@link Kind#WHOLE} (a block, an entity, a frame's item, furniture): only when everything it gained is held, all
 *       of it is taken and the unit goes back; else nothing is taken and it stays owed.</li>
 *   <li>{@link Kind#COUNT} (items taken out of a container): as many as are held are taken, and that many go back into
 *       the container; the rest stays owed.</li>
 * </ul>
 * Units are served in the order given (oldest first), each drawing from what is left. {@code forced} (an administrator's
 * 강제): everything goes back whatever was taken; what could be taken still is.
 */
public final class Confiscation {

    public enum Kind { WHOLE, COUNT }

    /** One unit: its id, how it goes back, and what it gained the person (item key -> amount; empty = nothing). */
    public record Unit(long id, Kind kind, Map<String, Integer> gain) {}

    /**
     * What happens to a unit: take = to remove from the person now; restore = it goes back; restoreCount = COUNT: how
     * many go back; owed = still to be taken back (it stays pending).
     */
    public record Result(long id, Map<String, Integer> take, boolean restore, int restoreCount, Map<String, Integer> owed) {}

    private Confiscation() {}

    public static List<Result> plan(List<Unit> units, Map<String, Integer> held, boolean forced) {
        Map<String, Integer> left = new HashMap<>(held);
        List<Result> out = new ArrayList<>();
        for (Unit u : units) {
            Map<String, Integer> gain = new LinkedHashMap<>();
            u.gain().forEach((k, n) -> {
                if (n != null && n > 0) gain.merge(k, n, Integer::sum);
            });
            int total = gain.values().stream().mapToInt(Integer::intValue).sum();
            if (gain.isEmpty()) {
                out.add(new Result(u.id(), Map.of(), true, 0, Map.of()));
                continue;
            }
            if (u.kind() == Kind.WHOLE) {
                boolean all = gain.entrySet().stream().allMatch(e -> left.getOrDefault(e.getKey(), 0) >= e.getValue());
                if (all) {
                    gain.forEach((k, n) -> left.merge(k, -n, Integer::sum));
                    out.add(new Result(u.id(), gain, true, total, Map.of()));
                } else {
                    // nothing is taken for a unit that cannot go back whole (taking part of it would only punish)
                    out.add(new Result(u.id(), Map.of(), forced, forced ? total : 0, forced ? Map.of() : gain));
                }
                continue;
            }
            Map<String, Integer> take = new LinkedHashMap<>(), owed = new LinkedHashMap<>();
            int taken = 0;
            for (Map.Entry<String, Integer> e : gain.entrySet()) {
                int t = Math.min(e.getValue(), Math.max(0, left.getOrDefault(e.getKey(), 0)));
                if (t > 0) {
                    take.put(e.getKey(), t);
                    left.merge(e.getKey(), -t, Integer::sum);
                    taken += t;
                }
                if (e.getValue() - t > 0 && !forced) owed.put(e.getKey(), e.getValue() - t);
            }
            int back = forced ? total : taken;
            out.add(new Result(u.id(), take, back > 0, back, owed));
        }
        return out;
    }
}
