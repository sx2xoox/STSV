package dev.stsv.economy.hwatu;

import java.util.List;
import java.util.UUID;

/**
 * The buttons that float on the table in front of a seat (README "도박장"), seen and clicked only by whoever sits there:
 * which ones show for the state of the table. Pure (unit-tested).
 *
 * <pre>
 * 섯다, my turn       다이 · 콜 · 레이즈 (레이즈 only while one is left)
 * 홀짝, my call       홀 · 짝
 * in the hand, not my turn: nothing (the turn timer floats over the table)
 * not in a hand (waiting · result · sat down mid-hand · folded): 일어나기, and 준비 if I sit out (missed a turn)
 * </pre>
 */
public final class Controls {

    private Controls() {}

    /** A button: its model economy:gamble_btn_&lt;key&gt; and its text hwatu.btn.&lt;key&gt;. */
    public enum Button {
        DIE("die"), CALL("call"), RAISE("raise"), ODD("odd"), EVEN("even"), READY("ready"), LEAVE("leave");

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
                case CALL -> Table.Move.CALL;
                case RAISE -> Table.Move.RAISE;
                case ODD -> Table.Move.ODD;
                case EVEN -> Table.Move.EVEN;
                default -> null;
            };
        }

        public static Button of(String key) {
            for (Button b : values()) if (b.key.equals(key)) return b;
            return null;
        }
    }

    /** What a seat's buttons depend on. sittingOut: missed a turn, not dealt in until 준비. */
    public record State(Table.Game game, Table.Phase phase, Table.Step step, boolean seated, boolean inHand, boolean myTurn,
                        boolean canRaise, boolean sittingOut) {}

    public static List<Button> buttons(State s) {
        if (!s.seated()) return List.of();
        if (s.phase() == Table.Phase.PLAYING && s.inHand()) {
            if (!s.myTurn() || s.step() == null) return List.of();
            return switch (s.step()) {
                case BET -> s.canRaise() ? List.of(Button.DIE, Button.CALL, Button.RAISE) : List.of(Button.DIE, Button.CALL);
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
        boolean raise = t.betting() != null && t.step() == Table.Step.BET && t.betting().canRaise();
        return new State(t.game(), t.phase(), t.step(), seated, in, turn, raise, sittingOut);
    }
}
