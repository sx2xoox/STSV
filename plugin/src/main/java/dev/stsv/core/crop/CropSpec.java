package dev.stsv.core.crop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A custom crop, from contents/&lt;pack&gt;/crops/*.yml. Pure data (unit-tested).
 *
 * <pre>
 * tomato:
 *   name: "토마토"
 *   seed: economy:tomato_seeds        # the item that plants it
 *   stages: 5                         # models: models/tomato_0 .. tomato_4 (stage 0 = just planted)
 *   grow-minutes: 120                 # watered time from planting to ripe
 *   water-hours: 8                    # one watering lasts this long
 *   wither-hours: 24                  # dry this long = withered
 *   pest-chance: 0.05                 # per stage: chance of pests (growth stops until treated)
 *   pest-hours: 12                    # untreated pests this long = withered
 *   regrow: 3                         # after harvest it goes back to this stage (none = the plant is gone)
 *   soil: [farmland]                  # blocks it can be planted on (pots always work)
 *   harvest:
 *     - { item: economy:tomato, min: 2, max: 4, quality: true }
 *     - { item: economy:tomato_seeds, min: 0, max: 1 }
 *   job: farmer                       # anything else is passed on to contents (e.g. who may plant it)
 * </pre>
 */
public record CropSpec(String id, String name, String seed, int stages, double growMinutes, double waterHours, double witherHours,
                       double pestChance, double pestHours, int regrow, List<String> soil, List<Drop> harvest,
                       List<String> models, String witheredModel, double height, Map<String, String> extra) {

    public record Drop(String item, int min, int max, boolean quality) {}

    public long growMillis() {
        return (long) (growMinutes * 60_000);
    }

    /** Growth time per stage (stage 0 to the last stage takes growMillis). */
    public long stageMillis() {
        return growMillis() / Math.max(1, stages - 1);
    }

    public int stageOf(long progress) {
        return (int) Math.min(stages - 1, Math.max(0, progress / Math.max(1, stageMillis())));
    }

    public boolean ripe(long progress) {
        return progress >= growMillis();
    }

    public static String norm(String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    public static CropSpec parse(String namespace, String key, ConfigurationSection c) {
        String id = namespace + ":" + key;
        String seed = c.getString("seed");
        if (seed == null) throw new IllegalArgumentException("seed 필요");
        int stages = c.getInt("stages", 4);
        if (stages < 2 || stages > 8) throw new IllegalArgumentException("stages 는 2~8");
        double grow = c.getDouble("grow-minutes", 120);
        if (grow <= 0) throw new IllegalArgumentException("grow-minutes 는 0보다 커야 함");
        List<Drop> harvest = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("harvest")) {
            Object item = m.get("item");
            if (item == null) throw new IllegalArgumentException("harvest 항목에 item 필요");
            int min = m.get("min") instanceof Number n ? n.intValue() : 1;
            int max = m.get("max") instanceof Number n ? n.intValue() : min;
            harvest.add(new Drop(seedOrItem(namespace, String.valueOf(item)), Math.max(0, min), Math.max(min, max), Boolean.TRUE.equals(m.get("quality"))));
        }
        if (harvest.isEmpty()) throw new IllegalArgumentException("harvest 가 비어 있음");
        int regrow = c.getInt("regrow", -1);
        if (regrow >= stages - 1) throw new IllegalArgumentException("regrow 는 마지막 단계보다 작아야 함");
        List<String> soil = new ArrayList<>();
        for (String s : c.getStringList("soil")) soil.add(norm(s));
        if (soil.isEmpty()) soil.add("minecraft:farmland");
        String model = c.getString("model", key);
        List<String> models = new ArrayList<>();
        for (int i = 0; i < stages; i++) models.add(namespace + ":model/" + model + "_" + i);
        String withered = c.getString("withered-model", "withered_crop");
        Map<String, String> extra = new LinkedHashMap<>();
        for (String k : c.getKeys(false)) {
            if (List.of("name", "seed", "stages", "grow-minutes", "water-hours", "wither-hours", "pest-chance", "pest-hours", "regrow",
                    "soil", "harvest", "model", "withered-model", "height").contains(k)) continue;
            extra.put(k, String.valueOf(c.get(k)));
        }
        return new CropSpec(id, c.getString("name", key), seedOrItem(namespace, seed), stages, grow,
                c.getDouble("water-hours", 8), c.getDouble("wither-hours", 24), c.getDouble("pest-chance", 0.05),
                c.getDouble("pest-hours", 12), regrow, soil, harvest, models,
                withered.contains(":") ? withered : namespace + ":model/" + withered, c.getDouble("height", 0.9), extra);
    }

    private static String seedOrItem(String namespace, String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : namespace + ":" + s;
    }
}
