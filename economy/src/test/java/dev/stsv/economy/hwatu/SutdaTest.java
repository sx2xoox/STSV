package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 섯다 족보 (README "화투방"): every hand, every pair of hands against a separately written referee, and the special rules. */
class SutdaTest {

    static Card g(int m) {   // 광 (1, 3, 8)
        Card c = Card.of(m, true);
        assertTrue(c.gwang());
        return c;
    }

    static Card y(int m) {   // 열끗 (the first card of 2, 4, 5, 6, 7, 9, 10; the second of 8)
        Card c = m == 8 ? Card.of(8, false) : Card.of(m, true);
        assertTrue(c.yeol());
        return c;
    }

    static Card t(int m) {   // 띠 (the second card; not 8)
        Card c = Card.of(m, false);
        assertEquals(Card.Kind.TTI, c.kind());
        return c;
    }

    static Sutda.Hand h(Card a, Card b) {
        return Sutda.of(a, b);
    }

    @Test
    void theDeckIsTwentyCardsTwoAMonth() {
        List<Card> d = Card.deck();
        assertEquals(20, d.size());
        assertEquals(20, d.stream().distinct().count());
        assertEquals(3, d.stream().filter(Card::gwang).count());
        // 열끗: 2 4 5 6 7 9 10 (first cards) and 8's 기러기
        assertEquals(8, d.stream().filter(Card::yeol).count());
        assertEquals(9, d.stream().filter(c -> c.kind() == Card.Kind.TTI).count());
        for (int m = 1; m <= 10; m++) {
            int month = m;
            assertEquals(2, d.stream().filter(c -> c.month() == month).count());
        }
        assertEquals("economy:hwatu_8a", g(8).item());
        assertEquals("economy:hwatu_8b", y(8).item());
        assertEquals("economy:hwatu_10a", y(10).item());
        assertEquals(20, d.stream().map(Card::item).distinct().count());
    }

    @Test
    void namedHands() {
        assertEquals("gwang38", h(g(3), g(8)).code());
        assertEquals("gwang18", h(g(8), g(1)).code());
        assertEquals("gwang13", h(g(1), g(3)).code());
        assertEquals("ttaeng10", h(y(10), t(10)).code());
        assertEquals("ttaeng1", h(g(1), t(1)).code());
        assertEquals("ttaeng8", h(g(8), y(8)).code());   // 8광 + 8열끗: a 땡, not a 광땡
        assertEquals("ali", h(t(2), g(1)).code());
        assertEquals("doksa", h(t(1), y(4)).code());
        assertEquals("gupping", h(t(1), t(9)).code());
        assertEquals("jangpping", h(g(1), y(10)).code());
        assertEquals("jangsa", h(t(4), t(10)).code());
        assertEquals("seryuk", h(y(4), y(6)).code());
        assertEquals("gabo", h(t(2), t(7)).code());
        assertEquals("gabo", h(y(4), y(5)).code());
        assertEquals("kkeut8", h(g(3), y(5)).code());
        assertEquals("mangtong", h(t(2), y(8)).code());
        assertEquals("mangtong", h(y(2), g(8)).code());
        // 1·3 without both 광 is a 4끗, 3·8 without both 광 a 1끗
        assertEquals("kkeut4", h(t(1), g(3)).code());
        assertEquals("kkeut1", h(g(3), y(8)).code());
    }

    @Test
    void specialHandsNeedTheirExactCards() {
        Sutda.Hand jabi = h(g(3), y(7));
        assertEquals(Sutda.Special.TTAENGJABI, jabi.special());
        assertEquals(Sutda.KKEUT, jabi.rank());   // otherwise 망통
        for (Card[] c : new Card[][]{{t(3), y(7)}, {g(3), t(7)}, {t(3), t(7)}}) {
            assertEquals(Sutda.Special.NONE, h(c[0], c[1]).special());
            assertEquals("mangtong", h(c[0], c[1]).code());
        }
        Sutda.Hand amhaeng = h(y(7), y(4));
        assertEquals(Sutda.Special.AMHAENG, amhaeng.special());
        assertEquals(Sutda.KKEUT + 1, amhaeng.rank());   // otherwise 1끗
        assertEquals(Sutda.Special.NONE, h(t(4), y(7)).special());
        assertEquals(Sutda.Special.NONE, h(y(4), t(7)).special());
        assertEquals(Sutda.Special.MEONG_GUSA, h(y(4), y(9)).special());
        for (Card[] c : new Card[][]{{t(4), y(9)}, {y(4), t(9)}, {t(4), t(9)}}) assertEquals(Sutda.Special.GUSA, h(c[0], c[1]).special());
        assertEquals(Sutda.KKEUT + 3, h(t(4), t(9)).rank());   // otherwise 3끗
        assertThrows(IllegalArgumentException.class, () -> h(g(1), g(1)));
    }

