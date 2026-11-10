package dev.stsv.core.vehicle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keys, seats, who may drive / open the bed / pick up, and the load shown. */
class VehicleRulesTest {

    private final UUID owner = UUID.randomUUID(), friend = UUID.randomUUID(), stranger = UUID.randomUUID();

    @Test
    void keysMapToDriving() {
        assertEquals(1, new DriveInput(true, false, true, false, false, false).steer(), "A = left");
        assertEquals(-1, new DriveInput(true, false, false, true, false, false).steer(), "D = right");
        assertEquals(0, new DriveInput(true, false, true, true, false, false).steer(), "both = straight");
        assertEquals(0, DriveInput.NONE.steer());
        assertTrue(DriveInput.pressed(false, true), "jump went down: one siren toggle / honk");
        assertFalse(DriveInput.pressed(true, true), "held: nothing more");
        assertFalse(DriveInput.pressed(true, false));
    }

    @Test
    void ctrlTogglesTheSirenOncePerPress() {
        DriveInput none = DriveInput.NONE;
        DriveInput space = new DriveInput(false, false, false, false, true, false, false);
        DriveInput ctrl = new DriveInput(false, false, false, false, false, false, true);
        DriveInput both = new DriveInput(true, false, false, false, true, false, true);
        assertEquals(List.of(), DriveInput.presses(none, space, true), "space is the horn: held, not pressed (VehicleSounds)");
        assertEquals(List.of(DriveInput.Press.SIREN), DriveInput.presses(none, ctrl, true), "Ctrl: siren on the patrol car");
        assertEquals(List.of(), DriveInput.presses(none, ctrl, false), "Ctrl on a vehicle without a light bar: nothing");
        assertEquals(List.of(), DriveInput.presses(ctrl, ctrl, true), "held Ctrl: no toggling back and forth");
        assertEquals(List.of(), DriveInput.presses(ctrl, none, true), "letting go does nothing");
        assertEquals(List.of(DriveInput.Press.SIREN), DriveInput.presses(none, both, true));
        assertEquals(List.of(DriveInput.Press.SIREN), DriveInput.presses(null, ctrl, true), "the first input seen counts as a press");
        assertTrue(both.forward() && both.jump(), "driving keys and the horn are read alongside");
    }

    @Test
    void aSeatedRidersBodyFacesTheVehicleAndOnlyTheHeadTurns() {
        // looking around within 85 degrees: the body stays exactly on the vehicle's facing
        for (float head = -85; head <= 85; head += 5) {
            float body = VehicleSeats.riderBodyYaw(30, 30 + head);
            float expect = Math.abs(head) > 50 ? 30 + head * 0.2f : 30;
            assertEquals(0, VehicleSeats.signed(body - expect), 1e-3, "head turned " + head);
        }
        assertEquals(30, VehicleSeats.riderBodyYaw(30, 30), 1e-4, "looking ahead");
        assertEquals(30, VehicleSeats.riderBodyYaw(30, 70), 1e-4, "40 degrees aside: the head alone turns");
        assertEquals(44, VehicleSeats.riderBodyYaw(30, 100), 1e-4, "70 aside: the shoulders twist along by a fifth");
        // looking further back: the body turns only enough to keep the head 85 off, never with the whole view
        assertEquals(30 + 15 + 17, VehicleSeats.riderBodyYaw(30, 130), 1e-4, "100 aside: body 15 + twist 17");
        assertEquals(VehicleSeats.signed(-90 + 85 - 17), VehicleSeats.riderBodyYaw(0, -90 + 360), 1e-4, "the other way round, wrapped");
        float back = VehicleSeats.riderBodyYaw(0, 180);
        assertEquals(85, Math.abs(VehicleSeats.signed(180 - back)) + 17, 1e-3, "straight back: head 85 off the body (+ twist)");
        // the car turns: the body turns with it (the head stays where the camera looks)
        assertEquals(VehicleSeats.signed(120), VehicleSeats.riderBodyYaw(120, 100), 1e-4);
        // angles wrap anywhere
        assertEquals(VehicleSeats.signed(350), VehicleSeats.riderBodyYaw(350 + 720, 10), 1e-3);
        assertEquals(-170, VehicleSeats.signed(190), 1e-6);
        assertEquals(-180, VehicleSeats.signed(180), 1e-6);
        assertEquals(179, VehicleSeats.signed(-181), 1e-6);
    }

