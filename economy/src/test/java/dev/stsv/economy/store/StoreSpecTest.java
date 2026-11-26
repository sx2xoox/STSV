package dev.stsv.economy.store;

import dev.stsv.economy.bus.BusSpec;
import dev.stsv.economy.people.PeopleSpec;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * store.yml (README "편의점"): it parses clean against bus.yml, holds the §15 products, every kind has its lines, who comes
 * when, what each kind takes, the pay's parts and the hourly calibration against a rented bus.
 */
class StoreSpecTest {

    static StoreSpec spec() {
        List<String> problems = new ArrayList<>();
        StoreSpec s = StoreSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/store.yml")), problems);
        assertEquals(List.of(), problems);
        return s;
    }

    static BusSpec bus() {
        List<String> problems = new ArrayList<>();
        BusSpec b = BusSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml")), problems);
        assertEquals(List.of(), problems);
        return b;
    }

    /** docs/plans/편의점_기획안.md §15: id, price, place. */
    private static final Object[][] CONTRACT = {
            {"samgak_tuna", 1200, "meal"}, {"samgak_bibim", 1200, "meal"}, {"samgak_bulgogi", 1300, "meal"}, {"dosirak", 4500, "meal"},
            {"hotbar", 2000, "meal"}, {"egg", 1800, "meal"}, {"ramen_red", 1300, "noodle"}, {"ramen_mild", 1200, "noodle"},
            {"ramen_big", 1700, "noodle"}, {"banana_milk", 1700, "fridge"}, {"coffee_can", 1400, "fridge"}, {"coffee_cup", 2200, "fridge"},
            {"soda", 1500, "fridge"}, {"ion_drink", 1600, "fridge"}, {"water", 900, "fridge"}, {"soju", 1900, "fridge"}, {"beer", 2800, "fridge"},
            {"hangover", 4000, "counter"}, {"chips", 1700, "snack"}, {"shrimp", 1500, "snack"}, {"choco_snack", 1800, "snack"},
            {"jelly", 1200, "snack"}, {"chocolate", 1500, "snack"}, {"candy", 800, "snack"}, {"gum", 1000, "counter"},
            {"ice_bar", 1000, "freezer"}, {"ice_cone", 2000, "freezer"}, {"umbrella", 7000, "daily"}, {"battery", 3500, "daily"},
            {"tissue", 2500, "daily"}, {"bandage", 2000, "daily"}, {"lottery", 1000, "counter"}, {"cigarette", 4500, "counter"}};

    @Test
    void theProductsAreTheContract() {
        StoreSpec s = spec();
        assertEquals(33, s.products().size());
        for (Object[] row : CONTRACT) {
            StoreSpec.Product p = s.products().get((String) row[0]);
            assertNotNull(p, (String) row[0]);
            assertEquals((int) row[1], p.price(), p.id());
            assertEquals(row[2], p.place(), p.id());
            assertEquals("economy:store_item_" + p.id(), p.item());
        }
        // every shelf a shopper browses is stocked, with a box that fills it
        assertEquals(Set.of("snack", "noodle", "daily", "meal", "fridge"), s.shelves().keySet());
        assertEquals("drink", s.shelves().get("fridge").box());
        assertEquals(List.of(7, 19), s.deliveries(), "twice a game day");
        assertEquals(0.4, s.pay().share(), 1e-9, "직영점: 40%");
        assertTrue(s.cash().manual());
    }

