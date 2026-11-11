package dev.stsv.core.vehicle;

/**
 * When a bed's contents go to the database ({@link VehicleStore#cargo}). Encoding a big bed costs (a 720-slot train is
 * hundreds of KB), so not on every click in its view: a change is saved at most {@link #DELAY} ticks later, and at once
 * when its view closes, when the vehicle is hidden (its chunk unloads), put away or sent back, after a dock sale, and
 * when the server stops. A bed that did not change since it was last saved (or read) is never encoded again. Pure
 * (unit-tested): versions are the bed's {@code cargoVersion}, times are plugin ticks.
 */
final class CargoSave {

    /** Ticks a change waits at most (2 s). */
    static final int DELAY = 40;

    /** the bed's version as last saved (or as read from the database) */
    private long saved;
    /** when the change not saved yet is saved at the latest (ticks), -1: none waiting */
    private long due = -1;

    CargoSave(long version) {
        saved = version;
    }

    /** The bed changed at this tick: saved by {@link #DELAY} ticks from the first change not saved yet (more changes do not put it off). */
    void changed(long now) {
        if (due < 0) due = now + DELAY;
    }

    /** Is this version of the bed not in the database? */
    boolean dirty(long version) {
        return version != saved;
    }

    /** Is a save due by now (a change has waited long enough)? */
    boolean due(long version, long now) {
        return dirty(version) && (due < 0 || now >= due);
    }

    /** It was saved at this version. */
    void saved(long version) {
        saved = version;
        due = -1;
    }
}
