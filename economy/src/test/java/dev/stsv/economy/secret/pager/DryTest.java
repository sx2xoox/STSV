package dev.stsv.economy.secret.pager;

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
 * 울리는 삐삐: the dry-ground rule (no 수상가옥) on a small made-up land — the sea, a lake's ice, kelp on the sea floor,
 * a waterlogged block, lava, a tree's leaves — and the house front's footprint, every column of it.
 */
class DryTest {

    /** Flat land: ground up to y 63 (standing at 64), air above; columns can be changed one by one. */
    static final class Land implements Dry.Probe {
        final Map<Long, Dry.Cell[]> cols = new HashMap<>();
        final int ground = 64;

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        /** A column: from y 0 up, one cell each; air above. */
        Land set(int x, int z, Dry.Cell... upFromZero) {
            cols.put(key(x, z), upFromZero);
            return this;
        }

        /** Sea: the floor at y 55, water up to 62 (the surface), plants on the floor if asked. */
        Land sea(int x, int z, Dry.Cell onFloor) {
            Dry.Cell[] c = new Dry.Cell[63];
            for (int y = 0; y < 63; y++) c[y] = y < 55 ? Dry.Cell.GROUND : Dry.Cell.WET;
            if (onFloor != null) c[55] = onFloor;
            return set(x, z, c);
        }

        Land column(int x, int z, int top, Dry.Cell topCell) {
            Dry.Cell[] c = new Dry.Cell[top + 1];
            for (int y = 0; y < top; y++) c[y] = Dry.Cell.GROUND;
            c[top] = topCell;
            return set(x, z, c);
        }

        @Override
        public Dry.Cell at(int x, int y, int z) {
            Dry.Cell[] c = cols.get(key(x, z));
            if (c == null) return y < ground ? Dry.Cell.GROUND : Dry.Cell.AIR;
            if (y < 0) return Dry.Cell.GROUND;
            return y < c.length ? c[y] : Dry.Cell.AIR;
        }
    }

    @Test
    void plainGroundIsDry() {
        Land l = new Land();
        assertEquals(64, Dry.ground(l, 0, 0, 72, 56));
    }

    @Test
    void theSeaItsFloorAndItsPlantsNeverCount() {
        Land l = new Land().sea(1, 0, null).sea(2, 0, Dry.Cell.WET);
        assertEquals(Dry.WET, Dry.ground(l, 1, 0, 72, 48));   // water first, the floor under it does not count
        assertEquals(Dry.WET, Dry.ground(l, 2, 0, 72, 48));   // (kelp, seagrass: wet)
        // even looked at from under the surface (a window that starts on the floor's plants)
        assertEquals(Dry.WET, Dry.ground(l, 2, 0, 55, 40));
        // the floor with only one block of water over it: still wet
        l.set(3, 0, cells(60, Dry.Cell.WET));
        assertEquals(Dry.WET, Dry.ground(l, 3, 0, 61, 50));
    }

    @Test
    void iceLavaAndWaterloggedAreNotGround() {
        Land l = new Land().column(0, 0, 62, Dry.Cell.ICE).column(1, 0, 62, Dry.Cell.WET).column(2, 0, 63, Dry.Cell.OTHER);
        assertEquals(Dry.WET, Dry.ground(l, 0, 0, 72, 50));   // a frozen lake
        assertEquals(Dry.WET, Dry.ground(l, 1, 0, 72, 50));   // lava, or a waterlogged slab
        assertEquals(Dry.NONE, Dry.ground(l, 2, 0, 72, 50));  // a slab on the ground: nothing to stand on under it
    }

    @Test
    void threeOpenBlocksOverTheGround() {
        Land l = new Land();
        Dry.Cell[] c = cells(64, Dry.Cell.AIR);
        c = java.util.Arrays.copyOf(c, 67);
        c[64] = Dry.Cell.AIR;
        c[65] = Dry.Cell.AIR;
        c[66] = Dry.Cell.WET;   // water hanging two blocks up
        l.set(0, 0, c);
        assertEquals(Dry.WET, Dry.ground(l, 0, 0, 65, 50));
        Dry.Cell[] low = java.util.Arrays.copyOf(cells(64, Dry.Cell.AIR), 66);
        low[64] = Dry.Cell.AIR;
        low[65] = Dry.Cell.GROUND;   // a roof two blocks up
        l.set(1, 0, low);
        assertEquals(Dry.NONE, Dry.ground(l, 1, 0, 64, 50));
    }

    @Test
    void underATreeIsStillTheGround() {
        Dry.Cell[] c = new Dry.Cell[72];
        for (int y = 0; y < 72; y++) c[y] = y < 64 ? Dry.Cell.GROUND : y >= 68 ? Dry.Cell.OTHER : Dry.Cell.AIR;
        Land l = new Land().set(0, 0, c);
        assertEquals(64, Dry.ground(l, 0, 0, 72, 56));
    }

    @Test
    void houseFootprintIsEveryColumnOnce() {
        for (float yaw : new float[] {0, 90, -90, 180}) {
            List<int[]> cols = Dry.house(10, -20, yaw);
            assertEquals(54, cols.size(), "yaw " + yaw);
            Set<Long> seen = new HashSet<>();
            for (int[] c : cols) assertTrue(seen.add(Land.key(c[0], c[1])));
            assertEquals(10, cols.getFirst()[0]);
            assertEquals(-20, cols.getFirst()[1]);
        }
    }

    @Test
    void aHouseHalfOverWaterIsAHouseOverWater() {
        Land l = new Land();
        // the sea begins three blocks to the gate's right: the wall's end and the mailbox's side stand over it
        for (int x = 3; x <= 12; x++) for (int z = -10; z <= 10; z++) l.sea(x, z, null);
        Dry.Check c = Dry.footprint(l, Dry.house(0, 0, 0), 72, 56);
        assertFalse(c.ok());
        assertTrue(c.wet());
        assertTrue(Dry.why(c).contains("물 위"));
        // a few blocks inland it stands
        Dry.Check inland = Dry.footprint(l, Dry.house(-6, 0, 0), 72, 56);
        assertTrue(inland.ok());
        assertEquals(64, inland.min());
        assertEquals(64, inland.max());
        // one column of it in a frozen pond is enough
        Land pond = new Land().column(-2, -2, 63, Dry.Cell.ICE);
        assertFalse(Dry.footprint(pond, Dry.house(0, 0, 0), 72, 56).ok());
        // a hole too deep to see the bottom of
        Land hole = new Land().set(1, 1, cells(40, Dry.Cell.AIR));
        Dry.Check h = Dry.footprint(hole, Dry.house(0, 0, 0), 72, 56);
        assertTrue(h.missing());
        assertFalse(h.wet());
    }

    @Test
    void theHouseLooksAtItsWholeFootprint() {
        // only the back corner (behind the wall, at the far end) is in the water: still refused
        Land l = new Land().sea(-4, -3, null);
        assertFalse(Dry.footprint(l, Dry.house(0, 0, 0), 72, 56).ok());
        // turned round, the house stands on the other side of its gate and that corner is not under it
        assertTrue(Dry.footprint(l, Dry.house(0, 0, 180), 72, 56).ok());
    }

    /** Ground up to (not including) y, then one cell at y. */
    static Dry.Cell[] cells(int y, Dry.Cell at) {
        Dry.Cell[] c = new Dry.Cell[y + 1];
        for (int i = 0; i < y; i++) c[i] = Dry.Cell.GROUND;
        c[y] = at;
        return c;
    }
}
