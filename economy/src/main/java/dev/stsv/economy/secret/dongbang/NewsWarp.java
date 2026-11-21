package dev.stsv.economy.secret.dongbang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How 동방해운 bends one player's copy of the stock news (lines are MiniMessage, oldest first). Pure: it only builds new
 * lists, the stored news and the prices are never touched. The same seed gives the same view, so reopening the list
 * during one offer shows the same wrong page again (it is not noise, it is a page from somewhere else).
 */
final class NewsWarp {

    private NewsWarp() {}

    /**
     * The offer page. head: the 1987 headline (shown first, as the oldest); fragment: the repeating line, set among the
     * real ones; stamp: put before every real line (the time they all claim); word: what a soaked word becomes; soaked /
     * thirteen: how many real lines lose a word / have their move read 13.0%; max: lines in all.
     */
    record Offer(String head, String fragment, String stamp, String word, int soaked, int thirteen, int max) {}

    static List<String> offer(List<String> real, Offer o, long seed) {
        Random r = new Random(seed);
        int room = Math.max(0, o.max() - (o.head().isEmpty() ? 0 : 1) - (o.fragment().isEmpty() ? 0 : 1));
        List<String> keep = new ArrayList<>(real.subList(Math.max(0, real.size() - room), real.size()));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keep.size(); i++) order.add(i);
        Collections.shuffle(order, r);
        int at = 0;
        for (int n = 0; n < o.soaked() && at < order.size(); n++, at++) keep.set(order.get(at), soak(keep.get(order.get(at)), o.word(), r));
        for (int n = 0; n < o.thirteen() && at < order.size(); n++, at++) keep.set(order.get(at), thirteen(keep.get(order.get(at))));
        keep.replaceAll(l -> o.stamp() + l);
        List<String> out = new ArrayList<>();
        if (!o.head().isEmpty()) out.add(o.head());
        if (!o.fragment().isEmpty()) {
            // somewhere in the middle, never first or last when there is anything around it
            int pos = keep.size() < 2 ? keep.size() : 1 + r.nextInt(keep.size() - 1);
            keep.add(pos, o.fragment());
        }
        out.addAll(keep);
        return out;
    }

    /** One line among the real ones (never the first or the last when there are two or more), keeping at most max lines. */
    static List<String> among(List<String> real, String line, Random r, int max) {
        List<String> out = new ArrayList<>(real);
        int pos = out.size() < 2 ? out.size() : 1 + r.nextInt(out.size() - 1);
        out.add(pos, line);
        while (out.size() > Math.max(1, max)) out.removeFirst();
        return out;
    }

    /** One line as the newest, keeping at most max lines. */
    static List<String> last(List<String> real, String line, int max) {
        List<String> out = new ArrayList<>(real);
        out.add(line);
        while (out.size() > Math.max(1, max)) out.removeFirst();
        return out;
    }

    /** Particles kept after a soaked word, mended for 물 (it ends in ㄹ): 를 → 을, 가 → 이, 는 → 은, 와 → 과. */
    private static final Map<Character, String> PARTICLES = Map.ofEntries(
            Map.entry('로', "로"), Map.entry('에', "에"), Map.entry('의', "의"), Map.entry('도', "도"), Map.entry('을', "을"),
            Map.entry('를', "을"), Map.entry('이', "이"), Map.entry('가', "이"), Map.entry('은', "은"), Map.entry('는', "은"),
            Map.entry('과', "과"), Map.entry('와', "과"));

    /**
     * One Korean word of the line (outside tags and outside the company name, which is the {@code <white>} part) becomes
     * the word ("갱도 침수로 작업 중단" → "갱도 물로 작업 중단"). A line with no such word comes back as it was.
     */
    static String soak(String line, String word, Random r) {
        List<int[]> runs = words(line);
        if (runs.isEmpty() || word.isEmpty()) return line;
        int[] w = runs.get(r.nextInt(runs.size()));
        int from = w[0], to = w[1];
        String tail = "";
        if (to - from >= 3) {
            String p = PARTICLES.get(line.charAt(to - 1));
            if (p != null) tail = p;
        }
        return line.substring(0, from) + word + tail + line.substring(to);
    }

    /** [from, to) of each run of two or more Hangul syllables outside tags and outside {@code <white>…</white>}. */
    static List<int[]> words(String line) {
        List<int[]> out = new ArrayList<>();
        boolean name = false;
        int i = 0, n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '<') {
                int end = line.indexOf('>', i);
                if (end < 0) break;
                String tag = line.substring(i + 1, end).trim();
                if (tag.equals("white")) name = true;
                else if (tag.equals("/white") || tag.equals("reset")) name = false;
                i = end + 1;
                continue;
            }
            if (hangul(c)) {
                int s = i;
                while (i < n && hangul(line.charAt(i))) i++;
                if (!name && i - s >= 2) out.add(new int[]{s, i});
                continue;
            }
            i++;
        }
        return out;
    }

    private static boolean hangul(char c) {
        return c >= '가' && c <= '힣';
    }

    private static final Pattern MOVE = Pattern.compile("([▲▼])\\d+(?:\\.\\d+)?%");

    /** The line's move reads 13.0% (the arrow and its colour stay). */
    static String thirteen(String line) {
        Matcher mt = MOVE.matcher(line);
        return mt.find() ? line.substring(0, mt.start()) + mt.group(1) + "13.0%" + line.substring(mt.end()) : line;
    }
}
