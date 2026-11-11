package dev.stsv.core.vehicle;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.furniture.DisplayView;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * 탈것: vehicles placed from an item, ridden and driven with the player's own keys, picked up again
 * (content/stsv/items/vehicles.yml: 경찰차, 1톤 트럭, 골프 카트, 리어카; README "탈것").
 *
 * <h2>How it is shown and kept</h2>
 * The database ({@link VehicleStore}) holds every vehicle; nothing of it is saved with a chunk. While a vehicle's chunk is
 * loaded it is a {@link LiveVehicle}: an item display for the body, one per wheel (spun and, at the front, steered through
 * their display transformation), the light bar and the load, a few click boxes (interaction entities) along it and, for
 * each rider, a seat (a carrier display with a chair on it, see below). The chunk unloads (or the server stops): its position
 * is saved and the entities go. A crash leaves nothing behind and nothing doubled; the vehicle is where it was last saved.
 *
 * <h2>Moving smoothly</h2>
 * Every tick the driver's keys ({@link Player#getCurrentInput()}) go through {@link VehiclePhysics}; the body, wheels and
 * parts are then teleported to the new place and yaw, and the client glides between ticks (display teleport duration
 * and transformation interpolation: vehicles.smoothing-ticks). The seats are teleported with their riders on them
 * (Paper keeps passengers on a teleport, see {@link #carry}): a rider stays seated, their client places them on the
 * gliding seat, and their camera stays their own (neither seat entity steers or turns its passenger).
 *
 * <h2>Seats: body forward, head free</h2>
 * A seat is two entities ({@link LiveVehicle.Seat}): a carrier display, teleported with the vehicle (it glides like the
 * body), and on it a chair, an invisible marker armor stand facing the vehicle, which the rider sits on. Clients draw a
 * rider's body from their mount only when the mount is a living entity (LivingEntityRenderer.solveBodyRot, as on a horse
 * or a pig): body = the mount's facing, the head turning up to 85 degrees either way ({@link VehicleSeats#riderBodyYaw}).
 * On a display entity (no body of its own) the client turns the body after the head like someone standing, so looking
 * around turned the whole body. The chair is turned with the car every tick (its rotation is sent at once, see
 * {@link #face}), so the body turns with the car as it steers. The marker has no hitbox, physics, AI, drops or health bar
 * (max health 1: no vehicle hearts in place of the food bar); the camera is not clamped (only boats clamp their rider's
 * view, and a boat would be driven by its rider's client) and steering stays on the car's yaw.
 *
 * <h2>Sounds</h2> {@link VehicleSounds}: the horn sounds while space is held (the pack's clip, stopped on release, plus a
 * short tail) and the engine hums while someone drives (short segments re-played back to back, their pitch following
 * the speed). Both are bound to the body, so they move with the vehicle (tools/assetgen/sounds.py makes them).
 *
 * <h2>Pulled carts (리어카)</h2> A vehicle with {@code pull:} has no seats: right-clicking it takes its handles, and every
 * tick it follows the puller like a trailer ({@link CartPull}: turning about its wheels, gravity, steps, walls, hazards).
 * The puller walks normally but slower ({@link PullSpeed}: a transient movement-speed modifier, sprinting blocked) and
 * lets go by sneaking or right-clicking it again; they let go by themselves when it gets stuck and they walk on (more
 * than its release distance from the handles), and whenever they quit, die, change worlds, teleport, get into
 * something, or the cart is picked up or goes. Nobody holding it, it rests tipped onto its handles.
 *
 * <h2>Rules</h2> {@link VehicleAccess} (who drives, pulls, opens the bed, picks up), {@link VehicleSeats} (which seat, where
 * to step out). Owners allow other players for all their vehicles with /탈것 권한 ({@link VehicleCommand}, kept in
 * {@link VehicleStore}). Vehicles cannot be hurt and hurt nobody; mobs cannot get in.
 */
public final class VehicleModule implements StsvModule, Listener {

    /** On every entity of a vehicle: its id. */
    public static final NamespacedKey PART = NamespacedKey.fromString("stsv:vehicle_part");
    /** On a seat entity: which seat. */
    static final NamespacedKey SEAT = NamespacedKey.fromString("stsv:vehicle_seat");
    /** On a vehicle's item: its id and token (VehicleStore). An item without them (made by an admin) is a new vehicle. */
    static final NamespacedKey ITEM_ID = NamespacedKey.fromString("stsv:vehicle_id");
    static final NamespacedKey ITEM_TOKEN = NamespacedKey.fromString("stsv:vehicle_token");
    private static final NamespacedKey BUTTON = NamespacedKey.fromString("stsv:vehicle_button");
    private static final String ADMIN_OWNER = "STSV";
    private static final SecureRandom RANDOM = new SecureRandom();

    private STSVPlugin plugin;
    private VehicleStore store;
    private Settings settings;
    /** vehicles in the world whose chunk is not loaded (shown when it loads) */
    private final Map<Long, VehicleStore.Row> parked = new HashMap<>();
    private final Map<Long, LiveVehicle> live = new HashMap<>();
    /** vehicles with a database step on the way (placing, picking up): nothing else may touch them meanwhile */
    private final Set<Long> busy = new HashSet<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, DriveInput> inputs = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    /** seats this module empties itself (their dismount is handled where it happens, not in onDismount) */
    private final Set<UUID> ejecting = new HashSet<>();
    /** who pulls which cart (player -> vehicle id) */
    private final Map<UUID, Long> pulling = new HashMap<>();
    /** /탈것 권한: owner -> the players they allowed (-> name when allowed), oldest first */
    private final Map<UUID, Map<UUID, String>> grants = new HashMap<>();
    private BiPredicate<UUID, UUID> trust;
    private BiConsumer<UUID, ItemStack> mailbox;
    private BukkitTask task;
    private long ticks;
    private boolean spawnSoon;

    /** config.yml vehicles.* */
    record Settings(double maxSpeed, int smoothing, float viewRange, boolean culling, int saveTicks, boolean sirenSound, String sirenKey,
                    float sirenVolume, float pitchHigh, float pitchLow, int sirenInterval, int flashTicks, boolean customHorn,
                    VehicleSounds.HornRules horn, boolean engine, double engineVolume, double yawOffset) {}

    /** The longest hold the horn clips cover (tools/assetgen/sounds.py HORN_HOLD), seconds. */
    static final double HORN_CLIP_SECONDS = 5.0;

    @Override
    public String id() {
        return "vehicles";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        settings = settings();
        store = new VehicleStore(plugin.db());
        store.init();
        for (VehicleStore.Row r : store.placed()) if (r.pos() != null) parked.put(r.id(), r);
        for (VehicleStore.Grant g : store.grants()) grants.computeIfAbsent(g.owner(), k -> new LinkedHashMap<>()).put(g.player(), g.playerName());
        // a plugin reload while someone pulled: nobody pulls now
        for (Player p : Bukkit.getOnlinePlayers()) PullSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        // a plugin reload: the last run's entities (never saved, so only ever after a reload in the same run)
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (isVehiclePart(e)) e.remove();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), 1L, 1L);
        plugin.admin().add(ADMIN_OWNER, "탈것", "목록 [플레이어] | 회수 <번호> | 제거 [반경]",
                "탈것 목록 / 주인에게 아이템으로 돌려주기 / 주변 탈것 지우기", this::command,
                (s, a) -> a.length == 1 ? List.of("목록", "회수", "제거") : a.length == 2 && a[0].equals("제거") ? List.of("8", "16", "32") : List.of());
        plugin.admin().report("STSV 탈것", (s, a) -> s.sendMessage(Lang.mm("vehicle.report", "live", live.size(), "parked", parked.size())));
        org.bukkit.command.PluginCommand command = plugin.getCommand("탈것");
        if (command != null) {
            VehicleCommand c = new VehicleCommand(this);
            command.setExecutor(c);
            command.setTabCompleter(c);
        }
        spawnSoon = true;
        plugin.getLogger().info("탈것 " + parked.size() + "대 (청크를 불러오면 보임)");
    }

    @Override
    public void reload() {
        settings = settings();
        // the item files may have changed: the shown vehicles take their new settings (parts again, riders stay)
        for (LiveVehicle v : List.copyOf(live.values())) {
            Optional<CustomItem> item = plugin.content().item(v.item.key().asString()).filter(CustomItem::isVehicle);
            if (item.isEmpty()) continue;
            quiet(v, false);   // its sounds stop under the keys they were started with
            syncCargo(v);
            ItemStack[] cargo = v.cargo;
            v.item = item.get();
            if (v.seats.length != v.item.vehicle().seats().size()) {
                for (int i = 0; i < v.seats.length; i++) leave(v, i);
                v.seats = new LiveVehicle.Seat[v.item.vehicle().seats().size()];
            }
            if (v.puller != null && !v.item.vehicle().pulled()) release(v, null);
            v.spec = v.item.vehicle();
            v.cargo = resized(cargo, v.spec);
            removeParts(v, false);
            spawnParts(v);
        }
    }

    @Override
    public void disable() {
        for (LiveVehicle v : List.copyOf(live.values())) {
            release(v, null);
            despawn(v, true);
        }
        if (task != null) task.cancel();
        plugin.admin().removeAll("STSV 탈것");
        HandlerList.unregisterAll(this);
    }

    private Settings settings() {
        return new Settings(Math.max(1, plugin.getConfig().getDouble("vehicles.max-speed")),
                Math.clamp(plugin.getConfig().getInt("vehicles.smoothing-ticks"), 1, 5),
                (float) (Math.max(16, plugin.getConfig().getDouble("vehicles.view-distance")) / 64.0),
                plugin.getConfig().getBoolean("display.frustum-culling"),
                Math.max(20, (int) Math.round(plugin.getConfig().getDouble("vehicles.save-seconds") * 20)),
                plugin.getConfig().getBoolean("vehicles.siren.sound-enabled"), plugin.getConfig().getString("vehicles.siren.sound"),
                (float) plugin.getConfig().getDouble("vehicles.siren.volume"), (float) plugin.getConfig().getDouble("vehicles.siren.pitch-high"),
                (float) plugin.getConfig().getDouble("vehicles.siren.pitch-low"), Math.max(2, plugin.getConfig().getInt("vehicles.siren.interval")),
                Math.max(1, plugin.getConfig().getInt("vehicles.siren.flash-ticks")), plugin.getConfig().getBoolean("vehicles.horn.custom-sound"),
                new VehicleSounds.HornRules(Math.clamp(plugin.getConfig().getInt("vehicles.horn.min-ticks"), 1, 20),
                        (int) Math.round(Math.clamp(plugin.getConfig().getDouble("vehicles.horn.max-hold-seconds"), 0.5, HORN_CLIP_SECONDS) * 20),
                        Math.clamp(plugin.getConfig().getInt("vehicles.horn.cooldown"), 0, 200)),
                plugin.getConfig().getBoolean("vehicles.engine.enabled"),
                Math.clamp(plugin.getConfig().getDouble("vehicles.engine.volume"), 0, 2),
                plugin.getConfig().getDouble("furniture.display-yaw-offset"));
    }

    // ------------------------------------------------------------------------------------------
    // API for content plugins
    // ------------------------------------------------------------------------------------------

    /**
     * Who besides the owner (and admins) may drive a vehicle and open its bed: (owner, player) -> true. The economy server
     * says: the people of the owner's land. null = nobody.
     */
    public void trust(BiPredicate<UUID, UUID> trust) {
        this.trust = trust;
    }

    /**
     * Where a vehicle an admin sends back goes: (owner, item) -> into their inventory or a mailbox (the economy server's
     * 우편함). null = the core gives it to them when they are online with room (on their next join otherwise).
     */
    public void mailbox(BiConsumer<UUID, ItemStack> mailbox) {
        this.mailbox = mailbox;
    }

    /** Is this entity part of a vehicle (body, wheel, click box, seat)? Land protection lets everyone touch these. */
    public static boolean isVehiclePart(Entity e) {
        return e != null && e.getPersistentDataContainer().has(PART, PersistentDataType.LONG);
    }

    private static Long partOf(Entity e) {
        return e == null ? null : e.getPersistentDataContainer().get(PART, PersistentDataType.LONG);
    }

    private boolean trusted(UUID owner, UUID player) {
        BiPredicate<UUID, UUID> t = trust;
        try {
            return t != null && t.test(owner, player);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean admin(Player p) {
        return p.hasPermission("stsv.admin");
    }

    /** The owner allowed this player (/탈것 권한). */
    boolean granted(UUID owner, UUID player) {
        Map<UUID, String> g = grants.get(owner);
        return g != null && g.containsKey(player);
    }

    /** Whether a player may drive this vehicle, pull it and open its bed (VehicleAccess). */
    private boolean mayDrive(LiveVehicle v, Player p) {
        UUID id = p.getUniqueId();
        return VehicleAccess.mayDrive(v.owner, id, admin(p), trusted(v.owner, id), granted(v.owner, id));
    }

    // ------------------------------------------------------------------------------------------
    // /탈것 권한 (VehicleCommand): who else may drive, pull and open the beds of an owner's vehicles
    // ------------------------------------------------------------------------------------------

    /** The most players one owner may allow. */
    static final int MAX_GRANTS = 45;

    /** The players an owner allowed (player -> their name when allowed), oldest first. */
    Map<UUID, String> grantsOf(UUID owner) {
        Map<UUID, String> g = grants.get(owner);
        return g == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(g));
    }

    /** Allows a player (saved first; then = true when newly allowed, on the main thread; null = the database failed). */
    void grant(UUID owner, UUID player, String name, java.util.function.Consumer<Boolean> then) {
        store.grant(owner, player, name, System.currentTimeMillis()).whenComplete((added, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[탈것] 권한 저장 실패", err);
                then.accept(null);
                return;
            }
            grants.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(player, name);
            then.accept(added);
        }));
    }

    /**
     * Takes a permission back (then = true when it was there; null = the database failed). Whoever loses it right now is
     * let out of what they use: the handles of a cart, the driver's seat (they step out), an open bed.
     */
    void revoke(UUID owner, UUID player, java.util.function.Consumer<Boolean> then) {
        store.revoke(owner, player).whenComplete((removed, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[탈것] 권한 취소 실패", err);
                then.accept(null);
                return;
            }
            Map<UUID, String> g = grants.get(owner);
            if (g != null) {
                g.remove(player);
                if (g.isEmpty()) grants.remove(owner);
            }
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                for (LiveVehicle v : List.copyOf(live.values())) {
                    if (!v.owner.equals(owner) || mayDrive(v, p)) continue;
                    if (player.equals(v.puller)) release(v, "vehicle.grant-lost");
                    if (v.seatOf(p) == 0) {
                        leave(v, 0);
                        p.sendMessage(Lang.msg("vehicle.grant-lost"));
                    }
                    if (player.equals(v.cargoViewer)) closeCargo(v);
                }
            }
            then.accept(removed);
        }));
    }

    // ------------------------------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------------------------------

    /** The item of a vehicle with its identity (id, token) and number in its lore. */
    ItemStack itemFor(CustomItem item, long id, long token) {
        ItemStack it = item.create(1);
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(ITEM_ID, PersistentDataType.LONG, id);
            m.getPersistentDataContainer().set(ITEM_TOKEN, PersistentDataType.LONG, token);
            List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
            lore.add(Lang.item("vehicle.item-number", "id", id));
            m.lore(lore);
        });
        return it;
    }

    private static long idOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Long v = it.getItemMeta().getPersistentDataContainer().get(ITEM_ID, PersistentDataType.LONG);
        return v == null ? 0 : v;
    }

    private static long tokenOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Long v = it.getItemMeta().getPersistentDataContainer().get(ITEM_TOKEN, PersistentDataType.LONG);
        return v == null ? 0 : v;
    }

    private static long newToken() {
        long t;
        do t = RANDOM.nextLong(); while (t == 0);
        return t;
    }

    /** The item's name as MiniMessage (content names are trusted text), for messages. */
    private static String label(CustomItem item) {
        return item.name() == null ? item.key().getKey() : item.name();
    }

    private static boolean room(Player p) {
        return p.getInventory().firstEmpty() >= 0;
    }

    // ------------------------------------------------------------------------------------------
    // Placing
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("deprecation") // isInteractable is approximate but has no replacement; furniture uses it too
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_AIR && e.getHand() != null) {
            // a boat is put on the water, which the client clicks through (in the air, or at the bottom under it)
            Player p = e.getPlayer();
            ItemStack hand = p.getInventory().getItem(e.getHand());
            Optional<CustomItem> boat = plugin.content().itemOf(hand).filter(i -> i.isVehicle() && i.vehicle().floats());
            if (boat.isEmpty()) return;
            e.setCancelled(true);
            if ((e.getHand() == EquipmentSlot.HAND || e.getHand() == EquipmentSlot.OFF_HAND) && !p.isInsideVehicle() && click(p)) {
                launch(p, boat.get(), hand, e.getHand());
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() == null || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItem(e.getHand());
        Optional<CustomItem> item = plugin.content().itemOf(hand).filter(CustomItem::isVehicle);
        if (item.isEmpty()) return;
        Block clicked = e.getClickedBlock();
        if (clicked.getType().asBlockType().isInteractable() && !p.isSneaking()) return;   // let doors and chests open
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;
        if (p.isInsideVehicle() || !click(p)) return;
        if (item.get().vehicle().floats()) launch(p, item.get(), hand, e.getHand());
        else place(p, item.get(), hand, e.getHand(), clicked, e.getBlockFace());
    }

    /** One action per 6 ticks (a right-click can arrive twice). */
    private boolean click(Player p) {
        long now = ticks;
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 6) return false;
        lastClick.put(p.getUniqueId(), now);
        return true;
    }

    private void place(Player p, CustomItem item, ItemStack hand, EquipmentSlot slot, Block clicked, BlockFace face) {
        VehicleSpec s = item.vehicle();
        World w = clicked.getWorld();
        Block base = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        WorldTerrain terrain = new WorldTerrain(w);
        // a car faces where the player looks; a cart turns its handles towards them (ready to take)
        float yaw = VehiclePhysics.wrap(p.getYaw() + (s.pulled() ? 180 : 0));
        VehiclePhysics.State st = new VehiclePhysics.State(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, yaw);
        // on the ground under the spot (a slab, a carpet, a step down)
        st.y += VehiclePhysics.collide(VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s), 0, -1.5, 0, terrain)[1];
        if (VehiclePhysics.collides(st.x, st.y, st.z, st.yaw, s, terrain) || VehiclePhysics.hazard(st.x, st.y, st.z, st.yaw, s, terrain)
                || st.y < w.getMinHeight()) {
            refuse(p, Lang.mm("vehicle.no-room", "length", fmt(s.length()), "width", fmt(s.width())));
            return;
        }
        // the land's and the build world's rules, as for a block in every cell it takes (asked like furniture asks)
        Set<Block> cells = new LinkedHashSet<>();
        for (double[] b : VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s)) {
            int y = (int) Math.floor(st.y + 0.01);
            for (int x = (int) Math.floor(b[0]); x <= (int) Math.floor(b[3] - 1e-6); x++)
                for (int z = (int) Math.floor(b[2]); z <= (int) Math.floor(b[5] - 1e-6); z++) cells.add(w.getBlockAt(x, y, z));
        }
        if (!plugin.furniture().mayBuild(p, cells, clicked, slot)) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            return;
        }
        commit(p, item, hand, w, st);
    }

    /**
     * A boat goes on the water the player looks at (through the water: the first water block on their line of sight,
     * 6 blocks at most), facing where they look, floating at its draft (BoatPhysics). Only where every point of its hull
     * is over water and nothing is in the way; the land's rules as for blocks in the cells at the water's surface.
     */
    private void launch(Player p, CustomItem item, ItemStack hand, EquipmentSlot slot) {
        VehicleSpec s = item.vehicle();
        World w = p.getWorld();
        org.bukkit.util.RayTraceResult hit = p.rayTraceBlocks(6, org.bukkit.FluidCollisionMode.ALWAYS);
        Block water = hit == null ? null : hit.getHitBlock();
        WorldWater terrain = new WorldWater(w);
        if (water == null || terrain.cell(water.getX(), water.getY(), water.getZ()) != BoatPhysics.Cell.WATER) {
            refuse(p, Lang.mm("vehicle.boat-on-water"));
            return;
        }
        double x = hit.getHitPosition().getX(), z = hit.getHitPosition().getZ();
        float yaw = VehiclePhysics.wrap(p.getYaw());
        double y = BoatPhysics.launchHeight(x, water.getY(), z, s, terrain);
        if (Double.isNaN(y) || y < w.getMinHeight() || !BoatPhysics.canLaunch(x, y, z, yaw, s, terrain)) {
            refuse(p, Lang.mm("vehicle.boat-no-room", "length", fmt(s.length()), "width", fmt(s.width())));
            return;
        }
        VehiclePhysics.State st = new VehiclePhysics.State(x, y, z, yaw);
        Set<Block> cells = new LinkedHashSet<>();
        int wl = (int) Math.floor(y + s.water().draft());
        for (double[] b : VehiclePhysics.footprint(st.x, st.y, st.z, st.yaw, s)) {
            for (int bx = (int) Math.floor(b[0]); bx <= (int) Math.floor(b[3] - 1e-6); bx++)
                for (int bz = (int) Math.floor(b[2]); bz <= (int) Math.floor(b[5] - 1e-6); bz++) cells.add(w.getBlockAt(bx, wl, bz));
        }
        if (!plugin.furniture().mayBuild(p, cells, water, slot)) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            return;
        }
        commit(p, item, hand, w, st);
    }

    /** The vehicle item in the hand becomes a vehicle in the world at st (a new row, or its row placed with its token). */
    private void commit(Player p, CustomItem item, ItemStack hand, World w, VehiclePhysics.State st) {
        long id = idOf(hand), token = tokenOf(hand);
        if (id > 0 && busy.contains(id)) return;
        // the item goes now (it is the vehicle); a fresh admin item in creative stays, like furniture
        ItemStack taken = hand.asOne();
        boolean consume = id > 0 || p.getGameMode() != GameMode.CREATIVE;
        if (consume) hand.subtract(1);
        VehicleStore.Pos pos = new VehicleStore.Pos(w.getUID(), w.getName(), st.x, st.y, st.z, st.yaw);
        long newToken = newToken(), now = System.currentTimeMillis();
        if (id > 0) busy.add(id);
        var done = id > 0 ? store.place(id, token, p.getUniqueId(), p.getName(), pos, newToken, now)
                : store.create(item.key().asString(), p.getUniqueId(), p.getName(), pos, newToken, now);
        done.whenComplete((row, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (id > 0) busy.remove(id);
            if (err != null) {
                if (consume) give(p, taken);
                p.sendMessage(Lang.msg("vehicle.error"));
                return;
            }
            if (row == null) {
                // a stale or copied item: the vehicle is already out, or was placed from another copy
                p.sendMessage(Lang.msg("vehicle.stale"));
                plugin.getLogger().warning("[탈것] " + p.getName() + " 님이 쓸 수 없는 탈것 아이템 #" + id + " 을 놓으려 함 (이미 세워졌거나 복사본). 아이템은 사라짐");
                return;
            }
            parked.put(row.id(), row);
            LiveVehicle v = spawnIfLoaded(row);
            w.playSound(new Location(w, st.x, st.y, st.z), item.vehicle().floats() ? Sound.ENTITY_GENERIC_SPLASH : Sound.BLOCK_IRON_DOOR_CLOSE,
                    0.8f, 1.2f);
            p.sendMessage(Lang.msg("vehicle.placed", "vehicle", label(item), "id", row.id()));
        }));
    }

    private void refuse(Player p, Component why) {
        HudModule.flash(p, why);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    // ------------------------------------------------------------------------------------------
    // Showing: spawn, parts, despawn
    // ------------------------------------------------------------------------------------------

    private LiveVehicle spawnIfLoaded(VehicleStore.Row r) {
        if (live.containsKey(r.id()) || busy.contains(r.id())) return live.get(r.id());
        World w = Bukkit.getWorld(r.pos().world());
        if (w == null) return null;
        Optional<CustomItem> item = plugin.content().item(r.item()).filter(CustomItem::isVehicle);
        if (item.isPresent() && !areaLoaded(w, r.pos().x(), r.pos().z(), item.get().vehicle(), true)) return null;
        if (item.isEmpty()) {
            if (warned.add(r.item())) plugin.getLogger().warning("[탈것] 알 수 없는 탈것 아이템 " + r.item() + " (#" + r.id() + "): 보이지 않음");
            return null;
        }
        VehiclePhysics.State st = new VehiclePhysics.State(r.pos().x(), r.pos().y(), r.pos().z(), r.pos().yaw());
        st.onGround = true;
        LiveVehicle v = new LiveVehicle(r.id(), item.get(), r.owner(), r.ownerName(), w, st);
        v.cargo = resized(decode(r.cargo()), v.spec);
        v.stage = stageOf(v);
        parked.remove(r.id());
        live.put(r.id(), v);
        spawnParts(v);
        return v;
    }

    private void spawnLoaded() {
        for (VehicleStore.Row r : List.copyOf(parked.values())) spawnIfLoaded(r);
    }

    /** How far from its centre a vehicle's entities reach (click boxes, seats, a cart's handles), blocks. */
    private static double reach(VehicleSpec s) {
        double r = Math.hypot(s.length(), s.width()) / 2 + 1;
        return s.pulled() ? Math.max(r, s.pull().hitch() + 1) : r;
    }

    /**
     * Are the chunks under the whole vehicle loaded (and their entities, when spawning)? A vehicle is shown only then, so
     * none of its entities ever stands in an unloaded chunk (spawning one there would load it).
     */
    private static boolean areaLoaded(World w, double x, double z, VehicleSpec s, boolean entities) {
        double r = reach(s);
        for (int cx = (int) Math.floor(x - r) >> 4; cx <= (int) Math.floor(x + r) >> 4; cx++) {
            for (int cz = (int) Math.floor(z - r) >> 4; cz <= (int) Math.floor(z + r) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) return false;
                if (entities && !w.getChunkAt(cx, cz).isEntitiesLoaded()) return false;
            }
        }
        return true;
    }

    /** Does the vehicle's area reach into this chunk? */
    private static boolean touches(LiveVehicle v, Chunk c) {
        if (!v.world.equals(c.getWorld())) return false;
        double r = reach(v.spec);
        int x0 = (int) Math.floor(v.st.x - r) >> 4, x1 = (int) Math.floor(v.st.x + r) >> 4;
        int z0 = (int) Math.floor(v.st.z - r) >> 4, z1 = (int) Math.floor(v.st.z + r) >> 4;
        return c.getX() >= x0 && c.getX() <= x1 && c.getZ() >= z0 && c.getZ() <= z1;
    }

    private Location at(LiveVehicle v) {
        return new Location(v.world, v.st.x, v.st.y, v.st.z, (float) (v.st.yaw + settings.yawOffset()), 0);
    }

    private float[] box(VehicleSpec s) {
        return new float[]{(float) (Math.hypot(s.length(), s.width()) + 1), (float) (s.height() + 1)};
    }

    private ItemDisplay display(LiveVehicle v, ItemStack shown, Transformation t) {
        return v.world.spawn(at(v), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(t);
            d.setTeleportDuration(settings.smoothing());
            d.setInterpolationDuration(settings.smoothing());
            DisplayView.apply(d, settings.viewRange(), box(v.spec), settings.culling());
            d.getPersistentDataContainer().set(PART, PersistentDataType.LONG, v.id);
        });
    }

    private static ItemStack model(NamespacedKey key) {
        if (key == null) return ItemStack.empty();
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> m.setItemModel(key));
        return it;
    }

    /** Display translation of a vehicle point: the display's own frame has +z forward and +x to the vehicle's left. */
    private static Vector3f local(VehicleSpec.Vec p) {
        return new Vector3f((float) -p.right(), (float) p.up(), (float) p.forward());
    }

    private void spawnParts(LiveVehicle v) {
        VehicleSpec s = v.spec;
        v.tiltShown = tilt(v);
        v.body = display(v, v.item.create(1), body(v, v.tiltShown));
        for (int i = 0; i < s.wheels().size(); i++) v.wheels.add(display(v, model(s.wheelModel()), wheel(v, i)));
        if (s.lights() != null) {
            v.lightsShown = v.siren ? 0 : -1;
            v.lights = display(v, model(v.siren ? s.lights().flash().getFirst() : s.lights().dark()), part(s.lights().at()));
        }
        if (s.cargo() != null) v.load = display(v, model(stageModel(v)), tilted(s, s.cargo().at(), 1, v.tiltShown));
        for (int i = 0; i < s.squares(); i++) {
            double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, new VehicleSpec.Vec(0, 0, s.squareOffset(i)));
            v.hits.add(v.world.spawn(new Location(v.world, c[0], c[1], c[2]), Interaction.class, h -> {
                h.setPersistent(false);
                h.setInteractionWidth((float) s.width());
                h.setInteractionHeight((float) s.clickHeight());
                h.setResponsive(true);
                h.getPersistentDataContainer().set(PART, PersistentDataType.LONG, v.id);
            }));
        }
        for (int i = 0; i < v.seats.length; i++) hideBoxes(v, v.rider(i), true);
        if (s.pulled()) {
            double[] c = CartPull.hitch(v.st, s);
            v.handle = v.world.spawn(new Location(v.world, c[0], c[1], c[2]), Interaction.class, h -> {
                h.setPersistent(false);
                h.setInteractionWidth(HANDLE_WIDTH);
                h.setInteractionHeight(HANDLE_HEIGHT);   // below the puller's eyes: their own clicks pass over it
                h.setResponsive(true);
                h.getPersistentDataContainer().set(PART, PersistentDataType.LONG, v.id);
            });
        }
    }

    /** A cart's handle click box (it stands where the puller stands). */
    private static final float HANDLE_WIDTH = 0.7f, HANDLE_HEIGHT = 0.9f;

    /** How far a cart tips forward now: onto its handles when nobody holds them, level while pulled (degrees). */
    private static float tilt(LiveVehicle v) {
        return v.spec.pulled() && v.puller == null ? (float) v.spec.pull().restTilt() : 0f;
    }

    private static Transformation body(LiveVehicle v, float tilt) {
        return tilted(v.spec, new VehicleSpec.Vec(0, 0, 0), v.spec.bodyScale(), tilt);
    }

    /**
     * A model at a vehicle point, scaled, tipped forward by tilt degrees about the vehicle's axle (a cart resting on its
     * handles): the display's rotation turns about the vehicle's ground centre, so the translation moves the turn onto
     * the axle (t = P + R (at - P)).
     */
    private static Transformation tilted(VehicleSpec s, VehicleSpec.Vec at, float scale, float tilt) {
        Vector3f t = local(at);
        Quaternionf r = new Quaternionf();
        if (tilt != 0) {
            r.rotateX((float) Math.toRadians(tilt));   // display +z is forward: +x rotation lowers the front
            Vector3f pivot = local(s.axle());
            t = new Vector3f(t).sub(pivot).rotate(r).add(pivot);
        }
        return new Transformation(t, r, new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static Transformation part(VehicleSpec.Vec at) {
        return new Transformation(local(at), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
    }

    private static Transformation wheel(LiveVehicle v, int i) {
        VehicleSpec.Wheel w = v.spec.wheels().get(i);
        Quaternionf steer = w.steer() ? new Quaternionf().rotateY((float) Math.toRadians(v.st.steer)) : new Quaternionf();
        Quaternionf spin = v.spec.floats() ? new Quaternionf().rotateZ((float) v.st.roll) : new Quaternionf().rotateX((float) v.st.roll);
        return new Transformation(local(w.at()), steer, new Vector3f(1, 1, 1), spin);
    }

    /** Removes the drawn parts (and the seats too when seats = true: riders step out first). */
    private void removeParts(LiveVehicle v, boolean seats) {
        quiet(v, false);
        if (seats) for (int i = 0; i < v.seats.length; i++) leave(v, i);
        if (v.body != null) v.body.remove();
        if (v.lights != null) v.lights.remove();
        if (v.load != null) v.load.remove();
        if (v.handle != null) v.handle.remove();
        v.wheels.forEach(Entity::remove);
        v.hits.forEach(Entity::remove);
        v.body = v.lights = v.load = null;
        v.handle = null;
        v.wheels.clear();
        v.hits.clear();
    }

    /**
     * Stops showing a vehicle: riders step out, an open bed is closed, entities go. save: it stays in the world (saved
     * where it is, shown again when its chunk loads); false: the caller takes it out of the world.
     */
    private void despawn(LiveVehicle v, boolean save) {
        release(v, "vehicle.pull-lost");
        closeCargo(v);
        removeParts(v, true);
        live.remove(v.id);
        if (save) {
            long now = System.currentTimeMillis();
            store.move(v.id, v.pos(), now);
            store.cargo(v.id, encode(v.cargo), now);
            parked.put(v.id, new VehicleStore.Row(v.id, v.item.key().asString(), v.owner, v.ownerName, VehicleStore.State.WORLD, 0, v.pos(),
                    encode(v.cargo), now, now));
        }
    }

    private void save(LiveVehicle v) {
        v.dirty = false;
        v.savedAt = ticks;
        store.move(v.id, v.pos(), System.currentTimeMillis());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        spawnSoon = true;
    }

    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        spawnSoon = true;
    }

    /** A chunk under a vehicle goes: the vehicle is saved and hidden first (its entities are never saved with chunks). */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (LiveVehicle v : List.copyOf(live.values())) if (touches(v, e.getChunk())) despawn(v, true);
    }

    // ------------------------------------------------------------------------------------------
    // Every tick: drive, move, lights
    // ------------------------------------------------------------------------------------------

    private void tick() {
        ticks++;
        if (spawnSoon || ticks % 40 == 0) {
            spawnSoon = false;
            spawnLoaded();
        }
        for (LiveVehicle v : List.copyOf(live.values())) {
            try {
                tick(v);
            } catch (RuntimeException ex) {
                if (warned.add("tick" + v.id)) plugin.getLogger().log(java.util.logging.Level.WARNING, "[탈것] #" + v.id, ex);
            }
        }
    }

    private void tick(LiveVehicle v) {
        if (!areaLoaded(v.world, v.st.x, v.st.z, v.spec, false)) {
            despawn(v, true);
            return;
        }
        if (v.body == null || !v.body.isValid() || v.hits.stream().anyMatch(h -> !h.isValid()) || v.wheels.stream().anyMatch(w -> !w.isValid())
                || (v.spec.pulled() && (v.handle == null || !v.handle.isValid()))) {
            removeParts(v, false);
            spawnParts(v);
        }
        Player driver = v.spec.pulled() ? null : v.rider(0);
        Player puller = puller(v);
        DriveInput in = driver != null ? DriveInput.of(driver.getCurrentInput()) : DriveInput.NONE;
        WorldTerrain terrain = new WorldTerrain(v.world);
        // a boat always follows the water under it (it rises and falls with it, settles aground when it goes)
        boolean active = driver != null || puller != null || v.st.speed != 0 || !v.st.onGround || v.st.steer != 0 || v.spec.floats();
        if (!active && (ticks + v.id) % 10 == 0 && !VehiclePhysics.supported(v.st, v.spec, terrain)) {
            v.st.onGround = false;   // the ground under it went: it falls
            active = true;
        }
        if (active) {
            double x = v.st.x, y = v.st.y, z = v.st.z, steer = v.st.steer, roll = v.st.roll;
            float yaw = v.st.yaw;
            if (puller != null) {
                Location at = puller.getLocation();
                CartPull.step(v.st, at.getX(), at.getZ(), v.spec, terrain);
            } else if (v.spec.floats()) {
                v.boatMode = BoatPhysics.step(v.st, in, v.spec, new WorldWater(v.world), settings.maxSpeed());
                v.boatTurn = VehicleSeats.signed(v.st.yaw - yaw);
            } else {
                VehiclePhysics.step(v.st, in, v.spec, terrain, settings.maxSpeed());
            }
            if (v.st.bumped) {
                if (v.spec.floats()) v.world.playSound(at(v), Sound.BLOCK_WOOD_HIT, SoundCategory.NEUTRAL, 0.8f, 0.6f);
                else v.world.playSound(at(v), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.NEUTRAL, 0.35f, 1.4f);
            }
            boolean moved = Math.abs(v.st.x - x) + Math.abs(v.st.y - y) + Math.abs(v.st.z - z) > 1e-5 || v.st.yaw != yaw;
            if (moved) {
                move(v);
                v.dirty = true;
                if (v.spec.pulled()) rolling(v, Math.hypot(v.st.x - x, v.st.z - z));
            }
            if (!v.spec.floats() && (moved || v.st.steer != steer || v.st.roll != roll)) {
                for (int i = 0; i < v.wheels.size(); i++) {
                    ItemDisplay w = v.wheels.get(i);
                    w.setInterpolationDelay(0);
                    w.setInterpolationDuration(settings.smoothing());
                    w.setTransformation(wheel(v, i));
                }
            }
            if (v.st.y < v.world.getMinHeight() - 32) {
                plugin.getLogger().warning("[탈것] #" + v.id + " 이(가) 월드 밖으로 떨어짐: 주인에게 돌려보냄");
                recall(v.id, null);
                return;
            }
        }
        if (puller != null) pulled(v, puller);
        if (v.spec.pulled() && tilt(v) != v.tiltShown) tiltTo(v, tilt(v));
        if (v.spec.floats()) boat(v, driver);
        lights(v);
        horn(v, driver != null && in.jump());
        engine(v, driver);
        if (v.cargoViewer != null) watchCargo(v);
        if (v.dirty && (ticks - v.savedAt >= settings.saveTicks() || (v.st.speed == 0 && driver == null && v.puller == null))) save(v);
    }

    /** Puts every entity of the vehicle where it is now (the client glides there over the smoothing ticks). */
    private void move(LiveVehicle v) {
        Location at = at(v);
        v.body.teleport(at);
        for (ItemDisplay w : v.wheels) w.teleport(at);
        if (v.lights != null) v.lights.teleport(at);
        if (v.load != null) v.load.teleport(at);
        if (v.handle != null) {
            double[] h = CartPull.hitch(v.st, v.spec);
            v.handle.teleport(new Location(v.world, h[0], h[1], h[2]));
        }
        for (int i = 0; i < v.hits.size(); i++) {
            double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, new VehicleSpec.Vec(0, 0, v.spec.squareOffset(i)));
            v.hits.get(i).teleport(new Location(v.world, c[0], c[1], c[2]));
        }
        for (int i = 0; i < v.seats.length; i++) {
            LiveVehicle.Seat seat = v.seats[i];
            if (seat == null) continue;
            if (!seat.valid()) {
                leave(v, i);   // something took the seat apart: its rider steps out, what is left goes
                continue;
            }
            carry(seat.carrier(), seatLocation(v, i));
            face(seat.chair(), v.st.yaw);
        }
    }

    /** A tiny head pose the chair switches to and back (it is invisible): see {@link #face}. */
    private static final EulerAngle NUDGE = new EulerAngle(0.001, 0, 0);

    /**
     * Turns a chair (and so its rider's body) to the vehicle's yaw. A passenger's rotation reaches clients only on the
     * armor stand's update interval (every 3rd tick) unless its entity data changed that tick, so the chair also switches
     * its (invisible) head pose: the turn is sent the same tick and the rider's body keeps up with the car's.
     */
    private static void face(ArmorStand chair, float yaw) {
        if (Math.abs(VehicleSeats.signed(chair.getYaw() - yaw)) < 0.05f) return;
        chair.setRotation(yaw, 0);
        chair.setBodyYaw(yaw);   // the server's own idea of it too (cosmetics read it), even where armor stands do not tick
        chair.setHeadPose(chair.getHeadPose().getX() == 0 ? NUDGE : EulerAngle.ZERO);
    }

    /**
     * Moves a seat with its rider on it. Paper 1.21.10+ keeps passengers on a teleport by itself (the old
     * RETAIN_PASSENGERS flag is deprecated: always on); a server that still refuses to move an entity with passengers
     * gets the flag.
     */
    @SuppressWarnings({"removal", "deprecation"})
    private static void carry(Entity seat, Location to) {
        if (!seat.teleport(to)) seat.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    private Location seatLocation(LiveVehicle v, int i) {
        double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, v.spec.seats().get(i));
        return new Location(v.world, c[0], c[1], c[2], v.st.yaw, 0);
    }

    /** The light bar flashes while the siren is on (and the siren sounds, two tones in turn). */
    private void lights(LiveVehicle v) {
        VehicleSpec.Lights l = v.spec.lights();
        if (l == null || v.lights == null) return;
        int want = v.siren ? (int) ((ticks / settings.flashTicks()) % l.flash().size()) : -1;
        if (want != v.lightsShown) {
            v.lightsShown = want;
            v.lights.setItemStack(model(want < 0 ? l.dark() : l.flash().get(want)));
        }
        if (v.siren && settings.sirenSound() && ticks % settings.sirenInterval() == 0) {
            boolean high = (ticks / settings.sirenInterval()) % 2 == 0;
            v.world.playSound(at(v), settings.sirenKey(), SoundCategory.NEUTRAL, settings.sirenVolume(), high ? settings.pitchHigh() : settings.pitchLow());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Boats (BoatPhysics, BoatMotion)
    // ------------------------------------------------------------------------------------------

    /**
     * A boat this tick: every few ticks its drawn parts rock on the waves (body, load and propeller about the
     * waterline's middle, the client gliding between); while driven, puffs of smoke from its stack in the engine's
     * rhythm ("통통통", faster with speed), and the bow splashes under way.
     */
    private void boat(LiveVehicle v, Player driver) {
        VehicleSpec.Water wa = v.spec.water();
        double top = Math.min(v.spec.speed(), settings.maxSpeed());
        if ((ticks + v.id) % BoatMotion.TICKS == 0) {
            BoatMotion.Rock r = BoatMotion.rock(ticks, v.id, v.st.speed, top, v.boatTurn, wa.rock(), v.boatMode == BoatPhysics.Mode.FLOAT);
            Quaternionf q = BoatMotion.rotation(r);
            Vector3f pivot = new Vector3f(0, (float) wa.draft(), 0);
            rock(v.body, body(v, 0), q, pivot, r.bob());
            if (v.load != null && v.spec.cargo() != null) rock(v.load, tilted(v.spec, v.spec.cargo().at(), 1, 0), q, pivot, r.bob());
            for (int i = 0; i < v.wheels.size(); i++) rock(v.wheels.get(i), wheel(v, i), q, pivot, r.bob());
        }
        if (driver == null) return;
        if (wa.exhaust() != null && settings.engine()) {
            int every = (int) Math.max(3, Math.round(9 - 5 * Math.abs(v.st.speed) / Math.max(top, 1e-9)));
            if (ticks % every == 0) {
                double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, wa.exhaust());
                v.world.spawnParticle(org.bukkit.Particle.SMOKE, c[0], c[1], c[2], 3, 0.03, 0.05, 0.03, 0.015);
            }
        }
        if (Math.abs(v.st.speed) > 1.5 && v.boatMode == BoatPhysics.Mode.FLOAT && ticks % 4 == 0) {
            for (double side : new double[]{-1, 1}) {
                double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw,
                        new VehicleSpec.Vec(side * v.spec.width() * 0.3, wa.draft() + 0.1, Math.signum(v.st.speed) * (v.spec.length() / 2 - 0.5)));
                v.world.spawnParticle(org.bukkit.Particle.SPLASH, c[0], c[1], c[2], 6, 0.15, 0.05, 0.15, 0.1);
            }
        }
    }

    private static void rock(ItemDisplay d, Transformation t, Quaternionf q, Vector3f pivot, float bob) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(BoatMotion.TICKS);
        d.setTransformation(new Transformation(BoatMotion.rocked(t.getTranslation(), pivot, q, bob), new Quaternionf(q).mul(t.getLeftRotation()),
                t.getScale(), t.getRightRotation()));
    }

    /** A boat's riders do not see its click boxes: they would catch a fishing rod's cast (nothing in it is clicked from inside). */
    private void hideBoxes(LiveVehicle v, Player rider, boolean hide) {
        if (!v.spec.floats() || rider == null) return;
        for (Interaction h : v.hits) {
            if (hide) rider.hideEntity(plugin, h);
            else rider.showEntity(plugin, h);
        }
    }

    /** A fishing hook passes through vehicles (a boat's riders fish over its side). */
    @EventHandler(ignoreCancelled = true)
    public void onHookHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.FishHook && isVehiclePart(e.getHitEntity())) e.setCancelled(true);
    }

    /** Where a boat's rider steps out when there is no shore beside it: into the water beside their seat. */
    private Location wetExit(LiveVehicle v, List<VehicleSpec.Vec> tries) {
        int wl = (int) Math.floor(v.st.y + v.spec.water().draft());
        for (VehicleSpec.Vec t : tries.subList(0, Math.min(4, tries.size()))) {
            double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, t);
            int bx = (int) Math.floor(c[0]), bz = (int) Math.floor(c[2]);
            if (!v.world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            Block feet = v.world.getBlockAt(bx, wl, bz);
            if (feet.getType() == Material.WATER && feet.getRelative(BlockFace.UP).isPassable()) return new Location(v.world, bx + 0.5, wl + 0.2, bz + 0.5);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Riding
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        Long id = partOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        LiveVehicle v = live.get(id);
        Player p = e.getPlayer();
        if (v == null || busy.contains(id) || p.isInsideVehicle() || !click(p)) return;
        if (v.spec.pulled()) {
            cartClick(v, p, e.getRightClicked());
            return;
        }
        if (p.isSneaking()) {
            if (v.spec.cargo() != null && inBed(v, p)) openCargo(v, p, 0);
            else pickUp(v, p);
        } else {
            board(v, p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(org.bukkit.event.player.PlayerInteractAtEntityEvent e) {
        if (partOf(e.getRightClicked()) != null) e.setCancelled(true);
    }

    /** Is the player looking at the truck's bed (behind its front edge)? */
    private boolean inBed(LiveVehicle v, Player p) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector d = eye.getDirection();
        double f = VehiclePhysics.hitForward(v.st.x, v.st.y, v.st.z, v.st.yaw, v.spec, eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ());
        if (Double.isNaN(f)) {
            // missed the box (clicked its edge): where the player stands along it
            double[] fw = VehiclePhysics.forward(v.st.yaw);
            f = (eye.getX() - v.st.x) * fw[0] + (eye.getZ() - v.st.z) * fw[1];
        }
        return f <= v.spec.cargo().behind();
    }

    private void board(LiveVehicle v, Player p) {
        boolean mayDrive = mayDrive(v, p);
        boolean[] taken = v.taken();
        int seat = VehicleSeats.pick(taken, mayDrive);
        if (seat < 0) {
            refuse(p, Lang.mm("vehicle.full"));
            return;
        }
        Location at = seatLocation(v, seat);
        ItemDisplay carrier = v.world.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(settings.smoothing());
            d.getPersistentDataContainer().set(PART, PersistentDataType.LONG, v.id);
        });
        ArmorStand chair = v.world.spawn(at, ArmorStand.class, a -> {
            a.setPersistent(false);
            a.setMarker(true);          // no hitbox: not clicked, hit, pushed or collided with
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setBasePlate(false);
            AttributeInstance health = a.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) health.setBaseValue(1);   // a mount with 0 hearts: the rider keeps their food bar
            a.setHealth(1);
            a.setRotation(v.st.yaw, 0);
            a.setBodyYaw(v.st.yaw);
            a.getPersistentDataContainer().set(PART, PersistentDataType.LONG, v.id);
            a.getPersistentDataContainer().set(SEAT, PersistentDataType.INTEGER, seat);
        });
        p.setRotation(v.st.yaw, p.getPitch());
        if (!carrier.addPassenger(chair) || !chair.addPassenger(p)) {
            ejecting.add(chair.getUniqueId());
            chair.eject();
            ejecting.remove(chair.getUniqueId());
            chair.remove();
            carrier.remove();
            return;
        }
        v.seats[seat] = new LiveVehicle.Seat(carrier, chair);
        hideBoxes(v, p, true);
        if (seat == 0) {
            HudModule.flash(p, Lang.mm("vehicle.driving", "keys", Lang.raw(v.spec.lights() != null ? "vehicle.keys-siren" : "vehicle.keys-horn")));
        } else if (!mayDrive && !taken[0]) {
            HudModule.flash(p, Lang.mm("vehicle.passenger", "owner", v.ownerName));
        }
    }

    /** A rider leaves a seat (it goes; they step out beside the vehicle, see onDismount). */
    private void leave(LiveVehicle v, int seat) {
        LiveVehicle.Seat s = seat < v.seats.length ? v.seats[seat] : null;
        if (s == null) return;
        Player p = v.rider(seat);
        v.seats[seat] = null;
        Location exit = exit(v, seat);
        ejecting.add(s.chair().getUniqueId());
        s.chair().eject();
        ejecting.remove(s.chair().getUniqueId());
        s.chair().remove();
        s.carrier().remove();
        if (p != null && p.isOnline()) hideBoxes(v, p, false);
        if (p != null && p.isOnline() && !p.isDead()) {
            exit.setYaw(p.getYaw());
            exit.setPitch(p.getPitch());
            p.teleport(exit);
        }
        if (seat == 0) driverLeft(v);
    }

    /** The driver's seat emptied: the siren goes off, the horn and the engine stop. */
    private void driverLeft(LiveVehicle v) {
        v.siren = false;
        quiet(v, true);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        // a rider leaving their chair (a chair leaving its carrier is noticed by move: Seat.valid)
        if (!(e.getDismounted() instanceof ArmorStand chair) || !(e.getEntity() instanceof Player)) return;
        Long id = partOf(chair);
        Integer index = chair.getPersistentDataContainer().get(SEAT, PersistentDataType.INTEGER);
        if (id == null || index == null || ejecting.contains(chair.getUniqueId())) return;
        LiveVehicle v = live.get(id);
        Entity rider = e.getEntity();
        Entity carrier = chair.getVehicle();
        if (v != null && rider instanceof Player rp) hideBoxes(v, rp, false);
        if (v != null && index < v.seats.length && v.seats[index] != null && v.seats[index].chair().equals(chair)) {
            carrier = v.seats[index].carrier();
            v.seats[index] = null;
            if (index == 0) driverLeft(v);
        }
        Entity seatCarrier = carrier;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location exit = v != null && live.containsKey(v.id) ? exit(v, index) : chair.getLocation();
            chair.remove();
            if (seatCarrier != null && isVehiclePart(seatCarrier)) seatCarrier.remove();
            // not someone teleported away meanwhile (a command, a world change): only a rider still by the seat steps out
            if (rider instanceof Player p && p.isOnline() && !p.isDead() && !p.isInsideVehicle() && p.getWorld().equals(exit.getWorld())
                    && p.getLocation().distanceSquared(exit) < 64) {
                exit.setYaw(p.getYaw());
                exit.setPitch(p.getPitch());
                p.teleport(exit);
            }
        });
    }

    /** A safe spot beside the vehicle for a rider of this seat (VehicleSeats.exits, in order); on top of it if none. */
    private Location exit(LiveVehicle v, int seat) {
        List<VehicleSpec.Vec> tries = VehicleSeats.exits(v.spec, v.spec.seats().get(Math.min(seat, v.spec.seats().size() - 1)));
        for (VehicleSpec.Vec t : tries) {
            double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, t);
            int bx = (int) Math.floor(c[0]), bz = (int) Math.floor(c[2]), by = (int) Math.floor(c[1] + 0.01);
            if (!v.world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            for (int dy : new int[]{0, 1, -1}) {
                Block feet = v.world.getBlockAt(bx, by + dy, bz);
                if (safe(feet)) return new Location(v.world, bx + 0.5, feet.getY() + floorHeight(feet.getRelative(BlockFace.DOWN)), bz + 0.5);
            }
        }
        if (v.spec.floats()) {
            Location wet = wetExit(v, tries);
            if (wet != null) return wet;
        }
        double[] top = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, tries.getLast());
        return new Location(v.world, top[0], top[1], top[2]);
    }

    private static boolean safe(Block feet) {
        Block below = feet.getRelative(BlockFace.DOWN);
        return feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable() && !below.isPassable()
                && below.getType() != Material.LAVA && below.getType() != Material.MAGMA_BLOCK && below.getType() != Material.CAMPFIRE;
    }

    /** How much of the block below sticks up into the feet block (a slab below a slab... is 0 here: full blocks). */
    private static double floorHeight(Block below) {
        double top = 0;
        for (var bb : below.getCollisionShape().getBoundingBoxes()) top = Math.max(top, bb.getMaxY());
        return Math.max(0, top - 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent e) {
        // only players get in (a vehicle's own chair sits on its carrier)
        if (isVehiclePart(e.getMount()) && !(e.getEntity() instanceof Player) && !isVehiclePart(e.getEntity())) e.setCancelled(true);
    }

    /** Ctrl = siren (patrol car), once per press (DriveInput.presses). Space (the horn) is read every tick: see horn. */
    @EventHandler
    public void onInput(PlayerInputEvent e) {
        Player p = e.getPlayer();
        DriveInput now = DriveInput.of(e.getInput());
        DriveInput before = inputs.put(p.getUniqueId(), now);
        Long id = partOf(p.getVehicle());
        LiveVehicle v = id == null ? null : live.get(id);
        if (v == null || v.seatOf(p) != 0) return;
        for (DriveInput.Press press : DriveInput.presses(before, now, v.spec.lights() != null)) {
            if (press == DriveInput.Press.SIREN) {
                v.siren = !v.siren;
                HudModule.flash(p, Lang.mm(v.siren ? "vehicle.siren-on" : "vehicle.siren-off"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pulling a hand cart (CartPull, PullSpeed)
    // ------------------------------------------------------------------------------------------

    /** The quiet "let go" message (a flash); every other reason to let go is a chat line. */
    private static final String LET_GO = "vehicle.pull-let-go";

    /** A right-click on a cart: its puller lets go; sneaking: the bed (the handles: pick up); else take the handles. */
    private void cartClick(LiveVehicle v, Player p, Entity clicked) {
        if (p.getUniqueId().equals(v.puller)) {
            release(v, LET_GO);
            return;
        }
        if (p.isSneaking()) {
            if (v.spec.cargo() == null || clicked.equals(v.handle)) pickUp(v, p);
            else openCargo(v, p, 0);
            return;
        }
        takeHandles(v, p);
    }

    private void takeHandles(LiveVehicle v, Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        Long other = pulling.get(p.getUniqueId());
        if (other != null && other != v.id) {
            refuse(p, Lang.mm("vehicle.pull-already"));
            return;
        }
        if (v.puller != null) {
            refuse(p, Lang.mm("vehicle.pull-taken"));
            return;
        }
        if (!mayDrive(v, p)) {
            refuse(p, Lang.mm("vehicle.pull-denied", "owner", v.ownerName));
            return;
        }
        if (v.cargoViewer != null) {
            refuse(p, Lang.mm("vehicle.cargo-busy"));
            return;
        }
        v.puller = p.getUniqueId();
        v.pullerSprinting = p.isSprinting();
        v.rolled = 0;
        pulling.put(p.getUniqueId(), v.id);
        PullSpeed.apply(p.getAttribute(Attribute.MOVEMENT_SPEED), v.spec.pull().slowdown(), v.pullerSprinting);
        if (v.pullerSprinting) noSprint(p);
        v.world.playSound(at(v), Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE, SoundCategory.NEUTRAL, 0.5f, 0.8f);
        HudModule.flash(p, Lang.mm("vehicle.pulling", "percent", Math.round(v.spec.pull().slowdown() * 100)));
    }

    /** The cart's puller, or null; someone who can no longer pull it (gone, dead, elsewhere, riding) lets go. */
    private Player puller(LiveVehicle v) {
        if (v.puller == null) return null;
        Player p = Bukkit.getPlayer(v.puller);
        if (p == null || !p.isValid() || p.isDead() || !p.getWorld().equals(v.world) || p.isInsideVehicle()
                || p.getGameMode() == GameMode.SPECTATOR) {
            release(v, p == null ? null : "vehicle.pull-lost");
            return null;
        }
        return p;
    }

    /** After the cart followed its puller this tick: too far from the handles (stuck) = let go; sprinting gains nothing. */
    private void pulled(LiveVehicle v, Player p) {
        Location at = p.getLocation();
        if (CartPull.tooFar(v.st, v.spec, at.getX(), at.getY(), at.getZ())) {
            release(v, "vehicle.pull-stuck");
            return;
        }
        boolean sprinting = p.isSprinting();
        if (sprinting) noSprint(p);
        if (sprinting != v.pullerSprinting) {
            v.pullerSprinting = sprinting;
            PullSpeed.apply(p.getAttribute(Attribute.MOVEMENT_SPEED), v.spec.pull().slowdown(), sprinting);
        }
    }

    /**
     * Stops a puller's sprint: the client sprints on its own, and only stops (and cannot start again) while it thinks
     * its food is 6 or less, so it is told that (the real food is unchanged, and the next health update or letting go
     * tells it the truth; the server's HUD shows the real one). Meanwhile the speed modifier takes the sprint's bonus away.
     */
    private static void noSprint(Player p) {
        if (p.getAllowFlight()) return;   // flying modes sprint whatever the food; the modifier is enough
        p.sendHealthUpdate(p.getHealth(), Math.min(6, p.getFoodLevel()), p.getSaturation());
    }

    /**
     * The puller lets go: their speed comes back (the modifier off, their real food re-sent) and the cart stops where
     * it is (it tips back onto its handles). key: what they are told (null = nothing; LET_GO = a flash, else chat).
     */
    void release(LiveVehicle v, String key) {
        if (v.puller == null) return;
        UUID id = v.puller;
        v.puller = null;
        v.pullerSprinting = false;
        v.st.speed = 0;
        v.dirty = true;
        pulling.remove(id, v.id);
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        PullSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        if (p.isOnline()) p.sendHealthUpdate();
        if (key == null || !p.isOnline()) return;
        if (key.equals(LET_GO)) {
            HudModule.flash(p, Lang.mm(key));
            v.world.playSound(at(v), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, SoundCategory.NEUTRAL, 0.4f, 0.7f);
        } else {
            p.sendMessage(Lang.msg(key));
        }
    }

    /** Whatever cart this player pulls, they let go of it. */
    private void releasePlayer(Player p, String key) {
        Long id = pulling.get(p.getUniqueId());
        if (id == null) return;
        LiveVehicle v = live.get(id);
        if (v != null && p.getUniqueId().equals(v.puller)) {
            release(v, key);
        } else {
            pulling.remove(p.getUniqueId());
            PullSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        }
    }

    /** A cart rolling: now and then a quiet wooden roll (vehicles.yml pull.sound), by the distance it made. */
    private void rolling(LiveVehicle v, double moved) {
        VehicleSpec.Roll r = v.spec.pull().roll();
        if (r == null) return;
        v.rolled += moved;
        if (v.rolled < r.every()) return;
        v.rolled %= r.every();
        float pitch = r.pitch() * (0.94f + 0.12f * (float) Math.random());
        v.world.playSound(at(v), r.sound(), SoundCategory.NEUTRAL, r.volume(), Math.clamp(pitch, VehicleSounds.MIN_PITCH, VehicleSounds.MAX_PITCH));
    }

    /** Tips a cart onto its handles (let go) or levels it (pulled), turning smoothly over a few ticks. */
    private void tiltTo(LiveVehicle v, float tilt) {
        v.tiltShown = tilt;
        int ticks = 6;
        if (v.body != null) {
            v.body.setInterpolationDelay(0);
            v.body.setInterpolationDuration(ticks);
            v.body.setTransformation(body(v, tilt));
        }
        if (v.load != null && v.spec.cargo() != null) {
            v.load.setInterpolationDelay(0);
            v.load.setInterpolationDuration(ticks);
            v.load.setTransformation(tilted(v.spec, v.spec.cargo().at(), 1, tilt));
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && pulling.containsKey(e.getPlayer().getUniqueId())) releasePlayer(e.getPlayer(), LET_GO);
    }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent e) {
        if (e.isSprinting() && pulling.containsKey(e.getPlayer().getUniqueId())) noSprint(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        releasePlayer(e.getPlayer(), null);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        releasePlayer(e.getPlayer(), "vehicle.pull-lost");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!pulling.containsKey(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (!from.getWorld().equals(to.getWorld()) || from.distanceSquared(to) > 1) releasePlayer(e.getPlayer(), "vehicle.pull-lost");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() == GameMode.SPECTATOR) releasePlayer(e.getPlayer(), "vehicle.pull-lost");
    }

    /** A puller getting into anything (a boat, a horse, a car's seat) lets go. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPullerMount(EntityMountEvent e) {
        if (e.getEntity() instanceof Player p && pulling.containsKey(p.getUniqueId())) releasePlayer(p, null);
    }

    // ------------------------------------------------------------------------------------------
    // Sounds: horn and engine (VehicleSounds)
    // ------------------------------------------------------------------------------------------

    /** Players near the vehicle who hear a sound of this volume (Minecraft: 16 blocks, or 16 x volume above 1). */
    private List<Player> listeners(LiveVehicle v, float volume) {
        double range = 16 * Math.max(1, volume) + 2;
        Location c = at(v);
        List<Player> out = new ArrayList<>();
        for (Player p : v.world.getPlayers()) if (p.getLocation().distanceSquared(c) <= range * range) out.add(p);
        return out;
    }

    /** Plays a sound bound to the body (it moves with the vehicle) to everyone in range; returns who was sent it. */
    private List<Player> play(LiveVehicle v, String key, float volume, float pitch) {
        List<Player> to = listeners(v, volume);
        boolean bound = v.body != null && v.body.isValid();
        for (Player p : to) {
            if (bound) p.playSound(v.body, key, SoundCategory.NEUTRAL, volume, pitch);
            else p.playSound(at(v), key, SoundCategory.NEUTRAL, volume, pitch);
        }
        return to;
    }

    /** Stops a sound for everyone who was sent it. */
    private static void stop(Set<UUID> heard, String key) {
        for (UUID id : heard) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.stopSound(key, SoundCategory.NEUTRAL);
        }
        heard.clear();
    }

    /** The horn this tick: held = the driver holds space. */
    private void horn(LiveVehicle v, boolean held) {
        VehicleSpec.Horn h = v.spec.horn();
        if (h == null) return;
        switch (v.horn.tick(held, ticks, settings.horn())) {
            case START -> {
                if (settings.customHorn()) for (Player p : play(v, h.sound(), h.volume(), 1f)) v.hornHeard.add(p.getUniqueId());
                else honk(v, h);
            }
            case STOP -> hornOff(v, h, true);
            case NONE -> { }
        }
    }

    /** Stops the horn for everyone who was sent it and (tail) plays its short release. */
    private void hornOff(LiveVehicle v, VehicleSpec.Horn h, boolean tail) {
        if (v.hornHeard.isEmpty()) return;   // the vanilla horn: nothing to stop
        stop(v.hornHeard, h.sound());
        if (tail && h.release() != null) play(v, h.release(), h.volume(), 1f);
    }

    /**
     * Without the pack's sounds (vehicles.horn.custom-sound: false): the vanilla sound once or a few times (빵빵) per
     * press; holding does not make it longer.
     */
    private void honk(LiveVehicle v, VehicleSpec.Horn h) {
        v.world.playSound(at(v), h.vanilla(), SoundCategory.NEUTRAL, h.volume(), h.pitch());
        for (int i = 1; i < h.times(); i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (live.get(v.id) == v) v.world.playSound(at(v), h.vanilla(), SoundCategory.NEUTRAL, h.volume(), h.pitch());
            }, (long) i * h.gap());
        }
    }

    /** The engine while someone drives: the next segment as the last one starts fading, at the speed's pitch. */
    private void engine(LiveVehicle v, Player driver) {
        VehicleSpec.Engine e = v.spec.engine();
        if (e == null || driver == null || !settings.engine()) {
            if (v.engineOn) engineOff(v);
            return;
        }
        if (v.engineOn && ticks < v.engineAt) return;
        double top = Math.min(v.spec.speed(), settings.maxSpeed());
        float pitch = VehicleSounds.enginePitch(v.st.speed, top, e);
        float volume = VehicleSounds.engineVolume(v.st.speed, top, e, settings.engineVolume());
        v.engineOn = true;
        v.engineAt = ticks + VehicleSounds.engineGap(e.segmentTicks(), pitch);
        if (volume > 0.005f) for (Player p : play(v, e.sound(), volume, pitch)) v.engineHeard.add(p.getUniqueId());
    }

    private void engineOff(LiveVehicle v) {
        v.engineOn = false;
        VehicleSpec.Engine e = v.spec.engine();
        if (e != null) stop(v.engineHeard, e.sound());
        v.engineHeard.clear();
    }

    /** Every sound of the vehicle stops now (tail: a sounding horn plays its release). */
    private void quiet(LiveVehicle v, boolean tail) {
        VehicleSpec.Horn h = v.spec.horn();
        v.horn.cut(ticks, settings.horn());
        if (h != null) hornOff(v, h, tail);
        engineOff(v);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        releasePlayer(p, null);
        lastClick.remove(p.getUniqueId());
        inputs.remove(p.getUniqueId());
        Long id = partOf(p.getVehicle());
        LiveVehicle v = id == null ? null : live.get(id);
        if (v != null) {
            int seat = v.seatOf(p);
            if (seat >= 0) leave(v, seat);   // they log in next time beside the vehicle, not inside it
        }
    }

    @EventHandler
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (isVehiclePart(e.getAttacked())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (isVehiclePart(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PullSpeed.remove(e.getPlayer().getAttribute(Attribute.MOVEMENT_SPEED));   // transient, but never left on anyone
        deliverOwed(e.getPlayer());
    }

    // ------------------------------------------------------------------------------------------
    // Picking up, sending back
    // ------------------------------------------------------------------------------------------

    private void pickUp(LiveVehicle v, Player p) {
        boolean own = p.getUniqueId().equals(v.owner);
        VehicleAccess.PickUp r = VehicleAccess.pickUp(v.owner, p.getUniqueId(), admin(p), v.hasRiders(), v.puller != null, v.cargoEmpty(),
                v.cargoViewer != null);
        switch (r) {
            case NOT_OWNER -> refuse(p, Lang.mm("vehicle.not-owner", "owner", v.ownerName));
            case RIDERS -> refuse(p, Lang.mm("vehicle.riders"));
            case PULLED -> refuse(p, Lang.mm("vehicle.pull-taken"));
            case VIEWER -> refuse(p, Lang.mm("vehicle.cargo-busy"));
            case CARGO -> refuse(p, Lang.mm("vehicle.cargo-not-empty"));
            case OK -> {
                if (own && !room(p)) {
                    refuse(p, Lang.mm("vehicle.no-room-inventory"));
                    return;
                }
                if (!own) {
                    recall(v.id, p);   // an admin: back to its owner
                    return;
                }
                long token = newToken();
                CustomItem item = v.item;
                VehicleStore.Row back = new VehicleStore.Row(v.id, item.key().asString(), v.owner, v.ownerName, VehicleStore.State.WORLD, 0,
                        v.pos(), encode(v.cargo), 0, 0);
                store.move(v.id, v.pos(), System.currentTimeMillis());
                despawn(v, false);
                busy.add(v.id);
                store.takeIn(v.id, token, false, System.currentTimeMillis()).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(back.id());
                    if (err != null || !Boolean.TRUE.equals(ok)) {
                        parked.put(back.id(), back);   // still in the world: show it again
                        spawnSoon = true;
                        p.sendMessage(Lang.msg("vehicle.error"));
                        return;
                    }
                    ItemStack it = itemFor(item, back.id(), token);
                    if (p.isOnline()) give(p, it);
                    else deliver(back.owner(), back.id(), token, it, false);
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.9f);
                    p.sendMessage(Lang.msg("vehicle.picked-up", "vehicle", label(item)));
                }));
            }
        }
    }

    /**
     * Sends a vehicle back to its owner as an item (/관리자 탈것 회수, an admin picking up someone's vehicle, one fallen out
     * of the world). Wherever it is: in the world (shown or not), or an item (a new token: the old item stops working).
     * Its bed's load stays with it.
     */
    void recall(long id, CommandSender by) {
        if (busy.contains(id)) {
            if (by != null) by.sendMessage(Lang.msg("vehicle.admin-busy", "id", id));
            return;
        }
        LiveVehicle v = live.get(id);
        if (v != null) {
            syncCargo(v);
            store.move(id, v.pos(), System.currentTimeMillis());
            store.cargo(id, encode(v.cargo), System.currentTimeMillis());
            despawn(v, false);
        }
        VehicleStore.Row parkedRow = parked.remove(id);
        busy.add(id);
        long token = newToken(), now = System.currentTimeMillis();
        boolean inWorld = v != null || parkedRow != null;
        var step = inWorld ? store.takeIn(id, token, true, now) : store.reissue(id, token, now);
        step.thenCompose(ok -> store.get(id).thenApply(row -> Boolean.TRUE.equals(ok) ? row : null))
                .whenComplete((row, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (err != null || row == null) {
                        if (parkedRow != null) parked.put(id, parkedRow);
                        if (v != null) {
                            parked.put(id, new VehicleStore.Row(id, v.item.key().asString(), v.owner, v.ownerName, VehicleStore.State.WORLD, 0,
                                    v.pos(), encode(v.cargo), 0, 0));
                            spawnSoon = true;
                        }
                        if (by != null) by.sendMessage(Lang.msg("vehicle.admin-unknown", "id", id));
                        return;
                    }
                    Optional<CustomItem> item = plugin.content().item(row.item()).filter(CustomItem::isVehicle);
                    if (item.isEmpty()) {
                        if (by != null) by.sendMessage(Lang.msg("vehicle.admin-unknown", "id", id));
                        return;
                    }
                    deliver(row.owner(), id, token, itemFor(item.get(), id, token), true);
                    if (by != null) by.sendMessage(Lang.msg("vehicle.returned-owner", "id", id, "owner", row.ownerName()));
                }));
    }

    /** Hands an owed vehicle's item to its owner: the mailbox (economy server), else now if online with room, else on join. */
    private void deliver(UUID owner, long id, long token, ItemStack it, boolean byAdmin) {
        BiConsumer<UUID, ItemStack> box = mailbox;
        Player o = Bukkit.getPlayer(owner);
        if (box == null && (o == null || !room(o))) {
            if (o != null) o.sendMessage(Lang.msg("vehicle.owed-waiting"));
            return;   // stays OWED: handed over when they join with room
        }
        store.delivered(id, token, System.currentTimeMillis()).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || !Boolean.TRUE.equals(ok)) return;
            if (box != null) box.accept(owner, it);
            else {
                Player now = Bukkit.getPlayer(owner);
                if (now != null) give(now, it);
            }
            Player now = Bukkit.getPlayer(owner);
            if (now != null && byAdmin) now.sendMessage(Lang.msg("vehicle.returned", "id", id));
        }));
    }

    private void deliverOwed(Player p) {
        store.owed(p.getUniqueId()).thenAccept(rows -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (VehicleStore.Row r : rows) {
                plugin.content().item(r.item()).filter(CustomItem::isVehicle)
                        .ifPresent(item -> deliver(r.owner(), r.id(), r.token(), itemFor(item, r.id(), r.token()), true));
            }
        }));
    }

    // ------------------------------------------------------------------------------------------
    // The storage bed
    // ------------------------------------------------------------------------------------------

    /** The bed's chest view: one page (45 slots) and a row of buttons. */
    static final class CargoHolder implements InventoryHolder {
        final long vehicle;
        final int page;
        Inventory inventory;

        CargoHolder(long vehicle, int page) {
            this.vehicle = vehicle;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final int PREV = 45, INFO = 49, NEXT = 53;

    private void openCargo(LiveVehicle v, Player p, int page) {
        VehicleSpec.Cargo c = v.spec.cargo();
        UUID id = p.getUniqueId();
        boolean other = v.cargoViewer != null && !v.cargoViewer.equals(id);
        switch (VehicleAccess.openCargo(v.owner, id, admin(p), trusted(v.owner, id), granted(v.owner, id), other, v.st.speed != 0, v.puller != null)) {
            case DENIED -> {
                refuse(p, Lang.mm("vehicle.cargo-denied", "owner", v.ownerName));
                return;
            }
            case BUSY -> {
                refuse(p, Lang.mm("vehicle.cargo-busy"));
                return;
            }
            case PULLED -> {
                refuse(p, Lang.mm("vehicle.cargo-pulled"));
                return;
            }
            case MOVING -> {
                refuse(p, Lang.mm("vehicle.cargo-moving"));
                return;
            }
            case OK -> { }
        }
        syncCargo(v);
        if (v.cargo == null) v.cargo = resized(null, v.spec);
        int pages = CargoPages.pages(v.cargo.length);   // a load left past a smaller bed (fewer slots in the file) stays reachable
        page = CargoPages.clamp(page, pages);
        CargoHolder h = new CargoHolder(v.id, page);
        Inventory inv = Bukkit.createInventory(h, 54, Lang.mm("vehicle.cargo-title", "vehicle", Text.plain(label(v.item)), "page", page + 1,
                "pages", pages));
        h.inventory = inv;
        List<ItemStack> shown = CargoPages.page(v.cargo, page);
        for (int i = 0; i < VehicleSpec.Cargo.PAGE; i++) inv.setItem(i, shown.get(i));
        for (int i = PREV; i <= NEXT; i++) inv.setItem(i, button(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        if (page > 0) inv.setItem(PREV, button(Material.ARROW, Lang.raw("vehicle.cargo-prev"), null));
        if (page < pages - 1) inv.setItem(NEXT, button(Material.ARROW, Lang.raw("vehicle.cargo-next"), null));
        inv.setItem(INFO, button(Material.CHEST, Lang.raw("vehicle.cargo-info", "used", v.cargoUsed(), "slots", c.slots()), null));
        v.cargoViewer = p.getUniqueId();
        v.cargoInv = inv;
        v.cargoPage = page;
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.6f, 1.0f);
    }

    private static ItemStack button(Material m, String name, String lore) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.itemName(Text.item(name));
            if (lore != null) meta.lore(List.of(Text.item(lore)));
            meta.getPersistentDataContainer().set(BUTTON, PersistentDataType.BYTE, (byte) 1);
            meta.setMaxStackSize(1);
        });
        return s;
    }

    /** What the open page holds goes into the bed (and the shown load follows). Saved when it changed. */
    private void syncCargo(LiveVehicle v) {
        if (v.cargo == null || v.cargoInv == null) return;
        Inventory inv = v.cargoInv;
        boolean changed = CargoPages.store(v.cargo, v.cargoPage, i -> {
            ItemStack now = inv.getItem(i);
            return now == null || now.isEmpty() ? null : now.clone();
        });
        if (!changed) return;
        store.cargo(v.id, encode(v.cargo), System.currentTimeMillis());
        int stage = stageOf(v);
        if (stage != v.stage) {
            v.stage = stage;
            if (v.load != null) v.load.setItemStack(model(stageModel(v)));
        }
        ItemStack info = v.cargoInv.getItem(INFO);
        if (info != null && v.spec.cargo() != null) {
            v.cargoInv.setItem(INFO, button(Material.CHEST, Lang.raw("vehicle.cargo-info", "used", v.cargoUsed(), "slots", v.spec.cargo().slots()), null));
        }
    }

    private void closeCargo(LiveVehicle v) {
        if (v.cargoViewer == null) return;
        syncCargo(v);
        Player p = Bukkit.getPlayer(v.cargoViewer);
        Inventory inv = v.cargoInv;
        v.cargoViewer = null;
        v.cargoInv = null;
        if (p != null && inv != null && p.getOpenInventory().getTopInventory() == inv) p.closeInventory();
    }

    /** The bed is closed for whoever has it open once they walk away or the vehicle moves. */
    private void watchCargo(LiveVehicle v) {
        Player p = Bukkit.getPlayer(v.cargoViewer);
        if (p == null || p.getOpenInventory().getTopInventory() != v.cargoInv) {
            syncCargo(v);
            v.cargoViewer = null;
            v.cargoInv = null;
            return;
        }
        if (v.st.speed != 0 || v.puller != null || !p.getWorld().equals(v.world) || p.getLocation().distanceSquared(at(v)) > 64) closeCargo(v);
    }

    private LiveVehicle cargoOf(Inventory top) {
        if (!(top.getHolder(false) instanceof CargoHolder h)) return null;
        LiveVehicle v = live.get(h.vehicle);
        return v != null && v.cargoInv == top ? v : null;
    }

    @EventHandler
    public void onCargoClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof CargoHolder h)) return;
        LiveVehicle v = cargoOf(top);
        if (v == null) {
            e.setCancelled(true);   // a page that is no longer the bed's (closed under it)
            return;
        }
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);   // could gather the buttons
            return;
        }
        if (e.getClickedInventory() == top && e.getSlot() >= PREV) {
            e.setCancelled(true);
            int slot = e.getSlot();
            if (slot == PREV || slot == NEXT) {
                Player p = (Player) e.getWhoClicked();
                int to = h.page + (slot == NEXT ? 1 : -1);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (cargoOf(top) != v) return;
                    syncCargo(v);
                    v.cargoViewer = null;
                    openCargo(v, p, to);
                });
            }
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (cargoOf(top) == v) syncCargo(v);
        });
    }

    @EventHandler
    public void onCargoDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof CargoHolder)) return;
        LiveVehicle v = cargoOf(top);
        for (int raw : e.getRawSlots()) {
            if (v == null || (raw < top.getSize() && raw >= PREV)) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (cargoOf(top) == v) syncCargo(v);
        });
    }

    @EventHandler
    public void onCargoClose(InventoryCloseEvent e) {
        LiveVehicle v = cargoOf(e.getInventory());
        if (v == null) return;
        syncCargo(v);
        v.cargoViewer = null;
        v.cargoInv = null;
        if (e.getPlayer() instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_BARREL_CLOSE, 0.6f, 1.0f);
    }

    private static int stageOf(LiveVehicle v) {
        VehicleSpec.Cargo c = v.spec.cargo();
        return c == null ? 0 : CargoStages.stage(v.cargoUsed(), c.slots(), c.stages().size());
    }

    private static NamespacedKey stageModel(LiveVehicle v) {
        VehicleSpec.Cargo c = v.spec.cargo();
        return c == null || v.stage <= 0 ? null : c.stages().get(v.stage - 1);
    }

    /** The bed's contents for the database (null = empty). */
    static byte[] encode(ItemStack[] cargo) {
        if (cargo == null) return null;
        boolean any = false;
        ItemStack[] out = new ItemStack[cargo.length];
        for (int i = 0; i < cargo.length; i++) {
            out[i] = cargo[i] == null || cargo[i].isEmpty() ? ItemStack.empty() : cargo[i];
            any |= !out[i].isEmpty();
        }
        return any ? ItemStack.serializeItemsAsBytes(out) : null;
    }

    private static ItemStack[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new ItemStack[0];
        ItemStack[] in = ItemStack.deserializeItemsFromBytes(bytes);
        for (int i = 0; i < in.length; i++) if (in[i] != null && in[i].isEmpty()) in[i] = null;
        return in;
    }

    /** The bed sized for the vehicle (never smaller than what it holds: fewer pages in the file must not lose a load). */
    private static ItemStack[] resized(ItemStack[] cargo, VehicleSpec s) {
        return CargoPages.resized(cargo, s.cargo() == null ? 0 : s.cargo().slots(), ItemStack[]::new);
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 탈것
    // ------------------------------------------------------------------------------------------

    private void command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        switch (sub) {
            case "목록", "list" -> list(s, a.length > 1 ? a[1] : null);
            case "회수", "recall" -> {
                long id = a.length > 1 ? parseId(a[1]) : -1;
                if (id <= 0) s.sendMessage(Lang.msg("vehicle.admin-usage"));
                else recall(id, s);
            }
            case "제거", "remove" -> remove(s, a.length > 1 ? a[1] : null);
            default -> s.sendMessage(Lang.msg("vehicle.admin-usage"));
        }
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s.startsWith("#") ? s.substring(1) : s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void list(CommandSender s, String player) {
        if (player != null) {
            dev.stsv.core.nickname.Names.find(player, found -> {
                if (found.isEmpty()) s.sendMessage(Lang.msg("vehicle.admin-no-player", "player", player));
                else listRows(s, found.get());
            });
        } else listRows(s, null);
    }

    private void listRows(CommandSender s, UUID owner) {
        store.list(owner, 100).thenAccept(rows -> Bukkit.getScheduler().runTask(plugin, () -> {
            s.sendMessage(Lang.msg("vehicle.admin-list", "count", rows.size()));
            for (VehicleStore.Row r : rows) {
                String name = plugin.content().item(r.item()).map(i -> i.name() == null ? r.item() : i.name()).orElse(r.item());
                String where = r.state() == VehicleStore.State.WORLD && r.pos() != null
                        ? Lang.raw("vehicle.admin-where", "world", r.pos().worldName(), "x", (long) Math.floor(r.pos().x()),
                        "y", (long) Math.floor(r.pos().y()), "z", (long) Math.floor(r.pos().z()))
                        + (live.containsKey(r.id()) ? Lang.raw("vehicle.admin-shown") : "")
                        : Lang.raw(r.state() == VehicleStore.State.OWED ? "vehicle.admin-owed" : "vehicle.admin-item");
                s.sendMessage(Text.mm(Lang.raw("vehicle.admin-line", "id", r.id(), "name", name, "owner", r.ownerName(), "where", where)));
            }
        }));
    }

    private void remove(CommandSender s, String radiusText) {
        if (!(s instanceof Player p)) {
            s.sendMessage(Lang.msg("common.players-only"));
            return;
        }
        int radius = 8;
        if (radiusText != null) {
            try {
                radius = Integer.parseInt(radiusText);
            } catch (NumberFormatException e) {
                radius = -1;
            }
        }
        if (radius < 1 || radius > 64) {
            s.sendMessage(Lang.msg("vehicle.admin-usage"));
            return;
        }
        Location here = p.getLocation();
        double r2 = (double) radius * radius;
        int removed = 0, strays = 0;
        for (LiveVehicle v : List.copyOf(live.values())) {
            if (!v.world.equals(here.getWorld()) || at(v).distanceSquared(here) > r2 || busy.contains(v.id)) continue;
            syncCargo(v);
            if (v.cargo != null) for (ItemStack it : v.cargo) if (it != null && !it.isEmpty()) v.world.dropItemNaturally(at(v), it);
            v.cargo = null;
            despawn(v, false);
            store.delete(v.id);
            plugin.getLogger().info("[탈것] " + p.getName() + " 님이 #" + v.id + " (" + v.item.key() + ", 주인 " + v.ownerName + ") 를 지움");
            removed++;
        }
        // entities of vehicles nobody shows any more (should not happen; a crash between ticks of a reload, a bug)
        for (Entity e : here.getWorld().getNearbyEntities(here, radius, radius, radius)) {
            Long id = partOf(e);
            if (id != null && !live.containsKey(id)) {
                e.eject();
                e.remove();
                strays++;
            }
        }
        s.sendMessage(Lang.msg("vehicle.admin-removed", "count", removed, "strays", strays, "radius", radius));
    }
}
