package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A 섯다 hand's main pot and side pots (README "도박장 (화투방)"), from what each player put in over the whole hand. Pure
 * (unit-tested).
 *
 * <pre>
 * 1. 안 받은 칩은 돌려줌: the one who put in the most gets back what nobody else matched (an all-in caller had less)
 * 2. levels: each amount a player still in put in, low to high. Pot k holds, from everyone (folded too), the chips between
 *    the level below and level k; it is contested by the players still in who put in at least level k
 *    (the first one = 메인 판, the others = 사이드 판)
 * 3. chips a folded player put in above every level of those still in go to the last pot (the one left takes them)
 * </pre>
 */
public final class Pots {

    private Pots() {}

    /** A pot: its chips and who may win it (seat indices, in seat order). */
    public record Pot(long amount, List<Integer> eligible) {}

    /** What goes back to each seat: the top contributor's chips that nobody else matched (all 0 if two share the top). */
    public static long[] uncalled(long[] put) {
        long[] back = new long[put.length];
        int top = -1;
        long first = -1, second = -1;
        for (int i = 0; i < put.length; i++) {
            if (put[i] > first) {
                second = first;
                first = put[i];
                top = i;
            } else if (put[i] > second) second = put[i];
        }
        if (top >= 0 && second >= 0 && first > second) back[top] = first - second;
        return back;
    }

    /** The pots, main first. put: each seat's chips in the pot (after {@link #uncalled}); folded: out of the hand. */
    public static List<Pot> of(long[] put, boolean[] folded) {
        TreeSet<Long> levels = new TreeSet<>();
        for (int i = 0; i < put.length; i++) if (!folded[i] && put[i] > 0) levels.add(put[i]);
        List<Pot> out = new ArrayList<>();
        if (levels.isEmpty()) {
            long all = 0;
            for (long v : put) all += v;
            List<Integer> in = new ArrayList<>();
            for (int i = 0; i < put.length; i++) if (!folded[i]) in.add(i);
            if (all > 0 || !in.isEmpty()) out.add(new Pot(all, in));
            return out;
        }
        long below = 0;
        for (long level : levels) {
            long amount = 0;
            List<Integer> eligible = new ArrayList<>();
            for (int i = 0; i < put.length; i++) {
                amount += Math.clamp(put[i] - below, 0, level - below);
                if (!folded[i] && put[i] >= level) eligible.add(i);
            }
            out.add(new Pot(amount, eligible));
            below = level;
        }
        long over = 0;
        for (long v : put) over += Math.max(0, v - below);
        if (over > 0) {
            Pot lastPot = out.removeLast();
            out.add(new Pot(lastPot.amount() + over, lastPot.eligible()));
        }
        return out;
    }
}
