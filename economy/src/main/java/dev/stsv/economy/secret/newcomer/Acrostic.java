package dev.stsv.economy.secret.newcomer;

import java.util.List;

/** What his questions really ask: the first character of each, read in order. */
final class Acrostic {

    private Acrostic() {}

    /** The first characters of the lines, in order (spaces in front are skipped; an empty line gives nothing). */
    static String of(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            String s = l == null ? "" : l.strip();
            if (!s.isEmpty()) b.appendCodePoint(s.codePointAt(0));
        }
        return b.toString();
    }

    /** Whether the lines spell the word (spaces in the word do not count: "내 비석 어디" = "내비석어디"). */
    static boolean spells(List<String> lines, String word) {
        return word != null && of(lines).equals(word.replace(" ", ""));
    }
}
