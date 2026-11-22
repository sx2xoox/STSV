package dev.stsv.economy.secret.pager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 울리는 삐삐: the tape's 초성, the screen (the last five, newest on top), that evening's clock, the years of 100 that
 * scroll by, the 8282 run's time, what the live pager may send, and pager.yml (every line the code reads; every errand
 * item exists).
 */
class PagerTest {

    static final Path PACK = Path.of(System.getProperty("stsv.content")).resolve("economy");

    @Test
    void chosungOfAStopName() {
        assertEquals("ㄷㄷ ㅇ", Codes.chosung("등대 앞"));
        assertEquals("ㅂㄷㄱ", Codes.chosung("바닷가"));
        assertEquals("ㄲㄸㅃㅆㅉ", Codes.chosung("까따빠싸짜"));
        assertEquals("ㅎㅎ", Codes.chosung("힣하"));
        assertEquals("", Codes.chosung(""));
        assertEquals("", Codes.chosung(null));
    }

    @Test
    void chosungKeepsSpacesAndWhatIsNotHangul() {
        assertEquals("3ㅂ ㅅㄱㄹ (A)", Codes.chosung("3번 삼거리 (A)"));
        assertEquals("Terminal 2", Codes.chosung("Terminal 2"));
        assertEquals("ㅅㅈ  ㅇ", Codes.chosung("시장  앞"));
        assertEquals("ㄱㅏ", Codes.chosung("ㄱㅏ"));   // loose jamo stay as they are
    }

    @Test
    void theTapeSaysStopItself() {
        assertEquals("등대 앞", Codes.stopWord("등대 앞 정류장"));
        assertEquals("등대", Codes.stopWord(" 등대정류장 "));
        assertEquals("정류장", Codes.stopWord("정류장"));
        assertEquals("", Codes.stopWord(null));
    }

    @Test
    void theScreenKeepsTheLastFiveNewestOnTop() {
        List<Codes.Msg> msgs = new ArrayList<>();
        for (int i = 1; i <= 8; i++) msgs = Codes.push(msgs, new Codes.Msg("1997.11.29 18:0" + i, String.valueOf(i)), 5);
        assertEquals(5, msgs.size());
        assertEquals("4", msgs.getFirst().code());
        assertEquals("8", msgs.getLast().code());
        List<String> colors = List.of("#a", "#b", "#c");
        List<String> lines = Codes.screen(msgs, 5, colors, "<gray>empty");
        assertEquals(5, lines.size());
        assertEquals("<#a>[1997.11.29 18:08] 8", lines.get(0));
        assertEquals("<#b>[1997.11.29 18:07] 7", lines.get(1));
        assertEquals("<#c>[1997.11.29 18:06] 6", lines.get(2));
        assertEquals("<#c>[1997.11.29 18:04] 4", lines.get(4));   // past the last colour: the dimmest stays
        assertEquals(3, Codes.screen(msgs, 3, colors, "x").size());
    }

    @Test
    void anEmptyScreenAndAMessageWithNoTime() {
        assertEquals(List.of("<gray>empty"), Codes.screen(List.of(), 5, List.of("#a"), "<gray>empty"));
        assertEquals("…", new Codes.Msg("", "…").text());
        assertEquals("[어제] 20000", new Codes.Msg("어제", "20000").text());
        assertEquals(List.of("<#a>…"), Codes.screen(List.of(new Codes.Msg("", "…")), 5, List.of("#a"), "x"));
    }

    @Test
    void theScreenSavesAndReadsBack() {
        List<Codes.Msg> msgs = List.of(new Codes.Msg("1997.11.29 17:40", "045"), new Codes.Msg("1997.11.29 17:44", "1004"), new Codes.Msg("", "…"),
                new Codes.Msg("어제", "20000"));
        assertEquals(msgs, Codes.decode(Codes.encode(msgs)));
        assertTrue(Codes.decode(null).isEmpty());
        assertTrue(Codes.decode("").isEmpty());
        assertTrue(Codes.decode("garbage").isEmpty());
        assertEquals(1, Codes.decode("a|1;broken;|").size());
    }

