package dev.stsv.economy.secret.newcomer;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What the model wrote, before he says it. A line that breaks a rule is thrown away and his scripted line is said in its
 * place: in public chat no "?" (only his five questions have one), never his name or its other spelling, no numbers that
 * could be the day, nothing about the lamp before that line is back on the stone, at most {@link #MAX} characters, no
 * English sentence, no emoji, no markup, no swearing, one line. Pure.
 * <p>
 * The name and the lamp are looked for in {@link #keys}: the line as it would be typed on a Korean two-set keyboard, in
 * plain lower-case letters and digits. So "rmama", "그믐", "ㄱㅡㅁㅡㅁ", "RMAMA", "ｒｍａｍａ", "гмама" (look-alike letters),
 * "r m a m a", "그​믐" (a zero-width space) and "rㅡ믐" all read "rmama"; "등 불" and "ㄷㅡㅇㅂㅜㄹ" both read "emdqnf".
 */
final class Filter {

    private Filter() {}

    static final int MAX = 40;

    /** Other ways the name might come out (romanised, read aloud): checked like the name. */
    static final List<String> NAME_FORMS = List.of("geumeum", "keumeum", "geummeum", "르마마", "알마마", "알엠에이엠에이", "아르마마");
    /** The lamp in other words (before that line is back). */
    static final List<String> LAMP_FORMS = List.of("등불", "꺼지지않", "꺼지지 않", "안꺼지는", "안 꺼지는", "랜턴", "램프", "등잔", "lantern", "lamp", "deungbul");
    /** The stone, in public, before anyone found it. */
    static final List<String> STONE_FORMS = List.of("비석", "기념비", "열세번째", "열세 번째", "묘비", "13번째");
    /** A day or a month in Korean numerals ("삼월", "십삼일", "이천이십육년"). */
    private static final Pattern KOREAN_DATE = Pattern.compile("[일이삼사오육칠팔구십백천영공]{2,}\\s?(?:년|일)|[일이삼사오육칠팔구십]{1,3}월(?![요드급세])");

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

    // ---- the line as keys on a Korean two-set keyboard

    private static final String[] CHO = {"r", "r", "s", "e", "e", "f", "a", "q", "q", "t", "t", "d", "w", "w", "c", "z", "x", "v", "g"};
    private static final String[] JUNG = {"k", "o", "i", "o", "j", "p", "u", "p", "h", "hk", "ho", "hl", "y", "n", "nj", "np", "nl", "b", "m", "ml", "l"};
    private static final String[] JONG = {"", "r", "r", "rt", "s", "sw", "sg", "e", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg", "a", "q", "qt", "t", "t",
            "d", "w", "c", "z", "x", "v", "g"};
    /** Compatibility jamo ㄱ (U+3131) .. ㅣ (U+3163). */
    private static final String[] COMPAT = {"r", "r", "rt", "s", "sw", "sg", "e", "e", "f", "fr", "fa", "fq", "ft", "fx", "fv", "fg", "a", "q", "q", "qt",
            "t", "t", "d", "w", "w", "c", "z", "x", "v", "g", "k", "o", "i", "o", "j", "p", "u", "p", "h", "hk", "ho", "hl", "y", "n", "nj", "np", "nl", "b",
            "m", "ml", "l"};

    /** Latin and Cyrillic / Greek look-alikes, small capitals. */
    private static char plain(char c) {
        return switch (c) {
            case 'а', 'ɑ', 'α', 'ᴀ' -> 'a';
            case 'е', 'ε', 'ᴇ' -> 'e';
            case 'о', 'ο', 'σ', 'ᴏ' -> 'o';
            case 'р', 'ρ', 'ᴘ' -> 'p';
            case 'с', 'ϲ', 'ᴄ' -> 'c';
            case 'у', 'γ' -> 'y';
            case 'х', 'χ' -> 'x';
            case 'м', 'ᴍ' -> 'm';
            case 'г', 'ʀ', 'ɾ', 'ꭇ' -> 'r';
            case 'т', 'τ', 'ᴛ' -> 't';
            case 'к', 'κ', 'ᴋ' -> 'k';
            case 'н', 'ʜ' -> 'h';
            case 'в', 'ʙ' -> 'b';
            case 'ѕ' -> 's';
            case 'і', 'ι', 'ɪ' -> 'i';
            case 'ј' -> 'j';
            case 'ԁ' -> 'd';
            case 'ո', 'ռ' -> 'n';
            default -> c;
        };
    }

    /**
     * The line as keys: compatibility-normalised (full-width and other look-alike forms to plain ones), Hangul as the keys
     * that type it, look-alike letters as Latin ones, lower case, and nothing but a-z and 0-9 (spaces, dots, zero-width
     * characters and the rest gone).
     */
    static String keys(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int x = c - 0xAC00;
                b.append(CHO[x / 588]).append(JUNG[(x % 588) / 28]).append(JONG[x % 28]);
            } else if (c >= 0x1100 && c <= 0x1112) {
                b.append(CHO[c - 0x1100]);
            } else if (c >= 0x1161 && c <= 0x1175) {
                b.append(JUNG[c - 0x1161]);
            } else if (c >= 0x11A8 && c <= 0x11C2) {
                b.append(JONG[c - 0x11A8 + 1]);
            } else if (c >= 0x3131 && c <= 0x3163) {
                b.append(COMPAT[c - 0x3131]);
            } else if (Character.isDigit(c)) {
                b.append((char) ('0' + Character.digit(c, 10)));
            } else {
                char p = plain(c);
                if ((p >= 'a' && p <= 'z') || (p >= '0' && p <= '9')) b.append(p);
            }
        }
        return b.toString();
    }

    private static boolean names(String keys, String word) {
        if (word == null || word.isBlank()) return false;
        String k = keys(word);
        return k.length() >= 2 && keys.contains(k);
    }

    private static boolean emoji(int c) {
        return c >= 0x1F000 || (c >= 0x2600 && c <= 0x27BF) || (c >= 0x2190 && c <= 0x21FF) || (c >= 0x2B00 && c <= 0x2BFF)
                || c == 0xFE0F || c == 0x200D || (c >= 0xE000 && c <= 0xF8FF);
    }

    /** Characters nobody types: zero-width, direction marks, the BOM. */
    private static boolean invisible(int c) {
        return (c >= 0x200B && c <= 0x200F) || (c >= 0x202A && c <= 0x202E) || (c >= 0x2060 && c <= 0x2064) || c == 0xFEFF || c == 0x00AD;
    }

    /** Why a line may not be said, or null when it may. */
    static String refuse(String line, Rules r) {
        if (line == null || line.isBlank()) return "빈 줄";
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.indexOf(' ') >= 0 || line.indexOf(' ') >= 0) return "여러 줄";
        if (line.codePointCount(0, line.length()) > r.max()) return "너무 김";
        if (line.codePoints().anyMatch(Filter::invisible)) return "보이지 않는 글자";
        String norm = Normalizer.normalize(line, Normalizer.Form.NFKC);
        if (r.publicLine() && (norm.contains("?") || line.contains("？") || line.contains("¿") || line.contains("⁇") || line.contains("‽"))) return "물음표";
        String keys = keys(line);
        if (names(keys, r.name())) return "이름";
        for (String a : r.alias()) if (names(keys, a)) return "이름";
        for (String a : NAME_FORMS) if (names(keys, a)) return "이름";
        int digits = 0, latin = 0;
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (Character.isDigit(c)) {
                digits++;
                int j = i + 1;
                while (j < norm.length() && norm.charAt(j) == ' ') j++;
                if (j < norm.length() && "년월일".indexOf(norm.charAt(j)) >= 0) return "날짜";
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
        }
        if (digits >= 2) return "날짜";
        if (KOREAN_DATE.matcher(line).find()) return "날짜";
        if (latin > 6) return "영어";
        if (!r.lampKnown()) for (String a : LAMP_FORMS) if (names(keys, a)) return "등불";
        if (r.publicLine() && !r.stoneKnown()) for (String a : STONE_FORMS) if (names(keys, a)) return "비석";
        if (line.codePoints().anyMatch(Filter::emoji)) return "이모지";
        if (line.contains("**") || line.contains("`") || line.contains("<") || line.contains(">") || line.contains("[") || line.contains("]")
                || line.contains("__") || line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ") || line.contains("§")
                || line.contains("‹") || line.contains("›")) return "꾸밈";
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
