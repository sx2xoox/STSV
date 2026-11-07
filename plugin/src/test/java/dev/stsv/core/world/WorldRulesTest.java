package dev.stsv.core.world;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 건축월드 / 야생월드 settings, the wild world's random spots and what counts as a safe one. */
class WorldRulesTest {

    private static WorldRules bundled() throws Exception {
        try (var in = WorldRulesTest.class.getResourceAsStream("/config.yml")) {
            return WorldRules.parse(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)).getConfigurationSection("worlds"));
        }
    }

    @Test
    void defaultsWorkOutOfTheBox() throws Exception {
        WorldRules r = bundled();
        assertEquals("world", r.buildName("world"), "empty build-world = the main world");
        assertEquals("wild", r.wild());
        assertEquals(WorldRules.Kind.BUILD, r.kind("world", "world"));
        assertEquals(WorldRules.Kind.WILD, r.kind("wild", "world"));
        assertEquals(WorldRules.Kind.OTHER, r.kind("world_nether", "world"));
        assertEquals(3, r.warmup());
        assertEquals(5000, r.radius());
        assertEquals(300, r.minDistance());
        assertTrue(r.tries() >= 1 && r.cooldown() == 60);
    }

    @Test
    void parsingClampsAndNamesAWildWorldOnlyWhenItIsNotTheBuildWorld() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("build-world", " town ");
        y.set("wild-world", "town");
        y.set("travel-warmup-seconds", -5);
        y.set("wild-random.radius", 100);
        y.set("wild-random.min-distance", 500);
        y.set("wild-random.tries", 0);
        y.set("wild-random.cooldown-seconds", -1);
        WorldRules r = WorldRules.parse(y);
        assertEquals("town", r.buildName("world"));
        assertFalse(r.hasWild("world"), "the build world cannot also be the wild world");
        assertEquals(WorldRules.Kind.BUILD, r.kind("town", "world"));
        assertEquals(WorldRules.Kind.OTHER, r.kind("world", "world"));
        assertEquals(0, r.warmup());
        assertTrue(r.minDistance() < r.radius());
        assertEquals(1, r.tries());
        assertEquals(0, r.cooldown());
        assertFalse(WorldRules.parse(new YamlConfiguration()).hasWild("world"), "no wild-world = none");
    }

    @Test
    void randomColumnsStayInTheRing() throws Exception {
        WorldRules r = bundled();
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 2000; i++) {
            int[] xz = r.randomColumn(random, 100, -40);
            double d = Math.hypot(xz[0] - 100, xz[1] + 40);
            assertTrue(d >= r.minDistance() - 1 && d <= r.radius() + 1, "distance " + d);
        }
    }

    @Test
    void safeSpots() {
        int min = -64, max = 320;
        assertTrue(SafeSpot.safe(Material.GRASS_BLOCK, true, Material.AIR, Material.AIR, 70, min, max));
        assertTrue(SafeSpot.safe(Material.SAND, true, Material.AIR, Material.CAVE_AIR, 63, min, max));
        // water, lava, leaves, cactus, powder snow, magma
        for (Material bad : new Material[]{Material.WATER, Material.LAVA, Material.OAK_LEAVES, Material.CACTUS, Material.POWDER_SNOW, Material.MAGMA_BLOCK}) {
            assertFalse(SafeSpot.safe(bad, true, Material.AIR, Material.AIR, 70, min, max), bad.name());
        }
        assertFalse(SafeSpot.safe(Material.SHORT_GRASS, false, Material.AIR, Material.AIR, 70, min, max), "not solid");
        assertFalse(SafeSpot.safe(Material.STONE, true, Material.WATER, Material.AIR, 70, min, max), "under water");
        assertFalse(SafeSpot.safe(Material.STONE, true, Material.AIR, Material.STONE, 70, min, max), "no head room");
        assertFalse(SafeSpot.safe(Material.STONE, true, Material.AIR, Material.AIR, min, min, max), "the void / the bottom");
        assertFalse(SafeSpot.safe(Material.STONE, true, Material.AIR, Material.AIR, max - 2, min, max), "under the top");
    }
}
