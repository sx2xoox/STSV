package dev.stsv.core.world;

import org.bukkit.Material;

import java.util.Set;

/**
 * Where /야생월드 may put a player: on solid ground that does not hurt or drop them, with two blocks of air
 * above, inside the world. Pure (unit-tested): the caller reads the three blocks of the column's top.
 */
public final class SafeSpot {

    private SafeSpot() {}

    private static final Set<Material> AIR = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);

    /** Ground nobody should be dropped on, solid or not. */
    private static final Set<Material> HAZARD = Set.of(Material.WATER, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE, Material.BUBBLE_COLUMN, Material.SEAGRASS,
            Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT, Material.WITHER_ROSE, Material.SCAFFOLDING, Material.BEDROCK);

    /**
     * @param ground      the block the player would stand on, groundSolid = it is a full solid block
     * @param feet        the block at the player's feet, head the one above
     * @param groundY     the ground's height; minY / maxY = the world's build limits (exclusive top)
     */
    public static boolean safe(Material ground, boolean groundSolid, Material feet, Material head, int groundY, int minY, int maxY) {
        if (ground == null || feet == null || head == null) return false;
        if (groundY <= minY || groundY + 2 >= maxY) return false;   // the void, or no room under the top
        if (!groundSolid || HAZARD.contains(ground) || ground.name().endsWith("_LEAVES")) return false;
        return AIR.contains(feet) && AIR.contains(head);
    }
}
