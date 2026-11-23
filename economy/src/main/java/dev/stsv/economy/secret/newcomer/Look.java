package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.profile.ChatLook;
import dev.stsv.core.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * How he looks on screen, built in one place each: his name, his join / leave line, his public chat line, and the two
 * whisper lines (his coming in, the sender's own going out). Everything he shows goes through here, so that all of it can
 * come from the core's own builders (dev.stsv.core.profile.ChatLook): his lines are built by the same code as real ones.
 */
final class Look {

    /** The core's chat format when its config names none (ProfileModule.DEFAULT_CHAT_FORMAT). */
    static final String DEFAULT_CHAT_FORMAT = "{title}{name} <gray>»</gray> {message}";

    private final Supplier<String> format;
    private String who;
    private UUID id;

    /** format: the core's chat format as it is now (read every time; null or blank = the default). */
    Look(String who, Supplier<String> format) {
        this.format = format;
        name(who);
    }

    void name(String who) {
        this.who = who;
        this.id = fake(who);
    }

    String who() {
        return who;
    }

    static UUID fake(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** NAME: his name as the game writes a player's in a whisper: click to whisper, shift-click to paste, hover for who it is (core ChatLook). */
    Component name() {
        try {
            return ChatLook.name(Component.text(who), who, id);
        } catch (RuntimeException e) {
            return Component.text(who);
        }
    }

    /** "… joined the game" / "… left the game", yellow, in each client's own language. */
    Component joinLine(boolean join) {
        return Component.translatable(join ? "multiplayer.player.joined" : "multiplayer.player.left", name()).color(NamedTextColor.YELLOW);
    }

    /** CHAT LINE: one public chat line from him (plain words); a format that cannot be read falls back to "<name> words". */
    Component chat(String words) {
        try {
            return chatLine(format.get(), who, words);
        } catch (RuntimeException e) {
            return Component.text("<" + who + "> " + words);
        }
    }

    /** WHISPER IN: a whisper from him exactly as the core sends a real player's (ChatLook.incoming; plain words). mm: MiniMessage, read as plain text. */
    Component incoming(String mm) {
        return ChatLook.incoming(name(), PlainTextComponentSerializer.plainText().serialize(Text.mm(mm)));
    }

    /** WHISPER OUT: the sender's own whisper to him, as the core echoes a real one (ChatLook.outgoing). */
    Component outgoing(String message) {
        return ChatLook.outgoing(name(), message);
    }

    /**
     * One chat line exactly as the core draws a real player's without a title or nickname (ChatLook.line: the same code
     * the core uses; a real player's chat name is plain text, no hover or click).
     */
    static Component chatLine(String format, String name, String words) {
        return ChatLook.line(format == null || format.isBlank() ? DEFAULT_CHAT_FORMAT : format, null, Component.text(name), Component.text(words));
    }
}
