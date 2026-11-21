package dev.stsv.economy.secret.letter;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The riddles' pure parts: compass ways, distances, Korean dates, name sounds, and the silence closing in. */
class LetterRiddleTest {

    @Test
    void compassWaysFollowMinecraftAxes() {
        assertEquals("동쪽", Riddle.direction(100, 0));
        assertEquals("서쪽", Riddle.direction(-100, 0));
        assertEquals("남쪽", Riddle.direction(0, 100));     // +z is south
        assertEquals("북쪽", Riddle.direction(0, -100));
        assertEquals("북동쪽", Riddle.direction(70, -70));
        assertEquals("남서쪽", Riddle.direction(-70, 70));
        assertEquals("동쪽", Riddle.direction(100, -20));   // nearly east is east
        assertNull(Riddle.direction(0, 0));
    }

    @Test
    void distancesAreRoundedLikeAGuess() {
        assertEquals("약 400걸음", Riddle.steps(412));
        assertEquals("약 450걸음", Riddle.steps(430));
        assertEquals("약 40걸음", Riddle.steps(37));
        assertEquals("약 10걸음", Riddle.steps(2));
    }

    @Test
    void datesAreKoreanTime() {
        long ms = ZonedDateTime.of(2026, 3, 4, 23, 12, 0, 0, Riddle.KST).toInstant().toEpochMilli();
        assertEquals("2026년 3월 4일", Riddle.date(ms));
        assertEquals("2026년 3월 4일 밤 11시 12분", Riddle.dateTime(ms));
        long dawn = ZonedDateTime.of(2026, 3, 5, 0, 5, 0, 0, Riddle.KST).toInstant().toEpochMilli();
        assertEquals("2026년 3월 5일 새벽 12시 5분", Riddle.dateTime(dawn));
        long noon = ZonedDateTime.of(2026, 3, 5, 12, 0, 0, 0, Riddle.KST).toInstant().toEpochMilli();
        assertEquals("2026년 3월 5일 오후 12시 0분", Riddle.dateTime(noon));
    }

    @Test
    void initialsAreTheFirstSounds() {
        assertEquals("ㄹㅂ ㅇㅅ", Riddle.initials("루비 원석"));
        assertEquals("ㅅㅍㅇㅇ", Riddle.initials("사파이어"));
        assertEquals("A ㄱ", Riddle.initials("A 감"));
        assertEquals("", Riddle.initials(null));
    }

    @Test
    void theSilenceClosesIn() {
        assertEquals(0, Riddle.callTier(0));
        assertEquals(0, Riddle.callTier(0.3));
        assertEquals(1, Riddle.callTier(0.5));
        assertEquals(2, Riddle.callTier(0.9));
        assertEquals(2, Riddle.callTier(1));
        long start = Riddle.callGap(0, 50_000, 10_000), mid = Riddle.callGap(0.5, 50_000, 10_000), end = Riddle.callGap(1, 50_000, 10_000);
        assertEquals(50_000, start);
        assertEquals(10_000, end);
        assertTrue(mid < start && mid > end);
        assertEquals(1000, Riddle.callGap(1, 50_000, 0), "never faster than a second");
    }

    @Test
    void omensQuickenWithEachLetter() {
        List<Double> pace = List.of(1.0, 0.85, 0.7, 0.55, 0.45);
        assertEquals(1.0, Riddle.pace(pace, 1));
        assertEquals(0.45, Riddle.pace(pace, 5));
        assertEquals(0.45, Riddle.pace(pace, 9));
        assertEquals(1.0, Riddle.pace(List.of(), 3));
        assertEquals(1.0, Riddle.pace(List.of(0.0), 1));
    }

    @Test
    void aHintLineWithAMissingFactDropsOut() {
        Map<String, String> v = new HashMap<>();
        v.put("claimed", null);
        v.put("biome", "<lang:biome.minecraft.plains>");
        v.put("where", "<gray>건축월드의 스폰에서</gray>\n<gray>북동쪽으로, 약 400걸음.</gray>");
        String r = LetterText.fill("다시 섰다.\n\n<gray>{claimed}이었다.</gray>\n<gray>{biome}의 흙.</gray>\n{where}", v);
        assertEquals("다시 섰다.\n\n<gray><lang:biome.minecraft.plains>의 흙.</gray>\n<gray>건축월드의 스폰에서</gray>\n<gray>북동쪽으로, 약 400걸음.</gray>", r);
    }
}
