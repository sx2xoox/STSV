package dev.stsv.economy.secret.newcomer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 새로 온 사람, sworn at: what counts as swearing (spaced out, dotted, with a digit in it), what only looks like it (the
 * allow list), when a public line is aimed at him, his hurt line has no "?", and a sulk lasts for its night only.
 */
class NewcomerSulkTest {

    static final List<Pattern> SWEARS = Sulk.patterns(Sulk.SWEARS), ALLOW = Sulk.patterns(Sulk.ALLOW), AIM = Sulk.patterns(Sulk.AIM);

    static boolean insult(String s) {
        return Sulk.insult(s, SWEARS, ALLOW);
    }

    @Test
    void swearing() {
        for (String s : List.of("시발", "씨발 뭐야", "ㅅㅂ", "ㅆㅂ 또 질문", "ㅅ1ㅂ", "시 발", "ㅅ.ㅂ", "ㅅ ㅂ 너", "병신", "병1신", "ㅂㅅ아",
                "븅신같네", "꺼져", "꺼 져", "닥쳐", "ㄲㅈ", "ㄷㅊ", "좆같네", "존나 짜증나", "존나시끄럽네", "미친놈", "미친 새끼",
                "개새끼", "개새야", "ㄱㅅㄲ", "이 새끼 뭐임", "지랄하네", "ㅈㄹ", "엿 먹어라", "죽어", "죽어라", "뒤져라", "디져",
                "짜증나게 하네", "꺼지라고", "tlqkf", "SIBAL", "ㅗㅗ", "ㅗ")) {
            assertTrue(insult(s), s);
        }
    }

    @Test
    void notSwearing() {
        for (String s : List.of("시발점이 어디였지", "병신년에 지은 집", "새끼 고양이 귀엽다", "고양이 새끼 데려옴", "새끼손가락 다침",
                "다시 발견했어", "다 시 발견", "불 꺼져 있네", "화로 꺼져서", "꺼져요 그거", "닥치는 대로 캐", "몹한테 죽어서 템 날아감",
                "상자 뒤져봐", "개 새로 샀어", "호박엿 먹었다", "저리 가면 마을 있어요", "석탄은 산 밑에 많아요", "ㅋㅋㅋ", "안녕하세요", "")) {
            assertFalse(insult(s), s);
        }
    }

    @Test
    void looseReading() {
        assertEquals("시발 놈아", Sulk.loose("시 발 놈아"));
        assertEquals("ㅅㅂ", Sulk.loose("ㅅ.ㅂ"));
        assertEquals("ㅅㅂ", Sulk.loose("ㅅ1ㅂ"));
        assertEquals("다시 발견", Sulk.loose("다시 발견"));
        assertEquals("ㅅ ㅂ 너", Sulk.plain("ㅅ.ㅂ 너!"));
    }

    @Test
    void aimedAtHim() {
        assertTrue(Sulk.aimed("sipsam 꺼져", "sipsam_", AIM));
        assertTrue(Sulk.aimed("뉴비 ㅅㅂ", "sipsam_", AIM));
        assertTrue(Sulk.aimed("너 ㅅㅂ", "sipsam_", AIM));
        assertTrue(Sulk.aimed("니 뭐냐 병신아", "sipsam_", AIM));
        assertTrue(Sulk.aimed("얘 뭐임 ㅅㅂ", "sipsam_", AIM));
        assertTrue(Sulk.aimed("너같은 놈은 꺼져", "sipsam_", AIM));
        assertFalse(Sulk.aimed("ㅅㅂ 렉 너무 심하네", "sipsam_", AIM), "너무 is not 너");
        assertFalse(Sulk.aimed("아 ㅅㅂ 또 죽었네", "sipsam_", AIM));
        assertFalse(Sulk.aimed("니켈 어디서 캐요", "sipsam_", AIM));
    }

