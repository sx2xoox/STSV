package dev.stsv.core.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HUD's glyph sheets (plugin resources pack/hud/, drawn by tools/assetgen/hud.py): every sheet is a grid of
 * equal cells, each cell one glyph with a name. The game measures a bitmap glyph by its rightmost visible column
 * (advance = round(width x scale) + 1, scale = height on screen / cell height); this class measures the same PNG
 * the same way, so the layout always knows where the game will put the pen.
 */
public final class HudSheets {

    static final String ROOT = "/pack/hud/";

    public record Sheet(String name, int cellW, int cellH, int cols, int height, List<String> names, int[] advances,
                        byte[] png) {

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

        /** The pen advance of a cell, as the game computes it. */
        public int advance(String cell) {
            return advances[index(cell)];
        }

        /** Width in screen pixels of what the cell shows (its advance minus the one pixel the game adds). */
        public int width(String cell) {
            return advance(cell) - 1;
        }
    }

    private final Map<String, Sheet> sheets;
    private final Map<Integer, Integer> charAdvance = new HashMap<>();

    private HudSheets(Map<String, Sheet> sheets) {
        this.sheets = sheets;
        for (String text : List.of("text", "hangul")) {
            Sheet s = sheets.get(text);
            if (s == null) continue;
            for (int i = 0; i < s.names().size(); i++) charAdvance.put(s.names().get(i).codePointAt(0), s.advances()[i]);
        }
    }

    public Sheet sheet(String name) {
        Sheet s = sheets.get(name);
        if (s == null) throw new IllegalArgumentException("no HUD sheet " + name);
        return s;
    }

    public java.util.Collection<Sheet> all() {
        return sheets.values();
    }

    /** Advance of a letter of the HUD's text sheets (ASCII, signs, Hangul), or -1 if the HUD has no glyph for it. */
    public int charAdvance(int codepoint) {
        Integer a = charAdvance.get(codepoint);
        return a == null ? -1 : a;
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
            List<String> names = new ArrayList<>();
            o.getAsJsonArray("names").forEach(n -> names.add(n.getAsString()));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) throw new IOException("HUD 시트를 읽지 못함: " + e.getKey());
            int[] adv = new int[names.size()];
            float scale = height / (float) ch;
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
                adv[k] = (int) (0.5f + w * scale) + 1;
            }
            out.put(e.getKey(), new Sheet(e.getKey(), cw, ch, cols, height, List.copyOf(names), adv, png));
        }
        return new HudSheets(out);
    }
}