    @Test
    void thatEveningsClockOnlyWalksForward() {
        assertEquals("1997.11.29 17:40", Codes.stamp(Codes.EVENING));
        assertEquals("1997.11.29 18:05", Codes.stamp(Codes.advance(Codes.EVENING, 25)));
        assertEquals(Codes.EVENING, Codes.advance(0, 0));   // never before the first message
        assertEquals(Codes.EVENING + 10, Codes.advance(Codes.EVENING + 10, -5));
        int c = Codes.EVENING;
        for (int i = 0; i < 100; i++) c = Codes.advance(c, 40);
        assertEquals(Codes.LATEST, c);
        assertEquals("1997.11.29 23:50", Codes.stamp(c));   // still before the first 100 (23:58)
    }

    @Test
    void oneHundredOnceAYearThenYesterday() {
        List<Codes.Msg> s = Codes.scroll(LocalDate.of(2025, 6, 10));
        assertEquals(new Codes.Msg("1997.11.29 23:58", "100"), s.get(0));
        assertEquals(new Codes.Msg("1997.12.01", "100"), s.get(1));
        assertEquals(new Codes.Msg("1998.11.29", "100"), s.get(2));
        assertEquals(new Codes.Msg("2024.11.29", "100"), s.get(s.size() - 2));
        assertEquals(new Codes.Msg("어제", "20000"), s.getLast());
        assertEquals(2 + (2024 - 1998 + 1) + 1, s.size());
        for (int i = 0; i < s.size() - 1; i++) assertEquals("100", s.get(i).code());
        // every year once, in order
        for (int i = 2; i < s.size() - 1; i++) assertEquals((1998 + i - 2) + ".11.29", s.get(i).stamp());
    }

    @Test
    void thisYearsDayCountsOnlyOnceItIsWellPast() {
        assertEquals("2025.11.29", last100(LocalDate.of(2026, 11, 22)));
        assertEquals("2025.11.29", last100(LocalDate.of(2026, 11, 29)));
        assertEquals("2025.11.29", last100(LocalDate.of(2026, 11, 30)));   // "어제" is that day itself: 20000, not 100
        assertEquals("2026.11.29", last100(LocalDate.of(2026, 12, 1)));
        assertEquals("2026.11.29", last100(LocalDate.of(2027, 1, 1)));
    }

    private static String last100(LocalDate today) {
        List<Codes.Msg> s = Codes.scroll(today);
        return s.get(s.size() - 2).stamp();
    }

    @Test
    void theRunGetsASecondForEveryFourBlocksBeyondTheLeast() {
        assertEquals(240, Codes.rushSeconds(240, 300, 300, 900));
        assertEquals(240, Codes.rushSeconds(240, 300, 120, 900));   // nearer (an admin's jump): the base
        assertEquals(241, Codes.rushSeconds(240, 300, 304, 900));
        assertEquals(240, Codes.rushSeconds(240, 300, 303.9, 900));
        assertEquals(340, Codes.rushSeconds(240, 300, 700, 900));
        assertEquals(900, Codes.rushSeconds(240, 300, 100_000, 900));
        assertEquals(900, Codes.rushSeconds(240, 300, Double.POSITIVE_INFINITY, 900));   // another world
        assertEquals(240, Codes.rushSeconds(240, 300, 5000, 100));   // a cap under the base never shortens it
    }

    @Test
    void itComesAgainSoonerAsTimeRunsOut() {
        int before = Integer.MAX_VALUE;
        for (long left : new long[] {600, 120, 61, 31, 11, 5, 0}) {
            int gap = Codes.repeatGap(left);
            assertTrue(gap > 0 && gap <= before, "gap at " + left);
            before = gap;
        }
        assertTrue(Codes.repeatGap(600) > Codes.repeatGap(5));
    }

    @Test
    void aPagerShowsOnlyDigits() {
        assertEquals("8282", Codes.digits("8282"));
        assertEquals("045", Codes.digits(" 045 "));
        assertEquals("123456789012", Codes.digits("123456789012"));
        assertNull(Codes.digits("1234567890123"));
        assertNull(Codes.digits(""));
        assertNull(Codes.digits(null));
        assertNull(Codes.digits("82-82"));
        assertNull(Codes.digits("82 82"));
        assertNull(Codes.digits("팔이팔이"));
        assertNull(Codes.digits("８２８２"));   // full-width digits are not what a pager sends
        assertNull(Codes.digits("-1"));
    }

    @Test
    void whoAndWhat() {
        assertEquals(new Codes.Page("닉네임", "8282"), Codes.parsePage("닉네임 8282"));
        assertEquals(new Codes.Page("Steve", "045"), Codes.parsePage("  Steve   045 "));
        assertEquals(new Codes.Page("이름 둘", "1004"), Codes.parsePage("이름 둘 1004"));
        assertEquals(new Codes.Page("Steve", null), Codes.parsePage("Steve hello"));
        assertNull(Codes.parsePage("8282"));
        assertNull(Codes.parsePage(""));
        assertNull(Codes.parsePage(null));
    }

