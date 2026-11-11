package dev.stsv.economy.dock;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dock's layout, counter and zones worked out once (Dock#shape: asked on every block right-click and every bed click
 * near a dock), the same as working them out every time.
 */
class DockShapeTest {

    @Test
    void theLayoutIsMadeOnce() {
        Dock d = new Dock("서해", DockLayout.Kind.PORT, "build", 100, 64, -200, 1, "", 0);
        DockLayout.Layout l = d.layout();
        assertSame(l, d.layout(), "the same object every call");
        assertSame(l, d.withPrice(110).layout(), "the same dock with another price");
        assertSame(l, new Dock("서해", DockLayout.Kind.PORT, "other", 5, 70, 9, 3, "1,2", 99).layout(), "the layout depends on kind and name only");
        assertNotSame(l, new Dock("동해", DockLayout.Kind.PORT, "build", 100, 64, -200, 1, "", 0).layout());
        assertNotSame(l, new Dock("서해", DockLayout.Kind.LAND, "build", 100, 64, -200, 1, "", 0).layout());
        assertEquals(DockLayout.of(DockLayout.Kind.PORT, "서해").blocks(), l.blocks(), "as made fresh");
        assertEquals(DockLayout.of(DockLayout.Kind.PORT, "서해").zones(), l.zones());
    }

    @Test
    void counterAndZonesAsWorkedOutEveryTime() {
        Random r = new Random(3);
        for (DockLayout.Kind kind : DockLayout.Kind.values()) {
            for (int facing = 0; facing < 4; facing++) {
                Dock d = new Dock("t" + facing, kind, "w", r.nextInt(2000) - 1000, 60 + r.nextInt(20), r.nextInt(2000) - 1000, facing, "", 0);
                DockLayout.Layout fresh = DockLayout.of(kind, d.name());
                DockLayout.Block c = fresh.counter();
                int[] want = d.block(c.x(), c.y(), c.z());
                assertArrayEquals(want, d.counter());
                d.counter()[0] = 12345;   // a copy: the remembered one stays
                assertArrayEquals(want, d.counter());
                assertTrue(d.isCounter("w", want[0], want[1], want[2]));
                assertFalse(d.isCounter("other", want[0], want[1], want[2]));
                assertFalse(d.isCounter("w", want[0] + 1, want[1], want[2]));
                assertFalse(d.isCounter("w", want[0], want[1] - 1, want[2]));
                // zones: every point decided as the layout's boxes turned into the world decide it
                int[] b = d.box(fresh.bounds());
                for (int i = 0; i < 4000; i++) {
                    double x = b[0] - 3 + r.nextDouble() * (b[3] - b[0] + 7), y = b[1] - 3 + r.nextDouble() * (b[4] - b[1] + 7);
                    double z = b[2] - 3 + r.nextDouble() * (b[5] - b[2] + 7);
                    boolean in = false;
                    for (DockLayout.Box zb : fresh.zones()) {
                        int[] w = d.box(zb);
                        in |= x >= w[0] && x < w[3] + 1 && y >= w[1] && y < w[4] + 1 && z >= w[2] && z < w[5] + 1;
                    }
                    assertEquals(in, d.inZone("w", x, y, z), kind + " " + facing + " at " + x + ", " + y + ", " + z);
                    assertFalse(d.inZone("elsewhere", x, y, z));
                }
                double[] mid = kind == DockLayout.Kind.TERMINAL ? DockModule.bay(d) : d.centre();
                assertTrue(d.inZone("w", mid[0], mid[1] + 1, mid[2]), "the zone's (bay's) middle");
            }
        }
    }
}
