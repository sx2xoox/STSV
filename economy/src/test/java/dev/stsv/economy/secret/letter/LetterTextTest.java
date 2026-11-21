package dev.stsv.economy.secret.letter;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The letters' text work: missing facts drop their sentence, the signature drifts but stays stable, day keys count. */
class LetterTextTest {

    @Test
    void aLineWithAMissingFactDropsOut() {
        Map<String, String> v = new HashMap<>();
        v.put("quit", "내 땅");
        v.put("sold", null);
        v.put("friend", "Steve");
        String page = LetterText.fill("나에게.\n\n어제 나는 {quit} 근처에서 나갔다.\n마지막으로 판 건 {sold}였다.\n{friend}랑 같이 있었다.", v);
        assertEquals("나에게.\n\n어제 나는 내 땅 근처에서 나갔다.\nSteve랑 같이 있었다.", page);
    }

    @Test
    void blankLinesLeftBehindCollapse() {
        Map<String, String> v = new HashMap<>();
        v.put("a", null);
        v.put("b", null);
        assertEquals("위\n\n아래", LetterText.fill("위\n\n{a}\n\n{b}\n\n아래\n\n", v));
    }

    @Test
    void unknownSlotsStayAndValuesMaySpanLines() {
        Map<String, String> v = Map.of("ritual", "첫째 줄\n둘째 줄");
        assertEquals("어제 나는\n첫째 줄\n둘째 줄\n{sig}", LetterText.fill("어제 나는\n{ritual}\n{sig}", v));
    }

    @Test
    void valuesAreNotReadAsRegexReplacements() {
        assertEquals("값 $1 \\", LetterText.fill("값 {x}", Map.of("x", "$1 \\")));
    }

    @Test
    void theFirstSignatureIsTheirNameThenItDrifts() {
        assertEquals("Steve", LetterText.drift("Steve", 0, 42));
        String prev = "Steve";
        for (int level = 1; level <= 4; level++) {
            String d = LetterText.drift("Steve", level, 42);
            assertNotEquals("Steve", d, "level " + level);
            assertEquals(d, LetterText.drift("Steve", level, 42), "the same seed signs the same way");
            prev = d;
        }
        assertFalse(prev.isEmpty());
    }

    @Test
    void aNameOfEqualLettersStillComesOutWrong() {
        assertNotEquals("aa", LetterText.drift("aa", 1, 7));
        assertNotEquals("oo", LetterText.drift("oo", 1, 1));
    }

    @Test
    void dayKeysCountDays() {
        assertEquals(1, LetterText.dayNo("2026-11-22") - LetterText.dayNo("2026-11-21"));
        assertEquals(1, LetterText.dayNo("2027-01-01") - LetterText.dayNo("2026-12-31"));
        assertEquals(29481235, LetterText.dayNo("t29481235"));
        assertEquals(0, LetterText.dayNo("garbage"));
        assertEquals(0, LetterText.dayNo(null));
    }

    @Test
    void durationsReadLikeALetter() {
        assertEquals("30분", LetterText.duration(1800));
        assertEquals("40초", LetterText.duration(40));
        assertEquals("1분 30초", LetterText.duration(90));
        assertEquals("3분", LetterText.duration(180));
        assertEquals("1시간", LetterText.duration(3600));
    }

    @Test
    void coordinatesRoundToTens() {
        assertEquals(120, LetterText.round(123));
        assertEquals(130, LetterText.round(125));
        assertEquals(-340, LetterText.round(-337));
        assertEquals(0, LetterText.round(4));
    }

    @Test
    void betweenStaysInItsRange() {
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            long v = LetterText.between(List.of(10, 18), 0, r);
            assertTrue(v >= 10 && v <= 18);
        }
        assertEquals(5, LetterText.between(List.of(5), 0, r));
        assertEquals(9, LetterText.between(List.of(), 9, r));
        long swapped = LetterText.between(List.of(20, 10), 0, r);
        assertTrue(swapped >= 10 && swapped <= 20);
    }
}
