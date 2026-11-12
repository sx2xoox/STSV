package dev.stsv.core.vehicle;

import org.bukkit.event.entity.EntityRemoveEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 귀속 and /탈것 양도: who places a bound item, who gives it, and the confirmation. */
class VehicleOwnershipTest {

    private final UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), third = UUID.randomUUID();

    @Test
    void onlyTheOwnerOrAnAdminPlacesABoundItem() {
        assertTrue(VehicleOwnership.mayPlace(owner, owner, false));
        assertFalse(VehicleOwnership.mayPlace(owner, other, false), "someone else holding it: told whose it is");
        assertTrue(VehicleOwnership.mayPlace(owner, other, true), "an admin places it for its owner");
        assertTrue(VehicleOwnership.mayPlace(null, other, false), "an admin-made item is not bound yet");
        assertEquals(owner, VehicleOwnership.ownerOnPlacing(owner, other), "placing never changes the owner");
        assertEquals(other, VehicleOwnership.ownerOnPlacing(null, other), "an unbound item is the placer's");
    }

    @Test
    void onlyTheOwnerGivesItAway() {
        assertEquals(VehicleOwnership.Give.OK, VehicleOwnership.mayGive(owner, owner, false, other));
        assertEquals(VehicleOwnership.Give.NOT_OWNER, VehicleOwnership.mayGive(owner, other, false, third));
        assertEquals(VehicleOwnership.Give.NOT_OWNER, VehicleOwnership.mayGive(owner, other, false, other), "nor to themselves");
        assertEquals(VehicleOwnership.Give.OK, VehicleOwnership.mayGive(owner, other, true, third), "an admin");
        assertEquals(VehicleOwnership.Give.SELF, VehicleOwnership.mayGive(owner, owner, false, owner));
        assertEquals(VehicleOwnership.Give.SELF, VehicleOwnership.mayGive(owner, other, true, owner), "already theirs");
        assertEquals(VehicleOwnership.Give.OK, VehicleOwnership.mayGive(null, other, false, third), "an unbound item: whoever holds it");
        assertEquals(VehicleOwnership.Give.SELF, VehicleOwnership.mayGive(null, other, false, other));
    }

    @Test
    void theSameCommandAgainInTimeConfirms() {
        VehicleOwnership.Pending p = new VehicleOwnership.Pending("ITEM:5:9:stsv:golf_cart", other, 1000 + VehicleOwnership.CONFIRM_MILLIS);
        assertTrue(VehicleOwnership.confirms(p, "ITEM:5:9:stsv:golf_cart", other, 1000 + VehicleOwnership.CONFIRM_MILLIS));
        assertFalse(VehicleOwnership.confirms(p, "ITEM:5:9:stsv:golf_cart", other, 1001 + VehicleOwnership.CONFIRM_MILLIS), "too late");
        assertFalse(VehicleOwnership.confirms(p, "ITEM:6:9:stsv:golf_cart", other, 2000), "another vehicle in the hand");
        assertFalse(VehicleOwnership.confirms(p, "ITEM:5:9:stsv:golf_cart", third, 2000), "to someone else");
        assertFalse(VehicleOwnership.confirms(null, "ITEM:5:9:stsv:golf_cart", other, 2000), "nothing asked yet");
        assertEquals(10_000, VehicleOwnership.CONFIRM_MILLIS);
    }

    /** Item entities: destroyed (gone) vs merely moved (picked up, into a hopper, unloaded). */
    @Test
    void whatDestroysAnItemOnTheGround() {
        for (EntityRemoveEvent.Cause c : new EntityRemoveEvent.Cause[]{EntityRemoveEvent.Cause.DEATH, EntityRemoveEvent.Cause.DESPAWN,
                EntityRemoveEvent.Cause.OUT_OF_WORLD, EntityRemoveEvent.Cause.EXPLODE}) {
            assertTrue(VehicleLedger.DESTROYED.contains(c), c.name());
        }
        for (EntityRemoveEvent.Cause c : new EntityRemoveEvent.Cause[]{EntityRemoveEvent.Cause.PICKUP, EntityRemoveEvent.Cause.UNLOAD,
                EntityRemoveEvent.Cause.MERGE, EntityRemoveEvent.Cause.PLAYER_QUIT, EntityRemoveEvent.Cause.ENTER_BLOCK, EntityRemoveEvent.Cause.DISCARD}) {
            assertFalse(VehicleLedger.DESTROYED.contains(c), c.name());
        }
    }
}
