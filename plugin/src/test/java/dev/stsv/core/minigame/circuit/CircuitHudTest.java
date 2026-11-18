package dev.stsv.core.minigame.circuit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitHudTest {

    /** Text hung under an icon takes the icon font and shows as squares: the bar's root must carry no font. */
    @Test
    void barTextIsNotInTheIconFont() {
        Component bar = CircuitHud.bar(1, 3, 1, 2, "0:12.3", 1, 0.5, false, Component.text("+0.42"));
        assertNull(bar.font(), "the root must not carry a font: its children would inherit it");
        walk(bar, false);
    }

    private static void walk(Component c, boolean inIcon) {
        boolean icon = inIcon || CircuitHud.ICON_FONT.equals(c.font());
        if (c instanceof TextComponent t && icon) {
            for (char ch : t.content().toCharArray()) {
                assertTrue(ch >= '' && ch <= '', "\"" + t.content() + "\" is drawn in the icon font");
            }
        }
        for (Component k : c.children()) walk(k, icon);
    }
}
