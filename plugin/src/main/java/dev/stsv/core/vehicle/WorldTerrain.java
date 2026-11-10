package dev.stsv.core.vehicle;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.BoundingBox;

import java.util.List;

/**
 * The real world for {@link VehiclePhysics}: blocks' collision shapes (leaves, fences, slabs, stairs as they are), lava,
 * water, slow blocks. Never loads a chunk: a block in an unloaded chunk is a wall, and so is the world's floor.
 */
final class WorldTerrain implements VehiclePhysics.Terrain {

    private final World world;

    WorldTerrain(World world) {
        this.world = world;
    }

    @Override
    public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        int x0 = (int) Math.floor(minX), x1 = (int) Math.floor(maxX), y0 = (int) Math.floor(minY), y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ), z1 = (int) Math.floor(maxZ);
        int bottom = world.getMinHeight(), top = world.getMaxHeight();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean loaded = world.isChunkLoaded(x >> 4, z >> 4);
                for (int y = y0; y <= y1; y++) {
                    if (!loaded || y < bottom) {
                        out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                        continue;
                    }
                    if (y >= top) continue;
                    Block b = world.getBlockAt(x, y, z);
                    if (b.isPassable()) continue;
                    for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) {
                        out.add(new double[]{x + bb.getMinX(), y + bb.getMinY(), z + bb.getMinZ(), x + bb.getMaxX(), y + bb.getMaxY(), z + bb.getMaxZ()});
                    }
                }
            }
        }
    }

    @Override
    public VehiclePhysics.Medium medium(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return VehiclePhysics.Medium.AIR;
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m == Material.LAVA) return VehiclePhysics.Medium.LAVA;
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS || (b.getBlockData() instanceof Waterlogged w && w.isWaterlogged())) {
            return VehiclePhysics.Medium.WATER;
        }
        if (m == Material.SOUL_SAND || m == Material.HONEY_BLOCK || m == Material.COBWEB || m == Material.POWDER_SNOW
                || m == Material.SWEET_BERRY_BUSH) return VehiclePhysics.Medium.SLOW;
        return VehiclePhysics.Medium.AIR;
    }
}