    @Test
    void theOrderOfTheNormalHands() {
        List<Sutda.Hand> order = List.of(h(g(3), g(8)), h(g(1), g(8)), h(g(1), g(3)), h(y(10), t(10)), h(y(9), t(9)), h(g(8), y(8)),
                h(y(7), t(7)), h(y(6), t(6)), h(y(5), t(5)), h(y(4), t(4)), h(g(3), t(3)), h(y(2), t(2)), h(g(1), t(1)),
                h(g(1), y(2)), h(g(1), y(4)), h(g(1), y(9)), h(g(1), y(10)), h(y(4), y(10)), h(y(4), y(6)),
                h(t(2), t(7)), h(t(3), t(5)), h(t(2), t(5)), h(t(1), t(5)), h(t(2), t(3)), h(t(1), t(3)), h(t(5), y(8)), h(t(2), y(10)), h(t(5), y(6)),
                h(t(2), y(8)));
        for (int i = 1; i < order.size(); i++) {
            assertTrue(order.get(i - 1).rank() > order.get(i).rank(), order.get(i - 1).code() + " > " + order.get(i).code());
            assertEquals(List.of(0), Sutda.showdown(List.of(order.get(i - 1), order.get(i)), true).winners());
            assertEquals(List.of(1), Sutda.showdown(List.of(order.get(i), order.get(i - 1)), true).winners());
        }
        assertEquals("gabo", order.get(19).code());
        assertEquals("mangtong", order.getLast().code());
    }

    // ------------------------------------------------------------------------------------------ the referee

    private static final List<String> RANKING = List.of("38", "18", "13", "d10", "d9", "d8", "d7", "d6", "d5", "d4", "d3", "d2", "d1",
            "알리", "독사", "구삥", "장삥", "장사", "세륙", "k9", "k8", "k7", "k6", "k5", "k4", "k3", "k2", "k1", "k0");

    /** What a hand is, worked out from the cards by a different route than Sutda.of: its place in RANKING. */
    private static String referee(Card a, Card b) {
        if (a.gwang() && b.gwang()) {
            int sum = a.month() + b.month();
            return sum == 11 ? "38" : sum == 9 ? "18" : "13";
        }
        if (a.month() == b.month()) return "d" + a.month();
        java.util.Set<Integer> ms = java.util.Set.of(a.month(), b.month());
        if (ms.equals(java.util.Set.of(1, 2))) return "알리";
        if (ms.equals(java.util.Set.of(1, 4))) return "독사";
        if (ms.equals(java.util.Set.of(1, 9))) return "구삥";
        if (ms.equals(java.util.Set.of(1, 10))) return "장삥";
        if (ms.equals(java.util.Set.of(4, 10))) return "장사";
        if (ms.equals(java.util.Set.of(4, 6))) return "세륙";
        return "k" + (a.month() + b.month()) % 10;
    }

