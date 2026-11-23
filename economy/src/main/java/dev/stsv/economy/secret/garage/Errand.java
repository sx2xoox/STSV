package dev.stsv.economy.secret.garage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The rules of the 폐차고's 후속 임무 「도윤 모터스」 ({@link GarageSequel}), pure and unit-tested: which of his unfinished
 * things this week's note asks for ({@link #pick}), the stretch of road it is driven on ({@link Course}, {@link #anchor}),
 * what is watched while it is driven ({@link Rules}, {@link #step}: bumps, a time limit, a speed band, the lights of his
 * line, stopping at a line) and the running list written on the back of the photo ({@link #stick}).
 */
final class Errand {

    private Errand() {}

    // ------------------------------------------------------------------------------------------
    // this week's note
    // ------------------------------------------------------------------------------------------

    /** The same number for the same player in the same week: a restart, or a second look, chooses the same thing. */
    static long seed(String week, UUID u) {
        long h = 1125899906842597L;
        String s = week + "/" + u;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return mix(h);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /**
     * This week's task among those that can be done on the roads as they are: by the seed, never one of the recent ones
     * (oldest first) as long as another is left; the oldest are let back in first. Null when there is none.
     */
    static String pick(List<String> ids, List<String> recent, long seed) {
        if (ids.isEmpty()) return null;
        List<String> pool = new ArrayList<>(ids);
        Collections.sort(pool);
        for (int skip = 0; skip <= recent.size(); skip++) {
            List<String> fresh = new ArrayList<>(pool);
            fresh.removeAll(recent.subList(skip, recent.size()));
            if (!fresh.isEmpty()) return fresh.get((int) Math.floorMod(seed, (long) fresh.size()));
        }
        return pool.get((int) Math.floorMod(seed, (long) pool.size()));
    }

    /** The recent list with this one at its end, at most {@code cap} long (a comma list in eco.kv). */
    static String remember(String recent, String id, int cap) {
        List<String> l = list(recent, ",");
        l.remove(id);
        l.add(id);
        while (l.size() > Math.max(0, cap)) l.removeFirst();
        return String.join(",", l);
    }

    static List<String> list(String saved, String sep) {
        List<String> out = new ArrayList<>();
        if (saved == null) return out;
        for (String s : saved.split(java.util.regex.Pattern.quote(sep))) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // the stretch of road
    // ------------------------------------------------------------------------------------------

    /** A stretch of a 국도: from point {@code from} to point {@code to} (to &lt; from: against the way the road runs). */
    record Course(String world, String dock, List<double[]> pts, int[] halves, int from, int to) {

        int dir() {
            return to >= from ? 1 : -1;
        }

        /** How long it is (points: a block each). */
        int length() {
            return Math.abs(to - from);
        }

        /** How far along the stretch the road's point s is (below 0: before its start). */
        double prog(double s) {
            return (s - from) * dir();
        }

        /** The road's point this far along the stretch. */
        double at(double prog) {
            return from + prog * dir();
        }
    }

    /**
     * A point of a road as the settings name it: {@code start}, {@code end}, {@code bend} or {@code garage}, with
     * {@code +N} / {@code -N} points after it ("bend-70", "end-20"). Kept inside the road. -1: not a point of this road
     * (a bad word; the bend or the garage on a road that has none: pass them below 0).
     */
    static int anchor(String expr, int n, int bend, int garage) {
        if (expr == null || n < 2) return -1;
        String e = expr.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        int cut = Math.max(e.indexOf('+'), e.indexOf('-'));
        String word = cut < 0 ? e : e.substring(0, cut);
        int off = 0;
        if (cut >= 0) {
            try {
                off = Integer.parseInt(e.substring(cut + 1)) * (e.charAt(cut) == '-' ? -1 : 1);
            } catch (NumberFormatException ex) {
                return -1;
            }
        }
        int base = switch (word) {
            case "start" -> 0;
            case "end" -> n - 1;
            case "bend" -> bend;
            case "garage" -> garage;
            default -> -1;
        };
        return base < 0 ? -1 : Math.clamp(base + off, 0, n - 1);
    }

    /** The seconds a timed stretch may take: its length at this speed (blocks/s), and a little to get going. */
    static int limit(int length, double speed, double slack) {
        return (int) Math.ceil(length / Math.max(0.5, speed) + Math.max(0, slack));
    }

    /**
     * The lights of his line along a stretch: one every {@code spacing} blocks from {@code first} on (none in the last 8),
     * each {@code lane} blocks to one side of the road's middle. Which side is the seed's, never the same three times in a
     * row. Each is {prog, lateral}: the lateral to the right of the way the ROAD runs (as {@link Roadway#lateral}), whichever
     * way the stretch is driven.
     */
    static List<double[]> gates(int length, double first, double spacing, double lane, long seed) {
        List<double[]> out = new ArrayList<>();
        if (spacing < 4) return out;
        long z = seed;
        int run = 0, last = 0;
        for (double prog = Math.max(8, first); prog <= length - 8; prog += spacing) {
            z = mix(z + 0x9e3779b97f4a7c15L);
            int side = (z & 1) == 0 ? 1 : -1;
            if (side == last && run >= 2) side = -side;
            run = side == last ? run + 1 : 1;
            last = side;
            out.add(new double[]{prog, side * lane});
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // what is watched while it is driven
    // ------------------------------------------------------------------------------------------

    /**
     * @param bumps      bumps let pass (0: not one; below 0: they do not matter)
     * @param limitTicks the stretch must be done within this (0: no limit)
     * @param lo         the speed band (blocks/s; hi 0: none): outside it for more than graceTicks in a row is the end
     *                   (counted from the first moment inside it, or five seconds after the start)
     * @param gates      his line's lights ({@link #gates}); each is passed within radius of it, or missed
     * @param misses     lights that may be missed
     * @param stop       the stretch ends standing still in the last {@code box} blocks before its end, having gone at
     *                   least {@code reach} on the way; rolling past the end is the end
     * @param offroad    further than this from the road's middle for three seconds is the end
     */
    record Rules(int bumps, int limitTicks, double lo, double hi, int graceTicks, List<double[]> gates, double radius, int misses,
                 boolean stop, double reach, double box, double offroad) {

        boolean band() {
            return hi > 0;
        }
    }

    /** One driven stretch as far as it has got (a tick at a time). */
    static final class Progress {
        int ticks, bumps, outBand, off, gate, missed, passed, still;
        boolean wasBumped, reached, settled;
        /** this tick: a light was passed / missed; stood still at the line without having gone fast enough */
        boolean hit, miss, tooGentle;
    }

    /** The car this tick: how far along the stretch, how far right of the road's middle, its speed, whether it hit something. */
    record Sample(double prog, double lateral, double speed, boolean bumped) {}

    enum Verdict { GO, PASS, BUMP, LATE, FAST, SLOW, GATE, OVER, OFF }

    /** One tick of a driven stretch: still going, done, or which rule ended it. */
    static Verdict step(Rules r, int length, Progress st, Sample s) {
        st.ticks++;
        st.hit = false;
        st.miss = false;
        st.tooGentle = false;
        if (s.bumped() && !st.wasBumped) st.bumps++;
        st.wasBumped = s.bumped();
        if (r.bumps() >= 0 && st.bumps > r.bumps()) return Verdict.BUMP;
        if (r.limitTicks() > 0 && st.ticks > r.limitTicks()) return Verdict.LATE;
        st.off = Math.abs(s.lateral()) > r.offroad() ? st.off + 1 : 0;
        if (st.off > 60) return Verdict.OFF;
        double v = Math.abs(s.speed());
        if (r.band() && s.prog() < length) {
            boolean out = v > r.hi() || v < r.lo();
            // (the band counts from the first moment inside it, or five seconds in: time to get up to it, or down)
            if (!st.settled) st.settled = !out || st.ticks > 100;
            if (!st.settled) out = false;
            st.outBand = out ? st.outBand + 1 : 0;
            if (st.outBand > r.graceTicks()) return v > r.hi() ? Verdict.FAST : Verdict.SLOW;
        }
        while (st.gate < r.gates().size() && s.prog() >= r.gates().get(st.gate)[0]) {
            double[] g = r.gates().get(st.gate++);
            if (Math.abs(s.lateral() - g[1]) <= r.radius()) {
                st.hit = true;
                st.passed++;
            } else {
                st.miss = true;
                if (++st.missed > r.misses()) return Verdict.GATE;
            }
        }
        if (r.stop()) {
            if (v >= r.reach()) st.reached = true;
            if (s.prog() > length + 1.5) return Verdict.OVER;
            boolean in = s.prog() >= length - r.box();
            st.still = in && v < 0.3 ? st.still + 1 : 0;
            if (st.still >= 20) {
                if (st.reached) return Verdict.PASS;
                st.still = 0;
                st.tooGentle = true;
            }
            return Verdict.GO;
        }
        return s.prog() >= length ? Verdict.PASS : Verdict.GO;
    }

    // ------------------------------------------------------------------------------------------
    // the back of the photo
    // ------------------------------------------------------------------------------------------

    /** One finished thing: the day and which ("2026.11.23|clean-home"). */
    record Done(String date, String id) {}

    /** The list as saved ("day|id;day|id", oldest first) with one more at its end, the oldest dropped over {@code cap}. */
    static String stick(String saved, String date, String id, int cap) {
        List<String> l = list(saved, ";");
        l.add(date.replace(";", "").replace("|", "") + "|" + id.replace(";", "").replace("|", ""));
        while (l.size() > Math.max(1, cap)) l.removeFirst();
        return String.join(";", l);
    }

    static List<Done> stuck(String saved) {
        List<Done> out = new ArrayList<>();
        for (String s : list(saved, ";")) {
            int i = s.indexOf('|');
            if (i > 0 && i < s.length() - 1) out.add(new Done(s.substring(0, i), s.substring(i + 1)));
        }
        return out;
    }
}
