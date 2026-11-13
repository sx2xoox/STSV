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
        for (BannerCatalog.Theme t : C.themes().values()) {
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

    @Test
    void longerTextMakesALongerPlate() {
        for (BannerCatalog.Theme t : C.themes().values()) {
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
