package dev.stsv.economy.store;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * store.yml's 3차 + 보완안 (README "편의점" 경력 · 야간 · 발주 · 단골 메뉴 · 2인 근무 · 남의 가게 알바 · 버스 · 러시 · 스캔 단축 ·
 * 하루 이벤트): the ranks and what they open, the night pay, the order's bonus, the two workers' and the owner's shares, the
 * rush, the events' picking and the inspection.
 */
class StoreLifeTest {

    static StoreLife life() {
        List<String> problems = new ArrayList<>();
        StoreLife l = StoreLife.parse(YamlConfiguration.loadConfiguration(new File("content/economy/store.yml")), StoreSpecTest.spec(), problems);
        assertEquals(List.of(), problems);
        return l;
    }

    @Test
    void fiveRanksByCustomersServed() {
        StoreLife l = life();
        assertEquals(List.of("수습", "알바", "선임", "점장 대리", "점장"), l.ranks().stream().map(StoreLife.Rank::name).toList());
        assertEquals("수습", l.rank(0).name());
        assertEquals("수습", l.rank(49).name());
        assertEquals("알바", l.rank(50).name());
        assertEquals("선임", l.rank(300).name());
        assertEquals("점장 대리", l.rank(800).name());
        assertEquals("점장", l.rank(2000).name());
        assertEquals("점장", l.rank(1_000_000).name());
        assertEquals(l.top(), l.rank(2000));
        assertNull(l.next(l.top()));
        assertEquals("알바", l.next(l.rank(0)).name());
        // by id or by name (the admin's 경력 command: 점장대리 without the space too)
        assertEquals(l.rankOf("deputy"), l.rankOf("점장대리"));
        assertEquals(l.rankOf("deputy"), l.rankOf("점장 대리"));
        assertNull(l.rankOf("사장"));
        assertTrue(l.reached(300, "senior"));
        assertFalse(l.reached(299, "senior"));
        assertFalse(l.reached(10_000, "nobody"));
        // about 70 customers an hour: 알바 within the first hour, 점장 after a long while
        assertTrue(l.rankOf("parttime").at() <= 70);
        assertTrue(l.top().at() >= 70 * 20);
        assertEquals("economy:store_manager", l.title());
        YamlConfiguration titles = YamlConfiguration.loadConfiguration(new File("content/economy/titles/titles.yml"));
        assertTrue(titles.contains("store_manager"), "점장's title exists");
    }

    @Test
    void scanningFasterWithTheRankAndAllAtOnceForARegular() {
        StoreLife l = life();
        assertEquals(1, StoreLife.scanBatch(l.rankOf("trainee"), false, 5));
        assertEquals(1, StoreLife.scanBatch(l.rankOf("parttime"), false, 5));
        assertEquals(2, StoreLife.scanBatch(l.rankOf("senior"), false, 5), "선임: two a click");
        assertEquals(2, StoreLife.scanBatch(l.rankOf("deputy"), false, 5));
        assertEquals(3, StoreLife.scanBatch(l.rankOf("manager"), false, 5), "점장: three a click");
        assertEquals(1, StoreLife.scanBatch(l.rankOf("manager"), false, 1), "never more than are left");
        assertEquals(5, StoreLife.scanBatch(l.rankOf("trainee"), true, 5), "묶음 스캔: a regular's basket at once");
        assertEquals(0, StoreLife.scanBatch(l.rankOf("manager"), true, 0));
        // the till scans the clicked one first, then the next unscanned
        Checkout c = new Checkout(List.of("a", "b", "c", "d"), List.of(100, 200, 300, 400), Checkout.Method.CASH);
        assertEquals(List.of(2, 0, 1), c.scanMany(2, 3));
        assertEquals(List.of(), c.scanMany(2, 3), "already scanned");
        assertEquals(List.of(3), c.scanMany(3, 3));
        assertTrue(c.allScanned());
        assertEquals(1000, c.total());
    }

    @Test
    void theCardTerminalDeadMeansCash() {
        Checkout c = new Checkout(List.of("a", "b"), List.of(1200, 1700), Checkout.Method.CARD);
        c.scan(0);
        c.ask("warm");
        Checkout cash = c.asCash();
        assertEquals(Checkout.Method.CASH, cash.method());
        assertTrue(cash.scanned(0));
        assertFalse(cash.scanned(1));
        assertEquals(List.of("warm"), cash.asks());
        assertEquals(2900, cash.bill());
        cash.scan(1);
        cash.tender(5000);
        assertEquals(2100, cash.change());
    }

