package dev.stsv.core.world;

import dev.stsv.core.world.WorldRules.Kind;
import org.bukkit.Material;

/**
 * Where portals lead while there is a wild world: the wild world, its nether and its end are linked to each other
 * like vanilla's three dimensions; the build world reaches neither (its portals do nothing). Pure (unit-tested).
 */
public final class PortalLinks {

    private PortalLinks() {}

    public enum Portal { NETHER, END }

    /** VANILLA = leave the server's own choice alone, BLOCKED = no travel, else the world to go to. */
    public enum Dest { VANILLA, BLOCKED, WILD, NETHER, END }

    /**
     * @param from     the kind of world the portal is in
     * @param hasWild  is there a wild world (without one every portal works as vanilla)
     */
    public static Dest target(Kind from, Portal portal, boolean hasWild) {
        if (!hasWild || from == null) return Dest.VANILLA;
        return switch (from) {
            case BUILD -> Dest.BLOCKED;
            case WILD -> portal == Portal.NETHER ? Dest.NETHER : Dest.END;
            // a nether portal in the nether leads back up; an end portal in the end leads out (to the wild world)
            case NETHER -> portal == Portal.NETHER ? Dest.WILD : Dest.END;
            case END -> portal == Portal.NETHER ? Dest.NETHER : Dest.WILD;
            case OTHER -> Dest.VANILLA;
        };
    }

    /** The world kind a destination is. */
    public static Kind kind(Dest d) {
        return switch (d) {
            case WILD -> Kind.WILD;
            case NETHER -> Kind.NETHER;
            case END -> Kind.END;
            default -> null;
        };
    }

    /** How a nether portal scales x and z going from one kind of world to another (vanilla: the nether is 8 times smaller). */
    public static double scale(Kind from, Kind to) {
        boolean a = from == Kind.NETHER, b = to == Kind.NETHER;
        return a == b ? 1 : a ? 8 : 1.0 / 8;
    }

    // ------------------------------------------------------------------------------------------
    // The end's spawn platform (vanilla: ServerLevel.END_SPAWN_POINT 100,50,0 one block down, EndPlatformFeature)
    // ------------------------------------------------------------------------------------------

    /** The obsidian layer of the platform: 5x5 around (PLATFORM_X, PLATFORM_Z) at PLATFORM_Y, three layers of air above. */
    public static final int PLATFORM_X = 100, PLATFORM_Y = 48, PLATFORM_Z = 0, PLATFORM_HALF = 2, PLATFORM_AIR = 3;

    /** Where a player arrives on it: standing on the obsidian, facing west like vanilla. */
    public static final double ARRIVE_X = PLATFORM_X + 0.5, ARRIVE_Y = PLATFORM_Y + 1, ARRIVE_Z = PLATFORM_Z + 0.5;
    public static final float ARRIVE_YAW = 90;

    /** What the platform has at (x, y, z), or null outside it. */
    public static Material platformBlock(int x, int y, int z) {
        if (Math.abs(x - PLATFORM_X) > PLATFORM_HALF || Math.abs(z - PLATFORM_Z) > PLATFORM_HALF) return null;
        if (y == PLATFORM_Y) return Material.OBSIDIAN;
        return y > PLATFORM_Y && y <= PLATFORM_Y + PLATFORM_AIR ? Material.AIR : null;
    }

    /** Does a block of the platform need replacing? (any air counts as air) */
    public static boolean needsFix(Material want, Material have) {
        if (want == null) return false;
        if (want == Material.AIR) return !(have == Material.AIR || have == Material.CAVE_AIR || have == Material.VOID_AIR);
        return have != want;
    }
}
