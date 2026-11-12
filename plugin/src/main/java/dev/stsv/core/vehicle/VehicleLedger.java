package dev.stsv.core.vehicle;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where vehicle and 지게 items are, so the lists show only those still there (VehicleStore GONE, JigeStore gone).
 *
 * <p>An item can vanish without the plugin being told: lava, the void, a cactus, despawning on the ground, /clear, deleted
 * in creative, used up by some other plugin. Two ways to notice:
 * <ul>
 *   <li><b>Destroyed where the server sees it</b>: an item entity on the ground burnt, blown up, despawned or fallen out of
 *       the world (also inside a shulker box or bundle that was): gone at once.</li>
 *   <li><b>Missing from its holder</b>: every online player's inventory, ender chest, cursor and open view are looked
 *       through (on join, on quit, when a view closes, every {@link #PERIOD_TICKS} ticks, and before a list is shown). An
 *       item found there is theirs to hold ({@code holder} = them); one last seen with them that is no longer anywhere on
 *       them (and did not go to the ground, a chest or another view, which marks it {@link VehicleStore#ELSEWHERE}) is gone.</li>
 * </ul>
 * Gone is never final: the row keeps its token, so an item that turns up again (seen on the ground, in a view, in an
 * inventory) is listed again and still places. Items elsewhere (a chest, the ground, a mailbox) are never called missing
 * by the scan; /관리자 탈것 정리 ({@link #sweep}) checks everything the server can see now and calls the rest gone.
 */
final class VehicleLedger implements Listener {

    /** How often every online player is looked through (ticks). */
    static final long PERIOD_TICKS = 200;
    /** A row changed less than this long ago (ms) is never called missing: its item may be on its way into someone's hands. */
    static final long GRACE_MILLIS = 15_000;

    /** What one look found: vehicle items and 지게 items (id -> token). */
    static final class Found {
        final Map<Long, Long> vehicles = new TreeMap<>();
        final Map<Long, Long> jige = new TreeMap<>();

        boolean isEmpty() {
            return vehicles.isEmpty() && jige.isEmpty();
        }

        /** A key telling whether anything changed since the last look. */
        String signature() {
            return vehicles + "|" + jige;
        }

        void removeAll(Found other) {
            vehicles.keySet().removeAll(other.vehicles.keySet());
            jige.keySet().removeAll(other.jige.keySet());
        }
    }

    /** Ways an item entity leaves the world that destroy it (not: picked up, merged, unloaded, into a hopper, through a portal). */
    static final Set<EntityRemoveEvent.Cause> DESTROYED = EnumSet.of(EntityRemoveEvent.Cause.DEATH, EntityRemoveEvent.Cause.DESPAWN,
            EntityRemoveEvent.Cause.OUT_OF_WORLD, EntityRemoveEvent.Cause.EXPLODE, EntityRemoveEvent.Cause.PLUGIN);

    /** Views whose items go back to the player when closed (a crafting grid, an anvil): what is in them is still theirs. */
    private static final class Views {
        static final Set<InventoryType> TRANSIENT = EnumSet.of(InventoryType.CRAFTING, InventoryType.WORKBENCH, InventoryType.ANVIL,
                InventoryType.GRINDSTONE, InventoryType.ENCHANTING, InventoryType.SMITHING, InventoryType.STONECUTTER, InventoryType.LOOM,
                InventoryType.CARTOGRAPHY, InventoryType.MERCHANT, InventoryType.BEACON, InventoryType.PLAYER, InventoryType.CREATIVE);
    }

    private final Plugin plugin;
    private final VehicleStore vehicles;
    private final JigeStore jige;
    /**
     * player -> what the last look found and when to look once more although nothing changed (0 = no need): a look spares
     * rows changed in the last {@link #GRACE_MILLIS}, so one more after that settles them. Nothing is written otherwise.
     */
    private record Look(String signature, long recheckAt) {}

    private final Map<UUID, Look> last = new HashMap<>();
    /** players whose next look writes whatever it finds (they just joined, gave something to an entity...) */
    private final Set<UUID> force = new HashSet<>();
    private BukkitTask task;

    VehicleLedger(Plugin plugin, VehicleStore vehicles, JigeStore jige) {
        this.plugin = plugin;
        this.vehicles = vehicles;
        this.jige = jige;
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) force.add(p.getUniqueId());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scanAll, PERIOD_TICKS, PERIOD_TICKS);
    }

    void stop() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Reading items
    // ------------------------------------------------------------------------------------------

    private static long key(ItemStack it, NamespacedKey k) {
        Long v = it.getPersistentDataContainer().get(k, PersistentDataType.LONG);
        return v == null ? 0 : v;
    }

    /** Adds a stack (and what a shulker box or bundle in it holds, at any depth) to what was found. */
    static void collect(ItemStack it, Found into) {
        collect(it, into, 0);
    }

    private static void collect(ItemStack it, Found into, int depth) {
        if (it == null || it.isEmpty() || depth > 4) return;
        long id = key(it, VehicleModule.ITEM_ID);
        if (id > 0) into.vehicles.put(id, key(it, VehicleModule.ITEM_TOKEN));
        long j = key(it, JigeModule.ITEM_ID);
        if (j > 0) into.jige.put(j, key(it, JigeModule.ITEM_TOKEN));
        if (Tag.ITEMS_BUNDLES.isTagged(it.getType()) && it.getItemMeta() instanceof BundleMeta b) {
            for (ItemStack in : b.getItems()) collect(in, into, depth + 1);
        } else if (Tag.ITEMS_SHULKER_BOXES.isTagged(it.getType()) && it.getItemMeta() instanceof BlockStateMeta m && m.hasBlockState()
                && m.getBlockState() instanceof Container c) {
            for (ItemStack in : c.getSnapshotInventory().getContents()) collect(in, into, depth + 1);
        }
    }

    static void collect(ItemStack[] items, Found into) {
        if (items != null) for (ItemStack it : items) collect(it, into);
    }

    /** Is this stack (or anything in it) a vehicle or 지게 item with an id? */
    static boolean tracked(ItemStack it) {
        Found f = new Found();
        collect(it, f);
        return !f.isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Looking through players
    // ------------------------------------------------------------------------------------------

    /** Every online player, writing only what changed. Returns when the writes are queued (the lists read after them). */
    void scanAll() {
        for (Player p : Bukkit.getOnlinePlayers()) scan(p, null, force.remove(p.getUniqueId()));
    }

    /**
     * Looks through a player: what they hold is theirs ({@code holder} = them, a lost one found again is back), what is in
     * the open view of a chest, a vehicle's bed and the like is elsewhere, and what was last seen with them and is not on
     * them now is gone. view: the view to read (a closing one), else their open view.
     */
    void scan(Player p, Inventory view, boolean always) {
        if (!p.isOnline() || p.isDead()) return;
        Found mine = new Found(), elsewhere = new Found();
        collect(p.getInventory().getContents(), mine);
        collect(p.getEnderChest().getContents(), mine);
        collect(p.getItemOnCursor(), mine);
        Inventory top = view != null ? view : p.getOpenInventory().getTopInventory();
        if (top != null && top != p.getEnderChest()) collect(top.getContents(), Views.TRANSIENT.contains(top.getType()) ? mine : elsewhere);
        elsewhere.removeAll(mine);
        UUID u = p.getUniqueId();
        String sig = mine.signature() + "/" + elsewhere.signature();
        long now = System.currentTimeMillis();
        Look was = last.get(u);
        if (!always && was != null && sig.equals(was.signature()) && (was.recheckAt() == 0 || now < was.recheckAt())) return;
        boolean again = was != null && sig.equals(was.signature()) && was.recheckAt() != 0 && now >= was.recheckAt();
        last.put(u, new Look(sig, again ? 0 : now + GRACE_MILLIS));
        String holder = u.toString();
        long before = now - GRACE_MILLIS;
        vehicles.seen(mine.vehicles, holder);
        jige.seen(mine.jige, holder);
        vehicles.seen(elsewhere.vehicles, VehicleStore.ELSEWHERE);
        jige.seen(elsewhere.jige, VehicleStore.ELSEWHERE);
        log(vehicles.missing(u, mine.vehicles.keySet(), before), "탈것", p.getName());
        log(jige.missing(u, mine.jige.keySet(), before), "지게", p.getName());
    }

    private void log(CompletableFuture<List<Long>> marked, String what, String player) {
        marked.whenComplete((ids, err) -> {
            if (err != null) plugin.getLogger().log(java.util.logging.Level.WARNING, "[탈것] 아이템 확인 실패", err);
            else if (!ids.isEmpty()) plugin.getLogger().info("[탈것] " + player + " 님에게서 사라진 " + what + " 아이템 " + ids + " (목록에서 숨김, 다시 나타나면 되살아남)");
        });
    }

    /** Items seen somewhere that is not a player's inventory (a mailbox, the ground, an entity's hand). */
    void elsewhere(ItemStack it) {
        Found f = new Found();
        collect(it, f);
        vehicles.seen(f.vehicles, VehicleStore.ELSEWHERE);
        jige.seen(f.jige, VehicleStore.ELSEWHERE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        last.remove(p.getUniqueId());
        // a moment later (whatever is handed over on joining is in by then): what they had when they left and no longer have is gone
        Bukkit.getScheduler().runTaskLater(plugin, () -> scan(p, null, true), 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        scan(e.getPlayer(), null, true);
        last.remove(e.getPlayer().getUniqueId());
        force.remove(e.getPlayer().getUniqueId());
    }

    /** A view closes: what went into a chest or a bed is elsewhere now, before the player's next look calls it missing. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) scan(p, e.getView().getTopInventory(), false);
    }

    /** Given to an entity (an item frame, an armour stand, an allay): elsewhere, until a look finds it on them again. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityUse(PlayerInteractEntityEvent e) {
        if (VehicleModule.isVehiclePart(e.getRightClicked())) return;
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (!tracked(hand)) return;
        elsewhere(hand);
        force.add(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // On the ground
    // ------------------------------------------------------------------------------------------

    /** An item entity added to a world (dropped, thrown, its chunk loaded, through a portal): it is there. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemAdded(EntityAddToWorldEvent e) {
        if (e.getEntity() instanceof Item i && tracked(i.getItemStack())) elsewhere(i.getItemStack());
    }

    /** An item entity destroyed (lava, fire, cactus, an explosion, the void, despawning, /kill): its items are gone. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemRemoved(EntityRemoveEvent e) {
        if (e.getEntity() instanceof Item i && DESTROYED.contains(e.getCause())) destroyed(i.getItemStack(), "바닥에서 없어짐 (" + e.getCause() + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent e) {
        destroyed(e.getEntity().getItemStack(), "바닥에서 사라짐");
    }

    private void destroyed(ItemStack it, String why) {
        Found f = new Found();
        collect(it, f);
        if (f.isEmpty()) return;
        for (Map.Entry<Long, Long> v : f.vehicles.entrySet()) {
            vehicles.gone(v.getKey(), v.getValue()).thenAccept(ok -> {
                if (ok) plugin.getLogger().info("[탈것] 탈것 아이템 #" + v.getKey() + " " + why + ": 목록에서 숨김");
            });
        }
        for (Map.Entry<Long, Long> j : f.jige.entrySet()) {
            jige.gone(j.getKey(), j.getValue()).thenAccept(ok -> {
                if (ok) plugin.getLogger().info("[탈것] 지게 아이템 #" + j.getKey() + " " + why + ": 목록에서 숨김");
            });
        }
    }

    // ------------------------------------------------------------------------------------------
    // /관리자 탈것 정리
    // ------------------------------------------------------------------------------------------

    /** What /관리자 탈것 정리 did: items found (vehicles, 지게) and those now called gone. */
    record Swept(int foundVehicles, int foundJige, List<Long> goneVehicles, List<Long> goneJige) {}

    /**
     * Everything the server can see now: every online player (as {@link #scan}), every loaded world's item entities, item
     * frames, mobs' hands and entity inventories (chest minecarts, donkeys), the containers of every loaded chunk, and
     * `more` (the beds of the vehicles: VehicleModule). Every vehicle and 지게 item not found there and not changed in the
     * last minute is gone (hidden from the lists; it comes back the moment it is seen). Items in chunks that are not loaded
     * or in a mailbox are called gone too, and come back the same way.
     */
    CompletableFuture<Swept> sweep(Found more) {
        for (Player p : Bukkit.getOnlinePlayers()) scan(p, null, true);
        Found players = new Found(), world = new Found();
        world.vehicles.putAll(more.vehicles);
        world.jige.putAll(more.jige);
        for (Player p : Bukkit.getOnlinePlayers()) {
            collect(p.getInventory().getContents(), players);
            collect(p.getEnderChest().getContents(), players);
            collect(p.getItemOnCursor(), players);
            collect(p.getOpenInventory().getTopInventory().getContents(), players);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Entity en : w.getEntities()) {
                if (en instanceof Player) continue;
                if (en instanceof Item i) collect(i.getItemStack(), world);
                else if (en instanceof ItemFrame f) collect(f.getItem(), world);
                if (en instanceof InventoryHolder h) collect(h.getInventory().getContents(), world);
                if (en instanceof LivingEntity le && le.getEquipment() != null) {
                    for (org.bukkit.inventory.EquipmentSlot s : org.bukkit.inventory.EquipmentSlot.values()) {
                        try {
                            collect(le.getEquipment().getItem(s), world);
                        } catch (IllegalArgumentException ignored) {
                            // a slot this entity does not have
                        }
                    }
                }
            }
            for (Chunk c : w.getLoadedChunks()) {
                for (BlockState s : c.getTileEntities(false)) {
                    if (s instanceof BlockInventoryHolder h) collect(h.getInventory().getContents(), world);
                }
            }
        }
        world.removeAll(players);
        // found in the world: there (a lost one is back); on players: the looks above did that
        vehicles.seen(world.vehicles, VehicleStore.ELSEWHERE);
        jige.seen(world.jige, VehicleStore.ELSEWHERE);
        Found all = new Found();
        for (Found f : List.of(players, world)) {
            all.vehicles.putAll(f.vehicles);
            all.jige.putAll(f.jige);
        }
        long before = System.currentTimeMillis() - 60_000;
        CompletableFuture<List<Long>> gv = vehicles.sweep(all.vehicles, before);
        CompletableFuture<List<Long>> gj = jige.sweep(all.jige, before);
        return gv.thenCombine(gj, (a, b) -> new Swept(all.vehicles.size(), all.jige.size(), new ArrayList<>(a), new ArrayList<>(b)));
    }
}
