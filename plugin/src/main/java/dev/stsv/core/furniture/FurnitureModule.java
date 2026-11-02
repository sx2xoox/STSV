package dev.stsv.core.furniture;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.FurnitureSpec;
import dev.stsv.core.module.StsvModule;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
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
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Furniture = an ItemDisplay showing the custom item's model, plus per block cell:
 * an Interaction entity (click target) and optionally a barrier block (collision).
 *
 * Also handles: a ghost preview while holding furniture, and sitting on furniture with seats.
 *
 * Model space -> world: the model's north face (-Z) is its front. Placing turns the front towards the player.
 */
public final class FurnitureModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyItem, keyCells, keyParts, keyOwner, keyYaw, keySeat;
    private final Map<String, FurnitureBehavior> behaviors = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private BukkitTask previewTask;

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
        keyYaw = new NamespacedKey(plugin, "furniture_yaw");
        keySeat = new NamespacedKey(plugin, "seat_of");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        previewTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPreviews, 1L, 2L);
    }

    @Override
    public void disable() {
        if (previewTask != null) previewTask.cancel();
        new ArrayList<>(previews.keySet()).forEach(this::clearPreview);
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(keySeat)) {
                    d.eject();
                    d.remove();
                }
            }
        }
        HandlerList.unregisterAll(this);
    }

    public void registerBehavior(String id, FurnitureBehavior behavior) {
        behaviors.put(id, behavior);
    }

    // ------------------------------------------------------------------------------------------
    // Placement maths (shared by real placement and the preview, so the preview never lies)
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

    /** Where the furniture would go and whether it physically fits (protection plugins are asked on placement). */
    public record Plan(Block anchor, float yaw, List<Block> cells, boolean fits) {}

    public Plan plan(Player player, FurnitureSpec spec, Block anchor) {
        float yaw = placementYaw(spec, player.getYaw());
        World world = anchor.getWorld();
        List<Block> cells = new ArrayList<>();
        boolean fits = true;
        for (int[] c : spec.cells()) {
            int[] o = rotate(c, yaw);
            Block b = anchor.getRelative(o[0], o[1], o[2]);
            if (b.getY() >= world.getMaxHeight() || b.getY() < world.getMinHeight()) fits = false;
            else if (!(b.isEmpty() || b.isReplaceable()) || furnitureAt(b).isPresent()) fits = false;
            cells.add(b);
        }
        return new Plan(anchor, yaw, cells, fits);
    }

    private float displayYaw(float yaw) {
        // Safety knob: if a client version renders item displays without the 180° flip, set this to 180.
        return yaw + (float) plugin.getConfig().getDouble("furniture.display-yaw-offset", 0);
    }

    private void applyModel(ItemDisplay d, CustomItem item) {
        FurnitureSpec spec = item.furniture();
        d.setItemStack(item.create(1));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        d.setTransformation(new Transformation(spec.translation(), new Quaternionf(), spec.scale(), new Quaternionf()));
    }

    public Optional<Furniture> place(Player player, CustomItem item, Block anchor, Block against, EquipmentSlot hand) {
        FurnitureSpec spec = item.furniture();
        Plan plan = plan(player, spec, anchor);
        if (!plan.fits()) return Optional.empty();
        for (Block b : plan.cells()) {
            // Ask protection plugins (WorldGuard, claims, season rules...) through a regular place event.
            BlockPlaceEvent check = new BlockPlaceEvent(b, b.getState(), against, player.getInventory().getItem(hand),
                    player, true, hand);
            Bukkit.getPluginManager().callEvent(check);
            if (check.isCancelled() || !check.canBuild()) return Optional.empty();
        }

        World world = anchor.getWorld();
        Location loc = anchor.getLocation().add(0.5, 0.5, 0.5);
        loc.setYaw(displayYaw(plan.yaw()));
        loc.setPitch(0);
        ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
            applyModel(d, item);
            d.setViewRange((float) plugin.getConfig().getDouble("furniture.view-range", 1.0));
            if (spec.brightness() >= 0) d.setBrightness(new Display.Brightness(spec.brightness(), 15));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(keyItem, PersistentDataType.STRING, item.key().asString());
            d.getPersistentDataContainer().set(keyYaw, PersistentDataType.FLOAT, plan.yaw());
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
        for (Block b : plan.cells()) {
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

    /** Placement yaw of a placed piece (the direction its front faces). */
    private float yawOf(Furniture f) {
        Float stored = f.data().get(keyYaw, PersistentDataType.FLOAT);
        if (stored != null) return stored;
        return f.display().getYaw() - (float) plugin.getConfig().getDouble("furniture.display-yaw-offset", 0);
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
        for (ItemDisplay seat : seatsOf(display)) {
            seat.eject();
            seat.remove();
        }
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
    // Sitting
    // ------------------------------------------------------------------------------------------

    private List<ItemDisplay> seatsOf(ItemDisplay furniture) {
        String owner = furniture.getUniqueId().toString();
        List<ItemDisplay> out = new ArrayList<>();
        for (ItemDisplay d : furniture.getWorld().getNearbyEntitiesByType(ItemDisplay.class, furniture.getLocation(), 4)) {
            if (owner.equals(d.getPersistentDataContainer().get(keySeat, PersistentDataType.STRING))) out.add(d);
        }
        return out;
    }

    /** Sits the player on the seat closest to where they clicked. Returns false if there is no free seat. */
    public boolean sit(Player player, Furniture furniture, Location clicked) {
        FurnitureSpec spec = furniture.item().furniture();
        if (!spec.sittable() || player.isInsideVehicle()) return false;
        float yaw = yawOf(furniture);
        Block anchor = furniture.display().getLocation().getBlock();

        Location best = null;
        for (FurnitureSpec.Seat seat : spec.seats()) {
            int[] o = rotate(seat.cell(), yaw);
            Block cell = anchor.getRelative(o[0], o[1], o[2]);
            Location at = cell.getLocation().add(0.5, seat.height(), 0.5);
            boolean taken = at.getWorld().getNearbyEntitiesByType(ItemDisplay.class, at, 0.2).stream()
                    .anyMatch(d -> d.getPersistentDataContainer().has(keySeat) && !d.getPassengers().isEmpty());
            if (taken) continue;
            if (best == null || at.distanceSquared(clicked) < best.distanceSquared(clicked)) best = at;
        }
        if (best == null) return false;

        best.setYaw(yaw);
        best.setPitch(0);
        String owner = furniture.display().getUniqueId().toString();
        ItemDisplay seat = best.getWorld().spawn(best, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(keySeat, PersistentDataType.STRING, owner);
        });
        player.setRotation(yaw, player.getPitch()); // face where the furniture faces
        seat.addPassenger(player);
        return true;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getDismounted() instanceof ItemDisplay seat) || !seat.getPersistentDataContainer().has(keySeat)) return;
        Entity rider = e.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location exit = exitLocation(seat, rider.getLocation().getYaw());
            seat.remove();
            if (rider.isValid()) rider.teleport(exit);
        });
    }

    /** Step out in front of the furniture; if that is blocked, stand on top of the seat block. */
    private Location exitLocation(ItemDisplay seat, float riderYaw) {
        Block cell = seat.getLocation().getBlock();
        double r = Math.toRadians(seat.getYaw());
        Block front = cell.getRelative((int) Math.round(-Math.sin(r)), 0, (int) Math.round(Math.cos(r)));
        Location out;
        if (front.isPassable() && front.getRelative(BlockFace.UP).isPassable()) {
            out = front.getLocation().add(0.5, 0, 0.5);
        } else {
            out = cell.getLocation().add(0.5, 1, 0.5);
        }
        out.setYaw(riderYaw);
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        clearPreview(e.getPlayer().getUniqueId());
        Entity vehicle = e.getPlayer().getVehicle();
        if (vehicle instanceof ItemDisplay seat && seat.getPersistentDataContainer().has(keySeat)) {
            seat.eject();
            seat.remove();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Placement preview (only visible to the player holding the furniture)
    // ------------------------------------------------------------------------------------------

    private static final class Preview {
        NamespacedKey item;
        ItemDisplay ghost;
        final List<BlockDisplay> floor = new ArrayList<>();
        Boolean fits;
    }

    private static final Color OK = Color.fromRGB(0x7DE07D);
    private static final Color BLOCKED = Color.fromRGB(0xFF5A5A);

    private Optional<CustomItem> heldFurniture(Player p) {
        Optional<CustomItem> main = plugin.content().itemOf(p.getInventory().getItemInMainHand()).filter(CustomItem::isFurniture);
        return main.isPresent() ? main
                : plugin.content().itemOf(p.getInventory().getItemInOffHand()).filter(CustomItem::isFurniture);
    }

    private double reach(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        return a == null ? 4.5 : a.getValue();
    }

    private void tickPreviews() {
        if (!plugin.getConfig().getBoolean("furniture.preview", true)) {
            new ArrayList<>(previews.keySet()).forEach(this::clearPreview);
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Optional<CustomItem> held = heldFurniture(p);
            if (held.isEmpty() || p.isInsideVehicle()) continue;
            RayTraceResult hit = p.rayTraceBlocks(reach(p), FluidCollisionMode.NEVER);
            if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) continue;
            Block clicked = hit.getHitBlock();
            Block anchor = clicked.isReplaceable() ? clicked : clicked.getRelative(hit.getHitBlockFace());
            showPreview(p, held.get(), plan(p, held.get().furniture(), anchor));
            seen.add(p.getUniqueId());
        }
        for (UUID id : new ArrayList<>(previews.keySet())) {
            if (!seen.contains(id)) clearPreview(id);
        }
    }

    private void showPreview(Player p, CustomItem item, Plan plan) {
        Preview pv = previews.computeIfAbsent(p.getUniqueId(), k -> new Preview());
        World world = plan.anchor().getWorld();
        if (!item.key().equals(pv.item) || pv.ghost == null || !pv.ghost.isValid() || pv.ghost.getWorld() != world) {
            clearPreview(p.getUniqueId());
            pv = new Preview();
            pv.item = item.key();
            previews.put(p.getUniqueId(), pv);
        }

        Location loc = plan.anchor().getLocation().add(0.5, 0.5, 0.5);
        loc.setYaw(displayYaw(plan.yaw()));
        if (pv.ghost == null) {
            pv.ghost = world.spawn(loc, ItemDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                applyModel(d, item);
                d.setGlowing(true);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(2);
            });
            p.showEntity(plugin, pv.ghost);
        } else {
            pv.ghost.teleport(loc);
        }

        // footprint plates under every cell that has no other cell directly below it
        List<Block> floor = new ArrayList<>();
        for (Block b : plan.cells()) {
            if (!plan.cells().contains(b.getRelative(BlockFace.DOWN))) floor.add(b);
        }
        while (pv.floor.size() < floor.size()) {
            BlockDisplay plate = world.spawn(floor.get(pv.floor.size()).getLocation(), BlockDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setPersistent(false);
                d.setTransformation(new Transformation(new Vector3f(0.03f, 0.01f, 0.03f), new Quaternionf(),
                        new Vector3f(0.94f, 0.04f, 0.94f), new Quaternionf()));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(2);
            });
            p.showEntity(plugin, plate);
            pv.floor.add(plate);
        }
        for (int i = 0; i < pv.floor.size(); i++) {
            pv.floor.get(i).teleport(floor.get(Math.min(i, floor.size() - 1)).getLocation());
        }

        if (!Boolean.valueOf(plan.fits()).equals(pv.fits)) {
            pv.fits = plan.fits();
            pv.ghost.setGlowColorOverride(plan.fits() ? OK : BLOCKED);
            BlockData glass = (plan.fits() ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS).createBlockData();
            pv.floor.forEach(f -> f.setBlock(glass));
        }
    }

    private void clearPreview(UUID player) {
        Preview pv = previews.remove(player);
        if (pv == null) return;
        if (pv.ghost != null) pv.ghost.remove();
        pv.floor.forEach(Entity::remove);
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
        Player player = e.getPlayer();
        FurnitureInteractEvent event = new FurnitureInteractEvent(player, f.get());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        // holding furniture = the player wants to place next to it, not to sit
        boolean holdingFurniture = heldFurniture(player).isPresent();
        if (f.get().item().furniture().sittable() && !player.isSneaking() && !holdingFurniture) {
            sit(player, f.get(), part.getLocation());
            return;
        }
        behavior(f.get().item()).ifPresent(b -> b.onInteract(player, f.get()));
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
