package dev.stsv.core.banner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What tools/assetgen/title_banners.py made (plugin resources pack/title_banners/banners.json): every banner theme cut
 * into parts (left end, tiles and their slices, centre ornament, right end, gem) with the code point of each part in
 * each of the 16 frames and the width the game measures for it (rightmost visible column + 1) at both sizes, and the
 * text glyphs (title: Galmuri9, nickname: the tall 5 x 7 face; both with Galmuri9 Hangul) with their widths.
 */
public record BannerCatalog(int frames, int ghostLevels, int pad, int gap, int spaceBase, double[] spaceSteps,
                            Map<Integer, Integer> titleWidths, Map<Integer, Integer> nickWidths, int space,
                            Map<String, Theme> themes, List<String> ghostFiles) {

    static final String ROOT = "/pack/title_banners/";

    /** One part in every frame: its code point per frame and the game's measured width per frame, big and mini. */
    public record Glyph(int[] chars, int[] big, int[] mini) {}

    /**
     * A theme at its canonical size (px, from the left of its picture): the plate's ends, the plate's middle row, the
     * left tile (and so where the left end stops), the right tile (the right end starts one tile after it), the centre
     * ornament [c0, c1), how many fills (1: only the left one, 2: one each side of the centre), the narrowest plate,
     * where the gem sits against its centre, the text tints (start and end colour, title and nickname).
     */
    public record Theme(String id, String name, int height, int mid, int plateLeft, int plateRight, int tile, int left,
                        int right, int c0, int c1, int width, int fills, int minPlate, int gemX, Map<String, Glyph> glyphs,
                        int[][] titleTint, int[][] nickTint) {

        public Glyph glyph(String name) {
            Glyph g = glyphs.get(name);
            if (g == null) throw new IllegalArgumentException("no part '" + name + "' in banner " + id);
            return g;
        }

        public int centreWidth() {
            return c1 - c0;
        }
    }

    public static BannerCatalog load() {
        try (InputStream in = BannerCatalog.class.getResourceAsStream(ROOT + "banners.json")) {
            if (in == null) throw new IOException(ROOT + "banners.json 없음");
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static BannerCatalog parse(JsonObject o) {
        JsonObject text = o.getAsJsonObject("text");
        Map<String, Theme> themes = new LinkedHashMap<>();
        for (JsonElement e : o.getAsJsonArray("themes")) {
            JsonObject t = e.getAsJsonObject();
            Map<String, Glyph> glyphs = new HashMap<>();
            for (Map.Entry<String, JsonElement> g : t.getAsJsonObject("glyphs").entrySet()) {
                JsonObject go = g.getValue().getAsJsonObject();
                glyphs.put(g.getKey(), new Glyph(go.get("chars").getAsString().codePoints().toArray(), ints(go.getAsJsonArray("big")),
                        ints(go.getAsJsonArray("mini"))));
            }
            JsonObject plate = t.getAsJsonObject("plate");
            JsonObject tint = t.getAsJsonObject("tint");
            themes.put(t.get("id").getAsString(), new Theme(t.get("id").getAsString(), t.get("name").getAsString(),
                    t.get("height").getAsInt(), t.get("mid").getAsInt(), plate.get("left").getAsInt(), plate.get("right").getAsInt(),
                    t.get("tile").getAsInt(), t.get("left").getAsInt(), t.get("right").getAsInt(), t.get("c0").getAsInt(),
                    t.get("c1").getAsInt(), t.get("width").getAsInt(), t.get("fills").getAsInt(), t.get("min_plate").getAsInt(),
                    t.getAsJsonObject("gem").get("x").getAsInt(), Collections.unmodifiableMap(glyphs),
                    colours(tint.getAsJsonArray("title")), colours(tint.getAsJsonArray("nick"))));
        }
        JsonObject space = o.getAsJsonObject("space");
        JsonArray steps = space.getAsJsonArray("steps");
        double[] st = new double[steps.size()];
        for (int i = 0; i < st.length; i++) st[i] = steps.get(i).getAsDouble();
        List<String> ghosts = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("ghosts")) ghosts.add(e.getAsString());
        return new BannerCatalog(o.get("frames").getAsInt(), o.get("ghost_levels").getAsInt(), o.get("pad").getAsInt(),
                o.get("gap").getAsInt(), space.get("base").getAsInt(), st, widths(text.getAsJsonObject("title")),
                widths(text.getAsJsonObject("nick")), text.get("space").getAsInt(), Collections.unmodifiableMap(themes), List.copyOf(ghosts));
    }

    private static Map<Integer, Integer> widths(JsonObject o) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) out.put(e.getKey().codePointAt(0), e.getValue().getAsJsonArray().get(0).getAsInt());
        return Collections.unmodifiableMap(out);
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static int[][] colours(JsonArray a) {
        int[][] out = new int[a.size()][];
        for (int i = 0; i < out.length; i++) out[i] = ints(a.get(i).getAsJsonArray());
        return out;
    }

    public Theme theme(String id) {
        return themes.get(id);
    }
}
