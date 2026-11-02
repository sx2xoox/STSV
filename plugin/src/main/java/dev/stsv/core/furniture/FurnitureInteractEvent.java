package dev.stsv.core.furniture;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when a player right-clicks a piece of furniture. Cancel to prevent it. */
public final class FurnitureInteractEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Furniture furniture;
    private boolean cancelled;

    public FurnitureInteractEvent(Player player, Furniture furniture) {
        this.player = player;
        this.furniture = furniture;
    }

    public Player getPlayer() { return player; }

    public Furniture getFurniture() { return furniture; }

    @Override public boolean isCancelled() { return cancelled; }

    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
