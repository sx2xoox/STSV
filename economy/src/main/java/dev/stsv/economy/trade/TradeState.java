package dev.stsv.economy.trade;

/**
 * The two-step agreement of a direct trade, without any Bukkit types (unit-tested).
 *
 * Both sides press 준비 (ready) when their offer is final; only then can each press 확정 (confirm).
 * Any change to either offer - an item moved, money changed - takes both sides back to the start,
 * so nobody can swap an item out after the other side agreed.
 */
public final class TradeState {

    public enum Side { A, B }

    private final long[] money = new long[2];
    private final boolean[] ready = new boolean[2];
    private final boolean[] confirmed = new boolean[2];
    private int changes;

    private static int i(Side s) {
        return s == Side.A ? 0 : 1;
    }

    public long money(Side s) {
        return money[i(s)];
    }

    public boolean ready(Side s) {
        return ready[i(s)];
    }

    public boolean confirmed(Side s) {
        return confirmed[i(s)];
    }

    public int changes() {
        return changes;
    }

    /** Something in an offer changed: both sides must agree again. */
    public void changed() {
        changes++;
        ready[0] = ready[1] = false;
        confirmed[0] = confirmed[1] = false;
    }

    /** Sets a side's money offer (not below 0, not above what they have); returns the new value. */
    public long setMoney(Side s, long value, long max) {
        long v = Math.clamp(value, 0, Math.max(0, max));
        if (v != money[i(s)]) {
            money[i(s)] = v;
            changed();
        }
        return v;
    }

    /**
     * The side's button: 준비 → (when both are ready) 확정. Pressing 준비 again takes it back.
     * Returns true when both have confirmed (the trade happens).
     */
    public boolean press(Side s) {
        int k = i(s);
        if (!ready[k]) {
            ready[k] = true;
            return false;
        }
        if (!ready[1 - k]) {
            ready[k] = false;   // take back
            return false;
        }
        confirmed[k] = true;
        return confirmed[0] && confirmed[1];
    }

    public boolean bothReady() {
        return ready[0] && ready[1];
    }
}