    @Test
    void onlyTheWordPutsThePagerAway() {
        assertTrue(Codes.cancel("취소"));
        assertTrue(Codes.cancel("  취소 "));
        assertTrue(Codes.cancel("Cancel"));
        assertFalse(Codes.cancel("취소해"));
        assertFalse(Codes.cancel("Steve 8282"));
        assertFalse(Codes.cancel(""));
        assertFalse(Codes.cancel(null));
        // "취소" alone is not a page either way (one word)
        assertNull(Codes.parsePage("취소"));
    }

    @Test
    void theRunsTimeDoesNotRunWhileTheyAreAway() {
        long now = 1_000_000L;
        assertEquals(90_000L, Codes.pause(now + 90_000L, now));
        assertEquals(0L, Codes.pause(now - 5_000L, now));   // it had already run out: nothing is left, nothing is owed
        // back an hour later: the same time is left
        long back = now + 3_600_000L, left = Codes.pause(now + 90_000L, now);
        assertEquals(back + 90_000L, back + left);
    }

    /** A written book's page: 114 px wide, 14 lines; Hangul 9 px, a space 4, anything else counted as 6 (9 beyond ASCII). */
    private static int bookLines(String page) {
        int lines = 0;
        for (String para : page.split("\n", -1)) {
            int x = 0;
            lines++;
            for (String word : para.split(" ")) {
                int w = 0;
                for (int i = 0; i < word.length(); i++) {
                    char c = word.charAt(i);
                    w += c < 0x80 ? 6 : 9;
                }
                if (x > 0 && x + 4 + w > 114) {
                    lines++;
                    x = 0;
                }
                x += (x > 0 ? 4 : 0) + w;
                while (x > 114) {   // one word longer than a line
                    lines++;
                    x -= 114;
                }
            }
        }
        return lines;
    }

    @Test
    void everyPageOfTheLetterFitsABooksPage() {
        assertEquals(1, bookLines("빵, 고맙습니다."));
        assertEquals(3, bookLines("가\n\n나"));
        assertEquals(2, bookLines("오늘 아침 우체통에서 아버지 삐삐를 찾았습니다."));
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/pager.yml").toFile());
        for (String page : y.getStringList("letter.pages")) {
            String plain = page.replace("{date}", "2026년 12월 31일").replace("{age}", "100").replace("{player}", "ABCDEFGHIJKLMNOP").replaceAll("<[^>]+>", "");
            assertTrue(bookLines(plain) <= 14, bookLines(plain) + " lines: " + plain);
        }
        assertTrue(y.getString("letter.title").length() <= 32, "a book's title holds 32 characters");
    }

