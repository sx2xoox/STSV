package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A storage bed of any number of 45-slot pages (the truck's 2, the hand cart's 6): which slots a page shows, what a
 * page's view puts back, and the bed resized for its vehicle. Generic over the item type, so it is unit-tested without a
 * server; the module uses it with ItemStacks (null = an empty slot).
 */
public final class CargoPages {

    public static final int PAGE = VehicleSpec.Cargo.PAGE;

    private CargoPages() {}

    /** How many pages hold this many slots. */
    public static int pages(int slots) {
        return Math.max(1, (slots + PAGE - 1) / PAGE);
    }

    /** A page number kept inside the bed (0 .. pages - 1). */
    public static int clamp(int page, int pages) {
        return Math.clamp(page, 0, Math.max(0, pages - 1));
    }

    /** The bed slot that a page's slot shows. */
    public static int index(int page, int slot) {
        return page * PAGE + slot;
    }

    /** The 45 items a page shows (null past the bed's end). */
    public static <T> List<T> page(T[] bed, int page) {
        List<T> out = new ArrayList<>(PAGE);
        for (int i = 0; i < PAGE; i++) {
            int at = index(page, i);
            out.add(bed != null && at < bed.length ? bed[at] : null);
        }
        return out;
    }

    /**
     * Puts what a page's view holds back into the bed: shown.apply(slot) for each of its 45 slots (null = empty).
     * Returns whether anything changed (then the bed is saved and its load model may change).
     */
    public static <T> boolean store(T[] bed, int page, IntFunction<T> shown) {
        if (bed == null) return false;
        boolean changed = false;
        for (int i = 0; i < PAGE; i++) {
            int at = index(page, i);
            if (at >= bed.length) break;
            T now = shown.apply(i);
            if (!Objects.equals(bed[at], now)) {
                bed[at] = now;
                changed = true;
            }
        }
        return changed;
    }

    /** How many slots hold something. */
    public static int used(Object[] bed) {
        if (bed == null) return 0;
        int n = 0;
        for (Object o : bed) if (o != null) n++;
        return n;
    }

    /**
     * The bed sized for a vehicle of this many slots, never smaller than its last filled slot (fewer slots in the item
     * file must not lose a load: it shows on extra pages until taken out). null when it has no bed and holds nothing.
     */
    public static <T> T[] resized(T[] bed, int slots, IntFunction<T[]> make) {
        int last = 0;
        if (bed != null) for (int i = 0; i < bed.length; i++) if (bed[i] != null) last = i + 1;
        if (slots <= 0 && last == 0) return null;
        T[] out = make.apply(Math.max(slots, last));
        if (bed != null) System.arraycopy(bed, 0, out, 0, Math.min(bed.length, out.length));
        return out;
    }
}
