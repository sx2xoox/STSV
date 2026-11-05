package dev.stsv.core.cosmetic;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cosmetics: purely visual items worn on top of the real equipment.
 *
 * Showing: the real helmet, its protection and the helmet slot are untouched; only what the clients are
 * told changes (CosmeticPackets rewrites the outgoing packets):
 *  - other players get the cosmetic as the wearer's head item;
 *  - the wearer's own helmet slot is sent as a self-view item (CosmeticViews) that looks exactly like the
 *    real helmet (or the empty slot) in the inventory but is drawn as the cosmetic on the head, so it
 *    shows in third person (F5) and on the inventory paper doll. Only after the resource pack loaded.
 * If packets cannot be rewritten (unknown server internals), others get the look as equipment changes,
 * re-sent whenever the server would overwrite it, and the wearer sees it on the wardrobe mannequin.
 *
 * Wardrobe: inside a wardrobe zone, /wardrobe opens the menu. Clicking a cosmetic wears it (or takes it
 * off if worn, or only previews it if not owned), closes the menu and shows a spectator camera on a
 * mannequin wearing the player's skin (nothing else on screen); sneaking reopens the menu, the exit
 * button leaves.
 */
public final class CosmeticModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyEquipped, keyOwned, keyReturn, keyWardrobeEntity;
    private final Map<String, BoundingBox> zones = new LinkedHashMap<>();
    private final Map<String, String> zoneWorlds = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private File zonesFile;
    private CosmeticStore store;
    /** null = packet rewriting unavailable (fallback: equipment changes to other players only). */
    private CosmeticPackets packets;
    /**
     * Per player: the self-view wrappers in the resource pack their client has loaded (snapshot taken
     * when it reported SUCCESSFULLY_LOADED; a rebuilt pack only counts once the client has it).
     */
    private final Map<UUID, Set<String>> packViews = new HashMap<>();
    /** players currently shown their own cosmetic */
    private final Set<UUID> selfShown = new java.util.HashSet<>();
    private final Map<UUID, Boolean> pendingSync = new HashMap<>();

    @Override
    public String id() {
        return "cosmetics";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keyEquipped = new NamespacedKey(plugin, "cosmetic_head");
        keyOwned = new NamespacedKey(plugin, "cosmetics_owned");
        keyReturn = new NamespacedKey(plugin, "wardrobe_return");
        keyWardrobeEntity = new NamespacedKey(plugin, "wardrobe_entity");
        zonesFile = new File(plugin.getDataFolder(), "wardrobes.yml");
        store = new CosmeticStore(plugin.db());
        try {
            store.load(plugin.getDataFolder().toPath().resolve("data").resolve("cosmetics.yml"));
        } catch (java.io.IOException e) {
            throw new RuntimeException("치장 데이터 가져오기 실패", e);
        }
        loadZones();
        try {
            packets = new CosmeticPackets(plugin.getLogger());
        } catch (Throwable t) {
            packets = null;
            plugin.getLogger().warning("치장 패킷 처리를 쓸 수 없어 다른 사람에게만 보입니다: " + t);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (packets == null) {
            // safety net: whatever overwrote the look (resyncs we cannot see), fix it within 5 seconds
            Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) if (equipped(p).isPresent()) refresh(p);
            }, 100L, 100L);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (packets != null) packets.inject(p);
            migrate(p);
            restoreIfStuck(p);
            refreshLater(p);
        }
        // enabled with players online (plugin reload): they keep the pack they have; the pack module is
        // enabled after this one, so look at its build on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getResourcePackStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                    packViews.put(p.getUniqueId(), currentViews());
                    refresh(p);
                }
            }
        });
        // leftovers from a crash (preview entities are not saved, but be safe)
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) if (e.getPersistentDataContainer().has(keyWardrobeEntity)) e.remove();
        }
    }

    @Override
    public void reload() {
        loadZones();
        Bukkit.getOnlinePlayers().forEach(this::refresh);
    }

    @Override
    public void disable() {
        new ArrayList<>(sessions.keySet()).forEach(id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) exitWardrobe(p, false);
        });
        if (packets != null) {
            packets.clear();
            for (Player p : Bukkit.getOnlinePlayers()) {
                packets.selfView(p, null, Set.of());
                packets.uninject(p);
            }
            // put the real helmet back on every screen (also with a menu open, unlike updateInventory)
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack helmet = helmet(p);
                p.sendEquipmentChange(p, EquipmentSlot.HEAD, helmet);
                for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, EquipmentSlot.HEAD, helmet);
            }
            packets = null;
        }
        HandlerList.unregisterAll(this);
    }

    // ------------------------------------------------------------------------------------------
    // Ownership & equipping
    // ------------------------------------------------------------------------------------------

    public List<CustomItem> cosmetics() {
        return plugin.content().items().stream().filter(i -> i.cosmetic() != null)
                .sorted(java.util.Comparator.comparing((CustomItem i) -> i.cosmetic().rarity()).thenComparing(i -> i.key().asString()))
                .toList();
    }

    /**
     * Owned = in the account's list. Admins can own everything for testing (cosmetics.op-owns-all), and a
     * rank can be given a specific cosmetic with the permission stsv.cosmetic.&lt;id&gt; (only if explicitly set,
     * so operators do not silently own everything).
     */
    public boolean owns(Player p, CustomItem c) {
        if (plugin.getConfig().getBoolean("cosmetics.op-owns-all") && p.hasPermission("stsv.cosmetic.*")) return true;
        String node = "stsv.cosmetic." + c.key().getKey();
        if (p.isPermissionSet(node) && p.hasPermission(node)) return true;
        return store.owned(p.getUniqueId()).contains(c.key().asString());
    }

    public Set<String> owned(Player p) {
        return store.owned(p.getUniqueId());
    }

    /** Grants or revokes. Returns true if something changed. */
    public boolean grant(Player p, CustomItem c, boolean give) {
        boolean changed = give ? store.grant(p.getUniqueId(), c.key().asString())
                : store.revoke(p.getUniqueId(), c.key().asString());
        if (changed) save();
        if (!give) refresh(p);
        return changed;
    }

    public Optional<CustomItem> equipped(Player p) {
        return store.equipped(p.getUniqueId()).flatMap(id -> plugin.content().item(id)).filter(i -> i.cosmetic() != null);
    }

    public void equip(Player p, CustomItem c) {
        store.setEquipped(p.getUniqueId(), c == null ? null : c.key().asString());
        save();
        refresh(p);
    }

    /** Changes are written to the database as they happen (CosmeticStore); nothing to flush. */
    private void save() {
    }

    /** Older versions kept ownership in the player's data file; move it into the store once. */
    private void migrate(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        boolean changed = false;
        String owned = pdc.get(keyOwned, PersistentDataType.STRING);
        if (owned != null) {
            for (String id : owned.split(",")) if (!id.isBlank()) changed |= store.grant(p.getUniqueId(), id);
            pdc.remove(keyOwned);
        }
        String eq = pdc.get(keyEquipped, PersistentDataType.STRING);
        if (eq != null) {
            if (store.equipped(p.getUniqueId()).isEmpty()) store.setEquipped(p.getUniqueId(), eq);
            pdc.remove(keyEquipped);
            changed = true;
        }
        if (changed) save();
    }

    // ------------------------------------------------------------------------------------------
    // Vouchers
    // ------------------------------------------------------------------------------------------

    @EventHandler
    public void onVoucher(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND || inWardrobe(e.getPlayer())) return;
        Optional<CustomItem> v = plugin.content().itemOf(e.getItem()).filter(i -> i.voucher() != null);
        if (v.isEmpty()) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Optional<CustomItem> c = plugin.content().item(v.get().voucher()).filter(i -> i.cosmetic() != null);
        if (c.isEmpty()) {
            p.sendMessage(Lang.msg("cosmetic.voucher-unknown"));
            return;
        }
        if (store.owned(p.getUniqueId()).contains(c.get().key().asString())) {
            dev.stsv.core.hud.HudModule.flash(p, Lang.mm("cosmetic.already-owned"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            return;
        }
        e.getItem().subtract(1);
        store.grant(p.getUniqueId(), c.get().key().asString());
        save();
        celebrate(p, c.get());
    }

    /** "New cosmetic!" moment: title in the rarity colour, toast sound, particles. */
    public void celebrate(Player p, CustomItem c) {
        CustomItem.Rarity r = c.cosmetic().rarity();
        p.showTitle(net.kyori.adventure.title.Title.title(
                Lang.mm("cosmetic.got-title", "color", r.color),
                Lang.mm("cosmetic.got-subtitle", "cosmetic", displayName(c), "color", r.color, "rarity", r.label),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200),
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.4);
        p.sendMessage(Lang.msg("cosmetic.got", "cosmetic", displayName(c)));
    }

    /** Updates what the player's head looks like, to everyone who sees them and to themselves. */
    public void refresh(Player p) {
        if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        Optional<CustomItem> worn = equipped(p);
        if (packets != null) {
            packets.othersView(p.getEntityId(), worn.map(c -> c.create(1)).orElse(null));
            // re-send the real helmet to the others: the packets are rewritten on the way out
            ItemStack helmet = helmet(p);
            for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, EquipmentSlot.HEAD, helmet);
            syncSelf(p);
            return;
        }
        ItemStack look = look(p);
        for (Player v : p.getTrackedBy()) if (v != p) v.sendEquipmentChange(p, EquipmentSlot.HEAD, look);
    }

    private static ItemStack helmet(Player p) {
        ItemStack h = p.getInventory().getHelmet();
        return h == null ? ItemStack.empty() : h;
    }

    private Set<String> currentViews() {
        dev.stsv.core.pack.PackBuilder.Built built = plugin.pack() == null ? null : plugin.pack().current();
        return built == null ? Set.of() : built.cosmeticViews();
    }

    /**
     * Tells the packet layer what the player sees on their own head and puts it in their helmet slot
     * now. Sent as an equipment change of their own entity: the client stores it straight in the slot,
     * also while a chest or menu is open (updateInventory only re-sends the open menu).
     */
    private void syncSelf(Player p) {
        if (packets == null || !p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        Set<String> views = packViews.getOrDefault(p.getUniqueId(), Set.of());
        Optional<CustomItem> worn = equipped(p);
        String id = worn.isPresent() && !views.isEmpty() ? worn.get().key().asString() : null;
        packets.selfView(p, id, views);
        ItemStack helmet = helmet(p);
        ItemStack shown = id == null ? null : CosmeticViews.selfView(helmet, id, views);
        boolean wasShown = id != null ? !selfShown.add(p.getUniqueId()) : selfShown.remove(p.getUniqueId());
        if (shown != null || wasShown) p.sendEquipmentChange(p, EquipmentSlot.HEAD, shown != null ? shown : helmet);
    }

    /**
     * The client predicts some changes itself and the server only corrects what it thinks differs, so
     * the client can be left with the real helmet (or nothing) in the slot: put the view back next tick.
     *
     * @param inventory also re-send the inventory (the client may hold a copy of the view in its hand)
     */
    private void syncSelfLater(Player p, boolean inventory) {
        if (packets == null || !selfShown.contains(p.getUniqueId())) return;
        Boolean pending = pendingSync.put(p.getUniqueId(), inventory || Boolean.TRUE.equals(pendingSync.get(p.getUniqueId())));
        if (pending != null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Boolean inv = pendingSync.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (Boolean.TRUE.equals(inv) && p.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
                p.updateInventory();
            }
            syncSelf(p);
        });
    }

    private ItemStack look(Player p) {
        return equipped(p).map(c -> c.create(1)).orElseGet(() -> p.getInventory().getHelmet());
    }

    private void refreshLater(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(p));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoinInject(PlayerJoinEvent e) {
        if (packets != null) packets.inject(e.getPlayer());
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { migrate(e.getPlayer()); restoreIfStuck(e.getPlayer()); refreshLater(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { refreshLater(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { refreshLater(e.getPlayer()); }

    /**
     * The self view uses models from the server resource pack: only with the pack the client has loaded.
     * A failed (re)load may leave the client without them, so the view is withdrawn.
     */
    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> packViews.put(p.getUniqueId(), currentViews());
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> packViews.remove(p.getUniqueId());
            default -> {
                return;
            }
        }
        if (equipped(p).isPresent() || selfShown.contains(p.getUniqueId())) refreshLater(p);
    }

    @EventHandler
    public void onEquipment(EntityEquipmentChangedEvent e) {
        if (!(e.getEntity() instanceof Player p) || equipped(p).isEmpty()) return;
        if (packets == null) {
            refreshLater(p);
            return;
        }
        // packets are rewritten as they go out, except when the client already predicted the new helmet
        // (it put it in the slot itself, so the server sends nothing)
        if (e.getEquipmentChanges().containsKey(EquipmentSlot.HEAD)) syncSelfLater(p, false);
    }

    /** e.g. picking up the empty-slot view: the server only corrects the cursor, the slot stays empty. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onClickView(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) syncSelfLater(p, false);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onDragView(org.bukkit.event.inventory.InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p) syncSelfLater(p, false);
    }

    /**
     * Right-clicking a helmet: the client swaps it with the view (they differ) while the server may
     * refuse (same as the real one) and send nothing back, leaving the view in the client's hand.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onUseHeadItem(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null || item.isEmpty()) return;
        @SuppressWarnings("UnstableApiUsage")
        io.papermc.paper.datacomponent.item.Equippable eq = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.EQUIPPABLE);
        if (eq != null && eq.slot() == EquipmentSlot.HEAD) syncSelfLater(e.getPlayer(), true);
    }

    /** Creative clients send whole items back: never let a self-view item become a real one. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onCreativeView(org.bukkit.event.inventory.InventoryCreativeEvent e) {
        ItemStack item = e.getCursor();
        ItemStack real = CosmeticViews.restore(item);
        if (real != item) e.setCursor(real);
    }

    /** A player starts seeing someone: give them the cosmetic look right after the vanilla packets. */
    @EventHandler
    public void onTrack(PlayerTrackEntityEvent e) {
        if (packets != null) return; // the spawn packets are rewritten (a head item is added if missing)
        if (!(e.getEntity() instanceof Player target) || equipped(target).isEmpty()) return;
        Player viewer = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && target.isOnline() && !sessions.containsKey(target.getUniqueId())) {
                viewer.sendEquipmentChange(target, EquipmentSlot.HEAD, look(target));
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Wardrobe zones
    // ------------------------------------------------------------------------------------------

    private void loadZones() {
        zones.clear();
        zoneWorlds.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(zonesFile);
        for (String name : y.getKeys(false)) {
            List<Double> min = y.getDoubleList(name + ".min"), max = y.getDoubleList(name + ".max");
            if (min.size() != 3 || max.size() != 3) continue;
            zones.put(name, new BoundingBox(min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2)));
            zoneWorlds.put(name, y.getString(name + ".world"));
        }
    }

    private void saveZones() throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("옷장 구역 (/관리자 옷장 생성 <이름> [반경] 으로 추가)"));
        zones.forEach((name, b) -> {
            y.set(name + ".world", zoneWorlds.get(name));
            y.set(name + ".min", List.of(b.getMinX(), b.getMinY(), b.getMinZ()));
            y.set(name + ".max", List.of(b.getMaxX(), b.getMaxY(), b.getMaxZ()));
        });
        y.save(zonesFile);
    }

    public void createZone(String name, Location center, double radius) throws IOException {
        zones.put(name, BoundingBox.of(center, radius, radius, radius));
        zoneWorlds.put(name, center.getWorld().getName());
        saveZones();
    }

    public boolean deleteZone(String name) throws IOException {
        if (zones.remove(name) == null) return false;
        zoneWorlds.remove(name);
        saveZones();
        return true;
    }

    public Map<String, BoundingBox> zones() {
        return zones;
    }

    public Optional<String> zoneAt(Location loc) {
        for (Map.Entry<String, BoundingBox> z : zones.entrySet()) {
            if (loc.getWorld().getName().equals(zoneWorlds.get(z.getKey())) && z.getValue().contains(loc.toVector())) {
                return Optional.of(z.getKey());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------
    // Wardrobe session, two states:
    //  - PREVIEW: spectator camera fixed on a mannequin wearing the player's own skin. Only the mannequin
    //    is seen: no body, no hand, no hotbar.
    //  - MENU: the cosmetics menu. Spectators' inventory clicks are ignored by the server, so while the
    //    menu is open the player is in creative mode (frozen, invisible, item powers off) at the camera spot.
    // Clicking a cosmetic (wear / take off / preview) or closing the menu goes to PREVIEW;
    // sneaking in PREVIEW (which would normally leave the camera) opens the MENU; the exit button leaves.
    // ------------------------------------------------------------------------------------------

    private static final class Session {
        Location origin;
        Location spot;
        boolean wasInvulnerable;
        boolean addedInvisibility;
        Mannequin mannequin;
        BukkitTask spin;
        String previewing;
        dev.stsv.core.gui.menu.MenuView menu;
        boolean closing;
        GameMode gameMode;
        ItemDisplay camera;
        boolean switching;
        boolean toMenu;
        int page;
    }


    public boolean inWardrobe(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public void enterWardrobe(Player p) {
        if (inWardrobe(p)) {
            openMenu(p);
            return;
        }
        if (zoneAt(p.getLocation()).isEmpty()) {
            p.sendMessage(Lang.msg("wardrobe.zone-only"));
            return;
        }
        if (p.isInsideVehicle()) {
            p.sendMessage(Lang.msg("wardrobe.dismount"));
            return;
        }
        Location stand = p.getLocation().clone();
        stand.setPitch(0);
        Vector forward = stand.getDirection().setY(0).normalize();
        Location spot = null;
        double want = plugin.getConfig().getDouble("cosmetics.wardrobe-distance");
        for (double d : new double[]{want, want - 0.5, want - 1.0}) {
            Location c = stand.clone().add(forward.clone().multiply(d));
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            p.sendMessage(Lang.msg("wardrobe.no-room", "blocks", (int) Math.ceil(want)));
            return;
        }
        // look straight at the mannequin's middle: the menu is closed while previewing, so it is centred
        spot.setDirection(stand.clone().add(0, 1.0, 0).toVector().subtract(spot.clone().add(0, 1.62, 0).toVector()));

        Session s = new Session();
        s.origin = p.getLocation().clone();
        s.spot = spot;
        s.wasInvulnerable = p.isMarkedInvulnerable();
        s.gameMode = p.getGameMode();
        s.previewing = equipped(p).map(c -> c.key().asString()).orElse(null);
        Location o = s.origin;
        p.getPersistentDataContainer().set(keyReturn, PersistentDataType.STRING, String.join(";",
                o.getWorld().getName(), "" + o.getX(), "" + o.getY(), "" + o.getZ(), "" + o.getYaw(), "" + o.getPitch(),
                "" + s.wasInvulnerable, s.gameMode.name()));

        s.mannequin = p.getWorld().spawn(stand, Mannequin.class, m -> {
            m.setVisibleByDefault(false);
            m.setPersistent(false);
            m.setInvulnerable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.setImmovable(true);
            m.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile()));
            m.setDescription(Text.mm("<gray>옷장 미리보기"));
            m.getPersistentDataContainer().set(keyWardrobeEntity, PersistentDataType.BYTE, (byte) 1);
            var eq = m.getEquipment();
            eq.setArmorContents(p.getInventory().getArmorContents());
            eq.setItemInMainHand(p.getInventory().getItemInMainHand());
        });
        // the camera: at eye height of the viewing spot, looking at the mannequin
        Location cam = spot.clone().add(0, 1.62, 0);
        s.camera = p.getWorld().spawn(cam, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(keyWardrobeEntity, PersistentDataType.BYTE, (byte) 1);
        });
        p.showEntity(plugin, s.mannequin);
        p.showEntity(plugin, s.camera);
        sessions.put(p.getUniqueId(), s);
        showOnMannequin(p, s);

        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.hidePlayer(plugin, p);
        if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
            s.addedInvisibility = true;
        }
        p.setInvulnerable(true);
        s.spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (s.mannequin.isValid()) s.mannequin.setRotation(s.mannequin.getYaw() + 1.5f, 0);
        }, 20L, 1L);
        p.playSound(spot, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.2f);
        // attach the camera first, then open the menu: while the client's camera is another entity it
        // draws no hotbar/health, so the menu shows over the mannequin view without any HUD
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(s.camera.getLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWardrobe(p)) return;
            p.setSpectatorTarget(s.camera);
            Bukkit.getScheduler().runTaskLater(plugin, () -> toMenu(p), 1L);
        }, 2L);
    }

    /**
     * MENU state: creative mode with the camera still attached (leaving spectator would detach it; that
     * event is cancelled while switching), then the menu.
     *  - not spectator: the server ignores spectators' inventory clicks
     *  - not adventure: the client draws the held item and the experience bar in adventure/survival even
     *    while its camera is another entity; creative draws neither (and no hearts)
     *  - creative's item powers are shut off for wardrobe players (onCreativeSlot), and the held item is
     *    shown as empty to the player only (hideHands), so no hand appears beside the menu
     */
    private void toMenu(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.toMenu = true;
        p.setGameMode(GameMode.CREATIVE);
        s.toMenu = false;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWardrobe(p)) return;
            openMenu(p);
            hideHands(p);
        }, 1L);
    }

    /** Client-side only: the player's own hands look empty (their invisible arm is then not drawn). */
    private void hideHands(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!inWardrobe(p)) return;
            ItemStack air = new ItemStack(org.bukkit.Material.AIR);
            p.sendEquipmentChange(p, java.util.Map.of(org.bukkit.inventory.EquipmentSlot.HAND, air,
                    org.bukkit.inventory.EquipmentSlot.OFF_HAND, air));
        });
    }

    /** Creative mode can create items (hacked clients send creative slot packets): never in the wardrobe. */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onCreativeSlot(org.bukkit.event.inventory.InventoryCreativeEvent e) {
        if (e.getWhoClicked() instanceof Player p && inWardrobe(p)) e.setCancelled(true);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onWardrobeDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    /** PREVIEW state: spectator camera on the mannequin (no body, hand or hotbar on screen). */
    private void toPreview(Player p, Component title, Component subtitle) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(s.camera.getLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inWardrobe(p)) return;
            p.setSpectatorTarget(s.camera);
            p.showTitle(net.kyori.adventure.title.Title.title(title, subtitle,
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150),
                            java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(400))));
        }, 2L);
    }

    private void showOnMannequin(Player p, Session s) {
        ItemStack head = Optional.ofNullable(s.previewing).flatMap(id -> plugin.content().item(id))
                .map(c -> c.create(1)).orElse(p.getInventory().getHelmet());
        s.mannequin.getEquipment().setHelmet(head);
    }

    static final String WARDROBE_MENU = "stsv:wardrobe";

    /** The cosmetics menu: layout, look and texts are in content/stsv/menus/wardrobe.yml. */
    public void openMenu(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        plugin.menus().open(p, WARDROBE_MENU, new WardrobeMenu(s)).ifPresent(v -> {
            s.menu = v;
            if (s.page > 0) v.page(s.page);
        });
    }

    private final class WardrobeMenu implements dev.stsv.core.gui.menu.MenuHandler {
        private final Session s;

        WardrobeMenu(Session s) {
            this.s = s;
        }

        @Override
        public Map<String, String> placeholders(dev.stsv.core.gui.menu.MenuView v) {
            Player p = v.player();
            Optional<CustomItem> worn = equipped(p);
            return Map.of(
                    "owned", String.valueOf(cosmetics().stream().filter(c -> owns(p, c)).count()),
                    "total", String.valueOf(cosmetics().size()),
                    "worn", worn.map(CosmeticModule::displayName).orElse(Lang.raw("common.none")),
                    "previewing", Optional.ofNullable(s.previewing).flatMap(id -> plugin.content().item(id))
                            .map(CosmeticModule::displayName).orElse(Lang.raw("common.none")));
        }

        @Override
        public List<ItemStack> list(dev.stsv.core.gui.menu.MenuView v) {
            Player p = v.player();
            Optional<CustomItem> worn = equipped(p);
            List<ItemStack> out = new ArrayList<>();
            for (CustomItem c : cosmetics()) {
                boolean owned = owns(p, c);
                boolean isWorn = worn.filter(w -> w.key().equals(c.key())).isPresent();
                CustomItem.Rarity r = c.cosmetic().rarity();
                ItemStack it = c.create(1);
                it.editMeta(m -> {
                    List<Component> lore = new ArrayList<>();
                    lore.add(Lang.item("wardrobe.lore-rarity", "color", r.color, "rarity", r.label));
                    lore.add(Text.item(""));
                    if (isWorn) {
                        lore.addAll(Lang.items("wardrobe.lore-worn"));
                    } else if (owned) {
                        lore.addAll(Lang.items("wardrobe.lore-owned"));
                    } else {
                        lore.addAll(Lang.items("wardrobe.lore-locked"));
                        if (c.cosmetic().obtain() != null) lore.add(Lang.item("wardrobe.lore-obtain", "obtain", c.cosmetic().obtain()));
                        lore.add(Lang.item("wardrobe.lore-locked-click"));
                    }
                    m.lore(lore);
                    m.setEnchantmentGlintOverride(isWorn || c.key().asString().equals(s.previewing));
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(dev.stsv.core.gui.menu.MenuView v, int index, org.bukkit.event.inventory.ClickType click) {
            Player p = v.player();
            List<CustomItem> all = cosmetics();
            if (index >= all.size() || !inWardrobe(p)) return;
            CustomItem c = all.get(index);
            String id = c.key().asString();
            boolean worn = equipped(p).filter(w -> w.key().equals(c.key())).isPresent();
            if (worn) {
                store.setEquipped(p.getUniqueId(), null);
                s.previewing = null;
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 0.8f);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("wardrobe.took-off", "cosmetic", displayName(c)));
            } else if (owns(p, c)) {
                store.setEquipped(p.getUniqueId(), id);
                s.previewing = id;
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
                dev.stsv.core.hud.HudModule.flash(p, Lang.mm("wardrobe.wearing", "cosmetic", displayName(c)));
            } else {
                s.previewing = id;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                dev.stsv.core.hud.HudModule.flash(p, c.cosmetic().obtain() == null ? Lang.mm("wardrobe.preview-only")
                        : Lang.mm("wardrobe.preview-only-obtain", "obtain", c.cosmetic().obtain()));
            }
            showOnMannequin(p, s);
            showPreview(p, s, c);
        }

        @Override
        public void onAction(dev.stsv.core.gui.menu.MenuView v, String action, org.bukkit.event.inventory.ClickType click) {
            Player p = v.player();
            switch (action) {
                case "wardrobe:take_off" -> {
                    store.setEquipped(p.getUniqueId(), null);
                    s.previewing = null;
                    showOnMannequin(p, s);
                    showPreview(p, s, null);
                }
                case "wardrobe:exit" -> Bukkit.getScheduler().runTask(plugin, () -> exitWardrobe(p, true));
                default -> { }
            }
        }

        /** Esc on the menu: go to the camera view (not out of the wardrobe). */
        @Override
        public void onClose(dev.stsv.core.gui.menu.MenuView v) {
            s.page = v.page();
            Player p = v.player();
            if (s.menu != v || s.closing || s.switching) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (inWardrobe(p) && !menuOpen(p)) toPreview(p, Lang.mm("wardrobe.title"), Lang.mm("wardrobe.hint"));
            });
        }
    }

    /** Any click in the wardrobe menu makes the client resync the real held item: hide it again. */
    @EventHandler
    public void onWardrobeClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder(false) instanceof dev.stsv.core.gui.menu.MenuView v && v.spec().id().equals(WARDROBE_MENU)
                && e.getWhoClicked() instanceof Player p) hideHands(p);
    }

    /** After a click: close the menu and switch to the camera, with the cosmetic's state as a title. */
    private void showPreview(Player p, Session s, CustomItem c) {
        String state;
        if (c == null) state = Lang.raw("wardrobe.state-none");
        else if (equipped(p).filter(w -> w.key().equals(c.key())).isPresent()) state = Lang.raw("wardrobe.state-worn");
        else if (owns(p, c)) state = Lang.raw("wardrobe.state-removed");
        else state = Lang.raw("wardrobe.state-locked");
        // never close an inventory inside its own click event: do it on the next tick
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!inWardrobe(p)) return;
            s.switching = true;
            p.closeInventory();
            s.switching = false;
            toPreview(p, c == null ? Lang.mm("wardrobe.state-none") : Text.mm(displayName(c)),
                    Lang.mm("wardrobe.subtitle", "state", state, "hint", Lang.raw("wardrobe.hint")));
        });
    }

    /**
     * The camera must stay attached for the whole session: sneaking in the preview would detach it (then
     * open the menu instead), and switching to creative for the menu would too (just keep it).
     */
    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.closing || e.getSpectatorTarget() != s.camera) return;
        e.setCancelled(true);
        if (!s.toMenu && e.getPlayer().getGameMode() == GameMode.SPECTATOR && !menuOpen(e.getPlayer())) {
            Bukkit.getScheduler().runTask(plugin, () -> toMenu(e.getPlayer()));
        }
    }

    public void exitWardrobe(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.closing = true;
        if (s.spin != null) s.spin.cancel();
        if (menuOpen(p)) p.closeInventory();
        // detach the camera (it may still be attached in creative mode), then restore the game mode
        p.setGameMode(GameMode.SPECTATOR);
        p.setSpectatorTarget(null);
        p.setGameMode(s.gameMode);
        if (s.mannequin != null) s.mannequin.remove();
        if (s.camera != null) s.camera.remove();
        if (s.addedInvisibility) p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        p.teleport(s.origin);
        p.setInvulnerable(s.wasInvulnerable);
        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.showPlayer(plugin, p);
        p.updateInventory();
        p.getPersistentDataContainer().remove(keyReturn);
        if (sound) p.playSound(s.origin, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1.2f);
        for (long delay : new long[]{1, 10, 40}) Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(p), delay);
        equipped(p).ifPresent(c -> p.sendMessage(Lang.msg("wardrobe.left", "cosmetic", displayName(c))));
    }

    /** After a crash the player may still be at the viewing spot: put them back. */
    private void restoreIfStuck(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String raw = pdc.get(keyReturn, PersistentDataType.STRING);
        if (raw == null || inWardrobe(p)) return;
        String[] a = raw.split(";");
        try {
            World w = Bukkit.getWorld(a[0]);
            if (w != null) p.teleport(new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]),
                    Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5])));
            p.setInvulnerable(a.length > 6 && Boolean.parseBoolean(a[6]));
            if (a.length > 7) p.setGameMode(GameMode.valueOf(a[7]));
            else if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(Bukkit.getDefaultGameMode());
        } catch (RuntimeException ignored) {
            p.setInvulnerable(false);
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(Bukkit.getDefaultGameMode());
        }
        // the invisibility we add never outlives the session (infinite, no icon): remove it if it is ours
        org.bukkit.potion.PotionEffect inv = p.getPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        if (inv != null && inv.isInfinite() && !inv.hasIcon()) p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        pdc.remove(keyReturn);
    }

    /** Frozen in place (looking around is fine). */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !e.hasChangedPosition() || e.getPlayer().getGameMode() == GameMode.SPECTATOR) return;
        Location to = s.camera.getLocation().clone();
        to.setYaw(e.getTo().getYaw());
        to.setPitch(e.getTo().getPitch());
        e.setTo(to);
    }

    private boolean menuOpen(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder(false) instanceof dev.stsv.core.gui.menu.MenuView v
                && v.spec().id().equals(WARDROBE_MENU);
    }

    @EventHandler
    public void onInteractWhileInWardrobe(PlayerInteractEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onClickMannequin(PlayerInteractEntityEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exitWardrobe(e.getPlayer(), false);
        packViews.remove(e.getPlayer().getUniqueId());
        selfShown.remove(e.getPlayer().getUniqueId());
        pendingSync.remove(e.getPlayer().getUniqueId());
        if (packets != null) packets.forget(e.getPlayer(), e.getPlayer().getEntityId());
    }

    @EventHandler
    public void onJoinHide(PlayerJoinEvent e) {
        for (UUID id : sessions.keySet()) {
            Player in = Bukkit.getPlayer(id);
            if (in != null && in != e.getPlayer()) e.getPlayer().hidePlayer(plugin, in);
        }
    }

    private static String displayName(CustomItem c) {
        return c.name() == null ? c.key().getKey() : c.name();
    }

    /** /stsv cosmetic info: what the plugin thinks the player is wearing (for debugging). */
    public List<String> info(Player p) {
        List<String> out = new ArrayList<>();
        out.add("착용 중: " + equipped(p).map(c -> c.key().asString()).orElse("없음"));
        out.add("보유(직접 지급): " + (owned(p).isEmpty() ? "없음" : String.join(", ", owned(p))));
        out.add("OP 전체 보유 설정(cosmetics.op-owns-all): " + plugin.getConfig().getBoolean("cosmetics.op-owns-all")
                + " / 권한: " + p.hasPermission("stsv.cosmetic.*"));
        out.add("보는 사람 수: " + p.getTrackedBy().size());
        out.add("패킷 처리: " + (packets == null ? "사용 불가 (다른 사람에게만 보임)" : packets.installed(p) ? "적용됨" : "미적용")
                + " / 리소스팩 적용: " + packViews.containsKey(p.getUniqueId())
                + " / 본인 화면 표시: " + selfShown.contains(p.getUniqueId()));
        out.add("옷장 안: " + inWardrobe(p));
        return out;
    }
}
