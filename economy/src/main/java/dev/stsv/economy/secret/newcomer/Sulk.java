package dev.stsv.economy.secret.newcomer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * When someone swears at him (newcomer.yml {@code chat.intents.rude} for the swear words, {@code sulk} for the rest), he
 * says one hurt line and is gone for the rest of that night: no questions, no small talk, no answer to a whisper, and no
 * leave line either (the server never says that he left). How a line is read, whether it is aimed at him, and which night
 * a sulk lasts for are here; what he does about it is in {@link Newcomer}.
 * <p>
 * A line is read in a loose form ({@link #loose}): lower case, anything that is not a letter (spaces, dots, digits, signs)
 * is a break, and runs of one-character pieces are joined ("시 발", "ㅅ.ㅂ", "ㅅ1ㅂ", "병1신" read as "시발", "ㅅㅂ", "ㅅㅂ",
 * "병신"), while longer words keep one space between them ("다시 발견" stays two words). The allow list is taken out of that
 * form first ("시발점", "병신년", "새끼 고양이"), then the swear words are looked for in what is left.
 */
final class Sulk {

    private Sulk() {}

    /** The hurt line he says (public chat, or a whisper back): rude's lines. No "?" in them. */
    static final List<String> HURT = List.of("아.. 네", "…죄송해요 저 갈게요", "저 그냥 나갈게요", "아 네.. 알겠어요",
            "그렇게까지 말할 필요는", "아.. 저 갈게요", "넵.. 죄송해요", "…저 오늘은 그만 할게요");

    /** The swear words (rude's match, read in the loose form). */
    static final List<String> SWEARS = List.of(
            "시발|씨발|씨빨|시빨|시벌|씨벌|시팔|씨팔|씨바ㄹ|시바ㄹ|ㅅㅂ|ㅆㅂ|ㅅ발|ㅆ발|시ㅂ|씨ㅂ|tlqkf|ssibal|sibal",
            "병신|븅신|빙신|ㅂㅅ|ㅄ|qudtls",
            "꺼져(?! ?(있|서|버|도|야|요|가|ㅆ))|꺼지라고|꺼지세요|꺼질래|ㄲㅈ",
            "닥쳐|닥치라고|닥치세요|닥칠래|ㄷㅊ",
            "좆|ㅈ같|ㅈ까|조까",
            "(존나|졸라|ㅈㄴ) ?(짜증|시끄|귀찮|못하|멍청|한심|답답|노잼|재미없|별로|이상)",
            "미친 ?(놈|넘|새끼|년|것)",
            "새끼|섀끼|쌔끼|색끼|ㅅㄲ|개새(?![로롭벽해우집])|개색",
            "지랄|ㅈㄹ|염병",
            "엿 ?먹",
            "죽어(라|버려|버리|$)|죽을래|(뒤|디)져(라|버려|$)|(뒤|디)질래",
            "짜증나게 ?(하|굴|좀|구|만)",
            "저리 ?가(?!면|서|요|니|는|야)",
            "^ㅗ+$|ㅗㅗ");

    /** What looks like a swear word but is not one (taken out first; read in the loose form). */
    static final List<String> ALLOW = List.of(
            "시발점|시발역|시발택시|시발 ?자동차",
            "병신년|병신 ?년도|병신 ?정변|병신 ?양요",
            "새끼 ?(고양이|강아지|돼지|여우|늑대|거북|오리|양|소|말|곰|판다|닭|토끼|동물|몹|손가락|발가락|줄)",
            "(고양이|강아지|돼지|여우|늑대|거북|오리|곰|판다|닭|토끼|동물|몹|새|물고기) ?새끼",
            "새끼를 ?낳|새끼 ?낳|새끼 ?쳤|새끼 ?치",
            "호박엿");

    /** What makes a public line one said to him (with his name; besides, any line just after one of his). */
    static final List<String> AIM = List.of("뉴비", "(^| )(너|넌|너가|네가|니|니가|얘|얘는|쟤|님|님아)( |$)", "너 ?같|니 ?같|너 ?따위|니 ?따위");

    /** The night after a sulk, now and then, before his first question (no "?"). */
    static final List<String> SORRY = List.of("어제는 죄송했어요", "어제 그냥 가서 죄송해요", "아 어제는 제가 좀..", "어제 일은 신경 쓰지 마세요");

    /** Patterns of a list (case is ignored; a pattern that cannot be read is taken as plain words). */
    static List<Pattern> patterns(Collection<String> l) {
        List<Pattern> out = new ArrayList<>();
        for (String s : l) {
            if (s == null || s.isBlank()) continue;
            try {
                out.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                out.add(Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
        }
        return List.copyOf(out);
    }

    private static boolean letter(int c) {
        return (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E) || (c >= 0x1100 && c <= 0x11FF) || (c >= 'a' && c <= 'z');
    }

    /** The words of a line: lower case, split on everything that is not a letter. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder w = new StringBuilder();
        String t = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < t.length(); ) {
            int c = t.codePointAt(i);
            i += Character.charCount(c);
            if (letter(c)) {
                w.appendCodePoint(c);
            } else if (!w.isEmpty()) {
                out.add(w.toString());
                w.setLength(0);
            }
        }
        if (!w.isEmpty()) out.add(w.toString());
        return out;
    }

    /** The words joined by one space ("ㅅ.ㅂ 너" → "ㅅ ㅂ 너"): how {@link #aimed} reads a line. */
    static String plain(String text) {
        return String.join(" ", words(text));
    }

    /** The loose form: like {@link #plain}, but runs of one-character words are joined ("시 발 놈아" → "시발 놈아"). */
    static String loose(String text) {
        List<String> ws = words(text);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) {
            String w = ws.get(i);
            boolean single = w.codePointCount(0, w.length()) == 1;
            boolean prevSingle = i > 0 && ws.get(i - 1).codePointCount(0, ws.get(i - 1).length()) == 1;
            if (i > 0 && !(single && prevSingle)) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    /** Whether a line swears (allow list taken out first). */
    static boolean insult(String text, List<Pattern> swears, List<Pattern> allow) {
        String s = loose(text);
        if (s.isEmpty()) return false;
        for (Pattern a : allow) s = a.matcher(s).replaceAll(" ");
        for (Pattern p : swears) if (p.matcher(s).find()) return true;
        return false;
    }

    /** Whether a public line is said to him by its words: his name, or one of the aim patterns. */
    static boolean aimed(String text, String name, List<Pattern> aim) {
        if (Talk.mentions(text, name)) return true;
        String s = plain(text);
        for (Pattern p : aim) if (p.matcher(s).find()) return true;
        return false;
    }

    /**
     * The night a sulk at this moment is for: the open night, or (between nights) the one that opens next — sworn at in a
     * whisper during the day, he does not come that night.
     */
    static String key(LocalDateTime now, int from, int to) {
        return Schedule.next(now, from, to).toString();
    }

    /**
     * Whether he is sulking at this moment, saved: the night of his last sulk (or null). While a night is open, only that
     * night; between nights, both the night just gone (it lasts until the next one opens) and the one about to open.
     */
    static boolean sulking(String saved, LocalDateTime now, int from, int to) {
        if (saved == null || saved.isBlank()) return false;
        Optional<LocalDate> open = Schedule.night(now, from, to);
        if (open.isPresent()) return open.get().toString().equals(saved);
        LocalDate next = Schedule.next(now, from, to);
        return next.toString().equals(saved) || next.minusDays(1).toString().equals(saved);
    }

    /** Whether the night that opens (night) comes right after a sulky one (saved): a sheepish word may come first. */
    static boolean dayAfter(String saved, String night) {
        if (saved == null || night == null) return false;
        try {
            return LocalDate.parse(night).minusDays(1).toString().equals(saved);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
