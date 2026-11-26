package dev.stsv.economy.hwatu;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The buttons that float on the table in front of a seat (README "도박장"), seen and clicked only by whoever sits there:
 * which ones show for the state of the table. Pure (unit-tested).
 *
 * <pre>
 * 섯다, my turn       one per legal action ({@link Betting#legal}), in this order:
 *                     다이 · 체크 · 삥 · 콜 · 따당 · 하프   (nobody bet yet: 다이 체크 삥 하프 · after a bet: 다이 콜 따당 하프)
 * 홀짝, my call       홀 · 짝
 * 홀짝 · 주사위, my roll   굴리기 (the bowl / my cup is shaken, the die / my dice show)
 * 세븐 포커, opening   공개 ×3 (one before each of my first three cards, until I opened one); then as 섯다
 * 바카라, bets open   the chips to pick (10 · 50 · 100 · 500: a row of their own, nearer me) · 플레이어 · 뱅커 · 타이 (puts the
 *                     picked chip there) · 베팅 끝; once dealt: 일어나기 (waiting for the cards like anyone not in a hand)
 * in the hand, not my turn: nothing (the turn timer floats over the table)
 * not in a hand (waiting · result · sat down mid-hand · folded): 일어나기, and 준비 if I sit out (missed a turn)
 * </pre>
 */
public final class Controls {

    private Controls() {}

    /** The most buttons in front of a seat at once (TableArt.pad lays out up to this many). */
    public static final int MOST = 7;

    /** 바카라's chip buttons: at most this many (hwatu.yml baccarat.chips). */
    public static final int CHIPS = 4;

    /** A button: its model economy:gamble_btn_&lt;key&gt; and its text hwatu.btn.&lt;key&gt;. */
    public enum Button {
        DIE("die"), CHECK("check"), PING("ping"), CALL("call"), DDADANG("ddadang"), HALF("half"),
        ODD("odd"), EVEN("even"), ROLL("roll"), READY("ready"), LEAVE("leave"),
        OPEN1("open1"), OPEN2("open2"), OPEN3("open3"),
        BAC_PLAYER("player"), BAC_BANKER("banker"), BAC_TIE("tie"), BAC_DONE("bet"),
        CHIP1("chip1"), CHIP2("chip2"), CHIP3("chip3"), CHIP4("chip4");

        private final String key;

        Button(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** Its model: economy:gamble_btn_&lt;key&gt; (공개 ×3 share gamble_btn_open, the chips are the chip piles 1 ~ 4). */
        public String model() {
            if (open() >= 0) return "economy:gamble_btn_open";
            if (chip() >= 0) return "economy:gamble_chips_" + (chip() + 1);
            return "economy:gamble_btn_" + key;
        }

        /** 공개 ×3: which of the first three cards it opens (0 ~ 2), else -1. */
        public int open() {
            return switch (this) {
                case OPEN1 -> 0;
                case OPEN2 -> 1;
                case OPEN3 -> 2;
                default -> -1;
            };
        }

        /** 바카라's chip buttons: which chip (0 ~ 3 of baccarat.chips), else -1. */
        public int chip() {
            return switch (this) {
                case CHIP1 -> 0;
                case CHIP2 -> 1;
                case CHIP3 -> 2;
                case CHIP4 -> 3;
                default -> -1;
            };
        }

        /** 바카라's spots: 플레이어 · 뱅커 · 타이, else null. */
        public Baccarat.Spot spot() {
            return switch (this) {
                case BAC_PLAYER -> Baccarat.Spot.PLAYER;
                case BAC_BANKER -> Baccarat.Spot.BANKER;
                case BAC_TIE -> Baccarat.Spot.TIE;
                default -> null;
            };
        }

        /** The table move it makes (null: 준비 · 일어나기, handled by the hall). */
        public Table.Move move() {
            return switch (this) {
                case DIE -> Table.Move.DIE;
                case CHECK -> Table.Move.CHECK;
                case PING -> Table.Move.PING;
                case CALL -> Table.Move.CALL;
                case DDADANG -> Table.Move.DDADANG;
                case HALF -> Table.Move.HALF;
                case ODD -> Table.Move.ODD;
                case EVEN -> Table.Move.EVEN;
                case ROLL -> Table.Move.ROLL;
                case OPEN1 -> Table.Move.OPEN1;
                case OPEN2 -> Table.Move.OPEN2;
                case OPEN3 -> Table.Move.OPEN3;
                case BAC_DONE -> Table.Move.BAC_DONE;
                default -> null;
            };
        }

        /** The 섯다 action it bets (null: not a betting button). */
        public Betting.Action action() {
            Table.Move m = move();
            return m == null ? null : Table.action(m);
        }

        public static Button of(String key) {
            for (Button b : values()) if (b.key.equals(key)) return b;
            return null;
        }

        static Button of(Betting.Action a) {
            return of(a.key());
        }
    }

    /**
     * What a seat's buttons depend on. legal: the 섯다 · 세븐 포커 actions I may take now; sittingOut: missed a turn, not
     * dealt in until 준비; opened: 세븐 포커, I already opened a card; chips: 바카라's chip kinds (baccarat.chips).
     */
    public record State(Table.Game game, Table.Phase phase, Table.Step step, boolean seated, boolean inHand, boolean myTurn,
                        Set<Betting.Action> legal, boolean sittingOut, boolean opened, int chips) {

        public State(Table.Game game, Table.Phase phase, Table.Step step, boolean seated, boolean inHand, boolean myTurn,
                     Set<Betting.Action> legal, boolean sittingOut) {
            this(game, phase, step, seated, inHand, myTurn, legal, sittingOut, false, 0);
        }
    }

    private static final List<Button> CHIP_BUTTONS = List.of(Button.CHIP1, Button.CHIP2, Button.CHIP3, Button.CHIP4);

    public static List<Button> buttons(State s) {
        if (!s.seated()) return List.of();
        if (s.game() == Table.Game.BACCARAT) {
            if (s.phase() == Table.Phase.PLAYING && s.step() == Table.Step.BETS) {
                List<Button> out = new ArrayList<>(CHIP_BUTTONS.subList(0, Math.clamp(s.chips(), 1, CHIPS)));
                out.addAll(List.of(Button.BAC_PLAYER, Button.BAC_BANKER, Button.BAC_TIE, Button.BAC_DONE));
                return out;
            }
            if (s.phase() == Table.Phase.PLAYING) return List.of();   // the cards are coming: nothing to press
            return List.of(Button.LEAVE);
        }
        if (s.phase() == Table.Phase.PLAYING && s.inHand() && s.step() == Table.Step.OPEN)
            return s.opened() ? List.of() : List.of(Button.OPEN1, Button.OPEN2, Button.OPEN3);
        if (s.phase() == Table.Phase.PLAYING && s.inHand()) {
            if (!s.myTurn() || s.step() == null) return List.of();
            return switch (s.step()) {
                case BET -> {
                    List<Button> out = new ArrayList<>();
                    for (Betting.Action a : Betting.Action.values()) if (s.legal().contains(a)) out.add(Button.of(a));
                    yield out;
                }
                case PICK -> List.of(Button.ODD, Button.EVEN);
                case ROLL -> List.of(Button.ROLL);
                case SHOW, DEAL, BETS, OPEN -> List.of();
            };
        }
        return s.sittingOut() ? List.of(Button.READY, Button.LEAVE) : List.of(Button.LEAVE);
    }

    /** The state of a player's seat at a table (seated: in one of its chairs). */
    public static State state(Table t, UUID u, boolean seated, boolean sittingOut, int chips) {
        boolean in = t.inHand(u);
        boolean turn = u.equals(t.turn());
        Set<Betting.Action> legal = turn ? t.legal(u) : EnumSet.noneOf(Betting.Action.class);
        return new State(t.game(), t.phase(), t.step(), seated, in, turn, legal, sittingOut, t.openedIndex(u) >= 0, chips);
    }

    public static State state(Table t, UUID u, boolean seated, boolean sittingOut) {
        return state(t, u, seated, sittingOut, Table.Game.BACCARAT == t.game() ? CHIPS : 0);
    }
}
