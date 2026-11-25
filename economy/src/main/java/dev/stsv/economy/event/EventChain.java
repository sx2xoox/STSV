package dev.stsv.economy.event;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * How events follow each other (events.yml "chain"): one at a time; when it ends, the next one starts
 * gapHours later. The next kind is drawn by weight, never the same kind twice in a row, and an event
 * that would start in the small hours (nightFrom to nightTo) is one of the long ones, so a short event
 * does not come and go while everyone sleeps. Pure (unit-tested).
 */
public record EventChain(double gapHours, int nightFrom, int nightTo, double longHours) {

    public static EventChain parse(ConfigurationSection c) {
        if (c == null) return new EventChain(6, 1, 9, 24);
        return new EventChain(c.getDouble("gap-hours", 6), c.getInt("night-from", 1), c.getInt("night-to", 9), c.getDouble("long-hours", 24));
    }

    public long gapMillis() {
        return (long) (gapHours * 3_600_000L);
    }

    public boolean night(long t, ZoneId zone) {
        int h = Instant.ofEpochMilli(t).atZone(zone).getHour();
        return nightFrom <= nightTo ? h >= nightFrom && h < nightTo : h >= nightFrom || h < nightTo;
    }

    /** The background kinds that may come next. */
    public List<EventSpec> candidates(Map<String, EventSpec> specs, String last, boolean night) {
        return candidates(specs, last, night, EventSpec.BACKGROUND);
    }

    /** The kinds of a line that may come next. */
    public List<EventSpec> candidates(Map<String, EventSpec> specs, String last, boolean night, String line) {
        List<EventSpec> out = new ArrayList<>();
        for (EventSpec s : specs.values()) {
            if (!s.chained() || !s.line().equals(line) || s.weight() <= 0 || s.id().equals(last)) continue;
            if (night && s.hours() < longHours) continue;
            out.add(s);
        }
        return out;
    }

    /** The next background kind, or null if none may come (then the chain waits another gap). */
    public EventSpec pick(Map<String, EventSpec> specs, String last, boolean night, Random r) {
        return pick(specs, last, night, EventSpec.BACKGROUND, r);
    }

    /** The next kind of a line, or null if none may come. */
    public EventSpec pick(Map<String, EventSpec> specs, String last, boolean night, String line, Random r) {
        List<EventSpec> c = candidates(specs, last, night, line);
        double total = 0;
        for (EventSpec s : c) total += s.weight();
        if (total <= 0) return null;
        double x = r.nextDouble() * total;
        for (EventSpec s : c) {
            x -= s.weight();
            if (x < 0) return s;
        }
        return c.getLast();
    }

    /**
     * The 행사 line (events.yml gatherings): one at a time beside the background event, the next gapHours after the last one
     * ended, started only between from and to o'clock (when people are on). Pure (unit-tested).
     */
    public record Gatherings(double gapHours, int from, int to) {

        public static Gatherings parse(ConfigurationSection c) {
            if (c == null) return new Gatherings(4, 12, 23);
            return new Gatherings(c.getDouble("gap-hours", 4), c.getInt("from", 12), c.getInt("to", 23));
        }

        public long gapMillis() {
            return (long) (gapHours * 3_600_000L);
        }

        /** May a gathering start at this time? */
        public boolean open(long t, ZoneId zone) {
            int h = Instant.ofEpochMilli(t).atZone(zone).getHour();
            return from <= to ? h >= from && h < to : h >= from || h < to;
        }
    }
}
