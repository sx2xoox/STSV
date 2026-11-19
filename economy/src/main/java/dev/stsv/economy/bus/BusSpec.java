package dev.stsv.economy.bus;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** bus.yml: fares, moods, stops and passenger types of the 마을버스 (README "마을버스"). Pure (unit-tested). */
public record BusSpec(Set<String> vehicles, Fare fare, Mood mood, Stops stops, Map<String, Type> types, Extra extra) {

    /** One kind of bus's rules: passengers only from this road distance on, only these kinds (empty: all), a fare factor. */
    public record Bus(double minRoad, Set<String> only, double fare) {
        public static final Bus ANY = new Bus(0, Set.of(), 1);

        public boolean takes(Type t) {
            return only.isEmpty() || only.contains(t.id());
        }
    }

    public record Gift(String item, int amount) {}

    /**
     * The 2차 details: stop traits (id -> word), market day (every n days, at stops of these traits), the picnic, transfers,
     * dozing, passing a stop, the weather, talk, gifts, and each bus's rules.
     */
    public record Extra(Map<String, String> tags, int marketEvery, Set<String> marketTags, int picnicFrom, int picnicTo, int picnicStudents,
                        double transfer, double transferBonus, int dozeAfter, double dozeMiss, int passed, double missedMood,
                        double rainTip, double snowJolt, int talkCooldown, double giftChance, double giftMood,
                        Map<String, List<String>> talk, Map<String, Bus> buses) {}

    public record Step(double at, double factor) {}

    public record Fare(double base, double perBlock, double onTimeSpeed, double onTimeGrace, double tip, List<Step> mood,
                       List<Step> daily, int repeatWindow, int repeatFree, double repeatFactor) {}

    public record Mood(double start, double jolt, double joltFrom, int joltWindow, double bump, double turn, double turnSpeed, double turnDegrees,
                       double smoothStop, int cooldown) {}

    public record Hours(int from, int to, double factor) {}

    public record Auto(int startSkip, int endMin, int endMax, int every, int apart, int junctionClear) {}

    public record Stops(int waitingMax, double refillMinutes, List<Hours> hours, int showRange, int hideAfter, int boardTicks, Auto auto,
                        int bayLength, int bayWidth, double stopReach) {}

    public record Type(String id, String name, String color, double weight, double fare, double mood, double slow, double jolt, double tip,
                       double far, double tipMood, double rush, double late, double scale, boolean announce, List<String> skins,
                       Map<String, Double> tags, int hourFrom, int hourTo, double weekend, double market, double luggage, boolean pair,
                       double wander, double turnFactor, boolean toEnd, double doze, List<Gift> gifts, Map<String, List<String>> talk) {

        /** This kind's weight at a stop of these traits, at this hour, on a weekend or not, on its market day or not (0: not now). */
        public double weightAt(Set<String> stopTags, int hour, boolean weekendDay, boolean marketDay, boolean rush) {
            if (hourFrom >= 0) {
                boolean in = hourFrom <= hourTo ? hour >= hourFrom && hour < hourTo : hour >= hourFrom || hour < hourTo;
                if (!in) return 0;
            }
            double w = weight * (rush ? this.rush : 1) * (weekendDay ? weekend : 1) * (marketDay ? market : 1);
            double best = 1;
            boolean any = false;
            for (String t : stopTags) {
                Double f = tags.get(t);
                if (f == null) continue;
                best = any ? Math.max(best, f) : f;
                any = true;
            }
            return w * best;
        }
    }

