package dev.stsv.economy.land.log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one person took out of and put into a container while they had it open: the counts of each kind of item (its
 * key: the item as one, serialized, so a renamed or enchanted sword is not a plain one) before and after. Pure.
 */
public final class ContainerDiff {

    private ContainerDiff() {}

    /** key -> amount: positive = put in, negative = taken out. Unchanged kinds are left out. */
    public static Map<String, Integer> diff(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int d = after.getOrDefault(e.getKey(), 0) - e.getValue();
            if (d != 0) out.put(e.getKey(), d);
        }
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            if (!before.containsKey(e.getKey()) && e.getValue() != 0) out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
