package dev.stsv.core.furniture;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.FurnitureSpec;
import dev.stsv.core.module.StsvModule;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Furniture = an ItemDisplay showing the custom item's model, plus per block cell:
 * an Interaction entity (click target) and optionally a barrier block (collision).
 *
 * Model space -> world: the model's north face (-Z) is its front. Placing turns the front towards the player.
 */
public final class FurnitureModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyItem, keyCells, keyParts, keyOwner;
    private final Map<String, FurnitureBehavior> behaviors = new HashMap<>();

    @Override
    public String id() {
        return "furniture";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keyItem = new NamespacedKey(plugin, "furniture");
        keyCells = new NamespacedKey(plugin, "furniture_cells");
        keyParts = new NamespacedKey(plugin, "furniture_parts");
        keyOwner = new NamespacedKey(plugin, "furniture_owner");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    public void registerBehavior(String id, FurnitureBehavior behavior) {
        behaviors.put(id, behavior);
    }

    // ------------------------------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------------------------------

    /** World offset of a model cell for a given entity yaw (degrees). */
    static int[] rotate(int[] cell, float yaw) {
        // The item display renderer turns models 180°, then the entity yaw rotates them further.
        double r = Math.toRadians(yaw);
        double x = -cell[0], z = -cell[2];
        double wx = x * Math.cos(r) - z * Math.sin(r);
        double wz = x * Math.sin(r) + z * Math.cos(r);
        return new int[]{(int) Math.round(wx), cell[1], (int) Math.round(wz)};
    }

    static float placementYaw(FurnitureSpec spec, float playerYaw) {
        float facePlayer = playerYaw + 180f;
        if (spec.rotation() == FurnitureSpec.Rotation.CARDINAL) {
            facePlayer = Math.round(facePlayer / 90f) * 90f;
        } else {
            facePlayer = Math.round(facePlayer / 15f) * 15f;
        }
        return ((facePlayer % 360f) + 360f) % 360f;
    }

    public Optional<Furniture> place(Player player, CustomItem item, Block anchor, Block against, EquipmentSlot hand) {
        FurnitureSpec spec = item.furniture();
        float yaw = placementYaw(spec, player.getYaw());
        World world = anchor.getWorld();

        List<Block> cells = new ArrayList<>();
        for (int[] c : spec.cells()) {
            int[] o = rotate(c, yaw);
            Block b = anchor.getRelative(o[0], o[1], o[2]);
            if (!(b.isEmpty() || b.isReplaceable()) || furnitureAt(b).isPresent()) return Optional.empty();
            if (b.getY() >= world.getMaxHeight() || b.getY() < world.getMinHeight()) return Optional.empty();
            // Ask protection plugins (WorldGuard, claims, season rules...) through a regular place event.
            BlockPlaceEvent check = new BlockPlaceEvent(b, b.getState(), against, player.getInventory().getItem(hand),
                    player, true, hand);
            Bukkit.getPluginManager().callEvent(check);
            if (check.isCancelled() || !check.canBuild()) return Optional.empty();
            cells.add(b);
        }

        Location loc = anchor.getLocation().add(0.5, 0.5, 0.5);
        // Safety knob: if a client version renders item displays without the 180° flip, set this to 180.
        // Occupied cells are unaffected (they only depend on the player's facing).
        loc.setYaw(yaw + (float) plugin.getConfig().getDouble("furniture.display-yaw-offset", 0));
        loc.setPitch(0);
        ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item.create(1));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(spec.translation(), new Quaternionf(), spec.scale(), new Quaternionf()));
            d.setViewRange((float) plugin.getConfig().getDouble("furniture.view-range", 1.0));
            if (spec.brightness() >= 0) d.setBrightness(new Display.Brightness(spec.brightness(), 15));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, item.key().asString());
        });

        Furniture furniture = new Furniture(display, item);
        FurniturePlaceEvent event = new FurniturePlaceEvent(player, furniture);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            display.remove();
            return Optional.empty();
        }

        StringBuilder cellData = new StringBuilder();
        StringBuilder partData = new StringBuilder();
        for (Block b : cells) {
            if (spec.solid()) b.setType(Material.BARRIER, false);
            Interaction hit = world.spawn(b.getLocation().add(0.5, -0.01, 0.5), Interaction.class, i -> {
                // a hair bigger than the barrier so the entity, not the block, is what the crosshair hits
                i.setInteractionWidth(1.02f);
                i.setInteractionHeight(1.02f);
                i.setResponsive(true);
                i.setPersistent(true);
                i.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, display.getUniqueId().toString());
            });
            cellData.append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ()).append(';');
            partData.append(hit.getUniqueId()).append(';');
        }
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        pdc.set(keyCells, PersistentDataType.STRING, cellData.toString());
        pdc.set(keyParts, PersistentDataType.STRING, partData.toString());

        world.playSound(loc, Sound.BLOCK_STONE_PLACE, 1f, 0.9f);
        behavior(item).ifPresent(b -> b.onPlace(player, furniture));
        return Optional.of(furniture);
    }

    // ------------------------------------------------------------------------------------------
    // Lookup & removal
    // ------------------------------------------------------------------------------------------

    public Optional<Furniture> furnitureAt(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Interaction i : block.getWorld().getNearbyEntitiesByType(Interaction.class, center, 0.6)) {
            Optional<Furniture> f = fromPart(i);
            if (f.isPresent()) return f;
        }
        return Optional.empty();
    }

    public Optional<Furniture> fromPart(Entity part) {
        String owner = part.getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
        if (owner == null) return Optional.empty();
        Entity e = Bukkit.getEntity(UUID.fromString(owner));
        if (!(e instanceof ItemDisplay display)) return Optional.empty();
        return fromDisplay(display);
    }

    public Optional<Furniture> fromDisplay(ItemDisplay display) {
        String id = display.getPersistentDataContainer().get(keyItem, PersistentDataType.STRING);
        if (id == null) return Optional.empty();
        return plugin.content().item(id).map(item -> new Furniture(display, item));
    }

    /** Removes the furniture and its helpers. Returns false if a listener cancelled it. */
    public boolean breakFurniture(Player player, Furniture furniture, boolean drop) {
        FurnitureBreakEvent event = new FurnitureBreakEvent(player, furniture);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        behavior(furniture.item()).ifPresent(b -> b.onBreak(player, furniture));

        ItemDisplay display = furniture.display();
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        World world = display.getWorld();
        for (String part : pdc.getOrDefault(keyParts, PersistentDataType.STRING, "").split(";")) {
            if (part.isBlank()) continue;
            Entity e = Bukkit.getEntity(UUID.fromString(part));
            if (e != null) e.remove();
        }
        for (String cell : pdc.getOrDefault(keyCells, PersistentDataType.STRING, "").split(";")) {
            String[] xyz = cell.split(",");
            if (xyz.length != 3) continue;
            Block b = world.getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
            if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        }
        Location loc = display.getLocation();
        if (drop) world.dropItemNaturally(loc, furniture.item().create(1));
        world.playSound(loc, Sound.BLOCK_STONE_BREAK, 1f, 0.9f);
        display.remove();
        return true;
    }

    private Optional<FurnitureBehavior> behavior(CustomItem item) {
        String id = item.furniture() == null ? null : item.furniture().behavior();
        return id == null ? Optional.empty() : Optional.ofNullable(behaviors.get(id));
    }

    private boolean shouldDrop(Player player) {
        return player.getGameMode() != GameMode.CREATIVE && plugin.getConfig().getBoolean("furniture.drop-on-break", true);
    }

    // ------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("deprecation") // isInteractable is approximate but has no replacement; good enough here
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() == null || e.getClickedBlock() == null) return;
        Player player = e.getPlayer();
        ItemStack hand = player.getInventory().getItem(e.getHand());
        Optional<CustomItem> item = plugin.content().itemOf(hand).filter(CustomItem::isFurniture);
        if (item.isEmpty()) return;

        Block clicked = e.getClickedBlock();
        if (clicked.getType().asBlockType().isInteractable() && !player.isSneaking()) return; // let chests, doors... open
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;

        Block anchor = clicked.isReplaceable() ? clicked : clicked.getRelative(e.getBlockFace());
        Block against = anchor == clicked ? clicked.getRelative(BlockFace.DOWN) : clicked;
        if (place(player, item.get(), anchor, against, e.getHand()).isPresent()) {
            if (player.getGameMode() != GameMode.CREATIVE) hand.subtract(1);
            player.swingHand(e.getHand());
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction part)) return;
        Optional<Furniture> f = fromPart(part);
        if (f.isEmpty()) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        FurnitureInteractEvent event = new FurnitureInteractEvent(e.getPlayer(), f.get());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) behavior(f.get().item()).ifPresent(b -> b.onInteract(e.getPlayer(), f.get()));
    }

    @EventHandler
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (!(e.getAttacked() instanceof Interaction part)) return;
        Optional<Furniture> f = fromPart(part);
        if (f.isEmpty()) return;
        e.setCancelled(true);
        breakFurniture(e.getPlayer(), f.get(), shouldDrop(e.getPlayer()));
    }

    /** Creative players can break the barrier blocks directly. */
    @EventHandler(ignoreCancelled = true)
    public void onBreakBarrier(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.BARRIER) return;
        Optional<Furniture> f = furnitureAt(e.getBlock());
        if (f.isEmpty()) return;
        e.setCancelled(true);
        breakFurniture(e.getPlayer(), f.get(), shouldDrop(e.getPlayer()));
    }
}
