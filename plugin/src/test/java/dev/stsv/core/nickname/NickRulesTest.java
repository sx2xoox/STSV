package dev.stsv.core.nickname;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NickRulesTest {

    private static NickRules.Problem check(String nick) {
        return NickRules.check(NickRules.clean(nick), 2, 8, List.of("관리자", "Admin"));
    }

    @Test
    void lettersAndLength() {
        assertNull(check("홍길동"));
        assertNull(check("Steve99"));
        assertNull(check("별빛2"));
        assertNull(check("가나다라마바사아"), "8 Hangul syllables count as 8");
        assertNull(check("  치킨  "), "surrounding spaces are trimmed");
        assertEquals(NickRules.Problem.LENGTH, check("가"));
        assertEquals(NickRules.Problem.LENGTH, check("가나다라마바사아자"));
        assertEquals(NickRules.Problem.LENGTH, check(""));
        assertEquals(NickRules.Problem.CHARACTERS, check("홍 길동"));
        assertEquals(NickRules.Problem.CHARACTERS, check("Steve_"));
        assertEquals(NickRules.Problem.CHARACTERS, check("&c빨강"));
        assertEquals(NickRules.Problem.CHARACTERS, check("§c빨강"));
        assertEquals(NickRules.Problem.CHARACTERS, check("<red>빨강"));
        assertEquals(NickRules.Problem.CHARACTERS, check("ㅋㅋㅋ"), "lone jamo are not syllables");
        assertEquals(NickRules.Problem.CHARACTERS, check("日本"));
        assertEquals(NickRules.Problem.CHARACTERS, check("😀😀"));
    }

    @Test
    void jamoTypedApartBecomeSyllables() {
        String decomposed = "홍길";   // 홍길 as separate jamo
        assertEquals("홍길", NickRules.clean(decomposed));
        assertNull(check(decomposed));
    }

    @Test
    void bannedWordsIgnoreCase() {
        assertEquals(NickRules.Problem.BANNED, check("나는관리자"));
        assertEquals(NickRules.Problem.BANNED, check("ADMIN1"));
        assertEquals("Admin", NickRules.bannedWord("superadmin", List.of("", "Admin")));
        assertNull(NickRules.bannedWord("홍길동", List.of("", " ")));
    }

    @Test
    void keysCompareWithoutCase() {
        assertEquals(NickRules.key("Steve"), NickRules.key("sTEVE"));
        assertEquals("홍길동", NickRules.key(" 홍길동 "));
        assertEquals(3, NickRules.length("홍길동"));
    }

    @Test
    void costs() {
        assertEquals(0, NickRules.cost(true, 5000, true), "the first nickname is free");
        assertEquals(5000, NickRules.cost(false, 5000, true));
        assertEquals(0, NickRules.cost(false, 5000, false), "free where nicknames are not charged");
        assertEquals(0, NickRules.cost(false, -5, true));

        NickColor pink = NickColor.solid(0xffb7c5), dawn = NickColor.gradient(0xffb347, 0xff5e8a);
        assertEquals(0, NickRules.colorCost(pink, true, true, 2000, 5000, true), "first single colour free");
        assertEquals(2000, NickRules.colorCost(pink, true, false, 2000, 5000, true), "unless first-free is off");
        assertEquals(2000, NickRules.colorCost(pink, false, true, 2000, 5000, true));
        assertEquals(5000, NickRules.colorCost(dawn, true, true, 2000, 5000, true), "gradients always cost");
        assertEquals(0, NickRules.colorCost(null, false, true, 2000, 5000, true), "taking the colour off is free");
        assertEquals(0, NickRules.colorCost(dawn, false, true, 2000, 5000, false));
    }

    @Test
    void cooldown() {
        long hour = 3_600_000L;
        assertEquals(0, NickRules.cooldownLeft(1000, 0, 2000), "0 = no cooldown");
        assertEquals(0, NickRules.cooldownLeft(0, hour, 2000), "never changed");
        assertEquals(hour - 1000, NickRules.cooldownLeft(1000, hour, 2000));
        assertEquals(0, NickRules.cooldownLeft(1000, hour, 1000 + hour));
    }
}
