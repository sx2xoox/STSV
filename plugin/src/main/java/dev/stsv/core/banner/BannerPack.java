package dev.stsv.core.banner;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The title banners' resource-pack files: the generated fonts and glyph sheets (plugin resources
 * pack/title_banners/assets, made by tools/assetgen/title_banners.py) and, for {@link Animation#SHADER}, the text
 * core shader that animates them (pack/title_banners/shader, written for Minecraft 26.3's core/text).
 */
public final class BannerPack {

    /** How banners move (config title-banners.animation). */
    public enum Animation {
        /** The pack's text shader picks the frame by game time: chat, tab list and name tag, no server work. */
        SHADER,
        /** No shader: the server sends the name tag and tab list again with the next frame (chat stays still). */
        SERVER,
        /** No shader, no movement: frame 0 everywhere. */
        STATIC;

        public static Animation parse(String s) {
            if (s == null) return SHADER;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "server", "서버" -> SERVER;
                case "static", "off", "false", "정지" -> STATIC;
                default -> SHADER;
            };
        }
    }

    /** Where the shader goes in the pack (26.3: one core/text program for every text pipeline, by defines). */
    public static final String VSH = "assets/minecraft/shaders/core/text.vsh";
    public static final String FSH = "assets/minecraft/shaders/core/text.fsh";

    private BannerPack() {}

    public static Map<String, byte[]> files(BannerCatalog c, Animation mode) {
        Map<String, byte[]> out = new TreeMap<>();
        java.util.Set<String> ghosts = new java.util.HashSet<>(c.ghostFiles());
        for (String path : c.files()) {
            byte[] data = BannerCatalog.resource(path);
            if (mode == Animation.SERVER && ghosts.contains(path)) data = unghost(data, c.frames(), c.ghostLevels());
            out.put(path, data);
        }
        if (mode == Animation.SHADER) {
            out.put(VSH, BannerCatalog.resource("shader/text.vsh"));
            out.put(FSH, BannerCatalog.resource("shader/text.fsh"));
        }
        return out;
    }

    /**
     * A part's sheet (one row per frame) with frames 1.. given their alpha back (stored as 1..levels so the game hides
     * them without the shader), for the server-side animation, which sends one frame at a time.
     */
    static byte[] unghost(byte[] png, int frames, int levels) {
        try {
            BufferedImage in = ImageIO.read(new ByteArrayInputStream(png));
            BufferedImage im = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
            im.setRGB(0, 0, in.getWidth(), in.getHeight(), in.getRGB(0, 0, in.getWidth(), in.getHeight(), null, 0, in.getWidth()), 0,
                    in.getWidth());
            int cellH = im.getHeight() / frames;
            for (int y = cellH; y < im.getHeight(); y++) {
                for (int x = 0; x < im.getWidth(); x++) {
                    int argb = im.getRGB(x, y);
                    int a = argb >>> 24;
                    if (a == 0) continue;
                    int full = Math.min(255, Math.round(a * 255f / levels));
                    im.setRGB(x, y, (full << 24) | (argb & 0xFFFFFF));
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(im, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
