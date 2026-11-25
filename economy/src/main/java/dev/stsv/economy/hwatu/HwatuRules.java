package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * The money rules of the 화투방 (README "화투방"): buying and cashing chips, who may sit, the 자릿세 and how a pot is split,
 * 홀짝 and 주사위. Pure (unit-tested). Refusals are message keys.
 */
public final class HwatuRules {

    private HwatuRules() {}

    /** null if n chips may be bought now, else why not. */
    public static String canBuy(long n, long boughtToday, long dailyLimit, long gold, long price) {
        if (n <= 0) return "hwatu.bad-amount";
        if (boughtToday + n > dailyLimit) return "hwatu.buy-limit";
        if (gold < n * price) return "hwatu.buy-gold";
        return null;
    }

    /** How many chips can still be bought today. */
    public static long buyLeft(long boughtToday, long dailyLimit) {
        return Math.max(0, dailyLimit - boughtToday);
    }

    /** null if n chips may be cashed, else why not. available = chips not in a running hand. */
    public static String canCash(long n, long available) {
        if (n <= 0) return "hwatu.bad-amount";
        if (n > available) return "hwatu.cash-short";
        return null;
    }

    /** Old enough: the account first joined at least minAgeMs ago (firstPlayed 0 = unknown: too new). */
    public static boolean oldEnough(long firstPlayed, long now, long minAgeMs) {
        return firstPlayed > 0 && now - firstPlayed >= minAgeMs;
    }

    /** Today's loss (chips lost minus won, never below 0) reached the cap: no more sitting today. */
    public static boolean resting(long netToday, long lossCap) {
        return -netToday >= lossCap;
    }

    /**
     * null if the player may sit (or be dealt the next hand), else why not: too new, resting after today's losses, not
     * enough chips for the worst case of a hand (need).
     */
    public static String canPlay(long firstPlayed, long now, long minAgeMs, long netToday, long lossCap, long available, long need) {
        if (!oldEnough(firstPlayed, now, minAgeMs)) return "hwatu.too-new";
        if (resting(netToday, lossCap)) return "hwatu.resting";
        if (available < need) return "hwatu.need-chips";
        return null;
    }

    /**
     * Chips a player must hold to be dealt in: the ante, and in 섯다 every raise of every round it may take (the first
     * round and each 구사 재경기), so a hand can never ask for more than the player has (no all-in, no side pots).
     */
    public static long need(Table.Game game, long ante, int maxRaises, int replays) {
        if (game != Table.Game.SUTDA) return ante;
        return ante + Betting.most(ante, maxRaises) * (1L + replays);
    }

    /** The 자릿세 of a pot: percent of it, rounded up (a pot is never free). */
    public static long rake(long pot, int percent) {
        if (pot <= 0 || percent <= 0) return 0;
        return (pot * percent + 99) / 100;
    }

    /** How a pot is paid: each winner's share and what is destroyed (the 자릿세 and the odd chips a split leaves). */
    public record Split(long share, long burned) {}

    public static Split split(long pot, int percent, int winners) {
        if (winners <= 0) return new Split(0, pot);
        long rake = rake(pot, percent);
        long rest = pot - rake;
        long share = rest / winners;
        return new Split(share, rake + rest - share * winners);
    }

    /** 홀짝: the caller wins if the die's parity is what they called. */
    public static boolean callerWins(boolean calledOdd, int die) {
        return (die % 2 == 1) == calledOdd;
    }

    /** 주사위: the indices of the highest totals (ties split). */
    public static List<Integer> highest(int[] totals) {
        int best = Integer.MIN_VALUE;
        for (int t : totals) best = Math.max(best, t);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < totals.length; i++) if (totals[i] == best) out.add(i);
        return out;
    }
}
