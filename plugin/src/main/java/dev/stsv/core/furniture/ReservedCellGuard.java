package dev.stsv.core.furniture;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Keeps the cells of a furniture that have no barrier (the model fills them too little for collision) free:
 * they are air, but nothing may be placed, poured, flowed, pushed, grown or formed into them, so the piece
 * can always be broken and picked up as before and its cells stay its own. Cells with a barrier need no help
 * (a barrier takes none of these). Lookups go through {@link ReservedCells}, so even liquid flow costs a map hit.
 */
final class ReservedCellGuard implements Listener {

    private final FurnitureModule furniture;

    ReservedCellGuard(FurnitureModule furniture) {
        this.furniture = furniture;
    }

    private boolean reserved(Block b) {
        return furniture.reserved(b);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (reserved(e.getBlockPlaced())) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof BlockMultiPlaceEvent multi) { // beds, doors, tall flowers: every half
            for (BlockState s : multi.getReplacedBlockStates()) {
                if (reserved(s.getBlock())) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (reserved(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (reserved(e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonBlocked(e, e.getBlocks(), true)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonBlocked(e, e.getBlocks(), false)) e.setCancelled(true);
    }

    private boolean pistonBlocked(BlockPistonEvent e, List<Block> moved, boolean extending) {
        Block piston = e.getBlock();
        BlockFace dir = e.getDirection();
        return ReservedCells.pistonBlocked(xyz(piston), moved.stream().map(ReservedCellGuard::xyz).toList(),
                new int[]{dir.getModX(), dir.getModY(), dir.getModZ()}, extending,
                c -> furniture.reserved(piston.getWorld(), c[0], c[1], c[2]));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!(e.getBlock().getBlockData() instanceof Directional d)) return;
        Block target = e.getBlock().getRelative(d.getFacing());
        ItemStack item = e.getItem();
        if (ReservedCells.dispenseBlocked(item.getType().name(), item.getType().isBlock(), reserved(target))) e.setCancelled(true);
    }

    /** Snow, ice, concrete, obsidian, a snow golem's trail... (EntityBlockFormEvent comes through here too). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (reserved(e.getBlock())) e.setCancelled(true);
    }

    /** Fire, vines, mushrooms, sculk, amethyst... */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (reserved(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (reserved(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (reserved(e.getBlock())) e.setCancelled(true);
    }

    /** Trees and huge mushrooms: the parts that would go into a furniture are left out. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStructure(StructureGrowEvent e) {
        e.getBlocks().removeIf(s -> reserved(s.getBlock()));
    }

    /** Bone meal on grass or moss. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        e.getBlocks().removeIf(s -> reserved(s.getBlock()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(PortalCreateEvent e) {
        for (BlockState s : e.getBlocks()) {
            if (reserved(s.getBlock())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Endermen setting blocks down, sand or an anvil landing in the empty top of a low machine... */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (!ReservedCells.changeBlocked(reserved(e.getBlock()), e.getTo().isAir())) return;
        e.setCancelled(true);
        // a cancelled landing deletes the falling block: drop it instead, as when it lands on a torch
        if (e.getEntity() instanceof FallingBlock fb && fb.getDropItem()) {
            Material m = fb.getBlockData().getPlacementMaterial();
            if (m.isItem() && !m.isAir()) fb.getWorld().dropItemNaturally(fb.getLocation(), new ItemStack(m));
        }
    }

    /** Item frames and paintings hung on a barrier's side would sit inside the model. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent e) {
        if (reserved(e.getBlock().getRelative(e.getBlockFace()))) e.setCancelled(true);
    }

    /** Armor stands, boats, minecarts set down in a furniture's empty cell (inside the model). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (reserved(e.getEntity().getLocation().getBlock())) e.setCancelled(true);
    }

    /** No mob spawns in a furniture's empty cells (it would stand inside the model). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.SPAWNER
                && r != CreatureSpawnEvent.SpawnReason.PATROL && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        if (reserved(e.getLocation().getBlock())) e.setCancelled(true);
    }

    private static int[] xyz(Block b) {
        return new int[]{b.getX(), b.getY(), b.getZ()};
    }
}
