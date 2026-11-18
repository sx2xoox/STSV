package dev.stsv.economy.accessory;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 가방 (/가방): the worn belt's bag, rows x 9 cells in pages of 45 (the bottom row turns pages). Its contents belong to the bag's
 * serial, so they go with the bag. 자석: items within its radius fly to the player; with a bag worn, what the inventory cannot take
 * goes into the bag.
 */
final class Bag implements Listener {

    static final int PAGE = 45;

    private final AccessoryModule acc;
    private final Map<UUID, Page> open = new HashMap<>();

    Bag(AccessoryModule acc) {
        this.acc = acc;
    }

    /** The worn bag's serial and its cells (rows x 9), or null. */
    private Object[] worn(UUID player) {
        int rows = (int) acc.value(player, "bag");
        if (rows <= 0) return null;
        for (ItemStack it : acc.store().worn(player)) {
            Accessory a = AccessoryModule.read(it);
            if (a != null && a.effect.equals("bag")) return new Object[] {a.serial, rows * 9};
        }
        return null;
    }

    void open(Player p, int page) {
        Object[] w = worn(p.getUniqueId());
        if (w == null) {
            HudModule.flash(p, Lang.mm("accessory.bag-none"));
            return;
        }
        UUID serial = (UUID) w[0];
        int cells = (int) w[1];
        int pages = (cells + PAGE - 1) / PAGE;
        page = Math.clamp(page, 0, pages - 1);
        Page holder = new Page(serial, page, cells);
        Inventory inv = Bukkit.createInventory(holder, 54, Text.item("<#f0e2b0>가방 <#b9a6d6>· " + (page + 1) + " / " + pages));
        holder.inv = inv;
        ItemStack[] bag = acc.store().bag(serial, cells);
        for (int i = 0; i < PAGE; i++) {
            int at = page * PAGE + i;
            if (at < cells) inv.setItem(i, bag[at]);
            else inv.setItem(i, locked());
        }
        inv.setItem(45, nav(page > 0, "◀ 이전"));
        inv.setItem(53, nav(page < pages - 1, "다음 ▶"));
        open.put(p.getUniqueId(), holder);
        p.openInventory(inv);
    }

    private static ItemStack locked() {
        ItemStack it = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        it.editMeta(m -> m.itemName(Text.item("<dark_gray>잠김 (가방이 커지면 열림)")));
        return it;
    }

    private static ItemStack nav(boolean on, String name) {
        ItemStack it = new ItemStack(on ? Material.ARROW : Material.GRAY_DYE);
        it.editMeta(m -> m.itemName(Text.item((on ? "<yellow>" : "<dark_gray>") + name)));
        return it;
    }

    private static final class Page implements InventoryHolder {
        final UUID serial;
        final int page, cells;
        Inventory inv;

