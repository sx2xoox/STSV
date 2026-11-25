package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * One card of the 섯다 deck: 20 hwatu cards, months 1~10, two of each. The first card of a month (a) is its 광 (1, 3, 8) or
 * 열끗 (every other month); the second (b) is a 띠, except 8월 whose second card is the 열끗 기러기. Only 1·3·8 광 and
 * 4·7·9 열끗 matter for the hand ranking ({@link Sutda}). Item: economy:hwatu_&lt;month&gt;&lt;a|b&gt; (art:
 * tools/assetgen/hwatu_icons.py).
 */
public record Card(int month, Kind kind) {

    public enum Kind { GWANG, YEOL, TTI }

    public Card {
        if (month < 1 || month > 10) throw new IllegalArgumentException("month " + month);
    }

    public boolean gwang() {
        return kind == Kind.GWANG;
    }

    public boolean yeol() {
        return kind == Kind.YEOL;
    }

    /** The first card of its month (광 or 열끗), "a"; the other one "b". */
    public boolean first() {
        return kind == Kind.GWANG || (kind == Kind.YEOL && month != 8);
    }

    /** The item id that shows it: economy:hwatu_3a. */
    public String item() {
        return "economy:hwatu_" + month + (first() ? "a" : "b");
    }

    /** The card of a month, first (광 / 열끗) or second (띠, 8월: 열끗 기러기). */
    public static Card of(int month, boolean first) {
        if (first) return new Card(month, month == 1 || month == 3 || month == 8 ? Kind.GWANG : Kind.YEOL);
        return new Card(month, month == 8 ? Kind.YEOL : Kind.TTI);
    }

    /** The 20 cards in order (1a 1b 2a 2b ... 10b). */
    public static List<Card> deck() {
        List<Card> out = new ArrayList<>(20);
        for (int m = 1; m <= 10; m++) {
            out.add(of(m, true));
            out.add(of(m, false));
        }
        return out;
    }

    /** The short name used to pick a card (/관리자 화투방 시험 섯다 패): "3광", "7열끗", "4띠" (8월's second card: "8열끗"). */
    public String label() {
        return month + switch (kind) {
            case GWANG -> "광";
            case YEOL -> "열끗";
            case TTI -> "띠";
        };
    }

    /** A card by its {@link #label()} ("3광") or its item suffix ("3a", "hwatu_3a", "economy:hwatu_3a"); null if none. */
    public static Card parse(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("economy:")) t = t.substring(8);
        if (t.startsWith("hwatu_")) t = t.substring(6);
        for (Card c : deck()) {
            if (c.label().equals(t) || c.item().substring("economy:hwatu_".length()).equals(t)) return c;
        }
        return null;
    }
}
