package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * 섯다 족보 (README "화투방"). Pure (unit-tested: every pair of hands).
 *
 * <pre>
 * 38광땡 (3광+8광) > 18광땡 (1광+8광) > 13광땡 (1광+3광)
 *   > 장땡 (10·10) > 9땡 > ... > 1땡 (삥땡)
 *   > 알리 (1·2) > 독사 (1·4) > 구삥 (1·9) > 장삥 (1·10) > 장사 (4·10) > 세륙 (4·6)
 *   > 갑오 (9끗) > 8끗 > ... > 1끗 > 망통 (0끗)              끗 = 두 달의 합의 끝자리
 * 특수패 (평소에는 끗으로 셈):
 *   땡잡이 (3광+7열끗, 망통): 판에서 가장 높은 패가 1땡~9땡이면 그 판을 이김 (장땡 · 광땡은 못 잡음)
 *   암행어사 (4열끗+7열끗, 1끗): 판에서 가장 높은 패가 18광땡 · 13광땡이면 그 판을 이김 (38광땡은 못 잡음)
 *   구사 (4·9, 3끗): 다른 사람의 가장 높은 패가 알리 이하면 재경기
 *   멍텅구리 구사 (4열끗+9열끗, 3끗): 다른 사람의 가장 높은 패가 9땡 이하면 재경기
 * 같은 패(같은 순위)는 나눠 가짐.
 * </pre>
 */
public final class Sutda {

    private Sutda() {}

    public enum Special { NONE, TTAENGJABI, AMHAENG, GUSA, MEONG_GUSA }

    /**
     * A two-card hand: its rank (higher wins), what it is (a message key part: hwatu.hand.&lt;code&gt;) and its special rule.
     * Ranks: 광땡 1000/990/980, 땡 900+month (장땡 910), 알리 800 ... 세륙 750, 끗 700+끗 (망통 700).
     */
    public record Hand(int rank, String code, Special special) {
        public boolean ttaeng() {
            return rank > 900 && rank < 910;   // 1땡 ~ 9땡 (not 장땡)
        }
    }

    public static final int GWANG_38 = 1000, GWANG_18 = 990, GWANG_13 = 980, TTAENG = 900, ALI = 800, DOKSA = 790, GUPPING = 780,
            JANGPPING = 770, JANGSA = 760, SERYUK = 750, KKEUT = 700;

    public static Hand of(Card a, Card b) {
        if (a.equals(b)) throw new IllegalArgumentException("same card twice");
        int lo = Math.min(a.month(), b.month()), hi = Math.max(a.month(), b.month());
        if (a.gwang() && b.gwang()) {
            if (lo == 3 && hi == 8) return new Hand(GWANG_38, "gwang38", Special.NONE);
            if (lo == 1 && hi == 8) return new Hand(GWANG_18, "gwang18", Special.NONE);
            return new Hand(GWANG_13, "gwang13", Special.NONE);   // 1 and 3
        }
        if (lo == hi) return new Hand(TTAENG + lo, "ttaeng" + lo, Special.NONE);
        int named = switch (lo * 100 + hi) {
            case 102 -> ALI;
            case 104 -> DOKSA;
            case 109 -> GUPPING;
            case 110 -> JANGPPING;
            case 410 -> JANGSA;
            case 406 -> SERYUK;
            default -> 0;
        };
        if (named > 0) return new Hand(named, switch (named) {
            case ALI -> "ali";
            case DOKSA -> "doksa";
            case GUPPING -> "gupping";
            case JANGPPING -> "jangpping";
            case JANGSA -> "jangsa";
            default -> "seryuk";
        }, Special.NONE);
        int kkeut = (lo + hi) % 10;
        Card c3 = a.month() == 3 ? a : b, c7 = a.month() == 7 ? a : b, c4 = a.month() == 4 ? a : b, c9 = a.month() == 9 ? a : b;
        if (lo == 3 && hi == 7 && c3.gwang() && c7.yeol()) return new Hand(KKEUT + kkeut, "ttaengjabi", Special.TTAENGJABI);
        if (lo == 4 && hi == 7 && c4.yeol() && c7.yeol()) return new Hand(KKEUT + kkeut, "amhaeng", Special.AMHAENG);
        if (lo == 4 && hi == 9) {
            boolean meong = c4.yeol() && c9.yeol();
            return new Hand(KKEUT + kkeut, meong ? "meong-gusa" : "gusa", meong ? Special.MEONG_GUSA : Special.GUSA);
        }
        return new Hand(KKEUT + kkeut, kkeut == 0 ? "mangtong" : kkeut == 9 ? "gabo" : "kkeut" + kkeut, Special.NONE);
    }

    /** Who wins a showdown: the winners' indices (ties split), or a replay (구사). */
    public record Outcome(boolean replay, List<Integer> winners) {}

    /**
     * The showdown of the hands still in (2 or more). replayAllowed: whether 구사 may still call a replay (재경기 has a limit).
     * Order of the rules: 구사 replay, then 암행어사 (catches the top hand if it is 18·13광땡), then 땡잡이 (catches the top
     * hand if it is 1~9땡), else the highest rank (ties split).
     */
    public static Outcome showdown(List<Hand> hands, boolean replayAllowed) {
        if (hands.size() < 2) return new Outcome(false, hands.isEmpty() ? List.of() : List.of(0));
        if (replayAllowed) {
            for (int i = 0; i < hands.size(); i++) {
                Special s = hands.get(i).special();
                if (s != Special.GUSA && s != Special.MEONG_GUSA) continue;
                int others = 0;
                for (int j = 0; j < hands.size(); j++) if (j != i) others = Math.max(others, hands.get(j).rank());
                if (others <= (s == Special.GUSA ? ALI : TTAENG + 9)) return new Outcome(true, List.of());
            }
        }
        int best = 0;
        for (Hand h : hands) best = Math.max(best, h.rank());
        for (int i = 0; i < hands.size(); i++) {
            if (hands.get(i).special() == Special.AMHAENG && (best == GWANG_18 || best == GWANG_13)) return new Outcome(false, List.of(i));
        }
        boolean ttaengOnTop = best > TTAENG && best < TTAENG + 10;
        for (int i = 0; i < hands.size(); i++) {
            if (hands.get(i).special() == Special.TTAENGJABI && ttaengOnTop) return new Outcome(false, List.of(i));
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < hands.size(); i++) if (hands.get(i).rank() == best) out.add(i);
        return new Outcome(false, out);
    }
}
