package dev.stsv.economy.secret.doll;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The plain sums of the 후속 임무 「인형의 심부름」 ({@link DollSequel}): which riddle the doll whispers this week, who answers
 * it now, when to say that the answer has changed, and its small hints. It asks with the secret's own riddles
 * ({@link Riddles}); nothing here touches the server (DollErrandTest).
 */
final class Errand {

    private Errand() {}

    /** The same for one player in one week (a restart goes on with the same riddle and the same line), different the next. */
    static long seed(String week, UUID player) {
        long h = 1125899906842597L;
        for (int i = 0; i < week.length(); i++) h = 31 * h + week.charAt(i);
        return h ^ player.getMostSignificantBits() ^ Long.rotateLeft(player.getLeastSignificantBits(), 23);
    }

    /**
     * This week's riddle: the first of the pool (in the order the seed fixes) that someone among `facts` (everyone online
     * but the solver) answers right now and that was not asked in the last few weeks; when only recent ones can be
     * answered, one of those; "any stranger" when no riddle names anyone; null when nobody else is there (it waits).
     */
    static Riddles.Kind choose(long seed, Collection<Riddles.Kind> recent, List<Riddles.Fact> facts) {
        if (facts.isEmpty()) return null;
        Set<Riddles.Kind> used = EnumSet.noneOf(Riddles.Kind.class);
        used.addAll(recent);
        Riddles.Kind k = Riddles.choose(seed, used, facts);
        if (k == Riddles.Kind.STRANGER && !used.isEmpty()) k = Riddles.choose(seed, EnumSet.noneOf(Riddles.Kind.class), facts);
        return k;
    }

    /**
     * Who counts right now. A riddle about "just now" (the deepest, the highest, the farthest, the last one in) keeps the
     * people it named when it was told, as long as one of them is still here: walking up to the farthest one must not make
     * someone else the farthest. Every other riddle is read off the records as they are this second. When none of the
     * named are left, whoever answers it now.
     */
    static Set<UUID> targets(Riddles.Kind k, Set<UUID> told, List<Riddles.Fact> facts) {
        if (k == null) return Set.of();
        if (k.moving() && !told.isEmpty()) {
            Set<UUID> here = new LinkedHashSet<>();
            for (Riddles.Fact f : facts) if (told.contains(f.id())) here.add(f.id());
            if (!here.isEmpty()) return here;
        }
        return Riddles.answers(k, facts);
    }

    /** Whether the doll must say that the answer changed: someone counts now who did not before (one of several leaving is not news). */
    static boolean changed(Set<UUID> before, Set<UUID> now) {
        return !before.isEmpty() && !now.isEmpty() && !before.containsAll(now);
    }

    /** Whether someone at this offset stands close enough (a cylinder: `radius` across the ground, 3 blocks up or down). */
    static boolean beside(double dx, double dy, double dz, double radius) {
        return Math.abs(dy) <= 3 && dx * dx + dz * dz <= radius * radius;
    }

    /** The first letter of a name, for the first hint ("민" of 민수, "S" of Sam). */
    static String initial(String name) {
        if (name == null || name.isBlank()) return "?";
        String s = name.strip();
        return new String(Character.toChars(s.codePointAt(0)));
    }

    /**
     * Which of `size` lines to say: fixed by the seed, and not one of the recent ones while another is left.
     * Returns -1 when there are no lines.
     */
    static int line(long seed, int size, Collection<Integer> recent) {
        if (size <= 0) return -1;
        int first = (int) Math.floorMod(seed ^ (seed >>> 29), (long) size);
        for (int i = 0; i < size; i++) {
            int at = (first + i) % size;
            if (!recent.contains(at)) return at;
        }
        return first;
    }

    // ---- small lists as saved

    static String join(Collection<?> all) {
        List<String> out = new ArrayList<>();
        for (Object o : all) out.add(String.valueOf(o));
        return String.join(",", out);
    }

    static Set<UUID> ids(String saved) {
        Set<UUID> out = new LinkedHashSet<>();
        if (saved == null || saved.isBlank()) return out;
        for (String w : saved.split(",")) {
            try {
                out.add(UUID.fromString(w.trim()));
            } catch (IllegalArgumentException ignored) {
                // not an id: left out
            }
        }
        return out;
    }

    static List<Riddles.Kind> kinds(String saved) {
        List<Riddles.Kind> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String w : saved.split(",")) {
            Riddles.Kind k = Riddles.Kind.of(w.trim());
            if (k != null) out.add(k);
        }
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

    /** The list with one more at its end, cut to the last `keep` (the oldest go first). */
    static <T> List<T> remember(List<T> recent, T one, int keep) {
        List<T> out = new ArrayList<>(recent);
        out.remove(one);
        out.add(one);
        while (out.size() > Math.max(0, keep)) out.removeFirst();
        return out;
    }
}
