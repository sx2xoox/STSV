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
 *   <li>Cards (6 x 8 px, lying flat), chip piles, the bowl and the dice cup have their bottom on the display's
 *       position; the die (4 px) and the buttons (12 x 6 px plates, standing) are centred on it.</li>
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
     * Whether a card is shown face up to everyone: only at the 섯다 · 세븐 포커 showdown (a hand that ended by showing, not
     * one everyone else folded out of), and never a folded player's. Before that each owner alone sees the face of theirs
     * (세븐 포커's open cards excepted: {@link #seesPokerCard}).
     */
    public static boolean revealed(Table t, UUID owner) {
        Table.Result r = t.last();
        return t.game().bets() && t.phase() == Table.Phase.RESULT && r != null && !r.uncontested()
                && r.players().contains(owner) && !"die".equals(r.detail().get(owner));
    }

    /**
     * 세븐 포커: who sees the face of card k of an owner: everyone if it is open (the one they opened, the 4th ~ 6th) or the
     * hand is shown down, else the owner alone (the other two of the first three, the 7th).
     */
    public static boolean seesPokerCard(Table t, UUID viewer, UUID owner, int k) {
        return t.isOpen(owner, k) || seesFace(viewer, owner, revealed(t, owner));
    }

    /** Who sees the face of an owner's card: the owner always, everyone once it is revealed. */
    public static boolean seesFace(UUID viewer, UUID owner, boolean revealed) {
        return revealed || viewer.equals(owner);
    }

    // ------------------------------------------------------------------------------------------ the table top

    /**
     * How high the sign over a table floats, from the FLOOR the table stands on (not its top: the two tables differ): just
     * over the bulb hanging over the table (its lamp about 0.06 .. 0.56 up the block 2 .. 3 over the floor), under the
     * ceiling (3 over the floor; the sign's three lines at {@code TableScene.SIGN_SCALE} rise about 0.38 over this), as
     * HallLayout.Table#sign.
     */
    public static final double SIGN_UP = 2.58;

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
    public static final Reach ROUND_REACH = new Reach(0.42, 0.62, 0.32, 0.5, 1.45), DUEL_REACH = new Reach(0.25, 0.42, 0.22, 0.3, 0.96),
            POKER_REACH = new Reach(0.6, 0.69, 0.32, 0.5, 1.2), BACCARAT_REACH = new Reach(0.5, 0.66, 0.32, 0.48, 1.2);

    public static Reach reach(TableKind k) {
        return switch (k) {
            case DUEL -> DUEL_REACH;
            case POKER -> POKER_REACH;
            case BACCARAT -> BACCARAT_REACH;
            default -> ROUND_REACH;
        };
    }

    /**
     * A table's size as the things on it see it (blocks): about how far its chairs are from the middle, and how far from
     * the middle its top still holds something (the round · poker top is 3 across with the chairs 2 out, the duel crate 1
     * across with its chairs 1 out).
     */
    public record Size(double seat, double top) {}

    public static final Size ROUND_SIZE = new Size(2.0, 1.45), DUEL_SIZE = new Size(1.0, 0.5);

    public static Size size(TableKind k) {
        return k == TableKind.DUEL ? DUEL_SIZE : ROUND_SIZE;
    }

    /** Gaps between things side by side (blocks): wider than the things (a card 0.28, a button 0.3, a die 0.15 across). */
    public static final double CARD_GAP = 0.32, BUTTON_GAP = 0.34, DIE_GAP = 0.2;

    /** The 주사위 cup's height (gamble_cup, blocks at scale 1; 8 px across). */
    public static final double CUP_HEIGHT = 8.5 / 16.0;

    /**
     * Footprints on the table top seen from above (radius, blocks, at the scales TableScene draws them): the cup (8 px at
     * 0.9), a die (4 px at 0.6, its half diagonal: it lands turned), the 홀짝 bowl (11 px at 0.8) and its die (×1.3). CLEAR:
     * the least gap kept between two of them.
     */
    public static final double CUP_FOOT = 0.235, DIE_FOOT = 0.11, BOWL_FOOT = 0.28, BOWL_DIE_FOOT = 0.14, CLEAR = 0.03;

    /** How much further out (blocks, towards the seat) than the dice a lifted cup is put down. */
    public static final double CUP_OUT = 0.26;

    /** Where a lifted cover goes: `reach` of the way out to the seat (as {@link #along}), `side` blocks to the sitter's right. */
    public record Aside(double reach, double side) {}

    /**
     * Where a 주사위 cup is put down mouth up once lifted off a roll (2026-11-26 fix: it used to stand between its player
     * and the dice, hiding them): to the sitter's right of the dice row, beside it and a little further out, with
     * {@link #CLEAR} between it and every die, so the dice stay in full view of their player and the table. Null when the
     * table top has no room for that (the duel crate): the cup is lifted away instead and comes back with the next hand.
     */
    public static Aside cupAside(Reach r, Size size, int dice) {
        double side = Math.max(0, dice - 1) / 2.0 * DIE_GAP + DIE_FOOT + CUP_FOOT + CLEAR;
        double out = r.dice() * size.seat() + CUP_OUT;
        if (Math.hypot(out, side) + CUP_FOOT > size.top()) return null;
        return new Aside(out / size.seat(), side);
    }

    /**
     * Where the 홀짝 bowl is put down once lifted off the die (still mouth down, the die in the middle in full view): beside
     * it, across the line between the two players, so it stands in neither one's way. Null when the top has no room (the
     * duel crate): the bowl is lifted away and comes back down for the next hand.
     */
    public static Aside bowlAside(Size size) {
        double side = BOWL_DIE_FOOT + BOWL_FOOT + CLEAR;
        if (side + BOWL_FOOT > size.top()) return null;
        return new Aside(0, side);
    }

    // ------------------------------------------------------------------------------------------ 세븐 포커's cards

    /**
     * 세븐 포커's cards before a seat lie in two rows: the hidden ones (the two of the first three not opened, the 7th)
     * nearer the player at {@link Reach#card()}, the open ones (the opened one, the 4th ~ 6th) further in at POKER_OPEN, a
     * little overlapped (the corner with the rank shows) so six seats' rows stay apart. Cards (pcard, 6 × 8 px like the
     * 화투 cards) at {@link #PCARD_SCALE}.
     */
    public static final double POKER_OPEN = 0.47, POKER_HIDDEN_GAP = 0.2, POKER_OPEN_GAP = 0.15;
    public static final float PCARD_SCALE = 0.5f;
    /** A playing card's half width and half depth at {@link #PCARD_SCALE} (blocks). */
    public static final double PCARD_HALF_W = 3 / 16.0 * PCARD_SCALE, PCARD_HALF_D = 4 / 16.0 * PCARD_SCALE;

    /**
     * Where card k (dealt order 0 ~ 6) of a 세븐 포커 hand lies: row and slot. The first three start in the hidden row (slots
     * 0 1 2); the one opened moves to the open row's slot 0; the 4th ~ 6th take open slots 1 ~ 3; the 7th takes the hidden
     * slot the opened card left. opened: which of the first three was opened (-1: not yet).
     */
    public static double[] pokerSlot(int k, int opened) {
        if (k < 3) {
            if (k == opened) return new double[]{1, 0};
            return new double[]{0, k};
        }
        if (k <= 5) return new double[]{1, k - 2};
        return new double[]{0, opened < 0 ? 2 : opened};
    }

    /** The spot of a 세븐 포커 card: row 0 hidden (3 slots), row 1 open (4 slots); facing its seat. */
    public static Spot pokerSpot(double[] center, double[] seat, Reach r, int row, int slot) {
        double side = row == 0 ? row(3, POKER_HIDDEN_GAP).get(Math.clamp(slot, 0, 2)) : row(4, POKER_OPEN_GAP).get(Math.clamp(slot, 0, 3));
        return along(center, seat, row == 0 ? r.card() : POKER_OPEN, side, true);
    }

    // ------------------------------------------------------------------------------------------ 바카라

    /**
     * The 바카라 table's layout from its middle and the way to the dealer (a unit vector on the ground): the dealer stands
     * DEALER_OUT behind the middle; the 플레이어 hand lies on the players' left of the dealer's line and the 뱅커 hand on
     * their right, HAND_IN towards the dealer, each in a row of three (the third card last); the shoe at the dealer's
     * left, the discard at the dealer's right. Cards face the players (read from the curved side).
     */
    public static final double DEALER_OUT = 1.35, HAND_IN = 0.45, HAND_SIDE = 0.55, HAND_GAP = 0.3, SHOE_IN = 0.8, SHOE_SIDE = 1.05;
    public static final float BAC_CARD_SCALE = 0.75f;

    /** The players' right hand as they face the dealer (dx, dz the way to the dealer). */
    public static double[] rightOf(double dx, double dz) {
        return new double[]{-dz, dx};
    }

    /** A point `in` towards the dealer and `side` to the players' right of the middle. */
    public static double[] bac(double[] center, double[] toDealer, double in, double side) {
        double[] r = rightOf(toDealer[0], toDealer[1]);
        return new double[]{center[0] + toDealer[0] * in + r[0] * side, center[1] + toDealer[1] * in + r[1] * side};
    }

    /** Card k (0 ~ 2) of a hand (player: true) on the 바카라 table. */
    public static double[] bacCard(double[] center, double[] toDealer, boolean player, int k) {
        double side = (player ? -HAND_SIDE : HAND_SIDE) + row(3, HAND_GAP).get(Math.clamp(k, 0, 2));
        return bac(center, toDealer, HAND_IN, side);
    }

    /**
     * The way from a 바카라 table's middle to its dealer: from the dealer's spot when known, else away from the chairs
     * (they stand round the curved side, so their middle lies off the table's middle towards it), else away from seat 0.
     */
    public static double[] toDealer(double[] center, List<double[]> seats, double[] dealer) {
        double dx, dz;
        if (dealer != null) {
            dx = dealer[0] - center[0];
            dz = dealer[1] - center[1];
        } else {
            double[] m = center(seats);
            dx = center[0] - m[0];
            dz = center[1] - m[1];
            if (Math.hypot(dx, dz) < 0.15 && !seats.isEmpty()) {
                dx = center[0] - seats.getFirst()[0];
                dz = center[1] - seats.getFirst()[1];
            }
        }
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return new double[]{0, -1};
        return new double[]{dx / len, dz / len};
    }

    /** Where a seat's bets on 플레이어 · 뱅커 · 타이 lie: in a row before it, at BET_REACH (the spot's ordinal: left to right). */
    public static final double BET_REACH = 0.55, BET_GAP = 0.26;

    public static Spot betSpot(double[] center, double[] seat, Baccarat.Spot spot) {
        return along(center, seat, BET_REACH, row(3, BET_GAP).get(spot.ordinal()), true);
    }

    /** 바카라's chip buttons lie in a row of their own nearer the player than the betting buttons (flat: they hide nothing). */
    public static final double CHIP_REACH = 0.78;

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
