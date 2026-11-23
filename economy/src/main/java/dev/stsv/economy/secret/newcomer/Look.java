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

    /** Whether the core has its chat builders (0.122.0+). An older core: the same lines built here (and one warning). */
    static final boolean CORE = coreLook();

    private static boolean coreLook() {
        try {
            Class.forName("dev.stsv.core.profile.ChatLook");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            org.bukkit.Bukkit.getLogger().warning("[STSV-Economy] 코어가 오래됐어요 (0.122.0 이상 필요): 숨은 비밀 '새로 온 사람' 이 채팅 모양을 직접 만들어 씀. 코어 jar 를 경제와 같은 때 받은 것으로 바꿔 주세요.");
            return false;
        }
    }

    static UUID fake(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** NAME: his name as the game writes a player's in a whisper: click to whisper, shift-click to paste, hover for who it is (core ChatLook). */
    Component name() {
        try {
            if (CORE) return ChatLook.name(Component.text(who), who, id);
            return Component.text(who).insertion(who)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/tell " + who + " "))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showEntity(net.kyori.adventure.key.Key.key("minecraft", "player"), id, Component.text(who)));
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
        String words = PlainTextComponentSerializer.plainText().serialize(Text.mm(mm));
        if (CORE) return ChatLook.incoming(name(), words);
        return Component.translatable("commands.message.display.incoming", name(), Component.text(words))
                .color(NamedTextColor.GRAY).decorate(net.kyori.adventure.text.format.TextDecoration.ITALIC);
    }

    /** WHISPER OUT: the sender's own whisper to him, as the core echoes a real one (ChatLook.outgoing). */
    Component outgoing(String message) {
        if (CORE) return ChatLook.outgoing(name(), message);
        return Component.translatable("commands.message.display.outgoing", name(), Component.text(message))
                .color(NamedTextColor.GRAY).decorate(net.kyori.adventure.text.format.TextDecoration.ITALIC);
    }

    /**
     * One chat line exactly as the core draws a real player's without a title or nickname (ChatLook.line: the same code
     * the core uses; a real player's chat name is plain text, no hover or click).
     */
    static Component chatLine(String format, String name, String words) {
        String f = format == null || format.isBlank() ? DEFAULT_CHAT_FORMAT : format;
        if (CORE) return ChatLook.line(f, null, Component.text(name), Component.text(words));
        return oldLine(f, name, words);
    }

    /** The core's ProfileModule.chatLine as it was before ChatLook (an older core): {title} nothing, {name}/{message} plain. */
    private static Component oldLine(String f, String name, String words) {
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
