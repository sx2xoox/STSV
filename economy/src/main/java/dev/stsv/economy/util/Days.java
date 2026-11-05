package dev.stsv.economy.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * The server calendar. A day starts at the reset hour (05:00), not at midnight, so a late-night
 * session still counts as "today". Day 1 is the day the economy server first ran; weeks start on
 * Monday at the reset hour, and week 1 is the week that contains day 1. Pure (no Bukkit): unit-tested.
 */
public final class Days {

    public static final long DAY = 86_400_000L;

    private final ZoneId zone;
    private final int resetHour;
    private final long epoch;
    private final int offset;

    /** epoch = when the server first ran (0 = now is day 1); offset = days added to the calendar (testing). */
    public Days(ZoneId zone, int resetHour, long epoch, int offset) {
        this.zone = zone;
        this.resetHour = resetHour;
        this.epoch = epoch;
        this.offset = offset;
    }

    public ZoneId zone() { return zone; }
    public long epoch() { return epoch; }

    /** The real calendar date a moment belongs to (before 05:00 = the previous date). */
    private LocalDate realDate(long t) {
        return Instant.ofEpochMilli(t).atZone(zone).minusHours(resetHour).toLocalDate();
    }

    /** The date the calendar shows for a moment (the testing offset included). */
    public LocalDate date(long t) {
        return realDate(t).plusDays(offset);
    }

    private LocalDate first(long t) {
        return epoch <= 0 ? realDate(t) : realDate(epoch);
    }

    public int day(long t) {
        return (int) Math.max(1, ChronoUnit.DAYS.between(first(t), date(t)) + 1);
    }

    public int week(long t) {
        LocalDate monday0 = first(t).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monday = date(t).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return (int) Math.max(1, ChronoUnit.WEEKS.between(monday0, monday) + 1);
    }

    /** When the day that contains t began (its 05:00, real time). */
    public long dayStart(long t) {
        return realDate(t).atTime(resetHour, 0).atZone(zone).toInstant().toEpochMilli();
    }

    public long nextDayStart(long t) {
        return realDate(t).plusDays(1).atTime(resetHour, 0).atZone(zone).toInstant().toEpochMilli();
    }

    /** The next start of a day that falls on this weekday (real time), strictly after t's day. */
    public long next(DayOfWeek weekday, long t) {
        long at = nextDayStart(t);
        while (date(at).getDayOfWeek() != weekday) at = nextDayStart(at);
        return at;
    }

    public DayOfWeek weekday(long t) {
        return date(t).getDayOfWeek();
    }
}
