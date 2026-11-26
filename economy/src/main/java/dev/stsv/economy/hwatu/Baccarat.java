package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 바카라 (README "도박장"): the one game the house plays. Players bet on 플레이어 · 뱅커 · 타이; the dealer deals two cards
 * to each hand and a third by the tableau; the total nearer 9 wins. Pure (unit-tested).
 *
 * <pre>
 * 값      A 1, 2~9 그대로, 10 · J · Q · K 0. 합은 끝자리 (7 + 8 = 5)
 * 내추럴  처음 두 장에 어느 쪽이든 8 · 9 이면 둘 다 멈춤
 * 플레이어 0~5 한 장 더, 6 · 7 멈춤
 * 뱅커    플레이어가 멈췄으면: 0~5 한 장 더, 6 · 7 멈춤
 *         플레이어가 받은 셋째 장(p3)에 따라:  0~2 받음 · 3 받음 (p3 = 8 빼고) · 4 받음 (p3 = 2~7) · 5 받음 (p3 = 4~7)
 *                                              6 받음 (p3 = 6 · 7) · 7 멈춤
 * 배당    플레이어 1:1 · 뱅커 0.95:1 (커미션 5%, 딴 칩 내림) · 타이 8:1 (타이면 플레이어 · 뱅커에 건 칩은 그대로 돌려줌)
 * </pre>
 * The shoe is {@link #DECKS} decks; it is shuffled anew once fewer than {@link #CUT} cards are left (the cut card).
 */
public final class Baccarat {

    private Baccarat() {}

    /** Where a bet goes; key: the message key part and the road's letter. */
    public enum Spot {
        PLAYER("player", 'P'), BANKER("banker", 'B'), TIE("tie", 'T');

        private final String key;
        private final char letter;

        Spot(String key, char letter) {
            this.key = key;
            this.letter = letter;
        }

        public String key() {
            return key;
        }

        public char letter() {
            return letter;
        }

        public static Spot of(char letter) {
            for (Spot s : values()) if (s.letter == letter) return s;
            return null;
        }
    }

    public static final int DECKS = 8, CUT = 60;

    /** A hand's total: the sum of the cards' values, its last digit. */
    public static int total(List<PCard> cards) {
        int t = 0;
        for (PCard c : cards) t += c.baccarat();
        return t % 10;
    }

    /** Either hand has 8 or 9 on its first two cards: both stand. */
    public static boolean natural(int player2, int banker2) {
        return player2 >= 8 || banker2 >= 8;
    }

    /** The player draws a third card on a two-card total of 0 ~ 5 (no natural on the table). */
    public static boolean playerDraws(int player2) {
        return player2 <= 5;
    }

    /**
     * The banker draws a third card (no natural): `playerThird` is the value (0 ~ 9) of the card the player drew, or -1 if
     * the player stood.
     */
    public static boolean bankerDraws(int banker2, int playerThird) {
        if (playerThird < 0) return banker2 <= 5;
        return switch (banker2) {
            case 0, 1, 2 -> true;
            case 3 -> playerThird != 8;
            case 4 -> playerThird >= 2 && playerThird <= 7;
            case 5 -> playerThird >= 4 && playerThird <= 7;
            case 6 -> playerThird == 6 || playerThird == 7;
            default -> false;
        };
    }

    /** A dealt round: each hand's cards in the order they came, and who won. */
    public record Coup(List<PCard> player, List<PCard> banker) {
        public int playerTotal() {
            return total(player);
        }

        public int bankerTotal() {
            return total(banker);
        }

        public Spot winner() {
            int p = playerTotal(), b = bankerTotal();
            return p > b ? Spot.PLAYER : b > p ? Spot.BANKER : Spot.TIE;
        }

        public boolean natural() {
            return Baccarat.natural(total(player.subList(0, 2)), total(banker.subList(0, 2)));
        }

        /**
         * The order the cards are dealt in (P1 B1 P2 B2, then the player's third, then the banker's third): each entry is
         * the hand (true: player) and the card's index in it.
         */
        public List<int[]> order() {
            List<int[]> out = new ArrayList<>();
            out.add(new int[]{1, 0});
            out.add(new int[]{0, 0});
            out.add(new int[]{1, 1});
            out.add(new int[]{0, 1});
            if (player.size() > 2) out.add(new int[]{1, 2});
            if (banker.size() > 2) out.add(new int[]{0, 2});
            return out;
        }
    }

    /** Deals a round from the top of the shoe (it takes the cards off): P B P B, then the third cards by the tableau. */
    public static Coup deal(Shoe shoe) {
        List<PCard> p = new ArrayList<>(), b = new ArrayList<>();
        p.add(shoe.draw());
        b.add(shoe.draw());
        p.add(shoe.draw());
        b.add(shoe.draw());
        int p2 = total(p), b2 = total(b);
        if (!natural(p2, b2)) {
            int third = -1;
            if (playerDraws(p2)) {
                PCard c = shoe.draw();
                p.add(c);
                third = c.baccarat();
            }
            if (bankerDraws(b2, third)) b.add(shoe.draw());
        }
        return new Coup(List.copyOf(p), List.copyOf(b));
    }

    /**
     * What a bet on a spot pays back (the bet itself included; 0 when it lost), the banker's commission (percent of what
     * it wins, rounded so the player gets the winnings rounded down) and the tie's odds from hwatu.yml.
     */
    public static long payout(Spot bet, long amount, Spot winner, int commissionPercent, int tieOdds) {
        if (amount <= 0) return 0;
        if (winner == Spot.TIE) {
            if (bet == Spot.TIE) return amount + amount * tieOdds;
            return amount;   // 플레이어 · 뱅커: push
        }
        if (bet != winner) return 0;
        if (bet == Spot.PLAYER) return 2 * amount;
        return amount + amount * (100 - commissionPercent) / 100;   // 뱅커: 0.95:1, the winnings rounded down
    }

    /** The shoe: decks shuffled together; draw() takes the top card. */
    public static final class Shoe {
        private final RandomGenerator rng;
        private final List<PCard> cards = new ArrayList<>();
        private int shuffles;

        public Shoe(RandomGenerator rng) {
            this.rng = rng;
            shuffle();
        }

        /** A fresh shoe of {@link #DECKS} decks. */
        public void shuffle() {
            cards.clear();
            for (int d = 0; d < DECKS; d++) cards.addAll(PCard.deck());
            Collections.shuffle(cards, rng);
            shuffles++;
        }

        /** Before a round: the cut card came out (fewer than {@link #CUT} left), so a new shoe. true if it was shuffled. */
        public boolean ready() {
            if (cards.size() >= CUT) return false;
            shuffle();
            return true;
        }

        public PCard draw() {
            if (cards.isEmpty()) shuffle();
            return cards.removeLast();
        }

        public int left() {
            return cards.size();
        }

        public int shuffles() {
            return shuffles;
        }

        /** Tests: the next cards to come out, in order (the first given is drawn first). */
        void stack(List<PCard> next) {
            for (int i = next.size() - 1; i >= 0; i--) cards.add(next.get(i));
        }
    }

    /** The road: the last results, oldest first, kept to `keep` letters (P B T). */
    public static String road(String road, Spot won, int keep) {
        String r = (road == null ? "" : road) + won.letter();
        return r.length() > keep ? r.substring(r.length() - keep) : r;
    }
}
