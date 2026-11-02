package dev.stsv.core.furniture;

import org.bukkit.entity.Player;

/**
 * Logic attached to furniture through "furniture.behavior" in items.yml.
 * Registered by modules: {@code plugin.furniture().registerBehavior("stsv:smelter", new SmelterBehavior())}.
 */
public interface FurnitureBehavior {

    default void onPlace(Player player, Furniture furniture) {}

    default void onInteract(Player player, Furniture furniture) {}

    default void onBreak(Player player, Furniture furniture) {}
}
