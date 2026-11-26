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
        stock.put(place, s.capacity());
        carrying = null;
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
}