    @Test
    void noLineSaysWhatANumberMeans() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/pager.yml").toFile());
        for (String k : y.getKeys(true)) {
            List<String> texts = new ArrayList<>();
            if (y.isString(k)) texts.add(y.getString(k));
            else if (y.isList(k)) for (Object o : y.getList(k)) texts.add(String.valueOf(o));
            for (String t : texts) {
                // said nowhere at all
                for (String meaning : List.of("빵 사", "펄펄", "빨리빨리", "SOS", "에스오에스", "천사", "이만", "구조", "뜻"))
                    assertFalse(t.contains(meaning), k + " explains a number: " + t);
                // and no line that shows one of the numbers says anything about it
                if (k.startsWith("codes.") || !t.matches("(?s).*(045|10288|8282|505|100|1004|20000).*")) continue;
                for (String meaning : List.of("빵", "열", "아파", "빨리", "살려", "돌아", "고마", "안녕", "잘 가"))
                    assertFalse(t.contains(meaning), k + " explains a number: " + t);
            }
        }
    }

    @Test
    void theSamePlayerAlwaysGetsTheSameStop() {
        Set<Integer> seen = new HashSet<>();
        for (long i = 0; i < 200; i++) {
            int a = Codes.pick(i * 7919, ~i * 104729, 7);
            assertTrue(a >= 0 && a < 7);
            assertEquals(a, Codes.pick(i * 7919, ~i * 104729, 7));
            seen.add(a);
        }
        assertEquals(7, seen.size());
        assertEquals(-1, Codes.pick(1, 2, 0));
        assertEquals(0, Codes.pick(Long.MIN_VALUE, Long.MAX_VALUE, 1));
    }

    @Test
    void settingsHaveEveryLineAndEveryErrandItemExists() throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(PACK.resolve("secrets/pager.yml").toFile());
        assertTrue(y.getBoolean("enabled"));
        assertEquals(0, y.getLong("rewards.money"));
        assertEquals("economy:pager_reply", y.getString("rewards.title"));
        for (String k : List.of("solved-line", "too-late", "pager.name", "pager.screen.empty", "pager.tape", "pager.tape-no-stop", "pager.live-name",
                "codes.bread", "codes.fever", "codes.hurry", "codes.sos", "codes.thanks", "codes.late", "beep.sound", "turn.knock-sound",
                "letter.title", "letter.author", "letter.mail-reason", "lines.vibrate", "lines.again", "lines.drop", "lines.back", "lines.refuse",
                "lines.dad", "lines.letter", "lines.crumble", "page.lines.prompt", "page.lines.sent", "page.lines.receive", "page.lines.bad",
                "page.lines.nobody", "page.lines.cooldown", "page.lines.retry", "page.lines.cancel", "page.lines.timeout"))
            assertFalse(y.getString(k, "").isBlank(), k);
        for (String k : List.of("start.chance", "start.max-y", "start.beep-seconds", "start.distance.min", "start.distance.max", "reply-seconds",
                "test-gap-seconds", "pager.return-after", "pager.screen.keep", "house.min-from-found", "house.show-range", "house.arrive-range",
                "house.first-seconds", "rush.min-distance", "rush.seconds", "rush.max-seconds", "turn.max-wait-seconds", "turn.knock-seconds", "turn.test-knock-seconds",
                "turn.scroll-seconds", "after.letter-seconds", "page.cooldown-seconds", "page.timeout-seconds", "page.enabled", "letter.born-year"))
            assertTrue(y.isSet(k), k);
        assertTrue(y.getDouble("start.chance") > 0 && y.getDouble("start.chance") <= 0.001, "rare");
        assertEquals(2, y.getIntegerList("gap-seconds").size());
        assertTrue(y.getStringList("pager.back").stream().anyMatch(l -> l.contains("{tape}")));
        assertTrue(y.getString("pager.tape").contains("{stop}"));
        assertFalse(y.getString("pager.tape-no-stop").contains("{stop}"));
        assertTrue(y.getStringList("pager.live-lore").stream().anyMatch(l -> l.contains("{player}")));
        assertEquals(y.getInt("pager.screen.keep"), y.getStringList("pager.screen.colors").size());
        assertTrue(y.getString("page.lines.receive").contains("{digits}"));
        assertTrue(y.getString("page.lines.sent").contains("{digits}"));
        // the prompt and the "not sent" line both say how to stop
        assertTrue(y.getString("page.lines.prompt").contains("취소"));
        assertTrue(y.getString("page.lines.retry").contains("취소"));
        assertTrue(y.getLong("page.timeout-seconds") >= 5 && y.getLong("page.timeout-seconds") <= 60);
        // the codes are what a pager can show (the "too late" one is the only thing that is not a number)
        for (String k : List.of("bread", "fever", "hurry", "sos", "thanks")) assertNotNull(Codes.digits(y.getString("codes." + k)), k);
        // a written book's page holds 14 short lines
        List<String> pages = y.getStringList("letter.pages");
        assertTrue(pages.size() >= 2 && pages.size() <= 3);
        for (String p : pages) assertTrue(p.replaceAll("<[^>]+>", "").length() <= 200, "page too long");
        assertTrue(String.join("\n", pages).contains("{age}"));
        // every errand item is a real one
        Set<String> items = new HashSet<>();
        try (Stream<Path> s = Files.list(PACK.resolve("items"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".yml")).toList())
                for (String k : YamlConfiguration.loadConfiguration(f.toFile()).getKeys(false)) items.add("economy:" + k);
        }
        assertFalse(y.getStringList("errands.bread").isEmpty());
        assertFalse(y.getStringList("errands.warm").isEmpty());
        for (String list : List.of("errands.bread", "errands.warm"))
            for (String id : y.getStringList(list)) assertTrue(id.equals("bread") || items.contains(id), list + ": " + id);
        // the house's blocks are plain block names
        for (String k : y.getConfigurationSection("house.blocks").getKeys(false))
            assertTrue(y.getString("house.blocks." + k).matches("[a-z_]+"), k);
    }
}
