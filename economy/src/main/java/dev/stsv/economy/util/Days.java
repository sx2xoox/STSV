package dev.stsv.economy.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * The season calendar. A day starts at the reset hour (05:00), not at midnight, so a late-night
 * session still counts as "today". Day 1 is the day the season started; week 1 is days 1-7.
 * Pure (no Bukkit): unit-tested.
 */
public final class Days {

    public static final long DAY = 86_400_000L;

    private final ZoneId zone;
    private final int resetHour;
    private final long start;
    private final int offset;

    /** start = epoch millis the season started (0 = no season: always day 1); offset = days added (testing). */
    public Days(ZoneId zone, int resetHour, long start, int offset) {
        this.zone = zone;
        this.resetHour = resetHour;
        this.start = start;
        this.offset = offset;
    }

    public ZoneId zone() { return zone; }
    public long start() { return start; }

    /** The calendar date a moment belongs to (before 05:00 = the previous date). */
    public LocalDate date(long t) {
        return Instant.ofEpochMilli(t).atZone(zone).minusHours(resetHour).toLocalDate();
    }

    public int day(long t) {
        if (start <= 0) return Math.max(1, 1 + offset);
        return (int) Math.max(1, ChronoUnit.DAYS.between(date(start), date(t)) + 1 + offset);
    }

    public int week(long t) {
        return (day(t) - 1) / 7 + 1;
    }

    /** When the day that contains t began (its 05:00). */
    public long dayStart(long t) {
        return date(t).atTime(resetHour, 0).atZone(zone).toInstant().toEpochMilli();
    }

    public long nextDayStart(long t) {
        return date(t).plusDays(1).atTime(resetHour, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /**
     * The moment of a clock time on a season day. Times before the reset hour belong to the end of that
     * day (01:00 of day 3 is the night after day 3's evening).
     */
    public long at(int day, LocalTime time) {
        LocalDate first = start <= 0 ? date(System.currentTimeMillis()).minusDays(Math.max(0, offset)) : date(start).minusDays(offset);
        LocalDate d = first.plusDays(day - 1L);
        if (time.getHour() < resetHour) d = d.plusDays(1);
        return ZonedDateTime.of(d, time, zone).toInstant().toEpochMilli();
    }

    public DayOfWeek weekday(long t) {
        return date(t).getDayOfWeek();
    }
}