    @Test
    void theDriverSeatGoesToWhoMayDriveOthersRideAlong() {
        assertEquals(0, VehicleSeats.pick(new boolean[4], true), "the owner takes the wheel");
        assertEquals(1, VehicleSeats.pick(new boolean[4], false), "a stranger never gets the driver's seat");
        assertEquals(2, VehicleSeats.pick(new boolean[]{true, true, false, false}, true), "driver's seat taken: ride along");
        assertEquals(3, VehicleSeats.pick(new boolean[]{false, true, true, false}, false));
        assertEquals(-1, VehicleSeats.pick(new boolean[]{true, true}, true), "full");
        assertEquals(-1, VehicleSeats.pick(new boolean[]{false}, false), "a one-seater only takes its driver");
        assertEquals(-1, VehicleSeats.pick(new boolean[0], true));
    }

    @Test
    void ridersStepOutOnTheirOwnSideFirst() {
        VehicleSpec s = VehiclePhysicsTest.car();
        List<VehicleSpec.Vec> left = VehicleSeats.exits(s, new VehicleSpec.Vec(-0.45, 0.28, 0.15));
        assertTrue(left.getFirst().right() < -0.95, "the driver (left seat) steps out to the left, clear of the body");
        assertEquals(0.15, left.getFirst().forward(), 1e-9, "beside their seat");
        assertTrue(left.get(1).right() > 0.95, "then the other side");
        assertEquals(s.height(), left.getLast().up(), 1e-9, "last resort: on top");
        assertTrue(VehicleSeats.exits(s, new VehicleSpec.Vec(0.45, 0.28, -0.7)).getFirst().right() > 0, "right seat: right side");
    }

    @Test
    void whoMayDriveAndOpenTheBed() {
        assertTrue(VehicleAccess.mayDrive(owner, owner, false, false, false));
        assertTrue(VehicleAccess.mayDrive(owner, friend, false, true, false), "someone of the owner's land");
        assertTrue(VehicleAccess.mayDrive(owner, stranger, true, false, false), "an admin");
        assertFalse(VehicleAccess.mayDrive(owner, stranger, false, false, false));
        assertTrue(VehicleAccess.mayOpenCargo(owner, friend, false, true, false));
        assertFalse(VehicleAccess.mayOpenCargo(owner, stranger, false, false, false));
    }

    /** /탈것 권한 <플레이어>: the allowed player drives, pulls and opens the beds of all the owner's vehicles. */
    @Test
    void anAllowedPlayerDrivesPullsAndOpensTheBedButNeverPicksUp() {
        UUID allowed = UUID.randomUUID();
        assertTrue(VehicleAccess.mayDrive(owner, allowed, false, false, true), "allowed by the owner");
        assertTrue(VehicleAccess.mayPull(owner, allowed, false, false, true), "pulls the hand cart");
        assertTrue(VehicleAccess.mayOpenCargo(owner, allowed, false, false, true), "opens the bed");
        assertFalse(VehicleAccess.mayPull(owner, stranger, false, false, false), "nobody else pulls it");
        assertTrue(VehicleAccess.mayPull(owner, friend, false, true, false), "the owner's land members pull it (as they drive)");
        assertTrue(VehicleAccess.mayPull(owner, stranger, true, false, false), "admins");
        assertTrue(VehicleAccess.mayPull(owner, owner, false, false, false), "the owner");
        assertEquals(VehicleAccess.PickUp.NOT_OWNER, VehicleAccess.pickUp(owner, allowed, false, false, false, true, false),
                "pick-up stays the owner's and the admins'");
        assertEquals(VehicleAccess.CargoOpen.OK, VehicleAccess.openCargo(owner, allowed, false, false, true, false, false, false));
        assertEquals(VehicleAccess.CargoOpen.DENIED, VehicleAccess.openCargo(owner, stranger, false, false, false, false, false, false));
        // who may be allowed: anyone but the owner
        assertTrue(VehicleAccess.mayGrant(owner, allowed));
        assertFalse(VehicleAccess.mayGrant(owner, owner), "not yourself");
        assertFalse(VehicleAccess.mayGrant(owner, null));
    }

