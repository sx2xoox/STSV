package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세븐 포커 at a table (README "도박장" 세븐 포커): three cards face down, one opened by each (or the timer: the lowest), five
 * betting rounds each opened by the best open cards, the 4th ~ 6th open and the 7th hidden, a pause while each street
 * is dealt, the showdown of the best five of seven, who sees which card, and whole random hands.
 */
class SevenPokerTableTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), D = UUID.randomUUID();

    private static Table table(long seed) {
        HwatuSpec s = HwatuTest.spec();
        return new Table(1, Table.Game.POKER, s.rooms().get(1), s, new Random(seed));
    }

    /** Seats them and lets the start timer run out: the hand is dealt. */
    private static long start(Table t, HwatuTest.Log log, List<UUID> who) {
        long now = 0;
        for (UUID u : who) t.sit(u, now);
        t.tick(now, HwatuTest.ALL, log);
        now = t.deadline();
        t.tick(now, HwatuTest.ALL, log);
        assertEquals(Table.Phase.PLAYING, t.phase());
        return now;
    }

    /** Lets the current deadline pass. */
    private static long expire(Table t, HwatuTest.Log log, long now) {
        now = Math.max(now, t.deadline());
        t.tick(now, HwatuTest.ALL, log);
        return now;
    }

    /** The player whose open cards are best among those still in. */
    private static UUID bestShowing(Table t) {
        UUID best = null;
        for (UUID u : t.players()) {
            if (!t.inHand(u)) continue;
            Poker.Hand h = t.showing(u);
            if (best == null || h.beats(t.showing(best))) best = u;
        }
        return best;
    }

    @Test
    void theSettings() {
        HwatuSpec s = HwatuTest.spec();
        assertEquals("seven_poker", Table.Game.POKER.key());
        assertEquals(6, s.game(Table.Game.POKER).max());
        assertEquals(3, s.game(Table.Game.POKER).sitAntes(), "sit like 섯다: ante × 3");
        assertEquals(30, s.need(Table.Game.POKER, s.rooms().get(1)));
        assertEquals(10, s.openSeconds());
        assertTrue(Table.Game.POKER.bets());
        assertEquals(1000, table(1).handLimit(), "일반방: 한도 1,000칩 like 섯다");
    }

    @Test
    void streetByStreetFromTheDealToTheShowdown() {
        Table t = table(11);
        HwatuTest.Log log = new HwatuTest.Log();
        long now = start(t, log, List.of(A, B, C));
        // three cards each, face down, while they fly in (no buttons, nobody's turn)
        assertEquals(Table.Step.DEAL, t.step());
        assertNull(t.turn());
        for (UUID u : t.players()) {
            assertEquals(3, t.pcards(u).size());
            for (int k = 0; k < 3; k++) assertFalse(t.isOpen(u, k));
            assertEquals(10, t.put(u), "the ante");
        }
        assertEquals(9, t.players().stream().flatMap(u -> t.pcards(u).stream()).distinct().count(), "one deck: no card twice");
        assertEquals(List.of(), Controls.buttons(Controls.state(t, A, true, false)));
        now = expire(t, log, now);
        // everyone picks the card to open at once
        assertEquals(Table.Step.OPEN, t.step());
        assertNull(t.turn());
        assertEquals(List.of(Controls.Button.OPEN1, Controls.Button.OPEN2, Controls.Button.OPEN3), Controls.buttons(Controls.state(t, A, true, false)));
        assertNull(t.act(A, Table.Move.OPEN2, now, log));
        assertEquals("hwatu.poker.opened-already", t.act(A, Table.Move.OPEN1, now, log));
        assertEquals(1, t.openedIndex(A));
        assertTrue(t.isOpen(A, 1));
        assertEquals(List.of(), Controls.buttons(Controls.state(t, A, true, false)), "opened: nothing more to press");
        assertNull(t.act(B, Table.Move.OPEN1, now, log));
        assertEquals(Table.Step.OPEN, t.step(), "C has not picked yet");
        // who sees what: A's opened card everyone, the other two A alone
        assertTrue(TableArt.seesPokerCard(t, B, A, 1));
        assertFalse(TableArt.seesPokerCard(t, B, A, 0));
        assertFalse(TableArt.seesPokerCard(t, C, A, 2));
        assertTrue(TableArt.seesPokerCard(t, A, A, 0));
        // C's time runs out: their lowest card opens, the first betting round starts with the best open card
        List<PCard> c3 = t.pcards(C);
        now = expire(t, log, now);
        assertEquals(Poker.lowest(c3), t.openedIndex(C));
        assertEquals(Table.Step.BET, t.step());
        assertEquals(bestShowing(t), t.turn(), "the best open card bets first");
        assertEquals(Set.of(Betting.Action.DIE, Betting.Action.CHECK, Betting.Action.PING, Betting.Action.HALF), t.legal(t.turn()));
        int streets = 0;
        while (t.phase() == Table.Phase.PLAYING) {
            if (t.step() == Table.Step.DEAL) {
                int street = t.street();
                for (UUID u : t.players()) {
                    assertEquals(street, t.pcards(u).size());
                    // 4th ~ 6th open to everyone, the 7th hidden
                    if (street >= 4 && street <= 6) assertTrue(t.isOpen(u, street - 1));
                    if (street == 7) {
                        assertFalse(t.isOpen(u, 6));
                        assertFalse(TableArt.seesPokerCard(t, A.equals(u) ? B : A, u, 6));
                    }
                }
                assertNull(t.turn(), "nobody's turn while the cards fly in");
                now = expire(t, log, now);
                assertEquals(Table.Step.BET, t.step());
                assertEquals(bestShowing(t), t.turn(), "every round: the best open cards first (street " + street + ")");
                streets++;
                continue;
            }
            UUID u = t.turn();
            assertNotNull(u);
            assertNull(t.act(u, HwatuTest.pass(t, u), now, log));
        }
        assertEquals(4, streets, "the 4th, 5th, 6th and 7th card");
        assertEquals(1, log.results.size());
        Table.Result r = log.results.getFirst();
        assertFalse(r.uncontested());
        UUID best = null;
        for (UUID u : r.players()) if (best == null || t.pokerHand(u).beats(t.pokerHand(best))) best = u;
        assertEquals(List.of(best), r.winners(), "the best five of seven wins");
        for (UUID u : r.players()) {
            assertEquals(7, t.pcards(u).size());
            assertTrue(r.detail().get(u).startsWith(t.pokerHand(u).code() + "|"));
            assertTrue(TableArt.revealed(t, u), "shown down: every card to everyone");
            for (int k = 0; k < 7; k++) assertTrue(TableArt.seesPokerCard(t, D, u, k));
        }
        assertEquals(30, r.pot(), "three antes, checked round");
        assertEquals(1, r.burned(), "5% of 30, down");
    }

    @Test
    void everyoneElseFoldingEndsItWithoutShowing() {
        Table t = table(3);
        HwatuTest.Log log = new HwatuTest.Log();
        long now = start(t, log, List.of(A, B, C));
        now = expire(t, log, now);   // dealt
        now = expire(t, log, now);   // the timer opens for everyone
        assertEquals(Table.Step.BET, t.step());
        UUID first = t.turn();
        assertNull(t.act(first, Table.Move.PING, now, log));
        while (t.phase() == Table.Phase.PLAYING) assertNull(t.act(t.turn(), Table.Move.DIE, now, log));
        Table.Result r = log.results.getFirst();
        assertTrue(r.uncontested());
        assertEquals(List.of(first), r.winners());
        for (UUID u : r.players()) assertFalse(TableArt.revealed(t, u), "nobody's hidden cards are shown");
        assertEquals(3, t.pcards(first).size(), "it ended on the first round");
    }

    @Test
    void leavingWhileTheyPickFoldsAndTheRestGoOn() {
        Table t = table(5);
        HwatuTest.Log log = new HwatuTest.Log();
        long now = start(t, log, List.of(A, B, C));
        now = expire(t, log, now);
        assertNull(t.act(A, Table.Move.OPEN1, now, log));
        assertNull(t.act(B, Table.Move.OPEN1, now, log));
        t.leave(C, now, log);   // the last one to pick goes: the round starts without them
        assertEquals(Table.Step.BET, t.step());
        assertFalse(t.inHand(C));
        assertTrue(t.folded(C));
        t.leave(B, now, log);
        assertEquals(Table.Phase.RESULT, t.phase(), "one left: they take the antes");
        assertEquals(List.of(A), log.results.getFirst().winners());
    }

    @Test
    void allInOnTheAnteStillDealsEveryStreetWithPauses() {
        Table t = table(9);
        HwatuTest.Log log = new HwatuTest.Log();
        long now = 0;
        t.sit(A, now);
        t.sit(B, now);
        Table.Bank tiny = new Table.Bank() {
            public String refusal(UUID p, long need) {
                return null;
            }

            public long stack(UUID p) {
                return 10;   // the ante and nothing more
            }
        };
        t.tick(now, tiny, log);
        now = t.deadline();
        t.tick(now, tiny, log);
        now = expire(t, log, now);
        now = expire(t, log, now);   // opened by the timer; nobody can bet: straight to the next card
        int pauses = 0;
        while (t.phase() == Table.Phase.PLAYING) {
            assertEquals(Table.Step.DEAL, t.step(), "every street still pauses for its cards");
            now = expire(t, log, now);
            pauses++;
        }
        assertEquals(4, pauses);
        assertEquals(7, t.pcards(A).size());
        assertEquals(1, log.results.size());
    }

    /** Hands with random legal moves: they all end, every chip is paid or destroyed, the best hand wins each pot. */
    @Test
    void randomHandsAllEndAndTheBestHandWins() {
        Random r = new Random(42);
        for (int hand = 0; hand < 300; hand++) {
            Table t = table(1000 + hand);
            HwatuTest.Log log = new HwatuTest.Log();
            List<UUID> who = new ArrayList<>(List.of(A, B, C, D).subList(0, 2 + r.nextInt(3)));
            long now = start(t, log, who);
            int guard = 0;
            while (t.phase() == Table.Phase.PLAYING && guard++ < 500) {
                switch (t.step()) {
                    case DEAL -> now = expire(t, log, now);
                    case OPEN -> {
                        for (UUID u : t.players()) if (t.inHand(u) && t.openedIndex(u) < 0 && r.nextBoolean())
                            assertNull(t.act(u, List.of(Table.Move.OPEN1, Table.Move.OPEN2, Table.Move.OPEN3).get(r.nextInt(3)), now, log));
                        if (t.step() == Table.Step.OPEN) now = expire(t, log, now);
                    }
                    case BET -> {
                        UUID u = t.turn();
                        List<Betting.Action> legal = new ArrayList<>(t.legal(u));
                        Betting.Action a = legal.get(r.nextInt(legal.size()));
                        if (a == Betting.Action.DIE && r.nextInt(3) > 0) a = legal.contains(Betting.Action.CHECK) ? Betting.Action.CHECK : Betting.Action.CALL;
                        assertNull(t.act(u, Table.move(a), now, log));
                    }
                    default -> throw new AssertionError(t.step());
                }
            }
            assertEquals(1, log.results.size(), "hand " + hand + " ended");
            Table.Result res = log.results.getFirst();
            if (!res.uncontested() && res.pots().size() == 1) {
                UUID best = null;
                for (UUID u : res.players()) {
                    if ("die".equals(res.detail().get(u))) continue;
                    if (best == null || t.pokerHand(u).beats(t.pokerHand(best))) best = u;
                }
                assertEquals(List.of(best), res.winners());
            }
            for (UUID u : res.players()) assertTrue(res.put().get(u) <= t.handLimit());
        }
    }
}
