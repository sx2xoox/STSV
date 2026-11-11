package dev.stsv.economy.road;

import org.bukkit.Bukkit;

/**
 * How much heavy background work (국도 SURVEY / PREPARE / BUILD, 하역장 짓기, 하역장 후보 살피기) may do this tick, by
 * how busy the server is: its average tick time (MSPT, Paper's 5-second average) and whether anyone is online. At or
 * over {@link Settings#maxMspt} it pauses (no chunk asked for, no block set); from {@link #SLOW} of it it goes slowly
 * (one chunk at a time, half its blocks); else it runs, with fewer chunk loads at once while players are online
 * (generating a chunk is the costly part). The work itself adds to the average, so it settles just under the limit.
 * Pure (unit-tested); {@link #now} reads the server.
 */
public final class LoadGate {

    /**
     * @param maxMspt          at or over this average tick time (ms) the work pauses; 0 or less: never
     * @param concurrent       chunk loads at once with nobody online
     * @param concurrentOnline chunk loads at once while players are online
     */
    public record Settings(double maxMspt, int concurrent, int concurrentOnline) {
        public Settings {
            concurrent = Math.max(1, concurrent);
            concurrentOnline = Math.max(1, concurrentOnline);
        }

        /** The most chunk loads it ever has on the way at once (what a {@link ChunkQueue} is made with). */
        public int most() {
            return Math.max(concurrent, concurrentOnline);
        }
    }

    /**
     * What the work may do this tick.
     *
     * @param run        false: pause (no new chunk loads, no blocks)
     * @param concurrent chunk loads it may have on the way at once (0 when paused)
     * @param share      the share of its block budget this tick (0 .. 1)
     */
    public record Allow(boolean run, int concurrent, double share) {
        public static final Allow PAUSE = new Allow(false, 0, 0);
    }

    /** From this share of the limit on it goes slowly. */
    static final double SLOW = 0.8;

    private LoadGate() {}

    /** What the work may do at this average tick time (ms) with this many players online. */
    public static Allow allow(Settings s, double mspt, int players) {
        int c = players > 0 ? s.concurrentOnline() : s.concurrent();
        if (s.maxMspt() <= 0 || !(mspt >= 0)) return new Allow(true, c, 1);
        if (mspt >= s.maxMspt()) return Allow.PAUSE;
        if (mspt >= s.maxMspt() * SLOW) return new Allow(true, 1, 0.5);
        return new Allow(true, c, 1);
    }

    /** {@link #allow} for the server now. */
    public static Allow now(Settings s) {
        return allow(s, Bukkit.getServer().getAverageTickTime(), Bukkit.getOnlinePlayers().size());
    }
}
