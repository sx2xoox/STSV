package dev.stsv.economy.fishing;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * contents/economy/fish.yml: the rarities (how hard the ring is), the codex groups and the fish (where, when,
 * how big, how much). Pure data and the catch maths (unit-tested).
 */
public record FishSpec(Map<String, Rarity> rarities, Map<String, Group> groups, Map<String, Species> species,
                       double legendShare, double pearl) {

    /** speed = needle cells per tick, zone = green cells, hits = successes needed. */
    public record Rarity(String id, int index, String name, String color, int hits, double speed, int zone) {}

    public record Group(String id, String name, String title) {}

    /** item: the item caught (economy:<id> unless a vanilla fish); when: a time or weather it only bites in. */
    public record Species(String id, String name, String habitat, String rarity, double min, double max, double price,
                          String shape, String item, String when) {

        /** Its codex group: its habitat, or the time and weather group. */
        public String group() {
            return when != null ? "special" : habitat;
        }
    }

    public static final List<String> SALT = List.of("sea", "deep", "warm", "cold", "mangrove", "mushroom");

    /** A fish whose size was taken off (in a machine slot) sells as the smallest of its kind. */
    public static final double STRIPPED_FACTOR = 0.7;

    /** The water around a small one: the mushroom island has no common fish of its own, the open sea gives them. */
    static final Map<String, String> AROUND = Map.of("mushroom", "sea");

    public static FishSpec parse(ConfigurationSection c, List<String> problems) {
        Map<String, Rarity> rarities = new LinkedHashMap<>();
        ConfigurationSection rs = c.getConfigurationSection("rarities");
        int i = 0;
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r == null) continue;
                rarities.put(id, new Rarity(id, i++, r.getString("name", id), r.getString("color", "#ffffff"), Math.max(1, r.getInt("hits", 2)),
                        r.getDouble("speed", 1), Math.max(3, r.getInt("zone", 8))));
            }
        }
        if (rarities.size() != 4) problems.add("fish.rarities: 4개 (흔함, 보통, 희귀, 전설) 필요");
        Map<String, Group> groups = new LinkedHashMap<>();
        ConfigurationSection gs = c.getConfigurationSection("groups");
        if (gs != null) for (String id : gs.getKeys(false)) groups.put(id, new Group(id, gs.getString(id + ".name", id), gs.getString(id + ".title")));
        Map<String, Species> species = new LinkedHashMap<>();
        ConfigurationSection ss = c.getConfigurationSection("species");
        if (ss != null) {
            for (String id : ss.getKeys(false)) {
                ConfigurationSection s = ss.getConfigurationSection(id);
                if (s == null) continue;
                List<Double> size = s.getDoubleList("size");
                String rar = s.getString("rarity", "common");
                if (!rarities.containsKey(rar)) problems.add("fish." + id + ": 없는 등급 " + rar);
                if (size.size() != 2 || size.get(0) <= 0 || size.get(1) < size.get(0)) problems.add("fish." + id + ": size [최소, 최대]");
                String item = s.getString("item", "economy:" + id);
                species.put(id, new Species(id, s.getString("name", id), s.getString("habitat", "river"), rar,
                        size.isEmpty() ? 10 : size.get(0), size.size() < 2 ? 20 : size.get(1), s.getDouble("price", 1),
                        s.getString("shape", "f"), item, s.getString("when")));
            }
        }
        if (species.isEmpty()) problems.add("fish.species: 물고기가 없음");
        return new FishSpec(rarities, groups, species, c.getDouble("legend-share", 0.2), c.getDouble("pearl", 0.15));
    }

    // ------------------------------------------------------------------------------------------
    // Where, when, what
    // ------------------------------------------------------------------------------------------

    /** The water a hook is in, from its biome key (minecraft:...) and whether it is underground. */
    public static String habitat(String biome, boolean underground) {
        String b = biome.contains(":") ? biome.substring(biome.indexOf(':') + 1) : biome;
        if (underground || b.equals("lush_caves") || b.equals("dripstone_caves") || b.equals("deep_dark")) return "cave";
        if (b.equals("mushroom_fields")) return "mushroom";
        if (b.equals("mangrove_swamp")) return "mangrove";
        if (b.equals("swamp")) return "swamp";
        if (b.contains("jungle")) return "jungle";
        if (b.equals("warm_ocean") || b.contains("lukewarm")) return "warm";
        if (b.contains("cold_ocean") || b.contains("frozen_ocean") || b.equals("snowy_beach")) return "cold";
        if (b.equals("deep_ocean")) return "deep";
        if (b.equals("ocean") || b.equals("beach") || b.equals("stony_shore")) return "sea";
        if (b.contains("frozen") || b.contains("snowy") || b.contains("ice") || b.contains("taiga") || b.equals("grove")
                || b.contains("peaks") || b.contains("slopes")) return "coldriver";
        return "river";
    }

    /** The moment: time of day in ticks (0..23999), weather, moon. */
    public record Moment(long time, boolean rain, boolean thunder, int moonPhase) {
        public boolean night() {
            return time >= 13000 && time < 23000;
        }

        public boolean is(String when) {
            if (when == null) return true;
            return switch (when) {
                case "night" -> night();
                case "dawn" -> time >= 22000 || time < 1000;
                case "dusk" -> time >= 11500 && time < 13500;
                case "rain" -> rain;
                case "thunder" -> thunder;
                case "fullmoon" -> night() && moonPhase == 0;
                default -> false;
            };
        }
    }

    public boolean livesIn(Species s, String habitat) {
        if (s.habitat().equals(habitat)) return true;
        boolean salt = SALT.contains(habitat);
        return s.habitat().equals("salt") && salt || s.habitat().equals("fresh") && !salt;
    }

    /**
     * The rarity of a bite: rare or legend with the given chance (a fisher's 5 -> 15%, anyone else 1%), a
     * legend-share of those legend; the rest common or uncommon 65 : 35.
     */
    public Rarity tier(double rareChance, Random r) {
        List<Rarity> l = new ArrayList<>(rarities.values());
        double x = r.nextDouble();
        if (x < rareChance) return r.nextDouble() < legendShare ? l.get(3) : l.get(2);
        return r.nextDouble() < 0.35 ? l.get(1) : l.get(0);
    }

    /**
     * A fish of that rarity living here now; a rarer roll with nothing to give falls back a rarity, a water
     * with nothing at all to give (the mushroom island's commons) lends the fish of the water around it.
     */
    public Species pick(String habitat, Moment m, Rarity tier, Random r) {
        Species s = pickIn(habitat, m, tier, r);
        if (s == null && AROUND.containsKey(habitat)) s = pickIn(AROUND.get(habitat), m, tier, r);
        return s;
    }

    private Species pickIn(String habitat, Moment m, Rarity tier, Random r) {
        List<Rarity> l = new ArrayList<>(rarities.values());
        for (int t = tier.index(); t >= 0; t--) {
            String rid = l.get(t).id();
            List<Species> pool = new ArrayList<>();
            for (Species s : species.values()) {
                if (s.rarity().equals(rid) && livesIn(s, habitat) && m.is(s.when())) {
                    pool.add(s);
                    if (s.when() != null) pool.add(s);   // a fish of the moment comes up twice as often
                }
            }
            if (!pool.isEmpty()) return pool.get(r.nextInt(pool.size()));
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Size and price
    // ------------------------------------------------------------------------------------------

    /**
     * The size of a catch: towards the small end (u^1.4), +3% per perfect hit, +10% for an all-perfect catch,
     * +10% for a fisher of Lv20 (the size bonus), at most 15% past its biggest.
     */
    public static double size(Species s, double u, int perfect, boolean allPerfect, double bonus) {
        double v = s.min() + (s.max() - s.min()) * Math.pow(u, 1.4);
        v *= 1 + 0.03 * perfect;
        if (allPerfect) v *= 1.10;
        v *= 1 + bonus;
        return Math.round(Math.min(v, s.max() * 1.15) * 10) / 10.0;
    }

    /** Price factor by size: 0.7 at the smallest, 1.5 at the biggest (and past it). */
    public static double priceFactor(Species s, double size) {
        double k = s.max() <= s.min() ? 1 : Math.clamp((size - s.min()) / (s.max() - s.min()), 0, 1);
        return 0.7 + 0.8 * k;
    }
}
