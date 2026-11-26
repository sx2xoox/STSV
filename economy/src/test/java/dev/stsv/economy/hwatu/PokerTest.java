package dev.stsv.economy.hwatu;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 세븐 포커 족보 (README "도박장" 세븐 포커): every category, kickers, the two named straights, best five of seven, suits. */
class PokerTest {

    /** "As Kh 10d 2c" → cards. */
    static List<PCard> cards(String s) {
        List<PCard> out = new ArrayList<>();
        for (String x : s.trim().split("\\s+")) {
            PCard c = PCard.parse(x);
            assertNotNull(c, x);
            out.add(c);
        }
        return out;
    }

    static Poker.Hand hand(String s) {
        return Poker.best(cards(s));
    }

    @Test
    void theCards() {
        assertEquals(52, PCard.deck().size());
        assertEquals(52, PCard.deck().stream().distinct().count());
        assertEquals("economy:pcard_as", new PCard(14, PCard.Suit.SPADE).item());
        assertEquals("economy:pcard_10h", new PCard(10, PCard.Suit.HEART).item());
        assertEquals("economy:pcard_jd", new PCard(11, PCard.Suit.DIAMOND).item());
        assertEquals("economy:pcard_2c", new PCard(2, PCard.Suit.CLUB).item());
        assertEquals("economy:pcard_back", PCard.BACK);
        for (PCard c : PCard.deck()) {
            assertEquals(c, PCard.parse(c.item()), "every item names its card");
            assertEquals(c, PCard.parse(c.label()), "and every label");
            assertTrue(c.item().matches("economy:pcard_(a|[2-9]|10|j|q|k)[shdc]"), c.item());
        }
        assertEquals("K♥", PCard.parse("kh").label());
        assertNull(PCard.parse("1s"));
        assertNull(PCard.parse("zz"));
        // the house order of an exact tie: ♠ > ♦ > ♥ > ♣
        assertTrue(PCard.Suit.SPADE.ordinal() > PCard.Suit.DIAMOND.ordinal());
        assertTrue(PCard.Suit.DIAMOND.ordinal() > PCard.Suit.HEART.ordinal());
        assertTrue(PCard.Suit.HEART.ordinal() > PCard.Suit.CLUB.ordinal());
        assertThrows(IllegalArgumentException.class, () -> new PCard(1, PCard.Suit.SPADE));
        assertThrows(IllegalArgumentException.class, () -> new PCard(15, PCard.Suit.SPADE));
    }

    // ------------------------------------------------------------------------------------------ categories

    @Test
    void everyCategoryAndItsName() {
        Map<String, String> ex = new java.util.LinkedHashMap<>();
        ex.put("royal", "as ks qs js 10s");
        ex.put("back-straight-flush", "ah 2h 3h 4h 5h");
        ex.put("straight-flush", "9d 8d 7d 6d 5d");
        ex.put("four-card", "qs qh qd qc 3s");
        ex.put("full-house", "ks kh kd 3c 3s");
        ex.put("flush", "as 9s 7s 4s 2s");
        ex.put("mountain", "ad kh qs jc 10d");
        ex.put("back-straight", "as 2d 3h 4c 5s");
        ex.put("straight", "9c 8d 7h 6s 5c");
        ex.put("triple", "9s 9h 9d kc 2s");
        ex.put("two-pair", "ks kh 7d 7c 2s");
        ex.put("one-pair", "as ah 9d 7c 2s");
        ex.put("top", "as jh 9d 7c 2s");
        assertEquals(Poker.CODES, List.copyOf(ex.keySet()), "the 족보 board's order");
        Poker.Hand above = null;
        for (Map.Entry<String, String> e : ex.entrySet()) {
            Poker.Hand h = hand(e.getValue());
            assertEquals(e.getKey(), h.code(), e.getValue());
            if (above != null) assertTrue(above.beats(h), above + " > " + h);
            above = h;
        }
        assertEquals("K·7", hand("ks kh 7d 7c 2s").ranksText(), "투페어 K·7");
        assertEquals("A", hand("as ah 9d 7c 2s").ranksText());
        assertEquals("K·3", hand("ks kh kd 3c 3s").ranksText());
        assertEquals("♠ A", hand("as 9s 7s 4s 2s").ranksText());
        assertEquals("9", hand("9c 8d 7h 6s 5c").ranksText());
        assertEquals("", hand("as ks qs js 10s").ranksText());
        assertEquals("Q", hand("qs qh qd qc 3s").ranksText());
    }

