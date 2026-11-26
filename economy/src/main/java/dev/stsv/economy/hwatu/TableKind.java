package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * The 도박장 table pieces (core furniture with seats, README "도박장"): the round one (6 seats: 섯다 · 주사위), the duel
 * one (2 seats: 홀짝), the poker table (6 seats like the round one: 세븐 포커) and the half-moon baccarat table (6 seats
 * round its curved side, the dealer behind its flat edge). Seat index = the item's seat list order: 0 at the table's
 * front, then clockwise. Pure (unit-tested).
 */
public enum TableKind {
    ROUND("economy:gamble_table_round", 6),
    DUEL("economy:gamble_table_duel", 2),
    POKER("economy:poker_table", 6),
    BACCARAT("economy:baccarat_table", 6);

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

    /** The kind a game is bound to when its furniture is not loaded (the one built for it). */
    public static TableKind forGame(Table.Game g) {
        return switch (g) {
            case HOLJJAK -> DUEL;
            case POKER -> POKER;
            case BACCARAT -> BACCARAT;
            default -> ROUND;
        };
    }

    /** 바카라 needs its own table (the dealer's side); every other game any table with chairs enough. */
    public boolean fits(Table.Game g) {
        return g == Table.Game.BACCARAT ? this == BACCARAT : this != BACCARAT;
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
