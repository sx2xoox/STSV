package dev.stsv.economy;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wash trades (QA wash-trade-achievements): two friends selling each other dirt for 1 G, or winning each other's 10 G
 * auctions, must not fill market_sold / auctions_won. Only trades at a meaningful price count, once a day per pair.
 */
class TradeCreditTest {

    private static final long HOUR = 3_600_000L;
    private static final TradeRules RULES = TradeRules.parse(ContentTest.yaml("trade.yml"));

    @Test
    void theShippedRulesHaveAFloorAndAWindow() {
        assertEquals(100, RULES.creditMinPrice());
        assertEquals(1.0, RULES.creditShopRatio());
        assertEquals(24, RULES.creditPairHours());
        // an old trade.yml without the section gets the same defaults
        TradeRules old = TradeRules.parse(new YamlConfiguration());
        assertEquals(100, old.creditMinPrice());
        assertEquals(24, old.creditPairHours());
    }

    @Test
    void onlyAMeaningfulPriceCounts() {
        // nothing the shop buys: the fixed floor
        assertFalse(TradeCredit.meaningful(1, 0, 100, 1.0));
        assertFalse(TradeCredit.meaningful(99, 0, 100, 1.0));
        assertTrue(TradeCredit.meaningful(100, 0, 100, 1.0));
        // 64 diamonds the shop pays 50 G each for: at least 3,200 G
        assertEquals(3_200, TradeCredit.floor(64 * 50.0, 100, 1.0));
        assertFalse(TradeCredit.meaningful(3_199, 64 * 50.0, 100, 1.0));
        assertTrue(TradeCredit.meaningful(3_200, 64 * 50.0, 100, 1.0));
        // a cheap stack still needs the fixed floor; fractional shop values round up
        assertEquals(100, TradeCredit.floor(3 * 2.5, 100, 1.0));
        assertEquals(151, TradeCredit.floor(150.2, 100, 1.0));
        assertEquals(1_600, TradeCredit.floor(64 * 50.0, 100, 0.5));
    }

    @Test
    void theWindowIsPerPair() {
        assertTrue(TradeCredit.windowOpen(null, 0, 24));
        assertFalse(TradeCredit.windowOpen(0L, 23 * HOUR, 24));
        assertTrue(TradeCredit.windowOpen(0L, 24 * HOUR, 24));
        assertTrue(TradeCredit.windowOpen(0L, 1, 0));          // 0 = no window
    }

    @Test
    void aWashTradingPairCountsOnceADayAndARestartDoesNotReopenIt(@TempDir Path dir) throws Exception {
        Db db = DatabaseModule.sqlite(dir.resolve("stsv.db"), Logger.getLogger("test"));
        TradeCredit c = new TradeCredit(db);
        c.init();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), other = UUID.randomUUID();
        long t = 1_000_000L;

        // the QA scenario: dirt for 1 G back and forth never counts
        for (int i = 0; i < 200; i++) {
            assertFalse(c.credit(TradeCredit.MARKET, i % 2 == 0 ? a : b, i % 2 == 0 ? b : a, 1, 0, RULES, t + i));
        }
        // a real sale counts, the same buyer again that day does not, another buyer does
        assertTrue(c.credit(TradeCredit.MARKET, a, b, 500, 0, RULES, t));
        assertFalse(c.credit(TradeCredit.MARKET, a, b, 500, 0, RULES, t + HOUR));
        assertTrue(c.credit(TradeCredit.MARKET, a, other, 500, 0, RULES, t + HOUR));
        // the other way round is its own pair (b sells to a)
        assertTrue(c.credit(TradeCredit.MARKET, b, a, 500, 0, RULES, t + HOUR));
        // auctions are counted apart from the market
        assertTrue(c.credit(TradeCredit.AUCTION, a, b, 500, 0, RULES, t + HOUR));
        // no one trades with themselves; server auctions (no seller) always count
        assertFalse(c.credit(TradeCredit.MARKET, a, a, 500, 0, RULES, t));
        assertTrue(c.credit(TradeCredit.AUCTION, a, null, 10, 0, RULES, t));
        assertTrue(c.credit(TradeCredit.AUCTION, a, null, 10, 0, RULES, t));

        db.async(x -> null).get();   // the writes are queued on the database thread: wait for them
        TradeCredit again = new TradeCredit(db);
        again.init();
        assertFalse(again.credit(TradeCredit.MARKET, a, b, 500, 0, RULES, t + 2 * HOUR), "a restart keeps the window");
        assertTrue(again.credit(TradeCredit.MARKET, a, b, 500, 0, RULES, t + 25 * HOUR), "the next day it counts again");

        again.reset().get();
        TradeCredit wiped = new TradeCredit(db);
        wiped.init();
        assertTrue(wiped.credit(TradeCredit.MARKET, a, b, 500, 0, RULES, t + 26 * HOUR));
        db.close();
    }
}
