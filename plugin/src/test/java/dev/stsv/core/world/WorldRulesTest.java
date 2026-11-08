package dev.stsv.core.world;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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
        assertEquals(WorldRules.Kind.NETHER, r.kind("wild_nether", "world"), "the wild world's own nether");
        assertEquals(WorldRules.Kind.END, r.kind("wild_the_end", "world"));
        assertEquals(WorldRules.Kind.OTHER, r.kind("world_nether", "world"), "the server's default nether is no role");
        assertEquals(WorldRules.Kind.OTHER, r.kind("world_the_end", "world"));
        assertTrue(r.retired("world_nether", "world") && r.retired("world_the_end", "world"));
        assertFalse(r.retired("wild_nether", "world") || r.retired("world", "world") || r.retired("town", "world"));
        assertEquals(Set.of(WorldRules.Kind.NETHER, WorldRules.Kind.END), r.keepInventory(), "items kept in the wild nether and end");
        assertFalse(r.keeps(WorldRules.Kind.WILD) || r.keeps(WorldRules.Kind.BUILD) || r.keeps(WorldRules.Kind.OTHER));
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
    void theWildWorldsNetherAndEndCanBeNamed() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("wild-world", "resource");
        y.set("wild-nether", " hell ");
        y.set("wild-end", "");
        WorldRules r = WorldRules.parse(y);
        assertEquals("hell", r.wildNetherName("world"));
        assertEquals("resource_the_end", r.wildEndName("world"), "empty = <wild>_the_end");
        assertEquals(WorldRules.Kind.NETHER, r.kind("hell", "world"));
        assertEquals(WorldRules.Kind.OTHER, r.kind("resource_nether", "world"));
        assertEquals(WorldRules.Kind.END, r.kind("resource_the_end", "world"));
        assertTrue(WorldRules.wildSet(WorldRules.Kind.WILD) && WorldRules.wildSet(WorldRules.Kind.NETHER) && WorldRules.wildSet(WorldRules.Kind.END));
        assertFalse(WorldRules.wildSet(WorldRules.Kind.BUILD) || WorldRules.wildSet(WorldRules.Kind.OTHER));
        assertEquals(Set.of(WorldRules.Kind.NETHER, WorldRules.Kind.END), r.keepInventory(), "no keep-inventory key = nether and end");

        // without a wild world there is no wild nether or end, and the default ones are not retired
        WorldRules none = WorldRules.parse(new YamlConfiguration());
        assertEquals(null, none.wildNetherName("world"));
        assertEquals(WorldRules.Kind.OTHER, none.kind("_nether", "world"));
        assertEquals(WorldRules.Kind.OTHER, none.kind("world_nether", "world"));
        assertFalse(none.retired("world_nether", "world"));
    }

    @Test
    void keepInventoryIsListedByRole() {
        assertEquals(WorldRules.Kind.NETHER, WorldRules.role(" Nether "));
        assertEquals(WorldRules.Kind.NETHER, WorldRules.role("지옥"));
        assertEquals(WorldRules.Kind.END, WorldRules.role("엔드"));
        assertEquals(WorldRules.Kind.WILD, WorldRules.role("wild"));
        assertEquals(WorldRules.Kind.BUILD, WorldRules.role("건축월드"));
        assertEquals(null, WorldRules.role("world_nether"), "names are not roles");
        assertEquals(null, WorldRules.role("other"));

        YamlConfiguration y = new YamlConfiguration();
        y.set("wild-world", "wild");
        y.set("keep-inventory", List.of("nether", "wild", "nonsense"));
        WorldRules r = WorldRules.parse(y);
        assertEquals(Set.of(WorldRules.Kind.NETHER, WorldRules.Kind.WILD), r.keepInventory());
        assertTrue(r.keeps(WorldRules.Kind.WILD) && !r.keeps(WorldRules.Kind.END) && !r.keeps(WorldRules.Kind.BUILD));
        y.set("keep-inventory", List.of());
        assertTrue(WorldRules.parse(y).keepInventory().isEmpty(), "an empty list turns it on nowhere");
    }

    @Test
    void portalsLinkTheWildWorldsAndNotTheBuildWorld() {
        var N = PortalLinks.Portal.NETHER;
        var E = PortalLinks.Portal.END;
        assertEquals(PortalLinks.Dest.NETHER, PortalLinks.target(WorldRules.Kind.WILD, N, true));
        assertEquals(PortalLinks.Dest.WILD, PortalLinks.target(WorldRules.Kind.NETHER, N, true), "back up to the wild world, not the build world");
        assertEquals(PortalLinks.Dest.END, PortalLinks.target(WorldRules.Kind.WILD, E, true));
        assertEquals(PortalLinks.Dest.WILD, PortalLinks.target(WorldRules.Kind.END, E, true), "out of the end to the wild world");
        assertEquals(PortalLinks.Dest.NETHER, PortalLinks.target(WorldRules.Kind.END, N, true));
        assertEquals(PortalLinks.Dest.BLOCKED, PortalLinks.target(WorldRules.Kind.BUILD, N, true));
        assertEquals(PortalLinks.Dest.BLOCKED, PortalLinks.target(WorldRules.Kind.BUILD, E, true));
        assertEquals(PortalLinks.Dest.VANILLA, PortalLinks.target(WorldRules.Kind.OTHER, N, true));
        for (WorldRules.Kind k : WorldRules.Kind.values()) {
            assertEquals(PortalLinks.Dest.VANILLA, PortalLinks.target(k, N, false), "no wild world: vanilla");
            assertEquals(PortalLinks.Dest.VANILLA, PortalLinks.target(k, E, false));
        }
        assertEquals(WorldRules.Kind.NETHER, PortalLinks.kind(PortalLinks.Dest.NETHER));
        assertEquals(null, PortalLinks.kind(PortalLinks.Dest.BLOCKED));
    }

    @Test
    void netherPortalsScaleLikeVanilla() {
        assertEquals(1.0 / 8, PortalLinks.scale(WorldRules.Kind.WILD, WorldRules.Kind.NETHER));
        assertEquals(8.0, PortalLinks.scale(WorldRules.Kind.NETHER, WorldRules.Kind.WILD));
        assertEquals(1.0, PortalLinks.scale(WorldRules.Kind.WILD, WorldRules.Kind.END));
        assertEquals(8.0, PortalLinks.scale(WorldRules.Kind.NETHER, WorldRules.Kind.END), "the end is overworld-scaled");
        assertEquals(1.0, PortalLinks.scale(WorldRules.Kind.NETHER, WorldRules.Kind.NETHER));
        assertEquals(100.0, 800 * PortalLinks.scale(WorldRules.Kind.WILD, WorldRules.Kind.NETHER));
        assertEquals(-800.0, -100 * PortalLinks.scale(WorldRules.Kind.NETHER, WorldRules.Kind.WILD));
    }

    @Test
    void theEndPlatformIsVanillas() {
        // obsidian 5x5 at y 48 around (100, 0), air y 49-51; players arrive standing on it
        int obsidian = 0, air = 0;
        for (int x = 95; x <= 105; x++) for (int z = -5; z <= 5; z++) for (int y = 45; y <= 55; y++) {
            Material m = PortalLinks.platformBlock(x, y, z);
            if (m == Material.OBSIDIAN) {
                obsidian++;
                assertEquals(48, y);
            } else if (m == Material.AIR) {
                air++;
                assertTrue(y >= 49 && y <= 51, "air at " + y);
            }
            if (m != null) assertTrue(Math.abs(x - 100) <= 2 && Math.abs(z) <= 2);
        }
        assertEquals(25, obsidian);
        assertEquals(75, air);
        assertEquals(Material.OBSIDIAN, PortalLinks.platformBlock((int) Math.floor(PortalLinks.ARRIVE_X), (int) PortalLinks.ARRIVE_Y - 1,
                (int) Math.floor(PortalLinks.ARRIVE_Z)), "standing on obsidian");
        assertEquals(Material.AIR, PortalLinks.platformBlock(100, (int) PortalLinks.ARRIVE_Y, 0));
        assertEquals(Material.AIR, PortalLinks.platformBlock(100, (int) PortalLinks.ARRIVE_Y + 1, 0), "head room");

        assertFalse(PortalLinks.needsFix(Material.OBSIDIAN, Material.OBSIDIAN));
        assertTrue(PortalLinks.needsFix(Material.OBSIDIAN, Material.END_STONE));
        assertTrue(PortalLinks.needsFix(Material.OBSIDIAN, Material.AIR), "a missing platform is built");
        assertFalse(PortalLinks.needsFix(Material.AIR, Material.CAVE_AIR));
        assertTrue(PortalLinks.needsFix(Material.AIR, Material.END_STONE), "the space above is cleared");
        assertFalse(PortalLinks.needsFix(null, Material.END_STONE), "outside the platform nothing changes");
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
