package dev.stsv.economy.road;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The roads snow keeps off (README "국도", 눈): every built road of each world as a {@link RoadNetwork}, looked up in O(1)
 * by every snow event. Built off the main thread (reading roads/*.txt and laying out the columns takes a few hundred ms
 * for a few long roads) and swapped in whole; until the first build is in, and while a new one is on its way, lookups use
 * the one before (at start: none). Built again only when asked ({@link #refresh}: a road saved or finished, a reload, signs
 * put up again), never on a lookup; refreshes asked for while one runs make one more build after it. Pure (unit-tested).
 */
final class SnowNets {

    private volatile Map<String, RoadNetwork> nets = Map.of();
    private final Supplier<Map<String, RoadNetwork>> build;
    private final Executor async;
    private final AtomicBoolean running = new AtomicBoolean(), again = new AtomicBoolean();

    /** build: every world's roads (run on async); async: where it runs. */
    SnowNets(Supplier<Map<String, RoadNetwork>> build, Executor async) {
        this.build = build;
        this.async = async;
    }

    /** The roads of a world as last built (none yet: {@link RoadNetwork#NONE}). */
    RoadNetwork get(String world) {
        RoadNetwork n = nets.get(world);
        return n == null ? RoadNetwork.NONE : n;
    }

    /** Builds them again (off the main thread); lookups go on using the old ones until it is done. */
    void refresh() {
        again.set(true);
        kick();
    }

    private void kick() {
        if (!running.compareAndSet(false, true)) return;   // one on its way: it builds once more when done
        async.execute(() -> {
            try {
                while (again.getAndSet(false)) nets = Map.copyOf(build.get());
            } finally {
                running.set(false);
            }
            if (again.get()) kick();
        });
    }
}
