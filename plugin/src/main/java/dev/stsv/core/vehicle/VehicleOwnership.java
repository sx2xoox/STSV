package dev.stsv.core.vehicle;

import java.util.UUID;

/**
 * 귀속 (a vehicle is bound to its owner) and /탈것 양도. Pure (unit-tested).
 *
 * <ul>
 *   <li>A vehicle, placed or as an item, and a 지게 belong to their owner whoever holds the item. Items made by an admin
 *       carry no owner until first placed (or worn); a shop sells them bound to the buyer ({@link VehicleModule#bind}).</li>
 *   <li><b>Placing</b> an item (a vehicle into the world): its owner, or an admin (it stays the owner's). Not the
 *       players the owner allowed with /탈것 권한: they drive and use it, they do not take it anywhere as an item.</li>
 *   <li><b>Wearing</b> a 지게 is using it: its owner, the players they allowed, the people of their land, admins.</li>
 *   <li><b>양도</b>: its owner (or an admin) gives it to another player (online or not, known to the server), confirmed by
 *       typing the same command again within {@link #CONFIRM_MILLIS}. Trading it any other way (the market, auctions, a
 *       trade window) is refused: the item would change hands but not its owner.</li>
 * </ul>
 */
public final class VehicleOwnership {

    private VehicleOwnership() {}

    /** How long a /탈것 양도 waits for the same command again. */
    public static final long CONFIRM_MILLIS = 10_000;

    /** May this player place this item? owner null = not bound yet (an admin-made item): whoever places it owns it. */
    public static boolean mayPlace(UUID owner, UUID player, boolean admin) {
        return owner == null || admin || owner.equals(player);
    }

    /** Who owns a vehicle placed from an item: its bound owner, else whoever placed it. */
    public static UUID ownerOnPlacing(UUID bound, UUID placer) {
        return bound != null ? bound : placer;
    }

    public enum Give { OK, NOT_OWNER, SELF }

    /**
     * May this player give it to `to`? owner null = an unbound item (an admin-made one): whoever holds it may.
     *
     * @param admin admins may give anyone's (an owner who asked them to, a mistake to fix)
     */
    public static Give mayGive(UUID owner, UUID player, boolean admin, UUID to) {
        UUID from = owner == null ? player : owner;
        if (from.equals(to)) return Give.SELF;
        if (owner != null && !admin && !owner.equals(player)) return Give.NOT_OWNER;
        return Give.OK;
    }

    /** A /탈것 양도 waiting for its confirmation: what (a key naming the vehicle), to whom, until when. */
    public record Pending(String subject, UUID to, long until) {}

    /** Is this /탈것 양도 the confirmation of the one waiting (the same vehicle to the same player, in time)? */
    public static boolean confirms(Pending pending, String subject, UUID to, long now) {
        return pending != null && pending.subject().equals(subject) && pending.to().equals(to) && now <= pending.until();
    }
}
