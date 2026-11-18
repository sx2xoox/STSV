package dev.stsv.core.minigame.circuit;

import java.util.ArrayList;
import java.util.List;

/**
 * One racer's way round a circuit: which lap, which checkpoint gate is next, the lap times. Pure (unit-tested).
 *
 * <p>Every tick the racer's place on the centre line is given ({@link #move}: a sample index, from {@link TrackPath#locate}).
 * A gate is passed when the racer goes over it forwards; only the next gate counts, so a shortcut that leaves one out
 * gains nothing: the finish line counts only after every gate of the lap. The start: karts stand behind the line, and
 * crossing it the first time starts lap 1. Crossing it after the last lap's gates finishes. The time of a crossing is
 * worked out within the tick (how far between the two places the gate lies), so lap times are finer than a tick.
 *
 * <p>Going the wrong way: the racer's place moving backwards for a while ({@link #wrongWay}).
 */
public final class RaceProgress {

    /** A step longer than this (samples) is not driven but jumped (a respawn, a teleport): no gate counts. */
    static final int MAX_STEP = 40;
    /** Ticks of going backwards before the racer is told they go the wrong way. */
    static final int WRONG_TICKS = 25;

    private final int samples;
    private final int[] gates;
    private final int laps;
    private int at;
    private int lap;
    private int next;
    private boolean finished;
    private double finishTime = Double.NaN;
    private double lapStart = Double.NaN;
    private final List<Double> lapTimes = new ArrayList<>();
    private int backwards;
    private double backwardsRun;
    private int gatePassed = -1;
    private double passTime = Double.NaN;

    /**
     * @param samples the centre line's samples
     * @param gates   the gates' sample indices, the finish line first
     * @param laps    laps to drive
     * @param start   where the racer stands at the start (behind the line)
     */
    public RaceProgress(int samples, int[] gates, int laps, int start) {
        if (gates.length < 2) throw new IllegalArgumentException("gates");
        this.samples = samples;
        this.gates = gates.clone();
        this.laps = laps;
        this.at = start;
        this.next = 0;
    }

    private int ahead(int a, int b) {
        return Math.floorMod(b - a, samples);
    }

    /** What a move did. */
    public enum Event { NONE, GATE, LAP, FINISH }

    /**
     * The racer is now at sample index (time: seconds since the start at the end of this tick; tick: seconds a tick).
     * Returns what happened (a gate, a new lap, the finish).
     */
    public Event move(int index, double time, double tick) {
        if (finished) {
            at = index;
            return Event.NONE;
        }
        int forward = ahead(at, index), back = ahead(index, at);
        Event out = Event.NONE;
        if (forward <= MAX_STEP && forward > 0) {
            backwards = Math.max(0, backwards - 2);
            backwardsRun = 0;
            int gate = gates[next];
            int toGate = ahead(at, gate);
            if (toGate > 0 && toGate <= forward) {
                double when = time - tick + tick * toGate / (double) forward;
                out = pass(when);
            }
        } else if (back <= MAX_STEP && back > 0) {
            backwardsRun += back;
            if (backwardsRun > 6) backwards++;
        }
        at = index;
        return out;
    }

    private Event pass(double when) {
        gatePassed = next;
        passTime = when;
        if (next != 0) {
            next = (next + 1) % gates.length;
            return Event.GATE;
        }
        // the finish line
        if (lap >= 1) lapTimes.add(when - lapStart);
        if (lap == laps) {
            finished = true;
            finishTime = when;
            return Event.FINISH;
        }
        lap++;
        lapStart = lap == 1 ? Math.max(0, when) : when;
        next = 1;
        return lap == 1 ? Event.GATE : Event.LAP;
    }

    /** Put back at a gate's place (a respawn): the gates passed stay passed. */
    public void teleported(int index) {
        at = index;
        backwards = 0;
        backwardsRun = 0;
    }

    /** The gate last passed (where a respawn puts the racer): the start's place before lap 1. */
    public int lastGate() {
        if (lap == 0) return -1;
        return gates[Math.floorMod(next - 1, gates.length)];
    }

    public boolean wrongWay() {
        return !finished && backwards >= WRONG_TICKS;
    }

    public int lap() {
        return lap;
    }

    public int laps() {
        return laps;
    }

    /** The next gate's number (0 = the finish line). */
    public int next() {
        return next;
    }

    public int at() {
        return at;
    }

    public boolean finished() {
        return finished;
    }

    public double finishTime() {
        return finishTime;
    }

    public List<Double> lapTimes() {
        return List.copyOf(lapTimes);
    }

    /** The best lap so far (seconds), or NaN. */
    public double bestLap() {
        double best = Double.NaN;
        for (double t : lapTimes) if (Double.isNaN(best) || t < best) best = t;
        return best;
    }

    /** The gate passed last (its number: 0 = the line), -1 before any. */
    public int passedGate() {
        return gatePassed;
    }

    /** When the last gate was passed (seconds since the start, finer than a tick), NaN before any. */
    public double passedAt() {
        return passTime;
    }

    /** When the current lap began (seconds since the start), or NaN before lap 1. */
    public double lapStart() {
        return lapStart;
    }

    /**
     * How far round the race the racer is, for the standings: laps, then gates, then distance past the last gate (samples).
     * Higher is further.
     */
    public long distance() {
        if (finished) return Long.MAX_VALUE;
        int gatesDone = lap == 0 ? 0 : (next == 0 ? gates.length : next);
        int from = lap == 0 ? gates[0] : gates[Math.floorMod(next - 1, gates.length)];
        int past = lap == 0 ? -ahead(at, gates[0]) : Math.min(ahead(from, at), samples / 2);
        return ((long) lap * (gates.length + 1) + gatesDone) * 1_000_000L + past + samples;
    }

    /** "1:23.456" (m:ss.mmm). */
    public static String format(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return "-:--.---";
        long ms = Math.round(seconds * 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000);
    }

    /** "1:23.45" (m:ss.cc), for the live clock. */
    public static String formatShort(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0) return "-:--.--";
        long cs = (long) Math.floor(seconds * 100);
        return String.format(java.util.Locale.ROOT, "%d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }
}
