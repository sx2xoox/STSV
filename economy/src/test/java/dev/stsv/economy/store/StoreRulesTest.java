package dev.stsv.economy.store;

import dev.stsv.economy.shop.ShopCatalog;
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
 * store.yml's 2차 (README "편의점" 내 가게 · 플레이어 손님 · 폐기 · 청소 · 날씨): the three 등급 and what they scale, their hourly
 * pay against the same-priced bus, the player customers' prices, the weather's demand, the chores.
 */
class StoreRulesTest {

    static StoreRules rules() {
        List<String> problems = new ArrayList<>();
        StoreRules r = StoreRules.parse(YamlConfiguration.loadConfiguration(new File("content/economy/store.yml")), StoreSpecTest.spec(), problems);
        assertEquals(List.of(), problems);
        return r;
    }

    static YamlConfiguration yaml(String f) {
        return YamlConfiguration.loadConfiguration(new File("content/economy/" + f));
    }

    @Test
    void threeTiersScaleLikeTheBuses() {
        StoreRules r = rules();
        assertEquals(List.of("alley", "standard", "large"), new ArrayList<>(r.tiers().keySet()));
        StoreRules.Tier a = r.tier("골목점"), s = r.tier("standard"), l = r.tier("대형점");
        assertEquals(500_000, a.price());
        assertEquals(1_500_000, s.price());
        assertEquals(3_000_000, l.price());
        assertEquals(List.of(2, 4, 6), List.of(a.maxInside(), s.maxInside(), l.maxInside()), "customers inside 2 / 4 / 6");
        assertEquals(List.of(12, 22, 33), List.of(a.products().size(), s.products().size(), l.products().size()), "products 12 / 22 / all");
        assertTrue(s.products().containsAll(a.products()) && l.products().containsAll(s.products()), "a bigger store sells what the smaller does");
        assertTrue(a.items() <= s.items() && s.items() <= l.items(), "things a customer takes");
        assertTrue(a.margin() < s.margin() && s.margin() < l.margin());
        // 술 · 담배 · 즉석 조리 (핫바 · 구운 계란): only the 대형점
        for (String p : List.of("soju", "beer", "cigarette", "hotbar", "egg")) {
            assertFalse(s.sells(p), p);
            assertTrue(l.sells(p), p);
        }
        assertEquals(List.of(1, 2, 3), List.of(a.rank(), s.rank(), l.rank()));
        assertEquals(l, r.byLicense("economy:store_license_large"));
        assertNull(r.byLicense("economy:store_card"));
    }

    @Test
    void aSmallStoreHasLessFurnitureAndNobodyBrowsesWhereThereIsNone() {
        StoreRules r = rules();
        StoreLayout.Layout alley = StoreLayout.of(r.tier("alley").without()), full = StoreLayout.of();
        assertNull(alley.piece("shelf_noodle"));
        assertNull(alley.piece("eatbar"));
        assertNotNull(alley.piece("counter"));
        assertNotNull(alley.piece("hanger"));
        assertTrue(alley.pieces().size() < full.pieces().size());
        assertFalse(alley.spots().containsKey("noodle"));
        assertEquals(List.of("fridge_a"), alley.spots().get("fridge"), "one fridge");
        assertEquals(full.nodes(), alley.nodes(), "the same walk");
        assertEquals(StoreLayout.of(Set.of()), full);
        // every product a tier sells has its shelf there (or the counter)
        StoreSpec spec = StoreSpecTest.spec();
        for (StoreRules.Tier t : r.tiers().values()) {
            StoreLayout.Layout l = StoreLayout.of(t.without());
            for (String p : t.products()) {
                String place = spec.products().get(p).place();
                assertTrue(place.equals("counter") || l.spots().containsKey(place), t.id() + " " + p);
            }
        }
        // a customer of the 골목점 only takes what it sells
        Random rnd = new Random(5);
        for (int i = 0; i < 300; i++) {
            for (String t : spec.pickItems(spec.customers().get("office"), rnd, r.tier("alley").products(), 0)) assertTrue(r.tier("alley").sells(t), t);
        }
        // one more thing at a 대형점
        StoreSpec.Customer one = spec.customers().get("tardy");
        int most = 0;
        for (int i = 0; i < 300; i++) most = Math.max(most, spec.pickItems(one, rnd, null, 1).size());
        assertEquals(one.max() + 1, most);
    }

