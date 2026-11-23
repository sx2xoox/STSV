package dev.stsv.core.profile;

import dev.stsv.core.STSVPlugin;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.UUID;

/**
 * The one definition of how chat and whisper lines look, so other plugins (economy's fake player) can build lines
 * that are identical to real players' ones.
 *
 * <ul>
 *   <li>Public chat: {@link #line}. The name a real player's chat line uses is the player's display name as Paper
 *       passes it to the renderer: {@code Component.text(accountName)} with no style, or, with a nickname/colour,
 *       the coloured nickname (with the "nickname.hover" text hover when it is a nickname). No click, no insertion.</li>
 *   <li>Whispers ({@code /msg}, {@code /tell}, {@code /w}): {@link #outgoing} / {@link #incoming} with names from
 *       {@link #name}, the way vanilla puts them on a player name.</li>
 * </ul>
 */
public final class ChatLook {

    private ChatLook() {}

    private static final Key PLAYER = Key.key("minecraft", "player");

    /**
     * A player's name as vanilla decorates it in whisper lines: the given text (the display name, i.e. the nickname)
     * with insertion = account name, click = suggest "/tell &lt;account&gt; ", hover = show_entity (minecraft:player,
     * uuid, account name). These replace any hover/click/insertion the text had at its root.
     */
    public static Component name(Component text, String account, UUID id) {
        return text.insertion(account)
                .clickEvent(ClickEvent.suggestCommand("/tell " + account + " "))
                .hoverEvent(HoverEvent.showEntity(PLAYER, id, Component.text(account)));
    }

    /** What the sender sees: "You whisper to {target}: {message}", grey italic. The message is plain text. */
    public static Component outgoing(Component targetName, String message) {
        return Component.translatable("commands.message.display.outgoing", targetName, Component.text(message))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    /** What the target sees: "{sender} whispers to you: {message}", grey italic. The message is plain text. */
    public static Component incoming(Component senderName, String message) {
        return Component.translatable("commands.message.display.incoming", senderName, Component.text(message))
                .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
    }

    /** One public chat line from a format (config profiles.chat-format): see {@link ProfileModule#chatLine}. */
    public static Component line(String format, Component title, Component name, Component message) {
        return ProfileModule.chatLine(format, title, name, message);
    }

    /** One public chat line in the server's current format (config profiles.chat-format). */
    public static Component line(Component title, Component name, Component message) {
        return line(format(), title, name, message);
    }

    /** The server's chat format (config profiles.chat-format), or the default without the core. */
    public static String format() {
        STSVPlugin plugin = STSVPlugin.get();
        return plugin == null ? ProfileModule.DEFAULT_CHAT_FORMAT : plugin.getConfig().getString("profiles.chat-format");
    }
}
