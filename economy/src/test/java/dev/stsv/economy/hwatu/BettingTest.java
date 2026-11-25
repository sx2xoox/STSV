package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 섯다 betting (README "도박장 (화투방)"): 삥 · 체크 · 콜 · 따당 · 하프 · 다이 for each state, any number of raises up to the
 * hand limit (한도), all-in, and
 * the main pot and side pots ({@link Pots}) with ties and the 자릿세.
 */
class BettingTest {

    private static final Betting.Action DIE = Betting.Action.DIE, CHECK = Betting.Action.CHECK, PING = Betting.Action.PING,
            CALL = Betting.Action.CALL, DDADANG = Betting.Action.DDADANG, HALF = Betting.Action.HALF;

    private static Set<Betting.Action> set(Betting.Action... a) {
        Set<Betting.Action> s = EnumSet.noneOf(Betting.Action.class);
        s.addAll(List.of(a));
        return s;
    }

    // ------------------------------------------------------------------------------------------ legal actions

    @Test
    void whatEachStateAllows() {
        // three seats of 1,000 chips, a 10 ante: 30 in the pot before the round
        Betting b = new Betting(10, 30, new long[]{1000, 1000, 1000}, null);
        assertEquals(0, b.turn());
        assertEquals(set(DIE, CHECK, PING, HALF), b.legal(0), "nobody bet yet: 체크 · 삥 · 하프 (no 콜, no 따당)");
        assertEquals(set(), b.legal(1), "not my turn: nothing");
        assertEquals(10, b.amount(0, PING), "삥 = the ante");
        assertEquals(15, b.amount(0, HALF), "하프 with nothing to call = half the pot (30 / 2)");
        assertEquals(0, b.act(0, CHECK));
        assertEquals(set(DIE, CHECK, PING, HALF), b.legal(1), "still nobody bet: 체크 again");
        assertEquals(10, b.act(1, PING));
        assertEquals(10, b.bet());
        assertEquals(set(DIE, CALL, DDADANG, HALF), b.legal(2), "after a bet: 콜 · 따당 · 하프, no 체크 or 삥");
        assertEquals(10, b.amount(2, CALL));
        assertEquals(20, b.amount(2, DDADANG), "따당 = twice the bet");
        // 하프: call 10, then half of the pot after it (30 + 10 + 10 = 50 → 25)
        assertEquals(10 + 25, b.amount(2, HALF));
        assertEquals(20, b.act(2, DDADANG));
        assertEquals(20, b.bet());
        assertEquals(set(DIE, CALL, DDADANG, HALF), b.legal(0), "the one who checked must answer");
        assertEquals(20, b.amount(0, CALL));
        assertThrows(IllegalStateException.class, () -> b.act(0, CHECK), "체크 is gone once someone bet");
        assertThrows(IllegalStateException.class, () -> b.act(0, PING), "삥 only opens");
        assertThrows(IllegalStateException.class, () -> b.act(1, CALL), "out of turn");
        assertEquals(20, b.act(0, CALL));
        assertEquals(10, b.act(1, CALL));
        assertTrue(b.done());
        assertEquals(30 + 60, b.pot());
        assertEquals(2, b.raises(), "삥 and 따당");
        assertEquals(List.of(0, 1, 2), b.in());
        assertThrows(IllegalStateException.class, () -> b.act(0, CALL), "the round is over");
    }

    @Test
    void halfIsAtLeastTheAnte() {
        Betting b = new Betting(100, 0, new long[]{5000, 5000}, null);
        assertEquals(100, b.amount(0, HALF), "half of an empty pot is less than the ante: the ante");
        Betting c = new Betting(10, 1000, new long[]{5000, 5000}, null);
        assertEquals(500, c.amount(0, HALF));
        c.act(0, HALF);
        assertEquals(500, c.bet());
        // call 500, then half of 1000 + 500 + 500 = 1000
        assertEquals(500 + 1000, c.amount(1, HALF));
        assertEquals(1000, c.amount(1, DDADANG));
    }

    @Test
    void everyoneChecksTheRoundEnds() {
        Betting b = new Betting(2, 10, 1000);
        assertEquals(CHECK, b.timeout(), "nothing to match: the timer checks");
        b.act(0, CHECK);
        assertFalse(b.done());
        b.act(1, CHECK);
        assertTrue(b.done());
        assertEquals(0, b.bet());
        assertEquals(2, b.active());
    }

