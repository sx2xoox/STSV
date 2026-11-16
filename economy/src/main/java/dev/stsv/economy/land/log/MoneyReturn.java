package dev.stsv.economy.land.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Giving back money a griefer received from other players: each payment back to whoever paid it, newest first, as far
 * as the griefer's balance goes (never below nothing); what does not fit is reported, not invented. Payments already
 * returned once are left out by the caller. Pure.
 */
public final class MoneyReturn {

    /** A payment the griefer received: ledger id, when, from whom, how much, what for (the ledger's reason). */
    public record Payment(long entry, long at, UUID from, long amount, String reason) {}

    /** What goes back for one payment: returned may be less than the payment (short = the rest). */
    public record Back(Payment payment, long returned, long shortBy) {}

    private MoneyReturn() {}

    public static List<Back> plan(List<Payment> payments, long balance) {
        List<Payment> sorted = new ArrayList<>(payments);
        sorted.sort(Comparator.comparingLong(Payment::at).reversed().thenComparing(Comparator.comparingLong(Payment::entry).reversed()));
        long left = Math.max(0, balance);
        List<Back> out = new ArrayList<>();
        for (Payment p : sorted) {
            long r = Math.min(left, p.amount());
            left -= r;
            out.add(new Back(p, r, p.amount() - r));
        }
        return out;
    }
}
