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
 * in the hand, not my turn: nothing (the turn timer floats over the table)
 * not in a hand (waiting · result · sat down mid-hand · folded): 일어나기, and 준비 if I sit out (missed a turn)
 * </pre>
 */
public final class Controls {

    private Controls() {}

    /** The most buttons in front of a seat at once (TableArt.pad lays out up to this many). */
    public static final int MOST = 7;

    /** A button: its model economy:gamble_btn_&lt;key&gt; and its text hwatu.btn.&lt;key&gt;. */
    public enum Button {
        DIE("die"), CHECK("check"), PING("ping"), CALL("call"), DDADANG("ddadang"), HALF("half"),
        ODD("odd"), EVEN("even"), READY("ready"), LEAVE("leave");

        private final String key;

        Button(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public String model() {
            return "economy:gamble_btn_" + key;
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

    /** What a seat's buttons depend on. legal: the 섯다 actions I may take now; sittingOut: missed a turn, not dealt in until 준비. */
    public record State(Table.Game game, Table.Phase phase, Table.Step step, boolean seated, boolean inHand, boolean myTurn,
                        Set<Betting.Action> legal, boolean sittingOut) {}

    public static List<Button> buttons(State s) {
        if (!s.seated()) return List.of();
        if (s.phase() == Table.Phase.PLAYING && s.inHand()) {
            if (!s.myTurn() || s.step() == null) return List.of();
            return switch (s.step()) {
                case BET -> {
                    List<Button> out = new ArrayList<>();
                    for (Betting.Action a : Betting.Action.values()) if (s.legal().contains(a)) out.add(Button.of(a));
                    yield out;
                }
                case PICK -> List.of(Button.ODD, Button.EVEN);
                case ROLL -> List.of();
            };
        }
        return s.sittingOut() ? List.of(Button.READY, Button.LEAVE) : List.of(Button.LEAVE);
    }

    /** The state of a player's seat at a table (seated: in one of its chairs). */
    public static State state(Table t, UUID u, boolean seated, boolean sittingOut) {
        boolean in = t.inHand(u);
        boolean turn = u.equals(t.turn());
        Set<Betting.Action> legal = turn ? t.legal(u) : EnumSet.noneOf(Betting.Action.class);
        return new State(t.game(), t.phase(), t.step(), seated, in, turn, legal, sittingOut);
    }
}
