package dev.stsv.economy.store;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * A customer another module sends into a store (a hidden secret's, docs/admin): who, what they buy, what they say, buttons
 * over them at the counter instead of paying. The store plays it; the module hears what happened ({@link Visitor}).
 */
public final class StoreVisit {

    private StoreVisit() {}

    /**
     * One visit.
     *
     * @param id      the visitor's own name for it (it hears it back)
     * @param kind    the bus kind it walks in as (scale, held things: bus.yml types)
     * @param skin    its skin key (null: the kind's)
     * @param name    its name tag (MiniMessage; null: the kind's)
     * @param items   what it buys (product ids; empty: nothing, it only comes to the counter)
     * @param exact   it pays the exact bill in coins (nothing to give back)
     * @param talk    its lines by situation (enter, browse, pay, thanks, wait); a situation it has none for it says nothing
     * @param buttons at the counter, these buttons (choice -> words) over it instead of paying; empty: it pays
     * @param counter a thing (item key) it leaves on the counter after paying, until a later visit's answer takes it away
     * @param parasol a thing (item key) left on the parasol table outside for a minute after it goes
     */
    public record Visit(String id, String kind, String skin, String name, List<String> items, boolean exact, Map<String, List<String>> talk,
                        Map<String, String> buttons, String counter, String parasol) {
        public List<String> lines(String situation) {
            return talk.getOrDefault(situation, List.of());
        }
    }

    /**
     * What an answer did: lines the customer says (one after another), whether the thing left on the counter is taken, a
     * figure (skin key) seen a moment at the parasol table outside after the lines, whether the customer goes now.
     */
    public record Reply(List<String> lines, boolean takeCounter, String figure, boolean leave) {}

    /** A module that sends visits (registered with {@link StoreModule#visitor}). */
    public interface Visitor {
        /**
         * Asked every 30 seconds of a real shift while nobody it sent is inside: a visit for this store now, or null.
         *
         * @param hour        the store's game hour
         * @param nightServed customers this worker served at night, ever
         * @param nightShift  whether this shift began at night (야간 근무)
         */
        StoreVisit.Visit due(Player worker, String store, int hour, long nightServed, boolean nightShift);

        /** It paid and is going. */
        default void paid(Player worker, String store, Visit v) {}

        /** A button over it was pressed. */
        default Reply answered(Player worker, String store, Visit v, String choice) {
            return new Reply(List.of(), false, null, true);
        }

        /** It went without paying or being answered (the shift ended, it was sent away). */
        default void missed(Player worker, String store, Visit v) {}
    }
}
