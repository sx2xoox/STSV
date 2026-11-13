package dev.stsv.core.emote;

import dev.stsv.core.banner.BannerText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * A face of the puppet as the text of its text display: one glyph of the pack's pixel font ({@link EmotePack}) per
 * skin pixel, coloured per character, rows separated by line breaks. Neighbouring pixels of one colour are one piece
 * of text (also across a line break), which is most of what keeps the spawn packets small.
 */
public final class PuppetText {

    public static final Key FONT = Key.key("stsv", "emote");
    /** One skin pixel: a square of {@link PuppetModel#CELL} text pixels, advancing exactly as much. */
    public static final char PIXEL = '';
    /** A transparent pixel: the same advance, nothing drawn (not U+0020: the game may wrap a line at a real space). */
    public static final char GAP = '';
    /** No line of a face is ever this wide: the text never wraps. */
    public static final int LINE_WIDTH = 4096;

    /** Pixels of one colour in reading order: 0xRRGGBB, or {@link PuppetModel#NONE} for gaps; text with its line breaks. */
    public record Run(int colour, String text) {}

    private PuppetText() {}

    /**
     * The colour a skin pixel is sent in: shaded for its face (text displays get no directional light), and never a
     * title banner's marker colour. The pack's text shader (pack/title_banners/shader) takes a glyph whose text
     * colour is one of its markers (red 250 .. 253 with green and blue near 255: BannerText.reserved) for a part of
     * a banner and hides, moves or recolours it: such a pixel goes out with red 254.
     */
    public static int colour(int rgb, double shade) {
        int r = shaded(rgb >> 16 & 0xFF, shade), g = shaded(rgb >> 8 & 0xFF, shade), b = shaded(rgb & 0xFF, shade);
        while (BannerText.reserved(TextColor.color(r, g, b))) r++;
        return r << 16 | g << 8 | b;
    }

    private static int shaded(int channel, double shade) {
        return Math.clamp(Math.round(channel * shade), 0, 255);
    }

    /** The face in pieces of one colour each, in reading order. */
    public static List<Run> runs(PuppetModel.Face face) {
        List<Run> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int current = PuppetModel.NONE;
        int[][] grid = face.grid();
        for (int row = 0; row < grid.length; row++) {
            for (int c : grid[row]) {
                int colour = c == PuppetModel.NONE ? PuppetModel.NONE : colour(c, face.shade());
                if (colour != current && !text.isEmpty()) {
                    out.add(new Run(current, text.toString()));
                    text.setLength(0);
                }
                current = colour;
                text.append(colour == PuppetModel.NONE ? GAP : PIXEL);
            }
            if (row < grid.length - 1) text.append('\n');
        }
        if (!text.isEmpty()) out.add(new Run(current, text.toString()));
        return out;
    }

    /** The face as a text display's text. */
    public static Component component(PuppetModel.Face face) {
        TextComponent.Builder b = Component.text().font(FONT);
        for (Run run : runs(face)) {
            b.append(run.colour() == PuppetModel.NONE ? Component.text(run.text()) : Component.text(run.text(), TextColor.color(run.colour())));
        }
        return b.build();
    }
}
