package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.List;

/**
 * One 섯다 betting round (README "화투방"), simplified from 다이 · 콜 · 하프 · 따당: 다이 (fold), 콜 (match the current bet;
 * with nothing to match it is a check), 레이즈 (the bet goes up by a fixed amount, at most maxRaises times a round). The round
 * ends when everyone still in has matched the bet since the last raise, or only one is left. Seats are 0..n-1 in turn order;
 * seat 0 acts first. Amounts are what each seat put in during this round (the ante is not part of it). Pure (unit-tested).
 */
public final class Betting {

    public enum Action { DIE, CALL, RAISE }

    private final int n;
    private final long raise;
    private final int maxRaises;
    private final boolean[] folded;
    private final long[] put;
    private final boolean[] pending;
    private long bet;
    private int raises;
    private int turn;

    public Betting(int players, long raise, int maxRaises) {
        if (players < 2) throw new IllegalArgumentException("players " + players);
        this.n = players;
        this.raise = raise;
        this.maxRaises = maxRaises;
        folded = new boolean[n];
        put = new long[n];
        pending = new boolean[n];
        java.util.Arrays.fill(pending, true);
        turn = 0;
    }

    public int turn() {
        return done() ? -1 : turn;
    }

    public boolean folded(int seat) {
        return folded[seat];
    }

    /** What the seat put in this round. */
    public long put(int seat) {
        return put[seat];
    }

    public long bet() {
        return bet;
    }

    public int raises() {
        return raises;
    }

    public int active() {
        int a = 0;
        for (boolean f : folded) if (!f) a++;
        return a;
    }

    /** What the seat must add to call. */
    public long toCall(int seat) {
        return bet - put[seat];
    }

    /** What the seat would add by raising. */
    public long toRaise(int seat) {
        return bet + raise - put[seat];
    }

    public boolean canRaise() {
        return raises < maxRaises;
    }

    public boolean done() {
        if (active() <= 1) return true;
        for (int i = 0; i < n; i++) if (pending[i] && !folded[i]) return false;
        return true;
    }

    /** The most a seat can put in during one round: every raise called. */
    public static long most(long raise, int maxRaises) {
        return raise * maxRaises;
    }

    /** The action the turn timer takes: 다이 if there is something to match, else 콜 (a check). */
    public Action timeout() {
        return toCall(turn) > 0 ? Action.DIE : Action.CALL;
    }

    /**
     * The seat whose turn it is acts. Returns what it added (0 for 다이 or a check); a raise past the limit is refused
     * (IllegalStateException), as is acting out of turn or after the round is over.
     */
    public long act(int seat, Action a) {
        if (done() || seat != turn) throw new IllegalStateException("not seat " + seat + "'s turn");
        long added = 0;
        switch (a) {
            case DIE -> fold(seat);
            case CALL -> {
                added = toCall(seat);
                put[seat] = bet;
                pending[seat] = false;
            }
            case RAISE -> {
                if (!canRaise()) throw new IllegalStateException("no more raises");
                bet += raise;
                raises++;
                added = bet - put[seat];
                put[seat] = bet;
                for (int i = 0; i < n; i++) pending[i] = !folded[i] && i != seat;
            }
        }
        if (a != Action.DIE) advance();
        return added;
    }

    /** A seat folds, in turn or not (it left the table). */
    public void fold(int seat) {
        if (folded[seat]) return;
        folded[seat] = true;
        pending[seat] = false;
        if (seat == turn) advance();
    }

    private void advance() {
        if (done()) return;
        for (int k = 1; k <= n; k++) {
            int s = (turn + k) % n;
            if (!folded[s] && pending[s]) {
                turn = s;
                return;
            }
        }
    }

    /** The seats still in. */
    public List<Integer> in() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!folded[i]) out.add(i);
        return out;
    }
}
