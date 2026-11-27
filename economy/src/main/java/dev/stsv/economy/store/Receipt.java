package dev.stsv.economy.store;

/**
 * The receipt of a sale: fed out of the counter's printer, torn off, and gone with what was bought. The times and what
 * becomes of the strip are pure (unit-tested); {@link StoreShow#receipt} shows it.
 *
 * <p>The strip is the model economy:store_receipt standing in the printer's slot (items/store.yml): its length is its
 * local y, so scaling y from next to nothing to 1 over {@link #FEED} ticks (one interpolation, no per-tick moves) feeds it
 * out. Then it is torn ({@link #TEAR}) and slides after the customer into their bag ({@link #AWAY} .. {@link #GONE}). Now
 * and then a customer does not want it ({@link Fate#DECLINED}): it stays in the slot until the worker's click drops it in
 * the bin, or draws back by itself after {@link #LEFT}. A strip that waits keeps the printer busy: the next sales print
 * nothing until it is gone.
 */
final class Receipt {

    private Receipt() {
    }

    /** The slot in the store's own frame (the counter model's (1.71875, 1.0949, 0.6748): x = -2 - mz, z = 8 - mx), blocks. */
    static final double X = -2 - 0.6748, Y = 1.0949, Z = 8 - 1.71875;
    /** The counter's yaw in the store, and the slot's lean towards the customer (degrees round the strip's own x). */
    static final float YAW = 90, TILT = -22.5f;
    /** The strip's length when nothing of it is out yet (a sliver, so the model is there to grow). */
    static final float SLIVER = 0.02f;
    /** Ticks: fed out by, torn at, sliding away from, gone at; a strip nobody wants draws back after LEFT. */
    static final int FEED = 16, TEAR = 18, AWAY = 20, GONE = 27, LEFT = 120, RETRACT = 8;

    /** What becomes of a printed strip. */
    enum Fate {
        /** into the bag with the goods (or the customer's hand) */
        BAG,
        /** the customer does not want it: it waits in the slot for the worker's click (the bin), or draws back */
        DECLINED,
        /** it waits in the slot until the worker takes it */
        KEPT
    }

    enum Phase { FEEDING, TORN, LEAVING, WAITING, RETRACTING, GONE }

    /** Where a strip is `tick` ticks after the sale. */
    static Phase phase(Fate fate, int tick) {
        if (tick < FEED) return Phase.FEEDING;
        if (fate == Fate.KEPT) return tick < TEAR ? Phase.FEEDING : Phase.WAITING;
        if (tick < AWAY) return Phase.TORN;
        if (fate == Fate.BAG) return tick < GONE ? Phase.LEAVING : Phase.GONE;
        if (tick < LEFT) return Phase.WAITING;
        return tick < LEFT + RETRACT ? Phase.RETRACTING : Phase.GONE;
    }

    /** How much of the strip is out (0..1) while it feeds: what the one interpolation passes through. */
    static float out(int tick) {
        return Math.clamp(SLIVER + (1 - SLIVER) * tick / (float) FEED, SLIVER, 1f);
    }

    /** Whether the worker can click the strip now (it waits in the slot). */
    static boolean clickable(Fate fate, int tick) {
        return phase(fate, tick) == Phase.WAITING;
    }

    /** Whether a sale prints at all: the pack has the model, and no earlier strip still waits in the slot. */
    static boolean prints(boolean model, boolean waiting) {
        return model && !waiting;
    }

    /** Whether this customer leaves their receipt (rare; never the strip that is kept for the worker). */
    static Fate fate(boolean kept, double roll, double decline) {
        if (kept) return Fate.KEPT;
        return roll < Math.clamp(decline, 0, 1) ? Fate.DECLINED : Fate.BAG;
    }

    /** The ticks at which the printer buzzes while it feeds (short, close together). */
    static int[] buzz() {
        return new int[]{0, 2, 4, 6, 8, 10, 12, 14};
    }
}
