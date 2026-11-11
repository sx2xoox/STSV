package dev.stsv.core.vehicle;

import java.util.List;

/**
 * How bright a vehicle's models are drawn. Pure (unit-tested): the world comes in through {@link Lookup}.
 *
 * <p>Why: a display entity without a brightness is lit by the one block its origin is in, and every part of a vehicle
 * (body, wheels, load, light bar, a train's trailers and animal, a boat) stands at the vehicle's ground centre: exactly
 * on the ground's top face (a boat: at its keel, under the water). That block is the air above the ground only while the
 * origin is at or a hair above the face. A step up or a train settling leaves the height a rounding error under it, soul
 * sand, mud and snow hold a vehicle inside their (opaque) block, the client's glide over a step cuts through a block
 * corner, and on a dirt path, slab or stairs or beside a lamp the light changes from one block to the next: the whole
 * vehicle goes black or flips from bright to dark as it drives (and stays black where it parked).
 *
 * <p>So each vehicle (each towed unit on its own) is given a brightness of its own, the world's light around it: block
 * and sky light, each the highest of a few points around the body ({@link #probes}: the middle half a block and a block
 * over its floor, its front, back and sides a block over it), so one solid block or a wall beside it does not black it
 * out. It is felt again every {@value #MOVING_TICKS} ticks while the vehicle is active, every {@value #PARKED_TICKS}
 * while parked, and what is shown follows the felt light by at most {@value #STEP} levels every {@value #FADE_TICKS}
 * ticks, so driving into a tunnel or past a lamp fades instead of jumping. Sky light is the block's own (0..15): the
 * client darkens it with the time of day and the weather, as for any block, so dusk needs no update.
 */
final class VehicleLight {

    /** Ticks between two feels of the light: while active (driven, pulled, moving, afloat), while parked. */
    static final int MOVING_TICKS = 10, PARKED_TICKS = 40;
    /** Ticks between two steps of the shown light towards the felt one, and the levels (of 15) a step moves at most. */
    static final int FADE_TICKS = 5, STEP = 2;
    /** Heights over the vehicle's floor felt (blocks): the middle at both, the ends and sides at the higher. */
    static final double LOW = 0.5, HIGH = 1.0;
    /** Share of the half length / half width the end and side points stand out from the middle (inside the body). */
    static final double INSET = 0.7;

    private VehicleLight() {}

    /** The world's light at a block: {@link #pack}(block, sky), or -1 when it is not known (unloaded, under the world). */
    interface Lookup {
        int light(int x, int y, int z);
    }

    static int pack(int block, int sky) {
        return (clamp(block) << 4) | clamp(sky);
    }

    static int block(int packed) {
        return packed >> 4 & 15;
    }

    static int sky(int packed) {
        return packed & 15;
    }

    private static int clamp(int level) {
        return Math.clamp(level, 0, 15);
    }

    /** The points felt, in the vehicle's frame (right, up, forward from its ground centre). */
    static List<VehicleSpec.Vec> probes(double length, double width) {
        double f = Math.max(0, length) / 2 * INSET, r = Math.max(0, width) / 2 * INSET;
        return List.of(
                new VehicleSpec.Vec(0, LOW, 0), new VehicleSpec.Vec(0, HIGH, 0),
                new VehicleSpec.Vec(0, HIGH, f), new VehicleSpec.Vec(0, HIGH, -f),
                new VehicleSpec.Vec(r, HIGH, 0), new VehicleSpec.Vec(-r, HIGH, 0));
    }

    static List<VehicleSpec.Vec> probes(VehicleSpec s) {
        return probes(s.length(), s.width());
    }

    /**
     * The light around a vehicle at (x, y, z) facing yaw: the highest block light and the highest sky light of the points
     * (each on its own: a lamp on one side, the open sky on the other), packed; -1 when no point is known.
     */
    static int sample(double x, double y, double z, float yaw, List<VehicleSpec.Vec> probes, Lookup world) {
        int block = -1, sky = -1;
        for (VehicleSpec.Vec p : probes) {
            double[] c = VehiclePhysics.world(x, y, z, yaw, p);
            int l = world.light((int) Math.floor(c[0]), (int) Math.floor(c[1]), (int) Math.floor(c[2]));
            if (l < 0) continue;
            block = Math.max(block, block(l));
            sky = Math.max(sky, sky(l));
        }
        return block < 0 ? -1 : pack(block, sky);
    }

    /** A level moved towards the target by at most step. */
    static int approach(int shown, int target, int step) {
        if (Math.abs(target - shown) <= step) return target;
        return shown + (target > shown ? step : -step);
    }

    /** Is the light to be felt again this tick (spread over the ticks by the vehicle's id)? */
    static boolean sampleDue(long ticks, long id, boolean active) {
        return Math.floorMod(ticks + id, active ? MOVING_TICKS : PARKED_TICKS) == 0;
    }

    /** Is the shown light to step towards the felt one this tick (on every feel too)? */
    static boolean fadeDue(long ticks, long id) {
        return Math.floorMod(ticks + id, FADE_TICKS) == 0;
    }

    /** The light shown on one vehicle (or towed unit) and the one last felt around it. */
    static final class Shown {
        private int block = -1, sky = -1, wantBlock = -1, wantSky = -1;

        /** Nothing shown yet: the display keeps the world's own light. */
        boolean unset() {
            return block < 0;
        }

        int block() {
            return block;
        }

        int sky() {
            return sky;
        }

        /** Shows what is felt, all at once (a vehicle that just appeared). Returns whether that changed what is shown. */
        boolean jump(int packed) {
            if (packed < 0) return false;
            boolean changed = block != VehicleLight.block(packed) || sky != VehicleLight.sky(packed);
            block = wantBlock = VehicleLight.block(packed);
            sky = wantSky = VehicleLight.sky(packed);
            return changed;
        }

        /**
         * The light felt now: what is shown fades towards it ({@link #fade}). The first one is shown at once. Returns
         * whether what is shown changed.
         */
        boolean felt(int packed) {
            if (packed < 0) return false;
            if (unset()) return jump(packed);
            wantBlock = VehicleLight.block(packed);
            wantSky = VehicleLight.sky(packed);
            return false;
        }

        /** One step of what is shown towards what was felt. Returns whether it changed. */
        boolean fade() {
            if (settled()) return false;
            block = approach(block, wantBlock, STEP);
            sky = approach(sky, wantSky, STEP);
            return true;
        }

        boolean settled() {
            return block == wantBlock && sky == wantSky;
        }
    }
}
