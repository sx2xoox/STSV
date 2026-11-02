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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
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
 * Wardrobe: inside a wardrobe zone, /wardrobe puts the player in front of a mannequin wearing their
 * own skin and opens a menu. Picking a cosmetic closes the menu (no blur) and dresses the mannequin;
 * right-click reopens the menu, sneaking leaves.
 */
public final class CosmeticModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private NamespacedKey keyEquipped, keyOwned, keyReturn, keyWardrobeEntity;
    private final Map<String, BoundingBox> zones = new LinkedHashMap<>();
    private final Map<String, String> zoneWorlds = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private File zonesFile;

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
        loadZones();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // safety net: whatever overwrote the look (resyncs we cannot see), fix it within 5 seconds
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (equipped(p).isPresent()) refresh(p);
        }, 100L, 100L);
        for (Player p : Bukkit.getOnlinePlayers()) {
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
        return plugin.content().items().stream().filter(i -> i.cosmetic() != null).toList();
    }

    public boolean owns(Player p, CustomItem c) {
        if (p.hasPermission("stsv.cosmetic.*") || p.hasPermission("stsv.cosmetic." + c.key().getKey())) return true;
        return owned(p).contains(c.key().asString());
    }

    private Set<String> owned(Player p) {
        String raw = p.getPersistentDataContainer().getOrDefault(keyOwned, PersistentDataType.STRING, "");
        return raw.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(raw.split(",")));
    }

    public void grant(Player p, CustomItem c, boolean give) {
        Set<String> set = owned(p);
        if (give) set.add(c.key().asString()); else set.remove(c.key().asString());
        p.getPersistentDataContainer().set(keyOwned, PersistentDataType.STRING, String.join(",", set));
        if (!give && equipped(p).filter(e -> e.key().equals(c.key())).isPresent()) equip(p, null);
    }

    public Optional<CustomItem> equipped(Player p) {
        String id = p.getPersistentDataContainer().get(keyEquipped, PersistentDataType.STRING);
        return id == null ? Optional.empty() : plugin.content().item(id).filter(i -> i.cosmetic() != null);
    }

    public void equip(Player p, CustomItem c) {
        if (c == null) p.getPersistentDataContainer().remove(keyEquipped);
        else p.getPersistentDataContainer().set(keyEquipped, PersistentDataType.STRING, c.key().asString());
        refresh(p);
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

    @EventHandler public void onJoin(PlayerJoinEvent e) { restoreIfStuck(e.getPlayer()); refreshLater(e.getPlayer()); }
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
    // Wardrobe session: the player stands frozen in front of a mannequin wearing their own skin.
    // A menu (inventory UI) picks the cosmetic; picking closes the menu so the world is not blurred.
    // Right-click reopens the menu, sneaking leaves.
    // ------------------------------------------------------------------------------------------

    private static final class Session {
        Location origin;
        Location spot;
        boolean wasInvulnerable;
        Mannequin mannequin;
        BukkitTask spin;
        String selected;
        Menu menu;
    }

    private static final class Menu implements InventoryHolder {
        Inventory inventory;
        final Map<Integer, String> slots = new HashMap<>();

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private static final int[] COSMETIC_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int SLOT_INFO = 4, SLOT_REMOVE = 46, SLOT_APPLY = 49, SLOT_EXIT = 52;

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
        // where the player watches from: in front of the mannequin, looking back at it
        Vector forward = stand.getDirection().setY(0).normalize();
        Location spot = null;
        for (double d : new double[]{2.6, 2.2, 1.8}) {
            Location c = stand.clone().add(forward.clone().multiply(d));
            if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = c;
                break;
            }
        }
        if (spot == null) {
            p.sendMessage(Text.msg("<red>앞쪽 공간이 부족합니다. 앞이 트인 곳에서 사용하세요."));
            return;
        }
        spot.setDirection(stand.clone().add(0, 1.0, 0).toVector().subtract(spot.clone().add(0, 1.62, 0).toVector()));

        Session s = new Session();
        s.origin = p.getLocation().clone();
        s.spot = spot;
        s.wasInvulnerable = p.isMarkedInvulnerable(); // the flag set by setInvulnerable (isInvulnerable() is not)
        s.selected = equipped(p).map(c -> c.key().asString()).orElse(null);
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

        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.hidePlayer(plugin, p);
        p.setInvulnerable(true);
        p.teleport(spot);
        s.spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (s.mannequin.isValid()) s.mannequin.setRotation(s.mannequin.getYaw() + 2.5f, 0);
        }, 20L, 1L);
        p.playSound(spot, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.2f);
        openMenu(p);
    }

    private void showOnMannequin(Player p, Session s) {
        ItemStack head = Optional.ofNullable(s.selected).flatMap(id -> plugin.content().item(id))
                .map(c -> c.create(1)).orElse(p.getInventory().getHelmet());
        s.mannequin.getEquipment().setHelmet(head);
    }

    public void openMenu(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 54, Text.mm("<dark_gray>옷장"));
        menu.inventory = inv;
        ItemStack pane = icon(org.bukkit.Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);

        Optional<CustomItem> worn = equipped(p);
        Optional<CustomItem> sel = Optional.ofNullable(s.selected).flatMap(id -> plugin.content().item(id));
        inv.setItem(SLOT_INFO, icon(org.bukkit.Material.ARMOR_STAND, "<gold>옷장", List.of(
                "<gray>착용 중: <white>" + worn.map(CosmeticModule::displayName).orElse("없음"),
                "<gray>미리보기: <white>" + sel.map(CosmeticModule::displayName).orElse("없음"),
                "",
                "<gray>치장을 클릭하면 창이 닫히고 마네킹이 입어봅니다.",
                "<gray>우클릭: 이 창 다시 열기 · 웅크리기: 나가기")));

        List<CustomItem> all = cosmetics();
        for (int i = 0; i < all.size() && i < COSMETIC_SLOTS.length; i++) {
            CustomItem c = all.get(i);
            ItemStack it;
            if (owns(p, c)) {
                it = c.create(1);
                boolean isWorn = worn.filter(w -> w.key().equals(c.key())).isPresent();
                it.editMeta(m -> {
                    List<Component> lore = new ArrayList<>();
                    lore.add(Text.item(isWorn ? "<green>✔ 착용 중" : "<gray>클릭: 미리보기"));
                    m.lore(lore);
                    if (s.selected != null && s.selected.equals(c.key().asString())) m.setEnchantmentGlintOverride(true);
                });
            } else {
                it = icon(org.bukkit.Material.GRAY_DYE, "<dark_gray>" + c.key().getKey() + " (잠김)",
                        List.of("<dark_gray>아직 가지고 있지 않은 치장입니다."));
            }
            inv.setItem(COSMETIC_SLOTS[i], it);
            menu.slots.put(COSMETIC_SLOTS[i], c.key().asString());
        }
        inv.setItem(SLOT_REMOVE, icon(org.bukkit.Material.BARRIER, "<yellow>치장 벗기", List.of("<gray>지금 착용 중인 치장을 벗습니다.")));
        inv.setItem(SLOT_APPLY, icon(org.bukkit.Material.LIME_CONCRETE, "<green>착용하기",
                List.of(sel.map(c -> "<gray>" + displayName(c) + " <gray>을(를) 입습니다.").orElse("<red>먼저 치장을 클릭해 고르세요."))));
        inv.setItem(SLOT_EXIT, icon(org.bukkit.Material.OAK_DOOR, "<red>나가기", List.of()));
        s.menu = menu;
        p.openInventory(inv);
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
            if (!owns(p, c)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
                return;
            }
            s.selected = id;
            showOnMannequin(p, s);
            p.closeInventory(); // close so the world is not blurred while looking at the mannequin
            p.showTitle(net.kyori.adventure.title.Title.title(Text.mm(displayName(c)),
                    Text.mm("<gray>우클릭: 메뉴 (착용하기) · 웅크리기: 나가기"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150),
                            java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(400))));
        } else if (slot == SLOT_APPLY) {
            applySelected(p);
            openMenu(p);
        } else if (slot == SLOT_REMOVE) {
            clearSelected(p);
            openMenu(p);
        } else if (slot == SLOT_EXIT) {
            p.closeInventory();
            exitWardrobe(p, true);
        }
    }

    public void applySelected(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        if (s.selected == null) {
            p.sendActionBar(Text.mm("<red>먼저 치장을 클릭해서 골라주세요."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
            return;
        }
        CustomItem c = plugin.content().item(s.selected).orElse(null);
        if (c == null) return;
        if (!owns(p, c)) {
            p.sendActionBar(Text.mm("<red>아직 가지고 있지 않은 치장입니다."));
            return;
        }
        p.getPersistentDataContainer().set(keyEquipped, PersistentDataType.STRING, c.key().asString());
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
        p.sendActionBar(Text.mm("<green>착용: </green>" + displayName(c)));
    }

    public void clearSelected(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.selected = null;
        p.getPersistentDataContainer().remove(keyEquipped);
        showOnMannequin(p, s);
        p.sendActionBar(Text.mm("<gray>치장을 벗었습니다."));
    }

    public void exitWardrobe(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.spin != null) s.spin.cancel();
        if (s.mannequin != null) s.mannequin.remove();
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) p.closeInventory();
        p.teleport(s.origin);
        p.setInvulnerable(s.wasInvulnerable);
        for (Player other : Bukkit.getOnlinePlayers()) if (other != p) other.showPlayer(plugin, p);
        p.getPersistentDataContainer().remove(keyReturn);
        if (sound) p.playSound(s.origin, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1.2f);
        // the teleport and re-tracking send fresh vanilla equipment; put the cosmetic back a few times
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

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && inWardrobe(e.getPlayer())) exitWardrobe(e.getPlayer(), true);
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (!inWardrobe(e.getPlayer()) || e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) openMenu(e.getPlayer());
    }

    @EventHandler
    public void onClickMannequin(PlayerInteractEntityEvent e) {
        if (!inWardrobe(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) openMenu(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exitWardrobe(e.getPlayer(), false);
    }

    @EventHandler
    public void onJoinHide(PlayerJoinEvent e) {
        // players already in a wardrobe stay hidden from people who join meanwhile
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
        out.add("전체 권한(stsv.cosmetic.*): " + p.hasPermission("stsv.cosmetic.*"));
        out.add("보는 사람 수: " + p.getTrackedBy().size() + " (+ 본인 표시: " + plugin.getConfig().getBoolean("cosmetics.show-to-self", true) + ")");
        out.add("옷장 안: " + inWardrobe(p));
        return out;
    }
}
