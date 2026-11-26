package dev.stsv.core.painting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 그림 그리기 (이젤): the pure parts, raycast to pixel, brush, fill, undo, palette and storage. */
class PaintingLogicTest {

    private static final byte RED = MapColors.id(28, 2), BLUE = MapColors.id(25, 2);

    // --- raycast -> pixel ------------------------------------------------------------------------

    /** A canvas facing north (towards -z), its centre at (0.5, 1.5, 0): the viewer stands at z < 0 looking +z. */
    private static CanvasRay.Face north() {
        return CanvasRay.Face.upright(new double[]{0.5, 1.5, 0}, new double[]{0, 0, -1});
    }

    @Test
    void lookingAtTheCentreHitsTheMiddlePixel() {
        CanvasRay.Hit h = CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{0, 0, 1}, north(), 5);
        assertNotNull(h);
        assertEquals(2.0, h.distance(), 1e-9);
        assertEquals(64, h.u());
        assertEquals(64, h.v());
    }

    @Test
    void topLeftAsSeenFromTheFrontIsPixelZero() {
        // facing +z (south) the viewer's right is -x: the top left corner is at the larger x
        CanvasRay.Hit tl = CanvasRay.hit(new double[]{0.999, 1.999, -1}, new double[]{0, 0, 1}, north(), 5);
        assertEquals(0, tl.u());
        assertEquals(0, tl.v());
        CanvasRay.Hit br = CanvasRay.hit(new double[]{0.001, 1.001, -1}, new double[]{0, 0, 1}, north(), 5);
        assertEquals(127, br.u());
        assertEquals(127, br.v());
        // a canvas facing east (+x): the viewer looks -x, their right is -z
        CanvasRay.Face east = CanvasRay.Face.upright(new double[]{0, 0.5, 0.5}, new double[]{1, 0, 0});
        CanvasRay.Hit e = CanvasRay.hit(new double[]{2, 0.999, 0.999}, new double[]{-1, 0, 0}, east, 5);
        assertEquals(0, e.u());
        assertEquals(0, e.v());
    }

    @Test
    void aSlantedLookFindsWhereItCrossesThePlane() {
        // from (0.5, 1.5, -2) looking at (0.25, 1.75, 0): a quarter to the right (-x) and up
        CanvasRay.Hit h = CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{-0.25, 0.25, 2}, north(), 5);
        assertEquals(96, h.u());
        assertEquals(32, h.v());
    }

    @Test
    void behindAwayPastTheEdgeOrTooFarIsNoHit() {
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, 2}, new double[]{0, 0, -1}, north(), 5), "from behind");
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{0, 0, -1}, north(), 5), "looking away");
        assertNull(CanvasRay.hit(new double[]{1.5, 1.5, -2}, new double[]{0, 0, 1}, north(), 5), "past the edge");
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, -6}, new double[]{0, 0, 1}, north(), 5), "too far");
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{1, 0, 0}, north(), 5), "edge on");
    }

    // --- brush ------------------------------------------------------------------------------------

    @Test
    void brushStampsAreOneTwoAndARoundFour() {
        PaintCanvas c = new PaintCanvas();
        assertEquals(1, c.stamp(10, 10, 1, RED, null));
        assertEquals(RED, c.get(10, 10));
        c = new PaintCanvas();
        assertEquals(4, c.stamp(10, 10, 2, RED, null));
        for (int[] p : new int[][]{{9, 9}, {10, 9}, {9, 10}, {10, 10}}) assertEquals(RED, c.get(p[0], p[1]));
        c = new PaintCanvas();
        assertEquals(12, c.stamp(10, 10, 4, RED, null), "4 x 4 without its corners");
        assertEquals(MapColors.CANVAS, c.get(8, 8));
        assertEquals(RED, c.get(9, 8));
        assertEquals(RED, c.get(10, 11));
        assertEquals(MapColors.CANVAS, c.get(11, 11), "a cut corner");
        // at the edge only the part on the canvas is painted
        c = new PaintCanvas();
        assertEquals(1, c.stamp(0, 0, 2, RED, null));
        assertEquals(8, new PaintCanvas().stamp(127, 127, 4, RED, null), "3 x 3 on the canvas, one cut corner");
    }

    @Test
    void aLineHasNoGaps() {
        PaintCanvas c = new PaintCanvas();
        c.line(0, 0, 127, 40, 1, RED, null);
        assertEquals(RED, c.get(0, 0));
        assertEquals(RED, c.get(127, 40));
        for (int u = 0; u < 128; u++) {
            boolean any = false;
            for (int v = 0; v < 128; v++) any |= c.get(u, v) == RED;
            assertTrue(any, "column " + u);
        }
        assertEquals(1, new PaintCanvas().line(5, 5, 5, 5, 1, RED, null));
    }

    // --- flood fill -------------------------------------------------------------------------------

    @Test
    void fillStaysInsideItsRegionAndTheCanvas() {
        PaintCanvas c = new PaintCanvas();
        // a closed box 10..20: filling inside changes the 9 x 9 inside only
        for (int i = 10; i <= 20; i++) {
            c.set(i, 10, BLUE, null);
            c.set(i, 20, BLUE, null);
            c.set(10, i, BLUE, null);
            c.set(20, i, BLUE, null);
        }
        assertEquals(81, c.fill(15, 15, RED, null));
        assertEquals(MapColors.CANVAS, c.get(5, 5));
        assertEquals(BLUE, c.get(10, 15));
        // outside: everything else (diagonal gaps do not leak: 4-connected)
        assertEquals(128 * 128 - 121, c.fill(0, 0, RED, null));
        assertEquals(0, c.fill(0, 0, RED, null), "already that colour");
        assertEquals(0, c.fill(-1, 3, RED, null), "off the canvas");
        PaintCanvas d = new PaintCanvas();
        d.set(1, 0, BLUE, null);
        d.set(0, 1, BLUE, null);
        assertEquals(1, d.fill(0, 0, RED, null), "the corner pixel is shut in by two edges");
        assertEquals(128 * 128, new PaintCanvas().fill(64, 64, RED, null), "a blank canvas fills whole");
    }

    // --- undo -------------------------------------------------------------------------------------

    @Test
    void undoPutsBackEachStrokeNewestFirst() {
        PaintCanvas c = new PaintCanvas();
        byte[] blank = c.pixels();
        UndoStack undo = new UndoStack(20);
        PaintCanvas.Stroke a = new PaintCanvas.Stroke();
        c.line(0, 0, 50, 50, 2, RED, a);
        undo.push(a);
        byte[] afterA = c.pixels();
        PaintCanvas.Stroke b = new PaintCanvas.Stroke();
        c.line(0, 0, 50, 50, 4, BLUE, b);          // over the same pixels twice: the first colour is what comes back
        c.line(0, 0, 50, 50, 1, RED, b);
        undo.push(b);
        PaintCanvas.Stroke f = new PaintCanvas.Stroke();
        c.fill(100, 5, BLUE, f);
        undo.push(f);
        undo.push(new PaintCanvas.Stroke());       // empty: not kept
        assertEquals(3, undo.size());
        assertTrue(undo.undo(c));
        assertTrue(undo.undo(c));
        assertArrayEquals(afterA, c.pixels());
        assertTrue(undo.undo(c));
        assertArrayEquals(blank, c.pixels());
        assertFalse(undo.undo(c));
    }

    @Test
    void undoKeepsOnlyTheLastStrokes() {
        PaintCanvas c = new PaintCanvas();
        UndoStack undo = new UndoStack(20);
        for (int i = 0; i < 25; i++) {
            PaintCanvas.Stroke s = new PaintCanvas.Stroke();
            c.set(i, 0, RED, s);
            undo.push(s);
        }
        assertEquals(20, undo.size());
        while (undo.undo(c)) { }
        for (int i = 0; i < 5; i++) assertEquals(RED, c.get(i, 0), "the 5 oldest strokes stay");
        for (int i = 5; i < 25; i++) assertEquals(MapColors.CANVAS, c.get(i, 0));
    }

    // --- palette ----------------------------------------------------------------------------------

    @Test
    void paletteMapsColoursToTheirNearestShade() {
        assertEquals(34, MapColors.CANVAS);
        assertEquals(0xFFFFFF, MapColors.rgb(MapColors.CANVAS));
        assertEquals(MapColors.CANVAS, MapColors.nearest(0xFFFFFF));
        assertEquals(0xFF0000, MapColors.rgb(MapColors.id(4, 2)));
        assertEquals(MapColors.id(4, 2), MapColors.nearest(0xFE0101));
        // every colour of the palette maps back to itself (no two shades with the same RGB that matter)
        for (int[] page : MapColors.PAGES) {
            for (int base : page) {
                for (int shade = 0; shade < 4; shade++) {
                    byte id = MapColors.id(base, shade);
                    assertEquals(MapColors.rgb(id), MapColors.rgb(MapColors.nearest(MapColors.rgb(id))), base + "/" + shade);
                }
            }
        }
        // shades: 180, 220, 255, 135 of the base; bytes over 127 work
        assertEquals((0x7F * 180 / 255) << 16 | (0xA7 * 180 / 255) << 8 | (0x96 * 180 / 255), MapColors.rgb(MapColors.id(61, 0)));
        assertTrue(MapColors.valid(MapColors.id(61, 3)));
        assertTrue(MapColors.id(61, 3) < 0, "id 247 is a negative byte");
        assertFalse(MapColors.valid((byte) 0));
        assertFalse(MapColors.valid((byte) 3));
        assertFalse(MapColors.valid((byte) 248));
    }

    @Test
    void thePaletteMenuOffersEveryBaseOnce() {
        Set<Integer> seen = new HashSet<>();
        for (int[] page : MapColors.PAGES) {
            assertTrue(page.length <= 9, "a column each");
            for (int b : page) assertTrue(seen.add(b), "twice: " + b);
        }
        assertEquals(61, seen.size());
        assertFalse(seen.contains(0));
        assertEquals(244, MapColors.usable());
        assertEquals(4, MapColors.SHADE_ROWS.length);
        assertEquals(2, MapColors.SHADE_ROWS[0], "lightest on top");
    }

    @Test
    @SuppressWarnings("removal")
    void theTableMatchesBukkitsMapPalette() {
        // org.bukkit.map.MapPalette has the server's own table: every colour we offer is the same RGB there
        for (int base = 1; base < MapColors.BASES; base++) {
            for (int shade = 0; shade < 4; shade++) {
                byte id = MapColors.id(base, shade);
                java.awt.Color c = org.bukkit.map.MapPalette.getColor(id);
                assertEquals(c.getRGB() & 0xFFFFFF, MapColors.rgb(id), "base " + base + " shade " + shade);
            }
        }
    }

    // --- storage ----------------------------------------------------------------------------------

    @Test
    void aPaintingSurvivesARestart(@TempDir Path dir) throws Exception {
        PaintingStore store = new PaintingStore(dir);
        UUID painter = UUID.randomUUID(), easel = UUID.randomUUID();
        Painting p = new Painting(42, UUID.randomUUID(), painter, "화가", 1_700_000_000_000L);
        p.canvas().line(0, 0, 127, 127, 4, RED, null);
        p.canvas().fill(127, 0, MapColors.id(61, 3), null);
        p.mountedOn(easel);
        store.add(p);
        store.write(42, p.encode());
        assertTrue(p.encode().length < 4000, "compact: " + p.encode().length + " bytes");

        PaintingStore again = new PaintingStore(dir);
        assertTrue(again.exists(42));
        assertFalse(again.exists(43));
        assertEquals(java.util.List.of(42), again.ids());
        Painting q = again.get(42).orElseThrow();
        assertEquals(p.id(), q.id());
        assertEquals(painter, q.painter());
        assertEquals("화가", q.painterName());
        assertEquals(easel, q.mountedOn());
        assertFalse(q.finished());
        assertNull(q.title());
        assertArrayEquals(p.canvas().pixels(), q.canvas().pixels());

        q.finish("  저녁\n노을이   지는 바다와 갈매기 두 마리, 그리고 등대  ", 1_700_000_100_000L);
        assertTrue(q.finished());
        assertNull(q.mountedOn(), "a finished painting is off the easel");
        assertEquals("저녁 노을이 지는 바다와 갈매기 두 마리,", q.title());
        again.write(42, q.encode());
        Painting r = Painting.decode(Files.readAllBytes(dir.resolve("42.stsvp")));
        assertEquals(q.title(), r.title());
        assertEquals(1_700_000_100_000L, r.finishedAt());
        assertTrue(Arrays.equals(q.canvas().pixels(), r.canvas().pixels()));
        assertNull(Painting.cleanTitle("   "));
    }

    @Test
    void brokenOrForeignFilesAreNotPaintings(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("7.stsvp"), new byte[]{1, 2, 3});
        Files.writeString(dir.resolve("notes.txt"), "x");
        PaintingStore store = new PaintingStore(dir);
        assertEquals(java.util.List.of(7), store.ids());
        assertTrue(store.get(7).isEmpty());
        // invalid colours read back as canvas white
        byte[] px = new byte[PaintCanvas.PIXELS];
        px[5] = 2;
        px[6] = RED;
        PaintCanvas c = new PaintCanvas(px);
        assertEquals(MapColors.CANVAS, c.get(5, 0));
        assertEquals(RED, c.get(6, 0));
    }
}
