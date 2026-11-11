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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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

    @Override
    public String id() {
        return "jige";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        store = new JigeStore(plugin.db());
        store.init();
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        org.bukkit.command.PluginCommand command = plugin.getCommand("지게");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        if (plugin.cosmetics() != null) plugin.cosmetics().backOverride(this::look);
        for (Player p : Bukkit.getOnlinePlayers()) JigeSpeed.remove(p.getAttribute(Attribute.MOVEMENT_SPEED));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
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
        }
        sessions.clear();
        holders.clear();
        if (plugin.cosmetics() != null) plugin.cosmetics().backOverride(null);
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

    /** Writes the 지게's id and token on its item (its number in the lore the first time). */
    private static void stamp(ItemStack it, long id, long token) {
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
            }
            case SAME -> JigeSpeed.apply(p.getAttribute(Attribute.MOVEMENT_SPEED), s.spec.slowdown());
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
        JigeSpec spec = specs.get(item.key());
        long next = newToken(), now = System.currentTimeMillis();
        pending.add(u);
        var step = id > 0 ? store.claim(id, token, next, now) : store.create(u, p.getName(), next, now);
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
            // the item claimed: still worn (start), moved within the inventory (stamped, not worn), or gone (undone)
            ItemStack worn = p.isOnline() ? p.getInventory().getChestplate() : null;
            if (worn != null && jige(worn).isPresent() && idOf(worn) == id && tokenOf(worn) == token) {
                stamp(worn, row.id(), next);
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
            if (moved != null) stamp(moved, row.id(), next);
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
                Holder h = new Holder(s.id);
                Inventory inv = Bukkit.createInventory(h, s.load.length,
                        Lang.mm("jige.title", "used", s.used(), "slots", s.load.length));
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
        for (int i = 0; i < s.load.length && i < inv.getSize(); i++) {
            ItemStack now = inv.getItem(i);
            now = now == null || now.isEmpty() ? null : now.clone();
            if (!java.util.Objects.equals(s.load[i], now)) {
                s.load[i] = now;
                changed = true;
            }
        }
        if (!changed) return;
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
