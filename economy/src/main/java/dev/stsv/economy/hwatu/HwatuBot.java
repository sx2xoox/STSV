package dev.stsv.economy.hwatu;

import java.util.Set;

/**
 * The bots of the 시험 판 (/관리자 도박장 시험, README "도박장"): simple sensible play so the admin can try every game alone.
 * Pure (unit-tested); the randomness comes in as a number in [0, 1).
 *
 * <pre>
 * 섯다 (only ever a legal action; the urge to raise halves with each raise already made this round, so a raise war ends)
 *   광땡        올림: 하프 (아무도 안 걸었으면 가끔 삥으로 숨김) · 받은 판이면 따당 · 못 올리면 콜
 *   땡          대개 올림 (따당 · 삥), 아니면 콜 / 체크
 *   알리~세륙   안 건 판: 가끔 삥, 아니면 체크 · 건 판: 콜 (가끔 따당)
 *   땡잡이 · 암행어사 · 구사: 끝까지 콜 / 체크 (특수 규칙을 보려고)
 *   끗          안 건 판: 체크 (8끗 이상 가끔 삥) · 건 판: 7끗 이상 콜, 4~6끗 가끔 다이, 3끗 이하 · 망통 대개 다이,
 *               판보다 큰 돈을 받아야 하면 더 자주 다이
 *   낼 것이 없으면 다이하지 않음 (체크 = 그대로 가기는 공짜)
 * 홀짝  반반
 * 생각하는 시간: 1초 ~ (차례 시간의 1/4, 4초까지), 차례 시간 안
 * </pre>
 */
public final class HwatuBot {

    private HwatuBot() {}

    /**
     * A 섯다 move for this hand. legal: what may be done now; toCall: what calling costs (0: nothing to match); pot: the
     * pot now; raises: how many times the bet went up this round.
     */
    public static Betting.Action sutda(Sutda.Hand h, Set<Betting.Action> legal, long toCall, long pot, int raises, double roll) {
        Betting.Action want = choose(h, legal, toCall, pot, raises, roll);
        return pick(legal, want);
    }

    /** The same from a betting round (the seat whose turn it is). */
    public static Betting.Action sutda(Sutda.Hand h, Betting b, int seat, double roll) {
        return sutda(h, b.legal(seat), b.toCall(seat), b.pot(), b.raises(), roll);
    }

    private static Betting.Action choose(Sutda.Hand h, Set<Betting.Action> legal, long toCall, long pot, int raises, double roll) {
        int rank = h.rank();
        boolean open = legal.contains(Betting.Action.CHECK);   // nobody has bet yet
        double urge = 1.0 / (1 << Math.min(raises, 10));        // halves with each raise this round
        Betting.Action stay = open ? Betting.Action.CHECK : Betting.Action.CALL;
        if (rank >= Sutda.GWANG_13) {
            if (roll >= urge) return stay;
            if (open) return roll < 0.25 * urge ? Betting.Action.PING : Betting.Action.HALF;
            return roll < 0.5 * urge ? Betting.Action.HALF : Betting.Action.DDADANG;
        }
        if (rank > Sutda.TTAENG) {
            if (roll >= 0.7 * urge) return stay;
            return open ? Betting.Action.PING : Betting.Action.DDADANG;
        }
        if (rank >= Sutda.SERYUK) {
            if (open) return roll < 0.35 ? Betting.Action.PING : Betting.Action.CHECK;
            return roll < 0.15 * urge ? Betting.Action.DDADANG : Betting.Action.CALL;
        }
        if (h.special() != Sutda.Special.NONE) return stay;
        int kkeut = rank - Sutda.KKEUT;
        if (toCall == 0) return kkeut >= 8 && open && roll < 0.15 ? Betting.Action.PING : stay;
        boolean steep = toCall > pot;   // asked for more than the whole pot
        if (kkeut >= 7) return steep && roll < 0.3 ? Betting.Action.DIE : Betting.Action.CALL;
        if (kkeut >= 4) return roll < (steep ? 0.7 : 0.35) ? Betting.Action.DIE : Betting.Action.CALL;
        return roll < (steep ? 0.9 : 0.75) ? Betting.Action.DIE : Betting.Action.CALL;
    }

    /** The wanted action if it may be done, else the nearest one that may (a raise falls back to a call · check). */
    private static Betting.Action pick(Set<Betting.Action> legal, Betting.Action want) {
        if (legal.contains(want)) return want;
        if (want.raises()) {
            for (Betting.Action a : new Betting.Action[]{Betting.Action.HALF, Betting.Action.DDADANG, Betting.Action.PING})
                if (legal.contains(a)) return a;
        }
        if (legal.contains(Betting.Action.CALL)) return Betting.Action.CALL;
        if (legal.contains(Betting.Action.CHECK)) return Betting.Action.CHECK;
        return Betting.Action.DIE;
    }

    /** 홀짝: true = 홀. */
    public static boolean callsOdd(double roll) {
        return roll < 0.5;
    }

    /** How long a bot thinks (millis): 1 s up to a quarter of the turn timer (at most 4 s), always well inside it. */
    public static long delay(int turnSeconds, double roll) {
        long most = Math.clamp(turnSeconds * 1000L / 4, 1000, 4000);
        return 1000 + (long) ((most - 1000) * Math.clamp(roll, 0, 1));
    }
}
