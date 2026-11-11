package dev.stsv.core.vehicle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a big bed costs per click and per chunk load: its weight remembered slot by slot ({@link WeightCache}, exactly the
 * full sum), its saves put off and made once ({@link CargoSave}), the loads near a dock found before they are copied
 * ({@link VehicleModule#near}), a settled boat felt only now and then ({@link BoatMotion#steps}).
 */
class CargoCostTest {

    /** A stack as the bed holds it: never changed in place (a change puts a new one). */
    record Stack(String id, int amount) {}

    static final ToDoubleFunction<Stack> WEIGH = s -> switch (s.id()) {
        case "kimchi" -> 0.1 * s.amount();
        case "ruby_ring" -> 2.5 * s.amount();
        case "stone" -> s.amount() / 64.0;
        default -> 0.3 * s.amount();
    };

    static Stack random(Random r) {
        String[] ids = {"kimchi", "ruby_ring", "stone", "steel"};
        return new Stack(ids[r.nextInt(ids.length)], 1 + r.nextInt(64));
    }

    @Test
    void cachedWeightIsExactlyTheFullSumAsTheBedChanges() {
        Random r = new Random(11);
        for (int round = 0; round < 40; round++) {
            Stack[] bed = new Stack[r.nextInt(3) == 0 ? 720 : 9 + r.nextInt(200)];
            for (int i = 0; i < bed.length; i++) if (r.nextBoolean()) bed[i] = random(r);
            WeightCache<Stack> cache = new WeightCache<>();
            long epoch = 0;
            for (int click = 0; click < 300; click++) {
                switch (r.nextInt(6)) {
                    case 0 -> bed[r.nextInt(bed.length)] = random(r);                       // put in
                    case 1 -> bed[r.nextInt(bed.length)] = null;                            // taken out
                    case 2 -> {                                                              // moved
                        int a = r.nextInt(bed.length), b = r.nextInt(bed.length);
                        Stack t = bed[a];
                        bed[a] = bed[b];
                        bed[b] = t;
                    }
                    case 3 -> {                                                              // split
                        int a = r.nextInt(bed.length);
                        if (bed[a] != null && bed[a].amount() > 1) bed[a] = new Stack(bed[a].id(), bed[a].amount() / 2);
                    }
                    case 4 -> {                                                              // the bed resized (a reload)
                        if (r.nextInt(10) == 0) bed = java.util.Arrays.copyOf(bed, bed.length + 45);
                    }
                    default -> { }
                }
                assertEquals(CargoWeights.total(bed, WEIGH), cache.total(bed, WEIGH, epoch), "round " + round + " click " + click);
                int i = r.nextInt(bed.length);
                assertEquals(bed[i] == null ? 0 : WEIGH.applyAsDouble(bed[i]), cache.weight(bed, i, WEIGH, epoch));
            }
        }
    }

    @Test
    void onlyChangedSlotsAreWeighedAgain() {
        AtomicInteger asked = new AtomicInteger();
        ToDoubleFunction<Stack> counting = s -> {
            asked.incrementAndGet();
            return WEIGH.applyAsDouble(s);
        };
        Stack[] bed = new Stack[720];
        Random r = new Random(5);
        for (int i = 0; i < bed.length; i++) bed[i] = random(r);
        WeightCache<Stack> cache = new WeightCache<>();
        cache.total(bed, counting, 0);
        assertEquals(720, asked.get());
        cache.total(bed, counting, 0);
        assertEquals(720, asked.get(), "nothing changed: nothing weighed");
        bed[3] = new Stack("kimchi", 5);
        bed[700] = null;
        assertEquals(CargoWeights.total(bed, WEIGH), cache.total(bed, counting, 0));
        assertEquals(721, asked.get(), "one new stack");
        assertEquals(CargoWeights.total(bed, WEIGH), cache.total(bed, counting, 1));
        assertEquals(721 + 719, asked.get(), "the weights changed: all again");
    }

    @Test
    void savesWaitAtMostTwoSecondsAndHappenOnceOnClose() {
        CargoSave s = new CargoSave(0);
        long version = 0;
        assertFalse(s.dirty(version));
        assertFalse(s.due(version, 1000));
        // a burst of clicks: due 40 ticks after the first, not pushed back by the others
        version++;
        s.changed(100);
        for (int t = 101; t < 130; t++) {
            version++;
            s.changed(t);
        }
        assertTrue(s.dirty(version));
        assertFalse(s.due(version, 139));
        assertTrue(s.due(version, 140));
        s.saved(version);
        assertFalse(s.dirty(version));
        assertFalse(s.due(version, 10_000), "saved: nothing more to do");
        // a click and the view closed: saved at once (close flushes if dirty), then not again
        version++;
        s.changed(500);
        assertFalse(s.due(version, 501));
        List<Long> written = new ArrayList<>();
        flush(s, version, written);   // closed
        flush(s, version, written);   // unloaded right after: unchanged, nothing encoded
        assertEquals(List.of(version), written);
        // unloaded with a change waiting: saved
        version++;
        s.changed(600);
        flush(s, version, written);
        assertEquals(List.of(version - 1, version), written);
        assertFalse(s.due(version, 9999));
    }

    /** VehicleModule#flushCargo: encode and store only when the bed is not as last saved. */
    static void flush(CargoSave s, long version, List<Long> written) {
        if (!s.dirty(version)) return;
        written.add(version);
        s.saved(version);
    }

    @Test
    void nearFiltersByEveryUnitBeforeAnythingIsCopied() {
        VehiclePhysics.State truck = new VehiclePhysics.State(100, 64, 100, 0);
        assertTrue(VehicleModule.near(truck, null, 110, 100, 64 * 64));
        assertTrue(VehicleModule.near(truck, null, 164, 100, 64 * 64), "on the edge");
        assertFalse(VehicleModule.near(truck, null, 164.01, 100, 64 * 64));
        assertFalse(VehicleModule.near(truck, List.of(), 100, 300, 64 * 64));
        // a train: its towing unit far, a towed unit in range
        List<VehiclePhysics.State> units = List.of(new VehiclePhysics.State(100, 64, 140, 0), new VehiclePhysics.State(100, 64, 180, 0));
        assertTrue(VehicleModule.near(truck, units, 100, 230, 64 * 64));
        assertFalse(VehicleModule.near(truck, units, 100, 250, 64 * 64));
        assertFalse(VehicleModule.near(truck, units, 300, 180, 48 * 48));
        // y does not count (as Hauled#points were measured: x and z)
        assertTrue(VehicleModule.near(new VehiclePhysics.State(0, 300, 0, 0), null, 0, 0, 1));
        Random r = new Random(9);
        for (int i = 0; i < 2000; i++) {
            VehiclePhysics.State a = new VehiclePhysics.State(r.nextInt(400), 64, r.nextInt(400), 0);
            List<VehiclePhysics.State> us = new ArrayList<>();
            for (int k = r.nextInt(4); k > 0; k--) us.add(new VehiclePhysics.State(r.nextInt(400), 64, r.nextInt(400), 0));
            double x = r.nextInt(400), z = r.nextInt(400), r2 = Math.pow(r.nextInt(100), 2);
            boolean want = false;
            List<VehiclePhysics.State> all = new ArrayList<>(us);
            all.addFirst(a);
            for (VehiclePhysics.State p : all) want |= (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z) <= r2;
            assertEquals(want, VehicleModule.near(a, us, x, z, r2));
        }
    }

    @Test
    void aSettledBoatIsFeltNowAndThen() {
        // driven, moving or turning its rudder: every tick
        for (long t = 0; t < 30; t++) {
            assertTrue(BoatMotion.steps(true, 0, 0, BoatMotion.SETTLE, t, 7));
            assertTrue(BoatMotion.steps(false, 0.5, 0, BoatMotion.SETTLE, t, 7));
            assertTrue(BoatMotion.steps(false, 0, 0.1, BoatMotion.SETTLE, t, 7));
            assertTrue(BoatMotion.steps(false, 0, 0, BoatMotion.SETTLE - 1, t, 7), "not settled yet");
        }
        // settled: once every RECHECK ticks, each boat at its own tick
        int ran = 0;
        for (long t = 0; t < 100; t++) if (BoatMotion.steps(false, 0, 0, BoatMotion.SETTLE, t, 7)) ran++;
        assertEquals(100 / BoatMotion.RECHECK, ran);
        assertTrue(BoatMotion.steps(false, 0, 0, BoatMotion.SETTLE, 3, 7));
        assertFalse(BoatMotion.steps(false, 0, 0, BoatMotion.SETTLE, 4, 7));
    }
}
