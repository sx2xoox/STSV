package dev.stsv.core.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 작업기 rules ({@link VehicleTools}), pure (unit-tested): which blocks a tool's strip covers, what a plough or rotary turns
 * into farmland, when it may be switched, how a harvest or a catch goes into a bed (slots, stacks and payload), which
 * seed goes next, how much the server lets the tools do this tick, and whether a block may be changed at all.
 */
public final class ToolRules {

    private ToolRules() {}

    // ------------------------------------------------------------------------------------------
    // Switching
    // ------------------------------------------------------------------------------------------

    /** Why a switch is refused, or OK. */
    public enum Switch { OK, MOVING, BED_OPEN }

    /** A tool is put on or taken off only standing still, and not while someone has the bed open. */
    public static Switch maySwitch(double speed, boolean bedOpen) {
        if (Math.abs(speed) > 1e-3) return Switch.MOVING;
        if (bedOpen) return Switch.BED_OPEN;
        return Switch.OK;
    }

    /** The mode after this one (Ctrl): the vehicle as it comes (0), each tool, then back to 0. */
    public static int next(int mode, int modes) {
        return modes <= 1 ? 0 : (Math.max(0, mode) + 1) % modes;
    }

    /** May the bed of this unit be opened with this tool on (a tool that took a trailer's place shuts its bed)? */
    public static boolean bedOpen(VehicleToolSpec.Tool tool, int unit) {
        return tool == null || !tool.replaces() || tool.unit() != unit;
    }

    // ------------------------------------------------------------------------------------------
    // Working
    // ------------------------------------------------------------------------------------------

    /** Does a tool work this tick: someone drives it forward (a plough is lifted while backing up), not too slowly? */
    public static boolean working(VehicleToolSpec.Tool tool, boolean driven, double speed) {
        return tool != null && tool.kind().works() && driven && speed > 0.2;
    }

    /** The speed up to which a net fishes (its speed, 3 blocks a second if it names none). */
    public static double netSpeed(VehicleToolSpec.Tool tool) {
        return tool.speed() > 0 ? tool.speed() : 3;
    }

    /**
     * Does a net catch now: someone at the helm, afloat, standing or going no faster than the net fishes? (Standing counts:
     * the people aboard fish with rods meanwhile, and a rod's line breaks 32 blocks from its float. What keeps a boat
     * left alone from fishing all day is {@link Spots}.)
     */
    public static boolean trawling(VehicleToolSpec.Tool tool, boolean driven, boolean afloat, double speed) {
        return tool.kind() == VehicleToolSpec.Kind.NET && driven && afloat && Math.abs(speed) <= netSpeed(tool) + 0.05;
    }

    /**
     * Is a boat's net down? It is not switched by hand: it goes down once the boat has stood or gone slowly for a moment
     * with someone at the helm, and comes up when the boat speeds off, runs aground or the helm is left.
     *
     * @param down      is it down now
     * @param slowTicks how long the boat has been slow enough (ticks)
     */
    public static boolean netDown(boolean down, boolean driven, boolean afloat, double speed, double top, int slowTicks) {
        if (!driven || !afloat) return false;
        double v = Math.abs(speed);
        return down ? v <= top + 0.5 : v <= top && slowTicks >= NET_SETTLE;
    }

    /** How long a boat goes slowly before its net goes down (ticks): not at every touch of the brake. */
    public static final int NET_SETTLE = 40;

    /**
     * The places a boat's net fished lately. One place (everything within {@link #RADIUS} blocks of where its first fish
     * came up) gives so many fish and then no more, for as long as the boat stays or keeps coming back: it fills again
     * only after the boat has let it be for {@link #REST} ticks. So a boat fishes a place for a few minutes, with or
     * without moving, and then has to sail on; one left standing with someone asleep at the helm catches a handful.
     */
    public static final class Spots {
        public static final double RADIUS = 48;
        public static final long REST = 20L * 60 * 10;
        static final int KEEP = 16;

        private static final class Spot {
            final double x, z;
            int count;
            long last;

            Spot(double x, double z, long now) {
                this.x = x;
                this.z = z;
                this.last = now;
            }
        }

        private final ArrayList<Spot> spots = new ArrayList<>();

        private Spot at(double x, double z) {
            Spot best = null;
            double d = RADIUS * RADIUS;
            for (Spot s : spots) {
                double q = (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z);
                if (q <= d) {
                    d = q;
                    best = s;
                }
            }
            return best;
        }

        /**
         * Is this place fished out for a net that takes `cap` fish from one place? Asking is fishing: a boat that keeps
         * its net in a place that gave all it had keeps it empty.
         */
        public boolean spent(double x, double z, int cap, long now) {
            Spot s = at(x, z);
            if (s == null || s.count < cap) return false;
            if (now - s.last >= REST) {
                s.count = 0;
                return false;
            }
            s.last = now;
            return true;
        }

        /** One fish came up here: how many this place has given now (1 = its first). */
        public int caught(double x, double z, long now) {
            Spot s = at(x, z);
            if (s == null) {
                s = new Spot(x, z, now);
                spots.add(s);
                if (spots.size() > KEEP) spots.removeFirst();
            }
            s.count++;
            s.last = now;
            return s.count;
        }
    }

    /** The chance of a catch on one check, checks `every` ticks apart, a catch every `seconds` on average. */
    public static double catchChance(double seconds, int every) {
        return Math.clamp(every / (seconds * 20.0), 0, 1);
    }

