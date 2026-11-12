package dev.stsv.core.vehicle;

import org.bukkit.Material;

/**
 * What a wheel throws up from the ground under it (바퀴 밑 흙먼지): dust on dirt, paths, farmland, sand and gravel, mud
 * on mud, a splash in shallow water, a puff on snow; nothing on grass, stone, roads or wood. Pure (unit-tested);
 * {@link VehicleFeelView} spawns the particles, now and then, only where players are near.
 */
public final class GroundFx {

    private GroundFx() {}

    public enum Kind { NONE, DUST, MUD, SPLASH, SNOW }

    /**
     * The effect of a wheel standing in block `in` (its lowest part: shallow water, a snow layer) on block `under`.
     */
    public static Kind of(Material under, Material in) {
        if (in == Material.WATER || in == Material.BUBBLE_COLUMN) return Kind.SPLASH;
        if (in == Material.SNOW || in == Material.POWDER_SNOW) return Kind.SNOW;
        if (under == null) return Kind.NONE;
        return switch (under) {
            case SNOW_BLOCK, SNOW, POWDER_SNOW -> Kind.SNOW;
            case MUD, MUDDY_MANGROVE_ROOTS -> Kind.MUD;
            case DIRT, COARSE_DIRT, ROOTED_DIRT, DIRT_PATH, FARMLAND, PODZOL, SAND, RED_SAND, SUSPICIOUS_SAND, GRAVEL,
                 SUSPICIOUS_GRAVEL, SOUL_SAND, SOUL_SOIL, CLAY, MYCELIUM -> Kind.DUST;
            default -> Kind.NONE;
        };
    }

    /** How many particles a wheel throws this time: more the faster it goes (1 .. 4). */
    public static int count(Kind k, double speed, double top) {
        if (k == Kind.NONE) return 0;
        double share = top <= 0 ? 0 : Math.min(1, Math.abs(speed) / top);
        int n = 1 + (int) Math.round(share * 3);
        return k == Kind.SPLASH ? n + 2 : n;
    }
}
