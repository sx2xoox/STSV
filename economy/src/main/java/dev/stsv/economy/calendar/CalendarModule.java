package dev.stsv.economy.calendar;

import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Days;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's own calendar. The economy server runs without end: day 1 is the day it first ran, a
 * day turns at the reset hour (05:00) and a week on Monday at that hour. At each turn the other
 * modules get their daily (and weekly) call: price recovery, new requests, taxes, the ranking.
 */
public final class CalendarModule extends EcoModule {

    private static final String EPOCH = "calendar.epoch", OFFSET = "calendar.offset", LAST_DAY = "calendar.last-day", LAST_WEEK = "calendar.last-week";

    private final List<DailyListener> daily = new ArrayList<>();
    private final List<WeeklyListener> weekly = new ArrayList<>();

    public CalendarModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "eco-calendar";
    }

    @Override
    protected void start() {
        eco.kv().removePrefix("season.");   // left over from the 4-week season server
        if (eco.kv().getLong(EPOCH, 0) <= 0) {
            eco.kv().set(EPOCH, now());
            // the first start is not a "new week": nothing happened in a week 0
            eco.kv().set(LAST_WEEK, days().week(now()));
        }
        every(200L, this::tick);
    }

    @Override
    public void reload() {
        eco.reloadRules();   // the first module: trade.yml and the messages before the others reload
    }

    // ------------------------------------------------------------------------------------------

    public Days days() {
        return new Days(ZoneId.of(eco.getConfig().getString("time.zone")), eco.getConfig().getInt("time.reset-hour"),
                eco.kv().getLong(EPOCH, 0), (int) eco.kv().getLong(OFFSET, 0));
    }

    public int day() {
        return days().day(now());
    }

    public int week() {
        return days().week(now());
    }

    public void onDaily(DailyListener l) {
        daily.add(l);
    }

    public void onWeekly(WeeklyListener l) {
        weekly.add(l);
    }

    /** Testing: moves the calendar a day forward and runs that day's turn now. */
    public void skipDay() {
        eco.kv().set(OFFSET, eco.kv().getLong(OFFSET, 0) + 1);
        tick();
    }

    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = now();
        Days d = days();
        int day = d.day(now);
        // once per calendar day (an offset change counts as a new day too)
        if (eco.kv().getLong(LAST_DAY, 0) == day) return;
        eco.kv().set(LAST_DAY, day);
        long dayStart = d.dayStart(now);
        int week = d.week(now);
        if (eco.kv().getLong(LAST_WEEK, 0) < week) {
            eco.kv().set(LAST_WEEK, week);
            eco.getLogger().info("새 주: " + week + "주차");
            for (WeeklyListener l : weekly) {
                try {
                    l.newWeek(week, dayStart);
                } catch (RuntimeException e) {
                    eco.getLogger().log(java.util.logging.Level.SEVERE, "주간 처리 오류 (" + l.getClass().getSimpleName() + ")", e);
                }
            }
        }
        eco.getLogger().info("새 날: " + day + "일차");
        for (DailyListener l : daily) {
            try {
                l.newDay(day, dayStart);
            } catch (RuntimeException e) {
                eco.getLogger().log(java.util.logging.Level.SEVERE, "일일 초기화 오류 (" + l.getClass().getSimpleName() + ")", e);
            }
        }
    }

    /** /관리자 초기화 경제서버: the calendar starts again at day 1. */
    public void reset() {
        eco.kv().removePrefix("calendar.");
        eco.kv().set(EPOCH, now());
        eco.kv().set(LAST_WEEK, days().week(now()));
    }
}
