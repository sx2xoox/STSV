package dev.stsv.economy.hwatu;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the 도박장 tables show and where (README "도박장"), as plain numbers: the chip pile's size for a pot, the die's turn
 * for a face, who sees a card's face, and the spots on a table top round its seats. Pure (unit-tested); {@link TableScene}
 * puts the displays there.
 *
 * <p>Model conventions (assets: economy:gamble_*; fix them here if a model turns out otherwise):
 * <ul>
 *   <li>Displays are spawned at yaw 0 and turned by their transformation ({@link #facing}): the model's north (-Z) side
 *       then points the given yaw's way (the core's furniture rule, {@link #MODEL_TURN}).</li>
 *   <li>Every display model reads upright for a viewer on its north side (tools/assetgen/models/gamble.py): cards and
 *       buttons are turned to face their seat.</li>
 *   <li>Cards (6 x 8 px, lying flat), chip piles and the bowl have their bottom on the display's position; the die
 *       (4 px) and the buttons (12 x 6 px plates, standing) are centred on it.</li>
 *   <li>The die (gamble_die) shows 1 up in its own pose; {@link #DIE_FACES} is where each face points then.</li>
 * </ul>
 */
public final class TableArt {

    private TableArt() {}

    // ------------------------------------------------------------------------------------------ the chip pile

    /** Pile models economy:gamble_chips_1 … _5. */
    public static final int CHIP_LEVELS = 5;

    /**
     * The pile in the middle for a pot (0: none): it grows with the pot measured in antes, so every room's piles look
     * alike (a hand starts at 1 ante a player: two players show a small pile, a raised six-player 섯다 a tall one).
     */
    public static int chipLevel(long pot, long ante) {
        if (pot <= 0) return 0;
        double antes = (double) pot / Math.max(1, ante);
        if (antes <= 2) return 1;
        if (antes <= 4) return 2;
        if (antes <= 7) return 3;
        if (antes <= 12) return 4;
        return 5;
    }

    /** A pile a little bigger within its model as it nears the next one (1.0 … 1.15). */
    public static float chipScale(long pot, long ante) {
        int level = chipLevel(pot, ante);
        if (level == 0) return 0;
        double antes = (double) pot / Math.max(1, ante);
        double[] bounds = {0, 2, 4, 7, 12, 20};
        double lo = bounds[level - 1], hi = bounds[level];
        double f = Math.clamp((antes - lo) / (hi - lo), 0, 1);
        return (float) (1.0 + 0.15 * f);
    }

    // ------------------------------------------------------------------------------------------ the die

    /**
     * Where each face of gamble_die points in the model's own pose (index = face - 1): 1 up, 6 down, 2 north (-Z), 5 south,
     * 3 west (-X), 4 east (opposite faces add up to 7; tools/assetgen/models/gamble.py, README "도박장"). The one place to
     * change if the model is drawn otherwise.
     */
    public static final Vector3f[] DIE_FACES = {
            new Vector3f(0, 1, 0), new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0),
            new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), new Vector3f(0, -1, 0)};

    /**
     * An item display draws its model turned half round the up axis (FIXED; the core's furniture counts on it): a model
     * direction as the display's own transformation sees it. The one place to change if the models come out turned.
     */
    public static final float MODEL_TURN = 180f;

    public static Vector3f drawn(Vector3f model) {
        return new Quaternionf().rotationY((float) Math.toRadians(MODEL_TURN)).transform(new Vector3f(model));
    }

    /**
     * The display rotation that brings this face (1~6) of the die to the top, then spins it `spin` degrees round the up
     * axis (any spin keeps it up).
     */
    public static Quaternionf dieFace(int face, float spin) {
        Vector3f from = drawn(DIE_FACES[Math.clamp(face, 1, 6) - 1]);
        Quaternionf up = new Quaternionf().rotationTo(from, new Vector3f(0, 1, 0));
        return new Quaternionf().rotationY((float) Math.toRadians(spin)).mul(up);
    }

    /** The display rotation that points a model's north side to this yaw (displays are spawned at yaw 0). */
    public static Quaternionf facing(float yaw) {
        return new Quaternionf().rotationY((float) Math.toRadians(-yaw));
    }

    // ------------------------------------------------------------------------------------------ cards

    /**
     * Whether a card is shown face up to everyone: only at the 섯다 showdown (a hand that ended by showing, not one everyone
     * else folded out of), and never a folded player's. Before that each owner alone sees the face of theirs.
     */
    public static boolean revealed(Table t, UUID owner) {
        Table.Result r = t.last();
        return t.game() == Table.Game.SUTDA && t.phase() == Table.Phase.RESULT && r != null && !r.uncontested()
                && r.players().contains(owner) && !"die".equals(r.detail().get(owner));
    }

    /** Who sees the face of an owner's card: the owner always, everyone once it is revealed. */
    public static boolean seesFace(UUID viewer, UUID owner, boolean revealed) {
        return revealed || viewer.equals(owner);
    }

    // ------------------------------------------------------------------------------------------ the table top

    /** Spots on a table top: x z on the ground plane (y is the top's height, the same for all). */
    public record Spot(double x, double z, float yaw) {}

    /** The middle of the table: the middle of its seats (round tables have them all round, duel tables face to face). */
    public static double[] center(List<double[]> seats) {
        double x = 0, z = 0;
        for (double[] s : seats) {
            x += s[0];
            z += s[1];
        }
        int n = Math.max(1, seats.size());
        return new double[]{x / n, z / n};
    }

    /** The yaw that points a model's north side along (dx, dz) (Minecraft: yaw 0 = +Z, 90 = -X). */
    public static float yawToward(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return ((yaw % 360f) + 360f) % 360f;
    }

    /**
     * A spot on the line from the middle to a seat: `reach` of the way out (0 middle, 1 the seat), `side` blocks to the
     * seat's right as its sitter looks at the table. yaw: facing the seat (`towardSeat`) or the middle.
     */
    public static Spot along(double[] center, double[] seat, double reach, double side, boolean towardSeat) {
        double dx = seat[0] - center[0], dz = seat[1] - center[1];
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-6) {
            dx = 0;
            dz = 1;
            len = 1;
        }
        double ux = dx / len, uz = dz / len;
        // the sitter looks at the middle (-u); their right hand is u turned a quarter (looking along -u: right = (uz, -ux))
        double rx = uz, rz = -ux;
        double x = center[0] + ux * len * reach + rx * side, z = center[1] + uz * len * reach + rz * side;
        return new Spot(x, z, towardSeat ? yawToward(ux, uz) : yawToward(-ux, -uz));
    }

    /** Offsets to the side for n things in a row, `gap` apart, centred (n = 3, gap 0.2: -0.2, 0, 0.2). */
    public static List<Double> row(int n, double gap) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add((i - (n - 1) / 2.0) * gap);
        return out;
    }

    /**
     * How far out (of the way from the middle to a seat) things lie in front of it: the round table's top is 3 blocks
     * across with its seats about 2 blocks out, the duel table's crate 1 block with its two chairs 1 block out.
     */
    public record Reach(double card, double button, double dice, double timer, double buttonWidth) {}

    /** buttonWidth: the most a row of buttons spans side to side (blocks), so it stays on the table and off the neighbours. */
    public static final Reach ROUND_REACH = new Reach(0.42, 0.62, 0.32, 0.5, 1.45), DUEL_REACH = new Reach(0.25, 0.42, 0.22, 0.3, 0.96);

    public static Reach reach(TableKind k) {
        return k == TableKind.DUEL ? DUEL_REACH : ROUND_REACH;
    }

    /** Gaps between things side by side (blocks): wider than the things (a card 0.28, a button 0.3, a die 0.15 across). */
    public static final double CARD_GAP = 0.32, BUTTON_GAP = 0.34, DIE_GAP = 0.2;

    /** Where one button of a seat's pad goes: `side` blocks to the sitter's right, drawn at `scale` (1 = full size). */
    public record Slot(double side, double scale) {}

    /**
     * A seat's buttons (up to {@link Controls#MOST}) in one row along the edge in front of it, centred: full size while
     * they fit in the reach's buttonWidth (the round table: four, which is the most a 섯다 turn shows), closer and smaller
     * past that (the duel crate's four, or seven anywhere), so the row never leaves the table or reaches a neighbour's.
     * One row: a second row would stand either off the table's edge or behind the first, hidden from the sitter.
     */
    public static List<Slot> pad(int n, Reach r) {
        double gap = Math.min(BUTTON_GAP, r.buttonWidth() / Math.max(1, n));
        double scale = gap / BUTTON_GAP;
        List<Slot> out = new ArrayList<>();
        for (double side : row(n, gap)) out.add(new Slot(side, scale));
        return out;
    }
}