    private static boolean is(Card a, Card b, Card x, Card y) {
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    /** 0: the first wins, 1: the second, 2: split, 3: replay. */
    private static int judge(Card[] p, Card[] q, boolean replayAllowed) {
        String rp = referee(p[0], p[1]), rq = referee(q[0], q[1]);
        int ip = RANKING.indexOf(rp), iq = RANKING.indexOf(rq);
        boolean gusaP = p[0].month() + p[1].month() == 13 && java.util.Set.of(p[0].month(), p[1].month()).equals(java.util.Set.of(4, 9));
        boolean gusaQ = q[0].month() + q[1].month() == 13 && java.util.Set.of(q[0].month(), q[1].month()).equals(java.util.Set.of(4, 9));
        boolean meongP = gusaP && p[0].yeol() && p[1].yeol(), meongQ = gusaQ && q[0].yeol() && q[1].yeol();
        if (replayAllowed) {
            // 구사: the other is 알리 or lower; 멍텅구리: the other is 9땡 or lower
            if (gusaP && iq >= RANKING.indexOf(meongP ? "d9" : "알리")) return 3;
            if (gusaQ && ip >= RANKING.indexOf(meongQ ? "d9" : "알리")) return 3;
        }
        boolean amP = is(p[0], p[1], y(4), y(7)), amQ = is(q[0], q[1], y(4), y(7));
        if (amP && (rq.equals("18") || rq.equals("13"))) return 0;
        if (amQ && (rp.equals("18") || rp.equals("13"))) return 1;
        boolean jabiP = is(p[0], p[1], g(3), y(7)), jabiQ = is(q[0], q[1], g(3), y(7));
        if (jabiP && rq.startsWith("d") && !rq.equals("d10")) return 0;
        if (jabiQ && rp.startsWith("d") && !rp.equals("d10")) return 1;
        return ip < iq ? 0 : ip > iq ? 1 : 2;
    }

    @Test
    void everyPairOfHandsAgreesWithTheReferee() {
        List<Card[]> hands = new ArrayList<>();
        List<Card> d = Card.deck();
        for (int i = 0; i < d.size(); i++) for (int j = i + 1; j < d.size(); j++) hands.add(new Card[]{d.get(i), d.get(j)});
        assertEquals(190, hands.size());
        int pairs = 0, replays = 0, splits = 0, catches = 0;
        for (Card[] p : hands) {
            for (Card[] q : hands) {
                if (p[0].equals(q[0]) || p[0].equals(q[1]) || p[1].equals(q[0]) || p[1].equals(q[1])) continue;   // the same card twice
                for (boolean allowed : new boolean[]{true, false}) {
                    Sutda.Outcome o = Sutda.showdown(List.of(Sutda.of(p[0], p[1]), Sutda.of(q[0], q[1])), allowed);
                    int want = judge(p, q, allowed);
                    String what = p[0] + "+" + p[1] + " vs " + q[0] + "+" + q[1] + " (replay allowed " + allowed + ")";
                    switch (want) {
                        case 3 -> assertTrue(o.replay(), what);
                        case 2 -> assertEquals(List.of(0, 1), o.winners(), what);
                        default -> assertEquals(List.of(want), o.winners(), what);
                    }
                    if (want != 3) assertFalse(o.replay(), what);
                    if (allowed) {
                        pairs++;
                        if (want == 3) replays++;
                        if (want == 2) splits++;
                    }
                    String rp = referee(p[0], p[1]), rq = referee(q[0], q[1]);
                    if (allowed && want == 0 && RANKING.indexOf(rp) > RANKING.indexOf(rq)) catches++;
                }
            }
        }
        assertEquals(190 * 153, pairs);   // every ordered pair of hands without a shared card
        assertTrue(replays > 0 && splits > 0 && catches > 0, replays + " " + splits + " " + catches);
    }

    // ------------------------------------------------------------------------------------------ the special rules

    @Test
    void ttaengjabiCatchesOneToNineTtaengOnly() {
        Sutda.Hand jabi = h(g(3), y(7));
        for (int m = 1; m <= 9; m++) {
            if (m == 3 || m == 7) continue;   // its own cards
            Sutda.Hand ttaeng = h(Card.of(m, true), Card.of(m, false));
            assertEquals(List.of(0), Sutda.showdown(List.of(jabi, ttaeng), true).winners(), "땡잡이 vs " + m + "땡");
        }
        assertEquals(List.of(1), Sutda.showdown(List.of(jabi, h(y(10), t(10))), true).winners(), "장땡 is not caught");
        assertEquals(List.of(1), Sutda.showdown(List.of(jabi, h(g(1), g(8))), true).winners(), "광땡 is not caught");
        assertEquals(List.of(1), Sutda.showdown(List.of(jabi, h(t(1), t(5))), true).winners(), "against 6끗 it is 망통");
        assertEquals(List.of(1), Sutda.showdown(List.of(jabi, h(g(1), y(2))), true).winners(), "against 알리 it is 망통");
        assertEquals(List.of(0, 1), Sutda.showdown(List.of(jabi, h(t(2), y(8))), true).winners(), "망통 against 망통: split");
    }

    @Test
    void amhaengCatchesEighteenAndThirteenOnly() {
        Sutda.Hand am = h(y(4), y(7));
        assertEquals(List.of(0), Sutda.showdown(List.of(am, h(g(1), g(8))), true).winners());
        assertEquals(List.of(0), Sutda.showdown(List.of(am, h(g(1), g(3))), true).winners());
        assertEquals(List.of(1), Sutda.showdown(List.of(am, h(g(3), g(8))), true).winners(), "38광땡 is not caught");
        assertEquals(List.of(1), Sutda.showdown(List.of(am, h(y(9), t(9))), true).winners(), "against 9땡 it is 1끗");
        assertEquals(List.of(0), Sutda.showdown(List.of(am, h(t(2), y(8))), true).winners(), "1끗 beats 망통");
        assertEquals(List.of(0, 1), Sutda.showdown(List.of(am, h(t(5), y(6))), true).winners(), "1끗 against 1끗: split");
    }

    @Test
    void gusaReplays() {
        Sutda.Hand gusa = h(t(4), y(9)), meong = h(y(4), y(9));
        assertTrue(Sutda.showdown(List.of(gusa, h(g(1), y(2))), true).replay(), "알리: replay");
        assertTrue(Sutda.showdown(List.of(gusa, h(t(2), t(7))), true).replay(), "갑오: replay");
        assertFalse(Sutda.showdown(List.of(gusa, h(g(1), t(1))), true).replay(), "1땡 stands");
        assertEquals(List.of(1), Sutda.showdown(List.of(gusa, h(g(1), t(1))), true).winners());
        assertTrue(Sutda.showdown(List.of(meong, h(t(9), y(2))), true).replay());
        assertTrue(Sutda.showdown(List.of(meong, h(g(8), y(8))), true).replay(), "멍텅구리: 8땡 replays");
        assertFalse(Sutda.showdown(List.of(meong, h(y(10), t(10))), true).replay(), "장땡 stands");
        assertFalse(Sutda.showdown(List.of(meong, h(g(1), g(3))), true).replay(), "광땡 stands");
        // no more replays allowed: 구사 is 3끗
        Sutda.Outcome o = Sutda.showdown(List.of(gusa, h(t(1), t(5))), false);
        assertFalse(o.replay());
        assertEquals(List.of(1), o.winners(), "3끗 loses to 6끗");
        assertEquals(List.of(0), Sutda.showdown(List.of(gusa, h(t(2), y(8))), false).winners(), "3끗 beats 망통");
        // three players: one strong hand among the others stops the replay
        assertFalse(Sutda.showdown(List.of(gusa, h(t(1), t(5)), h(y(2), t(2))), true).replay());
        assertTrue(Sutda.showdown(List.of(h(t(1), t(5)), gusa, h(g(1), y(2))), true).replay());
    }

    @Test
    void manyPlayers() {
        // 땡잡이 catches the 9땡 on top: the 5끗 below it does not matter
        assertEquals(List.of(1), Sutda.showdown(List.of(h(y(9), t(9)), h(g(3), y(7)), h(t(2), t(3))), true).winners());
        // a 광땡 on the table: 땡잡이 catches nothing
        assertEquals(List.of(2), Sutda.showdown(List.of(h(y(9), t(9)), h(g(3), y(7)), h(g(1), g(8))), true).winners());
        // 암행어사 catches the 18광땡 even with a 9땡 there too
        assertEquals(List.of(0), Sutda.showdown(List.of(h(y(4), y(7)), h(y(9), t(9)), h(g(1), g(8))), true).winners());
        // ties split among the best only
        assertEquals(List.of(0, 2), Sutda.showdown(List.of(h(t(2), t(5)), h(y(6), t(9)), h(y(2), y(5))), true).winners());
        assertEquals(List.of(0), Sutda.showdown(List.of(h(t(2), t(5))), true).winners());
    }
}