    @Test
    void checkOnlyBeforeTheFirstBet() {
        Betting b = new Betting(4, 10, 1000);
        b.act(0, CHECK);
        b.act(1, CHECK);
        b.act(2, PING);
        // the round goes on round the table; nobody may check again in it
        for (int k = 0; k < 6 && !b.done(); k++) {
            int s = b.turn();
            assertFalse(b.legal(s).contains(CHECK), "seat " + s);
            assertFalse(b.legal(s).contains(PING), "seat " + s);
            b.act(s, CALL);
        }
        assertTrue(b.done());
        assertEquals(0, b.toCall(0));
    }

    @Test
    void foldsInAndOutOfTurn() {
        Betting c = new Betting(4, 10, 1000);
        c.fold(2);   // left the table out of turn
        c.act(0, PING);
        assertEquals(1, c.turn());
        assertEquals(DIE, c.timeout(), "seat 1 owes 10: the timer folds");
        c.act(1, DIE);
        assertEquals(3, c.turn(), "seat 2 is skipped");
        c.fold(3);   // left on their turn
        assertTrue(c.done());
        assertEquals(1, c.active());
        assertEquals(List.of(0), c.in());
        // folds of an earlier round carry into the next (a 구사 재경기)
        Betting next = new Betting(10, 100, new long[]{100, 100, 100}, new boolean[]{true, false, false});
        assertEquals(1, next.turn(), "a folded seat never acts");
        assertTrue(next.folded(0));
    }

    // ------------------------------------------------------------------------------------------ no cap, all-in

    @Test
    void aLongRaiseWarEndsWhenTheChipsDo() {
        // two players with deep stacks 따당 each other: no cap, the bet doubles until one cannot raise any more
        Betting b = new Betting(2, 10, 1_000_000);
        b.act(0, PING);
        int raises = 1, steps = 0;
        while (!b.done()) {
            int s = b.turn();
            if (b.legal(s).contains(DDADANG)) {
                b.act(s, DDADANG);
                raises++;
            } else b.act(s, CALL);
            assertTrue(++steps < 100, "a raise war is bounded by the chips");
        }
        assertTrue(raises > 10, "far past any cap: " + raises);
        assertEquals(raises, b.raises());
        assertTrue(b.allIn(0) || b.allIn(1), "it ended in an all-in");
        assertEquals(b.put(0), b.put(1), "both matched (the caller had the same stack)");
        assertEquals(1_000_000, b.put(0));
        // 하프 forever: the same
        Betting h = new Betting(3, 10, 50_000);
        steps = 0;
        h.act(0, HALF);
        while (!h.done()) {
            int s = h.turn();
            h.act(s, h.legal(s).contains(HALF) ? HALF : CALL);
            assertTrue(++steps < 200);
        }
        for (int s = 0; s < 3; s++) assertEquals(50_000, h.put(s), "everyone all-in at the end");
    }

    @Test
    void shortOfChipsGoesAllIn() {
        // seat 1 holds only 15: it calls a 40 bet with all it has and stops acting
        Betting b = new Betting(10, 30, new long[]{1000, 15, 1000}, null);
        b.act(0, PING);
        assertEquals(15, b.amount(1, HALF), "a raise past one's chips is all-in");
        assertTrue(b.allInBy(1, HALF));
        assertEquals(10, b.amount(1, CALL));
        assertFalse(b.allInBy(1, CALL));
        b.act(1, CALL);
        b.act(2, DDADANG);   // 20
        assertEquals(0, b.turn(), "seat 0 answers");
        b.act(0, DDADANG);   // 40
        assertEquals(1, b.turn());
        assertEquals(5, b.toCall(1), "owes 30, holds 5: calling is all-in for 5");
        assertEquals(set(DIE, CALL), b.legal(1), "nothing past a call: no 따당 · 하프");
        assertEquals(5, b.act(1, CALL));
        assertTrue(b.allIn(1));
        assertEquals(2, b.turn(), "the all-in seat is skipped from now on");
        b.act(2, CALL);
        assertTrue(b.done(), "everyone still in matched or is all-in");
        assertEquals(15, b.put(1));
        assertEquals(40, b.put(0));
    }

