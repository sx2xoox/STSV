package dev.stsv.core.emote;

import java.awt.image.BufferedImage;

/**
 * A player skin as the client reads it: 64 x 64 ARGB pixels and whether the arms are slim (3 wide).
 *
 * <p>What the 26.3 client does to a downloaded skin (SkinTextureDownloader.processLegacySkin), done here too: a
 * 64 x 32 skin gets its left arm and leg from the right ones, mirrored, and loses its hat layer if nothing in the hat
 * area is transparent (old skins painted it solid); the first layer is always opaque.
 */
public final class PuppetSkin {

    public static final int SIZE = 64;

    private final int[] argb;
    private final boolean slim;

    private PuppetSkin(int[] argb, boolean slim) {
        this.argb = argb;
        this.slim = slim;
    }

    public boolean slim() {
        return slim;
    }

    /** ARGB of a pixel. */
    public int pixel(int x, int y) {
        return argb[y * SIZE + x];
    }

    /** A skin image: 64 x 64, or the old 64 x 32. Anything else is refused (IllegalArgumentException). */
    public static PuppetSkin of(BufferedImage image, boolean slim) {
        int w = image.getWidth(), h = image.getHeight();
        if (w != SIZE || (h != SIZE && h != SIZE / 2)) throw new IllegalArgumentException("skin is " + w + " x " + h);
        boolean legacy = h == SIZE / 2;
        int[] px = new int[SIZE * SIZE];
        image.getRGB(0, 0, w, h, px, 0, SIZE);
        if (legacy) {
            // source x, y, moved by dx, dy, width, height: copied mirrored left to right
            int[][] copies = {{4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                    {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                    {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
            for (int[] c : copies) {
                for (int j = 0; j < c[5]; j++) {
                    for (int i = 0; i < c[4]; i++) {
                        px[(c[1] + c[3] + j) * SIZE + c[0] + c[2] + i] = px[(c[1] + j) * SIZE + c[0] + c[4] - 1 - i];
                    }
                }
            }
        }
        opaque(px, 0, 0, 32, 16);
        if (legacy) notchHat(px);
        opaque(px, 0, 16, 64, 32);
        opaque(px, 16, 48, 48, 64);
        return new PuppetSkin(px, slim);
    }

    private static void opaque(int[] px, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) px[y * SIZE + x] |= 0xFF000000;
    }

    /** An old skin whose hat area has no transparent pixel at all: the hat was never meant to show. */
    private static void notchHat(int[] px) {
        for (int y = 0; y < 32; y++) for (int x = 32; x < 64; x++) if ((px[y * SIZE + x] >>> 24) < 128) return;
        for (int y = 0; y < 32; y++) for (int x = 32; x < 64; x++) px[y * SIZE + x] &= 0x00FFFFFF;
    }

    /**
     * The stand-in for a player whose skin could not be had: plain colours, made here (no game texture): a head in
     * a skin tone with dark hair and two eyes, a grey shirt, dark trousers, darker shoes; no second layer.
     */
    public static PuppetSkin placeholder() {
        BufferedImage im = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int skin = 0xFFD9A982, hair = 0xFF3B2A20, shirt = 0xFF8A8F99, trousers = 0xFF3A4458, shoes = 0xFF26282E;
        fill(im, 0, 0, 32, 16, skin);
        fill(im, 8, 0, 8, 8, hair);            // top of the head
        fill(im, 0, 8, 32, 2, hair);           // hair line all around
        fill(im, 24, 8, 8, 8, hair);           // back of the head
        fill(im, 10, 12, 1, 1, 0xFF2B2B33);    // eyes
        fill(im, 13, 12, 1, 1, 0xFF2B2B33);
        fill(im, 16, 16, 24, 16, shirt);       // body
        for (int[] arm : new int[][]{{40, 16}, {32, 48}}) {
            fill(im, arm[0], arm[1], 16, 16, shirt);
            fill(im, arm[0], arm[1] + 12, 16, 4, skin);       // hands
            fill(im, arm[0] + 8, arm[1], 4, 4, skin);
        }
        for (int[] leg : new int[][]{{0, 16}, {16, 48}}) {
            fill(im, leg[0], leg[1], 16, 16, trousers);
            fill(im, leg[0], leg[1] + 14, 16, 2, shoes);
            fill(im, leg[0] + 8, leg[1], 4, 4, shoes);        // soles
        }
        return of(im, false);
    }

    private static void fill(BufferedImage im, int x, int y, int w, int h, int argb) {
        for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) im.setRGB(i, j, argb);
    }
}
