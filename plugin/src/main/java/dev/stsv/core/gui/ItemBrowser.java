package dev.stsv.core.gui;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin GUI listing every custom item ({@link ItemCatalog}): category tabs on the top row (전체 and one per
 * category that has items), 36 items a page, page arrows and a search on the bottom row. Click an item = 1,
 * shift-click = a full stack. Search: the compass (then type in chat, "취소" to stop), or /관리자 아이템목록 &lt;검색어&gt;.
 */
public final class ItemBrowser implements Listener {

    private static final int TOP = 9, PAGE_SIZE = 36;
    private static final int PREV = 45, SEARCH = 47, INFO = 49, CLEAR = 51, NEXT = 53;
    private static final long ASK_MS = 60_000;

    private final STSVPlugin plugin;
    /** Players asked to type a search word in chat: until when, and the tab they were on. */
    private final Map<UUID, Ask> asking = new ConcurrentHashMap<>();

    private record Ask(long until, ItemCatalog.Category category) {}

    public ItemBrowser(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        final ItemCatalog.Category category;
        final String query;
        final int page;
        final List<ItemCatalog.Category> tabs = new ArrayList<>();
        Inventory inventory;

        Holder(ItemCatalog.Category category, String query, int page) {
            this.category = category;
            this.query = query;
            this.page = page;
        }

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private ItemCatalog.Facts facts(CustomItem i) {
        return ItemCatalog.facts(i, plugin.content().source(i));
    }

    public void open(Player player, int page) {
        open(player, null, null, page);
    }

    /** category null = all; query null or blank = no search. */
    public void open(Player player, ItemCatalog.Category category, String query, int page) {
        List<CustomItem> all = List.copyOf(plugin.content().items());
        List<CustomItem> items = ItemCatalog.pick(all, this::facts, category, query);
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.clamp(page, 0, pages - 1);
        Holder holder = new Holder(category, query == null || query.isBlank() ? null : query.trim(), page);
        String title = "<dark_gray>STSV 아이템 · " + (category == null ? "전체" : category.label)
                + (holder.query == null ? "" : " · \"" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(holder.query) + "\"") + " <gray>(" + (page + 1) + "/" + pages + ")";
        Inventory inv = Bukkit.createInventory(holder, 54, Text.mm(title));
        holder.inventory = inv;

        // top row: 전체, then each category that has items (with the search applied)
        holder.tabs.add(null);
        for (ItemCatalog.Category c : ItemCatalog.Category.values()) {
            if (!ItemCatalog.pick(all, this::facts, c, holder.query).isEmpty()) holder.tabs.add(c);
        }
        for (int i = 0; i < Math.min(TOP, holder.tabs.size()); i++) {
            ItemCatalog.Category c = holder.tabs.get(i);
            int n = ItemCatalog.pick(all, this::facts, c, holder.query).size();
            boolean on = c == category;
            ItemStack tab = button(c == null ? Material.BOOKSHELF : c.icon,
                    (on ? "<gold>▶ " : "<yellow>") + (c == null ? "전체" : c.label) + " <gray>(" + n + ")", on ? "<gray>보고 있는 분류" : "<gray>클릭: 이 분류 보기");
            if (on) {
                ItemMeta m = tab.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                tab.setItemMeta(m);
            }
            inv.setItem(i, tab);
        }

        for (int i = 0; i < PAGE_SIZE && page * PAGE_SIZE + i < items.size(); i++) {
            inv.setItem(TOP + i, items.get(page * PAGE_SIZE + i).create(1));
        }
        if (page > 0) inv.setItem(PREV, button(Material.ARROW, "<yellow>이전 페이지", null));
        if (page < pages - 1) inv.setItem(NEXT, button(Material.ARROW, "<yellow>다음 페이지", null));
        inv.setItem(SEARCH, button(Material.COMPASS, "<aqua>검색", "<gray>클릭한 뒤 채팅에 이름이나 id 입력 (예: 김치, ruby)"));
        inv.setItem(INFO, button(Material.BOOK, "<gold>아이템 " + items.size() + "개" + (holder.query == null ? "" : " <gray>(검색)"),
                "<gray>클릭: 1개 · 쉬프트 클릭: 한 묶음"));
        if (holder.query != null) inv.setItem(CLEAR, button(Material.BARRIER, "<red>검색 지우기", "<gray>\"" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(holder.query) + "\""));
        player.openInventory(inv);
    }

    private static ItemStack button(Material m, String name, String lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        meta.itemName(Text.item(name));
        if (lore != null) meta.lore(List.of(Text.item(lore)));
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        s.setItemMeta(meta);
        return s;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot < TOP) {
            if (slot < holder.tabs.size()) open(player, holder.tabs.get(slot), holder.query, 0);
            return;
        }
        switch (slot) {
            case PREV -> open(player, holder.category, holder.query, holder.page - 1);
            case NEXT -> open(player, holder.category, holder.query, holder.page + 1);
            case CLEAR -> open(player, holder.category, null, 0);
            case SEARCH -> {
                asking.put(player.getUniqueId(), new Ask(System.currentTimeMillis() + ASK_MS, holder.category));
                player.closeInventory();
                player.sendMessage(Text.msg("<aqua>찾을 아이템 이름이나 id를 채팅에 입력하세요. <gray>(취소: \"취소\", 1분)"));
            }
            default -> {
                if (slot >= TOP + PAGE_SIZE) return;
                plugin.content().itemOf(e.getCurrentItem()).ifPresent(item -> {
                    ItemStack give = item.create(1);
                    if (e.isShiftClick()) give.setAmount(give.getMaxStackSize());
                    player.getInventory().addItem(give).values()
                            .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                });
            }
        }
    }

    /** The search word typed after clicking the compass: the chat line is not sent to anyone. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Ask ask = asking.remove(e.getPlayer().getUniqueId());
        if (ask == null || System.currentTimeMillis() > ask.until()) return;
        e.setCancelled(true);
        String typed = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || !p.hasPermission("stsv.admin")) return;
            if (typed.isEmpty() || typed.equals("취소")) {
                p.sendMessage(Text.msg("<gray>검색 취소"));
                open(p, ask.category(), null, 0);
            } else {
                open(p, null, typed, 0);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        asking.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
    }
}
