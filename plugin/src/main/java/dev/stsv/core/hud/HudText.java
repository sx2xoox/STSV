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
 * A tab in a sidebar row separates its label from its value (the panel puts the value against its right edge;
 * everywhere else a tab is a space).
 */
final class HudText {

    /** One character and the style it is drawn with (font and bold are set when it is drawn). */
    record Cell(int cp, Style style) {}

    static final int ELLIPSIS = '…';
    static final int TAB = '\t';

    private HudText() {}

    /** The characters of a component with their effective styles. Newlines become spaces, tabs stay. */
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
                text.codePoints().forEach(cp -> out.add(new Cell(cp == '\n' || cp == '\r' ? ' ' : cp, s)));
            }

            @Override
            public void popStyle(Style style) {
                if (stack.size() > 1) stack.pop();
            }
        });
        return out;
    }

    /** Tabs as spaces (text that has no label / value columns). */
    static List<Cell> untab(List<Cell> cells) {
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) out.add(c.cp() == TAB ? new Cell(' ', c.style()) : c);
        return out;
    }

    /** The label (before the first tab) and the value (after it, spaces around it dropped), or null without a tab. */
    static List<List<Cell>> split(List<Cell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).cp() != TAB) continue;
            return List.of(trim(untab(cells.subList(0, i))), trim(lead(untab(cells.subList(i + 1, cells.size())))));
        }
        return null;
    }

    /**
     * Width of a character in a text font. Characters the font lacks: '?' when the font has no fallback (they
     * are replaced by {@link #supported}), otherwise an estimate of the game's own glyph (9 for wide scripts).
     */
    static int advance(HudFont font, int cp) {
        int a = font.textAdvance(cp == TAB ? ' ' : cp);
        if (a >= 0) return a;
        if (!font.hasFallback()) return font.textAdvance('?');
        return cp >= 0x1100 ? 9 : 6;
    }

    /** Characters the font cannot draw become '?' (fonts without the game's font behind them). Tabs stay. */
    static List<Cell> supported(HudFont font, List<Cell> cells) {
        if (font.hasFallback()) return cells;
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) out.add(c.cp() == TAB || font.textAdvance(c.cp()) >= 0 ? c : new Cell('?', c.style()));
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
     * (a word longer than a line is cut). Text that does not fit in the last line ends with an ellipsis. Text
     * that takes two lines is broken where both lines come out most even, so no word is left alone on the
     * second line.
     */
    static List<List<Cell>> wrap(HudFont font, List<Cell> cells, int max, int maxLines) {
        List<Cell> all = trim(lead(untab(cells)));
        List<List<Cell>> greedy = greedy(font, all, max, maxLines);
        if (greedy.size() != 2 || greedy.getLast().getLast().cp() == ELLIPSIS) return greedy;
        int best = -1, bestWidth = Integer.MAX_VALUE;
        for (int i = 1; i < all.size(); i++) {
            if (all.get(i).cp() != ' ' || all.get(i - 1).cp() == ' ') continue;
            int a = width(font, trim(all.subList(0, i))), b = width(font, trim(lead(all.subList(i, all.size()))));
            if (a <= max && b <= max && Math.max(a, b) < bestWidth) {
                bestWidth = Math.max(a, b);
                best = i;
            }
        }
        if (best < 0) return greedy;
        return List.of(trim(all.subList(0, best)), trim(lead(all.subList(best, all.size()))));
    }

    private static List<List<Cell>> greedy(HudFont font, List<Cell> cells, int max, int maxLines) {
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

    private static List<Cell> lead(List<Cell> line) {
        int k = 0;
        while (k < line.size() && line.get(k).cp() == ' ') k++;
        return line.subList(k, line.size());
    }

    private static List<Cell> trim(List<Cell> line) {
        int end = line.size();
        while (end > 0 && line.get(end - 1).cp() == ' ') end--;
        return line.subList(0, end);
    }
}
