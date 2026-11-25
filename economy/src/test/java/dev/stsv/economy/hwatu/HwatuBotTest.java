package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
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

/** 시험 판 (/관리자 도박장 시험, README "도박장"): the bots' play, picking cards by name and dealing forced cards. */
class HwatuBotTest {

    private static Sutda.Hand hand(String a, String b) {
        return Sutda.of(Card.parse(a), Card.parse(b));
    }

    private static final Set<Betting.Action> OPEN = EnumSet.of(Betting.Action.DIE, Betting.Action.CHECK, Betting.Action.PING, Betting.Action.HALF),
            FACING = EnumSet.of(Betting.Action.DIE, Betting.Action.CALL, Betting.Action.DDADANG, Betting.Action.HALF),
            CALL_ONLY = EnumSet.of(Betting.Action.DIE, Betting.Action.CALL);

    @Test
    void strongHandsRaiseWeakOnesFoldToARaise() {
        Sutda.Hand gwang = hand("3광", "8광"), ttaeng = hand("9열끗", "9띠"), ali = hand("1광", "2열끗"), mangtong = hand("2열끗", "8광");
        for (double roll = 0; roll < 1; roll += 0.05) {
            assertTrue(HwatuBot.sutda(gwang, FACING, 10, 40, 0, roll).raises(), "광땡 raises the first time");
            assertEquals(Betting.Action.CALL, HwatuBot.sutda(gwang, CALL_ONLY, 10, 40, 0, roll), "can't raise: call");
            assertTrue(HwatuBot.sutda(ttaeng, FACING, 10, 40, 0, roll) != Betting.Action.DIE);
            assertTrue(HwatuBot.sutda(ali, FACING, 10, 40, 0, roll) != Betting.Action.DIE);
        }
        assertEquals(Betting.Action.PING, HwatuBot.sutda(ttaeng, OPEN, 0, 40, 0, 0.1), "a 땡 usually opens");
        assertEquals(Betting.Action.DDADANG, HwatuBot.sutda(ttaeng, FACING, 10, 40, 1, 0.1), "… and doubles a bet");
        assertEquals(Betting.Action.CALL, HwatuBot.sutda(ttaeng, FACING, 10, 40, 1, 0.5), "less keen once raised");
        assertEquals(Betting.Action.HALF, HwatuBot.sutda(gwang, OPEN, 0, 40, 0, 0.9), "광땡 opens with 하프 (sometimes a sly 삥)");
        assertEquals(Betting.Action.CHECK, HwatuBot.sutda(gwang, OPEN, 0, 40, 3, 0.9), "after three raises the urge is an eighth");
        assertEquals(Betting.Action.DIE, HwatuBot.sutda(mangtong, FACING, 10, 40, 1, 0.1), "망통 folds to a bet, mostly");
        assertEquals(Betting.Action.CALL, HwatuBot.sutda(mangtong, FACING, 10, 40, 1, 0.9), "… not always");
        assertEquals(Betting.Action.DIE, HwatuBot.sutda(hand("2열끗", "5띠"), FACING, 500, 40, 1, 0.2), "7끗 asked for more than the pot: often 다이");
        assertEquals(Betting.Action.CALL, HwatuBot.sutda(hand("2열끗", "5띠"), FACING, 10, 40, 1, 0.5));
    }

