package dev.stsv.economy.calendar;

/**
 * Called once when a new week starts (Monday at the reset hour), before that day's {@link DailyListener}s.
 * week = the week that just started; the one that ended is week - 1.
 */
public interface WeeklyListener {
    void newWeek(int week, long weekStart);
}