    @Test
    void noRaiseWhenNobodyCanAnswer() {
        Betting b = new Betting(10, 30, new long[]{1000, 20, 1000}, null);
        b.act(0, HALF);   // 15
        b.act(1, HALF);   // all-in 20 (a short raise lifts the bet)
        assertTrue(b.allIn(1));
        assertEquals(20, b.bet());
        b.act(2, DIE);
        assertEquals(0, b.turn(), "seat 0 owes 5");
        assertEquals(set(DIE, CALL), b.legal(0), "the only other player is all-in: no raise");
        b.act(0, CALL);
        assertTrue(b.done());
        // with everyone else all-in from the start and nothing owed, there is nothing to bet
        Betting c = new Betting(10, 30, new long[]{1000, 0, 0}, null);
        assertTrue(c.done());
        assertEquals(-1, c.turn());
    }

    @Test
    void everyoneAllIn() {
        Betting b = new Betting(10, 40, new long[]{100, 250, 60, 400}, null);
        b.act(0, HALF);           // 10 + 40/2 → 20
        int steps = 0;
        while (!b.done()) {       // everyone shoves what they can
            int s = b.turn();
            Set<Betting.Action> l = b.legal(s);
            b.act(s, l.contains(HALF) ? HALF : CALL);
            assertTrue(++steps < 100);
        }
        // the round ended with all-ins: the biggest stack put in no more than the second biggest could match
        assertTrue(b.allIn(0) && b.allIn(2), "the short stacks are all-in");
        assertEquals(100, b.put(0));
        assertEquals(60, b.put(2));
        assertTrue(b.put(1) == 250);
        assertTrue(b.put(3) >= 250, "the big stack covered the next one");
    }

