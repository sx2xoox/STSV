package dev.stsv.core.module;

import dev.stsv.core.STSVPlugin;

/**
 * A self-contained feature of the STSV core (items, resource pack, season, economy, ...).
 * Modules are enabled in registration order and disabled in reverse order.
 * Mode plugins (tycoon, kingdom war, ...) can register their own modules through {@link ModuleManager}.
 */
public interface StsvModule {

    /** Unique id, e.g. "content" or "season". */
    String id();

    void enable(STSVPlugin plugin) throws Exception;

    default void disable() {}

    /** Called by /stsv reload. Default: nothing to reload. */
    default void reload() throws Exception {}
}
