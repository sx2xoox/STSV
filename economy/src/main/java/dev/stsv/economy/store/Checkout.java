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
    private final List<Integer> counted = new ArrayList<>();
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

    /**
     * 스캔 단축: scans thing i and then the next ones not yet scanned until `n` are scanned by this click (경력, 묶음 스캔).
     * Returns the things scanned now (none when i was scanned already or there is no such thing).
     */
    public List<Integer> scanMany(int i, int n) {
        List<Integer> out = new ArrayList<>();
        if (!scan(i)) return out;
        out.add(i);
        for (int k = 0; k < scanned.length && out.size() < n; k++) if (scan(k)) out.add(k);
        return out;
    }

    /** The same till paid in cash (정전: the card terminal is dead): what is scanned stays scanned, the asks stay. */
    public Checkout asCash() {
        Checkout c = new Checkout(items, prices, Method.CASH);
        System.arraycopy(scanned, 0, c.scanned, 0, scanned.length);
        c.asks.addAll(asks);
        c.mistake = mistake;
        return c;
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
        if (amount <= 0) return;
        inHand += amount;
        counted.add(amount);
    }

    /** [다시]: nothing in hand. */
    public void clear() {
        inHand = 0;
        counted.clear();
    }

    /** The pieces counted out so far (each key pressed, in order): what lies on the cash tray. */
    public List<Integer> counted() {
        return counted;
    }

    /**
     * How notes handed over lie (as {@link #notes} gives them, largest first): for each, the pile it is on (0, 1, … one for
     * each kind of note) and how many of its kind lie under it.
     */
    public static List<double[]> piles(List<Integer> notes) {
        List<double[]> out = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            int n = notes.get(i), under = 0;
            for (int k = 0; k < i; k++) if (notes.get(k) == n) under++;
            if (!kinds.contains(n)) kinds.add(n);
            out.add(new double[]{kinds.indexOf(n), under});
        }
        return out;
    }

    /**
     * Where the next piece of change lies on the cash tray, given what is on it: {along the tray (the worker's left +),
     * across it (toward the customer +), how many pieces up}. Notes on the worker's half fanned by kind, coins on the
     * customer's half; the same kind goes on its own pile.
     */
    public static double[] trayPlace(List<Integer> onTray, int amount) {
        int under = 0;
        for (int a : onTray) if (a == amount) under++;
        boolean coin = amount < 1000;
        double along = switch (amount) {
            case 50000 -> -0.045;
            case 10000 -> -0.015;
            case 5000 -> 0.015;
            case 1000 -> 0.045;
            case 500 -> -0.07;
            default -> 0.07;
        };
        // (a kind's pile starts a hair over the kinds fanned under it, so no two lie in one plane)
        double lift = switch (amount) {
            case 50000 -> 0;
            case 10000 -> 0.25;
            case 5000 -> 0.5;
            case 1000 -> 0.75;
            default -> 0;
        };
        return new double[]{coin ? 0.055 : -0.04, along, under + lift};
    }

    /**
     * [건네기]: the right amount pays the customer; too little and they say so (a mistake: what was given counts, the rest is
     * still owed and the worker gives again); too much pays them and the difference is {@link #overpaid}.
     */
    public Given give() {
        if (method != Method.CASH || paid) return Given.NOTHING;
        int owed = change();
        counted.clear();
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
