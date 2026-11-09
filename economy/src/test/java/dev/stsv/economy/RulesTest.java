package dev.stsv.economy;

import dev.stsv.economy.auction.Auction;
import dev.stsv.economy.fishing.FishSpec;
import dev.stsv.economy.fishing.Ring;
import dev.stsv.economy.fishing.RingGlyphs;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        EventSpec held = new EventSpec("held", "보류", 1, "purple", Map.of("chain", "false"));
        assertFalse(held.chained(), "chain: false = only when an admin starts it");
        assertNull(chain.pick(Map.of("a", held), null, false, r), "nothing may come: the chain waits");
    }

    @Test
    void anUrgentOrderPaysMoreForTheFirstOnes() {
        assertEquals(296, Order.target(10, 3000, 16, 512), "iron at 10G: about 3,000G worth, in eights (300 -> 296)");
        assertEquals(512, Order.target(2, 3000, 16, 512), "cheap things: at most 512");
        assertEquals(48, Order.target(60, 3000, 16, 512), "diamonds: 50 -> 48");
        assertEquals(16, Order.target(500, 3000, 16, 512), "at least 16");
        assertTrue(Order.reachable(150, 3000, 16), "netherite scrap: 16 x 150G = 2,400G");
        assertTrue(Order.reachable(250, 3000, 16), "4,000G: within half again 3,000G");
        assertFalse(Order.reachable(520, 3000, 16), "a 520G find: 16 of them is 8,320G, not an order");
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
                "earnings"), List.copyOf(events.keySet()));
        assertEquals(0, events.get("earnings").hours(), "실적 발표 happens at once");
        assertEquals(List.of("miner", "blacksmith"), EventSpec.list(events.get("mining_boom").params().get("jobs")));
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

    @Test
    void shopCategoriesLayOutSymmetrically() {
        List<String> seven = List.of("a", "b", "c", "d", "e", "f", "g");
        java.util.function.Function<List<String>, String> cells = l -> String.join("", l.stream().map(x -> x == null ? "." : x).toList());
        // 구매 row of 9: 7 centred, 8 split around the empty middle cell, 9 full; 판매 row of 7: full
        assertEquals(".abcdefg", cells.apply(ShopCatalog.row(seven, 9)));
        assertEquals("abcd.efgh", cells.apply(ShopCatalog.row(List.of("a", "b", "c", "d", "e", "f", "g", "h"), 9)));
        assertEquals("abcdefghi", cells.apply(ShopCatalog.row(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"), 9)));
        assertEquals(".abc.def", cells.apply(ShopCatalog.row(List.of("a", "b", "c", "d", "e", "f"), 9)));
        assertEquals("abcdefg", cells.apply(ShopCatalog.row(seven, 7)));
        assertEquals(".abcde", cells.apply(ShopCatalog.row(List.of("a", "b", "c", "d", "e"), 7)));
    }

    @Test
    void anOlderShopFileStillLoads() {
        // before categories: items straight under sell, one buy list, a vanity list, a merchant list
        YamlConfiguration old = new YamlConfiguration();
        old.set("sell.diamond.base", 60);
        old.set("sell.diamond.q", 4);
        old.set("sell.wheat.base", 2);
        old.set("sell.wheat.q", 128);
        old.set("buy", List.of(Map.of("item", "torch", "amount", 16, "price", 20)));
        old.set("vanity", List.of(Map.of("kind", "cosmetic", "id", "stsv:tomato_mask", "price", 15000),
                Map.of("kind", "title", "id", "economy:trader", "price", 5000)));
        old.set("merchant", List.of(Map.of("item", "elytra", "price", 30000, "stock", 1)));
        List<String> problems = new ArrayList<>();
        ShopCatalog c = ShopCatalog.parse(old, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(List.of("minecraft:diamond", "minecraft:wheat"), c.sellCategories().getFirst().items());
        assertEquals(List.of("goods", "cosmetics", "titles"), c.buyCategories().stream().map(ShopCatalog.BuyCategory::id).toList());
        assertEquals(new ShopCatalog.Good("item", "minecraft:torch", 16, 20), c.goods().getFirst());
        assertEquals(new ShopCatalog.Good("title", "economy:trader", 1, 5000), c.goods().getLast());
        assertEquals(1, c.merchant().goods().size());
        // a category that names an item twice is reported
        YamlConfiguration twice = new YamlConfiguration();
        for (String cat : List.of("a", "b")) {
            twice.set("sell." + cat + ".items.diamond.base", 60);
            twice.set("sell." + cat + ".items.diamond.q", 4);
        }
        List<String> p2 = new ArrayList<>();
        ShopCatalog.parse(twice, p2);
        assertEquals(1, p2.size(), p2.toString());
    }

    // ------------------------------------------------------------------------------------------ prices

    @Test
    void sellPriceFallsWithWhatThePlayerSold() {
        double q = 32, base = 10, floor = 0.25;
        assertEquals(10, PriceModel.unit(base, 0, q, floor, 1), 1e-9, "nothing sold lately: the market price");
        assertEquals(10 / Math.sqrt(2), PriceModel.unit(base, 32, q, floor, 1), 1e-9, "S = Q (the player's own quota): 71% of the base");
        assertEquals(5, PriceModel.unit(base, 96, q, floor, 1), 1e-9, "S = 3Q: half");
        assertEquals(10 / Math.sqrt(1.5), PriceModel.unit(base, 16, q, floor, 1), 1e-9);
        assertEquals(2.5, PriceModel.unit(base, 10_000, q, floor, 1), 1e-9, "never below the floor");
        assertTrue(PriceModel.atFloor(base, 10_000, q, floor));
        assertFalse(PriceModel.atFloor(base, 10, q, floor));
        // the floor is where (1 + S/Q)^(-1/2) = floor: S = Q (1/floor^2 - 1) = 15Q
        assertTrue(PriceModel.atFloor(base, 15 * q + 1e-6, q, floor));
        assertFalse(PriceModel.atFloor(base, 15 * q - 1e-3, q, floor));
        // no wall: every further unit before the floor pays a little less than the one before it
        for (int s = 0; s < 15 * q; s++) assertTrue(PriceModel.unit(base, s + 1, q, floor, 1) < PriceModel.unit(base, s, q, floor, 1));
        assertEquals(15, PriceModel.unit(base, 0, q, floor, 1.5), 1e-9, "수요 고시 x1.5");
        assertEquals(2.5 * 3, PriceModel.unit(base, 10_000, q, floor, 3), 1e-9, "the floor is multiplied too (품귀 x3)");
        // another exponent (shop.yml curve-exponent): 1 = falls faster
        assertEquals(5, PriceModel.unit(base, 32, q, floor, 1, 1.0), 1e-9);
    }

    @Test
    void oneSaleLowersItsOwnUnitsAsItGoes() {
        double q = 32, base = 10, floor = 0.35, sold = 20;
        // selling 64 at once = selling them one by one, each unit at the price after the ones before it
        double once = PriceModel.total(base, sold, q, floor, 1, 64), oneByOne = 0;
        for (int k = 0; k < 64; k++) oneByOne += PriceModel.unit(base, sold + k, q, floor, 1);
        assertEquals(oneByOne, once, 1e-9);
        assertTrue(once < 64 * PriceModel.unit(base, sold, q, floor, 1), "later units of the same sale pay less");
        assertTrue(once > 64 * PriceModel.unit(base, sold + 64, q, floor, 1));
        // split in two (the 고가 매입상's stock, then the rest): the same as one curve with two multipliers
        double split = PriceModel.total(base, sold, q, floor, 2, 10) + PriceModel.total(base, sold + 10, q, floor, 1, 54);
        double byHand = 0;
        for (int k = 0; k < 64; k++) byHand += PriceModel.unit(base, sold + k, q, floor, k < 10 ? 2 : 1);
        assertEquals(byHand, split, 1e-9);
        assertEquals(0, PriceModel.total(base, sold, q, floor, 1, 0), 1e-9);
    }

    @Test
    void eachPlayersPriceIsTheirOwn() {
        double q = 32 * 6, base = 10, floor = 0.25;
        // A sold 3,000 today, B nothing: A's price is low, B still gets the market price; how many play does not enter
        double a = PriceModel.unit(base, 3000, q, floor, 1), b = PriceModel.unit(base, 0, q, floor, 1);
        assertEquals(base * floor, a, 1e-9);
        assertEquals(base, b, 1e-9);
        // B sells 32 after A's 3,000: B's units start at the market price and fall only by B's own 32
        assertEquals(10 / Math.sqrt(1 + 32.0 / q), PriceModel.unit(base, 32, q, floor, 1), 1e-9);
        assertTrue(PriceModel.total(base, 0, q, floor, 1, 32) > 32 * PriceModel.unit(base, 3000, q, floor, 1) * 2);
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
        assertEquals(0.05, miner.range("extra", 1, 30), 1e-9);
        assertEquals(0.60, miner.range("extra", 30, 30), 1e-9);
        assertEquals(0.05 + 0.55 * 14 / 29, miner.range("extra", 15, 30), 1e-9, "evenly between");
        assertEquals(0, miner.range("extra", 0, 30), 1e-9, "no level, no effect");
        assertEquals(List.of("economy:rough_ruby"), miner.finds().drops("minecraft:redstone_ore"));
        assertEquals(0.12, miner.finds().chance().at(30, 30), 1e-9);
        assertEquals(0.01, miner.finds().outsider(), 1e-9);
        // machine bonuses switch on at their level
        assertEquals(0, miner.bonus("extra", 9), 1e-9);
        assertEquals(0.15, miner.bonus("extra", 10), 1e-9);
        assertEquals(0.20, miner.bonus("yield", 30), 1e-9);
        assertEquals(0, miner.bonus("yield", 29), 1e-9);
        JobSpec.Job cook = spec.jobs().get("cook");
        assertEquals(0.12, cook.bonus("speed", 12), 1e-9, "speed is per level");
        JobSpec.Job smith = spec.jobs().get("blacksmith");
        assertEquals(1, smith.bonus("fast", 19), 1e-9, "a time factor is 1 below its level");
        assertEquals(0.5, smith.bonus("fast", 20), 1e-9);
        assertEquals(0.15, smith.bonus("save", 20), 1e-9);
        assertEquals(0, smith.bonus("save", 19), 1e-9);
        assertEquals(0.5, spec.jobs().get("alchemist").bonus("aging", 25), 1e-9);
        assertEquals(miner, spec.ofMachine("gem_bench"));
        // fishing: the rare-bite chance and the Lv20 doubles (the fish themselves are in fish.yml)
        JobSpec.Finds fish = spec.jobs().get("fisher").finds();
        assertEquals(0.22, fish.chance().at(30, 30), 1e-9);
        assertEquals(0.06, fish.chance().at(1, 30), 1e-9);
        assertEquals(0.15, spec.jobs().get("fisher").bonus("save", 20), 1e-9, "Lv20: the fish bench keeps an input 15% of the time");
        assertEquals(0, spec.jobs().get("fisher").bonus("save", 19), 1e-9);
        assertTrue(fish.doubleItems().contains("economy:pearl"));
        assertEquals(20, fish.doubleLevel());
        JobSpec.Job farmer = spec.jobs().get("farmer");
        // the same field gives a farmer clearly more: a row (1x3) from Lv10, 3x3 from Lv20
        assertEquals(1, farmer.harvestSpan(9));
        assertEquals(3, farmer.harvestSpan(10));
        assertEquals(9, farmer.harvestSpan(20));
        assertEquals(10, farmer.growth().level());
        // the custom-crop harvest bonus: a chance per produce unit, 20% at Lv1 to 80% at Lv30
        assertEquals(0.20, farmer.harvestPct(1, 30), 1e-9);
        assertEquals(0.80, farmer.harvestPct(30, 30), 1e-9);
        assertEquals(1.0, farmer.harvestBonus(1, 30), 1e-9);
        assertEquals(3.0, farmer.harvestBonus(30, 30), 1e-9);
        // special materials: anyone may find them, their own job far more often; the shop does not buy them
        assertEquals(8, spec.specials().size());
        for (JobSpec.Special s : spec.specials().values()) {
            assertTrue(s.chance(s.job(), 1, 30) >= 3 * s.outsider(), s.item());
            assertTrue(s.chance(s.job(), 30, 30) >= 10 * s.outsider(), s.item());
            assertEquals(s.outsider(), s.chance("nobody", 30, 30), 1e-12);
            assertTrue(s.value() > 0, s.item());
        }
        assertEquals(3, spec.specials("harvest").size());
        assertEquals(3, spec.specials("catch").size());
        assertEquals(2, spec.specials("break").size());
    }

    /** The old per-level farmer keys still read as the same straight line (value x level). */
    @Test
    void farmerPerLevelKeysStillRead() {
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("jobs.farmer.name", "농부");
        y.set("jobs.farmer.harvest-pct-per-level", 0.02);
        y.set("jobs.farmer.harvest-per-level", 0.1);
        y.set("jobs.farmer.area-harvest", 30);
        JobSpec s = JobSpec.parse(y, new ArrayList<>());
        JobSpec.Job f = s.jobs().get("farmer");
        assertEquals(0.02 * 12, f.harvestPct(12, 30), 1e-9);
        assertEquals(0.1 * 30, f.harvestBonus(30, 30), 1e-9);
        assertEquals(1, f.harvestSpan(29));
        assertEquals(9, f.harvestSpan(30));
        assertTrue(s.specials().isEmpty());
    }

    /** Every job bonus rolls per unit, so a 4 -> 4 bulk recipe gains exactly four times what a 1 -> 1 one does. */
    @Test
    void machineBonusesAreProportional() {
        java.util.Random r = new java.util.Random(1);
        assertEquals(0, MachineSpec.binomial(0, 0.5, r));
        assertEquals(0, MachineSpec.binomial(5, 0, r));
        assertEquals(5, MachineSpec.binomial(5, 1, r));
        int draws = 100_000;
        java.util.Random four = new java.util.Random(1), one = new java.util.Random(1);
        long sum4 = 0, sum1 = 0;
        for (int i = 0; i < draws; i++) {
            sum4 += MachineSpec.binomial(4, 0.35, four);
            sum1 += MachineSpec.binomial(1, 0.35, one);
        }
        double mean4 = (double) sum4 / draws, mean1 = (double) sum1 / draws;
        assertEquals(1.40, mean4, 0.02);
        assertEquals(4 * mean1, mean4, 0.02, "four units, four times the bonus of one");
        // the room a batch needs: 'double' doubles it, 'extra' / 'yield' add at most one per unit
        assertEquals(8, MachineSpec.maxBatch(4, false, true));
        assertEquals(4, MachineSpec.maxBatch(2, true, false));
        assertEquals(10, MachineSpec.maxBatch(5, false, true));
        assertEquals(12, MachineSpec.maxBatch(3, true, true));
        assertEquals(1, MachineSpec.maxBatch(1, false, false));
    }

    /** The Lv30 bonus is 'yield' on the machine now: an old jobs.yml with the shop-price 'sell' gets a load problem. */
    @Test
    void theOldSellBonusIsAProblem() {
        for (String key : List.of("sell", "yield")) {
            YamlConfiguration y = new YamlConfiguration();
            y.set("jobs.miner.name", "광부");
            y.set("jobs.miner.machine", "gem_bench");
            y.set("jobs.miner.machine-bonus." + key + ".level", 30);
            y.set("jobs.miner.machine-bonus." + key + "." + (key.equals("sell") ? "bonus" : "chance"), 0.2);
            List<String> problems = new ArrayList<>();
            JobSpec spec = JobSpec.parse(y, problems);
            if (key.equals("sell")) assertEquals(List.of("miner.machine-bonus: 모르는 효과 sell"), problems);
            else {
                assertEquals(List.of(), problems);
                assertEquals(0.2, spec.jobs().get("miner").bonus("yield", 30), 1e-9);
            }
        }
    }

    /** 직업 변경 비용: by the level of the job changed TO, straight between the points (owner: 1,000 / 2,000 / 3,000 / 5,000). */
    @Test
    void changingJobsCostsByTheTargetLevel() {
        List<String> problems = new ArrayList<>();
        JobSpec spec = JobSpec.parse(ContentTest.yaml("jobs.yml"), problems);
        assertEquals(List.of(), problems);
        JobSpec.ChangeCost c = spec.changeCost();
        assertEquals(1000, c.at(1), "a job never worked (Lv1)");
        assertEquals(2000, c.at(10));
        assertEquals(3000, c.at(20));
        assertEquals(5000, c.at(30));
        assertEquals(1444, c.at(5), "evenly between Lv1 and Lv10");
        assertEquals(2500, c.at(15));
        assertEquals(4000, c.at(25));
        assertEquals(1000, c.at(0), "flat before the first point");
        assertEquals(5000, c.at(40), "flat after the last point");
        for (int lv = 1; lv < 30; lv++) assertTrue(c.at(lv + 1) >= c.at(lv), "never cheaper for a higher level: Lv" + lv);
        assertEquals(1000, c.min());
        assertEquals(5000, c.max());
        assertEquals(0, spec.changeDays(), "any time: no waiting days");

        // an old file with one number: the same at every level; a bad point is a problem, not a crash
        YamlConfiguration y = new YamlConfiguration();
        y.set("change-cost", 1500);
        assertEquals(1500, JobSpec.parse(y, new ArrayList<>()).changeCost().at(30));
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("change-cost.x", 10);
        bad.set("change-cost.5", 700);
        List<String> p2 = new ArrayList<>();
        JobSpec.ChangeCost b = JobSpec.parse(bad, p2).changeCost();
        assertTrue(p2.stream().anyMatch(s -> s.startsWith("change-cost.x")), p2.toString());
        assertEquals(700, b.at(30));
    }

    @Test
    void fishGroupsFeedTheRecipes() {
        var y = ContentTest.yaml("machines.yml");
        Map<String, MachineSpec> m = MachineSpec.parse(y, new ArrayList<>());
        Map<String, MachineSpec.Group> groups = MachineSpec.groups(y, new ArrayList<>());
        java.util.function.BiPredicate<String, String> tags = (tag, item) -> groups.containsKey(tag) && groups.get(tag).items().contains(item);
        // every member is a fish of fish.yml, none is a legend, and a fish is in one cooking group only
        FishSpec f = fish();
        java.util.Set<String> fishItems = new HashSet<>();
        f.species().values().forEach(sp -> fishItems.add(sp.item()));
        Map<String, String> seen = new java.util.HashMap<>();
        // the press's groups (seeds, compostables, stones, dirts) are not fish
        java.util.Set<String> pressGroups = new HashSet<>();
        for (var r : m.get("press").recipes()) for (String in : r.inputs().keySet()) if (in.startsWith("#economy:")) pressGroups.add(in.substring(1));
        assertEquals(4, pressGroups.size());
        for (var g : groups.values()) {
            if (pressGroups.contains(g.id())) continue;
            for (String it : g.items()) {
                assertTrue(fishItems.contains(it), g.id() + ": " + it + " is a fish");
                var sp = f.species().values().stream().filter(x -> x.item().equals(it)).findFirst().orElseThrow();
                assertNotEquals("legend", sp.rarity(), it + " is a legend: sold, not cooked");
                assertNull(seen.put(it, g.id()), it + " in two groups");
            }
        }
        // any fish of a group will do, mixed too
        MachineSpec bench = m.get("fish_bench");
        assertEquals("economy:cod_fillet", bench.auto(Map.of("economy:sandfish", 1, "minecraft:cod", 1), 0, tags).output());
        assertEquals("economy:sukhoe", bench.auto(Map.of("economy:octopus", 1), 1, tags).output());
        assertNull(bench.auto(Map.of("economy:octopus", 1), 0, tags), "숙회 is 1강");
        MachineSpec kitchen = m.get("kitchen");
        assertEquals("economy:maeuntang", kitchen.auto(Map.of("economy:carp", 1, "economy:loach", 1, "economy:chili_powder", 1,
                "economy:tofu", 1), 1, tags).output());
        assertNull(kitchen.auto(Map.of("economy:guppy", 2, "economy:chili_powder", 1, "economy:tofu", 1), 3, tags),
                "an aquarium fish is not food");
        assertEquals("economy:saengseonjeon", kitchen.auto(Map.of("economy:rockfish", 1, "economy:lenok", 1, "economy:flour", 1,
                "minecraft:egg", 1), 0, tags).output());
    }

    /** A sized fish goes into a lane (and loses its size) only where a recipe open at the machine's tier uses it. */
    @Test
    void machinesTakeOnlyTheFishTheyUse() {
        var y = ContentTest.yaml("machines.yml");
        Map<String, MachineSpec> m = MachineSpec.parse(y, new ArrayList<>());
        Map<String, MachineSpec.Group> groups = MachineSpec.groups(y, new ArrayList<>());
        java.util.function.BiPredicate<String, String> tags = (tag, item) -> groups.containsKey(tag) && groups.get(tag).items().contains(item);
        MachineSpec fishBench = m.get("fish_bench"), kitchen = m.get("kitchen"), press = m.get("press");
        assertTrue(fishBench.takes("economy:croaker", 1, tags));
        assertFalse(fishBench.takes("economy:croaker", 0, tags), "굴비 opens at 1강");
        assertTrue(kitchen.takes("economy:snow_crab", 2, tags), "through #economy:crab");
        assertFalse(kitchen.takes("economy:king_crab", 3, tags), "a trophy");
        assertFalse(press.takes("economy:whale_shark", 3, tags));
        assertTrue(fishBench.takes("economy:sardine", 0, tags), "through #economy:blue_fish (common, never sized)");
    }

    /** The press: every seed (vanilla or a custom crop's) makes seed oil, mixed in one lane; each good is one recipe. */
    @Test
    void thePressTakesAnySeedMixed() {
        var y = ContentTest.yaml("machines.yml");
        MachineSpec press = MachineSpec.parse(y, new ArrayList<>()).get("press");
        Map<String, MachineSpec.Group> groups = MachineSpec.groups(y, new ArrayList<>());
        java.util.Set<String> saplings = java.util.Set.of("minecraft:oak_sapling", "minecraft:cherry_sapling");
        java.util.function.BiPredicate<String, String> tags = (tag, item) -> {
            var g = groups.get(tag);
            if (g == null) return tag.equals("saplings") && saplings.contains(item);
            return g.items().contains(item) || g.items().stream().anyMatch(e -> e.startsWith("#") && e.equals("#saplings") && saplings.contains(item));
        };
        assertEquals("economy:seed_oil", press.auto(Map.of("minecraft:wheat_seeds", 20, "economy:tomato_seeds", 12), 0, tags).output());
        assertNull(press.auto(Map.of("minecraft:wheat_seeds", 20), 0, tags), "not 32 yet");
        assertEquals("economy:compost", press.auto(Map.of("minecraft:oak_sapling", 10, "minecraft:rotten_flesh", 10), 0, tags).output());
        assertNull(press.auto(Map.of("minecraft:oak_sapling", 6, "minecraft:rotten_flesh", 10), 0, tags), "not 20 yet");
        var custom = new HashSet<String>();
        try (var s = java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("stsv.content")).resolve("economy/items"))) {
            for (var f : s.toList()) for (String k : org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false))
                if (k.endsWith("_seeds")) custom.add("economy:" + k);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        assertFalse(custom.isEmpty());
        assertTrue(groups.get("economy:seeds").items().containsAll(custom), "every custom crop's seeds");
        assertEquals(press.recipes().size(), press.recipes().stream().map(MachineSpec.Recipe::output).distinct().count(), "one recipe per good");
    }

    @Test
    void machinesPickTheirRecipeByInputsAndTier() {
        Map<String, MachineSpec> m = MachineSpec.parse(ContentTest.yaml("machines.yml"), new ArrayList<>());
        java.util.function.BiPredicate<String, String> tags = (tag, item) -> tag.equals("logs") && item.endsWith("_log");
        MachineSpec gems = m.get("gem_bench");
        assertEquals("economy:ruby", gems.auto(Map.of("economy:rough_ruby", 1), 0, tags).output());
        assertEquals(1, gems.auto(Map.of("economy:rough_ruby", 4), 0, tags).amount(), "the bulk recipe is 1강");
        assertEquals(4, gems.auto(Map.of("economy:rough_ruby", 4), 1, tags).amount(), "from 1강: four rough gems are cut at once (same yield, faster)");
        assertEquals(1, gems.auto(Map.of("economy:rough_ruby", 3), 1, tags).amount(), "not enough for the bulk one");
        assertNull(gems.auto(Map.of("economy:gold_ring", 1, "economy:ruby", 1), 0, tags), "the ring is 1강");
        assertEquals("economy:ruby_ring", gems.auto(Map.of("economy:gold_ring", 1, "economy:ruby", 1), 1, tags).output(), "a ruby set in a gold ring, like the diamond ring");
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
        assertEquals(50000, s.cost(3));
        assertEquals(30, s.level(3));
        assertEquals(4, s.lanes(3));
        assertEquals(0.30, s.speed(2), 1e-9);
        assertEquals(0.2, s.xpPerGold(), 1e-9);
        // the press has its own upgrade costs, the rest of the table is the global one
        MachineSpec.Settings press = s.forMachine(m.get("press"));
        assertEquals(1000, press.cost(1)); assertEquals(4000, press.cost(2)); assertEquals(10000, press.cost(3));
        assertEquals(3, press.maxTier(), "the global levels are kept: the press still goes to 3강");
        assertEquals(4, press.lanes(3)); assertEquals(0.30, press.speed(2), 1e-9); assertEquals(30, press.level(3));
        assertSame(s, s.forMachine(m.get("gem_bench")), "a job machine without its own lanes keeps the global table");
        // the processing jobs' machines start with two lanes (machines.yml lanes), the rest of the table is the global one
        MachineSpec.Settings forgeT = s.forMachine(m.get("forge"));
        assertEquals(2, forgeT.lanes(0));
        assertEquals(5000, forgeT.cost(1)); assertEquals(30, forgeT.level(3)); assertEquals(0.30, forgeT.speed(2), 1e-9);
        assertEquals(0, MachineSpec.Settings.refund(s, forgeT, 3), "own lanes, the same costs: nothing to refund");
        assertEquals(2, s.forMachine(m.get("kitchen")).lanes(0));
        assertEquals(2, s.forMachine(m.get("still")).lanes(0));
        // presses upgraded at the global price before get the difference back
        assertEquals(4000, MachineSpec.Settings.refund(s, press, 1)); assertEquals(20000, MachineSpec.Settings.refund(s, press, 2));
        assertEquals(60000, MachineSpec.Settings.refund(s, press, 3)); assertEquals(0, MachineSpec.Settings.refund(s, s, 3), "job machines");
        // the refund pays back what was paid then (5,000 / 20,000 / 50,000, which is also today's global price)
        // the same upgrade bought again can cost more (machines.yml upgrade.repeat); the owner turned it off for now
        double repeat = ContentTest.yaml("machines.yml").getDouble("upgrade.repeat");
        assertEquals(0, repeat, 1e-9);
        assertEquals(50_000, s.cost(3), "3강 back at 50,000 G");
        assertEquals(50_000, MachineSpec.Settings.repeatCost(s.cost(3), 5, repeat), "repeat 0: every one the same");
        assertEquals(75_000, MachineSpec.Settings.repeatCost(s.cost(3), 1, 0.5), "the rule still works when switched on");
        assertEquals(100_000, MachineSpec.Settings.repeatCost(s.cost(3), 2, 0.5));
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
        assertEquals("12,345", StockGraph.label(12_345.4));
        List<StockGraph.Candle> candles = new ArrayList<>();
        for (int i = 0; i < 12; i++) candles.add(new StockGraph.Candle(100 + i, 103 + i, 98 + i, 101 + i + (i % 3 == 0 ? -4 : 0)));
        String title = StockGraph.title(candles, 12);
        assertTrue(title.startsWith("<white><font:economy:stockgraph>") && title.endsWith("</font></white>"));
        // walk the pen: spaces move it, glyphs advance by their width + 1; it must end at the title origin
        String body = title.substring("<white><font:economy:stockgraph>".length(), title.length() - "</font></white>".length());
        int tagLeft = StockGraph.TAG_RIGHT - StockGraph.tagWidth(StockGraph.label(candles.getLast().close()));
        int pen = StockGraph.ORIGIN, bodies = 0, wicks = 0, dashes = 0, columns = 0, digits = 0;
        for (char c : body.toCharArray()) {
            if (c > StockGraph.SPACE && c < StockGraph.SPACE + 0x20) {
                pen += StockGraph.spaceAdvance(c);
            } else if (c >= StockGraph.EXTRA + 0x100) {                       // the tag's digits
                assertTrue(pen >= tagLeft + 2 && pen < StockGraph.TAG_RIGHT, "digit on the tag: " + pen);
                pen += (c - StockGraph.EXTRA - 0x100) % 16 == 10 ? 2 : 4;
                digits++;
            } else if (c >= StockGraph.EXTRA + 64) {                          // the tag's columns
                assertTrue(pen >= tagLeft && pen < StockGraph.TAG_RIGHT, "tag inside the screen: " + pen);
                pen += 2;
                columns++;
            } else if (c >= StockGraph.EXTRA) {                               // the last price's dashes
                assertTrue(pen >= StockGraph.LEFT && pen + 3 <= tagLeft - 2, "dash before the tag: " + pen);
                pen += 4;
                dashes++;
            } else {
                int set = (c - StockGraph.BASE) / (StockGraph.H * StockGraph.H);
                assertTrue(pen >= StockGraph.LEFT && pen < StockGraph.RIGHT, "inside the chart: " + pen);
                if (set % 2 == 1) {
                    pen += 2;
                    wicks++;
                } else {
                    assertTrue(pen + StockGraph.BODY <= StockGraph.RIGHT, "a body ends inside the chart");
                    pen += 6;
                    bodies++;
                }
            }
        }
        assertEquals(StockGraph.ORIGIN, pen);
        assertEquals(12, bodies, "a body per candle");
        assertEquals(12, wicks, "a wick per candle");
        assertTrue(dashes > 10, "a dashed line across: " + dashes);
        assertEquals(StockGraph.TAG_RIGHT - tagLeft, columns);
        assertEquals(StockGraph.label(candles.getLast().close()).length(), digits);
        assertEquals("", StockGraph.title(List.of(), 12));
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
        assertEquals(100, r.tax(3, 2, 0, 1));
        assertEquals(8 * 100, r.tax(10, 2, 0, 1));
        assertEquals(8 * 100 + 2 * 200, r.tax(12, 2, 0, 1), "past 10 chunks: double");
        assertEquals(15, r.progressiveLimit(1), "10 + 5 per member");
        assertEquals(8 * 100, r.tax(12, 4, 1, 1), "two people: 4 free, double only past 15");
        assertEquals(2 * 100, r.tax(3, 2, 0, 2), "세무 조사");
        assertEquals(0, r.tax(0, 2, 0, 1));
        // the second step: past 25 chunks (+10 a member) three times
        assertEquals(25, r.progressiveLimit2(0));
        assertEquals(35, r.progressiveLimit2(1));
        assertEquals(8 * 100 + 15 * 200 + 5 * 300, r.tax(30, 2, 0, 1), "a 30-chunk land alone: 5,300 a day");
        assertEquals(8 * 100 + 15 * 200 + 15 * 300, r.tax(40, 2, 0, 1));
        assertEquals(11 * 100 + 20 * 200 + 5 * 300, r.tax(40, 4, 1, 1), "two people: 4 free, double past 15, triple past 35");
        for (int size = 3; size < 60; size++) assertTrue(r.tax(size + 1, 2, 0, 1) > r.tax(size, 2, 0, 1), "every chunk costs more: " + size);
        // an old land.yml without the second step: only the first one
        LandRules old = new LandRules(2, 500, 50, 13, 10, 5, 2, 3, 3, 5);
        assertEquals(8 * 13 + 30 * 26, old.tax(40, 2, 0, 1));
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
        // the community floor: 0.5% of the target, and the items worth the reward at request prices
        assertEquals(129, RequestSpec.minShare(2500, 1500, 9, 1.3));
        assertEquals(39, RequestSpec.minShare(2000, 3000, 60, 1.3));
        assertEquals(193, RequestSpec.minShare(30000, 1000, 4, 1.3));
        assertEquals(100, RequestSpec.minShare(20000, 500, 10, 1.3), "the 0.5% binds");
        assertEquals(1, RequestSpec.minShare(10, 0, 0, 1.3));
        ShopCatalog shop = ShopCatalog.parse(ContentTest.yaml("shop.yml"), new ArrayList<>());
        for (RequestSpec.Community c : spec.community()) {
            double base = shop.sell().containsKey(c.item()) ? shop.sell().get(c.item()).base() : 0;
            long min = spec.minShare(c, base);
            assertTrue(min * base * spec.rewardMultiplier() >= c.reward(), c.name() + ": " + min + " x " + base + " G is worth less than " + c.reward() + " G");
            assertTrue(min <= c.target() / 10, c.name() + ": the floor " + min + " is over a tenth of " + c.target());
        }
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

    // ------------------------------------------------------------------------------------------
    // Fishing
    // ------------------------------------------------------------------------------------------

    private static FishSpec fish() {
        List<String> problems = new ArrayList<>();
        FishSpec f = FishSpec.parse(ContentTest.yaml("fish.yml"), problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        return f;
    }

    @Test
    void fishLiveWhereTheBiomeSays() {
        assertEquals("sea", FishSpec.habitat("minecraft:ocean", false));
        assertEquals("deep", FishSpec.habitat("minecraft:deep_ocean", false));
        assertEquals("warm", FishSpec.habitat("minecraft:deep_lukewarm_ocean", false));
        assertEquals("cold", FishSpec.habitat("minecraft:deep_frozen_ocean", false));
        assertEquals("coldriver", FishSpec.habitat("minecraft:frozen_river", false));
        assertEquals("coldriver", FishSpec.habitat("minecraft:snowy_taiga", false));
        assertEquals("river", FishSpec.habitat("minecraft:plains", false));
        assertEquals("coldriver", FishSpec.habitat("minecraft:grove", false));
        assertEquals("river", FishSpec.habitat("minecraft:cherry_grove", false), "the cherry grove is temperate");
        assertEquals("swamp", FishSpec.habitat("minecraft:swamp", false));
        assertEquals("mangrove", FishSpec.habitat("minecraft:mangrove_swamp", false));
        assertEquals("jungle", FishSpec.habitat("minecraft:sparse_jungle", false));
        assertEquals("mushroom", FishSpec.habitat("minecraft:mushroom_fields", false));
        assertEquals("cave", FishSpec.habitat("minecraft:plains", true), "under the ground it is cave water whatever the biome");
        assertEquals("cave", FishSpec.habitat("minecraft:lush_caves", false));

        FishSpec f = fish();
        assertEquals(98, f.species().size());
        assertEquals(12, f.groups().size());
        FishSpec.Species cod = f.species().get("cod");
        assertEquals("minecraft:cod", cod.item());
        // every water has its common fish at any hour, so a bite never comes up empty
        FishSpec.Moment noon = new FishSpec.Moment(6000, false, false, 4);
        java.util.Random r = new java.util.Random(1);
        for (String h : List.of("sea", "deep", "warm", "cold", "river", "coldriver", "swamp", "mangrove", "jungle", "cave", "mushroom")) {
            for (var tier : f.rarities().values()) {
                FishSpec.Species s = f.pick(h, noon, tier, r);
                assertNotNull(s, h + " " + tier.id());
                assertTrue((f.livesIn(s, h) || h.equals("mushroom") && f.livesIn(s, "sea")) && noon.is(s.when()), s.id() + " in " + h);
                assertTrue(f.rarities().get(s.rarity()).index() <= tier.index(), "never rarer than the roll");
            }
            // every water has fish of its own up to legend, so its codex page can be completed there
            assertTrue(f.species().values().stream().filter(s -> s.habitat().equals(h)).count() >= 3, h);
            assertTrue(f.species().values().stream().anyMatch(s -> s.habitat().equals(h) && s.rarity().equals("legend")), h + " has a legend");
        }
        // the mushroom island's commons come from the sea around it
        FishSpec.Rarity common = f.rarities().get("common");
        assertEquals("sea", f.pick("mushroom", noon, common, r).habitat());
        // the moment: a night fish only at night, a full-moon fish only under the full moon
        FishSpec.Moment night = new FishSpec.Moment(18000, false, false, 0);
        assertTrue(night.is("night") && night.is("fullmoon") && !night.is("dawn") && !night.is("rain"));
        assertFalse(new FishSpec.Moment(18000, false, false, 3).is("fullmoon"));
        assertTrue(new FishSpec.Moment(23500, false, false, 0).is("dawn"));
        assertTrue(new FishSpec.Moment(12000, true, false, 0).is("dusk"));
        // a time-and-weather fish needs its moment, and lives in its kind of water
        for (FishSpec.Species s : f.species().values()) {
            if (s.when() == null) continue;
            assertEquals("special", s.group());
            assertTrue(s.habitat().equals("fresh") || s.habitat().equals("salt"), s.id());
        }
    }

    @Test
    void biteWaitIsShorterThanVanillaAndShrinksWithLureAndTheFisher() {
        FishSpec f = fish();
        assertArrayEquals(new int[] {40, 240}, f.bite().ticks(0, 0), "2-12 s with no Lure, no fisher");
        assertArrayEquals(new int[] {22, 132}, f.bite().ticks(3, 0), "Lure III: -45%");
        assertArrayEquals(new int[] {13, 79}, f.bite().ticks(3, 0.40), "Lure III and a Lv30 fisher: -45% then -40%");
        assertArrayEquals(new int[] {4, 26}, f.bite().ticks(3, 0.80), "an event doubling the fisher's cut is capped at 80%");
        assertTrue(f.bite().ticks(10, 0.8)[0] >= 1, "never below one tick");
        JobSpec jobs = JobSpec.parse(ContentTest.yaml("jobs.yml"), new ArrayList<>());
        JobSpec.Job fisher = jobs.jobs().get("fisher");
        assertEquals(0.10, fisher.range("bite", 1, 30), 1e-9);
        assertEquals(0.50, fisher.range("bite", 30, 30), 1e-9);
        assertArrayEquals(new int[] {11, 66}, f.bite().ticks(3, 0.50), "Lure III and a Lv30 fisher: -45% then -50%");
    }

    @Test
    void biteRarityAndSize() {
        FishSpec f = fish();
        java.util.Random r = new java.util.Random(7);
        int[] n = new int[4];
        for (int i = 0; i < 100_000; i++) n[f.tier(0.15, r).index()]++;
        assertEquals(0.15 * 0.95, n[2] / 100_000.0, 0.005, "rare: 15% x 0.95");
        assertEquals(0.15 * 0.05, n[3] / 100_000.0, 0.003, "legend: 15% x 0.05 (legend-share)");
        assertEquals(0.85 * 0.35, n[1] / 100_000.0, 0.006, "uncommon: 35% of the rest");

        FishSpec.Species s = f.species().values().stream().filter(x -> x.rarity().equals("legend")).findFirst().orElseThrow();
        assertEquals(s.min(), FishSpec.size(s, 0, 0, false, 0), 0.05);
        assertEquals(s.max(), FishSpec.size(s, 1, 0, false, 0), 0.05);
        assertEquals(Math.round(s.max() * 1.15 * 10) / 10.0, FishSpec.size(s, 1, 6, true, 0.10), 1e-9, "at most 15% past the biggest");
        assertTrue(FishSpec.size(s, 0.5, 0, false, 0) < (s.min() + s.max()) / 2, "towards the small end");
        assertEquals(0.7, FishSpec.priceFactor(s, s.min()), 1e-9);
        assertEquals(1.5, FishSpec.priceFactor(s, s.max() * 1.1), 1e-9);
    }

    /** A fish stripped of its size in a machine slot sells as the smallest of its kind: taking it back out never raises its price. */
    @Test
    void aStrippedFishNeverSellsForMore() {
        for (FishSpec.Species s : fish().species().values()) {
            for (int i = 0; ; i++) {
                double size = s.min() + i * 0.1;
                if (size > s.max() * 1.15 + 1e-9) break;
                double f = FishSpec.priceFactor(s, size);
                if (i == 0) assertEquals(FishSpec.STRIPPED_FACTOR, f, 1e-9, s.id() + " at its smallest");
                else assertTrue(FishSpec.STRIPPED_FACTOR < f, s.id() + " " + size + " cm: " + f);
            }
        }
    }

    /** A Random whose ints are always 0 and booleans false: the ring starts at cell 0 and goes forward. */
    private static final class Fixed extends java.util.Random {
        @Override
        public int nextInt(int bound) {
            return 0;
        }

        @Override
        public boolean nextBoolean() {
            return false;
        }
    }

    private static void runTo(Ring ring, java.util.function.IntPredicate stop) {
        for (int i = 0; i < 2000 && !stop.test(ring.needle()); i++) ring.tick();
    }

    @Test
    void ringHitsMissesAndHearts() {
        FishSpec f = fish();
        FishSpec.Rarity common = f.rarities().get("common");
        Ring ring = new Ring(common, 0, 3, new Fixed());
        assertEquals(8, ring.zoneW, "common: 8 green cells");
        assertEquals(2, ring.perfW);
        assertEquals(14, ring.zoneStart, "the zone is ahead of the needle");
        assertEquals(Ring.Result.IGNORED, ring.press(), "a press during the opening pause does nothing");
        // to the gold cells and press: perfect
        runTo(ring, seg -> ring.pause == 0 && ring.inPerfect(seg));
        assertEquals(Ring.Result.PERFECT, ring.press());
        assertEquals(1, ring.hits);
        assertEquals(7, ring.zoneW, "a cell narrower each success");
        assertEquals(1.22, ring.speed, 1e-9, "and faster");
        // a press outside the zone costs a heart
        while (ring.pause > 0) ring.tick();
        int outside = ring.needle();
        assertFalse(ring.inZone(outside));
        assertEquals(Ring.Result.MISS, ring.press());
        assertEquals(2, ring.hearts);
        // twice round without a press costs one too
        while (ring.pause > 0) ring.tick();
        Ring.Result last = Ring.Result.IGNORED;
        for (int i = 0; i < 200 && last == Ring.Result.IGNORED; i++) last = ring.tick();
        assertEquals(Ring.Result.MISS, last);
        assertEquals(1, ring.hearts);
        // the second success wins a common fish
        while (ring.pause > 0) ring.tick();
        runTo(ring, seg -> ring.inZone(seg) && !ring.inPerfect(seg));
        assertEquals(Ring.Result.WON, ring.press());
        assertFalse(ring.allPerfect());
        // the last heart lets it go
        Ring r2 = new Ring(common, 0, 1, new Fixed());
        while (r2.pause > 0) r2.tick();
        assertEquals(Ring.Result.ESCAPED, r2.press());

        // legends: two red cells just before the zone (two hearts), the zone drifts
        FishSpec.Rarity legend = f.rarities().get("legend");
        Ring l = new Ring(legend, 0, 4, new Fixed());
        assertEquals(List.of(13, 12), l.trap);
        assertEquals(-1, l.drift, "Fixed: nextBoolean false = drift back");
        while (l.pause > 0) l.tick();
        runTo(l, l::inTrap);
        assertEquals(Ring.Result.TRAP, l.press());
        assertEquals(2, l.hearts);
        // the fisher's wider zone: +1% a level, at least 3 cells
        assertEquals(10, new Ring(common, 0.30, 3, new Fixed()).zoneW, "8 x 1.3 = 10.4");
        assertEquals(13, new Ring(legend, 0, 3, new Fixed()).zoneW);

        // the title: one glyph per drawn cell, each followed by the back space, total advance 14
        String t = RingGlyphs.title(new Ring(common, 0, 3, new Fixed()), "f");
        assertTrue(t.startsWith("<!shadow><white><font:economy:fishring>") && t.endsWith("</font></white>"));
        long cells = t.chars().filter(c -> c >= 0xE100 && c < 0xE1C0).count();
        assertEquals(8, cells, "8 zone cells (green and gold)");
        assertEquals(t.chars().filter(c -> c >= 0xE000 && c < 0xF000).count(), t.chars().filter(c -> c == 0xF001).count(), "a back space after every glyph");
        // MiniMessage reads every tag (no text shadow under the ring: a title has one, 4 px off)
        net.kyori.adventure.text.Component c = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(t);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        assertFalse(plain.contains("<") || plain.contains("shadow"), plain);
        String icon = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(RingGlyphs.icon(3) + " <gold>대구"));
        assertEquals(((char) 0xE003) + " 대구", icon);
        net.kyori.adventure.text.Component styled = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<!shadow>x");
        assertNotNull(styled.style().shadowColor(), "the shadow tag is understood");
        assertEquals(0, styled.style().shadowColor().alpha(), "and turns the shadow off");
    }

    /** A Random whose nextInt always draws `v` (capped by the bound), so newRound puts the zone at a chosen distance; stateless. */
    private static final class Draw extends java.util.Random {
        final int v;

        Draw(int v) {
            this.v = v;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(v, bound - 1);
        }

        @Override
        public boolean nextBoolean() {
            return false;
        }
    }

    /** A ring after h successes, each pressed on the first green cell (the same r, lv, v, h always gives the same ring). */
    private static Ring ringAt(FishSpec.Rarity r, int lv, int v, int h) {
        Ring ring = new Ring(r, lv * 0.01, 4, new Draw(v));
        for (int k = 0; k < h; k++) {
            while (ring.pause > 0) ring.tick();
            runTo(ring, ring::inZone);
            Ring.Result res = ring.press();
            assertTrue(res == Ring.Result.HIT || res == Ring.Result.PERFECT, "reached hit " + (k + 1));
        }
        return ring;
    }

    /** Every hit of every rarity is on green for at least 2 server ticks, at every fisher level, and no round starts on green. */
    @Test
    void ringWindowsAreTwoTicks() {
        FishSpec f = fish();
        for (FishSpec.Rarity r : f.rarities().values()) {
            boolean legend = r.index() >= 3;
            for (int h = 0; h < r.hits(); h++) {                       // the closed form, as BalanceTest R12
                int zone = Math.max(Ring.MIN_ZONE, r.zone() - h);
                double speed = r.speed() + Ring.HIT_SPEEDUP * h;
                assertTrue(zone >= 2 * speed + (legend ? 1 : 0), r.id() + " hit " + (h + 1) + ": zone " + zone + " < 2 x " + speed);
            }
            for (int lv = 0; lv <= 30; lv++) {
                for (int v = 0; v < 19; v++) {
                    for (int h = 0; h < r.hits(); h++) {
                        Ring ring = ringAt(r, lv, v, h);
                        String at = r.id() + " Lv" + lv + " draw " + v + " hit " + (h + 1);
                        assertFalse(ring.inZone(ring.needle()), at + ": the round starts on green");   // R12b
                        while (ring.pause > 0) ring.tick();
                        int on = 0;
                        for (int i = 0; i < 400; i++) {
                            ring.tick();
                            if (ring.inZone(ring.needle())) on++;
                            else if (on > 0) break;
                        }
                        assertTrue(on >= 2, at + ": " + on + " tick(s) on green");
                    }
                }
            }
        }
    }

    @Test
    void adminFishingTestNames() {
        FishSpec f = fish();
        assertEquals("legend", AdminCommands.rarityId(f, "전설"));
        assertEquals("common", AdminCommands.rarityId(f, "흔함"));
        assertEquals("pearl", AdminCommands.rarityId(f, "진주"));
        assertNull(AdminCommands.rarityId(f, "아무거나"));
        assertEquals("cod", AdminCommands.speciesOf(f, "대구").id());
        assertEquals("cod", AdminCommands.speciesOf(f, "cod").id());
        var twoWords = f.species().values().stream().filter(x -> x.name().contains(" ")).findFirst().orElseThrow();
        assertEquals(twoWords.id(), AdminCommands.speciesOf(f, twoWords.name().replace(" ", "_")).id(), "tab completion writes spaces as _");
        assertNull(AdminCommands.speciesOf(f, "없는물고기"));
    }

    /** /시세: by id (vanilla in English), by a custom item's own name, or by its 판매 category's name. */
    @Test
    void priceSearchFindsIdsNamesAndCategories() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.loadFromString("""
                sell:
                  minerals:
                    name: "광물"
                    items:
                      diamond: { base: 60, q: 8 }
                      iron_ingot: { base: 10, q: 32 }
                  gems:
                    name: "보석·금속"
                    items:
                      economy:ruby: { base: 40, q: 16 }
                """);
        java.util.List<String> problems = new java.util.ArrayList<>();
        dev.stsv.economy.shop.ShopCatalog c = dev.stsv.economy.shop.ShopCatalog.parse(y, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        java.util.function.Function<String, String> names = id -> id.equals("economy:ruby") ? "루비" : "item." + id.replace(':', '.');
        assertEquals(java.util.List.of("minecraft:diamond"), c.search("diamond", names));
        assertEquals(java.util.List.of("minecraft:iron_ingot"), c.search("Iron Ingot", names));
        assertEquals(java.util.List.of("economy:ruby"), c.search("루비", names));
        assertEquals(java.util.List.of("minecraft:diamond", "minecraft:iron_ingot"), c.search("광물", names));
        assertTrue(c.search("다이아몬드", names).isEmpty(), "no Korean names for vanilla items on the server: the search says so");
    }

    /** 상인 행렬: each event brings one of the rare entries (the same one for everyone), with its own stock counter. */
    @Test
    void merchantBringsOneRareEntryPerEvent() {
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("merchant.items", java.util.List.of(java.util.Map.of("item", "totem_of_undying", "price", 6000, "stock", 2)));
        y.set("merchant.rare", java.util.List.of(java.util.Map.of("item", "stsv:crown_voucher", "price", 250000, "stock", 1),
                java.util.Map.of("item", "stsv:halo_voucher", "price", 250000, "stock", 1)));
        java.util.List<String> problems = new java.util.ArrayList<>();
        dev.stsv.economy.shop.ShopCatalog c = dev.stsv.economy.shop.ShopCatalog.parse(y, problems);
        assertTrue(problems.isEmpty(), problems.toString());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int e = 1; e <= 20; e++) {
            var shown = c.merchant().shown(String.valueOf(e));
            assertEquals(2, shown.size());
            assertEquals(shown, c.merchant().shown(String.valueOf(e)), "same event, same pick");
            assertTrue(shown.get(1).index() >= 1000, "rare stock counters do not share the goods' keys");
            seen.add(shown.get(1).id());
        }
        assertEquals(java.util.Set.of("stsv:crown_voucher", "stsv:halo_voucher"), seen, "both come up over time");
    }

    /**
     * The sell price curve as shipped, for one player (prices are per player, so how many play does not enter): selling a lot
     * is a loss, not a wall (owner decision: a gentler curve). One stack of melon liqueur (q 4) pays over 60% of its base
     * (45% before), ten stacks still about a third, and only past 15 quotas does the price reach the floor.
     */
    @Test
    void sellPriceFallsGently() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                java.nio.file.Path.of(System.getProperty("stsv.content")).resolve("economy/shop.yml").toFile());
        dev.stsv.economy.shop.ShopCatalog c = dev.stsv.economy.shop.ShopCatalog.parse(y, new java.util.ArrayList<>());
        var curve = c.curve();
        double e = curve.exponent();
        // an item one player sells 64 of a day (a stack): after that stack their price still pays well over half
        double afterQuota = dev.stsv.economy.shop.PriceModel.unit(10, 64, curve.quota(64), c.floor(), 1, e) / 10;
        assertTrue(afterQuota > 0.85, "after one day's stack: " + afterQuota);
        // melon liqueur (base 442, q 4): 1 stack / 10 stacks, average per unit
        double one = dev.stsv.economy.shop.PriceModel.total(442, 0, curve.quota(4), c.floor(), 1, 64, e) / 64 / 442;
        double ten = dev.stsv.economy.shop.PriceModel.total(442, 0, curve.quota(4), c.floor(), 1, 640, e) / 640 / 442;
        assertTrue(one > 0.6, "one stack: " + one);
        assertTrue(ten > 0.3 && ten < one, "ten stacks: " + ten);
        // the floor exists but is far: reached only after 15 quotas
        assertTrue(c.floor() >= 0.2 && c.floor() <= 0.3, "floor " + c.floor());
        assertFalse(dev.stsv.economy.shop.PriceModel.atFloor(10, 14 * curve.quota(64), curve.quota(64), c.floor(), e));
        assertEquals(c.floor(), dev.stsv.economy.shop.PriceModel.unit(10, 100 * curve.quota(64), curve.quota(64), c.floor(), 1, e) / 10, 1e-9);
        assertTrue(curve.hourlyRecovery() > 0 && curve.hourlyRecovery() < 0.5);
    }

    /** 판매 소득세: progressive on a player's shop sales of the day; only the part above each threshold, at its rate. */
    @Test
    void theShopIncomeTaxIsProgressive() throws Exception {
        List<String> problems = new ArrayList<>();
        dev.stsv.economy.shop.IncomeTax t = dev.stsv.economy.shop.IncomeTax.parse(ContentTest.yaml("shop.yml"), problems);
        assertEquals(List.of(), problems);
        assertEquals(0, t.taxOn(20_000), "a small seller pays nothing");
        assertEquals(0, t.taxOn(30_000));
        assertEquals(2_000, t.taxOn(50_000));
        assertEquals(7_000, t.taxOn(80_000));
        assertEquals(26_000, t.taxOn(150_000));
        // one big sale = the same sales one by one (only the day's total counts)
        long split = t.tax(0, 25_000) + t.tax(25_000, 40_000) + t.tax(65_000, 85_000);
        assertEquals(t.taxOn(150_000), split);
        // selling more never leaves less
        for (long g = 0; g < 300_000; g += 777) assertTrue(g + 777 - t.taxOn(g + 777) >= g - t.taxOn(g), "at " + g);
        assertEquals(0.3, t.rateAt(120_000), 1e-9);
        assertEquals(0, t.rateAt(10_000), 1e-9);
        // a file without income-tax: no tax; a bad bracket is a problem
        assertEquals(0, dev.stsv.economy.shop.IncomeTax.parse(new YamlConfiguration(), problems).taxOn(1_000_000));
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("income-tax", List.of(Map.of("over", 100, "rate", 1.5)));
        List<String> p2 = new ArrayList<>();
        dev.stsv.economy.shop.IncomeTax.parse(bad, p2);
        assertEquals(1, p2.size());
    }

    /** The codex achievements count what the codex holds: their goals match the shop's 판매 categories. */
    @Test
    void codexAchievementGoalsMatchTheCodex() {
        java.nio.file.Path content = java.nio.file.Path.of(System.getProperty("stsv.content")).resolve("economy");
        dev.stsv.economy.shop.ShopCatalog shop = dev.stsv.economy.shop.ShopCatalog.parse(
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(content.resolve("shop.yml").toFile()), new java.util.ArrayList<>());
        org.bukkit.configuration.file.YamlConfiguration a = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                content.resolve("achievements/codex.yml").toFile());
        int total = shop.sellCategories().stream().mapToInt(c -> c.items().size()).sum();
        assertEquals(total, a.getInt("achievements.codex_all.goal"), "codex_all");
        for (String key : a.getConfigurationSection("achievements").getKeys(false)) {
            String value = a.getString("achievements." + key + ".value");
            String[] parts = value.split(":");
            if (parts.length < 3) continue;
            var cat = shop.sellCategories().stream().filter(c -> c.id().equals(parts[2])).findFirst();
            assertTrue(cat.isPresent(), key + ": no tab " + parts[2]);
            assertTrue(a.getInt("achievements." + key + ".goal") <= cat.get().items().size(), key);
        }
    }

    // ------------------------------------------------------------------------------------------ missions

    private static dev.stsv.economy.mission.MissionSpec.Mission mission(String id, String stat, String job, String group) {
        return new dev.stsv.economy.mission.MissionSpec.Mission(id, id, stat, 10, job, group == null ? stat : group, "", "minecraft:paper", 10,
                new dev.stsv.economy.mission.MissionSpec.Reward(100, 0, List.of()));
    }

    /** One mission of the player's job first, then missions for everyone; never two of a group. */
    @Test
    void aMissionSetHasOneJobMissionAndNoTwoOfAGroup() {
        var all = new java.util.LinkedHashMap<String, dev.stsv.economy.mission.MissionSpec.Mission>();
        for (var m : List.of(mission("ores", "ores_mined", "miner", null), mission("gems", "made_miner", "miner", "machine"),
                mission("fish", "fish_caught", "fisher", null), mission("dig", "blocks_broken", null, null),
                mission("mobs", "mob_kills", null, null), mission("sell", "shop_sold", null, "shop"), mission("income", "shop_income", null, "shop"),
                mission("machine", "machine_made", null, "machine"))) all.put(m.id(), m);
        var k = new dev.stsv.economy.mission.MissionSpec.Kind(3, 1, dev.stsv.economy.mission.MissionSpec.Reward.NONE, all);
        for (int seed = 0; seed < 300; seed++) {
            var miner = dev.stsv.economy.mission.MissionSpec.draw(k, "miner", new java.util.Random(seed));
            assertEquals(3, miner.size());
            assertEquals("miner", miner.getFirst().job());
            assertTrue(miner.subList(1, 3).stream().allMatch(m -> m.job() == null), "the rest is for everyone");
            assertEquals(3, miner.stream().map(dev.stsv.economy.mission.MissionSpec.Mission::group).distinct().count(), "no two of a group");
            // no job (or a job with no missions): all three for everyone
            for (String job : java.util.Arrays.asList(null, "cook")) {
                var none = dev.stsv.economy.mission.MissionSpec.draw(k, job, new java.util.Random(seed));
                assertEquals(3, none.size());
                assertTrue(none.stream().allMatch(m -> m.job() == null));
            }
            // swapping: a job mission for another of that job, one for everyone for another for everyone, never a group already there
            var set = new ArrayList<>(miner);
            for (int i = 0; i < 3; i++) {
                var next = dev.stsv.economy.mission.MissionSpec.reroll(k, set, i, new java.util.Random(seed + i));
                assertNotNull(next);
                assertFalse(set.stream().anyMatch(m -> m.id().equals(next.id())), "a new mission");
                if (i == 0) {
                    // another miner mission if one fits (gems is of the machine group), else one for everyone
                    boolean gemsFits = set.subList(1, 3).stream().noneMatch(m -> m.group().equals("machine"));
                    assertEquals(gemsFits ? "miner" : null, next.job());
                } else assertNull(next.job());
                int at = i;
                assertTrue(java.util.stream.IntStream.range(0, 3).filter(x -> x != at).noneMatch(x -> set.get(x).group().equals(next.group())));
            }
        }
        // the only other miner mission shares a group with a mission already in the set: falls back to one for everyone
        var set = List.of(all.get("ores"), all.get("machine"), all.get("dig"));
        var swapped = dev.stsv.economy.mission.MissionSpec.reroll(k, set, 0, new java.util.Random(1));
        assertNull(swapped.job());
        assertFalse(List.of("machine", "blocks_broken").contains(swapped.group()));
    }

    @Test
    void missionRewardItemsParse() {
        assertEquals(new dev.stsv.economy.mission.MissionSpec.ItemReward("minecraft:diamond", 3), dev.stsv.economy.mission.MissionSpec.item("diamond x3"));
        assertEquals(new dev.stsv.economy.mission.MissionSpec.ItemReward("economy:ruby", 1), dev.stsv.economy.mission.MissionSpec.item("economy:ruby"));
        assertNull(dev.stsv.economy.mission.MissionSpec.item("diamond x0"));
        assertNull(dev.stsv.economy.mission.MissionSpec.item("two words"));
    }
}
