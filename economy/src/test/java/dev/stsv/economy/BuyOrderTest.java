package dev.stsv.economy;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import dev.stsv.economy.mail.MailStore;
import dev.stsv.economy.market.BuyOrderStore;
import dev.stsv.economy.market.BuyOrders;
import dev.stsv.economy.util.Items;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 거래소 구매 주문: the held money always equals (amount - filled) x price, nobody is paid twice, nothing is lost on a restart. SQLite. */
class BuyOrderTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final byte[] STACK = {9, 8, 7};
    private static final TradeRules RULES = TradeRules.parse(ContentTest.yaml("trade.yml"));
    private static final String FLOUR = "economy:flour";

    private static Db db(Path dir) throws Exception {
        return DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
    }

    /** A store with the mail table it writes into. */
    private static BuyOrderStore store(Db db) {
        new MailStore(db).init();
        BuyOrderStore s = new BuyOrderStore(db);
        s.init();
        return s;
    }

    /** filled, escrow, state as stored. */
    private static long[] stored(BuyOrderStore s, long id) throws Exception {
        return s.stored(id).get();
    }

    @Test
    void escrowIsExactThroughPartialFillsAndTheRefund(@TempDir Path dir) throws Exception {
        assertEquals(6_000, BuyOrders.escrow(500, 12));
        assertThrows(ArithmeticException.class, () -> BuyOrders.escrow(Integer.MAX_VALUE, Long.MAX_VALUE / 2));
        // the seller's side of a fill: 7% of 1,440 is 100.8, rounded up
        BuyOrders.Payout pay = BuyOrders.payout(12, 120, 0.07);
        assertEquals(1_440, pay.gross());
        assertEquals(101, pay.fee());
        assertEquals(1_339, pay.net());
        assertEquals(0, BuyOrders.payout(12, 120, 0).fee(), "수수료 면제");

        Db db = db(dir);
        BuyOrderStore s = store(db);
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID(), other = UUID.randomUUID();
        BuyOrderStore.Order o = s.add(buyer, FLOUR, "밀가루", 500, 12, 0, 1_000_000);
        assertEquals(6_000, s.escrowOf(buyer));

        BuyOrderStore.Fill f1 = s.fill(o.id(), seller, 120, 101, List.of(STACK, STACK), 10).get();
        assertNotNull(f1);
        assertEquals(1_440, f1.gross());
        assertEquals(buyer, f1.buyer());
        s.applied(o.id(), 120);
        assertArrayEquals(new long[]{120, 380 * 12, BuyOrderStore.OPEN}, stored(s, o.id()));
        assertEquals(380 * 12, s.escrowOf(buyer), "memory and database agree");

        BuyOrderStore.Fill f2 = s.fill(o.id(), other, 64, 54, List.of(STACK), 20).get();
        s.applied(o.id(), 64);
        assertEquals(768, f2.gross());
        assertEquals(316, s.get(o.id()).left());

        // more than is left: refused as a whole, nothing changes
        assertNull(s.fill(o.id(), seller, 317, 0, List.of(STACK), 30).get());
        assertArrayEquals(new long[]{184, 316 * 12, BuyOrderStore.OPEN}, stored(s, o.id()));

        // cancel: what is left comes back, once
        CompletableFuture<BuyOrderStore.Closed> c1 = s.close(o.id(), BuyOrderStore.CANCELLED, 40), c2 = s.close(o.id(), BuyOrderStore.CANCELLED, 40);
        BuyOrderStore.Closed closed = c1.get() != null ? c1.get() : c2.get();
        assertTrue(c1.get() == null ^ c2.get() == null, "refunded exactly once");
        assertEquals(316 * 12, closed.refund());
        // every G held went somewhere exactly once: to the sellers or back to the buyer
        assertEquals(6_000, f1.gross() + f2.gross() + closed.refund());
        assertNull(s.fill(o.id(), seller, 1, 0, List.of(STACK), 50).get(), "no fill after the cancel");
        assertEquals(0, s.escrowOf(buyer));

        // filled to the last one: closed as FILLED with nothing held
        BuyOrderStore.Order small = s.add(buyer, FLOUR, "밀가루", 10, 5, 0, 1_000_000);
        assertNotNull(s.fill(small.id(), seller, 10, 3, List.of(STACK), 60).get());
        s.applied(small.id(), 10);
        assertArrayEquals(new long[]{10, 0, BuyOrderStore.FILLED}, stored(s, small.id()));
        assertNull(s.get(small.id()), "a full order leaves the list");
        assertNull(s.close(small.id(), BuyOrderStore.CANCELLED, 70).get(), "a full order cannot be cancelled for money");
        db.close();
    }

    @Test
    void sellersFillingAtOnceNeverTakeMoreThanIsHeld(@TempDir Path dir) throws Exception {
        // two servers on one database (two connections, two database threads): the claim is the only guard
        Db a = db(dir), b = db(dir);
        BuyOrderStore sa = store(a), sb = new BuyOrderStore(b);
        sb.init();
        UUID buyer = UUID.randomUUID();
        BuyOrderStore.Order o = sa.add(buyer, FLOUR, "밀가루", 100, 7, 0, Long.MAX_VALUE);
        List<CompletableFuture<BuyOrderStore.Fill>> tries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID seller = UUID.randomUUID();
            tries.add(sa.fill(o.id(), seller, 9, 0, List.of(STACK), i));
            tries.add(sb.fill(o.id(), seller, 9, 0, List.of(STACK), i));
        }
        int handed = 0, ok = 0;
        long paid = 0;
        for (CompletableFuture<BuyOrderStore.Fill> t : tries) {
            try {
                BuyOrderStore.Fill f = t.get();
                if (f == null) continue;
                handed += f.amount();
                paid += f.gross();
                ok++;
            } catch (ExecutionException e) {
                // a busy database refuses a claim: the seller gets the items back, nothing is taken
            }
        }
        assertTrue(ok > 0, "some fills go through");
        assertTrue(handed <= 100, "never more than ordered: " + handed);
        assertEquals(handed, ok * 9, "a fill is all or nothing");
        long[] st = stored(sa, o.id());
        assertEquals(handed, st[0]);
        assertEquals((100 - handed) * 7L, st[1], "held = what is left x price");
        assertEquals(700, paid + st[1], "paid + held = what was put in");
        List<BuyOrderStore.Fill> recorded = sa.fills(o.id()).get();
        assertEquals(ok, recorded.size());
        assertEquals(handed, recorded.stream().mapToInt(BuyOrderStore.Fill::amount).sum());
        // the buyer has one mail per successful fill, written with the claim
        assertEquals(ok, new MailStore(a).list(buyer).get().size());
        // the last one: exactly what is left still fits
        assertNotNull(sb.fill(o.id(), UUID.randomUUID(), 100 - handed, 0, List.of(STACK), 99).get());
        assertEquals(BuyOrderStore.FILLED, stored(sa, o.id())[2]);
        a.close();
        b.close();
    }

    @Test
    void anExpiredOrderGivesItsMoneyBackOnceAndTakesNoMoreFills(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        BuyOrderStore s = store(db);
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        long hour = 3_600_000L;
        BuyOrderStore.Order o = s.add(buyer, FLOUR, "밀가루", 64, 20, 0, (long) (RULES.orderHours() * hour));
        assertEquals(168, RULES.orderHours(), 1e-9, "7 days");
        assertNotNull(s.fill(o.id(), seller, 16, 0, List.of(STACK), hour).get());
        s.applied(o.id(), 16);
        assertTrue(s.expired(167 * hour).isEmpty());
        long late = 169 * hour;
        assertEquals(List.of(o.id()), s.expired(late).stream().map(BuyOrderStore.Order::id).toList());
        // swept a minute late: a fill in that minute is still refused
        assertNull(s.fill(o.id(), seller, 1, 0, List.of(STACK), late).get());
        BuyOrderStore.Closed c = s.close(o.id(), BuyOrderStore.EXPIRED, late).get();
        assertEquals(48 * 20, c.refund());
        assertNull(s.close(o.id(), BuyOrderStore.EXPIRED, late).get(), "not twice");
        // the server stopped before the money moved: still owed at the next start, until marked
        assertEquals(List.of(o.id()), s.unrefunded().stream().map(x -> x.order().id()).toList());
        assertEquals(48 * 20, s.unrefunded().getFirst().refund());
        s.refunded(o.id()).get();
        assertTrue(s.unrefunded().isEmpty());
        // the buyer hears of it (and of the fill) once
        BuyOrderStore.Untold u = s.untold(buyer).get();
        assertEquals(1, u.expired().size());
        assertEquals(1, u.fills().size());
        BuyOrderStore.Untold again = s.untold(buyer).get();
        assertTrue(again.expired().isEmpty() && again.fills().isEmpty());
        db.close();
    }

    @Test
    void nobodyFillsTheirOwnOrder(@TempDir Path dir) throws Exception {
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        assertEquals("order.own", BuyOrders.fillProblem(buyer, buyer, 10, 64));
        assertEquals("order.gone", BuyOrders.fillProblem(buyer, seller, 0, 64));
        assertEquals("order.no-items", BuyOrders.fillProblem(buyer, seller, 10, 0));
        assertNull(BuyOrders.fillProblem(buyer, seller, 10, 1));
        // and the database refuses it too (another server, a modified client)
        Db db = db(dir);
        BuyOrderStore s = store(db);
        BuyOrderStore.Order o = s.add(buyer, FLOUR, "밀가루", 10, 5, 0, Long.MAX_VALUE);
        assertNull(s.fill(o.id(), buyer, 5, 0, List.of(STACK), 1).get());
        assertArrayEquals(new long[]{0, 50, BuyOrderStore.OPEN}, stored(s, o.id()));
        assertTrue(new MailStore(db).list(buyer).get().isEmpty());
        db.close();
    }

    @Test
    void itemsMatchAsTheShopMatchesThem() {
        // custom items: by id alone (a fish of any size, its lore and name do not matter)
        assertTrue(Items.plain("economy:flour", "economy:flour", false, false));
        assertTrue(Items.plain("economy:mackerel", "economy:mackerel", false, true));
        assertFalse(Items.plain("economy:flour", "economy:rice_flour", false, false));
        // goods bought from the admin shop are never taken
        assertFalse(Items.plain("economy:flour", "economy:flour", true, false));
        // vanilla: only a plain one (not renamed, enchanted or worn); ids with or without minecraft:
        assertTrue(Items.plain("minecraft:wheat", "wheat", false, false));
        assertFalse(Items.plain("minecraft:wheat", "minecraft:wheat", false, true));
        assertFalse(Items.plain("minecraft:wheat", "minecraft:hay_block", false, false));
        // the smallest fish goes first, the trophy last; slots that do not match are skipped
        double n = Double.NaN;
        assertArrayEquals(new int[]{3, 0, 4, 1}, BuyOrders.smallestFirst(new double[]{1.0, 1.4, n, 0.7, 1.0}));
        // a click hands one stack, shift as many as there are; never more than is left
        assertEquals(64, BuyOrders.fillCount(500, 300, false, 64));
        assertEquals(300, BuyOrders.fillCount(500, 300, true, 64));
        assertEquals(20, BuyOrders.fillCount(20, 300, true, 64));
        assertEquals(16, BuyOrders.fillCount(500, 100, false, 16));
        assertEquals(0, BuyOrders.fillCount(0, 100, true, 64));
    }

    @Test
    void ordersAndFillsSurviveARestart(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        BuyOrderStore s = store(db);
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        BuyOrderStore.Order o = s.add(buyer, FLOUR, "밀가루", 500, 12, 100, 9_000);
        BuyOrderStore.Fill f = s.fill(o.id(), seller, 200, 168, List.of(STACK, STACK, STACK, STACK), 200).get();
        s.applied(o.id(), 200);
        // the server stops before the seller is paid
        BuyOrderStore again = store(db);
        BuyOrderStore.Order back = again.get(o.id());
        assertEquals(new BuyOrderStore.Order(o.id(), buyer, FLOUR, "밀가루", 500, 200, 12, 100, 9_000), back);
        assertEquals(300 * 12, again.escrowOf(buyer));
        List<BuyOrderStore.Fill> unpaid = again.unpaid();
        assertEquals(List.of(f), unpaid, "the fill is paid at start");
        assertEquals(2_400, unpaid.getFirst().gross());
        assertEquals(168, unpaid.getFirst().fee());
        again.paid(f.id()).get();
        assertTrue(again.unpaid().isEmpty(), "and only once");
        // the items waited in the buyer's mailbox with their reason
        List<MailStore.Mail> mail = new MailStore(db).list(buyer).get();
        assertEquals(4, mail.size());
        assertEquals(BuyOrderStore.MAIL, mail.getFirst().reason());
        assertArrayEquals(STACK, mail.getFirst().item());
        db.close();
    }

    @Test
    void ordersAreCappedPerPlayerPricedAboveZeroAndSized() throws Exception {
        assertEquals(10, RULES.orderMax());
        assertEquals(1, RULES.orderMinPrice());
        assertEquals(64 * 54, RULES.orderMaxAmount());
        // the defaults when trade.yml has no orders section
        TradeRules old = TradeRules.parse(new YamlConfiguration());
        assertEquals(10, old.orderMax());
        assertEquals(168, old.orderHours(), 1e-9);
        assertEquals(1, old.orderMinPrice());
        assertEquals(3_456, old.orderMaxAmount());

        long rich = 1_000_000_000L;
        assertNull(BuyOrders.placeProblem(RULES, 9, 500, 12, rich));
        assertEquals("order.full", BuyOrders.placeProblem(RULES, 10, 500, 12, rich));
        assertEquals("order.min-price", BuyOrders.placeProblem(RULES, 0, 500, 0, rich));
        assertEquals("order.bad-amount", BuyOrders.placeProblem(RULES, 0, 0, 12, rich));
        assertEquals("order.bad-amount", BuyOrders.placeProblem(RULES, 0, 3_457, 12, rich));
        assertNull(BuyOrders.placeProblem(RULES, 0, 3_456, 12, rich));
        assertEquals("order.bad-price", BuyOrders.placeProblem(RULES, 0, 1, dev.stsv.economy.market.PriceInput.MAX + 1, Long.MAX_VALUE));
        // the whole amount must be there to hold
        assertEquals("order.not-enough", BuyOrders.placeProblem(RULES, 0, 500, 12, 5_999));
        assertNull(BuyOrders.placeProblem(RULES, 0, 500, 12, 6_000));
    }

    @Test
    void theStoreCountsEachBuyersOpenOrders(@TempDir Path dir) throws Exception {
        Db db = db(dir);
        BuyOrderStore s = store(db);
        UUID buyer = UUID.randomUUID();
        for (int i = 0; i < RULES.orderMax(); i++) s.add(buyer, FLOUR, "밀가루", 1, 10 + i, 0, 1_000);
        assertEquals(RULES.orderMax(), s.count(buyer));
        assertEquals("order.full", BuyOrders.placeProblem(RULES, s.count(buyer), 1, 10, 1_000));
        // best price first within an item
        assertEquals(10L + RULES.orderMax() - 1, s.active().getFirst().price());
        assertEquals(10L + RULES.orderMax() - 1, s.best(FLOUR));
        long first = s.active().getLast().id();
        assertNotNull(s.close(first, BuyOrderStore.CANCELLED, 1).get());
        assertEquals(RULES.orderMax() - 1, s.count(buyer), "a cancelled order frees its place");
        db.close();
    }
}
