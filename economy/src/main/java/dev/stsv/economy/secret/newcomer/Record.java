package dev.stsv.economy.secret.newcomer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What stands on his stone, and who put it back: the day and what he solved (filled glyph by glyph from shards,
 * {@link Shards}), his name (engraved whole with a name tag), and at the very end the witness. Saved under a prefix:
 * the server's ("secret.newcomer.stone.") or, for someone in test mode, their own ("secret.newcomer.&lt;uuid&gt;.stone.").
 * Keys: {@code found} / {@code found.by} (the first whisper at the stone: from then the stone shows its broken lines),
 * {@code date} / {@code what} (glyph flags "1010…"), {@code date.by} / {@code what.by} (who brought shards),
 * {@code name} (who engraved it), {@code whole} (the night all three were back), {@code witness},
 * {@code witness.name}, {@code witness.others}.
 */
final class Record {

    /** Where it is kept (eco.kv; a map in tests). */
    interface Keep {
        String get(String key);

        void set(String key, String value);

        void remove(String key);
    }

    /**
     * What was lost: his name and its other spellings, the day (yyyy-MM-dd; "" while the server does not know it yet)
     * and what he solved.
     */
    record Truth(String name, List<String> alias, String date, String what) {

        static final String NAME = "rmama", WHAT = "꺼지지 않는 등불";
        static final List<String> ALIAS = List.of("그믐");
        /** He solved it on the server's thirteenth day: the earliest first join and twelve days. */
        static final int DAYS_AFTER_OPENING = 12;

        String of(Shards.Line line) {
            return line == Shards.Line.DATE ? date : what;
        }

        /** The day when nobody set one: the thirteenth day counted from the earliest first join (ms), in that zone. */
        static String dateFrom(long earliestJoin, ZoneId zone) {
            return Instant.ofEpochMilli(earliestJoin).atZone(zone).toLocalDate().plusDays(DAYS_AFTER_OPENING).toString();
        }

        /** A configured day as it is used: a readable yyyy-MM-dd, else "" (not set). */
        static String date(String configured) {
            if (configured == null || configured.isBlank()) return "";
            try {
                return LocalDate.parse(configured.strip()).toString();
            } catch (RuntimeException e) {
                return "";
            }
        }
    }

    private final Keep keep;
    private final String prefix;
    private final Truth truth;

    Record(Keep keep, String prefix, Truth truth) {
        this.keep = keep;
        this.prefix = prefix;
        this.truth = truth;
    }

    Truth truth() {
        return truth;
    }

    private String get(String k) {
        return keep.get(prefix + k);
    }

    private void set(String k, String v) {
        keep.set(prefix + k, v);
    }

    // ---- found

    boolean found() {
        return get("found") != null;
    }

    /** The first whisper at his stone; false when it was found before. */
    boolean find(UUID by, long now) {
        if (found()) return false;
        set("found", String.valueOf(now));
        if (by != null) set("found.by", by.toString());
        return true;
    }

    // ---- the two lines of shards

    boolean[] have(Shards.Line line) {
        return Shards.bits(get(line.id), Shards.glyphs(truth.of(line)));
    }

    boolean whole(Shards.Line line) {
        return Shards.whole(have(line));
    }

    /** The line as the stone shows it now. */
    String shown(Shards.Line line) {
        String full = truth.of(line);
        return full.isEmpty() ? String.valueOf(Shards.GONE).repeat(10) : Shards.render(full, have(line));
    }

    /**
     * A shard goes in: how many glyphs it brought (0: nothing new, it is handed back; -1: that line cannot be read yet,
     * the day is not known). Whoever brought something is remembered.
     */
    int add(Shards.Shard s, UUID by) {
        if (truth.of(s.line()).isEmpty()) return -1;
        boolean[] have = have(s.line());
        int fresh = Shards.fill(have, s.kind());
        if (fresh <= 0) return 0;
        set(s.line().id, Shards.bits(have));
        if (by != null) {
            Set<String> who = new LinkedHashSet<>(ids(s.line().id + ".by"));
            who.add(by.toString());
            set(s.line().id + ".by", String.join(",", who));
        }
        return fresh;
    }