    @Test
    void nightShiftsFromTheSecondRankPayMore() {
        StoreLife l = life();
        assertTrue(l.night().at(22));
        assertTrue(l.night().at(3));
        assertFalse(l.night().at(12));
        assertFalse(l.mayWork(22, 10), "a 수습 does not work nights");
        assertTrue(l.mayWork(12, 10));
        assertTrue(l.mayWork(22, 50));
        assertEquals(1300, l.nightPay(1000, 22, 50), "알바 at night: × 1.3");
        assertEquals(1000, l.nightPay(1000, 12, 50), "by day as usual");
        assertEquals(1000, l.nightPay(1000, 22, 0), "a 수습 who stayed past dusk: as usual");
        // drunk and dawn customers come more often on a night shift
        StoreSpec spec = StoreSpecTest.spec();
        Set<String> allowed = spec.customers().keySet();
        Random r = new Random(5);
        int plain = 0, boosted = 0;
        for (int i = 0; i < 4000; i++) {
            if ("drunk".equals(spec.pickType(23, allowed, r, Map.of()))) plain++;
            if ("drunk".equals(spec.pickType(23, allowed, r, l.night().boost()))) boosted++;
        }
        assertTrue(boosted > plain * 1.2, plain + " vs " + boosted);
    }

    @Test
    void theOrderNeverLosesMoneyAndPaysWhenItMatchesTheDay() {
        StoreLife l = life();
        Set<Integer> summer = Set.of(6, 7, 8);
        assertEquals("rain", StoreLife.demand(true, true, 7, summer, true), "rain wins");
        assertEquals("market", StoreLife.demand(false, true, 7, summer, true), "then a 장날");
        assertEquals("cold", StoreLife.demand(false, false, 7, summer, true), "then summer");
        assertEquals("night", StoreLife.demand(false, false, 11, summer, true), "then a weekend");
        assertEquals("snack", StoreLife.demand(false, false, 11, summer, false));
        for (String d : StoreLife.DEMANDS) assertNotNull(l.order().groups().get(d), d);
        List<String> rainy = List.of("umbrella", "coffee_cup", "chips");
        long hit = l.orderBonus("rain", "rain", rainy), miss = l.orderBonus("rain", "snack", rainy);
        assertEquals(2 * l.order().hit(), hit, "two of the group sold, matched");
        assertEquals(2 * l.order().miss(), miss, "not matched: a little, never a loss");
        assertTrue(hit > miss && miss >= 0);
        assertEquals(0, l.orderBonus(null, "rain", rainy), "no order: nothing");
        assertEquals(0, l.orderBonus("rain", "rain", List.of("chips")), "nothing of the group: nothing");
        // the tablet pages through the groups and comes round
        String first = l.order().after(null).id();
        String id = first;
        for (int i = 0; i < l.order().groups().size(); i++) id = l.order().after(id).id();
        assertEquals(first, id);
        assertEquals("senior", l.order().rank());
    }

    @Test
    void regularsAskForTheirUsualFromAShelf() {
        StoreLife l = life();
        StoreSpec spec = StoreSpecTest.spec();
        StoreLife.Regulars r = l.regulars();
        for (String kind : spec.customers().keySet()) {
            String usual = r.usual(kind);
            assertNotNull(usual, kind);
            assertFalse(spec.products().get(usual).place().equals("counter"), kind + ": fetched from a shelf");
        }
        assertEquals("banana_milk", r.usual("student"));
        assertFalse(r.asks("student", r.at() - 1, 0), "not yet a regular");
        assertTrue(r.asks("student", r.at(), 0));
        assertFalse(r.asks("student", r.at(), 0.99), "only now and then");
        assertFalse(r.asks("nobody", 100, 0));
        assertTrue(r.regular(5));
        assertFalse(r.regular(4));
        for (String k : List.of("ask", "hint", "right", "wrong")) assertFalse(r.lines(k).isEmpty(), k);
        for (String h : r.lines("hint")) assertTrue(h.contains("{item}"), h);
    }

