package dev.stsv.core.vehicle;

import java.util.UUID;

/**
 * Who may do what with a vehicle. Pure (unit-tested).
 *
 * <ul>
 *   <li><b>Drive</b> and open the <b>storage bed</b>: its owner, the people of the owner's land (the economy server's land
 *       members: they share a home, so they share its cars) and admins. Anyone else who gets in rides as a passenger.</li>
 *   <li><b>Pick up</b> (back into an item): its owner and admins, only when nobody rides it, nobody has its bed open and
 *       the bed is empty (the load stays in the vehicle, never in an item, so it cannot be copied).</li>
 *   <li>Land: placing it asks the land protection like placing a block (own land, or the wild world); driving through
 *       anyone's land, getting in and out there, and picking up your own vehicle there are allowed (it is your thing,
 *       and taking it away is what the land's owner wants).</li>
 * </ul>
 */
public final class VehicleAccess {

    private VehicleAccess() {}

    public enum PickUp { OK, NOT_OWNER, RIDERS, VIEWER, CARGO }

    /** trusted: the player lives on the owner's land (the land plugin says so). */
    public static boolean mayDrive(UUID owner, UUID player, boolean admin, boolean trusted) {
        return admin || player.equals(owner) || trusted;
    }

    public static boolean mayOpenCargo(UUID owner, UUID player, boolean admin, boolean trusted) {
        return mayDrive(owner, player, admin, trusted);
    }

    /**
     * @param riders     someone sits in it
     * @param cargoEmpty its bed holds nothing (true for vehicles without one)
     * @param cargoOpen  someone has its bed open right now
     */
    public static PickUp pickUp(UUID owner, UUID player, boolean admin, boolean riders, boolean cargoEmpty, boolean cargoOpen) {
        if (!admin && !player.equals(owner)) return PickUp.NOT_OWNER;
        if (riders) return PickUp.RIDERS;
        if (cargoOpen) return PickUp.VIEWER;
        if (!cargoEmpty) return PickUp.CARGO;
        return PickUp.OK;
    }
}
