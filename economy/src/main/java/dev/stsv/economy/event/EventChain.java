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

    /** The kinds that may come next. */
    public List<EventSpec> candidates(Map<String, EventSpec> specs, String last, boolean night) {
        List<EventSpec> out = new ArrayList<>();
        for (EventSpec s : specs.values()) {
            if (!s.chained() || s.weight() <= 0 || s.id().equals(last)) continue;
            if (night && s.hours() < longHours) continue;
            out.add(s);
        }
        return out;
    }

    /** The next kind, or null if none may come (then the chain waits another gap). */
    public EventSpec pick(Map<String, EventSpec> specs, String last, boolean night, Random r) {
        List<EventSpec> c = candidates(specs, last, night);
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
}
