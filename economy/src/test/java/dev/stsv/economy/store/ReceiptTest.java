package dev.stsv.economy.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The receipt's times and what becomes of the strip (the show itself needs a server). */
class ReceiptTest {

    @Test
    void fedTornAndGoneWithTheGoods() {
        assertEquals(16, Receipt.FEED, "about 0.8 s");
        assertEquals(Receipt.Phase.FEEDING, Receipt.phase(Receipt.Fate.BAG, 0));
        assertEquals(Receipt.Phase.FEEDING, Receipt.phase(Receipt.Fate.BAG, Receipt.FEED - 1));
        assertEquals(Receipt.Phase.TORN, Receipt.phase(Receipt.Fate.BAG, Receipt.TEAR));
        assertEquals(Receipt.Phase.LEAVING, Receipt.phase(Receipt.Fate.BAG, Receipt.AWAY));
        assertEquals(Receipt.Phase.GONE, Receipt.phase(Receipt.Fate.BAG, Receipt.GONE));
        assertTrue(Receipt.FEED < Receipt.TEAR && Receipt.TEAR < Receipt.AWAY && Receipt.AWAY < Receipt.GONE);
        // one interpolation: from a sliver to the whole strip, never shrinking on the way
        assertEquals(Receipt.SLIVER, Receipt.out(0), 1e-6);
        assertEquals(1f, Receipt.out(Receipt.FEED), 1e-6);
        assertEquals(1f, Receipt.out(Receipt.FEED + 50), 1e-6);
        float last = 0;
        for (int t = 0; t <= Receipt.FEED; t++) {
            assertTrue(Receipt.out(t) > last);
            last = Receipt.out(t);
        }
        for (int t : Receipt.buzz()) assertTrue(t >= 0 && t < Receipt.FEED, "the printer only buzzes while it feeds");
        for (int t = 0; t < 400; t++) assertFalse(Receipt.clickable(Receipt.Fate.BAG, t), "a strip that goes with the goods is never clicked");
    }

    @Test
    void aStripNobodyWantsWaitsForTheWorker() {
        assertEquals(Receipt.Phase.WAITING, Receipt.phase(Receipt.Fate.DECLINED, Receipt.AWAY));
        assertTrue(Receipt.clickable(Receipt.Fate.DECLINED, Receipt.AWAY + 10));
        assertFalse(Receipt.clickable(Receipt.Fate.DECLINED, Receipt.FEED - 1), "not while it still feeds");
        assertEquals(Receipt.Phase.RETRACTING, Receipt.phase(Receipt.Fate.DECLINED, Receipt.LEFT), "left alone it draws back");
        assertEquals(Receipt.Phase.GONE, Receipt.phase(Receipt.Fate.DECLINED, Receipt.LEFT + Receipt.RETRACT));
        // a kept strip stays until it is taken, however long
        assertEquals(Receipt.Phase.WAITING, Receipt.phase(Receipt.Fate.KEPT, Receipt.TEAR));
        assertEquals(Receipt.Phase.WAITING, Receipt.phase(Receipt.Fate.KEPT, 100_000));
        assertTrue(Receipt.clickable(Receipt.Fate.KEPT, 5000));
    }

    @Test
    void whoGetsWhichStrip() {
        assertEquals(Receipt.Fate.BAG, Receipt.fate(false, 0.5, 0.05));
        assertEquals(Receipt.Fate.DECLINED, Receipt.fate(false, 0.01, 0.05), "rare");
        assertEquals(Receipt.Fate.BAG, Receipt.fate(false, 0.0, 0), "switched off");
        assertEquals(Receipt.Fate.KEPT, Receipt.fate(true, 0.0, 1), "a strip kept for the worker is never declined");
        assertTrue(Receipt.prints(true, false));
        assertFalse(Receipt.prints(false, false), "no model in the pack: no show");
        assertFalse(Receipt.prints(true, true), "a strip still in the slot: the printer is busy");
        // the slot: on the worker's half of the counter top, a little over it
        assertTrue(Receipt.Y > StoreLayout.TOP && Receipt.Y < StoreLayout.TOP + 0.3);
        assertTrue(Receipt.X < -2.5 && Receipt.X > -3.1 && Receipt.Z > 6 && Receipt.Z < 7);
    }
}
