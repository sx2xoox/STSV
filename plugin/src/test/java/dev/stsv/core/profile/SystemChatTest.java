package dev.stsv.core.profile;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat and whispers as system messages: the whisper command parsing and the line builders. */
class SystemChatTest {

    private static Optional<SystemChat.Whisper> parse(String line) {
        return SystemChat.parse(line);
    }

    @Test
    void whisperCommands() {
        assertEquals(Optional.of(new SystemChat.Whisper("w", "Name", "hi there")), parse("/w Name hi there"));
        assertEquals(Optional.of(new SystemChat.Whisper("tell", "name", "x")), parse("/minecraft:tell name x"));
        assertEquals(Optional.of(new SystemChat.Whisper("msg", "밤하늘", "안녕")), parse("/MSG 밤하늘 안녕"), "any case; a nickname as target");
        assertEquals(Optional.of(new SystemChat.Whisper("msg", "Steve", "a  b")), parse("/msg   Steve    a  b  "), "runs of spaces; inner ones kept");
    }

    @Test
    void leftToVanilla() {
        assertTrue(parse("/msg @a hi").isEmpty(), "selector");
        assertTrue(parse("/w @p[name=x] hi").isEmpty(), "selector");
        assertTrue(parse("/w name").isEmpty(), "no message");
        assertTrue(parse("/w name    ").isEmpty(), "blank message");
        assertTrue(parse("/w").isEmpty(), "no target");
        assertTrue(parse("/w \"Some One\" hi").isEmpty(), "quoted target");
        assertTrue(parse("/r hi").isEmpty(), "no /r in vanilla");
        assertTrue(parse("/msgs Steve hi").isEmpty(), "another command");
        assertTrue(parse("/essentials:msg Steve hi").isEmpty(), "another plugin's command");
        assertTrue(parse("w Steve hi").isEmpty(), "not a command");
    }

    @Test
    void nameIsDecoratedLikeVanilla() {
        UUID id = UUID.randomUUID();
        Component name = ChatLook.name(Component.text("밤하늘", NamedTextColor.AQUA), "Steve", id);
        assertEquals("밤하늘", PlainTextComponentSerializer.plainText().serialize(name), "the display name is the text");
        assertEquals(NamedTextColor.AQUA, name.color(), "its colour stays");
        assertEquals("Steve", name.insertion());
        assertEquals(ClickEvent.suggestCommand("/tell Steve "), name.clickEvent());
        HoverEvent<?> hover = name.hoverEvent();
        assertEquals(HoverEvent.Action.SHOW_ENTITY, hover.action());
        HoverEvent.ShowEntity e = (HoverEvent.ShowEntity) hover.value();
        assertEquals(Key.key("minecraft:player"), e.type());
        assertEquals(id, e.id());
        assertEquals(Component.text("Steve"), e.name());
        assertEquals(name, ChatLook.name(Component.text("밤하늘", NamedTextColor.AQUA), "Steve", id), "same input, same component");
    }

    @Test
    void whisperLinesAreVanillas() {
        Component target = ChatLook.name(Component.text("Alex"), "Alex", UUID.randomUUID());
        Component out = ChatLook.outgoing(target, "<red>hi</red> &c**");
        TranslatableComponent t = (TranslatableComponent) out;
        assertEquals("commands.message.display.outgoing", t.key());
        assertEquals(NamedTextColor.GRAY, t.color());
        assertEquals(TextDecoration.State.TRUE, t.decoration(TextDecoration.ITALIC));
        assertEquals(2, t.arguments().size());
        assertEquals(target, t.arguments().get(0).asComponent());
        assertEquals(Component.text("<red>hi</red> &c**"), t.arguments().get(1).asComponent(), "plain text: no MiniMessage or legacy codes");
        TranslatableComponent in = (TranslatableComponent) ChatLook.incoming(target, "hey");
        assertEquals("commands.message.display.incoming", in.key());
        assertEquals(NamedTextColor.GRAY, in.color());
        assertEquals(TextDecoration.State.TRUE, in.decoration(TextDecoration.ITALIC));
        assertEquals(Component.text("hey"), in.arguments().get(1).asComponent());
    }

    @Test
    void chatLineIsTheCoresOne() {
        Component name = Component.text("Steve"), msg = Component.text("hi"), title = Component.text("[T]");
        String f = ProfileModule.DEFAULT_CHAT_FORMAT;
        assertEquals(ProfileModule.chatLine(f, title, name, msg), ChatLook.line(f, title, name, msg));
        assertEquals(ProfileModule.chatLine(f, null, name, msg), ChatLook.line(null, name, msg), "no core running: the default format");
    }
}