    @Test
    void customersAreRealBusKindsAndAllHaveTheirLines() {
        StoreSpec s = spec();
        BusSpec bus = bus();
        assertEquals(List.of(), s.check(bus));
        assertTrue(s.customers().size() >= 20, "many kinds come in");
        for (String type : s.customers().keySet()) {
            for (String situation : StoreSpec.SITUATIONS) assertNotNull(s.lines(type, situation), type + " " + situation);
            for (String situation : StoreSpec.EXTRA) assertNotNull(s.lines(type, situation), type + " " + situation);
        }
        // the kinds with their own voice have every main situation of their own
        int own = 0;
        for (StoreSpec.Customer c : s.customers().values()) {
            if (c.talk().isEmpty()) continue;
            own++;
            for (String situation : StoreSpec.SITUATIONS) assertFalse(c.talk().getOrDefault(situation, List.of()).isEmpty(), c.type() + " " + situation);
        }
        assertTrue(own >= 25, "own lines: " + own);
        assertTrue(s.lines("grandma", "recognize").getFirst().contains("기사양반"));
        assertTrue(s.lines("resident", "recognize").contains("어, 기사님 아니세요?"));
        // a secret kind never comes in
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File("content/economy/store.yml"));
        y.set("customers.shades.weight", 1);
        assertTrue(StoreSpec.parse(y, new ArrayList<>()).check(bus).stream().anyMatch(p -> p.contains("shades")));
    }

    @Test
    void whoComesDependsOnTheGameHour() {
        StoreSpec s = spec();
        Set<String> all = s.customers().keySet();
        Map<String, Integer> morning = tally(s, 7, all), night = tally(s, 22, all), dawn = tally(s, 3, all), day = tally(s, 12, all);
        assertNull(morning.get("drunk"), "no drunks at 7");
        assertNull(day.get("ghost"));
        assertTrue(dawn.getOrDefault("ghost", 0) > 0, "the dawn's odd ones");
        assertTrue(night.getOrDefault("drunk", 0) > night.getOrDefault("grandma", 0), "night: drunks, not grandmas");
        assertTrue(morning.getOrDefault("office", 0) + morning.getOrDefault("student", 0) > 0.3 * 4000, "the morning rush");
        assertTrue(day.getOrDefault("grandma", 0) > morning.getOrDefault("grandma", 0), "grandmas come by day");
        // the morning is busy, the dawn sparse
        assertTrue(s.time(7).every() < s.time(12).every());
        assertTrue(s.time(3).every() > s.time(12).every());
        assertEquals("night", s.time(0).id(), "past midnight is still night");
        assertNull(s.pickType(12, Set.of(), new Random(1)), "nobody when no kind may come");
    }

    private static Map<String, Integer> tally(StoreSpec s, int hour, Set<String> allowed) {
        Random r = new Random(42);
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < 4000; i++) out.merge(s.pickType(hour, allowed, r), 1, Integer::sum);
        return out;
    }

    @Test
    void eachKindTakesWhatItLikes() {
        StoreSpec s = spec();
        Random r = new Random(7);
        StoreSpec.Customer student = s.customers().get("student");
        Map<String, Integer> got = new LinkedHashMap<>();
        int things = 0;
        for (int i = 0; i < 2000; i++) {
            List<String> pick = s.pickItems(student, r);
            assertTrue(pick.size() >= student.min() && pick.size() <= student.max());
            for (String p : pick) {
                assertTrue(s.products().containsKey(p));
                got.merge(p, 1, Integer::sum);
                things++;
            }
            for (String p : pick) assertTrue(pick.stream().filter(p::equals).count() <= 2, "the same thing at most twice");
        }
        int liked = 0;
        for (String k : student.likes().keySet()) liked += got.getOrDefault(k, 0);
        assertTrue(liked > 0.75 * things, "students mostly take 삼각김밥 · 젤리 …: " + liked + "/" + things);
        assertTrue(got.getOrDefault("jelly", 0) > got.getOrDefault("cigarette", 0) * 5);
        // the drunk takes the hangover cure, the hiker water
        Map<String, Integer> drunk = new HashMap<>(), hiker = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            for (String p : s.pickItems(s.customers().get("drunk"), r)) drunk.merge(p, 1, Integer::sum);
            for (String p : s.pickItems(s.customers().get("hiker"), r)) hiker.merge(p, 1, Integer::sum);
        }
        assertEquals("hangover", top(drunk));
        assertEquals("water", top(hiker));
        // a kind with no likes takes anything (by the products' weights)
        StoreSpec.Customer plain = new StoreSpec.Customer("x", 1, 3, 3, Map.of(), 0.8, -1, -1, Map.of());
        assertEquals(3, s.pickItems(plain, r).size());
    }

    private static String top(Map<String, Integer> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    @Test
    void thePayParts() {
        StoreSpec s = spec();
        assertEquals(250, s.tip(95, 1));
        assertEquals(150, s.tip(70, 1));
        assertEquals(50, s.tip(40, 1));
        assertEquals(0, s.tip(10, 1));
        assertEquals(500, s.tip(95, 2.0), "할머니: double");
        assertEquals(500, s.tip(95, 5.0), "at most tip-cap");
        assertEquals(5, s.stars(95));
        assertEquals(4, s.stars(80));
        assertEquals(3, s.stars(60));
        assertEquals(1, s.stars(5));
        assertEquals(0.15, s.ratingBonus(4.6), 1e-9);
        assertEquals(0.10, s.ratingBonus(4.0), 1e-9);
        assertEquals(0, s.ratingBonus(2.0), 1e-9);
        assertEquals(450 + 150 + 60, s.earn(75, 1, true));
        assertEquals(450 + 150, s.earn(75, 1, false));
        assertEquals(80, s.patience().mood(80, 10), 1e-9, "grace");
        assertEquals(20, s.patience().mood(80, 200), 1e-9, "floor");
    }

    @Test
    void theShiftPaysItsShareLessOverpaidChange() {
        StoreSpec s = spec();
        ShiftLog log = new ShiftLog();
        log.served(5, 760, 3900, true);
        log.served(4, 660, 1200, true);
        log.served(3, 500, 4500, false);
        log.lost();
        log.overpaid(1000);
        assertEquals(3, log.served());
        assertEquals((5 + 4 + 3 + 1) / 4.0, log.stars(), 1e-9);
        ShiftLog.Payout p = log.payout(s);
        assertEquals(1920, p.earned());
        assertEquals(96, p.bonus(), "★3.25: 5%");
        assertEquals(Math.round((1920 + 96) * 0.4), p.share());
        assertEquals(100, p.deduction(), "1,000원 over = 100 G");
        assertEquals(p.share() - 100, p.pay());
        assertEquals(9600, p.sales());
        assertEquals(1, p.mistakes());
        // never below 0
        ShiftLog bad = new ShiftLog();
        bad.overpaid(50000);
        assertEquals(0, bad.payout(s).pay());
        assertEquals(0, new ShiftLog().payout(s).bonus());
    }

    /**
     * The calibration (store.yml's comment): a steady worker serves ~70 customers an hour; a fair mix of moods gives about
     * 20,000 G an hour at the 40% share, like a rented bus (docs/plans/마을버스_3차_수입_기획안.md: ~1.5만 × 1.3).
     */
    @Test
    void anHourPaysLikeARentedBus() {
        StoreSpec s = spec();
        Random r = new Random(3);
        ShiftLog log = new ShiftLog();
        for (int i = 0; i < 70; i++) {
            double mood = 55 + r.nextDouble() * 45;   // most served well, some kept waiting
            boolean accurate = r.nextDouble() < 0.9;
            log.served(s.stars(mood), s.earn(mood, 1, accurate), 2500, accurate);
        }
        for (int i = 0; i < 5; i++) log.lost();
        long pay = log.payout(s).pay();
        assertTrue(pay >= 15000 && pay <= 25000, "an hour: " + pay);
        // the spawn rate can keep a worker that busy: about 95 an hour come in on average over a game day
        double every = 0;
        for (int h = 0; h < 24; h++) every += s.time(h).every();
        double perHour = 3600 / ((s.spawn().min() + s.spawn().max()) / 2.0 * every / 24);
        assertTrue(perHour > 75 && perHour < 120, "customers an hour: " + perHour);
    }

    @Test
    void theCodexHasTheStoreStamp() {
        List<String> problems = new ArrayList<>();
        PeopleSpec p = PeopleSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/people.yml")), problems);
        assertEquals("편의점", p.places().get(PeopleSpec.PLACE_STORE));
    }

    @Test
    void everyShelfAndBoxHasItsWords() throws Exception {
        StoreSpec s = spec();
        YamlConfiguration messages;
        try (var in = getClass().getResourceAsStream("/messages.yml")) {
            messages = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (StoreSpec.Shelf sh : s.shelves().values()) {
            assertTrue(messages.contains("store.place." + sh.place()), sh.place());
            assertTrue(messages.contains("store.box." + sh.box()), sh.box());
        }
    }

    /** items/store.yml (made by the asset side, §15): when it is there, every id the store shows or places is in it. */
    @Test
    void theAssetContractWhenThere() {
        File f = new File("content/economy/items/store.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        StoreSpec s = spec();
        List<String> missing = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (StoreSpec.Product p : s.products().values()) ids.add("store_item_" + p.id());
        for (StoreSpec.Shelf sh : s.shelves().values()) ids.add("store_box_" + sh.box());
        for (String k : List.of("snack", "noodle", "daily", "meal")) for (String st : List.of("full", "half", "empty")) ids.add("store_shelf_" + k + "_" + st);
        for (String st : List.of("full", "half", "empty")) ids.add("store_fridge_" + st);
        ids.addAll(List.of("store_counter", "store_cig_shelf", "store_freezer", "store_microwave", "store_kettle", "store_eat_bar", "store_parasol",
                "store_hanger", "store_waste", "store_mop", "store_sign", "store_card", "store_btn_card", "store_btn_give", "store_btn_no",
                "store_scanner", "store_vest"));
        for (int b : s.cash().bills()) ids.add("store_cash_" + b);
        for (int c : s.cash().change()) ids.add("store_btn_change_" + c);
        for (String id : ids) if (!y.contains(id)) missing.add(id);
        assertEquals(List.of(), missing);
        // the pieces the builder places are furniture
        for (StoreLayout.Piece p : StoreLayout.of().pieces()) {
            String id = p.item().substring("economy:".length());
            assertTrue(y.isConfigurationSection(id + ".furniture"), id + " is furniture");
        }
    }
}
