package dev.stsv.economy.secret.dongbang;

import java.util.ArrayList;
import java.util.List;

/**
 * 동방호's last radio log (마지막 교신): the 본사 address as six numbered lines. Pure (unit-tested).
 * <p>
 * The cipher is plain radio talk: east-west first, then north-south; digits are read one at a time in native Korean
 * (공, 하나, 둘 … 아홉); 해 뜨는 쪽 is east (+X), 해 지는 쪽 west (−X), 남 south (+Z), 북 north (−Z). The six lines are spread
 * over the places the holder already looks at:
 * <ol>
 *     <li>the notice: "먼저 동서. {해 뜨는 쪽|해 지는 쪽}으로…" (the sign of X)</li>
 *     <li>the news: the leading digits of |X| (all but the last two)</li>
 *     <li>the company window's info: the last two digits of |X|, "이상 동서. 다음 남북…"</li>
 *     <li>the news: "남북 숫자는 물에 젖어… 주가에 맡김. 어제 종가 그대로"</li>
 *     <li>the chart: the window's "어제" (yesterday's close) reads |Z| G, labelled [교신 5/6]</li>
 *     <li>the news: "{남|북}쪽으로. 현재 03시 33분. 이상." (the sign of Z)</li>
 * </ol>
 */
final class Radio {

    private Radio() {}

    static final String[] DIGITS = {"공", "하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉"};

    /** Where each line is found (index 0 = line 1). */
    enum Where { NOTICE, NEWS, WINDOW, CHART }

    static final Where[] WHERE = {Where.NOTICE, Where.NEWS, Where.WINDOW, Where.NEWS, Where.CHART, Where.NEWS};

    /** The news lines, in the order a holder's news shows them (line numbers, 1-based). */
    static final int[] NEWS = {2, 4, 6};

    /** Words: east, west, south, north. */
    record Words(String east, String west, String south, String north) {
        static final Words DEFAULT = new Words("해 뜨는 쪽", "해 지는 쪽", "남", "북");
    }

    /** Digits read one at a time: 1352 → "하나, 셋, 다섯, 둘". */
    static String read(String digits) {
        List<String> out = new ArrayList<>();
        for (char c : digits.toCharArray()) out.add(DIGITS[c - '0']);
        return String.join(", ", out);
    }

    /** All but the last two digits of |v| (|v| ≥ 100). */
    static String head(int v) {
        String s = String.valueOf(Math.abs(v));
        return read(s.substring(0, s.length() - 2));
    }

    /** The last two digits of |v|. */
    static String tail(int v) {
        String s = String.valueOf(Math.abs(v));
        return read(s.substring(s.length() - 2));
    }

    /**
     * The six lines for a 본사 at (x, z), from six templates with {dir-x}, {x-head}, {x-tail}, {dir-z}, {z} (|z| with
     * thousands separators, as the window shows a price).
     */
    static List<String> lines(int x, int z, List<String> templates, Words w) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String t = i < templates.size() ? templates.get(i) : "";
            out.add(t.replace("{dir-x}", x >= 0 ? w.east() : w.west())
                    .replace("{x-head}", head(x))
                    .replace("{x-tail}", tail(x))
                    .replace("{dir-z}", z >= 0 ? w.south() : w.north())
                    .replace("{z}", String.format(java.util.Locale.ROOT, "%,d", Math.abs(z))));
        }
        return out;
    }

    /** Found lines as a bit mask (bit i = line i + 1). */
    static int mark(int found, int line) {
        return found | 1 << (line - 1);
    }

    static int count(int found) {
        return Integer.bitCount(found & 0x3f);
    }

    /** "1·2·5" */
    static String list(int found) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 6; i++) if ((found & 1 << (i - 1)) != 0) out.add(String.valueOf(i));
        return out.isEmpty() ? "-" : String.join("·", out);
    }
}
