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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Furniture = an ItemDisplay showing the custom item's model, over a set of block cells (its footprint), plus:
 * <ul>
 *   <li>click targets: a few Interaction entities fitted to the model it shows ({@link FurnitureShape}: the
 *       model voxelized at 1/4 block, covered by at most 6 boxes, never outside the cells). Furniture without
 *       model geometry is clicked over its whole cells, as before.</li>
 *   <li>collision (if solid): a barrier block in each cell the model fills at least furniture.barrier-fill.
 *       The other cells stay air but belong to the piece (ReservedCellGuard keeps everything out of them).</li>
 * </ul>
 * Both follow the model shown: placing, a new model (machine upgrade: {@link #setModel}) and loading a piece
 * placed before (or with older shapes) bring them up to date; shapes are worked out once per model.
 *
 * Also handles: a ghost preview while holding furniture, and sitting on furniture with seats.
 *
 * Model space -> world: the model's north face (-Z) is its front. Placing turns the front towards the player.
 */
public final class FurnitureModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyItem, keyCells, keyParts, keyOwner, keyYaw, keySeat, keySpin, keySpinOff, keyShapeBoxes, keyShapeBarriers;
    private boolean active;
    /** fitted shapes, per item + models shown (+ settings); emptied when the resource pack is rebuilt */
    private final Map<String, FurnitureShape.Shape> shapes = new HashMap<>();
    private final Map<String, Optional<dev.stsv.core.pack.PackBuilder.Model>> resolved = new HashMap<>();
    private final Set<String> shapeFailures = new HashSet<>();
    private final ReservedCells reserved = new ReservedCells();
    private ReservedCellGuard guard;
    /** furniture (display id) whose click boxes / barriers must be checked, and the tick to do it at */
    private final Map<UUID, Long> refreshAt = new LinkedHashMap<>();
    private long refreshTick;
    private BukkitTask refreshTask;
    private BukkitTask spinTask;
    private final Map<String, FurnitureBehavior> behaviors = new HashMap<>();
    private final Map<UUID, Preview> previews = new HashMap<>();
    private final Map<UUID, Long> lastPlace = new HashMap<>();
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
        keySpin = new NamespacedKey(plugin, "spin");
        keySpinOff = new NamespacedKey(plugin, "spin_off");
        keyShapeBoxes = new NamespacedKey(plugin, "furniture_shape_boxes");
        keyShapeBarriers = new NamespacedKey(plugin, "furniture_shape_barriers");
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(guard = new ReservedCellGuard(this), plugin);
        previewTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPreviews, 1L, 2L);
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                track(d);
                loaded(d);
            }
        }
        spinTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpin, 1L, SPIN_STEP);
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRefresh, 1L, 1L);
    }

    @Override
    public void disable() {
        active = false;
        if (previewTask != null) previewTask.cancel();
        if (spinTask != null) spinTask.cancel();
        if (refreshTask != null) refreshTask.cancel();
        if (guard != null) HandlerList.unregisterAll(guard);
        reserved.clear();
        refreshAt.clear();
        shapes.clear();
        resolved.clear();
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

    /**
     * Where the furniture would go and whether it physically fits (protection plugins are asked on placement).
     * shape: its click boxes and barrier cells there. It does not fit where a barrier would go into a player or
     * mob (as a block cannot be placed inside one); a cell without a barrier may have someone standing in it.
     */
    public record Plan(Block anchor, float yaw, List<Block> cells, boolean fits, Placed shape) {}

    public Plan plan(Player player, CustomItem item, Block anchor) {
        FurnitureSpec spec = item.furniture();
        float yaw = placementYaw(spec, player.getYaw());
        World world = anchor.getWorld();
        List<Block> cells = new ArrayList<>();
        boolean fits = true;
        for (int[] c : spec.cells()) {
            int[] o = rotate(c, yaw);
            Block b = anchor.getRelative(o[0], o[1], o[2]);
            if (b.getY() >= world.getMaxHeight() || b.getY() < world.getMinHeight()) fits = false;
            else if (!(b.isEmpty() || b.isReplaceable()) || reserved(b) || furnitureAt(b).isPresent()) fits = false;
            cells.add(b);
        }
        Placed shape = placed(spec, shape(item, item.itemModelKey(), List.of()), anchor, yaw, cells);
        if (fits && obstructed(shape.barriers())) fits = false;
        return new Plan(anchor, yaw, cells, fits, shape);
    }

    /** A player, mob, armor stand, boat or minecart is (partly) inside this block. */
    private boolean obstructed(Block b) {
        return obstructed(Set.of(b));
    }

    private boolean obstructed(Set<Block> blocks) {
        if (blocks.isEmpty()) return false;
        BoundingBox all = null;
        for (Block b : blocks) all = all == null ? BoundingBox.of(b) : all.union(BoundingBox.of(b));
        for (Entity e : blocks.iterator().next().getWorld().getNearbyEntities(all, FurnitureModule::blocksBuilding)) {
            for (Block b : blocks) if (BoundingBox.of(b).overlaps(e.getBoundingBox())) return true;
        }
        return false;
    }

    private static boolean blocksBuilding(Entity e) {
        if (!e.isValid()) return false;
        if (e instanceof Player p) return p.getGameMode() != GameMode.SPECTATOR;
        if (e instanceof ArmorStand a) return !a.isMarker();
        return e instanceof LivingEntity || e instanceof Vehicle;
    }

    private float displayYaw(float yaw) {
        // Safety knob: if a client version renders item displays without the 180° flip, set this to 180.
        return yaw + (float) plugin.getConfig().getDouble("furniture.display-yaw-offset");
    }

    private void applyModel(ItemDisplay d, CustomItem item) {
        FurnitureSpec spec = item.furniture();
        d.setItemStack(item.create(1));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        d.setTransformation(new Transformation(spec.translation(), new Quaternionf(), spec.scale(), new Quaternionf()));
    }

    public Optional<Furniture> place(Player player, CustomItem item, Block anchor, Block against, EquipmentSlot hand) {
        FurnitureSpec spec = item.furniture();
        Plan plan = plan(player, item, anchor);
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
            d.setViewRange((float) plugin.getConfig().getDouble("furniture.view-range"));
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
            // collision only where the model is solid enough; the other cells are cleared (grass, snow, water...)
            if (spec.solid()) b.setType(plan.shape().barriers().contains(b) ? Material.BARRIER : Material.AIR, false);
            cellData.append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ()).append(';');
        }
        // click targets: a few interaction entities fitted to the model, never outside the cells
        for (double[] box : plan.shape().boxes()) partData.append(spawnHit(world, box, display.getUniqueId()).getUniqueId()).append(';');
        for (FurnitureSpec.Part part : spec.parts()) {
            ItemDisplay pe = spawnPart(display, plan, part);
            partData.append(pe.getUniqueId()).append(';');
        }
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        pdc.set(keyCells, PersistentDataType.STRING, cellData.toString());
        pdc.set(keyParts, PersistentDataType.STRING, partData.toString());
        pdc.set(keyShapeBoxes, PersistentDataType.STRING, plan.shape().hash());
        pdc.set(keyShapeBarriers, PersistentDataType.STRING, plan.shape().hash());
        loaded(display);

        world.playSound(loc, Sound.BLOCK_STONE_PLACE, 1f, 0.9f);
        ItemStack from = player.getInventory().getItem(hand);
        behavior(item).ifPresent(b -> b.onPlace(player, furniture, from));
        return Optional.of(furniture);
    }

    /** A click target over the world box {minX, minY, minZ, size, height}. */
    private Interaction spawnHit(World world, double[] box, UUID owner) {
        double size = box[3], height = box[4];
        return world.spawn(new Location(world, box[0] + size / 2, box[1] - 0.01, box[2] + size / 2), Interaction.class, i -> {
            // a hair bigger than the box (and the barriers) so the entity, not a block, is what the crosshair hits
            i.setInteractionWidth((float) size + 0.02f);
            i.setInteractionHeight((float) height + 0.02f);
            i.setResponsive(true);
            i.setPersistent(true);
            i.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, owner.toString());
        });
    }

    /**
     * Greedy cover of the occupied blocks with square-based prisms [x, y, z, size, height]
     * (interaction entities are axis-aligned with a square base). Every prism lies fully inside the
     * cell set, so the click area is exactly the furniture's footprint.
     */
    static List<int[]> coverWithPrisms(List<int[]> cells) {
        Set<Long> todo = new HashSet<>();
        for (int[] c : cells) todo.add(pack(c[0], c[1], c[2]));
        List<int[]> order = new ArrayList<>(cells);
        order.sort(java.util.Comparator.<int[]>comparingInt(c -> c[1]).thenComparingInt(c -> c[0]).thenComparingInt(c -> c[2]));
        List<int[]> out = new ArrayList<>();
        for (int[] c : order) {
            int x = c[0], y = c[1], z = c[2];
            if (!todo.contains(pack(x, y, z))) continue;
            int k = 1;
            while (k < 4 && square(todo, x, y, z, k + 1)) k++;
            int h = 1;
            while (h < 8 && square(todo, x, y + h, z, k)) h++;
            for (int dy = 0; dy < h; dy++)
                for (int dx = 0; dx < k; dx++)
                    for (int dz = 0; dz < k; dz++) todo.remove(pack(x + dx, y + dy, z + dz));
            out.add(new int[]{x, y, z, k, h});
        }
        return out;
    }

    private static boolean square(Set<Long> todo, int x, int y, int z, int k) {
        for (int dx = 0; dx < k; dx++)
            for (int dz = 0; dz < k; dz++) if (!todo.contains(pack(x + dx, y, z + dz))) return false;
        return true;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Model-space position (16 = 1 block, cell (0,0,0) spans 0..16) -> world offset from the anchor block centre. */
    static double[] modelToWorld(org.joml.Vector3f p, float yaw) {
        double x = (p.x - 8) / 16.0, y = (p.y - 8) / 16.0, z = (p.z - 8) / 16.0;
        double r = Math.toRadians(yaw);
        double mx = -x, mz = -z; // same 180° flip as the cells (see rotate)
        return new double[]{mx * Math.cos(r) - mz * Math.sin(r), y, mx * Math.sin(r) + mz * Math.cos(r)};
    }

    private ItemDisplay spawnPart(ItemDisplay owner, Plan plan, FurnitureSpec.Part part) {
        double[] o = modelToWorld(part.position(), plan.yaw());
        Location loc = plan.anchor().getLocation().add(0.5 + o[0], 0.5 + o[1], 0.5 + o[2]);
        loc.setYaw(displayYaw(plan.yaw()));
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER);
            it.editMeta(m -> m.setItemModel(part.model()));
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setViewRange((float) plugin.getConfig().getDouble("furniture.view-range"));
            d.setPersistent(true);
            if (part.spinAxis() != null && part.rpm() != 0) {
                d.getPersistentDataContainer().set(keySpin, PersistentDataType.STRING, part.spinAxis() + ":" + part.rpm());
            }
        });
    }

    // spinning parts: the server only sends a new rotation every few ticks; the client interpolates
    private final Set<UUID> spinning = new HashSet<>();
    private static final int SPIN_STEP = 5;

    private void tickSpin() {
        if (spinning.isEmpty()) return;
        for (java.util.Iterator<UUID> it = spinning.iterator(); it.hasNext(); ) {
            Entity e = Bukkit.getEntity(it.next());
            if (!(e instanceof ItemDisplay d) || !d.isValid()) {
                it.remove();
                continue;
            }
            String spec = d.getPersistentDataContainer().get(keySpin, PersistentDataType.STRING);
            if (spec == null) {
                it.remove();
                continue;
            }
            String[] a = spec.split(":");
            float rpm = Float.parseFloat(a[1]);
            // angle from world time. A quaternion repeats every 4π (not 2π), so wrapping at 4π keeps
            // consecutive rotations continuous (smooth interpolation) while staying precise as a float.
            double turns = (d.getWorld().getGameTime() + SPIN_STEP) * (double) rpm / 1200.0;
            double angle = ((turns % 2.0) + 2.0) % 2.0 * 2 * Math.PI;
            Quaternionf q = switch (a[0]) {
                case "x" -> new Quaternionf().rotateX((float) angle);
                case "z" -> new Quaternionf().rotateZ((float) angle);
                default -> new Quaternionf().rotateY((float) angle);
            };
            Transformation t = d.getTransformation();
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(SPIN_STEP);
            d.setTransformation(new Transformation(t.getTranslation(), q, t.getScale(), t.getRightRotation()));
        }
    }

    private void track(Entity e) {
        if (e instanceof ItemDisplay d && d.getPersistentDataContainer().has(keySpin)
                && !d.getPersistentDataContainer().has(keySpinOff)) spinning.add(d.getUniqueId());
    }

    @EventHandler
    public void onEntityAdd(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent e) {
        track(e.getEntity());
        if (e.getEntity() instanceof ItemDisplay d) loaded(d);
    }

    @EventHandler
    public void onEntityRemove(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent e) {
        spinning.remove(e.getEntity().getUniqueId());
        reserved.remove(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // Fitted shape: click boxes and barrier cells that follow the model shown
    // ------------------------------------------------------------------------------------------

    /**
     * A piece's click boxes {minX, minY, minZ, size, height} and barrier blocks in the world.
     * hash: what was applied (stored on the piece; a different one means boxes / barriers must be redone).
     */
    public record Placed(List<double[]> boxes, Set<Block> barriers, String hash) {}

    /**
     * The model-space shape of an item showing these models (item model ids; null = the item's own / its
     * parts' own). Worked out once per combination of models and cached until the resource pack is rebuilt.
     * Without model geometry (no pack yet, a 2D or vanilla model): the full cells, as before.
     */
    FurnitureShape.Shape shape(CustomItem item, NamespacedKey main, List<NamespacedKey> partModels) {
        FurnitureSpec spec = item.furniture();
        dev.stsv.core.pack.PackBuilder.Built built = plugin.pack() == null ? null : plugin.pack().current();
        dev.stsv.core.pack.PackBuilder.Model model = built == null || main == null ? null : resolve(built, main);
        if (model == null) {
            if (built == null) return FurnitureShape.fullCells(spec.cells(), spec.solid());
            return shapes.computeIfAbsent(item.key().asString() + "|full", k -> FurnitureShape.fullCells(spec.cells(), spec.solid()));
        }
        double minFill = plugin.getConfig().getDouble("furniture.barrier-fill");
        double yawOffset = plugin.getConfig().getDouble("furniture.display-yaw-offset");
        StringBuilder key = new StringBuilder(item.key().asString()).append('|').append(model.id());
        List<FurnitureShape.PartModel> parts = new ArrayList<>();
        for (int i = 0; i < spec.parts().size(); i++) {
            FurnitureSpec.Part part = spec.parts().get(i);
            NamespacedKey k = i < partModels.size() && partModels.get(i) != null ? partModels.get(i) : part.model();
            dev.stsv.core.pack.PackBuilder.Model pm = resolve(built, k);
            key.append('|').append(pm == null ? "-" : pm.id());
            String spin = part.spinAxis() != null && part.rpm() != 0 ? part.spinAxis() : null;
            parts.add(new FurnitureShape.PartModel(pm == null ? null : pm.json(), part.position(), spin));
        }
        key.append('|').append(minFill).append('|').append(yawOffset);
        FurnitureShape.Shape cached = shapes.get(key.toString());
        if (cached != null) return cached;
        FurnitureShape.Shape shape;
        try {
            FurnitureShape.Voxels v = FurnitureShape.voxelize(spec.cells(), model.json(), spec.translation(), spec.scale(), parts, yawOffset);
            shape = FurnitureShape.shape(v, spec.cells(), minFill, spec.solid());
        } catch (RuntimeException e) {
            if (shapeFailures.add(key.toString())) {
                plugin.getLogger().warning("가구 판정을 모델에서 계산하지 못해 칸 전체를 씁니다 (" + item.key() + ", " + model.id() + "): " + e);
            }
            shape = FurnitureShape.fullCells(spec.cells(), spec.solid());
        }
        shapes.put(key.toString(), shape);
        return shape;
    }

    private dev.stsv.core.pack.PackBuilder.Model resolve(dev.stsv.core.pack.PackBuilder.Built built, NamespacedKey itemModel) {
        return resolved.computeIfAbsent(itemModel.asString(), k -> Optional.ofNullable(built.itemModel(k))).orElse(null);
    }

    /** The shape of a placed piece, from the models it shows now (machine tier) and its parts'. */
    private FurnitureShape.Shape shapeOf(Furniture f) {
        ItemStack shown = f.display().getItemStack();
        NamespacedKey main = shown.hasItemMeta() ? shown.getItemMeta().getItemModel() : null;
        if (main == null) main = f.item().itemModelKey();
        List<NamespacedKey> partModels = new ArrayList<>();
        for (ItemDisplay part : parts(f)) {
            ItemStack it = part.getItemStack();
            partModels.add(it.hasItemMeta() ? it.getItemMeta().getItemModel() : null);
        }
        return shape(f.item(), main, partModels);
    }

    /**
     * A model-space shape at a placed position. Click boxes turn with the piece (any multiple of 90 degrees maps
     * squares onto squares); at an in-between angle (free rotation) it falls back to the full cells.
     */
    Placed placed(FurnitureSpec spec, FurnitureShape.Shape shape, Block anchor, float yaw, List<Block> cells) {
        boolean cardinal = Math.abs(yaw / 90f - Math.round(yaw / 90f)) < 1e-3;
        List<double[]> boxes = new ArrayList<>();
        Set<Block> barriers = new HashSet<>();
        if (!shape.fitted() || !cardinal) {
            for (int[] p : coverWithPrisms(cells.stream().map(b -> new int[]{b.getX(), b.getY(), b.getZ()}).toList())) {
                boxes.add(new double[]{p[0], p[1], p[2], p[3], p[4]});
            }
            if (spec.solid()) barriers.addAll(cells);
            return new Placed(boxes, barriers, "full:" + (spec.solid() ? "s" : "n") + cells.size());
        }
        for (FurnitureShape.Box b : shape.boxes()) {
            double[] o = boxOffset(b, yaw);
            boxes.add(new double[]{anchor.getX() + o[0], anchor.getY() + o[1], anchor.getZ() + o[2], o[3], o[4]});
        }
        Set<Block> inCells = new HashSet<>(cells);
        for (int[] c : shape.barriers()) {
            int[] o = rotate(c, yaw);
            Block b = anchor.getRelative(o[0], o[1], o[2]);
            if (inCells.contains(b)) barriers.add(b);
        }
        return new Placed(boxes, barriers, shape.hash());
    }

    /**
     * A model-space click box at a cardinal yaw: {minX, minY, minZ, size, height} in blocks from the anchor block's
     * corner. Same turn as the cells ({@link #rotate}) and the model ({@link #modelToWorld}).
     */
    static double[] boxOffset(FurnitureShape.Box b, float yaw) {
        double[] p = modelToWorld(new Vector3f((float) b.x0(), (float) b.y0(), (float) b.z0()), yaw);
        double[] q = modelToWorld(new Vector3f((float) b.x1(), (float) b.y1(), (float) b.z1()), yaw);
        return new double[]{round(0.5 + Math.min(p[0], q[0])), round(0.5 + Math.min(p[1], q[1])), round(0.5 + Math.min(p[2], q[2])),
                b.size() / 16.0, (b.y1() - b.y0()) / 16.0};
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    /** The resource pack was (re)built: shapes are worked out again from its models, and every loaded piece checked. */
    public void modelsChanged() {
        if (!active) return;
        shapes.clear();
        resolved.clear();
        shapeFailures.clear();
        // every furniture's own model now; other models it may show (machine tiers) the first time one is shown
        for (CustomItem item : plugin.content().items()) {
            if (item.isFurniture()) shape(item, item.itemModelKey(), List.of());
        }
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(keyCells)) refreshLater(d.getUniqueId(), 1);
            }
        }
    }

    private void refreshLater(UUID display, long ticks) {
        refreshAt.merge(display, refreshTick + ticks, Math::min);
    }

    private void tickRefresh() {
        refreshTick++;
        if (refreshAt.isEmpty()) return;
        int budget = 20; // pieces per tick, so a big reload is spread out
        List<UUID> due = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : refreshAt.entrySet()) {
            if (e.getValue() <= refreshTick) due.add(e.getKey());
            if (due.size() >= budget) break;
        }
        for (UUID id : due) {
            refreshAt.remove(id);
            if (!(Bukkit.getEntity(id) instanceof ItemDisplay d) || !d.isValid()) continue; // unloaded: checked again on load
            if (!refresh(d)) refreshLater(id, 20); // chunks not ready, or someone stands where a barrier goes
        }
    }

    /**
     * Brings a placed piece's click boxes and barriers in line with the models it shows now: after an upgrade
     * (new model), for pieces placed before click boxes were fitted (full-cell boxes and barriers, migrated once),
     * and after the models or furniture.barrier-fill changed. Returns false to try again a little later.
     */
    private boolean refresh(ItemDisplay d) {
        if (plugin.pack() == null || plugin.pack().current() == null) return true; // modelsChanged() checks again
        Optional<Furniture> of = fromDisplay(d);
        if (of.isEmpty() || of.get().item().furniture() == null) return true;
        Furniture f = of.get();
        FurnitureSpec spec = f.item().furniture();
        List<Block> cells = storedCells(d);
        if (cells.isEmpty()) return true;
        Placed want = placed(spec, shapeOf(f), d.getLocation().getBlock(), yawOf(f), cells);
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        boolean boxesOk = want.hash().equals(pdc.get(keyShapeBoxes, PersistentDataType.STRING));
        boolean barriersOk = want.hash().equals(pdc.get(keyShapeBarriers, PersistentDataType.STRING));
        if (boxesOk && barriersOk) return true;
        if (!chunksReady(cells)) return false; // never load chunks or lose track of entities in unloaded ones
        if (!boxesOk) {
            respawnBoxes(d, want, cells);
            pdc.set(keyShapeBoxes, PersistentDataType.STRING, want.hash());
        }
        if (!barriersOk) {
            if (!applyBarriers(spec, want, cells)) return false;
            pdc.set(keyShapeBarriers, PersistentDataType.STRING, want.hash());
        }
        return true;
    }

    private List<Block> storedCells(ItemDisplay d) {
        List<Block> out = new ArrayList<>();
        for (String cell : d.getPersistentDataContainer().getOrDefault(keyCells, PersistentDataType.STRING, "").split(";")) {
            String[] xyz = cell.split(",");
            if (xyz.length != 3) continue;
            out.add(d.getWorld().getBlockAt(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
        }
        return out;
    }

    private static boolean chunksReady(List<Block> cells) {
        World w = cells.getFirst().getWorld();
        Set<Long> seen = new HashSet<>();
        for (Block b : cells) {
            int cx = b.getX() >> 4, cz = b.getZ() >> 4;
            if (!seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))) continue;
            if (!w.isChunkLoaded(cx, cz) || !w.getChunkAt(cx, cz).isEntitiesLoaded()) return false;
        }
        return true;
    }

    /** Replaces the piece's click targets (old full-cell ones included); its part models stay, in order. */
    private void respawnBoxes(ItemDisplay d, Placed want, List<Block> cells) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        String owner = d.getUniqueId().toString();
        List<String> keep = new ArrayList<>();
        for (String id : pdc.getOrDefault(keyParts, PersistentDataType.STRING, "").split(";")) {
            if (id.isBlank()) continue;
            Entity e = Bukkit.getEntity(UUID.fromString(id));
            if (e instanceof Interaction) e.remove();
            else keep.add(id); // part models (or an entity long gone: harmless)
        }
        // strays: click targets of this piece its list lost track of (they always lie within its cells)
        BoundingBox area = BoundingBox.of(cells.getFirst());
        for (Block b : cells) area.union(BoundingBox.of(b));
        for (Entity e : d.getWorld().getNearbyEntities(area.expand(0.1), e -> e instanceof Interaction)) {
            if (owner.equals(e.getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING))) e.remove();
        }
        StringBuilder parts = new StringBuilder();
        for (double[] box : want.boxes()) parts.append(spawnHit(d.getWorld(), box, d.getUniqueId()).getUniqueId()).append(';');
        for (String id : keep) parts.append(id).append(';');
        pdc.set(keyParts, PersistentDataType.STRING, parts.toString());
    }

    /**
     * Puts barriers where the shape wants them and takes them out of the other cells. A cell where a barrier
     * should go but a player or mob stands stays open for now (false: try again), so nobody is shut inside a block.
     */
    private boolean applyBarriers(FurnitureSpec spec, Placed want, List<Block> cells) {
        if (!spec.solid()) return true;
        boolean done = true;
        for (Block b : cells) {
            Material now = b.getType();
            if (want.barriers().contains(b)) {
                if (now == Material.BARRIER) continue;
                if (!(b.isEmpty() || b.isReplaceable())) continue; // something else is there (never ours to remove)
                if (obstructed(b)) {
                    done = false;
                    continue;
                }
                b.setType(Material.BARRIER, false);
            } else if (now == Material.BARRIER) {
                b.setType(Material.AIR, false);
            }
        }
        return done;
    }

    /** A loaded piece: its cells are reserved, and its shape checked (next tick: not while the chunk is loading). */
    private void loaded(ItemDisplay d) {
        PersistentDataContainer pdc = d.getPersistentDataContainer();
        if (!pdc.has(keyItem) || !pdc.has(keyCells)) return;
        List<int[]> cells = new ArrayList<>();
        for (String cell : pdc.getOrDefault(keyCells, PersistentDataType.STRING, "").split(";")) {
            String[] xyz = cell.split(",");
            if (xyz.length == 3) cells.add(new int[]{Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])});
        }
        reserved.add(d.getWorld().getUID(), d.getUniqueId(), cells);
        refreshLater(d.getUniqueId(), 1);
    }

    /** A cell of some loaded furniture (with or without a barrier). */
    boolean reserved(Block b) {
        return reserved.reserved(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
    }

    boolean reserved(World w, int x, int y, int z) {
        return reserved.reserved(w.getUID(), x, y, z);
    }

    // ------------------------------------------------------------------------------------------
    // Lookup & removal
    // ------------------------------------------------------------------------------------------

    /** Furniture occupying this block, found through the cell list stored on nearby furniture. */
    public Optional<Furniture> furnitureAt(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        String me = block.getX() + "," + block.getY() + "," + block.getZ() + ";";
        for (ItemDisplay d : block.getWorld().getNearbyEntitiesByType(ItemDisplay.class, center, 4.5)) {
            String cells = d.getPersistentDataContainer().get(keyCells, PersistentDataType.STRING);
            if (cells != null && (";" + cells).contains(";" + me)) return fromDisplay(d);
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
        return f.display().getYaw() - (float) plugin.getConfig().getDouble("furniture.display-yaw-offset");
    }

    /** Removes the furniture and its helpers. Returns false if a listener cancelled it. */
    public boolean breakFurniture(Player player, Furniture furniture, boolean drop) {
        FurnitureBreakEvent event = new FurnitureBreakEvent(player, furniture);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        ItemStack dropped = drop ? behavior(furniture.item()).map(b -> b.drop(furniture, furniture.item().create(1)))
                .orElseGet(() -> furniture.item().create(1)) : null;
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
        reserved.remove(display.getUniqueId());
        refreshAt.remove(display.getUniqueId());
        Location loc = display.getLocation();
        if (dropped != null && !dropped.isEmpty()) world.dropItemNaturally(loc, dropped);
        world.playSound(loc, Sound.BLOCK_STONE_BREAK, 1f, 0.9f);
        display.remove();
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Changing a placed piece: another model (upgrade looks), its parts, spinning on and off
    // ------------------------------------------------------------------------------------------

    /**
     * Shows another item model on the furniture (e.g. "economy:model/mill_2"); null = the item's own model.
     * Its click boxes and barriers follow on the next tick (after the part models changed too, see setPartModel).
     */
    public void setModel(Furniture f, NamespacedKey model) {
        ItemDisplay d = f.display();
        refreshLater(d.getUniqueId(), 1);
        ItemStack it = model == null ? f.item().create(1) : d.getItemStack();
        if (model != null) {
            NamespacedKey now = it.hasItemMeta() ? it.getItemMeta().getItemModel() : null;
            if (model.equals(now)) return;
            it.editMeta(m -> m.setItemModel(model));
        }
        d.setItemStack(it);
    }

    /** A point in the furniture's model coordinates (16 = 1 block, anchor block floor = y 0) in the world. */
    public Location modelPoint(Furniture f, org.joml.Vector3f p) {
        double[] o = modelToWorld(p, yawOf(f));
        Block anchor = f.display().getLocation().getBlock();
        return anchor.getLocation().add(0.5 + o[0], 0.5 + o[1], 0.5 + o[2]);
    }

    /** The part entities of a piece (extra models such as wheels), in the order of the item's parts. */
    public List<ItemDisplay> parts(Furniture f) {
        List<ItemDisplay> out = new ArrayList<>();
        for (String part : f.data().getOrDefault(keyParts, PersistentDataType.STRING, "").split(";")) {
            if (part.isBlank()) continue;
            if (Bukkit.getEntity(UUID.fromString(part)) instanceof ItemDisplay d) out.add(d);
        }
        return out;
    }

    /**
     * Shows another model on a part. The piece's click boxes follow the parts it shows when it is next checked:
     * call {@link #setModel} for the piece in the same tick (as a tier change does), which schedules that.
     */
    public void setPartModel(ItemDisplay part, NamespacedKey model) {
        ItemStack it = part.getItemStack();
        NamespacedKey now = it.hasItemMeta() ? it.getItemMeta().getItemModel() : null;
        if (model.equals(now)) return;
        it.editMeta(m -> m.setItemModel(model));
        part.setItemStack(it);
    }

    /** Starts or stops a spinning part; it keeps its axis and speed (a stopped part stays where it is). */
    public void setSpinning(ItemDisplay part, boolean on) {
        PersistentDataContainer pdc = part.getPersistentDataContainer();
        if (!pdc.has(keySpin)) return;
        if (on) {
            pdc.remove(keySpinOff);
            spinning.add(part.getUniqueId());
        } else {
            pdc.set(keySpinOff, PersistentDataType.BYTE, (byte) 1);
            spinning.remove(part.getUniqueId());
        }
    }

    public boolean isSpinning(ItemDisplay part) {
        return spinning.contains(part.getUniqueId());
    }

    private Optional<FurnitureBehavior> behavior(CustomItem item) {
        String id = item.furniture() == null ? null : item.furniture().behavior();
        return id == null ? Optional.empty() : Optional.ofNullable(behaviors.get(id));
    }

    private boolean shouldDrop(Player player) {
        return player.getGameMode() != GameMode.CREATIVE && plugin.getConfig().getBoolean("furniture.drop-on-break");
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
        lastPlace.remove(e.getPlayer().getUniqueId());
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
        if (!plugin.getConfig().getBoolean("furniture.preview")) {
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
            showPreview(p, held.get(), plan(p, held.get(), anchor));
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

        // one right-click can arrive twice (held button repeat / a second use packet): one placement per 6 ticks
        long now = player.getWorld().getGameTime();
        Long last = lastPlace.get(player.getUniqueId());
        if (last != null && now - last < 6) return;

        Block anchor = clicked.isReplaceable() ? clicked : clicked.getRelative(e.getBlockFace());
        Block against = anchor == clicked ? clicked.getRelative(BlockFace.DOWN) : clicked;
        if (place(player, item.get(), anchor, against, e.getHand()).isPresent()) {
            lastPlace.put(player.getUniqueId(), now);
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