        Page(UUID serial, int page, int cells) {
            this.serial = serial;
            this.page = page;
            this.cells = cells;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    private void save(Page h) {
        ItemStack[] bag = acc.store().bag(h.serial, h.cells);
        for (int i = 0; i < PAGE; i++) {
            int at = h.page * PAGE + i;
            if (at < h.cells) {
                ItemStack it = h.inv.getItem(i);
                bag[at] = it == null || it.isEmpty() ? null : it;
            }
        }
        acc.store().saveBag(h.serial);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Page h)) return;
        int raw = e.getRawSlot();
        Player p = (Player) e.getWhoClicked();
        if (raw >= 45 && raw < 54) {
            e.setCancelled(true);
            if (raw == 45 || raw == 53) {
                save(h);
                int to = h.page + (raw == 45 ? -1 : 1);
                Bukkit.getScheduler().runTask(acc.eco(), () -> open(p, to));
            }
            return;
        }
        if (raw >= 0 && raw < 45 && h.page * PAGE + raw >= h.cells) {
            e.setCancelled(true);
            return;
        }
        // an accessory never goes into a bag (a bag inside a bag, or one hidden from the copy check)
        ItemStack moving = e.isShiftClick() ? e.getCurrentItem() : e.getCursor();
        if (AccessoryModule.isAccessory(moving) || e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                && AccessoryModule.isAccessory(p.getInventory().getItem(e.getHotbarButton()))) {
            if (e.isShiftClick() ? raw >= 54 : raw < 45) e.setCancelled(true);
        }
        if (e.isShiftClick() && raw >= 54) {
            // shift-click from the inventory must not land in the locked cells or the page row
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || it.isEmpty() || AccessoryModule.isAccessory(it)) return;
            for (int i = 0; i < PAGE && it.getAmount() > 0; i++) {
                if (h.page * PAGE + i >= h.cells) break;
                ItemStack cell = h.inv.getItem(i);
                if (cell == null || cell.isEmpty()) {
                    h.inv.setItem(i, it.clone());
                    it.setAmount(0);
                } else if (cell.isSimilar(it) && cell.getAmount() < cell.getMaxStackSize()) {
                    int n = Math.min(it.getAmount(), cell.getMaxStackSize() - cell.getAmount());
                    cell.setAmount(cell.getAmount() + n);
                    it.setAmount(it.getAmount() - n);
                }
            }
            e.setCurrentItem(it.getAmount() <= 0 ? null : it);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Page h)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= 45 && raw < 54 || raw < 45 && h.page * PAGE + raw >= h.cells || raw < 45 && AccessoryModule.isAccessory(e.getOldCursor())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Page h)) return;
        save(h);
        open.remove(e.getPlayer().getUniqueId(), h);
    }

    void forget(Player p) {
        Page h = open.remove(p.getUniqueId());
        if (h != null) save(h);
    }

    void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Page) p.closeInventory();
    }

    /** Puts an item into the worn bag (what fits), returns what is left (null = all in). */
    ItemStack store(Player p, ItemStack it) {
        Object[] w = worn(p.getUniqueId());
        if (w == null || it == null) return it;
        UUID serial = (UUID) w[0];
        if (open.containsKey(p.getUniqueId())) return it;   // the open page is the copy that counts
        ItemStack[] bag = acc.store().bag(serial, (int) w[1]);
        ItemStack left = it.clone();
        for (int i = 0; i < (int) w[1] && left.getAmount() > 0; i++) {
            if (bag[i] == null || bag[i].isEmpty()) {
                bag[i] = left.clone();
                left.setAmount(0);
            } else if (bag[i].isSimilar(left) && bag[i].getAmount() < bag[i].getMaxStackSize()) {
                int n = Math.min(left.getAmount(), bag[i].getMaxStackSize() - bag[i].getAmount());
                bag[i].setAmount(bag[i].getAmount() + n);
                left.setAmount(left.getAmount() - n);
            }
        }
        if (left.getAmount() != it.getAmount()) acc.store().saveBag(serial);
        return left.getAmount() <= 0 ? null : left;
    }

    /** 자석: items in the radius come to the player; with a bag, what the inventory cannot take goes into it. */
    void magnet() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            double r = acc.value(p.getUniqueId(), "magnet");
            if (r <= 0 || p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            for (var ent : p.getNearbyEntities(r, r, r)) {
                if (!(ent instanceof Item item) || item.getPickupDelay() > 0 || !item.canPlayerPickup()) continue;
                if (item.getThrower() != null && item.getThrower().equals(p.getUniqueId()) && item.getTicksLived() < 60) continue;
                ItemStack stack = item.getItemStack();
                if (Items.room(p.getInventory(), stack) >= stack.getAmount()) {
                    item.teleport(p.getLocation());
                } else {
                    ItemStack left = store(p, stack);
                    if (left == null) {
                        item.remove();
                        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.6f);
                        acc.activity(p.getUniqueId(), "pickup", 1);
                    } else if (left.getAmount() != stack.getAmount()) item.setItemStack(left);
                }
            }
        }
    }
}
