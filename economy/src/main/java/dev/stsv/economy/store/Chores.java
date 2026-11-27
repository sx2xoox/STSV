package dev.stsv.economy.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The work besides the till (README "편의점" 진열 · 데우기): the shelves' stock (snack · noodle · daily · meal · fridge; the
 * freezer and the counter's things never run out), the delivery boxes waiting by the back door (twice a game day), the box
 * the worker carries, and the microwave. Pure (unit-tested); one per store, kept between shifts.
 */
public final class Chores {

    /** The microwave: idle, warming (ticks left), done ("띵", ticks since), burnt. */
    public enum Oven { IDLE, WARMING, DONE }

    /** What the microwave did this tick. */
    public enum OvenEvent { NONE, DING, BURNT }

    private final Map<String, Integer> stock = new LinkedHashMap<>();
    private final Set<String> boxes = new LinkedHashSet<>();
    private String carrying;
    private long lastSlot = Long.MIN_VALUE;
    private Oven oven = Oven.IDLE;
    private int ovenTicks;

    /** Fills every shelf the spec stocks (a new store, after a restart). */
    public Chores(StoreSpec spec) {
        for (StoreSpec.Shelf s : spec.shelves().values()) stock.put(s.place(), s.capacity());
    }

    public int stock(String place) {
        return stock.getOrDefault(place, -1);
    }

    /** Whether this place is a stocked shelf (it can run out). */
    public boolean stocked(String place) {
        return stock.containsKey(place);
    }

    /** One thing off the shelf at this place: false when it is empty (a place that is not stocked never runs out). */
    public boolean take(String place) {
        Integer n = stock.get(place);
        if (n == null) return true;
        if (n <= 0) return false;
        stock.put(place, n - 1);
        if (expired.getOrDefault(place, 0) > n - 1) expired.put(place, n - 1);   // (the tagged ones never outnumber the stock)
        return true;
    }

    /** The model state of a shelf (full / half / empty). */
    public String state(StoreSpec spec, String place) {
        StoreSpec.Shelf s = spec.shelves().get(place);
        return s == null ? "full" : s.state(stock(place));
    }

    // ------------------------------------------------------------------------------------------ deliveries

    /**
     * The delivery slot of a world's full time (ticks): each game day has one slot per delivery hour; before the first
     * hour of a day it is the last one of the day before. Game hour 0 = 6000 ticks into the day.
     */
    public static long slot(long fullTime, List<Integer> hours) {
        long day = Math.floorDiv(fullTime + 6000, 24000);
        int hour = (int) Math.floorMod((fullTime + 6000) / 1000, 24);
        int k = -1;
        for (int i = 0; i < hours.size(); i++) if (hour >= hours.get(i)) k = i;
        if (k < 0) return day * hours.size() - 1;
        return day * hours.size() + k;
    }

    /** The game hour (0 .. 23) of a world time of day (ticks: 0 = 06:00). */
    public static int hour(long timeOfDay) {
        return (int) Math.floorMod(timeOfDay / 1000 + 6, 24);
    }

    /**
     * The truck came if this slot is new (the first call only remembers it): a box for every shelf that is not full.
     * Returns the boxes that came now.
     */
    public List<String> delivery(StoreSpec spec, long slot) {
        if (lastSlot == Long.MIN_VALUE) {
            lastSlot = slot;
            return List.of();
        }
        if (slot == lastSlot) return List.of();
        lastSlot = slot;
        return deliver(spec);
    }

    /** A box now for every shelf that is not full (and has none waiting): those boxes. */
    public List<String> deliver(StoreSpec spec) {
        List<String> came = new ArrayList<>();
        for (StoreSpec.Shelf s : spec.shelves().values()) {
            if (stock(s.place()) >= s.capacity() || boxes.contains(s.box()) || s.box().equals(carrying)) continue;
            boxes.add(s.box());
            came.add(s.box());
        }
        return came;
    }

    public Set<String> boxes() {
        return boxes;
    }

    /** The worker picks up a waiting box (one at a time): false when they already carry one or there is none. */
    public boolean pick(String box) {
        if (carrying != null || !boxes.remove(box)) return false;
        carrying = box;
        return true;
    }

    public String carrying() {
        return carrying;
    }

    /** The carried box goes back down by the door (the shift ended). */
    public void putDown() {
        if (carrying != null) boxes.add(carrying);
        carrying = null;
    }

    /** The carried box onto a shelf of this place: full again. False when it is not that shelf's box (or nothing is carried). */
    public boolean restock(StoreSpec spec, String place) {
        StoreSpec.Shelf s = spec.shelves().get(place);
        if (s == null || carrying == null || !carrying.equals(s.box())) return false;
        stock.put(place, s.capacity());        carrying = null;
        return true;
    }

