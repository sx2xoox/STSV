package dev.stsv.core.vehicle;

import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * A vehicle's load as a content plugin sees it (the economy server's 하역장: {@link VehicleModule#hauled}). A copy: changing
 * it changes nothing. To take things out of the bed, {@link VehicleModule#unload} with this {@link #version} (refused when
 * the bed changed since).
 *
 * @param id        the vehicle (VehicleStore row)
 * @param item      its item id ("stsv:light_truck")
 * @param name      its item's name (plain text)
 * @param owner     who owns it
 * @param ownerName their name as stored
 * @param world     where it is
 * @param points    the ground centre of each of its units {x, y, z}: the vehicle, then a train's towed units front to back
 * @param trip      where the load last changed and how far it went since
 * @param contents  the bed, slot by slot (null = empty; copies)
 * @param payload   the most it carries
 * @param weight    what the bed weighs now
 * @param version   changes whenever the bed does
 * @param moving    it is moving now
 */
public record Hauled(long id, String item, String name, UUID owner, String ownerName, World world, List<double[]> points,
                     VehicleTrip.Snapshot trip, ItemStack[] contents, double payload, double weight, long version, boolean moving) {

    /** The vehicle's own ground centre (the first point). */
    public double[] centre() {
        return points.getFirst();
    }
}
