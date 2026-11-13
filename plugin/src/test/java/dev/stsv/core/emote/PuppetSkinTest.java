package dev.stsv.core.emote;

import dev.stsv.core.emote.EmoteRig.Joint;
import dev.stsv.core.emote.PuppetModel.Face;
import dev.stsv.core.emote.PuppetModel.Side;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skin on the puppet: every face of every box shows the pixels of the skin the game's own player model shows
 * there (read from the 26.3 client's ModelPart.Cube and PlayerModel), for both arm widths, both layers and the old
 * 64 x 32 skins.
 */
class PuppetSkinTest {

    /** A skin whose every pixel tells where it is: colour (x, y, 85). */
    static int at(int x, int y) {
        return x << 16 | y << 8 | 85;
    }

    static BufferedImage coordinates(int height) {
        BufferedImage im = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < 64; x++) im.setRGB(x, y, 0xFF000000 | at(x, y));
        return im;
    }

    /** part: first layer u, v, second layer u, v, width, height, depth (the game's player model). */
    static final Map<String, int[]> PARTS = Map.of(
            "head", new int[]{0, 0, 32, 0, 8, 8, 8}, "body", new int[]{16, 16, 16, 32, 8, 12, 4},
            "arm_r", new int[]{40, 16, 40, 32, 4, 12, 4}, "arm_l", new int[]{32, 48, 48, 48, 4, 12, 4},
            "leg_r", new int[]{0, 16, 0, 32, 4, 12, 4}, "leg_l", new int[]{16, 48, 0, 48, 4, 12, 4});

    /** joint: skin part, first row, rows. */
    static final Map<Joint, Object[]> BOXES = Map.of(
            Joint.HEAD, new Object[]{"head", 0, 8}, Joint.CHEST, new Object[]{"body", 0, 10}, Joint.HIPS, new Object[]{"body", 6, 8},
            Joint.ARM_R, new Object[]{"arm_r", 0, 12}, Joint.ARM_L, new Object[]{"arm_l", 0, 12},
            Joint.THIGH_R, new Object[]{"leg_r", 0, 8}, Joint.SHIN_R, new Object[]{"leg_r", 4, 8},
            Joint.THIGH_L, new Object[]{"leg_l", 0, 8}, Joint.SHIN_L, new Object[]{"leg_l", 4, 8});

    /** Where the game's model takes the pixel of a part's side from: column c (from the left as seen from outside), row r of the part. */
    static int side(String part, int layer, Side side, int c, int row, boolean slim) {
        int[] p = PARTS.get(part);
        int u = p[layer * 2], v = p[layer * 2 + 1], w = slim && part.startsWith("arm") ? 3 : p[4], d = p[6];
        return switch (side) {
            case RIGHT -> at(u + c, v + d + row);
            case FRONT -> at(u + d + c, v + d + row);
            case LEFT -> at(u + d + w + c, v + d + row);
            default -> at(u + d + w + d + c, v + d + row);
        };
    }

    static int[][] upright(Face f) {
        if (!f.flipped()) return f.grid();
        int[][] g = f.grid(), out = new int[g.length][g[0].length];
        for (int j = 0; j < g.length; j++) for (int i = 0; i < g[0].length; i++) out[j][i] = g[g.length - 1 - j][g[0].length - 1 - i];
        return out;
    }

    static Face face(List<Face> faces, Joint joint, int layer, Side side) {
        return faces.stream().filter(f -> f.joint() == joint && f.layer() == layer && f.side() == side).findFirst().orElse(null);
    }

    @Test
    void everyFaceShowsItsPixels() {
        for (boolean slim : new boolean[]{false, true}) {
            List<Face> faces = PuppetModel.faces(PuppetSkin.of(coordinates(64), slim));
            assertEquals(108, faces.size(), "nothing transparent: six faces of nine boxes in two layers");
            for (Face f : faces) {
                Object[] box = BOXES.get(f.joint());
                String part = (String) box[0];
                int r0 = (Integer) box[1], rows = (Integer) box[2];
                int[] p = PARTS.get(part);
                int u = p[f.layer() * 2], v = p[f.layer() * 2 + 1], w = slim && part.startsWith("arm") ? 3 : p[4], h = p[5], d = p[6];
                int[][] g = upright(f);
                String where = (slim ? "slim " : "") + f.joint() + " layer " + f.layer() + " " + f.side();
                boolean lid = f.side() == Side.TOP || f.side() == Side.BOTTOM;
                assertEquals(lid ? d : rows, g.length, where + " rows");
                assertEquals(lid || f.side() == Side.FRONT || f.side() == Side.BACK ? w : d, g[0].length, where + " columns");
                if (!lid) {
                    for (int r = 0; r < rows; r++) {
                        for (int c = 0; c < g[0].length; c++) {
                            int row = r0 + r, want;
                            if (part.equals("body") && row >= 12) {
                                // the seat: under the body's last row, the first rows of the legs side by side
                                want = switch (f.side()) {
                                    case FRONT -> c < 4 ? side("leg_r", f.layer(), Side.FRONT, c, row - 12, slim) : side("leg_l", f.layer(), Side.FRONT, c - 4, row - 12, slim);
                                    case BACK -> c < 4 ? side("leg_l", f.layer(), Side.BACK, c, row - 12, slim) : side("leg_r", f.layer(), Side.BACK, c - 4, row - 12, slim);
                                    case RIGHT -> side("leg_r", f.layer(), Side.RIGHT, c, row - 12, slim);
                                    default -> side("leg_l", f.layer(), Side.LEFT, c, row - 12, slim);
                                };
                            } else {
                                want = side(part, f.layer(), f.side(), c, row, slim);
                            }
                            assertEquals(want, g[r][c], where + " [" + r + "][" + c + "]");
                        }
                    }
                } else if (f.side() == Side.TOP && r0 == 0) {
                    // the part's own top: as seen from above, the back row first
                    for (int r = 0; r < d; r++) for (int c = 0; c < w; c++) assertEquals(at(u + d + c, v + r), g[r][c], where + " [" + r + "][" + c + "]");
                } else if (f.side() == Side.BOTTOM && r0 + rows == h) {
                    // the part's own bottom: the picture has it as seen through the part from above, so read from below its rows are the other way round
                    for (int r = 0; r < d; r++) for (int c = 0; c < w; c++) assertEquals(at(u + d + w + c, v + d - 1 - r), g[r][c], where + " [" + r + "][" + c + "]");
                } else {
                    // a cap: every pixel is the nearest side's pixel of the row next to the cut
                    int row = f.side() == Side.TOP ? r0 : r0 + rows - 1;
                    Face front = face(faces, f.joint(), f.layer(), Side.FRONT), back = face(faces, f.joint(), f.layer(), Side.BACK),
                            right = face(faces, f.joint(), f.layer(), Side.RIGHT), left = face(faces, f.joint(), f.layer(), Side.LEFT);
                    int[] fr = upright(front)[row - r0], ba = upright(back)[row - r0], ri = upright(right)[row - r0], le = upright(left)[row - r0];
                    int cw = fr.length;
                    for (int j = 0; j < d; j++) {
                        for (int i = 0; i < cw; i++) {
                            int[][] choices = {{d - 1 - j, fr[i]}, {j, ba[cw - 1 - i]}, {i, ri[j]}, {cw - 1 - i, le[d - 1 - j]}};
                            int best = 0;
                            for (int k = 1; k < 4; k++) if (choices[k][0] < choices[best][0]) best = k;
                            // seen from above the cap's row j is its distance from the back; a bottom is read from below
                            int r = f.side() == Side.TOP ? j : d - 1 - j;
                            assertEquals(choices[best][1], g[r][i], where + " cap [" + r + "][" + i + "]");
                        }
                    }
                }
            }
        }
    }

    /** The same by places everyone knows on a skin, with nothing computed. */
    @Test
    void knownPlaces() {
        List<Face> faces = PuppetModel.faces(PuppetSkin.of(coordinates(64), false));
        int[][] head = face(faces, Joint.HEAD, 0, Side.FRONT).grid();
        assertEquals(at(8, 8), head[0][0], "the face: its top left on the skin is the head's top right corner (the puppet's right)");
        assertEquals(at(15, 15), head[7][7]);
        assertEquals(at(40, 8), face(faces, Joint.HEAD, 1, Side.FRONT).grid()[0][0], "the hat's front");
        assertEquals(at(0, 8), face(faces, Joint.HEAD, 0, Side.RIGHT).grid()[0][0], "the right ear's side starts at the back");
        assertEquals(at(16, 8), face(faces, Joint.HEAD, 0, Side.LEFT).grid()[0][0]);
        assertEquals(at(24, 8), face(faces, Joint.HEAD, 0, Side.BACK).grid()[0][0]);
        assertEquals(at(8, 0), face(faces, Joint.HEAD, 0, Side.TOP).grid()[0][0]);
        assertEquals(at(16, 7), face(faces, Joint.HEAD, 0, Side.BOTTOM).grid()[0][0], "under the chin: the front edge (the picture's last row) is read first");
        assertEquals(at(20, 20), face(faces, Joint.CHEST, 0, Side.FRONT).grid()[0][0], "the chest's front");
        assertEquals(at(32, 20), face(faces, Joint.CHEST, 0, Side.BACK).grid()[0][0], "the back");
        assertEquals(at(20, 36), face(faces, Joint.CHEST, 1, Side.FRONT).grid()[0][0], "the jacket");
        // limbs hang from their joints: their sides are written upside down (the text starts at the far end)
        Face arm = face(faces, Joint.ARM_L, 0, Side.FRONT);
        assertTrue(arm.flipped());
        assertEquals(at(36, 52), arm.grid()[11][3], "the left arm's front, its first pixel last");
        assertEquals(at(39, 63), arm.grid()[0][0]);
        assertEquals(at(52, 52), upright(face(faces, Joint.ARM_L, 1, Side.FRONT))[0][0], "the left sleeve");
        assertEquals(at(44, 20), upright(face(faces, Joint.ARM_R, 0, Side.FRONT))[0][0]);
        assertEquals(at(4, 20), upright(face(faces, Joint.THIGH_R, 0, Side.FRONT))[0][0], "the right leg's front");
        assertEquals(at(4, 24), upright(face(faces, Joint.SHIN_R, 0, Side.FRONT))[0][0], "the shin starts two rows over the knee");
        assertEquals(at(7, 31), upright(face(faces, Joint.SHIN_R, 0, Side.FRONT))[7][3]);
        assertEquals(at(20, 52), upright(face(faces, Joint.THIGH_L, 0, Side.FRONT))[0][0], "the left leg's front");
        assertEquals(at(4, 52), upright(face(faces, Joint.THIGH_L, 1, Side.FRONT))[0][0], "the left trouser leg");
        assertEquals(at(4, 36), upright(face(faces, Joint.THIGH_R, 1, Side.FRONT))[0][0], "the right trouser leg");
        assertEquals(at(8, 19), face(faces, Joint.SHIN_R, 0, Side.BOTTOM).grid()[0][0], "the right sole");
        assertEquals(at(4, 16), face(faces, Joint.THIGH_R, 0, Side.TOP).grid()[0][0]);
        // the hips: the body's last rows and, under them, the legs' first
        int[][] hips = upright(face(faces, Joint.HIPS, 0, Side.FRONT));
        assertEquals(at(20, 26), hips[0][0], "two rows over the waist");
        assertEquals(at(27, 31), hips[5][7], "the body's last row");
        assertEquals(at(4, 20), hips[6][0], "then the right leg's first row");
        assertEquals(at(23, 53), hips[7][7], "and the left leg's second, at the puppet's left");
        int[][] seat = upright(face(faces, Joint.HIPS, 0, Side.BACK));
        assertEquals(at(28, 52), seat[6][0], "from behind the left leg comes first");
        assertEquals(at(15, 21), seat[7][7]);
        // caps
        int[][] waist = face(faces, Joint.CHEST, 0, Side.BOTTOM).grid();
        assertEquals(at(23, 29), waist[0][3], "the chest's cut end, front row: the front's pixel above it");
        assertEquals(at(17, 29), waist[2][0], "at the puppet's right edge: the right side's");
        assertEquals(at(29, 29), waist[1][7], "at the left edge: the left side's");
        assertEquals(at(39, 29), waist[3][0], "back row: the back's (read from behind, the puppet's right is its last column)");
    }

    @Test
    void slimArms() {
        List<Face> faces = PuppetModel.faces(PuppetSkin.of(coordinates(64), true));
        Face right = face(faces, Joint.ARM_R, 0, Side.FRONT), left = face(faces, Joint.ARM_L, 0, Side.FRONT);
        assertEquals(3, right.cols());
        assertEquals(3, left.cols());
        assertEquals(-5.5, right.centre().x, 1e-9, "three wide, against the body: x -7..-4");
        assertEquals(5.5, left.centre().x, 1e-9, "x 4..7");
        assertEquals(at(44, 20), upright(right)[0][0]);
        assertEquals(at(46, 31), upright(right)[11][2]);
        assertEquals(at(47, 20), upright(face(faces, Joint.ARM_R, 0, Side.LEFT))[0][0], "the inner side comes after three columns");
        assertEquals(at(51, 20), upright(face(faces, Joint.ARM_R, 0, Side.BACK))[0][0]);
        assertEquals(at(47, 19), face(faces, Joint.ARM_R, 0, Side.BOTTOM).grid()[0][0], "the hand end");
        assertEquals(at(39, 52), upright(face(faces, Joint.ARM_L, 0, Side.LEFT))[0][0]);
        assertEquals(at(43, 52), upright(face(faces, Joint.ARM_L, 0, Side.BACK))[0][0]);
        assertEquals(4, face(faces, Joint.ARM_R, 0, Side.RIGHT).cols(), "still four deep");
        // classic arms
        faces = PuppetModel.faces(PuppetSkin.of(coordinates(64), false));
        assertEquals(4, face(faces, Joint.ARM_R, 0, Side.FRONT).cols());
        assertEquals(-6, face(faces, Joint.ARM_R, 0, Side.FRONT).centre().x, 1e-9);
        assertEquals(at(48, 20), upright(face(faces, Joint.ARM_R, 0, Side.LEFT))[0][0]);
    }

    @Test
    void secondLayerAndAlpha() {
        BufferedImage im = coordinates(64);
        // the first layer is opaque whatever the picture says
        for (int y = 16; y < 32; y++) for (int x = 16; x < 40; x++) im.setRGB(x, y, at(x, y));
        // the second layer: nothing but a few pixels of the jacket's front and one of the hat
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) if (secondLayer(x, y)) im.setRGB(x, y, at(x, y));
        im.setRGB(20, 39, 0xFF000000 | at(20, 39));     // jacket front, row 3: the chest only
        im.setRGB(22, 43, 0x80000000 | at(22, 43));     // row 7: chest and hips (they overlap around the waist)
        im.setRGB(23, 43, 0x7F000000 | at(23, 43));     // not quite half there: not shown
        im.setRGB(41, 9, 0xFF000000 | at(41, 9));       // hat front
        List<Face> faces = PuppetModel.faces(PuppetSkin.of(im, false));
        assertEquals(54, faces.stream().filter(f -> f.layer() == 0).count());
        assertEquals(at(20, 20), face(faces, Joint.CHEST, 0, Side.FRONT).grid()[0][0], "a see-through body pixel is still drawn");
        List<Face> second = faces.stream().filter(f -> f.layer() == 1).toList();
        assertEquals(3, second.size(), "faces with nothing on them are left out: " + second.stream().map(f -> f.joint() + " " + f.side()).toList());
        int[][] chest = face(faces, Joint.CHEST, 1, Side.FRONT).grid();
        assertEquals(at(20, 39), chest[3][0]);
        assertEquals(at(22, 43), chest[7][2], "half there or more: shown");
        assertEquals(PuppetModel.NONE, chest[7][3]);
        assertEquals(PuppetModel.NONE, chest[0][0]);
        int[][] hips = upright(face(faces, Joint.HIPS, 1, Side.FRONT));
        assertEquals(at(22, 43), hips[1][2]);
        assertEquals(at(41, 9), face(faces, Joint.HEAD, 1, Side.FRONT).grid()[1][1]);
        // the second layer stands off its box: the hat half a pixel, the rest a quarter; cut ends half of that
        assertEquals(4.5, face(faces, Joint.HEAD, 1, Side.FRONT).centre().z, 1e-9);
        assertEquals(9.0 / 8, face(faces, Joint.HEAD, 1, Side.FRONT).cellWidth(), 1e-9);
        assertEquals(2.25, face(faces, Joint.CHEST, 1, Side.FRONT).centre().z, 1e-9);
        assertEquals(8.5 / 8, face(faces, Joint.CHEST, 1, Side.FRONT).cellWidth(), 1e-9);
        assertEquals((10 + 0.25 + 0.125) / 10, face(faces, Joint.CHEST, 1, Side.FRONT).cellHeight(), 1e-9);
        assertEquals(1, face(faces, Joint.CHEST, 0, Side.FRONT).cellWidth(), 1e-9);
        assertEquals(2, face(faces, Joint.CHEST, 0, Side.FRONT).centre().z, 1e-9);

        // nothing on the second layer at all: the first layer alone
        assertEquals(54, PuppetModel.faces(PuppetSkin.placeholder()).size());
    }

    private static boolean secondLayer(int x, int y) {
        return (y < 16 && x >= 32) || (y >= 32 && y < 48) || (y >= 48 && (x < 16 || x >= 48));
    }

    @Test
    void oldSkins() {
        // 64 x 32: one arm and one leg in the picture; the left ones are the right ones mirrored
        List<Face> faces = PuppetModel.faces(PuppetSkin.of(coordinates(32), false));
        assertEquals(54, faces.size(), "a solid hat area on an old skin is no hat; there are no other second layers");
        for (Joint[] pair : new Joint[][]{{Joint.THIGH_L, Joint.THIGH_R}, {Joint.SHIN_L, Joint.SHIN_R}, {Joint.ARM_L, Joint.ARM_R}}) {
            for (Side side : Side.values()) {
                Side from = side == Side.LEFT ? Side.RIGHT : side == Side.RIGHT ? Side.LEFT : side;
                int[][] left = upright(face(faces, pair[0], 0, side)), right = upright(face(faces, pair[1], 0, from));
                for (int r = 0; r < left.length; r++) {
                    for (int c = 0; c < left[0].length; c++) {
                        assertEquals(right[r][left[0].length - 1 - c], left[r][c], pair[0] + " " + side + " [" + r + "][" + c + "] mirrors " + pair[1] + " " + from);
                    }
                }
            }
        }
        assertEquals(at(7, 20), upright(face(faces, Joint.THIGH_L, 0, Side.FRONT))[0][0], "the left leg's front: the right leg's, from its other edge");
        assertEquals(at(51, 20), upright(face(faces, Joint.ARM_L, 0, Side.RIGHT))[0][0], "the left arm's inner side: the right arm's inner side, from its other edge");
        assertEquals(at(8, 8), face(faces, Joint.HEAD, 0, Side.FRONT).grid()[0][0]);
        assertEquals(at(20, 20), face(faces, Joint.CHEST, 0, Side.FRONT).grid()[0][0]);

        // a hat area with a hole in it is a hat
        BufferedImage hat = coordinates(32);
        hat.setRGB(63, 31, 0);
        hat.setRGB(42, 10, at(42, 10));
        faces = PuppetModel.faces(PuppetSkin.of(hat, false));
        assertTrue(faces.stream().filter(f -> f.layer() == 1).allMatch(f -> f.joint() == Joint.HEAD));
        assertEquals(6, faces.stream().filter(f -> f.layer() == 1).count());
        int[][] front = face(faces, Joint.HEAD, 1, Side.FRONT).grid();
        assertEquals(at(40, 8), front[0][0]);
        assertEquals(PuppetModel.NONE, front[2][2], "its transparent pixel");

        // a picture without an alpha channel (old skins often are)
        BufferedImage rgb = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(coordinates(32), 0, 0, null);
        assertEquals(54, PuppetModel.faces(PuppetSkin.of(rgb, false)).size());

        // anything else is no skin
        assertThrows(IllegalArgumentException.class, () -> PuppetSkin.of(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), false));
        assertThrows(IllegalArgumentException.class, () -> PuppetSkin.of(new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB), false));
        assertThrows(IllegalArgumentException.class, () -> PuppetSkin.of(new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB), false));
    }

    @Test
    void thePlaceholderIsAWholeSkin() {
        PuppetSkin skin = PuppetSkin.placeholder();
        assertFalse(skin.slim());
        List<Face> faces = PuppetModel.faces(skin);
        for (Face f : faces) for (int[] row : f.grid()) for (int c : row) assertTrue(c != PuppetModel.NONE && c != 0, f.joint() + " " + f.side() + ": every pixel has a colour");
        long colours = faces.stream().flatMapToInt(f -> java.util.Arrays.stream(f.grid()).flatMapToInt(java.util.Arrays::stream)).distinct().count();
        assertTrue(colours >= 5 && colours <= 8, "plain colours: " + colours);
    }

    @Test
    void fetchedSkins() throws Exception {
        // only the game's texture host, always over https
        assertEquals("https://textures.minecraft.net/texture/1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b",
                String.valueOf(SkinCache.source("http://textures.minecraft.net/texture/1a4af718455d4aab528e7a61f86fa25e6a369d1768dcb13f7df319a713eb810b")));
        assertNotNull(SkinCache.source("https://textures.minecraft.net/texture/0123456789abcdefABCDEF"));
        for (String bad : new String[]{null, "", "not a url", "ftp://textures.minecraft.net/texture/0123456789abcdef",
                "file:///etc/passwd", "http://example.com/texture/0123456789abcdef", "http://textures.minecraft.net.example.com/texture/0123456789abcdef",
                "http://textures.minecraft.net@example.com/texture/0123456789abcdef", "http://user@textures.minecraft.net/texture/0123456789abcdef",
                "http://textures.minecraft.net:8080/texture/0123456789abcdef", "http://textures.minecraft.net/texture/../secret",
                "http://textures.minecraft.net/texture/0123456789abcdef/more", "http://textures.minecraft.net/other/0123456789abcdef",
                "http://textures.minecraft.net/texture/xyz", "http://127.0.0.1/texture/0123456789abcdef"}) {
            assertNull(SkinCache.source(bad), String.valueOf(bad));
        }
        try (SkinCache cache = new SkinCache()) {
            assertTrue(cache.skin("http://example.com/texture/0123456789abcdef", false).get().isEmpty(), "never asked: no skin");
            assertTrue(cache.skin(null, true).get().isEmpty());
            assertTrue(cache.cached("http://textures.minecraft.net/texture/0123456789abcdef", false).isEmpty());
        }
        // what comes back: a skin picture or nothing
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(coordinates(64), "png", png);
        PuppetSkin skin = SkinCache.decode(png.toByteArray(), true).orElseThrow();
        assertTrue(skin.slim());
        assertEquals(0xFF000000 | at(8, 8), skin.pixel(8, 8));
        png.reset();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", png);
        assertTrue(SkinCache.decode(png.toByteArray(), false).isEmpty(), "a picture, but no skin");
        assertTrue(SkinCache.decode("<html>not found</html>".getBytes(), false).isEmpty());
        assertTrue(SkinCache.decode(new byte[0], false).isEmpty());
        assertTrue(SkinCache.decode(null, false).isEmpty());
        assertTrue(SkinCache.decode(new byte[SkinCache.MAX_BYTES + 1], false).isEmpty(), "too large to be a skin");
    }
}
