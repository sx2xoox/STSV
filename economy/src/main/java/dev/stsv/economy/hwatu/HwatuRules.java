package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * The money rules of the 화투방 (README "화투방"): buying and cashing chips, who may sit, the 자릿세 and how a pot is split,
 * 홀짝 and 주사위. Pure (unit-tested). Refusals are message keys.
 */
public final class HwatuRules {

    private HwatuRules() {}

    /** null if n chips may be bought now, else why not. dailyLimit 0 (or less): no daily limit. */
    public static String canBuy(long n, long boughtToday, long dailyLimit, long gold, long price) {
        if (n <= 0) return "hwatu.bad-amount";
        if (dailyLimit > 0 && boughtToday + n > dailyLimit) return "hwatu.buy-limit";
        if (gold < n * price) return "hwatu.buy-gold";
        return null;
    }

    /** How many chips can still be bought today (Long.MAX_VALUE with no daily limit). */
    public static long buyLeft(long boughtToday, long dailyLimit) {
        if (dailyLimit <= 0) return Long.MAX_VALUE;
        return Math.max(0, dailyLimit - boughtToday);
    }

    /** null if n chips may be cashed, else why not. available = chips not in a running hand. */
    public static String canCash(long n, long available) {
        if (n <= 0) return "hwatu.bad-amount";
        if (n > available) return "hwatu.cash-short";
        return null;
    }

    /** Old enough: the account first joined at least minAgeMs ago (firstPlayed 0 = unknown: too new). minAgeMs 0: everyone. */
    public static boolean oldEnough(long firstPlayed, long now, long minAgeMs) {
        if (minAgeMs <= 0) return true;
        return firstPlayed > 0 && now - firstPlayed >= minAgeMs;
    }

    /** Today's loss (chips lost minus won, never below 0) reached the cap: no more sitting today. lossCap 0: never. */
    public static boolean resting(long netToday, long lossCap) {
        return lossCap > 0 && -netToday >= lossCap;
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
     * Chips a player must hold to sit and to be dealt in. 홀짝 · 주사위: the ante (a hand never takes more). 섯다 · 세븐 포커: ante ×
     * sitAntes (hwatu.yml games.sutda.sit-antes, at least 1). A 섯다 hand can never take more than the player holds
     * (short of chips they go all-in and play for the side pots they matched), so even 1 ante is safe; a few antes make
     * sure a player can pay the ante, open with 삥 and answer a 따당 before going all-in.
     */
    public static long need(Table.Game game, long ante, int sitAntes) {
        if (game != Table.Game.SUTDA && game != Table.Game.POKER) return ante;
        return ante * Math.max(1, sitAntes);
    }

    /**
     * The 자릿세 of a pot: percent of it, rounded down. With 1-chip antes a pot of a few chips would otherwise lose a whole
     * chip (2 chips → 1 = 50 %); rounded down it never takes more than the percent, and a small pot (under 20 chips at
     * 5 %) pays none.
     */
    public static long rake(long pot, int percent) {
        if (pot <= 0 || percent <= 0) return 0;
        return pot * percent / 100;
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