    /** The places that are empty now. */
    public List<String> empty() {
        List<String> out = new ArrayList<>();
        stock.forEach((k, v) -> {
            if (v <= 0) out.add(k);
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------ the microwave

    public Oven oven() {
        return oven;
    }

    public int ovenTicks() {
        return ovenTicks;
    }

    /** Puts food in (idle only): it warms for `seconds`. */
    public boolean startOven(int seconds) {
        if (oven != Oven.IDLE) return false;
        oven = Oven.WARMING;
        ovenTicks = seconds * 20;
        return true;
    }

    /** One tick: the ding when it is done, then BURNT `forget` seconds after (it goes idle: the food is spoilt). */
    public OvenEvent tickOven(int forget) {
        switch (oven) {
            case WARMING -> {
                if (--ovenTicks <= 0) {
                    oven = Oven.DONE;
                    ovenTicks = 0;
                    return OvenEvent.DING;
                }
            }
            case DONE -> {
                if (++ovenTicks >= forget * 20) {
                    oven = Oven.IDLE;
                    ovenTicks = 0;
                    return OvenEvent.BURNT;
                }
            }
            default -> {}
        }
        return OvenEvent.NONE;
    }

    /** Takes the food out once it dinged: true then (it goes idle). */
    public boolean takeOut() {
        if (oven != Oven.DONE) return false;
        oven = Oven.IDLE;
        ovenTicks = 0;
        return true;
    }

    /** The oven is emptied (the customer left, the shift ended). */
    public void resetOven() {
        oven = Oven.IDLE;
        ovenTicks = 0;
    }

    // ------------------------------------------------------------------------------------------ 폐기 (2차)

    private final Map<String, Integer> expired = new LinkedHashMap<>();
    private int carriedWaste;

    /** How many things on this shelf are past their date (red tags). */
    public int expired(String place) {
        return expired.getOrDefault(place, 0);
    }

    /** One more thing on this shelf goes past its date (at most `most`, never more than the shelf holds): true if one did. */
    public boolean expire(String place, int most) {
        int n = expired(place);
        if (n >= most || n >= Math.max(0, stock(place))) return false;
        expired.put(place, n + 1);
        return true;
    }

    /**
     * A customer takes a thing off this shelf: whether it is one of the expired (`roll` 0..1 against expired / stock; they
     * notice and complain, it goes back with its tag).
     */
    public boolean picksExpired(String place, double roll) {
        int n = expired(place), s = stock(place);
        return n > 0 && s > 0 && roll < (double) n / (s + 1);
    }

    /** The worker takes the expired things off the shelf (they leave its stock): how many. */
    public int takeExpired(String place) {
        int n = expired(place);
        if (n <= 0) return 0;
        expired.put(place, 0);
        Integer s = stock.get(place);
        if (s != null) stock.put(place, Math.max(0, s - n));
        carriedWaste += n;
        return n;
    }

    /** One expired thing handed out (to the 공시생): off the shelf, its tag gone. False when there is none. */
    public boolean giveExpired(String place) {
        int n = expired(place);
        if (n <= 0) return false;
        expired.put(place, n - 1);
        Integer s = stock.get(place);
        if (s != null) stock.put(place, Math.max(0, s - 1));
        return true;
    }

    public int carriedWaste() {
        return carriedWaste;
    }

    /** Into the 폐기 바구니: how many went. */
    public int dispose() {
        int n = carriedWaste;
        carriedWaste = 0;
        return n;
    }

    // ------------------------------------------------------------------------------------------ 청소 · 온수 (2차)

    private final List<String> spills = new ArrayList<>();
    private final boolean[] cups = new boolean[3];
    private boolean mop;
    private int water = -1;

    /** Spilt drinks on the floor (aisle node ids). */
    public List<String> spills() {
        return spills;
    }

    /** A drink spills at this aisle spot (one a spot; at most `most` messes in all): true if it did. */
    public boolean spill(String node, int most) {
        if (spills.contains(node) || messes() >= most) return false;
        spills.add(node);
        return true;
    }

    /** The worker picks up the mop (from its bucket) / puts it back. */
    public void mop(boolean holding) {
        mop = holding;
    }

    public boolean mop() {
        return mop;
    }

    /** Mops a spill (only with the mop in hand): true when it was there. */
    public boolean wipe(String node) {
        return mop && spills.remove(node);
    }

    /** A 컵라면 cup left at seat i of the eat bar (at most `most` messes): true if it was put there. */
    public boolean leaveCup(int seat, int most) {
        if (seat < 0 || seat >= cups.length || cups[seat] || messes() >= most) return false;
        cups[seat] = true;
        return true;
    }

    public boolean cup(int seat) {
        return seat >= 0 && seat < cups.length && cups[seat];
    }

    /** Clears the cup at seat i: true when there was one. */
    public boolean clearCup(int seat) {
        if (!cup(seat)) return false;
        cups[seat] = false;
        return true;
    }

    /** How many messes there are (spills and cups): each sours entering customers. */
    public int messes() {
        int n = spills.size();
        for (boolean c : cups) if (c) n++;
        return n;
    }

    /** Cups of hot water left in the kettle (full: `capacity` the first time asked). */
    public int water(int capacity) {
        if (water < 0) water = capacity;
        return water;
    }

    /** Pours a cup (a 컵라면): false when it is empty. */
    public boolean pour(int capacity) {
        if (water(capacity) <= 0) return false;
        water--;
        return true;
    }

    /** The worker fills the kettle: true when it was not full. */
    public boolean refill(int capacity) {
        if (water(capacity) >= capacity) return false;
        water = capacity;
        return true;
    }
}
