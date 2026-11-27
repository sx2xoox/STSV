package dev.stsv.economy.secret.newcomer;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How awake he is (docs/admin/숨은_비밀/새로_온_사람.md "깨어남"). When he answers is decided as it always was (intents,
 * chances, gaps); what changes is who writes the answer: from the day the model is switched on, a growing share of his
 * answers ({@code ai.ratio-start} on that day, 1.0 after {@code ai.ratio-days}), the rest his old lines. And a night has an
 * end: after that night's number of answers ({@code ai.max-per-night}, chosen from the night's date) he says he is going to
 * sleep and public chat is over, though never before his five questions are asked. All pure.
 */
final class Awakening {

    private Awakening() {}

    /** The share of his answers the model writes on a day: linear from start (the day it was switched on) to 1 after days. */
    static double ratio(LocalDate since, LocalDate today, double start, int days) {
        double s = Math.clamp(start, 0, 1);
        if (since == null || today == null) return s;
        long d = ChronoUnit.DAYS.between(since, today);
        if (days <= 0 || d >= days) return 1;
        if (d <= 0) return s;
        return Math.clamp(s + (1 - s) * d / days, 0, 1);
    }

    /** That night's number of answers, inside [lo, hi]: the same for the same night and seed. */
    static int cap(LocalDate night, long seed, int lo, int hi) {
        int a = Math.max(1, Math.min(lo, hi)), b = Math.max(a, Math.max(lo, hi));
        long z = night.toEpochDay() * 0x9E3779B97F4A7C15L + seed * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL;
        z = (z ^ (z >>> 31)) * 0xD6E8FEB86659FD93L;
        z ^= z >>> 32;
        return a + (int) Math.floorMod(z, (long) (b - a + 1));
    }

    /** How many answers before the cap he begins to sound sleepy. */
    static final int SLEEPY = 5;

    enum Mood {
        /** answering as usual */
        AWAKE,
        /** close to the cap: it shows */
        SLEEPY,
        /** at the cap with questions still to ask: he holds on, answering nothing more, and does not say good night yet */
        HOLDING,
        /** at the cap and nothing left to ask: his good night is due */
        GOODNIGHT,
        /** good night was said: public chat is over for the night (whispers are still heard) */
        ASLEEP
    }

    static Mood mood(int answers, int cap, int questionsLeft, boolean saidGoodnight) {
        if (saidGoodnight) return Mood.ASLEEP;
        if (answers >= cap) return questionsLeft > 0 ? Mood.HOLDING : Mood.GOODNIGHT;
        return answers >= cap - SLEEPY ? Mood.SLEEPY : Mood.AWAKE;
    }

    /** Whether one more call fits under a count kept per period (a night, a day): the count starts anew in a new period. */
    static boolean under(String period, String savedPeriod, long used, long most) {
        return most > 0 && (!period.equals(savedPeriod) || used < most);
    }
}
