package dev.stsv.core.vehicle;

import org.bukkit.Material;
import org.bukkit.World;

/**
 * The real world's ground for {@link TrainPhysics}: which kind of block a train rolls over (farmland, dirt paths, dirt,
 * grass), for its speed ({@link TrainSpec#ground}). Never loads a chunk.
 */
final class WorldGround implements TrainPhysics.Ground {

    private final World world;

    WorldGround(World world) {
        this.world = world;
    }

    @Override
    public TrainSpec.Surface surface(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return TrainSpec.Surface.OTHER;
        return of(world.getBlockAt(x, y, z).getType());
    }

    static TrainSpec.Surface of(Material m) {
        return switch (m) {
            case FARMLAND -> TrainSpec.Surface.FARMLAND;
            case DIRT_PATH -> TrainSpec.Surface.PATH;
            case DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM -> TrainSpec.Surface.DIRT;
            case GRASS_BLOCK, MOSS_BLOCK -> TrainSpec.Surface.GRASS;
            default -> TrainSpec.Surface.OTHER;
        };
    }
}
