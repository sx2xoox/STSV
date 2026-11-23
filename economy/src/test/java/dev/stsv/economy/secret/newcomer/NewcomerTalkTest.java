package dev.stsv.economy.secret.newcomer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 새로 온 사람, how he talks: whichever five a night takes still spell his question, only those five have a "?", the
 * night's five are the same for the same date and none was asked in the three nights before, what a line of chat or a
 * whisper is about, his slips never touch a first character, and the settings file says what the code says.
 */
class NewcomerTalkTest {

    static List<Talk.Intent> all() {
        List<Talk.Intent> l = new ArrayList<>(Talk.CHAT);
        l.addAll(Talk.WHISPER);
        return l;
    }

    /** What nothing he says in public may name: the stone, the monument, the thirteenth. */
    static final Pattern GIVES_AWAY = Pattern.compile("비석|기념비|돌|묘|무덤|열세|13");

    static List<String> publicLines() {
        List<String> l = new ArrayList<>();
        for (Talk.Intent i : Talk.CHAT) {
            l.addAll(i.lines);
            l.addAll(i.follow);
            l.addAll(i.slip);
        }
        l.addAll(Newcomer.FIXES);
        return l;
    }

    // ---- the acrostic, whatever the night

    @Test
    void everyCombinationSpellsHisQuestion() {
        List<List<Questions.Entry>> pools = List.copyOf(Questions.DEFAULTS.values());
        assertEquals(5, pools.size());
        long combos = 0;
        int[] ix = new int[5];
        while (true) {
            for (boolean rain : new boolean[]{false, true}) {
                List<String> five = new ArrayList<>();
                for (int k = 0; k < 5; k++) five.add(pools.get(k).get(ix[k]).words(rain));
                assertTrue(Acrostic.spells(five, Newcomer.DEFAULT_ACROSTIC), five.toString());
            }
            combos++;
            int k = 4;
            while (k >= 0 && ++ix[k] == pools.get(k).size()) ix[k--] = 0;
            if (k < 0) break;
        }
        assertTrue(combos > 100_000, "combinations " + combos);
    }

    @Test
    void poolsAreBigEnoughAndEveryQuestionIsAQuestion() {
        for (Map.Entry<String, List<Questions.Entry>> e : Questions.DEFAULTS.entrySet()) {
            int n = e.getValue().size();
            assertTrue(n >= 8 && n <= 12, e.getKey() + ": " + n);
            Set<String> distinct = new HashSet<>();
            for (Questions.Entry q : e.getValue()) {
                assertTrue(Questions.valid(q, e.getKey()), q.toString());
                assertTrue(distinct.add(q.text()), "twice: " + q.text());
                for (String w : new String[]{q.text(), q.rain()}) {
                    if (w == null) continue;
                    assertTrue(w.endsWith("?"), w);
                    assertFalse(w.endsWith("."), "no full stop: " + w);
                    assertFalse(GIVES_AWAY.matcher(w).find(), "gives it away: " + w);
                    // a placeholder is never the first character (that one is the clue)
                    assertFalse(w.startsWith("{"), w);
                }
            }
        }
        assertTrue(Questions.DEFAULTS.values().stream().flatMap(List::stream).anyMatch(q -> q.rain() != null), "some have rain words");
        assertTrue(Questions.DEFAULTS.values().stream().flatMap(List::stream).anyMatch(q -> q.text().contains("{online}")));
        assertTrue(Questions.DEFAULTS.values().stream().flatMap(List::stream).anyMatch(q -> q.text().contains("{time}")));
    }

    @Test
    void onlyHisQuestionsHaveAQuestionMark() {
        for (String line : publicLines()) {
            assertFalse(line.contains("?"), "small talk with a ?: " + line);
            assertTrue(Talk.small(line));
            assertFalse(GIVES_AWAY.matcher(line).find(), "gives it away: " + line);
        }
        assertFalse(Talk.small("뭐해요?"));
        assertFalse(Talk.small(""));
    }

    @Test
    void placeholdersAreFilledIn() {
        assertEquals("내일도 이 새벽에 들어오시는 분 있어요?", Questions.fill("내일도 {time} 들어오시는 분 있어요?", Map.of("time", "이 새벽에", "online", "3")));
        assertEquals("내일 저녁엔 3명보다 많이 와요?", Questions.fill("내일 저녁엔 {online}명보다 많이 와요?", Map.of("online", "3")));
        assertEquals("새벽", Questions.time(2, "새벽", "밤", "시간"));
        assertEquals("밤", Questions.time(23, "새벽", "밤", "시간"));
        assertEquals("시간", Questions.time(15, "새벽", "밤", "시간"));
        Questions.Entry e = new Questions.Entry("비 오면 낚시 더 잘돼요?", "비 오는데 지금 낚시하면 뭐 더 잘 잡혀요?");
        assertEquals(e.text(), e.words(false));
        assertEquals(e.rain(), e.words(true));
        assertEquals("내일?!", new Questions.Entry("내일?!").words(true));
    }