    @Test
    void twoWorkersAndHiredWorkersShareThePay() {
        StoreLife l = life();
        StoreLife.Staff s = l.staff();
        assertEquals(0.4, s.share(false, false, 0.4), 1e-9, "a 직영점: its 40%");
        assertEquals(1.0, s.share(true, true, 0.4), 1e-9, "the owner in their own store: all of it");
        assertEquals(0.4, s.share(true, false, 0.4), 1e-9, "a hired worker: 40%");
        assertEquals(200, s.ownerCut(400), "the owner's 20% of the same earning (the worker's 40% = 400)");
        assertEquals(0, s.ownerCut(0));
        assertTrue(s.twoIn(null), "two may work a 직영점");
        assertTrue(s.twoIn("large"));
        assertFalse(s.twoIn("alley"));
        // a hired worker's shift: the worker and the owner paid as it goes, the same as one payout at the end
        StoreSpec spec = StoreSpecTest.spec();
        ShiftLog log = new ShiftLog();
        long worker = 0, owner = 0;
        for (int i = 0; i < 10; i++) {
            log.served(5, spec.earn(95, 1, true), 3000, true);
            long g = log.settle(spec, s.hiredShare(), false);
            worker += g;
            owner += s.ownerCut(g);
        }
        long rest = log.settle(spec, s.hiredShare(), true);
        worker += rest;
        owner += s.ownerCut(rest);
        assertEquals(log.payout(spec, s.hiredShare()).pay(), worker);
        assertEquals(Math.round(worker * s.ownerCut() / s.hiredShare()), owner, 2.0);
        assertTrue(owner < worker, "the owner gets less than the one who works");
        // the 진열 worker earns per task and a little per customer, both before the share
        ShiftLog helper = new ShiftLog();
        helper.bonus(s.restock());
        helper.bonus(s.chore());
        helper.bonus(s.assist());
        assertEquals(Math.round((s.restock() + s.chore() + s.assist()) * 0.4), helper.settle(spec, 0.4, false));
        assertTrue(s.busy() < 1, "two at work: busier");
    }

    @Test
    void theRushClearsWithEnoughCustomersInTime() {
        StoreLife l = life();
        StoreLife.Rush r = l.rush();
        assertEquals(0, r.window(7));
        assertEquals(0, r.window(8));
        assertEquals(-1, r.window(9));
        assertEquals(1, r.window(16));
        assertEquals(-1, r.window(12));
        assertTrue(r.every() < 1, "customers come closer together");
        StoreLife.RushRun run = new StoreLife.RushRun(0, 3, 60);
        assertEquals(StoreLife.RushRun.State.ON, run.served(1000));
        assertEquals(StoreLife.RushRun.State.ON, run.served(2000));
        assertEquals(StoreLife.RushRun.State.CLEARED, run.served(3000), "the third in time: cleared");
        assertEquals(StoreLife.RushRun.State.CLEARED, run.served(4000), "once");
        StoreLife.RushRun late = new StoreLife.RushRun(0, 3, 60);
        late.served(1000);
        assertEquals(StoreLife.RushRun.State.ON, late.tick(59_000));
        assertEquals(1, late.left(59_000));
        assertEquals(StoreLife.RushRun.State.MISSED, late.tick(61_000));
        assertEquals(StoreLife.RushRun.State.MISSED, late.served(61_500), "too late: no bonus");
        assertEquals(0, late.left(61_000));
        assertTrue(r.bonus() > 0);
    }

    @Test
    void theDaysEventsArePickedByTheirChances() {
        StoreLife.Events e = life().events();
        double o = e.outage().chance(), g = e.group().chance(), i = e.inspect().chance();
        assertEquals(StoreLife.Event.OUTAGE, e.pick(0));
        assertEquals(StoreLife.Event.OUTAGE, e.pick(o - 1e-9));
        assertEquals(StoreLife.Event.GROUP, e.pick(o));
        assertEquals(StoreLife.Event.INSPECT, e.pick(o + g));
        assertNull(e.pick(o + g + i));
        assertNull(e.pick(0.999));
        // occasional: under one in ten a check, one a game day per store
        assertTrue(o + g + i < 0.1);
        assertEquals(1, e.perDay());
        Random r = new Random(3);
        Map<StoreLife.Event, Integer> n = new HashMap<>();
        for (int k = 0; k < 100_000; k++) {
            StoreLife.Event x = e.pick(r.nextDouble());
            if (x != null) n.merge(x, 1, Integer::sum);
        }
        assertEquals(o, n.get(StoreLife.Event.OUTAGE) / 100_000.0, 0.005);
        assertEquals(g, n.get(StoreLife.Event.GROUP) / 100_000.0, 0.005);
        assertEquals(i, n.get(StoreLife.Event.INSPECT) / 100_000.0, 0.005);
        assertTrue(e.group().min() >= 8 && e.group().max() <= 12);
        assertEquals("economy:entity/bus/hq_inspector", e.inspect().skin());
        for (int k = 0; k < 50; k++) {
            int b = StoreLife.between(e.group().min(), e.group().max(), r);
            assertTrue(b >= e.group().min() && b <= e.group().max());
        }
    }

