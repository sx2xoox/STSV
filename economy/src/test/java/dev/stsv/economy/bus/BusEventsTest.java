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
        // a tag from before kinds had ids (stop;expires;finder;id;name) still reads, its kind found by name
        BusEvents.LostTag old = BusEvents.LostTag.decode("7;1000000;" + finder + ";" + id + ";지갑");
        assertNotNull(old);
        assertEquals("wallet", lo.kind(old.what()).id());
        assertEquals(0, old.found());
        BusEvents.LostTag full = new BusEvents.LostTag(3, 5_000_000, finder, "lunchbox", id, 1_000, "grandma");
        assertEquals(full, BusEvents.LostTag.decode(full.encode()));
    }

    static final long MIN = 60_000;

    @Test
    void lostKindsByPassenger() {
        BusEvents.Lost lo = events().lost();
        assertEquals(List.of("wallet", "umbrella", "phone", "lunchbox", "hat", "key", "dentures", "leash", "loveletter", "signedball"),
                List.copyOf(lo.kinds().keySet()));
        // only their passenger kinds leave these
        for (String k : List.of("dentures", "leash", "loveletter", "signedball")) assertEquals(0, lo.kinds().get(k).weightFor("resident"), k);
        assertTrue(lo.kinds().get("dentures").weightFor("grandma") > 0);
        assertTrue(lo.kinds().get("leash").weightFor("dogwalker") > 0);
        assertTrue(lo.kinds().get("loveletter").weightFor("couple") > 0);
        assertTrue(lo.kinds().get("signedball").weightFor("fan") > 0);
        assertEquals(0, lo.kinds().get("dentures").weightFor("fan"));
        // a sweep of rolls: a resident never leaves dentures, a grandma often does, a dog walker most often a leash
        Map<String, Integer> resident = new HashMap<>(), grandma = new HashMap<>(), dog = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            double roll = i / 1000.0;
            resident.merge(lo.pick("resident", roll).id(), 1, Integer::sum);
            grandma.merge(lo.pick("grandma", roll).id(), 1, Integer::sum);
            dog.merge(lo.pick("dogwalker", roll).id(), 1, Integer::sum);
        }
        assertFalse(resident.containsKey("dentures") || resident.containsKey("leash") || resident.containsKey("loveletter") || resident.containsKey("signedball"));
        assertTrue(resident.keySet().containsAll(List.of("wallet", "umbrella", "phone", "lunchbox", "hat", "key")));
        assertTrue(grandma.get("dentures") > 300, "a grandma leaves her dentures often: " + grandma);
        assertTrue(grandma.get("dentures") > grandma.get("wallet"));
        assertEquals(dog.values().stream().max(Integer::compare).orElseThrow(), dog.get("leash"));
        assertNotNull(lo.pick("resident", 0.999999));
        assertEquals("wallet", lo.pick("resident", 0).id());
        // the biggest rewards: a love letter, then a wallet among the everyday things
        long wallet = lo.kinds().get("wallet").reward();
        for (String k : List.of("umbrella", "phone", "lunchbox", "hat", "key")) assertTrue(lo.kinds().get(k).reward() < wallet, k);
        assertTrue(lo.kinds().get("loveletter").reward() > wallet);
        assertEquals("economy:lost_wallet", lo.kinds().get("wallet").item(false));
        assertEquals("economy:lost_wallet", lo.kinds().get("wallet").item(true), "only food has a spoiled look");
        assertEquals("economy:lost_lunchbox_spoiled", lo.kinds().get("lunchbox").item(true));
        assertEquals("economy:lost_signedball_keepsake", lo.kinds().get("signedball").keepsakeItem());
        assertEquals(Map.of("economy:honest_driver", 30, "economy:lost_found_chief", 100), lo.titles());
        assertEquals(List.of("economy:honest_driver"), lo.titlesReached(29, 30));
        assertEquals(List.of(), lo.titlesReached(30, 31));
        assertEquals(List.of("economy:honest_driver", "economy:lost_found_chief"), lo.titlesReached(0, 100));
        assertTrue(lo.ringFrom() >= 120 && lo.ringTo() <= 180);
    }

    @Test
    void lostRewards() {
        BusEvents.Lost lo = events().lost();
        UUID f = UUID.randomUUID();
        long t0 = 10 * MIN;
        BusEvents.LostKind wallet = lo.kinds().get("wallet"), umbrella = lo.kinds().get("umbrella"), lunch = lo.kinds().get("lunchbox");
        BusEvents.LostTag w = new BusEvents.LostTag(1, t0 + 30 * MIN, f, "wallet", UUID.randomUUID(), t0, "resident");
        // back within 10 minutes: × 1.5; later: the reward as it is
        BusEvents.LostPay fast = lo.pay(wallet, w, t0 + 9 * MIN, false), slow = lo.pay(wallet, w, t0 + 11 * MIN, false);
        assertTrue(fast.fast());
        assertEquals(Math.round(wallet.reward() * 1.5), fast.amount());
        assertFalse(slow.fast());
        assertEquals(wallet.reward(), slow.amount());
        assertEquals(slow, lo.pay(wallet, w, t0 + 11 * MIN, true), "rain changes nothing for a wallet");
        // an umbrella on a rainy day: × 2 (and × 1.5 when fast too)
        BusEvents.LostTag u = new BusEvents.LostTag(1, t0 + 30 * MIN, f, "umbrella", UUID.randomUUID(), t0, "resident");
        BusEvents.LostPay rainy = lo.pay(umbrella, u, t0 + 20 * MIN, true);
        assertTrue(rainy.rain());
        assertEquals(umbrella.reward() * 2, rainy.amount());
        assertEquals(Math.round(umbrella.reward() * 2 * 1.5), lo.pay(umbrella, u, t0 + MIN, true).amount());
        // a lunchbox: less and less, then spoiled after 15 minutes (a small reward)
        assertEquals(lunch.reward(), lunch.value(0));
        assertTrue(lunch.value(5) < lunch.reward() && lunch.value(10) < lunch.value(5));
        assertEquals(Math.round(lunch.reward() * lunch.decay()), lunch.value(14.999), 1);
        assertEquals(lunch.spoiledReward(), lunch.value(15));
        assertEquals(lunch.spoiledReward(), lunch.value(29));
        assertTrue(lunch.spoiledReward() < lunch.reward() / 4);
        BusEvents.LostTag l = new BusEvents.LostTag(1, t0 + 30 * MIN, f, "lunchbox", UUID.randomUUID(), t0, "office");
        BusEvents.LostPay spoiled = lo.pay(lunch, l, t0 + 20 * MIN, false);
        assertTrue(spoiled.spoiled());
        assertEquals(lunch.spoiledReward(), spoiled.amount());
        assertEquals(Math.round(lunch.value(5) * 1.5), lo.pay(lunch, l, t0 + 5 * MIN, false).amount(), "a quick lunchbox: decayed a little, × fast");
        // nobody came: a third of its reward at the lost and found (food as it ended up)
        assertEquals(Math.round(wallet.reward() * lo.orphan()), lo.orphanPay(wallet));
        assertEquals(Math.round(lunch.spoiledReward() * lo.orphan()), lo.orphanPay(lunch));
        assertEquals(0.34, lo.orphan(), 1e-9);
        // an older tag (no time it was left): no fast bonus
        BusEvents.LostTag old = new BusEvents.LostTag(1, t0 + 30 * MIN, f, "wallet", UUID.randomUUID());
        assertEquals(wallet.reward(), lo.pay(wallet, old, t0 + MIN, false).amount());
    }

    @Test
    void lostItemsTurnOverTime() {
        BusEvents.Lost lo = events().lost();
        UUID f = UUID.randomUUID();
        long t0 = 10 * MIN, end = t0 + 30 * MIN;
        BusEvents.LostTag lunch = new BusEvents.LostTag(1, end, f, "lunchbox", UUID.randomUUID(), t0, "office");
        assertEquals(BusEvents.LostState.FRESH, lo.state(lunch, t0 + 14 * MIN));
        assertEquals(BusEvents.LostState.SPOILED, lo.state(lunch, t0 + 15 * MIN));
        assertEquals(BusEvents.LostState.ORPHAN, lo.state(lunch, end));
        BusEvents.LostTag ball = new BusEvents.LostTag(1, end, f, "signedball", UUID.randomUUID(), t0, "fan");
        assertEquals(BusEvents.LostState.FRESH, lo.state(ball, end - 1));
        assertEquals(BusEvents.LostState.KEEPSAKE, lo.state(ball, end), "a signed ball nobody came for is the driver's keepsake");
        BusEvents.LostTag wallet = new BusEvents.LostTag(1, end, f, "wallet", UUID.randomUUID(), t0, "resident");
        assertEquals(BusEvents.LostState.FRESH, lo.state(wallet, t0 + 29 * MIN));
        assertEquals(BusEvents.LostState.ORPHAN, lo.state(wallet, end + MIN));
        assertEquals(20.0, wallet.minutes(t0 + 20 * MIN), 1e-9);
    }

    @Test
    void koreanParticles() {
        assertEquals("할머니가", BusEvents.josa("할머니", "이", "가"));
        assertEquals("커플이", BusEvents.josa("커플", "이", "가"));
        assertEquals("강아지 데려온 할아버지가", BusEvents.josa("강아지 데려온 할아버지", "이", "가"));
        assertEquals("지갑이요", BusEvents.josa("지갑", "이요", "요"));
        assertEquals("틀니요", BusEvents.josa("틀니", "이요", "요"));
        assertEquals("VIP이(가)", BusEvents.josa("VIP", "이", "가"));
    }

    @Test
    void anOlderBusYmlStillGivesLostKinds() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("reward", 600);
        y.set("things", List.of(Map.of("name", "지갑", "item", "minecraft:leather"), Map.of("name", "열쇠", "item", "minecraft:tripwire_hook")));
        BusEvents.Lost lo = BusEvents.lost(y);
        assertEquals(List.of("wallet", "key"), List.copyOf(lo.kinds().keySet()));
        assertEquals(600, lo.kinds().get("key").reward());
        assertEquals(BusEvents.lost(new YamlConfiguration()).kinds().keySet(), java.util.Set.of("wallet"));
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
