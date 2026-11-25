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
 * Every font that draws text holds the whole Hangul sheet of its size (hangul: Galmuri9, hangul7: Galmuri7, the
 * 2,350 syllables of KS X 1001 and every one the repository's texts use); see HudLayout.
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

    private HudSheets(Map<String, Sheet> sheets) {
        this.sheets = sheets;
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
     * These sheets and one more: `name`, `base` with `extra` see-through pixel rows under every cell. A font may
     * lift a glyph at most its own height above its line (the game refuses an ascent over the height), so a glyph
     * drawn high over the action bar's line needs a taller cell; the picture stays at the cell's top, where the
     * ascent puts it. The base must be drawn at its own size (height = cell height).
     */
    public HudSheets pad(String base, String name, int extra) {
        Sheet b = sheet(base);
        if (b.height() != b.cellH()) throw new IllegalArgumentException("HUD sheet " + base + " is not drawn at its own size");
        List<Integer> all = new ArrayList<>();
        for (int k = 0; k < b.names().size(); k++) all.add(k);
        return copy(b, name, all, b.cols(), Math.max(0, extra));
    }

    private HudSheets copy(Sheet b, String name, List<Integer> keep, int cols, int extra) {
        int rows = (keep.size() + cols - 1) / cols, ch = b.cellH() + extra;
        BufferedImage from = image(b.png());
        BufferedImage to = new BufferedImage(cols * b.cellW(), rows * ch, BufferedImage.TYPE_INT_ARGB);
        List<String> names = new ArrayList<>();
        int[] widths = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            int k = keep.get(i);
            int sx = (k % b.cols()) * b.cellW(), sy = (k / b.cols()) * b.cellH();
            int dx = (i % cols) * b.cellW(), dy = (i / cols) * ch;
            for (int y = 0; y < b.cellH(); y++) {
                for (int x = 0; x < b.cellW(); x++) to.setRGB(dx + x, dy + y, from.getRGB(sx + x, sy + y));
            }
            names.add(b.names().get(k));
            widths[i] = b.widths()[k];
        }
        Map<String, Sheet> out = new LinkedHashMap<>(sheets);
        out.put(name, new Sheet(name, b.cellW(), ch, cols, b.height() + extra, List.copyOf(names), widths, b.space(), png(to)));
        return new HudSheets(out);
    }

    /** The sheets bundled in the plugin jar. */
    public static HudSheets load() throws IOException {
        JsonObject manifest;
        try (InputStream in = HudSheets.class.getResourceAsStream(ROOT + "sheets.json")) {
            if (in == null) throw new IOException(ROOT + "sheets.json 없음");
            manifest = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<String, Sheet> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : manifest.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
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
        return new HudSheets(out);
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