    @Test
    void theInspectorScoresTheStore() {
        StoreLife.Inspect in = life().events().inspect();
        assertEquals(100, StoreLife.Inspect.score(0, 0, 0, false, 0));
        assertEquals(StoreLife.Inspect.Verdict.GOOD, in.verdict(StoreLife.Inspect.score(0, 1, 1, false, 1)), "a little mess still passes");
        int bad = StoreLife.Inspect.score(2, 3, 2, true, 3);
        assertEquals(StoreLife.Inspect.Verdict.BAD, in.verdict(bad), "empty shelves, 폐기 and mess: a fine");
        assertEquals(StoreLife.Inspect.Verdict.OK, in.verdict(in.fail()));
        assertEquals(0, StoreLife.Inspect.score(9, 9, 9, true, 9), "never below 0");
        assertTrue(in.fine() < in.good(), "the fine is small");
    }

    @Test
    void theBusLinkHasItsLines() {
        StoreLife.BusLink b = life().bus();
        for (String k : List.of("ask", "driver", "none", "greet")) assertFalse(b.lines(k).isEmpty(), k);
        for (String d : b.lines("driver")) assertTrue(d.contains("{driver}"), d);
        assertEquals(24, b.radius(), 1e-9);
    }

    @Test
    void aBrokenConfigIsTold() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/store.yml"));
        y.set("career.ranks.senior.at", 10);
        y.set("regulars.menu.student", "cigarette");
        y.set("order.groups.rain.products", List.of("nothing"));
        y.set("night.rank", "boss");
        List<String> problems = new ArrayList<>();
        StoreLife.parse(y, StoreSpecTest.spec(), problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("career.ranks.senior")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("regulars.menu.student")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("order.groups.rain")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("night.rank")), problems.toString());
    }

    @Test
    void theTabletAndTheBreakerStandInTheStore() {
        StoreLayout.Layout l = StoreLayout.of();
        StoreLayout.Piece t = l.piece("order");
        assertNotNull(t);
        assertEquals("economy:store_order_tablet", t.item());
        assertEquals(1, t.width());
        assertEquals(1, t.depth());
        // beside the counter's end, behind it (the worker's side)
        StoreLayout.Piece counter = l.piece("counter");
        assertTrue(t.x0() < counter.x0() && Math.abs(t.z0() - counter.z1()) <= 1);
        boolean lever = false;
        for (var b : l.blocks()) if (b.data().startsWith("minecraft:lever") && b.x() == StoreDay.BREAKER[0] && b.y() == StoreDay.BREAKER[1] && b.z() == StoreDay.BREAKER[2]) lever = true;
        assertTrue(lever, "the breaker on the back wall");
        boolean lamp = false;
        for (var b : l.blocks()) if (b.data().equals(StoreDay.LAMP)) lamp = true;
        assertTrue(lamp, "the roof has lamps to put out");
        // every smaller store has the tablet too
        for (StoreRules.Tier tier : StoreRulesTest.rules().tiers().values()) assertNotNull(StoreLayout.of(tier.without()).piece("order"), tier.id());
    }

    // ---- 점검 규칙-5

    @Test
    void aRegularAsksForTheirUsualOnlyWhenItCanBeFetched() {
        assertTrue(StoreLife.usualInStock(true, 3));
        assertFalse(StoreLife.usualInStock(true, 0), "the shelf is empty: nobody asks for what cannot be handed over");
        assertTrue(StoreLife.usualInStock(false, -1), "the freezer and the counter never run out");
    }
}
