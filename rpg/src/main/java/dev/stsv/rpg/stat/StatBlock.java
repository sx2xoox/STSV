package dev.stsv.rpg.stat;

import java.util.Locale;

/** A set of stat values (a player's total, one item's stats). Values add up; caps apply on read. */
public final class StatBlock {

    private final double[] v = new double[Stat.values().length];

    public StatBlock add(Stat s, double value) {
        v[s.ordinal()] += value;
        return this;
    }

    public StatBlock set(Stat s, double value) {
        v[s.ordinal()] = value;
        return this;
    }

    public StatBlock add(StatBlock other) {
        for (int i = 0; i < v.length; i++) v[i] += other.v[i];
        return this;
    }

    /** The raw sum, without the cap. */
    public double raw(Stat s) {
        return v[s.ordinal()];
    }

    /** The value used in combat: never below 0, never above the stat's cap. */
    public double get(Stat s) {
        double x = Math.max(0, v[s.ordinal()]);
        return s.cap > 0 ? Math.min(s.cap, x) : x;
    }

    public boolean isEmpty() {
        for (double x : v) if (x != 0) return false;
        return true;
    }

    public StatBlock copy() {
        StatBlock c = new StatBlock();
        System.arraycopy(v, 0, c.v, 0, v.length);
        return c;
    }

    /** "attack=12;crit_chance=0.05" (the item storage format); zero values are left out. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Stat s : Stat.values()) {
            double x = v[s.ordinal()];
            if (x == 0) continue;
            if (!sb.isEmpty()) sb.append(';');
            sb.append(s.id()).append('=').append(String.format(Locale.ROOT, "%s", x));
        }
        return sb.toString();
    }

    /** Reads {@link #serialize()}. Unknown stats are skipped so old items keep working after a rename. */
    public static StatBlock parse(String s) {
        StatBlock b = new StatBlock();
        if (s == null || s.isBlank()) return b;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            Stat st = Stat.byId(part.substring(0, eq).trim());
            if (st == null) continue;
            try {
                b.add(st, Double.parseDouble(part.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited bad value: skip it rather than break the item
            }
        }
        return b;
    }
}
