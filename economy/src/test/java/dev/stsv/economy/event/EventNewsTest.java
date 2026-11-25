package dev.stsv.economy.event;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 사건 (README "사건"): what running events do to the machines and the sea, and the arithmetic of /소식. */
class EventNewsTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static long t(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST).toInstant().toEpochMilli();
    }

    static ActiveEvent ev(String type, long from, long to, Map<String, String> p) {
        return new ActiveEvent(1, type, from, to, p);
    }

    @Test
    void harvestAndMiningBoomSpeedUpTheirJobsMachinesAndMultiplyCleanly() {
        ActiveEvent harvest = ev("harvest", 0, 100, Map.of("jobs", "[farmer, fisher]", "machine-speed", "1.3"));
        ActiveEvent boom = ev("mining_boom", 0, 100, Map.of("jobs", "[miner, blacksmith]", "machine-speed", "1.4"));
        ActiveEvent shortage = ev("shortage", 0, 100, Map.of("jobs", "cook", "dock-factor", "1.12"));   // names a job, but no machine-speed
        List<ActiveEvent> all = List.of(harvest, boom, shortage);
        assertEquals(1.3, EventNews.machineSpeed(all, 50, "farmer"), 1e-12);
        assertEquals(1.3, EventNews.machineSpeed(all, 50, "fisher"), 1e-12);
        assertEquals(1.4, EventNews.machineSpeed(all, 50, "miner"), 1e-12);
        assertEquals(1, EventNews.machineSpeed(all, 50, "cook"), 1e-12, "품귀 names the job only for the stock market");
        assertEquals(1, EventNews.machineSpeed(all, 50, null), 1e-12, "the press: no job");
        assertEquals(1, EventNews.machineSpeed(all, 100, "farmer"), 1e-12, "over");
        // two at once (an admin's): they multiply
        ActiveEvent more = ev("harvest", 0, 100, Map.of("jobs", "farmer", "machine-speed", "1.5"));
        assertEquals(1.95, EventNews.machineSpeed(List.of(harvest, more), 50, "farmer"), 1e-12);
        // 효과 2배 is gone: nothing sets job-effect any more
        assertEquals(1, EventNews.product(all, 50, "job-effect", "farmer"), 1e-12);
    }

    @Test
    void theTyphoonStopsTheSeaAndTheFullCatchDoublesIt() {
        ActiveEvent typhoon = ev("typhoon", 0, 100, Map.of("school-factor", "0"));
        ActiveEvent full = ev("full_catch", 100, 200, Map.of("school-factor", "2"));
        assertEquals(0, EventNews.schoolFactor(List.of(typhoon, full), 50), 1e-12);
        assertEquals(2, EventNews.schoolFactor(List.of(typhoon, full), 150), 1e-12);
        assertEquals(1, EventNews.schoolFactor(List.of(typhoon, full), 250), 1e-12);
        assertEquals(0, dev.stsv.economy.fishing.SeaEvents.extraRoom(1, 3));
        assertEquals(3, dev.stsv.economy.fishing.SeaEvents.extraRoom(2, 3), "만선: as many again as fish.yml allows");
        assertEquals(0, dev.stsv.economy.fishing.SeaEvents.extraRoom(0, 3), "태풍: none");
    }

    @Test
    void theLogKeepsTheLastTenNewestFirst() {
        String log = "";
        for (int i = 1; i <= 12; i++) log = EventNews.appendLog(log, "사건 " + i, 10);
        List<String> lines = EventNews.lines(log);
        assertEquals(10, lines.size());
        assertEquals("사건 12", lines.getFirst());
        assertEquals("사건 3", lines.getLast());
        assertEquals(List.of("한 줄 둘째 줄"), EventNews.lines(EventNews.appendLog(null, "한 줄\n둘째 줄", 10)), "one line each");
    }

    @Test
    void theScheduleOfTheOtherGatherings() {
        // 장날 every 5 days (bus.yml market.every)
        assertEquals(0, EventNews.daysToMarket(d -> d % 5 == 0, 10, 30), "today");
        assertEquals(3, EventNews.daysToMarket(d -> d % 5 == 0, 12, 30));
        assertEquals(-1, EventNews.daysToMarket(d -> false, 12, 30));
        // 버스 대축제: Saturday (6) 20~21 o'clock. 2026-11-25 is a Wednesday
        assertEquals(t(2026, 11, 28, 20, 0), EventNews.nextWeekly(t(2026, 11, 25, 12, 0), KST, 6, 20, 21));
        assertEquals(t(2026, 11, 28, 20, 0), EventNews.nextWeekly(t(2026, 11, 28, 20, 30), KST, 6, 20, 21), "on now: this one");
        assertEquals(t(2026, 12, 5, 20, 0), EventNews.nextWeekly(t(2026, 11, 28, 21, 0), KST, 6, 20, 21), "just over: next week");
        // 낚시대회: every hour on the hour
        assertEquals(t(2026, 11, 25, 13, 0), EventNews.nextHour(t(2026, 11, 25, 12, 7), KST));
        assertEquals(t(2026, 11, 25, 13, 0), EventNews.nextHour(t(2026, 11, 25, 12, 0), KST));
    }
}
