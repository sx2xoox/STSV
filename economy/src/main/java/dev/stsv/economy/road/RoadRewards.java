package dev.stsv.economy.road;

import java.util.Set;

/**
 * What a road's character pays (README "국도", 성격 and "마을버스"): the multipliers of roads.characters.&lt;id&gt; as the bus
 * and the docks use them. A stop is on a road when the saved roads have one within {@link #STOP_REACH} blocks of its point
 * ({@link RoadModule#characterNear}); a dock by its own road ({@link RoadModule#characterOf}). Pure (unit-tested).
 */
public final class RoadRewards {

    private RoadRewards() {}

    /** How far from a bus stop's point (its bay beside the road) the road it stands by is looked for (blocks). */
    public static final int STOP_REACH = 8;
    /** The passengers who like a mountain road: their weight at its stops × outdoors. */
    public static final Set<String> OUTDOORS = Set.of("hiker", "camper");
    /** 산길 기사: this many stops served (a passenger got off or on) on hard roads, counted per driver in eco.kv. */
    public static final String MOUNTAIN_TITLE = "economy:mountain_driver";
    public static final int MOUNTAIN_STOPS = 50;
    public static final String MOUNTAIN_KEY = "bus.mountain.";

    private static RoadCharacter.Shape shape(RoadCharacter c) {
        return (c == null ? RoadCharacter.NORMAL : c).shape();
    }

    /** A fare's × for a ride between stops on these roads (null: none): the larger of the two ends'. */
    public static double fare(RoadCharacter from, RoadCharacter to) {
        return Math.max(shape(from).fare(), shape(to).fare());
    }

    /** The safe-driving streak bonus's × at a stop on this road. */
    public static double streak(RoadCharacter at) {
        return shape(at).streak();
    }

    /** A passenger kind's weight × at a stop on this road (hikers and campers like a hard road's). */
    public static double weight(String type, RoadCharacter at) {
        return OUTDOORS.contains(type) ? shape(at).outdoors() : 1;
    }

    /** What a dock with a road of this character pays for unloading × (roads.characters.&lt;id&gt;.dock-pay). */
    public static double dock(RoadCharacter c) {
        return shape(c).dock();
    }

    /** Whether a stop on this road counts towards 산길 기사. */
    public static boolean mountain(RoadCharacter at) {
        return at == RoadCharacter.HARD;
    }

    /** Whether going from `before` to `after` stops served on hard roads earns the title now. */
    public static boolean titleNow(long before, long after) {
        return before < MOUNTAIN_STOPS && after >= MOUNTAIN_STOPS;
    }
}
