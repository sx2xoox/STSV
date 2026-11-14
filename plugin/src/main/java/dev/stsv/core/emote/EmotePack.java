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

    /** The wheel's font ({@link EmoteHud#FONT}): made here, so its glyphs and the code's are one list. */
    public static final String WHEEL_FONT_JSON = "assets/stsv/font/emote_wheel.json";

    public static Map<String, byte[]> files() {
        Map<String, byte[]> out = new TreeMap<>();
        for (String path : FILES) out.put(path, resource(path));
        out.put(WHEEL_FONT_JSON, wheelFont().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return out;
    }

    /**
     * The font of the wheel on the screen: the dial, the ring and the pointer (textures/font/emote_wheel, drawn by
     * tools/assetgen/gui.py) and the emotes' menu icons (textures/item/gui) as glyphs. A glyph's ascent is half its
     * height less 3: a text display draws a line's baseline 3 text pixels over its origin, so the picture's middle
     * is the display's origin.
     */
    static String wheelFont() {
        StringBuilder b = new StringBuilder("{\"providers\":[");
        b.append(provider("stsv:font/emote_wheel/dial.png", EmoteHud.DIAL_PX, EmoteHud.DIAL)).append(',');
        b.append(provider("stsv:font/emote_wheel/ring.png", 26, EmoteHud.RING)).append(',');
        b.append(provider("stsv:font/emote_wheel/pointer.png", 8, EmoteHud.POINTER));
        for (String icon : EmoteHud.ICONS) b.append(',').append(provider("stsv:item/gui/" + icon + ".png", 16, EmoteHud.glyph(icon)));
        return b.append("]}").toString();
    }

    private static String provider(String file, int height, char glyph) {
        return "{\"type\":\"bitmap\",\"file\":\"" + file + "\",\"ascent\":" + (height / 2 - 3) + ",\"height\":" + height
                + ",\"chars\":[\"\\u" + String.format("%04X", (int) glyph) + "\"]}";
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
