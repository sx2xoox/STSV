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

/** 그림 그리기 (이젤): the pure parts, raycast to cell, brush, fill, undo, palette and storage. */
class PaintingLogicTest {

    private static final byte RED = MapColors.id(28, 2), BLUE = MapColors.id(25, 2);

    // --- raycast -> cell ------------------------------------------------------------------------

    /** A canvas facing north (towards -z), its centre at (0.5, 1.5, 0): the viewer stands at z < 0 looking +z. */
    private static CanvasRay.Face north() {
        return CanvasRay.Face.upright(new double[]{0.5, 1.5, 0}, new double[]{0, 0, -1});
    }

    @Test
    void lookingAtTheCentreHitsTheMiddlePixel() {
        CanvasRay.Hit h = CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{0, 0, 1}, north(), 5);
        assertNotNull(h);
        assertEquals(2.0, h.distance(), 1e-9);
        assertEquals(16, h.u());
        assertEquals(16, h.v());
        assertTrue(h.exact());
    }

    @Test
    void topLeftAsSeenFromTheFrontIsCellZero() {
        // facing +z (south) the viewer's right is -x: the top left corner is at the larger x
        CanvasRay.Hit tl = CanvasRay.hit(new double[]{0.999, 1.999, -1}, new double[]{0, 0, 1}, north(), 5);
        assertEquals(0, tl.u());
        assertEquals(0, tl.v());
        CanvasRay.Hit br = CanvasRay.hit(new double[]{0.001, 1.001, -1}, new double[]{0, 0, 1}, north(), 5);
        assertEquals(31, br.u());
        assertEquals(31, br.v());
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
        assertEquals(24, h.u());
        assertEquals(8, h.v());
    }

    @Test
    void behindAwayPastTheEdgeOrTooFarIsNoHit() {
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, 2}, new double[]{0, 0, -1}, north(), 5), "from behind");
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{0, 0, -1}, north(), 5), "looking away");
        assertNull(CanvasRay.hit(new double[]{1.5, 1.5, -2}, new double[]{0, 0, 1}, north(), 5), "past the edge");
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, -6}, new double[]{0, 0, 1}, north(), 5), "too far");
        assertNull(CanvasRay.hit(new double[]{0.5, 1.5, -2}, new double[]{1, 0, 0}, north(), 5), "edge on");
    }

    @Test
    void aLookSnapsToItsCell() {
        // cell 5 spans 5/32 .. 6/32 across: anywhere in it is cell 5 (the viewer's right is -x on this face)
        for (double x : new double[]{5.01, 5.5, 5.99}) {
            CanvasRay.Hit h = CanvasRay.hit(new double[]{1 - x / 32, 1.5, -1}, new double[]{0, 0, 1}, north(), 5);
            assertEquals(5, h.u(), "at " + x);
        }
        assertEquals(0, CanvasRay.cell(0));
        assertEquals(31, CanvasRay.cell(0.9999));
        assertEquals(31, CanvasRay.cell(1.2), "clamped");
        assertEquals(0, CanvasRay.cell(-0.1), "clamped");
        assertEquals(3, PaintCanvas.cellOf(15));
        assertEquals(4, PaintCanvas.cellOf(16));
    }

    @Test
    void justPastTheEdgeTakesTheEdgeCell() {
        double slack = 2.0 / 32;
        // a hair past the left edge (left is +x on a north face), and below the bottom
        CanvasRay.Hit left = CanvasRay.hit(new double[]{1.03, 1.5, -1}, new double[]{0, 0, 1}, north(), 5, slack);
        assertNotNull(left);
        assertFalse(left.exact());
        assertEquals(0, left.u());
        assertEquals(16, left.v());
        CanvasRay.Hit below = CanvasRay.hit(new double[]{0.5, 0.97, -1}, new double[]{0, 0, 1}, north(), 5, slack);
        assertEquals(31, below.v());
        assertNull(CanvasRay.hit(new double[]{1.03, 1.5, -1}, new double[]{0, 0, 1}, north(), 5), "no slack: a miss");
        assertNull(CanvasRay.hit(new double[]{1.2, 1.5, -1}, new double[]{0, 0, 1}, north(), 5, slack), "too far off");
    }

    @Test
    void thePreviewBoxCoversItsCellsInFrontOfThePicture() {
        // the top left cell of the north face: x from 1 - 1/32 to 1, y from 2 - 1/32 to 2, just in front (z < 0)
        double[][] b = CanvasRay.box(north(), 0, 0, 0, 0, 0.004, 0.002);
        assertEquals(1 - 1.0 / 32, b[0][0], 1e-9);
        assertEquals(1, b[1][0], 1e-9);
        assertEquals(2 - 1.0 / 32, b[0][1], 1e-9);
        assertEquals(2, b[1][1], 1e-9);
        assertEquals(-0.006, b[0][2], 1e-9);
        assertEquals(-0.004, b[1][2], 1e-9);
        // three cells wide, across the whole face's width at the end
        double[][] w = CanvasRay.box(north(), 29, 0, 31, 0, 0, 0.01);
        assertEquals(0, w[0][0], 1e-9);
        assertEquals(3.0 / 32, w[1][0] - w[0][0], 1e-9);
    }

    // --- brush ------------------------------------------------------------------------------------

    /** True when all 16 pixels of the cell are this colour. */
    private static boolean wholeCell(PaintCanvas c, int cu, int cv, byte colour) {
        for (int dv = 0; dv < 4; dv++) for (int du = 0; du < 4; du++) if (c.get(cu * 4 + du, cv * 4 + dv) != colour) return false;
        return true;
    }

    @Test
    void theGridIs32CellsOf4Pixels() {
        assertEquals(128, PaintCanvas.SIZE, "stored and shown at a map's 128 pixels");
        assertEquals(4, PaintCanvas.CELL);
        assertEquals(32, PaintCanvas.GRID);
        assertArrayEquals(new int[]{1, 2, 3}, PaintCanvas.BRUSH_SIZES);
    }

    @Test
    void brushStampsWholeCells() {
        PaintCanvas c = new PaintCanvas();
        assertEquals(1, c.stamp(10, 10, 1, RED, null));
        assertTrue(wholeCell(c, 10, 10, RED));
        assertEquals(MapColors.CANVAS, c.get(39, 40), "the next pixel left is the next cell");
        assertEquals(MapColors.CANVAS, c.get(44, 44));
        c = new PaintCanvas();
        assertEquals(4, c.stamp(10, 10, 2, RED, null));
        for (int[] p : new int[][]{{9, 9}, {10, 9}, {9, 10}, {10, 10}}) assertTrue(wholeCell(c, p[0], p[1], RED));
        c = new PaintCanvas();
        assertEquals(9, c.stamp(10, 10, 3, RED, null), "3 x 3 around it");
        assertTrue(wholeCell(c, 9, 9, RED));
        assertTrue(wholeCell(c, 11, 11, RED));
        assertTrue(wholeCell(c, 12, 12, MapColors.CANVAS));
        assertEquals(0, c.stamp(10, 10, 3, RED, null), "already painted");
        // at the edge only the part on the grid is painted
        assertEquals(1, new PaintCanvas().stamp(0, 0, 2, RED, null));
        assertEquals(4, new PaintCanvas().stamp(31, 31, 3, RED, null));
        assertArrayEquals(new int[]{30, 30, 31, 31}, PaintCanvas.stampBounds(31, 31, 3));
        assertArrayEquals(new int[]{9, 9, 10, 10}, PaintCanvas.stampBounds(10, 10, 2));
        assertNull(PaintCanvas.stampBounds(40, 3, 1));
    }

    @Test
    void aLineGoesCellByCellWithNoGaps() {
        PaintCanvas c = new PaintCanvas();
        c.line(0, 0, 31, 10, 1, RED, null);
        assertTrue(wholeCell(c, 0, 0, RED));
        assertTrue(wholeCell(c, 31, 10, RED));
        int painted = 0;
        for (int u = 0; u < 32; u++) {
            int inColumn = 0;
            for (int v = 0; v < 32; v++) {
                byte k = c.cell(u, v);
                if (k == RED) {
                    assertTrue(wholeCell(c, u, v, RED), "whole cells only");
                    inColumn++;
                }
            }
            assertEquals(1, inColumn, "one cell in column " + u);
            painted += inColumn;
        }
        assertEquals(32, painted);
        assertEquals(1, new PaintCanvas().line(5, 5, 5, 5, 1, RED, null));
        // a jump across the canvas in one tick (a fast flick) still joins up
        PaintCanvas d = new PaintCanvas();
        d.line(2, 30, 29, 1, 1, RED, null);
        for (int v = 1; v <= 30; v++) {
            boolean any = false;
            for (int u = 0; u < 32; u++) any |= d.cell(u, v) == RED;
            assertTrue(any, "row " + v);
        }
    }

    @Test
    void pickReadsTheCellsColour() {
        PaintCanvas c = new PaintCanvas();
        c.stamp(7, 3, 1, BLUE, null);
        assertEquals(BLUE, c.cell(7, 3));
        assertEquals(MapColors.CANVAS, c.cell(8, 3));
        // an older pixel-drawn cell: its top-left pixel speaks for it
        c.set(32, 0, RED, null);
        c.set(35, 3, BLUE, null);
        assertEquals(RED, c.cell(8, 0));
    }

    // --- flood fill -------------------------------------------------------------------------------

    @Test
    void fillStaysInsideItsRegionAndTheGrid() {
        PaintCanvas c = new PaintCanvas();
        // a closed box of cells 10..20: filling inside changes the 9 x 9 cells inside only
        for (int i = 10; i <= 20; i++) {
            c.setCell(i, 10, BLUE, null);
            c.setCell(i, 20, BLUE, null);
            c.setCell(10, i, BLUE, null);
            c.setCell(20, i, BLUE, null);
        }
        assertEquals(81, c.fill(15, 15, RED, null));
        assertTrue(wholeCell(c, 15, 15, RED));
        assertTrue(wholeCell(c, 5, 5, MapColors.CANVAS));
        assertTrue(wholeCell(c, 10, 15, BLUE));
        // outside: everything else (diagonal gaps do not leak: 4-connected)
        assertEquals(32 * 32 - 121, c.fill(0, 0, RED, null));
        assertEquals(0, c.fill(0, 0, RED, null), "already that colour");
        assertEquals(0, c.fill(-1, 3, RED, null), "off the grid");
        PaintCanvas d = new PaintCanvas();
        d.setCell(1, 0, BLUE, null);
        d.setCell(0, 1, BLUE, null);
        assertEquals(1, d.fill(0, 0, RED, null), "the corner cell is shut in by two edges");
        PaintCanvas blank = new PaintCanvas();
        assertEquals(32 * 32, blank.fill(16, 16, RED, null), "a blank canvas fills whole");
        for (int i = 0; i < PaintCanvas.PIXELS; i++) assertEquals(RED, blank.pixels()[i]);
    }

    @Test
    void fillOnAnOlderPixelPaintingLeavesNoHalfCells() {
        // drawn at 128 px before cells: a 1-pixel blue line through the middle of cell row 5 (v = 22), and a red dot
        // on the top-left pixel of cell (3, 3)
        PaintCanvas c = new PaintCanvas();
        for (int u = 0; u < 128; u++) c.set(u, 22, BLUE, null);
        c.set(12, 12, RED, null);
        // the line is not on any cell's top-left pixel: those cells count as white, and turn wholly green
        byte green = MapColors.id(27, 2);
        int changed = c.fill(0, 0, green, null);
        assertEquals(32 * 32 - 1, changed, "every cell but the red one");
        for (int cu = 0; cu < 32; cu++) {
            for (int cv = 0; cv < 32; cv++) {
                if (cu == 3 && cv == 3) continue;
                assertTrue(wholeCell(c, cu, cv, green), cu + "," + cv);
            }
        }
        // the red cell is its own region, and filling it recolours all 16 of its pixels (the white ones too)
        assertEquals(RED, c.cell(3, 3));
        assertEquals(MapColors.CANVAS, c.get(13, 13));
        PaintCanvas.Stroke s = new PaintCanvas.Stroke();
        assertEquals(1, c.fill(3, 3, BLUE, s));
        assertTrue(wholeCell(c, 3, 3, BLUE));
        assertEquals(16, s.size());
        // and the undo puts the old pixels back exactly
        c.undo(s);
        assertEquals(RED, c.get(12, 12));
        assertEquals(MapColors.CANVAS, c.get(13, 13));
    }

    // --- undo -------------------------------------------------------------------------------------

    @Test
    void undoPutsBackEachStrokeNewestFirst() {
        PaintCanvas c = new PaintCanvas();
        byte[] blank = c.pixels();
        UndoStack undo = new UndoStack(20);
        PaintCanvas.Stroke a = new PaintCanvas.Stroke();
        c.line(0, 0, 12, 12, 2, RED, a);
        undo.push(a);
        byte[] afterA = c.pixels();
        PaintCanvas.Stroke b = new PaintCanvas.Stroke();
        c.line(0, 0, 12, 12, 3, BLUE, b);          // over the same cells twice: the first colour is what comes back
        c.line(0, 0, 12, 12, 1, RED, b);
        undo.push(b);
        PaintCanvas.Stroke f = new PaintCanvas.Stroke();
        c.fill(25, 1, BLUE, f);
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
    void theHoverOutlineShowsOnEveryColour() {
        assertEquals(0x202020, MapColors.outline(MapColors.CANVAS), "dark on white");
        assertEquals(0xFFFFFF, MapColors.outline(MapColors.BLACK), "white on black");
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
        p.canvas().line(0, 0, 31, 31, 3, RED, null);
        p.canvas().fill(31, 0, MapColors.id(61, 3), null);
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
