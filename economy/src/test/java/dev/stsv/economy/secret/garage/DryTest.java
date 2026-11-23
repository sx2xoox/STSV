package dev.stsv.economy.secret.garage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 폐차고의 주인: the dry-ground rule (no garage over the water) on a small made-up land, and the garage's footprint
 * (every block column under its floor and apron, at any angle to the road).
 */
class DryTest {

    /** Flat land standing at y 64; single columns replaced (from y 0 up), air over them. */
    static final class Land implements Dry.Probe {
        final Map<Long, Dry.Cell[]> cols = new HashMap<>();

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        Land set(int x, int z, Dry.Cell[] c) {
            cols.put(key(x, z), c);
            return this;
        }

        /** A river: its bed at 58, water up to 62. */
        Land river(int x, int z) {
            Dry.Cell[] c = new Dry.Cell[63];
            for (int y = 0; y < 63; y++) c[y] = y < 58 ? Dry.Cell.GROUND : Dry.Cell.WET;
            return set(x, z, c);
        }

        @Override
        public Dry.Cell at(int x, int y, int z) {
            Dry.Cell[] c = cols.get(key(x, z));
            if (c == null) return y < 64 ? Dry.Cell.GROUND : Dry.Cell.AIR;
            return y < 0 ? Dry.Cell.GROUND : y < c.length ? c[y] : Dry.Cell.AIR;
        }
    }

    @Test
    void footprintCoversEveryBlockOnce() {
        List<int[]> square = Dry.cells(0.5, 0.5, 0, -3.45, 3.45, -6.95, 1.55);
        assertEquals(7 * 10, square.size());
        Set<Long> seen = new HashSet<>();
        for (int[] c : square) assertTrue(seen.add(Land.key(c[0], c[1])));
        // at an angle to the grid it reaches into more blocks, still each once, the frame's own block among them
        List<int[]> turned = Dry.cells(100.3, -40.7, 37, -3.45, 3.45, -6.95, 1.55);
        Set<Long> t = new HashSet<>();
        for (int[] c : turned) assertTrue(t.add(Land.key(c[0], c[1])));
        assertTrue(turned.size() > 70);
        assertTrue(t.contains(Land.key(100, -41)));
    }

    @Test
    void theFootprintFacesTheRoad() {
        // facing south (+z, the road), the garage lies to the north of its frame
        List<int[]> c = Dry.cells(0.5, 0.5, 0, -3.45, 3.45, -6.95, 1.55);
        assertTrue(c.stream().anyMatch(x -> x[1] == -7));
        assertTrue(c.stream().noneMatch(x -> x[1] > 2));
        // facing west (yaw 90: -x), it lies to the east
        List<int[]> w = Dry.cells(0.5, 0.5, 90, -3.45, 3.45, -6.95, 1.55);
        assertTrue(w.stream().anyMatch(x -> x[0] == 7));
        assertTrue(w.stream().noneMatch(x -> x[0] < -2));
    }

    @Test
    void aGarageOverAStreamIsRefused() {
        Land l = new Land();
        for (int z = -20; z <= 20; z++) l.river(2, z);   // a stream one block wide, under the garage's side wall
        List<int[]> here = Dry.cells(0.5, 0.5, 0, -3.45, 3.45, -6.95, 1.55);
        Dry.Check c = Dry.footprint(l, here, 70, 56);
        assertFalse(c.ok());
        assertTrue(c.wet());
        // moved along so the stream runs beside it
        Dry.Check moved = Dry.footprint(l, Dry.cells(8.5, 0.5, 0, -3.45, 3.45, -6.95, 1.55), 70, 56);
        assertTrue(moved.ok());
        assertEquals(64, moved.max());
        assertEquals(70, moved.ys().length);
    }

    @Test
    void aBridgeHasNoGroundUnderIt() {
        // the road crosses the sea on a bridge: beside it nothing but water down to the floor, or air down to the water
        Land l = new Land();
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) l.river(x, z);
        Dry.Check c = Dry.footprint(l, Dry.cells(0.5, 0.5, 0, -3.45, 3.45, -6.95, 1.55), 70, 56);
        assertTrue(c.wet());
        // and with the window above the water (nothing found at all): not dry either
        Dry.Check air = Dry.footprint(l, Dry.cells(0.5, 0.5, 0, -3.45, 3.45, -6.95, 1.55), 75, 64);
        assertFalse(air.ok());
    }

    @Test
    void iceAndPlantsUnderwater() {
        Dry.Cell[] ice = new Dry.Cell[64];
        for (int y = 0; y < 64; y++) ice[y] = y < 60 ? Dry.Cell.GROUND : y < 63 ? Dry.Cell.WET : Dry.Cell.ICE;
        Land l = new Land().set(0, 0, ice);
        assertEquals(Dry.WET, Dry.ground(l, 0, 0, 70, 56));
        Dry.Cell[] kelp = new Dry.Cell[63];
        for (int y = 0; y < 63; y++) kelp[y] = y < 58 ? Dry.Cell.GROUND : Dry.Cell.WET;
        l.set(1, 0, kelp);
        assertEquals(Dry.WET, Dry.ground(l, 1, 0, 59, 50));   // looked at from among the kelp itself
        assertEquals(64, Dry.ground(l, 2, 0, 70, 56));
    }
}
