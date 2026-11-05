package dev.stsv.economy;

import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.land.Claim;
import dev.stsv.economy.land.ClaimIndex;
import dev.stsv.economy.land.LandRules;
import dev.stsv.economy.request.RequestSpec;
import dev.stsv.economy.event.EventSpec;
import dev.stsv.economy.shop.ShopCatalog;
import dev.stsv.economy.shop.PriceModel;
import dev.stsv.economy.stats.Report;
import dev.stsv.economy.trade.TradeState;
import dev.stsv.economy.util.Days;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of the economy server, without a server. */
class RulesTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static long t(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------------------------------ calendar

    @Test
    void aDayStartsAtFiveInTheMorning() {
        long first = t(2026, 11, 4, 20, 0);             // the server first ran Wednesday 20:00 (day 1, week 1)
        Days d = new Days(KST, 5, first, 0);
        assertEquals(1, d.day(first));
        assertEquals(1, d.day(t(2026, 11, 5, 4, 59)), "after midnight is still the same day");
        assertEquals(2, d.day(t(2026, 11, 5, 5, 0)));
        assertEquals(t(2026, 11, 5, 5, 0), d.nextDayStart(first));
        assertEquals(t(2026, 11, 4, 5, 0), d.dayStart(first));
        assertEquals(DayOfWeek.MONDAY, d.weekday(t(2026, 11, 10, 3, 0)), "Tuesday 03:00 is still Monday");
        assertEquals(1, new Days(KST, 5, 0, 0).day(first), "not started yet: day 1");
    }

    @Test
    void aWeekStartsOnMondayAndNeverEnds() {
        long first = t(2026, 11, 4, 20, 0);             // a Wednesday
        Days d = new Days(KST, 5, first, 0);
        assertEquals(1, d.week(first));
        assertEquals(1, d.week(t(2026, 11, 9, 4, 59)), "Monday before 05:00 is still last week");
        assertEquals(2, d.week(t(2026, 11, 9, 5, 0)), "a short first week: the calendar week, not 7 days from day 1");
        assertEquals(6, d.day(t(2026, 11, 9, 5, 0)));
        assertEquals(54, d.week(t(2027, 11, 8, 5, 0)), "no end: 53 Mondays later is week 54");
        assertEquals(t(2026, 11, 9, 5, 0), d.next(DayOfWeek.MONDAY, first));
        assertEquals(t(2026, 11, 16, 5, 0), d.next(DayOfWeek.MONDAY, t(2026, 11, 9, 5, 0)), "from a Monday: the next one");
        // testing offset: the calendar moves, real times do not
        Days later = new Days(KST, 5, first, 5);
        assertEquals(6, later.day(first));
        assertEquals(2, later.week(first), "day 1 + 5 = Monday of week 2");
        assertEquals(DayOfWeek.MONDAY, later.weekday(first));
        assertEquals(t(2026, 11, 4, 5, 0), later.dayStart(first));
    }

    @Test
    void theEventsAndTheDemandNotice() {
        List<String> problems = new ArrayList<>();
        Map<String, EventSpec> events = EventSpec.parse(ContentTest.yaml("events.yml"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("shortage", "crash", "harvest", "merchant", "tax_audit", "world_boss"), List.copyOf(events.keySet()));
        assertEquals(24, events.get("shortage").hours(), "품귀 lasts a day");
        assertEquals(3.0, events.get("shortage").number("multiplier", 0));
        assertFalse(events.containsKey("lawless"), "무법 지대 is gone");
        ShopCatalog shop = ShopCatalog.parse(ContentTest.yaml("shop.yml"), problems);
        assertEquals(new ShopCatalog.DemandNotice(DayOfWeek.MONDAY, 3, 1.5), shop.demand());
        // bad entries are reported, not crashing
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("events.x.hours", 0);
        bad.set("demand-notice.weekday", "someday");
        List<String> p2 = new ArrayList<>();
        EventSpec.parse(bad, p2);
        ShopCatalog.parse(bad, p2);
        assertEquals(2, p2.size(), p2.toString());
    }

    // ------------------------------------------------------------------------------------------ prices

    @Test
    void sellPriceFallsWithWhatTheServerSold() {
        double q = 32, base = 10, floor = 0.35;
        assertEquals(10, PriceModel.unit(base, 0, q, 1, floor, 1), 1e-9);
        assertEquals(10 * Math.exp(-1), PriceModel.unit(base, 32, q, 1, floor, 1), 1e-9, "S = Q: 37% of the base");
        assertEquals(3.5, PriceModel.unit(base, 1000, q, 1, floor, 1), 1e-9, "never below the floor");
        assertTrue(PriceModel.atFloor(base, 1000, q, 1, floor));
        assertFalse(PriceModel.atFloor(base, 10, q, 1, floor));
        assertEquals(10 * Math.exp(-1), PriceModel.unit(base, 32 * 5, q, 5, floor, 1), 1e-9, "more players, more room");
        assertEquals(15, PriceModel.unit(base, 0, q, 1, floor, 1.5), 1e-9, "수요 고시 x1.5");
        // selling 64 at once = selling them one by one
        double once = PriceModel.total(base, 0, q, 3, floor, 1, 64), oneByOne = 0;
        for (int k = 0; k < 64; k++) oneByOne += PriceModel.unit(base, k, q, 3, floor, 1);
        assertEquals(oneByOne, once, 1e-9);
        assertTrue(once < 64 * base);
    }

    // ------------------------------------------------------------------------------------------ jobs

    @Test
    void levelsAreCappedByTheWeekAndTheRestIsMastery() {
        JobSpec spec = JobSpec.parse(ContentTest.yaml("jobs.yml"), new ArrayList<>());
        assertEquals(1, spec.levelFor(0));
        assertEquals(1, spec.levelFor(999));
        assertEquals(2, spec.levelFor(1000));
        assertEquals(3, spec.levelFor(5000));
        // week 1 (cap 1): XP stops one short of Lv2, the rest is mastery
        double[] g = spec.gain(900, 300, 1);
        assertEquals(999, g[0], 1e-9);
        assertEquals(201, g[1], 1e-9);
        g = spec.gain(999, 50, 1);
        assertEquals(999, g[0], 1e-9);
        assertEquals(50, g[1], 1e-9);
        // the cap rises: XP counts again
        g = spec.gain(999, 50, 2);
        assertEquals(1049, g[0], 1e-9);
        assertEquals(0, g[1], 1e-9);
        g = spec.gain(2990, 100, 3);
        assertEquals(3090, g[0], 1e-9, "no ceiling at the top level");
        JobSpec.Job miner = spec.jobs().get("miner");
        assertEquals(10, miner.xp("break", "minecraft:diamond_ore"), 1e-9);
        assertEquals(0, miner.xp("break", "minecraft:stone"), 1e-9);
        assertEquals(0.30, miner.passive("extra", 3), 1e-9);
        assertEquals(0.30, miner.passive("extra", 5), 1e-9, "past the table: last value");
        assertEquals(0, miner.passive("extra", 0), 1e-9, "no level, no passive");
        assertEquals(List.of("economy:rough_ruby"), miner.drops("minecraft:redstone_ore"));
        assertEquals(3, spec.jobs().get("fisher").drops("tier3").size());
        JobSpec.Product pick = spec.products().get("economy:reinforced_pickaxe");
        assertEquals("blacksmith", pick.job());
        assertEquals(3, pick.enchants().get("efficiency"));
        assertTrue(spec.products().get("economy:royal_feast").quality());
    }

    @Test
    void machinesMatchTheirRecipesByInputsAndLevel() {
        Map<String, MachineSpec> m = MachineSpec.parse(ContentTest.yaml("machines.yml"), new ArrayList<>());
        MachineSpec crusher = m.get("crusher");
        assertEquals("economy:iron_dust", crusher.match(Map.of("minecraft:raw_iron", 1), 1).output());
        assertNull(crusher.match(Map.of("economy:rough_ruby", 1), 2), "gem cutting is Lv3");
        assertEquals("economy:ruby", crusher.match(Map.of("economy:rough_ruby", 1), 3).output());
        assertNull(crusher.match(Map.of("minecraft:dirt", 64), 3));
        MachineSpec table = m.get("reinforcing_table");
        assertNull(table.match(Map.of("minecraft:iron_ingot", 2), 2), "needs coal too");
        assertEquals("economy:steel_ingot", table.match(Map.of("minecraft:iron_ingot", 5, "minecraft:coal", 9), 2).output());
    }

    // ------------------------------------------------------------------------------------------ trading

    @Test
    void aTradeNeedsBothSidesTwiceAndAnyChangeStartsOver() {
        TradeState s = new TradeState();
        assertFalse(s.press(TradeState.Side.A));          // A ready
        assertFalse(s.press(TradeState.Side.A));          // A takes it back (B not ready)
        assertFalse(s.ready(TradeState.Side.A));
        s.press(TradeState.Side.A);
        s.press(TradeState.Side.B);
        assertTrue(s.bothReady());
        assertFalse(s.press(TradeState.Side.A));          // A confirms
        s.setMoney(TradeState.Side.B, 500, 1000);          // B changes the offer...
        assertFalse(s.ready(TradeState.Side.A), "...so A must agree again");
        assertFalse(s.confirmed(TradeState.Side.A));
        s.press(TradeState.Side.A);
        s.press(TradeState.Side.B);
        assertFalse(s.press(TradeState.Side.A));
        assertTrue(s.press(TradeState.Side.B), "both confirmed: the trade happens");
        assertEquals(1000, s.setMoney(TradeState.Side.A, 5000, 1000), "cannot offer more than you have");
        assertEquals(0, s.setMoney(TradeState.Side.A, -5, 1000));
    }

    @Test
    void auctionRaisesAndLastMinuteBidsExtend() {
        Auction a = new Auction(1, UUID.randomUUID(), null, new byte[0], "x", 1, 100, 100, false, 1, 0, null, 1_000_000, 0);
        assertEquals(100, a.minNext(0.05), "first bid: the start price");
        a.bid = 100;
        a.bidder = UUID.randomUUID();
        assertEquals(105, a.minNext(0.05));
        a.bid = 10;
        assertEquals(11, a.minNext(0.05), "at least +1");
        assertFalse(a.extendIfLate(1_000_000 - 120_000, 60_000));
        assertTrue(a.extendIfLate(1_000_000 - 30_000, 60_000));
        assertEquals(1_000_000 - 30_000 + 60_000, a.ends);
        assertEquals(7, TradeRules.fee(100, 0.07));
        assertEquals(1, TradeRules.fee(3, 0.05), "at least 1");
        assertEquals(0, TradeRules.fee(0, 0.05));
    }

    // ------------------------------------------------------------------------------------------ land

    @Test
    void claimsAreFoundAcrossChunksAndDoNotOverlap() {
        ClaimIndex idx = new ClaimIndex();
        UUID a = UUID.randomUUID();
        Claim c = new Claim(1, a, "world", -20, -5, 10, 30, 0);   // spans chunks -2..0 by -1..1
        idx.add(c);
        assertSame(c, idx.at("world", -20, -5));
        assertSame(c, idx.at("world", 10, 30));
        assertSame(c, idx.at("world", -1, 0));
        assertNull(idx.at("world", 11, 30));
        assertNull(idx.at("nether", 0, 0));
        assertNotNull(idx.overlapping("world", 10, 30, 40, 40, null));
        assertNull(idx.overlapping("world", 11, 31, 40, 40, null));
        assertNull(idx.overlapping("world", -20, -5, 10, 30, c), "itself is ignored (resizing)");
        assertEquals(31L * 36, c.area());
        idx.update(c, -20, -5, 50, 30);
        assertSame(c, idx.at("world", 45, 0), "re-filed under the new chunks");
        Claim b = new Claim(2, a, "world", 100, 100, 104, 104, 1);
        idx.add(b);
        assertEquals(List.of(c, b), idx.of(a), "oldest first (the newest is released first)");
        assertEquals(c.area() + 25, idx.area(a));
        idx.remove(c);
        assertNull(idx.at("world", 0, 0));
    }

    @Test
    void propertyTaxIsProgressive() {
        LandRules r = LandRules.parse(ContentTest.yaml("land.yml"));
        assertEquals(400, r.freeArea());
        assertEquals(20, r.tax(400, 1));
        assertEquals(125, r.tax(2500, 1));
        assertEquals(125 + 50, r.tax(3000, 1), "above 2,500: double");
        assertEquals(40, r.tax(400, 2), "세무 조사");
        assertEquals(1, r.tax(1, 1), "rounded up");
        assertEquals(0, r.tax(0, 1));
    }

    // ------------------------------------------------------------------------------------------ requests, report

    @Test
    void theDailyBoardIsTheSameAllDayAndFitsTheDay() {
        RequestSpec spec = RequestSpec.parse(ContentTest.yaml("requests.yml"), new ArrayList<>());
        List<RequestSpec.Request> a = spec.draw(5, 42, id -> 10), b = spec.draw(5, 42, id -> 10);
        assertEquals(a, b, "same day, same board (also after a restart)");
        assertEquals(8, a.size());
        assertEquals(8, new HashSet<>(a.stream().map(RequestSpec.Request::item).toList()).size(), "no item twice");
        assertTrue(spec.draw(2, 42, id -> 10).stream().noneMatch(r -> r.item().equals("economy:steel_ingot")), "Lv2 goods not on day 2");
        for (RequestSpec.Request r : spec.draw(20, 42, id -> 2)) {
            RequestSpec.Entry e = spec.pool().stream().filter(x -> x.item().equals(r.item())).findFirst().orElseThrow();
            double unit = e.value() > 0 ? e.value() : 2;
            assertEquals(Math.max(1, Math.round(unit * r.amount() * 1.3)), r.reward(), r.item());
            assertTrue(r.amount() >= e.min() && r.amount() <= e.max());
        }
        // the weekly shared goal goes round the list for ever
        assertEquals(4, spec.community().size());
        assertEquals(spec.community().get(0), spec.communityFor(1));
        assertEquals(spec.community().get(3), spec.communityFor(4));
        assertEquals(spec.communityFor(1), spec.communityFor(5));
        assertEquals(spec.communityFor(2), spec.communityFor(102));
    }

    @Test
    void theReportFlagsWhatIsOutOfRange() {
        Report ok = new Report(3, 105_000, 100_000, 10_000, 2_000, 0.45, 300, 900, 1, 10, 0.20);
        assertTrue(ok.warnings().isEmpty(), ok.warnings().toString());
        Report bad = new Report(10, 120_000, 100_000, 10_000, 1_000, 0.7, 100, 900, 4, 10, 0.30);
        assertEquals(List.of("report.warn-growth-high", "report.warn-ratio-low", "report.warn-gini", "report.warn-gap", "report.warn-floor"), bad.warnings());
        double[] q = Report.quartiles(List.of(new long[]{100, 3600}, new long[]{400, 3600}, new long[]{900, 3600}, new long[]{1600, 3600},
                new long[]{99999, 60}));
        assertEquals(100, q[0], 1e-9);
        assertEquals(1600, q[1], 1e-9, "players under 10 minutes are left out");
    }
}
