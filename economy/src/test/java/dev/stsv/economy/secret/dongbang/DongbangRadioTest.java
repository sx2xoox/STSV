package dev.stsv.economy.secret.dongbang;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 동방해운's last radio log: the six lines carry the 본사 exactly, and reading them back gives it again. */
class DongbangRadioTest {

    static final List<String> T = List.of(
            "[교신 1/6] 여기는 동방호. 먼저 동서. {dir-x}으로…",
            "[교신 2/6] …{x-head}…",
            "[교신 3/6] …{x-tail}. 이상 동서. 다음 남북…",
            "[교신 4/6] …남북 숫자는 주가에 맡김. 어제 종가 그대로…",
            "{z} G [교신 5/6]",
            "[교신 6/6] …{dir-z}쪽으로. 이상.");

    @Test
    void example() {
        List<String> l = Radio.lines(-1352, 392, T, Radio.Words.DEFAULT);
        assertEquals("[교신 1/6] 여기는 동방호. 먼저 동서. 해 지는 쪽으로…", l.get(0));
        assertEquals("[교신 2/6] …하나, 셋…", l.get(1));
        assertEquals("[교신 3/6] …다섯, 둘. 이상 동서. 다음 남북…", l.get(2));
        assertEquals("392 G [교신 5/6]", l.get(4));
        assertEquals("[교신 6/6] …남쪽으로. 이상.", l.get(5));
        assertEquals("셋", Radio.head(-392));
        assertEquals("아홉, 둘", Radio.tail(392));
        assertEquals("공, 여덟", Radio.tail(1208));
        assertEquals("1,208 G [교신 5/6]", Radio.lines(8, -1208, T, Radio.Words.DEFAULT).get(4));
    }

    /** A player's reading: words back to digits, directions to signs. */
    private static int[] decode(List<String> l) {
        String digits = words(between(l.get(1), "…", "…")) + words(l.get(2).substring(l.get(2).indexOf('…') + 1, l.get(2).indexOf('.')));
        int x = Integer.parseInt(digits) * (l.get(0).contains("해 뜨는 쪽") ? 1 : -1);
        Matcher m = Pattern.compile("([\\d,]+) G").matcher(l.get(4));
        m.find();
        int z = Integer.parseInt(m.group(1).replace(",", "")) * (l.get(5).contains("남쪽") ? 1 : -1);
        return new int[]{x, z};
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a) + a.length();
        return s.substring(i, s.indexOf(b, i));
    }

    private static String words(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split(",")) {
            w = w.trim();
            for (int d = 0; d < 10; d++) if (Radio.DIGITS[d].equals(w)) b.append(d);
        }
        return b.toString();
    }

    @Test
    void everyAddressReadsBack() {
        Random r = new Random(1);
        for (int i = 0; i < 2000; i++) {
            int[] c = Ticker.candidates(r.nextLong(), 300, 1500, 1).getFirst();
            int[] back = decode(Radio.lines(c[0], c[1], T, Radio.Words.DEFAULT));
            assertEquals(c[0], back[0]);
            assertEquals(c[1], back[1]);
        }
    }

    @Test
    void found() {
        int f = Radio.mark(Radio.mark(0, 1), 5);
        assertEquals(2, Radio.count(f));
        assertEquals("1·5", Radio.list(f));
        assertEquals("-", Radio.list(0));
    }
}
