package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A playing card of the 도박장's 세븐 포커 and 바카라 (README "도박장"): rank 2..14 (11 J, 12 Q, 13 K, 14 A) and suit.
 * Item: economy:pcard_&lt;rank&gt;&lt;suit&gt; (rank a, 2 .. 10, j, q, k; suit s, h, d, c), the back economy:pcard_back.
 * Suits are ordered for the Korean house rule of an exact tie (♠ &gt; ♦ &gt; ♥ &gt; ♣): {@link Suit#ordinal()}.
 */
public record PCard(int rank, Suit suit) implements Comparable<PCard> {

    /** Low to high (the house order of an exact tie): ♣ ♥ ♦ ♠. */
    public enum Suit {
        CLUB('c', "♣"), HEART('h', "♥"), DIAMOND('d', "♦"), SPADE('s', "♠");

        private final char letter;
        private final String symbol;

        Suit(char letter, String symbol) {
            this.letter = letter;
            this.symbol = symbol;
        }

        public char letter() {
            return letter;
        }

        public String symbol() {
            return symbol;
        }

        /** Red (♥ ♦) or black (♠ ♣): the colour the name is shown in. */
        public boolean red() {
            return this == HEART || this == DIAMOND;
        }
    }

    public static final int ACE = 14, KING = 13, QUEEN = 12, JACK = 11;
    public static final String BACK = "economy:pcard_back";

    public PCard {
        if (rank < 2 || rank > 14) throw new IllegalArgumentException("rank " + rank);
        if (suit == null) throw new IllegalArgumentException("suit");
    }

    /** A rank as shown: A, 2 … 10, J, Q, K. */
    public static String rankName(int rank) {
        return switch (rank) {
            case 14, 1 -> "A";
            case 13 -> "K";
            case 12 -> "Q";
            case 11 -> "J";
            default -> String.valueOf(rank);
        };
    }

    /** "K♥", "10♠". */
    public String label() {
        return rankName(rank) + suit.symbol;
    }

    /** The item that shows its face: economy:pcard_kh, economy:pcard_10s, economy:pcard_as. */
    public String item() {
        String r = switch (rank) {
            case 14 -> "a";
            case 13 -> "k";
            case 12 -> "q";
            case 11 -> "j";
            default -> String.valueOf(rank);
        };
        return "economy:pcard_" + r + suit.letter;
    }

    /** Its 바카라 value: A 1, 2 … 9 as they are, 10 · J · Q · K 0. */
    public int baccarat() {
        if (rank == ACE) return 1;
        return rank >= 10 ? 0 : rank;
    }

    /** One 52-card deck in order (♣ 2 … ♠ A). */
    public static List<PCard> deck() {
        List<PCard> out = new ArrayList<>(52);
        for (Suit s : Suit.values()) for (int r = 2; r <= 14; r++) out.add(new PCard(r, s));
        return out;
    }

    /** By rank, then suit (♣ lowest, ♠ highest). */
    @Override
    public int compareTo(PCard o) {
        return rank != o.rank ? Integer.compare(rank, o.rank) : Integer.compare(suit.ordinal(), o.suit.ordinal());
    }

    /** A card by its item suffix ("kh", "10s", "pcard_as", "economy:pcard_as") or its label ("K♥"); null if none. */
    public static PCard parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("economy:")) t = t.substring(8);
        if (t.startsWith("pcard_")) t = t.substring(6);
        for (PCard c : deck()) {
            if (c.item().substring("economy:pcard_".length()).equals(t) || c.label().toLowerCase(Locale.ROOT).equals(t)) return c;
        }
        return null;
    }
}
