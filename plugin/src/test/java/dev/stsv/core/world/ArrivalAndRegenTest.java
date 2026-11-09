package dev.stsv.core.world;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /tpa arrival safety, the regenerated-wild-world check on join, and which mobs the peaceful build world refuses. */
class ArrivalAndRegenTest {

    @Test
    void arrivals() {
        assertTrue(SafeSpot.arrival(Material.AIR, false, Material.AIR, false, Material.STONE, false, false));
        assertTrue(SafeSpot.arrival(Material.WATER, false, Material.WATER, false, Material.SAND, false, false), "swimming is fine");
        assertTrue(SafeSpot.arrival(Material.STONE_SLAB, false, Material.AIR, false, Material.STONE, false, false), "on a slab");
        assertFalse(SafeSpot.arrival(Material.LAVA, false, Material.AIR, false, Material.STONE, false, true), "in lava (fire resistance)");
        assertFalse(SafeSpot.arrival(Material.AIR, false, Material.LAVA, false, Material.STONE, false, false));
        assertFalse(SafeSpot.arrival(Material.FIRE, false, Material.AIR, false, Material.NETHERRACK, false, false));
        assertFalse(SafeSpot.arrival(Material.AIR, false, Material.AIR, false, Material.MAGMA_BLOCK, false, false));
        assertFalse(SafeSpot.arrival(Material.POWDER_SNOW, false, Material.AIR, false, Material.DIRT, false, false));
        assertFalse(SafeSpot.arrival(Material.AIR, false, Material.AIR, false, Material.AIR, true, false), "flying, gliding or falling");
        assertFalse(SafeSpot.arrival(Material.AIR, false, Material.STONE, true, Material.STONE, false, false), "crawling in a gap: suffocation");
    }

    @Test
    void regeneratedWildWorld() {
        assertTrue(WorldsModule.regenerated("wild;1111", "wild", "2222"), "same name, new world id: regenerated");
        assertFalse(WorldsModule.regenerated("wild;1111", "wild", "1111"));
        assertFalse(WorldsModule.regenerated("wild_nether;1111", "wild", "2222"), "left from another world (a portal) is not this");
        assertFalse(WorldsModule.regenerated(null, "wild", "2222"), "nothing kept (quit before this update): left alone");
    }

    @Test
    void peacefulBuildWorld() {
        for (EntityType t : new EntityType[]{EntityType.ZOMBIE, EntityType.CREEPER, EntityType.PHANTOM, EntityType.SLIME, EntityType.ENDERMAN,
                EntityType.PILLAGER, EntityType.RAVAGER, EntityType.WITCH, EntityType.SILVERFISH, EntityType.GHAST}) {
            assertTrue(PeacefulBuild.hostile(t), t.name());
        }
        for (EntityType t : new EntityType[]{EntityType.COW, EntityType.VILLAGER, EntityType.IRON_GOLEM, EntityType.WOLF, EntityType.BEE,
                EntityType.ARMOR_STAND, EntityType.ITEM_DISPLAY}) {
            assertFalse(PeacefulBuild.hostile(t), t.name());
        }
        for (SpawnReason r : new SpawnReason[]{SpawnReason.NATURAL, SpawnReason.SPAWNER, SpawnReason.RAID, SpawnReason.PATROL,
                SpawnReason.REINFORCEMENTS, SpawnReason.VILLAGE_INVASION, SpawnReason.INFECTION, SpawnReason.LIGHTNING, SpawnReason.DEFAULT}) {
            assertTrue(PeacefulBuild.refused(r), r.name());
        }
        for (SpawnReason r : new SpawnReason[]{SpawnReason.COMMAND, SpawnReason.CUSTOM, SpawnReason.SPAWNER_EGG}) assertFalse(PeacefulBuild.refused(r));
    }
}
