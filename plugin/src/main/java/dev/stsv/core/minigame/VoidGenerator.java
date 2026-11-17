package dev.stsv.core.minigame;

import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/** The minigame world: nothing at all (every block in it is built by the minigames), always the plains biome. */
final class VoidGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public org.bukkit.block.Biome getBiome(WorldInfo info, int x, int y, int z) {
                return org.bukkit.block.Biome.PLAINS;
            }

            @Override
            public List<org.bukkit.block.Biome> getBiomes(WorldInfo info) {
                return List.of(org.bukkit.block.Biome.PLAINS);
            }
        };
    }

    @Override
    public org.bukkit.Location getFixedSpawnLocation(World world, Random random) {
        return new org.bukkit.Location(world, 0.5, 80, 0.5);
    }
}
