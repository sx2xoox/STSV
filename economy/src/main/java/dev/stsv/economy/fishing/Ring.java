package dev.stsv.economy.fishing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The ring minigame of one bite (pure, unit-tested; the module ticks it 20 times a second and draws it).
 * A needle goes round 48 cells; press when it is on a green cell. Each success speeds it up, narrows the
 * zone by a cell and moves it ahead; rare fish turn the needle round, legends drift the zone and put two
 * red trap cells where the needle comes from. A miss costs a heart, a trap two, and a needle that goes
 * twice round without a press one.
 */
public final class Ring {

    public static final int SEG = 48;
    /** The needle gets this much faster (cells a tick) with every hit. */
    public static final double HIT_SPEEDUP = 0.22;
    /** The narrowest green zone (cells), however many hits. */
    public static final int MIN_ZONE = 3;
    /** A legend's zone drifts one cell every this many ticks. */
    public static final int DRIFT_EVERY = 3;

    public enum Result { IGNORED, HIT, PERFECT, MISS, TRAP, WON, ESCAPED }

    private final FishSpec.Rarity rarity;
    private final double zoneBonus;
    private final Random rnd;
    public final int maxHearts;
    public int hearts, hits, perfect;
    public double p;
    public int dir = 1;
    public double speed;
    public int zoneStart, zoneW, perfW, pause, drift, flash, flashKind;
    public double travel;
    public List<Integer> trap = new ArrayList<>();
    private int tickCount;

    /** zoneBonus: the fisher's wider zone (0.01 per level); hearts: 3, or 4 for a fisher of Lv10. */
    public Ring(FishSpec.Rarity rarity, double zoneBonus, int hearts, Random rnd) {
        this.rarity = rarity;
        this.zoneBonus = zoneBonus;
        this.rnd = rnd;
        this.maxHearts = hearts;
        this.hearts = hearts;
        this.p = rnd.nextInt(SEG);
        this.pause = 6;
        newRound(true);
    }

    public FishSpec.Rarity rarity() {
        return rarity;
    }

    static int mod(int v, int m) {
        return ((v % m) + m) % m;
    }

    public int needle() {
        return mod((int) Math.floor(p), SEG);
    }

    public boolean inZone(int seg) {
        return mod(seg - zoneStart, SEG) < zoneW;
    }

    public boolean inPerfect(int seg) {
        int off = (zoneW - perfW) / 2;
        return mod(seg - zoneStart - off, SEG) < perfW;
    }

    public boolean inTrap(int seg) {
        return trap.contains(mod(seg, SEG));
    }

    private boolean reverse() {
        return rarity.index() >= 2;
    }

    private boolean legend() {
        return rarity.index() >= 3;
    }

    private void newRound(boolean first) {
        int base = Math.max(MIN_ZONE, rarity.zone() - hits);
        zoneW = Math.max(MIN_ZONE, (int) Math.round(base * (1 + zoneBonus)));
        perfW = zoneW >= 5 ? 2 : 1;
        speed = rarity.speed() + HIT_SPEEDUP * hits;
        if (reverse() && !first && rnd.nextBoolean()) dir = -dir;
        // the zone's far end stays behind the needle: ahead + zoneW - 1 <= SEG - 1 for every draw (a 17-cell legend zone: 14..31 ahead)
        int ahead = 14 + rnd.nextInt(Math.max(1, Math.min(19, SEG - 13 - zoneW)));
        int near = (int) Math.floor(p) + dir * ahead;
        zoneStart = mod(dir > 0 ? near : near - zoneW + 1, SEG);
        trap = new ArrayList<>();
        if (legend()) {
            if (dir > 0) trap = new ArrayList<>(List.of(mod(zoneStart - 1, SEG), mod(zoneStart - 2, SEG)));
            else trap = new ArrayList<>(List.of(mod(zoneStart + zoneW, SEG), mod(zoneStart + zoneW + 1, SEG)));
        }
        drift = legend() ? (rnd.nextBoolean() ? 1 : -1) : 0;
        travel = 0;
    }

    /** One tick (1/20 s). Returns MISS when the needle went twice round, ESCAPED when that was the last heart. */
    public Result tick() {
        tickCount++;
        if (flash > 0) flash--;
        if (pause > 0) {
            pause--;
            return Result.IGNORED;
        }
        p += speed * dir;
        travel += speed;
        if (drift != 0 && tickCount % DRIFT_EVERY == 0) {
            zoneStart = mod(zoneStart + drift, SEG);
            List<Integer> moved = new ArrayList<>();
            for (int t : trap) moved.add(mod(t + drift, SEG));
            trap = moved;
        }
        if (travel >= SEG * 2) return lose(1);
        return Result.IGNORED;
    }

    /** A press (right click). */
    public Result press() {
        if (pause > 0) return Result.IGNORED;
        int seg = needle();
        if (inZone(seg)) {
            boolean perf = inPerfect(seg);
            hits++;
            if (perf) perfect++;
            flash = 4;
            flashKind = perf ? 2 : 1;
            if (hits >= rarity.hits()) return Result.WON;
            pause = 6;
            newRound(false);
            return perf ? Result.PERFECT : Result.HIT;
        }
        boolean isTrap = inTrap(seg);
        Result r = lose(isTrap ? 2 : 1);
        return r == Result.ESCAPED ? r : isTrap ? Result.TRAP : Result.MISS;
    }

    private Result lose(int n) {
        hearts = Math.max(0, hearts - n);
        flash = 6;
        flashKind = 3;
        if (hearts <= 0) return Result.ESCAPED;
        pause = 8;
        newRound(false);
        return Result.MISS;
    }

    public boolean allPerfect() {
        return perfect >= rarity.hits();
    }
}
