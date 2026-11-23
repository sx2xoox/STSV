package dev.stsv.economy.secret.newcomer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * How long he takes to type a line (a moment to think, then a few characters a second, a little uneven), and the odd
 * slip of the fingers on a 2-beolsik keyboard, put right in a second short line ("*캐요"). A slip never touches the first
 * character of a line (that one is part of what he is really asking).
 */
final class Typing {

    private Typing() {}

    /**
     * Milliseconds from deciding to say a line to its appearing: think (thinkLo..thinkHi seconds) plus its length at
     * cpsLo..cpsHi characters a second (spaces count half), and ±10% on the whole.
     */
    static long millis(String line, RandomGenerator r, double thinkLo, double thinkHi, double cpsLo, double cpsHi) {
        double think = thinkLo + r.nextDouble() * Math.max(0, thinkHi - thinkLo);
        double cps = Math.max(0.5, cpsLo + r.nextDouble() * Math.max(0, cpsHi - cpsLo));
        double chars = 0;
        for (int i = 0; i < line.length(); i++) chars += line.charAt(i) == ' ' ? 0.5 : 1;
        double s = (think + chars / cps) * (0.9 + r.nextDouble() * 0.2);
        return Math.round(s * 1000);
    }

    /** A line with a slip in it, and the word it should have been (what the correction line puts right). */
    record Slip(String typed, String word) {}

    // 2-beolsik rows: initial consonants (choseong index) and vowels (jungseong index), neighbours side by side
    private static final int[][] CHO_ROWS = {{7, 12, 3, 0, 9}, {6, 2, 11, 5, 18}, {15, 16, 14, 17}};
    private static final int[][] JUNG_ROWS = {{12, 6, 2, 1, 5}, {8, 4, 0, 20}, {17, 13, 18}};

    static boolean syllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    private static boolean wordChar(char c) {
        return syllable(c) || c < 0x80 && Character.isLetterOrDigit(c);
    }

    private static char compose(int cho, int jung, int jong) {
        return (char) (0xAC00 + (cho * 21 + jung) * 28 + jong);
    }

    private static List<Integer> neighbours(int[][] rows, int v) {
        List<Integer> out = new ArrayList<>();
        for (int[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] != v) continue;
                if (i > 0) out.add(row[i - 1]);
                if (i + 1 < row.length) out.add(row[i + 1]);
            }
        }
        return out;
    }

    /**
     * One slip in a line, or empty when none fits: a vowel or an initial consonant hit with the key beside it, a final
     * consonant left off, or two syllables of a word swapped. Never at the first character; only inside a word of
     * Hangul syllables, whose right form is given back (without "?" or anything else around it).
     */
    static Optional<Slip> slip(String line, RandomGenerator r) {
        if (line == null || line.length() < 3) return Optional.empty();
        List<Integer> spots = new ArrayList<>();
        for (int i = 1; i < line.length(); i++) if (syllable(line.charAt(i))) spots.add(i);
        if (spots.isEmpty()) return Optional.empty();
        for (int tries = 0; tries < 8; tries++) {
            int i = spots.get(r.nextInt(spots.size()));
            char c = line.charAt(i);
            int s = c - 0xAC00, cho = s / (21 * 28), jung = (s / 28) % 21, jong = s % 28;
            List<char[]> kinds = new ArrayList<>();
            for (int v : neighbours(JUNG_ROWS, jung)) kinds.add(new char[]{compose(cho, v, jong)});
            for (int v : neighbours(CHO_ROWS, cho)) kinds.add(new char[]{compose(v, jung, jong)});
            if (jong != 0) kinds.add(new char[]{compose(cho, jung, 0)});
            boolean swap = i + 1 < line.length() && syllable(line.charAt(i + 1)) && line.charAt(i + 1) != c;
            if (swap) kinds.add(new char[]{line.charAt(i + 1), c});
            if (kinds.isEmpty()) continue;
            char[] with = kinds.get(r.nextInt(kinds.size()));
            String typed = line.substring(0, i) + new String(with) + line.substring(i + with.length);
            if (typed.equals(line) || typed.charAt(0) != line.charAt(0)) continue;
            String word = word(line, i);
            if (word.isEmpty()) continue;
            return Optional.of(new Slip(typed, word));
        }
        return Optional.empty();
    }

    /** The word of Hangul syllables (and letters, digits) around position i. */
    static String word(String line, int i) {
        int a = i, b = i + 1;
        while (a > 0 && wordChar(line.charAt(a - 1))) a--;
        while (b < line.length() && wordChar(line.charAt(b))) b++;
        return line.substring(a, b);
    }
}
