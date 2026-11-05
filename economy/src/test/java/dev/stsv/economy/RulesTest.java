package dev.stsv.economy;

import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.job.JobSpec;
import dev.stsv.economy.job.MachineSpec;
import dev.stsv.economy.stock.NewsSpec;
import dev.stsv.economy.stock.StockGraph;
import dev.stsv.economy.stock.StockSpec;
import dev.stsv.economy.land.ChunkPos;
import dev.stsv.economy.land.Land;
import dev.stsv.economy.land.LandIndex;
import dev.stsv.economy.land.LandRules;
import dev.stsv.economy.request.RequestSpec;
import dev.stsv.economy.event.EventChain;
import dev.stsv.economy.event.EventSpec;
import dev.stsv.economy.shop.Order;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void eventsFollowEachOther() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration y = ContentTest.yaml("events.yml");
        Map<String, EventSpec> events = EventSpec.parse(y, problems);
        EventChain chain = EventChain.parse(y.getConfigurationSection("chain"));
        assertEquals(6 * 3_600_000L, chain.gapMillis(), "the next one 6 hours after one ends");
        // never the same kind twice in a row, never one that does not chain
        java.util.Random r = new java.util.Random(1);
        Map<String, Integer> seen = new java.util.HashMap<>();
        String last = "shortage";
        for (int i = 0; i < 3000; i++) {
            EventSpec next = chain.pick(events, last, false, r);
            assertNotEquals(last, next.id());
            assertNotEquals("world_boss", next.id());
            seen.merge(next.id(), 1, Integer::sum);
            last = next.id();
        }
        assertEquals(10, seen.size(), "every chained kind comes up: " + seen);
        assertTrue(seen.get("urgent_order") > seen.get("tax_audit"), "weights count: " + seen);
        // in the small hours only the long ones (24 hours)
        for (EventSpec e : chain.candidates(events, "harvest", true)) assertTrue(e.hours() >= 24, e.id());
        assertEquals(List.of("shortage", "crash", "tax_audit"), chain.candidates(events, "harvest", true).stream().map(EventSpec::id).toList());
        java.time.ZoneId kst = java.time.ZoneId.of("Asia/Seoul");
        assertTrue(chain.night(t(2026, 11, 6, 1, 0), kst));
        assertTrue(chain.night(t(2026, 11, 6, 8, 59), kst));
        assertFalse(chain.night(t(2026, 11, 6, 9, 0), kst));
        assertFalse(chain.night(t(2026, 11, 6, 0, 30), kst));
        assertNull(chain.pick(Map.of("a", events.get("world_boss")), null, false, r), "nothing may come: the chain waits");
    }

    @Test
    void anUrgentOrderPaysMoreForTheFirstOnes() {
        assertEquals(296, Order.target(10, 3000, 16, 512), "iron at 10G: about 3,000G worth, in eights (300 -> 296)");
        assertEquals(512, Order.target(2, 3000, 16, 512), "cheap things: at most 512");
        assertEquals(48, Order.target(60, 3000, 16, 512), "diamonds: 50 -> 48");
        assertEquals(16, Order.target(500, 3000, 16, 512), "at least 16");
        Order o = new Order(10, 100, 2, 1);
        assertEquals(20, o.unit(0), 1e-9, "the first one: twice the base price");
        assertEquals(15, o.unit(50), 1e-9);
        assertEquals(10, o.unit(100), 1e-9);
        assertEquals(o.pay(0, 100), o.pay(0, 40) + o.pay(40, 60), 1e-9, "in parts or at once, the same money");
        assertTrue(o.pay(0, 10) > o.pay(90, 10), "early deliveries earn more");
    }

    @Test
    void theEventsAndTheDemandNotice() {
        List<String> problems = new ArrayList<>();
        Map<String, EventSpec> events = EventSpec.parse(ContentTest.yaml("events.yml"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("shortage", "crash", "harvest", "mining_boom", "tax_audit", "merchant", "urgent_order", "premium_buyer", "fee_free",
                "earnings", "world_boss"), List.copyOf(events.keySet()));
        assertEquals(0, events.get("earnings").hours(), "실적 발표 happens at once");
        assertEquals(List.of("miner", "blacksmith"), EventSpec.list(events.get("mining_boom").params().get("jobs")));
        assertFalse(events.get("world_boss").chained(), "the world boss is on hold");
        assertEquals(24, events.get("shortage").hours(), "품귀 lasts a day");
        assertEquals(3.0, events.get("shortage").number("multiplier", 0));
        assertFalse(events.containsKey("lawless"), "무법 지대 is gone");
        ShopCatalog shop = ShopCatalog.parse(ContentTest.yaml("shop.yml"), problems);
        assertEquals(new ShopCatalog.DemandNotice(DayOfWeek.MONDAY, 3, 1.5), shop.demand());
        // bad entries are reported, not crashing
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("events.x.hours", -1);
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
    void levelsFollowTheCurveAndEffectsGrowWithThem() {
        JobSpec spec = JobSpec.parse(ContentTest.yaml("jobs.yml"), new ArrayList<>());
        assertEquals(30, spec.maxLevel());
        // a(n-1)² + b(n-1) with a 40, b 60
        assertEquals(0, spec.xpFor(1), 1e-9);
        assertEquals(100, spec.xpFor(2), 1e-9);
        assertEquals(3780, spec.xpFor(10), 1e-9);
        assertEquals(15580, spec.xpFor(20), 1e-9);
        assertEquals(35380, spec.xpFor(30), 1e-9);
        assertEquals(1, spec.levelFor(0));
        assertEquals(1, spec.levelFor(99));
        assertEquals(2, spec.levelFor(100));
        assertEquals(9, spec.levelFor(3779));
        assertEquals(10, spec.levelFor(3780));
        assertEquals(30, spec.levelFor(1e9), "capped at the last level");
        assertEquals(620, spec.mastery(36000), 1e-9, "XP past Lv30 is 숙련");
        assertEquals(0, spec.mastery(100), 1e-9);

        JobSpec.Job miner = spec.jobs().get("miner");
        assertEquals(10, miner.xp("break", "minecraft:diamond_ore"), 1e-9);
        assertEquals(0, miner.xp("break", "minecraft:stone"), 1e-9);
        assertEquals(0.01, miner.range("extra", 1, 30), 1e-9);
        assertEquals(0.30, miner.range("extra", 30, 30), 1e-9);
        assertEquals(0.01 + 0.29 * 14 / 29, miner.range("extra", 15, 30), 1e-9, "evenly between");
        assertEquals(0, miner.range("extra", 0, 30), 1e-9, "no level, no effect");
        assertEquals(List.of("economy:rough_ruby"), miner.finds().drops("minecraft:redstone_ore"));
        assertEquals(0.08, miner.finds().chance().at(30, 30), 1e-9);
        assertEquals(0.01, miner.finds().outsider(), 1e-9);
        // machine bonuses switch on at their level
        assertEquals(0, miner.bonus("extra", 9), 1e-9);
        assertEquals(0.15, miner.bonus("extra", 10), 1e-9);
        assertEquals(0.20, miner.bonus("sell", 30), 1e-9);
        assertEquals(0, miner.bonus("sell", 29), 1e-9);
        JobSpec.Job cook = spec.jobs().get("cook");
        assertEquals(0.12, cook.bonus("speed", 12), 1e-9, "speed is per level");
        JobSpec.Job smith = spec.jobs().get("blacksmith");
        assertEquals(1, smith.bonus("fast", 19), 1e-9, "a time factor is 1 below its level");
        assertEquals(0.5, smith.bonus("fast", 20), 1e-9);
        assertEquals(0.5, spec.jobs().get("alchemist").bonus("aging", 25), 1e-9);
        assertEquals(miner, spec.ofMachine("gem_bench"));
        // fishing pool: weighted, the rarest last
        JobSpec.Finds fish = spec.jobs().get("fisher").finds();
        assertEquals("economy:red_seabream", fish.pick(0.0));
        assertEquals("economy:sturgeon", fish.pick(0.999));
        assertTrue(fish.doubleItems().contains("economy:pearl"));
        assertEquals(20, fish.doubleLevel());
        JobSpec.Job farmer = spec.jobs().get("farmer");
        assertEquals(30, farmer.areaHarvest());
        assertEquals(10, farmer.growth().level());
    }

    @Test
    void machinesPickTheirRecipeByInputsAndTier() {
        Map<String, MachineSpec> m = MachineSpec.parse(ContentTest.yaml("machines.yml"), new ArrayList<>());
        java.util.function.BiPredicate<String, String> tags = (tag, item) -> tag.equals("logs") && item.endsWith("_log");
        MachineSpec gems = m.get("gem_bench");
        assertEquals("economy:ruby", gems.auto(Map.of("economy:rough_ruby", 1), 0, tags).output());
        assertEquals(1, gems.auto(Map.of("economy:rough_ruby", 4), 0, tags).amount(), "the bulk recipe is 1강");
        assertEquals(5, gems.auto(Map.of("economy:rough_ruby", 4), 1, tags).amount(), "from 1강: four rough gems make five");
        assertEquals(1, gems.auto(Map.of("economy:rough_ruby", 3), 1, tags).amount(), "not enough for the bulk one");
        assertNull(gems.auto(Map.of("minecraft:gold_ingot", 2, "economy:ruby", 1), 0, tags), "the ring is 1강");
        assertEquals("economy:ruby_ring", gems.auto(Map.of("minecraft:gold_ingot", 2, "economy:ruby", 1), 1, tags).output());
        assertNull(gems.auto(Map.of("economy:rough_ruby", 1, "minecraft:dirt", 1), 3, tags), "a stray item stops the 자동");
        assertNull(gems.auto(Map.of(), 3, tags));
        MachineSpec forge = m.get("forge");
        MachineSpec.Recipe tools = forge.recipes().stream().filter(r -> r.output().equals("economy:steel_toolset")).findFirst().orElseThrow();
        assertTrue(MachineSpec.enough(tools, Map.of("economy:steel_ingot", 2, "economy:iron_chain", 1, "minecraft:birch_log", 2), tags), "#logs takes any log");
        assertFalse(MachineSpec.enough(tools, Map.of("economy:steel_ingot", 2, "economy:iron_chain", 1, "minecraft:birch_log", 1), tags));
        assertEquals("economy:steel_toolset", forge.auto(Map.of("economy:steel_ingot", 2, "economy:iron_chain", 1, "minecraft:oak_log", 2), 2, tags).output());
        MachineSpec still = m.get("still");
        assertTrue(still.recipes().stream().filter(r -> r.output().equals("economy:vintage_wine")).findFirst().orElseThrow().aging());
        MachineSpec.Settings s = MachineSpec.Settings.parse(ContentTest.yaml("machines.yml"));
        assertEquals(3, s.maxTier());
        assertEquals(5000, s.cost(1));
        assertEquals(30, s.level(3));
        assertEquals(4, s.lanes(3));
        assertEquals(0.30, s.speed(2), 1e-9);
        assertEquals(0.2, s.xpPerGold(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------ stocks

    @Test
    void stockPricesMoveInsideTheirBandAndComeBackToTheirValue() {
        StockSpec spec = StockSpec.parse(ContentTest.yaml("stocks.yml"), new ArrayList<>());
        StockSpec.Settings s = spec.settings();
        assertEquals(144, s.ticksPerDay());
        // no noise, no industry: the price closes half the gap to its value in reversion-days
        double p = 50, base = 100;
        for (int i = 0; i < s.ticksPerDay() * s.reversionDays(); i++) p = StockSpec.step(p, base, 0.01, 0, 0, 1, s.kappa());
        assertEquals(Math.sqrt(50 * 100.0), p, 0.5, "log gap halves: 50 -> ~70.7");
        assertEquals(120, StockSpec.clamp(200, 100, 0.2), 1e-9);
        assertEquals(80, StockSpec.clamp(10, 100, 0.2), 1e-9);
        assertEquals(1, StockSpec.atLimit(120, 100, 0.2));
        assertEquals(-1, StockSpec.atLimit(80, 100, 0.2));
        assertEquals(0, StockSpec.atLimit(110, 100, 0.2));
        // the industry moves a sensitive company more
        assertTrue(StockSpec.step(100, 100, 0, 0, 0.02, 1.5, 0) > StockSpec.step(100, 100, 0, 0, 0.02, 0.7, 0));
        // activity: twice the 7-day average = +3%, capped, nothing without history
        assertEquals(0.03, StockSpec.activityMove(200, 100, 0.03, 0.03), 1e-9);
        assertEquals(0.03, StockSpec.activityMove(900, 100, 0.03, 0.03), 1e-9, "capped");
        assertEquals(-0.015, StockSpec.activityMove(50, 100, 0.03, 0.03), 1e-9);
        assertEquals(0, StockSpec.activityMove(500, 0, 0.03, 0.03), 1e-9);
        assertEquals("▁▅█", StockSpec.sparkline(List.of(1.0, 1.5, 2.0)));
        assertEquals(0, StockSpec.band(105, 100, s.alerts()));
        assertEquals(1, StockSpec.band(112, 100, s.alerts()));
        assertEquals(-2, StockSpec.band(75, 100, s.alerts()));
    }

    @Test
    void dividendsFollowActivityAndOnlyPayingCompaniesPay() {
        StockSpec spec = StockSpec.parse(ContentTest.yaml("stocks.yml"), new ArrayList<>());
        Map<String, StockSpec.Company> cos = spec.companies();
        Map<String, Double> perShare = StockSpec.dividends(10_000, Map.of("steel", 999.0), cos, Map.of());
        assertFalse(perShare.containsKey("taeyang"), "★3 companies pay no dividend");
        assertTrue(perShare.get("hanseong") > perShare.get("taebaek"), "the busy industry gets most of the pool");
        double paid = 0;
        for (var e : perShare.entrySet()) paid += e.getValue() * cos.get(e.getKey()).shares();
        assertEquals(10_000, paid, 1e-6, "the whole pool is shared out");
        assertTrue(StockSpec.dividends(0, Map.of(), cos, Map.of()).isEmpty());
    }

    @Test
    void newsLinesDoNotRepeatTheirCauses() {
        List<String> problems = new ArrayList<>();
        NewsSpec news = NewsSpec.parse(ContentTest.yaml("news.yml"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        NewsSpec.Writer w = new NewsSpec.Writer(30);
        java.util.Random r = new java.util.Random(7);
        List<String> causes = news.causes().get("steel").get(true);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < causes.size(); i++) {
            String line = w.line(news, "hanseong", "한성제철", "steel", 7.5, null, r);
            assertTrue(line.contains("한성제철") && line.contains("▲7.5%"), line);
            String used = causes.stream().filter(line::contains).findFirst().orElseThrow();
            assertTrue(seen.add(used), "a cause is not drawn again while it is recent: " + used);
        }
        assertEquals("small", NewsSpec.size(5.2));
        assertEquals("medium", NewsSpec.size(-7.9));
        assertEquals("large", NewsSpec.size(12));
        String custom = w.line(news, "x", "회사", "food", -6, "직접 쓴 이유", r);
        assertTrue(custom.contains("직접 쓴 이유") && custom.contains("▼6.0%"));
    }

    @Test
    void theChartIsTextThatEndsWhereTheTitleStarts() {
        assertEquals(StockGraph.BASE, StockGraph.glyph(true, false, 0, 1));
        assertEquals(StockGraph.BASE + StockGraph.H * StockGraph.H * 3 + StockGraph.H - 1, StockGraph.glyph(false, true, 0, 99), "a height past the chart is cut");
        for (int px : new int[]{1, -1, 37, -200, 255}) {
            int sum = 0;
            for (char c : StockGraph.space(px).toCharArray()) sum += StockGraph.spaceAdvance(c);
            assertEquals(px, sum);
        }
        List<StockGraph.Candle> candles = new ArrayList<>();
        for (int i = 0; i < 24; i++) candles.add(new StockGraph.Candle(100 + i, 103 + i, 98 + i, 101 + i + (i % 3 == 0 ? -4 : 0)));
        String title = StockGraph.title(candles, 24);
        assertTrue(title.startsWith("<white><font:economy:stockgraph>") && title.endsWith("</font></white>"));
        // walk the pen: spaces move it, glyphs advance by their width + 1; it must end at the title origin
        String body = title.substring("<white><font:economy:stockgraph>".length(), title.length() - "</font></white>".length());
        int pen = StockGraph.ORIGIN, glyphs = 0;
        for (char c : body.toCharArray()) {
            if (c >= StockGraph.SPACE) pen += StockGraph.spaceAdvance(c);
            else {
                int set = (c - StockGraph.BASE) / (StockGraph.H * StockGraph.H);
                assertTrue(pen >= StockGraph.LEFT && pen < StockGraph.RIGHT, "inside the chart: " + pen);
                pen += set % 2 == 1 ? 2 : 4;
                glyphs++;
            }
        }
        assertEquals(StockGraph.ORIGIN, pen);
        assertEquals(48, glyphs, "a wick and a body per candle");
        assertEquals("", StockGraph.title(List.of(), 24));
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
    void aChunkBelongsToOneLandAndEveryoneLivesInOne() {
        LandIndex idx = new LandIndex();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        Land la = new Land(1, a, 0), lb = new Land(2, b, 0), town = new Land(3, null, 0);
        idx.add(la);
        idx.add(lb);
        idx.add(town);
        idx.claim(la, new ChunkPos("world", 0, 0), 10);
        idx.claim(la, new ChunkPos("world", -1, 0), 20);
        idx.claim(town, new ChunkPos("world", 5, 5), 1);
        assertSame(la, idx.at("world", 15, 15), "block 15,15 is chunk 0,0");
        assertSame(la, idx.at("world", -1, 0), "block -1 is chunk -1");
        assertNull(idx.at("world", 16, 0));
        assertNull(idx.at("nether", 0, 0));
        assertEquals(new ChunkPos("world", -1, 0), la.newest(), "the newest is released first");
        assertTrue(town.server() && idx.server() == town);
        assertEquals(0, idx.residents(town));
        // living together: b joins a's land; b's free chunks count there, not on b's own land
        assertEquals(1, idx.residents(la));
        assertEquals(1, idx.residents(lb));
        la.members.put(b, 5L);
        assertSame(la, idx.home(b));
        assertSame(lb, idx.ownedBy(b));
        assertEquals(2, idx.residents(la));
        assertEquals(0, idx.residents(lb), "b's own land lost b's free chunks");
        assertTrue(la.allows(b) && la.allows(a) && !la.allows(c) && !lb.allows(a));
        assertNull(idx.home(c));
        idx.release(la, new ChunkPos("world", 0, 0));
        assertNull(idx.at("world", 0, 0));
        assertEquals(1, la.size());
        idx.remove(la);
        assertNull(idx.at("world", -1, 0));
        assertNull(idx.memberOf(b));
    }

    @Test
    void landPriceAndTaxGrowWithSize() {
        LandRules r = LandRules.parse(ContentTest.yaml("land.yml"));
        assertEquals(2, r.free(1), "2 free chunks a person");
        assertEquals(6, r.free(3), "3 people living together: 6 free chunks");
        assertEquals(0, r.price(0, 2));
        assertEquals(0, r.price(1, 2));
        assertEquals(500, r.price(2, 2), "the first paid chunk");
        assertEquals(550, r.price(3, 2), "each paid chunk makes the next dearer");
        assertEquals(0, r.tax(2, 2, 0, 1), "free chunks are not taxed");
        assertEquals(13, r.tax(3, 2, 0, 1));
        assertEquals(8 * 13, r.tax(10, 2, 0, 1));
        assertEquals(8 * 13 + 2 * 26, r.tax(12, 2, 0, 1), "past 10 chunks: double");
        assertEquals(15, r.progressiveLimit(1), "10 + 5 per member");
        assertEquals(8 * 13, r.tax(12, 4, 1, 1), "two people: 4 free, double only past 15");
        assertEquals(2 * 13, r.tax(3, 2, 0, 2), "세무 조사");
        assertEquals(0, r.tax(0, 2, 0, 1));
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