    @Test
    void kickersBreakTies() {
        assertTrue(hand("as ah kd 7c 2s").beats(hand("ad ac qd 7h 3s")), "pair of A: K kicker over Q");
        assertTrue(hand("as ah kd 7c 3s").beats(hand("ad ac kh 7h 2s")), "the last kicker counts");
        assertTrue(hand("ks kh 7d 7c 3s").beats(hand("kd kc 6d 6c as")), "two pair: the lower pair before the kicker");
        assertTrue(hand("ks kh 7d 7c 3s").beats(hand("qd qc jd jc as")), "two pair: the higher pair first");
        assertTrue(hand("ks kh 7d 7c 9s").beats(hand("kd kc 7h 7s 8s")), "two pair: same pairs, the kicker");
        assertTrue(hand("3s 3h 3d 2c 2s").beats(hand("2d 2h 2c as ad")), "full house: the triple decides");
        assertTrue(hand("as 9s 7s 4s 3s").beats(hand("ah 9h 7h 4h 2h")), "flush: down to the fifth card");
        assertTrue(hand("as jh 9d 7c 3s").beats(hand("ah jd 9c 7h 2s")), "top: down to the fifth card");
        assertTrue(hand("9s 9h 9d kc 3s").beats(hand("8s 8h 8d ac ks")));
        assertTrue(hand("qs qh qd qc 4s").beats(hand("js jh jd jc as")));
    }

    @Test
    void theNamedStraightsFollowTheKoreanHouse() {
        Poker.Hand mountain = hand("ad kh qs jc 10d"), back = hand("as 2d 3h 4c 5s"), king = hand("kd qh js 10c 9d"), six = hand("6d 5h 4s 3c 2d");
        assertEquals(Poker.MOUNTAIN, mountain.ranks()[0]);
        assertEquals(Poker.BACK_STRAIGHT, back.ranks()[0]);
        assertTrue(mountain.beats(back), "마운틴 > 백스트레이트");
        assertTrue(back.beats(king), "백스트레이트 > K 스트레이트 (한국 규칙)");
        assertTrue(king.beats(six));
        assertTrue(hand("as ks qs js 10s").beats(hand("ah 2h 3h 4h 5h")), "로티플 > 백스트레이트 플러시");
        assertTrue(hand("ah 2h 3h 4h 5h").beats(hand("kd qd jd 10d 9d")), "백스트레이트 플러시 > K 스티플");
        assertEquals("top", hand("as kd 3h 4c 5s").code(), "A K 3 4 5 is no straight");
        assertEquals("top", hand("qs kd ah 2c 3s").code(), "no wrap round (Q K A 2 3)");
        assertEquals(0, Poker.straight(new int[]{14, 13, 12, 11, 9}));
    }

    @Test
    void anExactTieGoesBySuit() {
        // the same ranks: the suit of the hand's top card (a pair: its better suit)
        assertTrue(hand("as ah 9d 7c 2s").beats(hand("ad ac 9h 7d 2h")), "A♠ pair over A♦");
        assertTrue(hand("ad ac 9h 7d 2h").beats(hand("ah ac 9s 7h 2c")), "A♦ pair over A♥");
        assertTrue(hand("9s 8d 7h 6s 5c").beats(hand("9d 8s 7c 6h 5d")), "straights: the top card's suit");
        assertTrue(hand("9d 8s 7c 6h 5d").beats(hand("9h 8c 7d 6d 5h")), "♦ > ♥");
        assertTrue(hand("9h 8c 7d 6d 5h").beats(hand("9c 8h 7s 6c 5s")), "♥ > ♣");
        assertTrue(hand("ks 9s 7s 4s 2s").beats(hand("kd 9d 7d 4d 2d")), "flushes of the same ranks: ♠ over ♦");
        assertTrue(hand("ks kh 7d 7c 2s").beats(hand("kd kc 7h 7s 2d")), "two pair: the higher pair's best suit");
        assertTrue(hand("as jh 9d 7c 2s").beats(hand("ad jc 9h 7d 2h")), "top: the top card's suit");
    }

    // ------------------------------------------------------------------------------------------ seven cards

    @Test
    void theBestFiveOfSeven() {
        assertEquals("flush", hand("as 9s 7s 4s 2s 3d 5h").code(), "a flush over the A-5 straight in the same seven");
        assertEquals("full-house", hand("ks kh kd 3c 3s 3h 2d").code(), "two triples: a full house");
        assertArrayEquals(new int[]{13, 3}, hand("ks kh kd 3c 3s 3h 2d").ranks());
        assertEquals("two-pair", hand("ks kh 7d 7c 2s 2h ad").code(), "three pairs: the best two and the best kicker");
        assertArrayEquals(new int[]{13, 7, 14}, hand("ks kh 7d 7c 2s 2h ad").ranks());
        assertEquals(10, hand("6d 7h 8s 9c 10d 4s 5h").ranks()[0], "six in a row: the highest five");
        assertEquals("straight-flush", hand("9d 8d 7d 6d 5d ad kd").code(), "a straight flush over the flush");
        assertEquals("four-card", hand("qs qh qd qc ks kh kd").code());
        assertArrayEquals(new int[]{12, 13}, hand("qs qh qd qc ks kh kd").ranks(), "four Q with a K kicker");
        assertEquals("royal", hand("as ks qs js 10s 9s 8s").code());
        assertEquals("back-straight", hand("as 2d 3h 4c 5s kd kh").code(), "a straight over a pair");
        assertEquals("mountain", hand("as kd qh jc 10s 2d 3h").code());
        assertEquals(5, hand("as kd qh jc 10s 2d 3h").cards().size());
    }

