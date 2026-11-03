package dev.stsv.core.crop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The farming tools of a pack (crops/*.yml, top-level key "tools"): which items water, feed, cure,
 * and which furniture sprinkles or holds a plant. Pure data.
 *
 * <pre>
 * tools:
 *   watering-cans: { economy:watering_can: { capacity: 16, radius: 0 } }
 *   fertilizers: { economy:super_fertilizer: { speed: 1.6, quality: 0.3, yield: 1 } }
 *   pesticides: [economy:pesticide]
 *   sprinklers: { economy:sprinkler: { radius: 2 } }
 *   pots: [economy:planter_pot]
 * </pre>
 */
public final class CropTools {

    public record Can(int capacity, int radius) {}

    /** speed multiplies growth, quality adds to the harvest's quality, yield adds produce */
    public record Fertilizer(String id, double speed, double quality, int yield) {}

    public final Map<String, Can> cans = new LinkedHashMap<>();
    public final Map<String, Fertilizer> fertilizers = new LinkedHashMap<>();
    public final Set<String> pesticides = new LinkedHashSet<>();
    public final Map<String, Integer> sprinklers = new LinkedHashMap<>();
    public final Set<String> pots = new LinkedHashSet<>();

    public void read(ConfigurationSection t) {
        ConfigurationSection c = t.getConfigurationSection("watering-cans");
        if (c != null) for (String k : c.getKeys(false)) cans.put(CropSpec.norm(k), new Can(Math.max(1, c.getInt(k + ".capacity", 16)), Math.max(0, c.getInt(k + ".radius", 0))));
        ConfigurationSection f = t.getConfigurationSection("fertilizers");
        if (f != null) {
            for (String k : f.getKeys(false)) {
                String id = CropSpec.norm(k);
                fertilizers.put(id, new Fertilizer(id, Math.max(0.1, f.getDouble(k + ".speed", 1)), f.getDouble(k + ".quality", 0), f.getInt(k + ".yield", 0)));
            }
        }
        for (String p : t.getStringList("pesticides")) pesticides.add(CropSpec.norm(p));
        ConfigurationSection s = t.getConfigurationSection("sprinklers");
        if (s != null) for (String k : s.getKeys(false)) sprinklers.put(CropSpec.norm(k), Math.max(0, s.getInt(k + ".radius", 2)));
        for (String p : t.getStringList("pots")) pots.add(CropSpec.norm(p));
    }

    public List<String> allItems() {
        List<String> out = new java.util.ArrayList<>();
        out.addAll(cans.keySet());
        out.addAll(fertilizers.keySet());
        out.addAll(pesticides);
        out.addAll(sprinklers.keySet());
        out.addAll(pots);
        return out;
    }
}
