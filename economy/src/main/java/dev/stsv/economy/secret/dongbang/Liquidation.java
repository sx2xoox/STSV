package dev.stsv.economy.secret.dongbang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The pure parts of 청산인의 업무 (the 후속 임무 of 동방해운): the eleven who were aboard (the twelve chairs without the one
 * who was not), the fixed order their statements come in, which visit a count is (first round, the last of it, the
 * anniversaries after), which bus stop a family lives by, and how a statement speaks of a place without naming it.
 * No Bukkit here.
 */
final class Liquidation {

    /** The shareholders who went down with the ship. */
    static final int HOLDERS = Inquest.SEATS - 1;
    private static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String[] DIRS = {"동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽", "북쪽", "북동쪽"};

    private Liquidation() {}

    /** The twelve names of the chairs without the one who was not aboard: eleven, in chair order. */
    static List<String> holders(List<String> chairs, int liar) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < chairs.size(); i++) if (i != liar) out.add(chairs.get(i));
        return out;
    }

    /** The order the eleven come in for a player: always the same shuffle of 0..10. */
    static List<Integer> order(long seed) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < HOLDERS; i++) out.add(i);
        Collections.shuffle(out, new Random(seed ^ 0x1987_1129L));
        return out;
    }

    /**
     * One week's visit: whose statement (slot 0..10), whether it is an anniversary (they were visited before), whether it
     * is the eleventh of the first round (the final letter follows), and which round (0 = the first).
     */
    record Visit(int slot, boolean again, boolean last, int round) {}

    /** The visit after {@code done} finished ones. */
    static Visit visit(List<Integer> order, long done) {
        long d = Math.max(0, done);
        int i = (int) (d % HOLDERS), round = (int) Math.min(Integer.MAX_VALUE, d / HOLDERS);
        return new Visit(order.get(i), round > 0, round == 0 && i == HOLDERS - 1, round);
    }

    /**
     * Which of the stops (any list, its order given) a family lives by: a fixed shuffle of them for the player, one after
     * the other by slot, so eleven families live by eleven stops when there are that many. -1 when there are no stops.
     */
    static int stopFor(long seed, int slot, int stops) {
        if (stops <= 0) return -1;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < stops; i++) all.add(i);
        Collections.shuffle(all, new Random(seed ^ 0x0333L));
        return all.get(Math.floorMod(slot, stops));
    }

    /** "등대 앞" → "ㄷㄷ ㅇ": every Hangul syllable as its first consonant; anything else stays. */
    static String chosung(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) out.append(INITIALS.charAt((c - 0xAC00) / (21 * 28)));
            else out.append(c);
        }
        return out.toString();
    }

    /** A stop's name without a "정류장" at its end (the statement says that word itself). */
    static String stopWord(String name) {
        String n = name == null ? "" : name.trim();
        if (n.endsWith("정류장") && n.length() > 3) n = n.substring(0, n.length() - 3).trim();
        return n;
    }

    /** The compass way of (dx, dz): +x east, +z south. Null for the same spot. */
    static String direction(double dx, double dz) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return null;
        double deg = Math.toDegrees(Math.atan2(dz, dx));
        return DIRS[(int) Math.floorMod(Math.round(deg / 45.0), 8)];
    }

    /** A distance as an old form writes it: to the nearest 50 blocks, at least 50 ("약 350보"). */
    static String paces(double dist) {
        return "약 " + Math.max(50, Math.round(dist / 50.0) * 50) + "보";
    }

    /** The register number of a shareholder on a statement: slots 0..10 are numbers 1..12 without the thirteenth's own, as "제07번". */
    static String number(int slot) {
        return String.format("제%02d번", Math.floorMod(slot, HOLDERS) + 1);
    }

    /** A mark for a statement: its week and slot. A statement with another mark is an old one. */
    static String mark(String week, int slot) {
        return week + "#" + slot;
    }
}