    @Test
    void hisHurtLineIsNoQuestion() {
        for (List<String> pool : List.of(Sulk.HURT, Sulk.SORRY)) {
            assertFalse(pool.isEmpty());
            for (String l : pool) {
                assertTrue(Talk.small(l), l);
                assertFalse(l.contains("!"), l);
                assertFalse(NewcomerTalkTest.GIVES_AWAY.matcher(l).find(), l);
                assertFalse(insult(l), "he does not swear: " + l);
            }
        }
        Talk.Intent rude = Talk.CHAT.stream().filter(i -> i.id.equals("rude")).findFirst().orElseThrow();
        assertEquals(Sulk.HURT, rude.lines, "the hurt line is rude's");
        assertEquals(Sulk.SWEARS, rude.match);
    }

    @Test
    void hisTiredLineIsNoQuestionAndNoGoodbye() {
        assertTrue(Newcomer.TIRED.size() >= 8);
        for (String l : Newcomer.TIRED) {
            assertFalse(l.contains("?"), l);
            assertTrue(Talk.small(l), l);
            assertFalse(l.contains("!") || l.contains("당신"), l);
            assertFalse(l.contains("나갈") || l.contains("나가") || l.contains("갈게"), "he never leaves: " + l);
            assertFalse(NewcomerTalkTest.GIVES_AWAY.matcher(l).find(), l);
            assertFalse(insult(l), l);
            assertEquals(l.strip(), l);
        }
        YamlConfiguration y = NewcomerTest.yml();
        assertEquals(Newcomer.TIRED, y.getStringList("chat.tired"));
        for (String l : y.getStringList("chat.tired")) assertFalse(l.contains("?"), l);
        assertEquals(1800, y.getInt("chat.linger-seconds"));
        assertEquals(120, y.getInt("chat.test-linger-seconds"));
        assertEquals(List.of(20, 2), y.getIntegerList("visit.hours"));
    }

    @Test
    void theFileSaysWhatTheCodeSays() {
        YamlConfiguration y = NewcomerTest.yml();
        assertEquals(Sulk.ALLOW, y.getStringList("sulk.allow"));
        assertEquals(Sulk.AIM, y.getStringList("sulk.aim"));
        assertEquals(Sulk.SORRY, y.getStringList("sulk.sorry"));
        assertEquals(40, y.getInt("sulk.window-seconds"));
        assertTrue(y.getDouble("sulk.sorry-chance") > 0 && y.getDouble("sulk.sorry-chance") < 1);
    }

    @Test
    void aSulkLastsItsNight() {
        int from = 20, to = 2;
        LocalDateTime at23 = LocalDateTime.of(2026, 11, 23, 23, 0);
        String key = Sulk.key(at23, from, to);
        assertEquals("2026-11-23", key);
        assertTrue(Sulk.sulking(key, at23, from, to));
        assertTrue(Sulk.sulking(key, LocalDateTime.of(2026, 11, 24, 1, 30), from, to), "after midnight: the same night");
        assertTrue(Sulk.sulking(key, LocalDateTime.of(2026, 11, 24, 12, 0), from, to), "between nights: still gone");
        assertFalse(Sulk.sulking(key, LocalDateTime.of(2026, 11, 24, 20, 0), from, to), "the next night he comes as usual");
        assertFalse(Sulk.sulking(null, at23, from, to));
        assertFalse(Sulk.sulking("2026-11-22", at23, from, to));
        // sworn at in a whisper during the day: that coming night
        LocalDateTime noon = LocalDateTime.of(2026, 11, 23, 12, 0);
        assertEquals("2026-11-23", Sulk.key(noon, from, to));
        assertTrue(Sulk.sulking("2026-11-23", LocalDateTime.of(2026, 11, 23, 21, 0), from, to));
        // the sheepish word the night after
        assertTrue(Sulk.dayAfter("2026-11-23", "2026-11-24"));
        assertFalse(Sulk.dayAfter("2026-11-23", "2026-11-25"));
        assertFalse(Sulk.dayAfter(null, "2026-11-24"));
    }
}
