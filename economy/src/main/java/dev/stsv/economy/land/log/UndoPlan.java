package dev.stsv.economy.land.log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one person's block changes are undone. Pure (unit-tested).
 *
 * <p>Several changes of the same block by that person fold into one: the block goes back to what it was before their
 * first change. And it goes back only if it is still what they left it as (their last change): if anyone changed it
 * since (the owner rebuilt it, another member did something), that later work wins and the block is skipped.
 */
public final class UndoPlan {

    /** One logged block change: where, what was there before, what the person left. */
    public record Change(long id, String world, int x, int y, int z, String before, String after) {
        String where() {
            return world + ":" + x + "," + y + "," + z;
        }
    }

    /**
     * A block to put back: where, what it must still be now, what it goes back to, and the ids of the changes folded
     * into it (the first one carries the contents a broken container had).
     */
    public record Step(String world, int x, int y, int z, String expect, String restore, long first, List<Long> ids) {}

    private UndoPlan() {}

    /** The changes (any order) folded per block, in the order of their first change. */
    public static List<Step> fold(List<Change> changes) {
        List<Change> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparingLong(Change::id));
        Map<String, List<Change>> per = new LinkedHashMap<>();
        for (Change c : sorted) per.computeIfAbsent(c.where(), k -> new ArrayList<>()).add(c);
        List<Step> out = new ArrayList<>();
        for (List<Change> all : per.values()) {
            Change first = all.getFirst(), last = all.getLast();
            if (same(first.before(), last.after())) continue;   // they put it back themselves
            out.add(new Step(first.world(), first.x(), first.y(), first.z(), last.after(), first.before(), first.id(),
                    all.stream().map(Change::id).toList()));
        }
        return out;
    }

    /** Put this block back? Only when it is still what the person left. */
    public static boolean applies(Step s, String now) {
        return same(s.expect(), now);
    }

    /** Block data strings, with nothing and air the same (air, cave air and void air are all "nothing here"). */
    public static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        if (s == null || s.isEmpty()) return "minecraft:air";
        if (s.equals("minecraft:cave_air") || s.equals("minecraft:void_air")) return "minecraft:air";
        return s;
    }
}
