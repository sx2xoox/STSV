package dev.stsv.economy.dock;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.economy.event.ActiveEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사건 at the 하역장 (README "사건"): 품귀 / 시장 폭락 / 고가 매입상 change one dock's price for one category (or everything),
 * only for loads sealed while they run, and the effect on a typical dock hour stays small (the owner's rule).
 */
class DockEventTest {

    @TempDir
    Path dir;

    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    static ActiveEvent shortage(String dock, String category, double f) {
        return new ActiveEvent(1, "shortage", 0, 1000, Map.of("dock-factor", String.valueOf(f), "dock", dock, "category", category));
    }

    @Test
    void theGoodsHaveTheCategoryOfTheMachineThatMakesThem() {
        DockGoods g = DockTest.goods();
        assertEquals("kitchen", g.category("economy:kimchi"));
        assertEquals("gem_bench", g.category("economy:ruby_ring"));
        assertEquals("still", g.category("economy:soju"));
        assertEquals("forge", g.category("economy:steel_ingot"));
        assertNull(g.category("minecraft:diamond"), "raw: no category (the docks do not buy it)");
        for (String id : g.finished()) assertTrue(g.category(id) != null, "every finished good has a machine: " + id);
    }

    @Test
    void anEventChangesOneCategoryAtOneDock() {
        List<DockBoost> b = DockBoost.of(List.of(shortage("서해", "kitchen", 1.12),
                new ActiveEvent(2, "premium_buyer", 0, 1000, Map.of("dock-factor", "1.1", "dock", "동해", "category", "all")),
                new ActiveEvent(3, "crash", 0, 1000, Map.of("dock-factor", "0.9", "dock", "서해", "category", "still")),
                new ActiveEvent(4, "harvest", 0, 1000, Map.of("machine-speed", "1.3")),          // not a dock event
                new ActiveEvent(5, "shortage", 0, 10, Map.of("dock-factor", "1.12", "dock", "서해", "category", "forge"))), 500);   // over
        assertEquals(3, b.size());
        assertEquals(1.12, DockBoost.at(b, "서해", "kitchen").factor(), 1e-12);
        assertEquals(List.of("shortage"), DockBoost.at(b, "서해", "kitchen").types());
        assertEquals("+12", DockBoost.at(b, "서해", "kitchen").pct());
        assertEquals(0.9, DockBoost.at(b, "서해", "still").factor(), 1e-12);
        assertEquals("-10", DockBoost.at(b, "서해", "still").pct());
        assertTrue(DockBoost.at(b, "서해", "forge").none(), "the ended one does nothing");
        assertTrue(DockBoost.at(b, "동해", "kitchen").factor() == 1.1 && DockBoost.at(b, "동해", "forge").factor() == 1.1, "고가 매입상: everything");
        assertTrue(DockBoost.at(b, "남해", "kitchen").none(), "other docks as they were");
        // two on the same goods multiply
        List<DockBoost> two = DockBoost.of(List.of(shortage("서해", "kitchen", 1.12),
                new ActiveEvent(2, "premium_buyer", 0, 1000, Map.of("dock-factor", "1.1", "dock", "서해", "category", "all"))), 1);
        assertEquals(1.12 * 1.1, DockBoost.at(two, "서해", "kitchen").factor(), 1e-12);
        assertEquals(List.of("shortage", "premium_buyer"), DockBoost.at(two, "서해", "kitchen").types());
    }

    @Test
    void onlyALoadSealedDuringTheEventGetsItAndKeepsIt() throws Exception {
        DockGoods g = DockTest.goods();
        Dock terminal = new Dock("본사", DockLayout.Kind.TERMINAL, "world", 0, 64, 0, 0, "", 0);
        Dock sea = new Dock("서해", DockLayout.Kind.PORT, "world", 0, 64, 7_000, 0, "", 1).withPrice(110);
        List<DockBoost> b = DockBoost.of(List.of(shortage("서해", "kitchen", 1.12)), 1);
        java.util.function.Function<String, DockBoost.Hit> now = id -> DockBoost.at(b, "서해", g.category(id));
        var sell = DockTest.shop().sell();
        double kimchi = 100 * sell.get("economy:kimchi").base() * 1.1, ring = 10 * sell.get("economy:ruby_ring").base() * 1.1;
        // the inspection shows it, the seal keeps it
        DockModule.Offer shown = DockModule.offers(g, TerminalTest.BED, terminal, List.of(sea), d -> now).getFirst();
        assertEquals((long) Math.floor(kimchi * 1.12 + ring + 1e-6), shown.total(), "kimchi +12 % on top of the dock's 110 %, the ring as it was");
        DockGoods.Line k = shown.plan().lines().stream().filter(l -> l.id().equals("economy:kimchi")).findFirst().orElseThrow();
        assertEquals(List.of("shortage"), k.hit().types(), "the 품귀! mark on the kimchi line");
        CargoTrip t = CargoTrip.seal(42, OWNER, "본사", sea, g, TerminalTest.BED, 1000L, 120, 7000, 5000L, now);
        assertEquals(shown.total(), t.total());
        assertEquals(1.12, t.hits().get("economy:kimchi").factor(), 1e-9);
        assertNull(t.hits().get("economy:ruby_ring"), "only goods with an event are on record");
        assertEquals(CargoTrip.manifest(g.plan(TerminalTest.BED).lines()), t.manifest(), "the goods that go do not depend on the event");
        // the event ends: the sealed one keeps its value; one sealed now does not get it
        CargoTrip later = CargoTrip.seal(43, OWNER, "본사", sea, g, TerminalTest.BED, 1000L, 120, 7000, 6000L);
        assertEquals((long) Math.floor(kimchi + ring + 1e-6), later.total());
        assertEquals("", later.boost());
        assertEquals(t.total(), t.end(CargoTrip.Status.DONE, 9000).total());
        // and over a restart
        Db db = DatabaseModule.sqlite(dir.resolve("e.db"), Logger.getLogger("test"));
        try {
            TripStore s = new TripStore(db);
            s.init();
            CargoTrip stored = s.open(t);
            TripStore again = new TripStore(db);
            again.init();
            CargoTrip back = again.sealed().getFirst();
            assertEquals(stored, back);
            assertEquals(t.boost(), back.boost());
            assertEquals(1.12, back.hits().get("economy:kimchi").factor(), 1e-9);
            CargoTrip old = again.open(new CargoTrip(0, 7, OWNER, "본사", "서해", "economy:kimchi:1", 69, 1L, 0, 7000, 1L, CargoTrip.Status.SEALED, 0));
            assertEquals("", again.latest(1).getFirst().boost(), "a trip without events");
            assertTrue(old.id() > 0);
        } finally {
            db.close();
        }
    }

    @Test
    void theRecordOfEventsOnATripReadsBack() {
        String s = DockBoost.encode(Map.of("economy:kimchi", new DockBoost.Hit(1.12, List.of("shortage", "premium_buyer")),
                "economy:soju", DockBoost.Hit.NONE, "economy:ruby", new DockBoost.Hit(0.9, List.of("crash"))));
        assertEquals("economy:kimchi=1.1200:shortage+premium_buyer;economy:ruby=0.9000:crash", s);
        Map<String, DockBoost.Hit> back = DockBoost.decode(s);
        assertEquals(List.of("shortage", "premium_buyer"), back.get("economy:kimchi").types());
        assertEquals(0.9, back.get("economy:ruby").factor(), 1e-12);
        assertTrue(DockBoost.decode("").isEmpty());
        assertTrue(DockBoost.decode("rubbish=x:y").isEmpty(), "a broken entry: the plain price");
    }

    @Test
    void shortageGoesWhereLittleWasSoldAndTheCrashWhereMost() {
        DockGoods g = DockTest.goods();
        UUID u = OWNER;
        List<DockStore.Sale> sales = List.of(
                new DockStore.Sale(1, "서해", 1, u, u, "economy:kimchi x500, economy:soju x10", 0, 7000, 7000),
                new DockStore.Sale(2, "서해", 1, u, u, "economy:kimchi x300", 0, 7000, 7000),
                new DockStore.Sale(3, "동해", 1, u, u, "economy:ruby_ring x5, minecraft:diamond x3", 0, 7000, 7000));
        Map<String, Map<String, Double>> sold = DockBoost.sold(sales, g);
        double kimchi = DockTest.shop().sell().get("economy:kimchi").base();
        assertEquals(800 * kimchi, sold.get("서해").get("kitchen"), 1e-6);
        assertNull(sold.get("동해").get("kitchen"));
        assertTrue(sold.get("동해").containsKey("gem_bench"));
        List<String> docks = List.of("서해", "동해"), cats = List.of("kitchen", "still", "gem_bench");
        Random r = new Random(3);
        for (int i = 0; i < 50; i++) {
            assertArrayEquals(new String[]{"서해", "kitchen"}, DockBoost.pick(sold, docks, cats, false, 1, r), "폭락: the most sold");
            String[] least = DockBoost.pick(sold, docks, cats, true, 3, r);
            assertTrue(!(least[0].equals("서해") && least[1].equals("kitchen")), "품귀: never the most hauled");
            assertTrue(sold.getOrDefault(least[0], Map.of()).getOrDefault(least[1], 0.0) == 0, "품귀: of the least sold (nothing): " + String.join(",", least));
        }
        assertNull(DockBoost.pick(sold, List.of(), cats, true, 3, r), "no dock: nothing to pick");
    }

    /**
     * The owner's rule (3차 결정): dock prices are already high, so an event moves them by 10~15 % at one dock, one category.
     * The typical hour (docs/plans/4세대_확장_기획안.md 1.5: 중반 about 130,000 G an hour, 베테랑 about 200,000, nearly all of it
     * hauled to a dock) moves by at most that share, and only for those who haul that category there.
     */
    @Test
    void theEffectOnATypicalDockHourStaysSmall() {
        long mid = 130_000, veteran = 200_000;
        // 품귀 +12 %: a cook who hauls all their 요리 to that dock
        assertEquals(15_600, Math.round(mid * (1.12 - 1)));
        assertEquals(24_000, Math.round(veteran * (1.12 - 1)));
        // a mixed load, a third of it the category: +4 %
        assertEquals(5_200, Math.round(mid / 3.0 * 0.12));
        // 폭락 -10 %: who still hauls it there loses 13,000 an hour; going to another dock instead costs only the gap between docks
        assertEquals(-13_000, Math.round(mid * (0.9 - 1)));
        // 고가 매입상 +10 % on everything at one dock for 2 hours
        assertEquals(26_000, Math.round(mid * 0.10 * 2));
        // a 1-ton truck's full load (README "적재 무게": 500,000 G at the default 90 %): one trip of one category
        assertEquals(560_000, Math.round(500_000 * 1.12));
        // all within the rule
        for (double f : new double[]{1.12, 0.9, 1.1}) assertTrue(Math.abs(f - 1) <= 0.15);
    }
}
