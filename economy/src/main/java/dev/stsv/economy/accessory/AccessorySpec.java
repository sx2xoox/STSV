package dev.stsv.economy.accessory;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * contents/economy/accessories.yml: grades, effects with their ranges, the enhance table, levels and activities, the box, drops
 * and the 용궁 상자. Pure data and arithmetic (unit-tested): an accessory's amount = its grade's range at its roll x the enhance
 * multiplier. docs/plans/장신구_시스템_기획안.md
 */
public record AccessorySpec(Map<String, Grade> grades, Map<String, Effect> effects, List<Step> steps, int dropFrom, String stone,
                            String charm, NavigableMap<Integer, Integer> levelCaps, int maxLevel, double curve,
                            Map<String, Double> activities, String boxItem, String fragmentItem, int fragments,
                            Map<String, Double> drops, String chestItem, List<Loot> loot, int refines, double rerollStep,
                            double subSize, List<String> slots, Map<String, String> slotNames, Map<String, Double> rankWeights) {

    /** The last enhance's multiplier (1 + every step's add): a treasure's top roll at +10 reaches the effect's max. */
    public static final double FULL = 1.6;

    public record Grade(String id, int index, String name, String color, double lo, double hi, List<Integer> subs, double subFloor,
                        long refine, Map<String, Integer> refineItems, long reroll, double enhance, double box, double drop,
                        boolean announce, boolean announcePerfect) {}

    /**
     * An effect: format pct (x100 %), factor (x(1+v)), flat (a number with a unit). max = a treasure's top roll at +10; ranges =
     * per-grade [lo, hi] given outright (bag rows, magnet blocks). cap: the amount never passes it. desc: one line for the effects
     * screen (/장신구 효과).
     */
    public record Effect(String id, String name, String slot, double max, Map<String, double[]> ranges, String format, String sign,
                         String unit, double cap, String activity, boolean sub, boolean enhances, String desc) {

        /** [lo, hi] of a grade, before enhancing. */
        public double[] range(Grade g) {
            double[] r = ranges.get(g.id());
            if (r != null) return r;
            double top = max / FULL;
            return new double[] {top * g.lo(), top * g.hi()};
        }
    }

    public record Step(double add, long cost, int stones, double chance) {}

    public record Loot(double weight, String item, int min, int max, String fish, boolean accessory) {}

    // ---- arithmetic

    /** An accessory's ranking score (/순위 장신구): its grade's weight x its enhance multiplier x (0.5 + 0.5 x its roll). */
    public double score(String grade, double roll, int enhance) {
        return rankWeights.getOrDefault(grade, 0.0) * multiplier(enhance) * (0.5 + 0.5 * Math.clamp(roll, 0, 1));
    }

    /** The enhance multiplier at a level (0 = 1): 1 + the adds of the steps up to it. */
    public double multiplier(int enhance) {
        double m = 1;
        for (int i = 0; i < Math.min(enhance, steps.size()); i++) m += steps.get(i).add();
        return m;
    }

    /** An amount: the grade's range at the roll (0..1), times the enhance multiplier, at most the cap. */
    public double amount(Effect e, Grade g, double roll, int enhance) {
        double[] r = e.range(g);
        double v = r[0] + (r[1] - r[0]) * Math.clamp(roll, 0, 1);
        if (e.enhances()) v *= multiplier(enhance);
        if ("flat".equals(e.format()) && "줄".equals(e.unit())) v = Math.floor(v);
        return e.cap() > 0 ? Math.min(v, e.cap()) : v;
    }

    /** A sub-option's amount: the effect's treasure range scaled down (sub-size), at the roll; no enhancing. */
    public double subAmount(Effect e, double roll) {
        double[] r = e.range(grades.get("treasure"));
        double v = (r[0] + (r[1] - r[0]) * Math.clamp(roll, 0, 1)) * subSize;
        return e.cap() > 0 ? Math.min(v, e.cap()) : v;
    }

    /** "+43%", "×1.62", "12칸": an amount as the item shows it. */
    public static String text(Effect e, double v) {
        if (v < 0 && "flat".equals(e.format())) return "어디서나";
        return switch (e.format()) {
            case "factor" -> "×" + num(1 + v, 2);
            case "flat" -> num(v, 1) + e.unit();
            default -> e.sign() + num(v * 100, 1) + "%";
        };
    }

    static String num(double v, int digits) {
        double p = Math.pow(10, digits);
        double r = Math.round(v * p) / p;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%." + digits + "f", r);
    }

    /** Cumulative XP a level needs: curve x (n-1)². */
    public double xpFor(int level) {
        int n = Math.max(1, Math.min(level, maxLevel)) - 1;
        return curve * n * n;
    }

    public int levelFor(double xp) {
        int lv = 1;
        while (lv < maxLevel && xp >= xpFor(lv + 1)) lv++;
        return lv;
    }

    /** How far this level lets an accessory be enhanced. */
    public int enhanceCap(int level) {
        Map.Entry<Integer, Integer> e = levelCaps.floorEntry(level);
        return e == null ? 0 : e.getValue();
    }

    /** Sub-option slots open at this level for the grade. */
    public int subsOpen(Grade g, int level) {
        int n = 0;
        for (int at : g.subs()) if (level >= at) n++;
        return n;
    }

    /** The cost of an enhance step (1-based: going to +n) for a grade: gold and stones. */
    public long enhanceCost(Grade g, int to) {
        return Math.round(steps.get(to - 1).cost() * g.enhance());
    }

    public int enhanceStones(Grade g, int to) {
        return (int) Math.ceil(steps.get(to - 1).stones() * g.enhance());
    }

    /** The reroll cost for an accessory that has been rerolled n times. */
    public long rerollCost(Grade g, int done) {
        return Math.round(g.reroll() * Math.pow(1 + rerollStep, done));
    }

    /**
     * A grade drawn from a table (box or drop), with the appraiser's factor on the masterwork and treasure chances (the common
     * chance takes what is left). u in [0,1).
     */
    public Grade draw(boolean box, double appraiser, double u) {
        List<Grade> gs = new ArrayList<>(grades.values());
        double[] p = new double[gs.size()];
        double rest = 1;
        for (int i = gs.size() - 1; i >= 1; i--) {
            Grade g = gs.get(i);
            double base = box ? g.box() : g.drop();
            p[i] = g.index() >= 2 ? base * appraiser : base;
            rest -= p[i];
        }
        p[0] = Math.max(0, rest);
        double acc = 0;
        for (int i = gs.size() - 1; i >= 0; i--) {
            acc += p[i];
            if (u < acc) return gs.get(i);
        }
        return gs.getFirst();
    }

    public List<Effect> effectsOf(String slot) {
        return effects.values().stream().filter(e -> e.slot().equals(slot)).toList();
    }

    public List<Effect> subPool(String except) {
        return effects.values().stream().filter(e -> e.sub() && !e.id().equals(except)).toList();
    }

    // ---- parse

    public static AccessorySpec parse(ConfigurationSection c, List<String> problems) {
        Map<String, Grade> grades = new LinkedHashMap<>();
        ConfigurationSection gs = c.getConfigurationSection("grades");
        int i = 0;
        if (gs != null) for (String id : gs.getKeys(false)) {
            ConfigurationSection g = gs.getConfigurationSection(id);
            List<Double> r = g.getDoubleList("range");
            Map<String, Integer> items = new LinkedHashMap<>();
            ConfigurationSection ri = g.getConfigurationSection("refine-items");
            if (ri != null) for (String k : ri.getKeys(false)) items.put(k, ri.getInt(k));
            grades.put(id, new Grade(id, i++, g.getString("name", id), g.getString("color", "white"), r.size() > 0 ? r.get(0) : 0,
                    r.size() > 1 ? r.get(1) : 1, g.getIntegerList("subs"), g.getDouble("sub-floor", 0), g.getLong("refine"), items,
                    g.getLong("reroll"), g.getDouble("enhance", 1), g.getDouble("box"), g.getDouble("drop"), g.getBoolean("announce"),
                    g.getBoolean("announce-perfect")));
        }
        if (!grades.containsKey("treasure")) problems.add("accessories.grades: treasure 필요");
        Map<String, Effect> effects = new LinkedHashMap<>();
        ConfigurationSection es = c.getConfigurationSection("effects");
        List<String> slotKinds = List.of("ring", "necklace", "earring", "belt", "bracelet");
        if (es != null) for (String id : es.getKeys(false)) {
            ConfigurationSection e = es.getConfigurationSection(id);
            Map<String, double[]> ranges = new LinkedHashMap<>();
            ConfigurationSection rs = e.getConfigurationSection("ranges");
            if (rs != null) for (String g : rs.getKeys(false)) {
                List<Double> v = rs.getDoubleList(g);
                if (v.size() == 2) ranges.put(g, new double[] {v.get(0), v.get(1)});
                else problems.add("effects." + id + ".ranges." + g + ": [lo, hi]");
            }
            String slot = e.getString("slot", "");
            if (!slotKinds.contains(slot)) problems.add("effects." + id + ".slot: " + slot);
            if (ranges.isEmpty() && !e.contains("max")) problems.add("effects." + id + ": max 또는 ranges 필요");
            effects.put(id, new Effect(id, e.getString("name", id), slot, e.getDouble("max"), ranges, e.getString("format", "pct"),
                    e.getString("sign", ""), e.getString("unit", ""), e.getDouble("cap", 0), e.getString("activity", ""),
                    e.getBoolean("sub", true), e.getBoolean("enhance", true), e.getString("desc", "")));
        }
        List<Step> steps = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("enhance.steps")) {
            steps.add(new Step(d(m.get("add")), (long) d(m.get("cost")), (int) d(m.get("stones")), d(m.get("chance"))));
        }
        NavigableMap<Integer, Integer> caps = new TreeMap<>();
        ConfigurationSection lc = c.getConfigurationSection("enhance.level-caps");
        if (lc != null) for (String k : lc.getKeys(false)) caps.put(Integer.parseInt(k.trim()), lc.getInt(k));
        Map<String, Double> activities = new LinkedHashMap<>();
        ConfigurationSection as = c.getConfigurationSection("activities");
        if (as != null) for (String k : as.getKeys(false)) activities.put(k, as.getDouble(k));
        for (Effect e : effects.values()) if (!activities.containsKey(e.activity())) problems.add("effects." + e.id() + ".activity: " + e.activity());
        Map<String, Double> drops = new LinkedHashMap<>();
        ConfigurationSection ds = c.getConfigurationSection("drops");
        if (ds != null) for (String k : ds.getKeys(false)) drops.put(k, ds.getDouble(k));
        List<Loot> loot = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("dragon-chest.loot")) {
            loot.add(new Loot(d(m.get("weight")), m.get("item") == null ? null : m.get("item").toString(), (int) d(m.get("min")),
                    (int) d(m.get("max")), m.get("fish") == null ? null : m.get("fish").toString(), Boolean.TRUE.equals(m.get("accessory"))));
        }
        List<String> slots = c.getStringList("slots");
        Map<String, String> slotNames = new LinkedHashMap<>();
        ConfigurationSection sn = c.getConfigurationSection("slot-names");
        if (sn != null) for (String k : sn.getKeys(false)) slotNames.put(k, sn.getString(k));
        Map<String, Double> rankWeights = new LinkedHashMap<>();
        ConfigurationSection rw = c.getConfigurationSection("ranking");
        if (rw != null) for (String k : rw.getKeys(false)) rankWeights.put(k, rw.getDouble(k));
        return new AccessorySpec(grades, effects, steps, c.getInt("enhance.drop-from", 6), c.getString("enhance.item", "economy:tempering_stone"),
                c.getString("enhance.charm", "economy:guard_charm"), caps, c.getInt("levels.max", 30), c.getDouble("levels.curve", 100),
                activities, c.getString("box.item", "economy:acc_box"), c.getString("box.fragment", "economy:acc_fragment"),
                c.getInt("box.fragments", 30), drops, c.getString("dragon-chest.item", "economy:dragon_chest"), loot, c.getInt("refines", 3),
                c.getDouble("reroll-step", 0.2), c.getDouble("sub-size", 0.2), slots, slotNames, rankWeights);
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
