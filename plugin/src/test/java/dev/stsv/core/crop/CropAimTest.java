package dev.stsv.core.crop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Aiming at a crop drawn as a block: its old click box, before whatever the game says was clicked. */
class CropAimTest {

    private static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    @Test
    void rayAndBox() {
        double[] box = CropAim.box(0, 64, 0, 0.9);
        assertArrayEquals(new double[]{0.05, 64, 0.05, 0.95, 64.9, 0.95}, box, 1e-9);
        // straight at it from 2 blocks south
        assertEquals(2 - 0.95 + 0.5, CropAim.hit(new double[]{0.5, 64.5, 2.5}, unit(0, 0, -1), box, 5), 1e-9);
        // over the top of a short plant: missed
        assertTrue(Double.isNaN(CropAim.hit(new double[]{0.5, 65.2, 2.5}, unit(0, 0, -1), box, 5)));
        // too far
        assertTrue(Double.isNaN(CropAim.hit(new double[]{0.5, 64.5, 9.5}, unit(0, 0, -1), box, 4.5)));
        // standing in it
        assertEquals(0, CropAim.hit(new double[]{0.5, 64.5, 0.5}, unit(0, 0, -1), box, 5));
    }

    /**
     * A small sprout (0.3 tall) on farmland, looked at from where a player stands: the game's ray hits the farmland
     * under it (or the thin tripwire outline), the sprout's box comes first - so it is the sprout that was clicked.
     */
    @Test
    void theSproutComesBeforeTheFarmlandUnderIt() {
        double[] eye = {0.5, 65.62, 2.3};       // feet at y 64, two blocks south of the crop at (0, 64, 0)
        double[] look = unit(0, 64.15 - 65.62, 0.5 - 2.3);   // at the middle of the sprout
        double crop = CropAim.hit(eye, look, CropAim.box(0, 64, 0, 0.3), 4.5);
        // where the ray would meet the farmland's top (y 63.9375) behind it
        double farmland = CropAim.hit(eye, look, new double[]{0, 63, 0, 1, 63.9375, 1}, 4.5);
        assertTrue(!Double.isNaN(crop) && !Double.isNaN(farmland) && crop < farmland, crop + " < " + farmland);
        // a click on the farmland next to it (aimed past the sprout) is the farmland's
        double[] past = unit(1.5 - 0.5, 63.9375 - 65.62, 0.5 - 2.3);
        assertTrue(Double.isNaN(CropAim.hit(eye, past, CropAim.box(0, 64, 0, 0.3), 4.5)));
    }

    @Test
    void cellsAlongTheRay() {
        List<int[]> c = CropAim.cells(0.5, 0.5, 0.5, 1, 0, 0, 3.2);
        assertEquals(4, c.size());
        assertArrayEquals(new int[]{0, 0, 0}, c.get(0));
        assertArrayEquals(new int[]{3, 0, 0}, c.get(3));
        // diagonal down: every block the ray passes through, connected face to face
        double[] d = unit(1, -1, 0.3);
        List<int[]> diag = CropAim.cells(0.2, 65.6, 0.1, d[0], d[1], d[2], 4.5);
        for (int i = 1; i < diag.size(); i++) {
            int[] a = diag.get(i - 1), b = diag.get(i);
            assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]), "neighbours");
        }
        assertTrue(diag.size() >= 6);
        // negative coordinates
        assertArrayEquals(new int[]{-1, 64, -3}, CropAim.cells(-0.5, 64.5, -2.5, 0, 0, -1, 0.2).get(0));
    }

    @Test
    void tallCropsAreReachedAboveTheirBlock() {
        // corn, 1.8 tall at (0, 64, 0): a ray at head height through the block above hits its box
        double t = CropAim.hit(new double[]{0.5, 65.6, 3}, unit(0, 0, -1), CropAim.box(0, 64, 0, 1.8), 4.5);
        assertEquals(3 - 0.95, t, 1e-9);
        assertTrue(CropAim.REACH_UP >= 1);
    }
}
