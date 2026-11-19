package dev.stsv.economy.bus;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** bus.yml: fares, moods, stops and passenger types of the 마을버스 (README "마을버스"). Pure (unit-tested). */
public record BusSpec(Set<String> vehicles, Fare fare, Mood mood, Stops stops, Map<String, Type> types) {

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
                       double far, double tipMood, double rush, double late, double scale, boolean announce, List<String> skins) {}

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
            types.put(id, new Type(id, t.getString("name", id), t.getString("color", "white"), t.getDouble("weight", 1), t.getDouble("fare", 1),
                    t.getDouble("mood", 1), t.getDouble("slow", 1), t.getDouble("jolt", 1), t.getDouble("tip", 1), t.getDouble("far", 0),
                    t.getDouble("tip-mood", 0), t.getDouble("rush", 1), t.getDouble("late", 0), t.getDouble("scale", 1), t.getBoolean("announce"),
                    List.copyOf(t.getStringList("skins"))));
        }
        if (types.isEmpty()) problems.add("types 가 비어 있음");
        return new BusSpec(Set.copyOf(c.getStringList("vehicles")), fare, mood, stops, types);
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
