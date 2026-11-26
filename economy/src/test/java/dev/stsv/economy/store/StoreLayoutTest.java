package dev.stsv.economy.store;

import dev.stsv.economy.hwatu.hall.HallLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The GS26 store's plan: the walk graph, the furniture's boxes, fitting a piece, the stock and deliveries. */
class StoreLayoutTest {

    private final StoreLayout.Layout l = StoreLayout.of();

    @Test
    void everyNodeIsReachableFromTheDoorAndTheWalkStaysOffTheFurniture() {
        for (String id : l.nodes().keySet()) assertFalse(StoreLayout.path(l, "out", id).isEmpty(), id);
        for (String place : List.of("snack", "noodle", "daily", "meal", "fridge", "freezer")) assertFalse(l.spots().get(place).isEmpty(), place);
        List<StoreLayout.Node> p = StoreLayout.path(l, "out", "fridge_a");
        assertEquals("out", p.getFirst().id());
        assertEquals("fridge_a", p.getLast().id());
        // no walk leg crosses a furniture block (sampled along every edge, and from the line's entry to every place in line)
        Set<Long> solid = new HashSet<>();
        for (StoreLayout.Piece piece : l.pieces()) for (int x = piece.x0(); x <= piece.x1(); x++) for (int z = piece.z0(); z <= piece.z1(); z++) {
            if (piece.y() == 0) solid.add(key(x, z));
        }
        for (HallLayout.Block b : l.blocks()) if (b.y() == 0 && !b.data().contains("air") && !b.data().contains("bell")) solid.add(key(b.x(), b.z()));
        List<StoreLayout.Node[]> legs = new ArrayList<>();
        l.edges().forEach((a, ns) -> ns.forEach(n -> legs.add(new StoreLayout.Node[]{l.nodes().get(a), l.nodes().get(n)})));
        for (StoreLayout.Node q : l.queue()) legs.add(new StoreLayout.Node[]{l.nodes().get(StoreLayout.QUEUE_FROM), q});
        legs.add(new StoreLayout.Node[]{l.queue().getFirst(), l.nodes().get(StoreLayout.COUNTER_TO)});
        for (StoreLayout.Node[] leg : legs) {
            for (int i = 0; i <= 40; i++) {
                double x = leg[0].x() + (leg[1].x() - leg[0].x()) * i / 40, z = leg[0].z() + (leg[1].z() - leg[0].z()) * i / 40;
                assertFalse(solid.contains(key((int) Math.floor(x), (int) Math.floor(z))), leg[0].id() + " → " + leg[1].id() + " at " + x + ", " + z);
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @Test
    void theStoreHasEverythingThePlanNames() {
        for (String role : List.of("counter", "cig", "kettle", "microwave", "hanger", "waste", "mop", "shelf_snack", "shelf_noodle", "shelf_daily",
                "shelf_meal", "fridge_0", "fridge_1", "fridge_2", "freezer", "eatbar", "parasol_0", "parasol_1", "sign")) assertNotNull(l.piece(role), role);
        assertEquals("economy:store_shelf_snack_full", l.piece("shelf_snack").item());
        assertEquals("economy:store_sign", l.piece("sign").item(), "the lit GS26 sign over the glass front");
        assertEquals(StoreLayout.ROOF, l.piece("sign").y());
        assertEquals(5, l.slots().size(), "at most five things on the counter");
        assertEquals(6, l.changeButtons().size());
        // no two pieces share a block
        Set<String> taken = new HashSet<>();
        for (StoreLayout.Piece p : l.pieces()) for (int x = p.x0(); x <= p.x1(); x++) for (int z = p.z0(); z <= p.z1(); z++) {
            assertTrue(taken.add(x + "," + p.y() + "," + z), p.role() + " overlaps at " + x + "," + z);
        }
        // the door is open, the glass front shut
        Set<String> air = new HashSet<>();
        for (HallLayout.Block b : l.blocks()) if (b.z() == StoreLayout.FRONT && b.y() < 2 && b.data().endsWith("air")) air.add(b.x() + "," + b.y());
        assertEquals(Set.of("4,0", "4,1", "5,0", "5,1"), air);
    }

    @Test
    void aPieceIsTurnedAndMovedToFillItsBox() {
        // a 3×1 counter (cells along its x), meant to run along z facing +x
        List<int[]> counter = List.of(new int[]{-1, 0, 0}, new int[]{0, 0, 0}, new int[]{1, 0, 0});
        StoreLayout.Fit f = StoreLayout.fit(counter, l.piece("counter"));
        assertTrue(f.exact());
        assertEquals(90, f.yaw(), 1e-6);
        assertEquals(-3, f.x());
        assertEquals(7, f.z(), "anchored in the middle of z 6..8");
        // the same footprint from its end (cells 0..2): anchored at an end
        List<int[]> fromEnd = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{2, 0, 0});
        StoreLayout.Fit e = StoreLayout.fit(fromEnd, l.piece("counter"));
        assertTrue(e.exact());
        Set<Integer> zs = new HashSet<>();
        for (int[] c : fromEnd) zs.add(e.z() + StoreLayout.offset(c, e.yaw())[1]);
        assertEquals(Set.of(6, 7, 8), zs);
        // a shelf drawn 3 deep (along its z) runs along z at its own yaw; one drawn 3 wide is turned a quarter
        List<int[]> deep = List.of(new int[]{0, 0, -1}, new int[]{0, 0, 0}, new int[]{0, 0, 1});
        assertEquals(0, StoreLayout.fit(deep, l.piece("shelf_snack")).yaw(), 1e-6);
        StoreLayout.Fit wide = StoreLayout.fit(counter, l.piece("shelf_snack"));
        assertTrue(wide.exact());
        assertEquals(90, wide.yaw() % 180, 1e-6);
        // the front cell (-z) faces the way the yaw says: local yaw 0 = +z (forward)
        assertEquals(1, StoreLayout.offset(new int[]{0, 0, -1}, 0)[1]);
        assertEquals(1, StoreLayout.offset(new int[]{0, 0, -1}, 90)[0], "yaw 90 = +x (right)");
        // a piece that fits no turn is centred and said so
        List<int[]> big = List.of(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{0, 0, 1}, new int[]{1, 0, 1});
        assertFalse(StoreLayout.fit(big, l.piece("counter")).exact());
    }

    @Test
    void aSiteTurnsWithTheAdmin() {
        StoreLayout.Site s = new StoreLayout.Site("w", 100, 64, 200, 0);
        assertEquals(StoreLayout.Site.parse(s.text()), s);
        double[] m = s.middle();
        assertTrue(s.inside("w", m[0], 64.5, m[1]));
        assertFalse(s.inside("w", 100.5, 64.5, 200.5), "the pavement is outside");
        assertFalse(s.inside("other", m[0], 64.5, m[1]));
        assertFalse(s.chunks().isEmpty());
        StoreLayout.Site east = new StoreLayout.Site("w", 0, 0, 0, 3);
        assertEquals(270, east.yaw(0), 1e-6);
    }

    @Test
    void shelvesRunOutAndBoxesFillThem() {
        StoreSpec spec = StoreSpecTest.spec();
        Chores c = new Chores(spec);
        int cap = spec.shelves().get("noodle").capacity();
        assertEquals("full", c.state(spec, "noodle"));
        for (int i = 0; i < cap; i++) assertTrue(c.take("noodle"));
        assertFalse(c.take("noodle"));
        assertEquals("empty", c.state(spec, "noodle"));
        assertTrue(c.take("freezer"), "the freezer never runs out");
        assertEquals(List.of("noodle"), c.empty());
        // the truck: the first look only remembers the slot; the next slot brings a box for every shelf not full
        assertEquals(List.of(), c.delivery(spec, 10));
        assertEquals(List.of(), c.delivery(spec, 10));
        assertEquals(List.of("noodle"), c.delivery(spec, 11));
        assertEquals(List.of(), c.deliver(spec), "a box already waits");
        assertFalse(c.restock(spec, "noodle"), "nothing carried");
        assertTrue(c.pick("noodle"));
        assertFalse(c.pick("noodle"));
        assertFalse(c.restock(spec, "snack"), "the wrong shelf");
        assertTrue(c.restock(spec, "noodle"));
        assertEquals(cap, c.stock("noodle"));
        // a fridge takes the drink box
        for (int i = 0; i < 10; i++) c.take("fridge");
        assertEquals("half", c.state(spec, "fridge"));
        assertEquals(List.of("drink"), c.deliver(spec));
        c.pick("drink");
        c.putDown();
        assertTrue(c.boxes().contains("drink"), "put back down at clock-out");
    }

    @Test
    void deliveriesComeTwiceAGameDay() {
        List<Integer> hours = List.of(7, 19);
        // world time 0 = 06:00
        assertEquals(Chores.slot(0, hours), Chores.slot(500, hours), "06:00 .. 06:30: still last evening's");
        assertEquals(Chores.slot(0, hours) + 1, Chores.slot(1000, hours), "07:00: the morning truck");
        assertEquals(Chores.slot(1000, hours) + 1, Chores.slot(13000, hours), "19:00: the evening truck");
        assertEquals(Chores.slot(13000, hours), Chores.slot(23999, hours), "05:59 next morning: still the evening's");
        assertEquals(Chores.slot(13000, hours) + 1, Chores.slot(25000, hours));
        assertEquals(7, Chores.hour(1000));
        assertEquals(0, Chores.hour(18000));
    }

    @Test
    void theMicrowaveDingsThenBurns() {
        Chores c = new Chores(StoreSpecTest.spec());
        assertFalse(c.takeOut());
        assertTrue(c.startOven(1));
        assertFalse(c.startOven(1));
        int ding = -1;
        for (int i = 0; i < 40; i++) if (c.tickOven(1) == Chores.OvenEvent.DING) ding = i;
        assertEquals(19, ding, "20 ticks = 1 s");
        assertEquals(Chores.Oven.IDLE, c.oven(), "left 1 s after the ding: burnt");
        c.startOven(1);
        for (int i = 0; i < 20; i++) c.tickOven(5);
        assertEquals(Chores.Oven.DONE, c.oven());
        assertTrue(c.takeOut());
        assertEquals(Chores.Oven.IDLE, c.oven());
    }
}
