package dev.stsv.core.banner;

import java.util.ArrayList;
import java.util.List;

/**
 * Where every part of a banner goes for one title and one nickname, in the big size's pixels (the mini size is the
 * same at half). No Bukkit types (unit-tested); tools/assetgen/title_banners.py layout() draws the previews the same
 * way.
 *
 * <pre>
 *   L | left fill | C | right fill | R          (the plate: L's plate part .. R's plate part)
 *        [title] ◆ [nickname]                   (centred on the plate, PAD from its ends at least)
 * </pre>
 * A fill is whole tiles plus aligned slices of the same tile for the rest: the left fill's rest comes after its tiles
 * (slices from the tile's start, largest first: the tile goes on seamlessly), the right fill's rest before its tiles
 * (slices up to the tile's end, smallest first: its last tile meets R as in the picture). Themes without a centre
 * ornament have one fill.
 */
public record BannerLayout(BannerCatalog.Theme theme, List<Piece> pieces, List<Letter> title, List<Letter> nick,
                           int plateWidth, int width, int textStart) {

    /** A part (its glyph name in the theme) with its left edge. */
    public record Piece(String glyph, int x) {}

    /** A letter: its code point, left edge, and where it is along its word (0..1) for the tint. */
    public record Letter(int codePoint, int x, double along) {}

    public static BannerLayout of(BannerCatalog c, BannerCatalog.Theme t, String title, String nick) {
        int tw = width(c.titleWidths(), c.space(), title);
        int nw = width(c.nickWidths(), c.space(), nick);
        boolean titled = tw > 0;
        int content = titled ? tw + c.gap() + nw : nw;
        int pw = Math.max(t.minPlate(), content + 2 * c.pad());
        int extra = pw - t.minPlate();
        int cw = t.centreWidth();
        int fl = t.fills() == 2 ? Math.clamp(t.plateLeft() + Math.floorDiv(pw - cw, 2) - t.left(), 0, extra) : extra;
        int fr = extra - fl;
        int P = t.tile();

        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece("L", 0));
        int x = t.left();
        for (int k = 0; k < fl / P; k++, x += P) pieces.add(new Piece("TL", x));
        for (int[] s : prefix(fl % P)) {
            pieces.add(new Piece("TL:" + s[0] + ":" + s[1], x));
            x += s[1];
        }
        if (cw > 0) {
            pieces.add(new Piece("C", x));
            x += cw;
        }
        for (int[] s : suffix(fr % P, P)) {
            pieces.add(new Piece("TR:" + s[0] + ":" + s[1], x));
            x += s[1];
        }
        for (int k = 0; k < fr / P; k++, x += P) pieces.add(new Piece("TR", x));
        pieces.add(new Piece("R", x));
        int width = x + (t.width() - t.right() - P);

        int start = t.plateLeft() + Math.floorDiv(pw - content, 2);
        List<Letter> titleLetters = titled ? letters(c.titleWidths(), c.space(), title, start) : List.of();
        if (titled) pieces.add(new Piece("G", start + tw + c.gap() / 2 + t.gemX()));
        List<Letter> nickLetters = letters(c.nickWidths(), c.space(), nick, titled ? start + tw + c.gap() : start);
        return new BannerLayout(t, List.copyOf(pieces), titleLetters, nickLetters, pw, width, start);
    }

    /** Width of a word: known letters by their glyph width, a space by the space; letters the font lacks are left out. */
    static int width(java.util.Map<Integer, Integer> widths, int space, String s) {
        int w = 0;
        for (int cp : s.codePoints().toArray()) {
            if (cp == ' ') w += space;
            else w += widths.getOrDefault(cp, 0);
        }
        return w;
    }

    private static List<Letter> letters(java.util.Map<Integer, Integer> widths, int space, String s, int x) {
        int[] cps = s.codePoints().toArray();
        List<Letter> out = new ArrayList<>();
        for (int k = 0; k < cps.length; k++) {
            int cp = cps[k];
            if (cp == ' ') {
                x += space;
                continue;
            }
            Integer w = widths.get(cp);
            if (w == null) continue;
            out.add(new Letter(cp, x, cps.length < 2 ? 0 : k / (double) (cps.length - 1)));
            x += w;
        }
        return out;
    }

    /** The slices (offset, width) that make the first r columns of a tile: largest first, each starting where the last ended. */
    public static List<int[]> prefix(int r) {
        List<int[]> out = new ArrayList<>();
        int o = 0;
        for (int b = Integer.highestOneBit(Math.max(1, r)); b > 0 && r > 0; b >>= 1) {
            if ((r & b) != 0) {
                out.add(new int[]{o, b});
                o += b;
            }
        }
        return out;
    }

    /** The slices that make the last r columns of a P-column tile: smallest first, ending at the tile's end. */
    public static List<int[]> suffix(int r, int P) {
        List<int[]> out = new ArrayList<>();
        int o = P - r;
        for (int b = 1; b <= r; b <<= 1) {
            if ((r & b) != 0) {
                out.add(new int[]{o, b});
                o += b;
            }
        }
        return out;
    }
}
