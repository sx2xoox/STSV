package dev.stsv.economy.secret.jesa;

import java.util.Base64;

/**
 * The rite vessels (제기) on the table of 주인 없는 제사, as pure numbers: which kind stands where, where on the table top,
 * how tall, and how what lies on one is kept. Five rows of three (row 0 by the screen on the north; column 0 the west
 * vessel, 2 the east one), numbered row × 3 + column like {@link Table}'s places. Nothing here touches the server
 * (unit-tested); {@link House} builds them.
 * <ul>
 * <li>row 0: a lidded white porcelain bowl west and east (메 · 갱), the brass cup on its saucer between them (잔);</li>
 * <li>row 2: deep bowls on a foot (탕기);</li>
 * <li>rows 1, 3, 4: wooden pedestal plates (굽 높은 목기 접시: a round-ish plate on a short stem on a small foot).</li>
 * </ul>
 * Offsets are in blocks from the middle of the table's block: x east, z south.
 */
final class Vessel {

    enum Kind { PLATE, BOWL, CUP, DEEP }

    static final int COUNT = Table.SLOTS;
    /** Between two vessels of a row, between two rows, and the first row's offset (north of the block's middle). */
    static final double STEP_X = 0.62, STEP_Z = 0.34, FIRST_Z = -0.63;
    /** The table top: half its width (x), and its north and south edges (z), from the block's middle. */
    static final double HALF_W = 1.3, NORTH = -0.85, SOUTH = 0.95;

    private Vessel() {}

    static Kind kind(int slot) {
        int row = Table.row(slot), col = Table.col(slot);
        if (row == 0) return col == Table.MID ? Kind.CUP : Kind.BOWL;
        if (row == 2) return Kind.DEEP;
        return Kind.PLATE;
    }

    /** {east, south} offset of a vessel's middle. */
    static double[] at(int slot) {
        return new double[]{(Table.col(slot) - Table.MID) * STEP_X, FIRST_Z + Table.row(slot) * STEP_Z};
    }

    /** How high over the table top what is laid on it lies. */
    static double top(Kind k) {
        return switch (k) {
            case PLATE -> 0.155;
            case BOWL -> 0.12;
            case CUP -> 0.12;
            case DEEP -> 0.17;
        };
    }

    /** How wide a vessel is (its widest part): the click box, and the size of what lies on it. */
    static double width(Kind k) {
        return switch (k) {
            case PLATE -> 0.27;
            case BOWL -> 0.19;
            case CUP -> 0.17;
            case DEEP -> 0.22;
        };
    }

    /** The scale of an item shown lying on it. */
    static float food(Kind k) {
        return switch (k) {
            case PLATE -> 0.26f;
            case BOWL -> 0.19f;
            case CUP -> 0.13f;
            case DEEP -> 0.21f;
        };
    }

    /** Which way what lies on this vessel rolls off the table: -1 over the west edge, +1 over the east edge. */
    static int rollTo(int slot) {
        int c = Table.col(slot);
        return c < Table.MID ? -1 : c > Table.MID ? 1 : Table.row(slot) % 2 == 0 ? -1 : 1;
    }

    /**
     * Where a vessel is as seen by someone at (vx, vz) looking at the table's middle (offsets as {@link #at}): -1 on their
     * left, +1 on their right, 0 straight ahead (within a hand's width).
     */
    static int seenFrom(int slot, double vx, double vz) {
        double fx = -vx, fz = -vz;   // looking at the middle
        double len = Math.hypot(fx, fz);
        if (len < 1e-9) return 0;
        double rx = -fz / len, rz = fx / len;   // their right hand (x east, z south)
        double[] a = at(slot);
        double side = (a[0] - vx) * rx + (a[1] - vz) * rz;
        return Math.abs(side) < 0.1 ? 0 : side < 0 ? -1 : 1;
    }

    /** "서" · "가운데" · "동" (admin lines). */
    static String side(int slot) {
        int c = Table.col(slot);
        return c < Table.MID ? "서" : c > Table.MID ? "동" : "가운데";
    }

    // ------------------------------------------------------------------------------------------
    // what lies on a vessel, as saved (kv "plate.<slot>")
    // ------------------------------------------------------------------------------------------

    /** How far (blocks) from the eyes to a vessel's click box a hand reaches (survival reach 3 to the box, and some lag). */
    static final double REACH = 4.5;

    /** Whether a vessel this far away (squared, eyes to its click box) is within reach. */
    static boolean inReach(double distanceSquared) {
        return distanceSquared >= 0 && distanceSquared <= REACH * REACH;
    }

    static String key(int slot) {
        return "plate." + slot;
    }

    /** An item's bytes ({@code ItemStack.serializeAsBytes}) as one kv value. */
    static String pack(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The inverse of {@link #pack}; null when it does not read. */
    static byte[] unpack(String saved) {
        if (saved == null || saved.isBlank()) return null;
        try {
            byte[] out = Base64.getDecoder().decode(saved.trim());
            return out.length == 0 ? null : out;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
