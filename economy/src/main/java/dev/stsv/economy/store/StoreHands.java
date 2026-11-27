package dev.stsv.economy.store;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * What a worker holds in their main hand for a moment in place of the scanner (기획안 §17: the mop, a box in both arms, the
 * jug, the 폐기 bag, the tray of cups, the 발주 PDA, the customer's ID). The held thing is a {@link StoreModule#TOOL} like the
 * scanner (never dropped, never put in a chest, taken away at clock-out, on a quit, on death, at a join off shift) and also
 * {@link #HELD}: it cannot be moved in the worker's own inventory either, nor the hotbar slot changed off it. When it is put
 * down the scanner comes back into the same slot. A thing the pack does not have is never held (the caller keeps the old,
 * text-only way).
 */
final class StoreHands {

    /** The held thing (its id). */
    static final NamespacedKey HELD = new NamespacedKey("economy", "store_held");

    private final StoreModule m;
    final StoreMotion.HeldBook book = new StoreMotion.HeldBook();

    StoreHands(StoreModule m) {
        this.m = m;
    }

    static boolean isHeld(ItemStack it) {
        return it != null && !it.getType().isAir() && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(HELD);
    }

    /** What this player holds (id), or null. */
    String held(Player p) {
        return p == null ? null : book.id(p.getUniqueId());
    }

    boolean holds(Player p, String id) {
        return p != null && book.holds(p.getUniqueId(), id);
    }

    /**
     * `id` into the worker's main hand (where the scanner is; something held before is swapped out), put back by itself after
     * `seconds` (0: only by an action). False when the pack has no such item or there is no hand slot for it.
     */
    boolean hold(Player p, String id, int seconds) {
        if (p == null || !m.has(id)) return false;
        ItemStack it = dev.stsv.economy.util.Items.create(id, 1).orElse(null);
        if (it == null) return false;
        it.editMeta(meta -> {
            meta.getPersistentDataContainer().set(StoreModule.TOOL, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(HELD, PersistentDataType.STRING, id);
        });
        UUID u = p.getUniqueId();
        PlayerInventory inv = p.getInventory();
        StoreMotion.HeldBook.Held before = book.get(u);
        int slot;
        if (before != null) {
            slot = heldSlot(inv);
            if (slot < 0) slot = before.slot();
        } else {
            slot = scannerSlot(inv);
            if (slot < 0 || slot >= 9) {   // no scanner on the hotbar: an empty held slot, else not now
                int h = inv.getHeldItemSlot();
                ItemStack at = inv.getItem(h);
                if (at != null && !at.getType().isAir()) return false;
                slot = h;
            }
        }
        if (slot < 0 || slot >= 9) return false;
        inv.setItem(slot, it);
        inv.setHeldItemSlot(slot);
        book.hold(u, id, slot, seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : 0);
        return true;
    }

    /** A later time for the held thing to go back by itself (it is being used). */
    void touch(Player p, int seconds) {
        if (p != null) book.touch(p.getUniqueId(), seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : 0);
    }

    /** The held thing goes, the scanner comes back to its slot (when the worker has none elsewhere). What it was, or null. */
    String release(Player p) {
        if (p == null) return null;
        StoreMotion.HeldBook.Held h = book.release(p.getUniqueId());
        if (h == null) return null;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isHeld(inv.getItem(i))) inv.setItem(i, null);
        if (isHeld(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (isHeld(p.getItemOnCursor())) p.setItemOnCursor(null);
        if (scannerSlot(inv) < 0 && m.working(p.getUniqueId())) {
            ItemStack scanner = m.tool(StoreModule.SCANNER);
            ItemStack at = inv.getItem(h.slot());
            if (at == null || at.getType().isAir()) inv.setItem(h.slot(), scanner);
            else inv.addItem(scanner);
        }
        return h.id();
    }

    /** Forgets a worker (their tools were all taken away: clock-out, a quit). */
    void forget(UUID u) {
        book.release(u);
    }

    private static int heldSlot(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) if (isHeld(inv.getItem(i))) return i;
        return -1;
    }

    /** The scanner's slot (a tool that is not a held thing), -1 none. */
    private static int scannerSlot(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (StoreModule.isTool(it) && !isHeld(it)) return i;
        }
        return -1;
    }
}
