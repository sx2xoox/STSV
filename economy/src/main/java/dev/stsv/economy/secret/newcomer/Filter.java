package dev.stsv.economy.secret.newcomer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What the model wrote, before he says it. A line that breaks a rule is thrown away and his scripted line is said in its
 * place: in public chat no "?" (only his five questions have one), never his name or its other spelling, no numbers that
 * could be the day, nothing about the lamp before that line is back on the stone, at most {@link #MAX} characters, no
 * English sentence, no emoji, no markup, no swearing, one line. Pure.
 */
final class Filter {

    private Filter() {}

    static final int MAX = 40;

    /**
     * What may not get out. name, alias: what he was called; lampKnown: the "what" line is whole (then, and only then, he
     * may speak of the lamp); stoneKnown: someone has found his stone (before that he never names it in public);
     * publicLine: public chat (no "?"); max: the longest line; swear: whether a line is swearing.
     */
    record Rules(String name, List<String> alias, boolean lampKnown, boolean stoneKnown, boolean publicLine, int max, Predicate<String> swear) {

        static Rules chat(Record.Truth t, boolean lampKnown, boolean stoneKnown, Predicate<String> swear) {
            return new Rules(t.name(), t.alias(), lampKnown, stoneKnown, true, MAX, swear);
        }

        /** A whisper, or a line of a letter: a "?" is allowed and it may be a little longer. */
        static Rules whisper(Record.Truth t, boolean lampKnown, Predicate<String> swear) {
            return new Rules(t.name(), t.alias(), lampKnown, true, false, 60, swear);
        }
    }

    /** Only letters and digits, lower case: "R.m a_m-a" reads "rmama". */
    static String squeezed(String s) {
        StringBuilder b = new StringBuilder();
        s.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(b::appendCodePoint);
        return b.toString();
    }

    private static boolean emoji(int c) {
        return c >= 0x1F000 || (c >= 0x2600 && c <= 0x27BF) || (c >= 0x2190 && c <= 0x21FF) || (c >= 0x2B00 && c <= 0x2BFF)
                || c == 0xFE0F || c == 0x200D || (c >= 0xE000 && c <= 0xF8FF);
    }

    /** Why a line may not be said, or null when it may. */
    static String refuse(String line, Rules r) {
        if (line == null || line.isBlank()) return "빈 줄";
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return "여러 줄";
        if (line.codePointCount(0, line.length()) > r.max()) return "너무 김";
        if (r.publicLine() && (line.contains("?") || line.contains("？") || line.contains("¿"))) return "물음표";
        String sq = squeezed(line);
        if (r.name() != null && !r.name().isBlank() && sq.contains(squeezed(r.name()))) return "이름";
        for (String a : r.alias()) if (a != null && !a.isBlank() && sq.contains(squeezed(a))) return "이름";
        int digits = 0, latin = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
                if (i + 1 < line.length() && "년월일".indexOf(line.charAt(i + 1)) >= 0) return "날짜";
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        if (digits >= 2) return "날짜";
        if (latin > 6) return "영어";
        if (!r.lampKnown() && (sq.contains("등불") || sq.contains("꺼지지않는") || sq.contains("랜턴") || sq.contains("lantern"))) return "등불";
        if (r.publicLine() && !r.stoneKnown() && (sq.contains("비석") || sq.contains("기념비") || sq.contains("열세번째") || sq.contains("묘비"))) return "비석";
        if (line.codePoints().anyMatch(Filter::emoji)) return "이모지";
        if (line.contains("**") || line.contains("`") || line.contains("<") || line.contains(">") || line.contains("[") || line.contains("]")
                || line.contains("__") || line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ") || line.contains("§")) return "꾸밈";
        if (r.swear() != null && r.swear().test(line)) return "욕";
        return null;
    }

    /**
     * The line as he says it (trimmed, quotes the model put round it taken off), or empty when it may not be said.
     */
    static Optional<String> clean(String raw, Rules r) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        while (s.length() >= 2 && "\"'“”‘’「」".indexOf(s.charAt(0)) >= 0 && "\"'“”‘’「」".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return refuse(s, r) == null ? Optional.of(s) : Optional.empty();
    }
}
