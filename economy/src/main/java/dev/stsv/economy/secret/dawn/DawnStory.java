package dev.stsv.economy.secret.dawn;

/**
 * 새벽 단골's steps (docs/admin/숨은_비밀/새벽단골.md), pure (unit-tested). Stage 0: not started; 1..4: the next of his four
 * dawn visits (one a day); 5: he left the bottle on the counter and the one it is for may come in now; 6: solved.
 */
public final class DawnStory {

    /** His visits (stages 1..4), then the one the bottle is for (5). */
    public static final int VISITS = 4, WAITING = 5;

    private DawnStory() {}

    /** Whether a game hour is in the dawn window [from, to) (wrapping past midnight). */
    public static boolean dawn(int hour, int from, int to) {
        return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    /** Whether it may begin for this worker: a night shift, the dawn, enough night customers behind them (test: always). */
    public static boolean starts(boolean test, boolean nightShift, int hour, int from, int to, long nightServed, long need) {
        if (test) return true;
        return nightShift && dawn(hour, from, to) && nightServed >= need;
    }

    /** Whether he comes today: not on the day he last came (test: a test "day"), a night shift at dawn. */
    public static boolean comes(boolean test, boolean nightShift, int hour, int from, int to, String lastDay, String today) {
        if (today.equals(lastDay)) return false;
        return test || (nightShift && dawn(hour, from, to));
    }

    /** The stage after a visit of this stage was paid for: the next visit, or waiting (after the fourth). */
    public static int afterPaid(int stage) {
        return stage >= VISITS ? WAITING : Math.max(1, stage + 1);
    }

    /** The settings' key of the visit of a stage: visits.1 .. visits.4, then "daughter". */
    public static String key(int stage) {
        return stage >= WAITING ? "daughter" : "visits." + Math.clamp(stage, 1, VISITS);
    }

    /** Waiting for the one the bottle is for, but the bottle is gone (the shift ended, she was not given it): he comes again. */
    public static int missed(int stage) {
        return stage >= WAITING ? VISITS : stage;
    }
}
