package dev.stsv.economy.secret.newcomer;

import dev.stsv.core.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * How he looks on screen, built in one place each: his name, his join / leave line, his public chat line, and the two
 * whisper lines (his coming in, the sender's own going out). Everything he shows goes through here, so that all of it can
 * be switched to the core's own builders (dev.stsv.core.profile.ChatLook) in one go once the core has them.
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

    /** NAME: his name as the game writes a player's: click to whisper, shift-click to paste, hover for who it is. */
    Component name() {
        try {
            return Component.text(who).insertion(who).clickEvent(ClickEvent.suggestCommand("/tell " + who + " "))
                    .hoverEvent(HoverEvent.showEntity(Key.key("minecraft", "player"), id, Component.text(who)));
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

    /** WHISPER IN: a whisper from him as the game shows one coming in (grey, slanted). mm: MiniMessage. */
    Component incoming(String mm) {
        return Component.translatable("commands.message.display.incoming", name(), Text.mm(mm))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    /** WHISPER OUT: the sender's own whisper to him as the game echoes it (plain words). */
    Component outgoing(String message) {
        return Component.translatable("commands.message.display.outgoing", name(), Component.text(message))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    /**
     * One chat line exactly as the core draws a real player's without a title (ProfileModule.chatLine: the format is read
     * piece by piece, each piece MiniMessage of its own, {title} nothing, {name} and {message} plain text), so that his
     * line is a real line in whatever format the owner sets (NewcomerTest holds the two together).
     */
    static Component chatLine(String format, String name, String words) {
        String f = format == null || format.isBlank() ? DEFAULT_CHAT_FORMAT : format;
        Component out = Component.empty();
        int i = 0;
        while (i < f.length()) {
            int open = f.indexOf('{', i);
            if (open < 0) {
                out = out.append(Text.mm(f.substring(i)));
                break;
            }
            int close = f.indexOf('}', open);
            String key = close < 0 ? "" : f.substring(open + 1, close);
            if (!key.equals("title") && !key.equals("name") && !key.equals("message")) {
                out = out.append(Text.mm(f.substring(i, close < 0 ? f.length() : close + 1)));
                i = close < 0 ? f.length() : close + 1;
                continue;
            }
            if (open > i) out = out.append(Text.mm(f.substring(i, open)));
            if (key.equals("name")) out = out.append(Component.text(name));
            else if (key.equals("message")) out = out.append(Component.text(words));
            i = close + 1;
        }
        return out;
    }
}
