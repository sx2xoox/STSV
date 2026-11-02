package dev.stsv.core.furniture;

import dev.stsv.core.content.CustomItem;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;

/**
 * A placed piece of furniture. The ItemDisplay entity is the source of truth; its persistent data
 * holds the item id, the occupied blocks and the helper entities.
 */
public record Furniture(ItemDisplay display, CustomItem item) {

    public Location location() {
        return display.getLocation();
    }

    /** Persistent data of this furniture instance, for modules that store state (inventory, progress, owner...). */
    public org.bukkit.persistence.PersistentDataContainer data() {
        return display.getPersistentDataContainer();
    }
}
