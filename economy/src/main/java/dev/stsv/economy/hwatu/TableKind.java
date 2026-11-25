package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * The two 도박장 table pieces (core furniture with seats, README "도박장"): the round one (6 seats: 섯다 · 주사위) and the duel
 * one (2 seats: 홀짝). Seat index = the item's seat list order: 0 at the table's front, then clockwise. Pure (unit-tested).
 */
public enum TableKind {
    ROUND("economy:gamble_table_round", 6),
    DUEL("economy:gamble_table_duel", 2);

    private final String item;
    private final int seats;

    TableKind(String item, int seats) {
        this.item = item;
        this.seats = seats;
    }

    public String item() {
        return item;
    }

    public int seats() {
        return seats;
    }

    /** The kind of a furniture item id, or null if it is not a 도박장 table. */
    public static TableKind of(String itemId) {
        for (TableKind k : values()) if (k.item.equals(itemId)) return k;
        return null;
    }

    /**
     * The seats players may take for a game of at most `max` players on a table with `seatCount` chairs: all of them if
     * they fit, else spread round the table as evenly as can be (2 on a 6-seat table: 0 and 3, face to face).
     */
    public static List<Integer> usable(int seatCount, int max) {
        List<Integer> out = new ArrayList<>();
        if (seatCount <= 0 || max <= 0) return out;
        if (max >= seatCount) {
            for (int i = 0; i < seatCount; i++) out.add(i);
            return out;
        }
        for (int i = 0; i < max; i++) {
            int s = (int) Math.round((double) i * seatCount / max) % seatCount;
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }
}
