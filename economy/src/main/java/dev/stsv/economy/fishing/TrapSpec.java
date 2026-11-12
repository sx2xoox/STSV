package dev.stsv.economy.fishing;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Random;

/**
 * fish.yml trap / pen: 통발 (a trap in the water that gathers the commons and uncommons of its water by itself) and 가두리 (a pen
 * that breeds one kind of fish on feed). Pure data and the maths (unit-tested).
 *
 * <p>Neither ticks while it stands: its state is the time it last moved on, and what it has. Whenever someone looks into it (a
 * click, picking it up), {@link #accumulate} / {@link #grow} work out what happened since, at most its storage (or its feed) in
 * steps. So a trap left for a week costs no more than one left for an hour, and a chunk full of traps costs nothing while nobody
 * comes by.
 */
public record TrapSpec(Trap trap, Pen pen) {

    /**
     * minutes: a catch every that many minutes (a fisher's trap-speed takes its share off); storage: catches it holds, then it
     * stops; uncommon / rare: the chances of a catch (a fisher adds trap-uncommon / trap-rare); perChunk: traps and pens a chunk.
     */
    public record Trap(double minutes, int storage, double uncommon, double rare, int perChunk) {}

    /**
     * minutes: one growth every that many minutes (trap-speed too); capacity: fish it holds; feed: feed it holds (one a growth);
     * growth: a growth adds that share of the fish (at least one); keep: what a plain harvest leaves to breed; seed: the fish it
     * needs to breed at all; perChunk: pens a chunk (counted with the traps too).
     */
    public record Pen(double minutes, int capacity, int feed, double growth, int keep, int seed, int perChunk) {}

    /** The most a fisher's trap-speed may take off a cycle. */
    public static final double MAX_CUT = 0.8;

    public static TrapSpec parse(ConfigurationSection c, List<String> problems) {
        ConfigurationSection t = c == null ? null : c.getConfigurationSection("trap");
        ConfigurationSection p = c == null ? null : c.getConfigurationSection("pen");
        if (t == null) problems.add("fish.trap: 통발 설정이 없음");
        if (p == null) problems.add("fish.pen: 가두리 설정이 없음");
        Trap trap = new Trap(Math.max(0.5, num(t, "minutes", 15)), Math.max(1, (int) num(t, "storage", 8)),
                Math.clamp(num(t, "uncommon", 0.25), 0, 1), Math.clamp(num(t, "rare", 0.003), 0, 0.5), Math.max(1, (int) num(t, "per-chunk", 8)));
        Pen pen = new Pen(Math.max(0.5, num(p, "minutes", 60)), Math.max(2, (int) num(p, "capacity", 32)), Math.max(1, (int) num(p, "feed", 16)),
                Math.clamp(num(p, "growth", 0.25), 0.01, 1), Math.max(0, (int) num(p, "keep", 16)), Math.max(1, (int) num(p, "seed", 2)),
                Math.max(1, (int) num(p, "per-chunk", 2)));
        if (pen.keep() >= pen.capacity()) problems.add("fish.pen.keep: capacity 보다 작아야 함");
        if (pen.seed() > pen.capacity()) problems.add("fish.pen.seed: capacity 보다 클 수 없음");
        if (pen.perChunk() > trap.perChunk()) problems.add("fish.pen.per-chunk: trap.per-chunk (통발·가두리 합계) 보다 클 수 없음");
        return new TrapSpec(trap, pen);
    }

    private static double num(ConfigurationSection s, String key, double def) {
        return s == null ? def : s.getDouble(key, def);
    }

    // ------------------------------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------------------------------

    /** One cycle in milliseconds: minutes, less the owner's trap-speed share (0 for anyone but a fisher, at most 80%). */
    public static long cycleMs(double minutes, double speedCut) {
        return Math.max(1000L, Math.round(minutes * 60_000 * (1 - Math.clamp(speedCut, 0, MAX_CUT))));
    }

    /**
     * What a trap gathered since {@code last}: one catch a whole cycle, up to the room left. {@code last} moves on by the cycles
     * used, so a part-cycle carries over; a trap that filled up stood still from then on (last = now: emptying it starts a fresh
     * cycle). A clock that went back (now before last) restarts the cycle.
     */
    public record Step(int catches, long last) {}

    public static Step accumulate(long last, long now, long cycleMs, int stored, int storage) {
        if (now <= last) return new Step(0, now);
        long cycles = (now - last) / Math.max(1, cycleMs);
        int room = Math.max(0, storage - stored);
        if (cycles >= room) return new Step(room, now);
        return new Step((int) cycles, last + cycles * cycleMs);
    }

    /** Milliseconds until the next catch (0 when it is full). */
    public static long untilNext(long last, long now, long cycleMs, int stored, int storage) {
        if (stored >= storage) return 0;
        long into = Math.max(0, now - last) % Math.max(1, cycleMs);
        return cycleMs - into;
    }

    // ------------------------------------------------------------------------------------------
    // What a trap catches
    // ------------------------------------------------------------------------------------------

    /** The chances of a trap catch for its owner: uncommon share and rare chance, each with the fisher's additions. */
    public record Odds(double uncommon, double rare) {}

    public Odds odds(double uncommonBonus, double rareBonus) {
        return new Odds(Math.clamp(trap.uncommon() + uncommonBonus, 0, 1), Math.clamp(trap.rare() + rareBonus, 0, 0.5));
    }

    /** The rarity index (0 common, 1 uncommon, 2 rare) of one catch. Never a legend. */
    public static int roll(Odds odds, Random r) {
        if (r.nextDouble() < odds.rare()) return 2;
        return r.nextDouble() < odds.uncommon() ? 1 : 0;
    }

    /** A moment no fish of a time or weather bites in (noon, dry, a half moon): traps catch only the fish of any time. */
    public static final FishSpec.Moment ANY_TIME = new FishSpec.Moment(6000, false, false, 4);

    // ------------------------------------------------------------------------------------------
    // Pens
    // ------------------------------------------------------------------------------------------

    /** A pen after {@link #grow}: its fish, the feed left and the time it moved on to. */
    public record Growth(int count, int feed, long last) {}

    /**
     * A pen since {@code last}: each whole cycle, a pen with at least {@code seed} fish and feed grows by growth x fish (at least
     * one) up to its capacity and eats one feed. It stands still (last = now) once it is full, out of feed or has too few fish to
     * breed; otherwise a part-cycle carries over. At most {@code feed} steps, whatever the time.
     */
    public static Growth grow(int count, int feed, long last, long now, long cycleMs, Pen pen) {
        if (now <= last) return new Growth(count, feed, now);
        long cycles = (now - last) / Math.max(1, cycleMs);
        long used = 0;
        while (used < cycles && feed > 0 && count >= pen.seed() && count < pen.capacity()) {
            count = Math.min(pen.capacity(), count + Math.max(1, (int) Math.floor(count * pen.growth())));
            feed--;
            used++;
        }
        boolean stuck = feed <= 0 || count >= pen.capacity() || count < pen.seed();
        return new Growth(count, feed, stuck ? now : last + used * cycleMs);
    }

    /** Fish a harvest takes: all but the pen's keep (everything when {@code all}, e.g. to change the kind). */
    public static int harvest(int count, boolean all, Pen pen) {
        return all ? count : Math.max(0, count - pen.keep());
    }

    /** Whether a fish of this rarity can go in a pen: commons and uncommons (rare and legend fish keep their size). */
    public static boolean penFish(FishSpec.Rarity r) {
        return r != null && r.index() <= 1;
    }
}
