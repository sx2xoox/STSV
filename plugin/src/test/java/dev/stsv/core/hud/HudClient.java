package dev.stsv.core.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays a component out the way the game does, from the generated font JSON only (not from the layout's own
 * bookkeeping): a line is centred (starts at -(width / 2)), every character moves the pen by its advance, and a
 * bitmap glyph's top is at pen line + 7 - ascent. Gives every visible glyph's position, sheet and cell.
 */
final class HudClient {

    /** A visible glyph: x of its left edge from the screen's middle, y of its top from the pen line. */
    record Draw(int x, int y, String sheet, String cell, int color, boolean shadow) {}

    record Laid(int width, List<Draw> draws) {}

    private record Provider(String type, String sheet, int ascent, Map<Integer, String> cells, Map<Integer, Integer> advances) {}

    private final Map<Key, List<Provider>> fonts = new HashMap<>();
    private final HudSheets sheets;

    HudClient(HudSheets sheets, Map<String, JsonObject> fontFiles) {
        this.sheets = sheets;
        for (Map.Entry<String, JsonObject> f : fontFiles.entrySet()) {
            String path = f.getKey().replaceFirst("^assets/", "").replaceFirst("\\.json$", "");
            Key key = Key.key(path.substring(0, path.indexOf('/')), path.substring(path.indexOf("/font/") + 6));
            List<Provider> list = new ArrayList<>();
            for (JsonElement e : f.getValue().getAsJsonArray("providers")) {
                JsonObject p = e.getAsJsonObject();
                String type = p.get("type").getAsString();
                Map<Integer, String> cells = new HashMap<>();
                Map<Integer, Integer> adv = new HashMap<>();
                if (type.equals("space")) {
                    for (var a : p.getAsJsonObject("advances").entrySet()) adv.put(a.getKey().codePointAt(0), a.getValue().getAsInt());
                    list.add(new Provider(type, null, 0, cells, adv));
                } else if (type.equals("bitmap")) {
                    String file = p.get("file").getAsString();
                    String sheet = file.equals("minecraft:font/ascii.png") ? "ascii"
                            : file.substring(file.lastIndexOf('/') + 1).replace(".png", "");
                    int r = 0;
                    for (JsonElement row : p.getAsJsonArray("chars")) {
                        int[] cps = row.getAsString().codePoints().toArray();
                        for (int c = 0; c < cps.length; c++) {
                            if (cps[c] == 0) continue;
                            if (sheet.equals("ascii")) {
                                cells.put(cps[c], new String(Character.toChars(cps[c])));
                                adv.put(cps[c], HudText.ascii(cps[c]));
                            } else {
                                HudSheets.Sheet s = sheets.sheet(sheet);
                                int k = r * s.cols() + c;
                                cells.put(cps[c], s.names().get(k));
                                adv.put(cps[c], s.advances()[k]);
                            }
                        }
                        r++;
                    }
                    list.add(new Provider(type, sheet, p.get("ascent").getAsInt(), cells, adv));
                } else {
                    list.add(new Provider(type, null, 0, cells, adv));
                }
            }
            fonts.put(key, list);
        }
    }

    private record Glyph(int cp, Style style) {}

    Laid lay(Component c) {
        List<Glyph> glyphs = new ArrayList<>();
        Deque<Style> stack = new ArrayDeque<>();
        stack.push(Style.empty());
        ComponentFlattener.basic().flatten(c, new FlattenerListener() {
            @Override
            public void pushStyle(Style style) {
                stack.push(stack.peek().merge(style));
            }

            @Override
            public void component(String text) {
                Style s = stack.peek();
                text.codePoints().forEach(cp -> glyphs.add(new Glyph(cp, s)));
            }

            @Override
            public void popStyle(Style style) {
                stack.pop();
            }
        });
        int width = 0;
        for (Glyph g : glyphs) width += advance(g);
        int pen = -(width / 2);
        List<Draw> out = new ArrayList<>();
        for (Glyph g : glyphs) {
            Provider p = provider(g);
            if (p != null && p.type().equals("bitmap")) {
                int color = g.style().color() == null ? 0xFFFFFF : g.style().color().value();
                boolean shadow = g.style().shadowColor() != null && g.style().shadowColor().alpha() > 0;
                out.add(new Draw(pen, 7 - p.ascent(), p.sheet(), p.cells().get(g.cp()), color, shadow));
            }
            pen += advance(g);
        }
        return new Laid(width, out);
    }

    private Provider provider(Glyph g) {
        Key font = g.style().font();
        if (font == null) throw new AssertionError("a HUD character without a HUD font: U+" + Integer.toHexString(g.cp()));
        List<Provider> list = fonts.get(font);
        if (list == null) throw new AssertionError("no font " + font);
        for (Provider p : list) if (p.advances().containsKey(g.cp())) return p;
        return list.stream().anyMatch(p -> p.type().equals("reference")) ? null : fail(font, g.cp());
    }

    private static Provider fail(Key font, int cp) {
        throw new AssertionError(font + " has no glyph U+" + Integer.toHexString(cp));
    }

    private int advance(Glyph g) {
        Provider p = provider(g);
        if (p == null) return g.cp() >= 0x1100 ? 9 : 6;   // the game's own font (fallback)
        return p.advances().get(g.cp());
    }
}
