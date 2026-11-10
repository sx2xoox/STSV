package dev.stsv.core.vehicle;

import java.util.UUID;

/**
 * Who may do what with a vehicle. Pure (unit-tested).
 *
 * <ul>
 *   <li><b>Drive</b>, open the <b>storage bed</b> and <b>pull</b> a hand cart: its owner, the players the owner allowed
 *       ({@code /탈것 권한 <플레이어>}: every vehicle of that owner), the people of the owner's land (the economy server's
 *       land members: they share a home, so they share its cars) and admins. Anyone else who gets in rides as a
 *       passenger.</li>
 *   <li><b>Pick up</b> (back into an item): its owner and admins only (not the allowed players), only when nobody rides
 *       or pulls it, nobody has its bed open and the bed is empty (the load stays in the vehicle, never in an item, so it
 *       cannot be copied).</li>
 *   <li>Land: placing it asks the land protection like placing a block (own land, or the wild world); driving through
 *       anyone's land, getting in and out there, and picking up your own vehicle there are allowed (it is your thing,
 *       and taking it away is what the land's owner wants).</li>
 * </ul>
 */
public final class VehicleAccess {

    private VehicleAccess() {}

    public enum PickUp { OK, NOT_OWNER, RIDERS, PULLED, VIEWER, CARGO }

    /**
     * @param trusted the player lives on the owner's land (the land plugin says so)
     * @param granted the owner allowed this player (/탈것 권한)
     */
    public static boolean mayDrive(UUID owner, UUID player, boolean admin, boolean trusted, boolean granted) {
        return admin || player.equals(owner) || trusted || granted;
    }

    public static boolean mayOpenCargo(UUID owner, UUID player, boolean admin, boolean trusted, boolean granted) {
        return mayDrive(owner, player, admin, trusted, granted);
    }

    public enum CargoOpen { OK, DENIED, BUSY, MOVING, PULLED }

    /**
     * Whether a player may open a bed now: who may drive, one viewer at a time, only standing still and not while
     * someone pulls it (it would move under the open chest).
     *
     * @param otherViewer someone else has it open
     */
    public static CargoOpen openCargo(UUID owner, UUID player, boolean admin, boolean trusted, boolean granted, boolean otherViewer,
                                      boolean moving, boolean pulled) {
        if (!mayOpenCargo(owner, player, admin, trusted, granted)) return CargoOpen.DENIED;
        if (otherViewer) return CargoOpen.BUSY;
        if (pulled) return CargoOpen.PULLED;
        if (moving) return CargoOpen.MOVING;
        return CargoOpen.OK;
    }

    /** Taking a hand cart's handles: who may drive. */
    public static boolean mayPull(UUID owner, UUID player, boolean admin, boolean trusted, boolean granted) {
        return mayDrive(owner, player, admin, trusted, granted);
    }

    /** Who may be allowed: someone other than the owner. */
    public static boolean mayGrant(UUID owner, UUID player) {
        return owner != null && player != null && !owner.equals(player);
    }

    /**
     * @param riders     someone sits in it
     * @param pulled     someone holds its handles (a hand cart)
     * @param cargoEmpty its bed holds nothing (true for vehicles without one)
     * @param cargoOpen  someone has its bed open right now
     */
    public static PickUp pickUp(UUID owner, UUID player, boolean admin, boolean riders, boolean pulled, boolean cargoEmpty, boolean cargoOpen) {
        if (!admin && !player.equals(owner)) return PickUp.NOT_OWNER;
        if (riders) return PickUp.RIDERS;
        if (pulled) return PickUp.PULLED;
        if (cargoOpen) return PickUp.VIEWER;
        if (!cargoEmpty) return PickUp.CARGO;
        return PickUp.OK;
    }
}