    @Test
    void aBotOnlyEverPlaysALegalMoveAndNeverFoldsAFreeCheck() {
        List<Card> d = Card.deck();
        Random r = new Random(3);
        Set<Betting.Action> freeCall = EnumSet.of(Betting.Action.DIE, Betting.Action.CALL);
        for (int i = 0; i < d.size(); i++) for (int j = i + 1; j < d.size(); j++) {
            Sutda.Hand h = Sutda.of(d.get(i), d.get(j));
            for (int k = 0; k < 5; k++) {
                double roll = r.nextDouble();
                int raises = r.nextInt(6);
                for (Set<Betting.Action> legal : List.of(OPEN, FACING, CALL_ONLY)) {
                    long toCall = legal == OPEN ? 0 : 10 + r.nextInt(100);
                    Betting.Action a = HwatuBot.sutda(h, legal, toCall, 50, raises, roll);
                    assertTrue(legal.contains(a), h.code() + " " + a + " in " + legal);
                }
                assertTrue(HwatuBot.sutda(h, OPEN, 0, 50, raises, roll) != Betting.Action.DIE, h.code());
                assertTrue(HwatuBot.sutda(h, freeCall, 0, 50, raises, roll) != Betting.Action.DIE, "a free 콜: " + h.code());
            }
            if (h.special() != Sutda.Special.NONE) {
                assertEquals(Betting.Action.CALL, HwatuBot.sutda(h, FACING, 10, 40, 0, 0), "special hands stay in: " + h.code());
                assertEquals(Betting.Action.CHECK, HwatuBot.sutda(h, OPEN, 0, 40, 0, 0), "special hands stay in: " + h.code());
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
        return sutda(HwatuTest.spec(), seats, seed, who);
    }

    private static Table sutda(HwatuSpec s, int seats, long seed, List<UUID> who) {
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
            // dealt in order: the first card on the first street, the second on the second
            assertEquals(List.of(Card.parse("3광")), List.of(t.cards(admin)));
            assertEquals(List.of(Card.parse("9열끗")), List.of(t.cards(bot)));
            assertNull(t.hand(admin), "one card: no 족보 yet");
            assertTrue(t.forced().isEmpty(), "used once");
            for (int k = 0; k < 4; k++) assertNull(t.act(t.turn(), Table.Move.CHECK, 10_000, log));
            assertEquals(2, t.street());
            assertEquals(List.of(Card.parse("3광"), Card.parse("7열끗")), List.of(t.cards(admin)));
            assertEquals(List.of(Card.parse("9열끗"), Card.parse("9띠")), List.of(t.cards(bot)));
            assertEquals("ttaengjabi", t.hand(admin).code());
            Set<Card> dealt = new HashSet<>();
            for (UUID u : t.players()) dealt.addAll(List.of(t.cards(u)));
            assertEquals(8, dealt.size(), "nobody else holds a forced card");
            // everyone checks: when the bot's 9땡 is the top hand, the admin's 땡잡이 catches it (a 구사 may replay instead)
            int top = 0;
            for (UUID u : t.players()) if (!u.equals(admin)) top = Math.max(top, t.hand(u).rank());
            for (int k = 0; k < 4; k++) assertNull(t.act(t.turn(), Table.Move.CHECK, 10_000, log));
            if (log.replays == 0 && top == Sutda.TTAENG + 9) {
                assertEquals(List.of(admin), log.results.getFirst().winners(), "땡잡이 catches 9땡, seed " + seed);
                return;
            }
        }
        throw new AssertionError("no seed showed 땡잡이 against 9땡");
    }

    @Test
    void onOneCardGwangAndTenAreStrongAndAFreeCheckIsNeverFolded() {
        Card g8 = Card.parse("8광"), g1 = Card.parse("1광"), ten = Card.parse("10띠"), two = Card.parse("2띠"), five = Card.parse("5띠");
        assertTrue(HwatuBot.strength(g8) > HwatuBot.strength(g1));
        assertTrue(HwatuBot.strength(g1) > HwatuBot.strength(ten));
        assertTrue(HwatuBot.strength(ten) > HwatuBot.strength(Card.parse("1띠")));
        assertTrue(HwatuBot.strength(Card.parse("9띠")) > HwatuBot.strength(five));
        assertTrue(HwatuBot.strength(five) > HwatuBot.strength(two));
        int gwangOpens = 0, weakFolds = 0;
        for (double roll = 0; roll < 1; roll += 0.01) {
            for (Card c : Card.deck()) {
                Betting.Action open = HwatuBot.sutdaFirst(c, OPEN, 0, 40, 0, roll);
                assertTrue(OPEN.contains(open));
                assertTrue(open != Betting.Action.DIE, "nothing to match: never 다이");
                assertTrue(FACING.contains(HwatuBot.sutdaFirst(c, FACING, 10, 40, 0, roll)));
                assertTrue(CALL_ONLY.contains(HwatuBot.sutdaFirst(c, CALL_ONLY, 10, 40, 0, roll)));
            }
            assertTrue(HwatuBot.sutdaFirst(g8, FACING, 100, 40, 0, roll) != Betting.Action.DIE, "a 광 never folds on the first card");
            assertTrue(HwatuBot.sutdaFirst(ten, FACING, 100, 40, 0, roll) != Betting.Action.DIE, "nor a 10");
            if (HwatuBot.sutdaFirst(g8, OPEN, 0, 40, 0, roll).raises()) gwangOpens++;
            if (HwatuBot.sutdaFirst(two, FACING, 100, 40, 0, roll) == Betting.Action.DIE) weakFolds++;
        }
        assertTrue(gwangOpens > 20 && gwangOpens < 80, "a 광 opens often, not always: " + gwangOpens);
        assertTrue(weakFolds > 50, "a weak card mostly folds to a big bet: " + weakFolds);
    }

    /** Bots playing whole hands through the real table (초보방: ante 1, 한도 100): every move legal, every hand ends, within the limit. */
    @Test
    void botsPlayWholeHands() {
        botHands(HwatuTest.spec());
        // a low 한도 (4 antes) so the bots run into it often: they still only play legal moves and never pass it
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                java.nio.file.Path.of(System.getProperty("stsv.content")).resolve("economy/hwatu.yml").toFile());
        y.set("hand-limit-antes", 4);
        List<String> problems = new ArrayList<>();
        HwatuSpec low = HwatuSpec.parse(y, problems);
        assertEquals(List.of(), problems);
        assertTrue(botHands(low) > 0, "some hands reach the 한도");
    }