    // ---- the night's five

    @Test
    void theNightsFiveAreTheSameForTheSameDate() {
        List<String> letters = Questions.letters(Newcomer.DEFAULT_ACROSTIC);
        LocalDate d = LocalDate.of(2026, 11, 22);
        for (int i = 0; i < 50; i++, d = d.plusDays(1)) {
            assertEquals(Questions.tonight(d, 13, letters, Questions.DEFAULTS), Questions.tonight(d, 13, letters, Questions.DEFAULTS));
        }
        int differ = 0;
        d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 100; i++, d = d.plusDays(1)) {
            if (!Questions.tonight(d, 13, letters, Questions.DEFAULTS).equals(Questions.tonight(d, 14, letters, Questions.DEFAULTS))) differ++;
        }
        assertTrue(differ > 90, "another seed, other nights: " + differ);
    }

    @Test
    void noQuestionWithinFourNights() {
        for (int n = 7; n <= 13; n++) {
            for (long seed : new long[]{13, 1, 99, -5}) {
                for (int salt : new int[]{'내', '디', 0}) {
                    Set<Integer> used = new HashSet<>();
                    for (long day = 19_000; day < 21_000; day++) {
                        int q = Questions.index(day, seed, salt, n);
                        assertTrue(q >= 0 && q < n);
                        used.add(q);
                        for (int back = 1; back <= 3; back++) {
                            assertNotEquals(Questions.index(day - back, seed, salt, n), q, "n " + n + " seed " + seed + " day " + day + " back " + back);
                        }
                    }
                    assertEquals(n, used.size(), "every question gets its nights");
                }
            }
        }
        // a small pool still works (it cannot always keep clear)
        for (long day = 0; day < 50; day++) {
            assertTrue(Questions.index(day, 13, 1, 3) < 3);
            assertEquals(0, Questions.index(day, 13, 1, 1));
        }
    }

    @Test
    void theRealPoolsOverThreeYears() {
        List<String> letters = Questions.letters(Newcomer.DEFAULT_ACROSTIC);
        LocalDate d = LocalDate.of(2026, 1, 1);
        List<List<Questions.Entry>> nights = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1100; i++, d = d.plusDays(1)) {
            List<Questions.Entry> five = Questions.tonight(d, 13, letters, Questions.DEFAULTS);
            assertEquals(5, five.size());
            List<String> texts = five.stream().map(Questions.Entry::text).toList();
            assertTrue(Acrostic.spells(texts, "내비석어디"));
            for (int back = 1; back <= 3 && back <= nights.size(); back++) {
                List<Questions.Entry> before = nights.get(nights.size() - back);
                for (int k = 0; k < 5; k++) assertNotEquals(before.get(k), five.get(k), d + " letter " + k);
            }
            nights.add(five);
            seen.addAll(texts);
        }
        assertEquals(Questions.DEFAULTS.values().stream().mapToInt(List::size).sum(), seen.size(), "every question is asked some night");
    }

    @Test
    void theFileIsReadAndBadLinesDropped() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("questions.가", List.of("가방 어디서 사요?", "나무 어디 있어요?", "가방 사요", Map.of("text", "가볍게 뭐 해요?", "rain", "가끔 비 와요?")));
        y.set("questions.나", List.of(Map.of("text", "나무요?", "rain", "비 와요?")));
        List<String> problems = new ArrayList<>();
        Map<String, List<Questions.Entry>> pools = Questions.read(y.getConfigurationSection("questions"), "가나다", problems);
        assertEquals(List.of(new Questions.Entry("가방 어디서 사요?"), new Questions.Entry("가볍게 뭐 해요?", "가끔 비 와요?")), pools.get("가"));
        assertEquals(List.of(), pools.get("나"), "the rain words broke the letter: dropped, and there is no default for 나");
        assertEquals(List.of(), pools.get("다"));
        assertEquals(5, problems.size(), problems.toString());   // no ?, wrong letter, wrong rain letter, 나 empty, 다 empty
        // the default letters fall back to the code's pools
        assertEquals(Questions.DEFAULTS.get("내"), Questions.read(null, "내", new ArrayList<>()).get("내"));
    }

    // ---- what a line is about

    static String chat(String line, boolean addressed, boolean afterQuestion) {
        return Talk.match(Talk.CHAT, line, addressed, afterQuestion).map(i -> i.id).orElse("-");
    }

    static String whisper(String line) {
        return Talk.match(Talk.WHISPER, line, true, false).map(i -> i.id).orElse("-");
    }

    @Test
    void whatChatIsAbout() {
        assertEquals("greet", chat("ㅎㅇ", false, false));
        assertEquals("greet", chat("안녕하세요~", false, false));
        assertEquals("greet", chat("하이요 다들", false, false));
        assertEquals("name", chat("sipsam_ 님 누구에요", true, false));
        assertEquals("name", chat("저 뉴비 누구임", false, false));
        assertEquals("name", chat("새로 오신 분 반가워요", false, false));
        assertEquals("bot", chat("SIPSAM 봇이냐", true, false));
        assertEquals("bot", chat("저거 귀신 아님", true, true));
        assertEquals("-", chat("봇 만들고 싶다", false, false), "not said to him: not about him");
        assertEquals("meet", chat("좌표 좀요", true, false));
        assertEquals("meet", chat("tpa 할게요", true, false));
        assertEquals("meet", chat("같이 하실래요", true, true));
        assertEquals("meet", chat("어디세요", true, false));
        assertEquals("-", chat("좌표 좀요", false, false));
        assertEquals("rude", chat("꺼져", true, false));
        assertEquals("rude", chat("ㅅㅂ 또 질문이네", true, true));
        assertEquals("answer", chat("석탄은 산 밑에 많아요", false, true));
        assertEquals("answer", chat("ㄴㄴ 상점에서 팔아요", true, true));
        assertEquals("-", chat("석탄은 산 밑에 많아요", false, false), "only just after his question");
        assertEquals("laugh", chat("ㅋㅋㅋㅋ", false, false));
        assertEquals("laugh", chat("ㅋㅋㅋ", false, true), "a laugh is no answer");
        assertEquals("-", chat("다이아 어디서 캐요", false, false));
        assertEquals("-", chat("", true, true));
        assertTrue(Talk.mentions("SIPSAM 뭐해", "sipsam_"));
        assertTrue(Talk.mentions("sipsam_ 님", "sipsam_"));
        assertFalse(Talk.mentions("십사 뭐해", "sipsam_"));
        assertEquals("sipsam", Talk.base("_sipsam_"));
    }

    @Test
    void whatWhispersAreAbout() {
        assertEquals("greet", whisper("안녕"));
        assertEquals("greet", whisper("ㅎㅇ"));
        assertEquals("who", whisper("누구세요"));
        assertEquals("where", whisper("어디세요"));
        assertEquals("where", whisper("같이 놀아요"));
        assertEquals("bot", whisper("봇이에요?"));
        assertEquals("bot", whisper("혹시 죽었어요"));
        assertEquals("stone", whisper("비석"));
        assertEquals("stone", whisper("13번째 돌 앞이에요"));
        assertEquals("stone", whisper("기념비 봤어요"));
        assertEquals("other", whisper("ㅁㄴㅇㄹ"));
        assertEquals("other", whisper("오늘 날씨 좋네요"));
        // a whisper about the stone is never confirmed: at most echoed, or nothing
        Talk.Intent stone = Talk.WHISPER.stream().filter(i -> i.id.equals("stone")).findFirst().orElseThrow();
        assertTrue(stone.chance < 1, "sometimes he says nothing");
        for (String l : stone.lines) assertFalse(l.contains("맞") || l.contains("제 거") || l.contains("제꺼"), l);
        for (String l : Talk.STRANGE) assertFalse(GIVES_AWAY.matcher(l).find(), l);
    }

    @Test
    void hisNameInAPatternIsHisName() {
        Talk.Intent named = Talk.CHAT.stream().filter(i -> i.id.equals("name")).findFirst().orElseThrow().named("moon_child");
        assertTrue(named.finds("MOON_CHILD 뭐임"));
        assertFalse(named.finds("sipsam 뭐임"));
        assertTrue(named.finds("a.b 뉴비"));
    }

    @Test
    void picksAvoidWhatHeSaidLately() {
        Random r = new Random(1);
        List<String> pool = List.of("a", "b", "c");
        for (int i = 0; i < 50; i++) assertEquals("c", Talk.pick(pool, List.of("a", "b"), r));
        assertTrue(pool.contains(Talk.pick(pool, List.of("a", "b", "c"), r)), "all said lately: any");
        assertEquals(null, Talk.pick(List.of(), List.of(), r));
    }

    @Test
    void theFilesIntentsAreTheCodes() {
        YamlConfiguration y = NewcomerTest.yml();
        List<String> problems = new ArrayList<>();
        same(Talk.CHAT, Talk.read(y.getConfigurationSection("chat.intents"), List.of(), Newcomer.DEFAULT_NAME, true, problems));
        same(Talk.WHISPER, Talk.read(y.getConfigurationSection("whisper.intents"), List.of(), Newcomer.DEFAULT_NAME, false, problems));
        assertEquals(List.of(), problems);
        // a "?" in small talk is dropped, with a word about it
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("chat.intents.laugh.match", List.of("ㅋㅋ"));
        bad.set("chat.intents.laugh.lines", List.of("ㅋㅋ", "뭐가 웃겨요?"));
        List<Talk.Intent> read = Talk.read(bad.getConfigurationSection("chat.intents"), Talk.CHAT, "sipsam_", true, problems);
        assertEquals(List.of("ㅋㅋ"), read.get(0).lines);
        assertEquals(1, problems.size());
        assertEquals(Talk.CHAT.stream().filter(i -> i.id.equals("laugh")).findFirst().orElseThrow().chance, read.get(0).chance, "left out: the code's");
    }

    static void same(List<Talk.Intent> code, List<Talk.Intent> file) {
        assertEquals(code.stream().map(i -> i.id).toList(), file.stream().map(i -> i.id).toList(), "the same intents in the same order");
        for (int k = 0; k < code.size(); k++) {
            Talk.Intent a = code.get(k), b = file.get(k);
            assertEquals(a.match, b.match, a.id);
            assertEquals(a.addressed, b.addressed, a.id);
            assertEquals(a.answer, b.answer, a.id);
            assertEquals(a.chance, b.chance, 1e-9, a.id);
            assertEquals(a.lines, b.lines, a.id);
            assertEquals(a.follow, b.follow, a.id);
            assertEquals(a.followChance, b.followChance, 1e-9, a.id);
            assertEquals(a.slip, b.slip, a.id);
            assertEquals(a.slipChance, b.slipChance, 1e-9, a.id);
            assertEquals(a.hush, b.hush, a.id);
        }
    }

    // ---- typing

    @Test
    void longerLinesTakeLonger() {
        Random r = new Random(7);
        long shortSum = 0, longSum = 0;
        for (int i = 0; i < 200; i++) {
            long s = Typing.millis("아하", r, 1.5, 4, 4, 7);
            long l = Typing.millis("비슷한 물고기 많던데 도감엔 다 따로 들어가요?", r, 1.5, 4, 4, 7);
            assertTrue(s >= 1350 && s <= 5000, "short " + s);
            assertTrue(l >= 4000 && l <= 14000, "long " + l);
            shortSum += s;
            longSum += l;
        }
        assertTrue(longSum > shortSum * 2);
    }

    @Test
    void aSlipNeverTouchesTheFirstCharacter() {
        Random r = new Random(3);
        int slips = 0;
        List<String> lines = new ArrayList<>();
        for (List<Questions.Entry> pool : Questions.DEFAULTS.values()) {
            for (Questions.Entry e : pool) {
                lines.add(Questions.fill(e.text(), Map.of("online", "4", "time", "이 밤에")));
                if (e.rain() != null) lines.add(e.rain());
            }
        }
        for (String line : lines) {
            for (int i = 0; i < 100; i++) {
                Optional<Typing.Slip> s = Typing.slip(line, r);
                if (s.isEmpty()) continue;
                slips++;
                String typed = s.get().typed(), word = s.get().word();
                assertNotEquals(line, typed);
                assertEquals(line.codePointAt(0), typed.codePointAt(0), line + " → " + typed);
                assertEquals(line.length(), typed.length());
                assertTrue(typed.endsWith("?"), "still a question: " + typed);
                assertEquals(Acrostic.of(List.of(line)), Acrostic.of(List.of(typed)));
                assertFalse(word.isEmpty());
                assertTrue(line.contains(word), word);
                assertFalse(word.contains("?") || word.contains(" "), word);
                for (String fix : Newcomer.FIXES) {
                    String said = fix.replace("{word}", word);
                    assertFalse(said.contains("?"), "a correction is no question: " + said);
                }
            }
        }
        assertTrue(slips > lines.size() * 90, "slips " + slips);
        assertEquals(Optional.empty(), Typing.slip("가?", r));
        assertEquals(Optional.empty(), Typing.slip("내 ??", r), "nothing but the first syllable");
        assertEquals("캐요", Typing.word("석탄 어디서 캐요?", 8));
    }
}