    @Test
    void aBettingRoundAlwaysEnds() {
        Random r = new Random(7);
        for (int k = 0; k < 4000; k++) {
            int n = 2 + r.nextInt(5);
            long[] stacks = new long[n];
            for (int i = 0; i < n; i++) stacks[i] = r.nextInt(4) == 0 ? r.nextInt(40) : 10 + r.nextInt(5000);
            Betting b = new Betting(10, 10L * n, stacks, null);
            int steps = 0;
            while (!b.done()) {
                int seat = b.turn();
                List<Betting.Action> legal = new ArrayList<>(b.legal(seat));
                assertFalse(legal.isEmpty());
                Betting.Action a = legal.get(r.nextInt(legal.size()));
                long before = b.left(seat);
                long added = b.act(seat, a);
                assertEquals(before - added, b.left(seat));
                assertTrue(b.left(seat) >= 0, "never below 0");
                assertTrue(++steps < 500, "the round ends");
            }
            for (int s : b.in()) {
                assertTrue(b.put(s) == b.bet() || b.allIn(s), "everyone still in matched the bet or is all-in");
                assertTrue(b.put(s) <= stacks[s]);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the hand limit (한도)

    @Test
    void aRaisePastTheLimitIsCutToIt() {
        // ante 10, 한도 100 a hand: after the antes each may put in 90 more
        Betting b = new Betting(10, 30, new long[]{1000, 1000, 1000}, null, new long[]{90, 90, 90});
        b.act(0, PING);                                   // 10
        b.act(1, DDADANG);                                // 20
        assertEquals(60, b.act(2, HALF), "call 20 + half of (30 + 10 + 20 + 20)");
        assertTrue(b.legal(0).contains(DDADANG), "still under the limit: a raise is offered");
        assertEquals(80, b.amount(0, DDADANG), "따당 would be +110: cut to the 80 the limit leaves");
        assertTrue(b.limitBy(0, DDADANG));
        assertTrue(b.limitBy(0, HALF));
        assertFalse(b.limitBy(0, CALL), "a call is never labelled 한도");
        assertFalse(b.allInBy(0, DDADANG), "not all-in: chips are left");
        assertFalse(b.capped());
        assertEquals(80, b.act(0, DDADANG));
        assertEquals(90, b.bet());
        assertTrue(b.atLimit(0));
        assertFalse(b.allIn(0));
        assertTrue(b.capped(), "the bet is the limit");
        assertEquals(set(DIE, CALL), b.legal(1), "at the limit nobody can raise: 콜 · 다이 only");
        assertEquals(70, b.toCall(1));
        b.act(1, CALL);
        assertEquals(set(DIE, CALL), b.legal(2));
        b.act(2, CALL);
        assertTrue(b.done(), "everyone at the limit");
        for (int s = 0; s < 3; s++) assertEquals(90, b.put(s));
        assertEquals(1000 - 90, b.left(0), "the chips past the limit stay with the player");
    }

    @Test
    void noRaiseOnceTheLimitIsReachedOrLeftIsOnlyACall() {
        // one seat already at the limit when the round starts (an earlier street took it there): it never acts
        Betting b = new Betting(10, 200, new long[]{900, 900}, null, new long[]{0, 0});
        assertTrue(b.done(), "both at the limit: nothing to bet");
        assertTrue(b.atLimit(0) && b.atLimit(1));
        // the limit leaves exactly a call: no raise
        Betting c = new Betting(10, 30, new long[]{1000, 1000}, null, new long[]{40, 40});
        c.act(0, HALF);   // 15
        c.act(1, HALF);   // call 15 + half of (30 + 15 + 15) = 45 → cut to 40
        assertTrue(c.atLimit(1));
        assertEquals(set(DIE, CALL), c.legal(0), "the 25 left is a call: no 따당 · 하프");
        assertEquals(25, c.toCall(0));
        c.act(0, CALL);
        assertTrue(c.done());
        // 삥 cut too: with 5 left under the limit, 삥 (10) puts in 5
        Betting d = new Betting(10, 30, new long[]{1000, 1000}, null, new long[]{5, 5});
        assertEquals(5, d.amount(0, PING));
        assertTrue(d.limitBy(0, PING));
        // no limit (the old constructor): nothing is ever cut
        Betting e = new Betting(2, 10, 1_000_000);
        assertFalse(e.limitBy(0, HALF));
        assertFalse(e.atLimit(0));
    }

    @Test
    void theLimitCarriesAcrossStreets() {
        // street 1 takes each to 60 of a 100 limit (ante 10 in the pot already); street 2 has 40 left
        Betting one = new Betting(10, 20, new long[]{500, 500}, null, new long[]{90, 90});
        one.act(0, PING);
        one.act(1, DDADANG);   // 20
        one.act(0, DDADANG);   // 40
        one.act(1, DDADANG);   // 80 → cut to 90? no: 80 - 20 = 60 more, under the limit
        assertEquals(80, one.put(1));
        one.act(0, CALL);
        assertTrue(one.done());
        long put = 10 + one.put(0);   // 90
        Betting two = new Betting(10, one.pot(), new long[]{500 - one.put(0), 500 - one.put(1)}, null,
                new long[]{100 - put, 100 - put});
        assertEquals(set(DIE, CHECK, PING, HALF), two.legal(0), "10 left: still a 삥");
        assertEquals(10, two.amount(0, HALF), "하프 would be half the pot: cut to the 10 the limit leaves");
        assertTrue(two.limitBy(0, HALF));
        two.act(0, HALF);
        assertEquals(set(DIE, CALL), two.legal(1));
        two.act(1, CALL);
        assertTrue(two.done());
        assertEquals(100, put + two.put(0), "the whole hand: exactly the limit");
    }

    @Test
    void theLimitWithAnAllInAndSidePots() {
        // seat 1 holds 40 (short of the limit), seats 0 and 2 shove up to the 90 the limit leaves
        Betting b = new Betting(10, 30, new long[]{1000, 40, 1000}, null, new long[]{90, 90, 90});
        int steps = 0;
        while (!b.done()) {
            int s = b.turn();
            Set<Betting.Action> l = b.legal(s);
            b.act(s, l.contains(HALF) ? HALF : l.contains(CALL) ? CALL : CHECK);
            assertTrue(++steps < 50);
        }
        assertTrue(b.allIn(1), "the short stack is all-in, as before");
        assertFalse(b.atLimit(1));
        assertTrue(b.atLimit(0) && b.atLimit(2));
        assertEquals(90, b.put(0));
        assertEquals(40, b.put(1));
        assertEquals(90, b.put(2));
        // the hand (ante 10 each + this round): 100 · 50 · 100 → main 150 for all three, side 100 for seats 0 and 2
        long[] hand = {100, 50, 100};
        assertEquals(List.of(0L, 0L, 0L), toList(Pots.uncalled(hand)));
        List<Pots.Pot> pots = Pots.of(hand, new boolean[3]);
        assertEquals(List.of(new Pots.Pot(150, List.of(0, 1, 2)), new Pots.Pot(100, List.of(0, 2))), pots);
    }

    @Test
    void aLimitedRoundAlwaysEndsWithinTheLimit() {
        Random r = new Random(11);
        for (int k = 0; k < 4000; k++) {
            int n = 2 + r.nextInt(5);
            long[] stacks = new long[n], most = new long[n];
            long limit = 10 + r.nextInt(2000);
            for (int i = 0; i < n; i++) {
                stacks[i] = r.nextInt(4) == 0 ? r.nextInt(40) : 10 + r.nextInt(5000);
                most[i] = limit;
            }
            Betting b = new Betting(10, 10L * n, stacks, null, most);
            int steps = 0;
            while (!b.done()) {
                int seat = b.turn();
                List<Betting.Action> legal = new ArrayList<>(b.legal(seat));
                assertFalse(legal.isEmpty());
                Betting.Action a = legal.get(r.nextInt(legal.size()));
                b.act(seat, a);
                assertTrue(b.put(seat) <= limit, "never past the limit");
                assertTrue(b.left(seat) >= 0);
                assertTrue(++steps < 500, "the round ends");
            }
            for (int s : b.in()) {
                assertTrue(b.put(s) == b.bet() || b.allIn(s), "everyone still in matched the bet or is all-in");
                assertTrue(b.put(s) <= Math.min(stacks[s], limit));
            }
        }
    }

    // ------------------------------------------------------------------------------------------ pots

    @Test
    void theUncalledPartGoesBack() {
        assertEquals(List.of(0L, 0L, 70L), toList(Pots.uncalled(new long[]{30, 30, 100})));
        assertEquals(List.of(0L, 0L, 0L), toList(Pots.uncalled(new long[]{100, 30, 100})), "two share the top: nothing back");
        assertEquals(List.of(0L, 0L), toList(Pots.uncalled(new long[]{50, 50})));
        assertEquals(List.of(0L, 0L, 0L, 950L), toList(Pots.uncalled(new long[]{10, 50, 0, 1000})));
    }

    private static List<Long> toList(long[] a) {
        List<Long> out = new ArrayList<>();
        for (long v : a) out.add(v);
        return out;
    }

    @Test
    void sidePotsWithThreeAllIns() {
        // A all-in 50, B all-in 120, C all-in 200, D covers 200, E folded after putting 30
        long[] put = {50, 120, 200, 200, 30};
        boolean[] folded = {false, false, false, false, true};
        List<Pots.Pot> pots = Pots.of(put, folded);
        assertEquals(3, pots.size());
        assertEquals(new Pots.Pot(50 * 4 + 30, List.of(0, 1, 2, 3)), pots.get(0), "main: 50 from each, E's 30");
        assertEquals(new Pots.Pot(70 * 3, List.of(1, 2, 3)), pots.get(1), "side 1: B, C, D 70 more each");
        assertEquals(new Pots.Pot(80 * 2, List.of(2, 3)), pots.get(2), "side 2: C and D");
        long all = 0;
        for (Pots.Pot p : pots) all += p.amount();
        assertEquals(600, all, "every chip in exactly one pot");
    }

    @Test
    void aFoldedPlayersChipsAboveEveryoneLeftGoToTheLastPot() {
        // A all-in 50 is the only one left; B and C put in 200 and both left the table
        List<Pots.Pot> pots = Pots.of(new long[]{50, 200, 200}, new boolean[]{false, true, true});
        assertEquals(List.of(new Pots.Pot(450, List.of(0))), pots);
        // only everyone left in (nobody all-in): one pot
        assertEquals(List.of(new Pots.Pot(70, List.of(0, 1))), Pots.of(new long[]{30, 30, 10}, new boolean[]{false, false, true}));
    }

    @Test
    void eachPotPaysItsOwnRakeAndSplits() {
        // the main pot of 230 split by two: rake 11 (5 %, down), 219 / 2 = 109 each, 1 odd chip burned
        HwatuRules.Split main = HwatuRules.split(230, 5, 2);
        assertEquals(109, main.share());
        assertEquals(11 + 1, main.burned());
        // a side pot of 210 split three ways: rake 10, 200 / 3 = 66, 2 odd chips burned
        HwatuRules.Split side = HwatuRules.split(210, 5, 3);
        assertEquals(66, side.share());
        assertEquals(10 + 2, side.burned());
        // a small pot pays no 자릿세 (5 % of 19 rounded down)
        assertEquals(new HwatuRules.Split(19, 0), HwatuRules.split(19, 5, 1));
    }
}
