package dev.stsv.core.economy;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A cheque was cashed (main thread, after the casher's {@link BalanceChangeEvent}). When the casher is not the issuer,
 * money went from one player to another: what the issuer owned (the cheque) is now the casher's. For rankings that
 * must not count money players hand each other.
 */
public final class ChequeCashedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID issuer, casher;
    private final long amount;

    public ChequeCashedEvent(UUID issuer, UUID casher, long amount) {
        this.issuer = issuer;
        this.casher = casher;
        this.amount = amount;
    }

    public UUID issuer() { return issuer; }
    public UUID casher() { return casher; }
    public long amount() { return amount; }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