    @Test
    void sevenCardsAreNeverWorseThanAnyFiveOfThem() {
        Random r = new Random(7);
        for (int n = 0; n < 1500; n++) {
            List<PCard> deck = PCard.deck();
            Collections.shuffle(deck, r);
            List<PCard> seven = deck.subList(0, 7);
            Poker.Hand best = Poker.best(seven);
            for (int a = 0; a < 7; a++) for (int b = a + 1; b < 7; b++) {
                List<PCard> five = new ArrayList<>(seven);
                five.remove(b);
                five.remove(a);
                assertTrue(best.compareTo(Poker.of(five)) >= 0);
            }
            assertTrue(seven.containsAll(best.cards()));
        }
    }

    /** Every one of the 2,598,960 five-card hands falls in the textbook count of its category. */
    @Test
    void allFiveCardHandsCountAsTheyShould() {
        List<PCard> d = PCard.deck();
        Map<String, Integer> n = new HashMap<>();
        Map<Poker.Category, Integer> cat = new EnumMap<>(Poker.Category.class);
        PCard[] five = new PCard[5];
        for (int a = 0; a < 52; a++) for (int b = a + 1; b < 52; b++) for (int c = b + 1; c < 52; c++)
            for (int e = c + 1; e < 52; e++) for (int f = e + 1; f < 52; f++) {
                five[0] = d.get(a);
                five[1] = d.get(b);
                five[2] = d.get(c);
                five[3] = d.get(e);
                five[4] = d.get(f);
                Poker.Hand h = Poker.of(java.util.Arrays.asList(five));
                n.merge(h.code(), 1, Integer::sum);
                cat.merge(h.category(), 1, Integer::sum);
            }
        assertEquals(4, n.get("royal"));
        assertEquals(4, n.get("back-straight-flush"));
        assertEquals(32, n.get("straight-flush"));
        assertEquals(624, n.get("four-card"));
        assertEquals(3744, n.get("full-house"));
        assertEquals(5108, n.get("flush"));
        assertEquals(1020, n.get("mountain"));
        assertEquals(1020, n.get("back-straight"));
        assertEquals(8160, n.get("straight"));
        assertEquals(54_912, n.get("triple"));
        assertEquals(123_552, n.get("two-pair"));
        assertEquals(1_098_240, n.get("one-pair"));
        assertEquals(1_302_540, n.get("top"));
        assertEquals(40, cat.get(Poker.Category.STRAIGHT_FLUSH));
        assertEquals(10_200, cat.get(Poker.Category.STRAIGHT));
    }

    // ------------------------------------------------------------------------------------------ the open cards

    @Test
    void fewerThanFiveShowOnlyPairsAndTrips() {
        assertEquals("top", hand("as").code());
        assertEquals("one-pair", hand("9s 9h").code());
        assertEquals("two-pair", hand("9s 9h 4d 4c").code());
        assertEquals("triple", hand("9s 9h 9d").code());
        assertEquals("four-card", hand("9s 9h 9d 9c").code());
        assertEquals("top", hand("2s 3s 4s 5s").code(), "four to a straight flush show nothing");
        // who opens: the best open cards
        List<Poker.Hand> shown = List.of(hand("ks 7d"), hand("4s 4h"), hand("as qd"));
        assertEquals(1, Poker.bestOf(shown), "a pair of 4 over A high");
        assertEquals(2, Poker.bestOf(List.of(hand("kd"), hand("qd"), hand("as"))));
        assertEquals(1, Poker.bestOf(List.of(hand("kd"), hand("ks"))), "K♠ over K♦");
        assertEquals(-1, Poker.bestOf(java.util.Arrays.asList(null, null)));
        assertEquals(List.of(0), Poker.winners(List.of(hand("as ah"), hand("kd kc"))));
        // the timer opens the lowest of three
        assertEquals(1, Poker.lowest(cards("ks 2c 9d")));
        assertEquals(0, Poker.lowest(cards("2c 2d 9d")), "2♣ under 2♦");
        assertFalse(hand("as").beats(hand("as")));
    }
}
