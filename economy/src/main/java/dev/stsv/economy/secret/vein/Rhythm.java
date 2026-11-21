package dev.stsv.economy.secret.vein;

/**
 * The heart's beat and the judging of each strike (pure, unit-tested; times in ms).
 * <p>
 * The heart beats every {@code interval}. The first {@code listen} beats are only for listening (strikes are ignored).
 * A strike counts against the nearest beat (the one just played or the one due next, so a slightly early strike counts
 * too): within ±window = a hit (one per beat; a second strike on the same beat is ignored), outside = a miss. A miss makes
 * the heart beat faster (interval × faster, not below minInterval). {@code need} hits = done; {@code maxMiss} misses =
 * broken. Strikes closer than {@link #DEBOUNCE} to the previous one are ignored (one click can arrive as two swings).
 * <p>
 * The caller gives strike times already corrected for the player's latency (arrival − ping): the beat sound reaches the
 * client half a ping after it is sent, and the swing needs another half to come back.
 */
final class Rhythm {

    enum Hit { IGNORED, HIT, MISS, DONE, BROKEN }

    static final long DEBOUNCE = 90;

    private long interval;
    private final long window;
    private final double faster;
    private final long minInterval;
    private final int need;
    private final int maxMiss;
    private final int listen;

    private long next;
    private long last = Long.MIN_VALUE;
    private int beats;
    private int hits;
    private int misses;
    private int credited = -1;
    private long lastSwing = Long.MIN_VALUE / 2;
    private long lastDelta;
    private boolean over;

    Rhythm(long start, long interval, long window, double faster, long minInterval, int need, int maxMiss, int listen) {
        this.interval = Math.max(200, interval);
        this.window = Math.max(10, window);
        this.faster = faster <= 0 || faster > 1 ? 0.85 : faster;
        this.minInterval = Math.max(150, Math.min(minInterval, this.interval));
        this.need = Math.max(1, need);
        this.maxMiss = Math.max(1, maxMiss);
        this.listen = Math.max(0, listen);
        this.next = start;
    }

    /** Whether the next beat should be played now (the tick nearest to it: within half a tick). */
    boolean due(long now) {
        return !over && now + 25 >= next;
    }

    /** The beat is played now: returns its number (1, 2, ...). */
    int beat(long now) {
        last = now;
        beats++;
        next = now + interval;
        return beats;
    }

    /** A strike at time t (latency already taken off). */
    Hit strike(long t) {
        if (over) return Hit.IGNORED;
        if (t - lastSwing < DEBOUNCE) return Hit.IGNORED;
        lastSwing = t;
        long best = Long.MAX_VALUE;
        int bestBeat = -1;
        long signed = 0;
        // the beat just played
        if (beats > listen && last != Long.MIN_VALUE) {
            long d = Math.abs(t - last);
            if (d < best) {
                best = d;
                bestBeat = beats;
                signed = t - last;
            }
        }
        // the beat due next (an early strike)
        if (beats + 1 > listen && beats > 0) {
            long d = Math.abs(t - next);
            if (d < best) {
                best = d;
                bestBeat = beats + 1;
                signed = t - next;
            }
        }
        if (bestBeat < 0) return Hit.IGNORED;   // still listening
        lastDelta = signed;
        if (best <= window) {
            if (bestBeat == credited) return Hit.IGNORED;
            credited = bestBeat;
            hits++;
            if (hits >= need) {
                over = true;
                return Hit.DONE;
            }
            return Hit.HIT;
        }
        misses++;
        interval = Math.max(minInterval, Math.round(interval * faster));
        if (last != Long.MIN_VALUE) next = Math.min(next, last + interval);
        if (misses >= maxMiss) {
            over = true;
            return Hit.BROKEN;
        }
        return Hit.MISS;
    }

    long interval() {
        return interval;
    }

    long next() {
        return next;
    }

    int beats() {
        return beats;
    }

    int hits() {
        return hits;
    }

    int misses() {
        return misses;
    }

    int need() {
        return need;
    }

    /** How far off the last judged strike was (ms; negative = early). */
    long lastDelta() {
        return lastDelta;
    }

    boolean over() {
        return over;
    }
}
