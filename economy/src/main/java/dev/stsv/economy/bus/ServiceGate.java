package dev.stsv.economy.bus;

/**
 * 공정 운행 (README "마을버스", bus.yml `fair`): what counts as real service. A driver's stop visits count (안전 운행 연속, a
 * festival stop point, a 산길 정류장) only at a stop other than the last counted one, after the driver really drove a bus
 * there: at least `minDrive` blocks and `roadShare` of the road between the two stops since the last counted stop. A
 * passenger pays only for a ride the bus really drove ({@link #paidRoad}). Pure (unit-tested).
 */
final class ServiceGate {

    /**
     * bus.yml `fair`. `minDrive`: blocks a driver drives between two counted stops at least; `roadShare`: and this share of
     * the road between them (also: a passenger carried less than this share of their ride pays only what the bus drove);
     * `maxSpeed`: a bus moving faster than this (blocks/s: carried off, not driven) drove nothing; `minRide`: a ride shorter
     * than this (blocks) is no ride (no fare).
     */
    record Rules(double minDrive, double roadShare, double maxSpeed, double minRide) {
        static final Rules DEFAULT = new Rules(100, 0.7, 30, 30);

        Rules {
            minDrive = Math.max(0, minDrive);
            roadShare = Math.clamp(roadShare, 0, 1);
            maxSpeed = Math.max(1, maxSpeed);
            minRide = Math.max(0, minRide);
        }
    }

    /** How far something was really driven: the sum of its small steps (a jump faster than a bus goes is not driving). */
    static final class Odometer {
        private String world;
        private double x, z, total;
        private long tick = Long.MIN_VALUE;

        /** It is at (x, z) at this tick: the blocks driven since the last call (0: the first, another world, a jump). */
        double move(String world, double x, double z, long tick, double maxSpeed) {
            double step = 0;
            if (this.world != null && this.world.equals(world) && tick > this.tick) {
                double d = Math.hypot(x - this.x, z - this.z);
                long gap = Math.min(60, tick - this.tick);   // a few ticks unseen (drivers changing seats): still one drive
                if (d <= maxSpeed / 20.0 * gap) step = d;
            }
            this.world = world;
            this.x = x;
            this.z = z;
            this.tick = tick;
            total += step;
            return step;
        }

        double total() {
            return total;
        }
    }

    private int last = -1;
    private double driven;

    /** The driver drove a bus this far. */
    void drove(double blocks) {
        if (blocks > 0) driven += blocks;
    }

    /** The last stop that counted (-1: none yet). */
    int last() {
        return last;
    }

    /** Blocks driven since the last counted stop. */
    double driven() {
        return driven;
    }

    /**
     * Someone got on or off at `stop`, `road` blocks by road from the last counted stop (not finite: unknown). True when the
     * visit counts (then it is the last counted stop and the distance starts again): the first ever, or another stop than
     * the last one reached by really driving.
     */
    boolean visit(int stop, double road, Rules r) {
        if (last >= 0) {
            if (stop == last) return false;
            double need = Math.max(r.minDrive(), Double.isFinite(road) ? road * r.roadShare() : 0);
            if (driven + 1e-9 < need) return false;
        }
        last = stop;
        driven = 0;
        return true;
    }

    /**
     * The blocks a passenger pays for: `road` (what they rode by the line), unless the bus drove less than `roadShare` of
     * it with them aboard (`carried`): then only what it drove. Under `minRide`: 0 (no ride at all).
     */
    static double paidRoad(double road, double carried, Rules r) {
        double paid = carried + 1e-9 >= road * r.roadShare() ? road : Math.min(road, carried);
        return paid < r.minRide() || paid <= 0 ? 0 : paid;
    }

    /**
     * What a passenger getting off rode (blocks): `planned` at the stop they were going to; `rode` (from where they got on
     * to here) when they get off elsewhere; and when their stop was changed on the way (the bus went past it or turned
     * round: `rerouted`) the lesser of the two (a bus that turned round right after they got on is paid the short hop, not
     * the long ride they asked for).
     */
    static double rodeRoad(double planned, double rode, boolean atDest, boolean rerouted) {
        if (atDest && !rerouted) return planned;
        return atDest ? Math.min(planned, rode) : rode;
    }
}