    public static BusSpec parse(ConfigurationSection c, List<String> problems) {
        ConfigurationSection f = section(c, "fare"), m = section(c, "mood"), s = section(c, "stops");
        ConfigurationSection a = section(s, "auto"), b = section(s, "bay");
        Fare fare = new Fare(f.getDouble("base", 100), f.getDouble("per-block", 0.4), f.getDouble("on-time-speed", 6),
                f.getDouble("on-time-grace", 30), f.getDouble("tip", 0.15), steps(f.getMapList("mood"), "at", "factor"),
                steps(f.getMapList("daily"), "upto", "rate"), f.getInt("repeat-window", 30), f.getInt("repeat-free", 2),
                f.getDouble("repeat-factor", 0.5));
        if (fare.mood().isEmpty()) problems.add("fare.mood 가 비어 있음");
        if (fare.daily().isEmpty()) problems.add("fare.daily 가 비어 있음");
        Mood mood = new Mood(m.getDouble("start", 80), m.getDouble("jolt", -5), m.getDouble("jolt-from", 6), m.getInt("jolt-window", 30), m.getDouble("bump", -10),
                m.getDouble("turn", -3), m.getDouble("turn-speed", 6), m.getDouble("turn-degrees", 3), m.getDouble("smooth-stop", 2),
                m.getInt("cooldown", 20));
        List<Hours> hours = new ArrayList<>();
        for (Map<?, ?> h : s.getMapList("hours")) hours.add(new Hours(num(h, "from", 0).intValue(), num(h, "to", 0).intValue(), num(h, "factor", 1).doubleValue()));
        List<Integer> end = a.getIntegerList("end-before");
        Auto auto = new Auto(a.getInt("start-skip", 20), end.size() > 0 ? end.get(0) : 40, end.size() > 1 ? end.get(1) : 60, a.getInt("every", 2000),
                a.getInt("apart", 150), a.getInt("junction-clear", 150));
        Stops stops = new Stops(s.getInt("waiting-max", 4), s.getDouble("refill-minutes", 3), hours, s.getInt("show-range", 64),
                s.getInt("hide-after", 30), s.getInt("board-ticks", 10), auto, b.getInt("length", 24), b.getInt("width", 3), b.getDouble("stop-reach", 7));
        Map<String, Type> types = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection("types");
        if (ts != null) for (String id : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(id);
            if (t == null) continue;
            Map<String, Double> tg = new LinkedHashMap<>();
            ConfigurationSection tgs = t.getConfigurationSection("tags");
            if (tgs != null) for (String k : tgs.getKeys(false)) tg.put(k, tgs.getDouble(k, 1));
            List<Integer> when = t.getIntegerList("hours");
            List<Gift> gifts = new ArrayList<>();
            for (Map<?, ?> g : t.getMapList("gifts")) {
                if (g.get("item") instanceof String item) gifts.add(new Gift(item, num(g, "amount", 1).intValue()));
            }
            types.put(id, new Type(id, t.getString("name", id), t.getString("color", "white"), t.getDouble("weight", 1), t.getDouble("fare", 1),
                    t.getDouble("mood", 1), t.getDouble("slow", 1), t.getDouble("jolt", 1), t.getDouble("tip", 1), t.getDouble("far", 0),
                    t.getDouble("tip-mood", 0), t.getDouble("rush", 1), t.getDouble("late", 0), t.getDouble("scale", 1), t.getBoolean("announce"),
                    List.copyOf(t.getStringList("skins")), tg, when.size() == 2 ? when.get(0) : -1, when.size() == 2 ? when.get(1) : -1,
                    t.getDouble("weekend", 1), t.getDouble("market", 1), t.getDouble("luggage", 0), t.getBoolean("pair"), t.getDouble("wander", 0),
                    t.getDouble("turn", 1), t.getBoolean("to-end"), t.getDouble("doze", 0.15), List.copyOf(gifts), talk(t.getConfigurationSection("talk"))));
        }
        if (types.isEmpty()) problems.add("types 가 비어 있음");
        ConfigurationSection mk = section(c, "market"), pc = section(c, "picnic"), dz = section(c, "doze");
        Map<String, String> tagNames = new LinkedHashMap<>();
        ConfigurationSection tn = c.getConfigurationSection("tags");
        if (tn != null) for (String k : tn.getKeys(false)) tagNames.put(k, tn.getString(k, k));
        Map<String, Bus> buses = new LinkedHashMap<>();
        ConfigurationSection bs = c.getConfigurationSection("buses");
        if (bs != null) for (String k : bs.getKeys(false)) {
            ConfigurationSection bb = bs.getConfigurationSection(k);
            if (bb != null) buses.put(k, new Bus(bb.getDouble("min-road", 0), Set.copyOf(bb.getStringList("only")), bb.getDouble("fare", 1)));
        }
        Extra extra = new Extra(tagNames, Math.max(1, mk.getInt("every", 5)), Set.copyOf(mk.getStringList("tags")), pc.getInt("from", 9),
                pc.getInt("to", 12), pc.getInt("students", 10), c.getDouble("transfer", 0.25), c.getDouble("transfer-bonus", 0.2),
                dz.getInt("after", 90), dz.getDouble("miss", 0.4), c.getInt("passed", 15), c.getDouble("missed-mood", -10),
                c.getDouble("rain-tip", 0.2), c.getDouble("snow-jolt", 0.5), c.getInt("talk-cooldown", 60), c.getDouble("gift-chance", 0.35),
                c.getDouble("gift-mood", 90), talk(c.getConfigurationSection("talk")), buses);
        for (Type t : types.values()) for (String k : t.tags().keySet()) {
            if (!tagNames.containsKey(k)) problems.add("types." + t.id() + ".tags: 없는 특성 " + k);
        }
        for (Bus bus : buses.values()) for (String k : bus.only()) if (!types.containsKey(k)) problems.add("buses: 없는 손님 종류 " + k);
        return new BusSpec(Set.copyOf(c.getStringList("vehicles")), fare, mood, stops, types, extra);
    }

