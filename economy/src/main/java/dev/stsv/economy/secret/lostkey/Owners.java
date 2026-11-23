package dev.stsv.economy.secret.lostkey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 「주인 찾기」 ({@link LostKeySequel}): which thing turns up this week, at which stop its owner waits, the two or three
 * clues that say so (the same kinds as the key's: {@link Clues}), and what pulling into a stop means. Pure (unit-tested).
 * As with the key, every stop that fits all the clues counts: the clues are all the driver ever hears.
 */
final class Owners {

    private Owners() {}

    /** The same number for a week and a player, every time. */
    static int hash(String week, UUID u) {
        return (week + "/" + u).hashCode();
    }

    /** "3,0,5" → the variants of the last weeks, newest last. */
    static List<Integer> recent(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null) return out;
        for (String w : raw.split(",")) {
            try {
                if (!w.isBlank()) out.add(Integer.parseInt(w.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited note
            }
        }
        return out;
    }

    /** This week's variant (0..count-1): by the week and the player, stepping past the ones of the last weeks. */
    static int variant(String week, UUID u, int count, Collection<Integer> recent) {
        if (count <= 0) return 0;
        int i = Math.floorMod(hash(week, u), count);
        for (int tries = 0; tries < count && recent.contains(i); tries++) i = (i + 1) % count;
        return i;
    }

    /** The recent list with this week's variant added, at most keep long (and never all of them: one must stay free). */
    static String remember(String raw, int picked, int keep, int count) {
        List<Integer> r = recent(raw);
        r.remove(Integer.valueOf(picked));
        r.add(picked);
        int max = Math.max(0, Math.min(keep, count - 1));
        while (r.size() > max) r.removeFirst();
        List<String> out = new ArrayList<>();
        for (Integer i : r) out.add(String.valueOf(i));
        return String.join(",", out);
    }

    /** The stop the owner waits at: any stop but the one the thing turned up at, by the week and the player. Null if none. */
    static Clues.Stop target(List<Clues.Stop> net, int here, int seed) {
        List<Clues.Stop> can = new ArrayList<>();
        for (Clues.Stop s : net) if (s.id() != here) can.add(s);
        return can.isEmpty() ? null : can.get(Math.floorMod(seed, can.size()));
    }

    /**
     * The clues to a stop, two or three: its rarest trait (or "no trait"), the initial of its name, and — when those two
     * still fit more than one stop and it is a line's end — that it is a first stop or an end.
     */
    static List<Clues.Clue> clues(Clues.Stop target, List<Clues.Stop> net, List<String> tagOrder) {
        List<Clues.Clue> out = new ArrayList<>();
        String best = null;
        int bestN = Integer.MAX_VALUE;
        for (String t : tagOrder) {
            if (!target.tags().contains(t)) continue;
            int n = 0;
            for (Clues.Stop s : net) if (s.tags().contains(t)) n++;
            if (n < bestN) {
                bestN = n;
                best = t;
            }
        }
        if (best != null) out.add(new Clues.Clue(Clues.Kind.TAG, best));
        else if (target.tags().isEmpty()) out.add(new Clues.Clue(Clues.Kind.PLAIN, ""));
        String initial = Clues.initialOf(target.name());
        if (!initial.isEmpty()) out.add(new Clues.Clue(Clues.Kind.INITIAL, initial));
        if ((out.size() < 2 || fitting(out, net).size() > 1) && (target.first() || target.last()))
            out.add(new Clues.Clue(target.first() ? Clues.Kind.FIRST : Clues.Kind.END, ""));
        if (out.isEmpty()) out.add(new Clues.Clue(Clues.Kind.ANY, ""));
        return out;
    }

    /** Whether a stop fits every clue. */
    static boolean fits(List<Clues.Clue> clues, Clues.Stop s, int oldest) {
        if (s == null || clues.isEmpty()) return false;
        for (Clues.Clue c : clues) if (!Clues.matches(c, s, oldest)) return false;
        return true;
    }

    /** The stops that fit every clue (ids, in the network's order). */
    static List<Integer> fitting(List<Clues.Clue> clues, Collection<Clues.Stop> net) {
        int oldest = net.stream().mapToInt(Clues.Stop::id).min().orElse(-1);
        List<Integer> out = new ArrayList<>();
        for (Clues.Stop s : net) if (fits(clues, s, oldest)) out.add(s.id());
        return out;
    }

    static String encode(List<Clues.Clue> clues) {
        List<String> out = new ArrayList<>();
        for (Clues.Clue c : clues) out.add(c.encode());
        return String.join("|", out);
    }

    static List<Clues.Clue> decode(String raw) {
        List<Clues.Clue> out = new ArrayList<>();
        if (raw == null) return out;
        for (String w : raw.split("\\|")) {
            Clues.Clue c = Clues.Clue.decode(w);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** What pulling into a stop with the thing aboard means. */
    enum Arrive {
        /** the stop it turned up at: nothing */
        NOTHING,
        /** the owner waits here */
        RIGHT,
        /** not here (counted now) */
        WRONG,
        /** not here, and that was the last try: it goes to the shelf */
        SHELF,
        /** not here, said already (not counted twice) */
        AGAIN
    }

    /** wrongs: the wrong stops counted so far; max: how many wrong stops end it. */
    static Arrive arrive(int stop, int foundAt, boolean fits, Collection<Integer> wrongs, int max) {
        if (stop == foundAt) return Arrive.NOTHING;
        if (fits) return Arrive.RIGHT;
        if (wrongs.contains(stop)) return Arrive.AGAIN;
        return wrongs.size() + 1 >= Math.max(1, max) ? Arrive.SHELF : Arrive.WRONG;
    }
}
