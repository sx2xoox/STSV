package dev.stsv.core.furniture;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Logic attached to furniture through "furniture.behavior" in items.yml.
 * Registered by modules: {@code plugin.furniture().registerBehavior("stsv:smelter", new SmelterBehavior())}.
 */
public interface FurnitureBehavior {

    default void onPlace(Player player, Furniture furniture) {}

    /**
     * Placed from this stack (still in the player's hand: it is used up after this returns), for state
     * the item carries, e.g. an upgrade level kept when the furniture was picked up.
     */
    default void onPlace(Player player, Furniture furniture, ItemStack from) {
        onPlace(player, furniture);
    }

    /**
     * The item that drops when the furniture is broken (called before {@link #onBreak}); null drops nothing.
     * Default: the plain item.
     */
    default ItemStack drop(Furniture furniture, ItemStack item) {
        return item;
    }

    default void onInteract(Player player, Furniture furniture) {}

    default void onBreak(Player player, Furniture furniture) {}
}
