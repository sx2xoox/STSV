package dev.stsv.core.gui.menu;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.module.StsvModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Chest menus from content packs ({@link MenuSpec}), opened by modules with a {@link MenuHandler}.
 * All clicks inside a menu are cancelled (nothing can be taken out or put in); the menu then dispatches
 * built-in actions itself and everything else to the handler.
 */
public final class MenuModule implements StsvModule, Listener {

    private STSVPlugin plugin;
    private final Map<String, MenuSpec> menus = new LinkedHashMap<>();
    private final Map<String, String> overrides = new java.util.concurrent.ConcurrentHashMap<>();

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
        return Optional.ofNullable(menus.get(full(id)));
    }

    /**
     * Opening menu id shows menu by instead (while by exists), e.g. a content plugin's fuller version of a
     * core menu (economy:hub for stsv:hub). by == null removes the override. Plugins set it on enable and
     * remove it on disable, so leftover content of a removed plugin changes nothing.
     */
    public void override(String id, String by) {
        if (by == null) overrides.remove(full(id));
        else overrides.put(full(id), full(by));
    }

    private static String full(String id) {
        return id.contains(":") ? id : "stsv:" + id;
    }

    /** Opens a menu (or its override); empty if no such menu exists. */
    public Optional<MenuView> open(Player player, String id, MenuHandler handler) {
        String by = overrides.get(full(id));
        Optional<MenuSpec> spec = by != null && menus.containsKey(by) ? menu(by) : menu(id);
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
            Bukkit.getScheduler().runTask(plugin, () -> run(view, action.substring(8)));
        } else {
            view.handler().onAction(view, action, e.getClick());
        }
    }

    /**
     * Runs a command for the menu's player (main thread). A command that opens a menu of its own replaces this one; one that only
     * answers in chat (balance, rankings, a toggle) closes this menu, so the answer is seen and the click visibly did something.
     */
    public void run(MenuView view, String command) {
        Player p = view.player();
        p.performCommand(command);
        if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder(false) == view) view.close();
    }

    // ------------------------------------------------------------------------------------------
    // Typed answers (an amount, a search word): the menu closes, the next chat line is the answer
    // ------------------------------------------------------------------------------------------

    /** Seconds a typed answer is waited for. */
    public static final int PROMPT_SECONDS = 60;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    private record Prompt(Consumer<String> answer, BukkitTask timeout) {}

    /**
     * Asks the player to type an answer in chat: closes their menu, shows the question, and hands the next chat line (which is
     * not sent to anyone) to answer on the main thread. "취소" (or "cancel") gives answer null, so the caller can reopen its menu;
     * no answer in {@link #PROMPT_SECONDS} seconds, or leaving, drops the question.
     */
    public void prompt(Player p, Component question, Consumer<String> answer) {
        if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuView v) v.close();
        Prompt old = prompts.remove(p.getUniqueId());
        if (old != null) old.timeout().cancel();
        UUID id = p.getUniqueId();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (prompts.remove(id) != null && p.isOnline()) p.sendMessage(Lang.msg("menu.prompt-timeout"));
        }, PROMPT_SECONDS * 20L);
        prompts.put(id, new Prompt(answer, timeout));
        p.sendMessage(question);
        p.sendMessage(Lang.msg("menu.prompt", "seconds", PROMPT_SECONDS));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
    }

    /** True while the player is asked to type something (their next chat line is an answer, not chat). */
    public boolean prompting(Player p) {
        return prompts.containsKey(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        pr.timeout().cancel();
        String text = PlainTextComponentSerializer.plainText().serialize(e.originalMessage()).trim();
        boolean cancel = text.equals("취소") || text.equalsIgnoreCase("cancel");
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) pr.answer().accept(cancel ? null : text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Prompt pr = prompts.remove(e.getPlayer().getUniqueId());
        if (pr != null) pr.timeout().cancel();
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
