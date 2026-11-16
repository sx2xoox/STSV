package dev.stsv.core.cosmetic;

import dev.stsv.core.content.CustomItem;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cosmetic sets (contents/&lt;pack&gt;/cosmetic_sets.yml): cosmetics that name the same set (cosmetic.set) belong
 * together; whoever wears every piece of a set gets its bonus, a ring of particles round them that shows walking
 * or standing (on top of each piece's own look, motion and glow). The 후원 상점's sets are made this way. Pure: the
 * file and the sums, nothing of the server (tested as is).
 *
 * @param sets set id -> the set
 */
public record CosmeticSets(Map<String, Set> sets) {

    public static final CosmeticSets NONE = new CosmeticSets(Map.of());

    /**
     * One set.
     *
     * @param name   its name (MiniMessage)
     * @param pieces the cosmetics (ids "stsv:...") that name it, in the order the content lists them
     * @param bonus  the ring a full set gets (CustomItem.Fx: particle, colour, count, radius = spread, height)
     */
    public record Set(String id, String name, List<String> pieces, CustomItem.Fx bonus) {}

    /**
     * Reads the file's {@code sets:} and gathers each set's pieces from the cosmetics; a set no cosmetic names, or a
     * cosmetic naming a set the file does not have, is reported in problems.
     */
    public static CosmeticSets parse(ConfigurationSection c, Collection<CustomItem> items, List<String> problems) {
        Map<String, List<String>> pieces = new LinkedHashMap<>();
        for (CustomItem i : items) {
            if (i.cosmetic() == null || i.cosmetic().set() == null) continue;
            pieces.computeIfAbsent(i.cosmetic().set(), k -> new ArrayList<>()).add(i.key().asString());
        }
        Map<String, Set> out = new LinkedHashMap<>();
        ConfigurationSection s = c == null ? null : c.getConfigurationSection("sets");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(id);
                if (e == null) continue;
                List<String> p = pieces.getOrDefault(id, List.of());
                if (p.size() < 2) {
                    problems.add("sets." + id + ": 이 세트를 가리키는 치장(cosmetic.set: " + id + ")이 둘 이상 있어야 함 (" + p.size() + "개)");
                    continue;
                }
                CustomItem.Fx bonus = null;
                String particle = e.getString("bonus.particle");
                if (particle != null) {
                    try {
                        org.bukkit.Particle pt = org.bukkit.Particle.valueOf(particle.trim().toUpperCase(Locale.ROOT));
                        if (pt.getDataType() != Void.class && pt.getDataType() != org.bukkit.Particle.DustOptions.class) {
                            problems.add("sets." + id + ".bonus.particle: " + particle + " 은(는) 추가 데이터가 필요해서 쓸 수 없음");
                        } else {
                            bonus = new CustomItem.Fx(pt.name(), Math.max(1, e.getInt("bonus.count", 10)), e.getDouble("bonus.spread", 0.8),
                                    e.getDouble("bonus.speed", 0.0), e.getString("bonus.color"), e.getString("bonus.sound"), false, false,
                                    e.getDouble("bonus.height", 1.0));
                        }
                    } catch (IllegalArgumentException ex) {
                        problems.add("sets." + id + ".bonus.particle: 없는 파티클 " + particle);
                    }
                }
                out.put(id, new Set(id, e.getString("name", id), List.copyOf(p), bonus));
            }
        }
        for (String id : pieces.keySet()) if (!out.containsKey(id)) problems.add("cosmetic.set: " + id + " 세트가 cosmetic_sets.yml 에 없음 (" + pieces.get(id) + ")");
        return new CosmeticSets(Map.copyOf(out));
    }

    /** The sets this player wears whole (every piece worn). */
    public List<Set> complete(Collection<CustomItem> worn) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (CustomItem i : worn) ids.add(i.key().asString());
        List<Set> out = new ArrayList<>();
        for (Set s : sets.values()) if (ids.containsAll(s.pieces())) out.add(s);
        return out;
    }

    /** How many pieces of the set this player wears. */
    public int worn(Set s, Collection<CustomItem> worn) {
        int n = 0;
        for (CustomItem i : worn) if (s.pieces().contains(i.key().asString())) n++;
        return n;
    }
}
