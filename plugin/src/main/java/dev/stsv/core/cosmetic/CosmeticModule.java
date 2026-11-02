package dev.stsv.core.cosmetic;

import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
 * Wardrobe: inside a wardrobe zone, /wardrobe moves the camera in front of a mannequin wearing the
 * player's own skin, so they see themselves while choosing. Chat buttons pick/apply; sneak leaves.
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
    // Wardrobe session (camera + mannequin)
    // ------------------------------------------------------------------------------------------

    private static final class Session {
        Location origin;
        GameMode gameMode;
        ItemDisplay camera;
        Mannequin mannequin;
        BukkitTask spin;
        String selected;
    }

    public boolean inWardrobe(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public void enterWardrobe(Player p) {
        if (inWardrobe(p)) {
            menu(p);
            return;
        }
        if (zoneAt(p.getLocation()).isEmpty()) {
            p.sendMessage(Text.msg("<red>옷장 구역 안에서만 사용할 수 있습니다."));
            return;
        }
        if (p.isInsideVehicle() || p.getGameMode() == GameMode.SPECTATOR) {
            p.sendMessage(Text.msg("<red>지금은 옷장을 열 수 없습니다."));
            return;
        }
        Session s = new Session();
        s.origin = p.getLocation().clone();
        s.gameMode = p.getGameMode();
        s.selected = equipped(p).map(c -> c.key().asString()).orElse(null);
        // crash safety: remember how to put the player back
        Location o = s.origin;
        p.getPersistentDataContainer().set(keyReturn, PersistentDataType.STRING, String.join(";",
                o.getWorld().getName(), "" + o.getX(), "" + o.getY(), "" + o.getZ(), "" + o.getYaw(), "" + o.getPitch(), s.gameMode.name()));

        World w = p.getWorld();
        Location stand = o.clone();
        stand.setPitch(0);
        s.mannequin = w.spawn(stand, Mannequin.class, m -> {
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
        // camera: in front of the mannequin, slightly above eye level, looking back at it
        Vector forward = stand.getDirection().setY(0).normalize();
        Location cam = stand.clone().add(forward.multiply(2.8)).add(0, 1.75, 0);
        cam.setDirection(stand.clone().add(0, 1.1, 0).toVector().subtract(cam.toVector()));
        s.camera = w.spawn(cam, ItemDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.getPersistentDataContainer().set(keyWardrobeEntity, PersistentDataType.BYTE, (byte) 1);
        });
        p.showEntity(plugin, s.mannequin);
        p.showEntity(plugin, s.camera);
        sessions.put(p.getUniqueId(), s);
        showOnMannequin(s);

        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(cam);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (inWardrobe(p)) p.setSpectatorTarget(s.camera);
        }, 2L);
        // slow turntable so the cosmetic is seen from every side
        s.spin = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (s.mannequin.isValid()) s.mannequin.setRotation(s.mannequin.getYaw() + 2.5f, 0);
        }, 20L, 1L);
        p.playSound(cam, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.8f, 1.2f);
        menu(p);
    }

    private void showOnMannequin(Session s) {
        ItemStack head = Optional.ofNullable(s.selected).flatMap(id -> plugin.content().item(id))
                .map(c -> c.create(1)).orElse(null);
        Player owner = Bukkit.getPlayer(sessions.entrySet().stream().filter(e -> e.getValue() == s)
                .map(Map.Entry::getKey).findFirst().orElse(null));
        if (head == null && owner != null) head = owner.getInventory().getHelmet();
        s.mannequin.getEquipment().setHelmet(head);
    }

    public void preview(Player p, String id) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        Optional<CustomItem> c = plugin.content().item(id).filter(i -> i.cosmetic() != null);
        if (c.isEmpty()) return;
        s.selected = c.get().key().asString();
        showOnMannequin(s);
        p.sendActionBar(Text.mm("<gray>미리보기: </gray>" + displayName(c.get())));
    }

    public void applySelected(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        if (s.selected == null) {
            equip(p, null);
            p.sendActionBar(Text.mm("<gray>치장을 해제했습니다."));
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
        p.sendActionBar(Text.mm("<green>적용: </green>" + displayName(c)));
    }

    public void clearSelected(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.selected = null;
        showOnMannequin(s);
        p.getPersistentDataContainer().remove(keyEquipped);
        p.sendActionBar(Text.mm("<gray>치장을 해제했습니다."));
    }

    public void exitWardrobe(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.spin != null) s.spin.cancel();
        if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null);
        if (s.camera != null) s.camera.remove();
        if (s.mannequin != null) s.mannequin.remove();
        p.setGameMode(s.gameMode);
        p.teleport(s.origin);
        p.getPersistentDataContainer().remove(keyReturn);
        if (sound) p.playSound(s.origin, Sound.BLOCK_WOODEN_DOOR_CLOSE, 0.8f, 1.2f);
        refreshLater(p);
    }

    /** After a crash the player may still be a spectator at the camera: put them back. */
    private void restoreIfStuck(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String raw = pdc.get(keyReturn, PersistentDataType.STRING);
        if (raw == null || inWardrobe(p)) return;
        String[] a = raw.split(";");
        try {
            World w = Bukkit.getWorld(a[0]);
            if (w != null) p.teleport(new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]),
                    Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5])));
            p.setGameMode(GameMode.valueOf(a[6]));
        } catch (RuntimeException ignored) {
            p.setGameMode(Bukkit.getDefaultGameMode());
        }
        pdc.remove(keyReturn);
    }

    @EventHandler
    public void onStopSpectating(PlayerStopSpectatingEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null && e.getSpectatorTarget() == s.camera) {
            Bukkit.getScheduler().runTask(plugin, () -> exitWardrobe(e.getPlayer(), true)); // sneak = leave
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        exitWardrobe(e.getPlayer(), false);
    }

    // ------------------------------------------------------------------------------------------
    // Chat menu (chat does not blur the world, unlike inventory screens)
    // ------------------------------------------------------------------------------------------

    private static String displayName(CustomItem c) {
        return c.name() == null ? c.key().getKey() : c.name();
    }

    private static Component button(String label, String command, String hover) {
        return Text.mm(label).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Text.mm(hover)));
    }

    public void menu(Player p) {
        Component line = Text.mm("<gradient:#ffb35c:#ff6a3d><b>옷장</b></gradient> <dark_gray>»</dark_gray> ");
        for (CustomItem c : cosmetics()) {
            if (owns(p, c)) {
                line = line.append(button("<white>[" + displayName(c) + "<white>]</white> ",
                        "/wardrobe preview " + c.key().asString(), "<gray>클릭: 미리보기"));
            } else {
                line = line.append(Text.mm("<dark_gray>[" + c.key().getKey() + " 🔒] "));
            }
        }
        p.sendMessage(line);
        p.sendMessage(Text.mm("        ")
                .append(button("<green>[적용]</green> ", "/wardrobe apply", "<gray>미리보기 중인 치장을 입습니다"))
                .append(button("<yellow>[해제]</yellow> ", "/wardrobe remove", "<gray>치장을 벗습니다"))
                .append(button("<red>[나가기]</red>", "/wardrobe exit", "<gray>웅크려도 나갈 수 있습니다")));
        p.sendActionBar(Text.mm("<gray>채팅(T)에서 고르세요 · 웅크리면 나가기"));
    }
}
