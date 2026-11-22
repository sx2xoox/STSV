package dev.stsv.economy.secret.jesa;

import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The table as a window (주인 없는 제사): six rows — the folding screen with its two tablets on top (the north), then the
 * table's five rows of nine places. It is only a way of saying what goes where: <b>no real item ever enters or leaves
 * this window</b>. Every click in it is cancelled; pressing a dish in one's own bag "picks it up" (remembers its id, shown
 * in the corner of the top row), pressing a place lays a picture of it there, pressing a laid picture picks it up again,
 * right-click takes it off. The food stays in the bag the whole time, so closing the window, leaving, dying, a kick or a
 * reload can neither lose nor copy anything; the pictures carry a mark and are swept from the bag should one ever get there.
 * What is laid is kept by {@link Jesa} (place -> item id) and only taken from the bag when the rite is accepted.
 */
final class Setting implements InventoryHolder {

    /** On every picture in the window (never a real thing). */
    static final NamespacedKey GHOST = new NamespacedKey("economy", "jesa_ghost");
    static final int SIZE = 54, FIRST = 9, FATHER = 3, MOTHER = 5, HAND = 8;

    private final Jesa jesa;
    final UUID owner;
    final Map<Integer, String> layout = new TreeMap<>();
    private Inventory inv;
    /** the id picked up (a dish of the bag, or one lifted off the table), or null */
    private String held;
    boolean changed;

    Setting(Jesa jesa, Player p, Map<Integer, String> layout) {
        this.jesa = jesa;
        this.owner = p.getUniqueId();
        this.layout.putAll(layout);
        this.inv = Bukkit.createInventory(this, SIZE, Text.mm(jesa.text("gui.title")));
        for (int i = 0; i < FIRST; i++) inv.setItem(i, decor(Material.WHITE_STAINED_GLASS_PANE, jesa.text("gui.screen"), List.of()));
        inv.setItem(FATHER, decor(Material.PAPER, "<white>" + jesa.text("tablets.father"), List.of()));
        inv.setItem(MOTHER, decor(Material.PAPER, "<white>" + jesa.text("tablets.mother"), List.of()));
        for (Map.Entry<Integer, String> e : this.layout.entrySet()) inv.setItem(FIRST + e.getKey(), ghost(e.getValue(), false));
        drawHand();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    static boolean isGhost(ItemStack it) {
        return it != null && !it.isEmpty() && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(GHOST);
    }

    private static ItemStack mark(ItemStack it) {
        it.editMeta(meta -> meta.getPersistentDataContainer().set(GHOST, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    private static ItemStack decor(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        it.editMeta(meta -> {
            meta.itemName(Text.item(name));
            if (!lore.isEmpty()) meta.lore(Text.itemLines(lore));
        });
        return mark(it);
    }

    /** A picture of a dish: one of it, marked, saying it is only a place kept. */
    private ItemStack ghost(String key, boolean inHand) {
        ItemStack it = Items.create(key, 1).orElseGet(() -> new ItemStack(Material.PAPER));
        it.setAmount(1);
        List<Component> lore = new ArrayList<>(Text.itemLines(jesa.cfg().getStringList(inHand ? "gui.held-lore" : "gui.laid-lore")));
        it.editMeta(meta -> meta.lore(lore));
        return mark(it);
    }

    private void drawHand() {
        if (held == null) inv.setItem(HAND, decor(Material.STICK, jesa.text("gui.hand"), jesa.cfg().getStringList("gui.hand-lore")));
        else inv.setItem(HAND, ghost(held, true));
    }

    private int laid(String key) {
        int n = 0;
        for (String k : layout.values()) if (k.equals(key)) n++;
        return n;
    }

    /** A click anywhere while this window is open (already cancelled by the caller). */
    void click(Player p, InventoryClickEvent e) {
        if (e.getClickedInventory() == null) return;
        int raw = e.getRawSlot();
        if (e.getClickedInventory() != inv) {   // their own bag: pick a dish up (it does not move)
            ItemStack it = e.getCurrentItem();
            if (it == null || it.isEmpty() || isGhost(it)) return;
            if (Items.trial(it)) {
                deny(p, "lines.not-this");
                return;
            }
            held = Items.key(it);
            drawHand();
            p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.4f, 1.2f);
            return;
        }
        if (raw == HAND) {
            if (held != null) {
                held = null;
                drawHand();
            }
            return;
        }
        if (raw < FIRST || raw >= SIZE) return;
        int slot = raw - FIRST;
        String there = layout.get(slot);
        if (there != null && (e.isRightClick() || e.isShiftClick())) {   // off the table
            layout.remove(slot);
            inv.setItem(raw, null);
            touched(p, Sound.BLOCK_WOOD_HIT, 1.4f);
            return;
        }
        if (held == null) {
            if (there == null) return;
            layout.remove(slot);   // lifted, to be laid somewhere else
            inv.setItem(raw, null);
            held = there;
            drawHand();
            touched(p, Sound.ITEM_BUNDLE_REMOVE_ONE, 1.2f);
            return;
        }
        // lay what is held: only as many as the bag holds
        int others = laid(held) - (held.equals(there) ? 1 : 0);
        if (jesa.carried(p, held) <= others) {
            deny(p, "lines.no-more");
            held = null;
            drawHand();
            return;
        }
        layout.put(slot, held);
        inv.setItem(raw, ghost(held, false));
        held = null;
        drawHand();
        touched(p, Sound.BLOCK_WOOD_PLACE, 1.5f);
    }

    private void deny(Player p, String line) {
        jesa.whisper(p, line);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.6f);
    }

    private void touched(Player p, Sound sound, float pitch) {
        changed = true;
        p.playSound(p.getLocation(), sound, 0.35f, pitch);
        jesa.laid(p, this);
    }

    /** The window is closing: its pictures go, and any that strayed into the bag with them. */
    void closed(Player p) {
        inv.clear();
        sweep(p);
    }

    /** Takes pictures out of a bag (there should never be one). */
    static void sweep(Player p) {
        ItemStack[] all = p.getInventory().getContents();
        for (int i = 0; i < all.length; i++) if (isGhost(all[i])) p.getInventory().setItem(i, null);
        if (isGhost(p.getItemOnCursor())) p.setItemOnCursor(null);
    }
}
