package dev.stsv.core.vehicle;

/**
 * Where a vehicle's load was last changed and how far the vehicle has gone since (README "하역장": a dock buys only goods
 * hauled from far away, by a vehicle that really drove the way). Reset whenever the bed's contents change (loading,
 * unloading, a dock sale) and whenever the vehicle comes out of an item (placed): the load starts its trip there.
 * Opening the bed, or moving things between its slots, changes nothing. Pure (unit-tested); main thread.
 *
 * <p>The odometer counts the vehicle's own travel on the ground (horizontal, every tick it moves: driven, pulled, the
 * towing unit of a train), not the straight line; the straight line from where the load changed is {@link Snapshot#straight}.
 */
public final class VehicleTrip {

    private String world;
    private double x, z;
    private double odometer;
    private long changed;
    /** changed since it was last written to the database */
    boolean dirty;
    /** a 지게: where its wearer last had it on (null for vehicles, or not known) */
    Seen seen;

    /** Where a worn carrier was last seen on its wearer. */
    public record Seen(String world, double x, double z) {
        /** Is a wearer here now still where it was taken off (in the same world, within reach blocks)? */
        public boolean near(String inWorld, double atX, double atZ, double reach) {
            return world != null && world.equals(inWorld) && Math.hypot(atX - x, atZ - z) <= reach;
        }
    }

    public VehicleTrip(String world, double x, double z, double odometer, long changed) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.odometer = Math.max(0, odometer);
        this.changed = changed;
    }

    /** A trip starting here now (nothing driven yet). */
    public static VehicleTrip start(String world, double x, double z, long now) {
        VehicleTrip t = new VehicleTrip(world, x, z, 0, now);
        t.dirty = true;
        return t;
    }

    /** The vehicle went this far (blocks; a teleport-sized jump of more than 64 in a tick is not counted). */
    public void moved(double blocks) {
        if (!(blocks > 0) || blocks > MAX_STEP) return;
        odometer += blocks;
        dirty = true;
    }

    /** The most one tick may add: a vehicle tops out at 10 blocks a second (0.5 a tick); more is not driving. */
    public static final double MAX_STEP = 64;

    /** The load changed here: the trip starts again. */
    public void reset(String world, double x, double z, long now) {
        this.world = world;
        this.x = x;
        this.z = z;
        this.odometer = 0;
        this.changed = now;
        dirty = true;
    }

    public Snapshot snapshot() {
        return new Snapshot(world, x, z, odometer, changed);
    }

    /**
     * A trip as it is now.
     *
     * @param world    the world the load last changed in
     * @param x        where (block x, z)
     * @param odometer blocks driven since
     * @param changed  when (epoch millis)
     */
    public record Snapshot(String world, double x, double z, double odometer, long changed) {

        /** The straight distance from where the load changed to (x, z) in a world; infinite in another world. */
        public double straight(String inWorld, double toX, double toZ) {
            if (world == null || !world.equals(inWorld)) return Double.POSITIVE_INFINITY;
            return Math.hypot(toX - x, toZ - z);
        }

        /**
         * Hauled far enough to sell at a dock here: loaded at least min away (straight line, in the same world: a load from
         * another world went through a portal, not along the road) and driven at least min.
         */
        public boolean eligible(String inWorld, double toX, double toZ, double min) {
            double d = straight(inWorld, toX, toZ);
            return Double.isFinite(d) && d >= min && odometer >= min;
        }
    }
}
