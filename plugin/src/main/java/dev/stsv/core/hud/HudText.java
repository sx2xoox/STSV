package dev.stsv.core.hud;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Text for the HUD fonts: a message (any component, e.g. from messages.yml) as a list of styled characters,
 * measured with the HUD font's own widths, wrapped and shortened to fit. Bold is dropped (it would make every
 * glyph one pixel wider and the panel is laid out to the pixel); colours and the other decorations stay.
 */
final class HudText {

    /** One character and the style it is drawn with (font and bold are set when it is drawn). */
    record Cell(int cp, Style style) {}

    /** The game's ascii.png widths (pen advance) of 0x21..0x7E: read off the texture once, nothing copied. */
    private static final String ASCII = "2466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247";

    static final int ELLIPSIS = '…';

    private HudText() {}

    static int ascii(int cp) {
        return ASCII.charAt(cp - 0x21) - '0';
    }

    /** The characters of a component with their effective styles. Newlines become spaces. */
    static List<Cell> cells(Component component) {
        List<Cell> out = new ArrayList<>();
        Deque<Style> stack = new ArrayDeque<>();
        stack.push(Style.empty());
        ComponentFlattener.basic().flatten(component, new FlattenerListener() {
            @Override
            public void pushStyle(Style style) {
                stack.push(stack.peek().merge(style));
            }

            @Override
            public void component(String text) {
                Style s = stack.peek().decoration(TextDecoration.BOLD, false);
                text.codePoints().forEach(cp -> out.add(new Cell(cp == '\n' || cp == '\t' ? ' ' : cp, s)));
            }

            @Override
            public void popStyle(Style style) {
                if (stack.size() > 1) stack.pop();
            }
        });
        return out;
    }

    /**
     * Width of a character in a text font. Characters the font lacks: '?' when the font has no fallback (they
     * are replaced by {@link #supported}), otherwise an estimate of the game's own glyph (9 for wide scripts).
     */
    static int advance(HudFont font, int cp) {
        int a = font.textAdvance(cp);
        if (a >= 0) return a;
        if (!font.hasFallback()) return ascii('?');
        return cp >= 0x1100 ? 9 : 6;
    }

    /** Characters the font cannot draw become '?' (fonts without the game's font behind them). */
    static List<Cell> supported(HudFont font, List<Cell> cells) {
        if (font.hasFallback()) return cells;
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) out.add(font.textAdvance(c.cp()) >= 0 ? c : new Cell('?', c.style()));
        return out;
    }

    private static final TextColor PANEL_DARK_GRAY = TextColor.color(0x8C929E), PANEL_BLACK = TextColor.color(0x5A5F6A);

    /**
     * Colours made for the old sidebar's see-through black do not read on the charcoal panel: dark grey and black
     * are lifted (the messages stay the same for the scoreboard look).
     */
    static List<Cell> onPanel(List<Cell> cells) {
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) {
            TextColor col = c.style().color();
            if (col != null && col.value() == NamedTextColor.DARK_GRAY.value()) out.add(new Cell(c.cp(), c.style().color(PANEL_DARK_GRAY)));
            else if (col != null && col.value() == NamedTextColor.BLACK.value()) out.add(new Cell(c.cp(), c.style().color(PANEL_BLACK)));
            else out.add(c);
        }
        return out;
    }

    static int width(HudFont font, List<Cell> cells) {
        int w = 0;
        for (Cell c : cells) w += advance(font, c.cp());
        return w;
    }

    /**
     * Breaks the text into at most {@code maxLines} lines of at most {@code max} pixels, at spaces where it can
     * (a word longer than a line is cut). Text that does not fit in the last line ends with an ellipsis.
     */
    static List<List<Cell>> wrap(HudFont font, List<Cell> cells, int max, int maxLines) {
        List<List<Cell>> lines = new ArrayList<>();
        List<Cell> cur = new ArrayList<>();
        int w = 0, lastSpace = -1;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (c.cp() == ' ' && cur.isEmpty()) continue;
            int a = advance(font, c.cp());
            if (w + a > max && !cur.isEmpty()) {
                if (lines.size() + 1 >= maxLines) {
                    lines.add(ellipsis(font, cur, max));
                    return lines;
                }
                List<Cell> rest = new ArrayList<>();
                if (lastSpace > 0) {
                    rest.addAll(cur.subList(lastSpace + 1, cur.size()));
                    cur = new ArrayList<>(cur.subList(0, lastSpace));
                }
                lines.add(trim(cur));
                cur = rest;
                w = width(font, cur);
                lastSpace = -1;
                if (c.cp() == ' ' && cur.isEmpty()) continue;
                if (w + a > max && !cur.isEmpty()) {   // the carried word plus this one still too long
                    i--;
                    continue;
                }
            }
            if (c.cp() == ' ') lastSpace = cur.size();
            cur.add(c);
            w += a;
        }
        if (!cur.isEmpty()) lines.add(trim(cur));
        return lines;
    }

    private static List<Cell> ellipsis(HudFont font, List<Cell> line, int max) {
        List<Cell> out = new ArrayList<>(trim(line));
        Style style = out.isEmpty() ? Style.empty() : out.getLast().style();
        int dots = advance(font, ELLIPSIS);
        while (!out.isEmpty() && width(font, out) + dots > max) out.removeLast();
        out = new ArrayList<>(trim(out));
        out.add(new Cell(ELLIPSIS, style));
        return out;
    }

    private static List<Cell> trim(List<Cell> line) {
        int end = line.size();
        while (end > 0 && line.get(end - 1).cp() == ' ') end--;
        return line.subList(0, end);
    }
}
