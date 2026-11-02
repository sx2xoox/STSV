package dev.stsv.core.gui.menu;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chest menus from content packs ({@link MenuSpec}), opened by modules with a {@link MenuHandler}.
 * All clicks inside a menu are cancelled (nothing can be taken out or put in); the menu then dispatches
 * built-in actions itself and everything else to the handler.
 */
public final class MenuModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private final Map<String, MenuSpec> menus = new LinkedHashMap<>();

    @Override
    public String id() {
        return "menus";
    }

    @Override
    public void enable(STSVPlugin plugin) throws Exception {
        this.plugin = plugin;
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() throws Exception {
        load();
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuView) p.closeInventory();
        }
        HandlerList.unregisterAll(this);
    }

    private void load() throws Exception {
        List<String> problems = new ArrayList<>();
        menus.clear();
        for (MenuSpec m : MenuSpec.loadAll(plugin.content().packs(), problems)) menus.put(m.id(), m);
        problems.forEach(p -> plugin.getLogger().warning("[메뉴] " + p));
        plugin.getLogger().info("메뉴 " + menus.size() + "개");
    }

    public Collection<MenuSpec> menus() {
        return menus.values();
    }

    public Optional<MenuSpec> menu(String id) {
        return Optional.ofNullable(menus.get(id.contains(":") ? id : "stsv:" + id));
    }

    /** Opens a menu; empty if no such menu exists. */
    public Optional<MenuView> open(Player player, String id, MenuHandler handler) {
        Optional<MenuSpec> spec = menu(id);
        if (spec.isEmpty()) {
            plugin.getLogger().warning("[메뉴] 없는 메뉴: " + id);
            return Optional.empty();
        }
        MenuView view = new MenuView(spec.get(), player, handler == null ? MenuHandler.NONE : handler);
        view.inventory = Bukkit.createInventory(view, spec.get().rows() * 9,
                Text.mm(spec.get().backgroundPrefix() + view.fill(spec.get().title())));
        view.refresh();
        player.openInventory(view.inventory);
        return Optional.of(view);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof MenuView view)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() == null) return;
        if (e.getClickedInventory() != e.getInventory()) {
            if (e.getClickedInventory() == p.getInventory()) view.handler().onPlayerInventoryClick(view, e.getSlot(), e.getClick());
            return;
        }
        int slot = e.getSlot();
        Integer index = view.listAt.get(slot);
        if (index != null) {
            view.handler().onListClick(view, index, e.getClick());
            return;
        }
        MenuSpec.Button b = view.buttonAt.get(slot);
        if (b == null || Boolean.TRUE.equals(view.buttonOff.get(slot)) || b.action().isEmpty()) return;
        String action = b.action();
        if (action.equals("page:prev") || action.equals("page:next")) {
            view.page(view.page() + (action.equals("page:next") ? 1 : -1));
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1f);
        } else if (action.equals("close")) {
            Bukkit.getScheduler().runTask(plugin, view::close);
        } else if (action.startsWith("menu:")) {
            Bukkit.getScheduler().runTask(plugin, () -> open(p, action.substring(5), MenuHandler.NONE));
        } else if (action.startsWith("command:")) {
            Bukkit.getScheduler().runTask(plugin, () -> p.performCommand(action.substring(8)));
        } else {
            view.handler().onAction(view, action, e.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof MenuView) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof MenuView view) view.handler().onClose(view);
    }
}
