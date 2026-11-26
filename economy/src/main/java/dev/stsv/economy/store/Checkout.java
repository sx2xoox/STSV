package dev.stsv.economy.store;

import java.util.ArrayList;
import java.util.List;

/**
 * One customer at the till (README "편의점" 계산): the things they put on the counter, which are scanned (바코드 "삑"), the
 * total, and how they pay. A card customer: the worker taps the terminal. A cash customer hands over money ({@link #tender});
 * the worker presses change buttons (each adds its amount to what is in hand) and [건네기]; the amount to give back is shown
 * big ({@link #change}), so nobody has to do sums. Pure (unit-tested).
 *
 * <p>Phase 2 hooks: {@link #asks} holds what the customer wants besides paying ("warm" now; later "bag", "id" …), each
 * answered by its own button or chore; the till is done when they are paid and nothing is asked any more.
 */
public final class Checkout {

    public enum Method { CARD, CASH }

    /** What [건네기] did: the right change, too little (they wait for the rest), too much (it is theirs now). */
    public enum Given { EXACT, SHORT, OVER, NOTHING }

    private final List<String> items;
    private final List<Integer> prices;
    private final boolean[] scanned;
    private final Method method;
    private final List<String> asks = new ArrayList<>();
    private int tendered;
    private int inHand;
    private boolean paid;
    private boolean mistake;
    private int overpaid;

    public Checkout(List<String> items, List<Integer> prices, Method method) {
        if (items.size() != prices.size()) throw new IllegalArgumentException("items and prices");
        this.items = List.copyOf(items);
        this.prices = List.copyOf(prices);
        this.scanned = new boolean[items.size()];
        this.method = method;
    }

    public List<String> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public Method method() {
        return method;
    }

    public boolean scanned(int i) {
        return i >= 0 && i < scanned.length && scanned[i];
    }

    /** Scans thing i ("삑"): false if it was already scanned (or there is no such thing). */
    public boolean scan(int i) {
        if (i < 0 || i >= scanned.length || scanned[i]) return false;
        scanned[i] = true;
        return true;
    }

    public boolean allScanned() {
        for (boolean b : scanned) if (!b) return false;
        return true;
    }

    /** The total so far (원): what is scanned. */
    public int total() {
        int t = 0;
        for (int i = 0; i < scanned.length; i++) if (scanned[i]) t += prices.get(i);
        return t;
    }

    /** The whole bill (원), scanned or not. */
    public int bill() {
        int t = 0;
        for (int p : prices) t += p;
        return t;
    }

    // ------------------------------------------------------------------------------------------ paying

    /**
     * What a cash customer hands over for this total: with `roundUp` the next whole 1,000 (in 1,000 bills; the total itself
     * when it is whole thousands), else the smallest single bill that covers it (more 50,000s past that).
     *
     * @param bills the bills there are, smallest first (1000, 5000, 10000, 50000)
     */
    public static int tender(int total, List<Integer> bills, boolean roundUp) {
        if (total <= 0) return 0;
        if (roundUp) return (total + 999) / 1000 * 1000;
        for (int b : bills) if (b >= total) return b;
        int top = bills.isEmpty() ? 1000 : bills.getLast();
        return (total + top - 1) / top * top;
    }

    /** The bills (largest first) that make up an amount handed over (what lies on the counter). */
    public static List<Integer> notes(int amount, List<Integer> bills) {
        List<Integer> out = new ArrayList<>();
        List<Integer> big = new ArrayList<>(bills);
        big.sort(java.util.Comparator.reverseOrder());
        int left = amount;
        for (int b : big) while (left >= b) {
            out.add(b);
            left -= b;
        }
        return out;
    }

    /** The fewest pieces (largest first) that make an amount from these denominations (any order): the auto change. */
    public static List<Integer> breakdown(int amount, List<Integer> denominations) {
        return notes(amount, denominations);
    }

    /** The customer hands over cash (the counter shows it): `amount` 원. */
    public void tender(int amount) {
        this.tendered = amount;
    }

    public int tendered() {
        return tendered;
    }

    /** The change to give back (원): what was handed over less the bill; 0 for a card. */
    public int change() {
        return method == Method.CARD ? 0 : Math.max(0, tendered - bill());
    }

    /** What the worker has in hand to give (원). */
    public int inHand() {
        return inHand;
    }

    /** A change button: `amount` more in hand. */
    public void press(int amount) {
        if (amount > 0) inHand += amount;
    }

    /** [다시]: nothing in hand. */
    public void clear() {
        inHand = 0;
    }

    /**
     * [건네기]: the right amount pays the customer; too little and they say so (a mistake: what was given counts, the rest is
     * still owed and the worker gives again); too much pays them and the difference is {@link #overpaid}.
     */
    public Given give() {
        if (method != Method.CASH || paid) return Given.NOTHING;
        int owed = change();
        if (inHand == owed) {
            paid = true;
            inHand = 0;
            return Given.EXACT;
        }
        if (inHand < owed) {
            mistake = true;
            tendered -= inHand;   // what they got is theirs: the rest is owed
            inHand = 0;
            return Given.SHORT;
        }
        overpaid += inHand - owed;
        mistake = true;
        paid = true;
        inHand = 0;
        return Given.OVER;
    }

    /** The card tapped on the terminal. */
    public boolean card() {
        if (method != Method.CARD || paid || !allScanned()) return false;
        paid = true;
        return true;
    }

    /** cash.manual false: the till gives the change itself (always right). */
    public void autoChange() {
        if (method == Method.CASH && !paid) {
            inHand = 0;
            paid = true;
        }
    }

    public boolean paid() {
        return paid;
    }

    /**
     * Paid with nothing to give back (2차: a child's exact coins, 할머니's purse, 외상 the worker covers, a player's money): the
     * till is paid as it stands.
     */
    public void settle() {
        inHand = 0;
        paid = true;
    }

    /** A wrong change at any point (no accuracy bonus). */
    public boolean mistake() {
        return mistake;
    }

    /** Change given too much (원): taken from the worker's pay. */
    public int overpaid() {
        return overpaid;
    }

    // ------------------------------------------------------------------------------------------ asks (phase 2 hooks)

    public List<String> asks() {
        return asks;
    }

    public void ask(String what) {
        if (!asks.contains(what)) asks.add(what);
    }

    public boolean answer(String what) {
        return asks.remove(what);
    }

    /** Paid and nothing asked any more: the customer goes. */
    public boolean done() {
        return paid && asks.isEmpty();
    }
}
