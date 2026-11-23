package dev.stsv.economy.secret.scarecrow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The plain sums of the 후속 임무 「밭의 부탁」 ({@link ScarecrowSequel}): which one thing the scarecrow asks for this week,
 * which thank-you it says, and whether the week's small gift (the crops of its chunk growing a little faster) still
 * holds. Pure: no server needed (ScarecrowFavorTest).
 */
final class Favor {

    private Favor() {}

    /** The same for one player in one week (a restart asks for the same thing), different the next week. */
    static long seed(String week, UUID player) {
        long h = 7046029254386353131L;
        for (int i = 0; i < week.length(); i++) h = 31 * h + week.charAt(i);
        return h ^ Long.rotateLeft(player.getMostSignificantBits(), 11) ^ player.getLeastSignificantBits();
    }

    /**
     * Which of `size` (asks, lines) it is this week: fixed by the seed, and not one of the recent ones while another is
     * left. -1 when there is nothing to choose from.
     */
    static int pick(long seed, int size, Collection<Integer> recent) {
        if (size <= 0) return -1;
        int first = (int) Math.floorMod(seed ^ (seed >>> 31), (long) size);
        for (int i = 0; i < size; i++) {
            int at = (first + i) % size;
            if (!recent.contains(at)) return at;
        }
        return first;
    }

    /** The index of the ask with this id (the one saved for the going mission), or -1 when the file no longer has it. */
    static int indexOf(List<Riddles.Ask> asks, String id) {
        for (int i = 0; i < asks.size(); i++) if (asks.get(i).id().equals(id)) return i;
        return -1;
    }

    /** The asks that can be answered at all (one that names no item, or is "what you planted most", is not for the errand). */
    static List<Riddles.Ask> usable(List<Riddles.Ask> asks) {
        List<Riddles.Ask> out = new ArrayList<>();
        for (Riddles.Ask a : asks) if (!a.most() && !a.items().isEmpty()) out.add(a);
        return out;
    }

    static List<Integer> numbers(String saved) {
        List<Integer> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String w : saved.split(",")) {
            try {
                out.add(Integer.parseInt(w.trim()));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    static String join(List<Integer> all) {
        List<String> out = new ArrayList<>();
        for (int n : all) out.add(String.valueOf(n));
        return String.join(",", out);
    }

    /** The list with one more at its end, cut to the last `keep`. */
    static List<Integer> remember(List<Integer> recent, int one, int keep) {
        List<Integer> out = new ArrayList<>(recent);
        out.remove(Integer.valueOf(one));
        out.add(one);
        while (out.size() > Math.max(0, keep)) out.removeFirst();
        return out;
    }

    /**
     * Whether the week's gift holds: it was earned in this very week (the note of an older week is simply stale — it is
     * deleted the next time its owner is online, and gives nothing until then).
     */
    static boolean holds(String earnedWeek, String weekNow) {
        return earnedWeek != null && earnedWeek.equals(weekNow);
    }

    /** Whether a block column lies in a chunk. */
    static boolean inChunk(int x, int z, int cx, int cz) {
        return (x >> 4) == cx && (z >> 4) == cz;
    }

    /** The middle block of a chunk along one axis (the growth pulse reaches 8 blocks each way from it). */
    static int middle(int chunk) {
        return (chunk << 4) + 8;
    }

    /** Whether a second of the clock is one on which something that happens every `every` seconds happens. */
    static boolean beat(long clock, long every) {
        return every > 0 && Math.floorMod(clock, every) == 0;
    }
}
