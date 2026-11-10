package dev.stsv.core.crop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A custom crop, from contents/&lt;pack&gt;/crops/*.yml. It grows like a vanilla crop: an age from 0 to
 * max-age, one step per successful random tick (light 9 or more; faster on wet farmland), bone meal
 * adds 2 to 5. Pure data (unit-tested).
 *
 * <pre>
 * tomato:
 *   name: "토마토"
 *   seed: economy:tomato_seeds        # the item that plants it (and is used up when it replants itself)
 *   stages: 5                         # models: models/tomato_0 .. tomato_4 (the last one only when ripe)
 *   max-age: 7                        # growth steps, like wheat (7)
 *   regrow: 4                         # after harvest the plant stays and goes back to this age (none = replanted from a seed)
 *   height: 1.0                       # how tall the ripe plant is, in blocks (the click box)
 *   soil: [farmland]
 *   harvest:
 *     - { item: economy:tomato, min: 2, max: 4 }   # the first one is the produce
 *     - { item: economy:tomato_seeds, min: 0, max: 1 }
 *   job: farmer                       # anything else is passed on to contents
 * </pre>
 */
public record CropSpec(String id, String name, String seed, int stages, int maxAge, int regrow, List<String> soil, List<Drop> harvest,
                       List<String> models, double height, Map<String, String> extra) {

    public record Drop(String item, int min, int max) {}

    /** Which model a plant of this age shows: the last stage only once it is ripe. */
    public int stageOf(int age) {
        if (age >= maxAge) return stages - 1;
        return Math.max(0, Math.min(stages - 2, age * (stages - 1) / maxAge));
    }

    public boolean ripe(int age) {
        return age >= maxAge;
    }

    /** The main harvest (the first drop). */
    public String produce() {
        return harvest.getFirst().item();
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
        int maxAge = c.getInt("max-age", 7);
        if (maxAge < stages - 1 || maxAge > 25) throw new IllegalArgumentException("max-age 는 stages-1 ~ 25");
        List<Drop> harvest = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("harvest")) {
            Object item = m.get("item");
            if (item == null) throw new IllegalArgumentException("harvest 항목에 item 필요");
            int min = m.get("min") instanceof Number n ? n.intValue() : 1;
            int max = m.get("max") instanceof Number n ? n.intValue() : min;
            harvest.add(new Drop(seedOrItem(namespace, String.valueOf(item)), Math.max(0, min), Math.max(min, max)));
        }
        if (harvest.isEmpty()) throw new IllegalArgumentException("harvest 가 비어 있음");
        int regrow = c.getInt("regrow", -1);
        if (regrow >= maxAge) throw new IllegalArgumentException("regrow 는 max-age 보다 작아야 함");
        List<String> soil = new ArrayList<>();
        for (String s : c.getStringList("soil")) soil.add(norm(s));
        if (soil.isEmpty()) soil.add("minecraft:farmland");
        String model = c.getString("model", key);
        List<String> models = new ArrayList<>();
        for (int i = 0; i < stages; i++) models.add(namespace + ":model/" + model + "_" + i);
        Map<String, String> extra = new LinkedHashMap<>();
        for (String k : c.getKeys(false)) {
            if (List.of("name", "seed", "stages", "max-age", "regrow", "soil", "harvest", "model", "height").contains(k)) continue;
            extra.put(k, String.valueOf(c.get(k)));
        }
        return new CropSpec(id, c.getString("name", key), seedOrItem(namespace, seed), stages, maxAge, regrow, soil, harvest, models,
                c.getDouble("height", 0.9), extra);
    }

    /**
     * Every crop of these content packs (contents/&lt;pack&gt;/crops/*.yml, files in name order, crops in file order).
     * A crop that does not parse is left out with a warning; a later pack's crop of the same id replaces the earlier one.
     */
    public static List<CropSpec> loadAll(java.util.Collection<dev.stsv.core.content.ContentPack> packs, java.util.function.Consumer<String> warn) {
        Map<String, CropSpec> out = new LinkedHashMap<>();
        for (dev.stsv.core.content.ContentPack pack : packs) {
            java.nio.file.Path dir = pack.dir().resolve("crops");
            if (!java.nio.file.Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                    org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f.toFile());
                    for (String key : y.getKeys(false)) {
                        ConfigurationSection sec = y.getConfigurationSection(key);
                        if (sec == null) continue;
                        try {
                            CropSpec spec = parse(pack.namespace(), key, sec);
                            out.put(spec.id(), spec);
                        } catch (IllegalArgumentException e) {
                            warn.accept(pack.namespace() + ":" + key + ": " + e.getMessage());
                        }
                    }
                }
            } catch (java.io.IOException e) {
                warn.accept(dir + ": " + e.getMessage());
            }
        }
        return new ArrayList<>(out.values());
    }

    private static String seedOrItem(String namespace, String id) {
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : namespace + ":" + s;
    }
}
