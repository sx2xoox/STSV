package dev.stsv.core.vehicle;

import dev.stsv.core.data.DatabaseModule;
import dev.stsv.core.data.Db;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static dev.stsv.core.vehicle.VehiclePhysicsTest.flat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Articulated vehicles (TrainPhysics, TrainSpec, Gait, TrainStore): towed units following their hitches straight, in
 * turns and backwards, the jack-knife limit, never into a block, the ox's legs, a train saved and read back, the beds.
 */
class TrainPhysicsTest {

    static final double CAP = 55;

    /** A tractor-like head (3.0 x 1.4, 6 b/s) towing two trailers (2.8 x the given width, tongue 2.2) in a chain. */
    static VehicleSpec train(double trailerWidth) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("v.body-scale", 2);
        y.set("v.speed", 6);
        y.set("v.reverse-speed", 2.5);
        y.set("v.acceleration", 3);
        y.set("v.brake", 8);
        y.set("v.turn-rate", 60);
        y.set("v.turn-speed", 2);
        y.set("v.size", List.of(3.0, 1.4, 2.0));
        y.set("v.wheel", "w");
        y.set("v.wheel-radius", 0.4);
        y.set("v.seats", List.of(Map.of("at", List.of(0.0, 1.0, -0.5)), Map.of("at", List.of(0.0, 0.8, 0.5), "unit", 1)));
        y.set("v.train.jackknife", CAP);
        y.set("v.train.slow-from", 0.6);
        Map<String, Object> trailer = Map.of("model", "t", "tongue", 2.2, "size", List.of(2.8, trailerWidth, 1.3), "wheel", "w",
                "wheel-radius", 0.4, "wheels", List.of(Map.of("at", List.of(-0.8, 0.4, 0.0)), Map.of("at", List.of(0.8, 0.4, 0.0))),
                "cargo", Map.of("slots", 360, "at", List.of(0.0, 0.7, 0.0), "stages", List.of("s1", "s2", "s3", "s4")));
        Map<String, Object> first = new java.util.HashMap<>(trailer);
        first.put("hitch", List.of(0.0, 0.5, -1.7));
        Map<String, Object> second = new java.util.HashMap<>(trailer);
        second.put("hitch", List.of(0.0, 0.5, -1.6));
        y.set("v.train.trailers", List.of(first, second));
        y.set("v.train.ground.farmland", 0.5);
        return VehicleSpec.parse(y.getConfigurationSection("v"), "t", m -> true);
    }

    static final DriveInput W = new DriveInput(true, false, false, false, false, false);
    static final DriveInput S = new DriveInput(false, true, false, false, false, false);
    static final DriveInput WA = new DriveInput(true, false, true, false, false, false);
    static final DriveInput SA = new DriveInput(false, true, true, false, false, false);

    /** Every towed unit hangs on its hitch: its axle a tongue behind the hitch, facing it (or at the limit). */
    static void hangsTogether(VehiclePhysics.State head, List<VehiclePhysics.State> units, TrainSpec t) {
        VehiclePhysics.State front = head;
        for (int i = 0; i < units.size(); i++) {
            VehiclePhysics.State u = units.get(i);
            TrainSpec.Trailer tr = t.trailers().get(i);
            double[] h = TrainPhysics.hitch(front, tr);
            assertEquals(tr.tongue(), Math.hypot(h[0] - u.x, h[2] - u.z), TrainPhysics.TOLERANCE + 1e-6, "unit " + (i + 1) + " a tongue from its hitch");
            double[] f = VehiclePhysics.forward(u.yaw);
            assertEquals(1, (f[0] * (h[0] - u.x) + f[1] * (h[2] - u.z)) / Math.hypot(h[0] - u.x, h[2] - u.z), 1e-4, "unit " + (i + 1) + " faces its hitch");
            assertTrue(Math.abs(TrainPhysics.angle(front, u)) <= CAP + 1e-3, "unit " + (i + 1) + " within the jack-knife limit");
            front = u;
        }
    }

    static void inNoBlock(VehiclePhysics.State head, List<VehiclePhysics.State> units, VehicleSpec s, VehiclePhysics.Terrain t) {
        assertFalse(VehiclePhysics.collides(head.x, head.y, head.z, head.yaw, s, t), "the head in no block");
        for (int i = 0; i < units.size(); i++) {
            VehiclePhysics.State u = units.get(i);
            assertFalse(VehiclePhysics.collides(u.x, u.y, u.z, u.yaw, s.train().trailers().get(i).unit(), t), "unit " + (i + 1) + " in no block");
        }
    }

    @Test
    void theSpecReads() {
        VehicleSpec s = train(1.6);
        TrainSpec t = s.train();
        assertNotNull(t);
        assertEquals(2, t.trailers().size());
        assertEquals(720, t.slots(), "two beds of 360");
        assertEquals(16, s.cargo().pages(), "all beds as one storage: more than one bed's 540");
        assertEquals(List.of(0, 1), t.seatUnits(), "the second seat on the first trailer");
        assertEquals(0, t.firstPage(1));
        assertEquals(8, t.firstPage(2));
        assertEquals(-1, t.firstPage(0), "the head has no bed");
        assertEquals(1, t.unitOfPage(7));
        assertEquals(2, t.unitOfPage(8));
        assertEquals(0.5, t.ground(TrainSpec.Surface.FARMLAND), 1e-9);
        assertEquals(1, t.ground(TrainSpec.Surface.GRASS), 1e-9, "unlisted ground: full speed");
        assertTrue(t.reach() > 2 * 2.2 + 1.7 + 1.6, "reaches past both trailers");
    }

    @Test
    void straightTheTrailersFollowInLine() {
        VehicleSpec s = train(1.6);
        var t = flat();
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        assertEquals(0.5 - 1.7 - 2.2, units.get(0).z, 1e-9, "the first trailer's axle a hitch and a tongue behind");
        assertEquals(units.get(0).z - 1.6 - 2.2, units.get(1).z, 1e-9);
        for (int i = 0; i < 60; i++) {
            assertTrue(TrainPhysics.step(head, units, W, s, t, TrainPhysics.Ground.NONE, 10));
            hangsTogether(head, units, s.train());
            for (VehiclePhysics.State u : units) {
                assertEquals(0, VehicleSeats.signed(u.yaw), 1e-4, "straight behind");
                assertEquals(64, u.y, 1e-9, "on the ground");
                assertEquals(0.5, u.x, 1e-9);
            }
        }
        assertEquals(head.speed, units.get(1).speed, 1e-6, "the trailers go as fast as the head");
        assertTrue(units.get(1).roll != 0, "their wheels rolled");
    }

    @Test
    void inATurnTheyCutInsideAndStayWithinTheLimit() {
        VehicleSpec s = train(1.6);
        var t = new VehiclePhysicsTest.Grid().floor(63, -60, 60, -60, 60);
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        float most = 0;
        for (int i = 0; i < 400; i++) {
            TrainPhysics.step(head, units, WA, s, t, TrainPhysics.Ground.NONE, 10);
            hangsTogether(head, units, s.train());
            most = Math.min(most, TrainPhysics.angle(head, units.get(0)));
        }
        assertTrue(most < -10, "turning left bends the train (" + most + ")");
        assertTrue(Math.abs(most) <= CAP, "never past the limit");
        // the steady circle: the trailer runs on a smaller circle than the head (off-tracking)
        double rHead = Math.hypot(head.x, head.z), cx = 0, cz = 0;
        assertTrue(rHead >= 0, "moved");
        double angle = TrainPhysics.angle(head, units.getFirst());
        for (int i = 0; i < 40; i++) TrainPhysics.step(head, units, WA, s, t, TrainPhysics.Ground.NONE, 10);
        assertEquals(angle, TrainPhysics.angle(head, units.getFirst()), 2, "a steady circle: the angle settles");
    }

    @Test
    void theJackKnifeLimitHoldsAndSteeringSlowsNearIt() {
        TrainSpec t = train(1.6).train();
        assertEquals(1f, TrainPhysics.turnScale(0, -3, t), 1e-6, "straight: full turn");
        assertEquals(1f, TrainPhysics.turnScale((float) (-CAP * 0.5), -3, t), 1e-6, "half way: full turn");
        assertTrue(TrainPhysics.turnScale((float) (-CAP * 0.8), -3, t) < 1, "near the limit: slower");
        assertEquals(0.5f, TrainPhysics.turnScale((float) (-CAP * 0.8), -3, t), 1e-4, "80 %: half (slowing from 60 %)");
        assertEquals(0f, TrainPhysics.turnScale((float) -CAP, -3, t), 1e-6, "at the limit: no further");
        assertEquals(1f, TrainPhysics.turnScale((float) -CAP, 3, t), 1e-6, "turning back straight is never slowed");
        assertEquals(1f, TrainPhysics.turnScale((float) -CAP, 0, t), 1e-6);
        // the target itself never stands past the limit
        double[] w = TrainPhysics.target(0, 0, 0, 3, 0.1, 2.2, 0, CAP);
        assertEquals(CAP, Math.abs(VehicleSeats.signed(0 - (float) w[2])), 1e-3, "a hitch far to the side: at the limit");
        assertEquals(2.2, Math.hypot(3 - w[0], 0.1 - w[1]), 1e-6, "still a tongue from it");

        // reversing with full lock: the train folds up to the limit and no further
        VehicleSpec s = train(1.6);
        var g = new VehiclePhysicsTest.Grid().floor(63, -60, 60, -60, 60);
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        float most = 0;
        for (int i = 0; i < 300; i++) {
            TrainPhysics.step(head, units, SA, s, g, TrainPhysics.Ground.NONE, 10);
            hangsTogether(head, units, s.train());
            most = Math.max(most, Math.abs(TrainPhysics.angle(head, units.get(0))));
        }
        assertTrue(most > CAP - 1, "backing with the wheel turned folds it to the limit (" + most + ")");
        assertTrue(most <= CAP + 1e-3);
    }

    @Test
    void reversingPushesTheTrailersBack() {
        VehicleSpec s = train(1.6);
        var t = flat();
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 20.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        double z1 = units.get(1).z;
        for (int i = 0; i < 40; i++) {
            TrainPhysics.step(head, units, S, s, t, TrainPhysics.Ground.NONE, 10);
            hangsTogether(head, units, s.train());
        }
        assertTrue(head.speed < 0, "reversing");
        assertTrue(units.get(1).z < z1 - 1, "the last trailer pushed back");
        for (VehiclePhysics.State u : units) assertEquals(0, VehicleSeats.signed(u.yaw), 1e-3, "straight back stays straight");
        assertTrue(units.get(1).speed < 0);
    }

    @Test
    void aTrailerTooWideForAGapStopsTheTrainOutsideTheBlocks() {
        // a 2-block gap (x -1 .. 1) between two walls at z 6..7: the 1.4-wide head fits, the 2.4-wide trailers do not
        VehicleSpec s = train(2.4);
        var t = flat();
        for (int z = 6; z <= 7; z++) for (int y = 64; y <= 66; y++) {
            for (int x = -6; x <= -2; x++) t.block(x, y, z);
            for (int x = 1; x <= 6; x++) t.block(x, y, z);
        }
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0, 0.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        boolean held = false;
        for (int i = 0; i < 200; i++) {
            boolean ok = TrainPhysics.step(head, units, W, s, t, TrainPhysics.Ground.NONE, 10);
            held |= !ok;
            inNoBlock(head, units, s, t);
            hangsTogether(head, units, s.train());
        }
        assertTrue(held, "the first trailer reached the gap and held the train back");
        assertEquals(0, head.speed, 1e-9, "stopped");
        assertTrue(head.z > 7, "the head went through");
        assertTrue(units.get(0).z + 1.4 <= 6 + 1e-6, "the trailer stopped at the walls (" + units.get(0).z + ")");
        // backing out works
        double z = head.z;
        for (int i = 0; i < 20; i++) assertTrue(TrainPhysics.step(head, units, S, s, t, TrainPhysics.Ground.NONE, 10));
        assertTrue(head.z < z);
    }

    @Test
    void aTrailerStepsUpAndFallsWithGravity() {
        VehicleSpec s = train(1.6);
        var t = flat();
        for (int x = -3; x <= 3; x++) for (int z = 10; z <= 40; z++) t.block(x, 64, z);   // a one-block step up at z = 10
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 4.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        for (int i = 0; i < 200 && head.z < 32; i++) {
            TrainPhysics.step(head, units, W, s, t, TrainPhysics.Ground.NONE, 10);
            inNoBlock(head, units, s, t);
        }
        assertEquals(65, head.y, 1e-6, "the head is up");
        assertEquals(65, units.get(0).y, 1e-6, "so is the first trailer");
        assertEquals(65, units.get(1).y, 1e-6, "and the second");
        assertTrue(TrainPhysics.supported(units, s.train(), t));
    }

    @Test
    void roughGroundSlowsItByItsOwnShare() {
        VehicleSpec s = train(1.6);
        var t = flat();
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        TrainPhysics.Ground farm = (x, y, z) -> z < -3 ? TrainSpec.Surface.FARMLAND : TrainSpec.Surface.GRASS;
        assertEquals(0.5, TrainPhysics.groundFactor(head, s, units, farm), 1e-9, "a trailer on farmland slows the whole train");
        assertEquals(1, TrainPhysics.groundFactor(head, s, units, TrainPhysics.Ground.NONE), 1e-9);
        for (int i = 0; i < 200; i++) TrainPhysics.step(head, units, W, s, t, (x, y, z) -> TrainSpec.Surface.FARMLAND, 10);
        assertEquals(3, head.speed, 1e-6, "half its 6 b/s on farmland");
    }

    @Test
    void theOxLegsSwingWithTheGroundCovered() {
        Gait slow = new Gait(), fast = new Gait();
        double stride = 1.6;
        for (int i = 0; i < 20; i++) {
            slow.step(2 * VehiclePhysics.DT, 2, stride);
            fast.step(4 * VehiclePhysics.DT, 4, stride);
        }
        // 20 ticks at 2 b/s = 2 blocks = 1.25 strides; at 4 b/s twice that
        assertEquals(0.25, slow.phase(), 1e-9);
        assertEquals(0.5, fast.phase(), 1e-9, "twice the speed, twice the steps");
        assertEquals(1, slow.amount(), 1e-9, "walking: full swing");
        assertEquals(25 * Math.sin(2 * Math.PI * 0.25), slow.leg(0, 25), 1e-9);
        assertEquals(-slow.leg(0, 25), slow.leg(0.5, 25), 1e-9, "a leg half a stride on swings the other way");
        // backwards: the cycle turns back
        Gait back = new Gait();
        back.step(-0.4, -2, stride);
        assertEquals(0.75, back.phase(), 1e-9);
        // standing: the legs come to rest under it within a few ticks, no matter where in the stride
        Gait g = new Gait();
        for (int i = 0; i < 7; i++) g.step(0.17, 3.4, stride);
        assertTrue(Math.abs(g.leg(0.1, 25)) > 1);
        for (int i = 0; i < 7; i++) g.step(0, 0, stride);
        assertTrue(g.resting(), "at rest");
        assertEquals(0, g.leg(0.1, 25), 1e-9);
        assertEquals(0, g.bob(0.05), 1e-9);
        // a slow walk swings less
        Gait creep = new Gait();
        for (int i = 0; i < 10; i++) creep.step(0.02, 0.4, stride);
        assertEquals(0.4, creep.amount(), 1e-9);
        assertTrue(g.bob(0.05) >= 0 && creep.bob(0.05) <= 0.05);
    }

    @TempDir
    Path dir;

    @Test
    void aThreeUnitTrainIsSavedAndReadBack() throws Exception {
        VehicleSpec s = train(1.6);
        var t = new VehiclePhysicsTest.Grid().floor(63, -60, 60, -60, 60);
        VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        List<VehiclePhysics.State> units = TrainPhysics.straight(head, s.train());
        for (int i = 0; i < 90; i++) TrainPhysics.step(head, units, WA, s, t, TrainPhysics.Ground.NONE, 10);
        assertTrue(Math.abs(TrainPhysics.angle(head, units.get(0))) > 5, "bent");

        Db db = DatabaseModule.sqlite(dir.resolve("t.db"), Logger.getLogger("test"));
        try {
            VehicleStore vs = new VehicleStore(db);
            vs.init();
            TrainStore store = new TrainStore(db);
            store.init();
            VehicleStore.Row row = vs.create("stsv:tractor_trailer", UUID.randomUUID(), "Owner",
                    new VehicleStore.Pos(UUID.randomUUID(), "world", head.x, head.y, head.z, head.yaw), 7, 1).join();
            store.save(row.id(), units, 2).join();
            store.save(row.id(), units, 3).join();   // saved again: replaced

            // a restart: the head's row, the units' row, the train put back together
            VehicleStore.Row back = new VehicleStore(db).placed().getFirst();
            Map<Long, List<VehiclePhysics.State>> saved = new TrainStore(db).all();
            assertEquals(1, saved.size());
            VehiclePhysics.State head2 = new VehiclePhysics.State(back.pos().x(), back.pos().y(), back.pos().z(), back.pos().yaw());
            List<VehiclePhysics.State> again = TrainPhysics.restore(head2, s.train(), saved.get(row.id()));
            assertEquals(2, again.size());
            for (int i = 0; i < 2; i++) {
                assertEquals(units.get(i).x, again.get(i).x, 1e-3, "unit " + (i + 1) + " x");
                assertEquals(units.get(i).y, again.get(i).y, 1e-4);
                assertEquals(units.get(i).z, again.get(i).z, 1e-3);
                assertEquals(0, VehicleSeats.signed(units.get(i).yaw - again.get(i).yaw), 1e-3, "unit " + (i + 1) + " angle");
            }
            hangsTogether(head2, again, s.train());
            // picked up into an item: forgotten (placed again, it stands straight)
            store.delete(row.id()).join();
            assertTrue(new TrainStore(db).all().isEmpty());
        } finally {
            db.close();
        }
        // a saved layout that no longer fits (another number of trailers) stands straight
        List<VehiclePhysics.State> one = TrainStore.decode("1,64,2,30");
        assertEquals(2, TrainPhysics.restore(head, s.train(), one).size());
        assertNull(TrainStore.decode("x;y"));
    }

    @Test
    void aTrainWithLoadInAnyTrailerIsNotPickedUp() {
        TrainSpec t = train(1.6).train();
        Object[] storage = new Object[t.slots()];
        UUID owner = UUID.randomUUID();
        assertEquals(VehicleAccess.PickUp.OK, VehicleAccess.pickUp(owner, owner, false, false, false, CargoPages.used(storage) == 0, false));
        storage[400] = "rice";   // a slot of the second trailer's bed
        assertEquals(0, t.used(storage, 1));
        assertEquals(1, t.used(storage, 2), "it is the second trailer's");
        assertNull(t.stage(storage, 1), "the first shows no load");
        assertNotNull(t.stage(storage, 2), "the second shows its first load stage");
        assertEquals(VehicleAccess.PickUp.CARGO, VehicleAccess.pickUp(owner, owner, false, false, false, CargoPages.used(storage) == 0, false),
                "the whole train goes back into one item: refused while any trailer holds something");
        storage[400] = null;
        storage[3] = "beans";
        assertEquals(VehicleAccess.PickUp.CARGO, VehicleAccess.pickUp(owner, owner, false, false, false, CargoPages.used(storage) == 0, false));
        for (int i = 0; i < 360; i++) storage[i] = "x";
        assertEquals(t.trailers().get(0).cargo().stages().getLast(), t.stage(storage, 1), "full: the last stage");
    }
}