    /**
     * The block columns of a strip `width` blocks across, its middle at `at` on a unit standing at (x, z) facing yaw:
     * one point a block apart across it, each floored. Left to right; the same column twice only once.
     */
    public static List<int[]> strip(double x, double z, float yaw, VehicleSpec.Vec at, int width) {
        List<int[]> out = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            double right = at.right() + i - (width - 1) / 2.0;
            double[] p = VehiclePhysics.world(x, 0, z, yaw, new VehicleSpec.Vec(right, 0, at.forward()));
            int bx = (int) Math.floor(p[0]), bz = (int) Math.floor(p[2]);
            boolean seen = false;
            for (int[] c : out) seen |= c[0] == bx && c[1] == bz;
            if (!seen) out.add(new int[]{bx, bz});
        }
        return out;
    }

    /** What the ground under a tool is (only what matters to it). */
    public enum Ground { GRASS, DIRT, PATH, FARMLAND, OTHER }

    /** What stands on the ground block. */
    public enum Above { AIR, PLANT, CROP, OTHER }

    /**
     * Does a plough or rotary turn this into farmland? Grass, dirt and dirt paths, with nothing but air or a small wild
     * plant (short grass, fern: cut away) above, like a hoe (a crop, a flower, a block: left alone).
     */
    public static boolean tills(Ground ground, Above above) {
        return (ground == Ground.GRASS || ground == Ground.DIRT || ground == Ground.PATH) && (above == Above.AIR || above == Above.PLANT);
    }

    /** Does a seed drill sow here: farmland with nothing on it. */
    public static boolean sows(Ground ground, Above above) {
        return ground == Ground.FARMLAND && above == Above.AIR;
    }

    /** The first slot of the bed holding a seed (the bed's order: put what should be sown first), or -1. */
    public static <T> int seedSlot(T[] bed, Predicate<T> seed) {
        if (bed == null) return -1;
        for (int i = 0; i < bed.length; i++) if (bed[i] != null && seed.test(bed[i])) return i;
        return -1;
    }

    /** Is there room in a bed to harvest into: free slots and payload left (a harvest brings a few kinds at once)? */
    public static <T> boolean roomToHarvest(T[] bed, double used, double payload) {
        if (bed == null || used >= payload - 0.5) return false;
        int free = 0;
        for (T t : bed) if (t == null) free++;
        return free >= 2;
    }

    // ------------------------------------------------------------------------------------------
    // Into the bed
    // ------------------------------------------------------------------------------------------

    /** What the bed needs to know of its things (ItemStack in the plugin; anything in tests). */
    public interface Stacks<T> {
        boolean similar(T a, T b);

        int amount(T t);

        int max(T t);

        /** A copy of t holding n. */
        T withAmount(T t, int n);
    }

    /**
     * Puts as many of `item` into the bed as fit: first onto similar stacks, then into empty slots, never more than its
     * payload (unit: what one weighs; used: what the bed weighs now). Returns how many went in (the bed is changed).
     */
    public static <T> int insert(T[] bed, T item, double unit, double used, double payload, Stacks<T> s) {
        if (bed == null || item == null) return 0;
        int want = CargoWeights.fitting(used, unit, s.amount(item), payload);
        int left = want;
        for (int i = 0; i < bed.length && left > 0; i++) {
            T have = bed[i];
            if (have == null || !s.similar(have, item)) continue;
            int room = s.max(have) - s.amount(have);
            if (room <= 0) continue;
            int n = Math.min(room, left);
            bed[i] = s.withAmount(have, s.amount(have) + n);
            left -= n;
        }
        for (int i = 0; i < bed.length && left > 0; i++) {
            if (bed[i] != null) continue;
            int n = Math.min(s.max(item), left);
            bed[i] = s.withAmount(item, n);
            left -= n;
        }
        return want - left;
    }

    /** Takes one from a slot of the bed (empty: null). */
    public static <T> void takeOne(T[] bed, int slot, Stacks<T> s) {
        T t = bed[slot];
        if (t == null) return;
        int n = s.amount(t) - 1;
        bed[slot] = n <= 0 ? null : s.withAmount(t, n);
    }

    // ------------------------------------------------------------------------------------------
    // The server's load, and protection
    // ------------------------------------------------------------------------------------------

    /**
     * How many blocks the tools may work on this tick (all vehicles together): none while the average tick is at or over
     * maxMspt, half from 80 % of it, else perTick. maxMspt 0 or less: always perTick. (As the economy's roads do.)
     */
    public static int budget(double mspt, double maxMspt, int perTick) {
        if (perTick <= 0) return 0;
        if (maxMspt <= 0 || !(mspt >= 0)) return perTick;
        if (mspt >= maxMspt) return 0;
        if (mspt >= maxMspt * 0.8) return Math.max(1, perTick / 2);
        return perTick;
    }

    /**
     * Vanilla spawn protection: within `radius` blocks (a square) of the world spawn, nobody but an operator changes
     * blocks (off when the radius is 0 or there are no operators at all).
     */
    public static boolean spawnProtected(int bx, int bz, int spawnX, int spawnZ, int radius, boolean op, boolean anyOps) {
        if (radius <= 0 || op || !anyOps) return false;
        return Math.max(Math.abs(bx - spawnX), Math.abs(bz - spawnZ)) <= radius;
    }

    /** Why a block may not be changed by a tool, or OK. */
    public enum Verdict { OK, UNLOADED, SPAWN, GUARDED, LAND }

    /**
     * May a tool change this block for its driver? In order: its chunk loaded (nothing loads a chunk), not in spawn
     * protection, not where the content plugin guards (roads, docks, terminals), and the land's and the world's rules as
     * for placing a block there (someone else's land, server land, the build world).
     */
    public static Verdict verdict(boolean loaded, boolean spawn, boolean guarded, boolean builds) {
        if (!loaded) return Verdict.UNLOADED;
        if (spawn) return Verdict.SPAWN;
        if (guarded) return Verdict.GUARDED;
        if (!builds) return Verdict.LAND;
        return Verdict.OK;
    }
}