    private List<String> ids(String key) {
        String v = get(key);
        List<String> out = new ArrayList<>();
        if (v != null) for (String s : v.split(",")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    List<UUID> givers(Shards.Line line) {
        List<UUID> out = new ArrayList<>();
        for (String s : ids(line.id + ".by")) uuid(s).ifPresent(out::add);
        return out;
    }

    boolean gave(UUID u) {
        for (Shards.Line l : Shards.Line.values()) if (givers(l).contains(u)) return true;
        return false;
    }

    /** An admin's 줄 채우기 / 비우기. */
    void force(Shards.Line line, boolean whole) {
        if (!whole) {
            keep.remove(prefix + line.id);
            keep.remove(prefix + line.id + ".by");
            return;
        }
        boolean[] all = new boolean[Shards.glyphs(truth.of(line))];
        java.util.Arrays.fill(all, true);
        set(line.id, Shards.bits(all));
    }

    // ---- the name

    boolean named() {
        return get("name") != null;
    }

    Optional<UUID> namer() {
        return uuid(get("name"));
    }

    /** The name is engraved (by: who held the name tag; null: an admin). False when it already stood there. */
    boolean name(UUID by) {
        if (named()) return false;
        set("name", by == null ? "admin" : by.toString());
        return true;
    }

    void unname() {
        keep.remove(prefix + "name");
    }

    // ---- all of it

    /** How many of the three lines (name, day, what) are back. */
    int lines() {
        return (named() ? 1 : 0) + (whole(Shards.Line.DATE) ? 1 : 0) + (whole(Shards.Line.WHAT) ? 1 : 0);
    }

    boolean restored() {
        return lines() == 3;
    }

    /** The night everything was back (null: not yet). He stands at the stone from the night after. */
    String wholeNight() {
        return get("whole");
    }

    void wholeNight(String night) {
        if (night == null) keep.remove(prefix + "whole");
        else set("whole", night);
    }

    /** Whether he waits at his stone on this night: all three lines back, on a night after the one they came back in. */
    boolean waiting(String night) {
        String w = wholeNight();
        return restored() && w != null && night != null && night.compareTo(w) > 0;
    }

    // ---- the witness

    void witness(UUID who, String name, int others) {
        set("witness", who.toString());
        set("witness.name", name);
        set("witness.others", String.valueOf(Math.max(0, others)));
    }

    Optional<UUID> witness() {
        return uuid(get("witness"));
    }

    /** "증인 Steve 외 2명" / "증인 Steve" (plain). */
    static String witnessLine(String name, int others) {
        return "증인 " + name + (others > 0 ? " 외 " + others + "명" : "");
    }

    /** Forgets everything (an admin's 서버초기화, or a tester's 초기화). */
    void clear() {
        for (String k : List.of("found", "found.by", "date", "date.by", "what", "what.by", "name", "whole", "witness", "witness.name", "witness.others")) {
            keep.remove(prefix + k);
        }
    }

    /** Forgets only the end (the witness): the lines stay as they were restored. */
    void unsign() {
        for (String k : List.of("witness", "witness.name", "witness.others")) keep.remove(prefix + k);
    }

    private static Optional<UUID> uuid(String s) {
        try {
            return s == null ? Optional.empty() : Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // what the stone says (MiniMessage; the text display over his stone)
    // ------------------------------------------------------------------------------------------

    /**
     * Nothing until someone has found it (it is the bare stone). Then four lines, broken where nothing is back yet: his
     * name, the day, what he solved, and the witness (flickering once the three above are whole). Signed: all four whole.
     */
    String text() {
        if (!found() && witness().isEmpty()) return "";
        String w = get("witness.name");
        if (w != null) {
            long others = 0;
            try {
                others = Long.parseLong(String.valueOf(get("witness.others")));
            } catch (NumberFormatException ignored) {
                // hand-edited
            }
            return "<gold>" + truth.name() + "\n<white>" + truth.date() + "\n<white>" + truth.what() + "\n<gray>" + esc(witnessLine(w, (int) others));
        }
        String gone = String.valueOf(Shards.GONE).repeat(3);
        return (named() ? "<white>" + truth.name() : "<dark_gray>" + Shards.blank(truth.name()))
                + "\n" + (whole(Shards.Line.DATE) ? "<white>" : "<gray>") + shown(Shards.Line.DATE)
                + "\n" + (whole(Shards.Line.WHAT) ? "<white>" : "<gray>") + shown(Shards.Line.WHAT)
                + "\n<dark_gray>증인 " + (restored() ? "<gray><obf>" + gone + "</obf>" : gone);
    }

    private static String esc(String s) {
        return s.replace("<", "\\<");
    }
}
