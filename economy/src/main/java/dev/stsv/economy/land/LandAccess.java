package dev.stsv.economy.land;

import dev.stsv.core.world.WorldRules;

import java.util.UUID;

/**
 * Who may do what where, by world (core config.yml "worlds") and land. Pure (unit-tested).
 * <ul>
 *   <li>건축월드 (build world): land is bought here and only here. Inside a land its owner and members may do
 *       everything; everyone else nothing. Outside any land nobody changes blocks (break, place, buckets,
 *       fire, tilling...), only uses things (doors, chests, animals).</li>
 *   <li>야생월드 (wild world) and its own 지옥 and 엔드: free for everyone. Claims there (made before they were
 *       the wild worlds, e.g. in the server's old nether/end of the same name) are dormant: kept, but they
 *       protect nothing.</li>
 *   <li>Other worlds (e.g. the server's unused default nether / end): claims made there earlier keep
 *       protecting; the rest is free.</li>
 * </ul>
 * Operators (stsv.economy.land.bypass) may always.
 */
public final class LandAccess {

    public enum Verdict { ALLOW, OTHERS_LAND, UNCLAIMED_BUILD }

    private LandAccess() {}

    /** changes: the action changes blocks (break, place, pour, ignite, till, ...); false = only uses something. */
    public static Verdict decide(WorldRules.Kind world, Land land, UUID player, boolean bypass, boolean changes) {
        if (bypass || WorldRules.wildSet(world)) return Verdict.ALLOW;
        if (land != null) return land.allows(player) ? Verdict.ALLOW : Verdict.OTHERS_LAND;
        return world == WorldRules.Kind.BUILD && changes ? Verdict.UNCLAIMED_BUILD : Verdict.ALLOW;
    }

    /** Do claims count in this world (protect, show)? Not in the wild world, its nether or its end. */
    public static boolean claimsCount(WorldRules.Kind world) {
        return !WorldRules.wildSet(world);
    }

    /** Can land be bought here? Only in the build world. */
    public static boolean claimable(WorldRules.Kind world) {
        return world == WorldRules.Kind.BUILD;
    }

    /** May nature (explosions, spreading fire, liquids from elsewhere) change blocks here? Not in a land or in the build world. */
    public static boolean sheltered(WorldRules.Kind world, Land land) {
        return claimsCount(world) && (land != null || world == WorldRules.Kind.BUILD);
    }
}
