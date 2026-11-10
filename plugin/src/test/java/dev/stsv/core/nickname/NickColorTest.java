package dev.stsv.core.nickname;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NickColorTest {

    @Test
    void hexParsing() {
        assertEquals(0xffb7c5, NickColor.parseHex("#ffb7c5"));
        assertEquals(0xffb7c5, NickColor.parseHex("FFB7C5"));
        assertEquals(0xabcdef, NickColor.parseHex(" #AbCdEf "));
        for (String bad : List.of("#fff", "#ffb7c", "#ffb7c55", "&c", "§c", "red", "<red>", "#gggggg", "", "#")) {
            assertEquals(-1, NickColor.parseHex(bad), bad);
        }
        assertEquals(-1, NickColor.parseHex(null));
    }

    @Test
    void brightness() {
        assertEquals(1.0, NickColor.brightness(0xffffff), 1e-9);
        assertEquals(0.0, NickColor.brightness(0x000000), 1e-9);
        double min = 0.35;
        assertFalse(NickColor.readable(0x000000, min), "black");
        assertFalse(NickColor.readable(0x1a1a1a, min), "black-ish");
        assertFalse(NickColor.readable(0x555555, min), "dark grey");
        assertFalse(NickColor.readable(0x0000ff, min), "pure blue is hard to read on dark");
        assertFalse(NickColor.readable(0x000080, min), "navy");
        assertTrue(NickColor.readable(0x5555ff, min), "the game's blue");
        assertTrue(NickColor.readable(0xaa0000, min), "the game's dark red");
        assertTrue(NickColor.readable(0x8000ff, min), "purple");
        assertTrue(NickColor.readable(0xffb7c5, min));
        assertFalse(NickColor.gradient(0xffffff, 0x000000).readable(min), "both ends of a gradient must be readable");
        assertTrue(NickColor.gradient(0xffb347, 0xff5e8a).readable(min));
    }

    @Test
    void encodeAndDecode() {
        NickColor solid = NickColor.solid(0xffb7c5), grad = NickColor.gradient(0xffb347, 0xff5e8a);
        assertEquals("#ffb7c5", solid.encode());
        assertEquals("#ffb347>#ff5e8a", grad.encode());
        assertEquals(solid, NickColor.decode(solid.encode()));
        assertEquals(grad, NickColor.decode(grad.encode()));
        assertNull(NickColor.decode(null));
        assertNull(NickColor.decode(""));
        assertNull(NickColor.decode("#zzzzzz"));
        assertNull(NickColor.decode("#ffffff>nope"));
        assertEquals(NickColor.solid(0x123456), new NickColor(0x123456, 0xabcdef, false), "a single colour has no second one");
    }

    @Test
    void gradientAcrossTheLetters() {
        NickColor g = NickColor.gradient(0x000000, 0xff0000);
        assertEquals(0x000000, g.at(0, 3));
        assertEquals(0x800000, g.at(1, 3));
        assertEquals(0xff0000, g.at(2, 3));
        assertEquals(0x000000, g.at(0, 1), "one letter: the first colour");
        assertEquals(0x123456, NickColor.solid(0x123456).at(5, 9));

        Component c = NickColor.gradient(0xffffff, 0x00ff00).component("홍길동");
        assertEquals("홍길동", PlainTextComponentSerializer.plainText().serialize(c));
        List<Component> letters = c.children();
        assertEquals(3, letters.size(), "one component per letter");
        assertEquals(TextColor.color(0xffffff), letters.get(0).color());
        assertEquals(TextColor.color(0x00ff00), letters.get(2).color());
        assertEquals("길", ((TextComponent) letters.get(1)).content());

        Component s = NickColor.solid(0xffb7c5).component("Steve");
        assertEquals(TextColor.color(0xffb7c5), s.color());
        assertEquals("Steve", PlainTextComponentSerializer.plainText().serialize(NickColor.component(null, "Steve")));
        assertNull(NickColor.component(null, "Steve").color());
    }

    @Test
    void miniMessageOverridesTheSurroundingColour() {
        MiniMessage mm = MiniMessage.miniMessage();
        // a message template wraps the name in its own colour: the nickname's colour wins
        Component c = mm.deserialize("<white>" + NickColor.solid(0xffb7c5).mini("홍길동") + "님</white>");
        assertEquals("홍길동님", PlainTextComponentSerializer.plainText().serialize(c));
        assertEquals(TextColor.color(0xffb7c5), colorOf(c, "홍길동", null));
        assertEquals(TextColor.color(0xffffff), colorOf(c, "님", null), "the template's colour after the name");
        // a gradient tag works inside quoted hover text too (the teleport buttons put names there)
        Component hover = mm.deserialize("<hover:show_text:'" + NickColor.gradient(0xffb347, 0xff5e8a).mini("별빛") + "님'>x</hover>");
        assertEquals("x", PlainTextComponentSerializer.plainText().serialize(hover));
        assertEquals("Steve", NickColor.mini(null, "Steve"));
    }

    /** The colour a text is drawn in (inherited from the parents). */
    static TextColor colorOf(Component c, String text, TextColor inherited) {
        TextColor mine = c.color() != null ? c.color() : inherited;
        if (c instanceof TextComponent t && t.content().equals(text)) return mine;
        for (Component child : c.children()) {
            TextColor found = colorOf(child, text, mine);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void configPresetsAreReadable() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        double min = y.getDouble("nicknames.colors.min-brightness");
        var palette = y.getConfigurationSection("nicknames.colors.palette");
        assertEquals(36, palette.getKeys(false).size(), "four rows of the menu");
        for (String k : palette.getKeys(false)) {
            int c = NickColor.parseHex(palette.getString(k));
            assertTrue(c >= 0 && NickColor.readable(c, min), k);
        }
        var gradients = y.getConfigurationSection("nicknames.colors.gradients");
        assertEquals(9, gradients.getKeys(false).size(), "one row of the menu");
        for (String k : gradients.getKeys(false)) {
            List<String> two = gradients.getStringList(k);
            assertEquals(2, two.size(), k);
            assertTrue(NickColor.gradient(NickColor.parseHex(two.get(0)), NickColor.parseHex(two.get(1))).readable(min), k);
        }
    }
}
