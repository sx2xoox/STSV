package dev.stsv.economy.hwatu;

/**
 * The bots of the 시험 판 (/관리자 도박장 시험, README "도박장"): simple sensible play so the admin can try every game alone.
 * Pure (unit-tested); the randomness comes in as a number in [0, 1).
 *
 * <pre>
 * 섯다  광땡: 레이즈 (못 올리면 콜) · 땡: 대개 레이즈 · 알리~세륙: 가끔 레이즈, 아니면 콜
 *       땡잡이 · 암행어사 · 구사: 끝까지 콜 (특수 규칙을 보려고)
 *       7끗 이상: 콜 · 4~6끗: 올린 판이면 가끔 다이 · 3끗 이하 · 망통: 올린 판이면 대개 다이
 *       낼 것이 없으면 다이하지 않음 (콜 = 그대로 가기는 공짜)
 * 홀짝  반반
 * 생각하는 시간: 1초 ~ (차례 시간의 1/4, 4초까지), 차례 시간 안
 * </pre>
 */
public final class HwatuBot {

    private HwatuBot() {}

    /** A 섯다 move for this hand: toCall = what calling costs now (0: a free check), canRaise = a raise is left. */
    public static Betting.Action sutda(Sutda.Hand h, long toCall, boolean canRaise, double roll) {
        int rank = h.rank();
        Betting.Action raiseOrCall = canRaise ? Betting.Action.RAISE : Betting.Action.CALL;
        if (rank >= Sutda.GWANG_13) return raiseOrCall;
        if (rank > Sutda.TTAENG) return roll < 0.7 ? raiseOrCall : Betting.Action.CALL;
        if (rank >= Sutda.SERYUK) return toCall == 0 && roll < 0.35 ? raiseOrCall : Betting.Action.CALL;
        if (h.special() != Sutda.Special.NONE) return Betting.Action.CALL;
        int kkeut = rank - Sutda.KKEUT;
        if (toCall == 0) return kkeut >= 8 && roll < 0.15 ? raiseOrCall : Betting.Action.CALL;
        if (kkeut >= 7) return Betting.Action.CALL;
        if (kkeut >= 4) return roll < 0.35 ? Betting.Action.DIE : Betting.Action.CALL;
        return roll < 0.75 ? Betting.Action.DIE : Betting.Action.CALL;
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
