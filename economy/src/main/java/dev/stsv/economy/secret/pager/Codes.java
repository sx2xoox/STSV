package dev.stsv.economy.secret.pager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure parts of 울리는 삐삐 (unit-tested): the tape's 초성, what the little screen shows (the last few messages, the
 * newest brightest), the clock of that evening, the years of "100" that scroll by at the end, how long the 8282 run
 * may take, and what the live pager may send.
 */
final class Codes {

    private Codes() {}

    // ------------------------------------------------------------------------------------------
    // the tape: a stop's name as its initial consonants
    // ------------------------------------------------------------------------------------------

    private static final String INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

    /** "등대 앞" → "ㄷㄷ ㅇ": every Hangul syllable as its first consonant; spaces and anything else stay as they are. */
    static String chosung(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) out.append(INITIALS.charAt((c - 0xAC00) / (21 * 28)));
            else out.append(c);
        }
        return out.toString();
    }

    /** A stop's name as the tape has it: without a "정류장" at its end (the tape says that word itself), trimmed. */
    static String stopWord(String name) {
        String n = name == null ? "" : name.trim();
        if (n.endsWith("정류장") && n.length() > 3) n = n.substring(0, n.length() - 3).trim();
        return n;
    }

    // ------------------------------------------------------------------------------------------
    // the screen
    // ------------------------------------------------------------------------------------------

    /** One message on the screen: when ("1997.11.29 17:40", "어제", or nothing) and the digits. */
    record Msg(String stamp, String code) {

        String text() {
            return stamp == null || stamp.isEmpty() ? code : "[" + stamp + "] " + code;
        }
    }

    /** The messages as one saved value: "stamp|code;stamp|code" (oldest first). */
    static String encode(List<Msg> msgs) {
        StringBuilder out = new StringBuilder();
        for (Msg m : msgs) {
            if (!out.isEmpty()) out.append(';');
            out.append(clean(m.stamp())).append('|').append(clean(m.code()));
        }
        return out.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(";", "").replace("|", "");
    }

    static List<Msg> decode(String saved) {
        List<Msg> out = new ArrayList<>();
        if (saved == null || saved.isBlank()) return out;
        for (String part : saved.split(";")) {
            int bar = part.indexOf('|');
            if (bar < 0 || bar == part.length() - 1) continue;
            out.add(new Msg(part.substring(0, bar), part.substring(bar + 1)));
        }
        return out;
    }

    /** The list with one more message, keeping only the last {@code keep}. */
    static List<Msg> push(List<Msg> msgs, Msg m, int keep) {
        List<Msg> out = new ArrayList<>(msgs);
        out.add(m);
        int k = Math.max(1, keep);
        return out.size() > k ? new ArrayList<>(out.subList(out.size() - k, out.size())) : out;
    }

    /**
     * The screen as lore lines (MiniMessage): the newest message on top in the first colour, each older one below it in
     * the next (dimmer) colour, at most {@code keep} lines; an empty screen is the {@code empty} line.
     */
    static List<String> screen(List<Msg> msgs, int keep, List<String> colors, String empty) {
        List<String> out = new ArrayList<>();
        if (msgs.isEmpty()) {
            out.add(empty);
            return out;
        }
        int k = Math.max(1, keep);
        for (int i = msgs.size() - 1, n = 0; i >= 0 && n < k; i--, n++) {
            String color = colors.isEmpty() ? "#9fd8a0" : colors.get(Math.min(n, colors.size() - 1));
            out.add("<" + color + ">" + msgs.get(i).text());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // that evening's clock
    // ------------------------------------------------------------------------------------------

    /** The first message: 17:40 (minutes of 1997.11.29). */
    static final int EVENING = 17 * 60 + 40;
    /** The clock never passes this before the turn (the first "100" is 23:58). */
    static final int LATEST = 23 * 60 + 50;

    /** The clock a little later, never past {@link #LATEST} and never going back. */
    static int advance(int minutes, int add) {
        return Math.min(LATEST, Math.max(minutes, EVENING) + Math.max(0, add));
    }

    static String stamp(int minutes) {
        int m = Math.max(0, Math.min(24 * 60 - 1, minutes));
        return String.format("1997.11.29 %02d:%02d", m / 60, m % 60);
    }

    // ------------------------------------------------------------------------------------------
    // the turn: 100, once a year
    // ------------------------------------------------------------------------------------------

    /**
     * What scrolls by after the knocking: 100 that night, 100 two days later, then 100 on every 11.29 from 1998 to the
     * last one that has passed (last year's; this year's once 11.30 is over, for the day after it is "어제" itself), and
     * finally, yesterday, 20000.
     */
    static List<Msg> scroll(LocalDate today) {
        List<Msg> out = new ArrayList<>();
        out.add(new Msg("1997.11.29 23:58", "100"));
        out.add(new Msg("1997.12.01", "100"));
        int last = today.getYear() - 1;
        if (today.isAfter(LocalDate.of(today.getYear(), 11, 30))) last = today.getYear();
        for (int y = 1998; y <= last; y++) out.add(new Msg(y + ".11.29", "100"));
        out.add(new Msg("어제", "20000"));
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // 8282
    // ------------------------------------------------------------------------------------------

    /** How long the run to the gate may take: the base, a second more for every 4 blocks beyond the least distance, capped. */
    static int rushSeconds(int base, double minDistance, double distance, int max) {
        double extra = Double.isFinite(distance) ? Math.max(0, distance - Math.max(0, minDistance)) / 4.0 : Double.MAX_VALUE;
        long s = (long) Math.min((double) Integer.MAX_VALUE, base + Math.floor(extra));
        return (int) Math.max(1, Math.min(Math.max(base, max), s));
    }

    /** How long until 8282 comes again: shorter and shorter as the time runs out. */
    static int repeatGap(long secondsLeft) {
        if (secondsLeft > 120) return 45;
        if (secondsLeft > 60) return 25;
        if (secondsLeft > 30) return 12;
        if (secondsLeft > 10) return 6;
        return 3;
    }

    // ------------------------------------------------------------------------------------------
    // the live pager
    // ------------------------------------------------------------------------------------------

    static final int MAX_DIGITS = 12;

    /** What a pager can show: 1..12 digits and nothing else. The digits, or null. */
    static String digits(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        if (t.isEmpty() || t.length() > MAX_DIGITS) return null;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) < '0' || t.charAt(i) > '9') return null;
        return t;
    }

    /** Who and what: "닉네임 8282". digits is null when the last word is not something a pager can show. */
    record Page(String name, String digits) {}

    /** "받는 사람 숫자" (the name may have spaces: the last word is the number). Null when there are not two words. */
    static Page parsePage(String typed) {
        if (typed == null) return null;
        String t = typed.trim();
        int cut = -1;
        for (int i = t.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(t.charAt(i))) {
                cut = i;
                break;
            }
        }
        if (cut <= 0) return null;
        String name = t.substring(0, cut).trim();
        return name.isEmpty() ? null : new Page(name, digits(t.substring(cut + 1)));
    }

    /** Which of n stops is this player's: always the same one for the same player. */
    static int pick(long most, long least, int n) {
        return n <= 0 ? -1 : (int) Math.floorMod(most ^ (least * 31), (long) n);
    }
}
