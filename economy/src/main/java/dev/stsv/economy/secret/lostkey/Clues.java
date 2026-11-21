package dev.stsv.economy.secret.lostkey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The five clues of 분실물센터 (docs/admin/숨은_비밀/분실물센터.md): which stops the nameless key rattles at, worked out from
 * the bus network as it is (its stops, their traits, the lines' ends) and from the player (their id, their name, where
 * they found the key). A clue is a kind and one word (a trait, an initial, a stop id); it is saved the moment the
 * whispers tell it, so a stop built later never changes what was said. Every stop that fits a clue counts. Pure.
 */
public final class Clues {

    private Clues() {}

    /** What a clue points at. */
    public enum Kind {
        /** a line's first stop (the terminal end: 첫차) */
        FIRST,
        /** a stop with this trait (sea, mountain …) */
        TAG,
        /** a stop with no trait at all (fallback of TAG) */
        PLAIN,
        /** a stop whose name starts with this initial (a Hangul initial consonant, or a letter) */
        INITIAL,
        /** either end of a line: the 분실물 센터 (where nobody's things are handed in) */
        END,
        /** the stop the key was found at (its id) */
        FOUND,
        /** the oldest stop (the lowest id: fallback of FOUND) */
        OLDEST,
        /** any stop (the last fallback of all) */
        ANY
    }

    /** A stop as the clues see it. first/last: the first or last stop of some line. */
    public record Stop(int id, String name, Set<String> tags, boolean first, boolean last) {}

    /** A clue: its kind and word ("sea", "ㅅ", "12", or ""). */
    public record Clue(Kind kind, String word) {
        public String encode() {
            return kind + " " + word;
        }

        public static Clue decode(String s) {
            if (s == null || s.isBlank()) return null;
            String[] w = s.split(" ", 2);
            try {
                return new Clue(Kind.valueOf(w[0]), w.length > 1 ? w[1] : "");
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** Hangul initial consonants in syllable order (U+AC00 + 588 × index). */
    static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

    /**
     * The clue for stage n (1..5): 1 a line's first stop, 2 a trait (one per player among the traits that tell some stops
     * from the rest, in {@code tagOrder}), 3 the initial of the player's name as it sounds in Korean, 4 a line's end (the
     * lost and found), 5 the stop the key was found at. Each falls back to something that exists.
     */
    public static Clue pick(int stage, List<Stop> net, UUID player, String name, Integer found, List<String> tagOrder) {
        Clue c = switch (stage) {
            case 1 -> new Clue(Kind.FIRST, "");
            case 2 -> tag(net, player, tagOrder);
            case 3 -> initial(net, name);
            case 4 -> new Clue(Kind.END, "");
            default -> found != null && net.stream().anyMatch(s -> s.id() == found) ? new Clue(Kind.FOUND, String.valueOf(found))
                    : net.stream().mapToInt(Stop::id).min().isPresent() ? new Clue(Kind.OLDEST, "") : new Clue(Kind.ANY, "");
        };
        return matching(c, net).isEmpty() ? new Clue(Kind.ANY, "") : c;
    }

    /** A trait some stops have and others not (by the player's id among them), else one any stop has, else "no trait". */
    static Clue tag(List<Stop> net, UUID player, List<String> order) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String t : order) n.put(t, 0);
        for (Stop s : net) for (String t : s.tags()) if (n.containsKey(t)) n.merge(t, 1, Integer::sum);
        List<String> telling = new ArrayList<>(), any = new ArrayList<>();
        n.forEach((t, c) -> {
            if (c > 0) any.add(t);
            if (c > 0 && c < net.size()) telling.add(t);
        });
        List<String> from = telling.isEmpty() ? any : telling;
        if (from.isEmpty()) return new Clue(Kind.PLAIN, "");
        return new Clue(Kind.TAG, from.get(Math.floorMod(player.hashCode(), from.size())));
    }

