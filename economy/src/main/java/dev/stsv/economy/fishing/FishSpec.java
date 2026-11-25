package dev.stsv.economy.fishing;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * contents/economy/fish.yml: the rarities (name and colour), the codex groups and the fish (where, when, how big,
 * how much); the fisher's shorter wait for a bite. Pure data and the catch maths (unit-tested).
 */
public record FishSpec(Map<String, Rarity> rarities, Map<String, Group> groups, Map<String, Species> species,
                       double legendShare, double pearl, double pearlRiver, Map<String, String> colours) {

    /** The waters a pearl comes up from on a rare bite: the sea's ({@link #pearl}) and the rivers' ({@link #pearlRiver}). */
    public double pearlChance(String habitat) {
        if (SALT.contains(habitat)) return pearl;
        if (habitat.equals("river") || habitat.equals("coldriver")) return pearlRiver;
        return 0;
    }

    /** Vanilla's wait for a fish (FishingHook: 100 - 600 ticks), what Lure takes off a level (5 s), at most 80% off for the fisher. */
    public static final int VANILLA_WAIT_MIN = 100, VANILLA_WAIT_MAX = 600, LURE_TICKS = 100;
    public static final double MAX_FISHER_CUT = 0.8;

    /**
     * The hook's wait for a fish, {min, max} ticks, as vanilla makes it (a draw in 100 - 600 ticks less 100 a Lure level, drawn again
     * while it is not above 0: uniform over what is left of the range), then shortened by the fisher's "bite" cut (jobs.yml, 0..0.8).
     * With no cut the module leaves the hook as vanilla made it; with one it sets this range and turns the hook's own Lure off. The
     * fish's swim to the float after it (vanilla 1 - 4 s) is never shortened.
     */
    public static int[] waitTicks(int lure, double fisherCut) {
        int l = Math.max(0, lure) * LURE_TICKS;
        int hi = Math.max(1, VANILLA_WAIT_MAX - l), lo = Math.clamp(VANILLA_WAIT_MIN - l, 1, hi);
        double f = 1 - Math.clamp(fisherCut, 0, MAX_FISHER_CUT);
        int a = Math.max(1, (int) Math.round(lo * f));
        return new int[] {a, Math.max(a, (int) Math.round(hi * f))};
    }

    /** A rarity: its name and colour (the order in fish.yml: common, uncommon, rare, legend). */
    public record Rarity(String id, int index, String name, String color) {}

    public record Group(String id, String name, String title) {}

    /**
     * item: the item caught (economy:<id> unless a vanilla fish); when: a time or weather it only bites in; colour: its 낚시대회
     * colour group (fish.yml colors), null for none.
     */
    public record Species(String id, String name, String habitat, String rarity, double min, double max, double price,
                          String shape, String item, String when, String colour) {

        public Species(String id, String name, String habitat, String rarity, double min, double max, double price,
                       String shape, String item, String when) {
            this(id, name, habitat, rarity, min, max, price, shape, item, when, null);
        }

        /** Its codex group: its habitat, or the time and weather group. */
        public String group() {
            return when != null ? "special" : habitat;
        }
    }

    public static final List<String> SALT = List.of("sea", "deep", "warm", "cold", "mangrove", "mushroom");

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
                // an older fish.yml also has the ring's hits, speed and zone here: no longer read
                rarities.put(id, new Rarity(id, i++, r.getString("name", id), r.getString("color", "#ffffff")));
            }
        }
        if (rarities.size() != 4) problems.add("fish.rarities: 4개 (흔함, 보통, 희귀, 전설) 필요");
        Map<String, Group> groups = new LinkedHashMap<>();
        ConfigurationSection gs = c.getConfigurationSection("groups");
        if (gs != null) for (String id : gs.getKeys(false)) groups.put(id, new Group(id, gs.getString(id + ".name", id), gs.getString(id + ".title")));
        Map<String, String> colours = new LinkedHashMap<>();
        ConfigurationSection cs = c.getConfigurationSection("colors");
        if (cs != null) for (String id : cs.getKeys(false)) colours.put(id, cs.getString(id, id));
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
                String colour = s.getString("color");
                // (an older fish.yml has no colors at all: the 낚시대회 then has no colour event)
                if (!colours.isEmpty() && (colour == null || !colours.containsKey(colour))) problems.add("fish." + id + ": color 가 없거나 colors 에 없는 색 " + colour);
                species.put(id, new Species(id, s.getString("name", id), s.getString("habitat", "river"), rar,
                        size.isEmpty() ? 10 : size.get(0), size.size() < 2 ? 20 : size.get(1), s.getDouble("price", 1),
                        s.getString("shape", "f"), item, s.getString("when"), colour));
            }
        }
        if (species.isEmpty()) problems.add("fish.species: 물고기가 없음");
        // an older fish.yml also has bite: { min, max, lure-per-level }: the wait is vanilla's now, no longer read
        return new FishSpec(rarities, groups, species, c.getDouble("legend-share", 0.2), c.getDouble("pearl", 0.15),
                c.getDouble("pearl-river", 0.15), colours);
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
    // Size (the 낚시대회's text-only size; a caught fish carries none)
    // ------------------------------------------------------------------------------------------

    /**
     * A fisher's rare and legend bites from Lv20 on are this many times as likely (2026-11, in place of the +17% size the perk gave
     * while fish had sizes: a rare or legend fish then sold for about 15% more at a Lv20 fisher's typical size than at anyone
     * else's, mean price factor 1.18 against 1.03; now every fish sells at 1.0 and a Lv20 fisher catches 18% more of them).
     */
    public static final double FISHER_RARE_BONUS = 1.18;

    /**
     * A fisher's rare and legend bites below Lv20 are this many times as likely: part of the mean price factor every catch had (1.033),
     * as far as the job parity allows (BalanceTest R17: 1.033 put the Lv10 blacksmith at -15.0%, none put the Lv1 miner at +15.1%).
     */
    public static final double FISHER_RARE_BONUS_LOW = 1.015;

    /**
     * The size of a catch (cm): towards the small end (u^1.4), rounded to 0.1. Only the 낚시대회 rolls one (shown in the catch
     * message, used by its 대어 / 작은 고기 / 무게 합계 events); the fish itself has no size.
     */
    public static double size(Species s, double u) {
        double v = s.min() + (s.max() - s.min()) * Math.pow(Math.clamp(u, 0, 1), 1.4);
        return Math.round(v * 10) / 10.0;
    }

    /** The glyph of a fish's picture in the catch title (font economy:fishicon), by its place in fish.yml; what follows has the usual style again. */
    public static String icon(int index) {
        return "<!shadow><white><font:" + ICON_FONT + ">" + (char) (0xE000 + index) + "<reset>";
    }

    public static final String ICON_FONT = "economy:fishicon";
}
