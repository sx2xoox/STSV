package dev.stsv.economy.accessory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One accessory as its item carries it (the item's PDC economy:acc): a serial (no two alike: a copy is caught), the main effect,
 * grade and roll (0..1 within the grade's range), enhance level, refines used, rerolls done, level XP and the sub-options. Pure
 * (unit-tested encode / decode).
 */
public final class Accessory {

    public final UUID serial;
    public final String effect;
    public final String grade;
    public double roll;
    public int enhance;
    public int refines;
    public int rerolls;
    public double xp;
    public final List<Sub> subs = new ArrayList<>();

    /** A sub-option: an effect and its roll (0..1 of its sub range). */
    public record Sub(String effect, double roll) {}

    public Accessory(UUID serial, String effect, String grade, double roll) {
        this.serial = serial;
        this.effect = effect;
        this.grade = grade;
        this.roll = roll;
    }

    /** "v1;<serial>;<effect>;<grade>;<roll>;<enhance>;<refines>;<rerolls>;<xp>;<sub>=<roll>,..." */
    public String encode() {
        StringBuilder sb = new StringBuilder("v1;").append(serial).append(';').append(effect).append(';').append(grade).append(';')
                .append(String.format(Locale.ROOT, "%.6f", roll)).append(';').append(enhance).append(';').append(refines).append(';')
                .append(rerolls).append(';').append(String.format(Locale.ROOT, "%.2f", xp)).append(';');
        for (int i = 0; i < subs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(subs.get(i).effect()).append('=').append(String.format(Locale.ROOT, "%.6f", subs.get(i).roll()));
        }
        return sb.toString();
    }

    /** The accessory, or null for anything else. */
    public static Accessory decode(String s) {
        if (s == null || !s.startsWith("v1;")) return null;
        String[] f = s.split(";", -1);
        if (f.length < 10) return null;
        try {
            Accessory a = new Accessory(UUID.fromString(f[1]), f[2], f[3], Double.parseDouble(f[4]));
            a.enhance = Integer.parseInt(f[5]);
            a.refines = Integer.parseInt(f[6]);
            a.rerolls = Integer.parseInt(f[7]);
            a.xp = Double.parseDouble(f[8]);
            if (!f[9].isEmpty()) for (String sub : f[9].split(",")) {
                int eq = sub.indexOf('=');
                if (eq > 0) a.subs.add(new Sub(sub.substring(0, eq), Double.parseDouble(sub.substring(eq + 1))));
            }
            return a;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The item id of this accessory's look: economy:acc_<slot>_<grade>. */
    public static String itemId(String slot, String grade) {
        return "economy:acc_" + slot + "_" + grade;
    }

    /** Is its roll the top of its range (완벽)? */
    public boolean perfect() {
        return roll >= 0.9995;
    }
}
