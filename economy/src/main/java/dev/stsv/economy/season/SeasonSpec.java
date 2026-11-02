package dev.stsv.economy.season;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * contents/economy/season.yml: the 4-week plan. What opens on which day, the job level cap and land
 * price per week, scheduled events and announcements. Pure data (unit-tested).
 */
public record SeasonSpec(
        int lengthDays,
        List<Integer> jobLevelCap,
        List<Integer> landPrice,
        Map<String, Integer> opens,
        DemandNotice demand,
        List<Entry> schedule,
        Map<String, EventSpec> events,
        LocalTime dailyRank,
        List<Integer> weeklyRankDays,
        int finaleDay,
        LocalTime finaleTime) {

    public record DemandNotice(int firstDay, DayOfWeek weekday, int count, double multiplier, int lastDay) {}

    /** One scheduled thing: an event start, an announcement or an action ("finale"). */
    public record Entry(int index, int day, LocalTime time, String event, String action, String announce, Map<String, String> params) {
        public String key() {
            return "sched:" + index + ":" + day + ":" + time;
        }
    }

    public record EventSpec(String id, String name, double hours, String color, Map<String, String> params) {
        public double number(String key, double def) {
            try {
                return params.containsKey(key) ? Double.parseDouble(params.get(key)) : def;
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    public int weekOf(int day) {
        return (day - 1) / 7 + 1;
    }

    /** Job level cap on a day (last value repeats). */
    public int levelCap(int day) {
        return pick(jobLevelCap, weekOf(day), 3);
    }

    /** Price of 1 m² of extra land on a day. */
    public int landPrice(int day) {
        return pick(landPrice, weekOf(day), 1);
    }

    private static int pick(List<Integer> byWeek, int week, int def) {
        if (byWeek.isEmpty()) return def;
        return byWeek.get(Math.min(byWeek.size(), Math.max(1, week)) - 1);
    }

    /**
     * Opening day of a feature; a shared goal reached in the week before brings it one day forward.
     * Unknown features are open from day 1.
     */
    public int openDay(String feature, java.util.function.IntPredicate earlyWeek) {
        Integer d = opens.get(feature);
        if (d == null) return 1;
        int week = weekOf(d);
        return week > 1 && earlyWeek.test(week - 1) ? Math.max(1, d - 1) : d;
    }

    @SuppressWarnings("unchecked")
    public static SeasonSpec parse(ConfigurationSection c, List<String> problems) {
        Map<String, Integer> opens = new LinkedHashMap<>();
        ConfigurationSection o = c.getConfigurationSection("open");
        if (o != null) for (String k : o.getKeys(false)) opens.put(k, o.getInt(k));

        ConfigurationSection d = c.getConfigurationSection("demand-notice");
        DemandNotice demand = new DemandNotice(
                d == null ? 3 : d.getInt("first-day", 3),
                d == null ? DayOfWeek.MONDAY : weekday(d.getString("weekday", "MONDAY"), problems),
                d == null ? 3 : d.getInt("count", 3),
                d == null ? 1.5 : d.getDouble("multiplier", 1.5),
                d == null ? 99 : d.getInt("last-day", 99));

        Map<String, EventSpec> events = new LinkedHashMap<>();
        ConfigurationSection ev = c.getConfigurationSection("events");
        if (ev != null) {
            for (String id : ev.getKeys(false)) {
                ConfigurationSection e = ev.getConfigurationSection(id);
                if (e == null) continue;
                Map<String, String> params = new LinkedHashMap<>();
                for (String k : e.getKeys(false)) {
                    if (k.equals("name") || k.equals("hours") || k.equals("color")) continue;
                    params.put(k, String.valueOf(e.get(k)));
                }
                events.put(id, new EventSpec(id, e.getString("name", id), e.getDouble("hours", 24), e.getString("color", "yellow"), params));
            }
        }

        List<Entry> schedule = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> m : c.getMapList("schedule")) {
            i++;
            Object day = m.get("day");
            Object time = m.get("time");
            if (!(day instanceof Number n) || time == null) {
                problems.add("schedule " + i + "번째: day, time 필요");
                continue;
            }
            LocalTime t;
            try {
                t = LocalTime.parse(String.valueOf(time));
            } catch (Exception ex) {
                problems.add("schedule " + i + "번째: 시간 형식 HH:MM (" + time + ")");
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!List.of("day", "time", "event", "action", "announce").contains(k)) params.put(k, String.valueOf(e.getValue()));
            }
            String event = m.get("event") == null ? null : String.valueOf(m.get("event"));
            if (event != null && !events.containsKey(event)) problems.add("schedule " + i + "번째: 정의되지 않은 사건 '" + event + "'");
            schedule.add(new Entry(i, n.intValue(), t, event, m.get("action") == null ? null : String.valueOf(m.get("action")),
                    m.get("announce") == null ? null : String.valueOf(m.get("announce")), params));
        }

        return new SeasonSpec(
                c.getInt("length-days", 28),
                (List<Integer>) (List<?>) c.getIntegerList("job-level-cap"),
                (List<Integer>) (List<?>) c.getIntegerList("land-price"),
                opens, demand, schedule, events,
                time(c.getString("daily-rank", "21:00")),
                c.getIntegerList("weekly-rank-days"),
                c.getInt("finale.day", 28),
                time(c.getString("finale.time", "21:00")));
    }

    private static LocalTime time(String s) {
        try {
            return LocalTime.parse(s);
        } catch (Exception e) {
            return LocalTime.of(21, 0);
        }
    }

    private static DayOfWeek weekday(String s, List<String> problems) {
        try {
            return DayOfWeek.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("demand-notice.weekday: " + s);
            return DayOfWeek.MONDAY;
        }
    }
}
