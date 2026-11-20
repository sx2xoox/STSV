package dev.stsv.economy.bus;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BusEventsTest {

    static BusEvents events() {
        return BusTest.spec().events();
    }

    @Test
    void crowdsSplitIntoTeamsOfFourAtMost() {
        assertArrayEquals(new int[]{4, 4, 4}, BusEvents.split(12, 4));
        assertArrayEquals(new int[]{4, 3, 3}, BusEvents.split(10, 4));
        assertArrayEquals(new int[]{3, 3, 3}, BusEvents.split(9, 4));
        assertArrayEquals(new int[]{4, 4}, BusEvents.split(8, 4));
        assertArrayEquals(new int[]{3}, BusEvents.split(3, 4));
        assertArrayEquals(new int[0], BusEvents.split(0, 4));
        for (int n = 1; n <= 40; n++) {
            int[] t = BusEvents.split(n, 4);
            int sum = 0;
            for (int x : t) {
                assertTrue(x >= 1 && x <= 4, "team of " + x);
                sum += x;
            }
            assertEquals(n, sum);
            assertEquals((n + 3) / 4, t.length);
        }
    }

    @Test
    void theConfigHasEveryEvent() {
        BusEvents e = events();
        assertEquals(4, e.team().size());
        assertEquals(800, e.team().bonus());
        // 출근 대란: weekdays only, 12 office workers in 3 teams, by 9 o'clock
        assertEquals("office", e.rush().type());
        assertEquals(9, e.rush().deadline());
        assertEquals(12, e.rush().min());
        assertEquals(12, e.rush().max());
        assertEquals(1, e.rush().timesOn(java.time.DayOfWeek.MONDAY));
        assertEquals(0, e.rush().timesOn(java.time.DayOfWeek.SUNDAY));
        // 야구 끝: twice on a weekend, 8–12 fans
        assertEquals(1, e.ballgame().timesOn(java.time.DayOfWeek.WEDNESDAY));
        assertEquals(2, e.ballgame().timesOn(java.time.DayOfWeek.SATURDAY));
        assertEquals(8, e.ballgame().min());
        assertEquals(12, e.ballgame().max());
        // 소풍날 still reads the old picnic section
        assertEquals(12, e.picnic().min());
        assertEquals(9, e.picnic().from());
        assertEquals(12, e.picnic().to());
        assertEquals(3, e.special().kinds().size());
        assertEquals(6, e.festival().day());
    }

    @Test
    void theOldPicnicSectionStillCounts() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml"));
        y.set("events.picnic", null);
        y.set("picnic.students", 10);
        y.set("picnic.from", 10);
        y.set("picnic.to", 13);
        List<String> problems = new ArrayList<>();
        BusEvents.Crowd p = BusSpec.parse(y, problems).events().picnic();
        assertEquals(List.of(), problems);
        assertEquals(10, p.min());
        assertEquals(10, p.max());
        assertEquals(10, p.from());
        assertEquals(13, p.to());
        assertTrue(p.enabled());
    }

    @Test
    void teamBonusIsPerTeamNotPerHead() {
        BusEvents e = events();
        BusEvents.Crowd rush = e.rush(), ball = e.ballgame(), picnic = e.picnic();
        assertEquals(1500, rush.teamBonus(e.team(), false, true, false));
        assertEquals(1000, rush.teamBonus(e.team(), false, false, false));
        assertEquals(0, rush.teamBonus(e.team(), true, true, false), "late: nothing");
        // the ball game uses the team's numbers; a won game × 1.5
        assertEquals(1200, ball.teamBonus(e.team(), false, true, false));
        assertEquals(1800, ball.teamBonus(e.team(), false, true, true));
        assertEquals(800, picnic.teamBonus(e.team(), false, false, false));
    }

    @Test
    void crowdMinutesAreFixedForADay() {
        int[] a = BusEvents.minutes(20_000, 7, 12, 23, 2), b = BusEvents.minutes(20_000, 7, 12, 23, 2);
        assertArrayEquals(a, b);
        assertEquals(2, a.length);
        assertTrue(a[0] < a[1]);
        for (int m : a) assertTrue(m >= 12 * 60 && m < 23 * 60);
        assertTrue(a[1] - a[0] >= 60, "two games an hour apart at least");
        int[] one = BusEvents.minutes(20_001, 7, 7, 8, 1);
        assertEquals(1, one.length);
        assertTrue(one[0] >= 420 && one[0] < 480);
        assertEquals(0, BusEvents.minutes(1, 1, 9, 9, 1).length);
    }

    @Test
    void specialPassengersPayForSkill() {
        BusEvents.Special sp = events().special();
        BusEvents.Kind patient = sp.kinds().get("patient"), celebrity = sp.kinds().get("celebrity"), exam = sp.kinds().get("examinee");
        // 응급 환자: within 70% of the on-time, 500 off for every jolt or bump
        assertEquals(3000, patient.pay(60, 100, 50, 0));
        assertEquals(2000, patient.pay(60, 100, 50, 2));
        assertEquals(0, patient.pay(60, 100, 50, 7));
        assertEquals(0, patient.pay(71, 100, 80, 0));
        assertEquals(70, patient.limit(100), 1e-9);
        // 연예인: mood 90 at least, no time limit
        assertEquals(4000, celebrity.pay(1000, 100, 92, 3));
        assertEquals(0, celebrity.pay(10, 100, 89, 0));
        assertEquals(0, celebrity.limit(100), 1e-9);
        // 수험생: late = nothing (not even the fare)
        assertEquals(2000, exam.pay(100, 100, 10, 0));
        assertEquals(0, exam.pay(101, 100, 100, 0));
        assertEquals(0, exam.failFare(), 1e-9);
        assertEquals(sp.kinds().get("patient"), sp.pick(0.0));
        assertEquals(sp.kinds().get("examinee"), sp.pick(1.0));
    }

    @Test
    void rescueGrowsWithTheWaitAndStops() {
        BusEvents.Rescue r = events().rescue();
        assertEquals(0, r.bonus(14.9));
        assertEquals(500 + 50 * 15, r.bonus(15));
        assertEquals(500 + 50 * 30, r.bonus(30.7));
        assertEquals(2500, r.bonus(120));
    }

    @Test
    void safeStreakPaysEveryFiveAndGrows() {
        BusEvents.Streak s = events().streak();
        assertEquals(0, s.bonus(0));
        assertEquals(0, s.bonus(4));
        assertEquals(300, s.bonus(5));
        assertEquals(0, s.bonus(6));
        assertEquals(400, s.bonus(10));
        assertEquals(500, s.bonus(15));
        assertEquals(1000, s.bonus(40));
        assertEquals(1000, s.bonus(500));
    }

    @Test
    void lineCompletionCountsStopsInOrder() {
        BusEvents.Course c = events().course();
        assertEquals(0, c.bonus(3));
        assertEquals(600, c.bonus(4));
        assertEquals(1500, c.bonus(10));
        assertEquals(3000, c.bonus(40));
        List<Integer> way = List.of(1, 2, 3, 4, 5), back = List.of(5, 4, 3, 2, 1);
        BusEvents.CourseRun run = new BusEvents.CourseRun();
        assertEquals(0, run.arrive(1, 1, way, 2));   // not from the first stop: nothing
        assertEquals(0, run.arrive(1, 1, way, 3));
        assertEquals(0, run.arrive(1, 1, way, 1));   // the first: a run starts
        assertEquals(0, run.arrive(1, 1, way, 2));
        assertEquals(0, run.arrive(1, 1, way, 2));   // pulled out and in again
        assertEquals(0, run.arrive(1, 1, way, 3));
        assertEquals(0, run.arrive(1, 1, way, 4));
        assertEquals(5, run.arrive(1, 1, way, 5));
        // turned round at the end: the way back is a new run
        assertEquals(0, run.arrive(1, -1, back, 5));
        assertEquals(0, run.arrive(1, -1, back, 4));
        assertEquals(0, run.arrive(1, -1, back, 2));   // skipped 3: broken
        assertEquals(0, run.arrive(1, -1, back, 1));
        assertEquals(0, run.progress());
        // another line in the middle breaks it too
        run.arrive(2, 1, way, 1);
        run.arrive(2, 1, way, 2);
        assertEquals(2, run.progress());
        run.arrive(3, 1, List.of(7, 8, 3), 8);
        assertEquals(0, run.progress());
    }

    @Test
    void lostItemsExpire() {
        UUID finder = UUID.randomUUID(), id = UUID.randomUUID();
        BusEvents.LostTag t = new BusEvents.LostTag(7, 1_000_000, finder, "지갑", id);
        BusEvents.LostTag back = BusEvents.LostTag.decode(t.encode());
        assertEquals(t, back);
        assertFalse(back.expired(999_999));
        assertTrue(back.expired(1_000_000));
        assertEquals(1, back.minutesLeft(999_999));
        assertEquals(0, back.minutesLeft(2_000_000));
        assertNull(BusEvents.LostTag.decode("garbage"));
        assertNull(BusEvents.LostTag.decode(null));
        assertEquals("우산;이름", BusEvents.LostTag.decode(new BusEvents.LostTag(1, 2, finder, "우산;이름", id).encode()).what());
        BusEvents.Lost lo = events().lost();
        assertEquals(30, lo.expire());
        assertEquals(600, lo.reward());
        assertFalse(lo.things().isEmpty());
    }

    @Test
    void festivalTimeAndRanking() {
        BusEvents.Festival f = events().festival();
        ZoneId kst = ZoneId.of("Asia/Seoul");
        ZonedDateTime sat = ZonedDateTime.of(2026, 11, 21, 20, 30, 0, 0, kst);   // a Saturday
        assertTrue(f.on(sat));
        assertEquals(30, f.minutesLeft(sat));
        assertFalse(f.on(sat.withHour(21)));
        assertFalse(f.on(sat.withHour(19).withMinute(59)));
        assertFalse(f.on(sat.minusDays(1)));
        assertEquals(1, f.point("stop"));
        assertEquals(5, f.point("team"));
        assertEquals(5, f.point("special"));
        assertEquals(0, f.point("fare"), "fares are no points");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        Map<UUID, int[]> pts = new HashMap<>(Map.of(a, new int[]{12}, b, new int[]{20}, c, new int[]{12}, d, new int[]{0}));
        Map<UUID, Long> at = Map.of(a, 50L, b, 10L, c, 40L, d, 1L);
        List<BusEvents.Score> top = BusEvents.ranking(pts, at, 3);
        assertEquals(List.of(b, c, a), top.stream().map(BusEvents.Score::player).toList(), "a tie: the earlier first");
        assertEquals(2, BusEvents.ranking(pts, at, 2).size());
        assertTrue(BusEvents.ranking(Map.of(d, new int[]{0}), at, 3).isEmpty());
        assertEquals(List.of(30000L, 20000L, 10000L), f.prizes());
    }
}
