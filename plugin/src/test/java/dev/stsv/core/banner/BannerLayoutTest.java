package dev.stsv.core.banner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerLayoutTest {

    static final BannerCatalog C = BannerCatalog.get();
    static final List<String[]> WORDS = List.of(new String[]{"", "a"}, new String[]{"단골", "홍길동"}, new String[]{"명장 대장장이", "Steve_2024"},
            new String[]{"최고 부자", "xozne"}, new String[]{"아주 아주 아주 긴 칭호 이름입니다", "Long_Name_12345"});

    @Test
    void shipsTheFiveApprovedThemes() {
        assertEquals(List.of("fire", "lightning", "neon", "bloodmoon", "prism"), List.copyOf(C.themes().keySet()));
        assertEquals(16, C.frames());
    }

    @Test
    void slicesMakeAnyRemainder() {
        for (int P : new int[]{16, 32}) {
            for (int r = 0; r < P; r++) {
                int o = 0;
                for (int[] s : BannerLayout.prefix(r)) {
                    assertEquals(o, s[0], "prefix pieces follow each other");
                    assertEquals(Integer.bitCount(s[1]), 1);
                    o += s[1];
                }
                assertEquals(r, o);
                int end = P - r;
                for (int[] s : BannerLayout.suffix(r, P)) {
                    assertEquals(end, s[0], "suffix pieces follow each other");
                    end += s[1];
                }
                assertEquals(P, end, "a suffix ends at the tile end");
            }
        }
    }

    /** The parts of the plate follow each other without gaps or overlaps, and the text sits on the plate. */
    @Test
    void platePartsTileExactlyAndTextFits() {
        for (BannerCatalog.Theme t : plates()) {
            for (String[] w : WORDS) {
                BannerLayout l = BannerLayout.of(C, t, w[0], w[1]);
                int x = 0;
                for (BannerLayout.Piece p : l.pieces()) {
                    if (p.glyph().equals("G")) continue;
                    assertEquals(x, p.x(), t.id() + " " + p.glyph() + " starts where the last part ended");
                    x += partWidth(t, p.glyph());
                }
                assertEquals(l.width(), x, t.id() + ": the parts make the banner's width");
                int tw = BannerLayout.width(C.titleWidths(), C.space(), w[0]);
                int nw = BannerLayout.width(C.nickWidths(), C.space(), w[1]);
                int content = tw > 0 ? tw + C.gap() + nw : nw;
                assertEquals(Math.max(t.minPlate(), content + 2 * C.pad()), l.plateWidth());
                assertTrue(l.textStart() >= t.plateLeft() + C.pad() || l.plateWidth() == t.minPlate(), "padding");
                assertTrue(l.textStart() + content <= t.plateLeft() + l.plateWidth(), t.id() + ": text ends on the plate");
                assertEquals(w[0].isEmpty(), l.pieces().stream().noneMatch(p -> p.glyph().equals("G")), "a gem only between title and name");
                assertEquals(w[1].replace(" ", "").length(), l.nick().size(), "every letter of the name has a glyph");
            }
        }
    }

    /** Every theme and every chat banner (a theme's art drawn again for chat: cut and laid out the same way). */
    static List<BannerCatalog.Theme> plates() {
        List<BannerCatalog.Theme> out = new java.util.ArrayList<>();
        for (BannerCatalog.Theme t : C.themes().values()) {
            out.add(t);
            if (t.chat() != null) out.add(t.chat());
        }
        return out;
    }

    /** The theme a size draws: the theme itself, or its chat banner (null when it has none). */
    static BannerCatalog.Theme of(BannerCatalog.Theme t, BannerText.Size size) {
        return size == BannerText.Size.TALL ? t.chat() : t;
    }

    @Test
    void fireHasAChatBanner() {
        BannerCatalog.Theme fire = C.theme("fire");
        assertTrue(fire.chat() != null && fire.chat().id().equals("fire") && fire.chat().chat() == null);
        // the full banner's plate (17 rows): 8 under the middle row (and the picture's empty last row), over it 8
        // and one row of glow (and the empty first row): nothing rises over the plate
        assertEquals(10, fire.chat().height() - fire.chat().mid());
        assertEquals(10, fire.chat().mid());
    }

    @Test
    void longerTextMakesALongerPlate() {
        for (BannerCatalog.Theme t : plates()) {
            int a = BannerLayout.of(C, t, "단골", "Steve").width();
            int b = BannerLayout.of(C, t, "단골", "Steve_the_Builder").width();
            assertTrue(b > a, t.id());
        }
    }

    @Test
    void lettersTheFontLacksAreLeftOut() {
        BannerCatalog.Theme t = C.theme("fire");
        BannerLayout l = BannerLayout.of(C, t, "", "ab☃c");
        assertEquals(3, l.nick().size());
        assertFalse(C.nickWidths().containsKey(0x2603));
    }

    static int partWidth(BannerCatalog.Theme t, String g) {
        return switch (g) {
            case "L" -> t.left();
            case "TL", "TR" -> t.tile();
            case "C" -> t.centreWidth();
            case "R" -> t.width() - t.right() - t.tile();
            default -> Integer.parseInt(g.substring(g.lastIndexOf(':') + 1));
        };
    }
}
