package dev.stsv.economy.secret.letter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure text work of 내일의 나: filling a letter page (a line whose value is missing drops out), the signature that
 * drifts a little more with every letter, and rounding a place so a letter can say "near" it. No Bukkit here.
 */
final class LetterText {

    private static final Pattern SLOT = Pattern.compile("\\{([a-z]+)}");

    private LetterText() {}

    /**
     * Fills {key} slots. A line holding a slot whose key is in {@code values} with a null value is left out (so "I sold X"
     * disappears when nothing was sold); slots not in the map stay as written. Runs of blank lines left behind collapse
     * into one, and the page never starts or ends with one.
     */
    static String fill(String template, Map<String, String> values) {
        if (template == null) return "";
        List<String> out = new ArrayList<>();
        for (String line : template.split("\n", -1)) {
            Matcher mt = SLOT.matcher(line);
            boolean drop = false;
            StringBuilder b = new StringBuilder();
            while (mt.find()) {
                String k = mt.group(1);
                if (!values.containsKey(k)) {
                    mt.appendReplacement(b, Matcher.quoteReplacement(mt.group()));
                    continue;
                }
                String v = values.get(k);
                if (v == null) {
                    drop = true;
                    break;
                }
                mt.appendReplacement(b, Matcher.quoteReplacement(v));
            }
            if (drop) continue;
            mt.appendTail(b);
            // a value may itself hold several lines
            for (String l : b.toString().split("\n", -1)) out.add(l);
        }
        List<String> tidy = new ArrayList<>();
        for (String l : out) {
            boolean blank = l.isBlank();
            if (blank && (tidy.isEmpty() || tidy.getLast().isBlank())) continue;
            tidy.add(blank ? "" : l);
        }
        while (!tidy.isEmpty() && tidy.getLast().isBlank()) tidy.removeLast();
        return String.join("\n", tidy);
    }

    /**
     * Their name as tomorrow's self signs it: exact for the first letter (level 0), then each level adds one more slip —
     * two letters swapped, one doubled, one turned into a look-alike, one lost. The same name, level and seed always give
     * the same signature, so a letter delivered twice is signed the same way.
     */
    static String drift(String name, int level, long seed) {
        if (name == null || name.length() < 2 || level <= 0) return name;
        StringBuilder s = new StringBuilder(name);
        Random r = new Random(seed * 31 + name.hashCode());
        for (int step = 1; step <= level; step++) {
            int n = s.length();
            if (n < 2) break;
            switch ((step - 1) % 4) {
                case 0 -> {
                    int i = r.nextInt(n - 1);
                    char a = s.charAt(i);
                    s.setCharAt(i, s.charAt(i + 1));
                    s.setCharAt(i + 1, a);
                }
                case 1 -> {
                    int i = r.nextInt(n);
                    s.insert(i, s.charAt(i));
                }
                case 2 -> {
                    int i = r.nextInt(n);
                    s.setCharAt(i, lookAlike(s.charAt(i)));
                }
                default -> s.deleteCharAt(r.nextInt(n));
            }
        }
        String out = s.toString();
        // never accidentally right: a swap of two equal letters changes nothing, so force one visible slip
        if (out.equals(name)) out = lookAlike(name.charAt(name.length() - 1)) + name.substring(0, name.length() - 1);
        return out;
    }

    static char lookAlike(char c) {
        return switch (c) {
            case 'o', 'O' -> '0';
            case '0' -> 'o';
            case 'i', 'I' -> 'l';
            case 'l', 'L' -> 'I';
            case 'e', 'E' -> '3';
            case 'a', 'A' -> '4';
            case 's', 'S' -> '5';
            case 'b', 'B' -> '8';
            case 'g', 'G' -> '9';
            case '_' -> '-';
            default -> Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.isLowerCase(c) ? Character.toUpperCase(c) : '_';
        };
    }

    /**
     * A day key from {@code SecretModule.today} as a number that counts up by one a day: "2026-11-21" (Korean date) or
     * "t29481234" (a test-mode day). Unreadable keys are day 0.
     */
    static long dayNo(String key) {
        if (key == null || key.isEmpty()) return 0;
        try {
            if (key.charAt(0) == 't') return Long.parseLong(key.substring(1));
            return java.time.LocalDate.parse(key).toEpochDay();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A length as a letter says it: "30분", "40초", "1분 30초". */
    static String duration(long seconds) {
        long sec = Math.max(0, seconds);
        if (sec < 60) return sec + "초";
        long min = sec / 60, rest = sec % 60;
        if (min >= 60 && min % 60 == 0 && rest == 0) return (min / 60) + "시간";
        return rest == 0 ? min + "분" : min + "분 " + rest + "초";
    }

    /** A coordinate as a letter writes it, "about": to the nearest ten. */
    static int round(int v) {
        return Math.round(v / 10f) * 10;
    }

    /** A random whole number in [lo, hi] from a two-number list (a single number, or nothing, gives that number / def). */
    static long between(List<Integer> range, long def, Random r) {
        if (range == null || range.isEmpty()) return def;
        int lo = range.getFirst(), hi = range.size() > 1 ? range.get(1) : lo;
        if (hi < lo) {
            int t = lo;
            lo = hi;
            hi = t;
        }
        return lo + (hi == lo ? 0 : r.nextInt(hi - lo + 1));
    }
}
