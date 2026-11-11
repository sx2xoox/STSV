package dev.stsv.economy.road;

/**
 * Blocks a sliced job may set per tick (국도 BUILD: a cross-section at a time; 하역장 짓기: a block, or a foundation
 * column of up to 24). A piece is done whole once it starts, so one may cost more than is left: the debt is paid by
 * the next ticks (they start nothing until it is), and on average no more than perTick blocks a tick are set. Unused
 * blocks are not saved up past one tick's worth. Pure (unit-tested).
 */
public final class BlockBudget {

    private final int perTick;
    private long credit;
    /** this tick's share was 0 (the server busy): nothing starts, whatever is in hand */
    private boolean paused;

    public BlockBudget(int perTick) {
        this.perTick = Math.max(1, perTick);
    }

    /**
     * A new tick: share (0 .. 1, {@link LoadGate.Allow#share}) of a tick's blocks comes in, never more than one tick's in
     * hand; 0: paused (nothing starts this tick, even with blocks in hand).
     */
    public void tick(double share) {
        paused = !(share > 0);
        long add = Math.round(perTick * Math.clamp(paused ? 0 : share, 0, 1));
        credit = Math.min(perTick, credit + add);
    }

    /** May another piece start now? */
    public boolean any() {
        return !paused && credit > 0;
    }

    /** A piece cost this many blocks. */
    public void spend(long blocks) {
        credit -= Math.max(0, blocks);
    }

    /** Blocks in hand (negative: owed). */
    public long credit() {
        return credit;
    }

    public int perTick() {
        return perTick;
    }
}
