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

    // ---- 점검 위치 (docs/plans/편의점_점검.md §2): what the worker sees from behind the counter

    /** The worker's eye behind the counter (a standing player: 1.62 over the floor). */
    private static final double EYE = 1.62;

    @Test
    void thePosKeysLieOnTheCounterEdgeApartAndOutOfTheWay() {
        List<HallLayout.Point> keys = new ArrayList<>(l.changeButtons().values());
        keys.add(l.give());
        keys.add(l.clear2());
        assertEquals(8, keys.size());
        assertEquals(l.give(), l.cardKey(), "[카드] is where [건네기] is: one place to confirm a payment");
        double wx = l.worker().x();
        for (HallLayout.Point k : keys) {
            assertTrue(k.y() < 1.25, "a key in the worker's face: " + k);
            assertTrue(k.x() > wx + 0.3 && k.x() < -2.8, "on the counter's worker edge: " + k);
            assertTrue(k.z() > 6.4 && k.z() < 7.9, "between the printer and the scanned things: " + k);
            // not between the worker's eye and the POS text, nor the customer's face (q0, 1.6 up): under both sight lines
            double t = (k.x() - wx) / (StoreLayout.POS_X - wx);
            assertTrue(k.y() + 0.07 < EYE + (StoreLayout.POS_Y - EYE) * t, "hides the POS screen: " + k);
            double tf = (k.x() - wx) / (l.queue().getFirst().x() - wx);
            assertTrue(k.y() + 0.07 < EYE + (1.6 - EYE) * tf, "hides the customer: " + k);
        }
        // apart: columns at least 0.34, and no two click boxes share any room
        double[] h = StoreLayout.keyBox();
        for (int i = 0; i < keys.size(); i++) for (int j = i + 1; j < keys.size(); j++) {
            HallLayout.Point a = keys.get(i), b = keys.get(j);
            double dz = Math.abs(a.z() - b.z()), dx = Math.abs(a.x() - b.x()), dy = Math.abs(a.y() - b.y());
            if (dz > 1e-9) assertTrue(dz >= 0.34 - 1e-9, "columns closer than 0.34: " + a + " " + b);
            assertTrue(dz >= 2 * h[0] || dy >= 2 * h[1] || dx >= 2 * h[2], "click boxes overlap: " + a + " " + b);
        }
        assertTrue(12 / 16.0 * StoreLayout.KEY_SCALE < StoreLayout.KEY_GAP, "a key is narrower than its column");
        // the 5만 · 1천 keys are no more than 60 degrees off straight ahead from where the worker stands
        for (HallLayout.Point k : keys) {
            double off = Math.toDegrees(Math.atan2(Math.abs(k.z() - l.worker().z()), k.x() - wx));
            assertTrue(off <= 60, "too far to the side (" + off + "): " + k);
        }
    }

    @Test
    void theTotalIsOnThePosMonitorNotInTheAir() {
        HallLayout.Point s = l.screen();
        assertEquals(270, s.yaw(), 1e-6, "it faces the worker");
        assertTrue(s.y() > 1.2 && s.y() < 1.6, "on the monitor (the counter's top is " + StoreLayout.TOP + "), not over heads: " + s.y());
        assertTrue(s.x() > -2.9 && s.x() < -2.5 && s.z() > 6.94 && s.z() < 7.44, "inside the POS's footprint");
        // three lines of it fit the text area
        assertTrue(3 * Bubbles.LINE * StoreLayout.POS_TEXT <= StoreLayout.POS_TALL + 1e-9);
        // looking at it the worker looks down, not up
        assertTrue(s.y() < EYE);
    }

    @Test
    void theKitchenThingsFaceTheWorkersLane() {
        for (String role : List.of("microwave", "waste", "kettle")) assertEquals(270, l.piece(role).yaw(), 1e-6, role + ": its front to x -4");
        assertEquals(90, l.piece("counter").yaw(), 1e-6, "the counter's front stays the customers'");
        assertTrue(StoreLayout.OVEN_X > -3 && StoreLayout.OVEN_X < -2 && StoreLayout.OVEN_Z > 9 && StoreLayout.OVEN_Z < 10, "the food is inside the microwave's block");
    }

    @Test
    void thingsOnTheCounterLieOnClearTop() {
        // (the counter's model in the store's frame: x = -2 - mz / 16, z = 8 - mx / 16)
        HallLayout.Point card = l.card();
        boolean rack = card.z() >= 6.03 && card.z() <= 6.47 && card.x() <= -2.06 && card.x() >= -2.44;
        assertFalse(rack, "the card lies in the 숙취해소제 rack");
        assertTrue(card.z() > 6.81 + 0.05 && card.z() < 7.0 - 0.05, "between the terminal (z .. 6.81) and the cash tray (7.0 ..)");
        assertTrue(card.x() < -2.0 && card.x() > -3.0);
        // scanned things: apart by more than a 컵라면 is wide (0.23 at 0.75), on the counter, clear of the keys
        for (int i = 1; i < l.scanned().size(); i++) assertTrue(l.scanned().get(i).z() - l.scanned().get(i - 1).z() >= 0.23 - 1e-9);
        assertTrue(l.scanned().getLast().z() <= 9.0 - 0.05, "off the counter's end");
        assertTrue(l.scanned().getFirst().z() - 0.115 > l.give().z() + StoreLayout.keyBox()[0], "under the keys");
        // the cash tray's anchor is in its dish; the ID card and a visit's thing on the top
        assertTrue(StoreLayout.TRAY_X < -2.14 && StoreLayout.TRAY_X > -2.39 && StoreLayout.TRAY_Z > 7.03 && StoreLayout.TRAY_Z < 7.35);
        assertTrue(StoreLayout.ID_X > -3.0 && StoreLayout.ID_X < -2.75, "the ID on the worker's edge");
        assertTrue(StoreLayout.MONEY < 0.75, "money smaller than the goods");
    }

    @Test
    void aCupLeftOnTheEatBarStandsOnItsTop() {
        // the bar's top: 14 px up, z 4.0 .. 4.25 (the model's z 12 .. 16 in a piece at z 4, its back to the window)
        assertEquals(0.875, StoreLayout.CUP_Y, 1e-9);
        assertTrue(StoreLayout.CUP_Z > 4.0 && StoreLayout.CUP_Z < 4.25);
        assertTrue(StoreLayout.CUP_SCALE <= 0.5, "no bigger than the 컵라면 on the counter");
        for (int seat = 0; seat < 3; seat++) {
            double[] c = StoreModule.cupSpot(seat);
            assertTrue(c[0] >= l.piece("eatbar").x0() && c[0] <= l.piece("eatbar").x1() + 1);
            assertEquals(l.nodes().get(StoreLayout.EAT.get(seat)).x(), c[0], 1e-9, "in front of its seat");
        }
        assertTrue(StoreLayout.STOOL_Z < l.nodes().get("eat_0").z() && StoreLayout.STOOL_Z > 4.25, "they sit at the bar, in front of where they walked to");
    }

    @Test
    void theOneWaitingForTheMicrowaveStandsOffTheLineAndNobodyWalksThroughThem() {
        StoreLayout.Node w = l.nodes().get(StoreLayout.WARM);
        assertNotNull(w);
        for (StoreLayout.Node q : l.queue()) assertTrue(Math.hypot(q.x() - w.x(), q.z() - w.z()) >= 0.9, "in the line: " + q.id());
        assertEquals(1, l.edges().get(StoreLayout.WARM).size(), "a dead end");
        for (String to : List.of("fl", "bl", "snack_a", "door", "eat_0")) {
            for (StoreLayout.Node n : StoreLayout.path(l, "ml", to)) assertFalse(n.id().equals(StoreLayout.WARM), "ml → " + to);
        }
        assertFalse(StoreLayout.path(l, StoreLayout.WARM, "out").isEmpty());
    }

    @Test
    void customersComeAlongThePavementFromItsEnds() {
        for (String end : List.of(StoreLayout.STREET_L, StoreLayout.STREET_R)) {
            StoreLayout.Node n = l.nodes().get(end);
            assertTrue(Math.abs(n.x()) > StoreLayout.HALF && Math.abs(n.x()) <= StoreLayout.LOT && n.z() < StoreLayout.FRONT, end + " on the pavement, past the glass front");
            List<StoreLayout.Node> in = StoreLayout.path(l, end, "fridge_a");
            assertEquals("out", in.get(in.size() - 5 >= 0 && in.contains(l.nodes().get("out")) ? in.indexOf(l.nodes().get("out")) : 0).id(), "past the door's front");
            assertTrue(in.contains(l.nodes().get("door")));
        }
    }

    @Test
    void theStockroomDoorsLeafIsOnTheInsideAndItsLabelOverIt() {
        int doors = 0;
        for (HallLayout.Block b : l.blocks()) if (b.data().contains("iron_door")) {
            doors++;
            assertTrue(b.data().contains("facing=south"), "its leaf on the wall's inner face: " + b.data());
        }
        assertEquals(2, doors);
        HallLayout.Label staff = null;
        for (HallLayout.Label x : l.labels()) if (x.text().contains("STAFF ONLY")) staff = x;
        assertNotNull(staff);
        assertTrue(staff.y() >= 2.0, "over the door (the boxes pile up in front of it)");
    }

    @Test
    void theFigureOutsideIsSeenFromTheTill() {
        // from the worker's eye to the figure's head (1.6 up): the kettle (x -3 .. -2, z 5 .. 6, 1.8 tall) is not in between
        double ex = l.worker().x(), ez = l.worker().z(), fx = StoreLayout.FIGURE_X, fz = StoreLayout.FIGURE_Z;
        for (int i = 0; i <= 200; i++) {
            double x = ex + (fx - ex) * i / 200, z = ez + (fz - ez) * i / 200;
            assertFalse(x >= -3 && x <= -2 && z >= 5 && z <= 6, "behind the kettle");
        }
        assertTrue(fz < StoreLayout.FRONT && fx >= l.piece("parasol_0").x0() && fx <= l.piece("parasol_0").x1() + 1, "on the pavement at the left parasol table");
        assertTrue(Math.abs(StoreLayout.PARASOL_X - fx) < 1e-9, "what a visit leaves is on that same table");
    }
}
