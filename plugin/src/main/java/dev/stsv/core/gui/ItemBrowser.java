package dev.stsv.core.gui;

import dev.stsv.core.STSVPlugin;
import dev.stsv.core.content.CustomItem;
import dev.stsv.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Admin GUI listing every custom item. Click = 1, shift-click = full stack. */
public final class ItemBrowser implements Listener {

    private static final int PAGE_SIZE = 45;
    private final STSVPlugin plugin;

    public ItemBrowser(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        final int page;
        Inventory inventory;

        Holder(int page) { this.page = page; }

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    public void open(Player player, int page) {
        List<CustomItem> items = List.copyOf(plugin.content().items());
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.clamp(page, 0, pages - 1);
        Holder holder = new Holder(page);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.mm("<dark_gray>STSV 아이템 <gray>(" + (page + 1) + "/" + pages + ")"));
        holder.inventory = inv;
        for (int i = 0; i < PAGE_SIZE && page * PAGE_SIZE + i < items.size(); i++) {
            inv.setItem(i, items.get(page * PAGE_SIZE + i).create(1));
        }
        if (page > 0) inv.setItem(45, button(Material.ARROW, "<yellow>이전 페이지"));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "<yellow>다음 페이지"));
        inv.setItem(49, button(Material.BOOK, "<gold>아이템 " + items.size() + "개"));
        player.openInventory(inv);
    }

    private static ItemStack button(Material m, String name) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        meta.itemName(Text.item(name));
        s.setItemMeta(meta);
        return s;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getSlot();
        if (slot == 45) { open(player, holder.page - 1); return; }
        if (slot == 53) { open(player, holder.page + 1); return; }
        if (slot >= PAGE_SIZE) return;
        ItemStack clicked = e.getCurrentItem();
        plugin.content().itemOf(clicked).ifPresent(item -> {
            ItemStack give = item.create(1);
            if (e.isShiftClick()) give.setAmount(give.getMaxStackSize());
            player.getInventory().addItem(give).values()
                    .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
    }
}
