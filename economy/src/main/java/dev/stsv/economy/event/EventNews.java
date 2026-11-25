package dev.stsv.economy.event;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * What the running events do to other systems, and the bits of /소식 that are arithmetic (README "사건"). Pure (unit-tested):
 * {@link EventModule} feeds it the running events and the clock.
 */
public final class EventNews {

    private EventNews() {}

    /**
     * The product of one number setting (machine-speed, school-factor, ...) over the events running at now; job != null: only
     * events whose jobs list names it. 1 when none says anything: several events multiply cleanly.
     */
    public static double product(Collection<ActiveEvent> events, long now, String key, String job) {
        double v = 1;
        for (ActiveEvent e : events) {
            if (!e.runningAt(now) || e.param(key) == null) continue;
            if (job != null && !EventSpec.list(e.param("jobs")).contains(job)) continue;
            v *= e.number(key, 1);
        }
        return v;
    }

    /** A job machine's speed factor now (풍년 · 광산 호황: 1.4 = its batches take 1/1.4 of the time). */
    public static double machineSpeed(Collection<ActiveEvent> events, long now, String job) {
        return job == null ? 1 : Math.max(0.1, product(events, now, "machine-speed", job));
    }

    /** The fish schools' factor now (태풍 0: none come up, no net hauls at sea; 만선 2: twice as many, twice as often). */
    public static double schoolFactor(Collection<ActiveEvent> events, long now) {
        return Math.max(0, product(events, now, "school-factor", null));
    }

    /** The /소식 "지난 결과" log (newest first, one per line): this entry in front, at most max kept. */
    public static String appendLog(String log, String entry, int max) {
        List<String> out = new ArrayList<>();
        out.add(entry.replace("\n", " "));
        if (log != null && !log.isEmpty()) for (String l : log.split("\n")) if (!l.isBlank() && out.size() < max) out.add(l);
        return String.join("\n", out);
    }

    /** The log's lines, newest first. */
    public static List<String> lines(String log) {
        List<String> out = new ArrayList<>();
        if (log != null) for (String l : log.split("\n")) if (!l.isBlank()) out.add(l);
        return out;
    }

    /** In how many days the next market day is (0 = today), looking at most `within` days ahead; -1 if none. */
    public static int daysToMarket(IntPredicate marketDay, int today, int within) {
        for (int k = 0; k <= within; k++) if (marketDay.test(today + k)) return k;
        return -1;
    }

    /**
     * The next start of a weekly thing (the bus 대축제: dayOfWeek 1 Monday .. 7 Sunday, from o'clock) at or after now; if it is on
     * right now (until `to` o'clock) the start of this one.
     */
    public static long nextWeekly(long now, ZoneId zone, int dayOfWeek, int from, int to) {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone);
        ZonedDateTime day = t.toLocalDate().atStartOfDay(zone);
        for (int k = 0; k <= 7; k++) {
            ZonedDateTime d = day.plusDays(k);
            if (d.getDayOfWeek().getValue() != dayOfWeek) continue;
            ZonedDateTime start = d.plusHours(from), end = d.plusHours(to);
            if (end.toInstant().toEpochMilli() > now) return start.toInstant().toEpochMilli();
        }
        return day.plusDays(7).plusHours(from).toInstant().toEpochMilli();
    }

    /** The next fishing tournament's start (on the hour) after now. */
    public static long nextHour(long now, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1);
        return t.toInstant().toEpochMilli();
    }
}
