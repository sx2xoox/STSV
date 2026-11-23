package dev.stsv.economy.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** 공정 운행 (README "마을버스"): what counts as a served stop and as a paid ride (ServiceGate). */
class ServiceGateTest {
    private static final ServiceGate.Rules R = new ServiceGate.Rules(100, 0.7, 30, 30);

    @Test
    void busYmlHasTheRules() {
        List<String> problems = new ArrayList<>();
        ServiceGate.Rules r = BusSpec.parse(YamlConfiguration.loadConfiguration(new File("content/economy/bus.yml")), problems).fair();
        assertEquals(new ServiceGate.Rules(100, 0.7, 30, 30), r);
        // no `fair` at all (an older bus.yml): the same defaults; silly values are kept sane
        assertEquals(ServiceGate.Rules.DEFAULT, BusSpec.parse(new YamlConfiguration(), new ArrayList<>()).fair());
        assertEquals(new ServiceGate.Rules(0, 1, 1, 0), new ServiceGate.Rules(-5, 3, 0, -1));
    }

    @Test
    void oneStopOverAndOverCountsOnce() {
        ServiceGate g = new ServiceGate();
        assertTrue(g.visit(1, Double.NaN, R));   // the first stop of all
        for (int i = 0; i < 20; i++) {   // out of the bay, round the block, in again: however far
            g.drove(500);
            assertFalse(g.visit(1, 0, R));
        }
        assertEquals(1, g.last());
    }

    @Test
    void anotherStopCountsOnlyAfterReallyDrivingThere() {
        ServiceGate g = new ServiceGate();
        assertTrue(g.visit(1, Double.NaN, R));
        // the next stop without driving (got into a bus someone else drove there, a jump): not counted
        assertFalse(g.visit(2, 700, R));
        g.drove(300);   // under 70% of the 700 blocks of road between them
        assertFalse(g.visit(2, 700, R));
        g.drove(200);
        assertTrue(g.visit(2, 700, R));
        assertEquals(0, g.driven(), 1e-9);   // the distance starts again
        // two stops a few blocks apart (across the road from each other): the least drive between counted stops
        g.drove(40);
        assertFalse(g.visit(3, 12, R));
        g.drove(60);
        assertTrue(g.visit(3, 12, R));
        // a stop not joined by road to the last (unknown distance): the least drive
        g.drove(100);
        assertTrue(g.visit(9, Double.POSITIVE_INFINITY, R));
    }

    @Test
    void honestShortLinesStillCount() {
        // a line of three stops 260 apart, driven end to end and back (the terminus turned round at): every stop counts
        ServiceGate g = new ServiceGate();
        int[] order = {1, 2, 3, 2, 1, 2, 3};
        int counted = 0;
        for (int i = 0; i < order.length; i++) {
            if (i > 0) g.drove(262);   // a little more than the road: in and out of the bays
            if (g.visit(order[i], 260, R)) counted++;
        }
        assertEquals(order.length, counted);
        // a line of only two stops, there and back
        ServiceGate two = new ServiceGate();
        assertTrue(two.visit(1, Double.NaN, R));
        for (int i = 0; i < 6; i++) {
            two.drove(255);
            assertTrue(two.visit(i % 2 == 0 ? 2 : 1, 250, R));
        }
        // a stop where nobody got on or off is skipped: the next one is measured from the last counted one
        ServiceGate skip = new ServiceGate();
        assertTrue(skip.visit(1, Double.NaN, R));
        skip.drove(520);
        assertTrue(skip.visit(3, 520, R));
    }

    @Test
    void odometerCountsDrivingNotJumps() {
        ServiceGate.Odometer o = new ServiceGate.Odometer();
        assertEquals(0, o.move("w", 0, 0, 1, 30), 1e-9);   // the first sight of it
        assertEquals(0.5, o.move("w", 0.5, 0, 2, 30), 1e-9);   // 10 blocks/s
        assertEquals(0, o.move("w", 0.5, 0, 3, 30), 1e-9);   // standing
        assertEquals(0, o.move("w", 200, 0, 4, 30), 1e-9);   // a jump (teleported, carried off)
        assertEquals(1.5, o.move("w", 201.5, 0, 5, 30), 1e-9);   // as fast as counts
        assertEquals(0, o.move("w", 203.1, 0, 6, 30), 1e-9);   // faster than any bus
        assertEquals(0, o.move("other", 203.1, 0, 7, 30), 1e-9);   // another world
        assertEquals(0, o.move("other", 204, 0, 7, 30), 1e-9);   // the same tick twice
        // unseen for a few ticks (drivers changing seats): what it could have driven in them
        assertEquals(4, o.move("other", 208, 0, 11, 30), 1e-9);
        assertEquals(0, o.move("other", 508, 0, 400, 30), 1e-9);   // long unseen and far away: not driving
        assertEquals(6, o.total(), 1e-9);
    }

    @Test
    void aRideIsPaidAsFarAsItWasDriven() {
        // driven all the way (the bus's path a little longer or shorter than the line's): the ride's road
        assertEquals(700, ServiceGate.paidRoad(700, 712, R), 1e-9);
        assertEquals(700, ServiceGate.paidRoad(700, 640, R), 1e-9);
        // the bus drove far less than the ride with them aboard: only what it drove; nothing: no ride
        assertEquals(200, ServiceGate.paidRoad(700, 200, R), 1e-9);
        assertEquals(0, ServiceGate.paidRoad(700, 0, R), 1e-9);
        assertEquals(0, ServiceGate.paidRoad(700, 20, R), 1e-9);
        // a ride under min-ride (off where they got on: 0) is no ride however far the bus went round
        assertEquals(0, ServiceGate.paidRoad(0, 900, R), 1e-9);
        assertEquals(0, ServiceGate.paidRoad(25, 900, R), 1e-9);
        assertEquals(30, ServiceGate.paidRoad(30, 900, R), 1e-9);
    }

    @Test
    void aBusThatTurnedRoundIsPaidTheShortHop() {
        // at the stop they asked for: the planned ride
        assertEquals(5000, ServiceGate.rodeRoad(5000, 5000, true, false), 1e-9);
        // off early (a drunk wandering off, their stop gone): as far as they rode
        assertEquals(800, ServiceGate.rodeRoad(5000, 800, false, false), 1e-9);
        // the bus turned round after they got on: their stop became the one behind, 260 blocks away, not 5000
        assertEquals(260, ServiceGate.rodeRoad(5000, 260, true, true), 1e-9);
        // the bus went past their stop and let them off at the next one: what they asked for, as before
        assertEquals(700, ServiceGate.rodeRoad(700, 960, true, true), 1e-9);
        assertEquals(960, ServiceGate.rodeRoad(700, 960, false, true), 1e-9);
    }
}
