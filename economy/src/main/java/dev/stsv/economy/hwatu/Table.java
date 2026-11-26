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
 * PLAYING  everyone in pays the ante (the order: clockwise round the chairs from the dealer, who moves on each hand), then
 *            홀짝:   the first player calls 홀 or 짝 (turn timer: a random call), then presses 굴리기 to shake the bowl
 *                    (timers.roll: rolled for them); the die shows for timers.reveal, then the result
 *            주사위: each player in turn presses 굴리기 to shake their cup (timers.roll: rolled for them); when all have
 *                    rolled, timers.reveal to look, then the highest total wins (ties split)
 *            섯다:   two streets: one card each, a betting round ({@link Betting}: 삥 · 체크 · 콜 · 따당 · 하프 · 다이, no
 *                    cap on the number of raises, 올인 when short; turn timer: 다이 if there is something to match, else
 *                    체크), the second card,
 *                    a second betting round (체크 again while nobody bet), showdown ({@link Sutda}) of the main pot and
 *                    each side pot ({@link Pots}); everyone but one folding ends it at once; 구사 replays with the same
 *                    pot (no new ante), dealt anew street by street
 *            세븐 포커: three cards each face down (DEAL: a pause while they fly in), each player picks one to open
 *                    (OPEN, all at once; timers.open: the lowest opens), then five betting rounds of the same
 *                    {@link Betting}, each opened by the best open cards ({@link Poker}): after the open card, after
 *                    the 4th · 5th · 6th card (open) and after the 7th (hidden); showdown of the best five of seven per
 *                    pot; everyone but one folding ends it at once
 *            바카라: the house game, from one player: BETS (baccarat.bet-seconds, ends early once everyone seated pressed
 *                    베팅 끝) where each seated player puts chips on 플레이어 · 뱅커 · 타이 ({@link #wager}); nobody bet: the
 *                    window opens again; else the dealer deals ({@link Baccarat}, DEAL: the cards fly in and flip) and
 *                    every bet is paid by the house (no 자릿세, no pots: chips come from and go to nowhere)
 *          leaving mid-hand folds (the chips put in stay in the pot); one player left wins the pot
 *          a 섯다 player's stake is every chip they hold when the hand starts (the bank's {@link Bank#stack}): they can
 *          never put in more, so chips never go below 0; and nobody puts more than the 한도 ({@link #handLimit}: ante ×
 *          hand-limit-antes) into one hand, ante, both streets and a 구사 재경기 together
 * RESULT   timers.result to read it, then the next hand
 * </pre>
 * Every pot pays the 자릿세 ({@link HwatuRules#split}); the chips are moved by the listener (nothing is taken before a hand ends,
 * so a crash voids the hand instead of losing chips).
 */
public final class Table {

    public enum Game {
        HOLJJAK("holjjak"), DICE("dice"), SUTDA("sutda"), POKER("seven_poker"), BACCARAT("baccarat");

        private final String key;

        Game(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** 섯다 · 세븐 포커: played with {@link Betting} rounds (삥 · 체크 · 콜 · 따당 · 하프 · 다이, 올인, 한도, 사이드 판). */
        public boolean bets() {
            return this == SUTDA || this == POKER;
        }

        /** 홀짝 · 주사위 · 섯다 · 세븐 포커 need two players; 바카라 is played against the house, alone too. */
        public int least() {
            return this == BACCARAT ? 1 : 2;
        }
    }

    public enum Phase { WAITING, PLAYING, RESULT }

    /**
     * PICK: 홀짝's call; ROLL: someone ({@link #turn}) is to press 굴리기 (홀짝's caller, 주사위 each player in turn);
     * SHOW: everything is rolled, shown for timers.reveal before the result; BET: a 섯다 · 세븐 포커 betting round
     * ({@link #street}); OPEN: 세븐 포커's players each pick the card to open; DEAL: cards are flying in (세븐 포커 between
     * rounds, 바카라's coup), a short pause; BETS: 바카라's betting window.
     */
    public enum Step { PICK, ROLL, SHOW, BET, OPEN, DEAL, BETS }

    /** 홀짝's call, 굴리기, 섯다 · 세븐 포커's actions, 세븐 포커's open (one of the first three cards), 바카라's 베팅 끝. */
    public enum Move { ODD, EVEN, ROLL, DIE, CHECK, PING, CALL, DDADANG, HALF, OPEN1, OPEN2, OPEN3, BAC_DONE }

    /** The 섯다 action of a move (null: 홀 · 짝). */
    public static Betting.Action action(Move m) {
        return switch (m) {
            case DIE -> Betting.Action.DIE;
            case CHECK -> Betting.Action.CHECK;
            case PING -> Betting.Action.PING;
            case CALL -> Betting.Action.CALL;
            case DDADANG -> Betting.Action.DDADANG;
            case HALF -> Betting.Action.HALF;
            default -> null;
        };
    }

    public static Move move(Betting.Action a) {
        return switch (a) {
            case DIE -> Move.DIE;
            case CHECK -> Move.CHECK;
            case PING -> Move.PING;
            case CALL -> Move.CALL;
            case DDADANG -> Move.DDADANG;
            case HALF -> Move.HALF;
        };
    }

    /** Who may be dealt in: null, or why not (a message key; that player stands up). */
    public interface Bank {
        String refusal(UUID player, long need);

        /** The chips a player dealt in can bet this hand (all they hold); a 섯다 player goes all-in at this. */
        default long stack(UUID player) {
            return 1_000_000_000L;
        }

        /** The chair a player sits in (seat index: 0 at the table's front, then clockwise): the order of play. */
        default int chair(UUID player) {
            return 0;
        }

        /** 바카라: the chips this player already bet today (05시 to 05시) at 바카라, for baccarat.daily-wager-cap. */
        default long wagered(UUID player) {
            return 0;
        }
    }

    /**
     * A 섯다 bet made: who, what, the chips it added, whether it left them all-in (올인), and whether it brought them to
     * the hand limit with chips to spare (한도).
     */
    public record Bet(UUID player, Betting.Action action, long added, boolean allIn, boolean limit) {

        public Bet(UUID player, Betting.Action action, long added, boolean allIn) {
            this(player, action, added, allIn, false);
        }
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

        /** A 섯다 player bet (or the turn timer did for them); changed() follows. */
        default void bet(Table t, Bet b) {}

        /** 섯다: the second card was dealt (a new betting round); changed() follows. */
        default void street(Table t) {}

        /** 주사위 · 홀짝: this player rolled (pressed 굴리기, or the timer did for them); changed() follows. */
        default void rolled(Table t, UUID player) {}

        /** 세븐 포커: this player opened a card (or the timer did); changed() follows. */
        default void opened(Table t, UUID player) {}

        /** 세븐 포커: a street was dealt (the cards are coming); 바카라: the coup was dealt. changed() follows. */
        default void dealt(Table t) {}

        /** 바카라: a bet was placed on a spot; changed() follows. */
        default void wagered(Table t, UUID player, Baccarat.Spot spot, long amount) {}

        /** 바카라: the betting window opened (again). */
        default void betsOpen(Table t) {}
    }

    /** One pot of a finished hand (섯다 may have side pots): its chips, its winners, each one's share, what burned. */
    public record PotResult(long amount, List<UUID> winners, long share, long burned) {}

    /**
     * A finished hand. put: what each put in (ante + bets), payout: what each gets back (winners only), burned: the 자릿세 and
     * the odd chips of a split (destroyed). detail: per player what they had (섯다 hand code, 주사위 "a+b", 홀짝 the call
     * "odd"/"even" for the caller), "die" for a player who folded or left. uncontested: everyone else folded or left.
     * pots: the main pot first, then 섯다's side pots; winners: everyone who won a pot (the main pot's first). put is
     * after the chips nobody matched went back ({@link Pots#uncalled}), so pot = the sum of put. 세븐 포커's detail: the
     * hand's code and its ranks ("two-pair|K·7"). 바카라: pot is every bet, payout what the house paid back (more than was
     * put in for a win: chips made), burned 0, no pots, detail the bets ("P100,B0,T10"), coup the cards.
     */
    public record Result(Game game, List<UUID> players, Map<UUID, Long> put, Map<UUID, Long> payout, long pot, long burned,
                         List<UUID> winners, Map<UUID, String> detail, int die, boolean uncontested, List<PotResult> pots,
                         Baccarat.Coup coup) {

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
    private final Map<UUID, Long> stake = new HashMap<>();
    private Bet lastBet;
    private boolean[] out = new boolean[0];
    /** 섯다: the cards on the table (one after the first deal, two after the second), and both cards of each player. */
    private final Map<UUID, Card[]> cards = new HashMap<>();
    private final Map<UUID, Card[]> hole = new HashMap<>();
    private int street, deals, hands;
    private final Map<UUID, int[]> rolls = new HashMap<>();
    private Boolean calledOdd;
    private int die;
    private Betting betting;
    private int replays;
    private Step step;
    private Result last;
    /** 시험 판 (/관리자 도박장 시험 섯다 패): the cards some players get in the next 섯다 hand's first deal. */
    private final Map<UUID, Card[]> forced = new LinkedHashMap<>();
    /** 세븐 포커: each player's cards in the order dealt (0 1 2 face down, one of them opened, 3 4 5 open, 6 hidden) */
    private final Map<UUID, List<PCard>> pcards = new HashMap<>();
    /** 세븐 포커: which of the first three cards each player opened */
    private final Map<UUID, Integer> opened = new HashMap<>();
    private List<PCard> pdeck = new ArrayList<>();
    /** 세븐 포커: what comes when the DEAL pause is over (OPEN or BET) */
    private Step afterDeal;
    /** 바카라: each player's bets this round (index = {@link Baccarat.Spot#ordinal()}), who pressed 베팅 끝, the cards */
    private final Map<UUID, long[]> bets = new LinkedHashMap<>();
    private final java.util.Set<UUID> done = new java.util.HashSet<>();
    private Baccarat.Shoe shoe;
    private Baccarat.Coup coup;
    private boolean shuffled;
    private String road = "";
    private int coups;

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
    /** 섯다: 1 after the first card, 2 after the second (0: no 섯다 hand). */
    public int street() { return street; }
    /** How many times cards were dealt to a fresh deck (a new hand or a 구사 재경기): a change means new cards. */
    public int deals() { return deals; }
    /** How many hands this table has started (a change means a new hand). */
    public int hands() { return hands; }
    /** The last 섯다 bet of this round (null: none yet). */
    public Bet lastBet() { return lastBet; }

    public int max() {
        return spec.game(game).max();
    }

    /** 바카라: the road (the last results, oldest first: P B T). */
    public String road() {
        return road;
    }

    /** 바카라: the road as it was stored (a restart keeps the table's last results). */
    public void road(String r) {
        if (r == null) return;
        StringBuilder b = new StringBuilder();
        for (char c : r.toCharArray()) if (Baccarat.Spot.of(c) != null) b.append(c);
        int keep = spec.baccarat().road();
        road = b.length() > keep ? b.substring(b.length() - keep) : b.toString();
    }

    /** 바카라: the cards of the coup being dealt or just paid (null: none yet this round). */
    public Baccarat.Coup coup() {
        return coup;
    }

    /** 바카라: how many coups this table dealt (a change: new cards). */
    public int coups() {
        return coups;
    }

    /** 바카라: this coup came out of a fresh shoe (the cut card came). */
    public boolean shuffled() {
        return shuffled;
    }

    /** 바카라: a player's bets this round, by spot (zeros if none). */
    public long[] bets(UUID u) {
        long[] b = bets.get(u);
        return b == null ? new long[3] : b.clone();
    }

    /** 바카라: everyone's bets this round, in the order they first bet. */
    public Map<UUID, long[]> allBets() {
        Map<UUID, long[]> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, long[]> e : bets.entrySet()) out.put(e.getKey(), e.getValue().clone());
        return out;
    }

    /** 바카라: this player pressed 베팅 끝 (or cannot bet more) this round. */
    public boolean betsDone(UUID u) {
        return done.contains(u);
    }

    /**
     * 바카라: what this player may still bet this round: the least of what the round's 최대 베팅 leaves, what today's cap
     * leaves (`wageredToday`: bet before this round today) and their chips (`stack`, read when they first bet: all they
     * hold then).
     */
    public long betRoom(UUID u, long wageredToday, long stack) {
        HwatuSpec.BaccaratSpec b = spec.baccarat();
        long mine = put(u);
        long s = stake.getOrDefault(u, stack);
        return Math.max(0, Math.min(b.maxBet() - mine, Math.min(b.dailyCap() - wageredToday - mine, s - mine)));
    }

    /** 세븐 포커: the player's cards so far in the order dealt (empty if none). */
    public List<PCard> pcards(UUID u) {
        List<PCard> c = pcards.get(u);
        return c == null ? List.of() : List.copyOf(c);
    }

    /** 세븐 포커: which of the first three cards the player opened (-1: not yet). */
    public int openedIndex(UUID u) {
        Integer i = opened.get(u);
        return i == null ? -1 : i;
    }

    /** 세븐 포커: whether card k of a player is open to everyone (the opened one of the first three, the 4th ~ 6th). */
    public boolean isOpen(UUID u, int k) {
        if (k >= 3 && k <= 5) return true;
        return k < 3 && k == openedIndex(u);
    }

    /** 세븐 포커: the player's open cards (what everyone sees), in the order they came. */
    public List<PCard> openCards(UUID u) {
        List<PCard> c = pcards.get(u), out = new ArrayList<>();
        if (c == null) return out;
        for (int k = 0; k < c.size(); k++) if (isOpen(u, k)) out.add(c.get(k));
        return out;
    }

    /** 세븐 포커: the best hand of all the player's cards (what they hold, seen by them alone), or null. */
    public Poker.Hand pokerHand(UUID u) {
        List<PCard> c = pcards.get(u);
        return c == null || c.isEmpty() ? null : Poker.best(c);
    }

    /** 세븐 포커: what the player's open cards show (decides who bets first), or null. */
    public Poker.Hand showing(UUID u) {
        List<PCard> c = openCards(u);
        return c.isEmpty() ? null : Poker.best(c);
    }

    /** 세븐 포커: who opens the betting round: the player still in with the best open cards (players index; 0 if none). */
    public int opener() {
        List<Poker.Hand> hands = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            boolean in = betting == null || !betting.folded(i);
            hands.add(in && !out[i] ? showing(u) : null);
        }
        int b = Poker.bestOf(hands);
        return Math.max(0, b);
    }

    public boolean full() {
        return seats.size() >= max();
    }

    public long ante() {
        return room.ante();
    }

    /**
     * The most one player may put into one hand here (ante, every street, a 구사 재경기): ante × hand-limit-antes
     * ({@link Betting#MOST}: no limit). Only 섯다 can reach it (홀짝 · 주사위 put in the ante alone).
     */
    public long handLimit() {
        int k = spec.handLimitAntes();
        if (k <= 0) return Betting.MOST;
        return Math.min(Betting.MOST, ante() * k);
    }

    /** In the running 섯다 · 세븐 포커 hand with chips left but nothing more to put in: at the hand limit (한도). */
    public boolean atLimit(UUID u) {
        return game.bets() && inHand(u) && left(u) > 0 && put(u) >= handLimit();
    }

    /** The running 섯다 · 세븐 포커 hand reached the limit: someone still in put in the 한도, so nobody can raise any more. */
    public boolean limitReached() {
        if (!game.bets() || phase != Phase.PLAYING) return false;
        long cap = handLimit();
        for (UUID u : players) if (inHand(u) && put(u) >= cap) return true;
        return false;
    }

    /** Chips a player must hold to be dealt in here. */
    public long need() {
        return spec.need(game, room);
    }

    /**
     * 시험 판 only: deals this player these two cards in the next 섯다 hand (its first deal, not a 구사 재경기): `a` on the
     * first street, `b` on the second. false (nothing changes) if the two are the same card or a card another forced
     * player already gets.
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
        if (game.bets()) return betting == null ? !out[i] : !betting.folded(i);
        return game == Game.BACCARAT || !out[i];
    }

    /** Folded or left in this hand (also shown during the result). */
    public boolean folded(UUID u) {
        int i = players.indexOf(u);
        if (i < 0) return false;
        if (game == Game.BACCARAT) return false;
        if (game.bets()) return betting != null ? betting.folded(i) : out.length > i && out[i];
        return out.length > i && out[i];
    }

    /** What a player put in this hand (0 if not in it). */
    public long put(UUID u) {
        return put.getOrDefault(u, 0L);
    }

    /**
     * Chips held by the running hand (not yet won or lost, not to be cashed): what was put in, and in 섯다 · 세븐 포커 ·
     * 바카라 the whole stake (it may all be bet).
     */
    public long committed(UUID u) {
        if (phase != Phase.PLAYING) return 0;
        return game.bets() || game == Game.BACCARAT ? Math.max(put(u), stake.getOrDefault(u, 0L)) : put(u);
    }

    /** What a 섯다 · 세븐 포커 player can still put in this hand (0: all-in or not in it). */
    public long left(UUID u) {
        if (phase != Phase.PLAYING || !stake.containsKey(u)) return 0;
        return Math.max(0, stake.get(u) - put(u));
    }

    /** In the running 섯다 · 세븐 포커 hand with nothing left to bet. */
    public boolean allIn(UUID u) {
        return game.bets() && inHand(u) && left(u) == 0;
    }

    public long pot() {
        long p = 0;
        for (long v : put.values()) p += v;
        return p;
    }

    /** 섯다: the player's cards dealt so far (one on the first street, two on the second), or null. */
    public Card[] cards(UUID u) {
        return cards.get(u);
    }

    /** 섯다: the player's two cards when both are dealt (their hand), else null. */
    public Sutda.Hand hand(UUID u) {
        Card[] c = cards.get(u);
        return c == null || c.length < 2 ? null : Sutda.of(c[0], c[1]);
    }

    /** How long the current move may take (seconds): 굴리기 has its own timer (timers.roll), opening timers.open, 바카라's bets baccarat.bet-seconds, the rest timers.turn. */
    public int moveSeconds() {
        if (step == Step.ROLL) return spec.rollSeconds();
        if (step == Step.OPEN) return spec.openSeconds();
        if (step == Step.BETS) return spec.baccarat().betSeconds();
        return spec.turnSeconds();
    }

    public int[] rolls(UUID u) {
        return rolls.get(u);
    }

    /** Whose move it is (홀짝's caller, who rolls next, 섯다's turn), or null. */
    public UUID turn() {
        if (phase != Phase.PLAYING) return null;
        if (step == Step.PICK) return players.getFirst();
        if (step == Step.ROLL) return nextRoller();
        if (step == Step.BET && betting.turn() >= 0) return players.get(betting.turn());
        return null;
    }

    /** 홀짝: the caller (until the die is rolled); 주사위: the first player in order still in who has not rolled. */
    private UUID nextRoller() {
        if (game == Game.HOLJJAK) return die == 0 && !out[0] ? players.getFirst() : null;
        for (int i = 0; i < players.size(); i++) if (!out[i] && !rolls.containsKey(players.get(i))) return players.get(i);
        return null;
    }

    // ------------------------------------------------------------------------------------------ seats

    /** Sits down (to wait for the next hand if one is running; 바카라: to bet in the open window); false if full or already seated. */
    public boolean sit(UUID u, long now) {
        if (full() || seats.contains(u)) return false;
        seats.add(u);
        if (game == Game.BACCARAT) {
            if (phase == Phase.WAITING) deadline = now;   // the window opens on the next tick
            return true;
        }
        if (phase == Phase.WAITING && seats.size() >= 2 && deadline == 0) deadline = now + spec.startSeconds() * 1000L;
        return true;
    }

    /** Stands up; in a running hand that is a fold (what was put in stays in the pot). */
    public void leave(UUID u, long now, Listener l) {
        seats.remove(u);
        if (phase == Phase.WAITING && seats.size() < game.least()) deadline = 0;
        if (game == Game.BACCARAT) {
            done.remove(u);
            if (phase == Phase.PLAYING && step == Step.BETS) {   // the window is open: their bets come back (nothing moved yet)
                int i = players.indexOf(u);
                if (i >= 0) {
                    players.remove(i);
                    put.remove(u);
                    stake.remove(u);
                    bets.remove(u);
                }
                if (seats.isEmpty() && players.isEmpty()) {
                    phase = Phase.WAITING;
                    step = null;
                    deadline = 0;
                } else if (everyoneDone()) closeBets(now, l);
            }
            // once dealt, their bets stay and are paid like everyone's
            l.changed(this);
            return;
        }
        if (inHand(u)) {
            int i = players.indexOf(u);
            if (game.bets() && betting != null) {
                betting.fold(i);
                if (step == Step.BET) afterBet(now, l);
                else if (betting.active() <= 1) finishUncontested(now, l);
            } else if (game.bets()) {   // 세븐 포커 before the first betting round (dealing · opening)
                out[i] = true;
                if (active() <= 1) finishUncontested(now, l);
                else if (step == Step.OPEN && everyoneOpened()) firstRound(now, l);
            } else {
                UUID before = turn();
                out[i] = true;
                if (active() <= 1) finishUncontested(now, l);
                else if (step == Step.ROLL && u.equals(before)) afterRoll(now, l);   // the next one rolls (a fresh timer)
            }
        }
        l.changed(this);
    }

    public boolean empty() {
        return seats.isEmpty() && phase != Phase.PLAYING;
    }

    private int active() {
        if (game.bets() && betting != null) return betting.active();
        int a = 0;
        for (boolean o : out) if (!o) a++;
        return a;
    }

    // ------------------------------------------------------------------------------------------ time

    /** Called about once a second. */
    public void tick(long now, Bank bank, Listener l) {
        switch (phase) {
            case WAITING -> {
                if (game == Game.BACCARAT) {
                    if (seats.isEmpty()) {
                        deadline = 0;
                        return;
                    }
                    openBets(now, l);
                    l.changed(this);
                    return;
                }
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
                ok.sort(java.util.Comparator.comparingInt(bank::chair));   // clockwise round the table
                start(ok, now, bank, l);
                l.changed(this);
            }
            case PLAYING -> {
                if (now >= deadline) timeout(now, l);
            }
            case RESULT -> {
                if (now < deadline) return;
                phase = Phase.WAITING;
                deadline = seats.size() >= game.least() ? now : 0;   // the result was the pause: the next hand starts on the next tick
                l.changed(this);
            }
        }
    }

    private void start(List<UUID> ok, long now, Bank bank, Listener l) {
        hands++;
        players.clear();
        put.clear();
        stake.clear();
        lastBet = null;
        cards.clear();
        hole.clear();
        street = 0;
        rolls.clear();
        calledOdd = null;
        die = 0;
        replays = 0;
        betting = null;
        last = null;
        pcards.clear();
        opened.clear();
        afterDeal = null;
        int shift = Math.floorMod(dealer++, ok.size());
        for (int k = 0; k < ok.size(); k++) players.add(ok.get((shift + k) % ok.size()));
        for (UUID u : players) {
            long s = Math.clamp(bank.stack(u), 0, Betting.MOST);
            stake.put(u, s);
            put.put(u, Math.min(ante(), s));
        }
        out = new boolean[players.size()];
        phase = Phase.PLAYING;
        switch (game) {
            case HOLJJAK -> {
                step = Step.PICK;
                deadline = now + spec.turnSeconds() * 1000L;
            }
            case DICE -> {
                step = Step.ROLL;
                deadline = now + spec.rollSeconds() * 1000L;
            }
            case SUTDA -> {
                deal(null, forced);
                forced.clear();
                betting = round(null);
                step = Step.BET;
                deadline = now + spec.turnSeconds() * 1000L;
                if (betting.done()) afterBet(now, l);   // nobody can bet (all-in on the ante): the second card, the showdown
            }
            case POKER -> {
                pdeck = PCard.deck();
                Collections.shuffle(pdeck, rng);
                deals++;
                street = 3;
                for (int k = 0; k < 3; k++) for (UUID u : players) pcards.computeIfAbsent(u, x -> new ArrayList<>()).add(pdeck.removeLast());
                dealPause(Step.OPEN, 3 * players.size(), now);
                l.dealt(this);
            }
            case BACCARAT -> throw new IllegalStateException("바카라 starts with openBets");
        }
    }

    // ------------------------------------------------------------------------------------------ 세븐 포커

    /** How long cards take to fly in (millis): a little for the dealer, then each card. */
    public static final long DEAL_BASE_MS = 500, DEAL_CARD_MS = 200;

    /** The dealer is dealing `cards` cards: a pause, then `next`. */
    private void dealPause(Step next, int cards, long now) {
        step = Step.DEAL;
        afterDeal = next;
        deadline = now + DEAL_BASE_MS + DEAL_CARD_MS * cards;   // the open buttons · the round come once the cards are down
    }

    private boolean everyoneOpened() {
        for (int i = 0; i < players.size(); i++) if (!out[i] && !opened.containsKey(players.get(i))) return false;
        return true;
    }

    /** Everyone still in opened a card (or the timer opened it): the first betting round, opened by the best open card. */
    private void firstRound(long now, Listener l) {
        betting = null;   // who left before betting is out[] (carried into the rounds as a fold)
        pokerRound(now, l);
    }

    /** A betting round of 세븐 포커, opened by the best open cards (folds carry over). */
    private void pokerRound(long now, Listener l) {
        Betting prev = betting;
        int first = opener();
        long[] left = new long[players.size()], most = new long[players.size()];
        boolean[] gone = new boolean[players.size()];
        long cap = handLimit();
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            left[i] = Math.max(0, stake.get(u) - put(u));
            most[i] = Math.max(0, cap - put(u));
            gone[i] = (prev != null && prev.folded(i)) || out[i];
        }
        betting = new Betting(ante(), pot(), left, gone, most, first);
        lastBet = null;
        step = Step.BET;
        deadline = now + spec.turnSeconds() * 1000L;
        l.street(this);
        l.changed(this);
        if (betting.done()) afterBet(now, l);   // everyone left is all-in: the next card, the showdown
    }

    /** The next card to everyone still in (4th ~ 6th open, 7th hidden), from the opener round the table, then a pause. */
    private void nextStreet(long now, Listener l) {
        street++;
        int n = 0;
        for (int i = 0; i < players.size(); i++) {
            if (betting.folded(i)) continue;
            pcards.get(players.get(i)).add(pdeck.removeLast());
            n++;
        }
        dealPause(Step.BET, n, now);
        l.dealt(this);
        l.changed(this);
    }

    /** 세븐 포커's showdown: the best five of seven of each player still in, pot by pot. */
    private void pokerShowdown(long now, Listener l) {
        Map<UUID, String> detail = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            if (betting.folded(i)) detail.put(u, "die");
            else {
                Poker.Hand h = pokerHand(u);
                detail.put(u, h.code() + "|" + h.ranksText());
            }
        }
        settleBets(detail, false, now, l);
    }

    // ------------------------------------------------------------------------------------------ 바카라

    /** 바카라: the betting window opens (a new round): nobody has bet. */
    private void openBets(long now, Listener l) {
        hands++;
        players.clear();
        put.clear();
        stake.clear();
        bets.clear();
        done.clear();
        coup = null;
        shuffled = false;
        last = null;
        lastBet = null;
        out = new boolean[0];
        phase = Phase.PLAYING;
        step = Step.BETS;
        deadline = now + spec.baccarat().betSeconds() * 1000L;
        l.betsOpen(this);
    }

    /**
     * 바카라: a seated player puts `amount` chips on a spot (one press of 플레이어 · 뱅커 · 타이 with the picked chip). Null,
     * or why not (a message key): not the betting window, under the 최소 베팅, past the round's 최대 베팅, past today's cap
     * (baccarat.daily-wager-cap: what they bet today before this round is the bank's {@link Bank#wagered}), more than
     * their chips, or they may not play (the bank's refusal: too new, resting, too few chips).
     */
    public String wager(UUID u, Baccarat.Spot spot, long amount, long now, Bank bank, Listener l) {
        if (game != Game.BACCARAT || phase != Phase.PLAYING || step != Step.BETS) return "hwatu.bac.closed";
        if (!seats.contains(u)) return "hwatu.not-in-hand";
        HwatuSpec.BaccaratSpec b = spec.baccarat();
        if (amount < b.minBet()) return "hwatu.bac.min";
        boolean first = !players.contains(u);
        if (first) {
            String why = bank.refusal(u, b.minBet());
            if (why != null) return why;
        }
        long stack = first ? Math.clamp(bank.stack(u), 0, Betting.MOST) : stake.get(u);
        long mine = put(u), today = bank.wagered(u);
        if (mine + amount > b.maxBet()) return "hwatu.bac.over-max";
        if (today + mine + amount > b.dailyCap()) return "hwatu.bac.over-cap";
        if (mine + amount > stack) return "hwatu.bac.short";
        if (first) {
            players.add(u);
            stake.put(u, stack);
        }
        done.remove(u);
        bets.computeIfAbsent(u, k -> new long[3])[spot.ordinal()] += amount;
        put.merge(u, amount, Long::sum);
        l.wagered(this, u, spot, amount);
        if (betRoom(u, today, stack) < b.minBet()) done.add(u);   // nothing more can go on: as good as 베팅 끝
        if (everyoneDone()) closeBets(now, l);
        else l.changed(this);
        return null;
    }

    /** Every seated player pressed 베팅 끝 (or can bet no more), and someone bet. */
    private boolean everyoneDone() {
        if (players.isEmpty()) return false;
        for (UUID u : seats) if (!done.contains(u)) return false;
        return true;
    }

    /** 바카라: no more bets. Nobody bet: the window opens again; else the dealer deals the coup. */
    private void closeBets(long now, Listener l) {
        if (players.isEmpty()) {
            if (seats.isEmpty()) {
                phase = Phase.WAITING;
                step = null;
                deadline = 0;
                l.changed(this);
                return;
            }
            openBets(now, l);
            l.changed(this);
            return;
        }
        if (shoe == null) shoe = new Baccarat.Shoe(rng);
        shuffled = shoe.ready();
        coup = Baccarat.deal(shoe);
        coups++;
        deals++;
        out = new boolean[players.size()];
        step = Step.DEAL;
        deadline = now + dealMillis(coup);
        l.dealt(this);
        l.changed(this);
    }

    /** How long a coup takes to deal and turn over (millis): each card slides in and flips, then the totals. */
    public static long dealMillis(Baccarat.Coup c) {
        return BAC_DEAL_BASE_MS + BAC_CARD_MS * (c.player().size() + c.banker().size());
    }

    /** 바카라's pacing (millis): before the first card, then each card's slide and flip (TableScene follows it). */
    public static final long BAC_DEAL_BASE_MS = 1500, BAC_CARD_MS = 1000;

    /** 바카라: the coup is face up; the house pays every bet (or takes it). */
    private void settleBaccarat(long now, Listener l) {
        HwatuSpec.BaccaratSpec b = spec.baccarat();
        Baccarat.Spot won = coup.winner();
        Map<UUID, Long> payout = new LinkedHashMap<>();
        Map<UUID, String> detail = new LinkedHashMap<>();
        List<UUID> winners = new ArrayList<>();
        long pot = 0;
        for (UUID u : players) {
            long[] mine = bets.get(u);
            long back = 0;
            for (Baccarat.Spot s : Baccarat.Spot.values()) back += Baccarat.payout(s, mine[s.ordinal()], won, b.commission(), b.tiePays());
            if (back > 0) payout.put(u, back);
            if (back > put(u)) winners.add(u);
            pot += put(u);
            detail.put(u, "P" + mine[0] + ",B" + mine[1] + ",T" + mine[2]);
        }
        road = Baccarat.road(road, won, b.road());
        last = new Result(game, List.copyOf(players), Map.copyOf(put), Map.copyOf(payout), pot, 0, List.copyOf(winners),
                Collections.unmodifiableMap(detail), 0, false, List.of(), coup);
        phase = Phase.RESULT;
        step = null;
        deadline = now + spec.resultSeconds() * 1000L;
        l.finished(this, last);
        l.changed(this);
    }

    /**
     * A new betting round for the players still in (prev: the round before, whose folds carry over); each may put in what
     * the hand limit leaves after what they put in so far.
     */
    private Betting round(Betting prev) {
        if (game == Game.POKER) throw new IllegalStateException("세븐 포커: pokerRound");
        long[] left = new long[players.size()], most = new long[players.size()];
        boolean[] out = new boolean[players.size()];
        long cap = handLimit();
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            left[i] = Math.max(0, stake.get(u) - put(u));
            most[i] = Math.max(0, cap - put(u));
            out[i] = prev != null && prev.folded(i);
        }
        return new Betting(ante(), pot(), left, out, most);
    }

    /**
     * A fresh shuffled deck: two cards each to the players still in (folded: none), of which the first is on the table
     * (street 1) and the second comes with {@link #secondStreet}; `fixed`: these players' cards, in that order.
     */
    private void deal(Betting folds, Map<UUID, Card[]> fixed) {
        List<Card> deck = Card.deck();
        Collections.shuffle(deck, rng);
        cards.clear();
        hole.clear();
        street = 1;
        deals++;
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
            Card[] two = mine != null ? mine.clone() : new Card[]{deck.get(k++), deck.get(k++)};
            hole.put(u, two);
            cards.put(u, new Card[]{two[0]});
        }
    }

    /** The first street's betting is over with two or more still in: each of them gets their second card, a new round. */
    private void secondStreet(long now, Listener l) {
        street = 2;
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            if (betting.folded(i) || !hole.containsKey(u)) continue;
            cards.put(u, hole.get(u).clone());
        }
        betting = round(betting);
        lastBet = null;
        deadline = now + spec.turnSeconds() * 1000L;
        l.street(this);
        l.changed(this);
        if (betting.done()) afterBet(now, l);   // everyone left is all-in: straight to the showdown
    }

    private void timeout(long now, Listener l) {
        switch (step) {
            case PICK -> {
                calledOdd = rng.nextBoolean();
                step = Step.ROLL;
                deadline = now + spec.rollSeconds() * 1000L;
                l.changed(this);
            }
            case ROLL -> {
                UUID u = turn();
                if (u == null) afterRoll(now, l);
                else roll(u, now, l);
            }
            case SHOW -> settleRolls(now, l);
            case OPEN -> {   // the timer opens the lowest card of whoever did not pick
                for (int i = 0; i < players.size(); i++) {
                    UUID u = players.get(i);
                    if (out[i] || opened.containsKey(u)) continue;
                    opened.put(u, Poker.lowest(pcards.get(u).subList(0, 3)));
                    l.opened(this, u);
                }
                firstRound(now, l);
            }
            case DEAL -> {
                if (game == Game.BACCARAT) settleBaccarat(now, l);
                else if (afterDeal == Step.OPEN) {
                    step = Step.OPEN;
                    deadline = now + spec.openSeconds() * 1000L;
                    l.changed(this);
                } else pokerRound(now, l);
            }
            case BETS -> closeBets(now, l);
            case BET -> {
                int seat = betting.turn();
                if (seat < 0) afterBet(now, l);
                else bet(seat, betting.timeout(), now, l);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ moves

    /** A player's move; null if done, else why not (a message key). */
    public String act(UUID u, Move m, long now, Listener l) {
        if (game == Game.BACCARAT) {
            if (m != Move.BAC_DONE) return "hwatu.bad-bet";
            if (phase != Phase.PLAYING || step != Step.BETS) return "hwatu.bac.closed";
            if (!seats.contains(u)) return "hwatu.not-in-hand";
            done.add(u);
            if (everyoneDone() || (seats.stream().allMatch(done::contains) && players.isEmpty())) closeBets(now, l);
            else l.changed(this);
            return null;
        }
        if (phase != Phase.PLAYING || !inHand(u)) return "hwatu.not-in-hand";
        if (step == Step.OPEN) {
            int k = m == Move.OPEN1 ? 0 : m == Move.OPEN2 ? 1 : m == Move.OPEN3 ? 2 : -1;
            if (k < 0) return "hwatu.not-your-turn";
            if (opened.containsKey(u)) return "hwatu.poker.opened-already";
            opened.put(u, k);
            l.opened(this, u);
            if (everyoneOpened()) firstRound(now, l);
            else l.changed(this);
            return null;
        }
        if (!u.equals(turn())) return "hwatu.not-your-turn";
        if (step == Step.PICK) {
            if (m != Move.ODD && m != Move.EVEN) return "hwatu.not-your-turn";
            calledOdd = m == Move.ODD;
            step = Step.ROLL;
            deadline = now + spec.rollSeconds() * 1000L;
            l.changed(this);
            return null;
        }
        if (step == Step.ROLL) {
            if (m != Move.ROLL) return "hwatu.not-your-turn";
            roll(u, now, l);
            return null;
        }
        Betting.Action a = action(m);
        if (a == null) return "hwatu.not-your-turn";
        int seat = players.indexOf(u);
        if (!betting.legal(seat).contains(a)) return "hwatu.bad-bet";
        bet(seat, a, now, l);
        return null;
    }

    /** What the seat may do now (empty when it is not their turn or no 섯다 round runs). */
    public java.util.Set<Betting.Action> legal(UUID u) {
        int seat = players.indexOf(u);
        if (betting != null && seat >= betting.players()) return java.util.EnumSet.noneOf(Betting.Action.class);
        if (phase != Phase.PLAYING || step != Step.BET || betting == null || seat < 0) return java.util.EnumSet.noneOf(Betting.Action.class);
        return betting.legal(seat);
    }

    private void bet(int seat, Betting.Action a, long now, Listener l) {
        UUID u = players.get(seat);
        long added = betting.act(seat, a);
        put.merge(u, added, Long::sum);
        boolean allIn = a != Betting.Action.DIE && added > 0 && left(u) == 0;
        lastBet = new Bet(u, a, added, allIn, a != Betting.Action.DIE && added > 0 && !allIn && put(u) >= handLimit());
        l.bet(this, lastBet);
        afterBet(now, l);
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
        if (game == Game.POKER) {
            if (street < 7) nextStreet(now, l);
            else pokerShowdown(now, l);
            return;
        }
        if (street < 2) secondStreet(now, l);
        else showdown(now, l);
    }

    private void showdown(long now, Listener l) {
        List<Integer> in = betting.in();
        List<Sutda.Hand> hands = new ArrayList<>();
        for (int i : in) {
            Card[] c = hole.get(players.get(i));
            hands.add(Sutda.of(c[0], c[1]));
        }
        Sutda.Outcome o = Sutda.showdown(hands, replays < spec.game(Game.SUTDA).replays());
        if (o.replay()) {   // 구사: dealt anew, one card, a betting round, the second card, a betting round
            replays++;
            Betting next = round(betting);
            deal(next, Map.of());
            betting = next;
            lastBet = null;
            deadline = now + spec.turnSeconds() * 1000L;
            l.replay(this);
            l.changed(this);
            if (next.done()) afterBet(now, l);   // everyone left is all-in: the new cards go straight to the showdown
            return;
        }
        Map<UUID, String> detail = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) detail.put(players.get(i), betting.folded(i) ? "die" : null);
        for (int k = 0; k < in.size(); k++) detail.put(players.get(in.get(k)), hands.get(k).code());
        settleBets(detail, false, now, l);
    }

    /**
     * A 섯다 · 세븐 포커 hand ends: the chips nobody matched go back, then the main pot and each side pot is won by the
     * best hand among those who may win it ({@link Sutda#showdown} on them alone, no 재경기; {@link Poker#winners}), each
     * pot paying its own 자릿세.
     */
    private void settleBets(Map<UUID, String> detail, boolean uncontested, long now, Listener l) {
        int n = players.size();
        long[] c = new long[n];
        boolean[] folded = new boolean[n];
        for (int i = 0; i < n; i++) {
            c[i] = put(players.get(i));
            folded[i] = betting != null ? betting.folded(i) : out[i];
        }
        long[] back = Pots.uncalled(c);
        for (int i = 0; i < n; i++) {
            if (back[i] == 0) continue;
            c[i] -= back[i];
            put.put(players.get(i), c[i]);
        }
        List<PotResult> results = new ArrayList<>();
        for (Pots.Pot p : Pots.of(c, folded)) {
            List<Integer> e = p.eligible();
            List<UUID> won = new ArrayList<>();
            if (e.size() == 1) won.add(players.get(e.getFirst()));
            else if (e.size() > 1 && game == Game.POKER) {
                List<Poker.Hand> hands = new ArrayList<>();
                for (int i : e) hands.add(pokerHand(players.get(i)));
                for (int k : Poker.winners(hands)) won.add(players.get(e.get(k)));
            } else if (e.size() > 1) {
                List<Sutda.Hand> hands = new ArrayList<>();
                for (int i : e) {
                    Card[] cs = hole.get(players.get(i));
                    hands.add(Sutda.of(cs[0], cs[1]));
                }
                for (int k : Sutda.showdown(hands, false).winners()) won.add(players.get(e.get(k)));
            }
            HwatuRules.Split s = HwatuRules.split(p.amount(), spec.rakePercent(), won.size());
            results.add(new PotResult(p.amount(), List.copyOf(won), s.share(), s.burned()));
        }
        finishPots(results, detail, uncontested, now, l);
    }

    /** 굴리기: 홀짝's die under the bowl, or this player's dice under their cup. */
    private void roll(UUID u, long now, Listener l) {
        if (game == Game.HOLJJAK) die = 1 + rng.nextInt(6);
        else {
            int[] r = new int[spec.game(Game.DICE).dice()];
            for (int k = 0; k < r.length; k++) r[k] = 1 + rng.nextInt(6);
            rolls.put(u, r);
        }
        l.rolled(this, u);
        afterRoll(now, l);
    }

    /** After a roll (or the roller left): the next one rolls with a fresh timer, or everything shows for timers.reveal. */
    private void afterRoll(long now, Listener l) {
        if (turn() != null) deadline = now + spec.rollSeconds() * 1000L;
        else {
            step = Step.SHOW;
            deadline = now + spec.revealSeconds() * 1000L;
        }
        l.changed(this);
    }

    /** The rolls have been looked at: 홀짝's call against the die, or the highest dice total. */
    private void settleRolls(long now, Listener l) {
        Map<UUID, String> detail = new LinkedHashMap<>();
        if (game == Game.HOLJJAK) {
            UUID caller = players.get(0), other = players.get(1);
            detail.put(caller, calledOdd ? "odd" : "even");
            detail.put(other, calledOdd ? "even" : "odd");
            finish(List.of(HwatuRules.callerWins(calledOdd, die) ? caller : other), detail, false, now, l);
            return;
        }
        List<UUID> in = new ArrayList<>();
        int[] totals = new int[players.size()];
        for (int i = 0; i < players.size(); i++) {
            UUID u = players.get(i);
            int[] r = rolls.get(u);
            if (out[i] || r == null) {
                detail.put(u, "die");
                continue;
            }
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
        if (game.bets()) {
            settleBets(detail, true, now, l);
            return;
        }
        finish(winners, detail, true, now, l);
    }

    /** 홀짝 · 주사위: one pot, split among the winners. */
    private void finish(List<UUID> winners, Map<UUID, String> detail, boolean uncontested, long now, Listener l) {
        long pot = pot();
        HwatuRules.Split s = HwatuRules.split(pot, spec.rakePercent(), winners.size());
        finishPots(List.of(new PotResult(pot, List.copyOf(winners), s.share(), s.burned())), detail, uncontested, now, l);
    }

    private void finishPots(List<PotResult> pots, Map<UUID, String> detail, boolean uncontested, long now, Listener l) {
        long pot = pot(), burned = 0;
        Map<UUID, Long> payout = new LinkedHashMap<>();
        List<UUID> winners = new ArrayList<>();
        for (PotResult p : pots) {
            burned += p.burned();
            for (UUID w : p.winners()) {
                payout.merge(w, p.share(), Long::sum);
                if (!winners.contains(w)) winners.add(w);
            }
        }
        last = new Result(game, List.copyOf(players), Map.copyOf(put), Map.copyOf(payout), pot, burned, List.copyOf(winners),
                Collections.unmodifiableMap(detail), die, uncontested, List.copyOf(pots), null);
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
            stake.clear();
        }
        if (game == Game.BACCARAT) {
            players.clear();
            put.clear();
            stake.clear();
            bets.clear();
            done.clear();
            coup = null;
        }
        lastBet = null;
        street = 0;
        step = null;
        phase = Phase.WAITING;
        deadline = 0;
    }
}
