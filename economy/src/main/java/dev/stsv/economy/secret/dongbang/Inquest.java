package dev.stsv.economy.secret.dongbang;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The boardroom's deduction (pure, unit-tested): twelve seated shareholders each say one line; exactly one of them was
 * never aboard 동방호. Everyone who was aboard tells the truth; the one who was not says something false. A run's lines
 * are generated from a seed so that exactly one choice of "the one not aboard" fits every line ({@link #solve}).
 * <p>
 * Seats: 0..5 are one row of the table (row index 0 = the door end, 5 = next to the empty 13th chair), 6..11 the other row
 * in the same order, so seat s sits across from s ± 6. "우리 줄" / "건너편 줄" are the speaker's own row and the other.
 */
final class Inquest {

    static final int SEATS = 12, ROW = 6;

    enum Kind {
        /** a was aboard ("…뱃멀미로 난간을 붙들고 있었어요"). */
        ABOARD("aboard"),
        /** the one across the table was aboard. */
        ACROSS("aboard-across"),
        /** the neighbour on the door side was aboard. */
        DOOR_SIDE("aboard-door"),
        /** the neighbour on the empty chair's side was aboard. */
        CHAIR_SIDE("aboard-chair"),
        /** one of a and b was not aboard. */
        EITHER("either"),
        /** everyone in my row was aboard. */
        MY_ROW_ALL("my-row-all"),
        /** everyone in the other row was aboard. */
        OTHER_ROW_ALL("other-row-all"),
        /** someone in my row was not aboard. */
        MY_ROW_SOME("my-row-some"),
        /** someone in the other row was not aboard. */
        OTHER_ROW_SOME("other-row-some"),
        /** I was aboard all along. */
        SELF("self"),
        /** a true memory of the sinking (03:33, fog, 남해, 11월 29일). */
        FACT_TRUE("fact-true"),
        /** a wrong memory of the sinking. */
        FACT_FALSE("fact-false");

        final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /** One line: what kind, about whom (a, b: seats, -1 when unused), which wording (an index into the templates). */
    record Claim(Kind kind, int a, int b, int variant) {}

    final int liar;
    final List<Claim> claims;

    Inquest(int liar, List<Claim> claims) {
        this.liar = liar;
        this.claims = List.copyOf(claims);
    }

    static int row(int s) {
        return s / ROW;
    }

    static int across(int s) {
        return (s + ROW) % SEATS;
    }

    /** Whether a claim by `speaker` is true when `out` is the one who was not aboard. */
    static boolean truth(Claim c, int speaker, int out) {
        return switch (c.kind()) {
            case ABOARD -> out != c.a();
            case ACROSS -> out != across(speaker);
            case DOOR_SIDE -> out != speaker - 1;
            case CHAIR_SIDE -> out != speaker + 1;
            case EITHER -> out == c.a() || out == c.b();
            case MY_ROW_ALL, OTHER_ROW_SOME -> row(out) != row(speaker);
            case OTHER_ROW_ALL, MY_ROW_SOME -> row(out) == row(speaker);
            case SELF -> out != speaker;
            case FACT_TRUE -> true;
            case FACT_FALSE -> false;
        };
    }

    /** Every seat that could be the one not aboard: its own line false, everyone else's true. */
    static List<Integer> solve(List<Claim> claims) {
        List<Integer> out = new ArrayList<>();
        for (int c = 0; c < SEATS; c++) {
            boolean ok = true;
            for (int s = 0; s < SEATS && ok; s++) ok = truth(claims.get(s), s, c) == (s != c);
            if (ok) out.add(c);
        }
        return out;
    }

    /**
     * How many wrong suspects can only be cleared by someone else's line (their own line is false if they were the one,
     * so it does not clear them by itself). The more, the more cross-checking a solver needs.
     */
    static int crossChecks(List<Claim> claims, int liar) {
        int n = 0;
        for (int c = 0; c < SEATS; c++) if (c != liar && !truth(claims.get(c), c, c)) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------
    // making one
    // ------------------------------------------------------------------------------------------

    /** Wordings per kind, for picking distinct variants (facts need distinct ones so no two people say the same). */
    record Sizes(int factTrue, int factFalse) {
        static final Sizes DEFAULT = new Sizes(4, 4);
    }

    /** A run's puzzle: the same seed gives the same puzzle. */
    static Inquest make(long seed, Sizes sizes) {
        Random r = new Random(seed);
        int liar = r.nextInt(SEATS);
        for (int attempt = 0; attempt < 4000; attempt++) {
            List<Claim> cl = attempt(r, liar, sizes);
            if (cl == null) continue;
            if (!solve(cl).equals(List.of(liar))) continue;
            boolean byFact = cl.get(liar).kind() == Kind.FACT_FALSE;
            // a careful player must cross-check, not just read one line (a wrong memory is the one shortcut, and then
            // the true memories around it must still be told apart from it)
            if (crossChecks(cl, liar) < (byFact ? 1 : 3)) continue;
            return new Inquest(liar, cl);
        }
        return new Inquest(liar, fallback(r, liar, sizes));
    }

    private static List<Claim> attempt(Random r, int liar, Sizes sizes) {
        List<Claim> out = new ArrayList<>();
        Set<Integer> factsTrue = new HashSet<>();
        int eithers = 0, rows = 0;
        for (int s = 0; s < SEATS; s++) {
            Claim c = null;
            for (int tries = 0; tries < 40 && c == null; tries++) {
                c = s == liar ? lie(r, s, sizes) : truthful(r, s, liar, sizes);
                if (c == null) continue;
                if (truth(c, s, liar) != (s != liar)) c = null;
                else if (c.kind() == Kind.FACT_TRUE && (factsTrue.size() >= 2 || factsTrue.contains(c.variant()))) c = null;
                else if (c.kind() == Kind.EITHER && s != liar && eithers >= 3) c = null;
                else if (isRow(c.kind()) && rows >= 3) c = null;
            }
            if (c == null) return null;
            if (c.kind() == Kind.FACT_TRUE) factsTrue.add(c.variant());
            if (c.kind() == Kind.EITHER) eithers++;
            if (isRow(c.kind())) rows++;
            out.add(c);
        }
        return out;
    }

    private static boolean isRow(Kind k) {
        return k == Kind.MY_ROW_ALL || k == Kind.OTHER_ROW_ALL || k == Kind.MY_ROW_SOME || k == Kind.OTHER_ROW_SOME;
    }

    /** A seat other than these. */
    private static int other(Random r, int... not) {
        while (true) {
            int v = r.nextInt(SEATS);
            boolean ok = true;
            for (int n : not) ok &= v != n;
            if (ok) return v;
        }
    }

    private static Claim truthful(Random r, int s, int liar, Sizes sizes) {
        int roll = r.nextInt(100);
        if (roll < 26) return new Claim(Kind.ABOARD, other(r, s, liar), -1, r.nextInt(8));
        if (roll < 34) return new Claim(Kind.ACROSS, -1, -1, r.nextInt(8));
        if (roll < 40) return s % ROW > 0 ? new Claim(Kind.DOOR_SIDE, -1, -1, r.nextInt(8)) : null;
        if (roll < 46) return s % ROW < ROW - 1 ? new Claim(Kind.CHAIR_SIDE, -1, -1, r.nextInt(8)) : null;
        if (roll < 62) {
            int k = other(r, s, liar);
            return r.nextBoolean() ? new Claim(Kind.EITHER, liar, k, r.nextInt(8)) : new Claim(Kind.EITHER, k, liar, r.nextInt(8));
        }
        if (roll < 74) {
            boolean mine = row(liar) == row(s);
            return r.nextBoolean() ? new Claim(mine ? Kind.MY_ROW_SOME : Kind.OTHER_ROW_SOME, -1, -1, r.nextInt(8))
                    : new Claim(mine ? Kind.OTHER_ROW_ALL : Kind.MY_ROW_ALL, -1, -1, r.nextInt(8));
        }
        if (roll < 86) return new Claim(Kind.SELF, -1, -1, r.nextInt(8));
        return new Claim(Kind.FACT_TRUE, -1, -1, r.nextInt(Math.max(1, sizes.factTrue())));
    }

    private static Claim lie(Random r, int s, Sizes sizes) {
        int roll = r.nextInt(100);
        if (roll < 22) return new Claim(Kind.FACT_FALSE, -1, -1, r.nextInt(Math.max(1, sizes.factFalse())));
        if (roll < 40) return new Claim(Kind.SELF, -1, -1, r.nextInt(8));
        if (roll < 55) return new Claim(Kind.MY_ROW_ALL, -1, -1, r.nextInt(8));
        if (roll < 70) return new Claim(Kind.OTHER_ROW_SOME, -1, -1, r.nextInt(8));
        int a = other(r, s), b = other(r, s, a);
        return new Claim(Kind.EITHER, a, b, r.nextInt(8));
    }

    /** Always unique: the one not aboard misremembers the night, everyone else names someone who was aboard. */
    private static List<Claim> fallback(Random r, int liar, Sizes sizes) {
        List<Claim> out = new ArrayList<>();
        for (int s = 0; s < SEATS; s++) {
            out.add(s == liar ? new Claim(Kind.FACT_FALSE, -1, -1, r.nextInt(Math.max(1, sizes.factFalse())))
                    : new Claim(Kind.ABOARD, other(r, s, liar), -1, r.nextInt(8)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // words
    // ------------------------------------------------------------------------------------------

    /**
     * One seat's line in words. templates: kind key → wordings ({a}, {b} = names); names: the twelve tags (plain text,
     * already safe for MiniMessage).
     */
    static String text(Claim c, Map<String, List<String>> templates, List<String> names) {
        List<String> l = templates.getOrDefault(c.kind().key, List.of());
        if (l.isEmpty()) return "…";
        String t = l.get(Math.floorMod(c.variant(), l.size()));
        return t.replace("{a}", c.a() >= 0 ? names.get(c.a()) : "").replace("{b}", c.b() >= 0 ? names.get(c.b()) : "");
    }

    String text(int seat, Map<String, List<String>> templates, List<String> names) {
        return text(claims.get(seat), templates, names);
    }
}
