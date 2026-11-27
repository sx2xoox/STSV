package dev.stsv.economy.secret.newcomer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;

/**
 * Between his scripted lines and the mind that may reword them: whether the model is asked at all (switched on, a
 * credential there, this answer's turn by the day's share, the day's calls not used up), the counts that are kept
 * (eco.kv "secret.newcomer.ai.": {@code since} the day it was first switched on, {@code ratio} an admin's fixed share,
 * {@code day} / {@code calls} today's calls, {@code night} / {@code answers} / {@code asleep} that night's answers and
 * whether he said good night, {@code used} / {@code fell} lines of the model said and thrown away, {@code error} the last
 * reason a call gave nothing), and what comes of a call ({@link Thought}: the model's line when it is there in time and
 * passes {@link Filter}, else the scripted line).
 */
final class Voice {

    /** ai.* of newcomer.yml. */
    record Settings(boolean enabled, double ratioStart, int ratioDays, int capLo, int capHi, long seed, long callsPerDay, long timeoutMs) {

        static final Settings OFF = new Settings(false, 0.5, 2, 30, 40, 13, 400, 8000);
    }

    /** What may come of asking: {@link #or} is called when the line is due (on the server thread). */
    final class Thought {

        private final CompletableFuture<String> future;
        private final Filter.Rules rules;

        private Thought(CompletableFuture<String> future, Filter.Rules rules) {
            this.future = future;
            this.rules = rules;
        }

        /** Whether the model was asked. */
        boolean asked() {
            return future != null;
        }

        /** The model's line if it came in time and may be said, else the scripted line. */
        String or(String scripted) {
            if (future == null) return scripted;
            String got;
            try {
                got = future.getNow(null);
            } catch (RuntimeException e) {
                got = null;
            }
            if (got == null) {
                if (!future.isDone()) error("늦음 (대본으로)");
                future.cancel(true);
                count("fell");
                return scripted;
            }
            String why = Filter.refuse(strip(got), rules);
            if (why != null) {
                error("거름: " + why);
                count("fell");
                return scripted;
            }
            count("used");
            return strip(got);
        }

        /** Several lines (a letter): each is filtered; null when any fails or there are too few or too many. */
        java.util.List<String> lines(int min, int max) {
            if (future == null) return null;
            String got;
            try {
                got = future.getNow(null);
            } catch (RuntimeException e) {
                got = null;
            }
            if (got == null) return null;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String l : got.split("\\R")) {
                if (l.isBlank()) continue;
                String why = Filter.refuse(l.strip(), rules);
                if (why != null) {
                    error("거름: " + why);
                    count("fell");
                    return null;
                }
                out.add(l.strip());
            }
            if (out.size() < min || out.size() > max) {
                count("fell");
                return null;
            }
            count("used");
            return out;
        }

