package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One 섯다 betting round (README "도박장 (화투방)"): 삥 · 체크 · 콜 · 따당 · 하프 · 다이, with all-in. Seats are 0..n-1 in
 * turn order; the first seat that can still bet acts first. Amounts are what each seat put in during this round (the ante
 * and earlier rounds of the hand are in `basePot`, what a seat can still put in is its `stack`). Pure (unit-tested).
 *
 * <pre>
 * nobody has bet yet (bet 0)   다이 · 체크 (pass) · 삥 (open with the ante) · 하프
 * someone has bet              다이 · 콜 (match) · 따당 (the bet doubled) · 하프 (call, then raise by half the pot)
 * 하프 = what calling costs + half of (the pot after that call), at least the ante
 * 따당 = what makes my bet twice the bet to match
 * </pre>
 * There is no cap on raises: a seat short of chips puts in what it has (올인) and stops acting; the chips it could not
 * match are settled as side pots ({@link Pots}). 따당 and 하프 are offered only to a seat that can put in more than a call
 * while somebody else can still answer. Every raise lifts the bet and every seat's chips are finite, so a round always
 * ends: when everyone still in has matched the bet (or is all-in), or only one is left.
 */
public final class Betting {

    /** In the order the buttons show. */
    public enum Action {
        DIE("die"), CHECK("check"), PING("ping"), CALL("call"), DDADANG("ddadang"), HALF("half");

        private final String key;

        Action(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** 삥 · 따당 · 하프: lifts the bet (everyone else must answer again). */
        public boolean raises() {
            return this == PING || this == DDADANG || this == HALF;
        }
    }

    /** Chips past this are not counted (so doubling never overflows). */
    static final long MOST = 1_000_000_000_000_000L;

    private final int n;
    private final long ante, base;
    private final boolean[] folded;
    private final long[] put, left;
    private final boolean[] pending;
    private final Action[] last;
    private long bet;
    private int raises;
    private int turn = -1;

    /**
     * ante: the room's ante (the 삥 and the least 하프); basePot: chips already in the pot (antes, earlier rounds);
     * stacks: what each seat can still put in; out: seats already out of the hand (folded in an earlier round), or null.
     */
    public Betting(long ante, long basePot, long[] stacks, boolean[] out) {
        if (stacks.length < 2) throw new IllegalArgumentException("players " + stacks.length);
        if (ante < 1) throw new IllegalArgumentException("ante " + ante);
        this.n = stacks.length;
        this.ante = ante;
        this.base = Math.clamp(basePot, 0, MOST);
        folded = new boolean[n];
        put = new long[n];
        left = new long[n];
        pending = new boolean[n];
        last = new Action[n];
        for (int i = 0; i < n; i++) {
            folded[i] = out != null && out[i];
            left[i] = Math.clamp(stacks[i], 0, MOST);
            pending[i] = !folded[i] && left[i] > 0;
        }
        for (int i = 0; i < n; i++) {
            if (able(i)) {
                turn = i;
                break;
            }
        }
        if (turn < 0) turn = 0;
    }

    /** A round where every seat holds the same (tests, bots). */
    public Betting(int players, long ante, long stack) {
        this(ante, 0, filled(players, stack), null);
    }

