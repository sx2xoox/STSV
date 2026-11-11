package dev.stsv.core.vehicle;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.stsv.core.vehicle.VehiclePhysicsTest.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step down followed closely by a step up (what a road's 1-in-3 grade makes over a dip): the vehicle keeps its speed,
 * never goes into a block, and a real wall (two blocks high) still stops it. Also for towed units and pulled carts.
 */
class VehicleStepTest {

    /** A short two-wheeler: 1.8 long, 0.8 wide, step 1. */
    static VehicleSpec bike() {
        return new VehicleSpec(1f, 9, 2, 4, 9, 2, 90, 2, 1.0, 1.8, 0.8, 1.4, 1.0,
                new NamespacedKey("t", "model/wheel"), 0.3, List.of(), List.of(new VehicleSpec.Vec(0, 0.5, 0)), null, null, null, null, null);
    }

    /**
     * Ground along +z: top at 64 up to z 9, then down a block (top 63) for `run` blocks, then up again (top 64). With
     * `half`, every step is a slab first (half a block), as the road builds it.
     */
    static VehiclePhysicsTest.Grid dip(int run, boolean half) {
        VehiclePhysicsTest.Grid g = new VehiclePhysicsTest.Grid();
        for (int x = -6; x <= 6; x++) {
            for (int z = -40; z <= 160; z++) {
                g.block(x, 61, z);
                boolean low = z >= 10 && z < 10 + run;
                g.block(x, 62, z);
                if (!low) g.block(x, 63, z);
            }
            if (half) {
                g.slab(x, 63, 10);                 // the first block of the dip: half way down
                g.slab(x, 63, 10 + run - 1);       // the last: half way up
            }
        }
        return g;
    }

    /** Drives from z 0.5 at `speed` (W held) for 160 ticks; asserts the speed is kept and nothing goes in a block. */
    static VehiclePhysics.State drive(VehicleSpec s, VehiclePhysicsTest.Grid g, double speed, String what) {
        VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        st.speed = speed;
        double low = speed;
        for (int i = 0; i < 160 && st.z < 40; i++) {
            double before = st.speed;
            VehiclePhysics.step(st, W, s, g, 10);
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, g), what + ": inside a block at tick " + i + " (z " + st.z + ", y " + st.y + ")");
            assertTrue(st.speed >= before - 1e-9, what + ": lost speed at tick " + i + " (z " + st.z + ", y " + st.y + "): " + before + " -> " + st.speed);
            assertFalse(st.bumped, what + ": bumped at tick " + i);
            low = Math.min(low, st.speed);
        }
        assertTrue(st.z >= 40, what + ": got through (z " + st.z + ")");
        assertEquals(64, st.y, 1e-9, what + ": back up on the road");
        assertTrue(low >= speed - 1e-9, what + ": never slowed (" + low + ")");
        return st;
    }

    @Test
    void downOneThenUpOneKeepsTheSpeed() {
        for (VehicleSpec s : List.of(VehiclePhysicsTest.car(), bike())) {
            for (int run = 1; run <= 8; run++) {
                for (double speed : new double[]{2, 4, 6, 8, 9}) {
                    drive(s, dip(run, false), speed, "length " + s.length() + ", run " + run + ", " + speed + " b/s");
                    drive(s, dip(run, true), speed, "slabs, length " + s.length() + ", run " + run + ", " + speed + " b/s");
                }
            }
        }
    }

    @Test
    void aBlockDownIsFollowedNotFallen() {
        VehicleSpec s = bike();
        VehiclePhysicsTest.Grid g = dip(30, false);
        VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        st.speed = 6;
        double y = st.y;
        for (int i = 0; i < 60 && st.z < 30; i++) {
            VehiclePhysics.step(st, W, s, g, 10);
            assertTrue(st.onGround, "down a block it stays on the ground (tick " + i + ")");
            assertTrue(y - st.y <= 0.5 + 1e-9, "and goes down gently, not in one jump: " + (y - st.y));
            y = st.y;
        }
        assertEquals(63, st.y, 1e-9);
        // two blocks down is a fall
        VehiclePhysicsTest.Grid cliff = new VehiclePhysicsTest.Grid().floor(63, -6, 6, -10, 9).floor(61, -6, 6, 10, 60);
        VehiclePhysics.State c = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        c.speed = 6;
        boolean fell = false;
        for (int i = 0; i < 60; i++) {
            VehiclePhysics.step(c, W, s, cliff, 10);
            fell |= !c.onGround;
        }
        assertTrue(fell, "off a two-block edge it falls");
        assertEquals(62, c.y, 1e-9);
        assertTrue(c.speed > 5.9, "and lands without losing its speed: " + c.speed);
    }

    @Test
    void justAfterLeavingTheGroundALedgeIsStillStepped() {
        // off a two-block edge, and a one-block ledge right after the landing spot: hit while still falling
        VehicleSpec s = bike();
        VehiclePhysicsTest.Grid g = new VehiclePhysicsTest.Grid().floor(63, -6, 6, -10, 9).floor(62, -6, 6, 10, 80).floor(63, -6, 6, 12, 80);
        VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
        st.speed = 8;
        for (int i = 0; i < 80 && st.z < 30; i++) {
            VehiclePhysics.step(st, W, s, g, 10);
            assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, g));
            assertFalse(st.bumped, "tick " + i);
        }
        assertTrue(st.z >= 30 && st.y == 64 && st.speed > 7.9, "on over the ledge: z " + st.z + ", y " + st.y + ", " + st.speed + " b/s");
    }

    @Test
    void aRealWallStillStopsIt() {
        for (VehicleSpec s : List.of(VehiclePhysicsTest.car(), bike())) {
            // down a block, then a wall two blocks high over the road where it came back up
            VehiclePhysicsTest.Grid g = dip(4, false);
            for (int x = -6; x <= 6; x++) g.block(x, 64, 14).block(x, 65, 14);
            VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
            st.speed = 9;
            boolean bumped = false;
            for (int i = 0; i < 120; i++) {
                VehiclePhysics.step(st, W, s, g, 10);
                bumped |= st.bumped;
                assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, g));
            }
            assertTrue(bumped, "hit it");
            assertEquals(0, st.speed, 1e-9, "stopped");
            assertEquals(14 - s.length() / 2, st.z, 1e-6, "its front at the wall");
            assertTrue(st.y <= 64 + 1e-9, "and did not climb it: " + st.y);
        }
    }

    @Test
    void aPulledCartAndATrainFollowOverTheDip() {
        VehicleSpec cart = CartPullTest.cart();
        VehicleSpec train = TrainPhysicsTest.train(1.6);
        for (int run = 1; run <= 6; run++) {
            VehiclePhysicsTest.Grid g = dip(run, false);
            VehiclePhysics.State st = VehiclePhysicsTest.onGround(0.5, 0.5, 0);
            for (int i = 0; i < 160 && st.z < 30; i++) {
                // the puller walks on ahead: the cart wants 0.3 a tick (6 b/s)
                CartPull.step(st, 0.5, st.z + CartPullTest.HITCH + 0.3, cart, g);
                assertFalse(VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, cart, g), "cart: run " + run + " tick " + i);
                if (i > 2) assertTrue(st.speed > 5.9, "cart: kept up, run " + run + " tick " + i + ": " + st.speed);
            }
            assertTrue(st.z >= 30, "cart got over the dip, run " + run + ": " + st.z);
            assertEquals(64, st.y, 1e-9);

            VehiclePhysics.State head = VehiclePhysicsTest.onGround(0.5, 4.5, 0);
            head.speed = 6;
            List<VehiclePhysics.State> units = TrainPhysics.straight(head, train.train());
            for (int i = 0; i < 200 && units.getLast().z < 30; i++) {
                assertTrue(TrainPhysics.step(head, units, W, train, g, TrainPhysics.Ground.NONE, 10), "train held back: run " + run + " tick " + i);
                TrainPhysicsTest.inNoBlock(head, units, train, g);
                assertTrue(head.speed > 5.9, "train: kept its speed, run " + run + " tick " + i + ": " + head.speed);
            }
            assertTrue(units.getLast().z >= 30, "the train got over the dip, run " + run);
            for (VehiclePhysics.State u : units) assertEquals(64, u.y, 1e-9);
        }
    }
}
