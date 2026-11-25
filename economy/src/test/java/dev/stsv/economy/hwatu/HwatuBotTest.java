package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 시험 판 (/관리자 화투방 시험, README "화투방"): the bots' play, picking cards by name and dealing forced cards. */
class HwatuBotTest {

    private static Sutda.Hand hand(String a, String b) {
        return Sutda.of(Card.parse(a), Card.parse(b));
    }

    @Test
    void strongHandsRaiseWeakOnesFoldToARaise() {
        Sutda.Hand gwang = hand("3광", "8광"), ttaeng = hand("9열끗", "9띠"), ali = hand("1광", "2열끗"), mangtong = hand("2열끗", "8광");
        for (double roll = 0; roll < 1; roll += 0.05) {
            assertEquals(Betting.Action.RAISE, HwatuBot.sutda(gwang, 10, true, roll));
            assertEquals(Betting.Action.CALL, HwatuBot.sutda(gwang, 10, false, roll), "no raise left: call");
            assertTrue(HwatuBot.sutda(ttaeng, 10, true, roll) != Betting.Action.DIE);
            assertTrue(HwatuBot.sutda(ali, 10, true, roll) != Betting.Action.DIE);
        }
        assertEquals(Betting.Action.RAISE, HwatuBot.sutda(ttaeng, 0, true, 0.1), "a 땡 usually raises");
        assertEquals(Betting.Action.DIE, HwatuBot.sutda(mangtong, 10, true, 0.1), "망통 folds to a raise, mostly");
        assertEquals(Betting.Action.CALL, HwatuBot.sutda(mangtong, 10, true, 0.9), "… not always");
    }

    @Test
    void aBotNeverFoldsAFreeCheckNorRaisesPastTheLimit() {
        List<Card> d = Card.deck();
        Random r = new Random(3);
        for (int i = 0; i < d.size(); i++) for (int j = i + 1; j < d.size(); j++) {
            Sutda.Hand h = Sutda.of(d.get(i), d.get(j));
            for (int k = 0; k < 5; k++) {
                double roll = r.nextDouble();
                assertTrue(HwatuBot.sutda(h, 0, true, roll) != Betting.Action.DIE, h.code());
                assertTrue(HwatuBot.sutda(h, 10, false, roll) != Betting.Action.RAISE, h.code());
            }
            if (h.special() != Sutda.Special.NONE) {
                assertEquals(Betting.Action.CALL, HwatuBot.sutda(h, 10, true, 0), "special hands stay in: " + h.code());
            }
        }
    }

    @Test
    void botsThinkInsideTheTurnTimer() {
        for (int turn : List.of(5, 10, 20, 60)) {
            for (double roll = 0; roll <= 1; roll += 0.1) {
                long ms = HwatuBot.delay(turn, roll);
                assertTrue(ms >= 1000, "at least a second");
                assertTrue(ms <= 4000 && ms < turn * 1000L - 1000, turn + "s turn: " + ms);
            }
        }
        assertTrue(HwatuBot.callsOdd(0.2));
        assertFalse(HwatuBot.callsOdd(0.7));
    }

    @Test
    void cardsByName() {
        Set<String> labels = new HashSet<>();
        for (Card c : Card.deck()) {
            assertTrue(labels.add(c.label()), "unique: " + c.label());
            assertEquals(c, Card.parse(c.label()));
            assertEquals(c, Card.parse(c.item()));
            assertEquals(c, Card.parse(c.item().substring("economy:hwatu_".length())));
        }
        assertEquals(20, labels.size());
        assertEquals(new Card(3, Card.Kind.GWANG), Card.parse("3광"));
        assertEquals(new Card(8, Card.Kind.YEOL), Card.parse("8열끗"), "8월's second card is the 열끗 기러기");
        assertEquals(new Card(4, Card.Kind.TTI), Card.parse("4띠"));
        assertNull(Card.parse("2광"));
        assertNull(Card.parse("11광"));
        assertNull(Card.parse(null));
        assertEquals("ttaengjabi", hand("3광", "7열끗").code());
        assertEquals("amhaeng", hand("4열끗", "7열끗").code());
        assertEquals("meong-gusa", hand("4열끗", "9열끗").code());
        assertEquals("gusa", hand("4띠", "9열끗").code());
    }

