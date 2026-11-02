package dev.stsv.core.economy;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyStoreTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static EconomyStore reopen(Db db) {
        EconomyStore s = new EconomyStore(db);
        s.init();
        return s;
    }

    @Test
    void movementsAreCheckedAndSurviveARestart(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        EconomyStore s = reopen(db);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(s.open(a, 500, 1));
        assertFalse(s.open(a, 500, 2), "start money only once");
        assertTrue(s.open(b, 0, 1));

        assertTrue(s.move(a, b, 200, EconomyStore.Kind.TRANSFER, "pay", null, 3));
        assertFalse(s.move(a, b, 301, EconomyStore.Kind.TRANSFER, "pay", null, 4), "cannot pay more than you have");
        assertFalse(s.move(a, b, 0, EconomyStore.Kind.TRANSFER, "pay", null, 4));
        assertFalse(s.move(a, b, -50, EconomyStore.Kind.TRANSFER, "pay", null, 4), "negative amounts would steal");
        assertFalse(s.move(a, a, 10, EconomyStore.Kind.TRANSFER, "pay", null, 4));
        assertTrue(s.move(a, null, 6, EconomyStore.Kind.SINK, "transfer_fee", null, 5));
        assertEquals(294, s.balance(a));
        assertEquals(200, s.balance(b));

        s.set(b, 1000, "admin", 6);
        assertEquals(1000, s.balance(b));

        EconomyStore again = reopen(db);
        assertEquals(294, again.balance(a), "balances come back from the database");
        assertEquals(1000, again.balance(b));
        List<EconomyStore.Entry> history = again.history(a, 10).get();
        assertEquals(List.of("transfer_fee", "pay", "start"), history.stream().map(EconomyStore.Entry::reason).toList());
        db.close();
    }

    @Test
    void aChequeIsCashedOnlyOnce(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        EconomyStore s = reopen(db);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.open(a, 1000, 1);
        s.open(b, 0, 1);
        assertNull(s.issueCheque(a, 5000, 2), "cannot write a cheque you cannot cover");
        String id = s.issueCheque(a, 700, 2);
        assertNotNull(id);
        assertEquals(300, s.balance(a), "the money leaves when the cheque is written");

        assertEquals(700, s.claimCheque(id, b, 3).get());
        assertEquals(-1, s.claimCheque(id, b, 4).get(), "a copied cheque is worthless");
        assertEquals(-1, s.claimCheque(UUID.randomUUID().toString(), b, 4).get(), "a forged id is worthless");
        db.close();
    }

    @Test
    void undoReversesBothSidesAndVoidsCheques(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        EconomyStore s = reopen(db);
        UUID duper = UUID.randomUUID(), friend = UUID.randomUUID(), other = UUID.randomUUID();
        s.open(duper, 0, 1);
        s.open(friend, 0, 1);
        s.open(other, 100, 1);
        // before the window: stays
        s.move(other, duper, 50, EconomyStore.Kind.TRANSFER, "pay", null, 10);
        // inside the window: an exploit pays out, passes some on, writes cheques
        s.move(null, duper, 10_000, EconomyStore.Kind.FAUCET, "shop_sell", null, 100);
        s.move(duper, friend, 4_000, EconomyStore.Kind.TRANSFER, "pay", null, 101);
        String cashed = s.issueCheque(duper, 1_000, 102);
        String uncashed = s.issueCheque(duper, 2_000, 103);
        long paid = s.claimCheque(cashed, friend, 104).get();
        s.move(null, friend, paid, EconomyStore.Kind.CHEQUE, "cheque_cash", cashed, 104);
        assertEquals(5_000, s.balance(friend));

        assertEquals(5, s.revert(duper, 100, 200).get(), "4 own movements + the friend's cashing of the duper's cheque");
        db.sync(c -> null); // wait for the writes
        assertEquals(50, s.balance(duper), "back to what they had before the window");
        assertEquals(0, s.balance(friend), "passed-on money and the cashed cheque come back out");
        assertEquals(50, s.balance(other));
        assertEquals(-1, s.claimCheque(uncashed, friend, 300).get(), "the uncashed cheque is void");

        EconomyStore again = reopen(db);
        assertEquals(50, again.balance(duper), "the undo is in the database too");
        assertEquals(0, again.balance(friend));
        assertEquals(0, s.revert(duper, 100, 400).get(), "already undone");
        db.close();
    }

    @Test
    void statsAndInequality(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), LOG);
        EconomyStore s = reopen(db);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        s.open(a, 500, 1);
        s.open(b, 500, 1);
        s.move(a, null, 100, EconomyStore.Kind.SINK, "land_tax", null, 50);
        s.move(a, b, 200, EconomyStore.Kind.TRANSFER, "pay", null, 60);
        EconomyStore.Stats st = s.stats(0).get();
        assertEquals(1000, st.faucet());
        assertEquals(100, st.sink());
        assertEquals(200, st.transfer());
        assertEquals(900, st.total());
        assertEquals(2, st.accounts());
        assertEquals(0.0, EconomyStore.gini(List.of(10L, 10L, 10L)), 1e-9);
        assertEquals(0.75, EconomyStore.gini(List.of(0L, 0L, 0L, 100L)), 1e-9, "n=4, one owns all: (n-1)/n");

        s.reset().get();
        assertEquals(0, s.balances().size());
        assertEquals(0, reopen(db).balances().size(), "reset clears the database");
        db.close();
    }
}
