package dev.stsv.core.world;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * config.yml "worlds.wild-spots": how the 야생월드's ready spots ({@link WildSpotPool}) are filled in the
 * background, and how /야생월드 searches when the pool is empty. The filler generates or loads at most one chunk
 * at a time, only while the server is healthy, and rests between chunks. Pure (unit-tested).
 *
 * @param pool            ready spots to keep (0 = no pool: every trip searches)
 * @param intervalMs      rest after each chunk (normal pace)
 * @param fastIntervalMs  rest after each chunk while /관리자 야생스팟 채우기 runs
 * @param minTps          the filler rests while the 1-minute TPS is below this
 * @param maxMspt         or while the average tick takes this many ms or more
 * @param arrivalRadius   chunks around a spot generated before it counts as ready (1 = 3x3)
 * @param fallbackGenerate with an empty pool: at most this many never-generated chunks are tried
 */
public record WildSpotRules(int pool, long intervalMs, long fastIntervalMs, double minTps, double maxMspt, int arrivalRadius,
                            int fallbackGenerate) {

    public static final WildSpotRules DEFAULTS = new WildSpotRules(24, 2000, 250, 19, 40, 1, 2);

    /** The filler's state at one tick: why it does (or does not) work on a chunk now. */
    public enum Gate {
        /** no wild world (unset, or being regenerated) */ NO_WORLD,
        /** worlds.wild-spots.pool is 0 */ OFF,
        /** enough ready spots */ FULL,
        /** a chunk is being generated or loaded */ BUSY,
        /** the server is lagging: TPS or MSPT */ LAG,
        /** resting between chunks */ PACE,
        /** work on the next chunk now */ RUN
    }

    /** @param c the "worlds" section (null = defaults) */
    public static WildSpotRules parse(ConfigurationSection c) {
        if (c == null || !c.isConfigurationSection("wild-spots")) return DEFAULTS;
        ConfigurationSection s = c.getConfigurationSection("wild-spots");
        return new WildSpotRules(Math.clamp(s.getInt("pool"), 0, 1000),
                Math.round(Math.clamp(s.getDouble("interval-seconds"), 0.05, 600) * 1000),
                Math.round(Math.clamp(s.getDouble("fast-interval-seconds"), 0.05, 600) * 1000),
                Math.clamp(s.getDouble("min-tps"), 0, 20), Math.clamp(s.getDouble("max-mspt"), 1, 10_000),
                Math.clamp(s.getInt("arrival-chunk-radius"), 0, 3), Math.clamp(s.getInt("fallback-generate-tries"), 0, 50));
    }

    /** Is the server healthy enough to generate a chunk: 1-minute TPS at least min-tps and the average tick under max-mspt. */
    public boolean healthy(double tps, double mspt) {
        return tps >= minTps && mspt < maxMspt;
    }

    /**
     * What the filler does this tick.
     *
     * @param hasWorld   the wild world is there (not unset, not being regenerated)
     * @param size       ready spots now
     * @param target     ready spots wanted (the pool, or more while /관리자 야생스팟 채우기 runs)
     * @param inFlight   a chunk request is still running
     * @param sinceLastMs time since the last chunk request finished
     * @param fast       /관리자 야생스팟 채우기 is running (the short rest)
     */
    public Gate gate(boolean hasWorld, int size, int target, boolean inFlight, double tps, double mspt, long sinceLastMs, boolean fast) {
        if (!hasWorld) return Gate.NO_WORLD;
        if (target <= 0) return Gate.OFF;
        if (size >= target) return Gate.FULL;
        if (inFlight) return Gate.BUSY;
        if (!healthy(tps, mspt)) return Gate.LAG;
        if (sinceLastMs < (fast ? fastIntervalMs : intervalMs)) return Gate.PACE;
        return Gate.RUN;
    }

    /**
     * The order /야생월드 tries random columns in when the pool is empty: those whose chunk is already generated
     * first (a disk load at most), then at most maxGenerate others (each generates terrain), maxTries in all.
     */
    public static <T> List<T> plan(List<T> candidates, Predicate<T> generated, int maxTries, int maxGenerate) {
        List<T> ready = new ArrayList<>(), fresh = new ArrayList<>();
        for (T c : candidates) (generated.test(c) ? ready : fresh).add(c);
        List<T> out = new ArrayList<>();
        for (T c : ready) {
            if (out.size() >= maxTries) return out;
            out.add(c);
        }
        for (int i = 0; i < fresh.size() && i < maxGenerate && out.size() < maxTries; i++) out.add(fresh.get(i));
        return out;
    }

    /** The chunks around (cx, cz) within radius (a square, the centre first): what is generated before a spot is ready. */
    public static List<int[]> arrivalChunks(int cx, int cz, int radius) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[]{cx, cz});
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) if (dx != 0 || dz != 0) out.add(new int[]{cx + dx, cz + dz});
        }
        return out;
    }
}