    private static Table sutda(int seats, long seed, List<UUID> who) {
        HwatuSpec s = HwatuTest.spec();
        Table t = new Table(1, Table.Game.SUTDA, s.rooms().getFirst(), s, new Random(seed));
        for (int k = 0; k < seats; k++) {
            UUID u = UUID.randomUUID();
            who.add(u);
            t.sit(u, 0);
        }
        return t;
    }

    @Test
    void forcedCardsAreDealtInTheNextHandOnly() {
        for (long seed = 0; seed < 50; seed++) {
            List<UUID> who = new ArrayList<>();
            Table t = sutda(4, seed, who);
            UUID admin = who.getFirst(), bot = who.get(1);
            assertTrue(t.force(admin, Card.parse("3광"), Card.parse("7열끗")));
            assertTrue(t.force(bot, Card.parse("9열끗"), Card.parse("9띠")));
            assertFalse(t.force(who.get(2), Card.parse("9띠"), Card.parse("1광")), "a card someone else gets");
            assertFalse(t.force(who.get(2), Card.parse("1광"), Card.parse("1광")), "the same card twice");
            HwatuTest.Log log = new HwatuTest.Log();
            t.tick(10_000, HwatuTest.ALL, log);
            assertEquals(List.of(Card.parse("3광"), Card.parse("7열끗")), List.of(t.cards(admin)));
            assertEquals(List.of(Card.parse("9열끗"), Card.parse("9띠")), List.of(t.cards(bot)));
            Set<Card> dealt = new HashSet<>();
            for (UUID u : t.players()) dealt.addAll(List.of(t.cards(u)));
            assertEquals(8, dealt.size(), "nobody else holds a forced card");
            assertTrue(t.forced().isEmpty(), "used once");
            // everyone calls: when the bot's 9땡 is the top hand, the admin's 땡잡이 catches it (a 구사 may replay instead)
            int top = 0;
            for (UUID u : t.players()) if (!u.equals(admin)) top = Math.max(top, Sutda.of(t.cards(u)[0], t.cards(u)[1]).rank());
            for (int k = 0; k < 4; k++) t.act(t.turn(), Table.Move.CALL, 10_000, log);
            if (log.replays == 0 && top == Sutda.TTAENG + 9) {
                assertEquals(List.of(admin), log.results.getFirst().winners(), "땡잡이 catches 9땡, seed " + seed);
                return;
            }
        }
        throw new AssertionError("no seed showed 땡잡이 against 9땡");
    }

    /** Bots playing whole hands through the real table: every move legal, every hand ends. */
    @Test
    void botsPlayWholeHands() {
        Random r = new Random(11);
        for (long seed = 0; seed < 300; seed++) {
            List<UUID> who = new ArrayList<>();
            Table t = sutda(2 + (int) (seed % 5), seed, who);
            HwatuTest.Log log = new HwatuTest.Log();
            long now = 10_000;
            t.tick(now, HwatuTest.ALL, log);
            int steps = 0;
            while (t.phase() == Table.Phase.PLAYING) {
                UUID u = t.turn();
                assertNotNull(u);
                int seat = t.players().indexOf(u);
                Card[] c = t.cards(u);
                Betting.Action a = HwatuBot.sutda(Sutda.of(c[0], c[1]), t.betting().toCall(seat), t.betting().canRaise(), r.nextDouble());
                Table.Move m = switch (a) {
                    case DIE -> Table.Move.DIE;
                    case CALL -> Table.Move.CALL;
                    case RAISE -> Table.Move.RAISE;
                };
                assertNull(t.act(u, m, now, log));
                assertTrue(++steps < 200);
            }
            assertEquals(1, log.results.size());
        }
    }
}
