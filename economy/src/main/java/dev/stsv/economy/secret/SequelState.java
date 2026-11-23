package dev.stsv.economy.secret;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;

/**
 * The pure part of a {@link Sequel} (후속 임무): which week it is, what is saved for one solver and how that moves. Nothing
 * here touches the server, so it is tested alone (SequelTest).
 * <p>
 * Saved under {@code secret.<id>.<uuid>.sequel.}: {@code week} (the week a mission was last offered), {@code state},
 * {@code step}, {@code since}, {@code x.<key>} (the mission's own notes: gone when the next mission begins or the week
 * turns), {@code keep.<key>} (the author's notes that outlive a mission), {@code count} (missions completed overall).
 */
public final class SequelState {

    private SequelState() {}

    /** One player's saved keys of one secret's sequel (keys are short: "week", "x.place"). */
    public interface Store {
        String get(String key);

        void set(String key, String value);

        void remove(String key);

        void removePrefix(String prefix);
    }

    public enum State {
        /** Nothing this week yet. */
        NONE("없음"),
        /** It has begun (the note came, the figure stands there) but the player has not taken it up. */
        OFFERED("받음"),
        /** The player is doing it. */
        ACTIVE("진행 중"),
        DONE("완료"),
        FAILED("실패");

        private final String word;

        State(String word) {
            this.word = word;
        }

        /** Korean word for admin output. */
        public String word() {
            return word;
        }

        /** A mission that is going on: it ticks, resumes on join and expires with the week. */
        public boolean live() {
            return this == OFFERED || this == ACTIVE;
        }

        static State of(String saved) {
            if (saved == null) return NONE;
            try {
                return valueOf(saved.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    // ---- the week

    /** The ISO week of a date: "2026-W48" (the week-based year, so 2027-01-01 is still "2026-W53"). */
    public static String week(LocalDate d) {
        return String.format("%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** The ISO week of a moment in a zone (the module's is Korean time: a week turns on Monday 00:00 KST). */
    public static String week(long nowMs, ZoneId zone) {
        return week(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate());
    }

    /** A tester's "week": it lasts {@code weekMs} and never looks like a real one ("T14823"). */
    public static String testWeek(long nowMs, long weekMs) {
        return "T" + Math.floorDiv(nowMs, Math.max(1L, weekMs));
    }

    public static String week(long nowMs, ZoneId zone, boolean test, long testWeekMs) {
        return test ? testWeek(nowMs, testWeekMs) : week(nowMs, zone);
    }

    // ---- what is saved

    public static State state(Store s) {
        return State.of(s.get("state"));
    }

    /** The state as of this week, without changing anything: last week's done/failed reads as none. */
    public static State shown(Store s, String week) {
        State st = state(s);
        return st.live() || week.equals(s.get("week")) ? st : State.NONE;
    }

    public static long count(Store s) {
        return number(s.get("count"), 0);
    }

    public static int step(Store s) {
        return (int) number(s.get("step"), 0);
    }

    public static long since(Store s) {
        return number(s.get("since"), 0);
    }

    /** Whether a mission may be offered now: nothing going on and none offered in this week. */
    public static boolean due(Store s, String week) {
        return state(s) == State.NONE && !week.equals(s.get("week"));
    }

    /** Whether a mission that is going on belongs to an older week: it expires now (tidy up, then {@link #turn}). */
    public static boolean expiring(Store s, String week) {
        return state(s).live() && !week.equals(s.get("week"));
    }

    /**
     * The week turned: whatever was saved for an older week's mission goes (state, step, since, its notes). Returns true when
     * that mission was still going on (offered or active): it has expired and its things must be tidied up. Costs nothing
     * when there is nothing to do, so it can be asked every second.
     */
    public static boolean turn(Store s, String week) {
        State st = state(s);
        if (st == State.NONE || week.equals(s.get("week"))) return false;
        clearMission(s);
        return st.live();
    }

    /** A mission begins: offered in this week. False (nothing changed) when one is already going on. */
    public static boolean offer(Store s, String week, long nowMs) {
        if (state(s).live()) return false;
        s.set("week", week);
        s.set("state", "offered");
        s.set("step", "0");
        s.set("since", String.valueOf(nowMs));
        return true;
    }

    /** Offered → active. False when it was not offered. */
    public static boolean accept(Store s, long nowMs) {
        if (state(s) != State.OFFERED) return false;
        s.set("state", "active");
        s.set("since", String.valueOf(nowMs));
        return true;
    }

    public static void step(Store s, int step, long nowMs) {
        s.set("step", String.valueOf(step));
        s.set("since", String.valueOf(nowMs));
    }

    /** A going mission ends as done (count + 1) or failed. False (nothing changed) when none was going on: no second reward. */
    public static boolean finish(Store s, boolean done, long nowMs) {
        if (!state(s).live()) return false;
        s.set("state", done ? "done" : "failed");
        s.set("since", String.valueOf(nowMs));
        if (done) s.set("count", String.valueOf(count(s) + 1));
        return true;
    }

    /** Stops a going mission without an outcome (the solver is no longer the solver): true when one was going on. */
    public static boolean stop(Store s) {
        boolean live = state(s).live();
        clearMission(s);
        return live;
    }

    /** The state, step, since and the mission's notes; the week, the count and the kept notes stay. */
    public static void clearMission(Store s) {
        s.remove("state");
        s.remove("step");
        s.remove("since");
        s.removePrefix("x.");
    }

    // ---- the reward

    /**
     * {@code money: [100000, 300000]}, {@code money: 150000} or {@code money: "100000-300000"} → {min, max} (never negative,
     * min ≤ max). Anything else is {0, 0}.
     */
    public static long[] range(Object v) {
        long a = 0, b = 0;
        if (v instanceof Number n) {
            a = b = n.longValue();
        } else if (v instanceof List<?> l && !l.isEmpty()) {
            a = number(String.valueOf(l.get(0)), 0);
            b = l.size() > 1 ? number(String.valueOf(l.get(1)), a) : a;
        } else if (v instanceof String str) {
            String[] w = str.replace(",", "").replace("_", "").trim().split("\\s*[-~]\\s*|\\s+");
            a = number(w[0], 0);
            b = w.length > 1 ? number(w[1], a) : a;
        }
        a = Math.max(0, a);
        b = Math.max(0, b);
        return new long[]{Math.min(a, b), Math.max(a, b)};
    }

    /** A value in [min, max] for a roll in [0, 1), rounded down to {@code unit} (1,000 G steps read better) but never below min. */
    public static long pick(long[] range, double roll, long unit) {
        long span = range[1] - range[0];
        long v = range[0] + (long) Math.floor(Math.max(0, Math.min(1, roll)) * (span + 1));
        v = Math.min(range[1], v);
        if (unit > 1) v = Math.max(range[0], v - Math.floorMod(v, unit));
        return v;
    }

    private static long number(String v, long def) {
        if (v == null) return def;
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
