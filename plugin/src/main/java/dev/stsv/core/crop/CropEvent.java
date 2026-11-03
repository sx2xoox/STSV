package dev.stsv.core.crop;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Crop events for contents and protection: PLANT (cancellable: claims, job rules), USE (watering,
 * fertilizing, curing, looking: cancellable), BREAK (cancellable), HARVEST (drops and the quality
 * bonus can be changed before the quality is rolled).
 */
public final class CropEvent extends Event implements Cancellable {

    public enum Action { PLANT, USE, BREAK, HARVEST }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Action action;
    private final Player player;
    private final CropSpec crop;
    private final Location location;
    private final List<ItemStack> drops;
    private double qualityBonus;
    private boolean cancelled;

    public CropEvent(Action action, Player player, CropSpec crop, Location location, List<ItemStack> drops) {
        this.action = action;
        this.player = player;
        this.crop = crop;
        this.location = location;
        this.drops = drops;
    }

    public Action action() { return action; }
    public Player player() { return player; }
    public CropSpec crop() { return crop; }
    public Location location() { return location; }
    /** HARVEST: mutable list of what drops (quality stars are put on the produce afterwards) */
    public List<ItemStack> drops() { return drops; }
    public double qualityBonus() { return qualityBonus; }
    /** HARVEST: added to the quality roll (e.g. a skilled farmer) */
    public void qualityBonus(double v) { this.qualityBonus = v; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean c) { this.cancelled = c; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
