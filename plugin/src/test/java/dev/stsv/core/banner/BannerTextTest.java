package dev.stsv.core.banner;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerTextTest {

    static final BannerCatalog C = BannerCatalog.get();

    @Test
    void markersEncodeEveryFrameAndAreNotWhite() {
        for (int f = 0; f < 16; f++) {
            TextColor m = BannerText.marker(f);
            assertEquals(f, BannerText.frameOf(m));
            assertNotEquals(0xFFFFFF, m.value());
            assertTrue(m.red() >= 250 && m.green() >= 250 && m.blue() >= 250, "almost white without the shader");
        }
        assertEquals(-1, BannerText.frameOf(TextColor.color(0xFFFFFF)));
        assertEquals(-1, BannerText.frameOf(TextColor.color(0xFF9A3C)));
    }

    @Test
    void gameAdvanceMatchesTheClient() {
        // BitmapProvider: (int) (0.5 + width * scale) + 1
        assertEquals(17, BannerText.advance(16, 1));
        assertEquals(2, BannerText.advance(1, 0.5));
        assertEquals(9, BannerText.advance(16, 0.5));
        assertEquals(1, BannerText.advance(0, 1));
    }

    /** Plays the component as the client does (pen + advances) and checks every glyph lands where the layout wants it. */
    @Test
    void everyGlyphLandsWhereTheLayoutPutsIt() {
        for (BannerCatalog.Theme theme : C.themes().values()) {
            for (String[] w : BannerLayoutTest.WORDS) {
                for (BannerText.Size size : BannerText.Size.values()) {
                    BannerCatalog.Theme t = BannerLayoutTest.of(theme, size);
                    if (t == null) continue;
                    BannerLayout l = BannerLayout.of(C, t, w[0], w[1]);
                    for (int frame : new int[]{BannerText.ALL_FRAMES, 0, 7}) {
                        List<BannerText.Placed> placed = BannerText.place(C, l, size, frame);
                        Component c = BannerText.component(C, l, size, frame);
                        double pen = 0;
                        int k = 0;
                        for (TextComponent part : parts(c)) {
                            for (int cp : part.content().codePoints().toArray()) {
                                double sp = BannerText.spaceAdvance(C, cp);
                                if (!Double.isNaN(sp)) {
                                    pen += sp;
                                    continue;
                                }
                                BannerText.Placed g = placed.get(k++);
                                assertEquals(g.codePoint(), cp);
                                assertEquals(g.font(), part.font());
                                assertEquals(g.color(), part.color());
                                assertEquals(g.x(), pen, 1e-9, t.id() + " " + size + " glyph " + k);
                                pen += g.advance();
                            }
                        }
                        assertEquals(placed.size(), k);
                        assertEquals(BannerText.end(l, size), pen, 1e-9, "the pen ends after the banner");
                    }
                }
            }
        }
    }

    @Test
    void shaderModeStacksSixteenFramesAndFallbackOne() {
        BannerCatalog.Theme t = C.theme("neon");
        BannerLayout l = BannerLayout.of(C, t, "단골", "xozne");
        long plates = l.pieces().stream().filter(p -> !p.glyph().equals("G")).count();
        List<BannerText.Placed> all = BannerText.place(C, l, BannerText.Size.MINI, BannerText.ALL_FRAMES);
        List<BannerText.Placed> one = BannerText.place(C, l, BannerText.Size.MINI, 3);
        assertEquals(plates * 16, all.stream().filter(p -> BannerText.frameOf(p.color()) >= 0).count());
        assertEquals(0, one.stream().filter(p -> BannerText.frameOf(p.color()) >= 0).count(), "no markers without the shader");
        assertEquals(all.size() - plates * 15, one.size());
        // plate first, then gem and letters (TrueType: grayscale glyphs, drawn on top by the game)
        int lastPlate = -1, firstText = Integer.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).font().value().endsWith("neon_mini")) lastPlate = i;
            else firstText = Math.min(firstText, i);
        }
        assertTrue(lastPlate < firstText);
    }

    @Test
    void plateHasNoShadowBigLettersDo() {
        BannerLayout l = BannerLayout.of(C, C.theme("fire"), "단골", "xozne");
        Component big = BannerText.component(C, l, BannerText.Size.BIG, BannerText.ALL_FRAMES);
        Component mini = BannerText.component(C, l, BannerText.Size.MINI, BannerText.ALL_FRAMES);
        for (TextComponent p : parts(big)) {
            boolean plate = p.font().value().equals("title_banner/fire") || p.font().value().equals("title_banner/title")
                    && p.content().codePoints().anyMatch(cp -> java.util.Arrays.stream(C.theme("fire").gemChars()).anyMatch(g -> g == cp));
            if (plate) assertEquals(ShadowColor.none(), p.shadowColor());
            if (p.font().value().equals("title_banner/nick")) assertEquals(null, p.shadowColor(), "the game's drop shadow");
        }
        for (TextComponent p : parts(mini)) assertEquals(ShadowColor.none(), p.shadowColor());
    }

    static List<TextComponent> parts(Component c) {
        List<TextComponent> out = new ArrayList<>();
        if (c instanceof TextComponent t && !t.content().isEmpty()) out.add(t);
        for (Component ch : c.children()) out.addAll(parts(ch));
        return out;
    }
}
