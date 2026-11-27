package dev.stsv.economy.secret.newcomer;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * His one slip a night with the 한/영 key: in the middle of a Korean line a single Latin letter, put right at once ("아
 * 한영키"). The letters, night after night, spell his name (r · m · a · m · a, then from the start again). Everyone reads
 * it in public chat, so nothing has to be collected. Pure.
 */
final class Hanyoung {

    private Hanyoung() {}

    static final List<String> LINES = List.of("아 오늘도 사람 많네요", "저 아까부터 구경만 하고 있어요", "여기 밤에는 조용하네요 ㅎㅎ",
            "다들 뭐하고 계세요 저는 그냥 있어요", "아 손이 시려워서 자꾸 틀려요", "저 오늘은 좀 일찍 왔어요", "근데 여기 밤에 몹 많아요 ㅠ",
            "저 오늘도 그냥 구경 왔어요");
    static final List<String> FIXES = List.of("아 한영키", "한영키 ㅋㅋ", "아 한영키 또");

    /** The letter of the n-th slip (0 = the first night). */
    static char letter(String name, long n) {
        if (name == null || name.isEmpty()) return 'r';
        return Character.toLowerCase(name.charAt((int) Math.floorMod(n, (long) name.length())));
    }

    /** The key a word begins with on a Korean two-set keyboard (ㄱ → r, ㅁ → a, …), or 0. */
    static char key(String word) {
        String k = Filter.keys(word.isEmpty() ? "" : word.substring(0, 1));
        return k.isEmpty() ? 0 : k.charAt(0);
    }

    /**
     * The line as he typed it: the letter stuck in front of one of its words, never the first (and never the last
     * character). A line of one word gets it after its first character. A real slip looks like this: the key pressed while
     * still on English, then the switch and the whole word typed again ("r구경", "a많네요"), so a word that begins with
     * that key is taken when there is one.
     */
    static String slip(String line, char letter, RandomGenerator r) {
        List<Integer> starts = new ArrayList<>(), fitting = new ArrayList<>();
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i - 1) == ' ' && line.charAt(i) != ' ') {
                starts.add(i);
                if (key(line.substring(i)) == Character.toLowerCase(letter)) fitting.add(i);
            }
        }
        List<Integer> from = fitting.isEmpty() ? starts : fitting;
        int at = from.isEmpty() ? Math.min(1, line.length()) : from.get(r.nextInt(from.size()));
        return line.substring(0, at) + letter + line.substring(at);
    }

    /** A line to slip in: one with a word that begins with that key when the pool has one (else any), not said lately. */
    static String line(List<String> pool, char letter, List<String> avoid, RandomGenerator r) {
        List<String> fitting = new ArrayList<>();
        for (String l : pool) {
            String[] w = l.split(" ");
            for (int i = 1; i < w.length; i++) {
                if (!w[i].isEmpty() && key(w[i]) == Character.toLowerCase(letter)) {
                    fitting.add(l);
                    break;
                }
            }
        }
        return Talk.pick(fitting.isEmpty() ? pool : fitting, avoid, r);
    }
}