    private static long[] filled(int n, long v) {
        long[] a = new long[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    // ------------------------------------------------------------------------------------------ reading

    public int players() {
        return n;
    }

    public int turn() {
        return done() ? -1 : turn;
    }

    public boolean folded(int seat) {
        return folded[seat];
    }

    /** Still in, with nothing left to put in. */
    public boolean allIn(int seat) {
        return !folded[seat] && left[seat] == 0;
    }

    /** What the seat put in this round. */
    public long put(int seat) {
        return put[seat];
    }

    /** What the seat can still put in. */
    public long left(int seat) {
        return left[seat];
    }

    /** The bet to match this round (0: nobody has bet). */
    public long bet() {
        return bet;
    }

    /** How many times the bet went up this round (삥 · 따당 · 하프), for show: there is no cap. */
    public int raises() {
        return raises;
    }

    /** The seat's last action this round, or null. */
    public Action last(int seat) {
        return last[seat];
    }

    /** The whole pot: what was in it before this round and what everyone put in since. */
    public long pot() {
        long p = base;
        for (long v : put) p += v;
        return p;
    }

    public int active() {
        int a = 0;
        for (boolean f : folded) if (!f) a++;
        return a;
    }

    /** Still in and able to bet. */
    private boolean able(int seat) {
        return !folded[seat] && left[seat] > 0;
    }

    /** What the seat owes to match the bet (it may hold less: then calling is all-in). */
    private long owe(int seat) {
        return Math.max(0, bet - put[seat]);
    }

    /** What calling adds now (capped by what the seat has). */
    public long toCall(int seat) {
        return Math.min(owe(seat), left[seat]);
    }

    /** Someone other than this seat could still answer a raise. */
    private boolean answerable(int seat) {
        for (int i = 0; i < n; i++) if (i != seat && able(i)) return true;
        return false;
    }

    /** The seat can put in more than a call, and someone could answer it. */
    public boolean canRaise(int seat) {
        return able(seat) && left[seat] > owe(seat) && answerable(seat);
    }

    /** What an action would add for this seat (all it has if that is less: 올인). */
    public long amount(int seat, Action a) {
        long want = switch (a) {
            case DIE, CHECK -> 0;
            case PING -> ante;
            case CALL -> owe(seat);
            case DDADANG -> 2 * bet - put[seat];
            case HALF -> {
                long call = owe(seat);
                yield call + Math.max(ante, (pot() + call) / 2);
            }
        };
        return Math.clamp(want, 0, left[seat]);
    }

    /** Whether the action would leave the seat with nothing (shown as 올인). */
    public boolean allInBy(int seat, Action a) {
        long add = amount(seat, a);
        return add > 0 && add == left[seat];
    }

    /** What the seat may do now (empty when it is not its turn), in button order. */
    public Set<Action> legal(int seat) {
        Set<Action> out = EnumSet.noneOf(Action.class);
        if (done() || seat != turn) return out;
        out.add(Action.DIE);
        boolean raise = canRaise(seat);
        if (bet == 0) {
            out.add(Action.CHECK);
            if (raise) {
                out.add(Action.PING);
                out.add(Action.HALF);
            }
        } else {
            out.add(Action.CALL);
            if (raise) {
                out.add(Action.DDADANG);
                out.add(Action.HALF);
            }
        }
        return out;
    }

    /**
     * Over: one seat left, or no seat that can still bet owes anything or still has to act (a lone seat that can bet,
     * with everyone else all-in, acts only if it owes).
     */
    public boolean done() {
        if (active() <= 1) return true;
        int able = 0;
        for (int i = 0; i < n; i++) if (able(i)) able++;
        for (int i = 0; i < n; i++) {
            if (!pending[i] || !able(i)) continue;
            if (able <= 1 && owe(i) == 0) continue;
            return false;
        }
        return true;
    }

    /** The action the turn timer takes: 다이 if there is something to match, else 체크 (or a free 콜). */
    public Action timeout() {
        if (owe(turn) > 0) return Action.DIE;
        return bet == 0 ? Action.CHECK : Action.CALL;
    }

    // ------------------------------------------------------------------------------------------ acting

    /**
     * The seat whose turn it is acts. Returns what it added (0 for 다이 or 체크); an action not in {@link #legal} is refused
     * (IllegalStateException), as is acting out of turn or after the round is over.
     */
    public long act(int seat, Action a) {
        if (done() || seat != turn) throw new IllegalStateException("not seat " + seat + "'s turn");
        if (!legal(seat).contains(a)) throw new IllegalStateException(a + " is not allowed now");
        last[seat] = a;
        if (a == Action.DIE) {
            fold(seat);
            return 0;
        }
        long added = amount(seat, a);
        put[seat] += added;
        left[seat] -= added;
        pending[seat] = false;
        if (put[seat] > bet) {   // the bet went up (a raise, or an all-in call past it cannot happen: a call never passes it)
            bet = put[seat];
            if (a.raises()) raises++;
            for (int i = 0; i < n; i++) if (i != seat && able(i)) pending[i] = true;
        }
        advance();
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
            if (able(s) && pending[s]) {
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
