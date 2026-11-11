package dev.stsv.core.vehicle;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * The real world's light for {@link VehicleLight}: a block's block light and sky light (the sky's own level, 0..15:
 * the client darkens it with the time of day). Never loads a chunk: a block in an unloaded chunk or under the world is
 * not known; above the world is open sky.
 */
final class WorldLight implements VehicleLight.Lookup {

    private final World world;

    WorldLight(World world) {
        this.world = world;
    }

    @Override
    public int light(int x, int y, int z) {
        if (y < world.getMinHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return -1;
        if (y >= world.getMaxHeight()) return VehicleLight.pack(0, world.hasSkyLight() ? 15 : 0);
        Block b = world.getBlockAt(x, y, z);
        return VehicleLight.pack(b.getLightFromBlocks(), b.getLightFromSky());
    }
}
