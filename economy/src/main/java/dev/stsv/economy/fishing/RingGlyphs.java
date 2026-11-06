package dev.stsv.economy.fishing;

/**
 * The ring as a title made of resource-pack glyphs (font economy:fishring, made by
 * tools/assetgen/economy_fish.py): a base ring, the fish's silhouette in its rarity colour, a glyph per
 * green / gold / red cell and the needle, all stacked on one spot. Each glyph is 28 font units wide
 * (advance 29: every glyph has an invisible pixel in its last column, see economy_fish.py); a back space
 * returns the pen, and the total advance is 28 so the title centres it. No text shadow (a title has one,
 * and it would draw a dark copy of the ring 4 px off). Pure.
 */
public final class RingGlyphs {

    public static final String FONT = "economy:fishring", ICON_FONT = "economy:fishicon";
    static final char BASE = '', CENTER = '', GREEN = '', GOLD = '', RED = '', NEEDLE = '';
    static final char BACK = '', FORWARD = '';
    static final String SHAPES = "flpcq";

    private RingGlyphs() {}

    /** The title text (MiniMessage) for the ring right now. */
    public static String title(Ring r, String shape) {
        StringBuilder sb = new StringBuilder("<!shadow><white><font:" + FONT + ">");
        int rim = r.flash > 0 ? r.flashKind : 0;
        sb.append((char) (BASE + rim)).append(BACK);
        int s = Math.max(0, SHAPES.indexOf(shape));
        sb.append((char) (CENTER + s * 4 + r.rarity().index())).append(BACK);
        for (int seg = 0; seg < Ring.SEG; seg++) {
            if (r.inZone(seg)) sb.append((char) ((r.inPerfect(seg) ? GOLD : GREEN) + seg)).append(BACK);
            else if (r.inTrap(seg)) sb.append((char) (RED + seg)).append(BACK);
        }
        sb.append((char) (NEEDLE + r.needle())).append(BACK).append(FORWARD);
        return sb.append("</font></white>").toString();
    }

    /** The glyph of a fish's picture (economy:fishicon), by its place in fish.yml; what follows has the usual style again. */
    public static String icon(int index) {
        return "<!shadow><white><font:" + ICON_FONT + ">" + (char) (0xE000 + index) + "<reset>";
    }
}