    /**
     * The calibration (store.yml own): at the 1차's pace, each tier earns about what the bus of its price aims for
     * (docs/plans/마을버스_3차_수입_기획안.md: 소형 5~7만 목표 · 마을버스 8~11만 · 리무진 11~15만), the bigger the more.
     */
    @Test
    void anOwnStoreEarnsLikeItsBus() {
        StoreSpec s = StoreSpecTest.spec();
        StoreRules r = rules();
        long alley = StoreRules.hourly(s, r.tier("alley"), 55, 80, 2, 1500);
        long standard = StoreRules.hourly(s, r.tier("standard"), 62, 80, 3, 1750);
        long large = StoreRules.hourly(s, r.tier("large"), 62, 80, 3, 2050);
        assertTrue(alley >= 38_000 && alley <= 60_000, "골목점 " + alley);
        assertTrue(standard >= 70_000 && standard <= 100_000, "표준점 " + standard);
        assertTrue(large >= 105_000 && large <= 150_000, "대형점 " + large);
        assertTrue(alley < standard && standard < large);
        // the 직영점's 40% stays the rented bus's ~2만
        ShiftLog log = new ShiftLog();
        for (int i = 0; i < 70; i++) log.served(s.stars(80), s.earn(80, 1, true), 3000, true);
        long direct = log.payout(s).pay(), own = log.payout(s, 1).pay();
        assertTrue(direct >= 15_000 && direct <= 25_000, "직영점 " + direct);
        assertEquals(Math.round(direct / 0.4), own, 2, "the owner keeps it all");
    }

    @Test
    void theLicensesAreSoldNextToTheBuses() {
        StoreRules r = rules();
        ShopCatalog shop = ShopCatalog.parse(yaml("shop.yml"), new ArrayList<>());
        ShopCatalog.BuyCategory vehicles = shop.buyCategories().stream().filter(c -> c.id().equals("vehicles")).findFirst().orElseThrow();
        Map<String, Long> prices = new HashMap<>();
        for (ShopCatalog.Good g : vehicles.goods()) prices.put(g.id(), g.price());
        YamlConfiguration items = yaml("items/store_extra.yml");
        for (StoreRules.Tier t : r.tiers().values()) {
            assertEquals(t.price(), prices.get(t.license()), t.license() + " sold at its price");
            assertTrue(items.isConfigurationSection(t.license().substring("economy:".length())), t.license());
        }
        assertEquals(prices.get("stsv:mini_bus"), r.tier("alley").price(), "the 골목점 costs a 소형 버스");
        assertEquals(prices.get("stsv:village_bus"), r.tier("standard").price());
        assertEquals(prices.get("stsv:limousine_bus"), r.tier("large").price());
    }

    @Test
    void playersPayRealMoneyForRealFood() {
        StoreRules r = rules();
        StoreRules.Players p = r.players();
        StoreSpec spec = StoreSpecTest.spec();
        int tuna = spec.products().get("samgak_tuna").price();
        assertEquals(12, p.price(tuna, false), "1,200원 = 12 G");
        assertEquals(14, p.price(tuna, true), "the 무인 계산대 about 20% more");
        assertEquals(1, p.price(100, false), "never free");
        assertTrue(p.commission(12) < 12 && p.commission(100) == 25, "the worker's commission is a small part of it");
        assertTrue(p.sells("samgak_tuna", true));
        assertTrue(p.sells("dosirak", false));
        assertFalse(p.sells("dosirak", true), "the 무인 계산대 sells fewer kinds");
        assertFalse(p.sells("cigarette", false), "no cigarettes for players");
        assertFalse(p.sells("umbrella", false), "only food");
        assertTrue(p.unmannedItems().size() < p.food().size());
        // every food is an item with hunger; a 컵라면 needs hot water first
        YamlConfiguration items = yaml("items/store_extra.yml");
        for (Map.Entry<String, String> e : p.food().entrySet()) {
            String key = e.getValue().substring("economy:".length());
            assertTrue(items.isConfigurationSection(key), key);
            if (p.hot().containsKey(e.getKey())) assertFalse(items.contains(key + ".food"), key + " is dry");
            else assertTrue(items.getInt(key + ".food.nutrition") > 0, key + " fills");
        }
        for (String hot : p.hot().values()) assertTrue(items.getInt(hot.substring(8) + ".food.nutrition") > 0, hot);
        assertTrue(items.getInt(r.waste().food().substring(8) + ".food.nutrition") > 0, "the 폐기 is food");
    }