    @Test
    void theBedOpensOnlyStandingStillUnpulledForOneViewer() {
        assertEquals(VehicleAccess.CargoOpen.OK, VehicleAccess.openCargo(owner, owner, false, false, false, false, false, false));
        assertEquals(VehicleAccess.CargoOpen.BUSY, VehicleAccess.openCargo(owner, owner, false, false, false, true, false, false),
                "one viewer at a time");
        assertEquals(VehicleAccess.CargoOpen.PULLED, VehicleAccess.openCargo(owner, owner, false, false, false, false, false, true),
                "not while someone pulls it");
        assertEquals(VehicleAccess.CargoOpen.MOVING, VehicleAccess.openCargo(owner, owner, false, false, false, false, true, false),
                "not while it moves");
        assertEquals(VehicleAccess.CargoOpen.DENIED, VehicleAccess.openCargo(owner, stranger, false, false, false, true, true, true),
                "a stranger is refused first");
    }

    @Test
    void pickingUp() {
        assertEquals(VehicleAccess.PickUp.OK, VehicleAccess.pickUp(owner, owner, false, false, false, true, false));
        assertEquals(VehicleAccess.PickUp.NOT_OWNER, VehicleAccess.pickUp(owner, friend, false, false, false, true, false),
                "land members drive it but do not take it away");
        assertEquals(VehicleAccess.PickUp.OK, VehicleAccess.pickUp(owner, stranger, true, false, false, true, false), "an admin (back to its owner)");
        assertEquals(VehicleAccess.PickUp.RIDERS, VehicleAccess.pickUp(owner, owner, false, true, false, true, false));
        assertEquals(VehicleAccess.PickUp.PULLED, VehicleAccess.pickUp(owner, owner, false, false, true, true, false),
                "someone holds the cart's handles");
        assertEquals(VehicleAccess.PickUp.CARGO, VehicleAccess.pickUp(owner, owner, false, false, false, false, false),
                "짐칸을 비워야 회수할 수 있어요: the load never goes into an item");
        assertEquals(VehicleAccess.PickUp.CARGO, VehicleAccess.pickUp(owner, stranger, true, false, false, false, false), "not even for an admin");
        assertEquals(VehicleAccess.PickUp.VIEWER, VehicleAccess.pickUp(owner, owner, false, false, false, true, true), "someone has the bed open");
    }

    @Test
    void theLoadShownFollowsHowFullTheBedIs() {
        int slots = 90;
        assertEquals(0, CargoStages.stage(0, slots, 4), "empty: nothing on the bed");
        assertEquals(1, CargoStages.stage(1, slots, 4), "one stack: a couple of crates");
        assertEquals(1, CargoStages.stage(22, slots, 4));
        assertEquals(2, CargoStages.stage(23, slots, 4), "over a quarter");
        assertEquals(2, CargoStages.stage(45, slots, 4), "half");
        assertEquals(3, CargoStages.stage(46, slots, 4));
        assertEquals(3, CargoStages.stage(67, slots, 4));
        assertEquals(4, CargoStages.stage(68, slots, 4), "over three quarters: the full, tarped load");
        assertEquals(4, CargoStages.stage(90, slots, 4));
        assertEquals(4, CargoStages.stage(200, slots, 4), "more than it holds (pages taken away): still full");
        assertEquals(0, CargoStages.stage(10, slots, 0), "no stage models: nothing shown");
        assertEquals(0, CargoStages.stage(10, 0, 4));
    }
}
