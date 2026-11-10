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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
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
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * (content/stsv/items/vehicles.yml: 경찰차, 1톤 트럭, 골프 카트; README "탈것").
 *
 * <h2>How it is shown and kept</h2>
 * The database ({@link VehicleStore}) holds every vehicle; nothing of it is saved with a chunk. While a vehicle's chunk is
 * loaded it is a {@link LiveVehicle}: an item display for the body, one per wheel (spun and, at the front, steered through
 * their display transformation), the light bar and the load, a few click boxes (interaction entities) along it and, for
 * each rider, a seat (an item display without an item) they ride. The chunk unloads (or the server stops): its position
 * is saved and the entities go. A crash leaves nothing behind and nothing doubled; the vehicle is where it was last saved.
 *
 * <h2>Moving smoothly</h2>
 * Every tick the driver's keys ({@link Player#getCurrentInput()}) go through {@link VehiclePhysics}; the body, wheels and
 * parts are then teleported to the new place and yaw, and the client glides between ticks (display teleport duration
 * and transformation interpolation: vehicles.smoothing-ticks). The seats are teleported with their riders on them
 * (Paper keeps passengers on a teleport, see {@link #carry}): a rider stays seated, their client places them on the
 * gliding seat, and their camera stays their own (a display entity does not steer or turn its passenger).
 *
 * <h2>Rules</h2> {@link VehicleAccess} (who drives, opens the bed, picks up), {@link VehicleSeats} (which seat, where to step
 * out). Vehicles cannot be hurt and hurt nobody; mobs cannot get in.
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
    private BiPredicate<UUID, UUID> trust;
    private BiConsumer<UUID, ItemStack> mailbox;
    private BukkitTask task;
    private long ticks;
    private boolean spawnSoon;

    /** config.yml vehicles.* */
    record Settings(double maxSpeed, int smoothing, float viewRange, boolean culling, int saveTicks, boolean sirenSound, String sirenKey,
                    float sirenVolume, float pitchHigh, float pitchLow, int sirenInterval, int flashTicks, int hornCooldown,
                    double yawOffset) {}

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
        // a plugin reload: the last run's entities (never saved, so only ever after a reload in the same run)
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (isVehiclePart(e)) e.remove();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), 1L, 1L);
        plugin.admin().add(ADMIN_OWNER, "탈것", "목록 [플레이어] | 회수 <번호> | 제거 [반경]",
                "탈것 목록 / 주인에게 아이템으로 돌려주기 / 주변 탈것 지우기", this::command,
                (s, a) -> a.length == 1 ? List.of("목록", "회수", "제거") : a.length == 2 && a[0].equals("제거") ? List.of("8", "16", "32") : List.of());
        plugin.admin().report("STSV 탈것", (s, a) -> s.sendMessage(Lang.mm("vehicle.report", "live", live.size(), "parked", parked.size())));
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
            syncCargo(v);
            ItemStack[] cargo = v.cargo;
            v.item = item.get();
            if (v.seats.length != v.item.vehicle().seats().size()) {
                for (int i = 0; i < v.seats.length; i++) leave(v, i);
                v.seats = new ItemDisplay[v.item.vehicle().seats().size()];
            }
            v.spec = v.item.vehicle();
            v.cargo = resized(cargo, v.spec);
            removeParts(v, false);
            spawnParts(v);
        }
    }

    @Override
    public void disable() {
        for (LiveVehicle v : List.copyOf(live.values())) despawn(v, true);
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
                Math.max(1, plugin.getConfig().getInt("vehicles.siren.flash-ticks")), Math.max(1, plugin.getConfig().getInt("vehicles.horn-cooldown")),
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
        place(p, item.get(), hand, e.getHand(), clicked, e.getBlockFace());
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
        VehiclePhysics.State st = new VehiclePhysics.State(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, VehiclePhysics.wrap(p.getYaw()));
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
            w.playSound(new Location(w, st.x, st.y, st.z), Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 1.2f);
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

    /** How far from its centre a vehicle's entities reach (click boxes, seats), blocks. */
    private static double reach(VehicleSpec s) {
        return Math.hypot(s.length(), s.width()) / 2 + 1;
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
        float k = s.bodyScale();
        v.body = display(v, v.item.create(1), new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(k, k, k), new Quaternionf()));
        for (int i = 0; i < s.wheels().size(); i++) v.wheels.add(display(v, model(s.wheelModel()), wheel(v, i)));
        if (s.lights() != null) {
            v.lightsShown = v.siren ? 0 : -1;
            v.lights = display(v, model(v.siren ? s.lights().flash().getFirst() : s.lights().dark()), part(s.lights().at()));
        }
        if (s.cargo() != null) v.load = display(v, model(stageModel(v)), part(s.cargo().at()));
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
    }

    private static Transformation part(VehicleSpec.Vec at) {
        return new Transformation(local(at), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());
    }

    private static Transformation wheel(LiveVehicle v, int i) {
        VehicleSpec.Wheel w = v.spec.wheels().get(i);
        Quaternionf steer = w.steer() ? new Quaternionf().rotateY((float) Math.toRadians(v.st.steer)) : new Quaternionf();
        return new Transformation(local(w.at()), steer, new Vector3f(1, 1, 1), new Quaternionf().rotateX((float) v.st.roll));
    }

    /** Removes the drawn parts (and the seats too when seats = true: riders step out first). */
    private void removeParts(LiveVehicle v, boolean seats) {
        if (seats) for (int i = 0; i < v.seats.length; i++) leave(v, i);
        if (v.body != null) v.body.remove();
        if (v.lights != null) v.lights.remove();
        if (v.load != null) v.load.remove();
        v.wheels.forEach(Entity::remove);
        v.hits.forEach(Entity::remove);
        v.body = v.lights = v.load = null;
        v.wheels.clear();
        v.hits.clear();
    }

    /**
     * Stops showing a vehicle: riders step out, an open bed is closed, entities go. save: it stays in the world (saved
     * where it is, shown again when its chunk loads); false: the caller takes it out of the world.
     */
    private void despawn(LiveVehicle v, boolean save) {
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
        if (v.body == null || !v.body.isValid() || v.hits.stream().anyMatch(h -> !h.isValid()) || v.wheels.stream().anyMatch(w -> !w.isValid())) {
            removeParts(v, false);
            spawnParts(v);
        }
        Player driver = v.rider(0);
        DriveInput in = driver != null ? DriveInput.of(driver.getCurrentInput()) : DriveInput.NONE;
        WorldTerrain terrain = new WorldTerrain(v.world);
        boolean active = driver != null || v.st.speed != 0 || !v.st.onGround || v.st.steer != 0;
        if (!active && (ticks + v.id) % 10 == 0 && !VehiclePhysics.supported(v.st, v.spec, terrain)) {
            v.st.onGround = false;   // the ground under it went: it falls
            active = true;
        }
        if (active) {
            double x = v.st.x, y = v.st.y, z = v.st.z, steer = v.st.steer, roll = v.st.roll;
            float yaw = v.st.yaw;
            VehiclePhysics.step(v.st, in, v.spec, terrain, settings.maxSpeed());
            if (v.st.bumped) v.world.playSound(at(v), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.NEUTRAL, 0.35f, 1.4f);
            boolean moved = Math.abs(v.st.x - x) + Math.abs(v.st.y - y) + Math.abs(v.st.z - z) > 1e-5 || v.st.yaw != yaw;
            if (moved) {
                move(v);
                v.dirty = true;
            }
            if (moved || v.st.steer != steer || v.st.roll != roll) {
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
        lights(v);
        if (v.cargoViewer != null) watchCargo(v);
        if (v.dirty && (ticks - v.savedAt >= settings.saveTicks() || (v.st.speed == 0 && driver == null))) save(v);
    }

    /** Puts every entity of the vehicle where it is now (the client glides there over the smoothing ticks). */
    private void move(LiveVehicle v) {
        Location at = at(v);
        v.body.teleport(at);
        for (ItemDisplay w : v.wheels) w.teleport(at);
        if (v.lights != null) v.lights.teleport(at);
        if (v.load != null) v.load.teleport(at);
        for (int i = 0; i < v.hits.size(); i++) {
            double[] c = VehiclePhysics.world(v.st.x, v.st.y, v.st.z, v.st.yaw, new VehicleSpec.Vec(0, 0, v.spec.squareOffset(i)));
            v.hits.get(i).teleport(new Location(v.world, c[0], c[1], c[2]));
        }
        for (int i = 0; i < v.seats.length; i++) {
            ItemDisplay seat = v.seats[i];
            if (seat == null) continue;
            if (!seat.isValid()) {
                v.seats[i] = null;
                continue;
            }
            carry(seat, seatLocation(v, i));
        }
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
        boolean mayDrive = VehicleAccess.mayDrive(v.owner, p.getUniqueId(), admin(p), trusted(v.owner, p.getUniqueId()));
        boolean[] taken = v.taken();
        int seat = VehicleSeats.pick(taken, mayDrive);
        if (seat < 0) {
            refuse(p, Lang.mm("vehicle.full"));
            return;
        }
        ItemDisplay s = v.world.spawn(seatLocation(v, seat), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setTeleportDuration(settings.smoothing());
            d.getPersistentDataContainer().set(PART, PersistentDataType.LONG, v.id);
            d.getPersistentDataContainer().set(SEAT, PersistentDataType.INTEGER, seat);
        });
        p.setRotation(v.st.yaw, p.getPitch());
        if (!s.addPassenger(p)) {
            s.remove();
            return;
        }
        v.seats[seat] = s;
        if (seat == 0) {
            HudModule.flash(p, Lang.mm("vehicle.driving", "keys", Lang.raw(v.spec.lights() != null ? "vehicle.keys-siren" : "vehicle.keys-horn")));
        } else if (!mayDrive && !taken[0]) {
            HudModule.flash(p, Lang.mm("vehicle.passenger", "owner", v.ownerName));
        }
    }

    /** A rider leaves a seat (it goes; they step out beside the vehicle, see onDismount). */
    private void leave(LiveVehicle v, int seat) {
        ItemDisplay s = seat < v.seats.length ? v.seats[seat] : null;
        if (s == null) return;
        Player p = v.rider(seat);
        v.seats[seat] = null;
        Location exit = exit(v, seat);
        ejecting.add(s.getUniqueId());
        s.eject();
        ejecting.remove(s.getUniqueId());
        s.remove();
        if (p != null && p.isOnline() && !p.isDead()) {
            exit.setYaw(p.getYaw());
            exit.setPitch(p.getPitch());
            p.teleport(exit);
        }
        if (seat == 0) v.siren = false;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getDismounted() instanceof ItemDisplay seat)) return;
        Long id = partOf(seat);
        Integer index = seat.getPersistentDataContainer().get(SEAT, PersistentDataType.INTEGER);
        if (id == null || index == null || ejecting.contains(seat.getUniqueId())) return;
        LiveVehicle v = live.get(id);
        Entity rider = e.getEntity();
        if (v != null && index < v.seats.length && v.seats[index] == seat) {
            v.seats[index] = null;
            if (index == 0) v.siren = false;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Location exit = v != null && live.containsKey(v.id) ? exit(v, index) : seat.getLocation();
            seat.remove();
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
        if (isVehiclePart(e.getMount()) && !(e.getEntity() instanceof Player)) e.setCancelled(true);
    }

    /** Space = horn, Ctrl = siren (patrol car), once per press (DriveInput.presses). */
    @EventHandler
    public void onInput(PlayerInputEvent e) {
        Player p = e.getPlayer();
        DriveInput now = DriveInput.of(e.getInput());
        DriveInput before = inputs.put(p.getUniqueId(), now);
        Long id = partOf(p.getVehicle());
        LiveVehicle v = id == null ? null : live.get(id);
        if (v == null || v.seatOf(p) != 0) return;
        for (DriveInput.Press press : DriveInput.presses(before, now, v.spec.lights() != null)) {
            switch (press) {
                case SIREN -> {
                    v.siren = !v.siren;
                    HudModule.flash(p, Lang.mm(v.siren ? "vehicle.siren-on" : "vehicle.siren-off"));
                }
                case HORN -> honk(v);
            }
        }
    }

    /** The horn: its sound once or a few times (빵빵), then a short rest before it sounds again. */
    private void honk(LiveVehicle v) {
        VehicleSpec.Horn h = v.spec.horn();
        if (h == null || ticks - v.lastHorn < settings.hornCooldown()) return;
        v.lastHorn = ticks;
        v.world.playSound(at(v), h.sound(), SoundCategory.NEUTRAL, h.volume(), h.pitch());
        for (int i = 1; i < h.times(); i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (live.get(v.id) == v) v.world.playSound(at(v), h.sound(), SoundCategory.NEUTRAL, h.volume(), h.pitch());
            }, (long) i * h.gap());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
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
        deliverOwed(e.getPlayer());
    }

    // ------------------------------------------------------------------------------------------
    // Picking up, sending back
    // ------------------------------------------------------------------------------------------

    private void pickUp(LiveVehicle v, Player p) {
        boolean own = p.getUniqueId().equals(v.owner);
        VehicleAccess.PickUp r = VehicleAccess.pickUp(v.owner, p.getUniqueId(), admin(p), v.hasRiders(), v.cargoEmpty(), v.cargoViewer != null);
        switch (r) {
            case NOT_OWNER -> refuse(p, Lang.mm("vehicle.not-owner", "owner", v.ownerName));
            case RIDERS -> refuse(p, Lang.mm("vehicle.riders"));
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
        if (!VehicleAccess.mayOpenCargo(v.owner, p.getUniqueId(), admin(p), trusted(v.owner, p.getUniqueId()))) {
            refuse(p, Lang.mm("vehicle.cargo-denied", "owner", v.ownerName));
            return;
        }
        if (v.cargoViewer != null && !v.cargoViewer.equals(p.getUniqueId())) {
            refuse(p, Lang.mm("vehicle.cargo-busy"));
            return;
        }
        if (v.st.speed != 0) {
            refuse(p, Lang.mm("vehicle.cargo-moving"));
            return;
        }
        syncCargo(v);
        page = Math.clamp(page, 0, c.pages() - 1);
        CargoHolder h = new CargoHolder(v.id, page);
        Inventory inv = Bukkit.createInventory(h, 54, Lang.mm("vehicle.cargo-title", "page", page + 1, "pages", c.pages()));
        h.inventory = inv;
        for (int i = 0; i < VehicleSpec.Cargo.PAGE; i++) inv.setItem(i, v.cargo[page * VehicleSpec.Cargo.PAGE + i]);
        for (int i = PREV; i <= NEXT; i++) inv.setItem(i, button(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        if (page > 0) inv.setItem(PREV, button(Material.ARROW, Lang.raw("vehicle.cargo-prev"), null));
        if (page < c.pages() - 1) inv.setItem(NEXT, button(Material.ARROW, Lang.raw("vehicle.cargo-next"), null));
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
        boolean changed = false;
        for (int i = 0; i < VehicleSpec.Cargo.PAGE; i++) {
            ItemStack now = v.cargoInv.getItem(i);
            now = now == null || now.isEmpty() ? null : now.clone();
            int at = v.cargoPage * VehicleSpec.Cargo.PAGE + i;
            ItemStack was = v.cargo[at];
            if (!java.util.Objects.equals(was, now)) {
                v.cargo[at] = now;
                changed = true;
            }
        }
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
        if (v.st.speed != 0 || !p.getWorld().equals(v.world) || p.getLocation().distanceSquared(at(v)) > 64) closeCargo(v);
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
        int slots = s.cargo() == null ? 0 : s.cargo().slots();
        int used = 0;
        if (cargo != null) for (int i = 0; i < cargo.length; i++) if (cargo[i] != null) used = i + 1;
        if (slots == 0 && used == 0) return null;
        ItemStack[] out = new ItemStack[Math.max(slots, used)];
        if (cargo != null) System.arraycopy(cargo, 0, out, 0, Math.min(cargo.length, out.length));
        return out;
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
