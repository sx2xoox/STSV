package dev.stsv.core.economy;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** After a player's balance changed (main thread). For scoreboards, quests, achievements. */
public final class BalanceChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final long before, after;
    private final EconomyStore.Kind kind;
    private final String reason;

    public BalanceChangeEvent(UUID player, long before, long after, EconomyStore.Kind kind, String reason) {
        this.player = player;
        this.before = before;
        this.after = after;
        this.kind = kind;
        this.reason = reason;
    }

    public UUID player() { return player; }
    public long before() { return before; }
    public long after() { return after; }
    public long delta() { return after - before; }
    public EconomyStore.Kind kind() { return kind; }
    /** e.g. "pay", "cheque_cash", "shop_sell", "land_tax" */
    public String reason() { return reason; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
