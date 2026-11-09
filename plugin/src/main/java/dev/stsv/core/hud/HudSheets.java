package dev.stsv.core.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HUD's glyph sheets (plugin resources pack/hud/, drawn by tools/assetgen/hud.py): every sheet is a grid of
 * equal cells, each cell one glyph with a name. The game measures a bitmap glyph by its rightmost visible column
 * (advance = round(width x scale) + 1, scale = height on screen / cell height); this class measures the same PNG
 * the same way, so the layout always knows where the game will put the pen, at any height a font draws it.
 *
 * The panel's letters come from smaller sheets made here ({@link #subset}): only the Hangul syllables the server's
 * texts use, so the panel's many rows (three sizes, a range of heights) stay light on the client's memory.
 */
public final class HudSheets {

    static final String ROOT = "/pack/hud/";

    /**
     * A sheet: cell size, columns, the height the game draws a cell at by default, the cell names, every cell's
     * visible width (rightmost visible column + 1, 0 for an empty cell), the space advance of a letter sheet (0
     * for pictures), and the PNG.
     */
    public record Sheet(String name, int cellW, int cellH, int cols, int height, List<String> names, int[] widths,
                        int space, byte[] png) {

        public int rows() {
            return (names.size() + cols - 1) / cols;
        }

        public int index(String cell) {
            int i = names.indexOf(cell);
            if (i < 0) throw new IllegalArgumentException("no glyph '" + cell + "' in HUD sheet " + name);
            return i;
        }

        public boolean has(String cell) {
            return names.contains(cell);
        }

        /** The pen advance of a cell at the sheet's own height, as the game computes it. */
        public int advance(String cell) {
            return advanceAt(index(cell), 1);
        }

        /** The pen advance of cell k drawn `scale` times its own height (the game: (int) (0.5 + width x scale) + 1). */
        public int advanceAt(int k, int scale) {
            float s = scale * height / (float) cellH;
            return (int) (0.5 + (double) (widths[k] * s)) + 1;
        }

        /** Width in screen pixels of what the cell shows at its own height (its advance minus the game's pixel). */
        public int width(String cell) {
            return advance(cell) - 1;
        }
    }

    private final Map<String, Sheet> sheets;
    private final java.util.Set<Integer> panelLetters;

    private HudSheets(Map<String, Sheet> sheets, java.util.Set<Integer> panelLetters) {
        this.sheets = sheets;
        this.panelLetters = panelLetters;
    }

    /** The Hangul syllables of the repository's texts (sheets.json): the panel's letters always include them. */
    public java.util.Set<Integer> panelLetters() {
        return panelLetters;
    }

    public Sheet sheet(String name) {
        Sheet s = sheets.get(name);
        if (s == null) throw new IllegalArgumentException("no HUD sheet " + name);
        return s;
    }

    public boolean has(String name) {
        return sheets.containsKey(name);
    }

    public Collection<Sheet> all() {
        return sheets.values();
    }

    /**
     * These sheets and one more: `name`, the cells of `base` whose letter is one of `letters` (in the base's order,
     * same cell size, 64 to a row). Letters the base lacks are left out; with none left, the base's first cell
     * stays (a font provider needs a glyph).
     */
    public HudSheets subset(String base, String name, Collection<Integer> letters) {
        Sheet b = sheet(base);
        java.util.Set<Integer> wanted = new java.util.HashSet<>(letters);
        List<Integer> keep = new ArrayList<>();
        for (int k = 0; k < b.names().size(); k++) if (wanted.contains(b.names().get(k).codePointAt(0))) keep.add(k);
        if (keep.isEmpty()) keep.add(0);
        int cols = Math.min(64, keep.size()), rows = (keep.size() + cols - 1) / cols;
        BufferedImage from = image(b.png());
        BufferedImage to = new BufferedImage(cols * b.cellW(), rows * b.cellH(), BufferedImage.TYPE_INT_ARGB);
        List<String> names = new ArrayList<>();
        int[] widths = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            int k = keep.get(i);
            int sx = (k % b.cols()) * b.cellW(), sy = (k / b.cols()) * b.cellH();
            int dx = (i % cols) * b.cellW(), dy = (i / cols) * b.cellH();
            for (int y = 0; y < b.cellH(); y++) {
                for (int x = 0; x < b.cellW(); x++) to.setRGB(dx + x, dy + y, from.getRGB(sx + x, sy + y));
            }
            names.add(b.names().get(k));
            widths[i] = b.widths()[k];
        }
        Map<String, Sheet> out = new LinkedHashMap<>(sheets);
        out.put(name, new Sheet(name, b.cellW(), b.cellH(), cols, b.height(), List.copyOf(names), widths, b.space(), png(to)));
        return new HudSheets(out, panelLetters);
    }

    /** The sheets bundled in the plugin jar. */
    public static HudSheets load() throws IOException {
        JsonObject manifest;
        try (InputStream in = HudSheets.class.getResourceAsStream(ROOT + "sheets.json")) {
            if (in == null) throw new IOException(ROOT + "sheets.json 없음");
            manifest = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<String, Sheet> out = new LinkedHashMap<>();
        java.util.Set<Integer> panel = new java.util.TreeSet<>();
        for (Map.Entry<String, JsonElement> e : manifest.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            if (o.has("panel")) o.get("panel").getAsString().codePoints().forEach(panel::add);
            byte[] png;
            try (InputStream in = HudSheets.class.getResourceAsStream(ROOT + o.get("file").getAsString())) {
                if (in == null) throw new IOException(ROOT + o.get("file").getAsString() + " 없음");
                png = in.readAllBytes();
            }
            int cw = o.getAsJsonArray("cell").get(0).getAsInt(), ch = o.getAsJsonArray("cell").get(1).getAsInt();
            int cols = o.get("cols").getAsInt(), height = o.get("height").getAsInt();
            int space = o.has("space") ? o.get("space").getAsInt() : 0;
            List<String> names = new ArrayList<>();
            o.getAsJsonArray("names").forEach(n -> names.add(n.getAsString()));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("HUD 시트를 읽지 못함: " + e.getKey());
            int[] widths = new int[names.size()];
            for (int k = 0; k < names.size(); k++) {
                int x0 = (k % cols) * cw, y0 = (k / cols) * ch, w = 0;
                for (int x = cw - 1; x >= 0 && w == 0; x--) {
                    for (int y = 0; y < ch; y++) {
                        if ((img.getRGB(x0 + x, y0 + y) >>> 24) != 0) {
                            w = x + 1;
                            break;
                        }
                    }
                }
                widths[k] = w;
            }
            out.put(e.getKey(), new Sheet(e.getKey(), cw, ch, cols, height, List.copyOf(names), widths, space, png));
        }
        return new HudSheets(out, java.util.Collections.unmodifiableSet(panel));
    }

    private static BufferedImage image(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("not a PNG");
            return img;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
