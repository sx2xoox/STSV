package dev.stsv.core.cosmetic;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cosmetics: purely visual items worn on top of the real equipment.
 *
 * Showing: the cosmetic is sent as a visual-only equipment change to everyone who can see the player
 * (and to the player, for third-person view). The real helmet and its protection are untouched.
 * It is re-sent whenever the server would overwrite it (equipment change, new viewer, respawn...).
 *
 * Wardrobe: inside a wardrobe zone, /wardrobe gives a fixed view of a mannequin wearing the player's
 * own skin (the player disappears) and opens the menu. Clicking a cosmetic wears it (or takes it off if
 * worn, or only previews it if not owned) and closes the menu to show the mannequin unobstructed;
 * any click or F reopens the menu, sneaking or the exit button leaves.
 */
public final class CosmeticModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyEquipped, keyOwned, keyReturn, keyWardrobeEntity, keyReach;
    private final Map<String, BoundingBox> zones = new LinkedHashMap<>();
    private final Map<String, String> zoneWorlds = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private File zonesFile;
    private CosmeticStore store;

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
        keyReach = new NamespacedKey(plugin, "wardrobe_reach");
        zonesFile = new File(plugin.getDataFolder(), "wardrobes.yml");
        store = new CosmeticStore(plugin.getDataFolder().toPath().resolve("data").resolve("cosmetics.yml"));
        store.load();
        loadZones();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // safety net: whatever overwrote the look (resyncs we cannot see), fix it within 5 seconds
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (equipped(p).isPresent()) refresh(p);
        }, 100L, 100L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            migrate(p);
            restoreIfStuck(p);
            refreshLater(p);
        }
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
        if (plugin.getConfig().getBoolean("cosmetics.op-owns-all", false) && p.hasPermission("stsv.cosmetic.*")) return true;
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

    private void save() {
        try {
            store.save();
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("치장 저장 실패: " + e.getMessage());
        }
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
            p.sendMessage(Text.msg("<red>이 교환권의 치장을 찾을 수 없습니다. 관리자에게 알려주세요."));
            return;
        }
        if (store.owned(p.getUniqueId()).contains(c.get().key().asString())) {
            p.sendActionBar(Text.mm("<yellow>이미 가지고 있는 치장입니다."));
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
                Text.mm(r.color + "<b>새 치장 획득!</b>"),
                Text.mm(displayName(c) + " <dark_gray>·</dark_gray> " + r.color + r.label),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200),
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.4);
        p.sendMessage(Text.msg("<green>치장 </green>" + displayName(c) + "<green> 을(를) 얻었습니다. 옷장(/옷장)에서 입어보세요."));
    }

    /** Sends what the player's head should look like to everyone who sees them (and themselves). */
    public void refresh(Player p) {
        if (!p.isOnline() || sessions.containsKey(p.getUniqueId())) return;
        Set<Player> viewers = new HashSet<>(p.getTrackedBy());
        if (plugin.getConfig().getBoolean("cosmetics.show-to-self", true)) viewers.add(p);
        ItemStack look = look(p);
        for (Player v : viewers) v.sendEquipmentChange(p, EquipmentSlot.HEAD, look);
    }

    private ItemStack look(Player p) {
        return equipped(p).map(c -> c.create(1)).orElseGet(() -> p.getInventory().getHelmet());
    }

    private void refreshLater(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> refresh(p));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { migrate(e.getPlayer()); restoreIfStuck(e.getPlayer()); refreshLater(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { refreshLater(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { refreshLater(e.getPlayer()); }

    @EventHandler
    public void onEquipment(EntityEquipmentChangedEvent e) {
        if (e.getEntity() instanceof Player p && equipped(p).isPresent()) refreshLater(p);
    }

    /** A player starts seeing someone: give them the cosmetic look right after the vanilla packets. */
    @EventHandler
    public void onTrack(PlayerTrackEntityEvent e) {
        if (!(e.getEntity() instanceof Player target) || equipped(target).isEmpty()) return;
        Player viewer = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline() && target.isOnline() && !sessions.containsKey(target.getUniqueId())) {
                viewer.sendEquipmentChange(target, EquipmentSlot.HEAD, look(target));
            }
        });
    }

    /** The client's own inventory gets re-synced by the server on clicks; put the look back. */
    @EventHandler public void onInvClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && equipped(p).isPresent()) refreshLater(p); }
    @EventHandler public void onInvClose(InventoryCloseEvent e) { if (e.getPlayer() instanceof Player p && equipped(p).isPresent()) refreshLater(p); }

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
        y.options().setHeader(List.of("옷장 구역 (/stsv wardrobe create <이름> [반경] 으로 추가)"));
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
    // Wardrobe session: a fixed viewpoint in front of a mannequin wearing the player's own skin.
    // The player is invisible (to themselves and others) and frozen, so only the mannequin is seen.
    // The menu stays open next to it: click = preview + wear, click the worn one = take off.
    // Closing the menu leaves the wardrobe.
    //
    // (A real spectator camera is not used: the server ignores inventory clicks from spectators.)
    // ------------------------------------------------------------------------------------------

    private static final class Session {
        Location origin;
        Location spot;
        boolean wasInvulnerable;
        boolean addedInvisibility;
        Mannequin mannequin;
        BukkitTask spin;
        String previewing;
        Menu menu;
        boolean closing;
    }

    private static final class Menu implements InventoryHolder {
        Inventory inventory;
        final Map<Integer, String> slots = new HashMap<>();

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private static final int ROWS_FOR_COSMETICS = 2;           // slots 0..17
    private static final int SLOT_TAKE_OFF = 18, SLOT_INFO = 22, SLOT_EXIT = 26;
    // hidden from the player's own view while in the wardrobe: armor (third person) and both hands
    // (first person: with invisibility an empty hand draws no arm either)
    private static final EquipmentSlot[] HIDDEN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    public boolean inWardrobe(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public void enterWardrobe(Player p) {
        if (inWardrobe(p)) {
            openMenu(p);
            return;
        }
        if (zoneAt(p.getLocation()).isEmpty()) {
            p.sendMessage(Text.msg("<red>옷장 구역 안에서만 사용할 수 있습니다."));
            return;
        }
        if (p.isInsideVehicle()) {
            p.sendMessage(Text.msg("<red>탈것에서 내린 뒤 사용하세요."));
            return;
        }
        Location stand = p.getLocation().clone();
        stand.setPitch(0);
        Vector forward = stand.getDirection().setY(0).normalize();
        Location spot = null;
        double want = plugin.getConfig().getDouble("cosmetics.wardrobe-distance", 3.2);
        for (double d : new double[]{want, want - 0.5, want - 1.0}) {
            Location c = stand.clone().add(forward.clone().multiply(d));
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            p.sendMessage(Text.msg("<red>앞쪽 공간이 부족합니다. 앞쪽 " + (int) Math.ceil(want) + "칸이 트인 곳에서 사용하세요."));
            return;
        }
        // look straight at the mannequin's middle: the menu is closed while previewing, so it is centred
        spot.setDirection(stand.clone().add(0, 1.0, 0).toVector().subtract(spot.clone().add(0, 1.62, 0).toVector()));

        Session s = new Session();
        s.origin = p.getLocation().clone();
        s.spot = spot;
        s.wasInvulnerable = p.isMarkedInvulnerable();
        s.previewing = equipped(p).map(c -> c.key().asString()).orElse(null);
        Location o = s.origin;
        p.getPersistentDataContainer().set(keyReturn, PersistentDataType.STRING, String.join(";",
                o.getWorld().getName(), "" + o.getX(), "" + o.getY(), "" + o.getZ(), "" + o.getYaw(), "" + o.getPitch(),
                "" + s.wasInvulnerable));

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
        p.showEntity(plugin, s.mannequin);
        sessions.put(p.getUniqueId(), s);
        showOnMannequin(p, s);

        // the player disappears: hidden from others, invisible, and their own armor hidden (F5)
        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.hidePlayer(plugin, p);
        if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) {
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, false, false, false));
            s.addedInvisibility = true;
        }
        Map<EquipmentSlot, ItemStack> none = new HashMap<>();
        for (EquipmentSlot slot : HIDDEN) none.put(slot, null);
        p.sendEquipmentChange(p, none);
        p.setInvulnerable(true);
        org.bukkit.attribute.AttributeInstance reach = p.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        if (reach != null) {
            reach.removeModifier(keyReach);
            reach.addTransientModifier(new org.bukkit.attribute.AttributeModifier(keyReach, 4.0,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
        }
        p.teleport(spot);
        s.spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (s.mannequin.isValid()) s.mannequin.setRotation(s.mannequin.getYaw() + 1.5f, 0);
        }, 20L, 1L);
        p.playSound(spot, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.2f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> openMenu(p), 2L);
    }

    private void showOnMannequin(Player p, Session s) {
        ItemStack head = Optional.ofNullable(s.previewing).flatMap(id -> plugin.content().item(id))
                .map(c -> c.create(1)).orElse(p.getInventory().getHelmet());
        s.mannequin.getEquipment().setHelmet(head);
    }

    public void openMenu(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        Menu menu = new Menu();
        long ownedCount = cosmetics().stream().filter(c -> owns(p, c)).count();
        Inventory inv = Bukkit.createInventory(menu, 27, Text.mm("<dark_gray>옷장 <gray>· 보유 " + ownedCount + "/" + cosmetics().size()));
        menu.inventory = inv;
        s.menu = menu;
        fillMenu(p, s);
        p.openInventory(inv);
    }

    /** (Re)draws the menu in place, so clicking never closes or flickers the window. */
    private void fillMenu(Player p, Session s) {
        Inventory inv = s.menu.inventory;
        s.menu.slots.clear();
        ItemStack empty = icon(org.bukkit.Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of());
        ItemStack bar = icon(org.bukkit.Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, i < 9 * ROWS_FOR_COSMETICS ? empty : bar);

        Optional<CustomItem> worn = equipped(p);
        List<CustomItem> all = cosmetics();
        for (int i = 0; i < all.size() && i < 9 * ROWS_FOR_COSMETICS; i++) {
            CustomItem c = all.get(i);
            boolean owned = owns(p, c);
            boolean isWorn = worn.filter(w -> w.key().equals(c.key())).isPresent();
            CustomItem.Rarity r = c.cosmetic().rarity();
            ItemStack it = c.create(1);
            it.editMeta(m -> {
                List<Component> lore = new ArrayList<>();
                lore.add(Text.item(r.color + "◆ " + r.label));
                lore.add(Text.item(""));
                if (isWorn) {
                    lore.add(Text.item("<green>✔ 착용 중"));
                    lore.add(Text.item("<gray>클릭: <white>벗기"));
                } else if (owned) {
                    lore.add(Text.item("<green>보유 중"));
                    lore.add(Text.item("<gray>클릭: <white>착용"));
                } else {
                    lore.add(Text.item("<red>🔒 미보유"));
                    if (c.cosmetic().obtain() != null) lore.add(Text.item("<gray>획득: <white>" + c.cosmetic().obtain()));
                    lore.add(Text.item("<gray>클릭: <white>미리보기"));
                }
                m.lore(lore);
                m.setEnchantmentGlintOverride(isWorn || c.key().asString().equals(s.previewing));
            });
            inv.setItem(i, it);
            s.menu.slots.put(i, c.key().asString());
        }
        inv.setItem(SLOT_TAKE_OFF, icon(org.bukkit.Material.BARRIER, "<yellow>치장 벗기",
                List.of(worn.map(w -> "<gray>착용 중: " + displayName(w)).orElse("<gray>착용 중인 치장 없음"))));
        inv.setItem(SLOT_INFO, icon(org.bukkit.Material.ARMOR_STAND, "<gold>옷장", List.of(
                "<gray>착용 중: <white>" + worn.map(CosmeticModule::displayName).orElse("없음"),
                "<gray>미리보기: <white>" + Optional.ofNullable(s.previewing).flatMap(id -> plugin.content().item(id))
                        .map(CosmeticModule::displayName).orElse("없음"),
                "",
                "<gray>치장 클릭: 착용 / 착용 중이면 벗기 (창이 닫히고 미리보기)",
                "<gray>미보유 치장은 미리보기만 됩니다.",
                "<gray>우클릭 · 좌클릭 · F: 메뉴 다시 열기",
                "<gray>웅크리기 또는 나가기 버튼: 옷장 나가기")));
        inv.setItem(SLOT_EXIT, icon(org.bukkit.Material.OAK_DOOR, "<red>나가기", List.of()));
    }

    private static ItemStack icon(org.bukkit.Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.itemName(Text.item(name));
            meta.lore(Text.itemLines(lore));
        });
        return it;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            p.closeInventory();
            return;
        }
        int slot = e.getSlot();
        String id = menu.slots.get(slot);
        if (id != null) {
            CustomItem c = plugin.content().item(id).orElse(null);
            if (c == null) return;
            boolean worn = equipped(p).filter(w -> w.key().equals(c.key())).isPresent();
            if (worn) {
                store.setEquipped(p.getUniqueId(), null);
                save();
                s.previewing = null;
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 0.8f);
                p.sendActionBar(Text.mm("<gray>벗었습니다: </gray>" + displayName(c)));
            } else if (owns(p, c)) {
                store.setEquipped(p.getUniqueId(), c.key().asString());
                save();
                s.previewing = id;
                p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
                p.sendActionBar(Text.mm("<green>착용: </green>" + displayName(c)));
            } else {
                s.previewing = id;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
                p.sendActionBar(Text.mm("<red>🔒 미리보기만 가능합니다"
                        + (c.cosmetic().obtain() == null ? "" : " <gray>(획득: " + c.cosmetic().obtain() + ")")));
            }
            showOnMannequin(p, s);
            showPreview(p, s, c);
        } else if (slot == SLOT_TAKE_OFF) {
            store.setEquipped(p.getUniqueId(), null);
            save();
            s.previewing = null;
            showOnMannequin(p, s);
            showPreview(p, s, null);
        } else if (slot == SLOT_EXIT) {
            exitWardrobe(p, true);
        }
    }

    /** Close the menu and turn the view back to the mannequin, with a hint how to continue. */
    private void showPreview(Player p, Session s, CustomItem c) {
        p.closeInventory();
        p.teleport(s.spot); // same spot, but looking straight at the mannequin again
        String state;
        if (c == null) state = "<gray>치장 없음";
        else if (equipped(p).filter(w -> w.key().equals(c.key())).isPresent()) state = "<green>✔ 착용 중";
        else if (owns(p, c)) state = "<gray>벗음";
        else state = "<red>🔒 미보유 · 미리보기";
        p.showTitle(net.kyori.adventure.title.Title.title(
                Text.mm(c == null ? "<gray>치장 없음" : displayName(c)),
                Text.mm(state + " <dark_gray>|</dark_gray> <gray>우클릭: 메뉴 · 웅크리기: 나가기"),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150),
                        java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(400))));
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }

    /** Closing the menu (Esc or picking a cosmetic) keeps the player in the wardrobe, looking at the mannequin. */
    @EventHandler
    public void onMenuClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Menu menu) || !(e.getPlayer() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.menu != menu || s.closing) return;
        p.sendActionBar(Text.mm("<gray>우클릭 · 좌클릭 · F: 메뉴 열기 <dark_gray>|</dark_gray> <gray>웅크리기: 나가기"));
    }

    public void exitWardrobe(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.closing = true;
        if (s.spin != null) s.spin.cancel();
        if (s.mannequin != null) s.mannequin.remove();
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) p.closeInventory();
        if (s.addedInvisibility) p.removePotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        p.teleport(s.origin);
        p.setInvulnerable(s.wasInvulnerable);
        org.bukkit.attribute.AttributeInstance reach = p.getAttribute(org.bukkit.attribute.Attribute.ENTITY_INTERACTION_RANGE);
        if (reach != null) reach.removeModifier(keyReach);
        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.showPlayer(plugin, p);
        // give the player their real equipment view back, then the cosmetic on top
        Map<EquipmentSlot, ItemStack> real = new HashMap<>();
        for (EquipmentSlot slot : HIDDEN) real.put(slot, p.getInventory().getItem(slot));
        p.sendEquipmentChange(p, real);
        p.updateInventory();
        p.getPersistentDataContainer().remove(keyReturn);
        if (sound) p.playSound(s.origin, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1.2f);
        for (long delay : new long[]{1, 10, 40}) Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(p), delay);
        equipped(p).ifPresent(c -> p.sendMessage(Text.msg("<green>착용 중인 치장: </green>" + displayName(c)
                + " <gray>(F5로 확인)")));
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
        } catch (RuntimeException ignored) {
            p.setInvulnerable(false);
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
        if (s == null || !e.hasChangedPosition()) return;
        Location to = s.spot.clone();
        to.setYaw(e.getTo().getYaw());
        to.setPitch(e.getTo().getPitch());
        e.setTo(to);
    }

    private boolean menuOpen(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu;
    }

    /** Any click (right or left, air, block or the mannequin) reopens the menu. */
    @EventHandler
    public void onInteractWhileInWardrobe(PlayerInteractEvent e) {
        if (!inWardrobe(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND && !menuOpen(e.getPlayer())) openMenu(e.getPlayer());
    }

    @EventHandler
    public void onClickMannequin(PlayerInteractEntityEvent e) {
        if (!inWardrobe(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND && !menuOpen(e.getPlayer())) openMenu(e.getPlayer());
    }

    @EventHandler
    public void onHitMannequin(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        if (!inWardrobe(e.getPlayer())) return;
        e.setCancelled(true);
        if (!menuOpen(e.getPlayer())) openMenu(e.getPlayer());
    }

    /** F (swap hands) also opens the menu: works even with an empty hand looking at the sky. */
    @EventHandler
    public void onSwapHands(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (!inWardrobe(e.getPlayer())) return;
        e.setCancelled(true);
        if (!menuOpen(e.getPlayer())) openMenu(e.getPlayer());
    }

    /** Scrolling the hotbar would put another item in the (hidden) hand: keep the slot. */
    @EventHandler(ignoreCancelled = true)
    public void onHotbar(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (inWardrobe(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (e.isSneaking() && inWardrobe(e.getPlayer())) exitWardrobe(e.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exitWardrobe(e.getPlayer(), false);
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
        out.add("OP 전체 보유 설정(cosmetics.op-owns-all): " + plugin.getConfig().getBoolean("cosmetics.op-owns-all", false)
                + " / 권한: " + p.hasPermission("stsv.cosmetic.*"));
        out.add("보는 사람 수: " + p.getTrackedBy().size() + " (+ 본인 표시: " + plugin.getConfig().getBoolean("cosmetics.show-to-self", true) + ")");
        out.add("옷장 안: " + inWardrobe(p));
        return out;
    }
}