    @Test
    void rainBringsUmbrellasAndSummerIceCream() {
        StoreRules r = rules();
        StoreRules.Demand d = r.demand();
        Random rnd = new Random(2);
        int umbrellas = 0, ice = 0, winter = 0;
        for (int i = 0; i < 1000; i++) {
            if (d.extra(true, 11, null, List.of(), rnd).contains("umbrella")) umbrellas++;
            for (String x : d.extra(false, 7, null, List.of(), rnd)) if (x.startsWith("ice")) ice++;
            winter += d.extra(false, 1, null, List.of(), rnd).size();
        }
        assertTrue(umbrellas > 250 && umbrellas < 450, "umbrellas " + umbrellas);
        assertTrue(ice > 200 && ice < 400, "ice " + ice);
        assertEquals(0, winter, "no rain, not summer: nothing extra");
        // only what the store sells, never twice
        assertTrue(d.extra(true, 7, r.tier("alley").products(), List.of(), rnd).isEmpty(), "the 골목점 sells neither");
        for (int i = 0; i < 50; i++) assertFalse(d.extra(true, 1, null, List.of("umbrella"), rnd).contains("umbrella"));
        assertEquals(1.5, d.busy(true));
        assertEquals(1, d.busy(false));
        StoreSpec s = StoreSpecTest.spec();
        assertTrue(s.interval(12, new Random(1), 1.5) < s.interval(12, new Random(1)), "장날: customers come closer together");
    }

    @Test
    void wasteMessAndHotWater() {
        StoreSpec spec = StoreSpecTest.spec();
        StoreRules r = rules();
        Chores c = new Chores(spec);
        String meal = r.waste().place();
        for (int i = 0; i < 10; i++) c.expire(meal, r.waste().most());
        assertEquals(r.waste().most(), c.expired(meal), "at most this many red tags");
        int before = c.stock(meal);
        assertEquals(3, c.takeExpired(meal));
        assertEquals(before - 3, c.stock(meal), "off the shelf");
        assertEquals(0, c.expired(meal));
        assertEquals(3, c.carriedWaste());
        assertEquals(3, c.dispose());
        assertEquals(0, c.dispose());
        // a customer picks one up now and then; never more tags than things
        c.expire(meal, 3);
        assertTrue(c.picksExpired(meal, 0.0));
        assertFalse(c.picksExpired(meal, 0.99));
        while (c.take(meal)) { }
        assertEquals(0, c.expired(meal));
        assertTrue(c.giveExpired(meal) == false, "nothing to give");
        // spills: the mop first
        assertTrue(c.spill("fm", 3));
        assertFalse(c.spill("fm", 3), "one a spot");
        assertFalse(c.wipe("fm"), "no mop in hand");
        c.mop(true);
        assertTrue(c.wipe("fm"));
        assertTrue(c.leaveCup(0, 3));
        assertTrue(c.spill("mm", 3) && c.spill("bm", 3));
        assertFalse(c.leaveCup(1, 3), "three messes at most");
        assertEquals(3, c.messes());
        assertTrue(c.clearCup(0));
        assertFalse(c.clearCup(0));
        // the kettle
        int cap = r.mess().water();
        for (int i = 0; i < cap; i++) assertTrue(c.pour(cap));
        assertFalse(c.pour(cap), "empty");
        assertTrue(c.refill(cap));
        assertFalse(c.refill(cap), "full");
        // a thing back on the shelf
        Chores d = new Chores(spec);
        d.take("snack");
        d.putBack(spec, "snack");
        d.putBack(spec, "snack");
        assertEquals(spec.shelves().get("snack").capacity(), d.stock("snack"), "never over full");
    }

    @Test
    void troubleAndPlayerPaySettleTheTill() {
        Checkout c = new Checkout(List.of("jelly", "candy"), List.of(1200, 800), Checkout.Method.CASH);
        c.ask("trouble");
        c.settle();
        assertTrue(c.paid());
        assertFalse(c.done(), "still asked something");
        c.answer("trouble");
        assertTrue(c.done());
        assertFalse(c.mistake());
    }

    @Test
    void theShiftKeepsTheTroublesApart() {
        StoreSpec s = StoreSpecTest.spec();
        ShiftLog log = new ShiftLog();
        log.served(5, 1000, 2000, true);
        log.handled(4, 0);
        log.bonus(150);
        log.covered(1000);
        log.fine(3000);
        ShiftLog.Payout p = log.payout(s, 1);
        assertEquals(1, p.handled());
        assertEquals(1, p.served());
        assertEquals(0, p.lost(), "a handled customer is not lost");
        assertEquals(4.5, p.stars(), 1e-9);
        assertEquals(1150, p.earned());
        assertEquals(3000, p.fines());
        assertEquals(100 + 3000, p.deduction(), "1,000원 covered = 100 G, and the fine");
        assertEquals(0, p.pay(), "never below 0");
    }
}