    private static Map<String, List<String>> talk(ConfigurationSection t) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (t != null) for (String k : t.getKeys(false)) out.put(k, List.copyOf(t.getStringList(k)));
        return out;
    }

    /** A line for this kind in this situation (its own, else the default ones), or null. */
    public List<String> lines(Type t, String situation) {
        List<String> own = t == null ? null : t.talk().get(situation);
        if (own != null && !own.isEmpty()) return own;
        List<String> d = extra.talk().get(situation);
        return d == null || d.isEmpty() ? null : d;
    }

    /** A bus's rules (any bus not listed takes everyone). */
    public Bus bus(String itemKey) {
        return extra.buses().getOrDefault(itemKey, Bus.ANY);
    }

    /** Whether today (a calendar day number) is market day. */
    public boolean marketDay(int day) {
        return Math.floorMod(day, extra.marketEvery()) == 0;
    }

    private static ConfigurationSection section(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s != null ? s : c.createSection(key);
    }

    private static List<Step> steps(List<Map<?, ?>> list, String at, String factor) {
        List<Step> out = new ArrayList<>();
        for (Map<?, ?> m : list) out.add(new Step(num(m, at, 0).doubleValue(), num(m, factor, 1).doubleValue()));
        return out;
    }

    private static Number num(Map<?, ?> m, String key, Number def) {
        return m.get(key) instanceof Number n ? n : def;
    }

    // ------------------------------------------------------------------------------------------
    // The numbers
    // ------------------------------------------------------------------------------------------

    /** The fare before mood and the daily curve: base + road blocks × per-block, times the type's fare. */
    public double fare(double roadBlocks, Type t) {
        return (fare.base() + Math.max(0, roadBlocks) * fare.perBlock()) * (t == null ? 1 : t.fare());
    }

    /** The mood factor: the first step whose threshold the mood reaches (steps from the top down). */
    public double moodFactor(double mood) {
        for (Step s : fare.mood()) if (mood >= s.at()) return s.factor();
        return fare.mood().isEmpty() ? 1 : fare.mood().getLast().factor();
    }

    /** Seconds a ride of this many road blocks may take and still tip. */
    public double onTime(double roadBlocks) {
        return roadBlocks / Math.max(0.1, fare.onTimeSpeed()) + fare.onTimeGrace();
    }

    /**
     * What is paid of an amount when the driver already earned this much today: each band of the daily curve at its rate
     * (a fare across a band boundary is paid at both).
     */
    public double daily(double earnedToday, double amount) {
        double paid = 0, at = earnedToday, left = amount;
        for (Step s : fare.daily()) {
            if (left <= 0) break;
            double top = s.at() < 0 ? Double.MAX_VALUE : s.at();
            if (at >= top) continue;
            // the band's room is counted in what is paid out (earned money is what the driver received)
            double room = (top - at) / Math.max(1e-9, s.factor());
            double used = Math.min(left, room);
            paid += used * s.factor();
            at += used * s.factor();
            left -= used;
        }
        return paid;
    }

    /** How many times more passengers come at this hour (Korean time). */
    public double hourFactor(int hour) {
        for (Hours h : stops.hours()) {
            boolean in = h.from() <= h.to() ? hour >= h.from() && hour < h.to() : hour >= h.from() || hour < h.to();
            if (in) return h.factor();
        }
        return 1;
    }

    /** Whether this hour is a rush hour (a factor over 1). */
    public boolean rush(int hour) {
        return hourFactor(hour) > 1;
    }
}
