package dev.stsv.core.vehicle;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 작업기 (a boat's net, vehicle_tools.yml kind net): what the net brings up when the core looks into it, asked of a
 * content plugin ({@link VehicleModule#netHaul}). The core knows when (the tool's net.seconds, trawling slowly, afloat) and
 * puts the answer into the hold by weight; the plugin knows what the sea gives there, to that boat.
 */
@FunctionalInterface
public interface NetHaul {

    /**
     * @param driver  who trawls (in the first seat)
     * @param water   the water the net is in (behind the stern)
     * @param boat    the boat: its number, its item's id ("stsv:trawler"), how many people are aboard (the driver too)
     * @return what goes into the hold (one stack), or null: nothing this time
     */
    ItemStack haul(Player driver, Block water, Boat boat);

    /**
     * Whether this water is a place the plugin keeps its own count of (a fish school, 어군 조업): then the core's "this place
     * gave all it had" rule (net.spot within a radius) neither stops the net there nor counts what comes up, and the core
     * leaves the catch line on the action bar to the plugin. Default: no.
     */
    default boolean ownSpot(Block water, Boat boat) {
        return false;
    }

    /** The boat that trawls. */
    record Boat(long id, String item, int aboard) {}
}
