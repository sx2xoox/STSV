package dev.stsv.rpg.balance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElementRoleTest {

    @Test
    void parsesKoreanAndIds() {
        assertEquals(Element.FIRE, Element.parse("불"));
        assertEquals(Element.DARK, Element.parse("dark"));
        assertEquals(Element.NONE, Element.parse(null));
        assertEquals(Role.FINAL, Role.parse("최종 보스"));
        assertEquals(Role.FINAL, Role.parse("최종보스"), "typed without the space (tab completion)");
        assertEquals(Role.BOSS, Role.parse("군주"));
        assertEquals(Role.ELITE, Role.parse("elite"));
        assertThrows(IllegalArgumentException.class, () -> Role.parse("왕"));
    }
}
