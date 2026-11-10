package dev.stsv.core.profile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The chat line without the vanilla angle brackets: "[칭호] 닉네임 » 내용". */
class ChatFormatTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void noAngleBrackets() {
        Component name = Component.text("밤하늘"), msg = Component.text("안녕하세요");
        String f = ProfileModule.DEFAULT_CHAT_FORMAT;
        assertEquals("밤하늘 » 안녕하세요", plain(ProfileModule.chatLine(f, null, name, msg)));
        assertEquals("[농부왕] 밤하늘 » 안녕하세요", plain(ProfileModule.chatLine(f, Component.text("[농부왕]"), name, msg)));
    }

    @Test
    void ownFormats() {
        Component name = Component.text("Steve"), msg = Component.text("hi");
        assertEquals("<Steve> hi", plain(ProfileModule.chatLine("{title}<{name}> {message}", null, name, msg)), "the vanilla look, if wanted");
        assertEquals("Steve » hi", plain(ProfileModule.chatLine("{name} <dark_gray>»</dark_gray> {message}", null, name, msg)));
        assertEquals("T Steve » hi", plain(ProfileModule.chatLine("{name} » {message}", Component.text("T"), name, msg)), "no {title}: the title goes in front");
        assertEquals("Steve » hi", plain(ProfileModule.chatLine("", null, name, msg)), "empty: the default");
        assertEquals("{x} Steve: hi", plain(ProfileModule.chatLine("{x} {name}: {message}", null, name, msg)), "unknown keys stay as text");
    }
}
