package dev.stsv.core.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Hidden until owned: a reward marked hidden (a title's {@code hidden: true}, a cosmetic's {@code cosmetic.hidden: true},
 * a banner theme in {@code title-banners.hidden}) is left out of every player's list and every "n/m" count until
 * that player has it. Admin lists and commands still show everything.
 */
public final class HiddenUntilOwned {

    private HiddenUntilOwned() {}

    /** Whether an entry shows in a player's list: not hidden, or theirs. */
    public static boolean shows(boolean hidden, boolean owned) {
        return !hidden || owned;
    }

    /** The entries a player sees, in the same order. */
    public static <T> List<T> filter(Collection<T> all, Predicate<? super T> hidden, Predicate<? super T> owned) {
        List<T> out = new ArrayList<>(all.size());
        for (T t : all) if (shows(hidden.test(t), owned.test(t))) out.add(t);
        return out;
    }
}
