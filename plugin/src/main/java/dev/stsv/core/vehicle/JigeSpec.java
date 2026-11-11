package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A custom item's {@code jige:} section (content items/*.yml, see content/stsv/items/vehicles_misc.yml): a carrier worn
 * in the chestplate slot (지게), its storage and its look on the back ({@link JigeModule}). Pure (unit-tested).
 *
 * @param slots    storage slots (a multiple of 9, at most 54: one chest view)
 * @param slowdown the share of walking speed the wearer loses (0.1 = 10 % slower)
 * @param stages   the looks on the back: the empty frame first, then by how full it is (a few bundles ... a full load
 *                 under a tarp), as item models ("ns:model/name")
 * @param offset   the look's offset from the back anchor, pixels: x right, y up, z back (as a back cosmetic's offset)
 */
public record JigeSpec(int slots, double slowdown, List<NamespacedKey> stages, org.bukkit.util.Vector offset) {

    public static final int MAX_SLOTS = 54;

    public static JigeSpec parse(ConfigurationSection j, String namespace, Predicate<String> modelExists) {
        int slots = j.getInt("slots", MAX_SLOTS);
        if (slots < 9 || slots > MAX_SLOTS || slots % 9 != 0) throw new IllegalArgumentException("jige.slots 는 9 의 배수, 9 ~ 54");
        double slow = j.getDouble("slowdown", 0.1);
        if (slow < 0 || slow > 0.5) throw new IllegalArgumentException("jige.slowdown 은 0 ~ 0.5 (0.1 = 10% 느리게)");
        List<NamespacedKey> stages = new ArrayList<>();
        for (String s : j.getStringList("stages")) {
            if (s.contains(":")) {
                NamespacedKey k = NamespacedKey.fromString(s);
                if (k == null) throw new IllegalArgumentException("jige.stages: 잘못된 모델 id " + s);
                stages.add(k);
                continue;
            }
            if (!modelExists.test(s)) throw new IllegalArgumentException("jige.stages: models/" + s + ".bbmodel 이 없음");
            stages.add(new NamespacedKey(namespace, "model/" + s));
        }
        if (stages.size() < 2) throw new IllegalArgumentException("jige.stages 는 빈 지게 + 짐 단계 하나 이상");
        List<Double> off = j.getDoubleList("offset");
        if (j.contains("offset") && off.size() != 3) throw new IllegalArgumentException("jige.offset 은 [x, y, z] (픽셀)");
        return new JigeSpec(slots, slow, List.copyOf(stages),
                off.size() == 3 ? new org.bukkit.util.Vector(off.get(0), off.get(1), off.get(2)) : new org.bukkit.util.Vector());
    }

    /**
     * Which look the back shows for this many filled slots: 0 = the empty frame, then the load stages split the storage
     * evenly ({@link CargoStages}: with three, up to a third = a few bundles, up to two thirds = more, more = full under a tarp).
     */
    public int stage(int used) {
        return CargoStages.stage(used, slots, stages.size() - 1);
    }
}
