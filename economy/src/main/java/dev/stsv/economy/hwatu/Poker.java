package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 세븐 포커 족보 (README "도박장"): the best five of up to seven cards, and what fewer cards show (the 오픈 cards that decide
 * who bets first). Pure (unit-tested).
 *
 * <pre>
 * 로티플 (A K Q J 10 같은 무늬) > 백스트레이트 플러시 (A 2 3 4 5 같은 무늬) > 스트레이트 플러시
 *   > 포카드 > 풀하우스 > 플러시 > 마운틴 (A K Q J 10) > 백스트레이트 (A 2 3 4 5) > 스트레이트 > 트리플 > 투페어 > 원페어 > 탑
 * 같은 족보: 높은 숫자부터 (페어 · 트리플이면 그 숫자 먼저, 그다음 키커), A 가 가장 높음
 * 숫자까지 모두 같으면 무늬: ♠ > ♦ > ♥ > ♣ (그 족보의 가장 높은 장: 페어는 페어 중 좋은 무늬, 투페어는 높은 페어, 스트레이트 · 플러시는 맨 위 장)
 * </pre>
 * The two named straights follow the Korean house rule (한국 세븐 포커): 마운틴 is the top straight and 백스트레이트 the
 * second, above a K-high straight ({@link #BACK_STRAIGHT}); the same for straight flushes (로티플 > 백스트플 > 스티플). Cards
 * fewer than five (the open cards) count only pairs, two pairs, trips and quads, never a straight or flush. One deck: two
 * hands never tie (the suit decides), so a showdown always has one winner.
 */
public final class Poker {

    private Poker() {}

    /** Low to high. key: the message key part (hwatu.poker.&lt;key&gt;). */
    public enum Category {
        HIGH("top"), PAIR("one-pair"), TWO_PAIR("two-pair"), TRIPS("triple"), STRAIGHT("straight"), FLUSH("flush"),
        FULL_HOUSE("full-house"), FOUR("four-card"), STRAIGHT_FLUSH("straight-flush");

        private final String key;

        Category(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** A straight's value when it is A 2 3 4 5 (백스트레이트): above a K-high straight (13), under 마운틴 (A-high, 15). */
    public static final int BACK_STRAIGHT = 14, MOUNTAIN = 15;

    /**
     * A hand: its category, the ranks that break a tie (high first; a straight's one value: its top card, 14 백, 15
     * 마운틴), the card whose suit breaks an exact tie, the cards it is made of (best first), and its name's key (one of
     * {@link #CODES}).
     */
    public record Hand(Category category, int[] ranks, PCard key, List<PCard> cards, String code) implements Comparable<Hand> {

        @Override
        public int compareTo(Hand o) {
            if (category != o.category) return Integer.compare(category.ordinal(), o.category.ordinal());
            int c = Arrays.compare(ranks, o.ranks);
            if (c != 0) return c;
            if (key == null || o.key == null) return key == null ? (o.key == null ? 0 : -1) : 1;
            return Integer.compare(key.suit().ordinal(), o.key.suit().ordinal());
        }

        public boolean beats(Hand o) {
            return compareTo(o) > 0;
        }

        /**
         * What shows after the name: "K·7" (투페어), "K" (원페어 · 트리플 · 포카드 · 탑), "9" (a straight's top), "♠ A" (플러시:
         * its suit and top), "K·3" (풀하우스), "" (로티플 · 마운틴 · 백스트레이트 say it all).
         */
        public String ranksText() {
            return switch (code) {
                case "royal", "mountain", "back-straight", "back-straight-flush" -> "";
                case "two-pair", "full-house" -> PCard.rankName(ranks[0]) + "·" + PCard.rankName(ranks[1]);
                case "flush" -> key.suit().symbol() + " " + PCard.rankName(ranks[0]);
                case "straight-flush" -> key.suit().symbol() + " " + PCard.rankName(ranks[0]);
                default -> PCard.rankName(ranks[0]);
            };
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Hand h && compareTo(h) == 0 && category == h.category;
        }

        @Override
        public int hashCode() {
            return category.hashCode() * 31 + Arrays.hashCode(ranks);
        }

        @Override
        public String toString() {
            return code + " " + ranksText() + " " + cards;
        }
    }

    /** Every name a hand may have, highest first (the 족보 board: hwatu.poker.&lt;code&gt;). */
    public static final List<String> CODES = List.of("royal", "back-straight-flush", "straight-flush", "four-card", "full-house", "flush",
            "mountain", "back-straight", "straight", "triple", "two-pair", "one-pair", "top");

    /** The best hand of these cards (up to 7): the best five of them, or what fewer show. Empty: null. */
    public static Hand best(List<PCard> cards) {
        if (cards == null || cards.isEmpty()) return null;
        if (cards.size() <= 5) return of(cards);
        Hand best = null;
        int n = cards.size();
        PCard[] pick = new PCard[5];
        int[] idx = {0, 1, 2, 3, 4};
        while (true) {
            for (int k = 0; k < 5; k++) pick[k] = cards.get(idx[k]);
            Hand h = of(Arrays.asList(pick.clone()));
            if (best == null || h.beats(best)) best = h;
            int i = 4;
            while (i >= 0 && idx[i] == n - 5 + i) i--;
            if (i < 0) break;
            idx[i]++;
            for (int j = i + 1; j < 5; j++) idx[j] = idx[j - 1] + 1;
        }
        return best;
    }

    /** Exactly these cards (1 to 5): a straight or flush only with five. */
    public static Hand of(List<PCard> cards) {
        if (cards.isEmpty() || cards.size() > 5) throw new IllegalArgumentException("cards " + cards.size());
        List<PCard> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.reverseOrder());
        // groups of a rank: bigger groups first, then higher ranks
        int[] count = new int[15];
        for (PCard c : sorted) count[c.rank()]++;
        List<Integer> groups = new ArrayList<>();
        for (int r = 14; r >= 2; r--) if (count[r] > 0) groups.add(r);
        groups.sort((a, b) -> count[a] != count[b] ? Integer.compare(count[b], count[a]) : Integer.compare(b, a));
        int[] ranks = groups.stream().mapToInt(Integer::intValue).toArray();
        // the cards in that order (each group's best suit first)
        List<PCard> ordered = new ArrayList<>();
        for (int r : ranks) for (PCard c : sorted) if (c.rank() == r) ordered.add(c);
        PCard key = ordered.getFirst();
        int top = count[ranks[0]], second = ranks.length > 1 ? count[ranks[1]] : 0;
        if (sorted.size() == 5) {
            boolean flush = sorted.stream().allMatch(c -> c.suit() == sorted.getFirst().suit());
            int straight = straight(ranks);
            if (straight > 0) {
                // the key card: the top one (the A of 마운틴 and of 백스트레이트: high first, the A leads)
                PCard high = sorted.getFirst();
                List<PCard> line = sorted;
                if (flush) {
                    String code = straight == MOUNTAIN ? "royal" : straight == BACK_STRAIGHT ? "back-straight-flush" : "straight-flush";
                    return new Hand(Category.STRAIGHT_FLUSH, new int[]{straight}, high, line, code);
                }
                String code = straight == MOUNTAIN ? "mountain" : straight == BACK_STRAIGHT ? "back-straight" : "straight";
                return new Hand(Category.STRAIGHT, new int[]{straight}, high, line, code);
            }
            if (top == 4) return new Hand(Category.FOUR, ranks, key, ordered, "four-card");
            if (top == 3 && second == 2) return new Hand(Category.FULL_HOUSE, ranks, key, ordered, "full-house");
            if (flush) return new Hand(Category.FLUSH, ranks, key, ordered, "flush");
        }
        if (top == 4) return new Hand(Category.FOUR, ranks, key, ordered, "four-card");
        if (top == 3) return new Hand(Category.TRIPS, ranks, key, ordered, "triple");
        if (top == 2 && second == 2) return new Hand(Category.TWO_PAIR, ranks, key, ordered, "two-pair");
        if (top == 2) return new Hand(Category.PAIR, ranks, key, ordered, "one-pair");
        return new Hand(Category.HIGH, ranks, key, ordered, "top");
    }

    /** Five distinct ranks (high first) in a row: the straight's value (its top card, 14 백스트레이트, 15 마운틴), else 0. */
    static int straight(int[] ranks) {
        if (ranks.length != 5) return 0;
        int[] r = ranks.clone();
        Arrays.sort(r);   // low to high
        if (r[0] == 2 && r[1] == 3 && r[2] == 4 && r[3] == 5 && r[4] == 14) return BACK_STRAIGHT;
        for (int i = 1; i < 5; i++) if (r[i] != r[i - 1] + 1) return 0;
        return r[4] == 14 ? MOUNTAIN : r[4];
    }

    /** The index of the best hand (the opener of a betting round, the winner of a showdown); -1 if none. */
    public static int bestOf(List<Hand> hands) {
        int best = -1;
        for (int i = 0; i < hands.size(); i++) {
            Hand h = hands.get(i);
            if (h == null) continue;
            if (best < 0 || h.beats(hands.get(best))) best = i;
        }
        return best;
    }

    /** The winners among hands (one, but ties are split if they ever happen). */
    public static List<Integer> winners(List<Hand> hands) {
        int b = bestOf(hands);
        List<Integer> out = new ArrayList<>();
        if (b < 0) return out;
        for (int i = 0; i < hands.size(); i++) if (hands.get(i) != null && hands.get(i).compareTo(hands.get(b)) == 0) out.add(i);
        return out;
    }

    /** Which of three cards opens when nobody picks (the timer): the lowest (by rank, then suit). */
    public static int lowest(List<PCard> cards) {
        int lo = 0;
        for (int i = 1; i < cards.size(); i++) if (cards.get(i).compareTo(cards.get(lo)) < 0) lo = i;
        return lo;
    }
}
