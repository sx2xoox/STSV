package dev.stsv.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gradient:#ffb35c:#ff6a3d><b>STSV</b></gradient> <dark_gray>»</dark_gray> ";

    private Text() {}

    public static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Item names/lore: MiniMessage without the default italic that Minecraft adds to custom names. */
    public static Component item(String miniMessage) {
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> itemLines(List<String> lines) {
        return lines.stream().map(Text::item).toList();
    }

    public static Component msg(String miniMessage) {
        return MM.deserialize(Lang.prefix() + miniMessage);
    }
}
