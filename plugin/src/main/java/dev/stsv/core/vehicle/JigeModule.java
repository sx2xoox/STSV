package dev.stsv.core.vehicle;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.content.ModelRef;
import dev.stsv.core.hud.HudModule;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 지게: the A-frame back carrier, worn instead of placed (content: an item with {@code jige:} and {@code equipment: chest},
 * content/stsv/items/vehicles_misc.yml; README "지게").
 *
 * <ul>
 *   <li><b>Worn</b> in the chestplate slot. Putting it on claims it ({@link JigeStore}: a new token), the wearer walks 10 %
 *       slower ({@link JigeSpeed}) and carries it on their back: the empty frame, a few bundles, more, a full load under a
 *       tarp, by how full it is (drawn in the cosmetics' back slot, over a back cosmetic: CosmeticModule#backOverride).</li>
 *   <li><b>Opened</b> by its wearer: sneak + right click with an empty hand (in the air, or at a block that does nothing),
 *       or /지게. One chest view of its slots; every change is saved to the database at once.</li>
 *   <li><b>Taken off</b> freely: the load stays in the database under its id and comes back when it is put on again
 *       ({@link JigeRules}). The item never holds the load; a copy of it opens nothing (its token is old).</li>
 *   <li><b>Death</b>: 지게 items are kept (not dropped); the load is untouched.</li>
 *   <li><b>On the ground</b> a 지게 item never burns, blows up or despawns; one lost anyway is made again by
 *       /관리자 지게 회수 (a new token: every old item of it is dead; the load stays with its number).</li>
 *   <li><b>Teleporting</b> while wearing it breaks its load's trip off at once (a seal on it is void): {@link JigeRules#teleportBreaks}.</li>
 *   <li><b>귀속</b>: a 지게 is its owner's whoever has the item ({@link VehicleOwnership}): only its owner, the players they
 *       allowed with /탈것 권한, the people of their land and admins can put it on. /탈것 양도 (holding it) gives it away.</li>
 * </ul>
 */
public final class JigeModule implements StsvModule, Listener, TabExecutor {

    /** On a 지게 item: its row and token (JigeStore). An item without them (made by an admin) gets a new row when worn. */
    static final NamespacedKey ITEM_ID = NamespacedKey.fromString("stsv:jige_id");
    static final NamespacedKey ITEM_TOKEN = NamespacedKey.fromString("stsv:jige_token");
    private static final SecureRandom RANDOM = new SecureRandom();

    private STSVPlugin plugin;
    private JigeStore store;
    private BukkitTask task;
    /** item -> its jige section */
    private final Map<NamespacedKey, JigeSpec> specs = new HashMap<>();
    /** (item, stage) -> the look on the back */
    private final Map<String, CustomItem> looks = new HashMap<>();

    /** A 지게 a player holds (claimed and worn): its load in memory, the view open on it. */
    static final class Session {
        final long id;
        final long token;
        final CustomItem item;
        final JigeSpec spec;
        final ItemStack[] load;
        int stage;
        Inventory view;
        /** moves on whenever the load changes (a dock sale takes out only what it showed: {@link #unload}) */
        long version;
        /** where the wearer was at the last check (the trip's odometer counts the way from there) */
        String lastWorld;
        double lastX, lastZ;
        /** 탈것의 느낌: the height at the last check, the slope walked, the load's share of the payload (at version shareAt) */
        double lastY = Double.NaN, grade, share;
        long shareAt = -1;

        Session(long id, long token, CustomItem item, JigeSpec spec, ItemStack[] load) {
            this.id = id;
            this.token = token;
            this.item = item;
            this.spec = spec;
            this.load = load;
        }

        int used() {
            int n = 0;
            for (ItemStack it : load) if (it != null && !it.isEmpty()) n++;
            return n;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    /** jige id -> who holds it */
    private final Map<Long, UUID> holders = new HashMap<>();
    /** players whose claim is on the way */
    private final Set<UUID> pending = new HashSet<>();
    /** stale items already told about (id:token), so a dead copy is not reported every second */
    private final Set<String> told = new HashSet<>();
    /** players told that the 지게 they put on is held by someone else (until they wear another) */
    private final Set<UUID> heldTold = new HashSet<>();
    /** player -> the 지게 (id:token:owner) they put on that is someone else's (told once; until they take it off) */
    private final Map<UUID, String> notYours = new HashMap<>();
    /** 하역장: each 지게's trip since its load last changed, by jige id (kept in stsv_vehicle_trip under the negated id) */
    private VehicleTripStore tripStore;
    private final Map<Long, VehicleTrip> trips = new HashMap<>();
    private long checks;

    /** A wearer may take it off and put it on again this close to where it came off without starting the trip again. */
    static final double REWEAR_REACH = 16;
    /** The most a wearer covers between two checks (a second) and still walked it: more is a teleport (the trip starts again). */
    static final double MAX_WALK = 40;

    @Override
    public String id() {
        return "jige";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new JigeStore(plugin.db());
        store.init();
        tripStore = new VehicleTripStore(plugin.db());
        tripStore.init();
        for (Map.Entry<Long, VehicleTrip> e : tripStore.all().entrySet()) if (e.getKey() < 0) trips.put(-e.getKey(), e.getValue());
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        org.bukkit.command.PluginCommand command = plugin.getCommand("지게");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        if (plugin.cosmetics() != null) plugin.cosmetics().backOverride(this::look);
        if (plugin.vehicles() != null) plugin.vehicles().jige(this);
        plugin.admin().add("STSV", "지게", "목록 [플레이어] | 회수 <번호>",
                "지게 목록(번호, 주인, 짐, 지고 있는 사람) / 주인에게 새 아이템으로 돌려주기(예전 아이템은 못 씀, 짐은 그대로)", this::admin,
                (s, a) -> a.length == 1 ? List.of("목록", "회수") : List.of());
        for (Player p : Bukkit.getOnlinePlayers()) JigeSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            checks++;
            for (Player p : Bukkit.getOnlinePlayers()) check(p);
        }, 20L, 20L);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            Session s = e.getValue();
            sync(s);
            if (p != null) {
                if (s.view != null && p.getOpenInventory().getTopInventory() == s.view) p.closeInventory();
                JigeSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
            }
            store.save(s.id, s.token, VehicleModule.encode(s.load), System.currentTimeMillis()).join();
            VehicleTrip trip = trips.get(s.id);
            if (trip != null && p != null) {
                trip.seen = new VehicleTrip.Seen(p.getWorld().getName(), p.getX(), p.getZ());
                tripStore.save(VehicleTripStore.jige(s.id), trip.snapshot(), trip.seen).join();
            }
        }
        sessions.clear();
        holders.clear();
        if (plugin.cosmetics() != null) plugin.cosmetics().backOverride(null);
        if (plugin.vehicles() != null) plugin.vehicles().jige(null);
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
    }

    /** Every item with a {@code jige:} section (read from its items file). */
    private void load() {
        specs.clear();
        looks.clear();
        Map<Path, YamlConfiguration> files = new HashMap<>();
        for (CustomItem i : plugin.content().items()) {
            String source = plugin.content().source(i);
            if (source.isEmpty()) continue;
            Path file = i.pack().items().resolve(source + ".yml");
            YamlConfiguration y = files.computeIfAbsent(file, f -> YamlConfiguration.loadConfiguration(f.toFile()));
            ConfigurationSection j = y.getConfigurationSection(i.key().getKey() + ".jige");
            if (j == null) continue;
            try {
                specs.put(i.key(), JigeSpec.parse(j, i.pack().namespace(), m -> java.nio.file.Files.exists(i.pack().models().resolve(m + ".bbmodel"))));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[지게] " + i.key() + ": " + ex.getMessage());
            }
        }
        // a reload while worn: sessions keep their spec until the 지게 is put on again
    }

    // ------------------------------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------------------------------

    private Optional<CustomItem> jige(ItemStack it) {
        return plugin.content().itemOf(it).filter(i -> specs.containsKey(i.key()));
    }

    static long idOf(ItemStack it) {
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

    /** Is this a 지게 item? */
    boolean isJige(ItemStack it) {
        return jige(it).isPresent();
    }

    /**
     * Writes the 지게's id and token on its item (its number in the lore the first time), and its owner (귀속) when the
     * item does not carry one yet.
     */
    private static void stamp(ItemStack it, long id, long token, UUID owner, String ownerName) {
        if (VehicleModule.boundOwner(it) == null) VehicleModule.stampOwner(it, owner, ownerName);
        boolean fresh = idOf(it) <= 0;
        it.editMeta(m -> {
            m.getPersistentDataContainer().set(ITEM_ID, PersistentDataType.LONG, id);
            m.getPersistentDataContainer().set(ITEM_TOKEN, PersistentDataType.LONG, token);
            if (fresh) {
                List<Component> lore = m.hasLore() ? new ArrayList<>(m.lore()) : new ArrayList<>();
                lore.add(Lang.item("jige.item-number", "id", id));
                m.lore(lore);
            }
        });
    }

    /** The look on the back for a player: their 지게's stage (null = they hold none). */
    private CustomItem look(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return null;
        return looks.computeIfAbsent(s.item.key() + "#" + s.stage, k -> new CustomItem(
                new NamespacedKey(s.item.key().getNamespace(), s.item.key().getKey() + "_look_" + s.stage), s.item.pack(), Material.PAPER, null,
                List.of(), new ModelRef(ModelRef.Kind.RAW, s.spec.stages().get(s.stage).asString(), false), 1, null, false, null, null, null, null,
                null, new CustomItem.Cosmetic(CustomItem.CosmeticSlot.BACK, CustomItem.Rarity.COMMON, null, s.spec.offset(), 1f, false), null, null));
    }

    private void refreshLook(Player p) {
        if (plugin.cosmetics() != null) plugin.cosmetics().refresh(p);
    }

    // ------------------------------------------------------------------------------------------
    // Wearing (checked when the equipment changes, and every second)
    // ------------------------------------------------------------------------------------------

    /** What the chest slot holds now: a session starts (claim) or ends. */
    void check(Player p) {
        if (!p.isOnline() || p.isDead()) return;
        UUID u = p.getUniqueId();
        ItemStack chest = p.getInventory().getChestplate();
        Optional<CustomItem> item = jige(chest);
        Session s = sessions.get(u);
        JigeRules.Worn w = JigeRules.worn(item.isPresent(), idOf(chest), tokenOf(chest), s == null ? 0 : s.id, s == null ? 0 : s.token);
        switch (w) {
            case NONE -> {
                if (s != null) end(p, s);
                notYours.remove(u);
            }
            case SAME -> {
                double way = walked(p, s);
                JigeSpeed.apply(p.getAttribute(Attribute.MOVEMENT_SPEED), slowdown(p, s, way));
                effort(p, s, way);
            }
            case NEW, CLAIM -> {
                if (s != null) end(p, s);
                if (!pending.contains(u)) claim(p, item.get(), chest);
            }
        }
    }

    private void claim(Player p, CustomItem item, ItemStack chest) {
        UUID u = p.getUniqueId();
        long id = idOf(chest), token = tokenOf(chest);
        String key = id + ":" + token;
        if (id > 0 && !JigeRules.mayClaim(u, holders.get(id))) {
            if (heldTold.add(u)) p.sendMessage(Lang.msg("jige.held"));
            return;
        }
        heldTold.remove(u);
        if (id > 0 && told.contains(key)) return;   // a dead copy, already told
        // 귀속: someone else's 지게 goes on only for who may use their things (VehicleOwnership)
        UUID bound = VehicleModule.boundOwner(chest);
        String boundName = VehicleModule.boundOwnerName(chest);
        String mine = key + ":" + bound;
        if (mine.equals(notYours.get(u))) return;   // told already
        VehicleModule vehicles = plugin.vehicles();
        if (id <= 0 && bound != null && vehicles != null && !vehicles.mayUse(bound, p)) {
            notYours.put(u, mine);
            p.sendMessage(Lang.msg("jige.not-yours", "owner", boundName == null ? "?" : boundName));
            return;
        }
        JigeSpec spec = specs.get(item.key());
        long next = newToken(), now = System.currentTimeMillis();
        pending.add(u);
        var step = id > 0 ? store.claim(id, token, next, now)
                : store.create(bound != null ? bound : u, bound != null && boundName != null ? boundName : p.getName(), next, now);
        step.whenComplete((row, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(u);
            if (err != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[지게] " + p.getName() + " #" + id, err);
                if (p.isOnline()) p.sendMessage(Lang.msg("jige.error"));
                return;
            }
            if (row == null) {
                told.add(key);
                if (p.isOnline()) p.sendMessage(Lang.msg("jige.stale"));
                plugin.getLogger().warning("[지게] " + p.getName() + " 님이 쓸 수 없는 지게 #" + id + " 을 입음 (복사본이거나 예전 아이템)");
                return;
            }
            if (id > 0 && vehicles != null && !vehicles.mayUse(row.owner(), p)) {
                // someone else's: its token back (the item stays as it was, still theirs), nothing opens
                store.claim(id, next, token, System.currentTimeMillis());
                notYours.put(u, mine);
                if (p.isOnline()) p.sendMessage(Lang.msg("jige.not-yours", "owner", row.ownerName()));
                return;
            }
            // the item claimed: still worn (start), moved within the inventory (stamped, not worn), or gone (undone)
            ItemStack worn = p.isOnline() ? p.getInventory().getChestplate() : null;
            if (worn != null && jige(worn).isPresent() && idOf(worn) == id && tokenOf(worn) == token) {
                stamp(worn, row.id(), next, row.owner(), row.ownerName());
                p.getInventory().setChestplate(worn);
                start(p, row, next, jige(worn).get(), spec);
                return;
            }
            ItemStack moved = null;
            if (p.isOnline()) {
                for (ItemStack it : p.getInventory().getContents()) {
                    if (it != null && jige(it).isPresent() && idOf(it) == id && tokenOf(it) == token) {
                        moved = it;
                        break;
                    }
                }
            }
            if (moved != null) stamp(moved, row.id(), next, row.owner(), row.ownerName());
            else if (id > 0) store.claim(id, next, token, System.currentTimeMillis());   // the item left: its old token works again
        }));
    }

    private void start(Player p, JigeStore.Row row, long token, CustomItem item, JigeSpec spec) {
        ItemStack[] load = new ItemStack[spec.slots()];
        ItemStack[] stored = decode(row.contents());
        // a smaller jige.slots in the file never loses a load: the view grows to what it holds (up to 54)
        int last = 0;
        for (int i = 0; i < stored.length; i++) if (stored[i] != null) last = i + 1;
        if (last > load.length) load = new ItemStack[Math.min(JigeSpec.MAX_SLOTS, (last + 8) / 9 * 9)];
        System.arraycopy(stored, 0, load, 0, Math.min(stored.length, load.length));
        Session s = new Session(row.id(), token, item, spec, load);
        s.stage = spec.stage(s.used());
        // 하역장: the load's trip goes on only if it is put on again where it came off (else it was carried off the road)
        org.bukkit.Location at = p.getLocation();
        VehicleTrip trip = trips.get(row.id());
        if (trip == null || trip.seen == null || !trip.seen.near(at.getWorld().getName(), at.getX(), at.getZ(), REWEAR_REACH)) {
            trip = VehicleTrip.start(at.getWorld().getName(), at.getX(), at.getZ(), System.currentTimeMillis());
            trips.put(row.id(), trip);
        }
        s.lastWorld = at.getWorld().getName();
        s.lastX = at.getX();
        s.lastZ = at.getZ();
        // saved now (a new trip too) with where it is worn: after a crash while worn it goes on from about here
        saveTrip(row.id(), trip, p);
        sessions.put(p.getUniqueId(), s);
        holders.put(row.id(), p.getUniqueId());
        JigeSpeed.apply(p.getAttribute(Attribute.MOVEMENT_SPEED), spec.slowdown());
        refreshLook(p);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 0.8f);
        HudModule.flash(p, Lang.mm("jige.worn", "percent", Math.round(spec.slowdown() * 100)));
    }

    /** The player no longer holds it: the view closes, the load is saved, their speed and back come back. */
    private void end(Player p, Session s) {
        sync(s);
        if (s.view != null && p.getOpenInventory().getTopInventory() == s.view) p.closeInventory();
        s.view = null;
        store.save(s.id, s.token, VehicleModule.encode(s.load), System.currentTimeMillis());
        VehicleTrip trip = trips.get(s.id);
        if (trip != null) {
            walked(p, s);
            trip.seen = new VehicleTrip.Seen(p.getWorld().getName(), p.getLocation().getX(), p.getLocation().getZ());
            trip.dirty = false;
            tripStore.save(VehicleTripStore.jige(s.id), trip.snapshot(), trip.seen);
        }
        sessions.remove(p.getUniqueId());
        holders.remove(s.id, p.getUniqueId());
        JigeSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        refreshLook(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipment(EntityEquipmentChangedEvent e) {
        if (!(e.getEntity() instanceof Player p) || !e.getEquipmentChanges().containsKey(EquipmentSlot.CHEST)) return;
        Bukkit.getScheduler().runTask(plugin, () -> check(p));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        JigeSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        Bukkit.getScheduler().runTaskLater(plugin, () -> check(p), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) end(e.getPlayer(), s);
        pending.remove(e.getPlayer().getUniqueId());
        heldTold.remove(e.getPlayer().getUniqueId());
        notYours.remove(e.getPlayer().getUniqueId());
    }

    /** Death: every 지게 item is kept (JigeRules.keptOnDeath); the load stays in the database. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null) end(p, s);
        if (e.getKeepInventory()) return;
        boolean kept = false;
        for (var it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack d = it.next();
            if (JigeRules.keptOnDeath(jige(d).isPresent())) {
                it.remove();
                e.getItemsToKeep().add(d);
                kept = true;
            }
        }
        if (kept) p.sendMessage(Lang.msg("jige.kept"));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> check(p), 2L);
    }

    // ------------------------------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------------------------------

    /** The 지게's chest view. */
    static final class Holder implements InventoryHolder {
        final long id;
        Inventory inventory;

        Holder(long id) {
            this.id = id;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    @SuppressWarnings("deprecation") // isInteractable: as the vehicles and furniture use it
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean useful = e.getClickedBlock() != null && e.getClickedBlock().getType().asBlockType().isInteractable();
        if (!JigeRules.openClick(p.isSneaking(), p.getInventory().getItemInMainHand().isEmpty(), right, useful)) return;
        if (jige(p.getInventory().getChestplate()).isEmpty()) return;
        e.setCancelled(true);
        // 하역장: at a dock its sale menu comes first (VehicleModule#cargoClick)
        java.util.function.BiPredicate<Player, Hauled> dock = plugin.vehicles() == null ? null : plugin.vehicles().cargoClickHook();
        Hauled h = hauled(p);
        if (dock != null && h != null) {
            try {
                if (dock.test(p, h)) return;
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[지게] 하역장 처리 실패", ex);
            }
        }
        open(p);
    }

    /** Opens the wearer's 지게 (or says why not). */
    void open(Player p) {
        Session s = sessions.get(p.getUniqueId());
        boolean worn = jige(p.getInventory().getChestplate()).isPresent();
        switch (JigeRules.open(worn, s != null, pending.contains(p.getUniqueId()))) {
            case NOT_WORN -> HudModule.flash(p, Lang.mm("jige.not-worn"));
            case LOADING -> HudModule.flash(p, Lang.mm("jige.loading"));
            case STALE -> p.sendMessage(Lang.msg("jige.stale"));
            case OK -> {
                if (s.view != null && p.getOpenInventory().getTopInventory() == s.view) return;
                net.kyori.adventure.text.Component seal = plugin.vehicles() == null ? null : plugin.vehicles().sealed(VehicleTripStore.jige(s.id));
                if (seal != null) {   // 출발 터미널: inspected and sealed until its dock
                    HudModule.flash(p, seal);
                    return;
                }
                Holder h = new Holder(s.id);
                Inventory inv = Bukkit.createInventory(h, s.load.length, weighs()
                        ? Lang.mm("jige.title-weight", "used", s.used(), "slots", s.load.length, "weight", CargoWeights.format(weight(s.load)),
                        "payload", CargoWeights.format(s.spec.payload()))
                        : Lang.mm("jige.title", "used", s.used(), "slots", s.load.length));
                h.inventory = inv;
                for (int i = 0; i < s.load.length; i++) inv.setItem(i, s.load[i]);
                s.view = inv;
                p.openInventory(inv);
                p.playSound(p.getLocation(), Sound.BLOCK_BAMBOO_WOOD_TRAPDOOR_OPEN, 0.6f, 0.8f);
            }
        }
    }

    /** What the view holds goes into the load; saved and the look changed when it did. */
    private void sync(Session s) {
        Inventory inv = s.view;
        if (inv == null) return;
        boolean changed = false;
        ItemStack[] before = s.load.clone();
        for (int i = 0; i < s.load.length && i < inv.getSize(); i++) {
            ItemStack now = inv.getItem(i);
            now = now == null || now.isEmpty() ? null : now.clone();
            if (!java.util.Objects.equals(s.load[i], now)) {
                s.load[i] = now;
                changed = true;
            }
        }
        if (!changed) return;
        loadChanged(s, !CargoWeights.sameContents(before, s.load, VehicleModule::cargoKey, ItemStack::isSimilar, ItemStack::getAmount));
        store.save(s.id, s.token, VehicleModule.encode(s.load), System.currentTimeMillis()).thenAccept(ok -> {
            if (!ok) plugin.getLogger().warning("[지게] #" + s.id + " 저장 실패 (토큰이 바뀜)");
        });
        int stage = s.spec.stage(s.used());
        if (stage != s.stage) {
            s.stage = stage;
            UUID who = holders.get(s.id);
            Player p = who == null ? null : Bukkit.getPlayer(who);
            if (p != null) refreshLook(p);
        }
    }

    private Session sessionOf(Inventory top) {
        if (!(top.getHolder(false) instanceof Holder h)) return null;
        UUID who = holders.get(h.id);
        Session s = who == null ? null : sessions.get(who);
        return s != null && s.view == top ? s : null;
    }

    @EventHandler
    public void onViewClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Holder)) return;
        Session s = sessionOf(top);
        if (s == null) {
            e.setCancelled(true);   // a view no longer its 지게's (taken off under it)
            return;
        }
        if (weighs() && e.getWhoClicked() instanceof Player p) {
            CargoGuard.Move m = CargoGuard.click(e, top);
            if (m.refused()) {
                e.setCancelled(true);
                return;
            }
            if (m.in() != null && m.inAmount() > 0) {
                double now = viewWeight(top), adding = plugin.vehicles().weightOf(m.in().asOne()) * m.inAmount();
                if (!CargoWeights.fits(now - plugin.vehicles().weightOf(m.out()), adding, s.spec.payload())) {
                    e.setCancelled(true);
                    tooHeavy(p, s, now, adding);
                    return;
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessionOf(top) == s) sync(s);
        });
    }

    @EventHandler
    public void onViewDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Holder)) return;
        Session s = sessionOf(top);
        if (s == null) {
            e.setCancelled(true);
            return;
        }
        if (weighs() && e.getWhoClicked() instanceof Player p) {
            double now = viewWeight(top), adding = CargoGuard.dragged(e, top) * plugin.vehicles().weightOf(e.getOldCursor().asOne());
            if (!CargoWeights.fits(now, adding, s.spec.payload())) {
                e.setCancelled(true);
                tooHeavy(p, s, now, adding);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessionOf(top) == s) sync(s);
        });
    }

    @EventHandler
    public void onViewClose(InventoryCloseEvent e) {
        Session s = sessionOf(e.getInventory());
        if (s == null) return;
        sync(s);
        s.view = null;
        if (e.getPlayer() instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_BAMBOO_WOOD_TRAPDOOR_CLOSE, 0.6f, 0.8f);
    }

    // ------------------------------------------------------------------------------------------
    // 하역장 · 적재 무게 (README): the load's weight, its trip, and the API the economy server's docks use
    // ------------------------------------------------------------------------------------------

    /** Are there weights (the economy server's weights.yml, through VehicleModule#cargoWeight)? */
    private boolean weighs() {
        return plugin.vehicles() != null && plugin.vehicles().weighs();
    }

    private double weight(ItemStack[] items) {
        return CargoWeights.total(items, plugin.vehicles()::weightOf);
    }

    private double viewWeight(Inventory view) {
        return weight(view.getContents());
    }

    private void tooHeavy(Player p, Session s, double now, double adding) {
        HudModule.flash(p, Lang.mm("vehicle.cargo-too-heavy", "weight", CargoWeights.format(now), "payload", CargoWeights.format(s.spec.payload()),
                "adding", CargoWeights.format(adding), "left", CargoWeights.format(Math.max(0, s.spec.payload() - now))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
    }

    /** The load changed: its version moves on; things came or went: its trip starts again where the wearer stands. */
    private void loadChanged(Session s, boolean contents) {
        s.version++;
        if (!contents) return;
        UUID who = holders.get(s.id);
        Player p = who == null ? null : Bukkit.getPlayer(who);
        if (p == null) return;
        VehicleTrip trip = trips.computeIfAbsent(s.id, k -> VehicleTrip.start(p.getWorld().getName(), p.getX(), p.getZ(), 0));
        trip.reset(p.getWorld().getName(), p.getX(), p.getZ(), System.currentTimeMillis());
        s.lastWorld = p.getWorld().getName();
        s.lastX = p.getX();
        s.lastZ = p.getZ();
        saveTrip(s.id, trip, p);
    }

    /**
     * Writes a 지게's trip, with where its wearer is now as where it was last seen on them (never left empty while worn:
     * after a crash it is put on again about there, and its trip, and a seal on it, go on).
     */
    private void saveTrip(long id, VehicleTrip trip, Player p) {
        if (p != null) trip.seen = new VehicleTrip.Seen(p.getWorld().getName(), p.getX(), p.getZ());
        trip.dirty = false;
        tripStore.save(VehicleTripStore.jige(id), trip.snapshot(), trip.seen);
    }

    /**
     * The odometer: the way the wearer went since the last check (on foot, on a horse, in a car: they carried it). More than
     * {@link #MAX_WALK} in a second, or another world, is a teleport: the trip starts again there (a load cannot skip the road).
     */
    private double walked(Player p, Session s) {
        VehicleTrip trip = trips.get(s.id);
        if (trip == null) return 0;
        org.bukkit.Location at = p.getLocation();
        String w = at.getWorld().getName();
        double d = w.equals(s.lastWorld) ? Math.hypot(at.getX() - s.lastX, at.getZ() - s.lastZ) : Double.POSITIVE_INFINITY;
        if (d > MAX_WALK) {
            broke(p, s, at);
            return 0;
        }
        trip.moved(d);
        s.lastWorld = w;
        s.lastX = at.getX();
        s.lastZ = at.getZ();
        if (trip.dirty && checks % 10 == 0) saveTrip(s.id, trip, p);
        return d;
    }

    // ------------------------------------------------------------------------------------------
    // 탈것의 느낌 (VehicleFeel, vehicle_feel.yml vehicles.jige): the load and the slope in the pace, hunger for the way
    // ------------------------------------------------------------------------------------------

    private VehicleFeel.Spec feel(Session s) {
        return plugin.vehicles() == null ? VehicleFeel.Spec.plain(VehicleFeel.Power.MUSCLE, VehicleFeel.defaultProfile(VehicleFeel.Power.MUSCLE))
                : plugin.vehicles().feel(s.item.key().asString(), VehicleFeel.Power.MUSCLE);
    }

    /** The share of its payload the 지게 carries (by weight; nothing weighed: by the slots used), counted when it changed. */
    private double share(Session s) {
        if (s.shareAt != s.version) {
            s.shareAt = s.version;
            s.share = weighs() ? VehicleFeel.share(weight(s.load), s.spec.payload()) : Math.min(1, (double) s.used() / Math.max(1, s.load.length));
        }
        return s.share;
    }

    /**
     * The wearer's slowdown this second: the 지게's own, deeper with its load and uphill (the slope of the way since the
     * last check), in steps of 2 % (the modifier changes seldom).
     */
    private double slowdown(Player p, Session s, double way) {
        double y = p.getLocation().getY();
        if (!Double.isNaN(s.lastY) && way > 0.5 && p.getVehicle() == null) {
            double g = Math.clamp((y - s.lastY) / way, -1, 1);
            s.grade = s.grade * 0.5 + g * 0.5;
        } else if (way <= 0.5) {
            s.grade *= 0.5;
        }
        s.lastY = y;
        double f = VehicleFeel.topFactor(feel(s).profile(), share(s), s.grade, false);
        return Math.round(VehicleFeel.slowdown(s.spec.slowdown(), f) * 50) / 50.0;
    }

    /** Carrying it on foot costs a little food by the way, more with a load and uphill (not riding, flying or gliding). */
    private void effort(Player p, Session s, double way) {
        if (way <= 0 || p.getVehicle() != null || p.isFlying() || p.isGliding()
                || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        double ex = VehicleFeel.exhaustion(way, share(s), s.grade, feel(s).effort());
        if (ex > 0) p.setExhaustion((float) Math.min(40, p.getExhaustion() + ex));
    }

    /**
     * The load skipped the road (a teleport, another world): its trip starts again where the wearer arrives, saved at
     * once, and a seal on it is void now (VehicleModule#broken: the economy server's 출발 터미널), not only at the dock.
     */
    private void broke(Player p, Session s, org.bukkit.Location to) {
        VehicleTrip trip = trips.get(s.id);
        if (trip == null) return;
        String w = to.getWorld().getName();
        trip.reset(w, to.getX(), to.getZ(), System.currentTimeMillis());
        s.lastWorld = w;
        s.lastX = to.getX();
        s.lastZ = to.getZ();
        trip.seen = new VehicleTrip.Seen(w, to.getX(), to.getZ());
        saveTrip(s.id, trip, null);
        if (plugin.vehicles() != null) plugin.vehicles().broken(VehicleTripStore.jige(s.id));
    }

    /**
     * A wearer teleported (a command, /tpa, a pearl, another plugin): further than {@link JigeRules#TELEPORT_STEP}, or into
     * another world, the trip is broken off at once ({@link #broke}), not at the next check a second later (a sale could
     * come first). A short hop (stepping out of a vehicle's seat) is walking: the next check counts it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        org.bukkit.Location from = e.getFrom(), to = e.getTo();
        boolean same = from.getWorld() != null && from.getWorld().equals(to.getWorld());
        if (JigeRules.teleportBreaks(same, same ? Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ()) : 0)) broke(e.getPlayer(), s, to);
    }

    /** Into another world (a portal: its own event, not a teleport one): the trip is broken off there. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && !e.getPlayer().getWorld().getName().equals(s.lastWorld)) broke(e.getPlayer(), s, e.getPlayer().getLocation());
    }

    /** The load on a player's back as a dock sees it (null: they wear no 지게 now). Its id is the 지게's negated. */
    public Hauled hauled(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return null;
        walked(p, s);   // the way since the last check (a teleport since then breaks the trip off before any sale)
        sync(s);
        VehicleTrip trip = trips.get(s.id);
        if (trip == null) {
            trip = VehicleTrip.start(p.getWorld().getName(), p.getX(), p.getZ(), System.currentTimeMillis());
            trips.put(s.id, trip);
            saveTrip(s.id, trip, p);
        }
        ItemStack[] copy = new ItemStack[s.load.length];
        for (int i = 0; i < copy.length; i++) copy[i] = s.load[i] == null ? null : s.load[i].clone();
        org.bukkit.Location at = p.getLocation();
        return new Hauled(VehicleTripStore.jige(s.id), s.item.key().asString(), dev.stsv.core.util.Text.plain(s.item.name() == null ? s.item.key().getKey() : s.item.name()),
                p.getUniqueId(), p.getName(), p.getWorld(), List.of(new double[]{at.getX(), at.getY(), at.getZ()}), trip.snapshot(), copy,
                s.spec.payload(), weighs() ? weight(s.load) : 0, s.version, false);
    }

    /**
     * Takes things off a player's 지게 (a dock sale): the slots `take` picks, only if the load is as it was at `version`
     * (else null). Its view closes first; its trip starts again here.
     */
    public List<ItemStack> unload(Player p, long version, java.util.function.IntPredicate take) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return null;
        sync(s);
        if (s.view != null && p.getOpenInventory().getTopInventory() == s.view) p.closeInventory();
        s.view = null;
        if (s.version != version) return null;
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < s.load.length; i++) {
            if (s.load[i] == null || !take.test(i)) continue;
            out.add(s.load[i]);
            s.load[i] = null;
        }
        if (out.isEmpty()) return out;
        loadChanged(s, true);
        store.save(s.id, s.token, VehicleModule.encode(s.load), System.currentTimeMillis());
        int stage = s.spec.stage(s.used());
        if (stage != s.stage) {
            s.stage = stage;
            refreshLook(p);
        }
        return out;
    }

    /** Opens a player's 지게 as /지게 does (without asking a dock first). */
    public void openFor(Player p) {
        open(p);
    }

    /** Closes a 지게's open view on its wearer, what they did in it stored first (VehicleModule#closeLoad). */
    void closeView(long id) {
        UUID who = holders.get(id);
        Session s = who == null ? null : sessions.get(who);
        if (s == null || s.id != id || s.view == null) return;
        sync(s);
        Player p = Bukkit.getPlayer(who);
        Inventory view = s.view;
        s.view = null;
        if (p != null && p.getOpenInventory().getTopInventory() == view) p.closeInventory();
    }

    // ------------------------------------------------------------------------------------------
    // A 지게 item on the ground: never burns, blows up or despawns (its load is bound to it)
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item item = e.getEntity();
        if (jige(item.getItemStack()).isEmpty()) return;
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);   // fire, lava, explosions, cactus (not the void: /관리자 지게 회수)
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item i && e.getCause() != EntityDamageEvent.DamageCause.VOID && jige(i.getItemStack()).isPresent()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemBurn(EntityCombustEvent e) {
        if (e.getEntity() instanceof Item i && jige(i.getItemStack()).isPresent()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        if (jige(e.getEntity().getItemStack()).isPresent()) e.setCancelled(true);
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 지게 목록 [플레이어] | 회수 <번호>
    // ------------------------------------------------------------------------------------------

    /** 지게 with a 회수 on the way */
    private final Set<Long> recalling = new HashSet<>();

    private void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "목록" : a[0];
        switch (sub) {
            case "목록", "list" -> {
                String player = a.length > 1 ? a[1] : null;
                if (player == null) listRows(s, null);
                else dev.stsv.core.nickname.Names.find(player, found -> {
                    if (found.isEmpty()) s.sendMessage(Lang.msg("vehicle.admin-no-player", "player", player));
                    else listRows(s, found.get());
                });
            }
            case "회수", "recall" -> {
                long id = -1;
                if (a.length > 1) {
                    try {
                        id = Long.parseLong(a[1].startsWith("#") ? a[1].substring(1) : a[1]);
                    } catch (NumberFormatException ex) {
                        id = -1;
                    }
                }
                if (id <= 0) s.sendMessage(Lang.msg("jige.admin-usage"));
                else recall(s, id);
            }
            default -> s.sendMessage(Lang.msg("jige.admin-usage"));
        }
    }

    private void listRows(CommandSender s, UUID owner) {
        if (plugin.vehicles() != null && plugin.vehicles().ledger() != null) plugin.vehicles().ledger().scanAll();   // what was just thrown away: hidden
        store.list(owner, 100).thenAccept(rows -> Bukkit.getScheduler().runTask(plugin, () -> {
            s.sendMessage(Lang.msg("jige.admin-list", "count", rows.size()));
            for (JigeStore.Row r : rows) {
                UUID who = holders.get(r.id());
                Session held = who == null ? null : sessions.get(who);
                int used = 0;
                if (held != null && held.id == r.id()) used = held.used();
                else for (ItemStack it : decode(r.contents())) if (it != null) used++;
                Player wearer = who == null ? null : Bukkit.getPlayer(who);
                String worn = wearer == null ? "" : Lang.raw("jige.admin-worn", "player", wearer.getName());
                s.sendMessage(dev.stsv.core.util.Text.mm(Lang.raw("jige.admin-line", "id", r.id(), "owner", r.ownerName(), "used", used, "worn", worn)));
            }
        }));
        // after the list (one database thread: in this order)
        store.goneCount(owner).thenAccept(gone -> {
            if (gone > 0) Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Lang.msg("jige.admin-hidden", "count", gone)));
        });
    }

    /** The 지게 item to make again (the kind this one is: its wearer's, else the first 지게 item there is). */
    private Optional<CustomItem> itemFor(long id) {
        UUID who = holders.get(id);
        Session held = who == null ? null : sessions.get(who);
        if (held != null && held.id == id) return Optional.of(held.item);
        return specs.keySet().stream().sorted(java.util.Comparator.comparing(NamespacedKey::asString)).findFirst()
                .flatMap(k -> plugin.content().item(k.asString()));
    }

    /**
     * /관리자 지게 회수: a lost 지게 (burnt before items were kept from fire, fallen into the void, given away) made again for
     * its owner with a new token: every old item of it opens nothing from now on. Its load stays bound to its number and is
     * there when the new item is put on. To the mailbox (economy server), else into the owner's inventory now.
     */
    private void recall(CommandSender by, long id) {
        if (recalling.contains(id)) {
            by.sendMessage(Lang.msg("jige.admin-busy", "id", id));
            return;
        }
        store.get(id).thenAccept(row -> Bukkit.getScheduler().runTask(plugin, () -> {
            Optional<CustomItem> item = itemFor(id);
            if (row == null || item.isEmpty()) {
                by.sendMessage(Lang.msg("jige.admin-unknown", "id", id));
                return;
            }
            java.util.function.BiConsumer<UUID, ItemStack> box = plugin.vehicles() == null ? null : plugin.vehicles().mailboxHook();
            Player owner = Bukkit.getPlayer(row.owner());
            if (box == null && (owner == null || owner.getInventory().firstEmpty() < 0)) {
                by.sendMessage(Lang.msg("jige.admin-owner-away", "owner", row.ownerName()));
                return;
            }
            if (recalling.contains(id)) return;
            letGo(id);   // whoever wears it now lets go first: its load is saved with the token they hold
            recalling.add(id);
            long token = newToken();
            store.reissue(id, token, System.currentTimeMillis()).whenComplete((done, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                recalling.remove(id);
                if (err != null || done == null) {
                    by.sendMessage(Lang.msg("jige.admin-unknown", "id", id));
                    return;
                }
                letGo(id);   // an old copy claimed just before the new token: it holds an old token now
                ItemStack it = item.get().create(1);
                stamp(it, id, token, row.owner(), row.ownerName());
                Player o = Bukkit.getPlayer(row.owner());
                if (box != null) box.accept(row.owner(), it);
                else if (o != null && o.getInventory().addItem(it).isEmpty()) {
                    o.playSound(o.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.9f);
                } else {
                    // they left or filled up meanwhile: the admin keeps it to hand over (the old copies are dead already)
                    if (by instanceof Player admin) for (ItemStack left : admin.getInventory().addItem(it).values()) admin.getWorld().dropItem(admin.getLocation(), left);
                    by.sendMessage(Lang.msg("jige.admin-kept", "id", id, "owner", row.ownerName()));
                    plugin.getLogger().info("[지게] #" + id + " 회수: 주인 " + row.ownerName() + " 에게 못 줌, " + by.getName() + " 이(가) 받음");
                    return;
                }
                if (o != null) o.sendMessage(Lang.msg("jige.returned", "id", id));
                by.sendMessage(Lang.msg("jige.admin-returned", "id", id, "owner", row.ownerName()));
                plugin.getLogger().info("[지게] " + by.getName() + " 님이 #" + id + " 를 " + row.ownerName() + " 에게 새 아이템으로 돌려보냄 (예전 아이템 무효)");
            }));
        }));
    }

    /** Whoever wears this 지게 now takes it off as far as the plugin knows (saved; the item stays on them, dead after a 회수). */
    private void letGo(long id) {
        UUID who = holders.get(id);
        Session s = who == null ? null : sessions.get(who);
        Player p = who == null ? null : Bukkit.getPlayer(who);
        if (s != null && p != null && s.id == id) end(p, s);
    }

    // ------------------------------------------------------------------------------------------
    // /탈것 양도 of a 지게 (VehicleModule#give), /관리자 탈것 정리
    // ------------------------------------------------------------------------------------------

    /** The 지게 item in a hand as /탈것 양도 sees it (null: not a 지게). */
    VehicleModule.Subject subject(ItemStack hand) {
        Optional<CustomItem> item = jige(hand);
        if (item.isEmpty()) return null;
        return new VehicleModule.Subject(VehicleModule.Subject.Kind.JIGE, idOf(hand), tokenOf(hand), item.get().key().asString(),
                VehicleModule.boundOwner(hand), VehicleModule.boundOwnerName(hand), VehicleModule.label(item.get()));
    }

    /**
     * Gives the 지게 in the hand to `to` (from: its owner as the giver must be, null for an admin): the item leaves the hand,
     * its row becomes theirs with a new token (the old item opens nothing), its load goes with it, and a new item goes to
     * their mailbox or inventory. A 지게 is never owed: the command asks for a mailbox or the player online first.
     */
    void give(Player p, VehicleModule.Subject s, UUID from, UUID to, String toName) {
        VehicleModule vehicles = plugin.vehicles();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Optional<CustomItem> item = jige(hand);
        if (vehicles == null || item.isEmpty() || !item.get().key().asString().equals(s.item()) || idOf(hand) != s.id() || tokenOf(hand) != s.token()) {
            p.sendMessage(Lang.msg("vehicle.give-nothing"));
            return;
        }
        if (!vehicles.canHandOver(to)) {
            p.sendMessage(Lang.msg("vehicle.give-offline"));
            return;
        }
        if (s.id() > 0 && recalling.contains(s.id())) {
            p.sendMessage(Lang.msg("jige.admin-busy", "id", s.id()));
            return;
        }
        ItemStack taken = hand.asOne();
        hand.subtract(1);
        long token = newToken(), now = System.currentTimeMillis();
        java.util.concurrent.CompletableFuture<Long> step;
        if (s.id() > 0) {
            recalling.add(s.id());
            step = store.transfer(s.id(), s.token(), from, to, toName, token, now).thenApply(r -> switch (r) {
                case DONE -> s.id();
                case NOT_OWNER -> -1L;
                case STALE -> -2L;
            });
        } else {
            step = store.create(to, toName, token, now).thenApply(JigeStore.Row::id);
        }
        step.whenComplete((id, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (s.id() > 0) recalling.remove(s.id());
            if (err != null || id == null || id <= 0) {
                for (ItemStack left : p.getInventory().addItem(taken).values()) p.getWorld().dropItem(p.getLocation(), left);
                p.sendMessage(err != null || id == null ? Lang.msg("jige.error")
                        : id == -1L ? Lang.msg("jige.not-yours", "owner", s.ownerName() == null ? "?" : s.ownerName()) : Lang.msg("jige.stale"));
                return;
            }
            letGo(id);   // a copy someone wears holds an old token now
            ItemStack it = item.get().create(1);
            stamp(it, id, token, to, toName);
            if (!vehicles.handOver(to, it)) {
                // they left or filled up meanwhile: the giver hands it over (it is theirs already; nobody else can wear it)
                for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
                p.sendMessage(Lang.msg("vehicle.give-kept", "player", dev.stsv.core.nickname.Names.mini(to)));
            }
            vehicles.given(p, s, id, to, toName);
        }));
    }

    /** /관리자 탈것 정리: what the worn 지게 carry (a vehicle item in a load is not lost). */
    void collectLoads(VehicleLedger.Found into) {
        for (Session s : sessions.values()) {
            VehicleLedger.collect(s.load, into);
            if (s.view != null) VehicleLedger.collect(s.view.getContents(), into);
        }
    }

    private static ItemStack[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new ItemStack[0];
        ItemStack[] in = ItemStack.deserializeItemsFromBytes(bytes);
        for (int i = 0; i < in.length; i++) if (in[i] != null && in[i].isEmpty()) in[i] = null;
        return in;
    }

    // ------------------------------------------------------------------------------------------
    // /지게
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        return List.of();
    }
}
