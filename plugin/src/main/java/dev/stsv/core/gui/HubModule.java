package dev.stsv.core.gui;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.economy.EconomyModule;
import dev.stsv.core.gui.menu.MenuHandler;
import dev.stsv.core.gui.menu.MenuSpec;
import dev.stsv.core.gui.menu.MenuView;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 통합 메뉴: one menu with every player feature, opened with Shift+F (swap hands while sneaking) or /메뉴.
 * The core's menu is stsv:hub (its own features); a content plugin that adds features swaps in its own
 * menu with {@link dev.stsv.core.gui.menu.MenuModule#override} (the economy ships economy:hub, a superset)
 * and adds its {placeholders} with {@link #provide}.
 *
 * Besides the menu's built-in actions, hub buttons may use "hub:&lt;left&gt;|&lt;right&gt;" where each side is
 * "run:&lt;command&gt;" (run as the player) or "suggest:&lt;command&gt;" (close, and a chat line that types
 * "/&lt;command&gt; " for the player: commands that need a name or an amount). Without a right side the left
 * one is used for every click.
 */
public final class HubModule implements StsvModule, Listener {

    public static final String MENU = "stsv:hub";
    private static final NamespacedKey TIP = new NamespacedKey("stsv", "hub_tip");

    private STSVPlugin plugin;
    private boolean hotkey;
    private boolean joinTip;
    private final Map<String, Function<Player, Map<String, String>>> providers = new LinkedHashMap<>();

    @Override
    public String id() {
        return "hub";
    }

    @Override
    public void enable(STSVPlugin plugin) {
        this.plugin = plugin;
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() {
        load();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
    }

    private void load() {
        hotkey = plugin.getConfig().getBoolean("hub.hotkey");
        joinTip = plugin.getConfig().getBoolean("hub.join-tip");
    }

    public boolean hotkey() {
        return hotkey;
    }

    /** A content plugin's {placeholders} for the hub (asked each time it is drawn; keep them cheap). */
    public void provide(String owner, Function<Player, Map<String, String>> values) {
        providers.put(owner, values);
    }

    public void unprovide(String owner) {
        providers.remove(owner);
    }

    public void open(Player p) {
        plugin.menus().open(p, MENU, new Hub());
    }

    /**
     * The commands a hub button runs or suggests (for checks): "command:돈" -> [돈], "hub:run:직업|run:직업 순위"
     * -> [직업, 직업 순위]; close, menu:... and an empty action -> []; an unknown form -> null.
     */
    public static java.util.List<String> commands(String action) {
        if (action.isEmpty() || action.equals("close") || action.startsWith("menu:")) return java.util.List.of();
        if (action.startsWith("command:")) return java.util.List.of(action.substring(8));
        if (!action.startsWith("hub:")) return null;
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String side : action.substring(4).split("\\|", -1)) {
            if (side.startsWith("run:")) out.add(side.substring(4));
            else if (side.startsWith("suggest:")) out.add(side.substring(8));
            else return null;
        }
        return out.isEmpty() || out.size() > 2 ? null : out;
    }

    // ------------------------------------------------------------------------------------------

    /** Shift+F: sneaking and nothing else open (not in the wardrobe either). Plain F still swaps hands. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!hotkey || !p.isSneaking() || !p.hasPermission("stsv.menu")) return;
        InventoryType open = p.getOpenInventory().getType();
        if (open != InventoryType.CRAFTING && open != InventoryType.CREATIVE) return;
        if (plugin.cosmetics() != null && plugin.cosmetics().inWardrobe(p)) return;
        e.setCancelled(true);
        open(p);
    }

    /** Once per player: Shift+F opens the hub. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!joinTip || !hotkey || p.getPersistentDataContainer().has(TIP)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.getPersistentDataContainer().set(TIP, PersistentDataType.BYTE, (byte) 1);
            p.sendMessage(Lang.msg("hub.join-tip"));
        }, 100L);
    }

    // ------------------------------------------------------------------------------------------

    private final class Hub implements MenuHandler {

        @Override
        public Map<String, String> placeholders(MenuView v) {
            Player p = v.player();
            Map<String, String> m = new HashMap<>();
            m.put("player", p.getName());
            m.put("hotkey", Lang.raw(hotkey ? "hub.hotkey-on" : "hub.hotkey-off"));
            m.put("world", plugin.worlds() == null ? p.getWorld().getName() : plugin.worlds().displayName(p.getWorld()));
            m.put("warmup", String.valueOf(plugin.worlds() == null ? 0 : plugin.worlds().rules().warmup()));
            m.put("cooldown", String.valueOf(plugin.worlds() == null ? 0 : plugin.worlds().rules().cooldown()));
            m.put("balance", plugin.economy() == null ? "-" : EconomyModule.format(plugin.economy().balance(p.getUniqueId())));
            m.put("title", plugin.profiles() == null ? Lang.raw("common.none") : plugin.profiles().wornTitleName(p.getUniqueId()).orElse(Lang.raw("common.none")));
            m.put("points", plugin.achievements() == null ? "0" : String.format(java.util.Locale.ROOT, "%,d", plugin.achievements().points(p.getUniqueId())));
            m.put("cosmetics", plugin.cosmetics() == null ? "0" : plugin.cosmetics().owned(p).size() + "/" + plugin.cosmetics().cosmetics().size());
            m.put("fee", String.valueOf(Math.round((plugin.economy() == null ? 0 : plugin.economy().feeRate()) * 100)));
            for (Function<Player, Map<String, String>> f : providers.values()) {
                try {
                    m.putAll(f.apply(p));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("[통합 메뉴] 자리표시자 오류: " + ex);
                }
            }
            return m;
        }

        /** A PLAYER_HEAD button wears the viewer's own head. */
        @Override
        public ItemStack button(MenuView v, MenuSpec.Button b, ItemStack base) {
            if (base.getType() != Material.PLAYER_HEAD) return base;
            base.editMeta(SkullMeta.class, m -> m.setOwningPlayer(v.player()));
            return base;
        }

        @Override
        public void onAction(MenuView v, String action, ClickType click) {
            if (!action.startsWith("hub:")) return;
            String[] sides = action.substring(4).split("\\|", 2);
            String pick = sides.length > 1 && click.isRightClick() ? sides[1] : sides[0];
            Player p = v.player();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (pick.startsWith("run:")) {
                    p.performCommand(pick.substring(4));
                } else if (pick.startsWith("suggest:")) {
                    v.close();
                    p.sendMessage(Lang.msg("hub.suggest", "command", pick.substring(8)));
                }
            });
        }
    }
}
