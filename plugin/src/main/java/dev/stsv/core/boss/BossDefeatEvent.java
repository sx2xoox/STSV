package dev.stsv.core.boss;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A boss died. Contents can take the loot out of {@link #drops()} (e.g. to auction it) before the rest
 * is dropped on the ground, and reward by {@link #damage()}.
 */
public final class BossDefeatEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String bossId;
    private final Location location;
    private final Map<UUID, Double> damage;
    private final List<ItemStack> drops;

    public BossDefeatEvent(String bossId, Location location, Map<UUID, Double> damage, List<ItemStack> drops) {
        this.bossId = bossId;
        this.location = location;
        this.damage = damage;
        this.drops = drops;
    }

    public String bossId() { return bossId; }
    public Location location() { return location; }
    /** player -> damage dealt (read only) */
    public Map<UUID, Double> damage() { return damage; }
    /** mutable: what is still dropped on the ground afterwards */
    public List<ItemStack> drops() { return drops; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
