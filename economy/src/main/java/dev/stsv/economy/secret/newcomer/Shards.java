package dev.stsv.economy.secret.newcomer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The broken record (docs/admin/숨은_비밀/새로_온_사람.md "복구"): two of his stone's lines, the day and what he solved, come
 * back in pieces. A piece (a shard) is the whole line with only some of its glyphs left ({@code 2▒2▒-▒1-1▒}); which glyphs
 * a shard keeps is its kind (six kinds a line), and a line is whole when every glyph has been brought by some shard.
 * Separators (a space, "-") are never lost: they are how a player tells a date from words. The name is no shard: it is
 * read out of his chat and engraved with a name tag ({@link #names}). Everything here is pure.
 */
final class Shards {

    private Shards() {}

    static final char GONE = '▒';
    /** How many kinds of shard a line has (kinds 0..2: every third glyph; 3..5: the front, the middle, the back third). */
    static final int KINDS = 6;

    /** The two lines that come back in shards. */
    enum Line {
        DATE("date", "그날"), WHAT("what", "푼것");

        final String id, word;

        Line(String id, String word) {
            this.id = id;
            this.word = word;
        }

        static Optional<Line> of(String s) {
            if (s == null) return Optional.empty();
            String t = s.strip().toLowerCase(Locale.ROOT).replace(" ", "");
            for (Line l : values()) if (l.id.equals(t) || l.word.equals(t)) return Optional.of(l);
            return Optional.empty();
        }
    }

    /** What a shard looks like (where it turned up), and the lines it can be of. */
    enum Look {
        TICKET("economy:record_shard_ticket", "버스", Line.DATE),
        RECEIPT("economy:record_shard_receipt", "편의점", Line.DATE, Line.WHAT),
        LETTER("economy:record_shard_letter", "우편", Line.WHAT),
        BOTTLE("economy:record_shard_bottle", "낚시", Line.WHAT);

        final String item, place;
        final List<Line> lines;

        Look(String item, String place, Line... lines) {
            this.item = item;
            this.place = place;
            this.lines = List.of(lines);
        }

        static Optional<Look> ofPlace(String word) {
            for (Look l : values()) if (l.place.equals(word)) return Optional.of(l);
            return Optional.empty();
        }

        static Optional<Look> ofItem(String key) {
            for (Look l : values()) if (l.item.equals(key)) return Optional.of(l);
            return Optional.empty();
        }
    }

    /** One shard: which line it is a piece of and which glyphs it kept. */
    record Shard(Line line, int kind) {

        /** As it is written on the item ("date:3"). */
        String tag() {
            return line.id + ":" + kind;
        }
    }

    static Optional<Shard> parse(String tag) {
        if (tag == null) return Optional.empty();
        int c = tag.indexOf(':');
        if (c <= 0) return Optional.empty();
        Optional<Line> l = Line.of(tag.substring(0, c));
        try {
            int k = Integer.parseInt(tag.substring(c + 1).strip());
            return l.isPresent() && k >= 0 && k < KINDS ? Optional.of(new Shard(l.get(), k)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static boolean separator(char c) {
        return c == ' ' || c == '-' || c == '.' || c == '·';
    }

    /** How many glyphs a line has (its characters without the separators). */
    static int glyphs(String full) {
        int n = 0;
        for (int i = 0; i < full.length(); i++) if (!separator(full.charAt(i))) n++;
        return n;
    }

    /**
     * Which glyphs (counted without separators) a shard of this kind keeps, out of n. Kinds 0..2 keep every third glyph
     * (so the three together are the whole line), kinds 3..5 a third of the line each from the front (likewise).
     */
    static boolean[] keeps(int n, int kind) {
        boolean[] out = new boolean[n];
        int k = Math.floorMod(kind, KINDS);
        for (int j = 0; j < n; j++) out[j] = k < 3 ? j % 3 == k : Math.min(2, j * 3 / Math.max(1, n)) == k - 3;
        return out;
    }

    /** The line as it reads with only these glyphs there ("2▒2▒-▒1-1▒"). */
    static String render(String full, boolean[] have) {
        StringBuilder b = new StringBuilder();
        int j = 0;
        for (int i = 0; i < full.length(); i++) {
            char c = full.charAt(i);
            if (separator(c)) b.append(c);
            else b.append(j < have.length && have[j++] ? c : GONE);
        }
        return b.toString();
    }

    /** What is written on a shard of a line. */
    static String written(String full, int kind) {
        return render(full, keeps(glyphs(full), kind));
    }

    /** Saved glyphs ("1010011010") as flags for a line of n glyphs (anything else in the text: not there). */
    static boolean[] bits(String saved, int n) {
        boolean[] out = new boolean[n];
        if (saved != null) for (int j = 0; j < n && j < saved.length(); j++) out[j] = saved.charAt(j) == '1';
        return out;
    }

    static String bits(boolean[] have) {
        StringBuilder b = new StringBuilder();
        for (boolean h : have) b.append(h ? '1' : '0');
        return b.toString();
    }

    /** Puts a shard's glyphs into what is there; how many were new (0: it brought nothing). */
    static int fill(boolean[] have, int kind) {
        boolean[] k = keeps(have.length, kind);
        int fresh = 0;
        for (int j = 0; j < have.length; j++) {
            if (k[j] && !have[j]) {
                have[j] = true;
                fresh++;
            }
        }
        return fresh;
    }

    static boolean whole(boolean[] have) {
        for (boolean h : have) if (!h) return false;
        return have.length > 0;
    }

    static int missing(boolean[] have) {
        int n = 0;
        for (boolean h : have) if (!h) n++;
        return n;
    }

    /** The kinds that would still bring something. */
    static List<Integer> useful(boolean[] have) {
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < KINDS; k++) if (fill(have.clone(), k) > 0) out.add(k);
        return out;
    }

    /** Which part of the line a shard lacks, in his words ("앞부분", "뒷부분", "군데군데"). */
    static String lacks(int kind) {
        return switch (Math.floorMod(kind, KINDS)) {
            case 3 -> "뒷부분";
            case 5 -> "앞부분";
            case 4 -> "앞뒤";
            default -> "군데군데";
        };
    }

    // ------------------------------------------------------------------------------------------
    // the name: a name tag, not a shard
    // ------------------------------------------------------------------------------------------

    /** Whether what a name tag says is his name: the name in any case, or one of its other spellings (그믐). */
    static boolean names(String typed, String name, List<String> alias) {
        if (typed == null || name == null) return false;
        String t = typed.strip();
        if (t.isEmpty()) return false;
        if (t.equalsIgnoreCase(name.strip())) return true;
        for (String a : alias) if (a != null && !a.isBlank() && t.equalsIgnoreCase(a.strip())) return true;
        return false;
    }

    /** Whether one more try at the name is left tonight (savedNight, used: the night of the last tries and how many). */
    static boolean mayTry(String savedNight, long used, String night, int most) {
        return !night.equals(savedNight) || used < Math.max(1, most);
    }

    /** His name as it comes out when he says it himself: only the first and the last glyph get through ("r▒▒▒a"). */
    static String garbled(String name) {
        if (name == null || name.isEmpty()) return String.valueOf(GONE);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) b.append(i == 0 || (i == name.length() - 1 && name.length() > 2) ? name.charAt(i) : GONE);
        return b.toString();
    }

    /** The name line while nobody has engraved it: nothing of it is left. */
    static String blank(String name) {
        return String.valueOf(GONE).repeat(Math.max(1, name == null ? 5 : name.length()));
    }
}