    /** 300 bot hands; how many bets reached the 한도. */
    private static int botHands(HwatuSpec spec) {
        Random r = new Random(11);
        int limited = 0;
        for (long seed = 0; seed < 300; seed++) {
            List<UUID> who = new ArrayList<>();
            Table t = sutda(spec, 2 + (int) (seed % 5), seed, who);
            HwatuTest.Log log = new HwatuTest.Log();
            long now = 10_000;
            // short and deep stacks (some below a few antes: they go all-in), like a 시험 판 after some hands
            java.util.Map<UUID, Long> chips = new java.util.HashMap<>();
            for (UUID u : who) chips.put(u, r.nextInt(3) == 0 ? 10L + r.nextInt(40) : 100L + r.nextInt(5000));
            t.tick(now, HwatuTest.stacks(chips), log);
            int steps = 0;
            while (t.phase() == Table.Phase.PLAYING) {
                UUID u = t.turn();
                assertNotNull(u);
                int seat = t.players().indexOf(u);
                Card[] c = t.cards(u);
                Betting.Action a = c.length < 2 ? HwatuBot.sutdaFirst(c[0], t.betting(), seat, r.nextDouble())
                        : HwatuBot.sutda(Sutda.of(c[0], c[1]), t.betting(), seat, r.nextDouble());
                assertNull(t.act(u, Table.move(a), now, log));
                assertTrue(++steps < 500);
                if (t.lastBet() != null && t.lastBet().limit()) limited++;
            }
            assertEquals(1, log.results.size());
            for (long v : log.results.getFirst().put().values()) assertTrue(v <= t.handLimit(), "a bot never puts past the 한도");
            for (UUID u : log.results.getFirst().players()) assertTrue(chips.get(u) + log.results.getFirst().net(u) >= 0, "nobody below 0");
        }
        return limited;
    }

    /** A bot facing a bet at the limit only calls or folds (a raise it wants falls back to 콜). */
    @Test
    void aBotAtTheLimitOnlyCallsOrFolds() {
        Set<Betting.Action> capped = EnumSet.of(Betting.Action.DIE, Betting.Action.CALL);
        Betting b = new Betting(1, 3, new long[]{5000, 5000}, null, new long[]{99, 99});
        b.act(0, Betting.Action.HALF);
        while (b.legal(b.turn()).contains(Betting.Action.DDADANG)) b.act(b.turn(), Betting.Action.DDADANG);
        assertTrue(b.capped());
        int seat = b.turn();
        assertEquals(capped, b.legal(seat));
        for (double roll = 0; roll < 1; roll += 0.05) {
            assertTrue(capped.contains(HwatuBot.sutda(hand("3광", "8광"), b, seat, roll)), "38광땡 wants to raise: 콜");
            assertTrue(capped.contains(HwatuBot.sutdaFirst(Card.parse("3광"), b, seat, roll)));
        }
    }
}
