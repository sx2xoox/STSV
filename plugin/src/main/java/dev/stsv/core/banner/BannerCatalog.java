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
 * into parts (left end, tiles and their slices, centre ornament, right end) with the code point of each part in
 * each of the 16 frames and the width the game measures for it (rightmost visible column + 1) at both sizes (the
 * full picture, the chat band), the gem between title and nickname (TrueType layers, one per colour), and the text
 * glyphs (TrueType: the title in Galmuri9, the nickname in the tall 5 x 7 face, both with Galmuri9 Hangul) with their
 * advances in full-size pixels (ink + 1; the chat size is exactly half). files: every pack file.
 *
 * <p>A theme may have a chat banner ({@link Theme#chat}): its art drawn again for chat at full size, cut the same
 * way (fonts {@code <id>_chat}, {@code title_chat}, {@code nick_chat}; {@link BannerText.Size#TALL}).
 */
public record BannerCatalog(int frames, int ghostLevels, int pad, int gap, int spaceBase, double[] spaceSteps,
                            Map<Integer, Integer> titleWidths, Map<Integer, Integer> nickWidths, int space,
                            Map<String, Theme> themes, List<String> ghostFiles, List<String> files) {

    static final String ROOT = "/pack/title_banners/";

    /** One part in every frame: its code point per frame and the game's measured width per frame, big and mini. */
    public record Glyph(int[] chars, int[] big, int[] mini) {}

    /**
     * A theme (its name and menu item) at its canonical size (px, from the left of its picture): the plate's ends, the plate's middle row, the
     * left tile (and so where the left end stops), the right tile (the right end starts one tile after it), the centre
     * ornament [c0, c1), how many fills (1: only the left one, 2: one each side of the centre), the narrowest plate,
     * the gem (where it sits against the gap's centre; its layers: code points in the title fonts, colours and
     * advances in full-size pixels), the text tints (start and end colour, title and nickname), and the empty columns
     * before the left end's picture at both sizes (left out: a banner starts where it is drawn).
     * {@code chat}: its chat banner (the same id, its own parts and measures), null when it has none.
     */
    public record Theme(String id, String name, String icon, int height, int mid, int plateLeft, int plateRight, int tile, int left,
                        int right, int c0, int c1, int width, int fills, int minPlate, int gemX, int[] gemChars, int[][] gemTints,
                        int[] gemAdvances, Map<String, Glyph> glyphs, int[][] titleTint, int[][] nickTint, int leadBig, int leadMini,
                        Theme chat) {

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
            Theme t = theme(e.getAsJsonObject());
            themes.put(t.id(), t);
        }
        return parse(o, text, themes);
    }

    private static Theme theme(JsonObject t) {
        Map<String, Glyph> glyphs = new HashMap<>();
        for (Map.Entry<String, JsonElement> g : t.getAsJsonObject("glyphs").entrySet()) {
            JsonObject go = g.getValue().getAsJsonObject();
            glyphs.put(g.getKey(), new Glyph(go.get("chars").getAsString().codePoints().toArray(), ints(go.getAsJsonArray("big")),
                    ints(go.getAsJsonArray("mini"))));
        }
        JsonObject plate = t.getAsJsonObject("plate");
        JsonObject tint = t.getAsJsonObject("tint");
        JsonObject gem = t.getAsJsonObject("gem");
        return new Theme(t.get("id").getAsString(), t.get("name").getAsString(),
                t.has("icon") ? t.get("icon").getAsString() : "PAPER",
                t.get("height").getAsInt(), t.get("mid").getAsInt(), plate.get("left").getAsInt(), plate.get("right").getAsInt(),
                t.get("tile").getAsInt(), t.get("left").getAsInt(), t.get("right").getAsInt(), t.get("c0").getAsInt(),
                t.get("c1").getAsInt(), t.get("width").getAsInt(), t.get("fills").getAsInt(), t.get("min_plate").getAsInt(),
                gem.get("x").getAsInt(), gem.get("chars").getAsString().codePoints().toArray(), colours(gem.getAsJsonArray("tints")),
                ints(gem.getAsJsonArray("advances")), Collections.unmodifiableMap(glyphs),
                colours(tint.getAsJsonArray("title")), colours(tint.getAsJsonArray("nick")),
                t.getAsJsonObject("lead").get("big").getAsInt(), t.getAsJsonObject("lead").get("mini").getAsInt(),
                t.has("chat") ? theme(t.getAsJsonObject("chat")) : null);
    }

    private static BannerCatalog parse(JsonObject o, JsonObject text, Map<String, Theme> themes) {
        JsonObject space = o.getAsJsonObject("space");
        JsonArray steps = space.getAsJsonArray("steps");
        double[] st = new double[steps.size()];
        for (int i = 0; i < st.length; i++) st[i] = steps.get(i).getAsDouble();
        List<String> ghosts = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("ghosts")) ghosts.add(e.getAsString());
        List<String> files = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("files")) files.add(e.getAsString());
        return new BannerCatalog(o.get("frames").getAsInt(), o.get("ghost_levels").getAsInt(), o.get("pad").getAsInt(),
                o.get("gap").getAsInt(), space.get("base").getAsInt(), st, widths(text.getAsJsonObject("title")),
                widths(text.getAsJsonObject("nick")), text.get("space").getAsInt(), Collections.unmodifiableMap(themes), List.copyOf(ghosts),
                List.copyOf(files));
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
        return id == null ? null : themes.get(id);
    }

    private static BannerCatalog loaded;

    /** The jar's catalog, read once. */
    public static synchronized BannerCatalog get() {
        if (loaded == null) loaded = load();
        return loaded;
    }

    /** A resource of the pack folder (plugin resources pack/title_banners/), e.g. a file of {@link #files}. */
    public static byte[] resource(String path) {
        try (InputStream in = BannerCatalog.class.getResourceAsStream(ROOT + path)) {
            if (in == null) throw new IOException(ROOT + path + " 없음");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
