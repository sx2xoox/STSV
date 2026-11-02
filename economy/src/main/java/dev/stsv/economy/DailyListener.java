package dev.stsv.economy;

/** Called once per season day at the reset hour (05:00), in module order. */
public interface DailyListener {
    void newDay(int day, long dayStart);
}