        boolean done() {
            return future == null || future.isDone();
        }
    }

    private static String strip(String s) {
        return Filter.clean(s, new Filter.Rules(null, java.util.List.of(), true, true, false, Integer.MAX_VALUE, null)).orElse(s.strip());
    }

    private final Record.Keep keep;
    private final String prefix;
    private volatile NewcomerMind mind = new ScriptedMind();
    private volatile Settings settings = Settings.OFF;
    private volatile String lastError;

    Voice(Record.Keep keep, String prefix) {
        this.keep = keep;
        this.prefix = prefix;
    }

    void set(NewcomerMind mind, Settings settings) {
        NewcomerMind old = this.mind;
        this.mind = mind == null ? new ScriptedMind() : mind;
        this.settings = settings == null ? Settings.OFF : settings;
        if (old != null && old != this.mind) old.close();
    }

    Settings settings() {
        return settings;
    }

    void close() {
        mind.close();
    }

    /** Whether the model writes anything at all: switched on, and a mind that asks one. */
    boolean active() {
        return settings.enabled() && mind.live();
    }

    private long num(String k) {
        try {
            String v = keep.get(prefix + k);
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void count(String k) {
        keep.set(prefix + k, String.valueOf(num(k) + 1));
    }

    /** Called from the mind's thread too: only remembered, never written from there. */
    void error(String why) {
        lastError = why;
    }

    String lastError() {
        return lastError;
    }

    long used() {
        return num("used");
    }

    long fell() {
        return num("fell");
    }

    // ---- the share

    /** The day the model was first switched on (set the first time it is asked about while active). */
    LocalDate since(LocalDate today) {
        String v = keep.get(prefix + "since");
        try {
            if (v != null) return LocalDate.parse(v);
        } catch (RuntimeException ignored) {
            // hand-edited: begins today
        }
        if (active()) keep.set(prefix + "since", today.toString());
        return today;
    }

    /** An admin's fixed share (막), or null. */
    Double fixed() {
        String v = keep.get(prefix + "ratio");
        try {
            return v == null ? null : Math.clamp(Double.parseDouble(v), 0, 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void fix(Double ratio) {
        if (ratio == null) keep.remove(prefix + "ratio");
        else keep.set(prefix + "ratio", String.valueOf(Math.clamp(ratio, 0, 1)));
    }

    /** The share of his answers the model writes today (0 when it writes nothing). */
    double ratio(LocalDate today) {
        if (!active()) return 0;
        Double f = fixed();
        return f != null ? f : Awakening.ratio(since(today), today, settings.ratioStart(), settings.ratioDays());
    }

    // ---- the night

    /** How many answers that night has room for. */
    int cap(LocalDate night) {
        return Awakening.cap(night, settings.seed(), settings.capLo(), settings.capHi());
    }

    int answers(String night) {
        return night.equals(keep.get(prefix + "night")) ? (int) num("answers") : 0;
    }

    void answered(String night) {
        int n = answers(night) + 1;
        keep.set(prefix + "night", night);
        keep.set(prefix + "answers", String.valueOf(n));
    }

    boolean asleep(String night) {
        return night.equals(keep.get(prefix + "asleep"));
    }

    void sleep(String night) {
        keep.set(prefix + "asleep", night);
    }

    void wake() {
        keep.remove(prefix + "asleep");
        keep.remove(prefix + "answers");
    }

    Awakening.Mood mood(LocalDate night, int questionsLeft) {
        String key = night.toString();
        return Awakening.mood(answers(key), cap(night), questionsLeft, asleep(key));
    }

    long callsToday(String day) {
        return day.equals(keep.get(prefix + "day")) ? num("calls") : 0;
    }

    // ---- asking

    /** Nothing was asked: the scripted line it is. */
    Thought none() {
        return new Thought(null, null);
    }

    /**
     * Asks the mind for his words, when it is this answer's turn: for chat and whispers by the day's share (roll: a number
     * in [0, 1); in test mode pass 0), for everything else always; never past the day's calls. budgetMs: how long until the
     * line is due (the call gives up a little before; never longer than ai.timeout-seconds).
     */
    Thought think(NewcomerMind.Ask ask, Filter.Rules rules, long budgetMs, LocalDate today, DoubleSupplier roll) {
        if (!active()) return none();
        boolean shared = ask.kind() == NewcomerMind.Kind.CHAT || ask.kind() == NewcomerMind.Kind.WHISPER;
        if (shared && roll.getAsDouble() >= ratio(today)) return none();
        String day = today.toString();
        if (!Awakening.under(day, keep.get(prefix + "day"), num("calls"), settings.callsPerDay())) {
            error("하루 호출 상한 (" + settings.callsPerDay() + ")");
            return none();
        }
        keep.set(prefix + "calls", String.valueOf(callsToday(day) + 1));
        keep.set(prefix + "day", day);
        long wait = Math.max(300, Math.min(settings.timeoutMs(), budgetMs - 250));
        try {
            return new Thought(mind.think(ask, Duration.ofMillis(wait)), rules);
        } catch (RuntimeException | LinkageError e) {
            error(e.getClass().getSimpleName());
            return none();
        }
    }
}
