package dev.stsv.core.toolskin;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.ContentPack;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.toolskin.ToolSkins.Skin;
import dev.stsv.core.toolskin.ToolSkins.Tool;
import dev.stsv.core.unlock.UnlockModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool skins (도구 스킨, /스킨): a look a player owns ({@link UnlockModule}, kind {@link #KIND}) and switches on for a
 * kind of tool. A tool in their hands (either hand) of that kind takes the look while they hold it: the item's model
 * is changed and a tag says which skin it carries. Whoever else holds the tool sees it turn back, and so does the
 * owner when they switch the skin off. Nothing but the look changes (durability, enchantments, what it mines).
 *
 * <p>Not touched: custom items (they have looks of their own) and tools that carry a skin book's skin (SkinModule).
 */
public final class ToolSkinModule implements StsvModule, Listener, TabExecutor {

    public static final String KIND = "tool_skin";
    /** Wears every skin without owning it (admins: trying them out). */
    public static final String PERMISSION_ALL = "stsv.toolskin.all";
    private static final int SYNC_TICKS = 20;

    private STSVPlugin plugin;
    private ToolSkinStore store;
    private ToolSkins skins = ToolSkins.EMPTY;
    private NamespacedKey keySkin, keyBook;
    /** player → tool → skin id (online players) */
    private final Map<UUID, Map<Tool, String>> worn = new ConcurrentHashMap<>();
    private BukkitTask ticker;

    @Override
    public String id() {
        return "tool-skins";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        keySkin = new NamespacedKey(plugin, "tool_skin");
        keyBook = new NamespacedKey(plugin, "skin");          // SkinModule's tag: such a tool is left alone
        store = new ToolSkinStore(plugin.db());
        store.init();
        load();
        if (plugin.unlocks() != null) {
            plugin.unlocks().register(new UnlockModule.Kind(KIND, "도구스킨", id -> skins.skin(id) == null ? id : skins.skin(id).name(),
                    () -> skins.skins().keySet(), this::icon, null, (player, id) -> {
                        Player p = Bukkit.getPlayer(player);
                        if (p != null) sync(p);
                    }));
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) read(p);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) sync(p);
        }, SYNC_TICKS, SYNC_TICKS);
    }

    @Override
    public void reload() {
        load();
        for (Player p : Bukkit.getOnlinePlayers()) sync(p);
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        HandlerList.unregisterAll(this);
        worn.clear();
    }

    /** Reads every pack's tool_skins.yml (a later pack adds its series; the same skin id twice: the first stays). */
    private void load() {
        Map<String, ToolSkins.Series> series = new java.util.LinkedHashMap<>();
        Map<String, Skin> all = new java.util.LinkedHashMap<>();
        for (ContentPack pack : plugin.content().packs()) {
            Path f = pack.dir().resolve("tool_skins.yml");
            if (!Files.isRegularFile(f)) continue;
            ToolSkins read = ToolSkins.parse(YamlConfiguration.loadConfiguration(f.toFile()),
                    problem -> plugin.getLogger().warning("[도구 스킨] " + pack.id() + "/tool_skins.yml: " + problem));
            read.series().forEach(series::putIfAbsent);
            read.skins().forEach(all::putIfAbsent);
        }
        skins = new ToolSkins(java.util.Collections.unmodifiableMap(series), java.util.Collections.unmodifiableMap(all));
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("tool-skins.enabled") && !skins.skins().isEmpty();
    }

    public ToolSkins skins() {
        return skins;
    }

    /** Whether a player may wear a skin: they own it, or they are an admin trying it out. */
    public boolean may(Player p, String skin) {
        return p.hasPermission(PERMISSION_ALL) || (plugin.unlocks() != null && plugin.unlocks().has(p.getUniqueId(), KIND, skin));
    }

    // ------------------------------------------------------------------------------------------
    // The tool in the hand
    // ------------------------------------------------------------------------------------------

    private void read(Player p) {
        UUID u = p.getUniqueId();
        store.worn(u).thenAccept(map -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Map<Tool, String> mine = new HashMap<>();
            map.forEach((tool, skin) -> {
                Tool t = Tool.parse(tool);
                if (t != null) mine.put(t, skin);
            });
            worn.put(u, mine);
            sync(p);
        }));
    }

    /** The skin a player has on for a kind of tool and may wear now, or null. */
    private Skin wanted(Player p, Tool tool) {
        Map<Tool, String> mine = worn.get(p.getUniqueId());
        Skin s = mine == null || !enabled() ? null : skins.skin(mine.get(tool));
        return s != null && s.tool() == tool && may(p, s.id()) ? s : null;
    }

    /** Makes both hands' tools look as their holder has them. */
    public void sync(Player p) {
        for (EquipmentSlot hand : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            ItemStack stack = p.getInventory().getItem(hand);
            if (stack == null || stack.isEmpty() || !stack.hasItemMeta() && worn.getOrDefault(p.getUniqueId(), Map.of()).isEmpty()) continue;
            ItemStack fixed = dressed(p, stack);
            if (fixed != null) p.getInventory().setItem(hand, fixed);
        }
    }

    /** The stack as it should look in this player's hand, or null when it is right already. */
    private ItemStack dressed(Player p, ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        String tagged = meta.getPersistentDataContainer().get(keySkin, PersistentDataType.STRING);
        Tool tool = Tool.of(stack.getType().name());
        boolean plain = tool != null && !meta.getPersistentDataContainer().has(keyBook) && plugin.content().itemOf(stack).isEmpty();
        Skin want = plain ? wanted(p, tool) : null;
        String wantId = want == null ? null : want.id();
        if (Objects.equals(tagged, wantId)) return null;
        NamespacedKey model = want == null ? null : NamespacedKey.fromString(want.model());
        if (want != null && model == null) return null;
        ItemStack out = stack.clone();
        out.editMeta(m -> {
            if (want == null) {
                m.setItemModel(null);
                m.getPersistentDataContainer().remove(keySkin);
            } else {
                m.setItemModel(model);
                m.getPersistentDataContainer().set(keySkin, PersistentDataType.STRING, want.id());
            }
        });
        return out;
    }

    private void later(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) sync(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        read(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        worn.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) later(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) later(p);
    }

    // ------------------------------------------------------------------------------------------
    // /스킨
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("common.players-only"));
            return true;
        }
        if (!enabled()) {
            p.sendMessage(Lang.msg("toolskin.disabled"));
            return true;
        }
        plugin.menus().open(p, "stsv:tool_skins", new Screen());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    /** Switches a skin on for its kind of tool, or off (it was on). */
    private void toggle(Player p, Skin skin) {
        Map<Tool, String> mine = worn.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        boolean on = skin.id().equals(mine.get(skin.tool()));
        if (on) mine.remove(skin.tool());
        else mine.put(skin.tool(), skin.id());
        store.wear(p.getUniqueId(), skin.tool().name(), on ? null : skin.id());
        sync(p);
        dev.stsv.core.hud.HudModule.flash(p, Lang.mm(on ? "toolskin.turned-off" : "toolskin.turned-on", "name", skin.name(), "tool", skin.tool().word));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, on ? 1.0f : 1.5f);
    }

    /** A vanilla tool of a kind, to show a skin on. */
    private static Material sample(Tool tool) {
        return switch (tool) {
            case SWORD -> Material.IRON_SWORD;
            case PICKAXE -> Material.IRON_PICKAXE;
            case SHOVEL -> Material.IRON_SHOVEL;
            case AXE -> Material.IRON_AXE;
            case HOE -> Material.IRON_HOE;
            case FISHING_ROD -> Material.FISHING_ROD;
        };
    }

    /** A skin as an item to look at (a menu's tile, the donation shop's product): the tool in that look. */
    public ItemStack icon(String skinId) {
        Skin s = skins.skin(skinId);
        if (s == null) return new ItemStack(Material.PAPER);
        ItemStack it = new ItemStack(sample(s.tool()));
        NamespacedKey model = NamespacedKey.fromString(s.model());
        it.editMeta(m -> {
            if (model != null) m.setItemModel(model);
            m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        });
        return it;
    }

    private final class Screen implements MenuHandler {
        private List<Skin> shown = List.of();

        @Override
        public Map<String, String> placeholders(MenuView view) {
            Player p = view.player();
            long owned = skins.skins().keySet().stream().filter(id -> may(p, id)).count();
            Map<Tool, String> mine = worn.getOrDefault(p.getUniqueId(), Map.of());
            long on = mine.entrySet().stream().filter(e -> wanted(p, e.getKey()) != null).count();
            return Map.of("owned", String.valueOf(owned), "total", String.valueOf(skins.skins().size()), "on", String.valueOf(on));
        }

        @Override
        public List<ItemStack> list(MenuView view) {
            Player p = view.player();
            Map<Tool, String> mine = worn.getOrDefault(p.getUniqueId(), Map.of());
            List<Skin> order = new ArrayList<>(skins.skins().values());
            // what the player owns first; within that, as the file has them (series by series)
            order.sort(java.util.Comparator.comparingInt(s -> may(p, s.id()) ? 0 : 1));
            shown = order;
            List<ItemStack> out = new ArrayList<>();
            for (Skin s : order) {
                boolean owned = may(p, s.id()), on = owned && s.id().equals(mine.get(s.tool()));
                ToolSkins.Series series = skins.series().get(s.series());
                ItemStack it = icon(s.id());
                it.editMeta(m -> {
                    m.itemName(Lang.item(owned ? "toolskin.tile-name" : "toolskin.tile-name-locked", "name", s.name()));
                    m.lore(Lang.items(on ? "toolskin.tile-lore-on" : owned ? "toolskin.tile-lore" : "toolskin.tile-lore-locked",
                            "series", series == null ? s.series() : series.name(), "tool", s.tool().word));
                    m.setEnchantmentGlintOverride(on);
                });
                out.add(it);
            }
            return out;
        }

        @Override
        public void onListClick(MenuView view, int index, ClickType click) {
            Player p = view.player();
            if (index < 0 || index >= shown.size()) return;
            Skin s = shown.get(index);
            if (!may(p, s.id())) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return;
            }
            toggle(p, s);
            view.refresh();
        }

        @Override
        public void onAction(MenuView view, String action, ClickType click) {
            if (!action.equals("toolskin:off")) return;
            Player p = view.player();
            Map<Tool, String> mine = worn.get(p.getUniqueId());
            if (mine == null || mine.isEmpty()) return;
            mine.clear();
            store.clear(p.getUniqueId());
            sync(p);
            dev.stsv.core.hud.HudModule.flash(p, Lang.mm("toolskin.all-off"));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
            view.refresh();
        }
    }
}
