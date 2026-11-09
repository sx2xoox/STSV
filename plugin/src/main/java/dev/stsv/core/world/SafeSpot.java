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

    /** Blocks that burn, freeze, cut or hold whoever stands in or on them. */
    private static final Set<Material> ARRIVAL_HAZARD = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.CACTUS, Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.LAVA_CAULDRON, Material.COBWEB);

    /**
     * Is it safe to put another player where a player is right now (a /tpa arrival)? Not when they are in the air
     * (flying, gliding, falling: not on the ground, not in water, not climbing), in lava, or where the block at the
     * feet, the head or under the feet hurts or holds (lava, fire, magma, powder snow...), or where the feet or head
     * block is a full block (the arriving player would suffocate).
     *
     * @param airborne  flying, gliding, or neither on the ground nor in water nor on a ladder
     */
    public static boolean arrival(Material feet, boolean feetOccluding, Material head, boolean headOccluding, Material below,
                                  boolean airborne, boolean inLava) {
        if (airborne || inLava || feet == null || head == null) return false;
        if (feetOccluding || headOccluding) return false;
        return !ARRIVAL_HAZARD.contains(feet) && !ARRIVAL_HAZARD.contains(head) && (below == null || !ARRIVAL_HAZARD.contains(below));
    }
}
