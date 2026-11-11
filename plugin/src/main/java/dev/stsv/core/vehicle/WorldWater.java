package dev.stsv.core.vehicle;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;

import java.util.List;

/**
 * The real world for {@link BoatPhysics}: the blocks' collision boxes as {@link WorldTerrain} gives them, and what each
 * block is to a boat (water and how high it stands, falling water, bubble columns, ice, lava, solid). Never loads a
 * chunk: a block in an unloaded chunk is solid (the boat stops before it).
 */
final class WorldWater implements BoatPhysics.Water {

    private final World world;
    private final WorldTerrain terrain;

    WorldWater(World world) {
        this.world = world;
        this.terrain = new WorldTerrain(world);
    }

    @Override
    public void boxes(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        terrain.boxes(minX, minY, minZ, maxX, maxY, maxZ, out);
    }

    @Override
    public VehiclePhysics.Medium medium(int x, int y, int z) {
        return terrain.medium(x, y, z);
    }

    private Block block(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
        return world.getBlockAt(x, y, z);
    }

    @Override
    public BoatPhysics.Cell cell(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b == null) return y >= world.getMaxHeight() && world.isChunkLoaded(x >> 4, z >> 4) ? BoatPhysics.Cell.AIR : BoatPhysics.Cell.SOLID;
        Material m = b.getType();
        BlockData d = b.getBlockData();
        switch (m) {
            case LAVA -> {
                return BoatPhysics.Cell.LAVA;
            }
            case BUBBLE_COLUMN -> {
                return BoatPhysics.Cell.BUBBLE;
            }
            case WATER -> {
                return d instanceof Levelled l && l.getLevel() >= 8 ? BoatPhysics.Cell.FALLING : BoatPhysics.Cell.WATER;
            }
            case KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS -> {
                return BoatPhysics.Cell.WATER;
            }
            case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE -> {
                return BoatPhysics.Cell.ICE;
            }
            default -> { }
        }
        if (d instanceof Waterlogged w && w.isWaterlogged()) return BoatPhysics.Cell.WATER;   // its own shape still collides
        return b.isPassable() ? BoatPhysics.Cell.AIR : BoatPhysics.Cell.SOLID;
    }

    @Override
    public double level(int x, int y, int z) {
        Block b = block(x, y, z);
        if (b == null) return 0;
        if (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled l) {
            int level = l.getLevel();
            return level == 0 ? 8 / 9.0 : level >= 8 ? 1 : (8 - level) / 9.0;
        }
        return 8 / 9.0;   // a source's height: waterlogged blocks, kelp, seagrass, bubble columns
    }
}