    /**
     * The initial of the player's name in Korean (S → ㅅ, K → ㅋ …): the first letter of their name whose sound starts some
     * stop's name (told as the initial that really starts one: ㄱ when there is no ㅋ), else the initial most stops share.
     */
    static Clue initial(List<Stop> net, String name) {
        Set<String> starts = new TreeSet<>();
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Stop s : net) {
            String i = initialOf(s.name());
            if (i.isEmpty()) continue;
            starts.add(i);
            count.merge(i, 1, Integer::sum);
        }
        if (name != null) {
            for (char ch : name.toCharArray()) {
                if (!Character.isLetter(ch)) continue;
                String first = initialOf(String.valueOf(ch));   // a Korean name's own initial, or the letter itself
                if (starts.contains(first)) return new Clue(Kind.INITIAL, first);
                String j = jamoFor(ch);
                if (j == null) continue;
                for (String x : family(j)) if (starts.contains(x)) return new Clue(Kind.INITIAL, x);
            }
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> new Clue(Kind.INITIAL, e.getKey()))
                .orElse(new Clue(Kind.ANY, ""));
    }

    /** The stops a clue points at (ids, in the network's order). */
    public static List<Integer> matching(Clue c, Collection<Stop> net) {
        int oldest = net.stream().mapToInt(Stop::id).min().orElse(-1);
        List<Integer> out = new ArrayList<>();
        for (Stop s : net) if (matches(c, s, oldest)) out.add(s.id());
        return out;
    }

    /** Whether a stop fits a clue (oldest: the lowest stop id there is, for OLDEST). */
    public static boolean matches(Clue c, Stop s, int oldest) {
        return switch (c.kind()) {
            case FIRST -> s.first();
            case TAG -> s.tags().contains(c.word());
            case PLAIN -> s.tags().isEmpty();
            case INITIAL -> {
                String i = initialOf(s.name());
                yield !i.isEmpty() && family(c.word()).contains(i);
            }
            case END -> s.first() || s.last();
            case FOUND -> String.valueOf(s.id()).equals(c.word());
            case OLDEST -> s.id() == oldest;
            case ANY -> true;
        };
    }

    // ------------------------------------------------------------------------------------------
    // initials
    // ------------------------------------------------------------------------------------------

    /** A name's initial: the first Hangul syllable's initial consonant, else its first letter (upper case), else "". */
    static String initialOf(String name) {
        if (name == null) return "";
        for (char ch : name.trim().toCharArray()) {
            if (ch >= 0xAC00 && ch <= 0xD7A3) return String.valueOf(INITIALS.charAt((ch - 0xAC00) / 588));
            if (INITIALS.indexOf(ch) >= 0) return String.valueOf(ch);
            if (Character.isLetter(ch)) return String.valueOf(Character.toUpperCase(ch));
            if (!Character.isWhitespace(ch)) return "";
        }
        return "";
    }

    /** The Korean initial a Latin letter sounds like (S → ㅅ, F → ㅍ, vowels → ㅇ), or null. */
    static String jamoFor(char latin) {
        int i = "abcdefghijklmnopqrstuvwxyz".indexOf(Character.toLowerCase(latin));
        return i < 0 ? null : String.valueOf("ㅇㅂㅋㄷㅇㅍㄱㅎㅇㅈㅋㄹㅁㄴㅇㅍㅋㄹㅅㅌㅇㅂㅇㅇㅇㅈ".charAt(i));
    }

    /** An initial and its plain, tense and aspirated kin, itself first (ㅋ → ㅋ ㄱ ㄲ); a letter is only itself. */
    static List<String> family(String j) {
        for (String f : List.of("ㄱㄲㅋ", "ㄷㄸㅌ", "ㅂㅃㅍ", "ㅅㅆ", "ㅈㅉㅊ")) {
            if (j.length() == 1 && f.indexOf(j.charAt(0)) >= 0) {
                List<String> out = new ArrayList<>(List.of(j));
                for (char ch : f.toCharArray()) if (ch != j.charAt(0)) out.add(String.valueOf(ch));
                return out;
            }
        }
        return List.of(j);
    }

    /** "으로" or "로" after an initial or letter as it is read (ㄹ 리을로, ㅅ 시옷으로; M 엠으로, S 에스로). */
    static String ro(String j) {
        if (j.equals("ㄹ")) return "로";
        if (j.length() == 1 && INITIALS.indexOf(j.charAt(0)) >= 0) return "으로";
        return j.equals("M") || j.equals("N") ? "으로" : "로";
    }
}
