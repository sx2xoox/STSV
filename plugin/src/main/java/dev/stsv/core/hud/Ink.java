package dev.stsv.core.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes HUD glyphs into one component while tracking the pen exactly as the game will move it, so pictures
 * and text can be placed at absolute positions (the pen starts where the game starts the line: see HudLayout).
 * Consecutive characters of the same font and style share one text component. Pictures have no shadow; text
 * gets the game's usual shadow (its colour at a quarter brightness).
 */
final class Ink {

    private static final TextColor WHITE = TextColor.color(0xFFFFFF);

    private final List<Component> parts = new ArrayList<>();
    private final StringBuilder run = new StringBuilder();
    private Style runStyle;
    private int pen;

    Ink(int start) {
        this.pen = start;
    }

    int pen() {
        return pen;
    }

    /** Moves the pen to x with the font's shift characters. */
    Ink to(HudFont font, int x) {
        String s = HudFont.shift(x - pen);
        if (!s.isEmpty()) append(s, Style.style().font(font.key()).build());
        pen = x;
        return this;
    }

    /** A picture glyph at the pen (its own colours, or multiplied by color). */
    Ink glyph(HudFont font, char c, TextColor color) {
        Style.Builder b = Style.style().font(font.key());
        if (color != null) b.color(color);
        append(String.valueOf(c), b.build());
        pen += font.advance(c);
        return this;
    }

    Ink glyph(HudFont font, char c) {
        return glyph(font, c, null);
    }

    /** Text in a HUD text font (characters it cannot draw must have been replaced, see HudText.supported). */
    Ink text(HudFont font, List<HudText.Cell> cells) {
        for (HudText.Cell c : cells) {
            TextColor color = c.style().color() == null ? WHITE : c.style().color();
            Style s = c.style().font(font.key()).shadowColor(ShadowColor.shadowColor(
                    0xFF000000 | ((color.red() / 4) << 16) | ((color.green() / 4) << 8) | (color.blue() / 4)));
            append(new String(Character.toChars(c.cp())), s);
            pen += HudText.advance(font, c.cp());
        }
        return this;
    }

    private void append(String s, Style style) {
        if (runStyle != null && !runStyle.equals(style)) flush();
        runStyle = style;
        run.append(s);
    }

    private void flush() {
        if (run.isEmpty()) return;
        parts.add(Component.text(run.toString(), runStyle));
        run.setLength(0);
        runStyle = null;
    }

    /** The finished component: no shadow unless a part sets its own. */
    Component build() {
        flush();
        return Component.text().shadowColor(ShadowColor.none()).append(parts).build();
    }
}
