package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One table of the 화투방 (README "화투방"): its seats and the hand being played. No Bukkit: time, randomness, who may be dealt
 * in (chips, loss cap, still at the screen) and what happens after (chips, messages, the log) come from outside, so the
 * whole flow is unit-tested.
 *
 * <pre>
 * WAITING  2+ seated: after timers.start a hand starts with everyone the bank lets play (the others stand up)
 * PLAYING  everyone in pays the ante, then
 *            홀짝:   the first player calls 홀 or 짝 (turn timer: a random call), the die is rolled after timers.reveal
 *            주사위: everyone's dice are rolled after timers.reveal, the highest total wins (ties split)
 *            섯다:   two cards each, one betting round (다이 · 콜 · 레이즈; turn timer: 다이 if there is something to
 *                    match, else 콜), showdown ({@link Sutda}); 구사 replays with the same pot (no new ante)
 *          leaving mid-hand folds (the chips put in stay in the pot); one player left wins the pot
 * RESULT   timers.result to read it, then the next hand
 * </pre>
 * Every pot pays the 자릿세 ({@link HwatuRules#split}); the chips are moved by the listener (nothing is taken before a hand ends,
 * so a crash voids the hand instead of losing chips).
 */
public final class Table {

    public enum Game {
        HOLJJAK("holjjak"), DICE("dice"), SUTDA("sutda");

        private final String key;

        Game(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    public enum Phase { WAITING, PLAYING, RESULT }

    /** PICK: 홀짝's call; ROLL: the dice are about to show; BET: 섯다's betting round. */
    public enum Step { PICK, ROLL, BET }

    /** 홀짝's call and 섯다's actions. */
    public enum Move { ODD, EVEN, DIE, CALL, RAISE }

    /** Who may be dealt in: null, or why not (a message key; that player stands up). */
    public interface Bank {
        String refusal(UUID player, long need);
    }

    public interface Listener {
        /** Something on the table changed (redraw). */
        void changed(Table t);

        /** A seated player could not be dealt in and stood up. */
        void kicked(Table t, UUID player, String reason);

        /** A hand ended: move the chips, tell everyone, log it. */
        void finished(Table t, Result r);

        /** 구사: the hand is dealt again with the same pot. */
        void replay(Table t);
    }

    /**
     * A finished hand. put: what each put in (ante + bets), payout: what each gets back (winners only), burned: the 자릿세 and
     * the odd chips of a split (destroyed). detail: per player what they had (섯다 hand code, 주사위 "a+b", 홀짝 the call
     * "odd"/"even" for the caller), "die" for a player who folded or left. uncontested: everyone else folded or left.
     */
    public record Result(Game game, List<UUID> players, Map<UUID, Long> put, Map<UUID, Long> payout, long pot, long burned,
                         List<UUID> winners, Map<UUID, String> detail, int die, boolean uncontested) {

        /** Chips won (+) or lost (-) by a player in this hand. */
        public long net(UUID player) {
            return payout.getOrDefault(player, 0L) - put.getOrDefault(player, 0L);
        }
    }

    private final int id;
    private final Game game;
    private final HwatuSpec.Room room;
    private HwatuSpec spec;
    private final List<UUID> seats = new ArrayList<>();
    private Phase phase = Phase.WAITING;
    private long deadline;
    private int dealer;

    // the hand
    private final List<UUID> players = new ArrayList<>();
    private final Map<UUID, Long> put = new HashMap<>();
    private boolean[] out = new boolean[0];
    private final Map<UUID, Card[]> cards = new HashMap<>();
    private final Map<UUID, int[]> rolls = new HashMap<>();
    private Boolean calledOdd;
    private int die;
    private Betting betting;
    private int replays;
    private Step step;
    private Result last;
    /** 시험 판 (/관리자 화투방 시험 섯다 패): the cards some players get in the next 섯다 hand's first deal. */
    private final Map<UUID, Card[]> forced = new LinkedHashMap<>();

    private final RandomGenerator rng;

    public Table(int id, Game game, HwatuSpec.Room room, HwatuSpec spec, RandomGenerator rng) {
        this.rng = rng;
        this.id = id;
        this.game = game;
        this.room = room;
        this.spec = spec;
    }

    // ------------------------------------------------------------------------------------------ reading

    public int id() { return id; }
    public Game game() { return game; }
    public HwatuSpec.Room room() { return room; }
    public Phase phase() { return phase; }
    public Step step() { return step; }
    public long deadline() { return deadline; }
    public List<UUID> seats() { return Collections.unmodifiableList(seats); }
    public List<UUID> players() { return Collections.unmodifiableList(players); }
    public Result last() { return last; }
    public Boolean calledOdd() { return calledOdd; }
    public int die() { return die; }
    public Betting betting() { return betting; }
    public int replays() { return replays; }

    public int max() {
        return spec.game(game).max();
    }

    public boolean full() {
        return seats.size() >= max();
    }

    public long ante() {
        return room.ante();
    }

    /** Chips a player must hold to be dealt in here. */
    public long need() {
        return spec.need(game, room);
    }

    /**
     * 시험 판 only: deals this player these two cards in the next 섯다 hand (its first deal, not a 구사 재경기). false (nothing
     * changes) if the two are the same card or a card another forced player already gets.
     */
    public boolean force(UUID player, Card a, Card b) {
        if (a == null || b == null || a.equals(b)) return false;
        for (Map.Entry<UUID, Card[]> e : forced.entrySet()) {
            if (e.getKey().equals(player)) continue;
            for (Card c : e.getValue()) if (c.equals(a) || c.equals(b)) return false;
        }
        forced.put(player, new Card[]{a, b});
        return true;
    }

    /** The cards forced for the next hand (empty: none). */
    public Map<UUID, Card[]> forced() {
        return Collections.unmodifiableMap(forced);
    }

    /** Picks up a reloaded hwatu.yml (timers, limits); the room's ante stays until the table empties. */
    public void spec(HwatuSpec s) {
        this.spec = s;
    }

    /** In the running hand and not folded or gone. */
    public boolean inHand(UUID u) {
        int i = players.indexOf(u);
        if (phase != Phase.PLAYING || i < 0) return false;
        return game == Game.SUTDA ? !betting.folded(i) : !out[i];
    }

    /** Folded or left in this hand (also shown during the result). */
    public boolean folded(UUID u) {
        int i = players.indexOf(u);
        if (i < 0) return false;
        return game == Game.SUTDA ? betting != null && betting.folded(i) : out.length > i && out[i];
    }

    /** What a player put in this hand (0 if not in it). */
    public long put(UUID u) {
        return put.getOrDefault(u, 0L);
    }

    /** Chips held in the running hand (not yet won or lost). */
    public long committed(UUID u) {
        return phase == Phase.PLAYING ? put(u) : 0;
    }

    public long pot() {
        long p = 0;
        for (long v : put.values()) p += v;
        return p;
    }

    public Card[] cards(UUID u) {
        return cards.get(u);
    }

    public int[] rolls(UUID u) {
        return rolls.get(u);
    }

    /** Whose move it is (홀짝's caller, 섯다's turn), or null. */
    public UUID turn() {
        if (phase != Phase.PLAYING) return null;
        if (step == Step.PICK) return players.getFirst();
        if (step == Step.BET && betting.turn() >= 0) return players.get(betting.turn());
        return null;
    }

    // ------------------------------------------------------------------------------------------ seats

    /** Sits down (to wait for the next hand if one is running); false if full or already seated. */
    public boolean sit(UUID u, long now) {
        if (full() || seats.contains(u)) return false;
        seats.add(u);
        if (phase == Phase.WAITING && seats.size() >= 2 && deadline == 0) deadline = now + spec.startSeconds() * 1000L;
        return true;
    }

    /** Stands up; in a running hand that is a fold (what was put in stays in the pot). */
    public void leave(UUID u, long now, Listener l) {
        seats.remove(u);
        if (phase == Phase.WAITING && seats.size() < 2) deadline = 0;
        if (inHand(u)) {
            int i = players.indexOf(u);
            if (game == Game.SUTDA) {
                betting.fold(i);
                afterBet(now, l);
            } else {
                out[i] = true;
                if (active() <= 1) finishUncontested(now, l);
            }
        }
        l.changed(this);
    }

    public boolean empty() {
        return seats.isEmpty() && phase != Phase.PLAYING;
    }

    private int active() {
        if (game == Game.SUTDA) return betting.active();
        int a = 0;
        for (boolean o : out) if (!o) a++;
        return a;
    }

    // ------------------------------------------------------------------------------------------ time

    /** Called about once a second. */
    public void tick(long now, Bank bank, Listener l) {
        switch (phase) {
            case WAITING -> {
                if (seats.size() < 2) {
                    deadline = 0;
                    return;
                }
                if (deadline == 0) {
                    deadline = now + spec.startSeconds() * 1000L;
                    l.changed(this);
                    return;
                }
                if (now < deadline) return;
                List<UUID> ok = new ArrayList<>();
                for (UUID u : new ArrayList<>(seats)) {
                    String why = bank.refusal(u, need());
                    if (why == null) ok.add(u);
                    else {
                        seats.remove(u);
                        l.kicked(this, u, why);
                    }
                }
                if (ok.size() < 2) {
                    deadline = 0;
                    l.changed(this);
                    return;
                }
                start(ok, now);
                l.changed(this);
            }
            case PLAYING -> {
                if (now >= deadline) timeout(now, l);
            }
            case RESULT -> {
                if (now < deadline) return;
                phase = Phase.WAITING;
                deadline = seats.size() >= 2 ? now : 0;   // the result was the pause: the next hand starts on the next tick
                l.changed(this);
            }
        }
    }

    private void start(List<UUID> ok, long now) {
        players.clear();
        put.clear();
        cards.clear();
        rolls.clear();
        calledOdd = null;
        die = 0;
        replays = 0;
        betting = null;
        last = null;
        int shift = Math.floorMod(dealer++, ok.size());
        for (int k = 0; k < ok.size(); k++) players.add(ok.get((shift + k) % ok.size()));
        for (UUID u : players) put.put(u, ante());
        out = new boolean[players.size()];
        phase = Phase.PLAYING;
        switch (game) {
            case HOLJJAK -> {
                step = Step.PICK;
                deadline = now + spec.turnSeconds() * 1000L;
            }
            case DICE -> {
                step = Step.ROLL;
                deadline = now + spec.revealSeconds() * 1000L;
            }
            case SUTDA -> {
                deal(null, forced);
                forced.clear();
                betting = new Betting(players.size(), ante(), spec.game(Game.SUTDA).maxRaises());
                step = Step.BET;
                deadline = now + spec.turnSeconds() * 1000L;
            }
        }
    }

    /** Two cards each to the players still in (folded: null), from a fresh shuffled deck; `fixed`: these players' cards. */
    private void deal(Betting folds, Map<UUID, Card[]> fixed) {
        List<Card> deck = Card.deck();
        Collections.shuffle(deck, rng);
        cards.clear();
        Map<UUID, Card[]> use = new HashMap<>();
        for (Map.Entry<UUID, Card[]> e : fixed.entrySet()) {
            if (!players.contains(e.getKey())) continue;
            use.put(e.getKey(), e.getValue());
            for (Card c : e.getValue()) deck.remove(c);
        }
        int k = 0;
        for (int i = 0; i < players.size(); i++) {
            if (folds != null && folds.folded(i)) continue;
            UUID u = players.get(i);
            Card[] mine = use.get(u);
            cards.put(u, mine != null ? mine.clone() : new Card[]{deck.get(k++), deck.get(k++)});
        }
    }

    private void timeout(long now, Listener l) {
        switch (step) {
            case PICK -> {
                calledOdd = rng.nextBoolean();
                step = Step.ROLL;
                deadline = now + spec.revealSeconds() * 1000L;
                l.changed(this);
            }
            case ROLL -> roll(now, l);
            case BET -> {
                int seat = betting.turn();
                if (seat < 0) afterBet(now, l);
                else {
                    put.merge(players.get(seat), betting.act(seat, betting.timeout()), Long::sum);
                    afterBet(now, l);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ moves

    /** A player's move; null if done, else why not (a message key). */
    public String act(UUID u, Move m, long now, Listener l) {
        if (phase != Phase.PLAYING || !inHand(u)) return "hwatu.not-in-hand";
        if (!u.equals(turn())) return "hwatu.not-your-turn";
        if (step == Step.PICK) {
            if (m != Move.ODD && m != Move.EVEN) return "hwatu.not-your-turn";
            calledOdd = m == Move.ODD;
            step = Step.ROLL;
            deadline = now + spec.revealSeconds() * 1000L;
            l.changed(this);
            return null;
        }
        Betting.Action a = switch (m) {
            case DIE -> Betting.Action.DIE;
            case CALL -> Betting.Action.CALL;
            case RAISE -> Betting.Action.RAISE;
            default -> null;
        };
        if (a == null) return "hwatu.not-your-turn";
        if (a == Betting.Action.RAISE && !betting.canRaise()) return "hwatu.no-raise";
        int seat = players.indexOf(u);
        put.merge(u, betting.act(seat, a), Long::sum);
        afterBet(now, l);
        return null;
    }

    private void afterBet(long now, Listener l) {
        if (!betting.done()) {
            deadline = now + spec.turnSeconds() * 1000L;
            l.changed(this);
            return;
        }
        if (betting.active() <= 1) {
            finishUncontested(now, l);
            return;
        }
        showdown(now, l);
    }

    private void showdown(long now, Listener l) {
        List<Integer> in = betting.in();
        List<Sutda.Hand> hands = new ArrayList<>();
        for (int i : in) {
            Card[] c = cards.get(players.get(i));
            hands.add(Sutda.of(c[0], c[1]));
        }
        Sutda.Outcome o = Sutda.showdown(hands, replays < spec.game(Game.SUTDA).replays());
        if (o.replay()) {
            replays++;
            Betting next = new Betting(players.size(), ante(), spec.game(Game.SUTDA).maxRaises());
            for (int i = 0; i < players.size(); i++) if (betting.folded(i)) next.fold(i);
            deal(next, Map.of());
            betting = next;
            deadline = now + spec.turnSeconds() * 1000L;
            l.replay(this);
            l.changed(this);
            return;
        }
        Map<UUID, String> detail = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) detail.put(players.get(i), betting.folded(i) ? "die" : null);
        for (int k = 0; k < in.size(); k++) detail.put(players.get(in.get(k)), hands.get(k).code());
        List<UUID> winners = new ArrayList<>();
        for (int k : o.winners()) winners.add(players.get(in.get(k)));
        finish(winners, detail, false, now, l);
    }

    private void roll(long now, Listener l) {
        Map<UUID, String> detail = new LinkedHashMap<>();
        if (game == Game.HOLJJAK) {
            die = 1 + rng.nextInt(6);
            UUID caller = players.get(0), other = players.get(1);
            detail.put(caller, calledOdd ? "odd" : "even");
            detail.put(other, calledOdd ? "even" : "odd");
            finish(List.of(HwatuRules.callerWins(calledOdd, die) ? caller : other), detail, false, now, l);
            return;
        }
        int n = spec.game(Game.DICE).dice();
        List<UUID> in = new ArrayList<>();
        int[] totals = new int[players.size()];
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            if (out[i]) {
                detail.put(u, "die");
                continue;
            }
            int[] r = new int[n];
            for (int k = 0; k < n; k++) r[k] = 1 + rng.nextInt(6);
            rolls.put(u, r);
            in.add(u);
            int t = 0;
            StringBuilder b = new StringBuilder();
            for (int x : r) {
                t += x;
                if (!b.isEmpty()) b.append('+');
                b.append(x);
            }
            totals[in.size() - 1] = t;
            detail.put(u, b.toString());
        }
        List<UUID> winners = new ArrayList<>();
        for (int k : HwatuRules.highest(java.util.Arrays.copyOf(totals, in.size()))) winners.add(in.get(k));
        finish(winners, detail, false, now, l);
    }

    private void finishUncontested(long now, Listener l) {
        Map<UUID, String> detail = new LinkedHashMap<>();
        List<UUID> winners = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            boolean f = folded(players.get(i));
            detail.put(players.get(i), f ? "die" : null);
            if (!f) winners.add(players.get(i));
        }
        finish(winners, detail, true, now, l);
    }

    private void finish(List<UUID> winners, Map<UUID, String> detail, boolean uncontested, long now, Listener l) {
        long pot = pot();
        HwatuRules.Split s = HwatuRules.split(pot, spec.rakePercent(), winners.size());
        Map<UUID, Long> payout = new LinkedHashMap<>();
        for (UUID w : winners) payout.put(w, s.share());
        last = new Result(game, List.copyOf(players), Map.copyOf(put), Map.copyOf(payout), pot, s.burned(), List.copyOf(winners),
                Collections.unmodifiableMap(detail), die, uncontested);
        phase = Phase.RESULT;
        step = null;
        deadline = now + spec.resultSeconds() * 1000L;
        l.finished(this, last);
        l.changed(this);
    }

    /** The server stops or the table is closed mid-hand: the hand is void (nothing was moved yet). */
    public void abandon() {
        if (phase == Phase.PLAYING) {
            players.clear();
            put.clear();
        }
        phase = Phase.WAITING;
        deadline = 0;
    }
}
