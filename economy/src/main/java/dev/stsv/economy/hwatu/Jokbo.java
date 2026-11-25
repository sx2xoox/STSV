package dev.stsv.economy.hwatu;

import java.util.List;

/**
 * What the board in the bottom right corner shows a seated player (README "도박장"): 섯다's 족보 in pages that turn every
 * few seconds (the board holds a title and 6 rows), or the rules of 주사위 / 홀짝 on one page. Each row is a message
 * (hwatu.jokbo.&lt;row&gt;, hwatu.rules.&lt;game&gt;-&lt;n&gt;) with "{c}" where its colour goes: the row of the
 * player's own 섯다 hand is lit. Pure (unit-tested).
 */
public final class Jokbo {

    private Jokbo() {}

    /** 섯다's pages, highest first: 광땡 · 땡, 알리 ~ 세륙, 끗 and the special hands. */
    public static final List<List<String>> SUTDA = List.of(
            List.of("gwang38", "gwang18", "gwang13", "ttaeng10", "ttaeng", "ttaeng1"),
            List.of("ali", "doksa", "gupping", "jangpping", "jangsa", "seryuk"),
            List.of("kkeut", "ttaengjabi", "amhaeng", "gusa", "meong-gusa", "kkeut-note"));

    /** How long a page stays (millis) before the next. */
    public static final long PAGE_MILLIS = 4000;

    /** The rules rows of 주사위 / 홀짝 (hwatu.rules.&lt;key&gt;-1 ...). */
    public static final int RULE_ROWS = 6;

    /** The row of a hand ({@link Sutda.Hand#code()}): 9땡 ~ 2땡 share one, so do 갑오 · 끗 · 망통. */
    public static String row(String code) {
        if (code == null) return null;
        if (code.startsWith("ttaeng") && !code.equals("ttaeng10") && !code.equals("ttaeng1") && !code.equals("ttaengjabi")) return "ttaeng";
        if (code.equals("gabo") || code.equals("mangtong") || code.startsWith("kkeut")) return "kkeut";
        return code;
    }

    /** The page (0 ..) that holds a hand's row, or -1. */
    public static int pageOf(String code) {
        String r = row(code);
        for (int i = 0; i < SUTDA.size(); i++) if (SUTDA.get(i).contains(r)) return i;
        return -1;
    }

    /** The page to show now: the one with the player's hand when it is known (2 cards), else turning with the clock. */
    public static int page(String handCode, long now) {
        int mine = pageOf(handCode);
        if (mine >= 0) return mine;
        return (int) Math.floorMod(now / PAGE_MILLIS, (long) SUTDA.size());
    }
}
