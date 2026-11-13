package dev.stsv.core.emote;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The emotes' resource-pack files (plugin resources pack/emotes, made by tools/assetgen/emotes.py): the font
 * {@code stsv:emote} with the pixel glyph ({@link PuppetText#PIXEL}, a TrueType square of exactly its advance: a
 * bitmap glyph would advance one pixel more and leave gaps) and the gap ({@link PuppetText#GAP}, a space of the same
 * advance).
 */
public final class EmotePack {

    static final String ROOT = "/pack/emotes/";
    public static final String FONT_JSON = "assets/stsv/font/emote.json";
    public static final String FONT_TTF = "assets/stsv/font/emote/pixel.ttf";
    static final List<String> FILES = List.of(FONT_JSON, FONT_TTF);

    private EmotePack() {}

    public static Map<String, byte[]> files() {
        Map<String, byte[]> out = new TreeMap<>();
        for (String path : FILES) out.put(path, resource(path));
        return out;
    }

    static byte[] resource(String path) {
        try (InputStream in = EmotePack.class.getResourceAsStream(ROOT + path)) {
            if (in == null) throw new IOException(ROOT + path + " 없음");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
